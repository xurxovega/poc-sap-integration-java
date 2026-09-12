# SAP Integration — Stack tecnológico

> Stack e implementación concreta de la arquitectura descrita en
> [`OVERVIEW.md`](OVERVIEW.md). Java 25 LTS + Spring Boot 4.1 + Maven.

## 1. Plataforma

- **Lenguaje**: Java 25 LTS, **mínimo real** (`<maven.compiler.release>25`
  en el parent, sin perfil; records, sealed, pattern matching, virtual threads).
- **Framework**: Spring Boot 4.1.1.
- **Runtime**: JVM con **virtual threads** activados para concurrencia de
  Kafka/REST. Con JDK 25 desaparece el *pinning* sobre `synchronized` (JEP 491),
  una de las observaciones de la auditoría.

## 2. Build

- **Maven 3.9.9 vía wrapper** (`./mvnw`, `.mvn/wrapper/`), reactor multi-módulo
  con parent `sap-integration-parent`. `maven-enforcer` exige Maven ≥ 3.9 y
  JDK ≥ 25 y prohíbe dependencias duplicadas en un pom.
- **Cadena de suministro** (plan Fase 8): SBOM CycloneDX agregado
  (`target/bom.json`, artefacto de la CI), Dependabot semanal (Maven y GitHub
  Actions), imágenes de `external-services` fijadas por **digest**,
  `spring-boot:build-image` configurado (`poc-sap/<app>:<version>`). Sin
  Dockerfile ni Helm: la plataforma de despliegue está por decidir (D-9).
  `dependency:analyze` **no** está en `verify`: `maven-dependency-plugin` 3.8.1
  aún no lee class files de Java 25 («Unsupported class file major version 69»);
  se reintenta cuando el plugin actualice su ASM (Dependabot avisará).
- `spring-boot-dependencies` BOM importado en `dependencyManagement`.
- **Sin perfiles Maven**. No existen perfiles `dev`/`it`/`native` ni `jdk25`;
  la configuración por entorno va por variables de entorno (`scripts/env/`).
- Plugins fijados en `pluginManagement`: surefire y failsafe 3.5.3 (auditoría
  B7: sin versión fijada, `SyncCustomerControllerIT` no se ejecutaba nunca).
- **JaCoCo** en el parent: `prepare-agent`, `report` y `check` en `verify`. El
  `check` exige **≥ 75 % de líneas en `**/domain/**`** (suelo medido el
  12-09-2026: common 90 %, customer 78 %, article 88 %).
- **ArchUnit** 1.5.0: `DomainPurityTest` en `common`, `customer` y `article`
  prohíbe que `..domain..` dependa de Spring, Jackson, Mongo, Kafka, Micrometer
  o JPA.
- **CI**: [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) con dos
  jobs: `build` (`mvn verify` sin Docker, sube el informe JaCoCo) y
  `e2e-docker` (`-pl it verify -Ddocker.available=true`, Testcontainers).
- Versionado semántico de `common` como librería consumida por cada app.

## 3. Estructura de módulos

```
sap-integration-java/
├── pom.xml                       # parent reactor
├── common/                       # shared kernel (jar)
│   └── src/main/java/.../common/
│       ├── domain/               # StateMachine, SyncState, puertos base (MetricsPort...)
│       ├── application/          # SyncCycleRecorder, FeatureSyncPipeline<D> (recorrido comun por feature)
│       ├── kafka/                # KafkaErrorHandlingConfig (reintentos + DLT, compartido)
│       ├── sap/                  # SapClient, auth BTP/S4, DTOs contrato
│       ├── observability/        # Micrometer + OTel config
│       └── test/                 # Testcontainers support, WireMock SAP
├── customer/                     # customer-app (spring-boot jar)
│   └── src/main/java/.../customer/
│       ├── domain/               # Customer, CustomerValidations, ports
│       ├── application/          # SyncCustomer, ValidateCustomer, DeleteCustomer/Mandate, Sync/Validate<Feature>
│       ├── adapters/             # kafka, sqlserver_repo, mongo_repo, es_indexer, sap_sender
│       └── bootstrap/            # CustomerApplication, REST controllers, Kafka listener
├── article/                      # article-app
├── supplier/                     # supplier-app (futuro)
└── it/                           # pruebas integración cross-dominio + contrato SAP
```

