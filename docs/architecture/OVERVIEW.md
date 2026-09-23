# Arquitectura — Visión general

> Mapa y esquemas del aplicativo `poc-sap-integration-java` (Java 25 + Spring Boot 4.1 + Maven).

## Objetivo y alcance

Sincronizar datos maestros (`customer`, `article`, `supplier`) desde sistemas
legacy hacia **SAP S/4 Public Cloud**, con validación de negocio, trazabilidad
de estado por registro, idempotencia y observabilidad, permitiendo el
**despliegue independiente de cada dominio**.

| | |
|---|---|
| **En alcance** | CDC desde legacy (outbox + Debezium) · validación de negocio · indexación (imagen actual + histórico) · envío a APIs SAP BTP y nativas S/4 · API REST síncrona como entrada alternativa · consumo de eventos Kafka directos · trazabilidad por registro vía máquina de estados |
| **Fuera de alcance (futuro)** | orquestación tipo *saga* entre dominios · GraphQL y notificaciones WebSocket · event sourcing completo (sí historización en índice) |

## 1. Vista de módulos (reactor Maven)

```
+-------------------------------------------------------------------+
|              sap-integration-parent (pom reactor)                  |
+-------------------------------------------------------------------+
         |                         |                |           |
         ▼                         ▼                ▼           ▼
+-----------------+  +-----------------+  +---------------+  +------+
|     common      |  |    customer     |  |    article    |  |  it  |
|  (shared kernel |  |  (Spring Boot  |  | (Spring Boot  |  | (IT +|
|   jar lib)      |  |     app jar)    |  |    app jar)   |  |contract|
+-----------------+  +-----------------+  +---------------+  +------+
         ▲                  ▲                     ▲             |  ▲
         |                  |                     |             |  |
         +------------------+---------------------+-------------+  |
                            |                     |               |
                            +---------------------+---------------+
                                  (depends on)

+-----------------+
|    supplier     |   (placeholder, futuro, sin código)
+-----------------+
```

**Dependencias**:
- `common` ← `customer`, `article`, `supplier`, `it` (todos dependen del shared kernel).
- `sap-api-models` ← `customer` (modelos Java generados con openapi-generator desde la spec oficial `API_BUSINESS_PARTNER`; sin dependencias de otros módulos).
- `customer`, `article` ← `it` (tests de integración referencian las apps).
- `supplier` no depende de nadie más (placeholder).

**Despliegue**: cada dominio produce un jar Spring Boot independiente → desplegable por separado.
- `customer-app` :8081
- `article-app`  :8082
- `supplier-app` :8083 (futuro)

## 2. Vista de dominios (aggregate + features)

Un dominio = un *bounded context* = un artefacto desplegable. Cada uno declara
sus entidades y sus features:

| Dominio   | Entidades         | Features                                               | Estado |
|-----------|-------------------|--------------------------------------------------------|--------|
| customer  | Customer, Mandate | sync, validate, index, delete_customer, delete_mandate | activo |
| article   | Article           | sync, validate, index                                  | activo |
| supplier  | Supplier          | sync, validate, index                                  | placeholder (futuro) |

### Aggregate Customer

