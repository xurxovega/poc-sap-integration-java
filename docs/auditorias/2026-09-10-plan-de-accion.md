# Plan completo por fases — auditoría del 2026-09-10

> Plan de acción sobre la [auditoría del 2026-09-10](2026-09-10-auditoria-agentes.md).
> Redactado para que **una sesión nueva pueda ejecutar una fase sin contexto
> previo**: cada fase lleva qué, por qué y criterio de aceptación; el estado se
> lleva en la tabla de seguimiento del final.

## Contexto

Una auditoría externa (2 rondas, 10 handoffs, solo lectura) ha producido 60+
hallazgos con cita `fichero:línea`: 14 bloqueantes y ~36 de atención. Trae su
propio plan de 45 tareas (§15) con dependencias, criterios de aceptación y ruta
crítica. **Este documento lo adapta**, no lo reinventa: mismo orden de
criticidad, ajustado a nuestro método (SDD+TDD) y a las decisiones ya tomadas.

**Verifiqué contra el código los seis hallazgos más graves. Los seis son ciertos:**

| Hallazgo | Verificación |
|---|---|
| B1 · `SAP_ERROR` es sumidero | `SyncStateMachine.java:53` → `{SENDING_SAP, VALIDATING, ERROR}`, sin `RECEIVED`; `SyncCustomerUseCase.java:101` entra siempre por `RECEIVED`. Un cliente con un fallo previo no vuelve a sincronizarse jamás |
| B2 · DELETE inejecutable | `DeleteCustomerUseCase.java:38` abre por `SENDING_SAP` (ilegal sin historial y desde `SENT_SAP`); `BtpCustomerAdapter.java:35-36` manda `POST {}`. Alcanzable desde `CustomerKafkaListener` |
| B5 · Credencial versionada | Está en el *Initial commit* del repo anidado |
| B7 · 4 tests que nunca corren | `SyncCustomerControllerIT` existe; ni el parent ni `customer/pom.xml` declaran surefire o failsafe |
| B8 · `AGENTS.md` miente | `AGENTS.md:248` dice «sin `@ConditionalOnProperty`»; los 5 adaptadores lo llevan. **Lo escribí yo** |
| B11 · Estado no determinista | `currentState` ordena por `timestamp` en ms; `transition` es read-then-write |

**Hallazgo propio que el informe no recoge:** 14 sitios abren ciclo con `from=null`
usando **cuatro** estados de entrada (`RECEIVED`, `VALIDATING`, `SENDING_SAP`,
`INDEXING`) mientras `INITIAL_STATES` solo admite dos. `DeleteMandateUseCase` e
`IndexCustomerUseCase` están igual de rotos que el delete (hoy latentes: sin
llamadores). Confirma el diagnóstico: mi arreglo de ayer fue la **tercera
recurrencia** del mismo patrón y añadir filas garantiza la cuarta.

### Decisiones ya tomadas

- **B5 se mitiga con `.gitignore`**, sin rotar: es un repo local que no se sube.
  *Riesgo residual, dicho una vez:* la credencial sigue en el historial del repo
  anidado en tu disco y en cualquier copia de seguridad. Si ese tenant es real y
  compartido, la mitigación es del repo, no del tenant.
- Máquina de estados **de raíz**, no fila a fila.
- Informe a `docs/auditorias/` + convención `docs/incidencias/`.
- **D-1 · Transporte SAP: `RestClient` de Spring ahora**, y el VDM del Cloud SDK
  cuando SAP publique soporte de Boot 4. Se cambia la implementación detrás del
  puerto `SapClient` y se borran `webflux`, `.block()` y `sdk-core`. `If-Match`
  y deep insert los escribimos nosotros. **Va a ADR con el disparador de
  reevaluación**, porque no es una decisión de una vez: es una que se revisa.
- **D-6 · Todo el salto de versión**: Spring Boot **4.1**, Spring Cloud
  **2025.1.x** y **JDK 25 como mínimo real** (`release 25` sin perfil). El JDK 25
  además elimina el *pinning* de virtual threads sobre `synchronized` (JEP 491),
  que es una de las observaciones del informe.

### Modelo de ejecución: una sesión por fase

Sí a repartir el trabajo en varias sesiones para no agotar contexto, **pero
secuenciado por fases, no en abanico**. Tres razones concretas:

1. **La Fase 0 es estrictamente serial y va primera.** El `.gitattributes` y la
   renormalización tienen que aterrizar en un commit aislado. Si varias sesiones
   tocan ficheros antes, cada diff se llena de ruido CRLF y los conflictos se
   multiplican.
