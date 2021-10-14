(ns clojure-lsp.feature.semantic-tokens
  (:require
   [clojure-lsp.shared :as shared]
   [taoensso.timbre :as log])
  (:import
   [clojure.lang PersistentVector]))

(set! *warn-on-reflection* true)

(def token-types
  [:namespace
   :type
   :function
   :macro
   :keyword
   :class
   :variable
   :method
   :event])

(def token-types-str
  (->> token-types
       (map name)
       vec))

(def token-modifiers
  [:definition])

(def token-modifiers-str
  (->> token-modifiers
       (map name)
       vec))

(defn ^:private element-inside-range?
  [{element-row :name-row element-end-row :name-end-row}
   {:keys [name-row name-end-row]}]
  (and (>= element-row name-row)
       (<= element-end-row name-end-row)))

(defn ^:private element->absolute-token
  ([element token-type]
   (element->absolute-token element token-type nil))
  ([{:keys [name-row name-col name-end-col]}
    token-type
    token-modifier-bit]
   [(dec name-row)
    (dec name-col)
    (- name-end-col name-col)
    (.indexOf ^PersistentVector token-types token-type)
    (or token-modifier-bit 0)]))

(defn ^:private var-definition-element->absolute-tokens
  [{:keys [defined-by] :as element}]
  (cond

    defined-by
    [(element->absolute-token element :function 1)]

    :else
    nil))

(defn ^:private var-usage-element->absolute-tokens
  [{:keys [name alias macro name-col to] :as element}]
  (cond
    (and macro
         (not alias))
    [(element->absolute-token element :macro)]

    (and macro
         alias)
    (let [slash (+ name-col (count (str alias)))
          alias-pos (assoc element :name-end-col slash)
          slash-pos (assoc element :name-col slash :name-end-col (inc slash))
          name-pos (assoc element :name-col (inc slash))]
      [(element->absolute-token alias-pos :type)
       (element->absolute-token slash-pos :event)
       (element->absolute-token name-pos :macro)])

    alias
    (let [slash (+ name-col (count (str alias)))
          slash-pos (assoc element :name-col slash :name-end-col (inc slash))
          alias-pos (assoc element :name-end-col slash)
          name-pos (assoc element :name-col (inc slash))]
      [(element->absolute-token alias-pos :type)
       (element->absolute-token slash-pos :event)
       (element->absolute-token name-pos :function)])

    (and (identical? :clj-kondo/unknown-namespace to)
         (.equals \. (.charAt ^String (str name) 0)))
    [(element->absolute-token element :method)]

    (identical? :clj-kondo/unknown-namespace to)
    nil

    :else
    [(element->absolute-token element :function)]))

(defn ^:private elements->absolute-tokens
  [elements]
  (->> elements
       (sort-by (juxt :name-row :name-col))
       (map
         (fn [{:keys [bucket] :as element}]
           (cond
             ;; TODO needs better way to know it's class related
             ;; (and (= bucket :var-usages)
             ;;      (not alias)
             ;;      (Character/isUpperCase (.charAt ^String (str name) 0)))
             ;; [(element->absolute-token element :class)]

             (= bucket :var-usages)
             (var-usage-element->absolute-tokens element)

             (= bucket :var-definitions)
             (var-definition-element->absolute-tokens element)

             (#{:locals :local-usages} bucket)
             [(element->absolute-token element :variable)]

             (= bucket :namespace-definitions)
             [(element->absolute-token element :namespace)]

             (and (= bucket :keywords)
                  (not (:str element))
                  (not (:keys-destructuring element)))
             [(element->absolute-token element :keyword)])))
       (remove nil?)
       (mapcat identity)))

(defn ^:private absolute-token->relative-token
  [tokens
   index
   [row col length token-type token-modifier :as token]]
  (let [[previous-row previous-col _ _ _] (nth tokens (dec index) nil)]
    (cond
      (nil? previous-row)
      token

      (= previous-row row)
      [0
       (- col previous-col)
       length
       token-type
       token-modifier]

      :else
      [(- row previous-row)
       col
       length
       token-type
       token-modifier])))

(defn full-tokens [uri db]
  (let [elements (get-in @db [:analysis (shared/uri->filename uri)])
        absolute-tokens (elements->absolute-tokens elements)]
    (->> absolute-tokens
         (map-indexed (partial absolute-token->relative-token absolute-tokens))
         flatten)))

(defn range-tokens
  [uri range db]
  (let [elements (get-in @db [:analysis (shared/uri->filename uri)])
        range-elements (filter #(element-inside-range? % range) elements)
        absolute-tokens (elements->absolute-tokens range-elements)]
    (->> absolute-tokens
         (map-indexed (partial absolute-token->relative-token absolute-tokens))
         flatten)))

(defn element->token-type [element]
  (->> [element]
       elements->absolute-tokens
       (mapv (fn [[_ _ _ type modifier]]
               {:token-type (nth token-types type type)
                :token-modifier (nth token-modifiers modifier modifier)}))))
