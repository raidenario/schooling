(ns school.server
  "O servidor da Fase 1: `clojure -M:server` e o TUI abre uma matéria.

   Um turno = um processo GOAP do professor (goal :respondido?); o planner
   escolhe a ação pelo estágio do vault. Exige NVIDIA_APIKEY."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [embabel-clj.core :as ec]
            [embabel-clj.platform :as platform]
            [school.agenda :as agenda]
            [school.events :as events]
            [school.memoria :as memoria]
            [school.professor :as professor]
            [school.review :as review]
            [school.vault :as vault]
            [school.ws :as ws])
  (:gen-class))

(defonce sys* (atom nil))
(defonce session (atom nil)) ; {:subject :ch} — um aprendiz, uma matéria por vez

(defn- modo-de
  "O modo FINO do professor para o TUI colorir — mais granular que o estágio
   GOAP (calibragem e consulta compartilham o :prova-fria por baixo):
     calibragem — missão feita, prova fria ainda não gerada
     consulta   — uma prova (fria ou de módulo) está aberta sem correção
     normal     — todo o resto"
  ^String [subject]
  (if (str/blank? (str subject))
    "normal"
    (let [r (vault/resumo subject)]
      (cond
        (and (= :prova-fria (:stage r)) (:prova-gerada? r)
             (not (apply vault/exists? subject vault/prova-fria-respostas-path)))
        "consulta"

        (and (:modulo r) (:modulo-prova-gerada? r)
             (not (:modulo-prova-corrigida? r)))
        "consulta"

        (= :prova-fria (:stage r))
        "calibragem"

        :else "normal"))))

(defn- enviar-modo! [ch subject]
  (ws/send! ch {:type "modo" :modo (modo-de subject)}))

(defn- anunciar-materia! [ch subject]
  (let [r (vault/resumo subject)]
    (ws/send! ch {:type "info"
                  :text (str "matéria: " subject
                             " · estágio: " (name (:stage r))
                             " · vault: " (:dir r))})
    (enviar-modo! ch subject)))

(defn- on-start
  "start com matéria abre a matéria; sem matéria (fluxo normal do TUI) só
   apresenta o que existe — a matéria nasce da conversa."
  [ch subject]
  (if (str/blank? (str subject))
    (do (reset! session {:subject nil :ch ch})
        (let [existentes (vault/list-subjects)
              due        (:due (agenda/stats-all (java.time.Instant/now)))]
          (ws/send! ch {:type "info"
                        :text (str (if (seq existentes)
                                     (str "matérias: " (str/join ", " existentes)
                                          " — continue uma ou diga o que quer aprender")
                                     "diga o que você quer aprender")
                                   (when (pos? due)
                                     (str " · 🃏 " due " cards para revisar: "
                                          professor/base-url "/review")))})))
    (do (reset! session {:subject subject :ch ch})
        (anunciar-materia! ch subject))))

(defn- turno
  ([emit! ch text] (turno emit! ch text 0))
  ([emit! ch text tentativa]
   (let [{:keys [subject]} @session
         {:keys [sys ag]} @sys*
         proc (ec/run! (:platform sys) ag
                       {:bindings {:subject (or subject "") :message text :emit! emit!}})
         r    (ec/result proc {:slots [:interrupted? :stage :materia-escolhida]
                               :conditions [:respondido?]})]
     (when (not= "COMPLETED" (:status r))
       (throw (ex-info (str "processo terminou " (:status r)) r)))
     (if-let [nova (and (zero? tentativa) (get-in r [:slots :materia-escolhida]))]
       ;; a matéria nasceu desta mensagem: abre e re-roda o MESMO turno já
       ;; no estágio de missão — o aprendiz não repete nada
       (do (reset! session {:subject nova :ch ch})
           (anunciar-materia! ch nova)
           (turno emit! ch text 1))
       (do (ws/send! ch {:type "info" :text (str "estágio: " (get-in r [:slots :stage]))})
           (enviar-modo! ch (:subject @session))
           (when (get-in r [:slots :interrupted?])
             (throw (InterruptedException. "aula interrompida pelo aprendiz"))))))))

