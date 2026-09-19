# Auditoría 2 — Integración legacy → Kafka/Debezium → app → SAP

| | |
|---|---|
| **id** | `poc-sap:auditoria-2:integracion-sap-kafka-cdc` |
| **Agente** | `integrator-systems` (modo revisión, solo lectura) |
| **Repo / HEAD** | `A:\Documentos\Code\poc-sap-integration-java` · `ffd86e7` (2026-09-14) |
| **Fecha** | 2026-09-18 |
| **Alcance** | contratos y garantías de los cuatro tramos; B3, A7, A16, A28 |

Términos: [at-least-once](../docs/glosario.md#at-least-once--exactly-once),
[outbox transaccional](../docs/glosario.md#outbox-transaccional),
[DLQ/DLT](../docs/glosario.md#dlq),
[idempotencia](../docs/glosario.md#idempotencia),
[circuit breaker](../docs/glosario.md#circuit-breaker-disyuntor).

---

## 1. Garantías por tramo

| | **1. legacy → outbox** | **2. outbox → Kafka** | **3. Kafka → app** | **4. app → SAP** |
|---|---|---|---|---|
| **Entrega** | **exactly-once** respecto al cambio de negocio: trigger `AFTER` en la misma transacción (`sqlserver/init.sql:66-118`, `postgresql/init.sql:84-87`) | **at-least-once**: offsets de Connect periódicos; un reinicio re-publica | **at-least-once**: ack mode por lote, sin transacciones | **at-least-once sin idempotencia en destino** — ver §2 |
| **Orden** | por fila; `id IDENTITY`/`BIGSERIAL` (`init.sql:57`, `:33`) | total por tabla: `tasks.max: 1` (`register-*.json:5`) | por partición; **clave = `entity_id`** (`register-sqlserver-customer.json:23,30-31`) → orden por entidad | secuencial dentro del mensaje; **sin orden entre features** |
| **Particiones** | — | topics **auto-creados** (`docker-compose.yml:51`), sin `num.partitions` ni bean `NewTopic` en todo el repo → **1 partición por defecto**. El orden global de hoy es accidental, no diseñado | ídem; `@KafkaListener` **sin `concurrency`** → 1 hilo | — |
| **Idempotencia** | n/a | n/a | dedupe local por `payloadHash` contra el **último** `SENT_SAP` (`SyncCustomerUseCase.java:87-91`; spec `idempotencia-y-dedupe.md` R-1) | **nadie**. `Idempotency-Key` viaja (`RestClientSapClient.java:183-185`) y S/4 no la honra (§2) |
| **Reintentos** | — | **ninguno**: `errors.retry.timeout` y `errors.tolerance` ausentes → defaults `0` / `none` → la *task* falla y para el CDC del dominio | `ExponentialBackOff(1000, 2.0)`, `setMaxAttempts(3)` (`KafkaErrorHandlingConfig.java:35-36`) → 1 entrega + 3 reintentos = **4 entregas**, esperas 1 s + 2 s + 4 s = **7 s** | Resilience4j `max-attempts: 3`, `initial-backoff-ms: 500`, ×2 (`SapIntegrationConfig.java:46-50`; `application-common.yml:77-79`) |
| **Presupuesto (s)** | — | — | 4 × (peor caso tramo 4) + 7 s | por llamada: 3 × 20 000 ms + (500+1000) = **61,5 s**; × `calls-per-message: 5` = **307,5 s (5 min 7,5 s)** contra `max.poll.interval.ms: 900 000` (`application-common.yml:72,76,134`) |
| **DLQ/DLT** | — | **no configurada** (`errors.deadletterqueue.topic.name` ausente) | `DeadLetterPublishingRecoverer` por defecto → **`outbox.CUSTOMER-dlt` / `outbox.ARTICLE-dlt`**, misma partición. **Nadie los consume**: `grep dlt` solo devuelve el comentario de `KafkaErrorHandlingConfig.java:16` | — |
| **Circuit breaker** | — | — | — | 50 % sobre ventana de 10, 30 s abierto (`SapIntegrationConfig.java:59-61`); envuelve **por fuera** del retry (`RestClientSapClient.java:147-148`) ✔ |

**A16 — cerrado.** El sufijo real es `-dlt` y toda la documentación viva ya lo
dice (`ADR-0006:24,31`, `INTEGRATION-PATTERNS.md:57`, `sdd/README.md:176`); las
únicas apariciones de `.DLT` están dentro del propio informe de auditoría del
2026-09-10, que es histórico. Confirmado contra la documentación de Spring
Kafka: *"the dead-letter record is sent to a topic named `<originalTopic>-dlt`
… and to the same partition as the original record"* — **fuente web**,
<https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html>
(consultado 2026-09-18). Consecuencia no documentada: **el topic DLT debe tener
al menos tantas particiones como el original**; hoy ambos son 1 por
auto-creación, pero subir particiones del topic de entrada rompería el DLT.

---

## 2. Idempotencia frente a S/4 (A7) — abierto y peor de lo que parece

**¿Honra S/4 Public Cloud la cabecera `Idempotency-Key` en OData V2?**
No he encontrado **ninguna** documentación de SAP que diga que sí. Lo que existe
es distinto: el *Idempotent Process Call* de SAP Integration Suite, que es un
almacén de deduplicación **en el middleware**, no en S/4
(<https://help.sap.com/docs/integration-suite/sap-integration-suite/idempotent-process-call-handles-duplicates-with-alternative-response>,
*fuente web*, 2026-09-18); e idempotencia por UUID en ciertas APIs SOAP, con
caché de 2-4 h. El estándar OASIS *Repeatable Requests v1.0* usa la cabecera
`Repeatability-Request-ID` y es **OData V4**
(<https://docs.oasis-open.org/odata/repeatable-requests/v1.0/cnprd01/repeatable-requests-v1.0-cnprd01.html>,
*fuente web*, 2026-09-18) — ni el nombre ni la versión coinciden con lo que
enviamos. La documentación de `API_BUSINESS_PARTNER` no menciona la cabecera
(<https://help.sap.com/docs/SAP_S4HANA_CLOUD/3c916ef10fc240c9afc594b346ffaf77/85043858ea0f9244e10000000a4450e5.html>,
*fuente web*, 2026-09-18). **Conclusión: la afirmación "S/4 la ignora" de R-7
(`resiliencia-cliente-sap.md:56`) es la hipótesis prudente, pero sigue siendo
"sin fuente confirmatoria"; el experimento que la cierra es §7 del
`CHECKLIST-TENANT-SAP.md` y no se ha ejecutado.**

**Escenario: POST de `A_BusinessPartnerAddress` con timeout de respuesta.**

1. `FeatureSyncPipeline.sync:61` → `BusinessPartnerAddressODataAdapter.send:38` → POST.
2. S/4 **crea la dirección** y la respuesta se pierde; salta el read timeout de
   20 s (`RestClientSapClient.java:109`, `application-common.yml:72`).
3. Resilience4j reintenta (intento 2) el **mismo POST** con la **misma**
   `Idempotency-Key` → **segunda dirección**.
4. Intento 3 → **tercera dirección**.
5. Agotados los intentos, `catch (Exception)` (`RestClientSapClient.java:165-168`)
   devuelve `SapResponse(0, …)`. No es excepción: es un **valor**.
6. `FeatureSyncPipeline:62` marca la línea `ADDRESS` en `SAP_ERROR`; el agregado
   termina en `SAP_ERROR` (`SyncCustomerUseCase:188`), la **imagen no se guarda**
   (`:149-154`) y se emite `partialFailure`.
7. El listener **retorna normalmente** → offset comprometido → **no hay
   reintento Kafka**. Bien: se detiene ahí.
8. **Pero** el siguiente evento CDC de esa entidad vuelve a entrar. `alreadySent`
   es falso (el último estado es `SAP_ERROR`, no `SENT_SAP`) y el atajo "sin
   cambios reales" tampoco dispara porque exige `lastCycleSent`
   (`SyncCustomerUseCase:96-98,133`). Ciclo completo → POST → direcciones 4, 5, 6.

**¿El dedupe por `payloadHash` lo evita o lo agrava?** Ni lo evita ni lo agrava
directamente, pero **da una falsa sensación de cobertura**: el atajo solo actúa
en el camino feliz, y el camino que produce duplicados es exactamente el que lo
deja inerte. El efecto neto es peor que "no hacer nada", porque el estado propio
dice `SAP_ERROR` ("SAP no tiene nada") mientras S/4 acumula N direcciones. Como
`AddressID` lo asigna SAP y no lo enviamos (§3), no hay clave que colisione: **no
existe ningún mecanismo, ni local ni remoto, que impida la multiplicación**. La
única cura es el upsert con lookup (PRD-11 / B3), que sigue sin implementar.

---

## 3. Estado de B3 hoy, adaptador a adaptador

`sap-api-models/specs/customer/API_BUSINESS_PARTNER.yaml` y
`API_APAR_SEPA_MANDATE_SRV.yaml` abiertos y verificados.

| Adaptador | HTTP | Entidad / ruta | Lookup previo | ETag / `If-Match` | Obligatorios de la spec que NO envía |
|---|---|---|---|---|---|
| `BusinessPartnerODataAdapter` | POST (`:39`), DELETE (`:50`) | `/…/API_BUSINESS_PARTNER/A_BusinessPartner` | **no** | **no** | `required: [BusinessPartner]` (YAML `:32624-32625`) → lo envía ✔. `BusinessPartnerGrouping="BPEE"` (`:57`) es un valor fijo sin validar contra el tenant |
| `BusinessPartnerAddressODataAdapter` | POST (`:38`) | `…/A_BusinessPartnerAddress` | **no** | **no** | `required: [BusinessPartner, AddressID]` (YAML `:33519-33521`) → **falta `AddressID`** |
| `BusinessPartnerContactODataAdapter` | POST (`:38`) | `…/A_BusinessPartnerContact` | **no** | **no** | `required: [RelationshipNumber, BusinessPartnerCompany, BusinessPartnerPerson, ValidityEndDate]` (YAML `:34165-34169`) → **faltan 3 de 4**. Además el cuerpo **ignora por completo** el `ContactData` recibido (`:41-46`): ni email, ni teléfono, ni web |
| `BusinessPartnerTaxODataAdapter` | POST (`:41`) | `…/A_BusinessPartnerTaxNumber` | **no** | **no** | `required: [BusinessPartner, BPTaxType]` (YAML `:34831-34833`) → completo ✔ |
| `BusinessPartnerBankODataAdapter` | POST (`:45`) | `…/A_BusinessPartnerBank` | **no** | **no** | `required: [BusinessPartner, BankIdentification]` (YAML `:33980-33982`) → completo ✔. `BankIdentification` fijo `"0001"` (`:49`); checklist §4 abierto (`BankNumber`) |
| `SepaMandateODataAdapter` | POST (`:60`), PATCH (`:68`) | `…/API_APAR_SEPA_MANDATE_SRV/SEPAMandateSet` y `(Creditor='…',SEPAMandate='…')` | **no** | **no** — el PATCH de revocación va **sin `If-Match`** | `required: [Creditor, SEPAMandate]` (YAML `:522`) → completo ✔ |
| `S4ArticleAdapter` | POST (`:33`) | `${sap.article.s4.path:/sap/opu/odata/sap/API_PRODUCT}` | **no** | **no** | La ruta es **incorrecta**: el `servers` de la spec es `…/sap/opu/odata/sap/API_PRODUCT_SRV` y la entidad `/A_Product` (`API_PRODUCT_SRV.yaml:36,78`). Y de los 5 campos del DTO, **`Description`, `Category` y `Status` no existen** en `A_ProductType-create` (solo `Product` y `BaseUnit`; `required: [Product]`, `:6630`) |

**Resumen de B3: sigue abierto en lo esencial.** Se cerró lo que la Fase 3.2
declaró (banco y mandato con contrato real). Sigue sin existir **ni un solo
lookup, ni un solo `If-Match`, ni un solo PATCH de actualización**: los seis
adaptadores OData de *customer* emiten POST puro. Y hay dos hallazgos que la
auditoría del 2026-09-10 no recogía: el contacto está **peor** de lo descrito
(no es que el cuerpo sea escaso — es que descarta el argumento), y el adaptador
de artículo tiene **ruta y tres campos inventados**.

Detalle transversal: los adaptadores OData de *customer* normalizan `null → ""`
con `n()` (p. ej. `BusinessPartnerAddressODataAdapter:52`), lo que **anula** el
`NON_NULL` de `SapJsonMapper` y reintroduce exactamente el defecto A19/C10 que
`S4ProductDto` corrigió: S/4 valida dominios y `""` no es "sin valor". Y todos
los `send(…, null)` postean `"{}"` contra entidades con campos obligatorios: 400
garantizado.

---

## 4. Los adaptadores BTP: un contrato propio, no un contrato

- **¿Contra qué contrato?** Contra ninguno externo. Los cinco `Btp*Adapter`
  postean a `/sap/btp/odata/Customer|CustomerAddress|CustomerContact|CustomerFiscal|CustomerBanking`
  con DTOs propios (`adapters/sap/dto/Btp*Dto.java`). **No hay spec OpenAPI**:
  `sap-api-models/specs/` solo contiene APIs oficiales de S/4 (article, customer,
  finance). El propio [ADR-0004](../docs/architecture/adr/0004-dos-familias-de-adaptadores-btp-y-odata.md)
  §4 lo dice con todas las letras: *"los paths `/sap/btp/odata/*` son un
  **contrato propuesto**, no un servicio real"*.
- **Son los adaptadores por defecto.** Todos los `sap.odata.*.enabled` valen
  `false` (`application-common.yml:23-32`) y los `Btp*` llevan
  `matchIfMissing = true`. Es decir: **arrancando el sistema tal cual, el 100 %
  de las escrituras va a un servicio que no existe.**
- **¿WireMock lo valida?** No puede: no hay contrato que validar. Los contract
  tests (`it/src/test/java/com/poc/sap/it/contract/`) montan el
  `RestClientSapClient` real (bien, cierra B6) pero el stub devuelve **201 a
  cualquier cuerpo** en la ruta (`BtpAddressContractTest:26-29`); lo que se
  verifica son los nombres de campo de **nuestro propio DTO** contra **nuestro
  propio stub**. Es un bucle cerrado: comprueba que el adaptador no cambia sin
  querer, no que SAP lo acepte.
- **Cobertura**: hay contract test para los 5 `Btp*`, para `S4Article`,
  `S4BankOData` y `SepaMandate`. **No hay ninguno** para
  `BusinessPartner(Address|Contact|Tax)ODataAdapter` ni para el BP — justo los
  tres con campos obligatorios ausentes.
- `S4ArticleContractTest:19` **repite la ruta equivocada** `/sap/opu/odata/sap/API_PRODUCT`:
  el test fija el error en lugar de detectarlo.

---

## 5. Kafka: clasificación de excepciones, DLT y presupuesto

**Lista exacta de NO reintentables** (`KafkaErrorHandlingConfig.java:27-30`):
`IllegalStateException`, `IllegalArgumentException`, `JsonProcessingException`.
Todo lo demás se reintenta.

- **`ConcurrentTransitionException`** (`extends RuntimeException`) → **no está
  clasificada** → se reintenta. Es el comportamiento correcto (es transitoria),
  pero **por omisión**: nadie lo declara ni lo prueba. Un refactor que la hiciera
  heredar de `IllegalStateException` la mandaría a la DLT sin que ningún test
  se ponga rojo.
- **`SapCircuitOpenException`** (`extends RuntimeException`) → **tampoco está
  clasificada** → se reintenta, que es lo que exige R-3 de
  `resiliencia-cliente-sap.md`. Mismo comentario: correcto por omisión.
- **A28 — cerrado.** Los dos listeners cortocircuitan el *tombstone*
  (`CustomerKafkaListener:46-51`, `ArticleKafkaListener:40-45`) y `valueOf`/
  `entityId` vacío producen `IllegalArgumentException`, ya no reintentable.

**¿En qué estado deja el agregado un mensaje que va a la DLT?** Depende del
camino, y **ningún fallo de SAP llega nunca a la DLT** (devuelven `SapResponse`,
no excepción):

| Camino | Estado del agregado al llegar a la DLT | ¿Se recupera solo? |
|---|---|---|
| JSON malformado (`JsonProcessingException`) | **sin tocar** — no se abrió ciclo | no; el evento se pierde |
| `operation` desconocida, `entityId` vacío, `Customer` nulo, `sap.sepa.creditor-id` sin configurar (`SepaMandateODataAdapter:102`) | sin tocar o a medias | no |
| Transición ilegal (`IllegalStateException` de `SyncStateMachine`) | el que dejó el ciclo anterior | no |
| Infra caída (Mongo/ES) | **`ERROR`** vía `markError` (`SyncCustomerUseCase:159-161,211-217`) | **sí**: `ERROR → RECEIVED` es legal (`SyncStateMachine:74`) y `beginCycle` lo es desde cualquier estado, así que **el siguiente evento CDC** lo reabre |

Y en todos los casos: **nadie consume el topic `-dlt`**, no hay métrica de
profundidad de DLT, y el único aviso es el `WARN` de `partialFailure` — que
solo se emite en el camino que *no* va a la DLT. La DLT es hoy un agujero
silencioso.

**`RetryBudgetGuard` — recálculo propio** (`RetryBudgetGuard.java:61-68`):
`backoff = 500 + 1000 = 1 500 ms`; `perCall = 3 × 20 000 + 1 500 = 61 500 ms`;
`total = 61 500 × 5 = 307 500 ms = 5 min 7,5 s` < `900 000 ms`. **La cifra del
guard cuadra con la configuración.** Lo que el guard **no** cuenta:

1. **El reintento CSRF.** `RestClientSapClient.exchange:151-155` ejecuta
   `resilient.get()` **una segunda vez** completa ante un `403 + x-csrf-token:
   Required`. Eso **duplica** el peor caso: 123 000 ms/llamada → **615 000 ms
   (10 min 15 s)** por mensaje, el 68 % del presupuesto.
2. **El fetch CSRF**, con su propio `HttpClient` y timeout de **30 s**
   (`S4CsrfTokenProvider:41,45,66`), **fuera** del retry y del circuit breaker,
   hasta dos veces por llamada (cache vacía + `invalidate()`). En el caso
   patológico 615 000 + 5 × 2 × 30 000 = **915 000 ms > 900 000 ms**: el
   presupuesto **se excede** y el guard no lo ve.
3. El `connect-timeout` de 3 s por intento.
4. Fetch JDBC del legacy, `save` en Mongo e `index` en Elasticsearch: sin timeout
   declarado en ningún sitio.
5. Las **4 entregas** del `DefaultErrorHandler`. Si el contenedor no pausa el
   consumidor durante el backoff (comportamiento a confirmar en Boot 4 /
   Spring Kafka 3.x con un test), el coste acumulado sería 4 × 307 500 + 7 000 =
   **20 min 37 s** contra 15 min. **Sin verificar — requiere test.**
6. El comentario de `application-common.yml:73-75` dice que las 5 llamadas son
   "cabecera del BP + 4 features", pero `SyncCustomerUseCase.sendFeatures:170-190`
   solo emite **4**. El 5 encaja por casualidad, no por diseño, y el mandato SEPA
   es una llamada más en su propio flujo.

---

## 6. CDC

- **SMT manual vs `EventRouter` (D-3, pendiente).** La cadena es
  `ExtractNewRecordState` → `ExtractField$Value(payload)` → `ExtractField$Key(entity_id)`
  → `RegexRouter` (`register-*.json:24-35`), con `StringConverter` en clave y
  valor. Funciona y está bien documentada (`debezium/README.md`), pero
  reimplementa lo que el `EventRouter` oficial da hecho: enrutado por columna
  `type`, cabeceras de trazabilidad, `id` del evento y expansión del payload.
  Con `RegexRouter regex: ".*"` el enrutado es **un topic fijo por conector**:
  añadir un segundo agregado a la misma outbox obliga a un conector más.
- **Opciones deprecadas.** `drop.tombstones` y `delete.handling.mode` siguen
  siendo válidas en Debezium 2.7 pero están *"scheduled for removal in a future
  release"*, sustituidas por `delete.tombstone.handling.mode` — **fuente web**,
  <https://debezium.io/documentation/reference/2.7/transformations/event-flattening.html>
  (consultado 2026-09-18).
- **Purga de la outbox: no existe.** Lo reconoce el propio
  `debezium/README.md` ("La outbox no se purga (PoC)"). Las dos tablas crecen sin
  límite, **sin índice sobre `created_at`** (`init.sql:56-63` / `:32-39`), y en
  SQL Server crecen además las *capture tables* de CDC. Cuando se añada el job
  de retención, los `DELETE` de la outbox los absorbe el SMT
  (`delete.handling.mode: drop` + `tombstones.on.delete: false`) ✔.
- **Esquema del payload (D-8, sin Schema Registry).** El contrato lo produce una
  **concatenación de cadenas en T-SQL** (`sqlserver/init.sql:85-88`): cualquier
  fallo de escapado en un `NVARCHAR` genera JSON inválido →
  `JsonProcessingException` → DLT directa y silenciosa. Postgres usa
  `jsonb_build_object` (`postgresql/init.sql:71-76`), que es seguro. Dos
  productores del mismo contrato lógico, dos técnicas con riesgo distinto, y
  ningún validador entre ellos y el consumidor.
- **Tombstones**: triple defensa — `tombstones.on.delete: false` en el conector,
  `drop.tombstones: true` en el SMT, y guarda `record.value() == null` en los dos
  listeners. Correcto.
- **Un DELETE en el legacy, de punta a punta:**
  - *Customer*: `DELETE FROM dbo.customers` → el trigger inserta una fila
    `operation='DELETE'` con el **payload de la fila borrada** (`init.sql:73-92`)
    → Debezium captura ese INSERT → `CustomerKafkaListener:60-61` →
    `DeleteCustomerUseCase` → `BusinessPartnerODataAdapter.delete:50` →
    `DELETE A_BusinessPartner('id')`. El propio `CHECKLIST-TENANT-SAP.md` §6
    anticipa que S/4 responderá **405 o 400** porque no borra BPs: el flujo
    terminaría en `SAP_ERROR` indefinidamente. Con la familia BTP por defecto, el
    DELETE va a `/sap/btp/odata/Customer('id')`, que no existe.
  - *Article*: `ArticleKafkaListener:54-58` registra un `WARN` y **retorna**. El
    borrado se pierde: sin estado, sin métrica, sin alerta, con el offset
    comprometido. **Pérdida de dato silenciosa.**

---

## 7. Riesgos priorizados

Severidad según [`skills/agent-routing`](../skills/agent-routing/SKILL.md) §1.

| # | Sev. | Riesgo | Qué comprobar en el tenant |
|---|---|---|---|
| R1 | **bloqueante** | **Duplicación en S/4 por POST sin upsert** (B3 + A7). Afecta al 100 % de las escrituras y a datos maestros; cada reintento y cada re-sincronización tras `SAP_ERROR` multiplica registros | `CHECKLIST-TENANT-SAP.md` §1 (PATCH parcial + `If-Match`), §2 (`AddressID`, deep insert) y §7 (repetir POST idéntico) — **están, y son la prioridad absoluta** |
| R2 | **bloqueante** | **La familia BTP es la activa por defecto** y apunta a un servicio que no existe (ADR-0004 §4). Un despliegue sin `SAP_ODATA_*_ENABLED=true` escribe al vacío | **Falta** sección en el checklist: no cubre BTP en absoluto. Alternativa: decidir ya si se retira `Btp*` o se invierte el default |
| R3 | **bloqueante** | **Contacto**: faltan 3 de 4 campos obligatorios y el cuerpo ignora email/teléfono/web → el 100 % de los POST de CONTACT fallará con 400 | §3 (`A_AddressEmailAddress` / `A_AddressPhoneNumber`) |
| R4 | atención | `A_BusinessPartnerAddress` sin `AddressID` (obligatorio en la spec) | §2 |
| R5 | atención | **Artículo**: ruta `API_PRODUCT` en vez de `API_PRODUCT_SRV/A_Product`, y 3 de 5 campos inexistentes en la spec → 404 / 400 seguro. El contract test consagra el error | **Falta**: el checklist no tiene sección de artículo. Añadir §8 |
| R6 | atención | Kafka Connect **sin DLQ ni `errors.tolerance`**: un registro malo detiene el CDC del dominio entero | n/a (infra) — prueba: inyectar un `payload` no-JSON en la outbox |
| R7 | atención | **Nadie consume `*-dlt`**, no hay métrica de profundidad ni reprocesador (OPS-2) | n/a |
| R8 | atención | `RetryBudgetGuard` ignora el reintento CSRF (×2), el fetch CSRF (30 s fuera de retry/CB) y las 4 entregas Kafka; en el caso patológico el peor caso **supera** `max.poll.interval.ms` | §0 (CSRF): medir latencia real del fetch y si los 403 `Required` son frecuentes |
| R9 | atención | **DELETE de artículo descartado en silencio** | n/a — decisión de negocio, no de tenant |
| R10 | atención | **DELETE de BP**: S/4 previsiblemente lo rechaza (405); la baja debería ser `BusinessPartnerIsBlocked` | §6 |
| R11 | atención | `SepaMandateODataAdapter.revoke` hace **PATCH sin `If-Match`**; y `requireCreditor()` lanza `IllegalStateException` → **DLT directa** por un error de configuración | §5 |
| R12 | observación | Outbox sin purga ni índice; *capture tables* de SQL Server sin política | n/a |
| R13 | observación | Sin Schema Registry (D-8); el contrato lo produce concatenación de cadenas en T-SQL | n/a |
| R14 | observación | 1 partición por auto-creación; el DLT hereda el número de partición | n/a |
| R15 | observación | `n() → ""` en los adaptadores OData de customer reintroduce A19/C10 | §1 (ver si S/4 rechaza `""` en campos con dominio) |
| R16 | observación | `auto-offset-reset: earliest` + `snapshot.mode: initial` → un grupo nuevo reprocesa todo el historial; el dedupe solo mira el **último** `SENT_SAP`, así que una secuencia A→B→A se reenvía entera | n/a |
| R17 | observación | Opciones de SMT deprecadas en Debezium 2.7 | n/a |

---

## 8. Lo que NO verifiqué

- **No hay tenant SAP.** Ninguna afirmación sobre el comportamiento real de S/4
  (si acepta `Idempotency-Key`, si el PATCH parcial funciona, si `""` se rechaza,
  si el DELETE de BP da 405) está comprobada. Lo que digo sale de las specs
  OpenAPI del repo, del código, o de fuentes web citadas.
- **No ejecuté nada**: ni `mvn`, ni `docker compose`, ni los tests, ni los
  conectores. Las cifras de presupuesto son cálculo sobre la configuración
  declarada, no medición.
- **No verifiqué el comportamiento de `DefaultErrorHandler` bajo Spring Boot 4**
  respecto a si pausa el consumidor durante el backoff (afecta a R8, punto 5).
  Es un test de una tarde con Testcontainers.
- **No revisé** los flujos REST síncronos (`SyncCustomerController`, etc.), la
  seguridad, ni los dominios `supplier` y `finance`.
- **No auditar el número real de particiones** de los topics: se auto-crean y no
  hay fichero que lo fije; asumo el default del broker.
