// Inicialización de MongoDB para el proyecto.
// Spring Data creará las colecciones automáticamente, pero aquí pre-creamos
// las bases de datos y algunos índices útiles para customer y article.

db = db.getSiblingDB('customer');
db.createCollection('customers_current');
// Sin indice unico sobre 'id': los documentos usan @Id, que Mongo guarda
// como _id (ya unico por definicion). Un unique sobre el campo 'id',
// que no existe, hace que todos valgan null y solo entre UN documento
// (E11000 dup key: { id: null }) a partir del segundo.
db.createCollection('sync_state');
// El nombre debe coincidir con el @CompoundIndex de SyncStateDoc: si difiere,
// Mongo rechaza la creacion del indice de la app (error 85 IndexOptionsConflict)
// y la app no arranca.
db.sync_state.createIndex({ domain: 1, entityId: 1, timestamp: -1 }, { name: 'dom_ent_idx' });
// Secuencia por entidad: orden canonico y version optimista. Indice PARCIAL (seq
// existe): los docs anteriores a la secuencia no tienen el campo. Con sparse
// colisionarian en seq=null (sparse compuesto indexa si hay AL MENOS una clave).
// Mismo nombre y opciones que declara la app en SyncStateDoc.
db.sync_state.createIndex({ domain: 1, entityId: 1, seq: 1 }, { name: 'dom_ent_seq_uk', unique: true, partialFilterExpression: { seq: { $exists: true } } });
// Traza de pasos de un envio: todas las lineas de un mismo ciclo (agregado y
// features) en UNA consulta, en orden. No unico: un ciclo tiene muchas
// transiciones. Mismo nombre y definicion que el @CompoundIndex de SyncStateDoc.
db.sync_state.createIndex({ domain: 1, cycleId: 1, seq: 1 }, { name: 'dom_cycle_idx' });
// Claves que asigna SAP y no podemos deducir (AddressID...): una fila por
// (dominio, entidad, feature). El _id ya es unico, asi que el indice solo sirve
// para listar las claves de una entidad al darla de baja.
db.createCollection('sap_keys');
db.sap_keys.createIndex({ domain: 1, entityId: 1 }, { name: 'dom_ent_key_idx' });

db = db.getSiblingDB('article');
db.createCollection('articles_current');
// Sin indice unico sobre 'id': los documentos usan @Id, que Mongo guarda
// como _id (ya unico por definicion). Un unique sobre el campo 'id',
// que no existe, hace que todos valgan null y solo entre UN documento
// (E11000 dup key: { id: null }) a partir del segundo.
db.createCollection('sync_state');
// El nombre debe coincidir con el @CompoundIndex de SyncStateDoc: si difiere,
// Mongo rechaza la creacion del indice de la app (error 85 IndexOptionsConflict)
// y la app no arranca.
db.sync_state.createIndex({ domain: 1, entityId: 1, timestamp: -1 }, { name: 'dom_ent_idx' });
// Secuencia por entidad: orden canonico y version optimista. Indice PARCIAL (seq
// existe): los docs anteriores a la secuencia no tienen el campo. Con sparse
// colisionarian en seq=null (sparse compuesto indexa si hay AL MENOS una clave).
// Mismo nombre y opciones que declara la app en SyncStateDoc.
db.sync_state.createIndex({ domain: 1, entityId: 1, seq: 1 }, { name: 'dom_ent_seq_uk', unique: true, partialFilterExpression: { seq: { $exists: true } } });
// Traza de pasos de un envio: todas las lineas de un mismo ciclo (agregado y
// features) en UNA consulta, en orden. No unico: un ciclo tiene muchas
// transiciones. Mismo nombre y definicion que el @CompoundIndex de SyncStateDoc.
db.sync_state.createIndex({ domain: 1, cycleId: 1, seq: 1 }, { name: 'dom_cycle_idx' });
// Claves que asigna SAP y no podemos deducir (AddressID...): una fila por
// (dominio, entidad, feature). El _id ya es unico, asi que el indice solo sirve
// para listar las claves de una entidad al darla de baja.
db.createCollection('sap_keys');
db.sap_keys.createIndex({ domain: 1, entityId: 1 }, { name: 'dom_ent_key_idx' });

print('✅ MongoDB initialized: customer and article databases');
