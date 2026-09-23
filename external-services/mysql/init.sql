-- =============================================================================
-- Registro de features SDD
--
-- Informacion ampliada de cada feature solicitada y su ciclo de vida. NO es una
-- base de datos de la aplicacion: ningun modulo del reactor se conecta aqui.
-- Es el registro del trabajo, complementario a los specs de docs/sdd/.
--
-- Por que una BD y no solo los ficheros: el spec cuenta QUE hace una feature;
-- esto cuenta QUIEN la pidio, CUANDO y COMO ha ido cambiando, que en Markdown
-- no se consulta ni se agrega bien.
-- =============================================================================

CREATE DATABASE IF NOT EXISTS sdd_registry
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE sdd_registry;

-- -----------------------------------------------------------------------------
-- Una fila por feature solicitada. El par (subproyecto, slug) es la identidad,
-- y coincide con la ruta del spec: docs/sdd/<subproyecto>/<slug>.md
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS feature (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    subproyecto   VARCHAR(50)  NOT NULL COMMENT 'customer | article | supplier | common',
    slug          VARCHAR(120) NOT NULL COMMENT 'nombre del fichero del spec, sin .md',
    nombre        VARCHAR(200) NOT NULL COMMENT 'nombre descriptivo, en lenguaje de negocio',
    descripcion   TEXT                  COMMENT 'informacion ampliada: contexto, motivo, alcance acordado',
    spec_path     VARCHAR(300)          COMMENT 'ruta al spec, NULL si aun no se ha escrito',
    estado        ENUM('solicitada','en_diseno','en_curso','implementada','descartada')
                  NOT NULL DEFAULT 'solicitada',
    solicitada_por VARCHAR(120)         COMMENT 'quien la pide',
    solicitada_el  DATE                 COMMENT 'cuando se pide, no cuando se hace',
    notas         TEXT,
    creada_el     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modificada_el TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    eliminada_el  TIMESTAMP    NULL     COMMENT 'baja logica: nunca se borra la fila, para no perder el historial',
    UNIQUE KEY uk_feature (subproyecto, slug),
    KEY idx_estado (estado),
    KEY idx_subproyecto (subproyecto)
) ENGINE=InnoDB;

-- -----------------------------------------------------------------------------
-- Ciclo de vida: un evento por cada alta, modificacion o baja de una feature.
-- Es lo unico que se registra por ahora.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS feature_evento (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    feature_id   INT NOT NULL,
    accion       ENUM('ALTA','MODIFICACION','BAJA') NOT NULL,
    resumen      VARCHAR(500) NOT NULL COMMENT 'que cambio, en una linea',
    detalle      TEXT                  COMMENT 'motivo, decisiones, enlaces',
    autor        VARCHAR(120),
    ocurrido_el  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evento_feature FOREIGN KEY (feature_id) REFERENCES feature (id),
    KEY idx_feature (feature_id),
    KEY idx_ocurrido (ocurrido_el)
) ENGINE=InnoDB;

-- Vista de conveniencia: ultimo movimiento de cada feature.
CREATE OR REPLACE VIEW v_feature_estado AS
SELECT f.subproyecto,
       f.slug,
       f.nombre,
       f.estado,
       f.spec_path,
       (SELECT e.accion      FROM feature_evento e WHERE e.feature_id = f.id
         ORDER BY e.ocurrido_el DESC LIMIT 1) AS ultima_accion,
       (SELECT e.ocurrido_el FROM feature_evento e WHERE e.feature_id = f.id
         ORDER BY e.ocurrido_el DESC LIMIT 1) AS ultimo_movimiento,
       (SELECT COUNT(*)      FROM feature_evento e WHERE e.feature_id = f.id) AS n_eventos
FROM feature f
WHERE f.eliminada_el IS NULL;

-- =============================================================================
-- Carga inicial: las features ya identificadas en docs/sdd/README.md §5
-- =============================================================================

