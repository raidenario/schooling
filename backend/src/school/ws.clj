(ns school.ws
  "Servidor WebSocket do TUI — o cano por onde a aula streama.

   Protocolo (JSON, uma mensagem por frame):
     cliente -> servidor  {:type \"user_msg\" :text \"...\"}
                          {:type \"interrupt\"}
     servidor -> cliente  {:type \"token\" :text \"...\"}    ; streaming (conteúdo)
                          {:type \"thinking\" :text \"...\"} ; reasoning (ADR-0011)
                          {:type \"done\"}                   ; fim do turno
                          {:type \"done\" :interrupted true}
                          {:type \"modo\" :modo \"...\"}
                          {:type \"info\"/\"error\" :text \"...\"}"
  (:require [cheshire.core :as json]
            [org.httpkit.server :as http]))

(defonce server (atom nil))
(defonce turn (atom nil)) ; future do turno em curso — cancelável (critério 4)

(defn send! [ch m]
  (http/send! ch (json/generate-string m)))

(defn- finish-turn! [ch interrupted?]
  (send! ch (cond-> {:type "done"} interrupted? (assoc :interrupted true))))

(defn turn-active? []
  (boolean (when-let [t @turn] (not (future-done? t)))))

(defn start-turn!
  "Roda (f emit!) num future. Se JÁ HÁ turno em andamento, avisa e NÃO inicia
   outro — mensagem nova não mata turno (dogfood 2026-07-20: matar a correção
   no meio recomeçava tudo e queimava o rate limit); interromper é papel do
   ESC (interrupt!). emit! com string streama um token; com mapa envia o frame
   como está (ex.: {:type \"thinking\"})."
  [ch f]
  (if (turn-active?)
    (do (send! ch {:type "info"
                   :text "⏳ ainda estou terminando o turno anterior — um instante (ESC interrompe)"})
        nil)
    (reset! turn
            (future
              (send! ch {:type "turn_start"}) ; TUI trava o input também nos turnos por POST
              (try
                (f (fn emit! [x]
                     (send! ch (if (map? x) x {:type "token" :text (str x)}))))
                (finish-turn! ch false)
                (catch InterruptedException _
                  (finish-turn! ch true))
                (catch Exception e
                  (binding [*out* *err*]
                    (println "turno falhou:" (.getMessage e))
                    (.printStackTrace e))
                  (let [m (str (.getMessage e))]
                    (send! ch {:type "error"
                               :text (if (re-find #"429" m)
                                       (str "o free tier pediu um respiro (limite de "
                                            "requisições) — espere ~30s e mande de novo")
                                       m)}))
                  (finish-turn! ch false)))))))

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
