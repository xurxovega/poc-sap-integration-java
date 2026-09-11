# logAgentes — 2026-09-10 — Análisis de `poc-sap-integration-java`

> Informe de evaluación producido por el equipo de agentes de este repositorio sobre
> `/mnt/a/Documentos/Code/poc-sap-integration-java`. **Solo lectura**: no se ha creado,
> editado ni borrado ningún fichero del repo analizado, ni se ha ejecutado `mvn`, `docker`,
> `git` de escritura ni scripts del repo. Todo hallazgo cita `fichero:línea` del repo analizado.
> Las cifras que no salen del código llevan su origen etiquetado (*dato verificado* /
> *fuente web* / *estimación del equipo*).

---

## 0. Ficha

| Campo | Valor |
|---|---|
| Fecha del análisis | 2026-09-10 |
| Repo analizado | `poc-sap-integration-java` · rama `feature/saneamiento-integracion-sap` · HEAD `4ab38ab` (2026-09-10 01:17) · 26 commits (19 en julio, 7 en septiembre de 2026) · sin tags · `0.1.0-SNAPSHOT` |
| Qué es | PoC de sincronización de datos maestros (`customer`, `article`, `supplier`) desde legacy (SQL Server, PostgreSQL) hacia **SAP S/4HANA Public Cloud**, vía CDC (outbox + Debezium + Kafka) o REST. Java 23/25 + Spring Boot 4.0.0 + Maven multi-módulo, arquitectura hexagonal por dominio, shared kernel `common`, modelos SAP generados desde specs OpenAPI oficiales |
| Tamaño (*dato verificado*) | 120 ficheros Java en `src/main` (5.042 LOC) · 63 ficheros de test (4.557 LOC) · 27 ficheros `.md` (5.082 líneas) + 1 HTML con 16 diagramas Mermaid |
| Estado del árbol | 172 ficheros "modificados" sin commitear: **todos son solo CRLF↔LF** (`git diff -w --ignore-cr-at-eol` vacío). No hay `.gitattributes` |
| Clasificación (skill `agent-routing`) | Tipo **Diseño y prototipado** (evaluación de arquitectura sin desarrollo) · severidad **atencion** (hay hallazgos que bloquearían un piloto, pero no hay servicio en producción afectado) · circuito **completo acotado** |
| Método | **Ronda 1** (Parte I, §1-§8): un orquestador (esta sesión) + 5 subagentes en paralelo con los roles de este repo: `solution-architect`, `integrator-systems`, `qa-tester`, `auditor-monitor` (ampliado a seguridad e infraestructura) y `docs-writer` en modo auditor. Verificación web complementaria para la sección de propuestas. **Ronda 2** (Parte II, §9-§17, mismo día): los roles y fases que faltaban, todos en modo análisis y solo lectura: `solution-business` (Fase 0/1), `auditor-business` (Fase 1b), `planner` (Fase 2b), `dev-implementer` en modo revisión de código (Fase 3 sin escribir), `incident-responder` en modo post-mortem; más la revisión del propio método (§16-§17) |

---

## 1. Resumen ejecutivo

**Veredicto global: PoC bien pensada, con implementación todavía inmadura para un piloto.** La columna vertebral es sólida: dominio puro con `records`, puertos genéricos en un shared kernel, una única máquina de estados persistida por transición, dos familias de adaptadores SAP conmutables por configuración, outbox transaccional real con Debezium y una documentación inusualmente rica y honesta sobre lo que falta. Pero al bajar al código aparecen defectos que hoy impiden que el sistema funcione de forma sostenida contra un SAP real, y varias promesas escritas que el código no cumple.

| Eje | Nota (1-10) | Una frase |
|---|---|---|
| Arquitectura y diseño | **6** | `domain` impecable; `application` 100 % anotada con Spring; ~25 % de código duplicado; estados sumidero en la máquina de estados |
| Integración SAP / Kafka / CDC | **5** | Outbox y resiliencia bien hechos; ruta OData solo hace POST (duplica direcciones, no actualiza), el DELETE nunca es ejecutable, dedupe ignora regresiones |
| Calidad y tests (SDD+TDD) | **6** | 245 tests declarados, buena disciplina de estilo; 4 tests que nunca se ejecutan, contract tests tautológicos, solo el 2 % cita un criterio de aceptación |
| Observabilidad | **3** | Un contador por estado bien diseñado; nada de latencia/errores SAP, circuit breaker, DLT, logs JSON, trazas ni dashboards |
| Seguridad | **3** | APIs REST sin autenticación devolviendo IBAN/NIF; credencial real de tenant S/4 versionada en el spike anidado; contraseña por defecto en YAML empaquetado |
| Operación e infraestructura | **4** | Script de arranque robusto; sin empaquetado, CI, perfiles, graceful shutdown ni recuperación de entidades atascadas |
| Documentación | **6** | Completa, navegable (1 enlace roto de 226) y honesta; pero 3 afirmaciones falsas en `AGENTS.md`, `TECH.md` describe 9 adaptadores inexistentes, y el DLT está mal en 21 sitios |

> **Parte II (§9-§17)** añade lo que la ronda 1 no cubrió: caso de negocio y criterios de salida del PoC (no existen), dictamen independiente de los indicadores implícitos (5 aptos de 19), revisión de código línea a línea (43 hallazgos, 4 bugs nuevos), post-mortem de los 6 defectos "resueltos" (V1 y V2 son recurrencias del mismo fingerprint), un plan ejecutable de 45 tareas con ruta crítica, y lo que echo en falta en la propia metodología de este repo.

### Los cinco hallazgos que importan (si solo se lee esto)

1. **Un cliente cuyo envío a SAP falla una vez no se vuelve a sincronizar nunca.** `SAP_ERROR` no admite transición a `RECEIVED` (`SyncStateMachine.java:53`) y el orquestador siempre arranca por `RECEIVED` (`SyncCustomerUseCase.java:101`): el siguiente evento lanza `IllegalStateException`, agota 3 reintentos Kafka y cae en la DLT. Lo mismo ocurre si el proceso muere en `FETCHING`/`INDEXING`/`SENDING_SAP`. Ningún test lo detecta porque el repositorio de estados está mockeado.
2. **El borrado nunca es ejecutable.** `DeleteCustomerUseCase.java:38` entra por `null → SENDING_SAP`, transición ilegal (`INITIAL_STATES = {RECEIVED, VALIDATING}`, `SyncStateMachine.java:32`). Y si llegara a ejecutarse, haría un `POST {}` (`BtpCustomerAdapter.java:35-36`), no un DELETE. El catálogo de APIs afirma lo contrario.
3. **La ruta OData nativa solo hace POST.** `SapClient.patch` y `delete` no tienen llamadores. Cada actualización re-POSTea el Business Partner y crea una dirección nueva por evento porque no fija `AddressID` (`BusinessPartnerAddressODataAdapter.java:41-49`). Además el adaptador de contacto descarta email y teléfono (`BusinessPartnerContactODataAdapter.java:41-46`). Toda la familia OData (5 adaptadores) carece de tests.
4. **Seguridad**: APIs y actuator sin autenticación exponiendo PII (`CustomerHistoryController.java:49-51`, `CustomerHistoryDoc.java:35-45`) y permitiendo disparar escrituras facturables en SAP; y una credencial real de un communication user de S/4 versionada en `sap-sdk-client/src/main/resources/application.properties:5-7` (repo anidado, reconocido como SEC-4 en el backlog, **aún sin rotar**).
5. **El build no vigila nada de lo que promete.** Sin CI, sin Maven wrapper (el README dice que está incluido), sin ArchUnit, JaCoCo declarado pero nunca ejecutado, surefire sin versión fijada, Spring Cloud 2025.0.0 (tren de Boot 3.5) con Boot 4.0.0, y 4 tests (`SyncCustomerControllerIT`) que nunca corren por el patrón de nombre.

---

## 2. Hallazgos consolidados y priorizados

Deduplicados entre los cinco informes. Escala del equipo: `bloqueante` / `atencion` / `observacion`.

### 2.1 Bloqueantes

| # | Hallazgo | Evidencia | Mitigación (qué, no cómo) |
|---|---|---|---|
| B1 | Estados sumidero: `SAP_ERROR` y estados intermedios sin re-entrada a `RECEIVED`; reintento Kafka agrava (excepción no marcada como no reintentable) | `SyncStateMachine.java:45-47,53` · `SyncCustomerUseCase.java:101` · `KafkaErrorHandlingConfig.java:19-24` · CHANGELOG "Pendiente" | Todo estado no terminal admite `RECEIVED` ante evento nuevo (o lease con timeout); `addNotRetryableExceptions(IllegalStateException)`; test de caso de uso con repositorio de estados en memoria real |
| B2 | DELETE ilegal en la máquina y mal implementado (POST `{}`) | `DeleteCustomerUseCase.java:38-39` · `BtpCustomerAdapter.java:35-36` · `sap-api-catalog.md` (afirma DELETE) | Entrar por `RECEIVED`; usar `SapClient.delete`; corregir catálogo |
| B3 | Ruta OData sin upsert: solo POST, sin lookup de BP, sin `AddressID`, contacto vacío, BIC en `BankIdentification`, mandatos apuntando a una API inexistente (`API_CUSTOMER_MANDATE`) | `BusinessPartnerODataAdapter.java:43-45` · `BusinessPartnerAddressODataAdapter.java:41-49` · `BusinessPartnerContactODataAdapter.java:41-46` · `BusinessPartnerBankODataAdapter.java:44` · `customer/application.yml:57` · `SqlServerCustomerRepository.java:41` | Lookup con `BusinessPartnerReadAdapter` (ya existe) → POST con deep insert en alta / PATCH con `If-Match` en modificación; `A_AddressEmailAddress`/`A_AddressPhoneNumber`; `API_APAR_SEPA_MANDATE_SRV` (modelos ya generados y sin consumidor) |
| B4 | APIs REST y actuator sin autenticación; `/history?full=true` y `/diff` devuelven IBAN, NIF, email, teléfono; `/sync` dispara llamadas facturables a S/4; `show-details: always` en customer | `SyncCustomerController.java:31-52` · `CustomerHistoryController.java:49-66` · `customer/application.yml:37-44` · sin `spring-boot-starter-security` en ningún pom | Spring Security (resource server OAuth2 o API key), puerto de gestión separado, `show-details: when-authorized` |
| B5 | Credencial real de tenant S/4 (URL, usuario, contraseña) versionada en el repo anidado | `sap-sdk-client/src/main/resources/application.properties:5-7` (no se reproducen valores) · `MEJORAS-Y-PROPUESTAS.md:60` (SEC-4) | Rotar el communication user hoy; purgar historial del repo anidado; sacar `sap-sdk-client/` del árbol o ignorarlo explícitamente |
| B6 | Contract tests de `it/` no ejercitan código de producción (stubbean WireMock y lo llaman con `HttpClient` de JDK); el spec de dirección los cita como cobertura del mapeo BTP | `AbstractSapContractTest.java:27-34` · 0 imports de `customer`/`article` en `it/src/test` · `sincronizacion-direccion.md:118` | Inyectar la URL de WireMock en `WebClientSapClient` y llamar a los adaptadores reales; verificar path, cabeceras y body |
| B7 | `SyncCustomerControllerIT` (4 tests) nunca se ejecuta: patrón `*IT` sin failsafe en `customer`; sin CI que lo hubiera detectado | `customer/pom.xml:96-104` · `.github/` sin `workflows/` | Renombrar a `*Test`; fijar surefire en el parent; CI mínimo |
| B8 | `AGENTS.md` afirma que los adaptadores BTP van "sin `@ConditionalOnProperty`"; los 5 lo llevan (`havingValue=false, matchIfMissing=true`). Un agente que siga la instrucción creará beans duplicados | `AGENTS.md:248` vs `BtpAddressAdapter.java:21`, `BtpContactAdapter.java:16`, `BtpFiscalAdapter.java:16`, `BtpCustomerAdapter.java:21`, `S4BankingAdapter.java:20` | Corregir la frase; describir el toggle real |
| B9 | `TECH.md` §5/§8 describe 9 adaptadores que no existen (`DebeziumKafkaAdapter`, `RestAdapter`, `JpaStateRepository`, `BtpApiAdapter`…) y capacidades no implementadas (swagger-ui, logs JSON, Pact, perfiles `dev/it/native`) sin marcarlas como visión | `TECH.md:21,64-69,84,100-101,124,135` | Reescribir con clases reales o etiquetar como aspiracional |

### 2.2 Atención

