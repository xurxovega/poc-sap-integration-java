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
| Sincronización del cliente (agregado) | [`sincronizacion-cliente.md`](customer/sincronizacion-cliente.md) | ✅ | ✅ re-entrada desde cualquier estado (incl. `SAP_ERROR` y ciclo en vuelo) y `ERROR` ante fallo de infra — verificado en vivo el 2026-09-12 |
| Sincronización de dirección | [`sincronizacion-direccion.md`](customer/sincronizacion-direccion.md) | ✅ | ✅ implementado (BTP + OData), verificado end-to-end |
| Sincronización de datos fiscales | `sincronizacion-datos-fiscales.md` | ⬜ | ✅ implementado |
| Sincronización de datos de contacto | `sincronizacion-contacto.md` | ⬜ | ⚠️ no usa el contrato real de S/4 (`A_AddressEmailAddress`/`A_AddressPhoneNumber`) |
| Sincronización de datos bancarios | [`sincronizacion-datos-bancarios.md`](customer/sincronizacion-datos-bancarios.md) | ✅ | ⚠️ contratos S/4 corregidos (BIC fuera de `BankIdentification`, mandato en `API_APAR_SEPA_MANDATE_SRV`), pendientes de validar contra el tenant; los mandatos no llegan del legacy |
| Baja de cliente | [`baja-cliente.md`](customer/baja-cliente.md) | ✅ | ✅ ejecutable, `DELETE` HTTP real y modelo de bloqueo — verificado en vivo el 2026-09-12 por CDC |
| Baja de mandato SEPA | [`baja-mandato-sepa.md`](customer/baja-mandato-sepa.md) | ✅ | ⚠️ revoca por `PATCH` de estado (antes `POST` ficticio); sin llamador hasta que el legacy emita mandatos |

### `article/` — [ver carpeta](article/)

| Feature | Fichero | Spec | Estado del código |
|---|---|---|---|
| Sincronización del artículo | [`sincronizacion-articulo.md`](article/sincronizacion-articulo.md) | ✅ | ✅ implementado, verificado end-to-end |

### `supplier/` — [ver carpeta](supplier/)

| Feature | Fichero | Spec | Estado del código |
|---|---|---|---|
| Sincronización del proveedor | `sincronizacion-proveedor.md` | ⬜ | 🔮 placeholder, dominio no operativo |

### `common/` — [ver carpeta](common/)

Capacidades transversales del shared kernel, no features de negocio.

