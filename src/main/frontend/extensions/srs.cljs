(ns frontend.extensions.srs
  (:require [frontend.template :as template]
            [frontend.db.query-dsl :as query-dsl]
            [frontend.db.query-react :as react]
            [frontend.util :as util]
            [frontend.util.property :as property]
            [frontend.util.persist-var :as persist-var]
            [frontend.db :as db]
            [frontend.state :as state]
            [frontend.handler.editor :as editor-handler]
            [frontend.components.block :as component-block]
            [frontend.components.macro :as component-macro]
            [frontend.components.svg :as svg]
            [frontend.ui :as ui]
            [frontend.date :as date]
            [frontend.commands :as commands]
            [cljs-time.core :as t]
            [cljs-time.local :as tl]
            [cljs-time.coerce :as tc]
            [clojure.string :as string]
            [rum.core :as rum]))

;;; ================================================================
;;; Commentary
;;; - One block with tag "#card" or "[[card]]" is treated as a card.
;;; - {{cloze content}} show as "[...]" when reviewing cards

;;; ================================================================
;;; const & vars

(def card-hash-tag "card")

(def card-last-interval-property        :card-last-interval)
(def card-repeats-property              :card-repeats)
(def card-last-reviewed-property        :card-last-reviewed)
(def card-next-schedule-property        :card-next-schedule)
(def card-last-easiness-factor-property :card-ease-factor)
(def card-last-score-property           :card-last-score)

(def default-card-properties-map {card-last-interval-property -1
                                  card-repeats-property 0
                                  card-last-easiness-factor-property 2.5})

(def cloze-macro-name
  "cloze syntax: {{cloze: ...}}"
  "cloze")

(def query-macro-name
  "{{card-query ...}}"
  "card-query")

(def learning-fraction
  "any number between 0 and 1 (the greater it is the faster the changes of the OF matrix)"
  0.5)

(def of-matrix (persist-var/persist-var nil "srs-of-matrix"))

;;; ================================================================
;;; utils

(defn- get-block-card-properties
  [block]
  (when-let [properties (:block/properties block)]
    (merge
     default-card-properties-map
     (select-keys properties  [card-last-interval-property
                               card-repeats-property
                               card-last-reviewed-property
                               card-next-schedule-property
                               card-last-easiness-factor-property
                               card-last-score-property]))))

(defn- save-block-card-properties!
  [block props]
  (editor-handler/save-block-if-changed!
   block
   (property/insert-properties (:block/format block) (:block/content block) props)
   {:force? true}))

(defn- reset-block-card-properties!
  [block]
  (save-block-card-properties! block {card-last-interval-property -1
                                      card-repeats-property 0
                                      card-last-easiness-factor-property 2.5
                                      card-last-reviewed-property "nil"
                                      card-next-schedule-property "nil"
                                      card-last-score-property "nil"}))


;;; used by other ns


