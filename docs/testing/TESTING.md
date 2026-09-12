# Testing — SAP Integration (Java)

> estrategia y catálogo de la suite de tests del reactor Maven `poc-sap-integration-java`.
>
> **Cómo se escriben** los tests (ciclo TDD, orden de capas, qué test toca a
> cada AC del spec) está en [`DESARROLLO.md`](../architecture/DESARROLLO.md).
> Este documento es el catálogo de lo que **ya existe**.

## 1. Resumen ejecutivo

Total: **295 tests** declarados (medido el 12-09-2026 con JDK 25, `mvn clean test`).

La cifra es de `@Test` **declarados** en `src/test/java` de todos los módulos; la vigila
`TestCountMatchesDocsTest` (módulo `it`) y el build falla si diverge. Los IT gateados
por Docker y los contract tests de failsafe cuentan aunque `mvn test` no los ejecute.
El módulo `it` sigue ejecutando los contract dos veces — ver §8 issue 3.

| Módulo     | Tests aprox. | Contenido principal |
|------------|--------------|---------------------|
| common     | 94           | dominio (máquina de estados con estado inicial/re-sync, ValidationResult acumulativo), `FeatureSyncPipelineTest` (recorrido por feature con la máquina real), `KafkaErrorHandlingConfigTest` (compartido), Mongo repo (dedupe `alreadySent`), auth providers, **`RestClientSapClientTest`** (retry 5xx, no-retry 4xx, cabeceras, PATCH/DELETE, CSRF completo con auth del destino y 403 sin `Required` contra WireMock) |
| customer   | 141          | unit + slice + **`CustomerApplicationContextTest`** (smoke de contexto Spring completo) |
| article    | 44           | unit + slice + **`ArticleApplicationContextTest`** (smoke de contexto) |
| it         | 16           | contract (WireMock, adaptadores **reales**, failsafe) + `TestCountMatchesDocsTest` + `SyncStateMongoIT`/`InfrastructureSmokeIT` (skip sin `-Ddocker.available=true`) |
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

### 3.1 common (45 tests)

| Fichero | Clase cubierta | # | Tipo | Descripción |
|---------|----------------|---|------|-------------|
| SyncStateMachineTest | SyncStateMachine | 7 | unit | Happy path, rama terminal, transiciones inválidas, recuperación de errores |
| ErrorStateRecoveryTest | SyncStateMachine | 9 | unit | Estados de error (ERROR, COMMUNICATION_ERROR, SAP_ERROR) y recuperación |
| SyncStateTransitionTest | SyncStateTransition | 3 | unit | Builder del VO, invariantes |
| ValidationResultTest | ValidationResult | 4 | unit | Factories `success`/`invalid`, chaining `and` |
| IngestionMessageTest | IngestionMessage | 8 | unit | Builder, invariantes, enums |
| SyncMetricsTest | SyncMetrics | 4 | unit | Contadores y timers por dominio/estado |
| BtpAuthProviderTest | BtpAuthProvider | 3 | unit | Token BTP xsuaa, fallback stub |
| S4NativeAuthProviderTest | S4NativeAuthProvider | 3 | unit | Token S/4 nativo, fallback stub |
| MongoSyncStateRepositoryTest | MongoSyncStateRepository | 4 | unit | Mapeo `SyncStateDoc`, recuperación de estado |

### 3.2 customer (106 tests)

**Unit domain (28)**:

| Fichero | Clase cubierta | # | Descripción |
|---------|----------------|---|-------------|
| CustomerValidationsTest | CustomerValidations | 6 | Aggregate completo + subconjuntos de features |
| AddressValidatorTest | AddressValidator | 5 | Campos obligatorios, formatos country/postal |
| FiscalValidatorTest | FiscalValidator | 6 | taxId ES, VAT, legalName, taxResidency |
| ContactValidatorTest | ContactValidator | 5 | email/phone/url, "al menos un canal" |
| BankingValidatorTest | BankingValidator | 6 | IBAN/BIC, mandateIds, "iban o mandate" |

**Unit use cases (29)**:

| Fichero | Clase cubierta | # | Descripción |
|---------|----------------|---|-------------|
| SyncCustomerUseCaseTest | SyncCustomerUseCase (orchestrador) | 8 | Happy path, empty legacy, invalid, partial features, invalid feature, sap_error, args inválidos |
| ValidateCustomerUseCaseTest | ValidateCustomerUseCase | 4 | Valid, invalid, missing, partial features |
| DeleteCustomerUseCaseTest | DeleteCustomerUseCase | 2 | Borra ok, mantiene imagen si SAP falla |
| SyncAddressUseCaseTest | SyncAddressUseCase | 4 | Happy, invalid, sap_error, featureEntityId |
| SyncFiscalUseCaseTest | SyncFiscalUseCase | 4 | Happy, invalid, sap_error, featureEntityId |
| SyncContactUseCaseTest | SyncContactUseCase | 4 | Happy, no-channel invalid, sap_error, featureEntityId |
| SyncBankingUseCaseTest | SyncBankingUseCase | 4 | Happy, empty banking invalid, sap_error, featureEntityId |
| DeleteMandateUseCaseTest | DeleteMandateUseCase | 2 | Happy, sap_error |
| ValidateAddressUseCaseTest | ValidateAddressUseCase | 3 | Valid, invalid, null address |
| ValidateFiscalUseCaseTest | ValidateFiscalUseCase | 3 | Valid, invalid, null fiscal |
| ValidateContactUseCaseTest | ValidateContactUseCase | 3 | Valid, invalid, null contact |
| ValidateBankingUseCaseTest | ValidateBankingUseCase | 3 | Valid, invalid, null banking |