2. **La Fase 1 es una unidad causal indivisible.** La máquina de estados, el
   `try/catch`, el desempate y el DELETE tocan `SyncStateMachine` y los **mismos
   14 llamadores**. Repartirla entre agentes garantiza conflictos sobre los
   mismos ficheros. Una sesión, un PR.
3. **La Fase 7 no puede adelantarse a la 2.** Refactorizar sin red extremo a
   extremo es, según la propia auditoría, el mayor riesgo del plan.

**Qué sí puede ir en paralelo**, una vez cerrada la Fase 0:

| Ola | En paralelo | Por qué no chocan |
|---|---|---|
| 1 | Fase 1 (código) · Fase 9 parcial: ADRs, post-mortem, glosario (solo `docs/`) | Ficheros disjuntos |
| 2 | Fase 2 (CI, suite, e2e) · Fase 4 (seguridad) | `pom.xml`/CI vs `bootstrap` + nueva config |
| 3 | Fase 3 (upsert OData) · Fase 5 (observabilidad) | `adapters/sap` vs `common/observability` |
| 4 | Fase 6 (consistencia) · Fase 8 (supply chain) | `common/adapters/persistence` vs build |
| 5 | Fase 7 (refactor) **sola** | Toca todo; necesita la red de la Fase 2 |

Máximo **2-3 sesiones a la vez**: la propia auditoría perdió una ronda entera por
agotar la cuota lanzando cinco agentes pesados en paralelo (M8).

**Contrato de cada sesión.** El prompt de arranque lleva siempre: *objetivo* ·
*entrada* (esta fase del plan + ficheros citados) · *criterio de aceptación*
(el de la fase, verificable) · *devuelve-si* (condición de rechazo, p. ej. «la
Fase 0 no está commiteada» o «no hay decisión D-1»). Y una regla heredada del
método: **quien implementa no valida su propio trabajo** — el cierre de cada fase
lo verifica una sesión distinta contra los criterios escritos, no contra el
recuerdo de quien la hizo.

### Reglas que aplican a todas las fases

Método del repo, sin excepción: **spec → test en rojo → código → verde**, y al
cerrar cada tarea: §9/§10 del spec, `CHANGELOG.md` de la carpeta, evento en
`sdd_registry`, glosario si hay concepto nuevo, `CHANGELOG.md` raíz si se nota en
negocio. Cada fase termina con `mvn verify` en verde y, cuando toque pipeline,
verificación **en vivo** contra el entorno levantado.

---

## Fase 0 · Higiene — antes de tocar nada más

Barata, y el orden importa: si no va primero, ensucia todo lo demás.

| # | Qué | Por qué ahora |
|---|---|---|
| 0.1 | `.gitattributes` (`* text=auto eol=lf`, `*.sh eol=lf`) + renormalizar en **commit aislado** | Hay 172 ficheros con ruido CRLF. Cualquier commit anterior a esto destruye el `git blame` |
| 0.2 | `sap-sdk-client/` al `.gitignore` (B5) | Decisión tomada. Confirmar además que ese repo anidado no tiene remoto |
| 0.3 | Corregir `AGENTS.md:248` (B8) y las otras 2 afirmaciones falsas | Un agente que lee instrucciones falsas crea beans duplicados con confianza |
| 0.4 | `TECH.md` §5/§8: 9 adaptadores inexistentes → reescribir con clases reales o etiquetar «Visión» (B9) | Misma razón |
| 0.5 | Decidir el sufijo del topic DLT y alinear las 21-24 ocurrencias (A16/OPS-3) | Lleva abierto desde que lo detecté; es una línea de configuración o un `sed` |
| 0.6 | Mover el informe a `docs/auditorias/2026-09-10-auditoria-agentes.md` y commitearlo | Trabajo valioso y fechado |
| 0.7 | Dejar de llamarlo PoC en `README.md`, `AGENTS.md` y `CHANGELOG.md` | Ese encuadre justifica decisiones («es un PoC, no hace falta auth»). Si es el producto final, la documentación tiene que decirlo |

**Aceptación:** `git diff -w` vacío tras renormalizar · `grep "sin \`@ConditionalOnProperty\`" AGENTS.md` vacío · cada clase citada en `TECH.md` existe o lleva etiqueta.

---

## Fase 1 · Lo que hace el sistema inservible

El núcleo. Todo esto es un solo ciclo SDD+TDD porque comparte causa raíz.

**1.1 · Máquina de estados, de raíz** (B1, B11-B14)

