(ns athens.export
  "Entry point namespace for the Athens exporter tool.
  This takes an Athens database and attempts to dump it as a tree of Markdown
  files, named and formatted in the way that logseq expects."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [datascript.core :as ds]
    [datascript.transit :as dt])
  (:import
    [java.io FileInputStream]
    [java.util UUID])
  (:gen-class))

(defn rewrite-block-refs [block-refs text]
  (and text
       (reduce #(string/replace %1
                                (str "((" (:block/ref %2) "))")
                                (str "((" (:export/uuid %2) "))"))
               text block-refs)))

(def re-journal
  #"^(January|February|March|April|May|June|July|August|September|October|November|December) (\d+), (\d+)$")

(defn- indent-block [[first-line & rest-lines]]
  (cons (str "- " first-line)
        (map #(str "  " %) rest-lines)))

(defn markdown-block
  "Returns a Markdown-formatted block as a list of lines of text."
  [db block-refs block]
  (let [child-ids (map :db/id (:block/children block))
        children  (sort-by :block/order (ds/pull-many db '[*] child-ids))
        children-text     (map #(markdown-block db block-refs %) children)
        children-indented (mapcat indent-block children-text)
        text (rewrite-block-refs block-refs (:block/string block))
        text (if (:export/uuid block)
               (str text "\nid:: " (:export/uuid block))
               text)]
    (if text
      (cons text children-indented)
      children-indented)))

(defn convert-todos [text]
  "Converts TODO/DONE state. Athens may allow TODO markers mid sentence.
  However logseq expect those at the beginning of the block. If you have
  TODO markers mid block with text preceding it, you may need to manually
  look for those after the conversion. Logseq also does not use [ ] and [X]
  syntax like other MD-aware tools. The reason, logseq allows  configurable
  TODO flows based on  multiple labels to toggle from. Priorities are not
  supported in Athens, hence, nothing to convert here."
  (-> text
      (string/replace #"\{\{TODO\}\}|\{\{\[\[TODO\]\]\}\}" "TODO")
      (string/replace #"\{\{DONE\}\}|\{\{\[\[DONE\]\]\}\}" "DONE")))

(defn block->file [db block-refs filename preamble block]
  (->> (markdown-block db block-refs block)
       (string/join \newline)
       (str (or preamble ""))
       (convert-todos)
       (spit filename)))

(defn page-path
  "Returns [path preamble] for a page. Preamble might be nil, but it might
  set a custom title for a page if escaping is needed.
  Rules:
  - / is replaced with . and the page title rewritten
  - : is replaced with _ and the page title rewritten
  - . is not rewritten, but it needs a title:: rewrite or it's assumed to be /"
  [root title]
  (let [escaped (-> title
                    (string/replace "/" ".")
                    (string/replace ":" "_"))
        path    (str root "/pages/" escaped ".md")]
    (if (and (= escaped title)
             (not (string/includes? title ".")))
      [path nil]
      [path (str "title:: " title "\n\n")])))

(defn page->file [db block-refs root title]
  (let [[path preamble] (page-path root title)]
    (block->file db block-refs path preamble
                 (ds/pull db '[*] [:node/title title]))))

(def months
  {"January"    "01"
   "February"   "02"
   "March"      "03"
   "April"      "04"
   "May"        "05"
   "June"       "06"
   "July"       "07"
   "August"     "08"
   "September"  "09"
   "October"    "10"
   "November"   "11"
   "December"   "12"})

(defn date-format
  "Turns a journal entry with
  {:node/title 'July 13, 2021' :month 'July' :day 13 :year 2021}
  into a formatted filename of the date, eg. 2021_07_13."
  [{:keys [day month year]}]
  (str year "_" (months month) "_" (if (< day 10) (str "0" day) day)))

(defn journal->file [db block-refs root journal]
  (block->file db block-refs
               (str root "/journals/" (date-format journal) ".md")
               nil ; preamble
               (ds/pull db '[*] [:node/title (:node/title journal)])))

(defn extract-block-refs [db]
  (for [[eid text] (ds/q '[:find ?e ?text :where [?e :block/string ?text]] db)
        :let [block-refs (re-seq #"\(\(([\da-fA-F]+)\)\)" text)]
        [_ block-ref] block-refs]
    {:db/id eid
     :block/string text
     :block/ref block-ref
     :export/uuid (.toString (UUID/nameUUIDFromBytes (.getBytes block-ref)))}))

(defn export
  "Given the in-memory database and a target directory, output everything there.
  The directory must exist and should probably be empty."
  [{:keys [athens logseq]}]
  ; Ensure the subdirectories of a logseq tree are present.
  ; Foo doesn't exist; make-parents creates the parent directories above it.
  (io/make-parents (str logseq "/journals/foo"))
  (io/make-parents (str logseq "/pages/foo"))
  (io/make-parents (str logseq "/logseq/foo"))

  ; Write all the journal files.
  (let [base-db    (-> (FileInputStream. athens)
                       (dt/read-transit))
        conn       (atom base-db)
        block-refs (extract-block-refs base-db)
        ; Side effect: update all the referenced blocks to have a UUID attached.
        _ (ds/transact! conn
                        (for [{:keys [block/ref export/uuid]} block-refs]
                          {:block/uid ref
                           :export/uuid uuid}))
        db          @conn
        titles      (ds/q '[:find [?title ...]
                            :where [_ :node/title ?title]]
                          db)
        journals    (for [title titles
                          :let [[_ m d y] (re-find re-journal title)]
                          :when y]
                      {:node/title title
                       :day (Integer/parseInt d)
                       :month m
                       :year (Integer/parseInt y)})
        page-titles (remove #(re-find re-journal %) titles)]

    (doseq [j journals]
      (journal->file db block-refs logseq j))
    ; And all the named pages.
    (doseq [p page-titles]
      (page->file db block-refs logseq p))))

; This can be run from the REPL with eg.
; (export {:athens "path/to/athens/index.transit" :logseq "path/to/empty/dir"})
; or from the command-line with
; clj -X athens.export/export :athens '"path/to/athens/index.transit"' \
;   :logseq '"path/to/empty/dir"'
; (yes with the weird double quoting; we want to pass literal "s to Clojure)

(defn -main [athens-db logseq-dir & _]
  (export {:athens athens-db :logseq logseq-dir}))

