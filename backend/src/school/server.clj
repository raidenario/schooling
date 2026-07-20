(ns school.server
  "O servidor da Fase 1: `clojure -M:server` e o TUI abre uma matéria.

   Um turno = um processo GOAP do professor (goal :respondido?); o planner
   escolhe a ação pelo estágio do vault. Exige NVIDIA_APIKEY."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [embabel-clj.core :as ec]
            [embabel-clj.platform :as platform]
            [school.events :as events]
            [school.professor :as professor]
            [school.vault :as vault]
            [school.ws :as ws])
  (:gen-class))

(defonce sys* (atom nil))
(defonce session (atom nil)) ; {:subject :ch} — um aprendiz, uma matéria por vez

(defn- anunciar-materia! [ch subject]
  (let [r (vault/resumo subject)]
    (ws/send! ch {:type "info"
                  :text (str "matéria: " subject
                             " · estágio: " (name (:stage r))
                             " · vault: " (:dir r))})))

(defn- on-start
  "start com matéria abre a matéria; sem matéria (fluxo normal do TUI) só
   apresenta o que existe — a matéria nasce da conversa."
  [ch subject]
  (if (str/blank? (str subject))
    (do (reset! session {:subject nil :ch ch})
        (let [existentes (vault/list-subjects)]
          (ws/send! ch {:type "info"
                        :text (if (seq existentes)
                                (str "matérias: " (str/join ", " existentes)
                                     " — continue uma ou diga o que quer aprender")
                                "diga o que você quer aprender")})))
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

(defn- http-routes [req]
  (or (aula-routes req) (prova-routes req)))

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
