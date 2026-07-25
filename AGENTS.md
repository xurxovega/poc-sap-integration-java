# Agent Guidelines — SAP Integration (Java)

Migración del POC Python (`poc-sap-integration`) a Java 25 + Spring Boot 4.0 + Maven.

## Stack

- **Lenguaje**: Java 23 LTS mínimo (objetivo Java 25 LTS). Records, sealed, virtual threads.
- **Framework**: Spring Boot 4.0.
- **Build**: Maven 3.9+ multi-módulo reactor. Profile `jdk25` auto-activado con JDK 25.
- **SAP Cloud SDK**: v5.32.0 (`sdk-modules-bom` en parent, `sdk-core` en common).
- **Observabilidad**: Micrometer + Prometheus + OpenTelemetry.
- **Testing**: JUnit 5, Mockito, Testcontainers, WireMock, Spring Cloud Contract.

## Arquitectura

- **Hexagonal / puertos y adaptadores** por dominio.
- **Capas por paquete**: `domain` (puro, sin Spring) → `application` (use cases)
  → `adapters` (infra) → `bootstrap` (Spring wiring).
- **Dominios**: `customer`, `article`, `supplier`. Cada uno = app Spring Boot
  desplegable de forma independiente.
- **Shared kernel** `common/`: StateMachine, clientes SAP (BTP + S/4),
  observabilidad, soporte test. Versionado semántico.
- Regla dependencias: `bootstrap → adapters → application → domain`.
  `domain` no depende de nada. `application` solo de `domain`.

## Puertos clave

- `IngestionPort`: CDC (Debezium Kafka) / eventos Kafka directos / REST.
- `SapOutboundPort<P>`: APIs BTP (xsuaa + Destination Service) / APIs nativas S/4.
  Por feature: `AddressSapPort`, `FiscalSapPort`, `ContactSapPort`, `BankingSapPort`, `CustomerSapOutboundPort`.
- `BusinessPartnerReadPort`: lectura de Business Partners desde SAP S/4HANA OData (GET/search).
- `LegacyRepositoryPort`, `ImageStorePort` (Mongo), `HistoryIndexerPort` (ES),
  `SyncStateRepositoryPort`.

## Comandos críticos

- `mvn validate` — validar reactor.
- `mvn -pl common install -DskipTests` — publicar shared kernel local.
- `mvn -pl customer package` — empaquetar solo customer.
- `mvn verify` — unit + slice + integración.
- `mvn -pl it verify` — pruebas cross-dominio + contrato SAP.

## Integraciones SAP

### Cliente HTTP low-level

`SapClient` en `common/sap/` abstrae transporte, auth, retry y circuit breaker.
Soporta GET, POST (send), PATCH, DELETE. Implementado por `WebClientSapClient`
(WebClient reactivo + Resilience4j).

### Serialización JSON (DTOs)

Los adaptadores serializan via `SapJsonMapper.write(dto)` en `common/sap/json/`.
DTOs tipados con `@JsonProperty` en `<dominio>/adapters/sap/dto/`.
Nunca usar `String.format` para JSON.

### Vía BTP (adaptadores por defecto)

`customer/adapters/sap/Btp*Adapter.java`. Delegan en `SapClient.send()` con
destino `SapDestination.BTP`. Activos siempre (sin `@ConditionalOnProperty`).

### Vía OData S/4HANA (adaptadores condicionales)

`customer/adapters/sap/odata/BusinessPartner*ODataAdapter.java`. Mismos puertos,
pero envuelven el payload en `ODataPayload` (wrapper `{"d": {...}}` OData v2).
Se activan individualmente con `sap.odata.<feature>.enabled=true` en
`application-common.yml`. Coexisten con los BTP. Usan modelos generados desde
`sap-integration-api` (paquete `com.poc.sap.integration.api.customer.model`).

### Modelos SAP generados (`sap-api-models`)

Módulo reactor `sap-api-models` que contiene specs OpenAPI oficiales de SAP
en `specs/<dominio>/` y genera clases Java tipadas via
`openapi-generator-maven-plugin` en `target/generated-sources/` (no commiteado).

- `specs/customer/API_BUSINESS_PARTNER.yaml` — Business Partner (A2X, SAP_COM_0008)
- Modelos generados: `APIBUSINESSPARTNERABusinessPartnerTypeCreate`, `ABusinessPartnerType`, etc.
- Futuro: `specs/article/API_PRODUCT.yaml`, `specs/supplier/API_SUPPLIER.yaml`.
- Es un **Published Language** (DDD): lenguaje definido por SAP, consumido por todos los bounded contexts. No es Shared Kernel (que es `common`).

### Lectura OData (GET/search)

`BusinessPartnerReadPort` en `customer/domain/port/` implementado por
`BusinessPartnerReadAdapter`. Usa `SapClient.get()` con query params OData
(`$top`, `$filter`). Activado con `sap.odata.read.enabled=true`.

### Soporte CSRF (S/4 on-premise/private cloud)

`CsrfTokenProvider` en `common/sap/odata/` con implementación
`S4CsrfTokenProvider` (java.net.http.HttpClient, flujo `x-csrf-token: Fetch`).
No se cablea automáticamente en `WebClientSapClient` — evolución futura.

