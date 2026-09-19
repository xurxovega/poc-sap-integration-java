# Sincronización del artículo

| | |
|---|---|
| **Dominio** | `article` |
| **Estado** | ✅ implementado (verificado end-to-end contra el SAP simulado) |
| **Entradas** | CDC (`outbox.ARTICLE`) y REST `POST /articles/sync`, siempre a través de `SyncArticleUseCase` |
| **Destino SAP** | S/4 nativo (`API_PRODUCT`) |
| **Última revisión** | 2026-09-19 |

## 1. Objetivo

Que cada alta o modificación de un artículo en el legacy (PostgreSQL) llegue a
SAP como producto, una sola vez por cambio real, dejando rastro de cada envío.
Es el pipeline «simple» del proyecto: una entidad, un envío, sin features.

## 2. Alcance

**Dentro**: lectura del artículo en el legacy, validación, histórico, envío a
`API_PRODUCT`, imagen actual.

**Fuera** (y por qué):
- Stock, precios, características y números de serie: APIs catalogadas sin
  consumidor ([`../sap-api-catalog.md`](../sap-api-catalog.md)).
- Baja del artículo: no hay evento de baja en el legacy hoy.
- Upsert idempotente contra `API_PRODUCT` (`PATCH` con `If-Match`): Fase 3 del
  plan, tras la comprobación contra el tenant.

## 3. Entrada

`IngestionMessage` — **aviso de cambio fino** con `entityId` = id del artículo;
contrato completo en
[`../common/contrato-mensaje-de-cambio.md`](../common/contrato-mensaje-de-cambio.md).
El use case relee la entidad del legacy (`ArticleLegacyRepositoryPort.fetch`) y
**calcula el hash sobre ese snapshot** (`PayloadHasher`): ni el `payload` ni el
`payloadHash` del mensaje son fuente de nada
([ADR-0013](../../architecture/adr/0013-outbox-mensaje-fino-sin-payload.md)).

| Campo (`Article`) | Tipo | Obligatorio | Notas |
|---|---|---|---|
| `id` | texto | sí | clave del legacy |
| `sku` | texto | sí | → `Product` |
| `description` | texto | sí | → `Description`; vacía ⇒ `INVALID` |
| `category` | texto | no | uso interno |
| `unit` | texto | sí | → `BaseUnit` |
| `status` | `ACTIVE` \| `INACTIVE` | sí | → `Status` |

Reglas de validación en `ArticleValidations`.

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | Un `payloadHash` igual al del último `SENT_SAP` no se reprocesa ([`../common/idempotencia-y-dedupe.md`](../common/idempotencia-y-dedupe.md) R-1) | `SENT_SAP` inmediato |
| R-2 | Si el legacy no devuelve el artículo, el ciclo termina en `ERROR` | `ERROR` |
| R-3 | Si el artículo no valida, no se envía nada | `INVALID` |
| R-4 | Si el ciclo anterior terminó en `SENT_SAP` y el snapshot es idéntico a la imagen, no se reenvía | `SENT_SAP` desde `VALID` |
| R-5 | Un evento nuevo siempre abre ciclo, sea cual sea el estado anterior ([`../common/maquina-de-estados.md`](../common/maquina-de-estados.md) R-3) | — |
| R-6 | Cualquier fallo de infraestructura tras `VALID` deja `ERROR` y se propaga | `ERROR` + excepción |
| R-7 | El histórico registra el snapshot **antes** de enviar, un documento por intento; la imagen se guarda **solo** si SAP acepta ([`../common/idempotencia-y-dedupe.md`](../common/idempotencia-y-dedupe.md) R-4, R-5) | — |

## 5. Salida

`POST /sap/opu/odata/sap/API_PRODUCT` (`S4ArticleAdapter`):

| Campo origen | Campo SAP | Transformación |
|---|---|---|
| `sku` | `Product` | — |
| `description` | `Description` | — |
| `unit` | `BaseUnit` | — |
| `status` | `Status` | nombre del enum |
| *(cualquier campo nulo)* | *(se omite)* | `SapJsonMapper` NON_NULL: una cadena vacía no es «sin valor» para S/4 (A19/C10) |

Además: documento en `articles_history` (id `articleId-hash-epochMillis`) e
imagen en `articles_current` tras el ACK.

## 6. Estados y errores