En `SyncStateMachine`: separar **abrir ciclo** de **avanzar en el ciclo**.

- `ENTRY_STATES` sustituye a `INITIAL_STATES` con los cuatro puntos de entrada
  reales: `RECEIVED` (agregado), `VALIDATING` (feature), `SENDING_SAP` (baja),
  `INDEXING` (indexación).
- `beginCycle(actual, entrada)` — legal para **cualquier** `actual`, incluido
  `null`; solo valida `entrada ∈ ENTRY_STATES`. Un evento nuevo siempre abre
  ciclo: la idempotencia la da el dedupe por `payloadHash`, no el bloqueo de la
  máquina. **Cierra también OPS-1** (entidades atascadas) sin necesidad de lease.
- `isInFlight(estado)` — expone que el ciclo anterior quedó a medias, para
  registrarlo y medirlo. No bloquea.
- `advance(from, to)` — la tabla actual, solo avance.
- El **puerto** `SyncStateRepositoryPort` debe distinguir ambas intenciones: hoy
  recalcula `from` y llama siempre a `transition`, y por eso el diseño se puede
  esquivar desde los use cases.
- **Test de propiedad**: todos los estados × todas las entradas. Es lo que impide
  la cuarta recurrencia.

Migrar los **14 llamadores** (helper `transition` duplicado 14 veces): primera
transición → `beginCycle`, resto → `advance`.

**1.2 · Que un fallo no deje la entidad colgada** (B12/C2, B13/C3, A28/C5)

- `try/catch → ERROR` en el tramo posterior a `VALID` de ambos orquestadores: hoy
  cualquier fallo de Mongo/ES/HTTP deja la entidad en un estado intermedio. **Es
  el mecanismo por el que se dispara B1.**
- `WebClientSapClient:136-139` deja de tragarse `CallNotPermittedException`: con
  el circuito abierto hoy se registra `SAP_ERROR` sin reintento y sin señal.
- `addNotRetryableExceptions(IllegalStateException, IllegalArgumentException, JsonProcessingException)`
  y `if (value == null) return` en los listeners: hoy un tombstone o un
  `operation` en minúsculas se reintenta 3 veces con backoff.

**1.3 · Estado determinista y seguro entre instancias** (B11/C1 + A2)

Una sola pasada sobre `MongoSyncStateRepository.transition`, porque son el mismo
método y el mismo riesgo:

- Desempatar `currentState` por `_id` o secuencia: las transiciones se escriben
  en ráfaga y el orden por `timestamp` en ms empata → `from` no determinista →
  `IllegalStateException` **intermitente e irreproducible**.
- **Versión optimista** en la escritura. Adelantado desde la Fase 6 porque habrá
  **varias instancias por dominio**: hoy es read-then-write sin bloqueo, y dos
  instancias consumiendo el mismo topic se pisan el estado. Test con dos hilos
  virtuales: uno gana, el otro reintenta o falla limpio.

**1.4 · Baja ejecutable, con modelo de bloqueo** (B2)

Se implementa ya con semántica de **bloqueo**, no de borrado, para no escribirlo
dos veces.

- `DeleteCustomerUseCase` → `beginCycle(..., SENDING_SAP)`, ya legal.
- `BtpCustomerAdapter` deja de mandar `POST {}`: `SapClient.delete(destination, path)`
  ya existe y no tiene llamadores. Hay que separar el borrado del `send` del
  puerto — hoy `customer == null` se usa como señal de borrado, y esa conflación
  **es** el bug.
- **Nuestros almacenes**: la imagen de Mongo se marca bloqueada en vez de
  eliminarse (hoy `imageStore.delete`), y el histórico de ES y `sync_state` se
  conservan —son el rastro de auditoría— marcados como bloqueados.
- **Lado SAP**: qué significa «bloquear» en S/4 (flag de bloqueo del Business
  Partner vs borrado) se valida en la Fase 3 contra el tenant de test.
- Corregir `sap-api-catalog.md`, que afirma que ya se hace DELETE.
- Spec `customer/baja-cliente.md` con el estado «bloqueado» modelado en el dominio.

**Specs:** ampliar `common/maquina-de-estados.md` (§4 R-3/R-4 hoy no se cumplen
para `SAP_ERROR`, §6 tabla, §7 AC nuevos) y crear `customer/sincronizacion-cliente.md`
y `customer/baja-cliente.md`.

**Verificación en vivo** (lo que ninguna suite cazó):
- Forzar 500 en WireMock → sincronizar → restaurar 201 → re-sincronizar: debe
  llegar a `SENT_SAP`, no a la DLT.