```
+=========================================================================+
|                          Customer (aggregate root)                       |
|                                                                          |
|  id, code, name, status                                                  |
|                                                                          |
|  +---------------+  +-------------+  +---------------+  +---------------+ |
|  | AddressData   |  | FiscalData  |  | ContactData   |  | BankingData   | |
|  | (street, city,|  | (taxId,     |  | (email, phone,|  | (iban, bic,   | |
|  |  postalCode,  |  |  vatNumber, |  |  fax, website)|  |  mandateIds) | |
|  |  country,     |  |  legalName, |  +---------------+  +---------------+ |
|  |  region)      |  |  taxResidency) |                                       |
|  +---------------+  +-------------+                                          |
|                                                                          |
|  CustomerValidations  → orquesta 4 validadores (por subconjunto)          |
+=========================================================================+
         |
         | CustomerFeature enum: ADDRESS | FISCAL | CONTACT | BANKING
         ▼
+-----------------------------+      +-----------------------------+
|   Use cases por feature     |      |   Orchestrador general      |
|-----------------------------|      |-----------------------------|
| SyncAddressUseCase          |      | SyncCustomerUseCase         |
| SyncFiscalUseCase           |      |   .execute(msg)             | ← todas
| SyncContactUseCase          |      |   .execute(msg, Set<feat>) | ← subconjunto
| SyncBankingUseCase          |      |                             |
| ValidateAddressUseCase      |      | ValidateCustomerUseCase    |
| ValidateFiscalUseCase       |      |                            |
| ValidateContactUseCase      |      | DeleteCustomerUseCase      |
| ValidateBankingUseCase      |      +-----------------------------+
| DeleteMandateUseCase        |
+-----------------------------+
         |
         | Cada use case habla con sus ports (interfaces en domain/port/)
         ▼
+---------------------------------------------------------------------+
|                          Puertos por feature                          |
|----------------------------------------------------------------------|
|  AddressSapPort  | FiscalSapPort  | ContactSapPort  | BankingSapPort  |
|  (SapOutboundPort<AddressData>) | (SapOutboundPort<...>)            |
+---------------------------------------------------------------------+
         |
         ▼
+--------------------------------------------------------------------+
|   Adaptadores SAP (en customer/adapters/sap/)                       |
|--------------------------------------------------------------------|
|  BtpAddressAdapter  | BtpFiscalAdapter  | BtpContactAdapter         |
|  (SapDestination.BTP)                                              |
|                                                                     |
|  BtpBankingAdapter  (SapDestination.BTP)                            |
|  SepaMandateODataAdapter (S4_NATIVE, API_APAR_SEPA_MANDATE_SRV)     |
|  BtpCustomerAdapter (operaciones generales: DELETE OData)           |
|                                                                     |
|  Todos delegan en SapClient (common) → RestClient + OAuth2 + Retry   |
|                              + CircuitBreaker (Resilience4j)         |
+--------------------------------------------------------------------+
```

## 3. Vista de flujos

### 3.1 Flujo CDC (CDC por outbox + Debezium)

```
+------------+    +-------------+   +-----------+   +-----------------+
| Legacy DB  |    | outbox tabla|   | Debezium  |   | Kafka           |
| SQL Server |──> | (triggers)  |──>| (CDC)     |──>| outbox.CUSTOMER |
| PostgreSQL |    +-------------+   +-----------+   +-----------------+
+------------+                                                     |
                                                                   ▼
                                                     +------------------------+
                                                     | CustomerKafkaListener  |
                                                     | (bootstrap/kafka)      |
                                                     +------------------------+
                                                                   |
                                                                   ▼
                                              +----------------------------+
                                              | SyncCustomerUseCase        |
                                              |  .execute(IngestionMessage)|
                                              |                            |
                                              | 1. FETCHING  → legacyRepo  |
                                              | 2. VALIDATING → validators |
                                              | 3. INDEXING  → imageStore +|
                                              |                history     |
                                              | 4. SENDING_SAP → 4 puertos |
                                              |    SAP de feature          |
                                              +----------------------------+
                                                                   |
                                 +---------------------------------+---+
                                 |                                 |   |
                                 ▼                                 ▼   ▼
                    +-----------+   +-----------+   +-----------+
                    | AddressSap| | FiscalSap | | ContactSap | BankingSap(→ S/4)
                    | Port (BTP)| Port (BTP) | | Port (BTP)|
                    +-----------+   +-----------+   +-----------+
```

### 3.2 Flujo REST (entrada alternativa)

```
+----------+   POST /customers/sync       +-------------------------+
|  Cliente | ────────────────────────────> | SyncCustomerController |
|  HTTP    |   {entityId, operation,       | (bootstrap/web)         |
|  curl    |    [payloadHash opcional]}    +-------------------------+
+----------+                                                  |
                                                              ▼
                                              +----------------------------+
                                              | IngestionMessage           |
                                              | (origin = REST)            |
                                              +----------------------------+
                                                              |
                                                              ▼
                                              +----------------------------+
                                              | SyncCustomerUseCase        |
                                              | (mismo pipeline que CDC)   |
                                              +----------------------------+
```

### 3.3 Flujo por feature (subconjunto)

