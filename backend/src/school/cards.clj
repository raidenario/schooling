(ns school.cards
  "Mineração de flashcards (Fase 2a) por duas vias:

   1. PROVAS corrigidas — CÓDIGO puro, sem LLM. Questão errada ou 🤷 'não sei'
      vira card: frente = enunciado (+contexto), verso = alternativa correta +
      explicação, evidência = prova+qN.
   2. Itens `fraco` do DIAGNOSIS.md — gate e dedup DETERMINÍSTICOS (parse do
      Mapa de competências + slug `diag-<competencia>` na Agenda); só o
      CONTEÚDO frente/verso sai do LLM via create-edn! (o 'depois' previsto
      no ADR-0009), porque competência é semântica, não dado estruturado.

   Divisão ADR-0002: o CONTEÚDO do card vive no vault
   (school/<subject>/cards/NNNN-<slug>.md, legível/editável no Obsidian);
   o estado de agendamento vive na Agenda SQLite (school.agenda).
   Dedup por slug estável — a mesma lacuna re-testada em provas diferentes
   (ou re-diagnosticada) NÃO duplica card (a revisão dela já mora na Agenda)."
  (:require [clojure.string :as str]
            [embabel-clj.schema :as schema]
            [school.agenda :as agenda]
            [school.events :as events]
            [school.vault :as vault])
  (:import [java.time Instant]))

(defn- card-slug
  "Slug estável do card: prefixo do enunciado + hash curto (dedup entre
   provas sem depender de numeração)."
  ^String [^String enunciado]
  (let [base (vault/slugify enunciado)
        base (subs base 0 (min 40 (count base)))
        h    (format "%05x" (bit-and (hash enunciado) 0xfffff))]
    (str base "-" h)))

(defn- card-md ^String [{:keys [subject prova-id]} q item]
  (let [correta-txt (some #(when (= (:correta item) (:letra %)) (:texto %))
                          (:alternativas q))]
    (str "---\nsubject: " subject
         "\nevidencia: " prova-id " " (:id item)
         "\nminerado: " (subs (str (:ts item)) 0 10)
         "\n---\n\n"
         "## Frente\n\n" (:enunciado item) "\n"
         (when-not (str/blank? (str (:contexto q)))
           (str "\n```\n" (:contexto q) "\n```\n"))
         "\n## Verso\n\n**" (str/upper-case (str (:correta item))) ") "
         correta-txt "**\n\n" (:explicacao item) "\n")))

(defn- next-card-n [subject]
  (let [d (vault/subject-file subject "cards")]
    (inc (count (or (.listFiles d) [])))))

(defn minerar!
  "Minera cards das questões erradas/não-sei de uma prova corrigida.
   `prova` traz as questões completas (alternativas); `graded` é o resultado
   de prova/grade. Devolve o nº de cards NOVOS (dedup por slug)."
  [{:keys [subject prova-id prova graded ^Instant now]}]
  (let [slug-subj (vault/slugify subject)
        now       (or now (Instant/now))
        por-id    (into {} (map (juxt :id identity)) (:questoes prova))
        novos
        (reduce
         (fn [n {:keys [acertou? enunciado] :as item}]
           (if acertou?
             n
             (let [cslug  (card-slug enunciado)
                   antes  (agenda/card-count slug-subj)
                   path   ["cards" (format "%04d-%s.md" (next-card-n slug-subj) cslug)]
                   ;; upsert primeiro: se o card já existe, NÃO reescreve arquivo
                   id     (agenda/upsert-card! {:subject slug-subj :slug cslug
                                                :path (str/join "/" path) :now now})]
               (if (> (agenda/card-count slug-subj) antes)
                 (do (vault/write-file! subject path
                                        (card-md {:subject slug-subj :prova-id prova-id}
                                                 (get por-id (:id item))
                                                 (assoc item :ts now)))
                     (inc n))
                 n))))
         0
         (:itens graded))]
    (when (pos? novos)
      (events/emit! :cards-minerados {:subject slug-subj :prova prova-id :n novos}))
    novos))

;; ---------------------------------------------------------------------------
;; itens `fraco` do diagnóstico
;; ---------------------------------------------------------------------------

