# Baja de mandato SEPA

| | |
|---|---|
| **Dominio** | `customer` |
| **Estado** | ⚠️ implementado sin llamador: `DeleteMandateUseCase` existe y está probado, pero ningún listener ni endpoint lo invoca todavía (el legacy no emite eventos de mandato) |
| **Entradas** | ninguna cableada hoy; prevista por CDC (`outbox.CUSTOMER`, tipo `MANDATE`, operación `DELETE`) |
| **Destino SAP** | S/4 nativo, `API_APAR_SEPA_MANDATE_SRV` (siempre; no hay ruta BTP para mandatos) |
| **Última revisión** | 2026-09-12 |

## 1. Objetivo

Que cuando un cliente revoca una domiciliación, el mandato SEPA deje de poder
usarse en SAP **sin perder su historial**: los cobros ya ejecutados lo
referencian y Finanzas tiene que poder auditarlos.

## 2. Alcance

**Dentro**: revocación de un mandato identificado por su referencia, registro
del ciclo en la línea de estado `BANKING` del cliente.

**Fuera** (y por qué):
- Alta y datos del mandato: [`sincronizacion-datos-bancarios.md`](sincronizacion-datos-bancarios.md).
- Borrado físico: SAP no lo permite para mandatos usados y nosotros tampoco lo
  queremos (modelo de bloqueo, coherente con [`baja-cliente.md`](baja-cliente.md)).
- Cableado del evento de entrada: pendiente de que el legacy emita mandatos
  ([`../README.md`](../README.md) §6).

## 3. Entrada

| Campo | Tipo | Obligatorio | Notas |
|---|---|---|---|
| `mandateId` | texto | sí | Referencia única del mandato (`SEPAMandate`) |
| `customerId` | texto | sí | Cliente al que pertenece; da nombre a la línea de estado `<<customerId>>:BANKING` |
| `payloadHash` | texto | sí | Idempotencia del evento |

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | La baja abre ciclo por `SENDING_SAP` en la línea `BANKING` del cliente, desde cualquier estado previo | — |
| R-2 | Revocar es un **cambio de estado** en SAP: `PATCH SEPAMandateSet(Creditor='…',SEPAMandate='…')` con `SEPAMandateStatus = 3` (cancelado). Nunca un `DELETE` ni un `POST` | Un `DELETE` fallaría en SAP para mandatos usados y borraría el rastro de auditoría; hasta la Fase 3.2 se enviaba un `POST` con un payload bancario ficticio a una API inexistente (auditoría B3) |
| R-3 | Si SAP acepta, el ciclo termina en `SENT_SAP`; si rechaza, en `SAP_ERROR` y el siguiente evento vuelve a intentarlo | — |
| R-4 | Sin `sap.sepa.creditor-id` la revocación falla antes de llamar a SAP (la clave del mandato incluye al acreedor) | — |

## 5. Salida

`PATCH …/SEPAMandateSet(Creditor='<creditor-id>',SEPAMandate='<mandateId>')`
con cuerpo `{"SEPAMandateStatus":"3"}` y cabecera `Idempotency-Key = payloadHash`.
Nada cambia en nuestros almacenes: la imagen del cliente conserva el
identificador del mandato en `mandateIds`; su estado vive en SAP.

## 6. Estados y errores

| Situación | Estado final | Reintentable |
|---|---|---|
| SAP acepta (2xx) | `SENT_SAP` | — |
| SAP rechaza | `SAP_ERROR` | con el siguiente evento |
| Acreedor no configurado | excepción de configuración, se propaga | tras configurar |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dado un mandato, cuando llega la baja, entonces se invoca `revoke` en el puerto y **no** `send`, y el ciclo termina en `SENT_SAP` | `DeleteMandateUseCaseTest#happyPathRevokesTheMandate` |
| AC-2 | Dado que SAP rechaza la revocación, entonces el ciclo termina en `SAP_ERROR` | `DeleteMandateUseCaseTest#sapErrorReturnsSapError` |
| AC-3 | Cuando se revoca, entonces el adaptador emite un `PATCH` sobre la clave `(Creditor, SEPAMandate)` con `SEPAMandateStatus = 3`, sin `POST` ni `DELETE` | `SepaMandateODataAdapterTest#revokePatchesStatusCancelledOnTheMandateKey` · `SepaMandateContractTest#realAdapterRevokesByPatchingStatusOnTheKey` (HTTP real) |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

`SyncMetrics` con `stage=banking`; log del cliente SAP en caso de error.

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| R-1, R-3 | `application/banking/DeleteMandateUseCase.java` | `DeleteMandateUseCaseTest` |
| R-2, R-4 | `adapters/sap/odata/SepaMandateODataAdapter.revoke` · `domain/port/MandateSapOutboundPort.revoke` | `SepaMandateODataAdapterTest` · `SepaMandateContractTest` |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-12 | Spec inicial (plan Fase 3.2, auditoría B3/A18). `DeleteMandateUseCase` pasa de enviar un payload bancario ficticio por `BankingSapPort` a revocar por `MandateSapOutboundPort.revoke` (PATCH de estado). Se documenta que no tiene llamador | — |
