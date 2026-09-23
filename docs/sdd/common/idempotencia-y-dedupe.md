# Idempotencia, deduplicación y consistencia de la imagen

| | |
|---|---|
| **Dominio** | `common` (capacidad transversal; la aplican los orquestadores de `customer` y `article`) |
| **Estado** | ✅ implementado |
| **Entradas** | cualquier evento de ingesta (CDC, Kafka, REST): identidad del cambio; el hash lo calcula el consumidor ([`contrato-mensaje-de-cambio.md`](contrato-mensaje-de-cambio.md)) |
| **Destino SAP** | ninguno directo |
| **Última revisión** | 2026-09-19 |

## 1. Objetivo

Que un mismo evento procesado dos veces no duplique efectos en SAP, que un
evento **distinto** nunca se descarte por parecerse a uno antiguo, y que lo que
guardamos como «imagen» sea exactamente lo que SAP tiene. La auditoría encontró
las tres cosas rotas (A1, A31 y la imagen persistida antes del ACK).

## 2. Alcance

**Dentro**: dedupe por el **hash del snapshot** releído del legacy, semántica de
la imagen (staging) y del atajo «sin cambios reales», identidad de las versiones
del histórico.

**Fuera** (y por qué):
- `Idempotency-Key` hacia SAP: viaja en toda escritura pero SAP OData V2 la
  ignora ([`resiliencia-cliente-sap.md`](resiliencia-cliente-sap.md) R-7). Ver R-7
  de aquí abajo.
- La **verificación previa y el upsert** (preguntar a SAP qué tiene antes de
  escribir): tienen spec propio,
  [`upsert-idempotente-sap.md`](upsert-idempotente-sap.md). Son la capa de al
  lado: estas reglas evitan **hacer la llamada**; aquella evita el **duplicado**
  cuando la llamada sí ocurre.
- El **contrato del mensaje** de cambio (qué campos lleva, quién lo produce, cómo
  se mantiene la compatibilidad): tiene spec propio,
  [`contrato-mensaje-de-cambio.md`](contrato-mensaje-de-cambio.md). Aquí solo
  importa **de dónde sale el hash**, no cómo viaja el aviso.
- Compensación entre features cuando una falla y otras no: decisión D-2 del plan.
- Retención del histórico: plan de acción (RGPD).

## 3. Entrada

| Campo | Tipo | Notas |
|---|---|---|
| snapshot del legacy | entidad | Lo que se acaba de leer con `LegacyRepositoryPort#fetch`. **Es la única fuente de datos** ([ADR-0013](../../architecture/adr/0013-outbox-mensaje-fino-sin-payload.md)) |
| `payloadHash` | texto | **Calculado** por el consumidor sobre ese snapshot (`PayloadHasher`, SHA-256 sobre una forma canónica). Identifica **el contenido**, no el intento. El hash que traiga el mensaje, si lo trae, se ignora salvo como pista de log |
| estado anterior | `SyncState` | El último `SENT_SAP` de la entidad y su hash |
| imagen | entidad | Lo último que SAP aceptó (`customers_current` / `articles_current`) |

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | El `payloadHash` del ciclo **se calcula sobre el snapshot releído del legacy**, nunca se toma del mensaje. Es determinista (`PayloadHasher`: SHA-256 sobre la forma canónica del agregado), así que dos instancias con el mismo snapshot obtienen el mismo hash | Un hash traído del mensaje describe un estado que puede estar superado: deduplicaría contra algo que ya no es cierto, o dejaría de deduplicar por venir de un motor distinto |
| R-1 bis | Un evento está **deduplicado** solo si ese hash calculado coincide con el del **último** `SENT_SAP` de la entidad. Un `SENT_SAP` anterior con ese hash no cuenta: SAP ya tiene otra cosa. Secuencia A→B→A: el tercer evento **se envía** | Auditoría A1: se buscaba cualquier `SENT_SAP` histórico y el tercer evento se descartaba con SAP en B |
| R-2 | Un evento deduplicado responde `SENT_SAP` **sin abrir ciclo** ni tocar SAP, Mongo ni Elasticsearch. Sí lee el legacy: es lo único que permite saber qué snapshot es | — |
| R-3 | **«Sin cambios reales»**: si el ciclo anterior terminó en `SENT_SAP` y el snapshot re-leído del legacy es idéntico a la imagen (R-4), no se envía nada y el ciclo pasa `VALID → SENT_SAP`. Ese `SENT_SAP` con el hash nuevo significa «SAP está en sincronía con este payload», y por R-1 deduplica reintentos del mismo evento. No se indexa versión en el histórico porque no hubo envío | — |
| R-4 | La **imagen** (Mongo, staging) es «lo que SAP tiene»: se persiste **solo después** de que SAP acepte (todas las features en `SENT_SAP`, o el `send` del artículo en 2xx). Un `SAP_ERROR` o un `INVALID` de feature no la tocan | Antes se guardaba antes de enviar: tras un `SAP_ERROR` la imagen decía que SAP tenía un dato que nunca recibió, y R-3 se saltaba el reenvío |
| R-5 | El **histórico** (Elasticsearch) registra **lo que se va a enviar**, un documento por **intento**: id `entityId-payloadHash-epochMillis`. Un reenvío del mismo hash tras `SAP_ERROR` no sobrescribe la versión anterior | Auditoría A31: id `entityId-hash` pisaba el intento anterior y se perdía el rastro |
| R-7 | **Dedupe honesto tras un fallo parcial**: si el **último ciclo** de la entidad no terminó en `SENT_SAP`, no hay dedupe posible aunque el `payloadHash` coincida con el de un `SENT_SAP` anterior. Desde [ADR-0010](../../architecture/adr/0010-sin-compensacion-entre-features-marcar-y-avisar.md) un ciclo puede terminar en `SAP_ERROR` con parte del cliente ya en SAP: lo que SAP tiene entonces es una **mezcla**, y ningún hash la describe | Auditoría 2026-09-18 N4: deshacer un cambio y reenviar el hash original devolvía `SENT_SAP` sin hacer nada, y el cliente quedaba desincronizado para siempre |
| R-6 | El histórico y la imagen pueden discrepar a propósito: el histórico tiene el último **intento**, la imagen el último **éxito**. La diferencia entre ambos es exactamente lo que SAP no tiene todavía | — |
| R-8 | **La idempotencia frente a SAP la da la verificación previa, no la cabecera.** Estas reglas evitan reenviar lo que ya se envió igual, pero no protegen de un evento con contenido distinto sobre una entidad que SAP ya tiene, ni de un reintento tras una respuesta perdida: eso lo resuelve el upsert ([`upsert-idempotente-sap.md`](upsert-idempotente-sap.md) R-1). Las claves que asigna SAP se guardan en `sap_keys`, **no** en la imagen, precisamente para no hacer una excepción a R-4 | Confiar el no-duplicado a `Idempotency-Key` o al dedupe deja pasar los dos casos que más duelen en un sistema de facturación |

