(ns school.dev-smoke
  "Smoke offline das fns puras (vault + events) — sem LLM, sem Spring.
   Rodar com raiz de teste: SCHOOL_VAULT_ROOT/SCHOOL_EVENTS_FILE apontando
   para um diretório descartável, depois `clojure -M:smoke`."
  (:require [school.events :as events]
            [school.vault :as vault]))

(defn -main [& _]
  (assert (= "clojure-avancado" (vault/slugify "Clojure Avançado!")) "slugify")
  (assert (= :missao (vault/stage "teste-x")) "estágio inicial")
  (vault/write-file! "teste-x" vault/mission-path "# Missão de teste")
  (assert (= :prova-fria (vault/stage "teste-x")) "estágio pós-missão")
  (vault/write-file! "teste-x" vault/diagnosis-path "# Diagnóstico de teste")
  (assert (= :ensino (vault/stage "teste-x")) "estágio pós-diagnóstico")
  (assert (= "# Missão de teste" (apply vault/read-file "teste-x" vault/mission-path)) "read-file")
  (let [[dir nome] (vault/learning-record-path "teste-x" "Primeiro Record")]
    (assert (= "learning-records" dir))
    (assert (= "0001-primeiro-record.md" nome) "numeração do learning record"))
  (events/emit! :teste {:subject "teste-x"})
  (let [es (events/entries)]
    (assert (pos? (count es)) "evento gravado")
    (assert (= :teste (:event (last es))) "tipo do evento")
    (assert (:ts (last es)) "timestamp"))
  (println "VAULT+EVENTS SMOKE PASS")
  (System/exit 0))