| Capacidad | Fichero | Spec | Estado del código |
|---|---|---|---|
| Máquina de estados de sincronización | [`maquina-de-estados.md`](common/maquina-de-estados.md) | ✅ | ✅ implementada (`SyncStateMachine`) |
| Idempotencia, dedupe y consistencia de la imagen | [`idempotencia-y-dedupe.md`](common/idempotencia-y-dedupe.md) | ✅ | ✅ implementada: dedupe contra el último `SENT_SAP`, imagen tras el ACK, histórico por intento |
| Cliente SAP: transporte, resiliencia y CSRF | [`resiliencia-cliente-sap.md`](common/resiliencia-cliente-sap.md) | ✅ | ✅ implementada (`RestClientSapClient`, ADR-0001) |
| Autenticación hacia SAP (OAuth2/basic, sin stub silencioso) | [`autenticacion-sap.md`](common/autenticacion-sap.md) | ✅ | ✅ implementada (`BtpAuthProvider`, `S4NativeAuthProvider`; `sap.auth.allow-stub`) |
| Observabilidad y operación del pipeline | [`observabilidad.md`](common/observabilidad.md) | ✅ | ⚠️ métricas (estado, etapa, HTTP SAP, Resilience4j), parada ordenada y presupuesto de reintentos hechos; trazas pendientes de D-7 |

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
| Sin DLQ Kafka | `KafkaErrorHandlingConfig` (customer y article): reintentos con backoff + `DeadLetterPublishingRecoverer` → topic `<topic>-dlt` | 2026-07-25 |
| Sin timeout WebClient SAP | `sap.client.connect-timeout-ms` / `sap.client.response-timeout-ms` en `application-common.yml` | 2026-07-25 |
| CSRF no cableado | `common/sap/odata/CsrfTokenProvider` + `S4CsrfTokenProvider` (fetch de `x-csrf-token` para POST/PATCH/DELETE) | 2026-07-25 |
| Sin idempotencia de consumo | dedupe por `payloadHash` en `SyncCustomerUseCase` (`stateRepo.alreadySent(...)`) | 2026-07-25 |
| Elasticsearch 8 contra cliente 9 | `external-services/docker-compose.yml`: el compose levantaba ES/Kibana 8.11.0, pero Spring Boot 4.0 trae `elasticsearch-java` 9.x, que envía `application/vnd.elasticsearch+json;compatible-with=9` y el servidor 8 rechaza (`media_type_header_exception`). Rompía la indexación y dejaba el health en 503. Subido a 9.2.1 | 2026-09-09 |
| Mongo escribía en la base `test` | `customer`/`article` `application.yml`: Boot 4 movió las propiedades de conexión de `spring.data.mongodb.*` a `spring.mongodb.*`. La propiedad antigua se ignora en silencio y ambas apps caían al default del driver (`mongodb://localhost/test`), compartiendo base. Cubierto por `CustomerMongoDatabaseConfigTest` | 2026-09-09 |
| **`SAP_ERROR` y estados intermedios eran sumideros** (B1, B12, B14) | Un cliente con un envío fallido, o cuyo proceso murió a mitad, no volvía a sincronizarse: `SAP_ERROR → RECEIVED` no existía y el orquestador siempre entraba por `RECEIVED`. Causa raíz: abrir ciclo y avanzar compartían una tabla. Ahora `beginCycle` es legal desde cualquier estado (test de propiedad) y `advance` sigue la tabla. Además, un fallo de infra tras `VALID` deja `ERROR` y se propaga. Spec: [`common/maquina-de-estados.md`](common/maquina-de-estados.md), [`customer/sincronizacion-cliente.md`](customer/sincronizacion-cliente.md) | 2026-09-11 |
| **La baja nunca se ejecutaba** (B2) | Entraba por `SENDING_SAP` (no admitido) y de haberlo hecho mandaba `POST {}`. Ahora `SENDING_SAP` es estado de entrada, el puerto tiene `delete()` que emite `DELETE` real, y la imagen se **bloquea** en vez de borrarse. Spec: [`customer/baja-cliente.md`](customer/baja-cliente.md) | 2026-09-11 |
| Estado actual no determinista y sin versión optimista (B11, A2) | `currentState` ordenaba por `timestamp` en ms (empates en ráfaga) y `transition` era read-then-write. Ahora cada transición lleva `seq` monótona, el orden es por `seq`, y el índice único `dom_ent_seq_uk` hace que una escritura concurrente falle con `ConcurrentTransitionException` en vez de pisar el estado | 2026-09-11 |
| Circuito abierto tragado como `SapResponse(0)` (B13) | `CallNotPermittedException` caía en `catch (Exception)` y el use case marcaba `SAP_ERROR` sin reintento ni señal. Ahora se propaga como `SapCircuitOpenException` (transitoria) y el circuit breaker envuelve al retry, no al revés | 2026-09-11 |
| **Cuatro tests que nunca se ejecutaban** (B7) | `SyncCustomerControllerIT` seguía el patrón `*IT` sin failsafe en `customer`. Renombrado a `SyncCustomerControllerTest`; surefire y failsafe fijados a 3.5.3 en el parent; CI en `.github/workflows/ci.yml` (`mvn verify` sin Docker + job con Docker) que lo habría cazado | 2026-09-12 |
| **Contract tests que no tocaban código de producción** (B6) | `AbstractSapContractTest` stubbeaba WireMock y lo llamaba con `HttpClient` del JDK. Ahora construye el `WebClientSapClient` real y cada test ejercita el adaptador de producción (método, path, cabeceras, cuerpo). El `BtpCustomerContractTest` antiguo (fuera de `contract/`) se ha borrado | 2026-09-12 |
| Spring Cloud incompatible con Boot 4 y Boot desactualizado (A12, D-6) | BOM de Spring Cloud eliminado (solo lo usaba `spring-cloud-contract-wiremock`, sin referencias); Boot **4.1.1**; `release 25` sin perfil `jdk25` (JDK 25 mínimo real, JEP 491 sin *pinning* de virtual threads) | 2026-09-12 |
| Recuento de tests incoherente entre documentos (A15) | Una sola cifra de `@Test` **declarados** (§1 de `TESTING.md`) vigilada por `TestCountMatchesDocsTest`: el build falla si un documento se queda atrás | 2026-09-12 |
| Sin umbral de cobertura ni regla de arquitectura activa (TEST-6, A4 parcial) | JaCoCo `check` en el parent: ≥ 75 % de líneas en `**/domain/**` (suelo medido). ArchUnit `DomainPurityTest` en common, customer y article: `domain` sin Spring/Jackson/Mongo/Kafka/Micrometer/JPA | 2026-09-12 |
| `InfrastructureSmokeIT` nunca arrancaba Kafka | `KafkaContainer(String)` deprecado duplicaba el nombre de la imagen en Testcontainers 1.21 (`cp-kafka:confluentinc/cp-kafka:7.7.1`). Sustituido por `ConfluentKafkaContainer` | 2026-09-12 |
| **Transporte reactivo con `.block()` y Cloud SDK sin uso** (D-1) | `WebClientSapClient` → `RestClientSapClient` (`RestClient` sobre el `HttpClient` del JDK) detrás del mismo puerto; fuera `webflux`, Reactor, `sdk-core`, el `@ComponentScan("com.sap.cloud.sdk")` y el destino local del SDK. Mismo comportamiento observable: el test se portó íntegro. [ADR-0001](../architecture/adr/0001-transporte-http-sap-restclient.md); spec [`common/resiliencia-cliente-sap.md`](common/resiliencia-cliente-sap.md) | 2026-09-12 |
| CSRF: fetch con Basic fijo, cualquier 403 tratado como CSRF, caché sin sincronizar (A6, C4) | El fetch usa la misma `Authorization` que la escritura; solo un 403 con `x-csrf-token: Required` refresca y reintenta; token y cookies son un único valor inmutable (`CsrfToken`) | 2026-09-12 |
| **Contratos S/4 de banco y mandato incorrectos** (B3, parcial) | `S4BankingAdapter` enviaba a `API_CUSTOMER_MANDATE` (no existe) → `BtpBankingAdapter` en la familia BTP; `A_BusinessPartnerBank` llevaba el BIC en `BankIdentification` → ordinal `0001` + `BankCountryKey`, sin BIC; mandatos → `SepaMandateODataAdapter` sobre `API_APAR_SEPA_MANDATE_SRV` con `Creditor` por configuración, y la baja como `PATCH` de estado. Specs [`customer/sincronizacion-datos-bancarios.md`](customer/sincronizacion-datos-bancarios.md) y [`customer/baja-mandato-sepa.md`](customer/baja-mandato-sepa.md). Queda de B3: contacto (`A_AddressEmailAddress`/`A_AddressPhoneNumber`) y upsert con `AddressID`, ambos bloqueados por la comprobación contra el tenant | 2026-09-12 |
| Fallback silencioso a token stub y secretos en el YAML empaquetado (A8) | `BtpAuthProvider`/`S4NativeAuthProvider` validan al arrancar: sin credenciales, `IllegalStateException` con las propiedades que faltan; el stub solo con `sap.auth.allow-stub=true` y aviso. `sa`/`SqlServer_Pa55w0rd!`, `postgres/postgres` y `trustServerCertificate=true` fuera de los YAML: los aporta `scripts/env/*.env`. Spec [`common/autenticacion-sap.md`](common/autenticacion-sap.md) | 2026-09-12 |
| Observabilidad prometida y no implementada (A9, parcial) | `recordStageDuration` nunca se invocaba → cableado en `SyncCustomerUseCase` y `SyncArticleUseCase` (`fetch`/`validate`/`index`/`send`); sin métricas HTTP del cliente SAP → `sap_client_request_duration` por intento; sin binder de Resilience4j → `MeterBinder` para retry y circuit breaker `sap`; tag `application` idéntico en ambas apps → `${spring.application.name}`; logs JSON → formato ECS por `LOGGING_STRUCTURED_FORMAT_CONSOLE`. Quedan las trazas (D-7). Spec [`common/observabilidad.md`](common/observabilidad.md) | 2026-09-12 |
| **La primera escritura en Elasticsearch rompía** con `ClassNotFoundException: io.opentelemetry.semconv.DbAttributes` | `elasticsearch-java` 9.4.x (Boot 4.1) arrastra `opentelemetry-semconv` 1.30.0-rc.1 pero su instrumentación OTel usa `DbAttributes`, que existe desde 1.34.0. Fijado a 1.34.0 en el parent. **Ningún test lo cazó**: los slices y los smoke de contexto no escriben en ES; solo el e2e con Docker (TEST-1) lo habría visto. Detectado en la verificación en vivo de la Fase 6 | 2026-09-12 |
| README decía «wrapper incluido» y no existía; surefire sin versión resolvía distinto sin wrapper (A24) | `./mvnw` con Maven 3.9.9 fijado en `.mvn/wrapper/maven-wrapper.properties`; la CI lo usa; `maven-enforcer` exige Maven ≥ 3.9 y JDK ≥ 25 | 2026-09-12 |
| Sin Dependabot, SBOM ni imágenes fijadas (A22) | `.github/dependabot.yml` (Maven + Actions, semanal); SBOM CycloneDX agregado en `package`, artefacto `sbom` en la CI; 11 imágenes del compose por `@sha256` | 2026-09-12 |
| `launch.json` activaba un perfil `dev` inexistente (A21, parcial) | Sin perfil: carga `scripts/env/local.env` con `envFile`. El artefacto de despliegue queda pendiente de D-9 | 2026-09-12 |
| Dedupe contra cualquier `SENT_SAP` histórico (A1) | `alreadySent` compara solo con el **último** `SENT_SAP`: A→B→A ya reenvía el tercer evento. Spec [`common/idempotencia-y-dedupe.md`](common/idempotencia-y-dedupe.md) | 2026-09-12 |
| Imagen persistida antes del ACK de SAP | `SyncCustomerUseCase` y `SyncArticleUseCase` guardan la imagen **solo** si el ciclo termina en `SENT_SAP`; un `SAP_ERROR` ya no deja una imagen que SAP nunca recibió (y el atajo «sin cambios reales» deja de saltarse el reenvío) | 2026-09-12 |
| Id del histórico ES `entityId-hash` sobrescribía reenvíos (A31) | id `entityId-hash-epochMillis`: un documento por intento | 2026-09-12 |
| Sin parada ordenada y presupuesto de reintentos que rozaba `max.poll.interval.ms` (A10) | `server.shutdown=graceful` + 30 s por fase; `max.poll.interval.ms` a 15 min y `RetryBudgetGuard` que calcula el peor caso por mensaje (5,1 min con los defaults) y no deja arrancar si no cabe | 2026-09-12 |
| Listeners reintentaban fallos no transitorios (C5) | Tombstone, `operation` en minúsculas y operación desconocida producían 3 reintentos con backoff. Ahora: tombstone ignorado, operación normalizada, e `IllegalState/IllegalArgument/JsonProcessing` declaradas no reintentables en `KafkaErrorHandlingConfig` | 2026-09-11 |
| Topic DLT documentado ≠ real | Toda la documentación decía `<topic>.DLT`; el `DeadLetterPublishingRecoverer` usa el sufijo por defecto de Spring Kafka y el topic real es **`<topic>-dlt`**. Corregidas las 23 ocurrencias; decisión en `MEJORAS-Y-PROPUESTAS.md` OPS-3 | 2026-09-11 |
| Re-sync con cambios reales rompía el pipeline | Tras un primer ciclo, cada línea de feature quedaba en `SENT_SAP` y `SENT_SAP → VALIDATING` no era transición permitida: el segundo evento con cambios reales moría en la primera feature y acababa en la DLT. Añadida la **re-entrada de features** por `VALIDATING` desde `SENT_SAP`, `INVALID` y `SAP_ERROR`. Spec: [`common/maquina-de-estados.md`](common/maquina-de-estados.md) AC-4/AC-5 · verificado por CDC en vivo | 2026-09-10 |
| Pipeline por feature nunca arrancaba | Los cuatro `Sync<Feature>UseCase` no registraban la entrada en `VALIDATING`, así que la máquina evaluaba `null → VALID` y `POST /customers/sync` devolvía 500 siempre. Además `VALID → SENDING_SAP` no era legal: la máquina solo modelaba el pipeline agregado, que pasa por `INDEXING`. Primer ciclo SDD+TDD del proyecto: [`customer/sincronizacion-direccion.md`](customer/sincronizacion-direccion.md) AC-4/AC-5 | 2026-09-09 |
| Índice único inservible en `*_current` | `external-services/mongodb/init.js` creaba `{id:1} unique`, pero los documentos usan `@Id` (se guarda como `_id`) y no tienen campo `id`: todos valían `null` y solo entraba **un** documento por colección (`E11000 dup key: { id: null }`). Índice eliminado | 2026-09-09 |
| Índice Mongo con nombre distinto al de la app | `external-services/mongodb/init.js` creaba el índice de `sync_state` sin nombre (Mongo lo autonombraba) y la app lo declara como `dom_ent_idx`: al arrancar, error 85 `IndexOptionsConflict`. Alineado el nombre en el init | 2026-09-09 |
| Debezium/outbox no cableado | `external-services/`: tablas outbox + triggers (`sqlserver/init.sql`, `postgresql/init.sql`), servicio `kafka-connect` y conectores en `debezium/` | 2026-07-25 |

