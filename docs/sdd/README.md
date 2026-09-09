# SDD — Spec-Driven Development

> Carpeta ancla del proyecto. Aquí vive **qué debe hacer** cada feature; en
> [`../architecture/`](../architecture/) vive **cómo está construido** el sistema
> y en [`DESARROLLO.md`](../architecture/DESARROLLO.md) **cómo se trabaja**.
> Este fichero es además el estado global del proyecto (índice de features +
> changelog).

## 1. La regla del ancla (bidireccional)

El spec y el código son **el mismo hecho contado dos veces**. No pueden divergir:

| Si cambia… | …entonces |
|---|---|
| **el spec** | se cambian los tests y el código en el **mismo PR**; el criterio de aceptación nuevo empieza en rojo (TDD) |
| **el código** (comportamiento observable) | se actualiza el spec de la feature en el **mismo PR**, con su entrada en *Cambios* |
| **un contrato SAP** ([`sap-api-catalog.md`](sap-api-catalog.md)) | se revisan los specs de las features que lo consumen antes de tocar adaptadores |

Un PR que cambia comportamiento sin tocar spec está **incompleto**, igual que un
spec cambiado sin tests que lo respalden. Cada criterio de aceptación del spec
se corresponde con **al menos un test** que lo cita.

## 2. Estructura

Una carpeta por **subproyecto** (módulo Maven) y, dentro, **un fichero por
feature** nombrado por lo que la feature hace:

```
docs/sdd/
├── README.md              este índice + criterios globales + changelog
├── _template/feature.md   plantilla para una feature nueva
├── sap-api-catalog.md     contratos externos: specs OpenAPI oficiales de SAP
├── customer/              specs del dominio customer
│   ├── CHANGELOG.md       cambios de sus features, en orden cronológico
│   └── sincronizacion-direccion.md
├── article/
├── supplier/
└── common/                capacidades transversales del shared kernel
```

| Carpeta | Qué guarda |
|---|---|
| [`customer/`](customer/) | features del cliente: sync del agregado, dirección, datos fiscales, contacto, datos bancarios y bajas |
| [`article/`](article/) | features del artículo; hoy un único flujo sobre el agregado, sin pipeline por feature |
| [`supplier/`](supplier/) | features del proveedor, cuando se aborde el dominio (hoy es un placeholder no desplegable) |
| [`common/`](common/) | **capacidades transversales** del shared kernel: máquina de estados, idempotencia, resiliencia SAP, observabilidad. Un spec de negocio nunca va aquí: va en el dominio que lo usa, aunque se apoye en piezas de `common`. Cambiarlas afecta a todos los dominios ([`../architecture/OVERVIEW.md`](../architecture/OVERVIEW.md) §6) |

Convenciones:

- **El fichero es la feature**, en `kebab-case` y orientado a negocio:
  `sincronizacion-direccion.md`, `baja-mandato-sepa.md`. Nunca `spec.md`, ni el
  nombre de la clase (`BtpAddressAdapter`).
- Si una feature necesita material extra (payloads de ejemplo, tablas de mapeo
  largas), se le crea una carpeta hermana con su mismo nombre; el spec sigue
  siendo el fichero.
- El spec describe **comportamiento observable**; los detalles de stack se
  enlazan a [`../architecture/TECH.md`](../architecture/TECH.md) en vez de copiarse.

## 3. Ciclo de trabajo

`spec` → tests en rojo → código → refactor → actualizar estado aquí.
El detalle del ciclo y las reglas de TDD están en
[`DESARROLLO.md`](../architecture/DESARROLLO.md).

## 4. Criterios de aceptación globales

Aplican a **toda** feature, además de los suyos propios:

- La feature se procesa por el **mismo pipeline** venga de CDC, evento Kafka o REST.
- El envío a SAP es **idempotente** y verificable por `payloadHash`.
- El estado del registro es **consultable**: imagen actual (Mongo) + histórico (ES).
- Cada transición de estado emite **métrica** por dominio y estado.
- Un cambio en la feature **no obliga a desplegar otros dominios** (salvo cambio en `common`).
- Cobertura: 100 % unit sobre las validaciones de `domain`; al menos una
  integración end-to-end con infraestructura real (broker, BD, SAP mock).

