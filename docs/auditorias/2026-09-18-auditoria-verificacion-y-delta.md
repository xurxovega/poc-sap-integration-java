# Auditoría 2 — 2026-09-18 — Verificación independiente del cierre y delta sobre el código nuevo

> Segunda auditoría del equipo de agentes de `agents/` sobre `poc-sap-integration-java`,
> HEAD `ffd86e7` (2026-09-14). **Solo lectura**: no se ha ejecutado `mvn`, `docker`, `kubectl`,
> scripts del repo ni `git` de escritura; no se ha editado ningún fichero existente. Las únicas
> escrituras son este documento y la carpeta de anexos [`2026-09-18-anexos/`](2026-09-18-anexos/)
> (seis informes de detalle, uno por agente, con todas las citas `fichero:línea`).
> Este documento es la **verificación por una sesión distinta** que exigía el
> [plan de acción](2026-09-10-plan-de-accion.md) y que el
> [cierre de bloqueantes](2026-09-12-cierre-bloqueantes.md) dejó pendiente.

---

## 0. Ficha

| Campo | Valor |
|---|---|
| Petición | Analizar el código partiendo de la auditoría del 2026-09-10 y de su cierre; no aplicar cambios; entregar un fichero de auditoría con plan de revisión |
| Clasificación | `Diseño y prototipado` (alias Auditoría; el tipo no existe en la taxonomía, hueco M1 del informe anterior) · severidad `atencion` por defecto · circuito **completo acotado**, fan-out en solo lectura |
| id | `poc-sap:auditoria-2:verificacion-independiente-y-delta` |
| Puertas activadas | PII (RGPD) · cobros (mandatos SEPA → idempotencia) · integración con terceros (SAP → `integrator-systems`) |
| Rango analizado | `00bbbae^..ffd86e7`: 24 commits (2026-09-11 → 2026-09-14), 79 ficheros de producción (+2.447 / −1.131), 174 ficheros en total |
| Agentes (rol → modelo) | `docs-writer` revisión → Sonnet · `qa-tester` inventario → Sonnet · `qa-tester` verificación de cierre → Opus · `dev-implementer` revisión → Opus · `integrator-systems` → Opus · `auditor-monitor` + seguridad y PII → Opus · manager y consolidación → Fable 5.1 |
| Oleadas | 3 + 2 + 1 (lección M8 del informe anterior: no más de 5 pesados a la vez) |
| Coste | ≈ 841 k tokens en subagentes (*dato del harness*), 182 llamadas a herramientas, ~37 min de agente acumulados |
| Verificación de retornos | El manager comprobó en el código al menos dos citas de cada informe antes de aceptarlo (regla 10: nadie valida su propio trabajo; aquí nadie valida el de otro sin abrir el fichero) |

---

## 1. Resumen ejecutivo

**Veredicto: el cierre del 2026-09-12 es honesto y el sistema ha dado un salto real, pero todavía no puede escribir en un S/4 de verdad sin duplicar ni perder datos.** De los 14 bloqueantes del 2026-09-10, ninguno está declarado cerrado sin que el código lo respalde. Sin embargo, el mismo código que los cierra abre **tres bloqueantes nuevos de corrección** (fallo parcial sin aviso, colisión de concurrencia a DLT sin reintento, reintento de POST no idempotentes), y la revisión de contratos SAP confirma **tres bloqueantes de integración** que el ciclo anterior había dejado como "bloqueados por el tenant" y que en realidad se pueden atacar hoy (familia BTP activa por defecto contra un servicio inexistente, contacto con payload vacío, artículo con ruta y campos inventados). En seguridad, lo que se pidió (autenticación) está hecho y bien; lo que nadie pidió (retención, cifrado interno, rotación) sigue igual que el 10 de septiembre.

| Eje | Nota 2026-09-10 | Nota 2026-09-18 | Una frase |
|---|---|---|---|
| Arquitectura y diseño | 6 | **8** | `application` sin Spring, pipeline por feature único, `beginCycle`/`advance` separados. Queda el entrelazado de ciclos y el `from` decorativo |
| Integración SAP / Kafka / CDC | 5 | **4** | Baja porque ahora se ve más: BTP por defecto sin servicio real, contacto vacío, artículo contra ruta inexistente, POST reintentado. Banco y mandato sí tienen contrato real |
| Calidad y tests | 6 | **7** | 314 tests, 0 muertos, contract tests reales, 21-23 % citan AC. Pero `article` sigue con el puerto de estado mockeado (recurrencia de fingerprint) y 4 de 6 adaptadores OData sin test |
| Observabilidad | 3 | **6** | Métricas completas y coherentes con el spec; sin alertas, sin trazas activas, sin `traceId` en logs |
| Seguridad | 2 | **6** | Keycloak + `@PreAuthorize` vigilado por ArchUnit + PII enmascarada. Sin `aud`, `AccessScope` falla en abierto, sin TLS interno, credencial sin rotar |
| Operación e infraestructura | 3 | **5** | Manifiestos k8s con probes y `securityContext`; sin Ingress, NetworkPolicy, PDB, HPA, digest; nadie consume la DLT |
| Documentación | 5 | **7** | Cero enlaces rotos, ADRs, incidencias, runbooks. Pero `AGENTS.md` vuelve a contradecir al código en tres puntos (recurrencia de B8) |

### Los cinco hallazgos que importan (si solo se lee esto)

1. **`FeatureSyncPipeline.sync` no captura excepciones de `sapPort.send`**: con el circuito abierto, la línea de feature queda en `SENDING_SAP`, el bucle del orquestador aborta y **el aviso de sincronización parcial del ADR-0010 nunca se emite** en su caso motivador (§3, 2B-1).
2. **Los adaptadores BTP son los activos por defecto** y apuntan a `/sap/btp/odata/*`, un contrato que el proyecto se inventó y que ADR-0004 §4 reconoce sin servicio real. Un despliegue con la configuración por defecto escribe al vacío (§3, 2B-4).
3. **El retry HTTP reintenta POST**: un timeout de respuesta con S/4 ya procesando crea hasta 3 Business Partners (6 con el reintento CSRF). `Idempotency-Key` no lo evita (A7 sigue sin fuente que lo respalde) (§3, 2B-3).
4. **Dos eventos del mismo cliente en ráfaga sobre dos instancias** producen una `IllegalStateException` declarada no reintentable: el mensaje va a la DLT sin un solo reintento y nadie la consume (§3, 2B-2).
5. **La credencial real del tenant sigue sin rotar** ocho días después de detectarse; `.gitignore` protege el repositorio, no la credencial (§3, 2B-7). Es la única acción de esta lista que no requiere código.

