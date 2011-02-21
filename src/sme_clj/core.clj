
(ns sme-clj.core
  "Structure mapping engine core functionality.

  Reimplements SME for the most part, but in nice Clojure instead of Common Lisp
  spaghetti. There are more objective differences in some areas, such as GMap
  merging and scoring. Also likely to be slower, as it has not been profiled or
  optimised at all." ;; TODO: expand with more detail on diffs
  (:require [clojure.set :as set]
            [clojure.contrib.set :as c.set]
            [clojure.contrib.combinatorics :as comb])
  (:use sme-clj.typedef
        sme-clj.ruledef))

;;;;
;;;; GENERATING MATCH HYPOTHESES
;;;;

;; Note: "mh" = match hypothesis

(defn apply-filter-rules
  "Apply :filter rules from a ruleset to the base and target expressions. Return
  a set of match hypotheses."
  [base-expr target-expr rules]
  (->
   (map (fn [rule]
          ;; map rule over every possible expression pairing
          (remove nil? (map #(rule (% 0) (% 1))
                            (for [bx base-expr, tx target-expr] [bx tx]))))
        (:filter rules))
   flatten
   set))

(defn apply-intern-rules
  "Apply :intern rules to a set of match hypotheses generated by :filter rules,
  and see if new MHs are created. For every new MH, also apply the :intern rules
  to it. Return the resulting set of MHs."
  [mhs ruleset]
  (let [rules (:intern ruleset)]
    (loop [todo   (seq mhs)             ; treat todo as seq stack
           result (set mhs)]            ; result as final mh set
      (if-let [mh (first todo)]
        ;; test this MH on all intern rules, adding new matches to todo list
        (let [matches (->> (map #(% mh) rules)
                           flatten (remove nil?) set)]
          (recur (concat (next todo) matches)
                 (set/union result matches)))
        ;; set of all MHs tested on intern rules
        result))))

(defn create-match-hypotheses
  "Apply rules from a ruleset to base and target to generate match hypotheses
  for the graphs."
  [base target rules]
  (-> (apply-filter-rules (:graph base) (:graph target) rules)
      (apply-intern-rules rules)))

;;;;
;;;; FORMING GMAPS
;;;;

;; The below proves useful when checking consistency and such.
(defn build-hypothesis-structure
  "Creates a map from MHs to their structural information. So retrieving the
  match hypotheses is done with 'keys, while the structural info can be accessed
  with 'vals or 'get."
  [mhs]
  (let [add-as (fn [m k mh] (update-in m [k (k mh)] set/union #{mh}))
        ;; cache of base/target expressions mapped to their mh
        smap   (reduce (fn [s mh] (-> s
                                      (add-as :base mh)
                                      (add-as :target mh)))
                       {:base {}, :target {}}
                       mhs)
        {bmap :base, tmap :target} smap]
    (reduce (fn build-mh-structure [structure mh]
              (assoc structure
                mh

                ;; initial emaps is just ourselves if we are one, for the rest
                ;; this will be filled later
                {:emaps
                 (if (is-expression? mh) #{} #{mh})

                 ;; nogood is every mh mapping same target or base
                 :nogood
                 (-> (set/union 
                      (get bmap (:base mh) #{})
                      (get tmap (:target mh)) #{})
                     (disj mh))         ; not nogood of ourselves

                 ;; our children are mhs that map our arguments (so children
                 ;; does not equal our entire set of descendants)
                 :children
                 (if (is-expression? mh)
                   (set
                    (mapcat (fn [b t]
                              (set/intersection (get bmap b #{})
                                                (get tmap t #{})))
                            (expression-args (:base mh))
                            (expression-args (:target mh))))
                   #{})
                 }))
            {}
            mhs)))

;; For each expression without emaps, recursively add the union of its
;; children's emaps and nogoods.
(defn propagate-from-emaps
  "Extends structural MH information of each expression without emaps by
  recursively adding the union of its children's emaps and nogoods to
  it. Essentially flows up the structural information."
  [mh-structure]
  (letfn [(propagate [mstr mh]
            (if (seq (:emaps (get mstr mh)))
              mstr
              (let [kids (:children (get mstr mh))
                    mstr-kids (reduce propagate mstr kids)
                    kids-struct (vals (select-keys mstr-kids kids))]
                (update-in mstr-kids
                           [mh]
                           #(merge-with set/union %1 %2)
                           {:emaps
                            (reduce set/union (map :emaps kids-struct))

                            :nogood
                            (reduce set/union (map :nogood kids-struct))}))))]
    (reduce propagate
            mh-structure
            (keys mh-structure))))

(defn consistent?
  "True if an MH is consistent, meaning none of its emaps are in its nogoods."
  ([{:keys [emaps nogood] :as mstr-entry}]
     (empty? (set/intersection emaps nogood)))
  ([mh mstr]
     (consistent? (get mstr mh))))

(defn find-roots
  "Returns only the root hypotheses, ie. those that are not children of any
  other hypothesis."
  [mh-structure]
  (let [all-children (reduce #(set/union %1 (:children %2))
                             #{}
                             (vals mh-structure))]
    (filter #(not (contains? all-children %)) (keys mh-structure))))

(defn collect-children
  "Returns a set of all descendants of a root."
  [root mh-structure]
  (letfn [(collect [mh]
            (if (is-emap? mh)
              [mh]
              (cons mh (mapcat collect
                               (:children (get mh-structure mh))))))]
    (set (collect root))))

(defn make-gmap
  "Returns a gmap with the root and all of its descendants."
  [root mh-structure]
  (make-GMap (collect-children root mh-structure)
             (merge {:roots #{root}}
                    ;; gmap's nogoods/emaps are those of its root(s)
                    (select-keys (get mh-structure root) [:nogood :emaps]))))

(defn compute-initial-gmaps
  "Given match hypothesis information, builds a set of initial gmaps. Returns a
  map with the :mh-structure and the :gmaps set."
  [mh-structure]
  (->> 
   (find-roots mh-structure)
   (reduce (fn form-gmap [gmaps root]
             (if (consistent? (get mh-structure root))
               (conj gmaps (make-gmap root mh-structure))
               (if-let [kids (seq (:children (get mh-structure root)))]
                 (set/union gmaps
                            (set (mapcat #(form-gmap #{} %) kids)))
                 gmaps)))
           #{})
   (hash-map :mh-structure mh-structure
             :gmaps)))

(defn gmaps-consistent?
  "Two gmaps are consistent if none of their elements are in the NoGood set of
  the other."
  [gm-a gm-b]
  (and (empty? (set/intersection (:mhs gm-a) (:nogood (:structure gm-b))))
       (empty? (set/intersection (:mhs gm-b) (:nogood (:structure gm-a))))))

(defn gmap-sets-consistent?
  "True if both collections of gmaps are fully consistent with each other."
  [coll-a coll-b]
  (every? true?
          (map (fn [gm-b]
                 (every? true? (map #(gmaps-consistent? gm-b %) coll-a)))
               coll-b)))

(defn gmap-set-internally-consistent?
  "True if the given set of gmaps is internally consistent."
  [gmap-set]
  (gmap-sets-consistent? gmap-set gmap-set))

;; NOTE: SME's second merge step seems rather complex compared to its benefits.
;; Its results will already be generated in a third step we will be performing
;; that is more exhaustive than SME performs at that point. Therefore step 2 is
;; unnecessary here and we skip it.


;; The below is a very naive implementation, performance-wise.
(defn combine-gmaps
  "Combine all gmaps in all maximal, consistent ways."
  [data]
  (let [consistent-sets
        (->>
         (comb/subsets (:gmaps data))
         (remove empty?)
         (filter gmap-set-internally-consistent?)
         (map set))]
    (->>
     (remove (fn [gms-a]
               (some (fn [gms-b]
                       (and (not= gms-a gms-b)
                            (c.set/subset? gms-a gms-b)))
                     consistent-sets))
             consistent-sets)
     (assoc data :gmaps))))

(defn merge-gmaps
  "Given a collection of sets of gmaps, merges the gmaps in each set into a
  single gmap."
  [data]
  (letfn [(gather-gm [{:keys [mhs structure] :as total} gm]
            (assoc total
              :mhs (set/union mhs (:mhs gm))
              :structure (merge-with set/union structure (:structure gm))))

          (reduce-to-gm [gm-set]
            (let [args (reduce gather-gm {:mhs #{} :structure {}} gm-set)]
              (make-GMap (:mhs args) (:structure args))))]
    (->>
     (map reduce-to-gm (:gmaps data))
     (assoc data :gmaps))))


(letfn [(round [n]
          (.setScale (bigdec n) 2 BigDecimal/ROUND_HALF_UP))]
 (defn emaps-equal?
   "Special equals function for entities that rounds floating point numbers to
   two decimals before comparing them, to avoid rounding errors affecting
   equality."
   [a b]
   (and (= (keys a) (keys b))
        (every? true?
                (map (fn [x y]
                       (if (and (number? x) (number? y))
                         (== (round x) (round y))
                         (= x y)))
                     (vals a)
                     (vals b))))))

;; Entities may have keys that are implementation details. Bind this var to a
;; seq of those keys to ignore them in emap matching.
(def unmatched-keys nil)

(defn matching-emaps
  "Returns seq of MHs that are emaps of which the entities are equal."
  [{:as gmap,
    :keys [mhs]}]
  (filter #(and (is-emap? %)
                (emaps-equal? (apply dissoc (:base %) unmatched-keys)
                              (apply dissoc (:target %) unmatched-keys)))
          mhs))

(defn score-gmap
  "Computes SES and emap scores for a gmap. The emap score is not in the
  original SME. It simply counts how many entities match in their content."
  [{:keys [mh-structure] :as data} gm]
  (letfn [(score-mh [mh depth]
            ;; simplified trickle-down SES
            (if-let [kids (seq (:children (get mh-structure mh)))]
              (reduce + depth (map #(score-mh % (inc depth)) kids))
              depth))]
    (assoc gm
      :score (reduce + (count (:mhs gm))
                     (map #(score-mh % 0) (:roots (:structure gm))))
      :emap-matches (count (matching-emaps gm)))))

;;; Inference generation below. Not used in TCG model.

(defn gmap-inferences
  "Generates maximal inferences for a given gmap, based on SME algorithm."
  [gmap {base :graph}]
  (let [mh-bases  (set (map :base (:mhs gmap)))
        unmatched (set/difference (set base) mh-bases)
        ancestors (set/select #(ancestor? mh-bases %) unmatched)]
    (set/difference (set (mapcat get-descendants ancestors))
                    mh-bases)))

(defn generate-inferences
  "Appends :inferences to gmaps in given data, for a given base graph."
  [data base]
  (->>
   (map #(assoc %
           :inferences (gmap-inferences % base))
        (:gmaps data))
   (assoc data :gmaps)))

;; On inference integration, recursive method:
;;
;; Starting at inference root:
;; 1.  If expr/pred exists as :base of an MH
;; 1.a then return :target of the MH
;; 1.b else return (cons pred (map f args))
;;
;; This will return a relation expression for in the target gmap. All new parts
;; of the inference expression (ie. the inferences in Falkenhainer's terms) will
;; be included in the new relation, with all base predicates that were mapped
;; replaced with their targets. That way, the inferences are linked up in the
;; target graph.


;; Note that the behaviour of the original SME that concerns the creation of
;; "skolem" entities when transfering inferences is not implemented.

(defn transfer-gmap-inferences
  "Attempt to transfer inferences to the target of the gmap."
  [{:as gmap,
    :keys [inferences mhs]}]
  ;; This exception setup is ugly, but is a simple and efficient way of aborting
  ;; the whole inference transferring process for this gmap. Monads would
  ;; perhaps work as well (or CPS).
  (try
   (let [pairs (zipmap (map :base mhs) (map :target mhs))
         transfer (fn transfer [expr]
                    (if-let [t (get pairs expr)]
                      t
                      (if (entity? expr)
                        (throw (RuntimeException.
                                "cannot infer entities"))
                        (cons (expression-functor expr)
                              (doall (map transfer (expression-args expr)))))))]
     (assoc gmap
       :transferred (doall (map transfer inferences))))
   (catch RuntimeException e
     gmap)))

(defn transfer-inferences
  [data]
  (->>
   (map transfer-gmap-inferences (:gmaps data))
   (assoc data :gmaps)))

(defn finalize-gmaps
  "Computes additional information about the gmaps we have found and stores it
  in the gmaps."
  [data base target]
  (->> 
   (:gmaps data)

   (map #(score-gmap data %))                            ; scores
   (map #(assoc % :mapping {:base base :target target})) ; what we mappped

   (assoc data :gmaps)))

(defn match
  "Attempts to find a structure mapping between base and target using an
  implementation of the SME algorithm. Returns a collection of GMaps, which
  represent analogical mappings."
  ([base target rules]
     (-> (create-match-hypotheses base target rules)
         build-hypothesis-structure
         propagate-from-emaps
         compute-initial-gmaps
         combine-gmaps
         merge-gmaps
         (finalize-gmaps base target)
         ;; Inference generation is not used in the model at this time.
         ;;(generate-inferences base)
         ;;transfer-inferences
         ))
  ([base target]
     (match base target literal-similarity)))



