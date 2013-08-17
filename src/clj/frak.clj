(ns frak
  "Generate regular expressions from collections of strings."
  (:require [clojure.string :as s]))

;;;; Utilities

(defn- prefixes
  "Return a list of all prefixes for a given collection."
  [coll]
  (map-indexed (fn [i _] (take (inc i) coll)) coll))

;;;; Trie construction

(defn- grow [trie [_ & cs :as chars] terminal?]
  (letfn [(visit [inner-trie]
            (let [it (or inner-trie {})
                  lc (last chars)
                  it (if terminal?
                       (update-in it [:terminals] conj lc) 
                       it)]
              (-> it
                  (update-in [:visitors] conj lc)
                  (assoc lc (get-in trie chars)))))]
    (if (seq cs)
      (update-in trie (butlast chars) visit)
      (visit trie))))

(defn- trie-put
  ([s] (trie-put {} s))
  ([trie s]
     (let [s (str s)]
       (if-not (seq s)
         trie
         (loop [t trie, ps (prefixes s)]
           (if-let [cs (and (next ps) (first ps))]
             (recur (grow t cs false) (next ps))
             (grow t (first ps) true)))))))

(defn- build-trie [strs]
  (reduce trie-put {} strs))

;;;; Pattern rendering

(def ^{:private true
       :doc "Characters to escape when rendering a regular expression."}
  escape-chars
  #{\\ \^ \$ \* \+ \? \. \| \( \) \{ \} \[ \]})

(def ^:private escape-char? escape-chars)

(defn- escape [c]
  (str (when (escape-char? c) "\\") c))

(def ^{:private true :dynamic true} *capture* false)

(defn- re-group-fmt []
  (str (if *capture* "(" "(?:") "%s)"))

(defn- re-group [[s & more :as strs]]
  (if (seq more)
    (format (re-group-fmt) (s/join "|" strs))
    s))

(defn- re-char-set [chars]
  (format "[%s]" (apply str chars)))

(defn- render-trie [trie]
  (let [{vs :visitors ts :terminals} trie
        terminal? (set ts)
        ks (->> (dissoc trie :visitors :terminals)
                (keys)
                (sort-by (frequencies vs))
                reverse)
        nks (if-let [cs (seq (filter #(nil? (trie %)) ks))]
              (when (< 1 (count cs)) cs))
        char-set (and (seq nks) (re-char-set nks))
        branches (for [k (remove (set nks) ks)]
                   (let [sk (escape k)
                         fmt (if (terminal? k)
                               (str "%s" (re-group-fmt) "?")
                               "%s%s")]
                     (if-let [branch (trie k)]
                       (format fmt sk (render-trie branch))
                       sk)))]
    (re-group (if char-set (conj branches char-set) branches))))

(defn pattern
  "Construct a regular expression from a collection of strings."
  ([strs]
     (pattern strs {:capture? false, :exact? false}))
  ([strs opts]
     (let [pattern (binding [*capture* (:capture? opts)]
                     (-> strs build-trie render-trie str))]
       (re-pattern (if (:exact? opts)
                     (str "^" pattern "$")
                     pattern)))))
