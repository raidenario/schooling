(ns school.ws
  "Servidor WebSocket do TUI — o cano por onde a aula streama.

   Protocolo (JSON, uma mensagem por frame):
     cliente -> servidor  {:type \"user_msg\" :text \"...\"}
                          {:type \"interrupt\"}
     servidor -> cliente  {:type \"token\" :text \"...\"}   ; streaming
                          {:type \"done\"}                  ; fim do turno
                          {:type \"done\" :interrupted true}
                          {:type \"info\"/\"error\" :text \"...\"}"
  (:require [cheshire.core :as json]
            [org.httpkit.server :as http]))

(defonce server (atom nil))
(defonce turn (atom nil)) ; future do turno em curso — cancelável (critério 4)

(defn send! [ch m]
  (http/send! ch (json/generate-string m)))

(defn- finish-turn! [ch interrupted?]
  (send! ch (cond-> {:type "done"} interrupted? (assoc :interrupted true))))

(defn start-turn!
  "Roda (f emit!) num future cancelável, onde emit! streama um token ao
   cliente. InterruptedException = interrupção pedida pelo usuário."
  [ch f]
  (some-> @turn future-cancel)
  (reset! turn
          (future
            (try
              (f (fn emit! [text] (send! ch {:type "token" :text text})))
              (finish-turn! ch false)
              (catch InterruptedException _
                (finish-turn! ch true))
              (catch Exception e
                (binding [*out* *err*]
                  (println "turno falhou:" (.getMessage e))
                  (.printStackTrace e))
                (send! ch {:type "error" :text (str (.getMessage e))})
                (finish-turn! ch false))))))

(defn interrupt! [ch]
  (if-let [t @turn]
    (future-cancel t)
    (finish-turn! ch true)))

(defn start!
  "Sobe o servidor. `on-user-msg` recebe [ch text] e decide o turno
   (tipicamente chamando start-turn!); `on-start` (opcional) recebe
   [ch subject] quando o cliente abre uma matéria; `http-handler`
   (opcional) recebe requisições HTTP normais (provas interativas —
   ADR-0007) e devolve um response map ring-style ou nil."
  [{:keys [port on-user-msg on-start http-handler] :or {port 7777}}]
  (let [port (or port 7777)]
    (reset! server
            (http/run-server
             (fn [req]
               (if (:websocket? req)
                 (http/as-channel req
                   {:on-open    (fn [ch] (send! ch {:type "info" :text "conectado ao School"}))
                    :on-receive (fn [ch raw]
                                  (let [{:strs [type text subject]} (json/parse-string raw)]
                                    (case type
                                      "start"     (if on-start
                                                    (on-start ch subject)
                                                    (send! ch {:type "info" :text "start ignorado"}))
                                      "user_msg"  (on-user-msg ch text)
                                      "interrupt" (interrupt! ch)
                                      (send! ch {:type "error"
                                                 :text (str "tipo desconhecido: " type)}))))})
                 (or (when http-handler (http-handler req))
                     {:status 404
                      :headers {"Content-Type" "text/plain; charset=utf-8"}
                      :body "não encontrado"})))
             {:port port}))
    (println (str "school ws://localhost:" port))
    port))

(defn stop! []
  (when-let [s @server] (s) (reset! server nil)))