```
POST /customers/sync  { features:[ADDRESS] }  (futuro)
                |
                ▼
+-------------------------------+
| SyncCustomerUseCase.execute(  |
|   msg, EnumSet.of(ADDRESS))   |
+-------------------------------+
                |
                | Solo invoca ADDRESS
                ▼
+----------------------------+
| SyncAddressUseCase.execute |
+----------------------------+
   |                    |
   ▼                    ▼
+--------+        +------------------+
| VALID  |        | AddressSapPort   |
| ADDRESS|        |  .send(... AddressData)|
+--------+        +------------------+
                          |
                          ▼
                +-------------------+
                | BtpAddressAdapter  |
                | (SapDestination.BTP)|
                +-------------------+
                          |
                          ▼
                +-------------------+
                | SapClient (common) |
                | RestClient + OAuth2|
                | + Retry/Circuit    |
                +-------------------+
                          |
                          ▼
                +-------------------+
                |  SAP BTP API       |
                |  /CustomerAddress  |
                +-------------------+
```

## 4. Puertos y adaptadores

| Puerto                      | Adaptador (customer)              | Adaptador (article)               |
|-----------------------------|-----------------------------------|-----------------------------------|
| `IngestionPort`             | `CustomerKafkaListener`, `SyncCustomerController` | `ArticleKafkaListener`, `SyncArticleController` |
| `LegacyRepositoryPort<T>`   | `SqlServerCustomerRepository` (JPA, SQL Server) | `PostgresArticleRepository` (JPA, Postgres) |
| `ImageStorePort<T>`         | `MongoCustomerImageStore`         | `MongoArticleImageStore`          |
| `HistoryIndexerPort<T>`     | `ElasticsearchCustomerIndexer`    | `ElasticsearchArticleIndexer`    |
| `SyncStateRepositoryPort`   | `MongoSyncStateRepository` (en `common/`) | idem |
| `SapOutboundPort<T>`        | `BtpCustomerAdapter`, `BtpAddressAdapter`, `BtpFiscalAdapter`, `BtpContactAdapter`, `BtpBankingAdapter`; mandatos: `SepaMandateODataAdapter` | `S4ArticleAdapter` |

> `SyncStateRepositoryPort` está compartido en `common/adapters/persistence/MongoSyncStateRepository.java` — todos los dominios lo reutilizan sin duplicar.

## 5. Máquina de estados de sincronización

Reside en `common/domain/SyncStateMachine.java` (dominio-agnóstico).

```
                    +----------+
            ┌──────>|  ERROR   |<>(recuperar)─> RECEIVED
            │       +----------+
            ▼            ▲
+---------+   +---------+   +-----------+   +-------+
|RECEIVED |──>|FETCHING |──>|VALIDATING |──>| VALID |
+---------+   +---------+   +-----------+   +-------+
                  ▲              |              |
                  │              ▼              │
                  │         +-----------+       │
                  │         |  INVALID  |       │
                  │         +-----------+       │
                  │              (terminal)    ▼
                  │                        +--------+   +----------+
                  │                        |INDEXING|──>|INDEXED   |
                  │                        +--------+   +----------+
                  │                                           |
                  ▼                                           ▼
          +----------------+                          +-------------+
          |COMMUNICATION_ERR|<>──────────────────────>|SENDING_SAP  |
          +----------------+                          +-------------+
                                                             |
                                                  +----------+-----+
                                                  ▼                ▼
                                            +---------+    +---------+
                                            | SENT_SAP|    |SAP_ERROR|<> recuperar ─> SENDING_SAP
                                            +---------+    +---------+
                                            (terminal)
```

Cada transición se persiste con `timestamp`, `origen`, `payloadHash`, `cycleId`
y `detail` (motivo, solo en transiciones a error), de modo que el estado de
cualquier registro es consultable (imagen actual + histórico). El `cycleId` lo
comparte la línea del agregado con las de sus features, lo que permite
reconstruir la traza de pasos de un envío completo en una sola consulta
(`GET /customers/{id}/state`, `CustomerStateUseCase.CycleTrace`/`Step`). El
estado se ofrece también **por parte** (dirección, fiscal, contacto, banco) con
su propia traza (`LineState`), no solo a nivel de agregado.

