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

- **Maven 3.9+**, reactor multi-módulo con parent `sap-integration-parent`.
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
│       ├── domain/               # StateMachine, SyncState, value objects base
│       ├── sap/                  # SapClient, auth BTP/S4, DTOs contrato
│       ├── observability/        # Micrometer + OTel config
│       └── test/                 # Testcontainers support, WireMock SAP
├── customer/                     # customer-app (spring-boot jar)
│   └── src/main/java/.../customer/
│       ├── domain/               # Customer, CustomerValidations, ports
│       ├── application/          # SyncCustomer, ValidateCustomer, IndexCustomer, DeleteCustomer/Mandate
│       ├── adapters/             # kafka, sqlserver_repo, mongo_repo, es_indexer, sap_sender
│       └── bootstrap/            # CustomerApplication, REST controllers, Kafka listener
├── article/                      # article-app
├── supplier/                     # supplier-app (futuro)
└── it/                           # pruebas integración cross-dominio + contrato SAP
```

## 4. Arquitectura por dominio (capas por paquete)

- `domain`: **puro, sin Spring**. Records/sealed para entidades y value objects.
  Validaciones y **ports = interfaces Java**. Sin dependencias de framework.
- `application`: use cases / features. Orquesta ports. Sin infraestructura.
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
| `SapOutboundPort<P>` | BTP: `BtpAddressAdapter`, `BtpFiscalAdapter`, `BtpContactAdapter`, `BtpCustomerAdapter`, `S4BankingAdapter` · OData S/4: `BusinessPartnerODataAdapter`, `BusinessPartnerAddressODataAdapter`, `BusinessPartnerTaxODataAdapter`, `BusinessPartnerContactODataAdapter`, `BusinessPartnerBankODataAdapter` · article: `S4ArticleAdapter` |
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
- Contrato de cliente centralizado en `common/sap` para reutilizar auth,
  reintentos y circuit breaker (Resilience4j).
- Semántica de errores del cliente (`WebClientSapClient`): 5xx y errores de
  transporte disparan retry con backoff exponencial y cuentan para el circuit
  breaker; 4xx no se reintenta; timeouts de conexión/respuesta configurables
  vía `sap.client.*` en `application-common.yml`.
- OAuth2 client-credentials real con caché por expiración
  (`OAuth2TokenClient`; xsuaa para BTP, token endpoint o basic para S/4) con
  fallback a token stub cuando no hay credenciales configuradas (dev local).
- CSRF OData V2: fetch de `x-csrf-token` + cookies de sesión en escrituras
  S/4, con refresh y reintento único en 403 (`sap.s4.csrf.enabled`).
- Modelos de payload generados desde la spec oficial `API_BUSINESS_PARTNER`
  en el módulo `sap-api-models` (openapi-generator del SAP Cloud SDK);
  serialización con `SapJsonMapper` (NON_NULL, sin wrapper `d` en peticiones).

## 9. Observabilidad

- Micrometer + Prometheus registry expuesto por Actuator (`/actuator/prometheus`).
- Trazas distribuidas: **OpenTelemetry javaagent** en el arranque de la JVM
  (`-javaagent:opentelemetry-javaagent.jar` + `OTEL_EXPORTER_OTLP_ENDPOINT`).
  El starter Spring de OTel (2.x) solo soporta Boot 3 y rompe el arranque con
  Boot 4, por eso no se usa como dependencia.
- Logs estructurados (JSON) + correlación por `traceId` — **visión, no
  implementado**: hoy formato de consola por defecto (auditoría A9; plan Fase 5).
- Métricas por dominio y por estado de la máquina de estados.

## 10. Testing

- **Unit**: JUnit 5 + Mockito sobre `domain` y `application` (sin Spring).
- **Slice**: REST con `MockMvcBuilders.standaloneSetup(...)` (Spring Boot 4
  eliminó `@WebMvcTest`); listeners Kafka probados directamente con el use case
  stubbeado.
- **Integración**: Testcontainers (Kafka, PostgreSQL, SQL Server, MongoDB,
  Elasticsearch) + WireMock para SAP. Por dominio y en módulo `it/`.
- **Contrato SAP**: WireMock en `it/` (`*ContractTest`, ejecutados por
  failsafe). Desde la Fase 2 construyen el `WebClientSapClient` **real** contra
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
- Propiedades por dominio vía `application.yml` + profiles.
- Sin lógica de negocio en `bootstrap` ni `adapters`.
- Virtual threads activados: `spring.threads.virtual.enabled=true`.