## 4. Arquitectura por dominio (capas por paquete)

- `domain`: **puro, sin Spring**. Records/sealed para entidades y value objects.
  Validaciones y **ports = interfaces Java**. Sin dependencias de framework.
- `common/application`: lo que se repetía en cada use case. `SyncCycleRecorder`
  abre y avanza el ciclo registrando métrica (antes 14 copias de
  `beginCycle()`/`transition()`); `FeatureSyncPipeline<D>` es el recorrido por
  feature `VALIDATING → VALID|INVALID → SENDING_SAP → SENT_SAP|SAP_ERROR` con
  validador y puerto SAP inyectados (antes cuatro copias en `customer`).
- `application`: use cases / features. Orquesta ports. **Sin Spring ni Micrometer**
  (ArchUnit `ApplicationPurityTest`): las métricas van por `MetricsPort` y el
  wiring vive en `bootstrap/*UseCaseConfig` (plan Fase 7, auditoría A4).
- `adapters`: implementaciones de ports (Kafka, SQL, Mongo, ES, SAP). Spring beans.
- `bootstrap`: `@SpringBootApplication`, wiring, controladores REST, listeners
  Kafka, configuración CDC/Debezium, properties.

Regla de dependencias: `bootstrap → adapters → application → domain`. `domain`
no depende de nada. `application` solo de `domain`. `adapters` de `application`
(ports) y de `common`.

## 5. Puertos y adaptadores

Clases **reales** del repositorio (la auditoría del 2026-09-10 encontró aquí
nueve nombres que no existían):

| Puerto | Adaptadores |
|---|---|
| `IngestionPort` | **sin implementaciones**: listeners y controllers llaman al use case directamente (código muerto, A18) |
| `LegacyRepositoryPort<T>` | `SqlServerCustomerRepository`, `PostgresArticleRepository` |
| `ImageStorePort<T>` | `MongoCustomerImageStore`, `MongoArticleImageStore` |
| `HistoryIndexerPort<T>` | `ElasticsearchCustomerIndexer`, `ElasticsearchArticleIndexer` |
| `SyncStateRepositoryPort` | `MongoSyncStateRepository` (en `common`, único) |
| `SapOutboundPort<P>` | BTP: `BtpAddressAdapter`, `BtpFiscalAdapter`, `BtpContactAdapter`, `BtpCustomerAdapter`, `BtpBankingAdapter` · OData S/4: `BusinessPartnerODataAdapter`, `BusinessPartnerAddressODataAdapter`, `BusinessPartnerTaxODataAdapter`, `BusinessPartnerContactODataAdapter`, `BusinessPartnerBankODataAdapter`, `SepaMandateODataAdapter` (mandato SEPA, `API_APAR_SEPA_MANDATE_SRV`, sin alternativa BTP) · article: `S4ArticleAdapter` |
| `BusinessPartnerReadPort` | `BusinessPartnerReadAdapter` (GET/search, `sap.odata.read.enabled=true`) |

## 6. Entradas

Tres fuentes equivalentes alimentan el **mismo caso de uso** del dominio,
intercambiables a través del puerto `IngestionPort`. Contrato común del mensaje
(`common/domain/IngestionMessage.java`): identificador de entidad · tipo de
operación (`create`/`update`/`delete`) · payload · origen (`cdc`/`kafka`/`rest`)
· hash de idempotencia sobre payload + identificador.

- **CDC**: consumer Kafka (`spring-boot-starter-kafka`; en Boot 4 el
  `spring-kafka` suelto no autoconfigura) sobre topics `outbox.<DOMINIO>`
  (Debezium). Errores: `DefaultErrorHandler` con backoff exponencial y
  dead-letter topic `<topic>-dlt`; `DELETE` enruta al use case de borrado.
- **Eventos directos**: Spring Kafka sobre `events.<DOMINIO>` (futuro).
- **REST**: Spring Web `POST /{domain}/sync` y `/validate`, `GET /{domain}/{id}/history[/diff]`.
  *Sin OpenAPI ni swagger-ui publicados: visión, no implementado.*
