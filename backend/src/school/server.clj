(ns school.server
  "O servidor da Fase 1: `clojure -M:server` e o TUI abre uma matéria.

   Um turno = um processo GOAP do professor (goal :respondido?); o planner
   escolhe a ação pelo estágio do vault. Exige NVIDIA_APIKEY."
  (:require [embabel-clj.core :as ec]
            [embabel-clj.platform :as platform]
            [school.professor :as professor]
            [school.vault :as vault]
            [school.ws :as ws])
  (:gen-class))

(defonce sys* (atom nil))
(defonce session (atom nil)) ; {:subject "..."} — um aprendiz, uma matéria por vez

(defn- on-start [ch subject]
  (reset! session {:subject subject})
  (let [r (vault/resumo subject)]
    (ws/send! ch {:type "info"
                  :text (str "matéria: " subject
                             " · estágio: " (name (:stage r))
                             " · vault: " (:dir r))})))

(defn- turno [emit! ch text]
  (let [{:keys [subject]} @session]
    (when-not subject
      (throw (ex-info "nenhuma matéria ativa — abra o TUI com uma matéria" {})))
    (let [{:keys [sys ag]} @sys*
          proc (ec/run! (:platform sys) ag
                        {:bindings {:subject subject :message text :emit! emit!}})
          r    (ec/result proc {:slots [:interrupted? :stage]
                                :conditions [:respondido?]})]
      (when (not= "COMPLETED" (:status r))
        (throw (ex-info (str "processo terminou " (:status r)) r)))
      (ws/send! ch {:type "info" :text (str "estágio: " (get-in r [:slots :stage]))})
      (when (get-in r [:slots :interrupted?])
        (throw (InterruptedException. "aula interrompida pelo aprendiz"))))))

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
