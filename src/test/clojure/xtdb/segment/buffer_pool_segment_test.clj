(ns xtdb.segment.buffer-pool-segment-test
  (:require [clojure.test :as t]
            [xtdb.api :as xt]
            [xtdb.db-catalog :as db]
            [xtdb.node :as xtn]
            [xtdb.test-util :as tu]
            [xtdb.util :as util])
  (:import (kotlin.coroutines EmptyCoroutineContext)
           (kotlin.jvm.functions Function2)
           (kotlinx.coroutines BuildersKt)
           (org.apache.arrow.memory BufferAllocator)
           (org.apache.arrow.vector.ipc.message ArrowRecordBatch)
           (xtdb.arrow RelationReader)
           (xtdb.segment BufferPoolSegment Segment$Metadata Segment$Page Segment$PageMeta)
           (xtdb.storage BufferPool)
           (xtdb.table TableSlug)))

(defn- page-iids [^Segment$Page page al]
  (let [^RelationReader rel (BuildersKt/runBlocking EmptyCoroutineContext/INSTANCE
                                                   (reify Function2
                                                     (invoke [_ _scope cont] (.loadDataPage page al cont))))]
    (mapv :xt/iid (.getAsMaps rel))))

(defn- pages [^Segment$Metadata seg-meta]
  (for [leaf (.getLeaves (.getTrie seg-meta))]
    (.getPage ^Segment$PageMeta (.page seg-meta leaf))))

(defn- failing-bp ^BufferPool [^BufferPool bp !fail?]
  (reify BufferPool
    (getFooter [_ k _cont] (.getFooter bp k _cont))
    (getRecordBatch [_ k idx _cont]
      (let [rb (.getRecordBatchSync bp k idx)]
        (if @!fail?
          (with-open [rb rb]
            (ArrowRecordBatch. (.getLength rb) (.getNodes rb) (vec (butlast (.getBuffers rb)))))
          rb)))))

(t/deftest a-segment-loads-its-pages-in-turn
  (with-open [node (xtn/start-node {:indexer {:page-limit 16}, :compactor {:threads 0}})]
    (xt/execute-tx node [(into [:put-docs :docs] (for [i (range 100)] {:xt/id i}))])
    (tu/flush-block! node)

    (let [db (db/primary-db node)
          bp (.getBufferPool db)
          meta-mgr (.getMetadataManager db)
          slug (TableSlug/of #xt/table docs)]

      (t/testing "each page's rows replace the last's"
        (util/with-open [al (util/->child-allocator (.getAllocator node) "segment-test")
                         segs (BufferPoolSegment/openSync al bp meta-mgr slug "l00-rc-b00" nil)
                         seg-meta (.openMetadataSync ^BufferPoolSegment (first segs))]
          (let [pages (pages seg-meta)
                iids (vec (mapcat (fn [^Segment$Page page]
                                    (try
                                      (page-iids page al)
                                      (finally (.close page))))
                                  pages))]
            (t/is (< 1 (count pages)))
            (t/is (= 100 (count iids) (count (set (map vec iids))))))))

      (t/testing "a load that fails partway leaves the segment able to load the page again"
        (let [!fail? (atom true)]
          (util/with-open [^BufferAllocator al (util/->child-allocator (.getAllocator node) "segment-test")
                           segs (BufferPoolSegment/openSync al (failing-bp bp !fail?) meta-mgr slug "l00-rc-b00" nil)
                           seg-meta (.openMetadataSync ^BufferPoolSegment (first segs))]
            (let [^Segment$Page page (first (pages seg-meta))]
              (t/is (thrown? Exception (page-iids page al)))
              (t/is (zero? (.getAllocatedMemory al)) "the partly-loaded buffers are released")
              (reset! !fail? false)
              (t/is (seq (page-iids page al)))
              (.close page))))))))