---

## 2. Verificación independiente del cierre B1–B14

Hecha por `qa-tester` (Opus) contra el código actual, sin conocer el razonamiento de quien cerró. Detalle con `fichero:línea` y test protector en el [anexo 01](2026-09-18-anexos/01-verificacion-cierre-B1-B14.md).

| # | Declarado | Veredicto | Matiz que el cierre no dice |
|---|---|---|---|
| B1 | cerrado | **CONFIRMADO en diseño, PARCIAL en cobertura** | El test de re-sync desde `SAP_ERROR` y el fake con la máquina real solo existen en `customer`; `article` sigue con el puerto de estado mockeado |
| B2 | cerrado | **CONFIRMADO** | `SENDING_SAP` es estado de entrada, `DELETE` HTTP real, imagen bloqueada. Queda decidir si la baja en S/4 es `DELETE` o `BusinessPartnerIsBlocked` (2A-9) |
| B3 | parcial | **PARCIAL, coincide** | Banco y mandato con contrato real. Dirección sin `AddressID`, contacto sin email/teléfono y **sin ningún test** |
| B4 | cerrado | **CONFIRMADO en código** | No verificable contra el Keycloak corporativo. Sin validación de `aud` (2A-1) |
| B5 | mitigado | **CONFIRMADO como mitigado, no cerrado** | Credencial no rotada; el directorio sigue en disco |
| B6 | cerrado | **CONFIRMADO** | El cliente real contra WireMock; cada test instancia el adaptador de producción y asevera método, path, cabeceras y cuerpo |
| B7 | cerrado | **CONFIRMADO** | Barrido de las 87 clases de test: ninguna fuera de los patrones. `TestCountMatchesDocsTest` cuenta anotaciones, no ejecuciones: la red real es el patrón de nombres más la CI |
| B8 | cerrado | **CONFIRMADO** | La afirmación concreta se corrigió; el **patrón** recurre (§5) |
| B9 | cerrado | **CONFIRMADO** | 0 coincidencias de los adaptadores fantasma en `TECH.md` |
| B10 | reformulado | **CONFIRMADO como reformulado** | El resumen del cierre lo cuenta entre los "12 cerrados" |
| B11 | cerrado | **CONFIRMADO** | `seq` monótona, índice único parcial creado en producción, test de escritores concurrentes contra Mongo real |
| B12 | cerrado | **PARCIAL** | El `try` empieza en `VALID`: un fallo entre `RECEIVED` y `VALID` sigue dejando la entidad en `FETCHING`/`VALIDATING`. Sin test en `article` |
| B13 | cerrado | **CONFIRMADO con matiz** | El CB envuelve al retry y la excepción llega al listener. Solo probado a nivel de cliente; `BusinessPartnerReadAdapter` sigue capturando `Exception` |
| B14 | cerrado | **CONFIRMADO** | Índice de fingerprints con la regla de la segunda recurrencia |

**Recuento**: 8 confirmados, 3 confirmados con el matiz con que ya se declaraban (B4, B5, B10), 3 parciales (B1, B3, B12), **0 no confirmados**.

**Fases del plan**: todas las declaradas hechas tienen su código en HEAD. Única discrepancia: el cierre dice "Fase 7 pendiente, única que no ha empezado" y el plan dice "Fase 7 hecha, `d9ffce1`/`27fbbfc`". **El código respalda al plan**; el cierre está desactualizado.

---

## 3. Hallazgos nuevos, consolidados y priorizados

Deduplicados entre los seis informes. Numeración `2B` / `2A` / `2O` (ciclo 2). Cada uno lleva su *fingerprint* de causa raíz para el [índice de incidencias](../incidencias/README.md). Origen: **DEV** = anexo 04, **INT** = anexo 05, **SEC** = anexo 06, **QA** = anexos 01 y 03, **DOC** = anexo 02.

### 3.1 Bloqueantes (7)

| # | Hallazgo | Evidencia | Fingerprint | Origen |
|---|---|---|---|---|
| **2B-1** | `FeatureSyncPipeline.sync` no captura excepciones de `sapPort.send`. Con `SapCircuitOpenException` la línea `C1:ADDRESS` queda en `SENDING_SAP` (hasta el siguiente evento, que la recupera por `beginCycle`), el bucle de `sendFeatures` aborta, las features restantes no se intentan ni se miden, y **`notifications.partialFailure` no se llama**: el ADR-0010 se incumple justo en el caso que lo motivó. El agregado sí pasa a `ERROR` por el `try` de B12 | `common/.../application/FeatureSyncPipeline.java:60-64` · `customer/.../general/SyncCustomerUseCase.java:179-193` | `feature-pipeline:excepcion-en-send:sin-aviso-parcial` | DEV (verificado por el manager) |
| **2B-2** | Dos instancias con lecturas desfasadas de la cabecera producen `IllegalStateException` en `advance` (transición no permitida), que **está declarada no reintentable** → DLT sin reintento. El índice único sobre `seq` solo cubre la ventana estrecha; `ConcurrentTransitionException` sí se reintenta, pero por omisión (no está declarada ni probada). No hay lock por entidad ni identificador de ciclo en `SyncStateDoc`, así que dos ciclos en vuelo se entrelazan y el WARN es lo único que queda | `common/.../kafka/KafkaErrorHandlingConfig.java:27-30` · `common/.../domain/SyncStateMachine.java:112` · `common/.../adapters/persistence/MongoSyncStateRepository.java:52-66` | `sync-state:lectura-desfasada:dlt-sin-reintento` | DEV + QA (verificado) |
| **2B-3** | El retry de Resilience4j envuelve también los POST. Un read-timeout con S/4 ya procesando **duplica el Business Partner** (hasta 3 veces; 6 con el reintento CSRF). `Idempotency-Key` no protege: A7 sigue abierto y **sin fuente** que confirme que S/4 OData V2 lo honre. En `delete()` la cabecera ni se envía | `common/.../sap/RestClientSapClient.java:147-148,154,183-185` | `sap-client:retry-post:duplicado` | DEV + INT (verificado) |
| **2B-4** | **La familia `Btp*Adapter` es la activa por defecto** (`sap.odata.<feature>.enabled=false` salvo que se diga lo contrario) y apunta a `/sap/btp/odata/*`, contrato definido por el proyecto sin spec OpenAPI ni servicio real (lo admite ADR-0004 §4). WireMock lo valida porque el proyecto lo escribió. Un despliegue con el `ConfigMap` de `deploy/k8s/base` tal cual **escribe al vacío**. El checklist del tenant no tiene sección BTP | `customer/.../adapters/sap/Btp*Adapter.java` (`@ConditionalOnProperty havingValue="false", matchIfMissing=true`) · `docs/architecture/adr/0004-*.md` §4 · `deploy/k8s/base/common.yaml` | `btp:default-activo:servicio-inexistente` | INT |
| **2B-5** | `BusinessPartnerContactODataAdapter` **descarta el `ContactData`** que recibe: el payload solo lleva `BusinessPartnerCompany` y `RelationshipCategory`; faltan 3 de 4 campos obligatorios de la spec. El 100 % de los POST de CONTACT fallará con 400 | `customer/.../odata/BusinessPartnerContactODataAdapter.java:42-46` · `sap-api-models/specs/customer/API_BUSINESS_PARTNER.*` | `odata:contact:payload-vacio` | INT (verificado) |
| **2B-6** | Sin upsert en ninguno de los seis adaptadores OData: **ni un lookup, ni un `If-Match`, ni un PATCH de actualización**. Cada re-sincronización tras `SAP_ERROR` y cada reintento (2B-3) **crea** en vez de actualizar. Es B3 sin la excusa del tenant: el diseño de PRD-11 puede hacerse hoy y solo la comprobación de PATCH parcial depende del tenant | `customer/.../odata/*ODataAdapter.java` · `docs/MEJORAS-Y-PROPUESTAS.md` PRD-11 | `odata:post-sin-upsert:duplicado` | INT + QA |
| **2B-7** | **Credencial real de tenant S/4 sin rotar** desde el 2026-09-10 (SEC-4, TODO). `.gitignore` protege el repositorio, no la credencial: un *communication user* de S/4 es autenticación contra un sistema de facturación. El directorio sigue en disco | `.gitignore:47-49` · `2026-09-12-cierre-bloqueantes.md:14` · `docs/MEJORAS-Y-PROPUESTAS.md` SEC-4 | `secreto:spike:no-rotado` | SEC + QA |