```
RECEIVED → FETCHING → VALIDATING → VALID → INDEXING → INDEXED → SENDING_SAP → {SENT_SAP | SAP_ERROR}
```

| Situación | Estado final | Siguiente evento |
|---|---|---|
| Legacy sin el artículo | `ERROR` | abre ciclo nuevo |
| Validación falla | `INVALID` | abre ciclo nuevo |
| SAP rechaza | `SAP_ERROR` | abre ciclo nuevo |
| Fallo de infraestructura tras `VALID` | `ERROR` | abre ciclo nuevo |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dado un artículo válido y SAP en 2xx, entonces `SENT_SAP`, histórico indexado e imagen guardada | `SyncArticleUseCaseTest#happyPath` |
| AC-2 | Dado un snapshot cuyo hash ya está enviado, entonces `SENT_SAP` sin SAP (el legacy sí se lee) | `SyncArticleUseCaseTest#dedupeUsesTheHashOfTheSnapshotNotTheMessage` |
| AC-7 | Dado un aviso fino (sin hash y sin payload), entonces el ciclo se ejecuta con el estado actual del legacy y el hash calculado | `SyncArticleUseCaseTest#thinMessageWithoutPayloadIsProcessed` |
| AC-3 | Legacy sin el artículo ⇒ `ERROR`; artículo inválido ⇒ `INVALID`; en ambos sin llamar a SAP | `SyncArticleUseCaseTest#fetchEmptyReturnsError` · `#invalidArticleReturnsInvalid` |
| AC-4 | SAP rechaza ⇒ `SAP_ERROR`, histórico indexado, imagen **sin** guardar | `SyncArticleUseCaseTest#sapErrorReturnsSapError` |
| AC-5 | Snapshot idéntico a la imagen tras `SENT_SAP` ⇒ no se reenvía; distinto ⇒ se reenvía y se guarda | `SyncArticleUseCaseTest#unchangedSnapshotAfterSentSapSkipsResend` · `#changedSnapshotAfterSentSapIsResent` |
| AC-6 | Fallo de infraestructura tras `VALID` ⇒ `ERROR` y excepción | `SyncArticleUseCaseTest#infrastructureFailureAfterValidMarksErrorAndPropagates` |
| AC-7 | El adaptador real emite `POST API_PRODUCT` con `Product`, `Description`, `BaseUnit`, `Status`, `Authorization` e `Idempotency-Key` | `S4ArticleContractTest#realAdapterPostsMappedProduct` |
| AC-8 | Cada etapa registra su duración | `SyncArticleUseCaseTest#happyPathRecordsTheDurationOfEveryStage` |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

`sap_sync_state_total{domain="article"}`, `sap_sync_stage_duration{domain="article"}`;
logs «inicio»/«fin»/«dedupe»/«sin cambios reales» con `entityId`.

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| R-1..R-7, §6 | `article/application/SyncArticleUseCase.java` | `SyncArticleUseCaseTest` |
| §3 validación | `article/domain/ArticleValidations.java` | `ArticleValidationsTest` |
| §5 mapeo | `article/adapters/sap/S4ArticleAdapter.java` | `S4ArticleAdapterTest` · `S4ArticleContractTest` |
| Histórico / imagen | `article/adapters/index/ElasticsearchArticleIndexer.java` · `ArticleHistoryDoc` · `article/adapters/persistence/MongoArticleImageStore.java` | `ElasticsearchArticleIndexerTest` |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-19 | §3: la entrada es el **aviso fino**; el hash se calcula sobre el snapshot releído del legacy. AC-2 reescrito y AC-7 nuevo ([ADR-0013](../../architecture/adr/0013-outbox-mensaje-fino-sin-payload.md)) | — |
| 2026-09-12 | Fase 7 (A19/C10): `S4ArticleAdapter` serializa con `SapJsonMapper` via `S4ProductDto`; los campos nulos se omiten en vez de viajar como `""`. Test `nullFieldsAreOmittedInsteadOfSentAsEmptyStrings` | — |
| 2026-09-12 | Spec inicial, escrito al aplicar la Fase 6 del plan: imagen guardada solo tras el ACK de SAP, histórico con un documento por intento, dedupe contra el último `SENT_SAP`. Recoge el comportamiento ya verificado end-to-end el 2026-09-10 | — |
