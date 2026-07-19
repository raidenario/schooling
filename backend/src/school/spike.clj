(ns school.spike
  "Fase 0 — spike-gate do ADR-0003/0006.

   Etapa A (SCHOOL_SPIKE=fake, default): professor falso streama palavra a
   palavra — prova o cano WS→Ink e a interrupção sem gastar LLM.
   Etapa B (SCHOOL_SPIKE=embabel): a aula mínima via embabel-clj — os 4
   critérios do gate contra o framework de verdade.

   Rodar: clojure -M:spike   (ws://localhost:7777)"
  (:require [clojure.string :as str]
            [school.ws :as ws])
  (:gen-class))

(def aula-fake
  (str "Bem-vindo ao School. Esta é uma aula de mentira, streamada palavra a "
       "palavra pelo cano WebSocket, para provar que o TUI renderiza tokens "
       "progressivamente e que a interrupção com ESC funciona antes de "
       "envolvermos o Embabel. Se você está lendo isto aparecendo aos poucos "
       "no terminal, o critério de renderização do cano está de pé. "
       "Experimente interromper esta explicação no meio — o servidor deve "
       "parar na hora e marcar o turno como interrompido."))

(defn professor-fake [_user-text emit!]
  (doseq [w (str/split aula-fake #"(?<= )")]
    (emit! w)
    (Thread/sleep 25)))

(defn on-user-msg [ch text]
  (ws/start-turn! ch (fn [emit!] (professor-fake text emit!))))

(defn -main [& _]
  (case (or (System/getenv "SCHOOL_SPIKE") "fake")
    "fake" (do (ws/start! {:on-user-msg on-user-msg})
               (println "modo: professor fake (SCHOOL_SPIKE=embabel para a etapa B)"))
    "embabel" (do ((requiring-resolve 'school.spike-embabel/start!))
                  (println "modo: embabel")))
  @(promise))