### 3.2 Atención (16)

| # | Hallazgo | Evidencia | Origen |
|---|---|---|---|
| 2A-1 | Sin validación de `aud` en el resource server: cualquier token del realm con rol `sap-*` entra, venga del cliente que venga | `common/.../security/ApiSecurityConfig.java:48-65` (solo `issuer-uri`) | SEC |
| 2A-2 | Nada impide técnicamente `APP_SECURITY_ENABLED=false` fuera de local; en modo abierto el anónimo recibe superadmin. `AccessScope.canSeeSensitiveData(null)` devuelve `true`: **falla en abierto** y sirve PII sin enmascarar | `ApiSecurityConfig.java:71-80` · `common/.../security/AccessScope.java:27-30` | SEC (verificado) |
| 2A-3 | PII (IBAN, NIF, email, teléfono) **sin cifrado en tránsito ni autenticación** hacia Mongo, Elasticsearch y Kafka, y **sin NetworkPolicy**. Elasticsearch por `http://` en el `ConfigMap` | `deploy/k8s/base/common.yaml:15-16` · `customer-app.yaml:97` · `external-services/docker-compose.yml:210` | SEC (verificado) |
| 2A-4 | **Sin retención ni borrado RGPD** en ningún almacén con PII (imagen Mongo, `sync_state`, histórico ES que guarda un documento **por intento**, topics outbox y `-dlt`, `sap.sync.alerts`). Art. 5.1.e incumplido antes del primer dato real. Es decisión de negocio (D-11) pero el **mecanismo** (TTL/ILM) es técnico y no existe | `customer/.../index/CustomerHistoryDoc.java:17,55-57` · `external-services/mongodb/init.js:5-37` · `RUNBOOKS.md:121-124` | SEC |
| 2A-5 | El dedupe contra el último `SENT_SAP` **ignora que un ciclo posterior terminó en `SAP_ERROR` con parte del cliente ya en SAP**: secuencia `H1 ok → H2 parcial → H1` deja al cliente desincronizado mientras el sistema afirma que está en sincronía. Es A1 reintroducida por el camino del fallo parcial | `MongoSyncStateRepository.java:80-90` · `SyncCustomerUseCase.java:90-94` | DEV |
| 2A-6 | `POST /customers/sync` con `operation=DELETE` **no da de baja**: el controlador siempre llama al use case de sync, que nunca lee `operation`; responde `200 {"state":"ERROR"}`. El listener Kafka sí enruta. A29 vivo y peor | `customer/.../web/SyncCustomerController.java:41,45` vs `customer/.../kafka/CustomerKafkaListener.java:60-61` | DEV |
| 2A-7 | `SepaMandateODataAdapter.statusOf(null)` devuelve **ACTIVO**: un mandato cuyo estado se pierde en el mapeo se reactiva en SAP y habilita el cobro. Patrón A19 con consecuencias financieras. Además `revoke` hace PATCH sin `If-Match` y `requireCreditor()` lanza `IllegalStateException` → DLT directa por un error de configuración | `customer/.../odata/SepaMandateODataAdapter.java:86-93` | DEV + INT |
| 2A-8 | **Artículo**: ruta `/sap/opu/odata/sap/API_PRODUCT` en vez de `API_PRODUCT_SRV/A_Product`, y `Description`/`Category`/`Status` no existen en la spec de `A_Product`. 404/400 seguro; el contract test consagra el error. El checklist del tenant no tiene sección de artículo | `article/.../sap/S4ArticleAdapter.java:25` · `article/.../sap/dto/S4ProductDto.java:14-17` · `sap-api-models/specs/article/API_PRODUCT_SRV.yaml:78` | INT (verificado) |
| 2A-9 | Baja de BP: S/4 previsiblemente rechaza `DELETE` (405); la baja real es `BusinessPartnerIsBlocked`. El DELETE de **artículo se descarta en silencio** | `customer/.../odata/BusinessPartnerODataAdapter.java` · `article/.../kafka/ArticleKafkaListener.java` | INT |
| 2A-10 | Kafka Connect **sin `errors.tolerance` ni DLQ**: un registro malo en la outbox detiene el CDC del dominio entero | `external-services/debezium/*.json` (grep 0) | INT (verificado) |
| 2A-11 | **Nadie consume `*-dlt`**, no hay métrica de profundidad ni reprocesador (OPS-2); `sap.sync.alerts` tampoco tiene consumidor (OPS-8) y lleva `entityId` reidentificable | `RUNBOOKS.md:69-70` · `KafkaSyncNotificationAdapter.java:48,54-63` | INT + SEC |
| 2A-12 | `RetryBudgetGuard` calcula bien sus 307.500 ms pero **ignora** el reintento CSRF (×2), el fetch CSRF (30 s fuera de retry y CB) y las 4 entregas del `DefaultErrorHandler`: en el caso patológico el peor caso supera `max.poll.interval.ms` (recálculo del equipo, anexo 05 §5) | `common/.../sap/RetryBudgetGuard.java` · `application-common.yml` | INT |
| 2A-13 | **`article` sigue con el puerto de estado mockeado**: `InMemoryStateRepo` solo existe en `customer/src/test`. Ni test de re-sync desde `SAP_ERROR` ni de fallo tras `VALID` en artículo. **Recurrencia (5.ª) del fingerprint `test:puerto-mockeado-oculta-invariante`** → por la regla del índice, tarea de diseño, no arreglo puntual | `article/src/test/.../SyncArticleUseCaseTest.java:32-34` · `grep InMemoryStateRepo`: 4 ficheros, todos en `customer` | QA (verificado) |
| 2A-14 | 4 de 6 adaptadores OData **sin test propio** (BP, Address, Contact, Tax): exactamente los que tienen los defectos 2B-5 y 2B-6 | `customer/src/test/.../odata/` solo tiene `Bank` y `SepaMandate` | QA (verificado) |
| 2A-15 | Sin Ingress ni TLS en los manifiestos (solo prosa en `deploy/README.md`); sin PDB, HPA, `imagePullSecrets`; imagen por tag mutable con `IfNotPresent`; `automountServiceAccountToken` no desactivado y ServiceAccount compartido | `deploy/k8s/base/*.yaml` · `overlays/*/kustomization.yaml:9,12` | SEC (verificado: sin `kind: Ingress` ni `NetworkPolicy`) |
| 2A-16 | Cero alertas definidas y sin `traceId`/MDC en logs mientras el trazado esté apagado: métricas correctas que nadie vigila y logs sin correlación | `docs/sdd/common/observabilidad.md:31` · grep `MDC|traceId`: 0 | SEC |

