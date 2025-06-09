(ns xtdb.test-json
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [clojure.walk :as walk]
            [jsonista.core :as json]
            [xtdb.test-util :as tu]
            [xtdb.util :as util])
  (:import clojure.lang.MapEntry
           java.io.File
           java.nio.ByteBuffer
           java.nio.channels.FileChannel
           [java.nio.file CopyOption FileVisitOption Files OpenOption Path StandardCopyOption StandardOpenOption]
           org.apache.arrow.memory.RootAllocator
           [org.apache.arrow.vector.ipc ArrowFileReader ArrowStreamReader JsonFileWriter]))

(defn sort-arrow-json
  "For use in tests to provide consitent ordering to where ordering is undefined"
  [arrow-json-tree-as-clojure]
  (walk/postwalk
   (fn [node]
     (if (and (map-entry? node)
              (#{"children" "fields" "columns"} (key node)))
       (MapEntry/create (key node) (sort-by #(get % "name") (val node)))
       node))
   arrow-json-tree-as-clojure))

(defn- file->json-file ^java.nio.file.Path [^Path file]
  (.resolve (.getParent file) (format "%s.json" (.getFileName file))))

(defn write-arrow-json-file ^java.nio.file.Path [^Path file]
  (let [json-file (file->json-file file)]
    (with-open [file-ch (FileChannel/open file (into-array OpenOption #{StandardOpenOption/READ}))
                file-reader (ArrowFileReader. file-ch tu/*allocator*)
                file-writer (JsonFileWriter. (.toFile json-file)
                                             (.. (JsonFileWriter/config) (pretty true)))]
      (let [root (.getVectorSchemaRoot file-reader)]
        (.start file-writer (.getSchema root) nil)
        (while (.loadNextBatch file-reader)
          (.write file-writer root)))

      json-file)))

(comment
  (with-open [al (RootAllocator.)]
    (binding [tu/*allocator* al]
      (write-arrow-json-file (util/->path "/tmp/test.arrow")))))

(defn check-arrow-json-file [^Path expected, ^Path actual]
  (t/is (= (sort-arrow-json (json/read-value (Files/readString expected)))
           (sort-arrow-json (json/read-value (Files/readString actual))))
        actual))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn copy-expected-file [^Path file, ^Path expected-dir, ^Path actual-dir]
  (Files/copy file (doto (.resolve expected-dir (.relativize actual-dir file))
                     (-> (.getParent) (util/mkdirs)))
              ^"[Ljava.nio.file.CopyOption;" (into-array CopyOption #{StandardCopyOption/REPLACE_EXISTING})))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn delete-and-recreate-dir [^Path path]
  (when (.. path toFile isDirectory)
    (util/delete-dir path)
    (.mkdirs (.toFile path))))

(defn check-json
  ([expected-dir actual-dir] (check-json expected-dir actual-dir nil))

  ([^Path expected-dir, ^Path actual-dir, file-pattern]
   ;; uncomment if you want to remove files
   #_(delete-and-recreate-dir expected-dir) ;; <<no-commit>>
   (doseq [^Path path (iterator-seq (.iterator (Files/walk actual-dir (make-array FileVisitOption 0))))
           :let [file-name (str (.getFileName path))]
           :when (and (str/ends-with? file-name ".arrow")
                      (or (nil? file-pattern)
                          (re-matches file-pattern file-name)))]
     (doto (write-arrow-json-file path)
       ;; uncomment this to reset the expected file (but don't commit it)
       #_(copy-expected-file expected-dir actual-dir))) ;; <<no-commit>>

   (doseq [^Path expected (iterator-seq (.iterator (Files/walk expected-dir (make-array FileVisitOption 0))))
           :let [actual (.resolve actual-dir (.relativize expected-dir expected))
                 file-name (str (.getFileName expected))]
           :when (.endsWith file-name ".arrow.json")]
     (check-arrow-json-file expected actual))))

(defn arrow-streaming->json ^String [^bytes arrow-bytes]
  (let [json-file (File/createTempFile "arrow" "json")]
    (try
      (util/with-open [allocator (RootAllocator.)
                       in-ch (util/->seekable-byte-channel (ByteBuffer/wrap arrow-bytes))
                       file-reader (ArrowStreamReader. in-ch allocator)
                       file-writer (JsonFileWriter. json-file (.. (JsonFileWriter/config) (pretty true)))]
        (let [root (.getVectorSchemaRoot file-reader)]
          (.start file-writer (.getSchema root) nil)
          (while (.loadNextBatch file-reader)
            (.write file-writer root))))
      (slurp json-file)
      (finally
        (.delete json-file)))))