## 5. Índice de features

Los nombres de fichero son los **acordados**; los que aún no existen se crearán
con ese nombre la primera vez que se toque la feature.

### `customer/` — [ver carpeta](customer/)

| Feature | Fichero | Spec | Estado del código |
|---|---|---|---|
| Sincronización del cliente (agregado) | `sincronizacion-cliente.md` | ⬜ | ✅ implementado, verificado end-to-end |
| Sincronización de dirección | [`sincronizacion-direccion.md`](customer/sincronizacion-direccion.md) | ✅ | ✅ implementado (BTP + OData), verificado end-to-end |
| Sincronización de datos fiscales | `sincronizacion-datos-fiscales.md` | ⬜ | ✅ implementado |
| Sincronización de datos de contacto | `sincronizacion-contacto.md` | ⬜ | ⚠️ no usa el contrato real de S/4 (`A_AddressEmailAddress`/`A_AddressPhoneNumber`) |
| Sincronización de datos bancarios | `sincronizacion-datos-bancarios.md` | ⬜ | ⚠️ los mandatos no llegan del legacy |
| Baja de cliente | `baja-cliente.md` | ⬜ | ✅ implementado |
| Baja de mandato SEPA | `baja-mandato-sepa.md` | ⬜ | ✅ implementado |

### `article/` — [ver carpeta](article/)

| Feature | Fichero | Spec | Estado del código |
|---|---|---|---|
| Sincronización del artículo | `sincronizacion-articulo.md` | ⬜ | ✅ implementado, verificado end-to-end |

### `supplier/` — [ver carpeta](supplier/)

| Feature | Fichero | Spec | Estado del código |
|---|---|---|---|
| Sincronización del proveedor | `sincronizacion-proveedor.md` | ⬜ | 🔮 placeholder, dominio no operativo |

### `common/` — [ver carpeta](common/)

Capacidades transversales del shared kernel, no features de negocio.

| Capacidad | Fichero | Spec | Estado del código |
|---|---|---|---|
| Máquina de estados de sincronización | [`maquina-de-estados.md`](common/maquina-de-estados.md) | ✅ | ✅ implementada (`SyncStateMachine`) |
| Idempotencia y deduplicación por hash | `idempotencia-y-dedupe.md` | ⬜ | ✅ implementada (`alreadySent`, `Idempotency-Key`) |
| Resiliencia del cliente SAP (retry + circuit breaker + CSRF) | `resiliencia-cliente-sap.md` | ⬜ | ✅ implementada (`WebClientSapClient`) |
| Observabilidad: métricas por dominio y estado | `observabilidad.md` | ⬜ | ✅ implementada (`SyncMetrics`) |

> Las features ya implementadas se escribieron antes de adoptar SDD. Regla de
> transición: **la primera vez que se toca una feature, se escribe su spec** a
> partir de [`_template/feature.md`](_template/feature.md). No se escriben todos
> los specs de golpe para no generar documentación muerta.

## 6. Changelog global

Estado de las brechas detectadas sobre el código real.

### Resueltas

