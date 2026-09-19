# Sincronización del cliente (agregado)

| | |
|---|---|
| **Dominio** | `customer` |
| **Estado** | ✅ implementado — re-entrada y fallo de infra corregidos (Fase 1); verificado en vivo el 2026-09-12 |
| **Entradas** | CDC (`outbox.CUSTOMER`) · REST `POST /customers/sync` |
| **Destino SAP** | BTP o S/4 nativo, según `sap.odata.<feature>.enabled` |
| **Última revisión** | 2026-09-19 |

## 1. Objetivo

Llevar a SAP la ficha completa de un cliente del legacy cada vez que cambia, con
trazabilidad de cada paso y sin duplicar envíos. Es el pipeline principal del
dominio: orquesta las features (dirección, fiscal, contacto, banco) y mantiene la
imagen actual y el histórico.

## 2. Alcance

**Dentro**: ingesta por CDC y REST, lectura del legacy, validación del agregado,
imagen en Mongo, histórico en Elasticsearch, despacho a las features y estado
final del ciclo. Re-sincronización ante eventos nuevos, **venga de donde venga**
el ciclo anterior.

**Fuera**: la baja ([`baja-cliente.md`](baja-cliente.md)), el detalle de cada
feature (sus specs), la compensación entre features ante fallo parcial (D-2 del
plan, sin decidir).

## 3. Entrada

`IngestionMessage` — **aviso de cambio fino**: contrato completo en
[`../common/contrato-mensaje-de-cambio.md`](../common/contrato-mensaje-de-cambio.md)
(resumen en [`../../architecture/TECH.md`](../../architecture/TECH.md) §6).

El mensaje **no es fuente de datos de ninguna clase**: ni el `payload` (opcional
y obsoleto) ni el `payloadHash`. El use case relee la entidad del legacy por
`entityId` y **calcula el hash sobre ese snapshot** (`PayloadHasher`), que es el
que usa para el dedupe, para el ciclo, para el histórico y para SAP
([ADR-0013](../../architecture/adr/0013-outbox-mensaje-fino-sin-payload.md)).

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | Un **hash del snapshot** igual al del **último** `SENT_SAP` no se reprocesa ([`../common/idempotencia-y-dedupe.md`](../common/idempotencia-y-dedupe.md) R-1; antes valía cualquier `SENT_SAP` histórico y A→B→A descartaba el tercer evento) | `SENT_SAP` inmediato, sin tocar SAP |
| R-2 | Si el legacy no devuelve la entidad, el ciclo termina en error | `ERROR` |
| R-3 | Si el agregado no valida, no se envía nada | `INVALID` |
| R-4 | Si el ciclo anterior terminó en `SENT_SAP` y el snapshot es idéntico a la imagen (= lo que SAP tiene), no se reenvía | `SENT_SAP` directo desde `VALID` |
| R-5 | **Un evento nuevo siempre abre ciclo**, sea cual sea el estado anterior (`SENT_SAP`, `SAP_ERROR`, `ERROR`, o uno intermedio si el proceso murió) | — |
| R-6 | **Cualquier fallo de infraestructura tras `VALID`** (Mongo, ES, HTTP) deja la entidad en `ERROR`, nunca colgada en un estado intermedio, y se propaga para que la ingesta reintente | `ERROR` + excepción |
| R-7 | **Cero confianza** (D-14): el estado final del ciclo lo deciden las partes, ordenadas por criticidad real: todas `SENT_SAP` → `SENT_SAP`; **todas** `INVALID` (ninguna llegó a llamar a SAP) → `INVALID`; en cualquier otro caso → `SAP_ERROR`. Una parte `INVALID` con otra que sí llamó a SAP **no** es `INVALID`: eso se leería como «no se envió nada» y es falso | Antes bastaba una `INVALID` para marcar el agregado `INVALID` con partes ya en SAP |
| R-9 | **Sin compensación** ([ADR-0010](../../architecture/adr/0010-sin-compensacion-entre-features-marcar-y-avisar.md)): si alguna parte no llega a `SENT_SAP`, las que entraron se quedan en SAP, el agregado termina en `SAP_ERROR`/`INVALID`, cada línea de feature conserva su estado **y el motivo por el que falló**, y se **avisa** (`WARN` con partes OK y fallidas, mensaje `SYNC_PARTIAL_FAILURE` en `sap.sync.alerts` con `cycleId`, estado del agregado y un paso por parte, métrica `sap_sync_feature_result_total`). El bucle de partes **nunca aborta**: cada parte va en su propio `try`, así que una que lance no impide intentar las demás ni emitir el aviso. Todas las líneas comparten el `cycleId` del agregado, que es lo que permite reconstruir la **traza de pasos** del envío. El siguiente evento reenvía todas | Auditoría 2026-09-18 N1: con el circuito abierto, la primera parte se quedaba en `SENDING_SAP`, las otras tres no se intentaban y el aviso no salía jamás |
| R-10 | **Relanzado** (D-15): el ciclo se propaga como excepción reintentable **si y solo si** ninguna parte llegó a SAP y todas las fallidas son `COMMUNICATION_ERROR`; el estado y el aviso ya están escritos cuando se lanza. Si algo entró en SAP **no** se propaga: reintentar el mensaje entero duplicaría mientras no exista la verificación previa. Propiedad `sap.partial-failure.rethrow-when-nothing-reached-sap` (por defecto `true`); se reevalúa cuando el *lookup* previo sea fiable | Sin ello, un circuito abierto dejaba de reintentarse al capturar la excepción en el pipeline |
| R-8 | La **imagen** se persiste **solo** cuando el ciclo termina en `SENT_SAP`; el **histórico** registra el snapshot antes de enviar, un documento por intento ([`../common/idempotencia-y-dedupe.md`](../common/idempotencia-y-dedupe.md) R-4, R-5) | Antes la imagen se guardaba antes de enviar y un `SAP_ERROR` dejaba «SAP tiene X» siendo falso |

