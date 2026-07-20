(ns school.dev-f2b-test
  "Teste offline do ciclo Fase 2b (sem LLM, sem Spring): memória fina do
   professor — escrita determinística, dedup, leitura por confiança efetiva,
   contradição com história preservada, e replay do chronicle pós-restart.
   Rodar com SCHOOL_CHRONICLE_LOG descartável + clojure -M:f2b-test"
  (:require [school.memoria :as m]))

(defn -main [& _]
  ;; escrita + dedup
  (assert (= :salva (m/lembrar! "java" {:text "Aprendiz confunde == com equals"
                                        :confidence 0.9 :importance 0.8 :decay 0.15
                                        :tipo :misconception :evidencia "prova-fria q1"})))
  (assert (= :duplicada (m/lembrar! "java" {:text "Aprendiz confunde == com equals"
                                            :confidence 0.9}))
          "dedup por texto exato")
  (m/lembrar! "java" {:text "Engata melhor com exemplo de código do que definição"
                      :confidence 0.8 :importance 0.9 :decay 0.05 :tipo :preferencia})
  (m/lembrar! "java" {:text "Compreendeu closures com exemplo próprio (aula 01)"
                      :confidence 0.9 :importance 0.7 :decay 0.1 :tipo :episodio})
  (m/lembrar! "rust" {:text "Nunca usou linguagem sem GC" :confidence 0.95 :tipo :fato})

  ;; leitura por matéria
  (let [ms (m/memoria "java" 10)]
    (assert (= 3 (count ms)) "memória por matéria (rust separado)")
    (doseq [x ms]
      (println " -" (:texto x) (format "(%.2f)" (double (:confianca-efetiva x))))))
  (assert (re-find #"MEMÓRIA FINA" (m/bloco-prompt "java")))
  (assert (= "" (m/bloco-prompt "python")) "matéria sem memória => bloco vazio")

  ;; contradição preserva história
  (m/contradizer! "java" "Aprendiz confunde == com equals"
                  {:text "Superou a confusão == vs equals (prova módulo 01, q2 certa)"
                   :confidence 0.85 :tipo :episodio})
  (let [ms (m/memoria "java" 10)]
    (assert (= 3 (count ms)))
    (assert (not-any? #(re-find #"confunde ==" (:texto %)) ms)
            "contradita saiu da memória ativa"))
  (println "trajetória:")
  (doseq [e (m/trajetoria "java" 10)]
    (println "  " (:seq e) (:event e) (or (:status e) "") "-" (:texto e)))
  (assert (seq (m/trajetoria "java" 10)))

  ;; persistência Datomic-style: replay do log reconstrói após 'restart'
  (m/reset-para-testes!)
  (let [ms (m/memoria "java" 10)]
    (assert (= 3 (count ms)) "replay pós-restart")
    (assert (not-any? #(re-find #"confunde ==" (:texto %)) ms)
            "contradição sobreviveu ao restart"))

  ;; a página /memoria/<slug>
  (let [html (m/render-html "java")]
    (assert (re-find #"Memória ativa" html))
    (assert (re-find #"Trajetória" html))
    (assert (re-find #"CONTRADICTED" html) "trajetória mostra a contradição")
    (assert (re-find #"misconception|episodio|preferencia" html) "tipos renderizados")
    (when-let [out (System/getenv "F2B_HTML_OUT")]
      (spit out html :encoding "UTF-8")))
  (println "F2B-MEMORIA-TEST PASS"))
