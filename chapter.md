#Designing a database like an archaeologist

Software development is often viewed as a rigorous process, where the inputs are requirements and the output is the working product. However, software developers are people with their own perspectives and biases which color the outcome of their work. 

In this chapter, we will explore how a change in a common perspective affects the design and implementation of a well-studied type of software -- a database.

## Introduction 

Database systems are designed to store and query data. This is something that all information workers do; however, the systems themselves were designed by computer scientists. As a result, modern database systems are highly influenced by what a computer scientist’s definition of data is, and what can be done with it. 

For example, most modern databases implement updates by overwriting old data in-place instead of appending the new data and keeping the old. This mechanism, nicknamed "place oriented programming" by Rich Hickey [REF], saves storage space but makes it impossible to retrieve the entire history of a particular record. This design decision reflects the computer scientist’s perspective that ‘history’ is less important than the price of its storage. 

If you were to instead ask an archaeologist what should be done with the old data, the answer would be "hopefully, it’s just buried underneath".

(Disclaimer: my understanding of a typical archaeologist is based on a few museum visits, several wikipedia articles, and watching the entire Indiana Jones series).

### From archaeology to databases

If we were to ask our friendly archaeologist to design a database, we might expect the requirements to reflect what would be found at an *excavation site:*

* All data is found and cataloged at the site
* Digging deeper will expose the state of things in times past 
* Artefacts found at the same layer are from the same period
* Each artefact will consist of state that it accumulated in different periods 

For example, a wall may have Roman symbols drawn on it at on one layer, and in a lower layer there may be Greek symbols. Both these observations are recorded as part of the wall's state.

This analogy is visualized in Figure 1:

* The entire circle is the excavation site
* Each ring is a _layer_ (here numbered from 0 to 4) 
* Each slice is a labeled artefact (‘a’ through ‘e’)
* Each artefact has a ‘color’ attribute (where white means that no update was made)
* Black arrows denote a change in color between layers (e.g., from c.color @t2 to c.color @t0)
* Light blue arrows are arbitrary relationships of interest between entities (e.g., from ‘b’ to ‘d’)

 ![image alt text](image_0.png)

Figure 1

If we translate the archaeologist's language into terms a database designer would use:
* The excavation site is a _database_
* Each artefact is an _entity_ with a corresponding _id_
* Each entity has a set of _attributes_, which may change over time
* Each attribute has a specific _value_ at a specific time

This may look very different than the kinds of databases you are used to working with. This design is sometimes referred to as "functional database", since it uses ideas from the domain of functional programming. The rest of the chapter describes how to implement such a database.

Since we are building a functional database, we will be using a functional programming language called Clojure to do the job.

There are several qualities of Clojure that make it a good implementation language for a functional database, such as out-of-the-box immutability, higher order functions, and metaprogramming facilities. Ultimately, the reason Clojure was chosen is its emphasis on clean, rigorous design which few programming languages possess. 

## Laying the foundation

Let’s start by declaring the core constructs that make up our database. 

````clojure
(defrecord Database [layers top-id curr-time])
`````

A database consists of:

1. Layers of entities, each with its own unique timestamp (rings in Figure 1)
2. A top-id value which is used to generate unique ids
3. The time at which this database was last updated


````clojure
(defrecord Layer [storage VAET AVET VEAT EAVT])
````
Each layer consists of: 

1. A data store for entities

2. Indexes that are used to speed up queries to the database (these indexes and the meaning of their names will be explained later.) 

In our design, a single conceptual ‘database’ may consist of many *Database* instances, each of which represents a snapshot of the database at *curr-time*. A *Layer* may share the exact same entity with another *Layer* if the entity’s state hasn’t changed between the times that they represent.

### Entities

Our database wouldn't be of any use without entities to store, so we define those next. As discussed before, an entity has an *id* and a list of *attributes*; we create them using the *make-entity* function.

````clojure
(defrecord Entity [id attrs])

(defn make-entity
   ([] (make-entity :db/no-id-yet))
   ([id] (Entity.  id {})))
````
Note that if no id is given, the entity’s id is set to be *:db/no-id-yet*, which means that something else is responsible for giving it an id. We’ll see how that works later.

**Attributes**

Each attribute consists of its name, value, and the timestamps of both its most recent and previous update. In addition to these fields, each attribute has two fields that describe its *type* and *cardinality*. 

In the case that an attribute is used to represent a relationship to another entity, its *type* will be *:db/ref* and its value will be the id of the related entity. This simple type system also acts as an extension point. Users are free to define their own types and leverage them to provide additional semantics for their data.

An attribute's *cardinality* specifies whether the attribute represents a single value or a set of values. We use this field to determine the set of operations that are permitted on this attribute.

Similar to entities, creating an attribute is done using the *make-attr* function 

````clojure
(defrecord Attr [name value ts prev-ts])

(defn make-attr
   ([name value type ; these ones are required
       & {:keys [cardinality] :or {cardinality :db/single}} ]
     {:pre [(contains? #{:db/single :db/multiple} cardinality)]}
    (with-meta (Attr. name value -1 -1) {:type type :cardinality cardinality})))
````
There are a couple of interesting patterns used in this constructor function: 

* We use Clojure’s _Design by Contract_ [ADD REF HERE] pattern to validate that the cardinality parameter is a permissible value
* We use Clojure’s destructuring mechanism to provide a default value of *:db/single* if one is not given
* We use Clojure’s metadata capabilities to separate between an attribute's data (name, value and timestamps) and its metadata (type and cardinality). In Clojure, metadata handling is done using the *with-meta* (setting metadata) and *meta* (reading metadata) functions.

Attributes only have meaning if they are part of an entity. We make this connection with the *add-attr* function, which adds a given attribute to an entity's attribute map (called *:attrs*). 

Note that instead of directly using the attribute’s name, we first convert it into a keyword to adhere to Clojure’s idiomatic usage of maps.

````clojure
(defn add-attr [ent attr]
   (let [attr-id (keyword (:name attr))]
      (assoc-in ent [:attrs attr-id] attr)))
```

### Storage

So far, we have talked a lot about _what_ we are going to store, without thinking about _where_ we are going to store it. In this chapter, we resort to the simplest storage mechanism, which is storing the data in memory. This is certainly not reliable, but it simplifies development and debugging and allows us to focus on more interesting parts of the program. 

We will access the storage via a simple _protocol_ that will make it possible to add more durable storage providers in the future.