| Brecha | Dónde se resolvió | Fecha |
|---|---|---|
| Auth SAP era stub | `common/sap/auth/` — `OAuth2TokenClient` con client-credentials real; `BtpAuthProvider`/`S4NativeAuthProvider` caen a token stub solo si falta configuración | 2026-07-25 |
| Retry/circuit breaker Resilience4j no disparaba | `WebClientSapClient` decora las llamadas con `Retry` + `CircuitBreaker` de los registries | 2026-07-25 |
| Sin DLQ Kafka | `KafkaErrorHandlingConfig` (customer y article): reintentos con backoff + `DeadLetterPublishingRecoverer` → topic `<topic>.DLT` | 2026-07-25 |
| Sin timeout WebClient SAP | `sap.client.connect-timeout-ms` / `sap.client.response-timeout-ms` en `application-common.yml` | 2026-07-25 |
| CSRF no cableado | `common/sap/odata/CsrfTokenProvider` + `S4CsrfTokenProvider` (fetch de `x-csrf-token` para POST/PATCH/DELETE) | 2026-07-25 |
| Sin idempotencia de consumo | dedupe por `payloadHash` en `SyncCustomerUseCase` (`stateRepo.alreadySent(...)`) | 2026-07-25 |
| Elasticsearch 8 contra cliente 9 | `external-services/docker-compose.yml`: el compose levantaba ES/Kibana 8.11.0, pero Spring Boot 4.0 trae `elasticsearch-java` 9.x, que envía `application/vnd.elasticsearch+json;compatible-with=9` y el servidor 8 rechaza (`media_type_header_exception`). Rompía la indexación y dejaba el health en 503. Subido a 9.2.1 | 2026-09-09 |
| Mongo escribía en la base `test` | `customer`/`article` `application.yml`: Boot 4 movió las propiedades de conexión de `spring.data.mongodb.*` a `spring.mongodb.*`. La propiedad antigua se ignora en silencio y ambas apps caían al default del driver (`mongodb://localhost/test`), compartiendo base. Cubierto por `CustomerMongoDatabaseConfigTest` | 2026-09-09 |
| Re-sync con cambios reales rompía el pipeline | Tras un primer ciclo, cada línea de feature quedaba en `SENT_SAP` y `SENT_SAP → VALIDATING` no era transición permitida: el segundo evento con cambios reales moría en la primera feature y acababa en la DLT. Añadida la **re-entrada de features** por `VALIDATING` desde `SENT_SAP`, `INVALID` y `SAP_ERROR`. Spec: [`common/maquina-de-estados.md`](common/maquina-de-estados.md) AC-4/AC-5 · verificado por CDC en vivo | 2026-09-10 |
| Pipeline por feature nunca arrancaba | Los cuatro `Sync<Feature>UseCase` no registraban la entrada en `VALIDATING`, así que la máquina evaluaba `null → VALID` y `POST /customers/sync` devolvía 500 siempre. Además `VALID → SENDING_SAP` no era legal: la máquina solo modelaba el pipeline agregado, que pasa por `INDEXING`. Primer ciclo SDD+TDD del proyecto: [`customer/sincronizacion-direccion.md`](customer/sincronizacion-direccion.md) AC-4/AC-5 | 2026-09-09 |
| Índice único inservible en `*_current` | `external-services/mongodb/init.js` creaba `{id:1} unique`, pero los documentos usan `@Id` (se guarda como `_id`) y no tienen campo `id`: todos valían `null` y solo entraba **un** documento por colección (`E11000 dup key: { id: null }`). Índice eliminado | 2026-09-09 |
| Índice Mongo con nombre distinto al de la app | `external-services/mongodb/init.js` creaba el índice de `sync_state` sin nombre (Mongo lo autonombraba) y la app lo declara como `dom_ent_idx`: al arrancar, error 85 `IndexOptionsConflict`. Alineado el nombre en el init | 2026-09-09 |
| Debezium/outbox no cableado | `external-services/`: tablas outbox + triggers (`sqlserver/init.sql`, `postgresql/init.sql`), servicio `kafka-connect` y conectores en `debezium/` | 2026-07-25 |

### Pendientes

| Brecha | Dónde | Impacto |
|---|---|---|
| Topic DLT real ≠ documentado | `KafkaErrorHandlingConfig` | El `DeadLetterPublishingRecoverer` usa el sufijo por defecto de Spring Kafka, así que el topic real es **`outbox.CUSTOMER-dlt`**, no `<topic>.DLT` como dice toda la documentación. Verificado: el mensaje fallido aterrizó en `outbox.CUSTOMER-dlt`. Hay que decidir si se configura el sufijo o se corrige la documentación |
| Comandos `docker exec` rotos en Git Bash | `docs/QUICK_START.md`, `docs/testing/GUIA-PRUEBAS.md` | Las rutas absolutas del contenedor (`/opt/mssql-tools18/bin/sqlcmd`) las convierte Git Bash a rutas Windows y el `exec` falla. Hay que anteponer `MSYS_NO_PATHCONV=1` — justo el shell que la guía recomienda en Windows |
| Sin transacción distribuida / saga entre features | `SyncCustomerUseCase` (envíos por feature independientes) | fallos parciales dejan SAP a medias, sin compensación |
| Contactos no usan `A_AddressEmailAddress`/`A_AddressPhoneNumber` | adaptadores de CONTACT | el contrato real de S/4 para email/teléfono es por dirección |
| Mandatos no llegan desde el legacy | `S4BankingAdapter` (mandates) | BANKING incompleto |
| Atomicidad Mongo / race condition en estado sync | `MongoSyncStateRepository.transition` | inconsistencias bajo concurrencia |
| Sin mapeo fino de errores SAP | `WebClientSapClient` | diagnóstico deficiente |
| `supplier` vacío + MinIO sin uso | `SupplierApplicationPlaceholder`, compose | dominio/infra no operativos |
| APIs REST sin autenticación | controllers de `customer`/`article` | bloqueante para exponer las APIs a terceros o a un MCP ([`../tools-integrations/MCP.md`](../tools-integrations/MCP.md) §4) |
| Servidor MCP para agentes IA (propuesta) | servicio `mcp-server` futuro | requiere autenticación + ofuscación de PII — ver [`../tools-integrations/MCP.md`](../tools-integrations/MCP.md) |

