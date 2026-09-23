# Revisión de código — poc-sap-integration-java, rango 00bbbae^..ffd86e7

- **Rol:** `dev-implementer` en modo revisión (solo lectura). Fecha: 2026-09-18.
- **Handoff:** `poc-sap:auditoria-2:revision-codigo-nuevo`.
- **Rango verificado:** `git diff --stat 00bbbae^..HEAD -- common/src/main customer/src/main article/src/main` → 79 ficheros de producción, +2.447 / −1.131 líneas. El rango resuelve; los ficheros de prioridad 1 se abrieron todos.
- **No se ejecutó nada** (ni Maven, ni Docker, ni tests). Los juicios sobre tests son de lectura de nombres y de existencia de ficheros, no de ejecución.

Escala de severidad: [`skills/agent-routing`](../../../../../../a/Documentos/Code/agents/skills/agent-routing/SKILL.md) §1 — `bloqueante` = funcionalidad crítica o pérdida de datos; `atencion` = degradación que acabará comprometiendo el servicio; `observacion` = deuda o ruido.

---

## 1. Hallazgos nuevos

### N1 — `bloqueante` — La línea de feature queda colgada en `SENDING_SAP` y no se avisa del fallo parcial

**Dónde:** `common/src/main/java/com/poc/sap/common/application/FeatureSyncPipeline.java:60-64`; efecto en `customer/src/main/java/com/poc/sap/customer/application/general/SyncCustomerUseCase.java:179-193`.

**Qué pasa.** `sync()` hace `advance(VALID→SENDING_SAP)` (línea 60), llama `sapPort.send(...)` (línea 61) y sólo entonces avanza al terminal (línea 63). No hay `try/catch`. `send()` puede lanzar: `SapCircuitOpenException` (`RestClientSapClient.java:159`), `IllegalStateException` del CSRF (`S4CsrfTokenProvider.java:93`), `IllegalStateException` de `requireCreditor()` (`SepaMandateODataAdapter.java:102`), o cualquier fallo de red no convertido.

Escenario concreto: cliente `C1`, circuito SAP abierto. Entrada: mensaje Kafka de `C1` con las cuatro features.
1. Agregado llega a `SENDING_SAP`, entra en `sendFeatures` (`SyncCustomerUseCase.java:179`).
2. `ADDRESS` ejecuta: su línea `C1:ADDRESS` pasa a `SENDING_SAP` y `sapPort.send` lanza `SapCircuitOpenException`.
3. La excepción sube: el bucle `for` de la línea 179 **aborta**. `FISCAL`, `CONTACT` y `BANKING` no se ejecutan y no dejan ninguna fila. `results` queda vacío, así que **`notifications.partialFailure` (línea 192) nunca se llama**.
4. El `catch (RuntimeException)` de la línea 159 marca el **agregado** en `ERROR`; la línea `C1:ADDRESS` se queda en `SENDING_SAP` **para siempre**.

Estado incorrecto resultante: `GET /customers/C1/state` (`CustomerStateController.java:30`) devuelve `ADDRESS = SENDING_SAP` indefinidamente — "enviando a SAP" cuando no se envió nada — y las otras tres features no aparecen en absoluto. Es exactamente el caso que ADR-0010 dice cubrir ("el fallo parcial se marca, se localiza por parte y se avisa"): con retorno `SAP_ERROR` funciona; con excepción, ni se marca ni se avisa. Y es el caso **más probable**, porque el circuito abierto es el modo de fallo esperado de SAP degradado.

**Por qué ningún test lo caza.** Existe `common/src/test/java/com/poc/sap/common/application/FeatureSyncPipelineTest.java`, pero ningún test de `common/src/test` ni de `customer/src/test` menciona `SapCircuitOpenException` (sólo lo hace `common/src/test/.../sap/RestClientSapClientTest.java`, que prueba el cliente HTTP aislado, no el pipeline). El test que debería existir es `FeatureSyncPipelineTest#lineaQuedaEnSapErrorSiElPuertoLanza`, con un `SapOutboundPort` falso que lanza.

**Test primero (TDD).** `FeatureSyncPipelineTest`: puerto SAP que lanza `SapCircuitOpenException` → afirmar que la línea `entityId:FEATURE` termina en `SAP_ERROR` (o `COMMUNICATION_ERROR`) y que la excepción se sigue propagando. Después, `SyncCustomerUseCaseTest#avisaAunqueUnaFeatureLance`: afirmar que `partialFailure` se invoca con las cuatro features y su resultado.