- **DELETE por CDC**: nunca se ha ejecutado en vivo (CP-08). WireMock debe
  registrar un `DELETE`.
- `CUST-001`, hoy atascado en `SENDING_SAP`, debe desbloquearse con el siguiente evento.

---

## Fase 2 · La red de seguridad

Sin esto, las fases siguientes se hacen a ciegas. El informe es tajante:
refactorizar sin red extremo a extremo es el mayor riesgo del plan.

| # | Qué | Aceptación |
|---|---|---|
| 2.1 | Renombrar `SyncCustomerControllerIT` → `…Test` y fijar surefire con versión en el `pluginManagement` del parent (B7) | El recuento de tests sube respecto a los 240 actuales |
| 2.2 | CI mínimo: `mvn -B verify` sin Docker + job opcional con Docker | Primer run verde; habría cazado B7 |
| 2.3 | JaCoCo `report`+`check` con umbral en `domain/**` | Comentar un test de dominio rompe `verify` |
| 2.4 | Contract tests que ejerciten los **adaptadores reales** contra WireMock (B6) | Hoy `AbstractSapContractTest` stubbea WireMock y lo llama con `HttpClient` del JDK: no toca código de producción, y el spec de dirección lo cita como cobertura |
| 2.5 | Un e2e con Testcontainers por dominio: outbox → Debezium → app → WireMock → Mongo | `resyncsAfterSapError` verde |
| 2.6 | Unificar el recuento de tests (211/240/245/250 según el fichero) y decir si es *declarados* o *ejecutados* | Un test falla si se añade un `@Test` sin actualizarlo |
| 2.7 | **Salto de versión (D-6)**: Boot 4.1, Spring Cloud 2025.1.x — o quitar el BOM, ya que solo lo usa `spring-cloud-contract-wiremock` y ningún test lo referencia — y `release 25` sin perfil `jdk25` | `verify` verde; `grep jdk25 pom.xml` vacío |
| 2.8 | ArchUnit: `domain` sin Spring/Jackson/Mongo (activa) | 0 violaciones |

**Resultado (12-09-2026).** 2.1, 2.2, 2.3, 2.4, 2.6, 2.7 y 2.8 hechas tal cual.
2.5 **parcial**: `SyncStateMongoIT` ejercita el repositorio de estado contra un
Mongo 7 real (re-sync tras `SAP_ERROR`, dos escritores concurrentes, documentos
legacy sin `seq`); el e2e completo outbox → Debezium → app → WireMock → Mongo
queda en el backlog (TEST-1/TEST-4) porque necesita el compose entero dentro de
Testcontainers y no bloquea la Fase 3. Umbral JaCoCo: 0,75 de líneas en
`**/domain/**` (medido: common 90 %, customer 78 %, article 88 %; propiedad
`jacoco.domain.line-minimum`). Matiz sobre el criterio «comentar un test de
dominio rompe `verify`»: el dominio lo cubren también los tests de use case y
repositorio, así que excluir `SyncStateMachineTest` entero deja common en el mismo
90 %. El check protege la cobertura agregada; se comprobó que falla forzando el
mínimo a 0,95. Recuento:
**270 `@Test` declarados**. Hallazgo colateral: `InfrastructureSmokeIT` nunca
arrancaba Kafka (`KafkaContainer(String)` duplicaba el nombre de imagen).

---

## Fase 3 · Que funcione contra un SAP real

La más grande. D-1 ya está decidida: **`RestClient`**.

- **Cambiar el transporte primero** (D-1): `WebClientSapClient` pasa a `RestClient`
  detrás del mismo puerto `SapClient`; fuera `spring-boot-starter-webflux`,
  `.block()`, `sdk-core` y el `@ComponentScan("com.sap.cloud.sdk")` de ambas apps.
  Va antes del upsert para no escribir `If-Match` dos veces. **ADR con el
  disparador**: reevaluar el VDM cuando el Cloud SDK anuncie soporte de Boot 4.
- **Primera comprobación contra el tenant de test, antes de diseñar nada**:
  ¿S/4 acepta `PATCH` parcial sobre `A_BusinessPartner`, o exige el payload
  completo? Hoy creemos lo segundo, pero esa impresión viene de que **nuestra**
  ruta OData solo hace POST — `SapClient.patch` existe sin llamadores. La
  respuesta cambia el diseño del upsert, el coste (los upserts se facturan) y si
  el atajo «sin cambios reales» sigue teniendo sentido.
