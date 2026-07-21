(ns school.dev-research-test
  "Teste offline da pesquisa web (ADR-0012) SEM LLM e SEM HTTP: `:queries` e
   `:buscar-fn` injetados. Cobre a orquestração, o dedup por URL entre buscas,
   a acumulação/dedup no RESOURCES.md e o formato das fontes p/ a aula.
   Raiz descartável: SCHOOL_VAULT_ROOT / SCHOOL_EVENTS_FILE + clojure -M:research-test"
  (:require [clojure.string :as str]
            [school.research :as r]
            [school.vault :as vault]))

(def ^:private fake-db
  {"garbage collector jvm gerações"
   {:answer "O GC da JVM divide o heap em Young e Old (hipótese geracional)."
    :fontes [{:title "Oracle GC Tuning" :url "https://docs.oracle.com/gc" :content "Eden, Survivor, Old..."}
             {:title "GC Handbook" :url "https://gchandbook.org" :content "Tracing e roots..."}]}
   "gc roots tracing"
   {:answer nil
    :fontes [{:title "GC Roots" :url "https://gchandbook.org" :content "duplicada de propósito"}
             {:title "Baeldung GC" :url "https://baeldung.com/jvm-gc" :content "Minor vs Major GC..."}]}})

(defn- fake-buscar [q]
  (or (get fake-db q) {:answer nil :fontes []}))

(defn -main [& _]
  (let [subject "jvm"
        r1 (r/pesquisar! nil {:subject subject :topico "mecânica do GC"
                              :queries ["garbage collector jvm gerações" "gc roots tracing"]
                              :buscar-fn fake-buscar})]
    ;; 4 fontes brutas, 1 URL repetida (gchandbook) → 3 distintas
    (assert (= 3 (:n-novas r1)) (str "esperava 3 fontes distintas, veio " (:n-novas r1)))
    (assert (= 3 (count (:fontes r1))))
    (assert (= "O GC da JVM divide o heap em Young e Old (hipótese geracional)."
               (:resumo r1)) "answer da 1ª busca com resultado")
    ;; RESOURCES.md escrito, com frontmatter, resumo e as 3 URLs
    (let [md (apply vault/read-file subject vault/resources-path)]
      (assert (re-find #"tipo: resources" md))
      (assert (re-find #"## mecânica do GC" md))
      (assert (re-find #"docs\.oracle\.com/gc" md))
      (assert (re-find #"baeldung\.com/jvm-gc" md))
      (assert (= 1 (count (re-seq #"gchandbook\.org" md))) "URL repetida entra 1x só"))
    ;; fontes->prompt monta o bloco pra aula
    (let [p (r/fontes->prompt (:fontes r1))]
      (assert (re-find #"### Oracle GC Tuning" p))
      (assert (re-find #"https://baeldung\.com/jvm-gc" p)))
    ;; 2ª pesquisa no mesmo tópico: as URLs já catalogadas NÃO reentram
    (let [r2 (r/pesquisar! nil {:subject subject :topico "mais sobre GC"
                                :queries ["gc roots tracing"]
                                :buscar-fn fake-buscar})
          md (apply vault/read-file subject vault/resources-path)]
      (assert (zero? (:n-novas r2)) "tudo já catalogado → 0 novas")
      (assert (re-find #"## mais sobre GC" md) "seção registrada mesmo sem novas")
      (assert (re-find #"já catalogadas" md))
      (assert (= 1 (count (re-seq #"baeldung\.com/jvm-gc" md))) "sem duplicar no arquivo"))
    ;; sem chave e sem buscar-fn → no-op gracioso
    (assert (nil? (r/pesquisar! nil {:subject subject :topico "x" :queries ["y"]})))
    (println "RESEARCH-TEST PASS")))
