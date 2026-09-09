# Arquitectura — Visión general

> Mapa y esquemas del aplicativo `poc-sap-integration-java` (Java 25 + Spring Boot 4.0 + Maven).

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
| ValidateFiscalUseCase       |      | IndexCustomerUseCase       |
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
|  S4BankingAdapter   (SapDestination.S4_NATIVE, incluye mandates)    |
|  BtpCustomerAdapter (operaciones generales: DELETE OData)           |
|                                                                     |
|  Todos delegan en SapClient (common) → WebClient + OAuth2 + Retry    |
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
|  curl    |    payloadHash, payload}      +-------------------------+
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
                | WebClient + OAuth2 |
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
| `SapOutboundPort<T>`        | `BtpCustomerAdapter`, `BtpAddressAdapter`, `BtpFiscalAdapter`, `BtpContactAdapter`, `S4BankingAdapter` | `S4ArticleAdapter` |

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

Cada transición se persiste con `timestamp`, `origen` y `payloadHash`, de modo
que el estado de cualquier registro es consultable (imagen actual + histórico).

**Transiciones permitidas** (resumen):

| from            | to                         |
|-----------------|----------------------------|
| *(sin historial)* | RECEIVED (pipeline aggregate), VALIDATING (pipeline por feature) |
| RECEIVED        | FETCHING, ERROR            |
| FETCHING        | VALIDATING, ERROR, COMMUNICATION_ERROR |
| VALIDATING      | VALID, INVALID, ERROR       |
| VALID           | INDEXING (pipeline agregado), SENDING_SAP (pipeline por feature: valida y envía, no indexa), SENT_SAP (dedupe), ERROR |
| INVALID         | RECEIVED (re-sincronización tras corregir datos) |
| INDEXING        | INDEXED, ERROR              |
| INDEXED         | SENDING_SAP, ERROR          |
| SENDING_SAP     | SENT_SAP, SAP_ERROR, INVALID, COMMUNICATION_ERROR |
| SENT_SAP        | RECEIVED (nuevo evento de la misma entidad) |
| SAP_ERROR       | SENDING_SAP, ERROR          |
| COMMUNICATION_ERROR | FETCHING, SENDING_SAP, ERROR |
| ERROR           | RECEIVED (recover)          |

> `SENT_SAP` e `INVALID` son terminales *del ciclo* (así los reporta
> `isTerminal`), pero admiten re-entrada a `RECEIVED`: un nuevo evento CDC de
> la misma entidad reabre el ciclo. Antes eran terminales absolutos y cada
> entidad solo podía sincronizarse una vez. La idempotencia la garantiza el
> dedupe por `payloadHash` (`SyncStateRepositoryPort.alreadySent`).

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
     |  WebClient + Retry + Circuit Breaker       |
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

## 7. Stack tecnológico

Detalle completo (plataforma, build, persistencia, clientes SAP, observabilidad,
testing, empaquetado) en [`TECH.md`](TECH.md). Resumen: Java 25 LTS +
Spring Boot 4.0 + Maven 3.9 multi-módulo, Kafka (CDC Debezium) con DLT,
JPA (SQL Server/Postgres) + MongoDB + Elasticsearch, WebClient + OAuth2 hacia
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
│   │   ├── IndexCustomerUseCase
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
| **Idempotencia** | hash de payload + identificador de entidad; los reintentos no duplican envíos a SAP (`SyncStateRepositoryPort.alreadySent`, cabecera `Idempotency-Key`) |
| **Resiliencia** | retry con backoff exponencial y circuit breaker hacia SAP (Resilience4j); DLT `<topic>.DLT` en la ingesta Kafka |
| **Trazabilidad** | cada registro pasa por la máquina de estados (§5) y se persiste cada transición |
| **Observabilidad** | métricas por dominio y estado, logs estructurados y trazas distribuidas ([`TECH.md`](TECH.md) §9) |
| **Rendimiento** | procesamiento concurrente por dominio (virtual threads); throughput configurable por dominio |
| **Seguridad** | secretos fuera del código (variables de entorno), credenciales SAP rotativas, sin logs de secretos |

## 10. Referencias

- [`TECH.md`](TECH.md) — stack tecnológico detallado.
- [`../sdd/README.md`](../sdd/README.md) — specs por feature (SDD anchor) y estado del proyecto.
- [`DESARROLLO.md`](DESARROLLO.md) — ciclo de trabajo SDD + TDD.
- [`../sdd/sap-api-catalog.md`](../sdd/sap-api-catalog.md) — catálogo de las specs OpenAPI oficiales de SAP.
- [`FLOWS.md`](FLOWS.md) — flujos implementados con nombres de clase: CDC completo y mapa de rutas BTP vs OData directo.
- [`../tools-integrations/SAP_CLOUD_SDK.md`](../tools-integrations/SAP_CLOUD_SDK.md) — guía de integración con SAP Cloud SDK (fases de implementación).
- [`../testing/TESTING.md`](../testing/TESTING.md) — estrategia y catálogo de tests.
- `README.md` — intro, requisitos, comandos, debug VS Code.