- **Bloqueo en SAP**: qué operación representa la baja en S/4 (flag de bloqueo
  del BP vs borrado), coherente con el modelo de bloqueo ya decidido.
- **Upsert idempotente** (B3): lookup con `BusinessPartnerReadAdapter` — que ya
  existe — → deep insert en alta / `PATCH` con `If-Match` en modificación, con
  `AddressID` persistido en la imagen. Hoy la ruta OData **solo hace POST**: cada
  actualización crea una dirección nueva. `SapClient.patch` no tiene llamadores.
- Contratos correctos: `A_AddressEmailAddress`/`A_AddressPhoneNumber` para
  contacto (hoy el adaptador **descarta email y teléfono**), BIC fuera de
  `BankIdentification`, mandatos contra `API_APAR_SEPA_MANDATE_SRV` (modelos ya
  generados, sin consumidor) en vez de una API inexistente.
- CSRF (A6/C4): el fetch usa Basic aunque `auth.type=oauth2`; cualquier 403 se
  trata como rechazo CSRF; caché leída sin sincronización.
- Circuit breaker fuera del `Retry` (hoy `CallNotPermittedException` se reintenta 3 veces).
- Documentar que `Idempotency-Key` **no** es garantía en OData V2: la garantía es el upsert.

**Avance (12-09-2026).** 3.1 hecho: `RestClientSapClient` sustituye a
`WebClientSapClient` detrás del puerto; fuera `webflux`, Reactor, `sdk-core`,
`@ComponentScan("com.sap.cloud.sdk")` y el destino local del SDK. El test del
cliente se portó íntegro y sigue verde (más tres AC nuevos). ADR-0001 escrito con
el disparador de reevaluación; spec `common/resiliencia-cliente-sap.md` creado.
De paso, CSRF (A6/C4): fetch con la auth del destino, 403 solo es CSRF con
`Required`, caché atómica. `Idempotency-Key` documentado como no-garantía (R-7
del spec). Circuit breaker fuera del retry ya venía de la Fase 1. **Queda**: la
comprobación PATCH parcial vs payload completo contra el tenant de test, el
bloqueo en SAP, el upsert idempotente, y los contratos de contacto, BIC y
mandatos.

**Avance (12-09-2026, 3.2).** Contratos de **banco y mandato** corregidos (B3
parcial): `S4BankingAdapter` → `BtpBankingAdapter` (familia BTP; la API
`API_CUSTOMER_MANDATE` no existe); `A_BusinessPartnerBank` con
`BankIdentification=0001` y `BankCountryKey`, sin BIC; `SepaMandateODataAdapter`
sobre `API_APAR_SEPA_MANDATE_SRV` (clave `Creditor`+`SEPAMandate`, `Creditor` por
`sap.sepa.creditor-id`, baja = `PATCH` estado 3). Specs
`customer/sincronizacion-datos-bancarios.md` y `customer/baja-mandato-sepa.md`.
Contract tests reales para los tres. **Queda de B3**: contacto por
`A_AddressEmailAddress`/`A_AddressPhoneNumber` y upsert con `AddressID`: ambos
necesitan la comprobación PATCH contra el tenant de test, que es el siguiente
paso y no se puede adelantar sin él. Para validar en el tenant: la
correspondencia de `SEPAMandateStatus` (1/3/4), `SenderType=BUS1006`,
`SEPAMandateApplication=F` y que `BankCountryKey` + IBAN basten sin `BankNumber`.

---

## Fase 4 · Seguridad

- Autenticación en las APIs REST y en actuator (B4): hoy `/history?full=true`
  devuelve IBAN, NIF, email y teléfono sin credencial, y `/sync` dispara
  escrituras facturables en S/4. No hay `spring-boot-starter-security` en ningún pom.
- Puerto de gestión separado y `show-details: when-authorized`.
- Quitar los defaults de secretos (`sa`, `trustServerCertificate=true`) y el
  fallback silencioso a token stub: hoy en producción daría 401 en vez de fallar
  al arrancar (A8).

---

## Fase 5 · Observabilidad y operación

- Starter **oficial** de OTel de Boot 4 (no el de terceros que descartamos) y
  logs estructurados nativos (`logging.structured.format.console=ecs`): cierran
  OBS-2 y OBS-4 y hacen verdad lo que `TECH.md` ya afirma.
- `recordStageDuration` está definido y **nunca se invoca**; `WebClient.builder()`
  estático deja sin métricas HTTP; sin binder de Resilience4j; el tag
  `application` es idéntico en ambas apps.