;; ---------------------------------------------------------------------------
;; provas interativas por HTTP (ADR-0007)
;; ---------------------------------------------------------------------------

(defn- html-resp [^String html]
  (when html
    {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body html}))

(defn- prova-paths [tipo dir]
  (case tipo
    :fria   {:html vault/prova-fria-path :respostas vault/prova-fria-respostas-path
             :gabarito vault/gabarito-fria-path :nome "prova-fria"}
    :modulo {:html ["modules" dir "prova.html"] :respostas ["modules" dir "prova-respostas.edn"]
             :gabarito ["modules" dir "gabarito.html"] :nome dir}))

(defn- receber-respostas! [slug {:keys [respostas nome]} body]
  (let [answers (->> (get (json/parse-string body) "answers")
                     (mapv (fn [{:strs [id alternativa justificativa]}]
                             {:id id :alternativa alternativa
                              :justificativa (str justificativa)})))]
    (if (empty? answers)
      {:status 400 :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "sem respostas no corpo"}
      (do (vault/write-edn! slug respostas answers)
          (events/emit! :prova-respondida {:subject slug :prova nome :n (count answers)})
          ;; correção automática no chat da sessão ativa
          (let [{:keys [subject ch]} @session]
            (when (and ch subject (= (vault/slugify subject) slug))
              (ws/send! ch {:type "info" :text "respostas recebidas — corrigindo…"})
              (ws/start-turn! ch
                (fn [emit!]
                  (turno emit! ch "[O aprendiz clicou em CONCLUIR e enviou as respostas da prova.]")))))
          {:status 200 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "ok"}))))

;; -- aulas-documento por HTTP (ADR-0008) --------------------------------------

(defn- receber-entendimento! [slug body]
  (let [texto  (str/trim (str (get (json/parse-string body) "texto")))
        aberta (vault/read-edn slug vault/aula-aberta-path)]
    (cond
      (nil? aberta)
      {:status 404 :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "nenhuma aula aberta para esta matéria"}

      (str/blank? texto)
      {:status 400 :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "texto vazio"}

      :else
      (do (vault/write-edn! slug vault/aula-aberta-path (assoc aberta :entendimento texto))
          (events/emit! :entendimento-recebido {:subject slug :topico (:topico aberta)})
          ;; avaliação automática no chat da sessão ativa
          (let [{:keys [subject ch]} @session]
            (when (and ch subject (= (vault/slugify subject) slug))
              (ws/send! ch {:type "info" :text "entendimento recebido — avaliando…"})
              (ws/start-turn! ch
                (fn [emit!]
                  (turno emit! ch "[O aprendiz leu a aula e enviou o que entendeu pela página.]")))))
          {:status 200 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "ok"}))))