| # | Hallazgo | Evidencia |
|---|---|---|
| A1 | Dedupe por `payloadHash` busca **cualquier** `SENT_SAP` histórico: la secuencia A→B→A descarta el tercer evento aunque SAP tenga B | `MongoSyncStateRepository.java:50-56` |
| A2 | `transition()` es read-then-write sin bloqueo ni versión optimista; `timestamp` en ms sin desempate; REST y Kafka concurren con virtual threads sin exclusión por entidad | `MongoSyncStateRepository.java:35-40` · `SyncStateDoc.java:22` · `application-common.yml:75-77` |
| A3 | Sin compensación entre features: fallo parcial deja SAP a medias; el siguiente evento reenvía todas las features | `SyncCustomerUseCase.java:144-160` |
| A4 | `application` viola la regla "sin Spring": 16/16 clases con `@Service`; 14 dependen de `SyncMetrics` (Micrometer) y 2 de `JsonDiff` (Jackson) | p. ej. `SyncCustomerUseCase.java:20,38` · `SyncMetrics.java:6,16` · `JsonDiff.java:3-4` |
| A5 | ~900 LOC duplicadas de 3.468 en `customer`+`article` (~25 %): 4×`Sync<Feature>UseCase`, 4×`Validate<Feature>UseCase`, `*HistoryUseCase`, `*HistoryController`, indexers, image stores, `KafkaErrorHandlingConfig`, 14 copias de `transition()` | comparación por `diff` normalizado |
| A6 | CSRF: el fetch del token usa siempre Basic aunque `auth.type=oauth2`; cualquier 403 se trata como rechazo CSRF y dispara una segunda cadena de reintentos | `S4CsrfTokenProvider.java:60-66` · `WebClientSapClient.java:201-205` |
| A7 | `Idempotency-Key` no lo honra OData V2 de S/4: un POST reintentado tras timeout de respuesta duplica en SAP | `WebClientSapClient.java:158-160,176` |
| A8 | Contraseña de dev como default en YAML empaquetado (`sa`) + `trustServerCertificate=true`; fallback silencioso a token stub si falta config (en producción da 401 en vez de fallar al arrancar) | `customer/application.yml:10-12` · `BtpAuthProvider.java:33-34` · `S4NativeAuthProvider.java:47-48,57` |
| A9 | Observabilidad prometida y no implementada: sin logs JSON ni MDC (`TECH.md:124` y `OVERVIEW.md:428` dicen lo contrario), sin trazas ni collector, BOM de OTel importado sin uso, timer `recordStageDuration` nunca invocado en `main`, `WebClient.builder()` estático (sin métricas HTTP de Boot), sin binder de Resilience4j, tag `application` idéntico en ambas apps | `SyncMetrics.java:43` · `WebClientSapClient.java:80,82` · `SapIntegrationConfig.java:63` · `application-common.yml:67-72` · `pom.xml:106-119` |
| A10 | Sin `server.shutdown=graceful`; presupuesto de reintentos por mensaje (4 features × 3 intentos × 23 s ≈ 4,6 min, *estimación del equipo*) roza `max.poll.interval.ms` de 5 min → rebalanceos en cascada con SAP degradado | `application-common.yml:49-57` · `KafkaErrorHandlingConfig.java:21-22` |
| A11 | PII sin TLS (Kafka PLAINTEXT, ES sin xpack, Mongo sin auth) ni retención (sin TTL Mongo, sin ILM en ES); supresión RGPD no alcanza el histórico ES ni `sync_state` | `docker-compose.yml:38-40,204` · `DeleteCustomerUseCase.java:37-47` |
| A12 | Spring Cloud `2025.0.0` es el tren de Boot 3.5 y es **incompatible con Boot 4.0.1+** (*fuente web*, ver §5); solo lo usa `spring-cloud-contract-wiremock`, que ningún test referencia. Boot fijado en 4.0.0 cuando existen 4.0.7 y 4.1.0. Jackson 2 y 3 conviven por el orden de BOMs | `pom.xml:30-31,42-61` · `it/pom.xml:76-79` |
| A13 | SAP Cloud SDK `sdk-core` como dependencia de `common` sin uso real (solo registra un destino local que nadie consume) y `@ComponentScan("com.sap.cloud.sdk")`; el SDK 5.x soporta oficialmente Boot 3, no Boot 4 (*fuente web*) | `common/pom.xml:20-23` · `SapCloudSdkLocalDestinationConfig.java` · `CustomerApplication.java:21-22` |
| A14 | Ancla SDD real: 2 specs de 13 features; 5 tests de 245 (2 %) citan un `AC-n`; 3 nombres de test en los specs no existen; el DoD no es aplicable al ~85 % del código | `docs/sdd/README.md:84-115` · grep `AC-` en `src/test` |
| A15 | Documentación de tests incoherente: README dice 211, TESTING/GUIA/QUICK_START dicen 240, el código tiene 245; el catálogo de TESTING suma 192, incluye una clase fantasma (`JsonCustomerPayloadParserTest`) y se contradice entre §1 y §6 | `README.md:38,229` · `TESTING.md:11,38,50,71,160` |
| A16 | Topic DLT real `outbox.CUSTOMER-dlt` documentado como `<topic>.DLT` en 21 sitios (defecto conocido, OPS-3, sin decidir) | `KafkaErrorHandlingConfig.java:20` · `docs/sdd/README.md:148` |
| A17 | Máquina de estados descrita 8 veces; 4 copias desactualizadas tras el cambio del 2026-09-10 (re-entrada por `VALIDATING`) | `OVERVIEW.md:285-291` · `GLOSSARY.md:266` · `MAPA-FUNCIONAL.html:1033-1036` · `AGENTS.md:198` |
| A18 | Código muerto o sin implementar: `IngestionPort` sin implementaciones (la tabla de puertos dice que sí), `MandateSapOutboundPort` sin adaptador, `COMMUNICATION_ERROR` nunca producido, modelos generados de `API_PRODUCT_SRV` y SEPA sin consumidor, `sap.odata.enabled` global no leído, `sap.integration.mode` no leído | grep en `src/main` |
| A19 | Lógica de negocio filtrada a adaptadores: defaults `legalName→name`, `status null→ACTIVE`, constantes `"2"`/`"BPEE"` de categoría/grouping SAP; `S4ArticleAdapter` serializa con `String.format` violando la regla explícita de `AGENTS.md` §2.6 | `SqlServerCustomerRepository.java:37-38` · `CustomerDocument.java:46` · `BusinessPartnerODataAdapter.java:44-45` · `S4ArticleAdapter.java:33-40` |
| A20 | 27 `@Mock` sobre clases concretas o infraestructura (`SyncMetrics` ×14, use cases, repos Spring Data) contra la regla "mocks solo sobre puertos" de `AGENTS.md` §1.5 | `SyncCustomerUseCaseTest.java:48-51` · `CustomerKafkaListenerTest.java:32-33` |
| A21 | Sin artefacto de despliegue (Dockerfile, Helm, `mta.yaml`, buildpacks configurados) ni perfiles `local/test/prod`; `launch.json` activa un perfil `dev` inexistente | find vacío · `.vscode/launch.json:10,18` |
| A22 | Sin Dependabot/Renovate, SBOM ni dependency-check; imágenes flotantes (`mssql 2022-latest`, `postgres:16`, `mongo:7.0`, `mysql:8.4`) | `.github/` · `docker-compose.yml:110,169,201,239` |
| A23 | Referencias a ficheros borrados en la reorganización: `SPEC.md §3/§6/§9` y `docs/specs/sap/README.md` | `supplier/pom.xml:17` · `it/pom.xml:17` · `sap-api-models/pom.xml:65` |
| A24 | `README.md:81` "usar el wrapper incluido" vs `QUICK_START.md:82` "No hay wrapper": no existe `mvnw` ni `.mvn/` | raíz del repo |
| A25 | CRLF en árbol de trabajo sin `.gitattributes`: el próximo commit tocará miles de líneas sin cambio real y destruirá el `git blame` | `git diff --stat` 172 ficheros / `git diff -w` vacío |

### 2.3 Observación

- Circuit breaker envuelto por el `Retry`: con el circuito abierto, `CallNotPermittedException` se reintenta 3 veces (`WebClientSapClient.java:123-124`, `SapIntegrationConfig.java:46`).
- `.block()` sobre WebClient en app MVC arrastrando `webflux` completo; `synchronized` alrededor de I/O de red con virtual threads (pinning en JDK 23, resuelto en JDK 24+ por JEP 491; el proyecto compila por defecto con `release 23`); pool de conexiones sin `maxIdleTime`.
- Imagen Mongo guardada **antes** del envío a SAP; el atajo "sin cambios reales" registra `SENT_SAP` para un hash que nunca se envió (`SyncCustomerUseCase.java:128,133,137`).
- Topics autocreados (1 partición, RF=1), ZooKeeper en vez de KRaft, sin healthcheck de zookeeper, 14 puertos publicados en `0.0.0.0`, MinIO con bucket público y sin uso.
- SMT manual de Debezium (`ExtractNewRecordState` + `ExtractField` + `RegexRouter`) en lugar del `EventRouter` oficial; outbox append-only que nunca se purga; contrato JSON sin esquema.
- Comando de shell pegado por accidente dentro de la definición de *Business Partner* en `GLOSSARY.md:36`; 13 términos frecuentes sin entrada (MCP ×52, PII, SEPA, IBAN, BIC, Kafka Connect, SMT, OTLP, A2X, saga, Published Language…); 6 definiciones obsoletas.
- `TECH.md` llama "LTS" a Java 23 (los LTS son 21 y 25).
- Javadoc que cita sección de arquitectura en 55/120 clases (46 %); 21 clases sin Javadoc.
- Hooks de "GitHub Copilot App Modernization" en `.github/modernize/` (ignorados, inocuos) y `.DS_Store` en el spike.
- Histórico/diff implementado y sin fila en el índice de features; columna PR vacía en el §10 de ambos specs.
- 0 tests de rendimiento; NFR de rendimiento sin cifra (`OVERVIEW.md:430`).
- Registro MySQL `sdd_registry` solo con el seed; el `INSERT` es manual y nada lo audita.

---

## 3. Evaluación por eje (qué se aprende de cada uno)

### 3.1 Arquitectura y diseño

**Lo que está bien.** La regla de dependencias se cumple donde más importa: `domain` de los tres módulos no importa nada de Spring, Jackson, JPA, Mongo ni Kafka (*dato verificado*: 0 violaciones). El modelo usa `records` en el 100 % de entidades y value objects. Los puertos genéricos de `common` (`LegacyRepositoryPort<E>`, `ImageStorePort<E>`, `HistoryIndexerPort<E>`, `SapOutboundPort<P>`) se especializan por interfaces marcadoras en cada dominio, que es exactamente lo que Spring necesita para resolver beans por tipo. `sap-api-models` como *Published Language* separado del *Shared Kernel* es una distinción DDD correcta y poco habitual.

**Lo que falla.** La regla "sin Spring en `application`" no se cumple en ninguna clase (16/16 con `@Service`) y, peor, la capa de aplicación depende de Micrometer y Jackson a través de `common`. Es una decisión pragmática defendible, pero contradice la documentación y nada la vigila (sin ArchUnit). La anemia del dominio es deliberada (hay que representar datos legacy inválidos para llegar a `INVALID`), pero no está hecha explícita: un `sealed interface ValidationResult { Valid, Invalid }` o un par `Customer`/`ValidatedCustomer` lo dejaría claro. `Mandate` es un agregado huérfano: nadie lo carga y su puerto de salida no tiene adaptador.

**La duplicación es el coste oculto del "un artefacto por dominio".** Los cuatro `Sync<Feature>UseCase` son idénticos salvo el nombre; `customer` y `article` repiten indexer, image store, history use case, controller de histórico y configuración de Kafka. Un `SyncPipeline<E>` genérico en `common` (dedupe → fetch → validate → sin cambios → index → send) reduciría ~900 LOC y haría que el dominio `supplier` costara días en vez de semanas.

**Las dos familias de adaptadores funcionan, pero no como dice la documentación.** Son mutuamente excluyentes por `@ConditionalOnProperty` complementarios; no hay `@Primary` ni `@Qualifier` porque no hacen falta. El riesgo es de escala (2 clases × N features con el mismo esqueleto) y de coherencia de nombres de propiedades.

### 3.2 Integración SAP, Kafka y CDC

**El tramo legacy → Kafka es el mejor del proyecto.** Trigger `AFTER` que escribe en la outbox dentro de la misma transacción, CDC de SQL Server habilitado sobre la outbox (no sobre `customers`), clave Kafka = `entity_id` (orden por entidad garantizado), contrato JSON limpio. Bien documentado en `debezium/README.md`.

**El tramo Kafka → estado es frágil.** El reintento del `DefaultErrorHandler` choca con la máquina de estados: un fallo transitorio tras la primera transición persistida deja al agregado en un estado del que la re-entrada por `RECEIVED` es ilegal, así que los 3 reintentos fallan igual y el registro va a la DLT y queda bloqueado. Es decir, el reintento a nivel Kafka es hoy contraproducente.

**El tramo estado → SAP no es idempotente en destino.** El `Idempotency-Key` es correcto en intención, pero OData V2 de S/4 no lo honra. Lo que se necesita es upsert idempotente (lookup + PATCH con `If-Match`, o deep insert en alta), no *exactly-once* en Kafka. `BusinessPartnerReadAdapter` ya existe y no lo usa nadie: es la pieza que falta para el lookup.

**Resiliencia del cliente: bien acotada y testada.** Retry solo en 5xx/transporte, 4xx sin retry, circuit breaker y timeouts configurables, con tests contra WireMock real que lo demuestran (`WebClientSapClientTest.java:68-145`). Matices: el CB está dentro del Retry, el fetch CSRF ignora el `SapAuthProvider`, y cualquier 403 se trata como CSRF.

### 3.3 Calidad, tests y disciplina SDD+TDD

