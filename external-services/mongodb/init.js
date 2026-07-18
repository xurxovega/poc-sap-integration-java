// Inicialización de MongoDB para el proyecto.
// Spring Data creará las colecciones automáticamente, pero aquí pre-creamos
// las bases de datos y algunos índices útiles para customer y article.

db = db.getSiblingDB('customer');
db.createCollection('customer_documents');
db.customer_documents.createIndex({ id: 1 }, { unique: true });
db.createCollection('sync_state');
db.sync_state.createIndex({ domain: 1, entityId: 1, timestamp: -1 });

db = db.getSiblingDB('article');
db.createCollection('article_documents');
db.article_documents.createIndex({ id: 1 }, { unique: true });
db.createCollection('sync_state');
db.sync_state.createIndex({ domain: 1, entityId: 1, timestamp: -1 });

print('✅ MongoDB initialized: customer and article databases');