(defn card-block?
  [block]
  (let [card-entity (db/entity [:block/name card-hash-tag])
        refs (into #{} (:block/refs block))]
    (contains? refs card-entity)))

(declare get-root-block)
(defn- card-group-by-repeat [cards]
  (let [groups (group-by
                #(get (get-block-card-properties (get-root-block %)) card-repeats-property)
                cards)]
    groups))

;;; ================================================================
;;; sr algorithm (sm-5)
;;; https://www.supermemo.com/zh/archives1990-2015/english/ol/sm5

(defn- fix-2f
  [n]
  (/ (Math/round (* 100 n)) 100))

(defn- get-of [of-matrix n ef]
  (or (get-in of-matrix [n ef])
      (if (<= n 1)
        4
        ef)))

(defn- set-of [of-matrix n ef of]
  (->>
   (fix-2f of)
   (assoc-in of-matrix [n ef])))

(defn- interval
  [n ef of-matrix]
  (if (<= n 1)
    (get-of of-matrix 1 ef)
    (* (get-of of-matrix n ef)
       (interval (- n 1) ef of-matrix))))

(defn- next-ef
  [ef quality]
  (let [ef* (+ ef (- 0.1 (* (- 5 quality) (+ 0.08 (* 0.02 (- 5 quality))))))]
    (if (< ef* 1.3) 1.3 ef*)))

(defn- next-of-matrix
  [of-matrix n quality fraction ef]
  (let [of (get-of of-matrix n ef)
        of* (* of (+ 0.72 (* quality 0.07)))
        of** (+ (* (- 1 fraction) of) (* of* fraction))]
    (set-of of-matrix n ef of**)))

(defn next-interval
  "return [next-interval repeats next-ef of-matrix]"
  [last-interval repeats ef quality of-matrix]
  (assert (and (<= quality 5) (>= quality 0)))
  (let [ef (or ef 2.5)
        last-interval (if (or (nil? last-interval) (<= last-interval 0)) 1 last-interval)
        next-ef (next-ef ef quality)
        next-of-matrix (next-of-matrix of-matrix repeats quality learning-fraction ef)
        next-interval (interval repeats next-ef next-of-matrix)]

    (if (< quality 3)
      ;; If the quality response was lower than 3
      ;; then start repetitions for the item from
      ;; the beginning without changing the E-Factor
      [-1 1 ef next-of-matrix]
      [(fix-2f next-interval) (+ 1 repeats) (fix-2f next-ef) next-of-matrix])))


;;; ================================================================
;;; card protocol


(defprotocol ICard
  (get-root-block [this]))

(defprotocol ICardShow
  ;; return {:value blocks :next-phase next-phase}
  (show-cycle [this phase])

  (show-cycle-config [this phase]))


;;; ================================================================
;;; card impl


(deftype Sided-Cloze-Card [block]
  ICard
  (get-root-block [this] (db/pull [:block/uuid (:block/uuid block)]))
  ICardShow
  (show-cycle [this phase]
    (case phase
      1
      (let [blocks (db/get-block-and-children (state/get-current-repo) (:block/uuid block))
            blocks-count (count blocks)]
        {:value [block] :next-phase (if (> blocks-count 1) 2 3)})
      2
      {:value (db/get-block-and-children (state/get-current-repo) (:block/uuid block)) :next-phase 3}
      3
      {:value (db/get-block-and-children (state/get-current-repo) (:block/uuid block)) :next-phase 1}))

  (show-cycle-config [this phase]
    (case phase
      1
      {:cloze true}
      2
      {:cloze true}
      3
      {})))

(defn- ->card [block]
  {:pre [(map? block)]}
  (->Sided-Cloze-Card block))

;;; ================================================================
;;;

(defn- query
  "Use same syntax as frontend.db.query-dsl.
  Add an extra condition: block's :block/refs contains `#card or [[card]]'"
  [repo query-string]
  (when (string? query-string)
    (let [query-string (template/resolve-dynamic-template! query-string)]
      (when-not (string/blank? query-string)
        (let [{:keys [query sort-by blocks?] :as result} (query-dsl/parse repo query-string)]
          (when query
            (let [query* (concat `[[~'?b :block/refs [:block/name ~card-hash-tag]]]
                                 (if (coll? (first query))
                                   query
                                   [query]))]
              (when-let [query** (query-dsl/query-wrapper query* blocks?)]
                (react/react-query repo
                                   {:query query**}
                                   (if sort-by
                                     {:transform-fn sort-by}))))))))))

(defn- query-scheduled
  "Return blocks scheduled to 'time' or before"
  [repo {query-string :query-string query-result :query-result} time]
  (when-let [*blocks (or query-result (and query-string (query repo query-string)))]
    (when-let [blocks @*blocks]
      (->>
       (flatten blocks)
       (filterv (fn [b]
                  (let [props (:block/properties b)
                        next-sched (get props card-next-schedule-property)
                        next-sched* (tc/from-string next-sched)
                        repeats (get props card-repeats-property)]
                    (or (nil? repeats)
                        (< repeats 1)
                        (nil? next-sched)
                        (nil? next-sched*)
                        (t/before? next-sched* time)))))))))



;;; ================================================================
;;; operations


(defn- get-next-interval
  [card score]
  {:pre [(and (<= score 5) (>= score 0))
         (satisfies? ICard card)]}
  (let [block (.-block card)
        props (get-block-card-properties block)
        last-interval (or (util/safe-parse-float (get props card-last-interval-property)) 0)
        repeats (or (util/safe-parse-int (get props card-repeats-property)) 0)
        last-ef (or (util/safe-parse-float (get props card-last-easiness-factor-property)) 2.5)]
    (let [[next-interval next-repeats next-ef of-matrix*]
          (next-interval last-interval repeats last-ef score @of-matrix)
          next-interval* (if (< next-interval 0) 0 next-interval)
          next-schedule (tc/to-string (t/plus (tl/local-now) (t/hours (* 24 next-interval*))))
          now (tc/to-string (tl/local-now))]
      {:next-of-matrix of-matrix*
       card-last-interval-property next-interval
       card-repeats-property next-repeats
       card-last-easiness-factor-property next-ef
       card-next-schedule-property next-schedule
       card-last-reviewed-property now
       card-last-score-property score})))

(defn- operation-score!
  [card score]
  {:pre [(and (<= score 5) (>= score 0))
         (satisfies? ICard card)]}
  (let [block (.-block card)
        result (get-next-interval card score)
        next-of-matrix (:next-of-matrix result)]
    (reset! of-matrix next-of-matrix)
    (save-block-card-properties! (db/pull [:block/uuid (:block/uuid block)])
                                 (select-keys result
                                              [card-last-interval-property
                                               card-repeats-property
                                               card-last-easiness-factor-property
                                               card-next-schedule-property
                                               card-last-reviewed-property
                                               card-last-score-property]))))

(defn- operation-reset!
  [card]
  {:pre [(satisfies? ICard card)]}
  (let [block (.-block card)]
    (reset-block-card-properties! (db/pull [:block/uuid (:block/uuid block)]))))

(defn- operation-card-info-summary!
  [review-records review-cards card-query-block]
  (let [review-count (count (flatten (vals review-records)))
        review-cards-count (count review-cards)
        score-5-count (count (get review-records 5))
        score-4-count (count (get review-records 4))
        score-3-count (count (get review-records 3))
        score-2-count (count (get review-records 2))
        score-1-count (count (get review-records 1))
        score-0-count (count (get review-records 0))
        skip-count (count (get review-records "skip"))]
    (editor-handler/paste-block-tree-after-target
     (:db/id card-query-block) false
     [{:content (util/format "Summary: %d items, %d review counts [[%s]]"
                             review-cards-count review-count (date/today))
       :children [{:content
                   (util/format "Remembered:   %d (%d%%)" score-5-count (* 100 (/ score-5-count review-count)))}
                  {:content
                   (util/format "Forgotten :   %d (%d%%)" score-1-count (* 100 (/ score-1-count review-count)))}]}]
     (:block/format card-query-block))))

;;; ================================================================
;;; UI

(defn- score-help-info [days-3 days-4 days-5]
  (ui/tippy {:html [:div
                    [:p.text-sm "0-2: you have forgotten this card."]
                    [:p.text-sm "3-5: you remember this card."]
                    [:p.text-sm "0: completely forgot."]
                    [:p.text-sm "1: it still takes a while to recall even after seeing the answer."]
                    [:p.text-sm "2: immediately recall after seeing the answer."]
                    [:p.text-sm
                     (util/format "3: it takes a while to recall. (will reappear after %d days)" days-3)]
                    [:p.text-sm
                     (util/format "4: you recall this after a little thought. (will reappear after %d days)"
                                  days-4)]
                    [:p.text-sm
                     (util/format "5: you remember it easily. (will reappear after %d days)" days-5)]]
             :class "tippy-hover mr-2"
             :interactive true
             :disabled false}
            (svg/info)))

(defn- score-and-next-card [score card *card-index *cards *phase *review-records cb]
  (operation-score! card score)
  (swap! *review-records #(update % score (fn [ov] (conj ov card))))
  (if (>= (inc @*card-index) (count @*cards))
    (do (state/close-modal!)
        (and cb (cb @*review-records)))
    (do (swap! *card-index inc)
        (reset! *phase 1))))

(defn- skip-card [card *card-index *cards *phase *review-records cb]
  (swap! *review-records #(update % "skip" (fn [ov] (conj ov card))))
  (if (>= (inc @*card-index) (count @*cards))
    (do (state/close-modal!)
        (and cb (cb @*review-records)))
    (do
      (swap! *card-index inc)
      (reset! *phase 1))))

(rum/defcs view
  < rum/reactive
  (rum/local 1 ::phase)
  (rum/local 0 ::card-index)
  (rum/local nil ::cards)
  (rum/local {} ::review-records)
  [state cards {preview :preview cb :callback}]
  (let [cards* (::cards state)
        _ (when (nil? @cards*) (reset! cards* cards))
        review-records (::review-records state)
        card-index (::card-index state)
        card (nth @cards* @card-index)
        phase (::phase state)
        {blocks :value next-phase :next-phase} (show-cycle card @phase)
        root-block (.-block card)
        restore-card-fn #(swap! cards* (fn [o]
                                         (conj o (nth o @card-index))))]
    [:div
     [:div.w-144.h-96.resize.overflow-y-auto
      (component-block/blocks-container
       blocks
       (merge (show-cycle-config card @phase)
              {:id (str (:block/uuid root-block))}))]
     (into []
           (concat
            [:div.flex.items-start
             (ui/button (if (= 1 next-phase) "Hide Answers" "Show Answers")
                        :class "w-32 mr-2"
                        :on-click #(reset! phase next-phase))]
            (when preview
              [(ui/button "Reset"
                          :class "mr-8"
                          :on-click #(operation-reset! card))])
            (when (and (> (count cards) 1) preview)
              [(ui/button "Next"
                          :class "mr-2"
                          :on-click #(skip-card card card-index cards* phase review-records cb))])
            (when (and (not preview) (= 1 next-phase))
              (let [interval-days-score-3 (get (get-next-interval card 3) card-last-interval-property)
                    interval-days-score-4 (get (get-next-interval card 4) card-last-interval-property)
                    interval-days-score-5 (get (get-next-interval card 5) card-last-interval-property)]
                [(ui/button "Forgotten" :on-click (fn []
                                                  (restore-card-fn)
                                                  (score-and-next-card 1 card card-index cards* phase review-records cb)) :class "mr-2")

                 (ui/tippy
                  {:html [:p.text-sm (util/format "Remember it easily, (+ %d days)"
                                                  (Math/round interval-days-score-5))]
                   :class "tippy-hover"
                   :interactive true
                   :disabled false}
                  (ui/button "Remembered" :on-click #(score-and-next-card 5 card card-index cards* phase review-records cb) :class "mr-2"))

                 ;; (ui/tippy
                 ;;  {:html [:p.text-sm (util/format "Takes a while to recall, (+ %d days)"
                 ;;                                  (Math/round interval-days-score-3))]
                 ;;   :class "tippy-hover"
                 ;;   :interactive true
                 ;;   :disabled false}
                 ;;  (ui/button "Take a while to recall" :on-click #(score-and-next-card 3 card card-index cards* phase review-records cb) :class "mr-2 delay-1000"))
                 ]))))]))

(defn preview
  [blocks]
  (state/set-modal! #(view (mapv ->card blocks) {:preview true})))


;;; ================================================================
;;; register some external vars & related UI

;;; register cloze macro


(defn cloze-macro-show
  [config options]
  (if (:cloze config)
    [:span.text-blue-600 "[...]"]
    [:span
     [:span.text-blue-600 "["]
     (string/join ", " (:arguments options))
     [:span.text-blue-600 "]"]]))

(component-macro/register cloze-macro-name cloze-macro-show)

;;; register card-query macro
(rum/defcs card-query-show
  < rum/reactive
  (rum/local false ::need-requery)
  [state config options]
  (let [repo (state/get-current-repo)
        query-string (string/join ", " (:arguments options))]
    (if-let [*query-result (query repo query-string)]
      (let [sched-blocks (query-scheduled repo {:query-result *query-result} (tl/local-now))]
        [:div.opacity-70.custom-query-title.flex.flex-row
         [:div.w-full.flex-1
          [:code.p-1 (str "Card-Query: " query-string)]]
         [:div
          (ui/tippy
           {:html [:p.text-sm "click to start review overdue cards"]
            :delay [1000, 100]
            :class "tippy-hover"
            :interactive true
            :disabled false}
           [:a.opacity-70.hover:opacity-100.svg-small.inline
            {:on-click (fn [_]
                         (let [sched-blocks*
                               (query-scheduled (state/get-current-repo) {:query-string query-string} (tl/local-now))]
                           (when (> (count sched-blocks*) 0)
                             (let [review-cards (mapv ->card sched-blocks*)
                                   card-query-block (db/entity [:block/uuid (:block/uuid config)])]
                               (state/set-modal!
                                #(view review-cards
                                       {:callback
                                        (fn [review-records]
                                          (operation-card-info-summary!
                                           review-records review-cards card-query-block)
                                          (swap! (::need-requery state) not)
                                          (persist-var/persist-save of-matrix))}))))))}
            svg/edit])
          (ui/tippy
           {:html [:p.text-sm "click to preview all cards"]
            :delay [1000, 100]
            :class "tippy-hover"
            :interactive true
            :disabled false}
           [:a.opacity-70.hover:opacity-100.svg-small.inline
            {:on-click (fn [_]
                         (let [all-blocks (flatten @(query (state/get-current-repo) query-string))]
                           (when (> (count all-blocks) 0)
                             (let [review-cards (mapv ->card all-blocks)]
                               (state/set-modal! #(view
                                                   review-cards
                                                   {:preview true
                                                    :callback (fn [_]
                                                                (swap! (::need-requery state) not))}))))))}
            "A"])
          (ui/tippy
           {:html [:div
                   [:p.text-sm "overdue / new / total"]
                   [:p.text-sm "click to refresh count"]]
            :delay [1000, 100]
            :class "tippy-hover"
            :interactive true
            :disabled false}
           [:a.open-block-ref-link.bg-base-2.text-sm.ml-2
            {:on-click #(swap! (::need-requery state) (fn [o] (not o)))}
            (let [group-by-repeat (card-group-by-repeat (mapv ->card (flatten @*query-result)))
                  new-card-count (count (flatten (vals (filterv (fn [[k _]] (< k 1)) group-by-repeat))))] ; repeats < 1
              (str (count sched-blocks) "/"  new-card-count "/" (count (flatten @*query-result))))])]])

      ;; bad query-string
      [:div.opacity-70.custom-query-title
       [:div.w-full.flex-1
        [:code.p-1 (str "Card-Query: " query-string)]]
       [:div.text-sm.mt-2.ml-2.font-medium.opacity-50 "Empty"]])))

(component-macro/register query-macro-name card-query-show)

;;; register builtin properties
(property/register-built-in-properties #{card-last-interval-property
                                         card-repeats-property
                                         card-last-reviewed-property
                                         card-next-schedule-property
                                         card-last-easiness-factor-property
                                         card-last-score-property})

;;; register slash commands
(commands/register-slash-command ["Card Query"
                                  [[:editor/input "{{card-query }}" {:backward-pos 2}]]
                                  "Create a card query"])

(commands/register-slash-command ["Cloze"
                                  [[:editor/input "{{cloze }}" {:backward-pos 2}]]
                                  "Create a cloze"])