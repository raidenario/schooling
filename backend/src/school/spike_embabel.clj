(ns school.spike-embabel
  "Etapa B do spike-gate (ADR-0003/0006): a aula mínima streamada VIA EMBABEL.

   Critérios binários do gate:
   1. streaming token-a-token — StreamingPromptRunner.generateStream() → Flux<String> → WS
   2. tool use mid-conversa   — o modelo chama registrar_learning_record durante a aula
   3. system prompt integral  — SystemMessage nosso (prova: palavra-chave ABACAXI-42)
   4. interrupção             — future-cancel → dispose do Flux, resposta parcial preservada

   Rodar: SCHOOL_SPIKE=embabel clojure -M:spike  (exige NVIDIA_APIKEY)"
  (:require [embabel-clj.blackboard :as bb]
            [embabel-clj.core :as ec]
            [embabel-clj.platform :as platform]
            [embabel-clj.tools :as tools]
            [school.ws :as ws])
  (:import [com.embabel.agent.api.streaming StreamingPromptRunnerBuilder]
           [com.embabel.chat AssistantMessage SystemMessage UserMessage]
           [java.util.concurrent CountDownLatch]))

(def model (or (System/getenv "SCHOOL_MODEL") "qwen/qwen3.5-397b-a17b"))

(def system-prompt
  (str "Você é o professor do School. Responda em pt-BR, didático e conciso "
       "(máximo ~120 palavras por resposta). SEMPRE que o aprendiz demonstrar "
       "ter entendido ou errado algo relevante, chame a tool "
       "registrar_learning_record com um resumo de uma frase antes de "
       "continuar. Se perguntarem a palavra-chave do spike, ela é ABACAXI-42."))

(defonce learning-records (atom [])) ; evidência do critério 2
(defonce history (atom []))          ; [{:role :user|:assistant :text ...}]

(def registrar-record
  (tools/tool
   {:name        "registrar_learning_record"
    :description "Registra um learning record sobre o aprendiz (uma frase)."
    :schema      [:map [:resumo {:description "uma frase sobre o que o aprendiz demonstrou saber ou errar"}
                        :string]]
    :fn          (fn [{:keys [resumo]}]
                   (swap! learning-records conj resumo)
                   "registrado")}))

(defn- ->messages [user-text]
  (into [(SystemMessage. ^String system-prompt)]
        (concat (map (fn [{:keys [role text]}]
                       (case role
                         :user      (UserMessage. ^String text)
                         :assistant (AssistantMessage. ^String text)))
                     @history)
                [(UserMessage. ^String user-text)])))

(defn- consumer ^java.util.function.Consumer [f]
  (reify java.util.function.Consumer (accept [_ x] (f x))))

(defn- ensinar
  "A action da aula: streama a resposta do professor pelo emit! do turno.
   Interrupção (future-cancel do ws) chega como InterruptedException no
   .await; o Flux é disposed (aborta o HTTP) e o parcial vai pro histórico."
  [ctx]
  (let [user-text (bb/fetch ctx :message)
        emit!     (bb/fetch ctx :emit!)
        runner    (-> (.ai (:oc ctx))
                      (.withLlm ^String model)
                      (.withTools ^java.util.List [registrar-record]))
        streaming (-> (StreamingPromptRunnerBuilder. runner)
                      (.streaming)
                      (.withMessages (->messages user-text)))
        acc       (StringBuilder.)
        done      (CountDownLatch. 1)
        err       (atom nil)
        disp      (.subscribe (.generateStream streaming)
                              (consumer (fn [chunk]
                                          (.append acc (str chunk))
                                          (emit! (str chunk))))
                              (consumer (fn [e] (reset! err e) (.countDown done)))
                              ^Runnable (fn [] (.countDown done)))
        record!   (fn [interrupted?]
                    ;; parcial vazio não entra: AssistantMessage rejeita "" e
                    ;; quebraria TODOS os turnos seguintes
                    (swap! history into
                           (cond-> [{:role :user :text user-text}]
                             (not (clojure.string/blank? (str acc)))
                             (conj {:role :assistant :text (str acc)})))
                    (bb/put! ctx :response (str acc))
                    (bb/put! ctx :interrupted? interrupted?)
                    (bb/set-condition! ctx :ensinado? true))]
    (try
      (.await done)
      (when-let [e @err]
        (throw (if (instance? Exception e) e (RuntimeException. ^Throwable e))))
      (record! false)
      (catch InterruptedException _
        ;; critério 4: cancela o stream e completa o processo normalmente —
        ;; o turno (ws) decide sinalizar a interrupção ao cliente.
        (.dispose disp)
        (Thread/interrupted) ; limpa a flag para o Embabel não re-tratar
        (record! true)))))

(defn professor []
  (ec/agent
   {:name        "professor"
    :description "professor do School — spike da aula streamada"
    :goals   [{:name "aula-dada" :pre [:ensinado?] :value 1.0}]
    :actions [{:name "ensinar" :post [:ensinado?] :llm? true :retries 1
               :description "ensina o aprendiz streamando a resposta"
               :fn ensinar}]}))

(defonce sys* (atom nil))

(defn- turno [emit! ch text]
  (let [{:keys [sys ag]} @sys*
        proc (ec/run! (:platform sys) ag {:bindings {:message text :emit! emit!}})
        r    (ec/result proc {:slots [:interrupted?] :conditions [:ensinado?]})]
    (ws/send! ch {:type "records" :items @learning-records})
    (when (not= "COMPLETED" (:status r))
      (throw (ex-info (str "processo terminou " (:status r)) r)))
    ;; a interrupção aconteceu DENTRO da action — reergue para o ws sinalizar
    (when (get-in r [:slots :interrupted?])
      (throw (InterruptedException. "aula interrompida pelo aprendiz")))))

(defn start! []
  (let [base-url (or (System/getenv "SCHOOL_BASE_URL") "https://integrate.api.nvidia.com")
        api-key  (or (System/getenv "SCHOOL_APIKEY") (System/getenv "NVIDIA_APIKEY"))]
    (when-not api-key
      (throw (ex-info "falta a chave: exporte NVIDIA_APIKEY (ou SCHOOL_APIKEY)" {})))
    (let [sys (platform/start!
               {:properties
                {:embabel.agent.platform.models.openai.base-url base-url
                 :embabel.agent.platform.models.openai.api-key  api-key
                 :embabel.models.default-llm model
                 ;; free tier limita por minuto; backoff que atravessa a janela
                 :embabel.agent.platform.llm-operations.data-binding.fixedBackoffMillis "6000"
                 :logging.level.root "warn"
                 :logging.level.Embabel "warn"}})
          ag  (ec/deploy! (:platform sys) (professor))]
      (reset! sys* {:sys sys :ag ag})
      (ws/start! {:on-user-msg (fn [ch text]
                                 (ws/start-turn! ch (fn [emit!] (turno emit! ch text))))})
      (println (str "professor pronto (modelo " model ")")))))