## 5. Salida

`SENT_SAP` inmediato en dedupe (R-2) y en «sin cambios reales» (R-3); imagen
actualizada solo con `SENT_SAP` (R-4); un documento de histórico por intento (R-5).

## 6. Estados y errores

| Situación | Estado final | Imagen | Histórico |
|---|---|---|---|
| Hash = último `SENT_SAP` | `SENT_SAP` (sin ciclo) | sin cambio | sin cambio |
| Snapshot = imagen tras `SENT_SAP` | `SENT_SAP` (`VALID →`) | sin cambio | sin cambio |
| Envío aceptado | `SENT_SAP` | **actualizada** | +1 documento |
| Envío rechazado | `SAP_ERROR` | sin cambio | +1 documento |
| Feature inválida | `INVALID` | sin cambio | +1 documento |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dado el último `SENT_SAP` con hash B, cuando llega hash A (enviado antes de B), entonces **no** está deduplicado; con hash B sí; `null`/vacío nunca | `MongoSyncStateRepositoryTest#alreadySentOnlyMatchesTheLatestSentSap` |
| AC-2 | Dado un snapshot cuyo hash ya está en el último `SENT_SAP`, cuando llega un aviso, entonces `SENT_SAP` sin transiciones y sin SAP (el legacy sí se lee) | `SyncCustomerUseCaseTest#alreadySentPayloadSkipsPipeline` · `SyncArticleUseCaseTest#dedupeUsesTheHashOfTheSnapshotNotTheMessage` |
| AC-7 | Dado un mensaje cuyo `payloadHash` no corresponde con el snapshot, cuando se procesa, entonces manda el **calculado**: deduplica con él y escribe con él en el ciclo, el histórico y el envío a SAP | `SyncCustomerUseCaseTest#dedupeUsesTheHashOfTheSnapshotNotTheMessage` · `#messageHashAlreadySentDoesNotSkipWhenTheSnapshotDiffers` · `#computedHashTravelsToFeaturesAndTransitions` · `SyncArticleUseCaseTest#dedupeUsesTheHashOfTheSnapshotNotTheMessage` |
| AC-8 | El hash es determinista, cambia si cambia cualquier campo de negocio y no depende del orden de iteración de un mapa | `PayloadHasherTest` |
| AC-3 | Dado que SAP rechaza (o una feature es inválida), entonces el histórico registra el intento y la imagen **no** se guarda; cuando SAP acepta, la imagen se guarda | `SyncCustomerUseCaseTest#returnsSapErrorWhenAnyFeatureSapError` · `#returnsInvalidWhenAnyFeatureReturnsInvalid` · `#happyPathReturnsSentSapWhenAllFeaturesSucceed` · `SyncArticleUseCaseTest#sapErrorReturnsSapError` · `#happyPath` |
| AC-4 | Dos versiones con el mismo hash y distinto instante tienen ids distintos en el histórico | `ElasticsearchCustomerIndexerTest#retriesWithTheSameHashKeepBothVersions` · `ElasticsearchArticleIndexerTest#retriesWithTheSameHashKeepBothVersions` |
| AC-6 | Dado un ciclo que terminó en fallo (parcial o no) después del último `SENT_SAP`, cuando vuelve el `payloadHash` de ese `SENT_SAP`, entonces **no** está deduplicado y el pipeline se ejecuta | `MongoSyncStateRepositoryTest#dedupeIsHonestWhenTheLastCycleEndedInPartialFailure` · `SyncCustomerUseCaseTest#resendsWhenTheLastCycleEndedInPartialFailure` |
| AC-5 | Snapshot idéntico a la imagen tras `SENT_SAP`: no se envía, no se indexa, no se guarda; distinto: se envía y se guarda | `SyncCustomerUseCaseTest#unchangedSnapshotAfterSentSapSkipsResend` · `#changedSnapshotAfterSentSapIsResent` · los homólogos de `SyncArticleUseCaseTest` |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