**Arreglo en una frase.** Envolver `sapPort.send` en `try/catch RuntimeException`, registrar la transición a `SAP_ERROR`/`COMMUNICATION_ERROR` en la línea de feature y relanzar; y en `sendFeatures`, capturar por feature para que el bucle complete y `partialFailure` siempre se dispare.

**Qué se aprende.** Una máquina de estados que sólo avanza por el camino feliz no es una máquina de estados: es un log. Todo estado "en vuelo" necesita un dueño que lo cierre en el `finally`, no en el `if`.

---

### N2 — `bloqueante` — Una colisión de concurrencia acaba en DLT porque `IllegalStateException` no es reintentable

**Dónde:** `common/src/main/java/com/poc/sap/common/kafka/KafkaErrorHandlingConfig.java:27-30` + `common/src/main/java/com/poc/sap/common/domain/SyncStateMachine.java:110-113` + `common/src/main/java/com/poc/sap/common/adapters/persistence/MongoSyncStateRepository.java:61-66`.

**Qué pasa.** `transition()` recalcula `from` leyendo la cabecera (línea 63) y valida con `machine.advance(from, t.to())`. Si otra instancia movió la cabecera entretanto, `advance` lanza `IllegalStateException` (`SyncStateMachine.java:112`), que está declarada **no reintentable** (`KafkaErrorHandlingConfig.java:28`): el mensaje va **directo a la DLT, sin un solo reintento**.

Escenario concreto: dos eventos de `C1` en ráfaga, dos instancias del consumidor (particiones distintas por rebalanceo, o REST concurriendo con Kafka).
- Instancia A: `beginCycle` → `RECEIVED` (seq 1) → `advance(RECEIVED→FETCHING)` (seq 2) → está en el `fetch` contra el legacy.
- Instancia B: `beginCycle` → `RECEIVED` (seq 3). No hay colisión de `seq` porque B leyó la cabecera *después* de que A escribiera.
- A vuelve del fetch y hace `advance(→VALIDATING)`: la cabecera real ahora es `RECEIVED`, y `RECEIVED→VALIDATING` **no está en la tabla** (`SyncStateMachine.java:55`: `RECEIVED → {FETCHING, ERROR}`) → `IllegalStateException` → DLT.

Estado incorrecto: el evento de A se pierde del flujo normal (queda en `outbox.CUSTOMER-dlt`) por un fallo puramente transitorio, y `C1` queda con una línea de estado con dos ciclos entrelazados. El comentario de `MongoSyncStateRepository.java:25-28` afirma que el índice único da versión optimista; lo hace **sólo** cuando ambas instancias leen exactamente la misma cabecera, que es la ventana estrecha. En cuanto las lecturas se desfasan —el caso normal, porque entre transiciones hay llamadas a Mongo, ES y SAP— no hay protección: **no existe ningún lock por entidad ni un `findAndModify` condicionado**.

**Por qué ningún test lo caza.** `MongoSyncStateRepositoryTest` sí menciona `ConcurrentTransitionException`, es decir, prueba la colisión de `seq` (mismo `seq`, ambas escrituras a la vez). No prueba el caso de lecturas **desfasadas**, que es el que produce `IllegalStateException`. El test que debería existir: `MongoSyncStateRepositoryTest#transicionSobreCabeceraMovidaPorOtraInstancia`.

**Test primero (TDD).** Test de integración con Mongo (Testcontainers) que: escribe `RECEIVED`, luego `FETCHING`, luego **desde fuera** inserta un `RECEIVED` nuevo (simulando la otra instancia), y por último llama `transition(FETCHING→VALIDATING)`. Afirmar que la excepción resultante es reintentable (`ConcurrentTransitionException`), no `IllegalStateException`.

**Arreglo en una frase.** Que `MongoSyncStateRepository.transition` distinga "transición ilegal por error de programación" de "transición ilegal porque la cabecera se movió" —comparando `t.from()` (hoy ignorado) con el `from` real— y lance `ConcurrentTransitionException` en el segundo caso.

**Qué se aprende.** Clasificar una excepción como no reintentable es una decisión de negocio, no de tipo Java: `IllegalStateException` es el cajón de sastre de media JDK y ahí dentro caben fallos permanentes y transitorios mezclados.

---

### N3 — `bloqueante` — El retry HTTP reintenta POST no idempotentes y `Idempotency-Key` no lo protege

