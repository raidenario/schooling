(ns school.research
  "Pesquisa web ORQUESTRADA (ADR-0012) — o professor não decide chamar uma
   tool (tool-call solto no stream vaza como texto no free tier, School.md):
   o CÓDIGO conduz o fluxo, no mesmo espírito determinístico de todo o harness.

     queries (LLM, sem reasoning) → Tavily (HTTP) → fontes aterram a Aula
     e acumulam no RESOURCES.md do vault (dedup por URL).

   Sem chave (`SCHOOL_SEARCH_APIKEY`, Tavily) => no-op gracioso: pesquisa
   NUNCA derruba uma aula (mesma filosofia da mineração/memória). O RESOURCES.md
   é escrito de forma DETERMINÍSTICA a partir do que o Tavily extrai — não há
   síntese-LLM na v1 (o conteúdo limpo do Tavily já serve pra citar); enriquecer
   com uma passada de síntese fica pro futuro (provider pago)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [embabel-clj.schema :as schema]
            [org.httpkit.client :as http]
            [school.events :as events]
            [school.vault :as vault])
  (:import [java.time LocalDate]))

(def ^:private api-key (delay (System/getenv "SCHOOL_SEARCH_APIKEY")))
(def ^:private endpoint
  (or (System/getenv "SCHOOL_SEARCH_URL") "https://api.tavily.com/search"))
(def model (or (System/getenv "SCHOOL_MODEL") "z-ai/glm-5.2"))

(defn habilitada?
  "Pesquisa só roda com chave (produção) ou com buscar-fn injetado (teste)."
  ([] (boolean (seq (str @api-key))))
  ([buscar-fn] (boolean (or buscar-fn (seq (str @api-key))))))

(defn- trunc ^String [s n]
  (let [s (str/replace (str s) #"\s+" " ")]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- distinct-by [f coll]
  (let [seen (volatile! #{})]
    (filter (fn [x] (let [k (f x)]
                      (when-not (contains? @seen k) (vswap! seen conj k) true)))
            coll)))

;; ---------------------------------------------------------------------------
;; Tavily (HTTP, bloqueante)
;; ---------------------------------------------------------------------------

(defn buscar!
  "Uma busca no Tavily. Devolve {:answer <resumo> :fontes [{:title :url
   :content :score}]}. Lança se a chamada falhar (o chamador degrada com graça)."
  [query {:keys [max-results depth timeout-ms]
          :or   {max-results 5 depth "basic" timeout-ms 20000}}]
  (let [k @api-key
        _ (when-not (seq (str k))
            (throw (ex-info "research: falta SCHOOL_SEARCH_APIKEY (Tavily)" {})))
        resp @(http/post endpoint
                {:headers {"Content-Type"  "application/json"
                           "Authorization" (str "Bearer " k)}
                 :timeout timeout-ms
                 :body (json/generate-string
                        {:query query :search_depth depth
                         :max_results max-results :include_answer true})})]
    (when (:error resp) (throw (:error resp)))
    (when (not= 200 (:status resp))
      (throw (ex-info (str "tavily HTTP " (:status resp)) {:body (:body resp)})))
    (let [b (json/parse-string (:body resp) true)]
      {:answer (:answer b)
       :fontes (mapv #(select-keys % [:title :url :content :score]) (:results b))})))

;; ---------------------------------------------------------------------------
;; queries (LLM, SEM reasoning — extração mecânica; ADR-0011)
;; ---------------------------------------------------------------------------

(def ^:private QueriesSchema
  [:map [:queries {:description "2 a 3 buscas web focadas e complementares (strings), na língua da matéria, que juntas cubram o tópico em profundidade"}
         [:vector :string]]])

(defn- gerar-queries [ctx subject topico contexto]
  (->> (schema/create-edn! ctx
         {:schema QueriesSchema :llm model :max-tokens 1024
          :timeout-s 120 :retries 1
          :prompt (schema/edn-prompt QueriesSchema
                   {:preamble (str "Gere buscas web para APROFUNDAR o ensino de \""
                                   topico "\" (matéria: " subject "). Contexto:\n"
                                   contexto)
                    :extra "Buscas curtas e específicas — nada genérico demais."})})
       :queries
       (map str/trim)
       (remove str/blank?)
       (take 3)
       vec))

;; ---------------------------------------------------------------------------
;; RESOURCES.md (determinístico, acumulativo, dedup por URL)
;; ---------------------------------------------------------------------------

(defn- append-resources!
  "Acrescenta uma seção datada ao RESOURCES.md da matéria; só lista URLs
   AINDA não catalogadas. Devolve o nº de fontes novas."
  [subject topico resumo fontes]
  (let [existente (or (apply vault/read-file subject vault/resources-path) "")
        ja-urls   (set (re-seq #"https?://[^\s)]+" existente))
        novas     (remove #(contains? ja-urls (:url %)) fontes)
        cabecalho (when (str/blank? existente)
                    (str "---\nsubject: " (vault/slugify subject)
                         "\ntipo: resources\n---\n\n# Recursos — " subject "\n"))
        secao (str "\n## " topico " · " (LocalDate/now) "\n"
                   (when-not (str/blank? (str resumo))
                     (str "\n> " (trunc resumo 600) "\n"))
                   "\n"
                   (if (seq novas)
                     (str/join "\n"
                       (map (fn [{:keys [title url content]}]
                              (str "- [" (or (not-empty (str title)) url) "](" url ") — "
                                   (trunc content 160)))
                            novas))
                     "_(fontes já catalogadas acima)_")
                   "\n")]
    (vault/write-file! subject vault/resources-path
                       (str cabecalho existente secao))
    (count novas)))

;; ---------------------------------------------------------------------------
;; orquestração
;; ---------------------------------------------------------------------------

(defn fontes->prompt
  "Fontes formatadas para entrar no contexto de geração da Aula."
  ^String [fontes]
  (str/join "\n\n"
    (map (fn [{:keys [title url content]}]
           (str "### " (or (not-empty (str title)) url) "\n" url "\n"
                (trunc content 500)))
         fontes)))

(defn pesquisar!
  "Fluxo completo (ADR-0012): queries → Tavily → RESOURCES.md. Devolve
   {:fontes [...] :resumo <answer> :n-novas <int>} ou nil (desabilitada).
   `:queries`/`:buscar-fn` injetáveis para teste offline (sem LLM, sem HTTP)."
  [ctx {:keys [subject topico contexto max-fontes queries buscar-fn]
        :or   {max-fontes 6}}]
  (when (habilitada? buscar-fn)
    (let [qs (or (seq queries) (gerar-queries ctx subject topico contexto))
          bf (or buscar-fn #(buscar! % {:max-results max-fontes}))
          hits (keep (fn [q] (try (assoc (bf q) :query q)
                                  (catch Exception e
                                    (binding [*out* *err*]
                                      (println "research: busca falhou:" (.getMessage e)))
                                    nil)))
                     qs)
          fontes (->> hits (mapcat :fontes)
                      (filter :url) (distinct-by :url) (take max-fontes) vec)
          resumo (some (comp not-empty str :answer) hits)
          ;; n-novas = fontes AINDA não catalogadas no RESOURCES.md (o arquivo
          ;; acumula; :fontes devolvidas incluem as já vistas, pra aterrar a aula)
          novas  (if (seq fontes) (append-resources! subject topico resumo fontes) 0)]
      (when (seq fontes)
        (events/emit! :pesquisa-feita {:subject (vault/slugify subject)
                                       :topico topico :fontes (count fontes) :novas novas}))
      {:fontes fontes :resumo resumo :n-novas novas})))
