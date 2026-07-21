(ns school.agenda
  "A Agenda FSRS no SQLite (ADR-0002: dado temporal derivado, FORA do vault
   e do OneDrive — perder é re-minerável). O CONTEÚDO dos cards vive no
   vault (school/<subject>/cards/*.md); aqui mora só o estado de agendamento
   (FSRS) e o review log append-only.

   Tabelas:
     cards      — identidade: (subject, slug) únicos + caminho no vault
     agenda     — estado FSRS corrente por card (1:1)
     review_log — histórico append-only de revisões"
  (:require [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [school.fsrs :as fsrs])
  (:import [java.time Instant]))

(def db-file
  (or (System/getenv "SCHOOL_AGENDA_DB")
      (str (System/getenv "LOCALAPPDATA") "\\school\\agenda.db")))

(defonce ^:private ds*
  (delay
    (io/make-parents (io/file db-file))
    (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname db-file})]
      (jdbc/execute! ds ["create table if not exists cards (
                           id       integer primary key autoincrement,
                           subject  text not null,
                           slug     text not null,
                           path     text not null,
                           created  text not null,
                           suspended integer not null default 0,
                           unique (subject, slug))"])
      (jdbc/execute! ds ["create table if not exists agenda (
                           card_id    integer primary key references cards(id),
                           stability  real,
                           difficulty real,
                           reps       integer not null default 0,
                           lapses     integer not null default 0,
                           last_review text,
                           due        text,
                           interval_days integer)"])
      (jdbc/execute! ds ["create table if not exists review_log (
                           id      integer primary key autoincrement,
                           card_id integer not null references cards(id),
                           ts      text not null,
                           rating  integer not null,
                           elapsed_days   real,
                           interval_days  integer,
                           stability      real,
                           difficulty     real)"])
      ds)))

(defn- ds [] @ds*)

(def ^:private opts {:builder-fn rs/as-unqualified-lower-maps})

(defn- q  [sql] (jdbc/execute! (ds) sql opts))
(defn- q1 [sql] (jdbc/execute-one! (ds) sql opts))

;; ---------------------------------------------------------------------------
;; cards
;; ---------------------------------------------------------------------------

(defn upsert-card!
  "Registra um card ((subject, slug) únicos — repetir é no-op) e devolve o id.
   `now` explícito para determinismo nos testes."
  [{:keys [subject slug path ^Instant now]}]
  (or (:id (q1 ["select id from cards where subject=? and slug=?" subject slug]))
      (:id (jdbc/execute-one! (ds)
             ["insert into cards (subject, slug, path, created) values (?,?,?,?)
               returning id" subject slug path (str (or now (Instant/now)))]
             opts))))

(defn card-count [subject]
  (:n (q1 ["select count(*) as n from cards where subject=? and suspended=0" subject])))

(defn card-exists? [subject slug]
  (some? (q1 ["select id from cards where subject=? and slug=?" subject slug])))

(defn card-info
  "Identidade de um card pelo id: {:subject :slug :path}, ou nil."
  [card-id]
  (q1 ["select subject, slug, path from cards where id=?" card-id]))

;; ---------------------------------------------------------------------------
;; estado FSRS <-> linha da agenda
;; ---------------------------------------------------------------------------

(defn- row->state [row]
  (when (:reps row)
    (cond-> {:reps (:reps row) :lapses (:lapses row)}
      (:stability row)   (assoc :stability (:stability row)
                                :difficulty (:difficulty row))
      (:last_review row) (assoc :last-review (Instant/parse (:last_review row)))
      (:due row)         (assoc :due (Instant/parse (:due row)))
      (:interval_days row) (assoc :interval-days (:interval_days row)))))

(defn due-cards
  "Cards do subject vencidos em `now` (novos primeiro, depois por due asc),
   com o estado FSRS embutido. `limit` corta a fila do dia."
  [subject ^Instant now limit]
  (->> (q ["select c.id, c.subject, c.slug, c.path, a.stability, a.difficulty,
                   a.reps, a.lapses, a.last_review, a.due, a.interval_days
            from cards c left join agenda a on a.card_id = c.id
            where c.subject=? and c.suspended=0
              and (a.due is null or a.due <= ?)
            order by a.due is null desc, a.due asc
            limit ?" subject (str now) (long limit)])
       (mapv (fn [row] {:id (:id row) :subject (:subject row) :slug (:slug row)
                        :path (:path row) :state (row->state row)}))))

(defn due-cards-all
  "Fila do dia GLOBAL, interleaved entre matérias (round-robin na ordem das
   matérias): revisar em contexto alternado fortalece a discriminação —
   o interleaving explícito da Fase 2a. `limit` corta a fila."
  [^Instant now limit]
  (let [rows (->> (q ["select c.id, c.subject, c.slug, c.path, a.stability,
                              a.difficulty, a.reps, a.lapses, a.last_review,
                              a.due, a.interval_days
                       from cards c left join agenda a on a.card_id = c.id
                       where c.suspended=0 and (a.due is null or a.due <= ?)
                       order by a.due is null desc, a.due asc" (str now)])
                  (mapv (fn [row] {:id (:id row) :subject (:subject row)
                                   :slug (:slug row) :path (:path row)
                                   :state (row->state row)})))
        filas (vals (group-by :subject rows))] ; cada fila preserva a ordem due
    (loop [filas (vec filas) out []]
      (if (or (empty? filas) (>= (count out) limit))
        (vec (take limit out))
        (recur (into [] (keep #(not-empty (subvec % 1))) filas)
               (into out (keep first) filas))))))

(defn review!
  "Aplica FSRS ao card e persiste: agenda (upsert) + review_log (append).
   Devolve o estado novo."
  [card-id rating ^Instant now]
  (let [row   (q1 ["select * from agenda where card_id=?" card-id])
        state (row->state row)
        s'    (fsrs/review state rating now)]
    (jdbc/execute-one! (ds)
      ["insert into agenda (card_id, stability, difficulty, reps, lapses,
                            last_review, due, interval_days)
        values (?,?,?,?,?,?,?,?)
        on conflict(card_id) do update set
          stability=excluded.stability, difficulty=excluded.difficulty,
          reps=excluded.reps, lapses=excluded.lapses,
          last_review=excluded.last_review, due=excluded.due,
          interval_days=excluded.interval_days"
       card-id (:stability s') (:difficulty s') (:reps s') (:lapses s')
       (str (:last-review s')) (str (:due s')) (:interval-days s')])
    (jdbc/execute-one! (ds)
      ["insert into review_log (card_id, ts, rating, elapsed_days,
                                interval_days, stability, difficulty)
        values (?,?,?,?,?,?,?)"
       card-id (str now) (long (case rating (:again 1) 1 (:hard 2) 2
                                            (:good 3) 3 (:easy 4) 4))
       (:elapsed-days s') (:interval-days s') (:stability s') (:difficulty s')])
    s'))

(defn stats
  "Resumo do subject: total, due agora, revisões feitas."
  [subject ^Instant now]
  {:total (card-count subject)
   :due   (:n (q1 ["select count(*) as n from cards c
                    left join agenda a on a.card_id=c.id
                    where c.subject=? and c.suspended=0
                      and (a.due is null or a.due <= ?)" subject (str now)]))
   :reviews (:n (q1 ["select count(*) as n from review_log r
                      join cards c on c.id=r.card_id where c.subject=?" subject]))})

(defn stats-all
  "Resumo global (todas as matérias): total, due agora, revisões feitas."
  [^Instant now]
  {:total (:n (q1 ["select count(*) as n from cards where suspended=0"]))
   :due   (:n (q1 ["select count(*) as n from cards c
                    left join agenda a on a.card_id=c.id
                    where c.suspended=0 and (a.due is null or a.due <= ?)" (str now)]))
   :reviews (:n (q1 ["select count(*) as n from review_log"]))})