**Dónde:** `common/src/main/java/com/poc/sap/common/sap/RestClientSapClient.java:147-148` (retry alrededor de todos los métodos) y `:183-185` (`Idempotency-Key`).

**Qué pasa.** `exchange()` decora **cualquier** método HTTP con `Retry` (línea 148). Un `POST` a `A_BusinessPartner` que S/4 procesa pero cuya respuesta se pierde por *read timeout* (20 s por defecto, `application-common.yml:72`) se reintenta hasta 3 veces (`:78`).

Escenario concreto: alta de `C1`, S/4 tarda 21 s en responder al POST pero **crea** el Business Partner. El cliente ve timeout → reintento 1 → S/4 crea un **segundo** BP → reintento 2 → un tercero. Estado incorrecto: tres Business Partners duplicados en S/4 por un evento. La cabecera `Idempotency-Key` (línea 184) no lo evita: OData V2 de S/4HANA no la interpreta (hallazgo A7 de la auditoría previa, sigue vivo). Además, en `delete()` (`:138-140`) se pasa `payloadHash = null`, así que **las bajas ni siquiera llevan la cabecera**.

Agravante: el reintento CSRF de la línea 154 vuelve a ejecutar `resilient.get()` **completo**, es decir, hasta 3 intentos más. Peor caso real: 6 POST para un evento.

**Por qué ningún test lo caza.** `RestClientSapClientTest` existe y cubre circuito y 5xx, pero no puede cazar esto: el defecto no es que el código haga algo distinto de lo programado, es que lo programado duplica escrituras. Un test con MockWebServer que responda "lento y luego 201" demostraría el doble POST; hoy no existe.

**Test primero (TDD).** `RestClientSapClientTest#noReintentaEscriturasSinClaveDeIdempotenciaEfectiva`: servidor que agota el timeout y luego responde 201; afirmar que sólo se recibió **una** petición POST.

**Arreglo en una frase.** Reintentar sólo métodos idempotentes (`GET`, `DELETE`, `PATCH` con clave) y, para `POST`, o bien usar `upsert` por clave OData (`PATCH` sobre `A_BusinessPartner('id')`), o bien no reintentar y dejar que el reintento lo haga la ingesta con dedupe por `payloadHash`.

**Qué se aprende.** "Idempotency-Key" no hace idempotente nada por sí sola: la idempotencia es un contrato del servidor, y si el proveedor no lo firma, la cabecera es un comentario caro.

---

### N4 — `atencion` — El dedupe contra el último `SENT_SAP` oculta un fallo parcial y congela al cliente desincronizado

**Dónde:** `common/src/main/java/com/poc/sap/common/adapters/persistence/MongoSyncStateRepository.java:80-90` + `customer/src/main/java/com/poc/sap/customer/application/general/SyncCustomerUseCase.java:90-94`.

**Qué pasa.** El dedupe (A1) se corrigió para mirar **el último** `SENT_SAP`. Pero desde ADR-0010, un ciclo puede terminar en `SAP_ERROR` **con parte del cliente ya enviada a SAP**, y el dedupe no lo tiene en cuenta.

Escenario concreto:
1. Evento hash `H1` → todas las features OK → agregado `SENT_SAP` con `payloadHash = H1`.
2. Evento hash `H2` (cambia el IBAN) → `ADDRESS/FISCAL/CONTACT` entran en SAP, `BANKING` falla → agregado `SAP_ERROR`. **SAP ya no contiene `H1`**: contiene una mezcla.
3. El operador deshace el cambio en el legacy y llega de nuevo el evento `H1`. `alreadySent(customer, C1, H1)` mira el último `SENT_SAP` → tiene hash `H1` → **`true`** → `SyncCustomerUseCase.java:93` devuelve `SENT_SAP` y **no hace nada**.

Estado incorrecto: el cliente queda permanentemente desincronizado (SAP tiene la dirección de `H2`, el legacy la de `H1`) y el sistema afirma que está sincronizado. Es la misma clase de error que A1, reintroducida por el camino del fallo parcial.

**Por qué ningún test lo caza.** `MongoSyncStateRepositoryTest` prueba la secuencia `A → B → A` **con ciclos completos**; ningún test intercala un ciclo terminado en `SAP_ERROR` entre los dos. El test que debería existir: `MongoSyncStateRepositoryTest#noDeduplicaSiHuboUnFalloParcialPosterior`.