````clojure
(defprotocol Storage
   (get-entity [storage e-id] )
   (write-entity [storage entity])
   (drop-entity [storage entity]))
````

And here's our in-memory implementation of the protocol, which uses a map as the backing store:

````clojure
(defrecord InMemory [] Storage
   (get-entity [storage e-id] (e-id storage))
   (write-entity [storage entity] (assoc storage (:id entity) entity))
   (drop-entity [storage entity] (dissoc storage (:id entity))))
````

### Querying our data

Now that we've defined the basic elements of our database, we can start thinking about how we're going to query it. By virtue of how we've structured our data, any query is necessarily going to be interested in at least one of an entity's id, and the name and value of some of its attributes. This triplet of (entity-id, attribute-name, attribute-value) is important enough to our query process that we give it an explicit name -- a _datom_.

The reason that datoms are so important is that they represent facts, and our database accumulates facts. 

If you've used a database system before, you are probably already familiar with the concept of an _index_, which is a supporting data structure that consumes extra space in order to decrease the average query time.  In our database, an index is a three-leveled structure, which stores the components of a datom in a specific order. Each index derives its name from the order it stores the datom's components.

For example, let’s look at at the index sketched in Figure 2:
* the first level stores entity-ids (the blue-ish area) 
* the second level stores the related attribute-names (the green-ish area)
* the third level stores the related value (the pink-ish area)

This index is named EAVT, as the top level map holds (E) entity ids, the second level holds (A) attribute names, and the leaves hold (V) values. The (T) comes from the fact that each layer in the database has its own indexes, hence the index itself is relevant for a specific (T) time. 

![image alt text](image_1.png)

Figure 2

Figure 3 shows an index that would be called AVET since:

* First level map holds attribute-name
* Second level map holds the values (of the attributes)
* Third level set holds the entity-ids (of the entities whose attribute is at the first level) 

![image alt text](image_2.png)

Figure 3

Our indexes are implemented as a map of maps, where the keys of the root map act as the first level, each such key points to a map whose keys act as the index’s second-level and the values are the index’s third level. Each element in the third level is a set, holding the leaves of the index.

Each index stores the components of a datom as some permutation of its canonical 'EAV' ordering (entity_id, attribute-name, attribute-value). However, when we are working with datoms _outside_ of the index, we expect them to be in canonical format. We thus provide each index with functions *from-eav* and *to-eav* to convert to and from these orderings.

In most database systems, indexes are an optional component; for example, in an RDMBS like postgresql or mysql, you will choose to add indexes only to certain columns in a table. We provide each index with a *usage-pred* function that determines whether an attribute and decides whether that attribute should be included in this index or not. 

````clojure
(defn make-index [from-eav to-eav usage-pred]
    (with-meta {} {:from-eav from-eav :to-eav to-eav :usage-pred usage-pred}))
 
 (defn from-eav [index] (:from-eav (meta index)))
 (defn to-eav [index] (:to-eav (meta index)))
 (defn usage-pred [index] (:usage-pred (meta index)))
````

In our database there are four indexes - EAVT (as depicted in Figure 2), AVET (as can be seen in Figure 3), VEAT and VAET. We can access these as a vector of values returned from the *indexes* function.

````clojure
(defn indexes[] [:VAET :AVET :VEAT :EAVT])
````

To see how all of this comes together, the result of indexing the following five entities is visualized table below (the color coding follows the color coding of Figure 2 and Figure 3)

1. <span style="background-color:lightblue">Julius Caesar</span> (also known as JC) <span style="background-color:lightgreen">lives in</span> <span style="background-color:pink">Rome</span> 
2. <span style="background-color:lightblue">Brutus</span> (also known as B) <span style="background-color:lightgreen">lives in</span> <span style="background-color:pink">Rome</span> 
3. <span style="background-color:lightblue">Cleopatra</span> (also known as Cleo) <span style="background-color:lightgreen">lives in</span> <span style="background-color:pink">Egypt</span>
4. <span style="background-color:lightblue">Rome</span>’s <span style="background-color:lightgreen">river</span> is the <span style="background-color:pink">Tiber</span>
5. <span style="background-color:lightblue">Egypt</span>’s <span style="background-color:lightgreen">river</span> is the <span style="background-color:pink">Nile</span>
 
<table>
  <tr>
    <td>EAVT index</td>
    <td>AVET index</td>
  </tr>
  <tr>
    <td><ul>
<li>
<span style="background-color:lightblue">JC</span> ⇒ {<span style="background-color:lightgreen">lives-in</span> ⇒ {<span style="background-color:pink">Rome</span>}}
</li>
<li>
<span style="background-color:lightblue">B</span>  ⇒ {<span style="background-color:lightgreen">lives-in</span> ⇒ {<span style="background-color:pink">Rome</span>}}
</li>
<li>
<span style="background-color:lightblue">Cleo</span> ⇒ {<span style="background-color:lightgreen">lives-in</span> ⇒ {<span style="background-color:pink">Egypt</span>}}
</li>
<li>
<span style="background-color:lightblue">Rome</span> ⇒ {<span style="background-color:lightgreen">river</span> ⇒ {<span style="background-color:pink">Tiber</span>}}
</li>
<li>
<span style="background-color:lightblue">Egypt</span> ⇒ {<span style="background-color:lightgreen">river</span> ⇒ {<span style="background-color:pink">Nile</span>}}
</li>
</ul></td>
<td><ul>
<li>
<span style="background-color:lightgreen">lives-in</span> ⇒ {<span style="background-color:pink">Rome</span> ⇒ {<span style="background-color:lightblue">JC, B</span>}}</br>
                         {<span style="background-color:pink">Egypt</span> ⇒ {<span style="background-color:lightblue">Cleo</span>}}
</li>
<li>
<span style="background-color:lightgreen">river</span> ⇒ {<span style="background-color:pink">Rome</span> ⇒ {<span style="background-color:lightblue">Tiber</span>}}</br>
{<span style="background-color:pink">Egypt</span> ⇒ {<span style="background-color:lightblue">Nile</span>}}
</li>
</ul></td>
  </tr>
  <tr>
    <td>VEAT index</td>
    <td>VAET index</td>
  </tr>
  <tr>
    <td><ul>