- Opcional: Confluent Schema Registry (Avro/Protobuf) cuando maduren contratos.

## 7. Persistencia

- Spring Data JPA → SQL Server / PostgreSQL (legacy fetch).
- Spring Data MongoDB → imagen actual + estado de sincronización.
- Spring Data Elasticsearch → histórico y búsqueda.

## 8. Clientes SAP

Salida mediante el puerto `SapOutboundPort`, con dos familias de destino: **APIs
BTP** (vía Destination Service / xsuaa) y **APIs nativas S/4 Public Cloud**
(OData/REST con autenticación propia). Cada dominio declara qué entidad va a qué
destino y con qué mapeo; los mapeos son parte del dominio, no del shared kernel.

- Adaptadores **BTP** (`Btp*Adapter`) y **OData S/4** (`BusinessPartner*ODataAdapter`),
  excluyentes por `sap.odata.<feature>.enabled`; todos delegan en `SapClient`.
- Contrato de cliente centralizado en `common/sap` (`SapClient`) para reutilizar
  auth, reintentos y circuit breaker (Resilience4j). Spec:
  [`../sdd/common/resiliencia-cliente-sap.md`](../sdd/common/resiliencia-cliente-sap.md).
- **Transporte**: `RestClientSapClient`, `RestClient` de Spring sobre el
  `HttpClient` del JDK (síncrono; PATCH y DELETE nativos). Sustituyó al
  `WebClient` reactivo con `.block()` y retiró `webflux`, Reactor y `sdk-core`
  del runtime: [ADR-0001](adr/0001-transporte-http-sap-restclient.md), con su disparador de reevaluación (VDM del
  Cloud SDK cuando soporte Boot 4).
- Semántica de errores: 5xx y errores de transporte disparan retry con backoff
  exponencial y cuentan para el circuit breaker; 4xx no se reintenta; el
  circuit breaker envuelve al retry y con el circuito abierto se lanza
  `SapCircuitOpenException`; timeouts de conexión/respuesta configurables vía
  `sap.client.*` en `application-common.yml`.
- OAuth2 client-credentials real con caché por expiración
  (`OAuth2TokenClient`; xsuaa para BTP, token endpoint o basic para S/4). **Sin
  credenciales la app no arranca**; el token stub solo existe con
  `sap.auth.allow-stub=true` (SAP simulado) y se avisa en el log. Ningún secreto
  (SAP ni BD legacy) tiene default en los YAML empaquetados: los aporta el
  entorno (`scripts/env/*.env`). Spec
  [`../sdd/common/autenticacion-sap.md`](../sdd/common/autenticacion-sap.md).
- CSRF OData V2: fetch de `x-csrf-token` + cookies de sesión en escrituras
  S/4 (`sap.s4.csrf.enabled`). El fetch se autentica con la misma cabecera
  `Authorization` que la escritura; solo un 403 con `x-csrf-token: Required`
  dispara el refresh y el reintento único.
- `Idempotency-Key = payloadHash` viaja en toda escritura pero **no es garantía**
  en OData V2 de S/4: la idempotencia real es el dedupe por hash y, en la
  Fase 3, el upsert con lookup previo.
- Modelos de payload generados desde la spec oficial `API_BUSINESS_PARTNER`
  en el módulo `sap-api-models` (openapi-generator del SAP Cloud SDK);
  serialización con `SapJsonMapper` (NON_NULL, sin wrapper `d` en peticiones).

## 8b. Seguridad de las APIs

Spec: [`../sdd/common/seguridad-api.md`](../sdd/common/seguridad-api.md); decisión: [ADR-0007](adr/0007-keycloak-como-proveedor-de-identidad-de-las-apis.md).

- Spring Security como **resource server OAuth2**: JWT de Keycloak
  (`KEYCLOAK_ISSUER_URI`); roles de realm y de cliente → `ROLE_SAP_*` con
  jerarquía superadmin ⊃ admin ⊃ write ⊃ read; `sap-external-read` aparte.
- El acceso se declara **en cada endpoint** con `@PreAuthorize`;
  `EndpointsDeclareAccessTest` (ArchUnit) rompe el build si falta.