**La base de la pirámide es sólida y limpia.** 245 `@Test` declarados (~81 % unitarios), 0 `testX`, 0 aserciones JUnit (todo AssertJ), 0 `@Disabled`/TODO/`Thread.sleep`, strict stubs con `lenient()` justificado, puertos dinámicos en WireMock. La suite es ejecutable sin Docker. `SyncAddressUseCaseTest#completesAgainstRealStateMachine` (repositorio en memoria con la máquina real) es TDD de dentro afuera hecho bien, y fue lo que cazó el bug `null → VALID`.

**El vértice no existe.** El único IT con Testcontainers solo comprueba que las URLs de los contenedores tienen el prefijo esperado. Los 11 "contract tests" de `it/` no importan ninguna clase de producción. No hay test de la familia OData, de `OAuth2TokenClient`, del circuit breaker abriéndose, del timeout real, de la DLT, de la concurrencia en `transition`, ni de la compensación parcial. El propio backlog lo reconoce: "seis defectos llegaron a producción local porque los tests unitarios mockean los puertos" (TEST-1).

**SDD anchor: la operativa está muy bien escrita y muy poco aplicada.** 2 specs de 13 features; el DoD exige que cada `AC-n` tenga un test que lo cite, y eso pasa en 5 tests de 245. La regla de transición ("se escribe el spec la primera vez que se toca la feature") es razonable para una PoC, pero implica que el DoD no puede aplicarse a la mayoría de cambios. Los tests que ya trazan los AC existen; solo les falta la cita en el Javadoc.

**Sin CI, "mvn verify en verde" es una afirmación no verificable por terceros.** Y precisamente por eso nadie notó en dos meses que 4 tests nunca corren y 11 corren dos veces.

### 3.4 Observabilidad

`SyncMetrics` tiene un diseño correcto (cardinalidad controlada, percentiles) y se invoca en todas las transiciones. Todo lo demás está prometido y no implementado: el timer de etapas nunca se llama, no hay métricas HTTP del cliente SAP (se usa `WebClient.builder()` estático en vez del `WebClient.Builder` de Boot), no hay binder de Resilience4j, no hay métrica de DLT ni gauge de entidades atascadas, los logs son texto plano sin MDC, no hay trazas ni collector ni Grafana. El BOM de OpenTelemetry está importado y ninguna clase lo usa. `TECH.md` y `OVERVIEW.md` afirman "logs estructurados y trazas distribuidas".

SLI/SLO que el equipo propondría como línea base (*estimación del equipo*, sin dato histórico): latencia extremo a extremo legacy→`SENT_SAP` p95 ≤ 30 s (requiere llevar `created_at` de la outbox en el mensaje); % mensajes en DLT ≤ 0,5 %/día; SAP 2xx ≥ 99 % y p95 ≤ 2 s; consumer lag ≤ 100 sostenido; entidades atascadas > N min = 0. Ninguno se puede medir hoy.

### 3.5 Seguridad

Lo estructural está bien: secretos por variables de entorno, `.gitignore` de `*.env` y `application-local.yml`, `local.env` deliberadamente commiteado solo con credenciales del compose local. Lo que falla es lo que el propio repo ya lista como brecha y no ha cerrado: APIs abiertas con PII, credencial real en el spike anidado, contraseña por defecto en YAML, stub de token silencioso. El recorrido de la PII (outbox → Kafka PLAINTEXT → Mongo → ES → REST) no tiene cifrado ni retención, y el borrado no alcanza el histórico.

### 3.6 Operación e infraestructura

`start-all.sh` es notablemente robusto (`set -euo pipefail`, esperas por healthcheck real con timeout, registro idempotente de conectores, inventario final). Pero no hay forma de desplegar esto fuera del portátil: sin Dockerfile, Helm, `mta.yaml` ni perfiles. Sin graceful shutdown, un mensaje en `SENDING_SAP` al parar la app queda atascado (y sin transición de salida, ver B1).

### 3.7 Documentación

Es de lo mejor del repo y a la vez donde más se nota la deuda: 27 ficheros bien organizados "por para qué sirve", separación honesta implementado/propuesto (`INTEGRATION-PATTERNS.md`, `MCP.md`, `MEJORAS-Y-PROPUESTAS.md`), un changelog de negocio real, y `docs/sdd/README.md` §6 documentando defectos con causa raíz. Pero hay **tres puntos de entrada** que compiten (`AGENTS.md`, `README.md`, `docs/sdd/README.md`), la regla del ancla copiada 4 veces, la máquina de estados descrita 8 veces (4 desactualizadas), y un `TECH.md` aspiracional sin marcar. El cierre del ancla exige tocar 6 sitios por feature (spec §9/§10, 2 changelogs, MySQL manual, glosario, índice) y **ya se incumple**: PR vacío en los specs, glosario con términos sin definir, conteos de tests sin actualizar. Previsión del equipo: el MySQL y el glosario serán lo primero que se abandone porque no rompen ningún build.

---

## 4. Fortalezas (lo que hay que conservar)

1. **Dominio puro con `records`** en los tres módulos y regla de dependencias respetada en `domain` (0 violaciones).
2. **Outbox transaccional real** con Debezium, CDC sobre la outbox y clave Kafka por entidad.
3. **`SyncStateMachine` única**, con `from` leído del almacén (el caso de uso no puede saltarse la secuencia) y cada transición persistida con timestamp, origen y hash, con métrica por estado.
4. **Cliente SAP centralizado** con retry/CB/timeouts/CSRF/OAuth2 y semántica 4xx/5xx explícita, probado contra HTTP real.
5. **Dos familias de adaptadores** conmutables por configuración sin tocar código.
6. **Dedupe por `payloadHash` y detección de "sin cambios reales"**, cubiertos por tests.
7. **`sap-api-models` como Published Language** con specs oficiales de SAP fuera del jar y generación reproducible.
8. **Disciplina de estilo en tests** (AssertJ, strict stubs, nombres descriptivos, fixtures) y smoke de contexto por app sin infraestructura.
9. **Documentación honesta**: lo propuesto separado de lo implementado; brechas con causa raíz; 1 enlace roto de 226.
10. **`AGENTS.md` como contrato operativo** siguiendo el estándar agents.md, con comandos copiables y reglas verificables. Es de los mejores que se ven en repos de este tamaño, con las tres correcciones de §2.

---

## 5. Propuestas punteras y de futuro

Cada propuesta indica qué aporta, por qué encaja en este PoC y qué se verificó. Las fuentes web se consultaron el 2026-09-10; se listan al final.

### 5.1 Alinear el stack con lo que ya existe (bajo coste, alto retorno)

| Propuesta | Qué aporta | Verificado |
|---|---|---|
| **Subir a Spring Boot 4.0.7 o 4.1.0 y Spring Cloud 2025.1.x** | 7 parches de seguridad/corrección sin aplicar; Boot 4.1 (junio 2026) añade mitigación SSRF en clientes HTTP y auto-configuración gRPC. Spring Cloud 2025.0.0 es incompatible con Boot 4.0.1+; hoy solo compila porque Boot está clavado en 4.0.0 | *fuente web*: blog de Spring |
| **Usar el starter oficial `spring-boot-starter-opentelemetry`** | El repo descartó "el starter OTel" porque el de terceros (`io.opentelemetry.instrumentation`) solo soporta Boot 3. Pero Boot 4 trae uno **oficial** del equipo de Spring que exporta Micrometer vía OTLP sin javaagent. Resuelve OBS-2 sin tocar el arranque | *fuente web*: blog de Spring y guías de Boot 4 |
| **Logs estructurados nativos de Boot** (`logging.structured.format.console=ecs`) | Boot 3.4+ los trae sin librerías extra; con el starter OTel se correla `traceId` automáticamente. Cierra OBS-4 y hace verdadera la frase de `TECH.md:124` | conocimiento del equipo, sin verificar en línea |
| **Decidir el transporte SAP**: SAP Cloud SDK OData VDM tipado **o** `RestClient` sin `sdk-core` | Hoy `sdk-core` es peso muerto y el SDK 5.x solo soporta oficialmente Boot 3. Opción A (VDM): elimina `WebClientSapClient`, `S4CsrfTokenProvider` y medio `common/sap`, y trae `$batch`, `If-Match` y destinos resueltos, pero con riesgo de compatibilidad Boot 4 hasta que SAP lo soporte. Opción B (`RestClient` de Spring 6/7): idiomático en MVC, elimina `webflux` y `.block()`, mantiene el código propio. **Recomendación del equipo: B a corto plazo, reevaluar A cuando SAP publique soporte de Boot 4** | *fuente web*: release notes SAP Cloud SDK |
| **JDK 25 como mínimo real** (`release 25` sin perfil) | Elimina el pinning de virtual threads sobre `synchronized` (JEP 491), habilita CDS/AOT de Boot 4 y quita la ambigüedad "23 LTS" | conocimiento del equipo |

### 5.2 Corregir el modelo de integración (lo que convierte el PoC en piloto)

- **Upsert idempotente en destino** (lookup con `BusinessPartnerReadAdapter` → deep insert en alta / PATCH con `If-Match` en modificación, `AddressID` persistido en la imagen Mongo). Es la única forma de que reintentos y re-sincronizaciones no dupliquen en SAP. Sustituye al `Idempotency-Key` como garantía.
- **Máquina de estados con lease y reapertura**: todo estado no terminal admite `RECEIVED` ante un evento nuevo; los estados intermedios llevan `leaseUntil`; un job marca como `ERROR` los expirados. Cierra OPS-1 y B1 de una vez, y da base al reprocesador de la DLT (OPS-2).
- **Spring Modulith para los eventos internos entre features** (*fuente web*: docs de Spring Modulith 2.0). Su *event publication registry* persiste cada publicación con ciclo de vida (pendiente/en curso/completada/fallida) y permite reenviar solo las fallidas tras un crash. Es exactamente el hueco de "saga/compensación entre features" (OPS-5), sin montar una saga externa. Además externaliza eventos a Kafka con *at-least-once*.
- **Debezium Outbox Event Router SMT oficial** (en lugar de la cadena manual de 4 SMT) y valorar **Debezium Server** para eliminar Kafka Connect/ZooKeeper en local. Debezium 3.6.x es la serie estable actual (*fuente web*).
- **Schema Registry + Avro/Protobuf** para el envelope de la outbox cuando haya más de un productor o consumidor; hoy el contrato es texto construido por concatenación SQL.
- **API OData V4 de Business Partner (`A_BusinessPartner` V4)** como sucesora de `API_BUSINESS_PARTNER` V2 (*fuente web*: SAP Community). La migración V2→V4 de SAP está en curso y no todos los objetos tienen V4; para el PoC, seguir en V2 para BP y usar V4 en las `CE_*` ya catalogadas. Diseñar el adaptador para que el cambio de versión sea local.
- **Cerrar el bucle con SAP Advanced Event Mesh**: consumir `BusinessPartner.Changed` (escenarios `SAP_COM_0492/0493`, autenticación por certificado) para confirmar que S/4 aplicó el cambio y detectar ediciones hechas en SAP. Es el Patrón 5 ya identificado; sin él, la imagen Mongo asume que SAP = legacy sin comprobarlo (*fuente web*: SAP Community).
- **Intermediario BTP**: si se materializa el Patrón 2, **CAP Java 5** ya soporta Spring Boot 4.1 (*fuente web*: capire), lo que permite compartir stack y `sap-api-models` entre plataforma e intermediario.

### 5.3 Preparar el sistema para agentes de IA (el futuro que el repo ya apunta)

- **Servidor MCP de solo lectura con Spring AI 1.1**: la propuesta de `MCP.md` es correcta y ahora está respaldada por el stack: Spring AI 1.1 trae servidor MCP con transporte *Streamable HTTP* (stateless o con sesión) y un módulo `mcp-server-security` para OAuth2 (*fuente web*). Los dos prerrequisitos que el propio documento declara (autenticación y ofuscación de PII) coinciden con B4 y A11 de este informe: **resolverlos vale por sí mismo**, con o sin MCP. Regla de este equipo: MCP siempre en modo solo lectura primero (skill `mcp-evaluation`).
- **`AGENTS.md` como contrato ejecutable**: convertir las reglas verificables en comprobaciones automáticas (ArchUnit para la regla de capas, un test que compare el número de `@Test` con el documentado, el comprobador de enlaces DX-6 en CI, `git diff` que exija spec tocado cuando cambia `src/main`). Un agente que lea instrucciones falsas (B8) hace daño con confianza; un agente cuyas reglas fallan en CI se autocorrige.
- **Registro SDD generado, no manual**: sustituir el `INSERT` a mano en MySQL por un fichero versionado (`feature_evento.csv` o el propio frontmatter YAML de cada spec) del que se genere la tabla y el índice de `docs/sdd/README.md`. Lo que no vive en git y no rompe el build no sobrevive (ya hay evidencia: solo existe el seed).
- **Alinear los dos repos de agentes**: `poc-sap-integration-java` tiene su propio contrato operativo (SDD+TDD, glosario, changelogs) y este repo tiene el suyo (routing, handoff, glosario canónico, reglas de oro). Hay solape (dos glosarios, dos definiciones de DoD) y complementariedad (el PoC no tiene clasificación de peticiones ni contrato de handoff; este repo no tiene ancla spec↔test). Un ADR conjunto que diga qué manda dónde evitaría que un agente reciba instrucciones contradictorias según el repo desde el que arranque.
- **ADRs retroactivos** (5, *estimación del equipo*: WebClient vs Cloud SDK, Mongo para estado, ES para histórico, dos familias de adaptadores, MySQL para el registro). Sin ellos, cada agente y cada persona nueva re-deriva las decisiones desde cero.

