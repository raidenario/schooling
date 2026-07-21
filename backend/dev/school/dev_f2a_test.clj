(ns school.dev-f2a-test
  "Teste offline do ciclo Fase 2a (sem LLM, sem Spring): prova corrigida →
   mineração de cards (dedup) → fila due → página de revisão → rating FSRS →
   reagendamento; mais mineração de itens `fraco` do diagnóstico (parse +
   gravação + dedup; só o conteúdo é LLM em produção) e fila GLOBAL
   interleaved entre matérias. Rodar com raízes descartáveis:
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

(def ^:private diagnosis
  "---\nsubject: java\n---\n\n# Diagnóstico — java\n\n## Mapa de competências\n
| Competência | Nível | Evidência |
|---|---|---|
| Igualdade de objetos (== vs equals) | **fraco** | prova-fria q1 |
| String pool | fraco | prova-fria q2 |
| Aritmética básica | forte | prova-fria q3 |\n\n## Padrões de erro\n")

(defn- testa-fracos! [now]
  ;; parse do mapa: só as linhas `fraco`, negrito tolerado, separador ignorado
  (let [fracos (c/parse-fracos diagnosis)]
    (assert (= 2 (count fracos)) "2 itens fraco no mapa")
    (assert (= "Igualdade de objetos (== vs equals)" (:competencia (first fracos))))
    (assert (= "prova-fria q2" (:evidencia (second fracos))))
    (assert (= fracos (c/fracos-novos "java" diagnosis)) "nenhum tem card ainda")
    ;; gravação com conteúdo 'gerado' (em produção sai do create-edn!);
    ;; gerado sem item fraco correspondente é descartado
    (let [gerados [{:competencia "Igualdade de objetos (== vs equals)"
                    :frente "O que `==` compara entre objetos?" :verso "**Referência.** Use equals para conteúdo."}
                   {:competencia "String pool" :frente "O que o pool de strings reutiliza?"
                    :verso "Literais iguais — mesmo objeto."}
                   {:competencia "Competência inventada" :frente "x" :verso "x"}]]
      (assert (= 2 (c/gravar-fracos! {:subject "java" :fracos fracos
                                      :gerados gerados :now now})))
      (assert (= 4 (a/card-count "java")) "2 da prova + 2 do diagnóstico")
      ;; dedup: re-diagnosticar os mesmos fracos não duplica
      (assert (empty? (c/fracos-novos "java" diagnosis)))
      (assert (zero? (c/gravar-fracos! {:subject "java" :fracos fracos
                                        :gerados gerados :now now}))))))

(defn- testa-interleaving! [now]
  ;; segunda matéria com um card de diagnóstico
  (c/gravar-fracos! {:subject "clojure"
                     :fracos  [{:competencia "Atoms e swap!" :evidencia "prova-fria q1"}]
                     :gerados [{:competencia "Atoms e swap!"
                                :frente "Como atualizar um atom?" :verso "swap!/reset!"}]
                     :now now})
  (let [fila (a/due-cards-all now 20)]
    (assert (= 4 (count fila)) "3 due de java + 1 de clojure")
    (assert (= #{"java" "clojure"} (set (map :subject (take 2 fila))))
            "round-robin: as 2 primeiras posições alternam matérias")
    (let [cards (mapv #(assoc % :mat (:subject %)) (r/carrega-cards fila))
          html  (r/render-html {:subject "todas as matérias" :cards cards
                                :stats (a/stats-all now)} "/review")]
      (assert (every? :frente cards))
      (assert (re-find #"todas as matérias" html))
      (assert (re-find #"\"mat\":\"clojure\"" html) "badge de matéria no card global"))
    (let [s (a/stats-all now)]
      (assert (= 5 (:total s)))
      (assert (= 4 (:due s)))
      (assert (= 1 (:reviews s))))))

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
        cards (r/carrega-cards due)
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
    (when out (spit out html :encoding "UTF-8")))
  ;; itens `fraco` do diagnóstico + fila global interleaved
  (testa-fracos! now)
  (testa-interleaving! now)
  (println "F2A-CYCLE-TEST PASS"))
