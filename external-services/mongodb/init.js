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

print('✅ MongoDB initialized: customer and article databases');
