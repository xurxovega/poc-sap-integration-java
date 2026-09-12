# Baja de cliente

| | |
|---|---|
| **Dominio** | `customer` |
| **Estado** | ✅ implementado con modelo de bloqueo; verificado en vivo el 2026-09-12 por CDC |
| **Entradas** | CDC (`outbox.CUSTOMER` con `operation=DELETE`) |
| **Destino SAP** | BTP o S/4 nativo, según `sap.odata.customer.enabled` |
| **Última revisión** | 2026-09-12 |

## 1. Objetivo

Reflejar en SAP que un cliente ha sido dado de baja en el legacy, y dejar en
nuestros almacenes constancia de esa baja **sin destruir el rastro**: la ficha
pasa a estar bloqueada, no desaparece.

## 2. Alcance

**Dentro**: recibir el `DELETE` por CDC, emitir la operación de baja hacia SAP,
bloquear la imagen local y registrar el ciclo en la máquina de estados.

**Fuera**: qué significa exactamente «baja» en S/4 (flag de bloqueo del Business
Partner vs borrado físico) — se valida contra el tenant de test en la Fase 3 del
plan. La baja por REST (hoy `POST /customers/sync` con `operation=DELETE` **no**
enruta a este use case; auditoría C7, Fase 7).

## 3. Entrada

`entityId` y `payloadHash` del mensaje CDC. No se re-lee el legacy: el cliente
ya no está allí.

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | La baja abre ciclo **desde cualquier estado previo** de la entidad, incluido ninguno | — |
| R-2 | Hacia SAP se emite una operación de **baja** (`DELETE` sobre la clave de la entidad), nunca un alta vacía | — |
| R-3 | **Modelo de bloqueo**: si SAP acepta, la imagen local pasa a `status = BLOCKED`; no se elimina. El histórico y el estado se conservan | — |
| R-4 | Si SAP rechaza la baja, la imagen no se toca y el ciclo termina en `SAP_ERROR` | `SAP_ERROR` |
| R-5 | Una entidad sin imagen local (nunca sincronizada) puede darse de baja igualmente: se emite a SAP y no hay nada que bloquear | — |

Por qué bloqueo y no borrado: hay obligación de conservar por motivos fiscales y
mercantiles, y el histórico existe para auditar. Diseñarlo como borrado y
cambiarlo después obligaba a rehacer el camino (decisión del 2026-09-11).

## 5. Salida

Una llamada `DELETE` a SAP sobre `<path>('<entityId>')`. Imagen en
`customers_current` con `status = BLOCKED`. Dos transiciones en `sync_state`:
`SENDING_SAP` → `SENT_SAP` | `SAP_ERROR`.

## 6. Estados y errores

La baja es un pipeline corto que **entra por `SENDING_SAP`**: no hay nada que
leer ni validar.

| Situación | Estado final |
|---|---|
| SAP acepta (2xx) | `SENT_SAP`, imagen bloqueada |
| SAP rechaza | `SAP_ERROR`, imagen intacta |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dada una entidad en cualquier estado previo (`SENT_SAP`, `SAP_ERROR`, `SENDING_SAP` o sin historial), cuando llega la baja, entonces el ciclo se abre y termina en `SENT_SAP` contra la máquina de estados **real** | `DeleteCustomerUseCaseTest#deleteOpensCycleFromAnyPriorState` |
| AC-2 | Cuando se ejecuta la baja, entonces el adaptador emite un `DELETE` HTTP sobre la clave de la entidad y **no** un `POST` | `BtpCustomerAdapterTest#deleteIssuesHttpDeleteOnEntityKey` · `DeleteCustomerUseCaseTest#deleteIssuesDeleteNotSend` |
| AC-3 | Dado que SAP acepta, entonces la imagen local queda con `status = BLOCKED` y no se elimina | `DeleteCustomerUseCaseTest#blocksImageInsteadOfDeleting` |
| AC-4 | Dado que SAP rechaza, entonces la imagen no cambia y el estado es `SAP_ERROR` | `DeleteCustomerUseCaseTest#keepsImageWhenSapFails` |
| AC-5 | Dado un mensaje CDC con `operation=DELETE`, entonces se enruta a este use case | `CustomerKafkaListenerTest#deleteOperationInvokesDeleteUseCase` |
| AC-6 | Un `Customer` nulo ya no es una señal de borrado: el adaptador lo rechaza | `BtpCustomerAdapterTest#nullCustomerIsRejected` |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

Contador por transición. Una baja sobre una entidad sin imagen local se registra
en log a nivel `info`: es válido, pero conviene poder verlo.

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| R-1 apertura de ciclo | `DeleteCustomerUseCase.execute` → `stateRepo.beginCycle(SENDING_SAP)` | `DeleteCustomerUseCaseTest#deleteOpensCycleFromAnyPriorState` |
| R-2 `DELETE` real | `CustomerSapOutboundPort.delete` → `BtpCustomerAdapter.delete` / `BusinessPartnerODataAdapter.delete` → `SapClient.delete` | `BtpCustomerAdapterTest#deleteIssuesHttpDeleteOnEntityKey` |
| R-3 bloqueo | `DeleteCustomerUseCase.execute` (`status = BLOCKED`) | `DeleteCustomerUseCaseTest#blocksImageInsteadOfDeleting` |
| AC-5 enrutado CDC | `CustomerKafkaListener.onMessage` | `CustomerKafkaListenerTest` |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-12 | **Verificación en vivo**: `DELETE` de `CUST-002` en el legacy → Debezium → app → WireMock recibió `DELETE /sap/btp/odata/Customer('CUST-002')` (no `POST`), la imagen quedó `status=BLOCKED` y el ciclo `SENDING_SAP → SENT_SAP` (seq 11-12, origin cdc). Primera vez que la baja se ejecuta desde que existe (CP-08 nunca se había corrido) | — |
| 2026-09-11 | Spec inicial, escrito al abordar B2 de la auditoría. Hasta hoy la baja **nunca se ejecutaba**: entraba por `SENDING_SAP`, que no era estado de entrada, y de haberlo sido habría enviado `POST {}` en vez de `DELETE`. Se adopta el modelo de bloqueo en lugar de borrado | — |