### 5.4 Guardarraíles de build y cadena de suministro

`maven-enforcer` (requireJavaVersion, bannedDependencies para Jackson duplicado, dependencyConvergence), `dependency:analyze` en `verify`, ArchUnit por módulo, JaCoCo `report`+`check` con umbral 100 % en `domain/feature/**`, Maven wrapper, `.gitattributes` (`* text=auto eol=lf`), CI con `mvn -B verify` sin Docker + job opcional con `-Ddocker.available=true`, Dependabot, SBOM CycloneDX, OWASP dependency-check, imágenes por digest, `spring-boot:build-image` como artefacto y perfiles `local/test/prod`.

---

## 6. Hoja de ruta sugerida (sin implementar nada; esfuerzo = *estimación del equipo*)

| Horizonte | Qué | Esfuerzo |
|---|---|---|
| **Hoy** | Rotar la credencial del spike (B5). Corregir las 3 falsedades de `AGENTS.md` (B8) y marcar `TECH.md` como visión (B9). Añadir `.gitattributes` antes del próximo commit (A25). Decidir OPS-3 y reemplazar `.DLT` en 21 sitios (A16) | S |
| **Semana 1-2** | Cerrar los sumideros de la máquina de estados + excepciones no reintentables + DELETE por `RECEIVED` (B1, B2), con tests sobre repositorio en memoria real. Arreglar la ejecución de la suite: renombrar `*IT`, fijar surefire, excluir `*ContractTest` de surefire (B7). CI mínimo. Subir Boot 4.0.x y Spring Cloud 2025.1.x o quitar el BOM (A12) | S–M |
| **Semana 3-6** | Upsert OData idempotente + contratos S/4 correctos (B3) con contract tests que llamen a los adaptadores reales (B6) y 1 e2e Testcontainers por dominio. Autenticación REST/actuator + quitar defaults de secretos (B4, A8). Línea base de observabilidad con el starter OTel oficial, logs ECS, MDC, métricas HTTP y de CB (A9) | M |
| **Mes 2-3** | Extraer `SyncPipeline<E>` a `common` y quitar Spring de `application` con ArchUnit (A4, A5). Spring Modulith para eventos por feature y compensación (A3). Lease/versión optimista en `sync_state` (A2). Dedupe contra el último `SENT_SAP` (A1). Graceful shutdown y presupuesto de reintentos (A10) | M–L |
| **Después** | Retención/TLS/RGPD (A11). Empaquetado y perfiles (A21). Supply chain (A22). ADRs, fuente única de la máquina de estados, registro SDD generado (§5.3). MCP solo lectura. Evaluar AEM y V4 | L |

---

## 7. Método, desviación y deuda declarada

**Cómo se hizo.** Esta sesión actuó como `orchestrator`: leyó el contrato de ambos repos, clasificó la petición, y lanzó en paralelo cinco subagentes independientes con handoffs que incluían objetivo, entrada (rutas), criterio de aceptación (informe con `fichero:línea`, tablas, veredicto con la escala del equipo, sección "datos verificados" / "no verificado") y condición de rechazo (prohibido escribir o ejecutar). Cada subagente devolvió un informe de 1.500-3.000 palabras; este documento los consolida, deduplica y prioriza. La verificación web la hizo el orquestador para no duplicar trabajo.

**Desviación respecto a lo pedido: ninguna en alcance, una en ubicación.** Se pidió analizar, revisar documentación, valorar la arquitectura y proponer mejoras punteras, sin desarrollar ni tocar el repo analizado: se ha cumplido (0 escrituras, 0 builds, 0 comandos git de escritura sobre `poc-sap-integration-java`). El fichero de salida se ha dejado en **este** repo (`agents/`), porque el repo analizado no podía tocarse y "el mismo repo" se ha interpretado como el repo de agentes desde el que se trabaja.

**Lo que este análisis NO verificó** (exigiría ejecutar o acceder a sistemas):
- Que los 240 tests estén realmente en verde con JDK 25; la cobertura real (JaCoCo no se ejecuta); qué versión de surefire resuelve Maven sin wrapper.
- El comportamiento de S/4 Public Cloud ante los payloads reales (numeración externa `BPEE`, `AddressID` vacío, `Idempotency-Key`).
- El nombre efectivo del topic DLT (lo afirma el propio repo) y el comportamiento del `DefaultErrorHandler` bajo Boot 4.
- Las métricas realmente expuestas en `/actuator/prometheus` y el pinning real de virtual threads.
- CVE concretos de las versiones declaradas (se recomienda Dependabot + dependency-check en lugar de afirmarlos).
- Que las credenciales del spike sigan siendo válidas (no se abrió el valor; se verificó solo su existencia y ubicación).
- El estándar agents.md no se consultó en línea; la comparación es con el conocimiento previo del equipo.

**Deuda de este informe.** No se ha generado diagrama (la skill `architecture-diagram` no se invocó porque el repo analizado ya tiene 16 diagramas Mermaid y un mapa funcional; lo que falta allí es un C4 de contexto, anotado en §2.3). No se han escrito ADRs ni entradas de glosario: eso sería tocar el repo analizado.

---

## 8. Fuentes web consultadas (2026-09-10)

- Spring Boot 4.1.0 disponible (junio 2026) y últimos parches 4.0.x: https://spring.io/blog/2026/06/10/spring-boot-4/ · https://www.infoq.com/news/2026/06/spring-boot-4-1/
- Spring Cloud 2025.1.x (Oakwood) y su compatibilidad con Boot 4.0.1+/4.1: https://spring.io/blog/2026/06/11/spring-cloud-2025-1-2-aka-oakwood-has-been-released/ · https://spring.io/blog/2025/11/25/spring-cloud-2025-1-0-aka-oakwood-has-been-released/
- Starter oficial de OpenTelemetry en Spring Boot 4: https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot/ · https://www.danvega.dev/blog/2025/12/23/opentelemetry-spring-boot
- SAP Cloud SDK for Java, release notes y soporte de Spring Boot 3: https://sap.github.io/cloud-sdk/docs/java/release-notes · https://sap.github.io/cloud-sdk/docs/java/guides/5.0-upgrade-steps
- CAP Java 5 con Spring Boot 4.1: https://cap.cloud.sap/docs/releases/2026/jun26
- Spring AI 1.1: servidor MCP con Streamable HTTP y módulo de seguridad: https://spring.io/blog/2025/09/09/spring-ai-1-1-0-M1-available-now/ · https://spring.io/blog/2025/09/16/spring-ai-mcp-intro-blog/ · https://docs.spring.io/spring-ai/reference/1.0/api/mcp/mcp-server-boot-starter-docs.html
- Business Partner OData V4 como sucesora de `API_BUSINESS_PARTNER`: https://community.sap.com/t5/enterprise-resource-planning-blog-posts-by-sap/odata-service-of-business-partner-in-sap-s-4hana-cloud/ba-p/14278613 · https://api.sap.com/products/SAPS4HANACloud/apis/ODATAV4
- SAP Integration Suite, Advanced Event Mesh con S/4HANA Cloud (SAP_COM_0492/0493): https://community.sap.com/t5/technology-blog-posts-by-sap/sap-s-4hana-cloud-integration-with-sap-integration-suite-advanced-event/ba-p/13738930 · https://community.sap.com/t5/integration-blog-posts/event-based-integrations-in-s-4hana-cloud-using-event-mesh-for-integration/ba-p/13952304
- Debezium 3.6.2.Final: https://debezium.io/blog/2026/09/01/debezium-3-6-2-final-released/
- Spring Modulith, event publication registry y externalización a Kafka: https://docs.spring.io/spring-modulith/reference/events.html · https://spring.io/blog/2023/09/22/simplified-event-externalization-with-spring-modulith/

---

# Parte II — Segunda ronda: cobertura completa de agentes y fases (2026-09-10)

> Ampliación del mismo día. Objetivo: pasar el repo analizado por **todos** los agentes y fases del circuito completo de este repo (Fase 0 → Fase 4 más transversales), no solo por los cinco de la ronda 1, y revisar de paso si a la propia metodología le falta algo. Mismas reglas: solo lectura sobre `poc-sap-integration-java`, sin builds, sin escrituras. Los roles que en su definición escriben código (`dev-implementer`, `integrator-systems`, `qa-tester`, `auditor-monitor`, `docs-writer`) se ejecutaron en **modo análisis** por mandato explícito de la persona.

---

## 9. Cobertura de agentes, fases y skills

| Fase (CLAUDE.md §3) | Agente / skill | Ronda 1 | Ronda 2 | Qué hizo aquí | Desviación respecto a su definición |
|---|---|---|---|---|---|
| Clasificación | `orchestrator` + `agent-routing` | ✓ | ✓ | Clasificó (Diseño y prototipado, `atencion`, completo acotado), lanzó 10 handoffs, consolidó | Ninguna |
| Fase 0 — vocabulario y puertas | `business-glossary`, `business-kpi/okr/roi` | — | ✓ (`solution-business`) | Comparó glosarios, aplicó la puerta KPI/OKR/ROI | Retrospectiva: el PoC ya existía; la puerta se aplicó a lo construido |
| Fase 1 — negocio | `solution-business` | — | ✓ | Reconstruyó el caso de negocio desde la documentación; propuso criterios de salida | Sin acceso a stakeholders: todo lo de negocio queda "pendiente de confirmar" |
| Fase 1b — auditoría de indicadores | `auditor-business` + `business-audit` | — | ✓ | Auditó 19 indicadores implícitos con las baterías K/O/R/I y cartera | Independencia garantizada: agente distinto al que reconstruyó el caso |
| Fase 2 — arquitectura | `solution-architect` | ✓ | — | Auditoría hexagonal, DDD, build | Nueve dominios recorridos en §10 (no se hizo explícito en ronda 1) |
| Fase 2 — integración | `integrator-systems` | ✓ | — | Contratos SAP, Kafka/CDC, resiliencia, alternativas | Su definición escribe código; aquí solo analizó |
| Fase 2b — planificación | `planner` | — | ✓ | Plan de 45 tareas, ruta crítica, hitos, 8 decisiones devueltas | Precondición "sin decisión no hay plan" aplicada: 8 propuestas devueltas al arquitecto |
| Fase 3 — implementación | `dev-implementer` | — | ✓ (modo revisión) | Revisión de código línea a línea de 119 ficheros, 43 hallazgos | **No escribió código** por mandato; actuó como revisor de PR (rol que este repo no tiene, ver §17) |
| Fase 4 — QA | `qa-tester` + `qa-*` | ✓ | — | Inventario, ancla SDD, huecos | **No ejecutó la suite** (prohibido generar `target/`): todo por lectura |
| Fase 4 — observabilidad | `auditor-monitor` | ✓ | — | Golden Signals, SLI/SLO, más seguridad e infra | No instrumentó nada; se le amplió a seguridad porque ningún agente la tiene (§17) |
| Incidencias | `incident-responder` | — | ✓ (post-mortem) | 6 defectos "resueltos" y 4 vivos; fingerprints; triage | Sin incidencia en curso: se usó como post-mortem, formato que el repo no tiene (§17) |
| Soporte | `support-answer` | — | N/A | No había ruegos ni preguntas | — |
| Transversal — documentación | `docs-writer` | ✓ (modo auditor) | — | Veracidad doc↔código, enlaces, glosario | No escribió en el repo analizado (prohibido); sí este log |
| Skills no usadas | `architecture-diagram`, `mcp-evaluation`, `qa-performance-tests`, `business-make-or-buy`, `domain-scope-spec` | — | — | — | `architecture-diagram`: el repo ya tiene 16 diagramas Mermaid (falta un C4, anotado). `mcp-evaluation`: la propuesta MCP de §5.3 no evalúa una herramienta concreta; debe pasar por ella antes de elegir servidor. `qa-performance-tests`: no hay tests de rendimiento que ejecutar. `make-or-buy`: aplicaría a la decisión BTP intermedio (CAP vs Integration Suite), devuelta como D-1/D-3 |

## 10. Los nueve dominios de arquitectura (recorrido explícito)

El agente `solution-architect` exige recorrer los nueve y decir cuáles se descartan. En la ronda 1 quedó implícito; aquí se hace explícito.