**Test primero (TDD).** `SyncCustomerUseCaseTest#reenviaSiElCicloAnteriorFueParcial`: historial `SENT_SAP(H1)` → `SAP_ERROR(H2)` → evento `H1`; afirmar que el pipeline se ejecuta y **no** devuelve `SENT_SAP` por dedupe.

**Arreglo en una frase.** `alreadySent` debe devolver `false` si existe cualquier transición con `seq` mayor que el último `SENT_SAP` que no sea un ciclo cerrado limpio (o, más simple, si el último terminal del agregado no es `SENT_SAP`).

**Qué se aprende.** Un dedupe es correcto sólo si la "última verdad enviada" es atómica. En cuanto se admite sincronización parcial, el hash del agregado deja de describir lo que SAP tiene.

---

### N5 — `atencion` — `POST /customers/sync` con `operation=DELETE` no da de baja: hace un alta fallida (A29, vivo y peor)

**Dónde:** `customer/src/main/java/com/poc/sap/customer/bootstrap/web/SyncCustomerController.java:41,45` frente a `customer/src/main/java/com/poc/sap/customer/bootstrap/kafka/CustomerKafkaListener.java:60-61`.

**Qué pasa.** El listener Kafka enruta: `if (msg.operation() == DELETE) deleteUseCase.execute(...)`. El controlador REST acepta `OperationType operation` en el cuerpo (`:41`), lo mete en el `IngestionMessage` y **siempre** llama `syncUseCase.execute(msg)` (`:45`). `SyncCustomerUseCase` **nunca lee `message.operation()`** — no aparece en todo el fichero.

Escenario concreto: `POST /customers/sync {"entityId":"C1","operation":"DELETE"}`. El cliente ya no está en el legacy → `legacyRepo.fetch` devuelve vacío (`SyncCustomerUseCase.java:108`) → transición `FETCHING→ERROR` → responde `200 OK` con `{"state":"ERROR"}`. La baja **no llega a SAP** y quien llama recibe un 200.

**Por qué ningún test lo caza.** `SyncCustomerUseCaseTest` prueba el use case, no el enrutado del controlador; y `DeleteCustomerUseCaseTest` prueba la baja invocada directamente. No hay ningún test de `SyncCustomerController` que pase `operation=DELETE`.

**Test primero (TDD).** `SyncCustomerControllerTest#deletePorRestLlamaAlUseCaseDeBaja` (`@WebMvcTest`, mock de ambos use cases).

**Arreglo en una frase.** Enrutar por `operation` en el controlador igual que en el listener, o rechazar `DELETE` con 400 y exponer `DELETE /customers/{id}`.

**Qué se aprende.** Dos puertas de entrada al mismo dominio divergen siempre salvo que compartan el enrutado; el sitio correcto para el `switch(operation)` es el use case, no cada adaptador.

---

### N6 — `atencion` — Un mandato SEPA con estado nulo se envía a S/4 como ACTIVO

**Dónde:** `customer/src/main/java/com/poc/sap/customer/adapters/sap/odata/SepaMandateODataAdapter.java:86-93`.

**Qué pasa.** `statusOf(null)` devuelve `STATUS_ACTIVE` ("1"). Es el patrón A19 (defaults de negocio en el adaptador) aplicado ahora a un dato con consecuencias financieras.

Escenario concreto: el legacy no informa el estado del mandato (columna nula, mapeo incompleto, migración a medias). Entrada: `Mandate(id=M1, status=null)`. Estado incorrecto: S/4 recibe `SePAMandateStatus = "1"` y **habilita el cobro por domiciliación** sobre un mandato cuyo estado nadie confirmó. Un mandato revocado cuyo `status` se pierde en el mapeo se reactiva en SAP.

**Por qué ningún test lo caza.** El defecto está en el default, y un test del adaptador que pase `status=null` afirmaría lo que el código hace hoy ("1"), no lo que debe hacer. No hay validación previa: `CustomerValidations` valida el `BankingData`, no el estado de cada mandato.

**Test primero (TDD).** `SepaMandateODataAdapterTest#rechazaMandatoSinEstado`: afirmar que `send` con `status = null` lanza y no construye payload.

**Arreglo en una frase.** Que `statusOf(null)` falle en vez de asumir, y que el estado del mandato sea obligatorio en la validación de dominio.

**Qué se aprende.** Un default silencioso en un adaptador convierte un dato que falta en un dato falso, y el que paga la diferencia es el cliente al que se le cobra.