(defn- aula-routes [req]
  (let [uri (str (:uri req)) method (:request-method req)]
    (cond
      (= method :get)
      (when-let [[_ slug] (re-matches #"/aula/([^/]+)" uri)]
        (html-resp (when-let [aberta (vault/read-edn slug vault/aula-aberta-path)]
                     (apply vault/read-file slug (:html-segs aberta)))))

      (= method :post)
      (when-let [[_ slug] (re-matches #"/entendimento/([^/]+)" uri)]
        (receber-entendimento! slug (slurp (:body req)))))))

(defn- prova-routes [req]
  (let [uri    (:uri req)
        method (:request-method req)
        [_ tipo-rota slug resto] (re-matches #"/(prova|gabarito|respostas)/([^/]+)(?:/(.+))?" (str uri))
        [tipo dir] (cond
                     (= resto "fria") [:fria nil]
                     (and resto (str/starts-with? resto "modulo/")) [:modulo (subs resto 7)]
                     :else [nil nil])]
    (when (and tipo-rota slug tipo)
      (let [paths (prova-paths tipo dir)]
        (case [(keyword tipo-rota) method]
          [:prova :get]      (html-resp (apply vault/read-file slug (:html paths)))
          [:gabarito :get]   (html-resp (apply vault/read-file slug (:gabarito paths)))
          [:respostas :post] (receber-respostas! slug paths (slurp (:body req)))
          nil)))))

;; -- revisão FSRS por HTTP (Fase 2a) ------------------------------------------

(def ^:private review-limite-dia 20)

(defn- registrar-review!
  "`slug` nil = sessão global: a matéria do evento sai da identidade do card."
  [slug body]
  (let [{:strs [card rating]} (json/parse-string body)]
    (if-not (and (number? card) (#{1 2 3 4} rating))
      {:status 400 :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "esperava {card: id, rating: 1-4}"}
      (let [s'   (agenda/review! (long card) (long rating) (java.time.Instant/now))
            subj (or slug (:subject (agenda/card-info (long card))))]
        (events/emit! :card-revisado {:subject subj :card (long card) :rating rating})
        {:status 200 :headers {"Content-Type" "application/json; charset=utf-8"}
         :body (json/generate-string {:ok true :proxima (str (:due s'))
                                      :intervalo-dias (:interval-days s')})}))))

(defn- review-routes [req]
  (let [uri (str (:uri req)) method (:request-method req)]
    (cond
      ;; fila GLOBAL interleaved entre matérias (Fase 2a)
      (= uri "/review")
      (case method
        :get  (let [now   (java.time.Instant/now)
                    due   (agenda/due-cards-all now review-limite-dia)
                    cards (mapv #(assoc % :mat (:subject %))
                                (review/carrega-cards due))]
                (html-resp (review/render-html {:subject "todas as matérias"
                                                :cards cards
                                                :stats (agenda/stats-all now)}
                                               "/review")))
        :post (registrar-review! nil (slurp (:body req)))
        nil)

      :else
      (when-let [[_ slug] (re-matches #"/review/([^/]+)" uri)]
        (case method
          :get  (let [now   (java.time.Instant/now)
                      due   (agenda/due-cards slug now review-limite-dia)
                      cards (review/carrega-cards due)]
                  (html-resp (review/render-html {:subject slug :cards cards
                                                  :stats (agenda/stats slug now)}
                                                 (str "/review/" slug))))
          :post (registrar-review! slug (slurp (:body req)))
          nil)))))

(defn- memoria-routes [req]
  (when (= :get (:request-method req))
    (when-let [[_ slug] (re-matches #"/memoria/([^/]+)" (str (:uri req)))]
      (html-resp (memoria/render-html slug)))))

(defn- http-routes [req]
  (or (review-routes req) (memoria-routes req) (aula-routes req) (prova-routes req)))

(defn -main [& _]
  (let [base-url (or (System/getenv "SCHOOL_BASE_URL") "https://integrate.api.nvidia.com")
        api-key  (or (System/getenv "SCHOOL_APIKEY") (System/getenv "NVIDIA_APIKEY"))]
    (when-not api-key
      (throw (ex-info "falta a chave: exporte NVIDIA_APIKEY (ou SCHOOL_APIKEY)" {})))
    (let [log-level (or (System/getenv "SCHOOL_LOG_LEVEL") "warn")
          sys (platform/start!
               {:properties
                {:embabel.agent.platform.models.openai.base-url base-url
                 :embabel.agent.platform.models.openai.api-key  api-key
                 :embabel.models.default-llm professor/model
                 :embabel.agent.platform.llm-operations.data-binding.fixedBackoffMillis "6000"
                 :logging.level.root "warn"
                 :logging.level.Embabel log-level}})
          ag  (ec/deploy! (:platform sys) (professor/professor))]
      (memoria/start!)  ; replay do chronicle no boot (não no 1º turno)
      (reset! sys* {:sys sys :ag ag})
      (ws/start! {:port         (some-> (System/getenv "SCHOOL_PORT") Integer/parseInt)
                  :on-start     on-start
                  :http-handler http-routes
                  :on-user-msg  (fn [ch text]
                                  (swap! session assoc :ch ch)
                                  (ws/start-turn! ch (fn [emit!] (turno emit! ch text))))})
      (println (str "School pronto (modelo " professor/model
                    " · vault " vault/root ")"))
      @(promise))))