Log `INFO` «dedupe» y «sin cambios reales» con `entityId` y hash (el calculado).
Log `DEBUG` cuando el hash del mensaje difiere del calculado: es la pista de que
alguien sigue emitiendo el formato antiguo, y **no** cambia ninguna decisión. Métrica
`sap_sync_state_total` por estado (el dedupe no incrementa nada: no hay ciclo).

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| R-1, R-7 | `common/adapters/persistence/MongoSyncStateRepository.alreadySent` (último **estado final** del ciclo) · `SyncStateMongoRepository.findFirstByDomainAndEntityIdAndStateCodeInOrderBySeqDescTimestampDesc` · fixture `customer/src/test/.../InMemoryStateRepo` | `MongoSyncStateRepositoryTest#alreadySentOnlyMatchesTheLatestSentSap` · `#dedupeIsHonestWhenTheLastCycleEndedInPartialFailure` |
| R-1 (hash calculado) | `common/domain/PayloadHasher` | `PayloadHasherTest` |
| R-2, R-3, R-4 | `customer/application/general/SyncCustomerUseCase.execute` · `article/application/SyncArticleUseCase.execute` | `SyncCustomerUseCaseTest` · `SyncArticleUseCaseTest` |
| R-5 | `customer/adapters/index/CustomerHistoryDoc.from` · `article/adapters/index/ArticleHistoryDoc.from` | `Elasticsearch*IndexerTest` |
| Consulta del histórico con hashes repetidos | `customer/application/general/CustomerHistoryUseCase` | `CustomerHistoryUseCaseTest` |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-19 | **R-1 reescrita**: el hash de idempotencia deja de venir en el mensaje y lo calcula el consumidor sobre el snapshot releído del legacy (`PayloadHasher`). R-2 matizada (el dedupe sí lee el legacy), AC-7 y AC-8 nuevos, §3 y §9 al día. Contrato del mensaje en spec propio. [ADR-0013](../../architecture/adr/0013-outbox-mensaje-fino-sin-payload.md) | — |
| 2026-09-18 | §2 «Fuera»: el upsert deja de ser futuro y pasa a tener spec propio. R-8 nueva: la idempotencia frente a SAP la da la verificación previa, y las claves que asigna SAP van a `sap_keys` para no hacer una excepción a R-4 | — |
| 2026-09-18 | R-7 y AC-6: el dedupe deja de mirar solo el último `SENT_SAP` y mira **cómo terminó el último ciclo**. Tras un fallo parcial SAP tiene una mezcla, así que el mismo hash se reenvía en vez de responder «ya está sincronizado» (auditoría 2026-09-18 N4) | — |
| 2026-09-12 | **Verificación en vivo** (customer-app reconstruida, SAP simulado): con SAP en 500 y `city=Vigo` en el legacy, `SAP_ERROR` (seq 68) e imagen intacta en `Bilbao`, con documento de histórico `CUST-001-f6-err-3-<epoch>` con `Vigo`; al restaurar SAP, `SENT_SAP` (seq 76) e imagen `Vigo`; secuencia A→B→A (`f6-ok-4` → `f6-ok-5` → `f6-ok-4`): el tercer evento abrió ciclo (seq 90-92), hizo 4 POST a SAP y dejó la imagen en `Vigo`. Hallazgo colateral: la primera escritura en Elasticsearch fallaba con `ClassNotFoundException: io.opentelemetry.semconv.DbAttributes` (ver `sdd/README.md` §6) | — |
| 2026-09-12 | Spec inicial (plan Fase 6, auditoría A1/A31 e imagen antes del ACK). Dedupe contra el **último** `SENT_SAP`; imagen persistida solo tras el ACK de SAP; un documento de histórico por intento; semántica explícita del atajo «sin cambios reales» | — |
