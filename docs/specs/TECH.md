# SAP Integration — Stack tecnológico

> Implementación de [`SPEC.md`](./SPEC.md). Java 25 LTS + Spring Boot 4.0 + Maven.

## 1. Plataforma

- **Lenguaje**: Java 25 LTS objetivo; compila también con Java 23 LTS mínimo
  (records, sealed, pattern matching, virtual threads).
- **Framework**: Spring Boot 4.0 (baseline Java 17+, soporta Java 25).
- **Runtime**: JVM con **virtual threads** activados para concurrencia de
  Kafka/REST.
- **Profile Maven `jdk25`**: se activa automáticamente cuando `JAVA_HOME`
  apunta a JDK 25+ y sube `<maven.compiler.release>` a 25. Por defecto el
  reactor compila con `release 23`.

## 2. Build

- **Maven 3.9+**, reactor multi-módulo con parent `sap-integration-parent`.
- `spring-boot-dependencies` BOM importado en `dependencyManagement`.
- Perfiles: `dev`, `it` (Testcontainers), `native` (opcional, futuro), `jdk25`
  (auto-activado con JDK 25+).
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

| Puerto                  | Adaptadores                                              |
|-------------------------|----------------------------------------------------------|
| `IngestionPort`         | `DebeziumKafkaAdapter`, `DirectKafkaAdapter`, `RestAdapter` |
| `LegacyRepositoryPort`  | `SqlServerCustomerRepository`, `PostgresArticleRepository` |
| `ImageStorePort`        | `MongoImageStore`                                        |
| `HistoryIndexerPort`    | `ElasticsearchIndexer`                                   |
| `SyncStateRepositoryPort` | `MongoStateRepository` / `JpaStateRepository`          |
| `SapOutboundPort`       | `BtpApiAdapter`, `S4NativeApiAdapter`                    |

## 6. Entradas

- **CDC**: Spring Kafka consumer sobre topics `outbox.<DOMINIO>` (Debezium).
- **Eventos directos**: Spring Kafka sobre `events.<DOMINIO>` (futuro).
- **REST**: Spring Web `POST /{domain}/sync`, OpenAPI en `/swagger-ui.html`.
- Opcional: Confluent Schema Registry (Avro/Protobuf) cuando maduren contratos.

## 7. Persistencia

- Spring Data JPA → SQL Server / PostgreSQL (legacy fetch).
- Spring Data MongoDB → imagen actual + estado de sincronización.
- Spring Data Elasticsearch → histórico y búsqueda.

## 8. Clientes SAP

- `BtpApiAdapter`: WebClient + OAuth2 cliente xsuaa + Destination Service.
- `S4NativeApiAdapter`: cliente OData/REST con autenticación propia (basic/OAuth2).
- Contrato de cliente centralizado en `common/sap` para reutilizar auth,
  reintentos y circuit breaker (Resilience4j).

## 9. Observabilidad

- Micrometer + Prometheus registry expuesto por Actuator (`/actuator/prometheus`).
- OpenTelemetry SDK con exporter OTLP (trazas distribuidas).
- Logs estructurados (JSON) + correlación por `traceId`.
- Métricas por dominio y por estado de la máquina de estados.

## 10. Testing

- **Unit**: JUnit 5 + Mockito sobre `domain` y `application` (sin Spring).
- **Slice**: `@WebMvcTest` (REST), `@KafkaListenerTest` / Spring Kafka test utils.
- **Integración**: Testcontainers (Kafka, PostgreSQL, SQL Server, MongoDB,
  Elasticsearch) + WireMock para SAP. Por dominio y en módulo `it/`.
- **Contrato SAP**: Spring Cloud Contract o Pact en `it/` para fijar contratos
  BTP/S4 y detectar breaking changes.
- Cobertura: JaCoCo; umbral mínimo en `domain` y `common`.

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