---

### N7 — `observacion` — `SepaMandateODataAdapter` se crea siempre y falla en caliente si falta el acreedor

**Dónde:** `customer/src/main/java/com/poc/sap/customer/adapters/sap/odata/SepaMandateODataAdapter.java:29-30` (sin `@ConditionalOnProperty`, a diferencia de sus seis hermanos) y `:100-105`.

**Qué pasa.** `sap.sepa.creditor-id` no tiene default (`application-common.yml:46`). El bean se construye igualmente y `requireCreditor()` lanza `IllegalStateException` en la **primera llamada real** a SAP — que por N2 es no reintentable y va a DLT. Un fallo de configuración que se manifiesta como pérdida de mensaje en producción, en lugar de como un arranque fallido, que es el patrón que el propio repo ya aplica en `LegacyCredentialsGuard` y `RetryBudgetGuard`.

**Test primero (TDD).** `CustomerApplicationContextTest#noArrancaSinCreditorSiLosMandatosEstanActivos`.
**Arreglo:** validar `creditor-id` al arrancar (o condicionar el bean), como ya se hace con las credenciales del legacy.

---

### N8 — `observacion` — `CustomerStateUseCase` lee el historial completo cinco veces por consulta

**Dónde:** `customer/src/main/java/com/poc/sap/customer/application/general/CustomerStateUseCase.java:46-53`.

**Qué pasa.** `last()` llama `stateRepo.history(...)` y se queda con `history.get(size-1)`. Se invoca 5 veces por petición (agregado + 4 features). La colección `sync_state` es append-only, así que para un cliente con miles de ciclos se traen miles de documentos para leer uno. El repositorio Mongo **ya tiene** el método adecuado (`SyncStateMongoRepository.java:18`, `findFirstBy...OrderBySeqDescTimestampDesc`), pero `SyncStateRepositoryPort` sólo expone `currentState`, que devuelve el estado sin hash ni instante.

**Test primero:** no hay test unitario que lo detecte; el guardián natural es un test de rendimiento con N transiciones sembradas.
**Arreglo:** ampliar el puerto con un `lastTransition(domain, entityId)` y usarlo.

---

### N9 — `observacion` — El `origin` persistido no dice de dónde vino el evento

**Dónde:** `common/src/main/java/com/poc/sap/common/application/FeatureSyncPipeline.java:35` (`origin = feature.toLowerCase()`) y `customer/src/main/java/com/poc/sap/customer/application/general/DeleteCustomerUseCase.java:30` (`ORIGIN = "cdc"` constante).

**Qué pasa.** En la línea de feature, `origin` guarda `"address"`, `"fiscal"`... — el nombre de la feature, que ya está en el `entityId`. En la baja, guarda siempre `"cdc"` aunque la baja venga por REST. El campo existe para responder "¿quién disparó esto?" y hoy no responde. Es la misma falsedad que A34 señalaba con `origin = "rest"`, sólo que cambiada de sitio.

**Test primero:** `DeleteCustomerUseCaseTest#registraElOrigenRealDelEvento`.
**Arreglo:** propagar `IngestionOrigin` hasta el registrador en lugar de constantes locales.

---

### N10 — `observacion` — `LegacyCredentialsGuard` rechaza contraseñas que contengan `${`

**Dónde:** `common/src/main/java/com/poc/sap/common/config/LegacyCredentialsGuard.java:62-64`.

**Qué pasa.** `missing()` considera ausente cualquier valor que **contenga** `${`. Una contraseña generada con esos caracteres impide arrancar la aplicación con un mensaje que dice "placeholder sin resolver", enviando al operador a buscar un problema de entorno que no existe.

**Test primero:** `LegacyCredentialsGuardTest#aceptaPasswordConLlavesLiterales`.
**Arreglo:** comprobar el patrón completo `^\$\{[^}]*\}$`, no `contains`.

---

## 2. Re-comprobación de los hallazgos previos

