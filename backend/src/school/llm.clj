(ns school.llm
  "Transporte direto Spring AI → NIM para os pontos COM reasoning (ADR-0011).

   Por quê: o conversor de options do embabel descarta thinking/extensions
   (provado por bytecode no spike de 2026-07-20), mas o Spring AI 1.1.7 entrega
   `chat_template_kwargs` por request via extraBody e expõe o reasoning_content
   POR CHUNK no streaming. Então os pontos ON falam com o NIM por aqui —
   mantendo retry/validação no embabel-clj via o escape hatch `:ask-fn` do
   create-edn! — e os judges/cards (OFF) seguem no caminho embabel intocado.

   Caveat do spike: o pensamento CONSOME max_tokens (2048 inteiros sem
   resposta no teste) — todo chamador daqui usa teto generoso e, na prosa,
   effort mais leve."
  (:import [java.util.concurrent CountDownLatch TimeUnit TimeoutException]
           [org.springframework.ai.chat.messages AssistantMessage SystemMessage
            UserMessage]
           [org.springframework.ai.chat.prompt Prompt]
           [org.springframework.ai.openai OpenAiChatModel OpenAiChatOptions]
           [org.springframework.ai.openai.api OpenAiApi]))

(def model (or (System/getenv "SCHOOL_MODEL") "z-ai/glm-5.2"))
(def base-url (or (System/getenv "SCHOOL_BASE_URL") "https://integrate.api.nvidia.com"))

(defonce ^:private chat-model*
  (delay
    (let [api-key (or (System/getenv "SCHOOL_APIKEY") (System/getenv "NVIDIA_APIKEY"))]
      (when-not api-key
        (throw (ex-info "school.llm: falta NVIDIA_APIKEY (ou SCHOOL_APIKEY)" {})))
      (-> (OpenAiChatModel/builder)
          (.openAiApi (-> (OpenAiApi/builder)
                          (.baseUrl base-url)
                          (.apiKey ^String api-key)
                          (.build)))
          (.build)))))

(defn- opts ^OpenAiChatOptions [{:keys [max-tokens temperature effort]}]
  (-> (OpenAiChatOptions/builder)
      (.model ^String model)
      (.maxTokens (Integer/valueOf (int (or max-tokens 16384))))
      (.temperature (Double/valueOf (double (or temperature 0.6))))
      (.extraBody {"chat_template_kwargs"
                   (cond-> {"enable_thinking" true
                            "clear_thinking"  false}
                     effort (assoc "reasoning_effort" (name effort)))})
      (.build)))

(defn- ->prompt ^Prompt [{:keys [system history prompt] :as req}]
  (Prompt. ^java.util.List
           (vec (concat (when system [(SystemMessage. ^String system)])
                        (map (fn [{:keys [role text]}]
                               (case role
                                 :user      (UserMessage. ^String text)
                                 :assistant (AssistantMessage. ^String text)))
                             history)
                        [(UserMessage. ^String prompt)]))
           (opts req)))

(def ^:private backoffs-429-ms
  "Free tier da NVIDIA limita requests/minuto; 429 espera e re-tenta
   (dogfood 2026-07-20: sem isso, um estouro vira espiral de erro cru)."
  [15000 30000 60000])

(defn- rate-limited? [^Throwable e]
  (boolean (re-find #"429" (str (.getMessage e)))))

(defn- com-retry-429 [f]
  (loop [tentativa 0]
    (let [r (try
              {:ok (f)}
              (catch InterruptedException e (throw e))
              (catch Exception e
                (if (and (rate-limited? e) (< tentativa (count backoffs-429-ms)))
                  {:espera (nth backoffs-429-ms tentativa)}
                  (throw e))))]
      (if (contains? r :ok)
        (:ok r)
        (do (Thread/sleep (long (:espera r))) ; ESC interrompe o sleep
            (recur (inc tentativa)))))))

(defn pensando!
  "Chamada bloqueante COM reasoning: o pensamento fica no servidor elevando a
   qualidade; só o conteúdo volta. :timeout-s duro via future (ESC do aprendiz
   interrompe a thread → InterruptedException propaga, como no caminho
   embabel); 429 do free tier espera e re-tenta."
  ^String [{:keys [timeout-s] :as req}]
  (com-retry-429
   (fn []
     (let [f (future (-> (.call ^OpenAiChatModel @chat-model* (->prompt req))
                         .getResult .getOutput .getText str))]
       (try
         (.get f (long (or timeout-s 300)) TimeUnit/SECONDS)
         (catch TimeoutException _
           (future-cancel f)
           (throw (ex-info "school.llm: timeout na chamada com reasoning"
                           {:timeout-s (or timeout-s 300)})))
         (catch java.util.concurrent.ExecutionException e
           (throw (or (.getCause e) e))))))))

(defn ask-fn
  "Adaptador para o `:ask-fn` do create-edn! (embabel-clj): retry e validação
   malli continuam lá; só o transporte muda para o caminho com reasoning."
  [defaults]
  (fn [prompt] (pensando! (assoc defaults :prompt prompt))))

(defn- consumer ^java.util.function.Consumer [f]
  (reify java.util.function.Consumer (accept [_ x] (f x))))

(defn stream-pensando!
  "Streaming COM reasoning visível: `on-thinking` recebe os chunks do
   pensamento (metadata reasoningContent, separado do texto), `on-token` os do
   conteúdo. Bloqueia até o fim; interrupção (ESC) → dispose e
   {:interrupted? true} — mesmo contrato do stream! histórico. 429 ANTES do
   primeiro chunk espera e re-tenta; depois que algo foi emitido, sobe (não dá
   para re-streamar sem duplicar na tela)."
  [req on-thinking on-token]
  (loop [tentativa 0]
    (let [emitiu? (volatile! false)
          acc     (StringBuilder.)
          done    (CountDownLatch. 1)
          err     (atom nil)
          flux    (.stream ^OpenAiChatModel @chat-model* (->prompt req))
          disp    (.subscribe flux
                              (consumer
                               (fn [r]
                                 (when-let [out (some-> r .getResult .getOutput)]
                                   (let [rc (str (get (.getMetadata out) "reasoningContent"))]
                                     (when (seq rc)
                                       (vreset! emitiu? true)
                                       (on-thinking rc)))
                                   (when-let [t (.getText out)]
                                     (when (seq t)
                                       (vreset! emitiu? true)
                                       (.append acc t)
                                       (on-token t))))))
                              (consumer (fn [e] (reset! err e) (.countDown done)))
                              ^Runnable (fn [] (.countDown done)))
          r (try
              (.await done)
              (if-let [e @err]
                (if (and (rate-limited? e) (not @emitiu?)
                         (< tentativa (count backoffs-429-ms)))
                  {:retry-em (nth backoffs-429-ms tentativa)}
                  (throw (if (instance? Exception e) e (RuntimeException. ^Throwable e))))
                {:text (str acc) :interrupted? false})
              (catch InterruptedException _
                (.dispose disp)
                (Thread/interrupted)
                {:text (str acc) :interrupted? true}))]
      (if-let [espera (:retry-em r)]
        (do (Thread/sleep (long espera)) ; ESC interrompe o sleep
            (recur (inc tentativa)))
        r))))
