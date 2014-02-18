(ns core.fdb)

(defrecord Entity [id name attrs])
(defrecord Attr [name type value ts prev-ts])
(defrecord Database [timestamped top-id curr-time])
(defrecord Indices [EAVT AEVT])
(defn make-db[] (atom (Database. [(Indices. {} {})] 0 0) ;EAVT: all the entity info, AEVT for attrs who are REFs, we hold the back-pointing (from the REFFed entity to the REFing entities)
     )) ;


(defn make-entity  ([name] (make-entity :no-id-yet name))
  ([id name] (Entity.  id name {})))

(defn val-from-ref[attr-type attr-val](if (= :REF attr-type) (:id attr-val) attr-val ))

(defn make-attr[name value type]  (Attr. name type (val-from-ref type value) -1 -1))
(defn add-attr[ ent attr] (assoc-in ent [:attrs (keyword (:name attr))] attr))

(defn next-ts [db] (inc (:curr-time db)))

(defn nextId[db ent] (let   [ top-id (:top-id db)
                                        entId (:id ent)
                                       [idToUse nextTop] (if (= entId :no-id-yet) [(inc top-id) (inc top-id)] [entId top-id])]
                              [idToUse (keyword (str idToUse)) nextTop]))

(defn update-creation-ts [ent tsVal]
  (let [ks (keys (:attrs ent))
        vls (vals (:attrs ent))
        updatedAttrsVals (map #(assoc % :ts tsVal) vls)
        updatedAttrs (zipmap ks updatedAttrsVals)
        ](assoc ent :attrs updatedAttrs)))

;  AEVT -> {REFed-ent-id -> {attrName -> [REFing-elems-ids]}}
; this basically provides the info - for each entity that is REFFed by others, who are the others who are REFing it, separated
; by the names of the attribute used for reffing
(defn add-ref-to-aevt[ent operation aevt attr]
  (let [reffed-id (:value attr)
        attr-name (:name attr)
        back-reffing-set (get-in aevt [reffed-id attr-name] #{} )
        new-back-reffing-set (operation back-reffing-set (:id ent))
        ] (assoc-in aevt [reffed-id attr-name] new-back-reffing-set)))

(defn update-aevt[old-aevt ent operation]
  (let [reffingAttrs (filter #(= :REF (:type %)) (vals (:attrs ent)))
        add-ref (partial add-ref-to-aevt ent operation)]
       (reduce add-ref old-aevt reffingAttrs)))

;when adding an entity, its attributes' timestamp would be set to be the current one
(defn add-entity[db ent]   (let [[ent-id ent-id-key next-top] (nextId db ent)
                                 new-ts (next-ts db)
                                 indices (last (:timestamped db))
                                 fixed-ent (assoc ent :id ent-id-key)
                                 new-eavt (assoc (:EAVT indices) ent-id-key  (update-creation-ts fixed-ent new-ts) )
                                 new-aevt (update-aevt  (:AEVT indices) fixed-ent conj)
                                 new-indices (assoc indices :AEVT new-aevt :EAVT new-eavt )
                                ](assoc db :timestamped  (conj (:timestamped db) new-indices)
                                                 :top-id next-top)))

(defn remove-entity[db ent]
  (let [ent-id (:id ent)
         indices (last (:timestamped db))
        aevt (update-aevt  (:AEVT indices) ent disj)
        new-eavt (dissoc (:EAVT indices) ent-id) ; removing the entity
        new-aevt (dissoc aevt ent-id) ; removing incoming REFs to the entity
        new-indices (assoc indices :EAVT new-eavt :AEVT new-aevt)
        res  (assoc db :timestamped (conj  (:timestamped db) new-indices))]
        res))

(defn transact-on-db [initial-db  txs]
    (loop [[tx & rst-tx] txs transacted initial-db]

      (if tx    (recur rst-tx (apply (first tx) transacted (rest tx)))
                 (let [ initial-indices  (:timestamped initial-db)
                          new-indices (last (:timestamped transacted))
                        res (assoc initial-db :timestamped (conj  initial-indices new-indices)
                                           :curr-time (next-ts initial-db)
                                           :top-id (:top-id transacted))]
                  res))))

(defmacro transact_ [db op & txs]
  (when txs
    (loop              [[frst-tx# & rst-tx#] txs         res#   [op db 'transact-on-db]               accum-txs# []]
      (if frst-tx#     (recur                     rst-tx#              res#                                          (conj  accum-txs#  (vec  frst-tx#)))
                           (list* (conj res#  accum-txs#))))))

(defn _what-if [ db f  txs] (f db txs))

(defmacro what-if [db & txs]  `(transact_ ~db   _what-if  ~@txs))
(defmacro transact [db & txs] `(transact_ ~db swap! ~@txs))

(defn update-aevt-for-datom [aevt  ent-id attr new-val]
  (if (not= :REF (:type attr ))
    aevt
    (let [ old-ref-id (:value attr)
             attr-name (:name attr)
             old-reffed (get-in aevt [old-ref-id attr-name])
             cleaned-aevt (assoc-in aevt [old-ref-id attr-name] (disj old-reffed ent-id))
             to-be-updated-ref (get-in aevt [new-val attr-name] #{})
             updated-aevt (assoc-in cleaned-aevt [new-val attr-name] (conj  to-be-updated-ref ent-id) )
          ] updated-aevt)))

(defn update-datom [db ent-id att-name  new-val]
     (let [ new-ts (next-ts db)
            indices (last (:timestamped db))
            attr (get-in indices [:EAVT ent-id :attrs  att-name] )
            real-new-val  (val-from-ref (:type attr) new-val)
            updated-attr(assoc attr :value real-new-val :ts new-ts :prev-ts ( :ts attr))
            eavt-updated-indices (assoc-in indices [:EAVT ent-id :attrs att-name] updated-attr )
            new-aevt (update-aevt-for-datom (:AEVT indices) ent-id attr real-new-val)
            fully-updated-indices (assoc eavt-updated-indices :AEVT new-aevt)
            new-db (assoc db :timestamped (conj  (:timestamped db) fully-updated-indices))
           ]new-db))

(defn entity-at ([db ent-id ts] ((keyword ent-id) ((:timestamped db) ts)))
                      ([db ent-id] (entity-at db ent-id (:curr-time db))) )

(defn attr-at "The attribute of an entity at a given time (defaults to recent time)"
  ([db ent-id attr-name] (attr-at db ent-id attr-name (:curr-time db)))
  ([db ent-id attr-name ts]
   (let [indices ((:timestamped db) ts)]  (get-in indices [:EAVT ent-id :attrs attr-name]))))

(defn value-of-at  "value of a datom at a given time, if no time is provided, we default to the most recent value"
  ([db e-id attr-name]  (:value (attr-at db e-id attr-name)))
  ([db e-id attr-name ts] (:value (attr-at db e-id attr-name ts))))

(defn relates-to-as "returns a seq of all the entities that REFed to a specific entity with the given attr-name (alternativly had an attribute named attr-name whose type is REF and the value was e-id), all this at a given time"
   ([db e-id attr-name]  (relates-to-as db e-id attr-name (:curr-time db)))
  ([db e-id attr-name ts]
      (let [indices ((:timestamped db) ts)
              reffing-ids (get-in indices [:AEVT e-id attr-name])
            ]
        (map #(get-in indices [:EAVT %]) reffing-ids ))))

(defn evolution-of "The sequence of the values of of an entity's attribute, as changed through time" [db ent-id attr-name]
  (loop [res [] ts (:curr-time db)]
    (if (= -1 ts) (reverse res)
        (let [attr (attr-at db ent-id attr-name ts)]
          (recur (conj res {ts (:value attr)})  (:prev-ts attr))))))