> Esto son **defectos**. Las mejoras e ideas que aún no se han abordado viven en
> [`../MEJORAS-Y-PROPUESTAS.md`](../MEJORAS-Y-PROPUESTAS.md).

## 7. Supuestos vigentes del PoC

- **Auth SAP**: OAuth2 client-credentials vía variables de entorno; con
  configuración incompleta se usa token stub (solo para mocks locales).
- **Cliente HTTP SAP definitivo**: `WebClientSapClient`. `sap-sdk-client/` es un
  spike OpenAPI **desechable**, no forma parte del reactor.
- **Debezium/outbox**: cableado en `external-services/` (triggers + Kafka
  Connect); la operación en entornos reales sigue siendo externa.
- **`supplier`**: futuro, patrón simple como `article`.
- **MinIO**: sin uso; posible futuro `S3ImageStoreAdapter` si hace falta blob storage.

## 8. Registro de features (MySQL)

Los specs cuentan **qué** hace cada feature. Lo que no se consulta bien en
Markdown — quién la pidió, cuándo, en qué estado está, cómo ha ido cambiando —
vive en una base de datos aparte:

| | |
|---|---|
| Base | `sdd_registry` en el contenedor `mysql-sdd` (`localhost:3306`) |
| Credenciales locales | `sdd` / `sdd` |
| Definición y carga inicial | [`../../external-services/mysql/init.sql`](../../external-services/mysql/init.sql) |

**No es una base de datos de la aplicación**: ningún módulo del reactor se
conecta a ella. Es el registro del trabajo.

| Tabla | Qué guarda |
|---|---|
| `feature` | una fila por feature solicitada: subproyecto, slug, nombre, descripción ampliada, ruta al spec, estado, quién y cuándo la pidió |
| `feature_evento` | su ciclo de vida: un evento por cada **ALTA**, **MODIFICACION** o **BAJA**, con resumen, detalle y autor |
| `v_feature_estado` | vista de conveniencia: último movimiento de cada feature |

La identidad de una feature es el par `(subproyecto, slug)`, que coincide con la
ruta de su spec: `docs/sdd/<subproyecto>/<slug>.md`.

Las bajas son **lógicas** (`eliminada_el`): una feature descartada no se borra,
para no perder por qué se pidió ni por qué se dejó.

### Qué hay que registrar

Por ahora **solo el ciclo de vida**: cuando se crea, se modifica o se elimina una
feature. Al hacerlo hay que tocar tres sitios, y los tres van en el mismo PR:

1. El **spec** de la feature (§10 Cambios).
2. El **CHANGELOG.md** de su carpeta.
3. Un `INSERT` en `feature_evento` (y en `feature` si es un alta).

```sql
-- Alta de una feature nueva
INSERT INTO feature (subproyecto, slug, nombre, descripcion, spec_path, estado, solicitada_por, solicitada_el)
VALUES ('customer','baja-cliente','Baja de cliente','...','docs/sdd/customer/baja-cliente.md','en_diseno','...', CURDATE());

INSERT INTO feature_evento (feature_id, accion, resumen, detalle, autor)
SELECT id,'ALTA','Spec inicial','...','...' FROM feature
WHERE subproyecto='customer' AND slug='baja-cliente';
```