**Clave de lookup del agregado**: el propio `entityId`. El alta usa numeración
externa (`BusinessPartnerGrouping = "BPEE"`), así que la clave en SAP es la
nuestra y **no hay nada que persistir** — *a confirmar en tenant*. La
verificación previa es `GET A_BusinessPartner('<entityId>')`. Mecanismo:
[`../common/upsert-idempotente-sap.md`](../common/upsert-idempotente-sap.md).

## 5. Salida

Documento en `customers_history` con el snapshot íntegro **por intento** (id
`customerId-hash-epochMillis`; propósito: **auditar qué se envió a SAP en
cualquier momento**), una llamada a SAP por feature, e imagen en
`customers_current` **solo tras `SENT_SAP`** (la imagen es lo que SAP tiene).
`GET /customers/{id}/state` devuelve el estado del agregado y de cada parte
(último estado, hash, `cycleId`, **motivo** e instante) más `lastCycle`: la traza
ordenada de pasos del último ciclo (qué línea, en qué estado quedó y por qué).
Es `null` para ciclos anteriores a la traza. El motivo va **enmascarado** para
`sap-external-read` ([`../common/seguridad-api.md`](../common/seguridad-api.md) R-4),
porque un 400 de SAP suele repetir el valor rechazado. El histórico conserva el snapshot completo por
decisión explícita; ver retención en el plan de acción.

## 6. Estados y errores

Recorrido del agregado sobre la máquina de
[`../common/maquina-de-estados.md`](../common/maquina-de-estados.md):

```
RECEIVED → FETCHING → VALIDATING → VALID → INDEXING → INDEXED → SENDING_SAP → {SENT_SAP | SAP_ERROR | INVALID}
```

