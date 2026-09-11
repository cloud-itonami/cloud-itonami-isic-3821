(ns wasteops.store-contract-test
  "The Store contract as executable tests."
  (:require [clojure.test :refer [deftest is testing]]
            [wasteops.store :as store]))

(deftest seed-db-read-parity
  (let [s (store/seed-db)]
    (is (= "North Ridge Sanitary Landfill" (:name (store/facility s "landfill-north-1"))))
    (is (true? (:registered? (store/facility s "landfill-north-1"))))
    (is (true? (:verified? (store/facility s "landfill-north-1"))))
    (is (true? (:registered? (store/facility s "incin-facility-3"))))
    (is (false? (:verified? (store/facility s "incin-facility-3"))) "seeded as registered but not yet verified")
    (is (nil? (store/facility s "no-such-facility")))
    (is (= ["incin-facility-3" "landfill-north-1" "sorting-facility-2"] (mapv :facility-id (store/all-facilities s))))
    (is (= [] (store/ledger s)))
    (is (= [] (store/coordination-log s)))))

(deftest mem-store-honors-explicit-facilities-map
  (let [s (store/mem-store {"a" {:facility-id "a" :registered? true :verified? true}})]
    (is (some? (store/facility s "a")))
    (is (nil? (store/facility s "b"))))
  (testing "an empty facilities map means unregistered everywhere"
    (let [s (store/mem-store {})]
      (is (nil? (store/facility s "landfill-north-1"))))))

(deftest commit-record-appends-to-coordination-log
  (let [s (store/seed-db)]
    (store/commit-record! s {:op :log-facility-record :facility-id "landfill-north-1" :value {:intake-tons 42.5}})
    (store/commit-record! s {:op :schedule-maintenance :facility-id "landfill-north-1" :value {:equipment "compactor"}})
    (is (= 2 (count (store/coordination-log s))))
    (is (= [:log-facility-record :schedule-maintenance] (mapv :op (store/coordination-log s))))))

(deftest ledger-is-append-only-and-order-preserving
  (let [s (store/seed-db)]
    (store/append-ledger! s {:op :a :disposition :commit})
    (store/append-ledger! s {:op :b :disposition :hold})
    (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))

(deftest with-facilities-replaces-directory-when-non-empty
  (let [s (store/mem-store {"x" {:facility-id "x" :registered? true :verified? true}})]
    (store/with-facilities s {"y" {:facility-id "y" :registered? true :verified? true}})
    (is (nil? (store/facility s "x")))
    (is (some? (store/facility s "y"))))
  (testing "an empty replacement is a no-op (never silently wipes the directory)"
    (let [s (store/mem-store {"x" {:facility-id "x" :registered? true :verified? true}})]
      (store/with-facilities s {})
      (is (some? (store/facility s "x"))))))
