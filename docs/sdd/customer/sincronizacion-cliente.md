# Sincronización del cliente (agregado)

| | |
|---|---|
| **Dominio** | `customer` |
| **Estado** | ✅ implementado — re-entrada y fallo de infra corregidos (Fase 1); verificado en vivo el 2026-09-12 |
| **Entradas** | CDC (`outbox.CUSTOMER`) · REST `POST /customers/sync` |
| **Destino SAP** | BTP o S/4 nativo, según `sap.odata.<feature>.enabled` |
| **Última revisión** | 2026-09-12 |

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

`IngestionMessage` — contrato en [`../../architecture/TECH.md`](../../architecture/TECH.md) §6.
El use case **re-lee la entidad del legacy** por `entityId`; el `payload` del
mensaje no se usa como fuente de datos, solo el hash para idempotencia.

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | Un `payloadHash` igual al del **último** `SENT_SAP` no se reprocesa ([`../common/idempotencia-y-dedupe.md`](../common/idempotencia-y-dedupe.md) R-1; antes valía cualquier `SENT_SAP` histórico y A→B→A descartaba el tercer evento) | `SENT_SAP` inmediato, sin tocar SAP |
| R-2 | Si el legacy no devuelve la entidad, el ciclo termina en error | `ERROR` |
| R-3 | Si el agregado no valida, no se envía nada | `INVALID` |
| R-4 | Si el ciclo anterior terminó en `SENT_SAP` y el snapshot es idéntico a la imagen (= lo que SAP tiene), no se reenvía | `SENT_SAP` directo desde `VALID` |
| R-5 | **Un evento nuevo siempre abre ciclo**, sea cual sea el estado anterior (`SENT_SAP`, `SAP_ERROR`, `ERROR`, o uno intermedio si el proceso murió) | — |
| R-6 | **Cualquier fallo de infraestructura tras `VALID`** (Mongo, ES, HTTP) deja la entidad en `ERROR`, nunca colgada en un estado intermedio, y se propaga para que la ingesta reintente | `ERROR` + excepción |
| R-7 | El estado final del ciclo lo deciden las features: alguna `INVALID` → `INVALID`; todas `SENT_SAP` → `SENT_SAP`; en otro caso `SAP_ERROR` | — |
| R-9 | **Sin compensación** ([ADR-0010](../../architecture/adr/0010-sin-compensacion-entre-features-marcar-y-avisar.md)): si alguna parte no llega a `SENT_SAP`, las que entraron se quedan en SAP, el agregado termina en `SAP_ERROR`/`INVALID`, cada línea de feature conserva su estado, y se **avisa** (`WARN` con partes OK y fallidas, mensaje en `sap.sync.alerts`, métrica `sap_sync_feature_result_total`). El siguiente evento reenvía todas | Sin aviso nadie sabría que SAP tiene un cliente a medias |
| R-8 | La **imagen** se persiste **solo** cuando el ciclo termina en `SENT_SAP`; el **histórico** registra el snapshot antes de enviar, un documento por intento ([`../common/idempotencia-y-dedupe.md`](../common/idempotencia-y-dedupe.md) R-4, R-5) | Antes la imagen se guardaba antes de enviar y un `SAP_ERROR` dejaba «SAP tiene X» siendo falso |

## 5. Salida