- `server.shutdown=graceful` y presupuesto de reintentos por debajo de
  `max.poll.interval.ms` (hoy ≈4,6 min contra 5 min → rebalanceos en cascada con
  SAP degradado).
- Prometheus + Grafana: **ya aplazado por ti**; queda aquí como opcional.

**Avance (12-09-2026).** Hecho: `recordStageDuration` cableado en ambos
orquestadores (`fetch`/`validate`/`index`/`send`); `sap_client_request_duration`
por intento HTTP; `MeterBinder` de Resilience4j para retry y circuit breaker
`sap`; tag `application` = `${spring.application.name}`; `server.shutdown=graceful`
(30 s); `max.poll.interval.ms` a 15 min con `RetryBudgetGuard` (peor caso por
mensaje 5,1 min con los defaults; falla al arrancar si no cabe); formato ECS de
log con `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`. Spec `common/observabilidad.md`.
**Queda**: trazas distribuidas y `traceId` en logs, bloqueadas por **D-7**
(starter oficial de OTel para Boot 4 vs javaagent) y por el colector (OBS-2);
Prometheus + Grafana aplazados por ti.

---

## Fase 6 · Consistencia del estado

- Dedupe contra el **último** `SENT_SAP`, no contra cualquiera del histórico: hoy
  la secuencia A→B→A descarta el tercer evento aunque SAP tenga B (A1).
- Versión optimista en `transition()` → **movida a la Fase 1** por la decisión de
  alta disponibilidad.
- Imagen persistida **tras** el ACK de SAP, no antes; el atajo «sin cambios
  reales» registra `SENT_SAP` para un hash que nunca se envió.
- Id del histórico ES = `entityId-hash`: un reenvío tras `SAP_ERROR` **sobrescribe**
  la versión anterior (A31).
- Compensación entre features: **bloqueada por D-2**.

---

## Fase 7 · Refactor, ya con red

Solo después de la Fase 2. ~900 LOC duplicadas de 3.468 (~25 %).

- `SyncPipeline<E>` genérico en `common`; migrar los 4 `Sync<Feature>UseCase`,
  indexers, image stores, `KafkaErrorHandlingConfig` y las 14 copias de `transition()`.
- `application` sin Spring: hoy 16/16 clases con `@Service`, 14 dependen de
  Micrometer y 2 de Jackson, contra la regla que el propio `AGENTS.md` declara.
  Vía `MetricsPort`/`DiffPort` y wiring en `bootstrap`; ArchUnit lo vigila.
- Sustituir los 27 `@Mock` sobre clases concretas por fakes de puerto (A20).
- Lógica de negocio filtrada a adaptadores de vuelta al dominio; `S4ArticleAdapter`
  con `SapJsonMapper` en vez de `String.format` (A19).
- Borrar código muerto: `IngestionPort` sin implementaciones, `sap.odata.enabled`
  y `sap.integration.mode` que nadie lee, `IndexCustomerUseCase` y
  `DeleteMandateUseCase` sin llamadores (A18).

---

## Fase 8 · Cadena de suministro y despliegue

Maven wrapper (el README dice que existe y no existe), `maven-enforcer`,
`dependency:analyze` en `verify`, Dependabot, SBOM, imágenes por digest,
`spring-boot:build-image`, perfiles `local/test/prod`, `launch.json` que hoy
activa un perfil inexistente.

---

## Fase 9 · Documentación, ADRs y cierre

- **`docs/incidencias/`** con el post-mortem del primer arranque y un índice de
  *fingerprints* (`sync-state:reentrada-no-permitida` va por su tercera recurrencia).
- **5 ADRs retroactivos** de lo que hay hoy: WebClient propio, Mongo para estado,
  ES para histórico, dos familias de adaptadores, MySQL para el registro.
- **Reformular lo que declara resuelto algo que sigue vivo**: `CHANGELOG.md`
  («tantas veces como cambie» → mientras cada ciclo termine bien), `sdd/README.md` §6,
  `GUIA-PRUEBAS.md` CP-08/CP-10 («no verificado en vivo»).
- Fuente única de la máquina de estados (descrita 8 veces, 4 copias desactualizadas)
  y de la regla del ancla; glosario saneado (13 términos frecuentes sin entrada,
  6 definiciones obsoletas, un comando de shell pegado dentro de una definición).
- Registro SDD generado desde el frontmatter de cada spec en vez del `INSERT`
  manual: hoy solo existe el seed y nada lo audita.