### 3.3 Observación (12)

- `MongoSyncStateRepository.transition()` recalcula `from` desde la cabecera e ignora `t.from()`; `SyncStateDoc` no tiene campo `from` y `toTransition()` devuelve `null` siempre (A34 vivo). Validar contra la cabecera real es correcto; lo que falta es **comparar lo declarado con lo real**, que distinguiría 2B-2 de un bug de programación. `MongoSyncStateRepository.java:61-66`, `SyncStateDoc.java:32-43`.
- `origin` persistido sigue diciendo el nombre de la feature, no de dónde vino el evento (A34).
- `SepaMandateODataAdapter` se crea siempre y falla en caliente si falta `SAP_SEPA_CREDITOR_ID`.
- `CustomerStateUseCase` lee el historial completo cinco veces por consulta.
- `LegacyCredentialsGuard` rechaza contraseñas que contengan `${`.
- `Instant.now()` sin `Clock` en toda `application`; `@Value` en 19 ficheros y **cero** `@ConfigurationProperties`; beans sin llamadores (`DeleteMandateUseCase`, los cuatro `Validate*UseCase`, `BusinessPartnerReadAdapter`).
- `n() → ""` en los adaptadores OData de customer reintroduce A19/C10 (nulos como cadena vacía).
- Outbox sin purga ni índice; sin Schema Registry (D-8); 1 partición por autocreación; SMT deprecadas en Debezium 2.7; `auto-offset-reset: earliest` + `snapshot.mode: initial` reprocesa todo el historial con un grupo nuevo.
- `scripts/env/local.env` versionado por excepción con credenciales locales; `readOnlyRootFilesystem: false`; `name` y `address` fuera del enmascarado por decisión "B2B" sin firma de legal.
- 5 `@Mock` sobre clases concretas (9 apariciones, todas use cases) frente a 27 antes.
- 5 specs con AC sin cita en el Javadoc de su test (el mapeo por nombre en §9 sí existe; falta la doble cita que exige `AGENTS.md` §1.5).
- El cierre fecha D-2 el 14-09 dentro de un documento "a 12-09" y cuenta B10 como cerrado siendo reformulado.

---

## 4. Hallazgos previos que siguen vivos

| # anterior | Estado | Dónde | Nota |
|---|---|---|---|
| A7 `Idempotency-Key` en OData V2 | **VIVO, sin fuente** | `RestClientSapClient.java:183-185` | Ni la búsqueda web ni la doc de SAP lo confirman ni lo desmienten. El experimento que lo cierra es §7 del checklist del tenant. Con 2B-3 pasa de teórico a práctico |
| A19 lógica de negocio en adaptadores | **VIVO** | `SqlServerCustomerRepository`, `Btp*Adapter`, `SepaMandateODataAdapter` | Ahora con 2A-7 como caso con coste |
| A28 `@ControllerAdvice`/`ProblemDetail`/`@Valid` | **VIVO** | cero ocurrencias en `src/main` | |
| A29 enrutado DELETE divergente REST/Kafka | **VIVO y peor** | 2A-6 | |
| A30 CSRF | **CERRADO** | `S4CsrfTokenProvider` | Bien resuelto: misma `Authorization`, solo 403 `Required` refresca |
| A32 Jackson 2/3 mezclados | **VIVO** | listeners | |
| A33 `Status.valueOf` sin tolerancia | **VIVO** | `article` | Datos legacy sucios tumban el pipeline |
| A34 `origin` falso, `from` decorativo | **VIVO** | §3.3 | |
| A16 topic DLT | **CERRADO** | `-dlt` coherente en código y docs | |
| A28-bis listeners reintentando no transitorios | **CERRADO** | tombstone, `valueOf`, operación desconocida | |
| Lista O: método HTTP como String, `localhost:8080` | **CERRADOS** | | |

---

## 5. Incoherencias documentales (recurrencia de B8)

Detalle en el [anexo 02](2026-09-18-anexos/02-coherencia-docs-codigo.md). Cero enlaces rotos en toda la documentación. Las cinco contradicciones que **desorientan a un agente que siga la instrucción**:

| Documento afirma | Código dice | Cita |
|---|---|---|
| "Sin autenticación todavía — brecha abierta" | Keycloak + `@PreAuthorize` en 9 endpoints, vigilado por ArchUnit | `AGENTS.md:237` |
| Tabla de endpoints sin `GET /customers/{id}/state` | Existe y es la pieza clave del ADR-0010 | `AGENTS.md:228-236` vs `CustomerStateController.java:28` |
| `IngestionPort` "código muerto pendiente de retirar" | Borrado el 2026-09-12; 0 ocurrencias | `AGENTS.md:220`, `TECH.md:95,106`, `GLOSSARY.md:66` |
| "**sin** starter OTel — se usa el javaagent" | `spring-boot-starter-opentelemetry` en el POM y ADR-0009 aceptada | `AGENTS.md:299-300,306`, `GLOSSARY.md:272-278` (se contradice entre entradas vecinas) |
| "Fase 7 pendiente, única que no ha empezado" | Hecha el 2026-09-12 con dos commits | `2026-09-12-cierre-bloqueantes.md:39` vs `plan-de-accion.md:561` |

**Lo que se aprende**: B8 se corrigió como *afirmación* y recurre como *patrón*. `AGENTS.md` es el fichero que más se lee y el que menos se actualiza, porque no hay nada que rompa cuando miente. Es la **segunda recurrencia** del fingerprint `docs:agents-md:estado-caducado` → tarea de diseño (C2-6): un test que cruce afirmaciones verificables de `AGENTS.md` con el código (endpoints, puertos, starters), como ya hace `TestCountMatchesDocsTest` con la cifra de tests.

---

## 6. Estado de la suite de tests (cifras contadas, no estimadas)

Detalle en el [anexo 03](2026-09-18-anexos/03-inventario-tests.md). Comando de recuento: `grep -rn "^\s*@Test\b" --include=*.java` sobre `src/test` de cada módulo.

| Medida | 2026-09-10 | 2026-09-18 |
|---|---|---|
| `@Test` declarados | 245 (docs decían 211/240) | **314** (common 103, customer 150, article 45, it 16); coincide con `TESTING.md` §1 |
| Tests que nunca corren | 4 | **0** (barrido de 87 clases contra los patrones surefire/failsafe) |
| Tests que citan un `AC-n` | 2 % | **21–23 %** (65–72) |
| `@Mock` sobre clases concretas | 27 | **9** apariciones, 5 tipos, todos use cases |
| Contract tests que ejercitan producción | 0 | **9 de 9** (cliente real contra WireMock; aseveran método, path, cabeceras y cuerpo) |
| Tests gateados por Docker | — | 2 (`InfrastructureSmokeIT`, `SyncStateMongoIT`); la CI los ejecuta en un job aparte |
| Adaptadores OData con test propio | — | **2 de 6** |
| Fake con la máquina real (`InMemoryStateRepo`) | no existía | solo en `customer` |
| E2E app completa contra infra real | no | **no** (TEST-1 / TEST-4 siguen abiertos; dos incidencias solo se vieron en vivo por esto) |

**No verificado**: que los 314 estén en verde. Nadie ejecutó la suite en esta auditoría. Es el primer paso del plan (C2-0).

---

## 7. Fortalezas (lo que hay que conservar)

- **`beginCycle`/`advance` separados** con test de propiedad estados × entradas: el fingerprint de la reentrada no puede volver por la misma puerta.
- **`FeatureSyncPipeline<D>` y `SyncCycleRecorder`**: el recorrido de una feature se escribe una vez. 2B-1 se arregla en un solo sitio precisamente por esto.
- **`application` sin Spring ni Micrometer**, vigilado por ArchUnit; use cases como `@Bean` en `bootstrap`.
- **Seguridad de API**: matriz endpoint × rol 9 de 9 coherente con el spec; `EndpointsDeclareAccessTest` impide un endpoint sin `@PreAuthorize`; tres guards de arranque que fallan pronto y explicados.
- **Versión optimista por `seq`** con índice único parcial y test contra Mongo real.
- **Contract tests reales**, CI con y sin Docker, wrapper, enforcer, SBOM, Dependabot, imágenes del compose por digest.
- **Memoria del proyecto**: ADRs, incidencias con fingerprints, runbooks, aptitud para producción, checklist del tenant. Esta auditoría ha podido hacerse en una tarde gracias a eso.
- **Honestidad del cierre**: ningún bloqueante declarado cerrado que el código desmienta.

---

## 8. Plan de revisión — ciclo 2

Mismo formato que el [plan del ciclo 1](2026-09-10-plan-de-accion.md): una sesión puede ejecutar una fase sin contexto previo; el estado se lleva en la tabla de §10. Orden por criticidad y por dependencias, no por comodidad. **Todo se hace con TDD: el test que se nombra va primero y en rojo.**

### C2-0 · Verificación ejecutable y verdad documental — antes de tocar código

*Agentes*: `qa-tester` + `docs-writer`. *Esfuerzo*: ½ jornada (*estimación del equipo*).

1. Ejecutar `./mvnw -B -ntp verify` y `./mvnw -B -ntp -pl it -am verify -Ddocker.available=true` (comandos de `AGENTS.md` §2.8 y `ci.yml`). Anotar el resultado real en la tabla de §10. **Sin esto, ninguna fase posterior se da por verificada.**
2. Corregir las cinco contradicciones de §5 en `AGENTS.md`, `TECH.md`, `GLOSSARY.md` y el cierre. Actualizar `cierre-bloqueantes.md` con la Fase 7 y con el matiz de B1/B12.
3. Registrar los fingerprints nuevos de §3 en `docs/incidencias/README.md`, y la 5.ª recurrencia de `test:puerto-mockeado-oculta-invariante`.

*Aceptación*: suite ejecutada con cifra real de pasados/fallados; `AGENTS.md` sin ninguna afirmación de §5; índice de fingerprints al día.

### C2-1 · Los tres bloqueantes de corrección (2B-1, 2B-2, 2B-3) y 2A-5

*Agentes*: `dev-implementer` → `qa-tester`. *Esfuerzo*: 2–3 jornadas. Depende de C2-0.1.

