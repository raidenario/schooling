(ns school.dev-render-fixture
  "Renderiza prova-fria.html a partir do prova-fria.edn de uma matéria —
   utilitário de teste: SCHOOL_VAULT_ROOT + clojure -M:render-fixture <subject>."
  (:require [school.prova :as prova]
            [school.vault :as vault]))

(defn -main [& [subject]]
  (let [subject (or subject "spike-teste")
        p (vault/read-edn subject vault/prova-fria-edn-path)]
    (assert p (str "prova-fria.edn não encontrada para " subject))
    (vault/write-file! subject vault/prova-fria-path
                       (prova/render-html p (str "http://localhost:7777/respostas/"
                                                 (vault/slugify subject) "/fria")))
    (println "RENDERED")
    (System/exit 0)))
