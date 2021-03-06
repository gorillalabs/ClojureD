(ns clojured.core
  (:require  [sparkling.api :as spark]
             [sparkling.conf :as s-conf]
             [sparkling.destructuring :as s-de]
             [clj-time.format :as tf]
             [clojure.pprint :refer [pprint]]
             ))

(defonce filename "access.log")

(def master "local[*]")
(def conf {})
(def env {
          "spark.executor.memory" "4G",
          "spark.files.overwrite" "true"
          })

(defn line-count [lines]
  (->> lines
       count))

(defn process [f]
  (with-open [rdr (clojure.java.io/reader filename)]
    (let [result (f (line-seq rdr))]
      (if (seq? result)
        (doall result)
        result))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; In Practice 3: status codes

(defn transform-log-entry [m]
  (->
    m
    (update-in [:timestamp] #(tf/parse (tf/formatter "dd/MMM/yyyy:HH:mm:ss Z") %))
    (assoc :uri (-> (:request m)
                    (clojure.string/split #" ")
                    (get 1)
                    (clojure.string/split #"\?")
                    (get 0)))))

(defn parse-line [line]
  (some->> line
           (re-matches #"^(.*?) .*? .*? \[(.*?)] \"(.*?)\" (.*?) (.*?) \"(.*?)\" \"(.*?)\"(.*?)$")
           rest
           (zipmap [:ip :timestamp :request :status :length :referer :ua :duration])
           transform-log-entry))

(defn group-by-status-code [lines]
  (->> lines
       (map parse-line)
       (map (fn [entry] [(:status entry) 1]))
       (reduce (fn [a [k v]] (update-in a [k] #((fnil + 0) % v))) {})
       (map identity)))

#_ (process group-by-status-code)
; => (["200" 261] ["404" 8])



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; In Practice 5: top-errors

(defn top-errors [lines]
  (->> lines
       (map parse-line)
       (filter (fn [entry] (not= "200" (:status entry))))
       (map (fn [entry] [(:uri entry) 1]))
       (reduce (fn [a [k v]] (update-in a [k] #((fnil + 0) % v))) {})
       (sort-by val >)
       (take 10)))

#_ (process top-errors)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn- new-spark-context []
  (let [c (-> (s-conf/spark-conf)
              (s-conf/master master)
              (s-conf/app-name "sparkling")
              (s-conf/set "spark.akka.timeout" "300")
              (s-conf/set conf)
              (s-conf/set-executor-env env))]
    (spark/spark-context c) ))

(defonce sc (delay (new-spark-context)))

(defn process* [f]
  (let [lines-rdd (spark/text-file @sc filename)]
    (f lines-rdd)))


(defn line-count* [lines]
  (-> lines
       spark/count))

#_
(process line-count)

#_
(process* line-count*)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; In Practice 4: status codes cont'd

(defn group-by-status-code* [lines]
  (-> lines
      (spark/map parse-line)
      (spark/map-to-pair (fn [entry]
                       (spark/tuple (:status entry) 1)))
      (spark/reduce-by-key +)
      (spark/map (s-de/key-value-fn vector))
      (spark/collect)))

#_
(process* group-by-status-code*)




(defn top-errors* [lines]
  (-> lines
      (spark/map parse-line)
      (spark/filter (fn [entry] (not= "200" (:status entry))))
      (spark/rdd-name "only-ok")
      spark/cache
      (spark/map-to-pair (fn [entry] (spark/tuple (:uri entry) 1)))
      (spark/reduce-by-key +)
      (spark/rdd-name "count-by-url")
      ;; flip
      (spark/map-to-pair (s-de/key-value-fn (fn [a b] (spark/tuple b a))))
      (spark/sort-by-key false) ;; descending order
      ;; flip
      (spark/map-to-pair (s-de/key-value-fn (fn [a b] (spark/tuple b a))))
      (spark/map (s-de/key-value-fn vector))
      (spark/take 10)))

#_
(process* top-errors*)















#_
(clojure.java.browse/browse-url "http://localhost:4040/")




(defn -main [& args]
  (println (process line-count)))