**Transiciones permitidas** (resumen):

| from            | to                         |
|-----------------|----------------------------|
| *(apertura de ciclo — `beginCycle`, desde cualquier estado)* | RECEIVED (agregado), VALIDATING (feature), SENDING_SAP (baja), INDEXING (indexación) |
| RECEIVED        | FETCHING, ERROR            |
| FETCHING        | VALIDATING, ERROR, COMMUNICATION_ERROR |
| VALIDATING      | VALID, INVALID, ERROR       |
| VALID           | INDEXING (pipeline agregado), SENDING_SAP (pipeline por feature: valida y envía, no indexa), SENT_SAP (dedupe), ERROR |
| INVALID         | RECEIVED (re-sincronización tras corregir datos) |
| INDEXING        | INDEXED, ERROR              |
| INDEXED         | SENDING_SAP, ERROR          |
| SENDING_SAP     | SENT_SAP, SAP_ERROR, INVALID, COMMUNICATION_ERROR, ERROR |
| SENT_SAP        | RECEIVED (nuevo evento de la misma entidad) |
| SAP_ERROR       | SENDING_SAP, ERROR          |
| COMMUNICATION_ERROR | FETCHING, SENDING_SAP, ERROR |
| ERROR           | RECEIVED (recover)          |

> **Fuente única**: [`../sdd/common/maquina-de-estados.md`](../sdd/common/maquina-de-estados.md)
> §6; esta tabla es un resumen y se corrige desde allí (auditoría A17).
>
> Abrir ciclo (`beginCycle`) y avanzar (`advance`) son operaciones distintas.
> Un evento nuevo abre ciclo **desde cualquier estado** — cerrado, de error o a
> medias — por el estado de entrada de su pipeline; la tabla solo gobierna el
> avance. La idempotencia la garantiza el dedupe por el `payloadHash` **que se
> calcula sobre el snapshot releído del legacy** (`SyncStateRepositoryPort.alreadySent`;
> [ADR-0013](adr/0013-outbox-mensaje-fino-sin-payload.md)), no el bloqueo de la máquina. Detalle
> en [`../sdd/common/maquina-de-estados.md`](../sdd/common/maquina-de-estados.md).

### 5.1 Concurrencia entre instancias (ADR-0011)

Varias instancias en dos clústeres pueden recibir eventos de la **misma**
entidad. Lo que impide que se pisen:

| Pieza | Qué garantiza |
|---|---|
| Clave de partición `entity_id` | todos los eventos de una entidad van a la **misma partición** y los consume el mismo hilo, en orden |
| Un solo `consumer group` por dominio, **compartido por los dos clústeres** | cada mensaje lo procesa **un** consumidor; con grupos por clúster, cada clúster escribiría el mismo cambio en el mismo S/4 |
| Fencing por `cycleId` en la cabecera de estado | un ciclo no avanza sobre la cabecera de otro: si la cabecera cambió de dueño, `ConcurrentTransitionException` |
| `ConcurrentTransitionException` **declarada reintentable** | la colisión se reintenta con backoff en vez de ir a la DLT al primer intento |
| `SapCircuitOpenException` con backoff propio (30 s) | el reintento cubre la ventana de circuito abierto, en vez de agotarse en 7 s |
| `409 Conflict` en `POST /customers/sync` | el REST síncrono, único camino que rompe el orden por entidad, devuelve un conflicto honesto en vez de un 500 |

**No garantizado**: el fencing protege el estado propio, no SAP — la llamada HTTP
pudo salir antes de detectarse la colisión. Y **no hay *lease*** por entidad:
descartado con criterio de reevaluación en
[ADR-0011](adr/0011-concurrencia-entre-instancias-fencing-sin-lease.md).

## 6. Vista de deployment