### Pendientes

| Brecha | Dónde | Impacto |
|---|---|---|
| Comandos `docker exec` rotos en Git Bash | `docs/QUICK_START.md`, `docs/testing/GUIA-PRUEBAS.md` | Las rutas absolutas del contenedor (`/opt/mssql-tools18/bin/sqlcmd`) las convierte Git Bash a rutas Windows y el `exec` falla. Hay que anteponer `MSYS_NO_PATHCONV=1` — justo el shell que la guía recomienda en Windows |
| Sin transacción distribuida / saga entre features | `SyncCustomerUseCase` (envíos por feature independientes) | fallos parciales dejan SAP a medias, sin compensación |
| Contactos no usan `A_AddressEmailAddress`/`A_AddressPhoneNumber` | adaptadores de CONTACT | el contrato real de S/4 para email/teléfono es por dirección |
| Mandatos no llegan desde el legacy | `SepaMandateODataAdapter` listo, `DeleteMandateUseCase` sin llamador | BANKING incompleto: solo llegan `mandateIds` |
| Sin mapeo fino de errores SAP | `RestClientSapClient` | diagnóstico deficiente |
| `supplier` vacío + MinIO sin uso | `SupplierApplicationPlaceholder`, compose | dominio/infra no operativos |
| APIs REST sin autenticación | controllers de `customer`/`article` | bloqueante para exponer las APIs a terceros o a un MCP ([`../tools-integrations/MCP.md`](../tools-integrations/MCP.md) §4) |
| Servidor MCP para agentes IA (propuesta) | servicio `mcp-server` futuro | requiere autenticación + ofuscación de PII — ver [`../tools-integrations/MCP.md`](../tools-integrations/MCP.md) |

> Esto son **defectos**, con su detalle técnico. Lo mismo contado en lenguaje de
> negocio está en el [`CHANGELOG.md`](../../CHANGELOG.md) raíz; las mejoras aún
> no abordadas, en [`../MEJORAS-Y-PROPUESTAS.md`](../MEJORAS-Y-PROPUESTAS.md).

## 7. Supuestos vigentes

- **Auth SAP**: OAuth2 client-credentials vía variables de entorno; sin
  credenciales la app no arranca, salvo `sap.auth.allow-stub=true` (mock local).
- **Cliente HTTP SAP definitivo**: `RestClientSapClient` ([ADR-0001](../architecture/adr/0001-transporte-http-sap-restclient.md)).
  `sap-sdk-client/` es un spike OpenAPI **desechable**, gitignored, fuera del reactor.
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