- PII enmascarada (`PiiMasker`) para `external-read`; `diff` solo con `read`.
- `health`/`info`/`prometheus` sin token; resto de actuator, `admin`;
  `show-details: when-authorized`. `APP_SECURITY_ENABLED=false` solo en local.

## 9. Observabilidad

Spec: [`../sdd/common/observabilidad.md`](../sdd/common/observabilidad.md).

- Micrometer + Prometheus registry expuesto por Actuator (`/actuator/prometheus`),
  con el tag `application` = nombre de cada app (antes idéntico en las dos).
- **Métricas propias**: `sap_sync_state_total{domain,state}` por transición;
  `sap_sync_stage_duration{domain,stage}` (`fetch`/`validate`/`index`/`send`,
  p50/p95/p99) desde ambos orquestadores; `sap_client_request_duration
  {destination,method,outcome}` por cada intento HTTP hacia SAP; y las de
  Resilience4j del retry y el circuit breaker `sap` (`resilience4j_retry_calls`,
  `resilience4j_circuitbreaker_state`...).
- **Operación**: `server.shutdown=graceful` (30 s por fase);
  `max.poll.interval.ms` a 15 min y `RetryBudgetGuard`, que al arrancar
  comprueba que el peor caso de reintentos por mensaje (`calls-per-message ×
  (intentos × timeout + backoff)`) cabe en ese intervalo y si no, la app no
  arranca (auditoría A10).
- **Logs estructurados**: formato ECS (JSON) nativo de Boot activable con
  `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`; en local consola legible. La
  correlación por `traceId` llega con las trazas.
- **Trazas distribuidas** ([ADR-0009](adr/0009-trazas-con-el-starter-oficial-de-opentelemetry.md)):
  `spring-boot-starter-opentelemetry` (oficial de Boot 4) en `common`, apagado
  por defecto; `TRACING_ENABLED=true` + `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`
  cuando exista Tempo. Prometheus, Grafana y Loki los aporta la plataforma.
- **Despliegue**: Kubernetes, dos clústeres, Kustomize en `deploy/k8s`
  ([ADR-0008](adr/0008-kubernetes-como-plataforma-de-despliegue.md), [`../../deploy/README.md`](../../deploy/README.md)).

## 10. Testing

- **Unit**: JUnit 5 + Mockito sobre `domain` y `application` (sin Spring).
- **Slice**: REST con `MockMvcBuilders.standaloneSetup(...)` (Spring Boot 4
  eliminó `@WebMvcTest`); listeners Kafka probados directamente con el use case
  stubbeado.
- **Integración**: Testcontainers (Kafka, PostgreSQL, SQL Server, MongoDB,
  Elasticsearch) + WireMock para SAP. Por dominio y en módulo `it/`.
- **Contrato SAP**: WireMock en `it/` (`*ContractTest`, ejecutados por
  failsafe). Desde la Fase 2 construyen el `RestClientSapClient` **real** contra
  WireMock y ejercitan cada adaptador de producción (path, método, cabeceras,
  cuerpo). Spring Cloud Contract/Pact: visión, no usados.
- Cobertura: JaCoCo con `check` en el parent: **≥ 75 % de líneas en
  `**/domain/**`** (propiedad `jacoco.domain.line-minimum`, solo sube); por debajo, `mvn verify` falla.
- **TDD obligatorio**: el test se escribe antes que el código de producción. El
  ciclo, el orden de las capas y la definición de hecho están en
  [`DESARROLLO.md`](DESARROLLO.md).

## 11. Empaquetado y despliegue

- Cada app = imagen de contenedor propia (Cloud Native Buildpacks o `jib`).
- Despliegue independiente por dominio (k8s / Compose, equivalente al Python).
- `common` se publica como artefacto versionado (Maven repo interno o instalar
  local en el reactor).

## 12. Convenciones

- Value objects como `record`; agregados como clases con estado encapsulado.
- Ports como interfaces en `domain`; implementaciones en `adapters`.
- Propiedades por dominio vía `application.yml` (sin perfiles Spring: variables de entorno, `scripts/env/*.env`).
- Sin lógica de negocio en `bootstrap` ni `adapters`.
- Virtual threads activados: `spring.threads.virtual.enabled=true`.
