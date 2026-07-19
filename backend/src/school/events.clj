(ns school.events
  "Eventos de domínio da v1 (ADR-0005): append-only, EDN, um por linha,
   FORA do OneDrive (dado temporal derivado — perder é recuperável).
   Sem consumidor ainda; é o gancho que a Fase 2 (chronicle/DICE) consome."
  (:require [clojure.java.io :as io]))

(def file
  ^java.io.File
  (io/file (or (System/getenv "SCHOOL_EVENTS_FILE")
               (str (System/getenv "LOCALAPPDATA") "\\school\\events.edn"))))

(defn emit!
  "Registra um evento: (emit! :missao-definida {:subject \"java\"}).
   Nunca lança — telemetria não pode derrubar uma aula."
  [type m]
  (try
    (io/make-parents file)
    (spit file
          (str (pr-str (assoc m :event type :ts (str (java.time.Instant/now)))) "\n")
          :append true :encoding "UTF-8")
    (catch Exception e
      (binding [*out* *err*]
        (println "events/emit! falhou:" (.getMessage e))))))

(defn entries
  "Todos os eventos (para testes/inspeção)."
  []
  (if (.exists file)
    (with-open [r (io/reader file :encoding "UTF-8")]
      (mapv read-string (line-seq r)))
    []))
