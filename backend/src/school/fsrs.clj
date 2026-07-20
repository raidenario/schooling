(ns school.fsrs
  "Port do FSRS-5 (Free Spaced Repetition Scheduler) — funções PURAS.

   Variante long-term (sem learning steps intra-dia, como o scheduler
   long-term do ts-fsrs): toda revisão reagenda em DIAS. É o recorte certo
   para o `school review` diário; os pesos w17/w18 (same-day) ficam de fora.

   Referências: ts-fsrs e py-fsrs (FSRS-5, 19 pesos). Fórmulas:
   - R(t,S)   = (1 + FACTOR·t/S)^DECAY                 (retrievability)
   - I(r,S)   = (S/FACTOR)·(r^(1/DECAY) − 1)           (intervalo p/ retenção r)
   - S0(G)    = w[G−1]                                 (estabilidade inicial)
   - D0(G)    = w4 − e^(w5·(G−1)) + 1                  (dificuldade inicial)
   - D'       = D + (−w6·(G−3))·(10−D)/9, depois mean-reversion p/ D0(4) via w7
   - recall   : S' = S·(1 + e^w8·(11−D)·S^−w9·(e^(w10·(1−R))−1)·hard·easy)
   - forget   : S' = min(S, w11·D^−w12·((S+1)^w13 − 1)·e^(w14·(1−R)))

   Rating: 1/:again · 2/:hard · 3/:good · 4/:easy. Estado de card é um mapa
   {:stability :difficulty :reps :lapses :last-review :due :interval-days};
   card novo = estado nil. Tempo entra como java.time.Instant — nada de
   Date/now aqui dentro (determinismo é o teste)."
  (:import [java.time Duration Instant]))

(def default-w
  "Pesos default do FSRS-5 (ts-fsrs/py-fsrs)."
  [0.40255 1.18385 3.173 15.69105 7.1949 0.5345 1.4604 0.0046 1.54575
   0.1192 1.01925 1.9395 0.11 0.29605 2.2698 0.2315 2.9898 0.51655 0.6621])

(def default-params
  {:w default-w
   :retention 0.9          ; retenção-alvo: agenda quando R cai a 90%
   :max-interval-days 36500})

(def ^:private DECAY -0.5)
(def ^:private FACTOR (/ 19.0 81.0))

(defn- rating->int ^long [g]
  (long (case g
          (:again 1) 1
          (:hard 2)  2
          (:good 3)  3
          (:easy 4)  4)))

(defn- clamp ^double [^double x ^double lo ^double hi]
  (-> x (max lo) (min hi)))

;; ---------------------------------------------------------------------------
;; fórmulas
;; ---------------------------------------------------------------------------

(defn retrievability
  "Probabilidade de recall após `elapsed-days` com estabilidade `s`."
  ^double [^double elapsed-days ^double s]
  (Math/pow (+ 1.0 (* FACTOR (/ (max 0.0 elapsed-days) s))) DECAY))

(defn interval-days
  "Dias até R cair à retenção-alvo, dado `s`. Mínimo 1, teto :max-interval-days."
  (^double [^double s] (interval-days s default-params))
  (^double [^double s {:keys [retention max-interval-days]}]
   (-> (* (/ s FACTOR) (- (Math/pow retention (/ 1.0 DECAY)) 1.0))
       Math/round double
       (clamp 1.0 (double max-interval-days)))))

(defn- init-stability ^double [w g]
  (max 0.1 (double (nth w (dec (rating->int g))))))

(defn- init-difficulty ^double [w g]
  (clamp (+ (- (nth w 4) (Math/exp (* (nth w 5) (dec (rating->int g))))) 1.0)
         1.0 10.0))

(defn- next-difficulty ^double [w ^double d g]
  (let [delta (* (- (nth w 6)) (- (rating->int g) 3))
        d'    (+ d (* delta (/ (- 10.0 d) 9.0)))          ; linear damping (FSRS-5)
        d0-4  (init-difficulty w 4)]
    (clamp (+ (* (nth w 7) d0-4) (* (- 1.0 (nth w 7)) d')) 1.0 10.0)))

(defn- next-recall-stability
  ;; sem hints primitivos (nem no retorno): fn primitiva aceita no máx 4 args
  [w d s r g]
  (let [d    (double d) s (double s) r (double r)
        hard (if (= 2 (rating->int g)) (nth w 15) 1.0)
        easy (if (= 4 (rating->int g)) (nth w 16) 1.0)]
    (* s (+ 1.0 (* (Math/exp (nth w 8))
                   (- 11.0 d)
                   (Math/pow s (- (nth w 9)))
                   (- (Math/exp (* (nth w 10) (- 1.0 r))) 1.0)
                   hard easy)))))

(defn- next-forget-stability ^double [w ^double d ^double s ^double r]
  (min s
       (* (nth w 11)
          (Math/pow d (- (nth w 12)))
          (- (Math/pow (+ s 1.0) (nth w 13)) 1.0)
          (Math/exp (* (nth w 14) (- 1.0 r))))))

;; ---------------------------------------------------------------------------
;; a transição de estado
;; ---------------------------------------------------------------------------

(defn- elapsed-days ^double [state ^Instant now]
  (if-let [^Instant lr (:last-review state)]
    (max 0.0 (/ (.toMillis (Duration/between lr now)) 86400000.0))
    0.0))

(defn review
  "Aplica uma revisão: (estado-anterior|nil, rating, Instant[, params]) →
   estado novo {:stability :difficulty :reps :lapses :last-review :due
   :interval-days :elapsed-days :retrievability}. Estado nil = primeira vez."
  ([state rating ^Instant now] (review state rating now default-params))
  ([state rating ^Instant now {:keys [w] :as params}]
   (let [g       (rating->int rating)
         again?  (= 1 g)
         first?  (nil? (:stability state))
         t       (elapsed-days state now)
         r       (if first? 1.0 (retrievability t (:stability state)))
         s'      (cond
                   first? (init-stability w g)
                   again? (next-forget-stability w (:difficulty state) (:stability state) r)
                   :else  (next-recall-stability w (:difficulty state) (:stability state) r g))
         d'      (if first?
                   (init-difficulty w g)
                   (next-difficulty w (:difficulty state) g))
         ivl     (interval-days s' params)]
     {:stability      s'
      :difficulty     d'
      :reps           (inc (long (:reps state 0)))
      :lapses         (cond-> (long (:lapses state 0)) (and again? (not first?)) inc)
      :last-review    now
      :due            (.plus now (Duration/ofMillis (long (* ivl 86400000))))
      :interval-days  (long ivl)
      :elapsed-days   t
      :retrievability r})))

(defn due?
  "O card está vencido em `now`? Card novo (estado nil) está sempre due."
  [state ^Instant now]
  (or (nil? (:due state))
      (not (.isBefore now ^Instant (:due state)))))
