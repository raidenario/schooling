(ns school.vault
  "O vault Obsidian como contrato (ADR-0002/0004): toda prosa vive em
   markdown na estrutura school/<subject>/ das skills agent-schools//teach.
   O backend RELÊ o vault antes de decidir — edição manual no Obsidian vence.

   Estágio de uma matéria é INFERIDO do estado dos arquivos, nunca guardado:
     :missao      — sem MISSION.md
     :prova-fria  — missão existe, sem DIAGNOSIS.md (prova gerada ou não)
     :ensino      — DIAGNOSIS.md + CURRICULUM.md existem"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def root
  "Raiz do school no vault. Override por env (testes usam um diretório temp)."
  (or (System/getenv "SCHOOL_VAULT_ROOT")
      "C:\\Users\\jpedr\\OneDrive\\Documentos\\Obsidian Vault\\school"))

(defn slugify ^String [s]
  (-> (str s)
      str/lower-case
      (java.text.Normalizer/normalize java.text.Normalizer$Form/NFD)
      (str/replace #"[̀-ͯ]" "")
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-|-$)" "")))

(defn subject-dir ^java.io.File [subject]
  (io/file root (slugify subject)))

(defn subject-file ^java.io.File [subject & segs]
  (apply io/file (subject-dir subject) segs))

(defn read-file
  "Conteúdo do arquivo, ou nil se não existe. Sempre relido do disco."
  [subject & segs]
  (let [f (apply subject-file subject segs)]
    (when (.exists f) (slurp f :encoding "UTF-8"))))

(defn write-file!
  "Escreve (criando diretórios) e devolve o caminho absoluto."
  ^String [subject segs ^String content]
  (let [f (apply subject-file subject segs)]
    (io/make-parents f)
    (spit f content :encoding "UTF-8")
    (.getAbsolutePath f)))

(defn exists? [subject & segs]
  (.exists (apply subject-file subject segs)))

;; -- os arquivos do contrato --------------------------------------------------

(def mission-path    ["MISSION.md"])
(def diagnosis-path  ["DIAGNOSIS.md"])
(def curriculum-path ["CURRICULUM.md"])
(def prova-fria-path ["prova-fria.html"])
(def prova-fria-resultado-path ["prova-fria-resultado.md"])
(def gabarito-fria-path        ["gabarito-fria.html"])

(defn learning-record-path
  "Próximo learning record numerado: learning-records/NNNN-<slug>.md."
  [subject titulo]
  (let [dir (subject-file subject "learning-records")
        n   (inc (count (or (.listFiles dir) [])))]
    ["learning-records" (format "%04d-%s.md" n (slugify titulo))]))

;; -- módulos do currículo -----------------------------------------------------

(defn parse-curriculum
  "Módulos do CURRICULUM.md: cabeçalho `## NN — Nome` com `status: ...` em até
   3 linhas abaixo. Tolerante a variações de travessão e caixa."
  [subject]
  (when-let [c (apply read-file subject curriculum-path)]
    (let [linhas (vec (str/split-lines c))]
      (->> (map-indexed vector linhas)
           (keep (fn [[i l]]
                   (when-let [[_ nn nome] (re-find #"^##\s*(\d+)\s*[—–-]+\s*(.+?)\s*$" l)]
                     (let [status (some #(second (re-find #"(?i)status:\s*\**\s*(pending|active|passed|skipped)" %))
                                        (subvec linhas (inc i)
                                                (min (count linhas) (+ i 4))))]
                       {:nn nn :nome (str/trim nome)
                        :status (str/lower-case (or status "pending"))}))))
           vec))))

(defn active-module [subject]
  (first (filter #(= "active" (:status %)) (parse-curriculum subject))))

(defn module-dir ^String [{:keys [nn nome]}]
  (str nn "-" (slugify nome)))

(defn module-prova-path  [m] ["modules" (module-dir m) "prova.html"])
(defn module-result-path [m] ["modules" (module-dir m) "prova-result.md"])

(defn all-modules-done? [subject]
  (let [ms (parse-curriculum subject)]
    (boolean (and (seq ms)
                  (every? #(contains? #{"passed" "skipped"} (:status %)) ms)))))

(defn stage
  "Estágio inferido do estado dos arquivos — a base das pré-condições GOAP."
  [subject]
  (cond
    (not (apply exists? subject mission-path))   :missao
    (not (apply exists? subject diagnosis-path)) :prova-fria
    (all-modules-done? subject)                  :capstone
    :else                                        :ensino))

(defn resumo
  "Estado compacto da matéria para o system prompt do professor."
  [subject]
  (let [m (active-module subject)]
    {:subject    subject
     :stage      (stage subject)
     :dir        (.getAbsolutePath (subject-dir subject))
     :mission    (apply read-file subject mission-path)
     :diagnosis  (apply read-file subject diagnosis-path)
     :curriculum (apply read-file subject curriculum-path)
     :prova-gerada? (apply exists? subject prova-fria-path)
     :modulo m
     :modulo-prova-gerada?   (boolean (and m (apply exists? subject (module-prova-path m))))
     :modulo-prova-corrigida? (boolean (and m (apply exists? subject (module-result-path m))))}))