| # | Hallazgo previo | Estado | Fichero:línea actual |
|---|---|---|---|
| A7 | `Idempotency-Key` no honrado por OData V2 | **VIVO** (agravado, ver N3) | `RestClientSapClient.java:183-185` (se envía), `:138-140` (en `delete` va `null`) |
| A19 | Lógica de negocio en adaptadores (defaults, constantes) | **VIVO** | `BusinessPartnerODataAdapter.java:56-57` (`"2"`, `"BPEE"`), `SepaMandateODataAdapter.java:87` (nuevo caso, N6), `CustomerDocument.java:46` / `SqlServerCustomerRepository.java:44` / `CustomerHistoryDoc.java:74` (`null → ACTIVE`) |
| A28 | `@ControllerAdvice` / `ProblemDetail` / `@Valid` ausentes | **VIVO** | Cero coincidencias de `ControllerAdvice`, `ProblemDetail` y `@Valid` en `*/src/main`. Único manejo: `@ExceptionHandler` local en `CustomerHistoryController.java:82-86`, que devuelve un `Map` ad-hoc |
| A29 | DELETE divergente REST vs Kafka | **VIVO** (ver N5) | `SyncCustomerController.java:41,45` vs `CustomerKafkaListener.java:60-61` |
| A30 | CSRF | **CERRADO** | `S4CsrfTokenProvider.java:54-95` (token y cookies cacheados juntos e inmutables), `RestClientSapClient.java:244-263` (fetch con la auth del destino, reintento único sólo ante `403 + x-csrf-token: Required`). Apagado por defecto (`application-common.yml:61`) |
| A32 | Jackson 2/3 mezclados; listeners con el mapper de SAP | **VIVO** | `CustomerKafkaListener.java:30` y `ArticleKafkaListener.java:28` (`SapJsonMapper.mapper()` para parsear Kafka), `KafkaSyncNotificationAdapter.java:3,29` (`new ObjectMapper()` de `com.fasterxml` sobre un Boot 4 con Jackson 3) |
| A33 | `Status.valueOf` sin tolerancia / NPE con status nulo | **VIVO** | `PostgresArticleRepository.java:27` (sin guarda: NPE si `getStatus()` es nulo), `SqlServerCustomerRepository.java:44`, `CustomerDocument.java:46`, `ArticleDocument.java:31`, `CustomerHistoryDoc.java:74`, `ArticleHistoryDoc.java:61`. Ninguno tolera un valor desconocido: `IllegalArgumentException` → no reintentable → DLT |
| A34 | `origin` falso; campo `from` decorativo | **VIVO** | `from`: `MongoSyncStateRepository.java:61-66` **ignora `t.from()`** y recalcula desde la cabecera; `SyncStateDoc` no tiene campo `from` y `toTransition()` (`:66-70`) devuelve `null` siempre. `origin`: `FeatureSyncPipeline.java:35`, `DeleteCustomerUseCase.java:30` (ver N9) |

**Pista (1) del orquestador — confirmada.** `MongoSyncStateRepository.transition()` (`:61-66`) recalcula `from` desde la cabecera y no mira `t.from()` en ningún punto; el `from` que pasa `SyncCycleRecorder.advance` (`SyncCycleRecorder.java:38-39`) es decorativo. Matiz importante: **no es un fallo de seguridad de la máquina** (validar contra el estado real almacenado es lo correcto), sino una oportunidad perdida: comparar lo declarado con lo real es justo lo que distinguiría N2 (colisión transitoria) de un error de programación. Y el `from` persistido no "no coincide": simplemente **no se persiste**.

**Pista (2) — confirmada y desarrollada en N1 y N2.**

### Lista "O" de §16

| Ítem | Estado | Fichero:línea |
|---|---|---|
| `Instant.now()` sin `Clock` | **VIVO** | `SyncCycleRecorder.java:31,39`, `SyncStateDoc.java:62`, `SyncStateTransition.java:40`, `KafkaSyncNotificationAdapter.java:62`, `ElasticsearchCustomerIndexer.java:26`, `ElasticsearchArticleIndexer.java:22`, `OAuth2TokenClient.java:38,65` |
| `@Value` sin `@ConfigurationProperties` | **VIVO** | 19 ficheros con `@Value`; **cero** `@ConfigurationProperties` en todo el repo. P. ej. `RetryBudgetGuard.java:30-34`, `SapIntegrationConfig.java`, `SepaMandateODataAdapter.java:44-46` |
| Método HTTP como `String` con default `POST` | **CERRADO** | `RestClientSapClient` usa `HttpMethod` tipado (`:123,134,139`); el `SapClient` expone `send`/`get`/`patch`/`delete`. Cero coincidencias de `"POST"` literal en `src/main` |
| `localhost:8080` hardcodeado | **CERRADO** (parcial) | No queda ningún `localhost:8080`. Quedan defaults `localhost` en YAML, pero todos tras variable de entorno (`application.yml:13,26,28,30`; `application-common.yml:109`), que es aceptable para arranque local |
| Beans sin llamadores | **VIVO** | `DeleteMandateUseCase` (`CustomerUseCaseConfig.java:49`), `ValidateAddressUseCase` / `ValidateFiscalUseCase` / `ValidateContactUseCase` / `ValidateBankingUseCase` (`:42,44,46,48`) y `BusinessPartnerReadAdapter`: se instancian y **nadie los invoca** desde ningún controlador ni listener |