Documento en `customers_history` con el snapshot íntegro **por intento** (id
`customerId-hash-epochMillis`; propósito: **auditar qué se envió a SAP en
cualquier momento**), una llamada a SAP por feature, e imagen en
`customers_current` **solo tras `SENT_SAP`** (la imagen es lo que SAP tiene).
`GET /customers/{id}/state` devuelve el estado del agregado y de cada parte
(último estado, hash e instante) para saber dónde se quedó un ciclo. El histórico conserva el snapshot completo por
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
| Fallo de infraestructura tras `VALID` | `ERROR` | abre ciclo nuevo |
| Proceso muere a mitad | estado intermedio | **abre ciclo nuevo** y se registra que el anterior quedó en vuelo |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dado un cliente válido, cuando todas las features llegan a `SENT_SAP`, entonces el agregado termina en `SENT_SAP` | `SyncCustomerUseCaseTest#happyPathReturnsSentSapWhenAllFeaturesSucceed` |
| AC-2 | Dado un `payloadHash` ya enviado, cuando llega de nuevo, entonces responde `SENT_SAP` sin llamar a SAP | `SyncCustomerUseCaseTest#alreadySentPayloadSkipsPipeline` |
| AC-3 | Dado que el legacy no devuelve la entidad, entonces el ciclo termina en `ERROR` | `SyncCustomerUseCaseTest#returnsErrorWhenLegacyFetchEmpty` |
| AC-4 | Dado un agregado en `SAP_ERROR` por un ciclo anterior, cuando llega un evento nuevo contra la máquina de estados **real**, entonces se re-sincroniza y llega a `SENT_SAP` | `SyncCustomerUseCaseTest#resyncsCustomerStuckInSapError` |
| AC-5 | Dado un fallo de infraestructura tras `VALID` (p. ej. el indexador lanza), entonces el estado queda en `ERROR` y la excepción se propaga | `SyncCustomerUseCaseTest#infrastructureFailureAfterValidMarksErrorAndPropagates` |
| AC-6 | Dado un agregado que quedó en `SENDING_SAP` porque el proceso murió, cuando llega un evento nuevo, entonces abre ciclo en vez de fallar | `SyncCustomerUseCaseTest#reopensCycleWhenPreviousOneWasLeftInFlight` |
| AC-7 | Dado que una feature acaba en `SAP_ERROR` o `INVALID`, entonces el histórico registra el intento y la imagen **no** cambia; con todas en `SENT_SAP`, la imagen se guarda | `SyncCustomerUseCaseTest#returnsSapErrorWhenAnyFeatureSapError` · `#returnsInvalidWhenAnyFeatureReturnsInvalid` · `#happyPathReturnsSentSapWhenAllFeaturesSucceed` |
| AC-8 | Dado que alguna parte no llega a `SENT_SAP`, entonces se notifica con el resultado de **cada** parte y se cuenta por feature; con todas en `SENT_SAP` no hay aviso | `SyncCustomerUseCaseTest#returnsSapErrorWhenAnyFeatureSapError` · `#fullSuccessDoesNotNotify` · `SyncMetricsTest#featureResultsAreCountedPerFeatureAndResult` |
| AC-9 | La notificación es un `WARN` con partes OK/fallidas y un JSON `SYNC_PARTIAL_FAILURE` en `sap.sync.alerts`; sin Kafka o con él caído, solo el `WARN`, sin romper el ciclo | `KafkaSyncNotificationAdapterTest` (2) |
| AC-10 | `GET /customers/{id}/state` devuelve el último estado, hash e instante del agregado y de cada feature con historial | `CustomerStateUseCaseTest` (2) |

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
| R-9 sin compensación: aviso y estado por parte | `SyncCustomerUseCase.sendFeatures` · `common/domain/port/SyncNotificationPort` · `common/kafka/KafkaSyncNotificationAdapter` · `application/general/CustomerStateUseCase` · `bootstrap/web/CustomerStateController` | `SyncCustomerUseCaseTest` · `KafkaSyncNotificationAdapterTest` · `CustomerStateUseCaseTest` |
| R-8 imagen tras ACK / histórico por intento | `SyncCustomerUseCase.execute` · `adapters/index/CustomerHistoryDoc.from` | `SyncCustomerUseCaseTest#returnsSapErrorWhenAnyFeatureSapError` · `ElasticsearchCustomerIndexerTest#retriesWithTheSameHashKeepBothVersions` |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-14 | D-2 decidida ([ADR-0010](../../architecture/adr/0010-sin-compensacion-entre-features-marcar-y-avisar.md)): R-9, sin compensación; aviso por log + `sap.sync.alerts` + métrica por feature; `GET /customers/{id}/state`. AC-8, AC-9, AC-10 | — |
| 2026-09-12 | Verificación en vivo de R-1 y R-8: ver [`../common/idempotencia-y-dedupe.md`](../common/idempotencia-y-dedupe.md) §10 (SAP_ERROR sin tocar la imagen, A→B→A reenviado) | — |
| 2026-09-12 | Fase 6 del plan (A1, A31, imagen antes del ACK): R-1 dedupe contra el último `SENT_SAP`; R-8 imagen solo tras `SENT_SAP` e histórico con un documento por intento. AC-7 | — |
| 2026-09-12 | **Verificación en vivo** de AC-4 y AC-6: `CUST-001` atascado en `SENDING_SAP` se re-sincronizó abriendo ciclo; con SAP devolviendo 500 un cambio real por CDC terminó en `SAP_ERROR` y el evento siguiente abrió ciclo (`RECEIVED`, seq 17) y llegó a `SENT_SAP` (seq 24). Antes moría en la DLT | — |
| 2026-09-11 | Spec inicial, escrito al abordar B1/B12 de la auditoría: el agregado en `SAP_ERROR` no volvía a sincronizarse (`SAP_ERROR → RECEIVED` no era transición permitida) y un fallo de infraestructura tras `VALID` dejaba la entidad colgada. Se introduce la regla R-5 (un evento nuevo siempre abre ciclo) y R-6 | — |
