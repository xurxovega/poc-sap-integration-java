# Máquina de estados de sincronización

| | |
|---|---|
| **Módulo** | `common` (shared kernel) — la usan todos los dominios |
| **Estado** | ✅ implementada (`SyncStateMachine`, `SyncState`) |
| **Entradas** | ninguna: es una capacidad transversal, no un flujo |
| **Destino SAP** | ninguno |
| **Última revisión** | 2026-09-10 |

## 1. Objetivo

Dar a cada registro sincronizado una **línea de vida auditable**: en qué punto
del pipeline está, cómo llegó ahí y si puede volver a entrar. Es lo que hace
consultable el estado de una entidad y lo que impide que un flujo avance por
donde no debe.

## 2. Alcance

**Dentro**: los estados, las transiciones permitidas, los estados de entrada y
las reglas de re-entrada. Es dominio-agnóstica: no sabe qué es un cliente ni un
artículo.

**Fuera**:

- La **persistencia** de las transiciones (`SyncStateRepositoryPort` y su
  implementación Mongo).
- La compensación entre features cuando un envío falla a medias (no hay saga).
- La recuperación de un registro atascado en un estado intermedio tras una caída
  del proceso — brecha abierta, ver [`../README.md`](../README.md) §6.

## 3. Entrada

`transition(from, to)`, donde `from` es el **estado almacenado** de la entidad
(no el que declara quien llama) y `null` si no tiene historial.

> Esto es deliberado y es el corazón de la garantía: el repositorio calcula el
> `from` leyendo el último estado persistido. Un use case no puede saltarse la
> secuencia declarando un `from` conveniente.

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | Una entidad **sin historial** solo puede entrar por `RECEIVED` (pipeline agregado) o `VALIDATING` (pipeline por feature) | `IllegalStateException` |
| R-2 | Toda transición debe estar en la tabla de §6 | `IllegalStateException` |
| R-3 | Los estados que **cierran un ciclo** admiten re-entrada, para que una entidad se pueda re-sincronizar cuando llega un evento nuevo | — |
| R-4 | La re-entrada respeta el pipeline de origen: el agregado vuelve a `RECEIVED`, una feature vuelve a `VALIDATING` | `IllegalStateException` |
| R-5 | Los estados de error son recuperables: `ERROR` vuelve a `RECEIVED`, `COMMUNICATION_ERROR` reanuda en el paso que falló | — |

`SENT_SAP` e `INVALID` son terminales **del ciclo** (así los reporta
`isTerminal`), no absolutos: R-3 permite reabrirlos. La idempotencia la
garantiza el dedupe por `payloadHash`, no el bloqueo de la máquina.

## 5. Salida

El estado resultante, o excepción si la transición es ilegal. Cada transición se
persiste con `timestamp`, origen y `payloadHash`.

## 6. Estados y transiciones

Dos recorridos comparten la misma máquina:

```
Agregado:  RECEIVED → FETCHING → VALIDATING → VALID → INDEXING → INDEXED → SENDING_SAP → SENT_SAP
Feature:                         VALIDATING → VALID ──────────────────────→ SENDING_SAP → SENT_SAP
```

La feature no indexa: la imagen y el histórico son del agregado.

| Desde | Hacia |
|---|---|
| *(sin historial)* | `RECEIVED` (agregado) · `VALIDATING` (feature) |
| `RECEIVED` | `FETCHING`, `ERROR` |
| `FETCHING` | `VALIDATING`, `ERROR`, `COMMUNICATION_ERROR` |
| `VALIDATING` | `VALID`, `INVALID`, `ERROR` |
| `VALID` | `INDEXING` (agregado), `SENDING_SAP` (feature), `SENT_SAP` (sin cambios reales), `ERROR` |
| `INVALID` | `RECEIVED` (re-entrada del agregado), `VALIDATING` (re-entrada de feature) |
| `INDEXING` | `INDEXED`, `ERROR` |
| `INDEXED` | `SENDING_SAP`, `ERROR` |
| `SENDING_SAP` | `SENT_SAP`, `SAP_ERROR`, `INVALID`, `COMMUNICATION_ERROR` |
| `SENT_SAP` | `RECEIVED` (re-entrada del agregado), `VALIDATING` (re-entrada de feature) |
| `SAP_ERROR` | `SENDING_SAP` (reintento), `VALIDATING` (re-entrada de feature), `ERROR` |
| `COMMUNICATION_ERROR` | `FETCHING`, `SENDING_SAP`, `ERROR` |
| `ERROR` | `RECEIVED` |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dada una entidad sin historial, cuando entra por `RECEIVED` o `VALIDATING`, entonces se acepta; cualquier otro estado inicial se rechaza | `SyncStateMachineTest#initialStates…` |
| AC-2 | Dado el recorrido completo del pipeline agregado, cuando se ejecuta paso a paso, entonces todas las transiciones son legales | `SyncStateMachineTest#happyPath` |
| AC-3 | Dado `VALID`, cuando el pipeline es por feature, entonces puede ir directo a `SENDING_SAP` sin pasar por `INDEXING` | `SyncStateMachineTest#validGoesToSendingSapInTheFeaturePipeline` |
| AC-4 | Dada una **feature ya enviada** (`SENT_SAP`), cuando llega un evento nuevo, entonces puede re-entrar por `VALIDATING` | `SyncStateMachineTest#featureLineReentersThroughValidating` |
| AC-5 | Ídem desde `INVALID` y desde `SAP_ERROR`: una feature rechazada o fallida puede reintentarse con un evento nuevo | `SyncStateMachineTest#featureLineReentersThroughValidating` |
| AC-6 | Dado un agregado en `SENT_SAP`, cuando llega un evento nuevo, entonces re-entra por `RECEIVED` (no por `VALIDATING`) | `SyncStateMachineTest#reSyncFromTerminalStates` |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

Cada transición incrementa un contador etiquetado por dominio y estado
(`SyncMetrics`). Una transición ilegal se propaga como excepción hasta el
listener o el controller, y en la ingesta Kafka acaba en la DLT tras agotar los
reintentos.

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| §6 tabla de transiciones | `common/domain/SyncStateMachine.java` | `SyncStateMachineTest` |
| R-1 estados iniciales | `SyncStateMachine.INITIAL_STATES` | `SyncStateMachineTest` |
| §3 el `from` se lee del almacén | `common/adapters/persistence/MongoSyncStateRepository.java` | `MongoSyncStateRepositoryTest` |
| R-5 recuperación de errores | `SyncStateMachine` | `ErrorStateRecoveryTest` |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-10 | Spec inicial. Se añade la **re-entrada de las líneas de feature** por `VALIDATING` desde `SENT_SAP`, `INVALID` y `SAP_ERROR` (AC-4/AC-5): sin ella, un segundo evento con cambios reales rompía el pipeline por feature y el mensaje acababa en la DLT | — |
| 2026-09-09 | `VALID → SENDING_SAP` para el pipeline por feature (AC-3) | — |
