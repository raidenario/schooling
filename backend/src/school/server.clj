(ns school.server
  "O servidor da Fase 1: `clojure -M:server` e o TUI abre uma matéria.

   Um turno = um processo GOAP do professor (goal :respondido?); o planner
   escolhe a ação pelo estágio do vault. Exige NVIDIA_APIKEY."
  (:require [clojure.string :as str]
            [embabel-clj.core :as ec]
            [embabel-clj.platform :as platform]
            [school.professor :as professor]
            [school.vault :as vault]
            [school.ws :as ws])
  (:gen-class))

(defonce sys* (atom nil))
(defonce session (atom nil)) ; {:subject "..."} — um aprendiz, uma matéria por vez

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
    (do (reset! session {:subject nil})
        (let [existentes (vault/list-subjects)]
          (ws/send! ch {:type "info"
                        :text (if (seq existentes)
                                (str "matérias: " (str/join ", " existentes)
                                     " — continue uma ou diga o que quer aprender")
                                "diga o que você quer aprender")})))
    (do (reset! session {:subject subject})
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
       (do (reset! session {:subject nova})
           (anunciar-materia! ch nova)
           (turno emit! ch text 1))
       (do (ws/send! ch {:type "info" :text (str "estágio: " (get-in r [:slots :stage]))})
           (when (get-in r [:slots :interrupted?])
             (throw (InterruptedException. "aula interrompida pelo aprendiz"))))))))

(defn -main [& _]
  (let [base-url (or (System/getenv "SCHOOL_BASE_URL") "https://integrate.api.nvidia.com")
        api-key  (or (System/getenv "SCHOOL_APIKEY") (System/getenv "NVIDIA_APIKEY"))]
    (when-not api-key
      (throw (ex-info "falta a chave: exporte NVIDIA_APIKEY (ou SCHOOL_APIKEY)" {})))
    (let [sys (platform/start!
               {:properties
                {:embabel.agent.platform.models.openai.base-url base-url
                 :embabel.agent.platform.models.openai.api-key  api-key
                 :embabel.models.default-llm professor/model
                 :embabel.agent.platform.llm-operations.data-binding.fixedBackoffMillis "6000"
                 :logging.level.root "warn"
                 :logging.level.Embabel "warn"}})
          ag  (ec/deploy! (:platform sys) (professor/professor))]
      (reset! sys* {:sys sys :ag ag})
      (ws/start! {:on-start    on-start
                  :on-user-msg (fn [ch text]
                                 (ws/start-turn! ch (fn [emit!] (turno emit! ch text))))})
      (println (str "School pronto (modelo " professor/model
                    " · vault " vault/root ")"))
      @(promise))))
