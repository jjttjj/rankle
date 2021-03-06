(ns com.semperos.rankle
  (:refer-clojure :exclude [= + - * / > < >= <= count drop not not= take])
  (:require [clojure.core.matrix :as mx]
            [clojure.core.memoize :as memo]
            [clojure.pprint :refer [cl-format]]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [com.semperos.rankle.util
             :refer [cond-table defalias print-table]
             :as util])
  (:import [java.io Writer]))

(defalias c* clojure.core/*)
(defalias c+ clojure.core/+)
(defalias c- clojure.core/-)
(defalias c< clojure.core/<)
(defalias c<= clojure.core/<=)
(defalias c= clojure.core/=)
(defalias c> clojure.core/>)
(defalias c>= clojure.core/>=)
(defalias ccount clojure.core/count)
(defalias cdiv clojure.core//)
(defalias cdrop clojure.core/drop)
(defalias cnot clojure.core/not)
(defalias ctake clojure.core/take)

;;;;;;;;;;;;;;;;;;
;; Shape & Rank ;;
;;;;;;;;;;;;;;;;;;

(defn ^{:rank ##Inf}
  count
  [coll]
  (ccount coll))

(defn shape
  [x]
  (or (mx/shape x) []))

(defn reshape
  [shape coll]
  {:pre [(every? number? shape)]}
  (mx/reshape coll shape))

(defn rank
  ([x]
   (if (var? x)
     ((comp :rank meta) x)
     (count (shape x))))
  ([f r]
   (fn rankle
     ([arg0]
      (if (c<= (rank arg0) r)
        (f arg0)
        (map rankle arg0)))
     ([arg0 arg1]
      (if (c<= (rank arg1) r)
        (f arg0 arg1)
        (map #(f arg0 %) arg1))))))

(defn check-ragged
  [x y]
  (let [shape-x (shape x)
        shape-y (shape y)
        cx (count shape-x)
        cy (count shape-y)
        min' (min cx cy)]
    (when-not (c= (ctake min' shape-x)
                  (ctake min' shape-y))
      (throw (ex-info (str "The shape of the lower-ranked argument must "
                           "match the common frame of the shape of the higher "
                           "ranked argument , but x had shape " shape-x
                           "and y had shape " shape-y)
                      {:x shape-x
                       :y shape-y})))))

;;;;;;;;;;;;;;;;;;;;
;; Implementation ;;
;;;;;;;;;;;;;;;;;;;;

(defprotocol IIndexable
  (index-of [this x] "Return 0-based index of `x` in `this` or the length of `this` if `x` is not present."))

(extend-protocol IIndexable
  clojure.lang.Indexed
  (index-of [this x]
    (let [idx (.indexOf this x)]
      (if (c= idx -1)
        (count this)
        idx)))

  String
  (index-of [this x]
    (let [idx (.indexOf this (str x))]
      (if (c= idx -1)
        (count this)
        idx)))

  Object
  (index-of [this x]
    (let [idx (.indexOf this x)]
      (if (c= idx -1)
        (count this)
        idx))))

(def one? (partial c= 1))

(defalias head first)
(defalias tail rest)
(def behead (partial cdrop 1))
(defalias curtail butlast)

;; TODO Aliasing here as reminder to consider filling,
;;      since take fills when over-taking.
(defalias take clojure.core/take)
(defalias drop clojure.core/drop)

(declare *)
(defn in
  "J's i."
  ([n]
   (cond
     (number? n) (range n)
     (and (vector? n) (every? number? n)) (reshape n (range (reduce * 1 n)))
     :else (throw (IllegalArgumentException. "For 1-arity in, you must supply either a number or a vector of numbers."))))
  ([x y]
   (if (seqable? y)
     (map (partial in x) y)
     (index-of x y))))

(defn indices*
  [indices y]
  (let [cnt (count y)
        idx (in y 1)]
    (if (c= idx cnt)
      indices
      (recur (conj indices (c+ (peek indices) (inc idx)))
             (cdrop (inc idx) y)))))

;; TODO Handle higher ranked y's
(defn indices
  "J's I."
  ([y]
   (let [cnt (count y)
         idx (in y 1)]
     (if (c= idx cnt)
       []
       (indices* [idx] (cdrop (inc idx) y))))))

(defn- wrap [y] (if (seqable? y) y [y]))

(defn over
  "J's /

  Monadic - u Insert y
  Dyadic  - x u Table y"
  ([f]
   (with-meta
     (fn overly
       ([coll]
        (reduce (fn [acc x]
                  (f acc x))
                coll))
       ([x y]
        (let [res (for [x (wrap x)]
                    (for [y (wrap y)]
                      (f x y)))]
          (if (and (seqable? x)
                   (seqable? y))
            res
            (first res))))
       ;; TODO Consider if this is right place, or if belongs in `over` itself.
       ([init-kw init coll]
        (reduce (fn [acc x]
                  (f acc x))
                init
                coll)))
     {::over f}))
  ([f init]
   (with-meta
     (fn overly
       ([coll]
        (reduce (fn [acc x]
                  (f acc x))
                init
                coll))
       ([x y]
        (let [res (for [x (wrap x)]
                    (for [y (wrap y)]
                      (f x y)))]
          (if (and (seqable? x)
                   (seqable? y))
            res
            (first res)))))
     {::over f
      ::init init})))

(declare +)
(defn prefixes [coll]
  (if (empty? coll)
    coll
    (map #(ctake % coll) (+ 1 (in (count coll))))))

(defn prefix
  "J's \\ adverb.

  Monadic - u Prefix y
  (TODO) Dyadic  - x u Infix y"
  [f]
  (fn prefixly
    ([coll]
     (if-let [f' (::over (meta f))]
       (if-let [init (::init (meta f))]
         (reductions f' init coll)
         (reductions f' coll))
       (map f (prefixes coll))))))

;; TODO Consider what from clojure.core.matrix could be used here.
;;      N.B. The implementation of mx/add, for example, is not
;;      equivalent to this, because this check-ragged allows for
;;      operations on data of different shape as long as the common
;;      frame is equal.
(defn +
  ([] 0)
  ([x] x)
  ([x y]
   (check-ragged x y)
   (cond
     (and (seqable? x) (seqable? y)) (map + x y)
     (seqable? x) (map (rank (partial c+ y) 0) x)
     (seqable? y) (map (rank (partial c+ x) 0) y)
     :else (c+ x y)))
  ([x y & more]
   (reduce + (+ x y) more)))

(defn *
  ([] 1)
  ([x] x)
  ([x y]
   (check-ragged x y)
   (cond
     (and (seqable? x) (seqable? y)) (map * x y)
     (seqable? x) (map (rank (partial c* y) 0) x)
     (seqable? y) (map (rank (partial c* x) 0) y)
     :else (c* x y)))
  ([x y & more]
   (reduce * (* x y) more)))

(defn -
  ([x] (if (seqable? x)
         ((rank c- 0) x)
         (c- x)))
  ([x y]
   (check-ragged x y)
   (cond
     (and (seqable? x) (seqable? y)) (map - x y)
     (seqable? x) (map (rank #(c- % y) 0) x)
     (seqable? y) (map (rank (partial c- x) 0) y)
     :else (c- x y)))
  ([x y & more]
   (reduce - (- x y) more)))

(defn /
  ([x] (if (seqable? x)
         ((rank (partial cdiv 1) 0) x)
         (cdiv 1 x)))
  ([x y]
   (check-ragged x y)
   (cond
     (and (seqable? x) (seqable? y)) (map / x y)
     (seqable? x) (map (rank #(cdiv % y) 0) x)
     (seqable? y) (map (rank (partial cdiv x) 0) y)
     :else (cdiv x y)))
  ([x y & more]
   (reduce / (/ x y) more)))

(defn =
  ([x] (if (seqable? x)
         ((rank = 0) x)
         1))
  ([x y]
   (check-ragged x y)
   (cond
     (and (seqable? x) (seqable? y)) (map = x y)
     (seqable? x) (map (rank #(= % y) 0) x)
     (seqable? y) (map (rank (partial = x) 0) y)
     :else (if (c= x y) 1 0)))
  ([x y & more]
   (reduce = (= x y) more)))

(defn >
  ([x] (if (seqable? x)
         ((rank > 0) x)
         1))
  ([x y]
   (check-ragged x y)
   (cond
     (and (seqable? x) (seqable? y)) (map > x y)
     (seqable? x) (map (rank #(> % y) 0) x)
     (seqable? y) (map (rank (partial > x) 0) y)
     :else (if (c> x y) 1 0)))
  ([x y & more]
   (reduce > (> x y) more)))

(defn >=
  ([x] (if (seqable? x)
         ((rank >= 0) x)
         1))
  ([x y]
   (check-ragged x y)
   (cond
     (and (seqable? x) (seqable? y)) (map >= x y)
     (seqable? x) (map (rank #(>= % y) 0) x)
     (seqable? y) (map (rank (partial >= x) 0) y)
     :else (if (c>= x y) 1 0)))
  ([x y & more]
   (reduce >= (>= x y) more)))

(defn <
  ([x] (if (seqable? x)
         ((rank < 0) x)
         1))
  ([x y]
   (check-ragged x y)
   (cond
     (and (seqable? x) (seqable? y)) (map < x y)
     (seqable? x) (map (rank #(< % y) 0) x)
     (seqable? y) (map (rank (partial < x) 0) y)
     :else (if (c< x y) 1 0)))
  ([x y & more]
   (reduce < (< x y) more)))

(defn <=
  ([x] (if (seqable? x)
         ((rank <= 0) x)
         1))
  ([x y]
   (check-ragged x y)
   (cond
     (and (seqable? x) (seqable? y)) (map <= x y)
     (seqable? x) (map (rank #(<= % y) 0) x)
     (seqable? y) (map (rank (partial <= x) 0) y)
     :else (if (c<= x y) 1 0)))
  ([x y & more]
   (reduce <= (<= x y) more)))

;; TODO base, antibase https://code.jsoftware.com/wiki/Vocabulary/numberdot

(declare from)
(defn ?
  "J's ?.

  Monadic - Roll y
  Dyadic  - x Deal y"
  ([y]
   (if (seqable? y)
     (map ? y)
     (rand-int y)))
  ([x y]
   (let [ys (in y)]
     (repeatedly x #(from (rand-int y) ys)))))

(def alphabet
  (mapv char (range 256)))

(defn copy
  "Creates a new collection in which each integer in `x` controls how many
  times the corresponding item of `y` appears.

  If `y` is a map, `x` should be a list of keys to copy into a new map."
  [x y]
  (let [ret (cond
              (map? y)
              (select-keys y x)

              (seqable? x)
              (if (c= (count x) 1)
                (let [n (first x)]
                  (mapcat #(repeat n %) y))
                (do (check-ragged x y)
                    (mapcat #(repeat %1 %2) x y)))

              :else ;; x is atomic value
              (mapcat #(repeat x %) y))]
    (if (string? y)
      (apply str ret)
      ret)))

(defn fork
  "J's fork, verb composition.

  Classic APL example, arithmetic mean:

  +/ % #

  Which divides the sum of the argument's items by their count.

  We'll see how far we can get with just forks, as hooks are a
  specialization of forks.

  TODO Consider order of arguments. Current matches Lisper's intuition.
  TODO Consider the capped fork use-case for `f` called monadically."
  [g f h]
  (fn forky
    ([y]
     (g (f y) (h y)))
    ([x y]
     (g (f x y) (h x y)))))

(defn from*
  [x y]
  (if (seqable? x)
    (map #(from* % y) x)
    (nth y x)))

(defn from
  "J's left-curly"
  [x y]
  (let [x-seqable? (seqable? x)
        ret (if x-seqable?
              (map #(from* % y) x)
              (nth y x))]
    (if (and x-seqable? (string? y))
      (apply str ret)
      ret)))

(defn intervals
  "J's ;.

  Monadic - u Self Intervals x
  Dyadic  - x u Intervals y

  See https://code.jsoftware.com/wiki/Vocabulary/semidot1"
  ([y]
   ;; y=: 'alpha bravo charlie'
   ;; (<;._1) ' ',y
   ;;  +-----+-----+-------+
   ;;  |alpha|bravo|charlie|
   ;;  +-----+-----+-------+
   )
  ([x y]
   (let [idxs (indices x)
         lidx (volatile! 0)]
     (map (fn [idx]
            (let [ret (from (range @lidx idx) y)]
              (vreset! lidx idx)
              ret))
          (concat (rest idxs) [(count y)])))))

(defn not
  [y]
  (if (seqable? y)
    (map (rank not 0) y)
    (- 1 y)))

(def not= (comp not =))

(defn nub-sieve
  [y]
  (let [seen (volatile! #{})]
    (map (fn [item]
           (if (@seen item)
             0
             (do
               (vswap! seen conj item)
               1)))
         y)))

;; TODO Consider fill
(defn ravel
  "J's ,

  Monadic - Ravel y
  Dyadic  - x Append y

  WARNING Presently this does _not_ fill arrays, so ragged collections
  will remain so."
  ([y]
   (flatten y))
  ([x y]
   (cond
     (and (seqable? x) (seqable? y)) (concat x y)
     (seqable? x) (ravel x [y])
     (seqable? y) (ravel [x] y)
     :else (ravel [x] [y]))))

(defn reflex
  "J's ~

  Monadic - y u Reflex y
  Dyadic  - y u Reflex x"
  [f]
  (fn
    ([y] (f y y))
    ([x y] (f y x))))

(defn rot
  "J's dyadic |.

  Monadic - Reverse y
  Dyadic  - x Rotate y"
  ([y]
   (let [ret (reverse y)]
     (if (string? y)
       (apply str ret)
       ret)))
  ([x y]
   (if (string? y)
     (apply str (mx/rotate (seq y) [x]))
     (mx/rotate y [x]))))

;; TODO sorting/grading
#_(defn asc
  "J's /:

  Monadic - Grade Up y
  Dyadic  - x Sort Up y

  Common idiom is to use `reflex` to sort:

  ((reflex asc) [3 6 2 4]) ;=> [2 3 4 6]"
  ([y]
   (let [sorted (sort y)]
     (loop [indices [] items sorted]
       (if-let [item (first items)]
         (index-of ))
       (index-of y))))
  ([x y]
   (let [ret (if (c= x y)
               (sort x)
               (from (in y (sort y)) x))]
     (if (string? y)
       (apply str ret)
       ret))))

(defn tally
  "J's #"
  ([y]
   (count y))
  ([x y]
   (copy x y)))

(defn unicode
  [y]
  (if (seqable? y)
    (map unicode y)
    (first (Character/toChars y))))

;;;;;;;;;;;;;;
;; Printing ;;
;;;;;;;;;;;;;;
(defn print-aligned
  ([rows]
   (if-not (seqable? (first rows))
     (println (str/join " " rows))
     (print-aligned (util/largest rows) rows)))
  ([largest rows]
   (doseq [xs rows]
     (if-not (seqable? (first xs))
       (doseq [x xs]
         (print (format (str "%" (inc largest) "s") x)))
       (print-aligned largest xs))
     (println))))

(defalias pp print-aligned)

(defn- type-name
  [x]
  (let [raw-name (.getName (type x))
        sym (symbol
             (cond
               (str/starts-with? raw-name "clojure.lang.") (subs raw-name 13)
               (str/starts-with? raw-name "java.lang.")    (subs raw-name 10)
               :else raw-name))]
    (cond
      (symbol? x)
      (try
        (type-name (resolve x))
        (catch Throwable t
          sym))

      (var? x)
      (type-name @x)

      (fn? x)
      'fn

      :else sym)))

(defn print-forms
  [forms walker]
  (let [forms (if (string? forms)
                (read-string forms)
                forms)
        pos (atom 0)
        res (atom {})]
    (walker
     (fn [x]
       (swap! pos inc)
       (swap! res assoc [@pos (type-name x)] x)
       x)
     forms)
    (print-table [(into (sorted-map) @res)])))

(defn forms-top-down
  "Compare with J's facilities for showing the parsed output as boxed words, for
  example:

     ;: '<;._1'
  +-+--+--+
  |<|;.|_1|
  +-+--+--+ "
  [forms]
  (print-forms forms walk/prewalk))

(defn forms-bottom-up
  [forms]
  (print-forms forms walk/postwalk))

(defprotocol IAmOrderable
  (zero [this] "Return the zero item of an orderable collection of values.")
  (succ [this] "Return the this + 1 item of an orderable collection of values.")
  (pred [this] "Return the this - 1 item of an orderable collection of values."))

(extend-protocol IAmOrderable
  Number
  (zero [this] 0)
  (succ [this] (inc this))

  Character
  (zero [this] (char 0))
  (succ [this] ((comp char inc int) this))

  String
  (zero [this] "")
  (succ [this]
    (if-let [l (last this)]
      (str this (succ l))
      "a"))
  (pred [this]
    (let [length (count this)]
      (if (zero? length)
        ""
        (subs this 0 (dec (count this)))))))

;; TODO Support step argument in addition to start, end
(defn value-range
  "Both `start` and `end` are inclusive. Expects `IAmOrderable` values."
  ([start] (iterate succ start))
  ([start end]
   ;; TODO Lazy
   (loop [current start idx 0 res [start]]
     (if (or (c= current end)
             (c= idx end))
       res
       (let [nxt (succ current)
             nxt-idx (inc idx)]
         (recur nxt
                nxt-idx
                (conj res nxt)))))))

(defn memo-table
  "Table the given `f` over the given `domains` which should be ranges
  of values for each argument to `f`, memoizing the results.

  You can craft your function's domain by hand or reify the
  `IAmOrderable` protocol if your data is amenable to ordering.

  Infinite ranges are acceptable but are cached only up to `limit`.

  Example:

  (def fahrenheit->celsius
       (memo-table (fn [temp] (float (* (- temp 32) (/ 5 9))))
                   (map vector (range 0 500))))"
  ([f domain] (memo-table f domain 1000))
  ([f domain limit]
   (let [memoized (memo/lru f :lru/threshold limit)]
     (doseq [arg (ctake limit domain)]
       (apply f arg))
     memoized)))

(defn subs'
  "Version of subs that is 2-arity, for use with fn-table"
  [s [start end]]
  (let [cnt (count s)
        end (or end cnt)]
    (subs s start (if (> end cnt)
                    cnt
                    end))))

(defn maybe-name
  [x]
  (or (:name (meta x))
      (str x)))

(defn print-fn-table
  "Render a table of arguments and return values for `f` as a table. The
  function `f` is assumed to take two arguments.

  Examples:
  (print-fn-table #'+ (range -5 5) (range -5 5))
  (print-fn-table #'- (range -5 5) (range -5 5))
  (print-fn-table #'* (range -5 5) (range -5 5))
  (defn safe-div [a b] (if (zero? b) (or (and (pos? a ) ##Inf) ##-Inf) (/ a b)))
  (print-fn-table #'safe-div (range -5 5) (range -5 5))
  "
  [f args-first args-second]
  (let [fn-name (maybe-name f)]
    (let [ks (cons fn-name (map maybe-name args-second))
          rows (map (fn [arg-first]
                      (let [arg-first-name (maybe-name arg-first)]
                        (reduce
                         (fn [acc arg-second]
                           (let [ret (f arg-first arg-second)
                                 ret (if (instance? clojure.lang.LazySeq ret)
                                       (into [] ret)
                                       ret)
                                 arg-second-name (maybe-name arg-second)]
                             (-> acc
                                 (assoc arg-second-name ret)
                                 (assoc fn-name arg-first-name))))
                         {}
                         args-second)))
                    args-first)]
      (print-table ks rows))))

(comment
  (copy [1 0 1] ['a 'b 'c])
  (copy (map (partial * 2) [1 0 1]) ['a 'b 'c])
  (copy [:alpha :gamma] {:alpha "Alpha" :beta "Beta" :gamma "Gamma"})
  )

(defn upper
  [x]
  (cond
    (string? x)
    (str/upper-case x)

    (coll? x)
    (map upper x)

    :else ::error))