| Situación | Estado final | Siguiente evento |
|---|---|---|
| Legacy sin la entidad | `ERROR` | abre ciclo nuevo |
| Validación falla | `INVALID` | abre ciclo nuevo |
| Alguna feature falla en SAP | `SAP_ERROR` | **abre ciclo nuevo** (antes quedaba bloqueado: B1) |
| Una parte `INVALID` y otra llegó a SAP | `SAP_ERROR` (cero confianza, R-7) | abre ciclo nuevo |
| Todas las partes `INVALID` | `INVALID` | abre ciclo nuevo |
| Ninguna parte llegó a SAP, todas por comunicación | `SAP_ERROR` + excepción reintentable (R-10) | lo reintenta la ingesta |
| Fallo de infraestructura tras `VALID` | `ERROR` | abre ciclo nuevo |
| Proceso muere a mitad | estado intermedio | **abre ciclo nuevo** y se registra que el anterior quedó en vuelo |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dado un cliente válido, cuando todas las features llegan a `SENT_SAP`, entonces el agregado termina en `SENT_SAP` | `SyncCustomerUseCaseTest#happyPathReturnsSentSapWhenAllFeaturesSucceed` |
| AC-2 | Dado un snapshot ya enviado, cuando llega otro aviso, entonces responde `SENT_SAP` sin llamar a SAP | `SyncCustomerUseCaseTest#alreadySentPayloadSkipsPipeline` |
| AC-11 | Dado un aviso fino (sin hash y sin payload), cuando llega, entonces el ciclo se ejecuta con el estado actual del legacy | `SyncCustomerUseCaseTest#thinMessageWithoutPayloadIsProcessed` |
| AC-12 | Dado un aviso cuyo hash no corresponde con el snapshot, entonces manda el calculado | `SyncCustomerUseCaseTest#dedupeUsesTheHashOfTheSnapshotNotTheMessage` · `#computedHashTravelsToFeaturesAndTransitions` |
| AC-3 | Dado que el legacy no devuelve la entidad, entonces el ciclo termina en `ERROR` | `SyncCustomerUseCaseTest#returnsErrorWhenLegacyFetchEmpty` |
| AC-4 | Dado un agregado en `SAP_ERROR` por un ciclo anterior, cuando llega un evento nuevo contra la máquina de estados **real**, entonces se re-sincroniza y llega a `SENT_SAP` | `SyncCustomerUseCaseTest#resyncsCustomerStuckInSapError` |
| AC-5 | Dado un fallo de infraestructura tras `VALID` (p. ej. el indexador lanza), entonces el estado queda en `ERROR` y la excepción se propaga | `SyncCustomerUseCaseTest#infrastructureFailureAfterValidMarksErrorAndPropagates` |
| AC-6 | Dado un agregado que quedó en `SENDING_SAP` porque el proceso murió, cuando llega un evento nuevo, entonces abre ciclo en vez de fallar | `SyncCustomerUseCaseTest#reopensCycleWhenPreviousOneWasLeftInFlight` |
| AC-7 | Dado que una feature acaba en `SAP_ERROR` o `INVALID`, entonces el histórico registra el intento y la imagen **no** cambia; con todas en `SENT_SAP`, la imagen se guarda | `SyncCustomerUseCaseTest#returnsSapErrorWhenAnyFeatureSapError` · `#returnsInvalidWhenAnyFeatureReturnsInvalid` · `#happyPathReturnsSentSapWhenAllFeaturesSucceed` |
| AC-8 | Dado que alguna parte no llega a `SENT_SAP`, entonces se notifica con el resultado de **cada** parte y se cuenta por feature; con todas en `SENT_SAP` no hay aviso | `SyncCustomerUseCaseTest#returnsSapErrorWhenAnyFeatureSapError` · `#fullSuccessDoesNotNotify` · `SyncMetricsTest#featureResultsAreCountedPerFeatureAndResult` |
| AC-9 | La notificación es un `WARN` con partes OK/fallidas y un JSON `SYNC_PARTIAL_FAILURE` en `sap.sync.alerts`; sin Kafka o con él caído, solo el `WARN`, sin romper el ciclo | `KafkaSyncNotificationAdapterTest` (2) |
| AC-10 | `GET /customers/{id}/state` devuelve el último estado, hash e instante del agregado y de cada feature con historial | `CustomerStateUseCaseTest` (2) |
| AC-11 | Dado un ciclo de cuatro partes en el que una **lanza** (circuito SAP abierto), entonces las cuatro se intentan, cada una deja su estado final escrito y ninguna queda en `SENDING_SAP` | `SyncCustomerUseCaseTest#aFeatureThrowingDoesNotAbortTheRemainingFeatures` · `FeatureSyncPipelineTest#sapPortThrowingLeavesTheLineInCommunicationError` |
| AC-12 | Ídem, entonces se emite **siempre** el aviso de fallo parcial con el resultado de **cada** parte, su motivo y el `cycleId` | `SyncCustomerUseCaseTest#partialFailureCarriesEveryFeatureStepWithItsReason` · `KafkaSyncNotificationAdapterTest#alertCarriesCycleIdAggregateStateAndPerFeatureDetail` |
| AC-13 | Dado un ciclo con una parte `INVALID` y otra que llegó a SAP, entonces el agregado termina en `SAP_ERROR` (cero confianza), no en `INVALID`; solo con **todas** `INVALID` es `INVALID` | `SyncCustomerUseCaseTest#aggregateIsSapErrorWhenAnyPartReachedSap` · `#aggregateIsInvalidOnlyWhenEveryPartIsInvalid` |
| AC-14 | Dado un ciclo en el que **ninguna** parte llegó a SAP y todas fallaron por comunicación, entonces el agregado queda en `SAP_ERROR`, el aviso se emite y la excepción se propaga para que la ingesta reintente; con una parte en SAP **no** se propaga | `SyncCustomerUseCaseTest#allPartsFailingBeforeReachingSapRethrowsSoKafkaRetries` · `#mixedFailureDoesNotRethrowBecauseRetryingWouldDuplicate` |
| AC-15 | Dado un fallo parcial, cuando se consulta `GET /customers/{id}/state`, entonces devuelve el estado de cada parte **con su motivo** y la traza ordenada de pasos del último ciclo | `CustomerStateUseCaseTest#lastCycleTraceListsEveryStepWithItsReason` · `CustomerStateControllerTest#stateExposesTheCycleTrace` |
| AC-16 | Dado un rol `sap-external-read`, cuando consulta el estado, entonces el motivo del error va enmascarado | `CustomerStateControllerTest#externalReadNeverSeesUnmaskedErrorDetail` |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