```
+---------------------------------------------------------------------+
|                       Plataforma SAP                                  |
|   +-----------------------------+   +-------------------------+       |
|   |   BTP APIs                  |   |   S/4 APIs nativas      |       |
|   |   (xsuaa OAuth2 +          |   |   (OData/REST propio)   |       |
|   |    Destination Service)    |   |                         |       |
|   +-----+-----------------------+   +-------+-----------------+       |
|         |                                  |                           |
+---------|----------------------------------|---------------------------+
          |                                  |
          |   HTTPS (OAuth2 + Idempotency-Key)|
          ▼                                  ▼
     +-------------------------------------------+
     |              SapClient (common)            |
     |  RestClient + Retry + Circuit Breaker      |
     +-----+---------------+-------------+--------+
           |               |             |
           ▼               ▼             ▼
  +-------------+   +-------------+   +-------------+
  | customer-app|   | article-app |   | supplier-app |
  | :8081       |   | :8082       |   | :8083 (fut.) |
  +------+------+   +------+------+   +-------------+
         |                 |
         |                 |
         ▼                 ▼
  +-------------+   +-------------+   +-------------+
  | Kafka       |   | SQL Server  |   | PostgreSQL  |
  | outbox.CUST |   | (customers) |   | (articles)  |
  | outbox.ART  |   +-------------+   +-------------+
  +-------------+
         |
         ▼
  +-------------+   +-------------+
  | MongoDB     |   | Elasticsearch|
  | (imagen +   |   | (histórico)  |
  |  estado)    |   +-------------+
  +-------------+

  Observabilidad:
  +-------------+   +-------------+
  | Prometheus  |   | OTLP collector|
  +-------------+   +-------------+
         |
         ▼
  +-------------+
  |  Grafana    |
  +-------------+
```

**Topología Kafka** (ADR-0011; los topics los crea la plataforma, no la app —
[`../../deploy/README.md`](../../deploy/README.md)):

| Topic | Particiones | Clave | Consumer group |
|---|---|---|---|
| `outbox.CUSTOMER` y `outbox.CUSTOMER-dlt` | 12 | `entity_id` | `customer-consumer`, **uno para los dos clústeres** |
| `outbox.ARTICLE` y `outbox.ARTICLE-dlt` | 12 | `entity_id` | `article-consumer`, ídem |

Regla de dimensionado: **particiones ≥ instancias × `concurrency`**
(12 = 2 clústeres × 2 réplicas × 3 hilos). El `-dlt` lleva siempre las mismas
particiones que su topic de entrada. **[Supuesto D-16, pendiente de plataforma]**:
un único Kafka multi-AZ visible desde los dos clústeres.

**Independencia de despliegue**:
- Modificar `customer` → redeploy `customer-app` (jar Spring Boot propio, contenedor propio).
- Modificar `article` → redeploy `article-app`.
- Modificar `common` (minor/major) → redeploy todos los dominios (por eso `common` requiere pruebas de integración estrictas y versionado semántico).

**Versionado del shared kernel**: `common` se versiona semánticamente y evoluciona
*backward-compatible* por defecto. Criterio de re-despliegue de los dominios:

| Cambio en `common` | Re-despliegue de dominios |
|--------------------|---------------------------|
| `patch`            | Opcional                  |
| `minor`            | Recomendado               |
| `major`            | Obligatorio               |

Los cambios del 18-09-2026 en `common` (upsert idempotente, fallo parcial con
traza, fencing por `cycleId`, `RestClientSapClient`/`RetryBudgetGuard`) son
**MINOR**: retrocompatibles, sin romper el contrato de los puertos existentes.
Re-despliegue **recomendado** de `customer` y `article` (ver CHANGELOG.md
2026-09-18 y [`../sdd/README.md`](../sdd/README.md) §6).

## 7. Stack tecnológico

Detalle completo (plataforma, build, persistencia, clientes SAP, observabilidad,
testing, empaquetado) en [`TECH.md`](TECH.md). Resumen: Java 25 LTS +
Spring Boot 4.1 + Maven 3.9 multi-módulo, Kafka (CDC Debezium) con DLT,
JPA (SQL Server/Postgres) + MongoDB + Elasticsearch, `RestClient` + OAuth2 hacia
SAP con Resilience4j, Micrometer/Prometheus + OTel javaagent.

## 8. Convenciones de paquetes

Regla de dependencias: `bootstrap → adapters → application → domain`.
`domain` no depende de nada. `application` solo de `domain`. `adapters` de `application` (ports) y de `common`.

