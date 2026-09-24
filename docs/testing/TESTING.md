# Testing — SAP Integration (Java)

> estrategia y catálogo de la suite de tests del reactor Maven `poc-sap-integration-java`.
>
> **Cómo se escriben** los tests (ciclo TDD, orden de capas, qué test toca a
> cada AC del spec) está en [`DESARROLLO.md`](../architecture/DESARROLLO.md).
> Este documento es el catálogo de lo que **ya existe**.

## 1. Resumen ejecutivo

Total: **559 tests** declarados (medido el 23-09-2026 con JDK 25, `mvn clean test`; UI-001 añade 80: 11 unit del `PiiMaskerCommonTest` (en `common`, fachada legacy de `customer`), y 69 en el modulo nuevo `dashboard-customer`: 23 del dominio, 21 de los use cases, 14 de los adaptadores Mongo/ES (5 IT Testcontainers), 8 del bootstrap web/controllers (Prometheus, OpenApi, DashboardCompilation, Controllers), 2 del job de KPIs (con 2 IT Testcontainers adicionales si se ejecuta con Docker). OPS-010 añade 6: 3 del `TopologyTest` (renombrado y sustitución), 2 de los `*ApplicationContextTest#contextStartsWithMessagingBootstrapEnv` (uno por dominio), 1 del `InfrastructureSmokeIT` (migrado a `RedpandaContainer`); `DebeziumRedpandaIT` queda como IT Testcontainers (no cuenta en `clean test`). PRD-9 añade 16 anotaciones `@Test` (5 del helper `PiiMasker.maskName` en `common`, 5 del `LookupBusinessPartnerUseCaseTest` y 6 del `BusinessPartnerControllerTest`; los `@ParameterizedTest` que validan `top` fuera de `[1..200]` no entran en la cuenta porque `TestCountMatchesDocsTest` solo cuenta `@Test` que empiezan linea).

La cifra es de `@Test` **declarados** en `src/test/java` de todos los módulos; la vigila
`TestCountMatchesDocsTest` (módulo `it`) y el build falla si diverge. Los IT gateados
por Docker y los contract tests de failsafe cuentan aunque `mvn test` no los ejecute.
El módulo `it` sigue ejecutando los contract dos veces — ver §8 issue 3.

| Módulo     | Tests aprox. | Contenido principal |
|------------|--------------|---------------------|
| common     | 180          | dominio (máquina de estados con estado inicial/re-sync, ValidationResult acumulativo, `ConcurrentTransitionExceptionTest`), `FeatureSyncPipelineTest` (recorrido por feature con la máquina real: puerto que lanza, `httpStatus=0`, motivo del error, ciclo y **verificación previa cableada**: alta si SAP no la tiene, actualización con `If-Match` si la tiene, nada si el lookup no concluye, y el re-lookup único ante un `412`), `KafkaErrorHandlingConfigTest` (compartido: no reintentables, reintentables declaradas y backoff del circuito abierto), `KafkaSyncNotificationAdapterTest` (aviso con traza de pasos), Mongo repo (dedupe honesto `alreadySent`, `from` declarado frente al real, fencing por ciclo, consulta por ciclo), auth providers, **`RestClientSapClientTest`** (retry por método y fase del fallo: GET reintenta, POST solo antes de enviar, `If-Match`/`ETag`; no-retry 4xx, cabeceras, CSRF completo con auth del destino y 403 sin `Required` contra WireMock), `TransportFailuresTest` (clasificación antes/tras enviar contra la pila real), `MongoSapKeyStoreTest`, `RetryBudgetGuardTest` (lecturas y escrituras por separado, más el backoff de reentrega de Kafka), **`PayloadHasherTest`** (hash canónico del snapshot: determinista, sensible a cualquier campo, estable ante el orden de un mapa), **`PiiMaskerCommonTest`** (UI-001 H-1: enmascara IBAN/NIF/vatNumber ultimos 4, email inicial+dominio, telefono/fax ultimos 3, BIC passthrough; null/vacio defensivos; maskDetail para texto libre) |
| customer   | 198          | unit + slice (`SyncCustomerUseCaseTest` con fallo parcial y cero confianza, `CustomerStateUseCaseTest`/`CustomerStateControllerTest` con la traza de pasos y su enmascarado, `SyncCustomerControllerTest` con el `409 Conflict`, `CustomerKafkaListenerTest` con la `concurrency` declarada, los 6 adaptadores OData con `lookup`/`update` y el contacto con datos reales, `BusinessPartnerReadAdapterTest`) + **`CustomerApplicationContextTest`** (smoke de contexto Spring completo) + `PiiMaskerTest` (fachada `@Deprecated` despues de UI-001 H-1) |
| article    | 54           | unit + slice + **`ArticleApplicationContextTest`** (smoke de contexto) |
| dashboard-customer | 69   | UI-001 (puerto 8091, lectura directa Mongo+ES): `DashboardIsolationTest` (no cruza bounded contexts), `DomainPurityTest`/`ApplicationPurityTest`/`EndpointsDeclareAccessTest` mirrors; `DashboardControllerTest` (PII enmascarada para `sap-external-read`), `AlertControllerTest` (POST /ack con `sap-write` compone actor `<rol>:<subject>`); `KpiJobTest` y **`DashboardEmitsPrometheusMetricsTest`** (tag `application=dashboard-customer`); 4 IT Testcontainers Mongo + 1 IT Testcontainers ES (sin Docker son skipped) |
| it         | 27           | contract (WireMock, adaptadores **reales**, failsafe; incluye los de upsert `BusinessPartner*UpsertContractTest` y `S4ContactCommunicationContractTest`) + `TestCountMatchesDocsTest` + `SyncStateMongoIT` (traza por ciclo, lectura desfasada y fencing) / `InfrastructureSmokeIT` (skip sin `-Ddocker.available=true`) |
| supplier   | 0            | placeholder |

Los smoke tests de contexto levantan cada app sin infraestructura externa
(JPA sin acceso a metadata, listeners Kafka sin auto-arranque, índices ES con
`createIndex=false`): son los que detectaron los fallos de arranque de la
migración a Boot 4 (beans sin definir, YAML duplicado, starter OTel
incompatible, Jackson 3, `spring-kafka` sin autoconfiguración).

## 2. Tipos de tests

| Tipo            | Cuándo se usa                                              | Herramientas                |
|-----------------|-----------------------------------------------------------|------------------------------|
| **unit**        | Lógica de dominio, use cases, adapters (mocks de ports).   | JUnit 5, Mockito, AssertJ     |
| **slice web**   | REST controllers (sin contexto Spring Boot completo).      | `MockMvcBuilders.standaloneSetup` |
| **unit adapter**| Mapeo entity↔domain, formato JSON de adapters SAP.         | JUnit 5, Mockito             |
| **contract**    | Contratos SAP BTP/S4 (WireMock stubs).                    | WireMock, JDK HttpClient     |
| **integration** | Infraestructura real (Kafka, Mongo, ES).                   | Testcontainers, gateado      |

**Spring Boot 4.x** (4.0 eliminó `@MockBean` y `@WebMvcTest`; el proyecto está en 4.1.1). Las slices web se hacen con `MockMvcBuilders.standaloneSetup(...)` y mocks de Mockito.

## 3. Estructura por módulo

> Las cifras por módulo están en la tabla de §1 (medidas junto con el total de
> 447 y vigiladas por `TestCountMatchesDocsTest`); las tablas fichero a fichero
> que había aquí se desincronizaban en cada cambio y se han retirado. El
> detalle real —qué cubre cada clase de test— vive en el propio fichero de
> test (nombre de la clase + Javadoc citando el `AC-n`, según §1.5 de
> `AGENTS.md`); es la fuente que no puede quedarse desactualizada porque el
> build la ejecuta.
>
> Piezas nuevas relevantes desde el 18-09-2026, para ubicarlas rápido:
> - `common`: `TransportFailuresTest`, `MongoSapKeyStoreTest`,
>   `RetryBudgetGuardTest` (lecturas/escrituras separadas + backoff Kafka),
>   `FeatureSyncPipelineTest` (verificación previa cableada, cubre también
>   `SyncCycleRecorder` al no tener test dedicado propio).
> - `customer`: los 6 `*ODataAdapterTest` con `lookup`/`update`,
>   `SyncCustomerControllerTest` (409), `CustomerStateControllerTest`
>   (traza de pasos y enmascarado), `BusinessPartnerReadAdapterTest`.
> - `it`: `BusinessPartnerAddressUpsertContractTest` y similares
>   (`BusinessPartner*UpsertContractTest`), `S4ContactCommunicationContractTest`,
>   `BtpAddressContractTest` (renombrado), `SyncStateMongoIT`.

## 4. Convenciones

- **Naming**: `*Test.java` (unit/slice/adapter) — ejecutados por surefire. `*IT.java` (integration) — ejecutados por failsafe (`it/` module). `*ContractTest.java` — contrato WireMock (surefire en `it/`).
- **Fixtures**: helper reutilizable `CustomerFixtures` (en `customer/src/test/java/.../application/CustomerFixtures.java`), public, builders `validCustomer()`, `invalidAddressCustomer()`, `ingestionMessage(...)`.
- **Mocks**: `@ExtendWith(MockitoExtension.class)` + `@Mock` sobre ports (interfaces de `domain/port/`). NO mockar implementaciones concretas (`@InjectMocks` solo en use cases).
- **Strict stubs**: si hay stubs innecesarios en algún test, usar `lenient().when(...)`. Por defecto Mockito rechaza stubs no consumidos.
- **Assertions**: AssertJ (`assertThat(...).isEqualTo(...)`, `containsExactly`, `anyMatch`).
- **Sin Spring para domain/application**: dominio puro, sin beans Spring ni contexto.
- **Slice web**: `MockMvcBuilders.standaloneSetup(new Controller(mockUseCase))` (Spring Boot 4 eliminó `@WebMvcTest`).

## 5. Comandos

```bash
# Todo el reactor
mvn clean verify

# Solo tests unitarios (sin IT)
mvn test

# Un módulo
mvn -pl customer test
mvn -pl article test
mvn -pl common test

# Un test concreto
mvn -pl customer test -Dtest=SyncCustomerUseCaseTest
mvn -pl customer test -Dtest=SyncCustomerUseCaseTest#happyPathReturnsSentSapWhenAllFeaturesSucceed

# IT con Testcontainers (requiere Docker daemon)
mvn -pl it verify -Ddocker.available=true

# Contracts SAP (WireMock, sin Docker)
mvn -pl it test -Dtest=BtpCustomerContractTest,S4BankingContractTest

# Instalar sin tests
mvn clean install -DskipTests
```

JDK 25 (`C:\Program Files\Java\jdk-25.0.3`). **JDK 25 es el mínimo**: el reactor compila con `release 25` y ya no hay perfil `jdk25`; con un JDK anterior la compilación falla.

## 6. Cobertura y gaps conocidos

- **JaCoCo con umbral forzado** (Fase 2 de la auditoría): el parent declara
  `prepare-agent`, `report` y `check` en `verify`. El `check` exige **≥ 75 % de
  líneas en `**/domain/**`** de cada módulo. Suelo medido el 18-09-2026: common
  90 %, customer 78 %, article 88 %. Si `domain` baja del umbral, `mvn verify`
  falla (comprobado forzando `-Djacoco.domain.line-minimum=0.95`). El dominio lo
  cubren también los tests de use case, así que protege la cobertura agregada, no
  cada test por separado. Informe HTML en `<módulo>/target/site/jacoco/index.html`.
- **ArchUnit**: `DomainPurityTest` (common, customer, article): `..domain..` no
  puede depender de Spring, Jackson, Mongo, Kafka, Micrometer ni JPA;
  `ApplicationPurityTest` (customer, article): `..application..` no depende de
  Spring, Micrometer ni Jackson (plan Fase 7; el wiring está en
  `bootstrap/*UseCaseConfig`). Las reglas `@ArchTest` no cuentan como `@Test`.
  **Ojo (hallazgo del 2026-09-18):** con Spring Boot 4 el classpath lleva JUnit
  Platform **6**, y el módulo `archunit-junit5` (compilado contra Platform 1.x)
  no se registraba como motor: las reglas aparecían con `Tests run: 0` y **nunca
  se habían ejecutado**; una regla imposible pasaba en verde. Desde esa fecha el
  repo usa `archunit-junit6` y surefire/failsafe **≥ 3.6.0** (los primeros con
  soporte de JUnit 6). La comprobación es trivial: cada clase ArchUnit debe salir
  con `Tests run: 1` (o el número de sus `@ArchTest`) en el log, no con 0.
- **Sincronización parcial (ADR-0010)**: `KafkaSyncNotificationAdapterTest`,
  `CustomerStateUseCaseTest` y las aserciones de aviso en `SyncCustomerUseCaseTest`.
- **Seguridad de la API**: `ApiSecurityTest` (customer y article) levanta el contexto
  completo con la cadena de seguridad activa e inyecta JWT con `spring-security-test`
  (401 sin token, 403 sin rol, PII enmascarada para `external-read`);
  `EndpointsDeclareAccessTest` (ArchUnit) exige `@PreAuthorize` en todo endpoint.
- **Contrato REST vigilado**: `OpenApiMatchesControllersTest` (customer y article,
  4 `@Test` cada uno) carga el `openapi.yml` del módulo con SnakeYAML y lo cruza
  con los controladores descubiertos por reflexión sobre `bootstrap.web`: todo
  endpoint del código está en el contrato, toda operación del contrato existe en
  el código, cada operación declara `x-required-role` y coincide con su
  `@PreAuthorize`, y el contrato lleva `servers` y `securitySchemes`. Spec:
  [`../sdd/common/contrato-openapi-rest.md`](../sdd/common/contrato-openapi-rest.md).
- **Recuento de tests vigilado**: `TestCountMatchesDocsTest` (módulo `it`) cuenta
  los `@Test` declarados y falla si §1 de este documento no coincide.
- **IT con Docker** (`-Ddocker.available=true`): `SyncStateMongoIT` (Mongo 7 real:
  re-sync tras `SAP_ERROR`, escritores concurrentes, documentos legacy sin `seq`)
  e `InfrastructureSmokeIT` (Kafka + Mongo arrancan). Falta el e2e completo
  outbox → Debezium → app → WireMock → Mongo (backlog TEST-1/TEST-4).
- **`*HistoryDoc.toDomain()`** pierde datos (`unit`/`banking` a null): §8.4, pendiente.

## 7. Contratos SAP — patrón y cobertura

### Patrón `AbstractSapContractTest`

Base en `it/src/test/java/com/poc/sap/it/contract/AbstractSapContractTest.java`.
Desde la Fase 2 (auditoría B6) construye el **`RestClientSapClient` real** (antes `WebClientSapClient`; ADR-0001) con la
URL de WireMock como destino BTP y S/4, un `AuthProvider` fijo
(`Bearer contract-token`), retry de 3 intentos con 10 ms y circuit breaker por
defecto. Cada test instancia el **adaptador de producción** y verifica en
WireMock método, path, `Authorization`, `Idempotency-Key` y cuerpo JSON
(`matchingJsonPath`). Los ejecuta **failsafe** (`mvn verify`); surefire los excluye.

### Contratos cubiertos

| Adaptador real | Endpoint (path) | Casos |
|---|---|---|
| `BtpCustomerAdapter` | `/sap/btp/odata/Customer` | POST cabecera del cliente · **`DELETE …('C-1')`** en la baja, sin POST (spec baja-cliente AC-2) |
| `BtpAddressAdapter` | `/sap/btp/odata/CustomerAddress` | POST mapeado · 503 reintentado 3 veces |
| `BtpFiscalAdapter` | `/sap/btp/odata/CustomerFiscal` | POST mapeado (NIF, IVA, razón social, residencia) |
| `BtpContactAdapter` | `/sap/btp/odata/CustomerContact` | POST mapeado |
| `BtpBankingAdapter` | `/sap/btp/odata/CustomerBanking` | POST del contrato BTP (IBAN, BIC, `Mandates` como lista). Antes `S4BankingAdapter` contra `API_CUSTOMER_MANDATE`, inexistente (B3) |
| `BusinessPartnerBankODataAdapter` | `/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartnerBank` | POST con `BankIdentification=0001`, `BankCountryKey` del IBAN y **sin BIC** |
| `SepaMandateODataAdapter` | `/sap/opu/odata/sap/API_APAR_SEPA_MANDATE_SRV/SEPAMandateSet` | POST del alta (clave `Creditor`+`SEPAMandate`, BIC en `SenderBankSWIFTCode`) · **PATCH** de revocación (`SEPAMandateStatus=3`) sobre la clave, sin POST ni DELETE |
| `S4ArticleAdapter` | `/sap/opu/odata/sap/API_PRODUCT` | POST mapeado (Product, Description, BaseUnit, Status) |

WireMock sigue respondiendo lo que se le pide: estos tests fijan **lo que nosotros
enviamos**, no lo que SAP acepta. Eso se valida contra el tenant de test (Fase 3;
backlog TEST-5).

## 8. Issues conocidos

| # | Issue | Estado |
|---|-------|--------|
| 1 | `MongoSyncStateRepository.transition` rechazaba la primera transición `null → RECEIVED` | **Resuelto (25-07-2026)**: `SyncStateMachine` admite estados iniciales (`RECEIVED`, `VALIDATING`) y re-entrada desde `SENT_SAP`/`INVALID`. Cubierto por tests de common. |
| 2 | `SyncCustomerControllerIT` no se ejecuta (nombrado `*IT.java` sin failsafe en `customer/pom.xml`) | **Resuelto (12-09-2026, Fase 2)**: renombrado a `SyncCustomerControllerTest` (es un slice MockMvc, no necesita Docker); surefire fijado a 3.5.3 en el parent. |
| 3 | Contract tests del módulo `it` corren dos veces (surefire + failsafe) | **Resuelto (12-09-2026, Fase 2)**: `**/*ContractTest.java` y `**/*IT.java` excluidos de surefire en `it/pom.xml`. |
| 4 | `*HistoryDoc.toDomain()` pierde datos (`unit`/`banking` a null) | Pendiente fix de mapeo. |
| 5 | Adapters BTP/S4 escribían `BusinessPartner:""` | **Resuelto (25-07-2026)**: los adaptadores rellenan `BusinessPartner`/`CustomerID` con el `entityId` real; aserciones añadidas en sus tests. |
| 6 | Contract tests de `it/` no pasan por el código de producción (stubbean WireMock y verifican el propio stub) | **Resuelto (12-09-2026, Fase 2, auditoría B6)**: `AbstractSapContractTest` construye el cliente SAP real (hoy `RestClientSapClient`) contra WireMock y cada test ejercita el adaptador real (path, método, `Authorization`, `Idempotency-Key`, cuerpo). |

## 9. Próximos pasos

1. **Fix issue §8.4**: mapeo completo en `*HistoryDoc.toDomain()`.
2. **E2E con Testcontainers** por dominio: outbox → Debezium → app → WireMock →
   Mongo, gateado con `-Ddocker.available=true` (backlog TEST-1/TEST-4).
3. **ArchUnit sobre `application`** (sin Spring/Micrometer/Jackson) cuando la
   Fase 7 introduzca `MetricsPort`/`DiffPort`.
4. **Validación contra tenant real**: escrituras OData (`API_BUSINESS_PARTNER`)
   contra el tenant S/4 de test (Fase 3; backlog TEST-5).