| Hallazgo | Test primero (rojo) | Arreglo en una frase |
|---|---|---|
| 2B-1 | `FeatureSyncPipelineTest#sendThrowingLeavesLineInSapErrorAndPropagates` y `SyncCustomerUseCaseTest#circuitOpenInOneFeatureStillNotifiesPartialFailure` | `try/catch` alrededor de `sapPort.send` que avance la línea a `SAP_ERROR` (o `COMMUNICATION_ERROR`) y relance; el orquestador captura por feature, sigue con las demás y notifica |
| 2B-2 | `KafkaErrorHandlingConfigTest#concurrentTransitionIsRetried` (declararla explícitamente) y `MongoSyncStateRepositoryTest#staleReadIsReportedAsConcurrentNotIllegal` | Que `transition` compare `t.from()` con la cabecera real y lance `ConcurrentTransitionException` (reintentable) cuando difieran, reservando `IllegalStateException` para transiciones ilegales de verdad. Evaluar `cycleId` en `SyncStateDoc` |
| 2B-3 | `RestClientSapClientTest#postIsNotRetriedOnResponseTimeout` | Retry solo para GET/HEAD y para errores de **conexión** (antes de enviar); en POST/PATCH/DELETE, ningún reintento tras timeout de respuesta hasta que exista upsert (C2-2) |
| 2A-5 | `MongoSyncStateRepositoryTest#noDeduplicaSiHuboUnFalloParcialPosterior` | `alreadySent` devuelve `false` si el último terminal del agregado no es `SENT_SAP` |

*Aceptación*: los cuatro tests en verde; spec `maquina-de-estados.md`, `idempotencia-y-dedupe.md` y `resiliencia-cliente-sap.md` con sus AC nuevos; `mvn verify` en verde.

### C2-2 · Contratos SAP: lo que NO depende del tenant

*Agentes*: `integrator-systems` (diseño) → `dev-implementer` → `qa-tester`. *Esfuerzo*: 3–4 jornadas. Necesita **D-10** (§9).

1. **2B-4**: decidir D-10 y ejecutarla: o se retira la familia `Btp*` del reactor, o se invierte el default (`sap.odata.*.enabled=true`) y la app **no arranca** con un adaptador sin servicio real detrás salvo `sap.auth.allow-stub=true`. Test: `CustomerApplicationContextTest#defaultAdaptersTargetARealContract`.
2. **2B-5**: contacto con `A_AddressEmailAddress`/`A_AddressPhoneNumber` según la spec (PRD-2); el payload lleva lo que `ContactData` trae. Test: `BusinessPartnerContactODataAdapterTest` (no existe hoy) + contract test.
3. **2A-8**: artículo contra `API_PRODUCT_SRV/A_Product` con los campos de la spec; corregir el contract test que hoy consagra el error. Test: `S4ArticleContractTest` reescrito contra la spec.
4. **2B-6 / PRD-11**: diseñar el upsert (lookup → `POST` deep insert o `PATCH If-Match`) con `AddressID`/ETag persistidos en la imagen. Implementar la rama de lookup y el `PATCH`; la comprobación de PATCH parcial queda para el tenant (§1–§2 del checklist).
5. **2A-7**: `statusOf(null)` falla; estado de mandato obligatorio en `BankingValidator`; `revoke` con `If-Match`. Test: `SepaMandateODataAdapterTest#rechazaMandatoSinEstado`.
6. **2A-14**: test unitario para los 4 adaptadores OData que no lo tienen.
7. Añadir al checklist del tenant las secciones de **artículo** y de **BTP** (si D-10 lo mantiene).

*Aceptación*: ningún adaptador activo por defecto apunta a un servicio inexistente; los 6 adaptadores OData con test; contract tests derivados de la spec, no del código.

### C2-3 · Seguridad y PII: lo que no es decisión de negocio

*Agentes*: `auditor-monitor` (+ seguridad) → `dev-implementer`. *Esfuerzo*: 2 jornadas + acciones de persona.

1. **2B-7 — acción de persona, hoy**: rotar el *communication user* del tenant. No requiere código y lleva ocho días pendiente.
2. **2A-1**: validar `aud` (`JwtValidators` + `audience` por configuración). Test: `ApiSecurityTest#tokenForAnotherClientIsRejected`.
3. **2A-2**: `AccessScope` falla **cerrado** (sin autenticación → enmascarado); en `prod` el overlay fija `APP_SECURITY_ENABLED=true` y un guard de arranque rechaza `false` fuera de `local`. Test: `AccessScopeTest#anonymousNeverSeesSensitiveData`.
4. **2A-3 / 2A-15**: `NetworkPolicy` por app (solo hacia Kafka, Mongo, ES, Keycloak, SAP); `Ingress` con TLS solo para `/customers/**` y `/articles/**`; ES y Mongo con autenticación y TLS en los overlays; imagen por digest; `automountServiceAccountToken: false`; PDB.
5. **2A-4 — mecanismo**: TTL en Mongo (`sync_state`, imagen bloqueada) e ILM en ES parametrizados por propiedad, con el **valor** pendiente de D-11. Que exista el mecanismo desbloquea la decisión.
6. **2A-11**: `sap.sync.alerts` sin `entityId` en claro o con retención corta.

*Aceptación*: credencial rotada (confirmado por la persona); tests de `aud` y fail-closed en verde; manifiestos con NetworkPolicy, Ingress TLS y digest; TTL/ILM configurables presentes.

### C2-4 · La red de tests que faltó dos veces

*Agentes*: `qa-tester`. *Esfuerzo*: 2–3 jornadas. Puede ir en paralelo con C2-1.

1. **2A-13 (recurrencia → diseño)**: mover `InMemoryStateRepo` a `common` (test-jar o módulo `common-test`) y usarlo en `SyncArticleUseCaseTest`; añadir en artículo los tests de re-sync desde `SAP_ERROR` y de fallo tras `VALID`. Regla ArchUnit o de revisión: **ningún test de use case mockea `SyncStateRepositoryPort`**.
2. **B12 parcial**: `SyncCustomerUseCaseTest#fetchFailureMarksErrorNotStuckInFetching`; ampliar el `try` a todo el ciclo.
3. **TEST-1 paso intermedio**: `ElasticsearchIndexerIT` contra ES en Testcontainers (habría cazado la incidencia del 2026-09-12). Después el e2e completo outbox → Debezium → app → WireMock → Mongo/ES (TEST-4), gateado por `docker.available`.
4. **2B-2 / B13**: test de recorrido `SapCircuitOpenException` → use case → listener → reintento (hoy solo a nivel de cliente).
5. Doble cita `AC-n` en los tests de los 5 specs que la tienen solo por nombre.

*Aceptación*: `InMemoryStateRepo` en los dos dominios; IT de ES en la CI; e2e mínimo en verde con Docker.

### C2-5 · Operación: DLT, CDC y presupuesto

*Agentes*: `integrator-systems` + `auditor-monitor`. *Esfuerzo*: 2 jornadas.

