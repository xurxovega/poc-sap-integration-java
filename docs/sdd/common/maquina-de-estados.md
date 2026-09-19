# Máquina de estados de sincronización

| | |
|---|---|
| **Módulo** | `common` (shared kernel) — la usan todos los dominios |
| **Estado** | ✅ implementada (`SyncStateMachine`, `SyncState`) |
| **Entradas** | ninguna: es una capacidad transversal, no un flujo |
| **Destino SAP** | ninguno |
| **Última revisión** | 2026-09-11 |

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

La máquina distingue **dos intenciones** que antes se confundían en una sola
llamada:

- `beginCycle(actual, entrada)` — **abrir un ciclo nuevo** porque llega un
  evento. `actual` es el último estado almacenado de la entidad (o `null` si no
  tiene historial); `entrada` es el punto por el que arranca el pipeline.
- `advance(from, to)` — **avanzar dentro del ciclo abierto**. `from` es el estado
  almacenado, no el que declara quien llama.

> El repositorio calcula el estado almacenado leyéndolo; un use case no puede
> saltarse la secuencia declarando un `from` conveniente. Esa garantía se
> mantiene: lo que cambia es que abrir ciclo y avanzar ya no comparten tabla.

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | Los **estados de entrada** son los cuatro puntos reales por los que arranca un pipeline: `RECEIVED` (agregado), `VALIDATING` (feature), `SENDING_SAP` (baja), `INDEXING` (indexación). Abrir ciclo por cualquier otro se rechaza | `IllegalStateException` |
| R-2 | Avanzar dentro del ciclo solo por las transiciones de la tabla de §6 | `IllegalStateException` |
| R-3 | **Un evento nuevo siempre puede abrir ciclo**, sea cual sea el estado actual: cerrado (`SENT_SAP`, `INVALID`), de error (`SAP_ERROR`, `ERROR`, `COMMUNICATION_ERROR`) o **en vuelo** (el proceso murió a mitad). La idempotencia la da el dedupe por `payloadHash`, no el bloqueo de la máquina | — |
| R-4 | Si el ciclo anterior estaba en vuelo, la máquina lo **señala** (`isInFlight`) para que se registre; no lo impide | — |
| R-5 | Los estados de error son recuperables también *dentro* del ciclo: `ERROR → RECEIVED`, `COMMUNICATION_ERROR` reanuda en el paso que falló | — |
| R-6 | Cualquier paso del pipeline puede caer a `ERROR` ante un fallo de infraestructura, incluido `SENDING_SAP` | `IllegalStateException` si la transición faltase |
| R-7 | **El `from` declarado se compara con el real.** Si quien avanza declara venir de un estado que ya no es el de la cabecera, otra instancia la movió: es una **colisión**, no un error de programación | `ConcurrentTransitionException` (reintentable), nunca `IllegalStateException` |
| R-8 | **Fencing por ciclo.** Aunque el estado coincida, un ciclo no avanza sobre la cabecera de **otro** ciclo: el que perdió la carrera se entera en vez de escribir encima | `ConcurrentTransitionException` |
| R-9 | La **línea de feature** distingue `COMMUNICATION_ERROR` (no se llamó a SAP —circuito abierto— o no se sabe si llegó —transporte agotado, `httpStatus = 0`—) de `SAP_ERROR` (SAP respondió y rechazó). Toda transición a un estado de error persiste su **motivo** (`detail`), a lo sumo 512 caracteres, con la respuesta de SAP y **nunca** el payload enviado | Sin la distinción no se sabe si reenviar es seguro |

`from == null` significa «no compruebes» (ni R-7 ni R-8): cerrar en `ERROR`
tras un fallo no puede fallar a su vez por una colisión, o el estado se
quedaría sin cerrar. `IllegalStateException` queda reservada a lo que de
verdad es un error de programación: una transición que la tabla no admite.
Es lo que evita que una colisión pasajera acabe en la DLT sin un solo
reintento (auditoría 2026-09-18 N2).

`SENT_SAP` e `INVALID` siguen siendo terminales **del ciclo** (`isTerminal`). Lo
que desaparece es la idea de que la re-entrada sea una transición más de la
tabla: es una operación distinta, `beginCycle`, y por eso ya no puede faltar
"una fila" para un camino nuevo — el fallo que se repitió tres veces
(*fingerprint* `sync-state:reentrada-no-permitida`).