- Volcar al backlog todo lo no abordado, con severidad.
- **Aptitud para producción** (B10 reformulado, no lo cubre la auditoría por haber
  analizado un PoC): objetivos de rendimiento con cifra y prueba de carga que los
  verifique, plan de capacidad, copia de seguridad y restauración con RTO/RPO
  probados, runbooks de las incidencias que ya conocemos (entidad atascada,
  mensaje en la DLT, SAP caído) y quién opera esto.
- `supplier` sigue siendo un placeholder no desplegable: decidir si entra en el
  alcance del producto final o se retira del reactor.
- **Auditoría de cierre**: bloqueante a bloqueante, cerrado/abierto con evidencia.

---

## Decisiones que necesito de ti, y cuándo

D-1 y D-6 están decididas (arriba). Quedan abiertas:

| ID | Decisión | La necesito antes de |
|---|---|---|
| D-2 | Compensación entre features: Spring Modulith, saga propia, o ninguna | Fase 6 |
| D-7 | Trazas con el starter oficial de OTel | Fase 5 |
| D-3 | Debezium Server vs Kafka Connect; Event Router SMT oficial | Fase 3 (compose del e2e) |
| D-4 | OData V4 ahora o después | Fase 3 (se diseña para que el cambio sea local) |
| D-5 | MCP de solo lectura | Después |
| D-8 | Schema Registry para la outbox | Después |

## Esto no es un PoC: es probablemente la aplicación final

Dato aportado después de la auditoría, que la analizó **como PoC**. Cambia el
plan, no es un matiz.

**Deja de ser opcional** lo que había marcado como recortable: autenticación,
TLS, retención y borrado. Unas APIs sin credencial que devuelven IBAN y NIF, y
que además disparan escrituras facturables en S/4, son una molestia en un PoC y
un impedimento para salir a producción.

**Varias instancias por dominio (HA)** sube la concurrencia a bloqueante:
`transition()` es hoy read-then-write sin bloqueo ni versión optimista, y dos
instancias consumiendo el mismo topic se pisan el estado. **Se adelanta a la
Fase 1**, donde ya vamos a reescribir ese método por el desempate (B11): es la
misma pasada sobre `MongoSyncStateRepository`.

**Qué se puede aplicar a posteriori y qué no** (RGPD/LOPD):

| Retrofit barato | Retrofit caro o inviable |
|---|---|
| Autenticación y autorización | **Retención**: aplicarla después no borra lo ya escrito en 6 almacenes |
| Enmascarado de PII en logs y respuestas | **Derecho de supresión**: hoy `DeleteCustomerUseCase` solo borra la imagen de Mongo, no el histórico de ES ni `sync_state` |
| TLS en tránsito | **Minimización**: el histórico de ES guarda el snapshot íntegro con IBAN |
| Registro de accesos | Retención de Kafka: los topics y la DLT ya contienen PII |

**Quién decide qué** (no es asesoría legal; es el reparto de responsabilidad):

| Qué | Quién |
|---|---|
| Que exista plazo de conservación y no sea «para siempre» | La ley (art. 5.1.e) |
| Cuántos meses o años | El negocio, condicionado por obligaciones mercantiles, fiscales y las reglas SEPA de los mandatos |
| Que exista derecho de supresión | La ley (art. 17). No es opcional |
| Cómo se implementa (borrado, anonimización o **bloqueo**) y en qué plazo | **Nosotros** |
| Qué campos se indexan en el histórico | **Nosotros** — minimización (art. 5.1.c) |
| Base legal y DPA con SAP | Cliente y legal, previos al tratamiento |
| Autenticación, enmascarado, TLS | **Nosotros** el cómo; la ley solo exige que sean «apropiadas» (art. 32) |

**Decisiones tomadas:**

- **Modelo de bloqueo, no de borrado.** La baja marca el dato como bloqueado en
  lugar de eliminarlo. Encaja con la obligación de conservar por motivos
  fiscales o mercantiles, y evita rehacer el camino después. Implica modelar el
  estado «bloqueado» en el dominio — va con spec.
- **El histórico de ES conserva el snapshot íntegro**, IBAN incluido: su
  propósito declarado es **auditar en cualquier momento qué se envió a SAP**.
  Con ese propósito la minimización se sostiene. Dos consecuencias que hay que
  asumir a cambio: sigue haciendo falta **un plazo de conservación** («para
  auditoría» no es ilimitado por sí solo), y el histórico entra en el modelo de
  bloqueo como todo lo demás.
- **Auth, enmascarado y TLS en fase posterior.** Decidido. Queda un disparador
  objetivo, no una opinión: el art. 32 aplica desde que se tratan datos reales,
  así que si el tenant de test se alimenta con datos reales de cliente, la Fase 4
  se adelanta. Con datos de prueba, no.

