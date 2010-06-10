; todo 
; done 1. emit text deltas, not plain text replacement (or IDEs will not like it)
; done 2. have a story for invalid parsetrees : just do nothing : currently = paredit deactivated if error from start-of-file to area of paredit's work
; 3. use restartable version of the parser
; 4. make paredit optional in ccw
; 5. prepare a new release of ccw
; 6. write with clojure.zip functions the close-* stuff
; 7. write the string related stuff
; ... ?
; . add support for more clojure-related source code ( #{}, #""... )
; ... and all the other paredit stuff ...

(ns paredit.core
  (:use clojure.contrib.def)
  (:use [paredit.parser :exclude [pts]])
  (:use clojure.set)
  (:use clojure.contrib.core)
  (:require clojure.contrib.pprint)
  (:require [clojure.contrib.str-utils2 :as str2])
  (:require [paredit.text-utils :as t])
  (:require [clojure.zip :as z])
  (:use paredit.loc-utils)) ; TODO avoir un require :as l

#_(set! *warn-on-reflection* true)

;;; adaptable paredit configuration
(def ^String *newline* "\n")
;;; adaptable paredit configuration

(def *real-spaces* #{(str \newline) (str \tab) (str \space)})
(def *extended-spaces* (conj *real-spaces* (str \,)))
(def *open-brackets* (conj #{"(" "[" "{"} nil)) ; we add nil to the list to also match beginning of text 
(def *close-brackets* (conj #{")" "]" "}"} nil)) ; we add nil to the list to also match end of text
(def *form-macro-chars* #{(str \#) (str \~) "~@" (str \') (str \`) (str \@) "^" "#'" "#_" "#!"})
(def *not-in-code* #{"\"" "\"\\" ";" "\\"})

(defn parsed-in-tags?
  [parsed tags-set]
  (tags-set (-> parsed :parents peek :tag)))

(defn parse-stopped-in-code?
  ; TODO the current function is not general enough, it just works for the offset
  ; the parse stopped at  
  "true if character at offset offset is in a code
   position, e.g. not in a string, regexp, literal char or comment"
  [parsed]
  (not (parsed-in-tags? parsed *not-in-code*)))

(defn in-code? [loc] (not (*not-in-code* (loc-tag loc))))
  
(defmulti paredit (fn [k & args] k))

(defn insert-balanced
  [[o c] t chars-with-no-space-before chars-with-no-space-after]
  (let [add-pre-space? (not (contains? chars-with-no-space-before 
                                       (t/previous-char-str t)))
        add-post-space? (not (contains? chars-with-no-space-after 
                                        (t/next-char-str t)))
        ins-str (str (if add-pre-space? " " "")
                     (str o c)
                     (if add-post-space? " " ""))
        offset-shift (if add-post-space? -2 -1)]
    (-> t (t/insert ins-str) (t/shift-offset offset-shift))))

(declare wrap-with-balanced)

(defn open-balanced
  [[o c] {:keys [^String text offset length] :as t} 
   chars-with-no-space-before chars-with-no-space-after]
  (if (zero? length) 
    (let [parsed (parse text offset)]
      (if (parse-stopped-in-code? parsed)
        (do
          (insert-balanced [o c] t chars-with-no-space-before chars-with-no-space-after))
        (-> t (t/insert (str o)))))
    (wrap-with-balanced [o c] t)))
  
(defn close-balanced
  [[o c] {:keys [^String text offset length] :as t} 
   chars-with-no-space-before chars-with-no-space-after]
    (let [parsed (parse text (.length text))
          offset-loc (-> parsed parsed-root-loc (loc-for-offset offset))]       
      (if (and offset-loc (not (*not-in-code* (-> offset-loc z/node :tag))))
        (let [up-locs (take-while identity (iterate z/up offset-loc))
              match (some #(when (= o (-> % z/node :tag)) %) up-locs)]
          (if match
            (let [last-node (-> match z/down z/rightmost z/left z/node)
                  nb-delete (if (= (str \space) (:tag last-node)) 
                              (- (:end-offset last-node) (:offset last-node))
                              0)
                  t (if (> nb-delete 0) 
                      (t/delete t (:offset last-node) nb-delete)
                      t)] ; z/left because there is the closing node
              (-> t (t/set-offset (- (-> match z/node (:end-offset)) nb-delete))))
            (-> t (t/insert (str c)))))
        (-> t (t/insert (str c))))))

(defmethod paredit 
  :paredit-open-round
  [cmd {:keys [text offset length] :as t}]
  (open-balanced ["(" ")"] t 
    (union (conj (into *real-spaces* *open-brackets*) "#") *form-macro-chars*)
    (into *extended-spaces* *close-brackets*)))
    
(defmethod paredit 
  :paredit-open-square
  [cmd {:keys [text offset length] :as t}]
  (open-balanced ["[" "]"] t
    (union (into *real-spaces* *open-brackets*) *form-macro-chars*)
    (into *extended-spaces* *close-brackets*)))
    
(defmethod paredit 
  :paredit-open-curly
  [cmd {:keys [text offset length] :as t}]
  (open-balanced ["{" "}"] t
    (union (conj (into *real-spaces* *open-brackets*) "#") *form-macro-chars*)
    (into *extended-spaces* *close-brackets*)))
    
(defmethod paredit 
  :paredit-close-round
  [cmd {:keys [text offset length] :as t}]
  (close-balanced ["(" ")"] t
    nil nil))

(defmethod paredit 
  :paredit-close-square
  [cmd {:keys [text offset length] :as t}]
  (close-balanced ["[" "]"] t
    nil nil))

(defmethod paredit 
  :paredit-close-curly
  [cmd {:keys [text offset length] :as t}]
  (close-balanced ["{" "}"] t
    nil nil))

(defmethod paredit
  :paredit-doublequote
  [cmd {:keys [text offset length] :as t}]
  (let [parsed (parse text offset)]
    (cond
      (parse-stopped-in-code? parsed)
        (insert-balanced [\" \"] t
          (conj (into *real-spaces* *open-brackets*) \#)
          (into *extended-spaces* *close-brackets*))
      (not (parsed-in-tags? parsed #{"\""}))
        (-> t (t/insert (str \")))
      (and (= "\\" (t/previous-char-str t)) (not= "\\" (t/previous-char-str t 2)))
        (-> t (t/insert (str \")))
      (= "\"" (t/next-char-str t))
        (t/shift-offset t 1)
        #_(close-balanced ["\"" "\""] t nil nil)
      :else
        (-> t (t/insert (str \\ \"))))))

(defmethod paredit 
  :paredit-forward-delete
  [cmd {:keys [^String text offset length] :as t}]
  (let [parsed (parse text (.length text))
        parse-ok (not= :ko (:parser-state parsed))]
    (if parse-ok
      (let [offset-loc (-> parsed parsed-root-loc (loc-for-offset offset))
            offset-node (-> offset-loc z/node)
            handled-forms (conj *open-brackets* "\"")
            in-handled-form (handled-forms (:tag offset-node))]
        (cond 
          (and in-handled-form (= offset (:offset offset-node)))
            (t/shift-offset t 1)
          (and in-handled-form (= offset (dec (:end-offset offset-node))))
            (if (> (-> offset-node :content count) 2)
              t     ; don't move
              (-> t ; delete the form 
                (t/delete (:offset offset-node) (- (:end-offset offset-node) (:offset offset-node)))
                (t/shift-offset -1)))
          :else
            (t/delete t offset 1)))
      (t/delete t offset 1))))

(defmethod paredit 
  :paredit-backward-delete
  [cmd {:keys [^String text offset length] :as t}]
  (let [offset (dec offset)
        parsed (parse text (.length text))
        parse-ok (not= :ko (:parser-state parsed))]
    (if parse-ok
      (let [offset-loc (-> parsed parsed-root-loc (loc-for-offset offset))
            offset-node (-> offset-loc z/node)
            handled-forms (conj *open-brackets* "\"")
            in-handled-form (handled-forms (:tag offset-node))]
        (cond 
          (and in-handled-form (= offset (:offset offset-node)))
            (if (> (-> offset-node :content count) 2)
              t     ; don't move
              (-> t ; delete the form 
                (t/delete (:offset offset-node) (- (:end-offset offset-node) (:offset offset-node)))
                (t/shift-offset -1)))
          (and in-handled-form (= offset (dec (:end-offset offset-node))))
            (t/shift-offset t -1)
          :else
            (-> t (t/delete offset 1) (t/shift-offset -1))))
      (-> t (t/delete offset 1) (t/shift-offset -1)))))

(defn indent-column 
  "pre-condition: line-offset is really the starting offset of a line"
  [root-loc line-offset]
  (let [loc (loc-for-offset root-loc (dec line-offset))]
    (if-let [loc (z/left loc)]
      (loop [loc loc seen-loc nil indent 0]
        (cond
          (nil? loc)
            indent
          (punct-loc? loc)
            ; we reached the start of the parent form, indent depending on the form's type
            (+ (loc-col loc)
              (loc-count loc)    
              (if (= "(" (loc-text loc)) 1 0))
          (= " " (loc-tag loc))
            ; we see a space
            (if (.contains (loc-text loc) "\n")
              (if seen-loc
                (+ indent (dec (-> (loc-text loc) (.substring (.lastIndexOf (loc-text loc) "\n")) .length)))
                (recur (z/left loc) nil 0))
              (recur (z/left loc) nil (+ indent (-> (loc-text loc) .length))))
          :else
            (recur (z/left loc) loc 0)))
      ; we are at the start of the file !
      0)))

(defn text-selection
  "returns a vector [offset length] from a normalized-selection"
  [nsel]
  (let [[l r] nsel
        offset (start-offset l)
        length (if (nil? r) 0 (- (end-offset r) offset))]
    [offset length]))

(defn normalized-selection
  "makes a syntaxically correct selection, that is the returned nodes are siblings.
   returns a vector of 2 locs.
   If the selection is empty, the first loc will give the start (get it via a call to 'loc-start on it)
   and the second loc will be nil.
   If the selection is not empty, the second loc will give the end (get it via a call to 'loc-end on it).
   Pre-requisites: length >=0, offset >=0. rloc = root loc of the tree"
  [rloc offset length]
  (let [left-leave (parse-leave (leave-for-offset rloc offset))
        right-leave (parse-leave (leave-for-offset rloc (+ offset length)))
        right-leave (cond 
                      (= :root (loc-tag right-leave)) 
                        (parse-leave (leave-for-offset rloc (dec (+ offset length)))) 
                      (not= (+ offset length) (start-offset right-leave))
                        (parse-node right-leave) 
                      (nil? (seq (previous-leaves right-leave)))
                        (parse-node right-leave)
                      :else
                        (parse-node (first (previous-leaves right-leave))))]
    (if (or
          (= [0 0] [offset length])
          (and 
            (= 0 length)
            (= (start-offset left-leave) offset))
          (and 
            (= (start-offset (parse-node left-leave)) offset)
            (= (end-offset (parse-node right-leave)) (+ offset length))  
            (same-parent? (parse-node left-leave) (parse-node right-leave)))) 
      (do
        [left-leave (when-not (zero? length) right-leave)])
      (do
        (let [left-leave (parse-node left-leave)
            right-leave (parse-node right-leave)
            min-depth (min (loc-depth left-leave) (loc-depth right-leave))
            left-leave (up-to-depth left-leave min-depth)
            right-leave (up-to-depth right-leave min-depth)]
        (first 
          (filter 
            (fn [[l r]] (= (z/up l) (z/up r))) 
            (iterate 
              (fn [[l r]] [(z/up l) (z/up r)])
              [left-leave right-leave])))))))) 

(defn sel-match-normalized? 
  "Does the selection denoted by offset and length match l (left) and r (right) locs ?"
  [offset length [l r]]
  (if (zero? length)
    (and (nil? r) (= offset (start-offset l)))
    (and (= offset (start-offset l)) (= (+ offset length) (end-offset r)))))

(defmethod paredit
  :paredit-expand-left
  [cmd {:keys [^String text offset length] :as t}]
  (let [parsed (parse text)]
    (if-let [rloc (-?> parsed (parsed-root-loc true))]
      (let [[l r] (normalized-selection rloc offset length)
            l (if (sel-match-normalized? offset length [l r])
                (if-let [nl (z/left l)] nl (if (punct-loc? l) (z/left (z/up l)) (z/up l)))
                (do
                  (spy [(z/node l) (and r (z/node r))])
                  (spy "not normalized!" l)))
            r (if (nil? r) l r)
            [l r] (normalized-selection rloc (spy (start-offset l)) (spy (- (end-offset r) (start-offset l))))]
          (spy (-> t (assoc-in [:offset] (start-offset l))
                (assoc-in [:length] (if (nil? r) 0 (- (end-offset r) (start-offset l)))))))
      t)))

(defmethod paredit
  :paredit-expand-up
  [cmd {:keys [^String text offset length] :as t}]
  (let [parsed (parse text)]
    (if-let [rloc (-?> parsed (parsed-root-loc true))]
      (let [[l r] (normalized-selection rloc offset length)]
        (if-not (sel-match-normalized? offset length [l r])
          (-> t (assoc-in [:offset] (start-offset l))
            (assoc-in [:length] (if (nil? r) 0 (- (end-offset r) (start-offset l)))))
          (let [l (if-let [nl (z/up (if (= offset (start-offset (parse-node l)))
                                        (parse-node l) 
                                        (parse-leave l)))]
                    nl 
                    l)
                _ (spy "l after up" (z/node l))]
            (-> t (assoc-in [:offset] (start-offset l))
              (assoc-in [:length] (- (end-offset l) (start-offset l)))))))
      t)))

(defmethod paredit
  :paredit-expand-right
  [cmd {:keys [^String text offset length] :as t}]
  (let [parsed (parse text)]
    (if-let [rloc (-?> parsed (parsed-root-loc true))]
      (let [[l r] (normalized-selection rloc offset length)]
        (if-not (sel-match-normalized? offset length [l r])
          (-> t (assoc-in [:offset] (start-offset l))
            (assoc-in [:length] (if (nil? r) 0 (- (end-offset r) (start-offset l)))))
          (let [r (if (nil? r) 
                    l 
                    (if-let [nr (z/right r)] 
                      nr
                      (z/up r)))
                [l r] (normalized-selection rloc (spy (start-offset l)) (spy (- (end-offset r) (start-offset l))))]
            (-> t (assoc-in [:offset] (start-offset l))
              (assoc-in [:length] (if (nil? r) 0 (- (end-offset r) (start-offset l))))))))
      t)))

(defmethod paredit
  :paredit-raise-sexp
  [cmd {:keys [^String text offset length] :as t}]
  (let [parsed (parse text)]
    (if-let [rloc (-?> parsed (parsed-root-loc true))]
      (let [[l r] (normalized-selection rloc offset length)]
        (if-not (and
                  (sel-match-normalized? offset length [l r]) 
                  (= offset (start-offset (parse-node l))))
          t
          (let  
            [to-raise-offset (start-offset l)
             to-raise-length (- (if r (end-offset r) (end-offset (parse-node l))) (start-offset l))
             to-raise-text (.substring text to-raise-offset (+ to-raise-offset to-raise-length))
             l (if-let [nl (z/up (parse-node l))] nl l)
             replace-offset (start-offset l)
             replace-length (- (end-offset l) replace-offset)]
            (-> t (assoc-in [:text] (t/str-replace text replace-offset replace-length to-raise-text))
              (assoc-in [:offset] replace-offset)
              (assoc-in [:length] 0)
              (update-in [:modifs] conj {:offset replace-offset :length replace-length :text to-raise-text})))))
      t)))

(defmethod paredit
  :paredit-split-sexp
  [cmd {:keys [^String text offset length] :as t}]
  (if (not= 0 length)
    t
    (let [parsed (parse text)]
      (if-let [rloc (-?> parsed (parsed-root-loc true))]
        (let [[l r] (normalized-selection rloc offset length)
              parent (cond
                       (= (str \") (loc-tag l)) l ; stay at the same level, and let the code take the correct open/close puncts, e.g. \" \"
                       :else (if-let [nl (z/up (if (start-punct? l) (parse-node l) (parse-leave l)))] nl (parse-leave l)))
              open-punct (loc-tag parent)
              _ (spy open-punct)
              close-punct ^String (*brackets* open-punct)]
          (if-not close-punct
            t
            (let [replace-text (str close-punct " " open-punct)
                  [replace-offset 
                   replace-length] (if (and
                                         (not= (str \space) (loc-tag l))
                                         (or
                                           (= (str \") (loc-tag l))
                                           (not (and
                                                  (sel-match-normalized? offset length [l r]) 
                                                  (= offset (start-offset (parse-node l)))))))
                                     [offset 0]
                                     (let [start (or (some #(when-not (= (str \space) (loc-tag %)) (end-offset %)) (previous-leaves l)) offset)
                                           end (or (some #(when-not (= (str \space) (loc-tag %)) (start-offset %)) (next-leaves l)) 0)]
                                       [start (- end start)]))
                                   new-offset (+ replace-offset (.length close-punct))]
              (-> t (assoc-in [:text] (t/str-replace text replace-offset replace-length replace-text))
                (assoc-in [:offset] new-offset)
                (update-in [:modifs] conj {:offset replace-offset :length replace-length :text replace-text})))))
        t))))

(defmethod paredit
  :paredit-join-sexps
  [cmd {:keys [^String text offset length] :as t}]
  (spy "++++" t)
  (if (not= 0 length)
    t
    (let [parsed (parse text)]
      (if-let [rloc (-?> parsed (parsed-root-loc true))]
        (let [[l _] (normalized-selection rloc offset length)
              _ (spy "node" (z/node l))
              lf (first (remove #(= (str \space) (loc-tag %)) (previous-leaves l)))
              _ (spy "lf" (z/node lf))
              rf (first (remove #(= (str \space) (loc-tag %)) (cons l (next-leaves l))))
              _ (spy "lr" (z/node rf))]
          (if (or (nil? lf) (nil? rf) (start-punct? lf) (end-punct? rf))
            t
            (let [ln (parse-node lf)
                  rn (parse-node rf)
                  _ (spy "ln"  (z/node ln))
                  _ (spy "rn" (z/node rn))] 
              (if-not (and
                        (= (loc-tag ln) (loc-tag rn)))
                t
                (let [replace-offset (- (end-offset ln) (if-let [punct ^String (*brackets* (loc-tag ln))] (.length punct) 0))
                      replace-length (- (+ (start-offset rn) (if (*brackets* (loc-tag rn)) (.length (loc-tag rn)) 0)) replace-offset)
                      replace-text   (if (#{(str \") (str \a)} (loc-tag ln)) "" " ")
                      new-offset (if (= offset (start-offset rn)) (+ replace-offset (.length replace-text)) replace-offset)]
                  (-> t (assoc-in [:text] (t/str-replace text replace-offset replace-length replace-text))
                    (assoc-in [:offset] new-offset)
                    (update-in [:modifs] conj {:offset replace-offset :length replace-length :text replace-text})))))))
        t))))

(defn wrap-with-balanced
  [[^String o c] {:keys [^String text offset length] :as t}]
  (let [bypass #(-> t 
                  (update-in [:text] t/str-replace offset length o)
                  (update-in [:offset] + (.length o))
                  (assoc-in [:length] 0)
                  (update-in [:modifs] conj {:text o :offset offset :length length}))
        parsed (parse text)]
    (if-let [rloc (-?> parsed (parsed-root-loc true))]
      (let [left-leave (some (fn [l] (when (not= " " (loc-tag l)) l)) (next-leaves (leave-for-offset rloc offset)))
            right-leave (some (fn [l] (when (not= " " (loc-tag l)) l)) (previous-leaves (leave-for-offset rloc (+ offset length))))
            right-leave (if (or (nil? right-leave) (<= (start-offset right-leave) (start-offset left-leave))) left-leave right-leave)]
        (if (or
              (not (in-code? (loc-containing-offset rloc offset)))
              (not (in-code? (loc-containing-offset rloc (+ offset length))))
              (> offset (start-offset left-leave))
              (and (not= 0 length) (or (< (+ offset length) (end-offset right-leave))
                                           (not= (z/up (loc-parse-node left-leave)) (z/up (loc-parse-node right-leave))))))
          (bypass)
          (let [text-to-wrap (.substring text (start-offset (z/up left-leave)) (end-offset (z/up right-leave))) 
                new-text (str o text-to-wrap c)
                t (update-in t [:text] t/str-replace (start-offset left-leave) (.length text-to-wrap) new-text)
                t (assoc-in t [:offset] (inc (start-offset left-leave)))]
            (update-in t [:modifs] conj {:text new-text :offset (start-offset left-leave) :length (.length text-to-wrap)})))) 
      (bypass))))

(defmethod paredit
  :paredit-wrap-square
  [cmd t]
  (wrap-with-balanced ["[" "]"] t))

(defmethod paredit
  :paredit-wrap-curly
  [cmd t]
  (wrap-with-balanced ["{" "}"] t))

(defmethod paredit
  :paredit-wrap-round
  [cmd t]
  (wrap-with-balanced ["(" ")"] t))

(defmethod paredit
  :paredit-newline
  [cmd {:keys [text offset length] :as t}]
  (let [r (paredit :paredit-indent-line 
            {:text (t/str-insert text offset "\n") 
             :offset (inc offset) 
             :length length 
             :modifs [{:text *newline* :offset offset :length 0}]})]
    (if (-?> r :modifs count (= 2))
      (let [m1 (get-in r [:modifs 0])
            m2 (get-in r [:modifs 1])
            r  (assoc-in r [:modifs] [{:text (str (:text m1) (:text m2)) :offset offset :length (+ (:length m1) (:length m2))}])
            r  (assoc-in r [:offset] (+ (.length ^String (get-in r [:modifs 0 :text])) offset))]
        r)
      r)))
  
(defmethod paredit
  :paredit-indent-line
  [cmd {:keys [^String text offset length] :as t}]
  (spy "++++ indent line +++++")
  (if-let [rloc (-?> (parse text (.length text)) (parsed-root-loc true))]
    (let [line-start (spy (t/line-start (spy text) (spy offset)))
          line-stop (t/line-stop text offset)
          loc (-> rloc (loc-for-offset line-start))]
      (if (and (= (str \") (loc-tag loc)) (< (:offset (z/node loc)) line-start))
        t
        (let [indent (spy (indent-column rloc line-start))
              cur-indent-col (or (some (fn [l]
                                         (when (not= (str \space) (loc-tag l)) 
                                           (- (start-offset l) line-start)))
                                   (filter (fn [l] (<= line-start (start-offset l) line-stop)) 
                                     (next-leaves loc)))
                               (- line-stop line-start))
              to-add (- indent cur-indent-col)]
          (cond
            (zero? to-add) t
            :else (let [t (update-in t [:modifs] conj {:text (str2/repeat " " indent) :offset line-start :length cur-indent-col})
                        t (update-in t [:text] t/str-replace line-start cur-indent-col (str2/repeat " " indent))]
                    (cond 
                      (>= offset (+ line-start cur-indent-col)) 
                        (update-in t [:offset] + to-add)
                      (<= offset (+ line-start indent))
                        t
                      :else
                        (update-in t [:offset] + (max to-add (- line-start offset)))))))))
    t))