(defn parse-fracos
  "Linhas nível `fraco` do Mapa de competências do DIAGNOSIS.md:
   [{:competencia :evidencia}]. Tolerante a negrito e caixa."
  [^String diagnosis]
  (->> (str/split-lines (str diagnosis))
       (keep (fn [l]
               (when-let [[_ comp niv evid]
                          (re-find #"^\|([^|]+)\|([^|]+)\|([^|]+)\|?\s*$" l)]
                 (let [limpa #(str/trim (str/replace % #"\*+" ""))]
                   (when (= "fraco" (str/lower-case (limpa niv)))
                     {:competencia (limpa comp) :evidencia (limpa evid)})))))
       vec))

(defn- fraco-slug ^String [competencia]
  (let [base (vault/slugify competencia)]
    (str "diag-" (subs base 0 (min 36 (count base))))))

(defn fracos-novos
  "Itens fraco do diagnóstico que ainda NÃO têm card na Agenda."
  [subject diagnosis]
  (let [slug-subj (vault/slugify subject)]
    (into []
          (remove #(agenda/card-exists? slug-subj (fraco-slug (:competencia %))))
          (parse-fracos diagnosis))))

(def FracoCardsSchema
  [:map
   [:cards
    [:vector
     [:map
      [:competencia {:description "ecoa a competência EXATAMENTE como recebida"} :string]
      [:frente {:description "pergunta de recall ativo que testa a competência (pt-BR; pode incluir trecho de código entre cercas ```)"} :string]
      [:verso {:description "resposta correta + explicação curta e didática (markdown simples)"} :string]]]]])

(defn- fraco-card-md ^String [{:keys [subject evidencia frente verso ^Instant ts]}]
  (str "---\nsubject: " subject
       "\nevidencia: diagnóstico — " evidencia
       "\nminerado: " (subs (str ts) 0 10)
       "\n---\n\n## Frente\n\n" (str/trim frente)
       "\n\n## Verso\n\n" (str/trim verso) "\n"))

(defn gravar-fracos!
  "Persiste cards gerados para itens fraco (casados por slugify da
   competência; gerados sem par são descartados). Devolve o nº de NOVOS."
  [{:keys [subject fracos gerados ^Instant now]}]
  (let [slug-subj (vault/slugify subject)
        now       (or now (Instant/now))
        por-slug  (into {} (map (juxt #(fraco-slug (:competencia %)) identity)) gerados)
        novos
        (reduce
         (fn [n {:keys [competencia evidencia]}]
           (let [cslug (fraco-slug competencia)]
             (if-let [g (get por-slug cslug)]
               (let [antes (agenda/card-count slug-subj)
                     path  ["cards" (format "%04d-%s.md" (next-card-n slug-subj) cslug)]
                     _     (agenda/upsert-card! {:subject slug-subj :slug cslug
                                                 :path (str/join "/" path) :now now})]
                 (if (> (agenda/card-count slug-subj) antes)
                   (do (vault/write-file! subject path
                                          (fraco-card-md {:subject slug-subj
                                                          :evidencia evidencia
                                                          :frente (:frente g)
                                                          :verso (:verso g)
                                                          :ts now}))
                       (inc n))
                   n))
               n)))
         0
         fracos)]
    (when (pos? novos)
      (events/emit! :cards-minerados {:subject slug-subj :prova "diagnostico" :n novos}))
    novos))

(defn minerar-fracos!
  "Minera cards dos itens `fraco` do diagnóstico: gate/dedup em código,
   conteúdo via create-edn! (uma chamada para todos os itens novos).
   Devolve o nº de cards novos; 0 se não há item novo."
  [ctx {:keys [llm subject diagnosis ^Instant now]}]
  (let [fracos (fracos-novos subject diagnosis)]
    (if (empty? fracos)
      0
      (let [gerados
            (:cards (schema/create-edn! ctx
                      {:schema FracoCardsSchema :llm llm :max-tokens 4096
                       :timeout-s 300 :retries 2
                       :prompt (schema/edn-prompt FracoCardsSchema
                                {:preamble (str "Você elabora flashcards do School para a matéria \""
                                                subject "\". Gere UM card de recall ativo por "
                                                "competência fraca abaixo (eco exato em :competencia). "
                                                "Frente = pergunta que força lembrar, não reconhecer; "
                                                "verso = resposta + explicação curta. Conteúdo em pt-BR."
                                                "\n\nCOMPETÊNCIAS FRACAS:\n"
                                                (str/join "\n" (map #(str "- " (:competencia %)
                                                                          " (evidência: " (:evidencia %) ")")
                                                                    fracos)))})}))]
        (gravar-fracos! {:subject subject :fracos fracos
                         :gerados gerados :now now})))))