INSERT INTO feature (subproyecto, slug, nombre, spec_path, estado, solicitada_el, descripcion) VALUES
 ('customer','sincronizacion-cliente','Sincronización del cliente (agregado)',NULL,'implementada','2026-07-01',
  'Pipeline principal: ingesta CDC o REST, lectura del legacy, validación, imagen en Mongo, histórico en ES y envío a SAP.'),
 ('customer','sincronizacion-direccion','Sincronización de dirección','docs/sdd/customer/sincronizacion-direccion.md','implementada','2026-07-01',
  'Valida y envía a SAP la dirección postal del cliente. Primera feature con spec SDD del proyecto.'),
 ('customer','sincronizacion-datos-fiscales','Sincronización de datos fiscales',NULL,'implementada','2026-07-01',
  'NIF/CIF, número de IVA, razón social y residencia fiscal hacia SAP.'),
 ('customer','sincronizacion-contacto','Sincronización de datos de contacto',NULL,'en_curso','2026-07-01',
  'Email, teléfono y web. Implementada pero sin el contrato real de S/4: en S/4 cuelgan de la dirección (A_AddressEmailAddress, A_AddressPhoneNumber).'),
 ('customer','sincronizacion-datos-bancarios','Sincronización de datos bancarios',NULL,'en_curso','2026-07-01',
  'IBAN y BIC. Incompleta: los mandatos SEPA no llegan desde el legacy.'),
 ('customer','baja-cliente','Baja de cliente',NULL,'implementada','2026-07-01',
  'Borrado del cliente en SAP a partir de una operación DELETE del CDC.'),
 ('customer','baja-mandato-sepa','Baja de mandato SEPA',NULL,'implementada','2026-07-01',
  'Borrado de un mandato SEPA asociado al cliente.'),
 ('article','sincronizacion-articulo','Sincronización del artículo',NULL,'implementada','2026-07-01',
  'Pipeline del dominio artículo, sin pipeline por feature: un único flujo sobre el agregado.'),
 ('supplier','sincronizacion-proveedor','Sincronización del proveedor',NULL,'solicitada','2026-07-01',
  'Dominio no operativo: el módulo es un placeholder que no arranca.'),
 ('common','maquina-de-estados','Máquina de estados de sincronización','docs/sdd/common/maquina-de-estados.md','implementada','2026-07-01',
  'Capacidad transversal: estados, transiciones permitidas, entrada y re-entrada de agregados y de líneas de feature.'),
 ('common','idempotencia-y-dedupe','Idempotencia y deduplicación por hash',NULL,'implementada','2026-07-01',
  'Dedupe por payloadHash y cabecera Idempotency-Key: un reintento no duplica envíos a SAP.'),
 ('common','resiliencia-cliente-sap','Resiliencia del cliente SAP',NULL,'implementada','2026-07-01',
  'Retry con backoff, circuit breaker, timeouts y CSRF OData V2 en el cliente HTTP hacia SAP.'),
 ('common','observabilidad','Observabilidad: métricas por dominio y estado',NULL,'implementada','2026-07-01',
  'Contadores por dominio y estado de la máquina, expuestos en /actuator/prometheus.');

-- Eventos ya conocidos de las features que tienen spec escrito.
INSERT INTO feature_evento (feature_id, accion, resumen, detalle, autor, ocurrido_el)
SELECT id,'ALTA','Spec inicial de la feature',
       'Escrito al arreglar el pipeline por feature: la línea de estado no registraba la entrada en VALIDATING y POST /customers/sync devolvía 500.',
       'equipo','2026-09-09 00:00:00'
FROM feature WHERE subproyecto='customer' AND slug='sincronizacion-direccion';

INSERT INTO feature_evento (feature_id, accion, resumen, detalle, autor, ocurrido_el)
SELECT id,'MODIFICACION','AC-6: re-sincronización de una dirección ya enviada',
       'La línea de feature quedaba en SENT_SAP y SENT_SAP → VALIDATING no estaba permitida: el segundo evento con cambios reales acababa en la DLT.',
       'equipo','2026-09-10 00:00:00'
FROM feature WHERE subproyecto='customer' AND slug='sincronizacion-direccion';

INSERT INTO feature_evento (feature_id, accion, resumen, detalle, autor, ocurrido_el)
SELECT id,'ALTA','Spec inicial de la máquina de estados',
       'Documenta estados, transiciones y reglas de re-entrada. Añade la re-entrada de líneas de feature por VALIDATING desde SENT_SAP, INVALID y SAP_ERROR.',
       'equipo','2026-09-10 00:00:00'
FROM feature WHERE subproyecto='common' AND slug='maquina-de-estados';

INSERT INTO feature_evento (feature_id, accion, resumen, detalle, autor, ocurrido_el)
SELECT id, 'MODIFICACION', 'OBS-005: tags application/env/cluster y artefactos en deploy/observability/',
       'R-8 (tags comunes) y R-9 (dashboards+alertas versionados) en el spec de observabilidad. AC-6 y AC-7 con sus tests. Implementado en 4 commits (a2a0f9e, 4f2d369, e67174a, 0d7a0b2). QA verde: 10/10 tests OBS-005; mvn verify SUCCESS; ArchUnit y JaCoCo check en verde.',
       'docs-writer', NOW()