## 5. Salida

El estado resultante, o excepción si la transición es ilegal. Cada transición se
persiste con `timestamp`, origen, `payloadHash`, el **`cycleId`** del ciclo al que
pertenece y, si es de error, su **`detail`**.

El `cycleId` es lo que une la línea del agregado con las de sus features: todas
las transiciones de un mismo envío lo comparten, y `cycle(domain, cycleId)` las
devuelve todas de una sola consulta (índice `dom_cycle_idx`, ni único ni parcial).
Esa es la **traza de pasos** del envío. `lastTransition(domain, entityId)` da el
estado de una línea leyendo solo la cabecera, sin traerse el historial entero.

Los documentos anteriores a este cambio no tienen `cycleId` ni `detail` y se
siguen leyendo: ambos son `null`-tolerantes, igual que se hizo con `seq`. Para
ellos la traza es `null`, y decirlo es más honesto que inventarla.

## 6. Estados y transiciones

Dos recorridos comparten la misma máquina:

```
Agregado:    RECEIVED → FETCHING → VALIDATING → VALID → INDEXING → INDEXED → SENDING_SAP → SENT_SAP
Feature:                           VALIDATING → VALID ──────────────────────→ SENDING_SAP → SENT_SAP
Baja:                                                                          SENDING_SAP → SENT_SAP
Indexación:                                              INDEXING → INDEXED
```

**Estados en vuelo** (`isInFlight`): `RECEIVED`, `FETCHING`, `VALIDATING`,
`VALID`, `INDEXING`, `INDEXED`, `SENDING_SAP`. Los demás cierran ciclo.

La feature no indexa: la imagen y el histórico son del agregado.