Un contador por transición (`SyncMetrics`). Cuando se abre ciclo sobre uno que
quedó en vuelo, se registra en log con el estado abandonado: es la señal de que
un proceso murió a mitad.

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| R-1 dedupe | `SyncCustomerUseCase.execute` (`alreadySent`) | `SyncCustomerUseCaseTest` |
| R-5 apertura de ciclo | `SyncStateRepositoryPort.beginCycle` → `SyncStateMachine.beginCycle` | `SyncCustomerUseCaseTest#resyncsCustomerStuckInSapError` |
| R-6 fallo tras `VALID` → `ERROR` | `SyncCustomerUseCase.execute` (bloque protegido) | `SyncCustomerUseCaseTest#infrastructureFailureAfterValidMarksErrorAndPropagates` |
| R-7 estado final | `SyncCustomerUseCase.execute` | `SyncCustomerUseCaseTest#partialValidationSkipsInvalidFeatures` |
| R-7 cero confianza | `SyncCustomerUseCase.aggregateStateOf` | `SyncCustomerUseCaseTest#aggregateIsSapErrorWhenAnyPartReachedSap` · `#aggregateIsInvalidOnlyWhenEveryPartIsInvalid` |
| R-9 sin compensación: aviso, estado y **motivo** por parte, traza por ciclo | `SyncCustomerUseCase.sendFeatures` (un `try` por parte) · `common/domain/FeatureOutcome` · `common/domain/port/SyncNotificationPort.SyncPartialFailure` · `common/kafka/KafkaSyncNotificationAdapter` · `application/general/CustomerStateUseCase` (`CycleTrace`, `Step`) · `bootstrap/web/CustomerStateController` | `SyncCustomerUseCaseTest` · `KafkaSyncNotificationAdapterTest` · `CustomerStateUseCaseTest` · `CustomerStateControllerTest` |
| R-10 relanzado | `SyncCustomerUseCase.execute` (`nothingReachedSap`) · `common/domain/NothingReachedSapException` · `sap.partial-failure.rethrow-when-nothing-reached-sap` | `SyncCustomerUseCaseTest#allPartsFailingBeforeReachingSapRethrowsSoKafkaRetries` |
| R-8 imagen tras ACK / histórico por intento | `SyncCustomerUseCase.execute` · `adapters/index/CustomerHistoryDoc.from` | `SyncCustomerUseCaseTest#returnsSapErrorWhenAnyFeatureSapError` · `ElasticsearchCustomerIndexerTest#retriesWithTheSameHashKeepBothVersions` |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-19 | §3: la entrada es el **aviso fino**; ni el payload ni el hash del mensaje son fuente de nada. El hash se calcula sobre el snapshot releído. AC-11 y AC-12 nuevos ([ADR-0013](../../architecture/adr/0013-outbox-mensaje-fino-sin-payload.md)) | — |
| 2026-09-18 | Verificación previa del Business Partner: si SAP ya lo tiene se actualiza con `If-Match`; si el `GET` no responde no se escribe nada y la parte queda en `COMMUNICATION_ERROR`. §4 declara la clave de lookup | — |
| 2026-09-18 | **El fallo parcial se marca y se avisa siempre** (auditoría N1): el envío de cada parte va en su propio `try`, el bucle nunca aborta, cada línea cierra con su motivo y el aviso lleva la traza de pasos del ciclo (`cycleId`, estado del agregado y un paso por parte). R-7 pasa a **cero confianza** (D-14): `INVALID` solo si ninguna parte llegó a SAP. R-10 nueva (D-15): se propaga solo cuando es demostrable que nada entró en SAP. `GET /customers/{id}/state` devuelve el motivo y `lastCycle`, con el motivo enmascarado para `sap-external-read`. AC-11..AC-16 | — |
| 2026-09-14 | **Verificación en vivo de R-9**: con el mock devolviendo 500 solo en `CustomerBanking` y un `UPDATE` en el legacy (CDC) y un `POST /sync`, el agregado terminó en `SAP_ERROR`, `GET /customers/CUST-001/state` mostró `ADDRESS/FISCAL/CONTACT=SENT_SAP` y `BANKING=SAP_ERROR`, se emitió el `WARN` y el mensaje `SYNC_PARTIAL_FAILURE` en `sap.sync.alerts`, `sap_sync_feature_result_total{feature="BANKING",result="SAP_ERROR"}=1` y la imagen no cambió; al restaurar el mock, `SENT_SAP` e imagen actualizada. Hallazgo colateral: en modo abierto los endpoints devolvían 403 (corregido, seguridad-api R-6/AC-11) | — |
| 2026-09-14 | D-2 decidida ([ADR-0010](../../architecture/adr/0010-sin-compensacion-entre-features-marcar-y-avisar.md)): R-9, sin compensación; aviso por log + `sap.sync.alerts` + métrica por feature; `GET /customers/{id}/state`. AC-8, AC-9, AC-10 | — |
| 2026-09-12 | Verificación en vivo de R-1 y R-8: ver [`../common/idempotencia-y-dedupe.md`](../common/idempotencia-y-dedupe.md) §10 (SAP_ERROR sin tocar la imagen, A→B→A reenviado) | — |
| 2026-09-12 | Fase 6 del plan (A1, A31, imagen antes del ACK): R-1 dedupe contra el último `SENT_SAP`; R-8 imagen solo tras `SENT_SAP` e histórico con un documento por intento. AC-7 | — |
| 2026-09-12 | **Verificación en vivo** de AC-4 y AC-6: `CUST-001` atascado en `SENDING_SAP` se re-sincronizó abriendo ciclo; con SAP devolviendo 500 un cambio real por CDC terminó en `SAP_ERROR` y el evento siguiente abrió ciclo (`RECEIVED`, seq 17) y llegó a `SENT_SAP` (seq 24). Antes moría en la DLT | — |
| 2026-09-11 | Spec inicial, escrito al abordar B1/B12 de la auditoría: el agregado en `SAP_ERROR` no volvía a sincronizarse (`SAP_ERROR → RECEIVED` no era transición permitida) y un fallo de infraestructura tras `VALID` dejaba la entidad colgada. Se introduce la regla R-5 (un evento nuevo siempre abre ciclo) y R-6 | — |