FROM feature WHERE subproyecto='common' AND slug='observabilidad';

INSERT INTO feature (subproyecto, slug, nombre, spec_path, estado, solicitada_por, solicitada_el, descripcion) VALUES
 ('customer','consulta-entidad-ui','Dashboard de consulta de entidad y búsqueda (UI-001)','docs/sdd/customer/consulta-entidad-ui.md','implementada','equipo','2026-09-22',
  'Web estática del dominio customer (puerto 8091) que lee directo de Mongo y ES; NO consume las APIs REST del customer-app. Thymeleaf+HTMX+Alpine.js. PII enmascarada con common.security.PiiMasker (promovido en este PR). F-9 promoted: POST /customers/alerts/{id}/ack con sap-write y TTL 30d. F-12: métricas Prometheus propias. Job KPIs con cardinalidad ≤ 2.');

INSERT INTO feature_evento (feature_id, accion, resumen, detalle, autor, ocurrido_el)
SELECT id, 'MODIFICACION', 'Cierre UI-001: spec consulta-entidad-ui.md (AC-1..10, R-1..R-7)',
       '7 commits (d04b860 andamiaje + ArchUnit, 2622178 PiiMasker promoted, 9ec59d9 dominio + use cases, b6fc4e5 adaptadores Mongo/ES + init.js, 3464e86 controllers Thymeleaf + Grafana var entityId, 83b511b job KPIs + gauges, 2df602c docs). 80 tests nuevos (11 en common, 69 en dashboard-customer). 537 tests totales. JaCoCo >= 75% domain. 5 ArchUnit en verde incluido DashboardIsolationTest (3 reglas). mvn verify SUCCESS 3:44 min; mvn -pl it verify -Ddocker.available=true SUCCESS 4:40 min.',
       'docs-writer', NOW()
FROM feature WHERE subproyecto='customer' AND slug='consulta-entidad-ui';

-- -----------------------------------------------------------------------------
-- Broker de mensajeria (OPS-010): sustituye a Apache Kafka+ZooKeeper por
-- Redpanda v25.3.9 LTS. Spec inicial (broker-de-mensajeria.md), ADR-0014
-- nuevo, ADR-0011 revisado (consumer group por cluster K8s).
-- 5 commits (H-0..H-5): tests rojos, renombrado de variable, compose,
-- manifiestos K8s, doc + baseline pre-Redpanda.
-- -----------------------------------------------------------------------------
INSERT INTO feature (subproyecto, slug, nombre, descripcion, spec_path, estado, solicitada_por, solicitada_el) VALUES
 ('common','broker-de-mensajeria','Broker de mensajeria: sustitucion Kafka+ZooKeeper por Redpanda','docs/sdd/common/broker-de-mensajeria.md','implementada','sdd-registry-check', CURDATE())
 ON DUPLICATE KEY UPDATE estado='implementada';

INSERT INTO feature_evento (feature_id, accion, resumen, detalle, autor, ocurrido_el)
SELECT id,'ALTA','Spec inicial del broker de mensajeria: Redpanda v25.3.9 LTS','Sustituye Kafka 3.x + ZooKeeper por Redpanda (wire Kafka 3.x, sin JVM, sin ZooKeeper). Operado por Redpanda Operator + CRD cluster.redpanda.com/v1alpha2 (kind: Redpanda). Manifiestos Kustomize puros (no Helm+FluxCD). Un cluster por cluster K8s, RF=3 test/prod / RF=1 local, Tiered Storage desactivado (OPS-011 propuesto). Cero cambios funcionales en Java: solo renombrado cosmetico de KAFKA_BOOTSTRAP a MESSAGING_BOOTSTRAP. 5 commits H-0..H-5 (da09f34 tests rojos, 4f41596 renombrado, c2e425e compose, 50e1273 K8s, 5cdc987 ADR-0014 + revision ADR-0011; commit H-5 doc + baseline). ADR-0014 cierra el supuesto D-16 de ADR-0011 (unico Kafka multi-AZ compartido por clusters).','dev-implementer', NOW()
FROM feature WHERE subproyecto='common' AND slug='broker-de-mensajeria';
