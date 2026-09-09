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
