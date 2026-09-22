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

// Alertas operativas del dashboard-customer (UI-001 F-9 promoted): el
// dashboard las persiste con TTL 30 dias para que el operador las
// reconozca sin acumularlas para siempre. La fuente real es el topic Kafka
// sap.sync.alerts; otro consumidor las materializa aqui (dashboard-feature
// o un job). Los indices los necesita el AlertRepository del dashboard.
db.createCollection('alerts');
// PK ya viene en el _id (alertId unico). Forzamos el nombre para que el
// insert con _id manual no choque y para que el dashboard pueda hacer
// upserts deterministas.
db.alerts.createIndex({ alertId: 1 }, { name: 'alert_id_uk', unique: true });
// Listar las alertas abiertas de un cliente: la entidad en su ultima ack.
db.alerts.createIndex({ entityId: 1, ackedAt: 1 }, { name: 'entity_acked_idx' });
// Limpieza automatica de alertas reconocidas (ackedAt + 30 dias).
db.alerts.createIndex({ ackedAt: 1 }, { name: 'acked_ttl', expireAfterSeconds: 30 * 24 * 3600 });
// Limpieza de alertas que nadie ha reconocido tras 30 dias desde su emision
// (si nadie reconoce, expire por openedAt).
db.alerts.createIndex({ openedAt: 1 }, { name: 'opened_ttl', expireAfterSeconds: 30 * 24 * 3600 });

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

// Alertas operativas del dashboard-customer (UI-001 F-9 promoted): mismo
// shape de indices que en la base customer.
db.createCollection('alerts');
db.alerts.createIndex({ alertId: 1 }, { name: 'alert_id_uk', unique: true });
db.alerts.createIndex({ entityId: 1, ackedAt: 1 }, { name: 'entity_acked_idx' });
db.alerts.createIndex({ ackedAt: 1 }, { name: 'acked_ttl', expireAfterSeconds: 30 * 24 * 3600 });
db.alerts.createIndex({ openedAt: 1 }, { name: 'opened_ttl', expireAfterSeconds: 30 * 24 * 3600 });

print('✅ MongoDB initialized: customer and article databases');