1. **2A-10**: `errors.tolerance=all` + DLQ en los conectores Debezium; prueba: inyectar un `payload` no JSON en la outbox.
2. **2A-11**: métrica de profundidad de `*-dlt`, consumidor de `sap.sync.alerts` (OPS-8) y reprocesador mínimo desde la DLT (OPS-2), aunque sea un comando.
3. **2A-12**: `RetryBudgetGuard` con la fórmula completa (CSRF ×2, fetch fuera de CB, entregas Kafka) o `max.poll.interval.ms` derivado de ella.
4. **2A-16**: al menos tres alertas en Grafana (`SAP_ERROR` sostenido, DLT > 0, circuito abierto) y `traceId` en logs cuando se active OBS-2.
5. **2A-9**: decidir D-12 y D-13 (baja de BP = bloqueo; DELETE de artículo) y ejecutar.

*Aceptación*: un registro malo en la outbox no detiene el CDC; la DLT tiene métrica y consumidor; tres alertas definidas como código.

### C2-6 · Verdad documental como test (diseño)

*Agentes*: `docs-writer` + `dev-implementer`. *Esfuerzo*: 1 jornada. Segunda recurrencia de "`AGENTS.md` miente".

Un `AgentsMdMatchesCodeTest` en `it/` que compruebe lo verificable de `AGENTS.md`: que cada ruta de la tabla de endpoints existe en un `@RequestMapping`, que cada puerto de la tabla de puertos existe como interfaz, que los starters que se afirman o niegan están o no en el POM. Mismo patrón que `TestCountMatchesDocsTest`.

*Aceptación*: el test rompe si se reintroduce cualquiera de las cinco contradicciones de §5.

---

## 9. Decisiones que necesita la persona, y cuándo

| ID | Decisión | La necesita | Recomendación del equipo |
|---|---|---|---|
| **D-10** | Familia BTP: ¿retirarla o invertir el default? | C2-2, antes de nada | **Retirar**. No hay servicio, no hay spec, y cada adaptador BTP es código y tests que mantener para un contrato que no existe. Si BTP llega algún día, será OData V4 y otro diseño |
| **D-11** | Plazo de retención del histórico, `sync_state`, topics con PII | C2-3.5 (el valor; el mecanismo no espera) | Negocio + legal. Sin cifra no hay TTL; sin TTL no hay producción |
| **D-12** | Baja de BP en S/4: `DELETE` vs `BusinessPartnerIsBlocked` | C2-5.5, y §6 del checklist | Bloqueo, salvo que el tenant diga otra cosa |
| **D-13** | DELETE de artículo: ¿se ignora, se marca o se propaga? | C2-5.5 | Marcar como bloqueado, igual que cliente |
| **Audiencia** | Nombre del `aud` que emite Keycloak para `sap-integration` | C2-3.2 | Equipo de identidad |
| D-3, D-4, D-8 | Debezium Server/SMT, OData V4, Schema Registry | Siguen abiertas del ciclo 1 | Sin cambio |
| **D-10** | Familia BTP: ¿retirarla o invertir el default? | Tomada por el propietario, 2026-09-18 | **Se mantiene**: el servicio del propietario existe y se probó aislado. Mitigación: **OPS-9**, guard de arranque que impida un `Btp*Adapter` activo sin `sap.btp.base-url` real |
| **D-14** | Estado final del ciclo del cliente ante un fallo parcial: ¿basta una parte `INVALID` para marcar todo el agregado `INVALID`? | Tomada por el propietario, 2026-09-18 | **Cero confianza**: `INVALID` solo si **ninguna** parte llegó a llamar a SAP; en cualquier otro caso, `SAP_ERROR` — una parte `INVALID` con otra que sí llamó a SAP no puede leerse como «no se envió nada» |
| **D-15** | ¿Se relanza el mensaje como reintentable tras un fallo parcial? | Tomada por el propietario, 2026-09-18 | **Sí, y solo si es demostrable que nada llegó a SAP** (todas las partes fallidas son `COMMUNICATION_ERROR`); si algo entró en SAP, no se relanza, porque reintentar el mensaje entero duplicaría mientras no exista la verificación previa fiable |
| **D-16** | ¿Un único Kafka multi-AZ visible desde los dos clústeres, o un Kafka por clúster? | Pendiente de **plataforma**; el diseño de concurrencia (ADR-0011) **asume** la primera opción | Se ha asumido para poder diseñar el fencing por `cycleId`; si en realidad hay un Kafka por clúster, la decisión no basta y hay que rediseñar la idempotencia frente a SAP asumiendo dos consumidores legítimos |
| **D-17** | ¿El motivo de un fallo parcial (guardado para explicar «qué entró y qué no») puede llevar datos personales, y si eso obliga a una retención distinta? | Pendiente del propietario, junto con D-11 | **Provisional**: se trata como el resto del histórico hasta que se decida; no se ha añadido ningún tratamiento especial |
| **D-18** | ¿Cuándo se activa la lectura previa (lookup) contra SAP por feature? | Tomada por el equipo, 2026-09-18 | **Obligatorio** cuando el adaptador de escritura OData de la feature está activo: no es opcional por configuración, para que un upsert a medias no sea posible por descuido |
| ~~Rotación~~ | ~~Rotar el *communication user* del tenant~~ | — | **Retirada de este listado por decisión del propietario**: fuera de alcance; sin más detalle |

---

## 10. Seguimiento entre sesiones

| Fase | Estado | Cerrada el | Commit | Verificada por |
|---|---|---|---|---|
| C2-0 · Verificación ejecutable y verdad documental | ✅ suite ejecutada en verde antes de tocar código: 314 tests, `BUILD SUCCESS` sin Docker; contradicciones §5 corregidas; fingerprints registrados | 2026-09-18 | sin commit (pendiente) | manager Fable (misma sesión; falta verificación por sesión distinta) |
| C2-1 · Bloqueantes de corrección | ✅ 2B-1, 2B-2, 2B-3, 2A-5 implementados con TDD | 2026-09-18 | sin commit (pendiente) | manager Fable (misma sesión; falta verificación por sesión distinta) |
| C2-2 · Contratos SAP sin tenant | 🟡 parcial — D-10 decidida: BTP se mantiene; 2B-5 y 2B-6 implementados; 2A-8 (artículo) y 2A-7 (mandato) **no tocados**; 2A-14 cubierto solo para los adaptadores OData de `customer` | 2026-09-18 | sin commit (pendiente) | manager Fable (misma sesión; falta verificación por sesión distinta) |
| C2-3 · Seguridad y PII | 🟡 propuesta ADR-0012 (mantener Keycloak); 2A-1/2A-2 sin código; rotación de credencial retirada del plan por decisión del propietario | 2026-09-18 | sin commit (pendiente) | manager Fable (misma sesión; falta verificación por sesión distinta) |
| C2-4 · Red de tests | ⬜ | | | |
| C2-5 · Operación | 🟡 parcial — 3d/3e: reintentables declaradas, topología Kafka, `409`; DLT sigue sin consumidor | 2026-09-18 | sin commit (pendiente) | manager Fable (misma sesión; falta verificación por sesión distinta) |
| C2-6 · Verdad documental como test | ⬜ | | | |