---

## 3. Lo que está bien (conservar)

- **La separación `beginCycle` / `advance`** (`SyncStateMachine.java:86-115`) es el mejor cambio del rango: convierte "una fila que falta en la tabla" en una decisión explícita, y el comentario de las líneas 33-36 documenta *por qué* con el fingerprint de la incidencia. Eso es un ADR en el sitio correcto.
- **`FeatureSyncPipeline` + `SyncCycleRecorder`** eliminan 14 copias de la misma lógica de registro. La regla vive en un sitio y se prueba una vez; los defectos N1 y N2 son ahora *un* arreglo, no catorce.
- **`application` sin Spring** (`CustomerUseCaseConfig` / `ArticleUseCaseConfig` como único punto de contacto, vigilado por ArchUnit) es una decisión cara de tomar y barata de mantener. No la deshagáis.
- **`RetryBudgetGuard`** (`:42-54`) convierte una relación invisible entre dos configuraciones (`max.poll.interval.ms` y el presupuesto de reintentos SAP) en un fallo de arranque con instrucciones. Es el patrón a replicar (ver N7).
- **El orden en `SyncCustomerUseCase`**: indexar antes de enviar y guardar la imagen sólo tras el ACK (`:148-154`) es correcto y está justificado en el comentario.
- **`LegacyCredentialsGuard` como `EnvironmentPostProcessor`** (`:21,72`): fallar antes que Hibernate, con el placeholder sin resolver a la vista, ahorra la hora de diagnóstico que costó descubrirlo.

## 4. Lo que NO verifiqué

- **No ejecuté nada**: ni tests, ni compilación, ni Maven, ni arranque. Todo juicio sobre tests es por lectura de nombres de fichero y `grep`; **no afirmo que un test falle ni que pase**.
- **No abrí** (y por tanto no juzgo): `common/.../security/*` (`ApiSecurityConfig`, `KeycloakRoleConverter`, `AccessScope`, `ApiRoles`) más allá de su uso en los controladores; `sap/auth/BtpAuthProvider` y `S4NativeAuthProvider`; `SapIntegrationConfig` (sólo greps); los adaptadores `Btp*Adapter` de address/fiscal/contact; `BusinessPartnerAddress/Contact/Tax ODataAdapter`; `S4ArticleAdapter`; los `*Indexer` y `Mongo*ImageStore`; `SyncMetrics`; los ficheros de test; toda la documentación (`sdd/`, ADRs).
- **No verifiqué** el contenido de la auditoría del 2026-09-10: la tabla de re-comprobación usa los enunciados que me pasó el orquestador en el handoff, contrastados contra el código actual. No abrí `docs/auditorias/2026-09-10-auditoria-agentes.md`.
- **No comprobé** si el índice `dom_ent_seq_uk` existe realmente en los entornos desplegados: `auto-index-creation: true` está en `application-common.yml:140`, pero eso sólo garantiza el intento en el arranque.
- **N3** asume que S/4 procesa un POST cuya respuesta se pierde por timeout. Es el comportamiento normal de HTTP, pero **no lo verifiqué contra el tenant**.

## 5. Métricas del revisor

| Métrica | Valor |
|---|---|
| Ficheros de producción en el rango | 79 (`src/main` de common, customer, article) |
| Ficheros abiertos íntegros | 24 |
| Líneas de código leídas íntegras | ≈ 2.400 |
| Búsquedas (`grep` / `git`) | 9 |
| Hallazgos nuevos | 10 (3 bloqueantes, 3 atención, 4 observación) |
| Hallazgos previos re-comprobados | 8 + 5 ítems de la lista O |
| Tiempo aproximado | ~35 min |
| Cobertura estimada del rango | ~60 % de las líneas nuevas de `src/main`; prioridad 1 completa, prioridad 2 parcial, prioridad 3 por muestreo |