| Desde | Hacia |
|---|---|
| *(apertura de ciclo, cualquier estado actual)* | `beginCycle` → `RECEIVED` · `VALIDATING` · `SENDING_SAP` · `INDEXING` |
| `RECEIVED` | `FETCHING`, `ERROR` |
| `FETCHING` | `VALIDATING`, `ERROR`, `COMMUNICATION_ERROR` |
| `VALIDATING` | `VALID`, `INVALID`, `ERROR` |
| `VALID` | `INDEXING` (agregado), `SENDING_SAP` (feature), `SENT_SAP` (sin cambios reales), `ERROR` |
| `INVALID` | `RECEIVED` (re-entrada del agregado), `VALIDATING` (re-entrada de feature) |
| `INDEXING` | `INDEXED`, `ERROR` |
| `INDEXED` | `SENDING_SAP`, `ERROR` |
| `SENDING_SAP` | `SENT_SAP`, `SAP_ERROR`, `INVALID`, `COMMUNICATION_ERROR`, `ERROR` |
| `SENT_SAP` | `RECEIVED` (re-entrada del agregado), `VALIDATING` (re-entrada de feature) |
| `SAP_ERROR` | `SENDING_SAP` (reintento), `VALIDATING` (re-entrada de feature), `ERROR` |
| `COMMUNICATION_ERROR` | `FETCHING`, `SENDING_SAP`, `ERROR` |
| `ERROR` | `RECEIVED` |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dada una entidad sin historial, cuando entra por cualquiera de los cuatro estados de entrada (`RECEIVED`, `VALIDATING`, `SENDING_SAP`, `INDEXING`), entonces se acepta; cualquier otro estado se rechaza | `SyncStateMachineTest#entryStatesOpenACycleWithoutHistory` · `SyncStateMachineTest#initialTransitionRejectsOtherStates` |
| AC-2 | Dado el recorrido completo del pipeline agregado, cuando se ejecuta paso a paso, entonces todas las transiciones son legales | `SyncStateMachineTest#happyPath` |
| AC-3 | Dado `VALID`, cuando el pipeline es por feature, entonces puede ir directo a `SENDING_SAP` sin pasar por `INDEXING` | `SyncStateMachineTest#validGoesToSendingSapInTheFeaturePipeline` |
| AC-4 | Dada una **feature ya enviada** (`SENT_SAP`), cuando llega un evento nuevo, entonces puede re-entrar por `VALIDATING` | `SyncStateMachineTest#featureLineReentersThroughValidating` |
| AC-5 | Ídem desde `INVALID` y desde `SAP_ERROR`: una feature rechazada o fallida puede reintentarse con un evento nuevo | `SyncStateMachineTest#featureLineReentersThroughValidating` |
| AC-6 | Dado un agregado en `SENT_SAP`, cuando llega un evento nuevo, entonces re-entra por `RECEIVED` (no por `VALIDATING`) | `SyncStateMachineTest#reSyncFromTerminalStates` |
| AC-7 | Dado **cualquier** estado actual (los 12 más «sin historial»), cuando llega un evento nuevo, entonces `beginCycle` abre ciclo por **cualquiera** de los estados de entrada. Test de propiedad: estados × entradas | `SyncStateMachineTest#beginCycleOpensFromAnyCurrentStateForEveryEntryState` |
| AC-8 | Dado un estado que no es de entrada, cuando se intenta abrir ciclo por él, entonces se rechaza | `SyncStateMachineTest#beginCycleRejectsStatesThatAreNotEntryPoints` |
| AC-9 | Los estados en vuelo son exactamente `RECEIVED`, `FETCHING`, `VALIDATING`, `VALID`, `INDEXING`, `INDEXED`, `SENDING_SAP` | `SyncStateMachineTest#inFlightStatesAreTheIntermediateOnes` |
| AC-10 | Dado `SENDING_SAP`, cuando falla la infraestructura, entonces puede pasar a `ERROR` | `SyncStateMachineTest#sendingSapCanFailToError` |
| AC-11 | El estado actual se resuelve por **secuencia**, no por `timestamp`: dos transiciones en el mismo milisegundo no empatan | `MongoSyncStateRepositoryTest#currentStateIsResolvedBySequenceNotTimestamp` |
| AC-12 | Dadas dos escrituras concurrentes sobre la misma entidad, una gana y la otra recibe `ConcurrentTransitionException`; nunca se pisa el estado en silencio | `MongoSyncStateRepositoryTest#concurrentWriteIsRejectedNotSilentlyOverwritten` |
| AC-13 | Al abrir ciclo, la nueva transición recibe la secuencia siguiente a la última almacenada | `MongoSyncStateRepositoryTest#beginCycleAssignsNextSequence` |
| AC-15 | Dado que el puerto SAP de una parte **lanza**, cuando el pipeline la sincroniza, entonces la línea termina en `COMMUNICATION_ERROR` si la causa fue de comunicación y en `SAP_ERROR` si SAP respondió; nunca queda en `SENDING_SAP` | `FeatureSyncPipelineTest#sapPortThrowingLeavesTheLineInCommunicationError` · `#sapAnsweringAnErrorLeavesTheLineInSapError` |
| AC-16 | Dada una respuesta sin éxito con `httpStatus = 0` (transporte agotado), entonces la línea es `COMMUNICATION_ERROR`, no `SAP_ERROR` | `FeatureSyncPipelineTest#statusZeroIsCommunicationErrorNotSapError` |
| AC-17 | Toda transición a un estado de error persiste su motivo, truncado a 512 caracteres y sin el payload enviado | `FeatureSyncPipelineTest#errorDetailKeepsTheSapStatusAndBody` · `SyncStateTransitionTest#detailIsTruncated` |
| AC-18 | Todas las transiciones de un mismo envío (agregado y líneas de feature) comparten `cycleId` y se recuperan con **una** consulta; la cabecera de una línea se lee sin el historial completo | `FeatureSyncPipelineTest#everyTransitionOfTheLineCarriesTheCycleId` · `MongoSyncStateRepositoryTest#everyLineOfACycleSharesTheCycleId` · `#cycleReturnsTheTransitionsOfEveryLine` · `#lastTransitionDoesNotReadTheWholeHistory` · `SyncStateMongoIT#cycleQueryReturnsEveryLineOfTheCycle` |
| AC-19 | Dada una `ConcurrentTransitionException` al registrar el estado de una parte, entonces **no** se convierte en `SAP_ERROR`: se propaga | `FeatureSyncPipelineTest#concurrentTransitionIsNotSwallowedAsASapError` |
| AC-20 | Dada una lectura desfasada (otra instancia movió la cabecera), entonces se lanza `ConcurrentTransitionException` —reintentable— y no `IllegalStateException`; con `from = null` no se comprueba nada y marcar `ERROR` sigue funcionando | `MongoSyncStateRepositoryTest#staleHeadIsReportedAsConcurrentNotIllegal` · `#nullDeclaredFromSkipsTheCheckSoMarkErrorStillWorks` · `ConcurrentTransitionExceptionTest#messageNamesTheDeclaredAndTheRealState` · `SyncStateMongoIT#staleReadFromAnotherInstanceIsConcurrentNotIllegal` |
| AC-21 | Dado un ciclo que intenta avanzar sobre la cabecera de otro ciclo, aunque el estado coincida, entonces se rechaza: dos ciclos en vuelo nunca entrelazan sus pasos | `MongoSyncStateRepositoryTest#advancingOverAnotherCyclesHeadIsRejected` · `SyncStateMongoIT#twoCyclesInFlightNeverInterleave` |
| AC-22 | Dada una transición que la tabla de §6 no admite, entonces sigue siendo `IllegalStateException`, no una colisión | `MongoSyncStateRepositoryTest#anIllegalTransitionIsStillAnIllegalStateException` · `ConcurrentTransitionExceptionTest#isNotAnIllegalStateException` |
| AC-14 | Contra un **Mongo real** (no el fake en memoria): una entidad en `SAP_ERROR` vuelve a `SENT_SAP` con el siguiente evento; N escritores concurrentes nunca se pisan (cada `seq` aparece una vez); los documentos legacy sin `seq` conviven con el índice único parcial | `SyncStateMongoIT#resyncsAfterSapErrorAgainstRealMongo` · `#concurrentWritersNeverOverwriteEachOther` · `#legacyDocumentsWithoutSeqCoexistWithTheUniqueIndex` (módulo `it`, `-Ddocker.available=true`) |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

