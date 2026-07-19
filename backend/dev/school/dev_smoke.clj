(ns school.dev-smoke
  "Smoke offline das fns puras (vault + events) — sem LLM, sem Spring.
   Rodar com raiz de teste: SCHOOL_VAULT_ROOT/SCHOOL_EVENTS_FILE apontando
   para um diretório descartável, depois `clojure -M:smoke`."
  (:require [clojure.string :as str]
            [school.events :as events]
            [school.prova :as prova]
            [school.vault :as vault]))

(def ^:private prova-fixture
  {:titulo "Prova de teste"
   :questoes [{:id "q1" :enunciado "Quanto é 1+1?"
               :alternativas [{:letra "a" :texto "2"} {:letra "b" :texto "3"}]
               :correta "a" :explicacao "aritmética básica"}
              {:id "q2" :enunciado "Quanto é 2+2?" :contexto "x = 2 + 2"
               :alternativas [{:letra "a" :texto "5"} {:letra "b" :texto "4"}]
               :correta "b" :explicacao "idem" :revisao true}]})

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
  ;; parser do currículo + estágio capstone
  (vault/write-file! "teste-x" vault/curriculum-path
                     (str "# Currículo\n\n## 01 — Fundamentos\nstatus: passed\n\n"
                          "## 02 — Transducers Avançados\nstatus: active\nnotas...\n\n"
                          "## 03 — Macros\nstatus: pending\n"))
  (let [ms (vault/parse-curriculum "teste-x")]
    (assert (= 3 (count ms)) "3 módulos parseados")
    (assert (= ["passed" "active" "pending"] (mapv :status ms)) "statuses")
    (assert (= "02" (:nn (vault/active-module "teste-x"))) "módulo ativo")
    (assert (= "02-transducers-avancados" (vault/module-dir (vault/active-module "teste-x")))
            "dir do módulo"))
  (assert (not (vault/all-modules-done? "teste-x")) "ainda não terminou")
  (assert (= :ensino (vault/stage "teste-x")) "estágio ensino")
  (vault/write-file! "teste-x" vault/curriculum-path
                     "## 01 — A\nstatus: passed\n\n## 02 — B\nstatus: skipped\n")
  (assert (vault/all-modules-done? "teste-x") "tudo passou/skipped")
  (assert (= :capstone (vault/stage "teste-x")) "estágio capstone")
  (events/emit! :teste {:subject "teste-x"})
  (let [es (events/entries)]
    (assert (pos? (count es)) "evento gravado")
    (assert (= :teste (:event (last es))) "tipo do evento")
    (assert (:ts (last es)) "timestamp"))
  ;; entidade Prova (ADR-0007): renderer + correção em código
  (let [html (prova/render-html prova-fixture "http://localhost:7777/respostas/x/fria")]
    (assert (str/includes? html "CONCLUIR") "botão concluir")
    (assert (str/includes? html "Ainda não sei") "opção não sei")
    (assert (str/includes? html "justificativa") "campo justificativa")
    (assert (str/includes? html "/respostas/x/fria") "post url")
    (assert (not (str/includes? html "aritmética básica")) "gabarito NÃO vaza no HTML"))
  (let [graded (prova/grade prova-fixture
                            [{:id "q1" :alternativa "a" :justificativa "porque sim"}
                             {:id "q2" :alternativa prova/NAO-SEI :justificativa ""}])]
    (assert (= 50 (:score-pct graded)) "score em código")
    (assert (= [true false] (mapv :acertou? (:itens graded))) "acertos")
    (assert (:nao-sei? (second (:itens graded))) "não sei detectado")
    (let [md (prova/resultado-md graded {:subject "teste-x" :prova-id "prova-fria"})]
      (assert (str/includes? md "score: 50%") "score no resultado"))
    (let [gab (prova/gabarito-html graded "Prova de teste")]
      (assert (str/includes? gab "aritmética básica") "explicação no gabarito")
      (assert (str/includes? gab "porque sim") "justificativa no gabarito")))
  (println "VAULT+EVENTS+PROVA SMOKE PASS")
  (System/exit 0))