Regla heredada del ciclo 1: **cada fase la verifica una sesión distinta de la que la ejecutó**, y la verificación se anota aquí con la cifra real de la suite.

---

## 11. Qué comprobar en el tenant de test (delta al checklist)

El [checklist](../testing/CHECKLIST-TENANT-SAP.md) cubre §1–§7 para customer OData. Faltan:

- **§8 Artículo**: ruta real de `API_PRODUCT_SRV`, campos mínimos de `A_Product`, numeración interna/externa.
- **§9 BTP** (solo si D-10 la mantiene): URL, spec y auth del servicio real.
- **§7 ampliado**: repetir el mismo POST con la misma `Idempotency-Key` y comprobar si S/4 duplica (cierra A7 y dimensiona 2B-3).
- **§6 ampliado**: qué devuelve `DELETE /A_BusinessPartner('...')` (cierra D-12).

---

## 12. Método, desviación y deuda declarada

**Cómo se hizo.** Fable actuó como manager: clasificó, leyó los tres documentos del ciclo 1, las dos incidencias, `TODO.md`, `MEJORAS-Y-PROPUESTAS.md`, `APTITUD-PRODUCCION.md` y `AGENTS.md` del repo analizado, y lanzó seis handoffs con el contrato completo (`objetivo`, `entrada` por punteros, `aceptacion`, `supuestos`, `devuelve-si`). Sonnet para inventario y coherencia documental (lectura y conteo), Opus para verificación de cierre, revisión de código, integración y seguridad. Cada retorno se verificó abriendo el código en al menos dos citas antes de aceptarlo; en tres casos el manager añadió matices (2B-1 "hasta el siguiente evento", AC por nombre vs por Javadoc, `ConcurrentTransitionException` sí reintentable). Ningún bucle pasó de una iteración.

**Desviación respecto a lo pedido: una, declarada.** Se pidió "otro fichero de auditoría"; se entregan **este fichero y una carpeta de anexos** con los seis informes de detalle, porque las ~15.000 palabras con `fichero:línea` no caben aquí y el scratchpad donde nacieron es efímero. Son ficheros nuevos, sin commit; borrar la carpeta deshace la desviación. Además, el orquestador del equipo tiene `write` denegado por diseño y aquí el manager ha escrito el informe él mismo, por indicación explícita de la petición ("Fable saque conclusiones y defina próximos pasos").

**Lo que NO se verificó** (exigiría ejecutar): que los 314 tests pasen; la cobertura JaCoCo real; el comportamiento de S/4 ante cualquier payload (A7, `AddressID`, `DELETE`); el Keycloak corporativo; el clúster; que la credencial del spike siga siendo válida (se comprobó ubicación e ignorado, no el valor).

**Sobre el método del repo `agents`** (hallazgos M1–M10 del ciclo 1, verificados hoy): siguen abiertos **M1** (no hay tipo Auditoría; esta petición se volvió a clasificar como Diseño y prototipado), **M2** (ningún agente es dueño de seguridad ni PII: `auditor-monitor` se amplió por prompt otra vez), **M3** (`dev-implementer` en modo revisión por prompt, sin variante), **M6** (sin `mode: readonly`), **M7** (contrato de handoff pegado a mano en seis prompts). Nuevo: la skill `agent-routing` no está registrada como skill invocable en la sesión (`Unknown skill`); se cargó leyendo el fichero. Se dejan aquí para que el repo `agents` los recoja en su propio ciclo.

**Corrección posterior al informe (cierre de la revisión 3, 2026-09-18).** Este documento y el del 2026-09-10 afirman que la arquitectura está «vigilada por ArchUnit». **Era falso**: `DomainPurityTest`, `ApplicationPurityTest` y `EndpointsDeclareAccessTest` salían con `Tests run: 0` porque `archunit-junit5` no se registra como motor en JUnit Platform 6 (Spring Boot 4) y surefire 3.5.x tampoco lo lanza; una regla imposible pasaba en verde. Lo destapó el informe de línea base de C2-0 y se corrigió el mismo día (`archunit-junit6` + surefire/failsafe 3.6.0, fingerprint `archunit:motor-junit5-sobre-platform-6:reglas-no-ejecutadas`). Ejecutadas por primera vez, las siete reglas pasan. Lección para el método: «vigilado por un test» solo se afirma tras ver ese test **contar** en el log, y B7 («0 tests muertos») debe incluir los `@ArchTest`, que `TestCountMatchesDocsTest` no cuenta.

**Deuda de este informe**: no se generó diagrama; no se consultó la documentación oficial de SAP para 2B-5/2A-8 más allá de las specs OpenAPI del propio repo; el esfuerzo por fase es *estimación del equipo* sin línea base propia (el ciclo 1 tampoco la midió: C2-0 debería anotar las horas reales).

---

## 13. Anexos

| Fichero | Agente | Modelo | Palabras |
|---|---|---|---|
| [01-verificacion-cierre-B1-B14.md](2026-09-18-anexos/01-verificacion-cierre-B1-B14.md) | `qa-tester` | Opus | ~2.300 |
| [02-coherencia-docs-codigo.md](2026-09-18-anexos/02-coherencia-docs-codigo.md) | `docs-writer` (revisión) | Sonnet | ~1.500 |
| [03-inventario-tests.md](2026-09-18-anexos/03-inventario-tests.md) | `qa-tester` (inventario) | Sonnet | ~1.800 |
| [04-revision-codigo.md](2026-09-18-anexos/04-revision-codigo.md) | `dev-implementer` (revisión) | Opus | ~2.900 |
| [05-integracion.md](2026-09-18-anexos/05-integracion.md) | `integrator-systems` | Opus | ~2.900 |
| [06-seguridad-operacion.md](2026-09-18-anexos/06-seguridad-operacion.md) | `auditor-monitor` (+ seguridad, PII) | Opus | ~3.000 |

---

*Generado por el equipo de agentes de `A:\Documentos\Code\agents` el 2026-09-18. Informe de verificación y análisis; no contiene ni implica cambios en el código del repositorio analizado.*
