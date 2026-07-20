(ns school.cards
  "Mineração de flashcards a partir das provas corrigidas — CÓDIGO puro, sem
   LLM (Fase 2a). Questão errada ou 🤷 'não sei' vira card: frente = enunciado
   (+contexto), verso = alternativa correta + explicação, evidência = prova+qN.

   Divisão ADR-0002: o CONTEÚDO do card vive no vault
   (school/<subject>/cards/NNNN-<slug>.md, legível/editável no Obsidian);
   o estado de agendamento vive na Agenda SQLite (school.agenda).
   Dedup por slug estável do enunciado — a mesma lacuna re-testada em provas
   diferentes NÃO duplica card (a revisão dela já mora na Agenda)."
  (:require [clojure.string :as str]
            [school.agenda :as agenda]
            [school.events :as events]
            [school.vault :as vault])
  (:import [java.time Instant]))

(defn- card-slug
  "Slug estável do card: prefixo do enunciado + hash curto (dedup entre
   provas sem depender de numeração)."
  ^String [^String enunciado]
  (let [base (vault/slugify enunciado)
        base (subs base 0 (min 40 (count base)))
        h    (format "%05x" (bit-and (hash enunciado) 0xfffff))]
    (str base "-" h)))

(defn- card-md ^String [{:keys [subject prova-id]} q item]
  (let [correta-txt (some #(when (= (:correta item) (:letra %)) (:texto %))
                          (:alternativas q))]
    (str "---\nsubject: " subject
         "\nevidencia: " prova-id " " (:id item)
         "\nminerado: " (subs (str (:ts item)) 0 10)
         "\n---\n\n"
         "## Frente\n\n" (:enunciado item) "\n"
         (when-not (str/blank? (str (:contexto q)))
           (str "\n```\n" (:contexto q) "\n```\n"))
         "\n## Verso\n\n**" (str/upper-case (str (:correta item))) ") "
         correta-txt "**\n\n" (:explicacao item) "\n")))

(defn- next-card-n [subject]
  (let [d (vault/subject-file subject "cards")]
    (inc (count (or (.listFiles d) [])))))

(defn minerar!
  "Minera cards das questões erradas/não-sei de uma prova corrigida.
   `prova` traz as questões completas (alternativas); `graded` é o resultado
   de prova/grade. Devolve o nº de cards NOVOS (dedup por slug)."
  [{:keys [subject prova-id prova graded ^Instant now]}]
  (let [slug-subj (vault/slugify subject)
        now       (or now (Instant/now))
        por-id    (into {} (map (juxt :id identity)) (:questoes prova))
        novos
        (reduce
         (fn [n {:keys [acertou? enunciado] :as item}]
           (if acertou?
             n
             (let [cslug  (card-slug enunciado)
                   antes  (agenda/card-count slug-subj)
                   path   ["cards" (format "%04d-%s.md" (next-card-n slug-subj) cslug)]
                   ;; upsert primeiro: se o card já existe, NÃO reescreve arquivo
                   id     (agenda/upsert-card! {:subject slug-subj :slug cslug
                                                :path (str/join "/" path) :now now})]
               (if (> (agenda/card-count slug-subj) antes)
                 (do (vault/write-file! subject path
                                        (card-md {:subject slug-subj :prova-id prova-id}
                                                 (get por-id (:id item))
                                                 (assoc item :ts now)))
                     (inc n))
                 n))))
         0
         (:itens graded))]
    (when (pos? novos)
      (events/emit! :cards-minerados {:subject slug-subj :prova prova-id :n novos}))
    novos))
