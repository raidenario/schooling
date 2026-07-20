(ns school.dev-f2a-test
  "Teste offline do ciclo Fase 2a (sem LLM, sem Spring): prova corrigida →
   mineração de cards (dedup) → fila due → página de revisão → rating FSRS →
   reagendamento. Rodar com raízes descartáveis:
   SCHOOL_AGENDA_DB / SCHOOL_VAULT_ROOT / SCHOOL_EVENTS_FILE + clojure -M:f2a-test"
  (:require [school.agenda :as a]
            [school.cards :as c]
            [school.prova :as p]
            [school.review :as r]))

(def ^:private now (java.time.Instant/parse "2026-07-20T12:00:00Z"))

(def ^:private prova
  {:titulo "Prova fria — java"
   :questoes [{:id "q1" :enunciado "O que == compara em objetos Java?"
               :alternativas [{:letra "a" :texto "Conteúdo"} {:letra "b" :texto "Referência"}]
               :correta "b" :explicacao "== compara identidade de referência; equals compara conteúdo."}
              {:id "q2" :enunciado "O que é o string pool?" :contexto "String a = \"x\";"
               :alternativas [{:letra "a" :texto "Cache de literais reutilizados"} {:letra "b" :texto "Um tipo de GC"}]
               :correta "a" :explicacao "Literais iguais apontam para o mesmo objeto no pool."}
              {:id "q3" :enunciado "Quanto é 1+1?"
               :alternativas [{:letra "a" :texto "2"} {:letra "b" :texto "3"}]
               :correta "a" :explicacao "aritmética"}]})

(def ^:private graded
  (p/grade prova [{:id "q1" :alternativa "a" :justificativa "achei que era conteúdo"}
                  {:id "q2" :alternativa "ns" :justificativa ""}
                  {:id "q3" :alternativa "a" :justificativa ""}]))

(defn -main [& [out]]
  ;; minera: q1 errou + q2 não-sei = 2 cards; q3 acertou = nada
  (assert (= 2 (c/minerar! {:subject "java" :prova-id "prova-fria"
                            :prova prova :graded graded :now now})))
  (assert (= 2 (a/card-count "java")))
  ;; re-minerar a MESMA prova não duplica
  (assert (zero? (c/minerar! {:subject "java" :prova-id "prova-fria"
                              :prova prova :graded graded :now now})))
  (assert (= 2 (a/card-count "java")) "dedup por slug")
  ;; fila e página
  (let [due   (a/due-cards "java" now 20)
        cards (r/carrega-cards "java" due)
        html  (r/render-html {:subject "java" :cards cards
                              :stats (a/stats "java" now)} "/review/java")]
    (assert (= 2 (count cards)) "cards carregados do vault")
    (assert (every? #(and (seq (:frente %)) (seq (:verso %))) cards))
    (assert (re-find #"MOSTRAR RESPOSTA" html))
    (assert (re-find #"Errei" html))
    (assert (re-find #"string pool" html) "conteúdo do card na página")
    (assert (re-find #"<pre>" html) "contexto vira bloco de código")
    ;; rating aplica FSRS e reagenda
    (a/review! (:id (first due)) 3 now)
    (assert (= 1 (count (a/due-cards "java" now 20))) "revisado saiu da fila de hoje")
    (let [vazio (r/render-html {:subject "java" :cards []
                                :stats (a/stats "java" now)} "/review/java")]
      (assert (re-find #"Tudo em dia" vazio)))
    (when out (spit out html :encoding "UTF-8"))
    (println "F2A-CYCLE-TEST PASS")))
