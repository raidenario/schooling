(ns school.dev-research-live
  "Smoke AO VIVO do Tavily (ADR-0012) — valida o round-trip HTTP real que os
   mocks do :research-test não cobrem. Precisa da chave:
     $env:SCHOOL_SEARCH_APIKEY = '<tavily>'
     clojure -M:research-live \"garbage collector jvm generational\""
  (:require [school.research :as r]))

(defn -main [& [query]]
  (when-not (r/habilitada?)
    (println "falta SCHOOL_SEARCH_APIKEY (chave Tavily)") (System/exit 1))
  (let [q (or query "how the JVM garbage collector divides the heap into generations")
        {:keys [answer fontes]} (r/buscar! q {:max-results 4})]
    (println "query:" q)
    (println "answer:" (subs (str answer) 0 (min 200 (count (str answer)))) "…")
    (println "fontes:" (count fontes))
    (doseq [{:keys [title url]} fontes] (println "  -" title "—" url))
    (assert (seq fontes) "Tavily devolveu fontes")
    (println "RESEARCH-LIVE PASS")
    (System/exit 0)))
