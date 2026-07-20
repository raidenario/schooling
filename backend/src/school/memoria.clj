(ns school.memoria
  "Fase 2b — a memória FINA do professor (ADR-0005): DICE embutido na mesma
   JVM, decorado pelo dice-chronicle. Duas camadas de memória:

     DIAGNOSIS.md  — mapa MACRO de habilidades, no vault (contrato, Obsidian)
     DICE          — micro-fatos, misconceptions e episódios, com confiança,
                     decay e contradição — o que muda turno a turno

   Persistência Datomic-style: o repo é in-memory; a fonte de verdade é o
   LOG do chronicle (EDN append-only, fora do OneDrive — dado temporal
   derivado, ADR-0002). No boot, replay do log reconstrói a memória; o
   bundle/replay :upto dá time travel ('o que o professor sabia antes?').

   Escrita v1 DETERMINÍSTICA (sem reviser LLM): os momentos-chave do School
   já são estruturados — mesma filosofia da mineração de cards (ADR-0009).
   Dedup por texto exato ACTIVE no mesmo contexto."
  (:require [clojure.string :as str]
            [dice-chronicle.bundle :as bundle]
            [dice-chronicle.interop :as interop]
            [dice-chronicle.listener :as listener]
            [dice-chronicle.log :as log]
            [school.vault :as vault])
  (:import (com.embabel.dice.proposition PropositionQuery)
           (com.embabel.dice.proposition.store InMemoryPropositionRepository)))

(def log-file
  (or (System/getenv "SCHOOL_CHRONICLE_LOG")
      (str (System/getenv "LOCALAPPDATA") "\\school\\chronicle.edn")))

(defonce ^:private sys* (atom nil))

(defn start!
  "Sobe a memória (idempotente): abre o log, REPLAYA a história no repo
   in-memory (no repo cru — replay não deve re-logar) e decora com o
   listener do chronicle. Devolve {:chronicle :repo}."
  []
  (or @sys*
      (let [chronicle (log/open! log-file)
            raw       (InMemoryPropositionRepository. nil)
            _         (bundle/replay! {:events (log/entries chronicle)} raw)
            repo      (listener/wrap raw chronicle)]
        (reset! sys* {:chronicle chronicle :repo repo}))))

(defn reset-para-testes!
  "Zera o singleton (os testes apontam SCHOOL_CHRONICLE_LOG para um temp e
   precisam re-bootar dentro da mesma JVM)."
  []
  (reset! sys* nil))

(defn- ctx-id ^String [subject]
  (str "school-" (vault/slugify subject)))

;; ---------------------------------------------------------------------------
;; escrita
;; ---------------------------------------------------------------------------

(defn- ativa? [p] (= "ACTIVE" (.name (.getStatus p))))

(defn- busca-ativa
  "Proposição ACTIVE do contexto por texto exato (string) ou {:prefixo s}."
  [repo cid alvo]
  (let [match? (if (map? alvo)
                 #(str/starts-with? % (:prefixo alvo))
                 #(= alvo %))]
    (some #(when (and (ativa? %) (match? (.getText %))) %)
          (.query repo (PropositionQuery/againstContext cid)))))

(defn lembrar!
  "Grava um micro-fato/misconception/episódio sobre o aprendiz na matéria.
   {:text :confidence :importance :decay :tipo :evidencia}. Texto idêntico
   ACTIVE no mesmo contexto => :duplicada (não regrava). Nunca lança —
   memória não pode derrubar uma aula."
  [subject {:keys [text confidence importance decay tipo evidencia]}]
  (try
    (let [{:keys [repo]} (start!)
          cid (ctx-id subject)]
      (if (busca-ativa repo cid text)
        :duplicada
        (do (.save repo (interop/make-proposition
                         {:context-id cid
                          :text       text
                          :confidence (double (or confidence 0.8))
                          :importance (double (or importance 0.5))
                          :decay      (double (or decay 0.1))
                          :metadata   (cond-> {}
                                        tipo      (assoc "tipo" (name tipo))
                                        evidencia (assoc "evidencia" (str evidencia)))}))
            :salva)))
    (catch Exception e
      (binding [*out* *err*]
        (println "memoria/lembrar! falhou:" (.getMessage e)))
      :falhou)))

(defn contradizer!
  "Marca como CONTRADICTED a proposição ACTIVE que casa com `antiga` (texto
   exato ou {:prefixo s}) e grava a substituta. O chronicle preserva a
   história da troca."
  [subject antiga nova]
  (try
    (let [{:keys [repo]} (start!)
          cid (ctx-id subject)]
      (when-let [p (busca-ativa repo cid antiga)]
        (.save repo (interop/with-status p :CONTRADICTED)))
      (lembrar! subject nova))
    (catch Exception e
      (binding [*out* *err*]
        (println "memoria/contradizer! falhou:" (.getMessage e)))
      :falhou)))

;; ---------------------------------------------------------------------------
;; leitura
;; ---------------------------------------------------------------------------

(defn memoria
  "Top-n proposições ACTIVE da matéria por confiança EFETIVA (decay aplicado
   pelo DICE) — a memória de trabalho do professor."
  [subject n]
  (try
    (let [{:keys [repo]} (start!)]
      (->> (.query repo (-> (PropositionQuery/againstContext (ctx-id subject))
                            (.orderedByEffectiveConfidence)))
           (filter ativa?)
           (take n)
           (mapv (fn [p] {:texto (.getText p)
                          :confianca-efetiva (.effectiveConfidence p 2.0)
                          :tipo (get (.getMetadata p) "tipo")
                          :evidencia (get (.getMetadata p) "evidencia")}))))
    (catch Exception e
      (binding [*out* *err*]
        (println "memoria/memoria falhou:" (.getMessage e)))
      [])))

