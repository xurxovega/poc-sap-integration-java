# Testing — SAP Integration (Java)

> estrategia y catálogo de la suite de tests del reactor Maven `poc-sap-integration-java`.
>
> **Cómo se escriben** los tests (ciclo TDD, orden de capas, qué test toca a
> cada AC del spec) está en [`../development/README.md`](../development/README.md).
> Este documento es el catálogo de lo que **ya existe**.

## 1. Resumen ejecutivo

Total: **211 tests** en verde (validado el 25-07-2026 con JDK 25, `mvn clean test`).
El módulo `it` sigue ejecutando los contract dos veces — ver §8 issue 3.

| Módulo     | Tests aprox. | Contenido principal |
|------------|--------------|---------------------|
| common     | 61           | dominio (máquina de estados con estado inicial/re-sync, ValidationResult acumulativo), Mongo repo (dedupe `alreadySent`), auth providers, **`WebClientSapClientTest`** (retry 5xx, no-retry 4xx, cabeceras, CSRF completo contra WireMock) |
| customer   | ~105         | unit + slice + **`CustomerApplicationContextTest`** (smoke de contexto Spring completo) |
| article    | ~33          | unit + slice + **`ArticleApplicationContextTest`** (smoke de contexto) |
| it         | 11+1         | contract (WireMock) + `InfrastructureSmokeIT` (skip sin `-Ddocker.available=true`) |
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

**Spring Boot 4.0** eliminó `@MockBean` y `@WebMvcTest`. Las slices web se hacen con `MockMvcBuilders.standaloneSetup(...)` y mocks de Mockito.

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
| IndexCustomerUseCaseTest | IndexCustomerUseCase | 2 | Indexa, error legacy empty |
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
| S4BankingAdapterTest | S4BankingAdapter | 3 | Mapeo JSON, null banking, mandates |

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

JDK 25 (`C:\Program Files\Java\jdk-25.0.3`). Si `JAVA_HOME` apunta a JDK 23, el reactor usa automáticamente ese JDK (release 23). Con JDK 25, el profile `jdk25` sube a release 25.

## 6. Cobertura y gaps conocidos

- **WebClientSapClient** (common): sin test directo. Es reactivo (WebClient + Resilience4j Retry/CircuitBreaker) — requiere `MockWebServer` o WireMock con base-url inyectada. Pendiente.
- **`@SpringBootTest` wiring**: `CustomerApplication`/`ArticleApplication` no tienen test de contexto (necesitan datasource/Kafka/ES live o sustitutos). Pendiente.
- **Testcontainers IT**: gateados por `-Ddocker.available=true`. Solo `InfrastructureSmokeIT` (Kafka + Mongo). Faltan IT de Postgres, SQL Server, ES, SAP end-to-end.
- **JaCoCo**: configurado en parent pero sin umbral exigido. Pendiente reporte de cobertura.

## 7. Contratos SAP — patrón y cobertura

### Patrón `AbstractSapContractTest`

Base en `it/src/test/java/com/poc/sap/it/contract/AbstractSapContractTest.java`. Provee:
- `WireMockExtension` (puerto dinámico por test).
- `HttpClient` JDK.
- Helper `postJson(path, body)`.

### Contratos cubiertos

| Destino SAP | Endpoint (path) | Casos testeados |
|-------------|-----------------|-----------------|
| BTP Customer | `/sap/btp/odata/Customer` | 201 Created + Location, 400 bad |
| BTP Address | `/sap/btp/odata/CustomerAddress` | 201 + Location, header Idempotency-Key |
| BTP Fiscal | `/sap/btp/odata/CustomerFiscal` | 202 Accepted, 409 conflict |
| BTP Contact | `/sap/btp/odata/CustomerContact` | 201, 400 bad request |
| S4 Banking | `/sap/opu/odata/sap/API_CUSTOMER_MANDATE` | 202 Accepted, 401 Unauthorized |
| S4 Article | `/sap/opu/odata/sap/API_PRODUCT` | 201 Created, 400 bad request |

Cada contrato fija la firma del endpoint SAP para detectar breaking changes antes de re-desplegar la app correspondiente.

## 8. Issues conocidos

| # | Issue | Estado |
|---|-------|--------|
| 1 | `MongoSyncStateRepository.transition` rechazaba la primera transición `null → RECEIVED` | **Resuelto (25-07-2026)**: `SyncStateMachine` admite estados iniciales (`RECEIVED`, `VALIDATING`) y re-entrada desde `SENT_SAP`/`INVALID`. Cubierto por tests de common. |
| 2 | `SyncCustomerControllerIT` no se ejecuta (nombrado `*IT.java` sin failsafe en `customer/pom.xml`) | Pendiente: renombrar a `*Test.java` o añadir failsafe. |
| 3 | Contract tests del módulo `it` corren dos veces (surefire + failsafe) | Pendiente: excluir `**/*ContractTest.java` de surefire en `it/pom.xml`. |
| 4 | `*HistoryDoc.toDomain()` pierde datos (`unit`/`banking` a null) | Pendiente fix de mapeo. |
| 5 | Adapters BTP/S4 escribían `BusinessPartner:""` | **Resuelto (25-07-2026)**: los adaptadores rellenan `BusinessPartner`/`CustomerID` con el `entityId` real; aserciones añadidas en sus tests. |
| 6 | Contract tests de `it/` no pasan por el código de producción (stubbean WireMock y verifican el propio stub) | Pendiente: apuntar los adaptadores reales inyectando la base-url de WireMock. |

## 9. Próximos pasos

1. **Fix issue §8.2**: renombrar `SyncCustomerControllerIT` a `SyncCustomerControllerTest` para que surefire lo ejecute.
2. **Fix issue §8.3**: excluir `*ContractTest` de surefire en `it/pom.xml`.
3. **Fix issue §8.6**: reescribir los contract tests para ejercitar los adaptadores de producción y el contrato OData V2 real (envoltura `d` en respuestas, CSRF, errores SAP).
4. **IT con Testcontainers**: Postgres, SQL Server, ES, SAP end-to-end (gatear con `-Ddocker.available=true`), incluyendo el flujo CDC con Debezium de `external-services/`.
5. **JaCoCo** report + umbral mínimo en `domain` (100%) y `common` (>80%).
6. **Validación contra tenant real**: escrituras OData (`API_BUSINESS_PARTNER`) contra sandbox/tenant S/4 cuando esté disponible.