**Unit adapter (5)**:

| Fichero | Clase cubierta | # | Descripción |
|---------|----------------|---|-------------|
| JsonCustomerPayloadParserTest | JsonCustomerPayloadParser | 5 | Full payload, defaults, null banking, json inválido |

**Unit SAP adapters (11)**:

| Fichero | Clase cubierta | # | Descripción |
|---------|----------------|---|-------------|
| BtpCustomerAdapterTest | BtpCustomerAdapter | 2 | Mapeo JSON, null customer |
| BtpAddressAdapterTest | BtpAddressAdapter | 2 | Mapeo JSON, null address |
| BtpFiscalAdapterTest | BtpFiscalAdapter | 2 | Mapeo JSON, null fiscal |
| BtpContactAdapterTest | BtpContactAdapter | 2 | Mapeo JSON, null contact |
| BtpBankingAdapterTest | BtpBankingAdapter | 3 | Contrato BTP, mandatos como lista, null banking (antes `S4BankingAdapterTest`) |
| BusinessPartnerBankODataAdapterTest | BusinessPartnerBankODataAdapter | 2 | `BankIdentification` ordinal sin BIC, `BankCountryKey` del IBAN |
| SepaMandateODataAdapterTest | SepaMandateODataAdapter | 4 | Alta en `SEPAMandateSet`, revocación por PATCH de estado, acreedor obligatorio, mapa de estados |

**Unit persistence (12)**:

| Fichero | Clase cubierta | # | Descripción |
|---------|----------------|---|-------------|
| SqlServerCustomerRepositoryTest | SqlServerCustomerRepository | 5 | Mapping Entity↔Domain, campos legacy↔features |
| MongoCustomerImageStoreTest | MongoCustomerImageStore | 4 | save/find/delete, banking preservation |
| ElasticsearchCustomerIndexerTest | ElasticsearchCustomerIndexer | 3 | index, history, doc desde aggregate |

**Slice web (4)**:

| Fichero | Clase cubierta | # | Descripción |
|---------|----------------|---|-------------|
| SyncCustomerControllerIT | SyncCustomerController | 4 | sync ok, default UPDATE, validate ok, validate invalid |

> ⚠️ **No se ejecutan** (issue §8.2): nombrado `*IT.java` sin `maven-failsafe-plugin` en `customer/pom.xml`.

**Unit kafka (4)**:

| Fichero | Clase cubierta | # | Descripción |
|---------|----------------|---|-------------|
| CustomerKafkaListenerTest | CustomerKafkaListener | 4 | parseo + invoke, default UPDATE, json malformado no propaga, objectMapper |

### 3.3 article (29 tests)

| Fichero | Clase cubierta | # | Tipo | Descripción |
|---------|----------------|---|------|-------------|
| ArticleValidationsTest | ArticleValidations | 5 | unit | valid, missing description/unit/status, null |
| SyncArticleUseCaseTest | SyncArticleUseCase | 4 | unit | happy, empty legacy, invalid, sap_error |
| SyncArticleControllerTest | SyncArticleController | 3 | slice | sync ok, default UPDATE, endpoint |
| ArticleKafkaListenerTest | ArticleKafkaListener | 4 | unit | parseo, default op, json malformado |
| S4ArticleAdapterTest | S4ArticleAdapter | 3 | unit | mapeo, null, formato |
| PostgresArticleRepositoryTest | PostgresArticleRepository | 3 | unit | Entity↔Domain |
| MongoArticleImageStoreTest | MongoArticleImageStore | 4 | unit | save/find/delete |
| ElasticsearchArticleIndexerTest | ElasticsearchArticleIndexer | 3 | unit | index, history |

### 3.4 it (11 + 1 skip)

| Fichero | # | Tipo | Descripción |
|---------|---|------|-------------|
| BtpCustomerContractTest | 1 | contract | Contract BTP Customer (201 + Location) |
| BtpAddressContractTest | 2 | contract | BTP Address 201 + header Idempotency-Key |
| BtpFiscalContractTest | 2 | contract | BTP Fiscal 202 + 409 conflict |
| BtpContactContractTest | 2 | contract | BTP Contact 201 + 400 bad request |
| S4BankingContractTest | 2 | contract | S4 Banking 202 + 401 unauthorized |
| S4ArticleContractTest | 2 | contract | S4 API_PRODUCT 201 + 400 |
| InfrastructureSmokeIT | 1 (skip) | integration | Kafka + Mongo Testcontainers (gateado `-Ddocker.available=true`) |

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
  líneas en `**/domain/**`** de cada módulo. Suelo medido el 12-09-2026: common
  90 %, customer 78 %, article 88 %. Si `domain` baja del umbral, `mvn verify`
  falla (comprobado forzando `-Djacoco.domain.line-minimum=0.95`). El dominio lo
  cubren también los tests de use case, así que protege la cobertura agregada, no
  cada test por separado. Informe HTML en `<módulo>/target/site/jacoco/index.html`.
- **ArchUnit**: `DomainPurityTest` (common, customer, article): `..domain..` no
  puede depender de Spring, Jackson, Mongo, Kafka, Micrometer ni JPA;
  `ApplicationPurityTest` (customer, article): `..application..` no depende de
  Spring, Micrometer ni Jackson (plan Fase 7; el wiring está en
  `bootstrap/*UseCaseConfig`). Las reglas `@ArchTest` no cuentan como `@Test`.
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