(defn bloco-prompt
  "A seção de memória para o system prompt do professor ('' se vazia) — o
   equivalente manual do PromptContributor do ADR-0005."
  ^String [subject]
  (let [ms (memoria subject 12)]
    (if (empty? ms)
      ""
      (str "\nMEMÓRIA FINA DO PROFESSOR (o que você já sabe DESTE aprendiz — "
           "use ao adaptar; confiança efetiva entre parênteses):\n"
           (str/join "\n" (map #(format "- %s (%.2f)"
                                        (:texto %) (double (:confianca-efetiva %)))
                               ms))
           "\n"))))

;; ---------------------------------------------------------------------------
;; trajetória (chronicle)
;; ---------------------------------------------------------------------------

(defn trajetoria
  "Últimos `n` eventos do chronicle da matéria, mais recentes primeiro —
   a história auditável de por que o professor acredita no que acredita."
  [subject n]
  (try
    (let [{:keys [chronicle]} (start!)
          cid (ctx-id subject)]
      (->> (log/entries chronicle)
           (filter #(= cid (get-in % [:proposition :context-id])))
           (take-last n)
           reverse
           (mapv (fn [e]
                   {:seq (:seq e) :at (:at e) :event (:event e)
                    :texto (get-in e [:proposition :text])
                    :status (some-> (:new-status e) name)}))))
    (catch Exception _ [])))

;; ---------------------------------------------------------------------------
;; a página /memoria/<matéria> — renderer fixo (padrão ADR-0007/0008/0009)
;; ---------------------------------------------------------------------------

(defn- esc ^String [s]
  (-> (str s)
      (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(def ^:private css "
:root{--verde:#58cc02;--azul:#1cb0f6;--roxo:#8b5cf6;--amarelo:#ffc800;
--vermelho:#ff4b4b;--cinza:#e5e5e5;--texto:#3c3c3c;--fundo:#f7f7f7}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Segoe UI',system-ui,sans-serif;background:var(--fundo);color:var(--texto)}
.topo{position:sticky;top:0;background:#fff;padding:14px 20px;box-shadow:0 2px 8px #0001}
.topo small{color:#999}
main{max-width:760px;margin:0 auto;padding:24px 16px 60px}
h2{font-size:19px;margin:22px 0 10px}
.m{background:#fff;border:2px solid var(--cinza);border-radius:12px;
padding:12px 16px;margin-bottom:10px;display:flex;gap:12px;align-items:baseline}
.m .conf{font-weight:800;color:var(--verde);min-width:52px}
.m .tipo{font-size:12px;border-radius:8px;padding:2px 8px;color:#fff;white-space:nowrap}
.tipo.misconception{background:var(--vermelho)}.tipo.lacuna{background:var(--amarelo);color:#3c3c3c}
.tipo.episodio{background:var(--azul)}.tipo.preferencia{background:var(--roxo)}
.tipo.fato{background:var(--verde)}
.t{background:#fff;border-left:4px solid var(--cinza);padding:8px 14px;margin-bottom:6px;
font-size:14px;color:#555}
.t b{color:#333}
.t.contra{border-left-color:var(--vermelho)}
.vazio{color:#999;padding:20px;text-align:center}
")

(defn render-html
  "A memória fina + trajetória da matéria como página read-only."
  ^String [subject]
  (let [ms (memoria subject 20)
        tj (trajetoria subject 30)]
    (str "<!doctype html><html lang='pt-BR'><head><meta charset='utf-8'>"
         "<meta name='viewport' content='width=device-width,initial-scale=1'>"
         "<title>Memória — " (esc subject) "</title><style>" css "</style></head><body>"
         "<div class='topo'><b>✳ School</b> — Memória do professor: " (esc subject)
         "<small> · o que ele sabe de você e por quê (DICE + chronicle)</small></div><main>"
         "<h2>🧠 Memória ativa (por confiança efetiva)</h2>"
         (if (empty? ms)
           "<p class='vazio'>ainda não sei nada sobre você nesta matéria — estude comigo!</p>"
           (apply str
                  (for [{:keys [texto confianca-efetiva tipo]} ms]
                    (str "<div class='m'><span class='conf'>"
                         (format "%.2f" (double confianca-efetiva)) "</span>"
                         "<span>" (esc texto) "</span>"
                         (when tipo (str "<span class='tipo " (esc tipo) "'>" (esc tipo) "</span>"))
                         "</div>"))))
         "<h2>📜 Trajetória (mais recente primeiro)</h2>"
         (if (empty? tj)
           "<p class='vazio'>sem eventos ainda</p>"
           (apply str
                  (for [{:keys [seq at event texto status]} tj]
                    (str "<div class='t" (when (= status "CONTRADICTED") " contra") "'>"
                         "<b>#" seq "</b> · " (esc (name event))
                         (when status (str " → <b>" (esc status) "</b>"))
                         " · " (esc texto)
                         "<br><small>" (esc (subs (str at) 0 (min 19 (count (str at))))) "</small></div>"))))
         "</main></body></html>")))