> Interacción entre las dos primeras decisiones, dicha una vez: el histórico
> íntegro con IBAN es exactamente lo que sirve hoy `GET /customers/{id}/history?full=true`
> **sin credencial**. La decisión de auditar aumenta el valor de ese endpoint, no
> lo reduce.

**Huecos nuevos que la auditoría no cubre por haber analizado un PoC**: sin
objetivos de rendimiento ni plan de capacidad, sin copia de seguridad ni
restauración, sin runbooks, y `supplier` sigue siendo un placeholder no
desplegable. Van a la Fase 9.

**B10 se reformula**: no es «criterio de fin del PoC» sino **criterios de aptitud
para producción** — SLOs, capacidad, RTO/RPO y quién opera esto.

**La documentación miente sobre su propia naturaleza**: `README.md`, `AGENTS.md`
y `CHANGELOG.md` lo llaman PoC, y ese encuadre justifica decisiones («es un PoC,
no hace falta auth»). Se corrige en la Fase 0.

## Hito inmediato: el tenant SAP de test

Es el siguiente objetivo declarado, y **aterriza al cerrar la Fase 3**. El camino
mínimo es `0 → 1 → 2 → 3`; la Fase 2 no se salta porque sus contract tests contra
los adaptadores reales (2.4) son precisamente lo que hace verificable la Fase 3 —
hoy los de `it/` no tocan código de producción.

Antes de apuntar a un tenant real, dos cosas de la Fase 4 se adelantan aunque el
resto de seguridad espere: **quitar el fallback silencioso a token stub** (hoy,
sin credenciales, no falla al arrancar: da 401 en la primera llamada) y **sacar
los secretos de los YAML empaquetados**. **Hechas el 12-09-2026**: `sap.auth.allow-stub`
(default `false`) y credenciales de BD sin default; spec
`common/autenticacion-sap.md`. Para el tenant: `SAP_AUTH_ALLOW_STUB=false` en
`test.env` y credenciales reales.

> **Condición**: si el tenant de test se alimenta con **datos reales de cliente**,
> la Fase 4 entera pasa por delante de la 3. El art. 32 aplica desde que se tratan
> datos reales, no desde go-live, y hoy las APIs devuelven IBAN y NIF sin
> credencial. Con datos de prueba, el orden `0 → 1 → 2 → 3` se mantiene.

## Seguimiento entre sesiones

Esta tabla se actualiza **en el repo** al cerrar cada fase. Es lo que permite que
una sesión nueva sepa dónde estamos sin leer el historial de ninguna otra.

| Fase | Estado | Cerrada el | PR / commit | Verificada por |
|---|---|---|---|---|
| 0 · Higiene | ✅ hecha | 2026-09-11 | `b55f889` (.gitattributes) + `00bbbae` | pendiente de verificación por otra sesión |
| 1 · Inservible | ✅ hecha | 2026-09-12 | `84a9da4` | pendiente de verificación por otra sesión (verificación en vivo: 3/3 en esta sesión) |
| 2 · Red de seguridad | ✅ hecha | 2026-09-12 | `299567a` | pendiente de verificación por otra sesión (`mvn verify` y `-pl it verify -Ddocker.available=true` en verde en esta sesión) |
| 3 · SAP real | 🚧 en curso | | 3.1 transporte `RestClient` + CSRF (ADR-0001, `c07adb0`) · 3.2 contratos banco y mandato SEPA, hechos el 2026-09-12 | |
| 4 · Seguridad | 🚧 parcial | | A8 adelantado el 2026-09-12 (sin stub silencioso, secretos fuera del YAML) como prerrequisito del tenant; B4 (auth REST/actuator) pendiente | |
| 5 · Observabilidad | 🚧 parcial | | A9 (métricas, tag, ECS) y A10 (graceful, presupuesto de reintentos) hechos el 2026-09-12; trazas bloqueadas por D-7 | |
| 6 · Consistencia | ⬜ pendiente | | | |
| 7 · Refactor | ⬜ pendiente (desbloqueada: Fase 2 cerrada) | | | |
| 8 · Supply chain | ⬜ pendiente | | | |
| 9 · Documentación | ⬜ pendiente | | | |

## Esfuerzo

La auditoría estima **58-100 jornadas** para las 45 tareas con 3+ ejecutores en
paralelo y ruta crítica de 15-27. Nuestro ritmo real es otro: propongo medir al
cerrar la Fase 1 y recalibrar, en vez de comprometer un calendario ahora.
