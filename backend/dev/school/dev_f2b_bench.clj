(ns school.dev-f2b-bench
  "Benchmark do custo do DICE em processo (Fase 2b): escrita, leitura
   ranqueada, bloco de prompt e replay de boot em escalas crescentes.
   Rodar com SCHOOL_CHRONICLE_LOG descartável: clojure -M:f2b-bench"
  (:require [school.memoria :as m]))

(defn- ms [f]
  (let [t0 (System/nanoTime)] (f) (/ (- (System/nanoTime) t0) 1e6)))

(defn- media-ms [n f]
  (/ (reduce + (repeatedly n #(ms f))) (double n)))

(defn -main [& _]
  (doseq [escala [10 100 1000]]
    (let [subj (str "bench-" escala)
          t-escrita (ms (fn []
                          (dotimes [i escala]
                            (m/lembrar! subj {:text (str "Fato " i " sobre o aprendiz — detalhe " i)
                                              :confidence 0.8 :importance 0.6 :decay 0.1
                                              :tipo :episodio}))))
          t-leitura (media-ms 20 #(m/memoria subj 12))
          t-bloco   (media-ms 20 #(m/bloco-prompt subj))
          bloco     (m/bloco-prompt subj)]
      (println (format "escala %4d | escrita total %7.1fms (%.2fms/prop) | leitura top-12 %6.2fms | bloco-prompt %6.2fms | bloco %d chars (~%d tokens)"
                       escala t-escrita (/ t-escrita escala) t-leitura t-bloco
                       (count bloco) (quot (count bloco) 4)))))
  ;; replay de boot com o log acumulado (1110 proposições)
  (let [t-replay (ms m/reset-para-testes!)  ; só zera; o replay é no próximo uso
        t-boot   (ms #(m/memoria "bench-1000" 1))]
    (println (format "replay de boot (log com ~1110 props): %.1fms (reset %.2fms)"
                     t-boot t-replay)))
  (let [f (java.io.File. (str m/log-file))]
    (println (format "tamanho do log: %.1f KB" (/ (.length f) 1024.0))))
  (println "F2B-BENCH DONE"))