### Modos de integración (Push / Pull)

La integración con SAP no es solo push desde nuestra app. Conviven dos modos,
activables por `sap.integration.mode=push|pull|both` en `application-common.yml`:

| Modo | Quién inicia | Canal | Quién paga upserts |
|---|---|---|---|
| **Push** | Nuestra app (CDC o REST) | `SapClient.send/patch()` → BTP o API directa SAP | Nosotros |
| **Pull** | SAP BTP (polling) | `GET /btp/pending` + `POST /btp/result` | SAP |

- **Push**: CDC Kafka → `SyncCustomerUseCase` → adaptador → `SapClient` → SAP.
- **Pull**: CDC Kafka → estado `PENDING_SAP` → SAP BTP pregunta pendientes → SAP procesa → SAP notifica resultado.
- **Both**: se hace push inmediato y además queda pendiente para pull.

Ver [`docs/architecture/FLOWS.md`](docs/architecture/FLOWS.md) para el detalle de cada flujo con nombres de clase.

### Contrato SAP Business Partner

La especificación oficial de la API está en [`sap-api-models/specs/customer/API_BUSINESS_PARTNER.yaml`](sap-api-models/specs/customer/API_BUSINESS_PARTNER.yaml)
(43156 líneas, SAP_COM_0008). El README acompañante en
[`docs/specs/sap/README.md`](docs/specs/sap/README.md) lista los endpoints
relevantes para nuestro dominio y el mapping features↔API.

## Documentación

Fuentes de verdad del proyecto (consultar antes de cambiar arquitectura o stack):

- `docs/specs/SPEC.md` — especificación funcional (agnóstica a tecnología): objetivo, dominios, ingestas, destinos SAP, máquina de estados, criterios de aceptación.
- `docs/specs/TECH.md` — stack tecnológico: Java 25 + Spring Boot 4.0 + Maven, capas hexagonales, puertos/adaptadores, persistencia, observabilidad, testing.
- `sap-api-models/specs/customer/API_BUSINESS_PARTNER.yaml` — especificación OpenAPI oficial de SAP S/4HANA (Business Partner A2X, SAP_COM_0008, 43156 líneas).
- `docs/specs/sap/README.md` — catálogo de endpoints SAP relevantes para nuestro dominio y mapping features↔API.
- `docs/architecture/OVERVIEW.md` — mapas y esquemas: módulos, aggregate Customer, flujos CDC/REST/feature, deployment, convención de paquetes.
- `docs/architecture/FLOWS.md` — flujos de integración SAP con nombres de clase: CDC completo, consulta BP, creación BP, callback BTP, mapa de rutas BTP vs OData, actualización BP.
- `docs/testing/TESTING.md` — estrategia y catálogo de tests (191 tests, tipos, contratos SAP, issues conocidos).
- `docs/integrations/SAP_CLOUD_SDK.md` — guía de integración con SAP Cloud SDK: OData VDM, OpenAPI, BTP destinations, arquitectura hexagonal, módulos Maven.
- `docs/GLOSSARY.md` — términos del proyecto con definiciones y enlaces internos/externos.
- `external-services/README.md` — cómo levantar la infraestructura local (Kafka, PostgreSQL, SQL Server, MongoDB, Elasticsearch/Kibana, MinIO).
- Proyecto Python de referencia: `../poc-sap-integration`.

## Al modificar código

- **Nuevo dominio**: añadir módulo al reactor + entrada en `<modules>` del parent.
- **Nueva feature**: use case en `<dominio>/application/`.
- **Nuevo puerto**: interfaz en `<dominio>/domain/port/`; adaptador en
  `<dominio>/adapters/`.
- **Nuevo DTO SAP**: record/POJO con `@JsonProperty` en `<dominio>/adapters/sap/dto/`.
  Usar `SapJsonMapper.write(dto)` en el adaptador, nunca `String.format`.
- **Nuevo adaptador OData**: en `<dominio>/adapters/sap/odata/`, implementa el
  puerto existente, serializa la entidad **sin envolver** con
  `SapJsonMapper.write(modelo)` (el wrapper `{"d":...}` solo aparece en las
  respuestas OData v2, nunca en el body de las peticiones), activación
  condicional con `@ConditionalOnProperty("sap.odata.<feature>.enabled")`.
  Usa modelos generados de `sap-api-models` (paquete
  `com.poc.sap.integration.api.customer.model`), no DTOs manuales.
- **Nueva spec SAP**: colocar el YAML en `sap-api-models/specs/<dominio>/`
  (fuera de `src/main/resources` para no empaquetarlo en el JAR) y añadir una
  `<execution>` en el `openapi-generator-maven-plugin`. Tras regenerar
  (`mvn generate-sources -pl sap-api-models`), los modelos aparecen en
  `target/generated-sources/openapi/`.
- **Cambio en `SapClient`**: si se añade un nuevo método HTTP, implementar en
  `WebClientSapClient` via el método `exchange()` interno.
- **Cambio en `common`**: bump de versión según semver; ejecutar `it/` antes.
- **Nada de lógica de negocio en `bootstrap` ni `adapters`**.
- **Secretos fuera del código**: vía variables de entorno / Vault, nunca en YAML
  commiteados.