<li>
<span style="background-color:pink">Rome</span> ⇒ {<span style="background-color:lightblue">JC</span> ⇒ {<span style="background-color:lightgreen">lives-in</span>}}<br/>
{<span style="background-color:lightblue">B</span> ⇒ {<span style="background-color:lightgreen">lives-in</span>}}
</li>
<li>
<span style="background-color:pink">Egypt</span> ⇒ {<span style="background-color:lightblue">Cleo</span> ⇒ {<span style="background-color:lightgreen">lives-in</span>}}
</li>
<li>
<span style="background-color:pink">Tiber</span> ⇒ {<span style="background-color:lightblue">Rome</span> ⇒ {<span style="background-color:lightgreen">river</span>}}
</li>
<li>
<span style="background-color:pink">Nile</span> ⇒ {<span style="background-color:lightblue">Egypt</span> ⇒ {<span style="background-color:lightgreen">river</span>}}
</li></ul></td>
<td><ul>
<li>
<span style="background-color:pink">Rome</span> ⇒ {<span style="background-color:lightgreen">lives-in</span> ⇒ {<span style="background-color:lightblue">JC, B</span>}}
</li>
<li>
<span style="background-color:pink">Egypt</span> ⇒ {<span style="background-color:lightgreen">lives-in</span> ⇒ {<span style="background-color:lightblue">Cleo</span>}}</li>
<li>
<span style="background-color:pink">Tiber</span> ⇒ {<span style="background-color:lightgreen">river</span> ⇒ {<span style="background-color:lightblue">Rome</span>}}
</li>
<li>
<span style="background-color:pink">Nile</span> ⇒ {<span style="background-color:lightgreen">river</span> ⇒ {<span style="background-color:lightblue">Egypt</span>}}
</li></ul></td>
  </tr>
</table>
Table 2

### Database

We now have all the components we need to construct our database! Initializing our database means:

* creating an initial empty layer with no data 
* creating a set of empty indexes
* settings its top-id and curr-time to be 0 and its curr-time to be 0 

````clojure
(defn ref? [attr] (= :db/ref (:type (meta attr))))

(defn always[& more] true)