Cada transición incrementa un contador etiquetado por dominio y estado
(`SyncMetrics`). Una transición ilegal se propaga como excepción hasta el
listener o el controller, y en la ingesta Kafka acaba en la DLT tras agotar los
reintentos.

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| §6 tabla de transiciones (`advance`) | `common/domain/SyncStateMachine.java` | `SyncStateMachineTest` |
| R-1/R-3 `beginCycle` y `ENTRY_STATES` | `SyncStateMachine.beginCycle` · `SyncStateRepositoryPort.beginCycle` | `SyncStateMachineTest#beginCycleOpensFromAnyCurrentStateForEveryEntryState` |
| Registro del ciclo desde `application` (abrir/avanzar + métrica) | `common/application/SyncCycleRecorder.java` (único punto que construye `SyncStateTransition`) | `FeatureSyncPipelineTest` · tests de los orquestadores |
| §6 línea de feature (`VALIDATING → … → SENT_SAP`) | `common/application/FeatureSyncPipeline.java`; el paso `SENDING_SAP → …` pasa por `write(...)`, la verificación previa de [`upsert-idempotente-sap.md`](upsert-idempotente-sap.md) | `FeatureSyncPipelineTest` (17 tests con la máquina real) |
| R-9 y AC-15..AC-17 estado y motivo de la parte | `common/application/FeatureSyncPipeline.sync` · `common/domain/FeatureOutcome.java` · `SyncStateTransition.detail` (`MAX_DETAIL`). Un lookup no concluyente (`SapLookupUnavailableException`) cierra la línea en `COMMUNICATION_ERROR`, como el circuito abierto: no se tocó SAP | `FeatureSyncPipelineTest` · `SyncStateTransitionTest` |
| §5 ciclo y traza de pasos (AC-18) | `SyncCycleRecorder.Cycle` · `SyncStateTransition.cycleId` · `SyncStateDoc` (índice `dom_cycle_idx`) · `SyncStateRepositoryPort.cycle` / `lastTransition` | `MongoSyncStateRepositoryTest` · `SyncStateMongoIT#cycleQueryReturnsEveryLineOfTheCycle` |
| R-7/R-8 y AC-19..AC-22 colisión frente a error de programación | `MongoSyncStateRepository.transition` (compara `from` y `cycleId` **antes** de la máquina) · `common/domain/ConcurrentTransitionException.java` (`staleHead`, `foreignCycle`, `duplicateSeq`) | `MongoSyncStateRepositoryTest` · `ConcurrentTransitionExceptionTest` · `SyncStateMongoIT` |
| Reloj inyectado (el instante de cada paso es verificable) | `SyncCycleRecorder(Clock)` · `FeatureSyncPipeline(Clock)`; el `@Bean Clock` vive en `bootstrap/<Dominio>UseCaseConfig` | `FeatureSyncPipelineTest` (reloj fijo) |
| AC-11/12/13 secuencia y concurrencia | `common/adapters/persistence/MongoSyncStateRepository.java` (`seq`, índice único) | `MongoSyncStateRepositoryTest` |
| AC-14 lo mismo contra Mongo real | `MongoSyncStateRepository` + `SyncStateDoc` (índice parcial `dom_ent_seq_uk`) | `SyncStateMongoIT` (Testcontainers `mongo:7.0`) |
| §3 el `from` se lee del almacén | `common/adapters/persistence/MongoSyncStateRepository.java` | `MongoSyncStateRepositoryTest` |
| R-5 recuperación de errores | `SyncStateMachine` | `ErrorStateRecoveryTest` |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-18 | **Ciclo de sincronización y traza de pasos**: cada transición lleva `cycleId` y, si es de error, `detail` (≤ 512, sin el payload); consultas nuevas `cycle` y `lastTransition` sobre el índice `dom_cycle_idx`. La línea de feature distingue `COMMUNICATION_ERROR` de `SAP_ERROR` y **nunca** queda colgada en `SENDING_SAP` aunque el puerto lance (R-9, AC-15..AC-19; auditoría N1). El `from` declarado se compara con el real y el `cycleId` actúa de *fencing token*: una lectura desfasada es `ConcurrentTransitionException` reintentable y no `IllegalStateException` camino de la DLT (R-7, R-8, AC-20..AC-22; auditoría N2). `Instant.now()` sale de `application`: `Clock` inyectado. Migración: los documentos sin `cycleId`/`detail` se siguen leyendo y el índice nuevo no es único | — |
| 2026-09-12 | Fase 7 (A5): `SyncCycleRecorder` y `FeatureSyncPipeline<D>` en `common/application` sustituyen a las 14 copias de `beginCycle()/transition()` y a los cuatro `Sync<Feature>UseCase` idénticos. Comportamiento sin cambios: los tests de cada use case siguen en verde | — |
| 2026-09-12 | AC-14: la secuencia, la versión optimista y la convivencia con documentos legacy se prueban también contra un **Mongo real** con Testcontainers (`SyncStateMongoIT`), no solo contra el fake en memoria (Fase 2 de la auditoría, TEST-1 parcial) | — |
| 2026-09-12 | Verificado en vivo el 2026-09-12: apertura desde `SAP_ERROR` y desde `SENDING_SAP` en vuelo; `seq` monótona entre escritores CDC y REST (1→24). El índice único tuvo que ser **parcial**, no `sparse`: sparse compuesto indexa si hay al menos una clave y los docs antiguos colisionaban en `seq=null` (E11000 al arrancar) | — |
| 2026-09-11 | **Rediseño de raíz** (auditoría B1/B11/B14): se separa **abrir ciclo** (`beginCycle`, legal desde cualquier estado, por cuatro estados de entrada) de **avanzar** (`advance`, la tabla). Cierra el *fingerprint* `sync-state:reentrada-no-permitida` en su tercera recurrencia: `SAP_ERROR` y los estados intermedios eran sumideros, y la baja/indexación abrían por estados que la tabla no admitía. Test de propiedad estados × entradas. `SENDING_SAP → ERROR`. En el repositorio Mongo, orden por **secuencia** en vez de `timestamp` y **versión optimista** por índice único (`seq`) para varias instancias | — |
| 2026-09-10 | Spec inicial. Se añade la **re-entrada de las líneas de feature** por `VALIDATING` desde `SENT_SAP`, `INVALID` y `SAP_ERROR` (AC-4/AC-5): sin ella, un segundo evento con cambios reales rompía el pipeline por feature y el mensaje acababa en la DLT | — |
| 2026-09-09 | `VALID → SENDING_SAP` para el pipeline por feature (AC-3) | — |