| Dominio | Cubierto | Hallazgos clave | Descartado / parcial y por qué |
|---|---|---|---|
| Frontend / cliente | N/A | — | No hay frontend; el panel web (UI-1..7) es propuesta. Cuando se aborde, SEC-1 y SEC-3 son prerrequisito |
| Backend / servicios | ✓ | A4, A5, A19, B1-B3, §13 | — |
| Datos y persistencia | ✓ | A1, A2, A11, A24, §13 (#4, #13, #21, #23, #24) | — |
| Integración y mensajería | ✓ | B3, A6, A7, A10, A16, R5-R14 | — |
| Red y entrega | parcial | Puertos en `0.0.0.0`, sin TLS interno, pool sin `maxIdleTime` | Sin CDN, balanceo ni límites de tasa: no aplican a un PoC local. Aplicarán al exponer la API (B4) |
| Infraestructura y despliegue | ✓ | A21, A22, compose, scripts | — |
| Sistemas / SO | parcial | Pinning de virtual threads con `synchronized`, sin flags JVM ni límites de recursos en compose, presupuesto Kafka vs `max.poll.interval.ms` | Sin medidas de CPU/memoria reales: exigiría ejecutar |
| Seguridad e identidad | ✓ | B4, B5, A8, A11 | Cubierto sin agente propietario (§17) |
| Observabilidad | ✓ | A9, §3.4, SLI/SLO | — |

**Quién va a operar cada cosa** (la restricción que más recomendaciones tumba): el repo no lo dice. No hay rol de operación definido, ni runbooks, ni on-call. Todo el análisis asume que opera el mismo equipo que desarrolla.

## 11. Fase 0 y Fase 1 — negocio (`solution-business`, retrospectivo)

**Vocabulario.** El glosario del PoC es técnico (Adapter, BAPI, OData…) y casi no define negocio: faltan mandato SEPA, datos maestros, PII, RGPD, KPI, línea base, MVP/piloto, saga. Contradicciones: DLT `.DLT` (glosario) vs `-dlt` (código); DLT (PoC) vs DLQ (este repo), mismo concepto; y la de fondo: el glosario de este repo define PoC como algo que "se tira y nunca se promociona", pero el repo analizado hace semver, releases en GitHub y criterios de re-despliegue. **Se comporta como producto sin haber decidido si es PoC, MVP o piloto.**

**Puerta de decisión (cerrada así):** aplicamos KPIs (4, revisión semanal; el primer entregable es medir la línea base, no fijar objetivos) y ROI en modo cualitativo (faltan los tres datos mínimos: volumen, coste unitario, coste de construir y operar); **no aplicamos OKRs** porque hay un solo equipo ejecutor, alcance impuesto por la migración y ningún ciclo de revisión. En su lugar, MoSCoW y criterios de salida.

**Caso de negocio reconstruido, en una tabla:**

| Bloque | Qué hay en el repo | Qué falta |
|---|---|---|
| Problema | "Sincronizar datos maestros legacy → S/4 sin carga manual ni duplicados" (`OVERVIEW.md:5-10`) | El dolor concreto (horas manuales, errores, pedidos parados) no está escrito en ningún sitio |
| Usuarios | Operador de integración, usuario SAP, equipo SAP (dependencia), agentes IA (futuro) | Volumen y frecuencia: sin dato |
| AS-IS | PoC Python que tampoco llegaba a SAP | **Cómo se cargan hoy los datos maestros en SAP: desconocido.** Sin AS-IS no hay línea base de negocio ni ROI |
| TO-BE | Pipeline en 7 pasos (captura → validación → imagen/histórico → envío por bloques → estado consultable → reintentos → DLT) | Validación contra SAP real |
| Requisitos funcionales | 22 identificados; 13 implementados, 3 Must para piloto sin hacer (atascados, reproceso DLT, auth), 6 Won't del PoC | La priorización MoSCoW es estimación del equipo; no existe en el repo |
| NFR | 6 ejes en `OVERVIEW.md` §9 | **Ni una cifra.** La pregunta correcta no es "cuántos nueves" sino "cuánto retraso máximo tolera SAP respecto al legacy" |
| KPIs | — | K1 tasa de sincronización correcta (fuente existe), K2 latencia e2e p95 (timestamps existen, timer no invocado), K3 atascados + DLT (KRI, sin alerta), **K4 intervenciones manuales en SAP/mes: el único de negocio y el único sin fuente en el sistema** |
| ROI | Una frase: "cada upsert contra S/4 se factura" sin cifra | 7 datos a pedir: horas/mes de carga manual y coste/hora; incidencias/mes por datos maestros y coste medio; precio por llamada facturable; personas-mes desde 2026-07-18; coste de operar Kafka+Debezium+Mongo+ES+2 apps; licencia del intermediario BTP; coste de salida |
| Compliance | Ofuscación de PII (SEC-3, `MCP.md` §5), solo para MCP y logs | PII en al menos seis almacenes (legacy, outbox, Kafka, Mongo, ES, DLT, logs); **nadie trata el borrado**; base legal, registro de actividades, DPA con SAP, región del tenant, plazos de conservación; mandatos SEPA con obligación de conservación (rulebook EPC, verificar) |

**Hallazgo principal de negocio:** no existe caso de negocio, KPIs, MoSCoW, MVP, stakeholders ni criterios de éxito. La palabra "éxito" aparece una vez en todo el repo, y es el `BUILD SUCCESS` del spike del SDK. **Un PoC sin criterio de salida no puede terminar**: se convierte en producto por acumulación.

**Criterios de salida propuestos** (*estimación del equipo*, a validar con negocio):

| # | Criterio de salida del PoC | Supuesto |
|---|---|---|
| S1 | Un cliente con sus cuatro bloques (contacto con contrato real) y un artículo llegan a un **tenant real de S/4 de test** y se ven en Fiori | El equipo SAP concede tenant y communication arrangement |
| S2 | Re-sincronización y "sin cambios reales" verificadas contra tenant real **contando llamadas**: una modificación = una llamada; un evento sin cambios = cero | Se pueden contar llamadas (métrica o log) |
| S3 | Línea base **medida** de K1, K2 y K3 durante una semana con Prometheus recogiendo | Negocio acepta volumen sintético representativo si no hay legacy real |
| S4 | ADR firmado: OData directo vs BTP intermedio, y eventos de stock decididos o descartados | El equipo SAP se pronuncia |
| S5 | Inventario de PII por almacén con decisión de retención/borrado, y autenticación antes de salir de localhost | Hay responsable de protección de datos |
| — | **Fecha límite.** Si S1 no se cumple en N semanas, el PoC se cierra con informe | Un PoC sin fecha de fin no es un PoC |

## 12. Fase 1b — dictamen de indicadores (`auditor-business`, independiente)

Profundidad estándar (PoC de equipo pequeño). 19 indicadores implícitos auditados: **Aptos 5 · Con reservas 7 · No aptos 5 · No evaluables 2.** Cero OKR y cero ROI formales: la batería de ROI no aplica.

| Dictamen | Indicador | Prueba que falla / por qué |
|---|---|---|
| ✅ Apto | Idempotencia por `payloadHash` | Fórmula inequívoca, fuente existe, test la cubre |
| ✅ Apto | Estado consultable (Mongo + ES) | Verificable por inspección |
| ✅ Apto | Métrica por transición | **El único indicador con fuente real e instrumentada**; reserva K1: nadie lo lee (no hay Prometheus) |
| ✅ Apto | Trazabilidad por transición | Probado contra la máquina real |
| ✅ Apto | AC-1..AC-6 de dirección | Formato dado/cuando/entonces, un test nominado por AC, los 6 existen. **Patrón a copiar** |
| ❌ No apto | "Cobertura 100 % unit en validaciones de `domain` + al menos una e2e" | K5 (JaCoCo sin ejecución), K2 (nunca medida), K4 (¿línea, rama? ¿qué es "validaciones"?), K7 Goodhart; la e2e no existe. Y todas las features figuran "✅ implementado" apelando a este criterio |
| ❌ No apto | "211 / 240 tests" | **Cuatro cifras** para lo mismo (211, 240, 192, 245); K1: ninguna decisión cambia con el número. Reconocimiento: `TESTING.md:11` es la **única cifra con fecha de medición en todo el repo** |
| ❌ No apto | Rendimiento: "virtual threads; throughput configurable" | Sin objetivo, plazo, línea base ni percentiles; ninguna propiedad de throughput existe en los YAML |
| ❌ No apto | Observabilidad: "métricas, logs estructurados y trazas" | Dos de tres fuentes no existen |
| ❌ No apto | KRI profundidad de la DLT | Sin umbral ni acción; la documentación manda al operador a consumir un topic que no existe (`.DLT`) |
| ⚠️ Reservas | Mismo pipeline; despliegue independiente; seguridad; TEST-4/TEST-6; afirmaciones del CHANGELOG | Sin test que lo cite; SEC-4 contradice el NFR; sin cifra/fecha; verificado una vez a mano y el fallo original ("sin aviso") sigue sin aviso |
| ❓ No evaluable | OBS-3; OPS-1 | Solo títulos; OPS-1 es un riesgo verificado en vivo sin indicador aunque la fuente (`sync_state`) ya existe |

**Cartera.** Punto ciego principal: el objetivo declarado del proyecto ("sincronizar hacia SAP") **no tiene ningún indicador de resultado** (% de eventos CDC que alcanzan `SENT_SAP` en < N min). Tampoco lo tienen "sin duplicar envíos", "datos a medias" ni "atascados". Y `SENT_SAP` contra WireMock (201 a todo) es indistinguible de "aceptado por SAP". Equilibrio: 19 de 19 técnicos; cero de negocio, cero de coste, cero adelantados operativos. Volumen: bastan **cinco** (`SENT_SAP`/recibidos por dominio, p95 CDC→`SENT_SAP`, DLT > 0, atascados > 10 min, AC con test = 100 %).

**Bloqueantes antes de aprobar el cuadro:** activar `jacoco:check` o retirar el "100 %"; un único origen para la cifra de tests; renombrar en `OVERVIEW.md` §9 la columna "Cómo se cumple" por "Estado"; decidir el sufijo DLT con umbral y acción; medir la línea base p95 antes de escribir ningún objetivo numérico. **Re-auditar** al cerrar TEST-6/OBS-1 (o en 30 días) y, obligatoriamente, **antes del primer tenant real**: en ese momento `SENT_SAP` cambia de significado y hay que re-basar.

## 13. Fase 3 en modo revisión — código línea a línea (`dev-implementer` como revisor)

**Veredicto del revisor: no aprobaría el PR tal cual.** 119 ficheros y 5.023 LOC leídos íntegros. 43 hallazgos: 10 bloqueantes, 24 de atención, 9 de observación. Condiciones para aprobar: corregir los 6 puntos funcionales, añadir un `@ControllerAdvice` común y un `try/catch → ERROR` en los dos orquestadores, y sustituir el JSON manual por `SapJsonMapper`.

**Bugs nuevos que la ronda 1 no había visto** (los ya conocidos B1-B3 se confirman):

| # | Sev. | Hallazgo | Evidencia | Por qué importa |
|---|---|---|---|---|
| C1 | bloqueante | El estado actual se resuelve por `OrderByTimestampDesc` con `Date` a milisegundos; las transiciones se escriben en ráfaga (`RECEIVED→FETCHING` en microsegundos) | `SyncStateMongoRepository.java:11`, `MongoSyncStateRepository.java:29`, `SyncCustomerUseCase.java:101-102` | Empates → `from` no determinista → `IllegalStateException` **intermitente** e irreproducible. Desempatar por `_id` o secuencia |
| C2 | bloqueante | Ninguna llamada a Mongo/ES/SAP tras `VALID` está protegida | `SyncCustomerUseCase.java:132-137`, `SyncArticleUseCase.java:94-100` | Una excepción de infraestructura deja la entidad colgada en `INDEXING`/`SENDING_SAP` sin pasar a `ERROR`; es el mecanismo por el que B1 se dispara |
| C3 | bloqueante | `catch (Exception e)` convierte todo, incluida `CallNotPermittedException` del circuit breaker, en `SapResponse(0, …)` | `WebClientSapClient.java:136-139` | El use case marca `SAP_ERROR` con normalidad y el listener no reintenta nunca; el log dice "error de transporte" con el circuito abierto. `SapServerException.status()` es package-private |
| C4 | bloqueante | `S4CsrfTokenProvider` devuelve `null` en 4xx; `cachedCookies` se lee fuera del lock sin `volatile`; el token no expira salvo 403 | `S4CsrfTokenProvider.java:77,83,118` | 403 → invalidar → segundo intento igual; visibilidad entre hilos no garantizada |
| C5 | bloqueante | Listeners: value `null` (tombstone) → IAE; `OperationType.valueOf("update")` → IAE; `entityId` ausente → IAE; ninguna marcada como no reintentable | `CustomerKafkaListener.java:45-52`, `ArticleKafkaListener.java:39-46`, `KafkaErrorHandlingConfig.java:18-24` | Reintentos inútiles con backoff y ruido; falta `addNotRetryableExceptions(IAE, ISE, JsonProcessingException)` y `if (value == null) return` |
| C6 | atención | Sin `@ControllerAdvice`/`ProblemDetail`, sin `@Valid`; IAE e ISE salen como 500; `@ExceptionHandler` duplicado en dos controllers | controllers de `bootstrap/web` | La API responde 500 a errores del cliente; el contrato de error no existe |
| C7 | atención | Por REST una `operation=DELETE` va a `syncUseCase`; por Kafka a `deleteUseCase` | `SyncCustomerController.java:36-40` vs `CustomerKafkaListener.java:53-57` | Mismo mensaje, distinto comportamiento según canal; el enrutado debería vivir en el `IngestionPort` que nadie implementa |
| C8 | atención | `SyncStateDoc.toTransition()` fija `from = null`; `transition()` ignora `t.from()` | `SyncStateDoc.java:38-40`, `MongoSyncStateRepository.java:36-37` | El campo `from` del record es decorativo en 17 llamadas; `history()` devuelve transiciones sin origen |
| C9 | atención | 20 `Instant.now()` sin `Clock`; 34 `@Value` con default y 0 `@ConfigurationProperties`; claves que solo existen en la anotación | varios | No testeable en el tiempo; doble fuente de verdad de configuración invisible para operaciones |
| C10 | atención | `n(String)` convierte `null → ""` en 10 copias mientras `SapJsonMapper` omite `null` a propósito | DTOs y adaptadores OData | Se envía `"Region": ""` a S/4, que valida dominios: contradicción de diseño |
| C11 | atención | Jackson 2 llega transitivo por `sdk-core` sin declararse; `JsonDiff.java:60` usa `node.fields()` (eliminado en Jackson 3); anotaciones `com.fasterxml` ignoradas si el objeto pasa por Jackson 3 de Boot; los listeners parsean Kafka con el mapper de SAP | `common/pom.xml`, 11 ficheros | La versión de databind la decide el Cloud SDK, no Boot |
| C12 | atención | `Status.valueOf` sin tolerancia; `PostgresArticleRepository:27` NPE si `status` es `NULL` (columna nullable); defaults distintos por módulo | `CustomerDocument.java:46`, `PostgresArticleRepository.java:27`, `ArticleDocument.java:31` | Un dato legacy sucio tumba el pipeline en vez de acabar en `INVALID` |
| C13 | atención | `createIndex = false` + 10 `@Field(type=…)` decorativos; id del histórico = `entityId-hash` | `CustomerHistoryDoc.java:17,23-55` | Mapping dinámico real; un reenvío tras `SAP_ERROR` **sobrescribe** el snapshot y su timestamp: el histórico pierde versiones |
| C14 | atención | `Retry` envuelve POST no idempotentes; método HTTP como `String` con `default → POST`; `uri(String)` expande `{}` | `WebClientSapClient.java:116-140,163-185` | Un typo se convierte en POST silencioso; un id con `{` rompe la URI |
| C15 | atención | `origin="rest"` fijo en Delete/Index/Validate aunque vengan de Kafka; `origin` mezcla canal y etapa | `DeleteCustomerUseCase.java:52`, `SyncAddressUseCase.java:72` | El historial guarda un origen falso para auditoría |
| C16 | atención | `@ConditionalOnMissingBean` en `@Configuration` de usuario; `localhost:8080` hardcodeado en el destino local del SDK; `catch (Exception) → Optional.empty()` sin log; 9 beans `@Service` sin llamadores | `SapIntegrationConfig.java:39,52,73`, `SapCloudSdkLocalDestinationConfig.java:30`, `BusinessPartnerReadAdapter.java:78,97` | Orden de evaluación no garantizado; puerto que no coincide con nada; errores de contrato invisibles; superficie muerta |

**Lo que está bien escrito y hay que copiar:** records con invariantes en constructor compacto y copia defensiva (`IngestionMessage.java:22-38`, `SyncStateTransition.java:26-42`, `BankingData.java:16-18`); inyección por constructor en el 100 % de los beans (0 `@Autowired` en campo, 0 `System.out`, 0 `printStackTrace`, 0 TODO); adaptadores excluyentes sin `@Primary`; `EnumMap`/`EnumSet` con `switch` exhaustivo; `SyncMetrics` con `computeIfAbsent` y cardinalidad acotada; escape de literales OData; triggers de outbox sin inyección (`STRING_ESCAPE`+`FOR JSON`, `jsonb_build_object`); comentarios que documentan bugs aprendidos; Kafka con `read_committed`.

**Métricas del revisor:** métodos > 40 líneas: 3 (`SyncCustomerUseCase.execute` 80, `SyncArticleUseCase.execute` 57, `fetchToken` 53) · clases > 250 líneas: 0 · `catch (Exception` 5 · `synchronized` 4 · `Instant.now()` 20 · `new ObjectMapper()` 3 · literal `"customer"` 23 · `n(String)` duplicada 10 · FQN inline 22 · `getBytes()` sin charset 3.

## 14. Post-mortem e incidencias (`incident-responder`)

**Contexto que cambia la lectura:** no hubo "migración a Spring Boot 4": el proyecto **nació** en Boot 4.0.0 desde el primer commit (`d1eba38`, 2026-07-18) con configuración y compose escritos al estilo Boot 3. La lección MET-2 (checklist de versión mayor) aplica también cuando se arranca en una versión mayor nueva. El repo no tiene carpeta de incidencias ni ADRs; el único rastro son `CHANGELOG.md`, `sdd/README.md` §6 y el mensaje del commit `fa9b4e9`.

**Los seis defectos "resueltos" el 2026-09-10:**

| # | Defecto | Causa raíz | Introducido → arreglado | Latencia | Por qué ningún test lo cazó |
|---|---|---|---|---|---|
| D1 | Nada llegaba a SAP | Compose ES 8.11 contra cliente ES 9 de Boot 4; el indexer no captura y el agregado queda en `INDEXING` | `d1eba38` → `fa9b4e9` | 53 días | Indexer mockeado; `createIndex=false` deja arrancar sin ES |
| D2 | Un cliente solo se sincronizaba una vez | `SENT_SAP → RECEIVED` modelado solo para el agregado, no para las líneas de feature | `d1eba38` (medio arreglado en `d9904cc`) → `fa9b4e9` | 54 días | Mocks del puerto de estado; **`ErrorStateRecoveryTest` consagraba el defecto** (`nextStates(SENT_SAP) containsExactly(RECEIVED)`) |
| D3 | Solo un cliente y un artículo | `init.js` creaba `{id:1} unique` y los documentos usan `_id` | `d1eba38` → `fa9b4e9` | 53 días | `init.js` no se ejecuta en ningún test; **enmascarado por D5** (las apps escribían en `test`) |
| D4 | Article no arrancaba | Conflicto de índice `sync_state` sin nombre vs `dom_ent_idx`, y `mvn -pl article -am spring-boot:run` sobre el POM padre | `d1eba38` / `1539899` → `fa9b4e9` | 53 / 5 días | Tests de contexto con `auto-index-creation=false` |
| D5 | Estado no consultable donde debía | Boot 4 movió `spring.data.mongodb.*` a `spring.mongodb.*`; la antigua se ignora en silencio; ambas apps caían a `mongodb://localhost/test` | `d1eba38` → `fa9b4e9` | 53 días | Ningún test afirmaba el nombre de la base; `69ba587` corrigió la propiedad de ES del mismo bloque y no la de Mongo |
| D6 | Los bloques de dirección/fiscal/contacto/banco no se enviaban | Los `Sync<Feature>UseCase` no registraban la entrada en `VALIDATING` y `VALID→SENDING_SAP` no existía; `d9904cc` arregló la máquina pero **no tocó los use cases** | `d1eba38` → `fa9b4e9` | 53 días | Mocks del puerto de estado en los 4 tests |

Ninguno vivió menos de 5 días; cinco vivieron 53. **Patrones:** (a) mocks de puertos que ocultan invariantes explica 4 de 6 y **los 4 vivos**; (e) ausencia de e2e explica 6 de 6 y 4 de 4; (d) máquina de estados modelada para un pipeline y extendida fila a fila explica D2, D6, V1 y V2; (b) versión mayor sin checklist explica D1 y D5; (c) infra local que contradice a la app explica D1, D3, D4.

**Conclusión del post-mortem: V1 y V2 son recurrencias del mismo fingerprint que D2 y D6.** Id estable propuesto: **`sync-state:reentrada-no-permitida`**, ya en su **tercera recurrencia** (`d1eba38→d9904cc`, `d9904cc→fa9b4e9`, y ahora). La causa raíz no es "faltaba la fila X" sino el diseño: cada use case codifica su estado de entrada en duro, el repositorio ignora ese `from` y recalcula el real, y la tabla global decide si el par casa. Cada camino nuevo (feature, delete, error, interrupción) descubre un par que falta y se manifiesta igual: `IllegalStateException` → 3 reintentos → DLT. Añadir dos filas más dejará el cuarto caso. Resolver de raíz: separar "abrir un ciclo nuevo" de "avanzar dentro del ciclo" (`beginCycle` que acepte cualquier estado no en vuelo, con obsolescencia para los en vuelo) y un **test de propiedad que recorra todos los estados × todas las entradas**. V3 recibe el id `idempotencia:dedupe-historico` (hermano de la decisión de `d9904cc`); V4, `sync-state:transicion-no-atomica`.

**Triage de los vivos:** V1 `bloqueante` (100 % de las entidades con un solo fallo previo; además neutraliza los reintentos Kafka) · V2 `bloqueante` (100 % de las bajas de customer; `fa9b4e9` verificó sync y re-sync, **no** el delete: CP-08 nunca se ejecutó en vivo) · V3 `atencion` (silencioso; sube a bloqueante si el dato revertido es fiscal o bancario) · V4 `observacion` (un solo consumidor; sube con más instancias). Mitigación de V1 sin código: insertar en `sync_state` una transición manual a `ERROR` (permitida hacia `RECEIVED`) con timestamp posterior; revertir borrando ese documento. V2 no tiene mitigación de datos: los DELETE quedan en la DLT hasta que exista reproceso (OPS-2).

**Rastro documental que pediría a `docs-writer`:** crear `docs/incidencias/2026-09-09-primer-arranque-e2e.md` con cronología absoluta, causa raíz, detección y "qué habría acortado la detección", más un índice de fingerprints; y **reformular** lo que declara resuelto algo cuya causa sigue viva: `CHANGELOG.md` ("tantas veces como cambie" → "mientras cada ciclo termine bien"), `sdd/README.md` §6 (mover el resto de D2 a Pendientes con el fingerprint; anotar que la solución de idempotencia introdujo V3; añadir el DELETE), OPS-1 (incluir `SAP_ERROR` y `COMMUNICATION_ERROR`), GUIA-PRUEBAS CP-08/CP-10 ("no verificado en vivo") y CP-11 (`.DLT` → `-dlt`), y la regla R-3 del spec de la máquina de estados (no se cumple para `SAP_ERROR` del agregado: cambiar la regla o el código).

## 15. Fase 2b — plan ejecutable (`planner`)

**Decisión de partida:** este informe §2 y §5.4. **No existe ADR.** El plan cubre solo correcciones y guardarraíles inequívocos y **devuelve al arquitecto ocho decisiones** (§15.4). Hereda las reglas del repo analizado: spec SDD + test en rojo antes de cualquier código; `qa-tester` escribe los tests en rojo y verifica lo de `dev-implementer` (nadie valida su propio trabajo). Estimación en **jornadas de esfuerzo con rango**, no fechas: convertirlas a calendario exige la capacidad del equipo.

**Esfuerzo total: 58-100 jornadas** (punto medio ≈ 79). **Ruta crítica: 15-27 jornadas** (`T03 → T06 → T07 → T08 → T16 → T20 → T30 → T31 → T32 → T45`). Con tres o más ejecutores en paralelo el plan cabe en la ruta crítica; con uno solo, tiende al esfuerzo total.

### 15.1 Tareas (DEV = `dev-implementer`, QA = `qa-tester`, AUD = `auditor-monitor`, INT = `integrator-systems`, DOC = `docs-writer`, ARQ = `solution-architect`; RC = ruta crítica)

| # | Tarea | Agente | Depende de | Criterio de aceptación verificable | Jornadas | RC |
|---|---|---|---|---|---|---|
| **A — Hoy** | | | | | | |
| T01 | Rotar el communication user de S/4 versionado en el spike (B5) | INT | — | La credencial antigua devuelve 401; la nueva solo vive en el gestor de secretos | 0,25-0,5 | |
| T02 | Sacar `sap-sdk-client/` del árbol y purgar su historial (B5) | DEV | T01 | `git check-ignore` o directorio ausente; `git log -S` de la contraseña vacío | 0,5-1 | |
| T03 | `.gitattributes` (`* text=auto eol=lf`) y renormalización en commit aislado (A25) | DEV | — | `git status` vacío tras renormalizar; diff ignorando CR vacío | 0,25-0,5 | ✓ |
| T04 | Corregir las 3 afirmaciones falsas de `AGENTS.md` (B8) y etiquetar o reescribir `TECH.md` §5/§8 (B9) | DOC | — | `grep "sin @ConditionalOnProperty" AGENTS.md` vacío; cada clase citada existe o lleva etiqueta "Visión" | 0,75-1,5 | |
| T05 | ADR del nombre del topic DLT (OPS-3) y alinear código y las 24 ocurrencias (A16) | ARQ + DOC | — | ADR existe; `grep "\.DLT"` = 0 o coincide con lo configurado; `KafkaErrorHandlingConfigTest#publishesToDocumentedDltTopic` verde | 0,75-1,25 | |
| **B — Máquina de estados y DELETE** | | | | | | |
| T06 | Specs `sincronizacion-cliente.md`, `baja-cliente.md`; ampliar `maquina-de-estados.md` con AC de reapertura y de baja (B1, B2) | DOC + ARQ | T03 | AC-n numerados con nombre de test y fila en el índice | 1-2 | ✓ |
| T07 | Tests en rojo: reapertura en máquina, `resyncsCustomerStuckInSapError` con repo en memoria real, `doesNotRetryIllegalState`, delete contra máquina real, `deleteIssuesHttpDelete` | QA | T06 | Los 5 existen, citan AC y **fallan** por la aserción esperada | 1-1,5 | ✓ |
| T08 | Reapertura en `SyncStateMachine`; `addNotRetryableExceptions(IllegalStateException)` (B1) | DEV | T07 | Tests de B1 en verde; QA confirma | 1-2 | ✓ |
| T09 | `DeleteCustomerUseCase` por `RECEIVED`; `BtpCustomerAdapter` con `SapClient.delete`; catálogo corregido (B2) | DEV | T08 | WireMock registra `DELETE`; tests de B2 verdes | 1-1,5 | |
| **C — Suite y CI** | | | | | | |
| T10 | Renombrar `SyncCustomerControllerIT`, fijar surefire, excluir `*ContractTest` de surefire en `it` (B7) | DEV | T03 | `mvn -B test` reporta 249 tests; ningún contract test corre dos veces | 0,5-1 | ✓ |
| T11 | Quitar BOM Spring Cloud 2025.0.0 y `spring-cloud-contract-wiremock`; subir Boot al último 4.0.x (A12) | DEV | T10 | `grep spring-cloud` vacío; `verify` verde | 0,5-1,5 | |
| T12 | `ci.yml`: `mvn -B verify` sin Docker + job opcional con Docker; JaCoCo `report`+`check` 100 % en `domain/**` (B7) | DEV | T10 | Primer run verde; comentar un test de dominio rompe `verify` | 1-2 | ✓ |
| T13 | Unificar la cifra de tests en README/TESTING/QUICK_START; `TestCountMatchesDocsTest` (A15) | QA + DOC | T12 | Misma cifra que CI; el test falla si se añade un `@Test` sin actualizarla | 0,5-1 | |
| **D — Upsert OData, contratos, contract tests reales, e2e** | | | | | | |
| T14 | Specs de dirección, contacto y bancarios con AC de upsert (lookup → deep insert / PATCH + `If-Match`, `AddressID`, `A_AddressEmailAddress`, BIC, `API_APAR_SEPA_MANDATE_SRV`) (B3) | INT + DOC | T06 | Cada entidad OData citada existe en `sap-api-models` | 1-2 | |
| T15 | `AbstractSapContractTest` arranca los adaptadores **reales** contra WireMock; contract tests de upsert en rojo (B6) | QA | T10, T14 | ≥ 5 ficheros de `it` importan `com.poc.sap.customer`; verifican método, path, `If-Match`, `x-csrf-token`, body | 1,5-2,5 | |
| T16 | Upsert de Business Partner: lookup → deep insert en alta / PATCH con `If-Match` en modificación; `AddressID` persistido (B3) | DEV | T08, T14, T15 | `updateIssuesPatchWithIfMatch`, `createIssuesDeepInsert`, `updateReusesPersistedAddressId` verdes; segundo evento sin POST de dirección | 3-6 | ✓ |
| T17 | Contacto, banco y mandatos con contrato real; adaptador para `MandateSapOutboundPort` (B3, A18) | DEV | T16 | Contract tests verdes; el body de contacto lleva email/teléfono | 2-3 | |
| T18 | CSRF con `SapAuthProvider`; 403 solo es CSRF si `x-csrf-token: Required`; CB fuera del Retry (A6) | DEV | T15 | `csrfFetchUsesConfiguredAuthProvider`, `plainForbiddenIsNotRetried`, `openCircuitIsNotRetried` verdes | 1-1,5 | |
| T19 | Documentar que `Idempotency-Key` no es garantía en OData V2; la garantía es el upsert (A7) | DOC | T16 | La sección cita el test de T16 | 0,25-0,5 | |
| T20 | e2e Testcontainers por dominio: outbox → Debezium → app → WireMock → Mongo; `resyncsAfterSapError`, `updateIssuesPatch` (B1, B3, B6) | QA | T12, T16, T09 | Verdes en el job Docker; sustituye al IT que solo mira prefijos de URL | 2-4 | ✓ |
| **E — Autenticación y secretos** | | | | | | |
| T21 | ADR del mecanismo de autenticación (resource server OAuth2 vs API key) — puerta (B4) | ARQ | — | ADR enlazado desde `AGENTS.md` | 0,5 | |
| T22 | Tests en rojo: 401 sin credencial en sync e history, actuator con detalles requiere auth, `failsFastWithoutCredentials` (B4, A8) | QA | T21, T10 | Los 4 fallan por la aserción esperada; spec `seguridad-api.md` | 0,5-1 | |
| T23 | Spring Security en ambas apps, puerto de gestión separado, `show-details: when-authorized` (B4) | DEV | T22 | `curl …/history` = 401; health sin detalles sin credencial | 2-3 | |
| T24 | Quitar defaults de secretos (`sa`, `trustServerCertificate`) y el stub silencioso de token; fallo al arrancar si falta config (A8) | DEV | T22 | `grep "password: sa"` vacío; smoke arranca solo con variables presentes | 0,5-1 | |
| **F — Observabilidad** | | | | | | |
| T25 | Spec `observabilidad.md` con SLI candidatos y AC medibles; sin objetivos hasta tener línea base (A9) | DOC + AUD | T03 | Cada SLI con fórmula, fuente y test | 0,5-1 | |
| T26 | `recordStageDuration` invocado, `WebClient.Builder` de Boot, binder Resilience4j, contador DLT, gauge atascados, tag por app, logs ECS nativos con MDC (A9) | DEV | T25, T10 | 4 familias nuevas en `/actuator/prometheus`; una línea de log del listener es JSON válido con `entity.id` | 2-3,5 | |
| T27 | Prometheus + Grafana en compose con dashboard versionado (p95 SAP, DLT, entidades por estado) y alertas | AUD | T26 | Dashboard y reglas versionados; `promtool test rules` verde; el panel muestra datos tras el e2e | 1,5-2,5 | |
| T28 | `server.shutdown=graceful`; presupuesto de reintentos < `max.poll.interval.ms` (A10) | DEV | T08 | `KafkaRetryBudgetTest#totalBudgetBelowMaxPollInterval` verde; parar en `SENDING_SAP` deja estado reabrible | 0,5-1 | |
| **G — Refactor `common`, ArchUnit, duplicación** | | | | | | |
| T29 | ArchUnit por módulo: `domain` sin Spring/Jackson/Mongo (activa); `application` sin Spring como `FreezingArchRule` (A4) | DEV | T12 | Regla `domain` 0 violaciones; la congelada no admite nuevas; CI la ejecuta | 1-1,5 | |
| T30 | `SyncPipeline<E>` genérico en `common`; migrar `Sync<Feature>UseCase`, indexers, image stores, `KafkaErrorHandlingConfig` (A5) | DEV | T29, T20 | Duplicación < 10 % (línea base ~25 %); suite y e2e verdes sin cambio de métricas | 3-5 | ✓ |
| T31 | `application` sin Spring/Micrometer/Jackson vía `MetricsPort` y `DiffPort`; wiring en `bootstrap` (A4) | DEV | T30 | Regla ArchUnit descongelada y verde; `grep org.springframework` en `application` = 0 | 2-3 | ✓ |
| T32 | Sustituir 27 `@Mock` de clases concretas por fakes de puerto; defaults de negocio al dominio; `S4ArticleAdapter` con Jackson; borrar `IngestionPort`, `sap.odata.enabled`, `sap.integration.mode` (A18-A20) | DEV | T31 | 0 `@Mock` sobre `SyncMetrics`/use cases/repos; `dependency:analyze` sin "Unused declared" | 1,5-3 | ✓ |
| T33 | Verificación independiente del refactor: suite, e2e, métricas antes/después, ArchUnit | QA + AUD | T32 | Informe con diff de métricas del e2e y 0 violaciones | 0,5-1 | |
| **H — Estado consistente y compensación** | | | | | | |
| T34 | Spec `idempotencia-y-dedupe.md`: dedupe contra el **último** `SENT_SAP`, versión optimista, lease en intermedios, imagen tras ACK de SAP (A1, A2) | DOC + ARQ | T06 | AC con nombre de test; sin decisión de stack nueva | 0,5-1 | |
| T35 | Dedupe contra el último `SENT_SAP`; imagen persistida solo tras `SENT_SAP` (A1) | DEV | T34 | `abaSequenceIsNotDeduplicated` (Mongo Testcontainers) e `imageIsPersistedOnlyAfterSapAck` verdes | 1-2 | |
| T36 | Versión optimista en `transition()`, `leaseUntil` en intermedios, job que marca `ERROR` los expirados (A2, OPS-1) | DEV | T34, T08 | `concurrentTransitionsOneWins` (2 hilos virtuales) y `expiredLeaseBecomesError` verdes | 2-3 | |
| T37 | Compensación entre features ante fallo parcial (A3) | — | **Bloqueada por D-2** | Sin criterio hasta la decisión | — | |
| **I — Supply chain y despliegue** | | | | | | |
| T38 | Maven wrapper; `maven-enforcer` (Java, Jackson 2 prohibido, convergencia); `dependency:analyze` en `verify`; README vs QUICK_START coherentes (A24) | DEV | T11 | `./mvnw -v` funciona; añadir `jackson-databind:2.x` rompe `verify` | 1-1,5 | |
| T39 | Dependabot, SBOM CycloneDX, OWASP dependency-check en CI; imágenes por digest (A22) | DEV | T12 | `dependabot.yml` existe; `bom.json` como artefacto; nº de `@sha256:` = nº de `image:` | 1-1,5 | |
| T40 | `spring-boot:build-image` en CI; perfiles `local/test/prod`; corregir `launch.json` (A21) | DEV | T12, T24 | Imagen OCI publicada; `application-prod.yml` sin secretos; smoke con perfil `test` | 1,5-2,5 | |
| T41 | TTL en `sync_state`, ILM en ES, la baja purga histórico y estado; Mongo con auth y Kafka SASL_SSL en compose (A11) | DEV + INT | T36, T23 | `purgesHistoryAndState` verde; índice TTL visible; `grep PLAINTEXT` vacío | 2-4 | |
| **J — Documentación** | | | | | | |
| T42 | 5 ADRs retroactivos de **lo que hay hoy** (WebClient propio, Mongo estado, ES histórico, dos familias, MySQL registro) sin prejuzgar D-1 | ARQ + DOC | — | `docs/adr/ADR-0001…0005.md`; enlazados; 0 enlaces rotos | 1,5-2,5 | |
| T43 | Fuente única de la máquina de estados y de la regla del ancla; glosario saneado; referencias a `SPEC.md` fuera de los poms (A17, A23) | DOC | T08, T04 | Las 4 copias enlazan; `grep SPEC.md */pom.xml` vacío; comprobador de enlaces en CI | 1-2 | |
| T44 | Registro SDD generado desde frontmatter YAML (sustituye el `INSERT` manual); citar AC en los tests que ya los trazan; corregir 3 nombres de test inexistentes (A14) | DEV + QA | T19, T13 | `sdd-registry.sh --check` falla en CI si difiere; ≥ 20 tests citan `AC-` (línea base 2) | 1,5-2,5 | |
| T45 | Auditoría de cierre: B1-B9 y A1-A25 cerrado/abierto con evidencia; dictamen de seguridad | AUD | T33, T41, T44 | 9/9 bloqueantes cerrados con evidencia; 0 bloqueantes nuevos | 1 | ✓ |

### 15.2 Paralelismo e hitos

Día 1: T01, T03, T04, T05, T21 a la vez. Tras T03: bloques B, C, F (spec) y J en paralelo. Tras T10: E y arranque de D. Tras T08: H y T28 en paralelo con D. Tras T12: I y T29. **G es el único bloque que espera al e2e (T20)**: refactorizar sin red de seguridad extremo a extremo es el mayor riesgo del plan.

| Hito | Entregable verificable | Tras |
|---|---|---|
| H0 Higiene | Credencial rotada, árbol limpio, `AGENTS.md` sin la frase falsa, ADR DLT, 0 `.DLT` incoherentes | T05 |
| H1a Re-sync en caso de uso | `resyncsCustomerStuckInSapError` verde con repositorio real en memoria | T08 |
| H2 CI verde sin Docker | 249 tests, JaCoCo 100 % en `domain/**` | T12 |
| H3 PATCH real en WireMock | `updateIssuesPatchWithIfMatch` verde desde el adaptador real; sin POST de dirección en el segundo evento | T16 |
| H1 Re-sync extremo a extremo | `CustomerEndToEndIT#resyncsAfterSapError` verde en el job Docker | T20 |
| H4 API cerrada | `/history` = 401 sin credencial; actuator sin detalles | T23 |
| H5 Dashboard operativo | Grafana con p95 SAP y profundidad DLT alimentado por el e2e; alerta DLT > 0 probada | T27 |
| H6 Arquitectura vigilada | ArchUnit verde con `application` sin Spring; duplicación < 10 % | T32 |
| H7 Cierre auditado | 9/9 bloqueantes cerrados con evidencia | T45 |

### 15.3 Supuestos y riesgos del plan (resumen)

Supuestos etiquetados: capacidad del equipo desconocida [pendiente]; ≥ 3 ejecutores en paralelo [supuesto]; tenant S/4 de test accesible [pendiente]; Docker en CI [pendiente]; el equipo acepta Boot 4.0.x último parche [supuesto]; la suite está en verde con JDK 25 [dato del informe, no ejecutado]; `sap-sdk-client` sin remoto compartido con terceros [pendiente]. Riesgos: conocimiento concentrado (mitigación: los specs se escriben antes de tocar código y son el traspaso); dependencia del equipo SAP (escalar al segundo día); T16/T20/T30/T41 con rango > 2× (partir T16 en alta/modificación; reducir T20 a un dominio; congelar G con `FreezingArchRule`); documentación abandonada por no romper el build (hacer que `sdd-registry.sh --check` y el comprobador de enlaces fallen el pipeline).

### 15.4 Decisiones devueltas al arquitecto (sin ADR no se planifican)

| ID | Decisión abierta | Condiciona | `devuelve-si` |
|---|---|---|---|
| D-1 | Transporte SAP: Cloud SDK VDM vs `RestClient` sin `sdk-core` | T16-T18 (riesgo de retrabajo), retirada de A13 y `webflux` | "no hay ADR que fije el cliente HTTP hacia S/4 antes de empezar T16" |
| D-2 | Spring Modulith para eventos entre features y compensación | **Bloquea T37** | "la compensación se implementa sin ADR que elija Modulith, saga propia o ninguna" |
| D-3 | Debezium Server vs Kafka Connect; Event Router SMT oficial | Compose local y T20 | "se toca la cadena de SMT sin ADR" |
| D-4 | OData V4 ahora o después | T16 se diseña para que el cambio sea local | "T16 introduce entidades V4 sin ADR" |
| D-5 | MCP de solo lectura con Spring AI | Sus prerrequisitos (B4, A11) sí están en el plan | "aparece un módulo `mcp-*` sin ADR ni `mcp-evaluation`" |
| D-6 | Boot 4.1 / Spring Cloud 2025.1 y JDK 25 como mínimo | T11 se limita a 4.0.x | "T11 cambia la major/minor de Boot o `maven.compiler.release`" |
| D-7 | Trazas con el starter oficial de OpenTelemetry | T26 cubre métricas y logs nativos | "T26 añade dependencias OTel sin ADR" |
| D-8 | Schema Registry + Avro/Protobuf para la outbox | — | "se cambia el contrato JSON de la outbox sin ADR" |

**Lo que el planner no sabe y necesita:** capacidad del equipo; acceso a tenant S/4 y quién rota la credencial; runner de CI con Docker; si el spike tiene remoto compartido; estado real de la suite con JDK 25; y las ocho decisiones anteriores.

## 16. Hallazgos nuevos de la ronda 2 (consolidados)

Se añaden a la tabla de §2 sin renumerar la anterior:

| # | Sev. | Hallazgo | Origen |
|---|---|---|---|
| B10 | bloqueante | No existe caso de negocio, criterio de éxito ni fecha de fin del PoC; el repo se comporta como producto (semver, releases) sin decisión PoC/MVP/piloto | §11 |
| B11 | bloqueante | `currentState` por `timestamp` en milisegundos sin desempate: transiciones en ráfaga empatan y producen `IllegalStateException` intermitente | §13 C1 |
| B12 | bloqueante | Tramo posterior a `VALID` sin captura de excepciones: cualquier fallo de Mongo/ES/HTTP deja la entidad en estado intermedio sin pasar a `ERROR` (es el mecanismo de B1) | §13 C2 |
| B13 | bloqueante | `catch (Exception)` en el cliente SAP traga `CallNotPermittedException`: el circuito abierto se registra como `SAP_ERROR` sin reintento y sin señal | §13 C3 |
| B14 | bloqueante | V1 y V2 son la **tercera recurrencia** del fingerprint `sync-state:reentrada-no-permitida`; arreglar fila a fila garantiza la cuarta | §14 |
| A26 | atención | El criterio global "100 % cobertura + una e2e" es no apto (sin fuente, sin línea base, sin fórmula) y todas las features se declaran implementadas apelando a él | §12 |
| A27 | atención | 19 de 19 indicadores implícitos son técnicos; el objetivo del proyecto no tiene indicador de resultado; `SENT_SAP` contra WireMock es indistinguible de aceptado por SAP | §12 |
| A28 | atención | Listeners con excepciones no transitorias (tombstone, `valueOf`, `entityId` vacío) reintentadas 3 veces | §13 C5 |
| A29 | atención | Sin `@ControllerAdvice`/`ProblemDetail`/`@Valid`; enrutado de DELETE divergente entre REST y Kafka | §13 C6, C7 |
| A30 | atención | CSRF devuelve `null` en 4xx y lee la caché sin sincronización; token sin expiración salvo 403 | §13 C4 |
| A31 | atención | Id del histórico ES = `entityId-hash`: un reenvío tras `SAP_ERROR` sobrescribe versiones; `@Field` decorativos con `createIndex=false` | §13 C13 |
| A32 | atención | Jackson 2 transitivo sin declarar, `node.fields()` eliminado en Jackson 3, listeners parseando Kafka con el mapper de SAP | §13 C11 |
| A33 | atención | `Status.valueOf` sin tolerancia y NPE con `status` nulo en article: datos legacy sucios tumban el pipeline en vez de acabar en `INVALID` | §13 C12 |
| A34 | atención | `origin` falso ("rest") en transiciones que vienen de Kafka; campo `from` decorativo | §13 C8, C15 |
| A35 | atención | PII en al menos seis almacenes y **nadie trata el borrado**; base legal, DPA con SAP y conservación de mandatos SEPA sin definir | §11 |
| A36 | atención | No hay carpeta de incidencias ni post-mortem; el CHANGELOG y `sdd/README.md` §6 declaran resuelto lo que sigue vivo | §14 |
| O | observación | 20 `Instant.now()` sin `Clock`; 34 `@Value` sin `@ConfigurationProperties`; `n(String)` ×10 contradice `NON_NULL`; método HTTP como `String` con `default → POST`; `localhost:8080` hardcodeado; 9 beans sin llamadores | §13 |

## 17. Lo que echo en falta en la metodología de este repo (`agents`)

La ronda 2 sirvió también para probar el método contra un caso real. Lo que funcionó: la clasificación previa, el contrato de handoff (objetivo, entrada, aceptación, `devuelve-si`) como plantilla de prompt, la regla "nadie valida su propio trabajo" (el `auditor-business` cazó lo que el `solution-business` anticipó que caería), la precondición del `planner` ("sin decisión no hay plan"), y la separación implementado/propuesto exigida en todos los informes. Lo que falta, con evidencia:

| # | Hueco | Evidencia | Propuesta |
|---|---|---|---|
| M1 | **No hay circuito de auditoría de un sistema existente.** La taxonomía de tipos es la de Jira y no tiene "Auditoría / Evaluación"; esta petición se clasificó como Diseño y prototipado por falta de encaje | `skills/agent-routing/SKILL.md:41-48` | Nuevo tipo o alias `Auditoría` con circuito **fan-out en solo lectura** de los roles (exactamente lo ejecutado aquí), con consolidación por el orquestador y escala común de severidad |
| M2 | **Ningún agente es dueño de Seguridad e identidad** (dominio 8 de 9) ni de datos y privacidad (PII, RGPD, retención). Hubo que ampliar `auditor-monitor` por prompt | Nueve dominios del `solution-architect`; `agents/` sin rol de seguridad | Agente `auditor-security` (threat model ligero, secretos, dependencias/SBOM, PII y RGPD) + skill `security-review` con checklist; que la puerta de clasificación lo dispare cuando haya PII o exposición externa |
| M3 | **No hay rol de revisión de código en solo lectura.** `dev-implementer` escribe; `qa-tester` prueba; nadie revisa calidad. La regla 10 ("nadie valida su propio trabajo") queda coja para el código | `agents/dev-implementer.md` (edit: true); ningún `code-reviewer` | Agente `code-reviewer` (o modo `revisión` de `dev-implementer` con `edit: deny`) con el formato usado en §13: hallazgos por severidad, "lo que está bien", métricas del revisor |
| M4 | **No hay rol de plataforma/release.** CI/CD, empaquetado, IaC, supply chain (SBOM, Dependabot, digests) no son de nadie: `dev-implementer` tiene prohibido tocar CI/Docker y `solution-architect` no implementa | `agents/dev-implementer.md` §4; hallazgos A21, A22, B7 sin agente natural | Agente `platform-engineer` (o ampliar `integrator-systems`) dueño de CI, contenedores, perfiles y cadena de suministro |
| M5 | **No hay skill ni plantilla de post-mortem.** `incident-responder` pide "dejar rastro" al cerrar pero no define formato; la cronología absoluta, el fingerprint recurrente y "qué habría acortado la detección" se improvisaron | `agents/incident-responder.md` "Al cerrar" | Skill `incident-postmortem` con la estructura de §14 y una carpeta `docs/incidencias/` con índice de fingerprints como convención de todo proyecto |
| M6 | **Los agentes que escriben no tienen variante de solo lectura.** En una auditoría hay que prohibírselo por prompt cada vez | `edit: true` en auditor-monitor, dev-implementer, docs-writer, integrator-systems, qa-tester | Campo `mode: readonly` en frontmatter o variantes `*-review`; el circuito de auditoría (M1) las usaría por defecto |
| M7 | **El contrato de handoff no tiene plantilla reutilizable**; se aplicó de memoria en 10 prompts | `skills/agent-routing/SKILL.md` §3 describe los campos pero no hay fichero para pegar | Skill `handoff-template` con los campos listos y las reglas de solo lectura, scratchpad y citas `fichero:línea` |
| M8 | **Fan-out sin control de cuota.** Cinco subagentes pesados en paralelo agotaron el límite de sesión (HTTP 429) y se perdió íntegra la primera ejecución de la ronda 2 | Esta sesión, 2026-09-10 | En `docs/entorno.md`: lanzar en oleadas de 2-3, persistir resultados parciales en scratchpad, y considerar modelo más ligero para `planner`/`docs-writer` (regla ya enunciada: "el coste se controla con el esfuerzo antes que con el modelo") |
| M9 | **Dos repos, dos contratos.** El PoC tiene su propio `AGENTS.md` (SDD+TDD, glosario, changelogs) y este repo el suyo (routing, handoff, glosario canónico). Solapan (dos glosarios, dos DoD, DLT vs DLQ) y se complementan (el PoC no clasifica ni tiene handoff; este repo no tiene ancla spec↔test) | §11; `AGENTS.md` de ambos | ADR conjunto de precedencia: qué manda cuando un agente arranca desde cada repo; glosario del PoC enlaza al canónico y añade solo lo de dominio |
| M10 | **La skill `mcp-evaluation` no se disparó** aunque el repo analizado propone un servidor MCP y este informe lo recomienda | §5.3; `docs/entorno.md` "siempre evaluar antes de recomendar" | Que el orquestador la exija cuando una propuesta nombre MCP (puerta, no memoria) |

**Hallazgos sobre el propio repo `agents` (verificados 2026-09-10):**

- **Ficheros núcleo sin versionar.** Desde el último commit (`a80711b`, 2026-08-23) están sin trackear: `AGENTS.md`, `CLAUDE.md`, `agents/orchestrator.md`, `agents/planner.md`, `agents/incident-responder.md`, `agents/support-answer.md`, `skills/agent-routing/`, `docs/flujos.md`, `docs/entorno.md`, además de 27 ficheros modificados. Diecisiete días del núcleo del método viven fuera de git.
- **Cuatro agentes sin `name:` en el frontmatter**: `auditor-monitor`, `dev-implementer`, `docs-writer`, `integrator-systems` (CLAUDE.md §8 exige frontmatter con rol).
- **CLAUDE.md §8 dice que solo dos agentes usan CRLF**; en el árbol de trabajo tienen CRLF 8 agentes, 14 skills, 3 specs y `docs/glosario.md`. Es el mismo fenómeno WSL del repo analizado y la misma solución: `.gitattributes`.
- `TalkAgents.md` se declara borrable y sigue en la raíz.
- `docs/entorno.md` fija `claude-opus-5` por defecto y no dice nada de cuotas ni de fan-out (M8).

---

## 18. Desviación y método (actualizado tras la ronda 2)

**Desviación respecto a lo pedido: ninguna en alcance.** Se pidió pasar el repo por todos los agentes y fases, incluir un desarrollador que revise el código sin tocarlo, señalar lo que falta y añadirlo a este resumen, sin modificar el repo analizado. Todo hecho: 10 handoffs en dos rondas, 0 escrituras y 0 builds sobre `poc-sap-integration-java` (comprobado con `git status` antes y después: el árbol sigue con los mismos 172 ficheros de ruido CRLF y el mismo directorio no trackeado `sap-sdk-client/`).

**Incidencia del método:** la primera ejecución de la ronda 2 (cinco subagentes en paralelo) se perdió por límite de cuota (HTTP 429) antes de producir nada; se relanzó íntegra tras el reinicio del límite. Coste: una ronda de arranque; aprendizaje registrado como M8.

**Bucles:** ninguno superó una iteración. **Deuda declarada de este informe:** no se ejecutó la suite ni JaCoCo (prohibido); no se generó diagrama C4; no se consultó en línea el estándar agents.md; el plan de §15 no está convertido a fechas por falta de capacidad del equipo; las ocho decisiones de §15.4 siguen abiertas y son de la persona, no del equipo de agentes.

---

*Generado por el equipo de agentes de `/mnt/a/Documentos/Code/agents` el 2026-09-10, en dos rondas el mismo día. Informe de análisis; no contiene ni implica cambios en el repositorio analizado.*