(defn make-db []
   (atom 
       (Database. [(Layer.
                   (fdb.storage.InMemory.) ; storage
                   (make-index #(vector %3 %2 %1) #(vector %3 %2 %1) #(ref? %)); VAET                     
                   (make-index #(vector %2 %3 %1) #(vector %3 %1 %2) always); AVET                        
                   (make-index #(vector %3 %1 %2) #(vector %2 %3 %1) always); VEAT                       
                   (make-index #(vector %1 %2 %3) #(vector %1 %2 %3) always); EAVT
                  )] 0 0)))
````

There is one snag, though -- all collections in Clojure are immutable. Since write operations are pretty critical in a database, we call **atom** on our structure first. This is one of Clojure’s reference types, and it provides atomic writes to the element it wraps. 

You may be wondering why we use the *always* function for the AVET, VEAT and EAVT indexes, and the *ref?* predicate for the VAET index. This is because these indexes are used in different scenarios, which we’ll see later when we explore queries in depth.

### Basic accessors

Before we can build complex querying facilities for our database, we need to provide a lower-level API that different parts of the system can use to retrieve the components we've built thus far by their associated identifiers from any point in time. Consumers of the database can also use this API; however, it is more likely that they will be using the more fully-featured components built on top of it.

This lower-level API is composed of the following four accessor functions:

````clojure
(defn entity-at
   ([db ent-id] (entity-at db (:curr-time db) ent-id))
   ([db ts ent-id] (stored-entity (get-in db [:layers ts :storage]) ent-id)))

(defn attr-at
   ([db ent-id attr-name] (attr-at db ent-id attr-name (:curr-time db)))
   ([db ent-id attr-name ts] (get-in (entity-at db ts ent-id) [:attrs attr-name])))

(defn value-of-at
   ([db ent-id attr-name]  (:value (attr-at db ent-id attr-name)))
   ([db ent-id attr-name ts] (:value (attr-at db ent-id attr-name ts))))

(defn ind-at
   ([db kind] (ind-at db kind (:curr-time db)))
   ([db kind ts] (kind ((:layers db) ts))))
````

Since we treat our database just like any other value, each of these functions take a database as an argument. Each element is retrieved by its associated identifier, and optionally the timestamp of interest. This timestamp is used in each case to find the corresponding layer that our lookup should be applied to.

#### Evolution

A first usage of the basic accessors is to provide a "read-into-the-past" API. This is possible as in our database, an update operation is done by appending a new layer (as oppose to overwriting). Therefore we can use the *prev-ts* property of an attribute look at the attribute at that layer, and continue going back in time looking deeper into history, thus observing the how the attribute’s value evolved throughout time.  

The function *evolution-of* does exactly that, and return a sequence of pairs - each consist of the timestamp and value of an attribute’s update.

````clojure
(defn evolution-of [db ent-id attr-name]
   (loop [res [] ts (:curr-time db)]
     (if (= -1 ts) (reverse res)
         (let [attr (attr-at db ent-id attr-name ts)]
           (recur (conj res {(:ts attr) (:value attr)})  (:prev-ts attr))))))
````

## Data behavior and life cycle

So far, our discussion has focused on the structure of our data -- what the core components are, and how they are aggregated together. It's time now to explore the dynamics of our system; how data is changed over time through the _data lifecycle_ (add => update => remove). 

As we've already discussed, data in an archaeologist's world never actually changes. Once it is created, it exists forever and can only be hidden from the world by data in a newer layer. Note the term ‘hidden’, as it is crucial here. Older data does not 'disappear' -- it is buried, and can be revealed again by exposing an older layer. Conversely, updating data means obscuring the old by adding a new layer on top of it with something else. We can thus 'delete' data by adding a layer of 'nothing' on top of it. 

This means that when talking about data lifecycle, we are really talking about adding layers to our data over time. 

### The bare necessities

The data lifecycle consists of three basic operations, which we will discuss here:

* adding an entity with the *add-entity* function
* removing an entity with the *remove-entity* function
* updating an entity with the *update-entity* function. 

Remember that, even though these functions provide the illusion of mutability, all that we are really doing in each case is adding another layer to the data. Also, since we use here Clojure's immutable data structures, we pay for such operations the price of an "in-place" change from the caller's perspective, while maintaining immutability for all other users of that data structure.

#### Adding an entity

Adding an entity requires us to do three things:

* prepare the entity for addition (by giving it an id and a timestamp)
* place the entity in storage 
* update indexes as necessary

These steps are performed in the *add-entity* function
````clojure
(defn add-entity [db ent]
   (let [[fixed-ent next-top-id] (fix-new-entity db ent)
         layer-with-updated-storage (update-in 
                            (last (:layers db)) [:storage] write-entity fixed-ent)
         add-fn (partial add-entity-to-index fixed-ent)
         new-layer (reduce add-fn layer-with-updated-storage (indexes))]
    (assoc db :layers (conj (:layers db) new-layer) :top-id next-top-id)))
````

Preparing an entity is done calling the *fix-new-entity* function and its auxiliary functions *next-id*, *next-ts* and *update-creation-ts*. 
These latter two helper functions are responsible for finding the next timestamp of the database(done by *next-ts* function), and updating the creation timestamp of the given entity. Updating the creation timestamp of an entity means going over the attributes of the entity and update their *:ts* field (done by the *update-creation-ts* function).

````clojure
(defn- next-ts [db] (inc (:curr-time db)))

(defn- update-creation-ts [ent ts-val]
   (reduce #(assoc-in %1 [:attrs %2 :ts ] ts-val) ent (keys (:attrs ent))))

(defn- next-id [db ent]
   (let [top-id (:top-id db)
         ent-id (:id ent)
         increased-id (inc top-id)]
         (if (= ent-id :db/no-id-yet)
             [(keyword (str increased-id)) increased-id]
             [ent-id top-id])))

(defn- fix-new-entity [db ent]
   (let [[ent-id next-top-id] (next-id db ent)
         new-ts               (next-ts db)]
       [(update-creation-ts (assoc ent :id ent-id) new-ts) next-top-id]))
````

To add the entity to storage, we locate the most recent layer in the database and update the storage in that layer with a new layer. The results of this operation are assigned to the *layer-with-updated-storage* local variable.

Finally, we must update the indexes. This means:

1. For each of the indexes (done by the combination of *reduce* and the *partial*-ed *add-entity-to-index* at the *add-entity* function)
2. Find the attributes that should be indexed (see the combination of *filter* with the index’s *usage-pred* that operates on the attributes in *add-entity-to-index*) 
3. build an index-path from the the entity’s id (see the combination of the *partial*-ed *update-entry-in-index* with *from-eav* at the *update-attr-in-index* function)
4. Add that path to the index (see the *update-entry-in-index* function)

````clojure
(defn- add-entity-to-index [ent layer ind-name]
   (let [ent-id (:id ent)
         index (ind-name layer)
         all-attrs  (vals (:attrs ent))
         relevant-attrs (filter #((usage-pred index) %) all-attrs)
         add-in-index-fn (fn [ind attr] 
                                 (update-attr-in-index ind ent-id (:name attr) (:value attr) :db/add))]
        (assoc layer ind-name  (reduce add-in-index-fn index relevant-attrs))))

(defn- update-attr-in-index [index ent-id attr-name target-val operation]
   (let [colled-target-val (collify target-val)
         update-entry-fn (fn [indx vl] 
                                 (update-entry-in-index indx ((from-eav index) ent-id attr-name vl) operation))]
     (reduce update-entry-fn index colled-target-val)))
     
(defn- update-entry-in-index [index path operation]
   (let [update-path (butlast path)
         update-value (last path)
         to-be-updated-set (get-in index update-path #{})]
     (assoc-in index update-path (conj to-be-updated-set update-value))))
````
All of these components are added as a new layer to the given database; all that’s left is to update the database’s timestamp and top-id fields. That last step occurs on the last line of *add-entity*, which also returns the updated database.

We also provide an *add-entities* convenience function that adds multiple entities to the database in one call by iteratively applying *add-entity*.

````clojure
(defn add-entities [db ents-seq] (reduce add-entity db ents-seq))
````
#### Removing an entity

Removing an entity from our database means adding a layer in which it does not exist. To do this, we need to:

* remove the entity itself
* update any attributes of other entities that reference it 
* clear the entity from our indexes

This "construct-without" process is executed by the *remove-entity* function, which looks very similar to *add-entity*:

````clojure
(defn remove-entity [db ent-id]
   (let [ent (entity-at db ent-id)
         layer (remove-back-refs db ent-id (last (:layers db)))
         no-ref-layer (update-in layer [:VAET] dissoc ent-id)
         no-ent-layer (assoc no-ref-layer :storage 
                                   (drop-entity  
                                          (:storage no-ref-layer) ent))
         new-layer (reduce (partial remove-entity-from-index ent) 
                                 no-ent-layer (indexes))]
     (assoc db :layers (conj  (:layers db) new-layer))))
````

Reference removal is done by the *remove-back-refs* function:

````clojure
(defn- remove-back-refs [db e-id layer]
   (let [reffing-datoms (reffing-to e-id layer)
         remove-fn (fn[d [e a]] (update-entity db e a e-id :db/remove))
         clean-db (reduce remove-fn db reffing-datoms)]
     (last (:layers clean-db))))
````

We begin by using *reffing-datoms-to* to find all entities that reference ours in the given layer; it returns a sequence of triplets that contain the id of the referencing entity, as well as the attribute name and the id of the removed entity.

````clojure
(defn- reffing-to [e-id layer]
   (let [vaet (:VAET layer)]
         (for [[attr-name reffing-set] (e-id vaet)
               reffing reffing-set]
              [reffing attr-name])))

````
We then apply *update-entity* to each triplet to update the attributes that reference our removed entity. (We'll explore how *update-entity* works in the next section.)

The last step of *remove-back-refs* is to clear the reference it self from our indexes, and more specifically - from the VAET index, since it is the only index that stores references information. 

#### Updating an entity

At its essence, an update is the modification of an entity’s attribute’s value. The modification process itself depends on the cardinality of the attribute: an attribute with cardinality *:db/multiple* holds a set of values, so we must allow addition and removal of items to this set, or replacing the set entirely. An attribute with cardinality *:db/single* holds a single value, and thus only allows replacement.  

Since we also have indexes that provide lookups directly on attributes and their values, these will also have to be updated. 

As with *add-entity* and *remove-entity*, we won't actually be modifying our entity in-place, but will instead add a new layer which contains the updated entity.

````clojure
(defn update-entity
   ([db ent-id attr-name new-val]
    (update-entity db ent-id attr-name new-val :db/reset-to ))
   ([db ent-id attr-name new-val operation]
      (let [update-ts (next-ts db)
            layer (last (:layers db))
            attr (attr-at db ent-id attr-name)
            updated-attr (update-attr attr new-val update-ts operation)
            fully-updated-layer (update-layer layer ent-id attr 
                                                          updated-attr new-val operation)]
        (update-in db [:layers] conj fully-updated-layer))))
````

To update an attribute, we locate it with *attr-at* and then use *update-attr* to perform the actual update. 

````clojure
(defn- update-attr [attr new-val new-ts operation]
    {:pre  [(if (single? attr)
            (contains? #{:db/reset-to :db/remove} operation)
            (contains? #{:db/reset-to :db/add :db/remove} operation))]}
    (-> attr
       (update-attr-modification-time new-ts)
       (update-attr-value new-val operation)))
````
We use two helper functions to perform the update. *update-attr-modification-time* updates timestamps to reflect the creation of the black arrows in Figure 1:

````clojure
(defn- update-attr-modification-time  
  [attr new-ts]
       (assoc attr :ts new-ts :prev-ts (:ts attr)))
````

*update-attr-value* actually updates the value:

````clojure
(defn- update-attr-value [attr value operation]
   (cond
      (single? attr)    (assoc attr :value #{value})
    ; now we're talking about an attribute of multiple values
      (= :db/reset-to operation)  (assoc attr :value value)
      (= :db/add operation) (assoc attr :value (CS/union (:value attr) value))
      (= :db/remove operation) (assoc attr :value (CS/difference (:value attr) value))))
````
All that remains is to remove the old value from the indexes and to add the new one to them, and then construct to the new layer with all of our updated components. Luckily, we can leverage the code we wrote for adding and removing entities to do this!

### Transactions


Each of the operations in our low-level API act on a single entity. However, nearly all databases have a mechanism for allowing users to perform multiple operations as a single _transaction_. (TODO: Reference other chapters on transactional semantics here.) This means: 

* The batch of operations is viewed as a single atomic operation, meaning that either all of the operations succeed together or fail together
* The database is in a valid state before, and after the transaction
* The batch update appears to be _isolated_; other queries should never see a database state in which only some of the operations have been applied

We can fulfill these requirements through an interface that consumes a database and a set of operations to be performed, and which produces a database whose state reflects the given changes. All of the changes submitted in the batch should be applied through the addition of a _single_ layer. However, we have one small problem: All of the functions we wrote in our low-level API add a new layer to the database. If we were to perform a batch with _n_ operations, we would thus see _n_ new layers added, when what we would really like is to have exactly 1 new layer.   

The key insight here is that the layer we want is the _top_ layer that would be produced by performing those updates in sequence. Therefore, the solution is to execute each of the user’s operations one after another, each of which will create a new layer. When the last layer is created, we take only that top layer and place it on the initial database (leaving all the intermediate layers to pine for the fjords). Only after we've done all this will we update the database's timestamp.
All this is done in the *transact-on-db* function, that receives the initial value of the database and the batch of operations to perform, and returns its updated value. 

````clojure
(defn transact-on-db [initial-db ops]
    (loop [[op & rst-ops] ops transacted initial-db]
      (if op
          (recur rst-ops (apply (first op) transacted (rest op)))
          (let [initial-layer  (:layers initial-db)
                new-layer (last (:layers transacted))]
            (assoc initial-db :layers (conj  initial-layer new-layer) :curr-time (next-ts initial-db) :top-id (:top-id transacted))))))
```` 

Note here that we used the term _value_, this means that only the caller to this function is exposed to the updated state, and all other users of the database are unaware of this change (as a database is a value, and therefore cannot change). 
In order to have a system where users can be exposed to state changes performed by others, users do not interact directly with the database, but rather refer to it using another level of indirection [REF HERE]. This additional level is implemented using Clojure's element called *Atom*, which is a one of Clojure's reference types. Here we leverage two key features of an *Atom*, which are:
1. It references other elements
2. Getting to the value of the referenced elements is done by dereferencing the *Atom*, which returns the state of that element at that time
3. All updates to the value referenced by the *Atom* are done in a transactional manner (using Clojure's Software Transaction Memory capabilities), by providing to the transaction the *Atom* and a function that updates its state.

In between Clojure's *Atom* and the work done in *transact-on-db*, there's still gap to be bridged, namely, to invoke the transaction with the right inputs. 
To have the simplest and clearest APIs, we  would like users to just provide the *Atom* and the list of operations, and have database transform the user input into a proper transaction.

That transformation occurs in the following transaction call chain:

 transact →  _transact → swap! → transact-on-db

* Users call *transact* with the *Atom* (i.e., the database connection) and the operations to perform, relays its input to *_transact*, adding to it the name of the function that updates the *Atom* (*swap!*)
````clojure
(defmacro transact [db-conn & txs]  `(_transact ~db-conn swap! ~@txs))
````
* *_transact* prepares the call to *swap!*. It does so by creating a list that begins with *swap!*, followed by the db-connection (the *Atom*), then the *transact-on-db* symbol and the batch of operations.
````clojure
(defmacro  _transact [db op & txs]
   (when txs
     (loop [[frst-tx# & rst-tx#] txs  res#  [op db `transact-on-db]  accum-txs# []]
       (if frst-tx#
           (recur rst-tx# res#  (conj  accum-txs#  (vec frst-tx#)))
           (list* (conj res#  accum-txs#))))))
````
* *swap!* invokes *transact-on-db* within a transaction (with the previously prepared arguments)
* *transact-on-db* creates the new state of the database and returns it

At this point we can see that with few minor tweaks, we can also provide a way to ask "what-if" questions. It can be done by replacing *swap!* with a function that would not impose any change to the system. This scenario is implemented with the "what-if" call chain:

what-if → _transact →   _what-if → transact-on-db

* The user calls *what-if* with the database value and the operations to perform. It then relays these inputs to *_transact*, adding to them a function that mimics *swap!*'s APIs, without its effect (callled *_what-if*).  
````clojure
(defmacro what-if [db & ops]  `(_transact ~db _what-if  ~@ops))
````
* *_transact* prepares the call to *_what-if*. It does so by creating a list that begins with *_what-if*, followed by the database, then the *transact-on-db* symbol and the batch of operations.
* *_what-if* invokes *transact-on-db*, just like *swap!* does in the transaction scenario, but does not inflict any change on the system.

````clojure
(defn- _what-if [db f txs]  (f db txs))
````
 
Note that we are not using functions, but macros. The reason for using macros here is that arguments to macros do not get evaluated as the call happens, this allows us to offer a cleaner API design where the user provides the operations structured in the same way that any function call is structured in Clojure. 

The above described process can be seen in the following examples:

Transaction: 

User call: 
````clojure
(transact db-conn  (add-entity e1) (update-entity e2 atr2 val2 :db/add))  
````
Changes into: 
````clojure
(_transact db-conn swap! (add-entity e1) (update-entity e2 atr2 val2 :db/add))
````
Becomes: 
````clojure
(swap! db-conn transact-on-db [[add-entity e1][update-entity e2 atr2 val2 :db/add]])
````
What-if: 

User call: 
````clojure
(what-if my-db (add-entity e3) (remove-entity e4))
````
Changes into: 
````clojure
(_transact my-db _what-if (add-entity e3) (remove-entity e4))
````
Changes into: 
````clojure
(_what-if my-db transact-on-db [[add-entity e3] [remove-entity e4]])
````
Becomes eventually: 
````clojure
(transact-on-db my-db  [[add-entity e3] [remove-entity e4]])
````

## Insight extraction as libraries

At this point, we have the core functionality of the database in place, and it is time to add to it its raison d'être - insights extraction. The architecture approach we used here is to allow adding these capabilities as libraries, as different usages of the database would need different such mechanisms. 

### Graph traversal

A reference connection between entities is created when an entity’s attribute’s type is *:db/ref*, which means that the value of that attribute is an id of another entity. When a referring entity is added to the database, the reference is indexed at the VAET index.  
The information found in the VAET index can be leveraged to extract all the incoming links to an entity and is done in the function *incoming-refs*, which collects for the given entity all the leaves that are reachable from it at that index:

````clojure
(defn incoming-refs [db ts ent-id & ref-names]
   (let [vaet (ind-at db :VAET ts)
         all-attr-map (vaet ent-id)
         filtered-map (if ref-names (select-keys ref-names all-attr-map) all-attr-map)]
      (reduce into #{} (vals filtered-map))))
````
We can also, for a given entity, go through all of it’s attributes and collect all the values of attribute of type :db/ref, and by that extract all the outgoing references from that entity. This is done at the *outgoing-refs* function 

````clojure
(defn outgoing-refs [db ts ent-id & needed-keys]
   (let [val-filter-fn (if ref-names #(vals (select-keys ref-names %)) vals)]
   (if-not ent-id []
     (->> (entity-at db ts ent-id)
          (:attrs) (val-filter-fn) (filter ref?) (mapcat :value)))))
````
These two functions act as the basic building blocks for any graph traversal operation, as they are the ones that raise the level of abstraction from entities and attributes to nodes and links in a graph. Once we have the ability to look at our database as a graph, we can provide various graph traversing and querying APIs. We leave this as a solved exercise to the reader - one solution can be found in the chapter's source code (see graph.clj).   


## Querying the database

A second library we present here provides querying capabilities, which is the main issue of this section. 
A database is not very useful to its users without a powerful query mechanism. This feature is usually exposed to users through a _query language_ that is used to declaratively specify the set of data of interest. 

Our data model is based on accumulation of facts (i.e. datoms) over time. For this model, a natural place to look for the right query language is _logic programming_. A commonly used query language influenced by logic programming is _Datalog_ which, in addition to being well-suited for our data model, has a very elegant adaptation to Clojure’s syntax. Our query engine will implement a subset of the *Datalog* language from the [Datatomic database](http://docs.datomic.com/query.html).

### Query language

Let's look at an example query in our proposed language. This query asks "what are the names and ages of people who like pizza, whose age is more than 20, and who have a birthday this week?"

````clojure
{  :find [?nm ?ag ]
   :where [
      [?e  :likes "pizza"]
      [?e  :name  ?nm] 
      [?e  :age (< ?ag 20)]
      [?e  :birthday (birthday-this-week? _)]]}
````

#### Syntax

We directly use the syntax of Clojure’s data literals to provide the basic syntax for our queries. This allows us to avoid having to write a specialized parser, while still providing a form that is familiar and easily readable to programmers familiar with Clojure.

A query is a map with two items:

* An item with *:where* as a key, and with a _rule_ as a value. A rule is a vector of _clauses_, and a clause is a vector composed of three _predicates_, each of which operates on a different component of a datom.  In the example above, *[?e  :likes "pizza"]* is a clause.  This *:where* item defines a rule that acts as a filter on datoms in our database (like the 'WHERE' clause in a SQL query.)

* An item with *:find* as a key, and with a vector as a value. The vector defines which components of the selected datom should be projected into the results (like the 'SELECT' clause in an SQL query.)

The description above omits a crucial requirement, which is how to make different clauses sync on a value (i.e., make a join operation between them), and how to structure the found values in the output (specified by the *:find* part.) 

We fulfill both of these requirements using _variables_, which are denoted with a leading *?* in their names. The only exception to this definition is the "don't-care" variable *‘_’*  (underscore).  

A clause in a query is composed of three predicates. The following table defines what can act as a predicate in our query language:

<table>
  <tr>
    <td>Name</td>
    <td>Meaning</td>
    <td>Example</td>
  </tr>
  <tr>
    <td>Constant</td>
    <td>Is the value of the item in the datom equal to the constant?</td>
    <td>:likes</td>
  </tr>
  <tr>
    <td>Variable</td>
    <td>Bind the value of the item in the datom to the variable and return true.</td>
    <td>?e</td>
  </tr>
  <tr>
    <td>Don’t-care</td>
    <td>Always returns true.</td>
    <td>_</td>
  </tr>
  <tr>
    <td>Unary operator</td>
    <td>Unary operation that takes a variable as its operand.<br/>
        Bind the datom's item's value to the variable (unless it's an '_').<br/>
        Replace the variable with the value of the item in the datom.<br/>
        Return the application of the operation.</td>
    <td>(birthday-this-week? _)</td>
  </tr>
  <tr>
    <td>Binary operator</td>
    <td>A binary operation that must have a variable as one of its operands.<br/>
        Bind the datom's item's value to the variable (unless it's an '_').<br/>        
        Replace the variable with the value of the item in the datom.<br/>
        Return the result of the operation.</td>
    <td>(&gt; :ag 20)</td>
  </tr>
</table>

Table 3

#### Limitations of our query language 

Engineering is all about managing tradeoffs, and designing our query engine is no different. In our case, the first tradeoff we must make is feature-richness versus complexity. Resolving this tradeoff requires us to look at common use-cases of the system, and from there deciding on what limitations would be acceptable. 

In our database, the decision was to build a query engine with the following limitations:

* Users cannot define logical operations between the clauses; they are always ‘ANDed’ together. (This can be worked around by using unary or binary predicates.)
* If there is more than one clause in a query, there must be one variable that is found in all of the clauses of that query. This variable acts as a joining variable. This limitation simplifies the query optimizer.
* A query is only executed on a single database. 

While these design decisions result in a query language that is less rich than Datalog, we are still able to support many types of simple-but-useful queries.

### Query engine design

While our query language allows the user to specify _what_ they want to access, it hides the details of _how_ this will be accomplished. The *query engine* is the database component responsible for yielding the data for a given query. 

This involves four steps:

1. Transformation to internal representation: transform the query from its textual form into a data structure that is consumed by the query planner.
2. Building a query plan: Determine an efficient _plan_ for yielding the results of the given query. In our case, a query plan is a function to be invoked.
3. Executing the plan: Execute the plan and send its results to the next phase.
4. Unification and reporting: Extract only the results that need to be reported and format them as specified.

#### Phase 1 - Transformation

In this phase, we transform the given query from a representation that is easy for the user to understand into a representation that can be consumed efficiently by the query planner. 

The *:find* part of the query is transformed into a set of the given variable names:

````clojure
(defmacro symbol-col-to-set [coll] (set (map str coll)))
````

The *:where* part of the query retains its nested vector structure. However, each of the terms in each of the clauses is replaced with a predicate according to Table 3. 

````clojure
(defmacro clause-term-expr [clause-term]
   (cond
    (variable? (str clause-term)) #(= % %) ; variable
    (not (coll? clause-term)) `#(= % ~clause-term) ; constant
    (= 2 (count clause-term)) `#(~(first clause-term) %) ; unary operator
    (variable? (str (second clause-term))) `#(~(first clause-term) % ~(last clause-term)) ; binary operator, first operand is a variable
    (variable? (str (last clause-term))) `#(~(first clause-term) ~(second clause-term) %))) ; binary operator, second operand is variable
````

Also, for each clause, a vector with the names of the variables used in that clause is set as its metadata. 

````clojure
(defmacro clause-term-meta [clause-term]
   (cond
   (coll? clause-term)  (first (filter variable?  (map str clause-term))) 
   (variable? (str clause-term)) (str clause-term) 
   :no-variable-in-clause nil))
````

We use *pred-clause* to iterate over the terms in each clause: 

````clojure
(defmacro pred-clause [clause]
   (loop [[trm# & rst-trm#] clause exprs# [] metas# []]
     (if  trm#
          (recur rst-trm# (conj exprs# `(clause-term-expr ~ trm#)) 
                       (conj metas#`(clause-term-meta ~ trm#)))
          (with-meta exprs# {:db/variable metas#}))))
````

Iterating over the clauses themselves happens in *q-clauses-to-pred-clauses*:
          
````clojure
(defmacro  q-clauses-to-pred-clauses [clauses]
     (loop [[frst# & rst#] clauses preds-vecs# []]
       (if-not frst#  preds-vecs#
         (recur rst# `(conj ~preds-vecs# (pred-clause ~frst#))))))
````
We are once again relying on the fact that macros do not eagerly evaluate their arguments. This allows us to define a simpler API where uers provide variable names as symbols e.g., ?name instead of "?name" (stringifying the variable) or even worse - '?name (asking the user to use some of Clojure's dark magic).

[TODO: I am not sure how messy it would be, but it might be useful here to show the instance of the data structure that would be produced from our example query by this phase.]

#### Phase 2 - Making a plan

In this phase, we inspect the query representation produced in phase 1 to determine a good _plan_ that will produce the result it describes.

In general, this will involve choosing the appropriate index, applying on it the predicate clauses and merging the results. 

We choose the index based on the joining variable. We assume that there is at most one such variable, which yields three possibilities: 

<table>
	<tr>
		<td>Joining variable operates on</td><td>Index to use</td>
	</tr>
	<tr>
		<td>Entity ids</td><td>AVET</td>
	</tr>
	<tr>
		<td>Attribute names</td><td>VEAT</td>
	</tr>
	<tr>
		<td>Attribute values</td><td>EAVT</td>
	</tr>
</table>

The reasoning behind this mapping will become clearer in the next section, where we actually execute the plan produced here, just for now note that the key insight here is to select an index whose leaves hold the elements that the joining variable operates on.

Locating the index of the joining variable is done by *index-of-joining-variable*:

````clojure
(defn index-of-joining-variable [query-clauses]
   (let [metas-seq  (map #(:db/variable (meta %)) query-clauses) 
         collapsing-fn (fn [accV v] (map #(when (= %1 %2) %1)  accV v))
         collapsed (reduce collapsing-fn metas-seq)] 
     (first (keep-indexed #(when (variable? %2 false) %1)  collapsed)))) 
````
We begin by extracting the metadata of each clause in the query. This metadata is a 3-element vector, each of which is a variable name or *nil*, and reduces them to produce a single variable name or *nil*. If a variable name is produced, this means it appears in all of the metadata vectors at the same index -- i.e. this is the joining variable. We can thus choose the appropriate index by using the mapping described above. 

Once the index is chosen, we construct our plan, which is a function that closes over the query and the index name and executes the operations necessary to return the query results. 

````clojure
(defn build-query-plan [query]
   (let [term-ind (index-of-joining-variable query)
         ind-to-use (case term-ind 0 :AVET 1 :VEAT 2 :EAVT)]
      (partial single-index-query-plan query ind-to-use)))
````
#### Phase 3 - Execution of the plan

We saw in the previous phase that the query plan we construct ends by calling *single-index-query-plan*. This function will:

1. Apply each predicate clause on an index (each predicate on its appropriate index level)
2. Perform an AND operation across the results 
3. Merge the results into a simpler data structure

````clojure
(defn single-index-query-plan [query indx db]
   (let [q-res (query-index (ind-at db indx) query)]
     (bind-variables-to-query q-res (ind-at db indx))))
````
Let’s go deeper into the rabbit's hole and take a look at the *query-index* function, where our query finally begins to yield some results:

````clojure
(defn query-index [index pred-clauses]
   (let [result-clauses (filter-index index pred-clauses)
         relevant-items (items-that-answer-all-conditions (map last result-clauses) 
                                                          (count pred-clauses))
         cleaned-result-clauses (map (partial mask-path-leaf-with-items relevant-items)
                                     result-clauses)] 
     (filter #(not-empty (last %)) cleaned-result-clauses)))
````
This function starts by applying the predicate clauses on the previously-chosed index. Each application of a predicate clause on an index returns a _result clause_. 

The main characteristics of a result are:
1. It is built of three items, each from a different level of the index, and each passed its respective predicate. 
2. The order of items matches the index's levels structure (predicate clause are always in EAV order). 
3. The metadata of the predicate clause is attached 

All of this is done in the function *filter-index*

````clojure
(defn filter-index [index predicate-clauses]
   (for [pred-clause predicate-clauses
         :let [[lvl1-prd lvl2-prd lvl3-prd] (apply (from-eav index) pred-clause)] 
         [k1 l2map] index  ; keys and values of the first level
         :when (try (lvl1-prd k1) (catch Exception e false))  
         [k2  l3-set] l2map  ; keys and values of the second level
         :when (try (lvl2-prd k2) (catch Exception e false))
         :let [res (set (filter lvl3-prd l3-set))] ]
     (with-meta [k1 k2 res] (meta pred-clause))))
````
Once we have produced all of the result clauses, we need to perform an *AND* operation between them. This is done by finding all of the elements that passed all the predicate clauses:

````clojure
(defn items-that-answer-all-conditions [items-seq num-of-conditions]
   (->> items-seq ; take the items-seq
         (map vec) ; make each collection (actually a set) into a vector
         (reduce into []) ;reduce all the vectors into one vector
         (frequencies) ; count for each item in how many collections (sets) it was in
         (filter #(<= num-of-conditions (last %))) ;items that answered all conditions
         (map first) ; take from the duos the items themselves
         (set))) ; return it as set
````

We now have to remove the items that didn’t pass all of the conditions:

````clojure
(defn mask-path-leaf-with-items [relevant-items path]
     (update-in path [2] CS/intersection relevant-items))
````

Finally, we remove all of the result clauses that are 'empty' (i.e. their last item is empty.) We do this in the last line of the *query-index* function. 

We are now ready to being reporting the results. The result clause structure is unwieldy for this purposes, so we will convert it into an an index-like structure (map of maps) -- with a significant twist. 

To understand the twist, we must first introduce the idea of a _binding pair_, which is a pair that matches a variable name to its value. The variable name is the one used at the predicate clauses, and the value is the value found in the result clauses.

The twist to the index structure is that now we hold a binding pair of the entity-id / attr-name / value in the location where we held an entity-id / attr-name / value in an index: 

````clojure
(defn bind-variables-to-query [q-res index]
   (let [seq-res-path (mapcat (partial combine-path-and-meta (from-eav index)) q-res)         
         res-path (map #(->> %1 (partition 2)(apply (to-eav index))) seq-res-path)] 
     (reduce #(assoc-in %1  (butlast %2) (last %2)) {} res-path)))
     
(defn combine-path-and-meta [from-eav-fn path]
    (let [expanded-path [(repeat (first path)) (repeat (second path)) (last path)] 
          meta-of-path(apply from-eav-fn (map repeat (:db/variable (meta path))))
          combined-data-and-meta-path (interleave meta-of-path expanded-path)]
       (apply (partial map vector) combined-data-and-meta-path)))
````

#### Phase 4 - Unify and report

At this point, we’ve produced a superset of the results that the user initially asked for. In this phase, we extract the specific values that the user has expressed interest in. This process is called _unification_ -- it is here that we will unify the binding pairs structure with the vector of variable names that the user defined in the *:find* clause of the query. 

````clojure
(defn unify [binded-res-col needed-vars]
   (map (partial locate-vars-in-query-res needed-vars) binded-res-col))
````  

Each unification step is handled by *locate-vars-in-query-result*, which iterates over a query result (structured as an index entry, but with binding pairs) to detect all the variables and values that the user asked for.

````clojure
(defn locate-vars-in-query-res [vars-set q-res]
   (let [[e-pair av-map]  q-res
         e-res (resultify-bind-pair vars-set [] e-pair)]
     (map (partial resultify-av-pair vars-set e-res)  av-map)))

(defn resultify-bind-pair [vars-set accum pair]
   (let [[ var-name _] pair]
      (if (contains? vars-set var-name) (conj accum pair) accum)))

(defn resultify-av-pair [vars-set accum-res av-pair]
   (reduce (partial resultify-bind-pair vars-set) accum-res av-pair))
````

#### Running the show

We've finally built all of the components we need to to build our user-facing query mechanism, the *q* macro. [TODO: I think it would be useful to have an early black-box example of this macro is invoked, so that readers know what they are building towards:

````clojure
(defmacro q
  [db query]
  `(let [pred-clauses#  (q-clauses-to-pred-clauses ~(:where query)) ; transforming the clauses of the query to an internal representation structure called query-clauses
           needed-vars# (symbol-col-to-set  ~(:find query))  ; extracting from the query the variables that needs to be reported out as a set
           query-plan# (build-query-plan pred-clauses#) ; extracting a query plan based on the query-clauses
           query-internal-res# (query-plan# ~db)] ;executing the plan on the database
     (unify query-internal-res# needed-vars#)));unifying the query result with the needed variables to report out what the user asked for
````  
## Summary

Our journey started with a conception of a different kind of database, and ended with one that:

* Supports ACI transactions (durability was lost when we decided to have the data stored in-memory) 
* Supports "what-if" interactions
* Answers time-related questions 
* Handle simple datalog queries that are optimized by using indexes
* provides APIs for graph queries
* Introduces and implemented the notion of evolutionary queries

There are still many things that we could improve: We could add caching to several components to improve performance; support richer queries; and add real storage support to provide data durability, to name a few.

However, our final product can do a great many things, and was implemented in 488 lines of Clojure source code, 73 of which are blank lines and 55 of which are docstrings. 

Finally, there's one thing that is still missing - a Name. 
The only sensible option for an in-memory, index optimized, query supporting, library developer friendly database that is implemented in 360 lines of Clojure code is CircleDB.