```
com.poc.sap.<dominio>/
├── domain/                      puro, sin Spring
│   ├── <Entity>.java            record o clase (aggregate root)
│   ├── <Entity>Validations.java validaciones (ValidationResult)
│   ├── CustomerFeature.java     enum de features (solo customer)
│   ├── feature/
│   │   ├── address/
│   │   │   ├── AddressData.java     record (value object)
│   │   │   └── AddressValidator.java
│   │   ├── fiscal/ ...
│   │   ├── contact/ ...
│   │   └── banking/ ...
│   └── port/                    interfaces (puertos)
│       ├── IngestionPort (común, en common)
│       ├── SapOutboundPort (común)
│       ├── <Domain>LegacyRepositoryPort extends LegacyRepositoryPort<Entity>
│       ├── <Domain>ImageStorePort extends ImageStorePort<Entity>
│       ├── <Domain>HistoryIndexerPort extends HistoryIndexerPort<Entity>
│       └── <Feature>SapPort extends SapOutboundPort<FeatureVO>
│
├── application/                 use cases (sin infra, sin Spring)
│   ├── general/
│   │   ├── SyncCustomerUseCase       (orchestrador)
│   │   ├── ValidateCustomerUseCase
│   │   └── DeleteCustomerUseCase
│   ├── address/SyncAddressUseCase, ValidateAddressUseCase
│   ├── fiscal/ ...
│   ├── contact/ ...
│   └── banking/ ...
│
├── adapters/                    implementaciones de ports (Spring beans)
│   ├── persistence/             JPA, Mongo
│   ├── index/                    Elasticsearch
│   └── sap/                      BTP/S4 adapters, parser JSON
│
└── bootstrap/                   Spring wiring (lo único con Spring)
    ├── <Domain>Application.java  @SpringBootApplication (main)
    ├── web/                      @RestController
    └── kafka/                    @KafkaListener
```

## 9. Requisitos no funcionales

| Requisito | Cómo se cumple |
|---|---|
| **Idempotencia** | hash calculado sobre el snapshot releído del legacy (ADR-0013); los reintentos no duplican envíos a SAP (`SyncStateRepositoryPort.alreadySent`, cabecera `Idempotency-Key`) |
| **Resiliencia** | retry con backoff exponencial y circuit breaker hacia SAP (Resilience4j); DLT `<topic>-dlt` en la ingesta Kafka |
| **Trazabilidad** | cada registro pasa por la máquina de estados (§5) y se persiste cada transición |
| **Observabilidad** | métricas por dominio, estado, etapa y llamada SAP; parada ordenada y presupuesto de reintentos vigilado al arrancar; logs JSON (ECS) por configuración; trazas distribuidas **pendientes** (D-7) ([`TECH.md`](TECH.md) §9, spec [`../sdd/common/observabilidad.md`](../sdd/common/observabilidad.md)) |
| **Rendimiento** | procesamiento concurrente por dominio (virtual threads); throughput configurable por dominio |
| **Seguridad** | secretos fuera del código (variables de entorno), credenciales SAP rotativas, sin logs de secretos |

## 10. Referencias

- [`TECH.md`](TECH.md) — stack tecnológico detallado.
- [`../sdd/README.md`](../sdd/README.md) — specs por feature (SDD anchor) y estado del proyecto.
- [`DESARROLLO.md`](DESARROLLO.md) — ciclo de trabajo SDD + TDD.
- [`../sdd/sap-api-catalog.md`](../sdd/sap-api-catalog.md) — catálogo de las specs OpenAPI oficiales de SAP.
- [`FLOWS.md`](FLOWS.md) — flujos implementados con nombres de clase: CDC completo y mapa de rutas BTP vs OData directo.
- [`adr/`](adr/README.md) — decisiones de arquitectura y cuándo se reevalúan (ADR-0001: transporte `RestClient`).
- [`../tools-integrations/SAP_CLOUD_SDK.md`](../tools-integrations/SAP_CLOUD_SDK.md) — guía del SAP Cloud SDK (VDM), opción aparcada por ADR-0001.
- [`../testing/TESTING.md`](../testing/TESTING.md) — estrategia y catálogo de tests.
- `README.md` — intro, requisitos, comandos, debug VS Code.