# Verificación previa y upsert en SAP

| | |
|---|---|
| **Dominio** | `common` (capacidad transversal; la usan todos los adaptadores `SapOutboundPort`) |
| **Estado** | ⚠️ implementado con brechas — cableado en `FeatureSyncPipeline`; pendiente **la verificación contra un tenant S/4 real** |
| **Entradas** | llamadas de los adaptadores de salida de cada feature |
| **Destino SAP** | S/4 nativo (OData V2). Los adaptadores BTP no verifican: no tienen servicio real detrás |
| **Última revisión** | 2026-09-18 |

> ⚠️ **Todo lo que esta spec dice sobre rutas, claves y formatos de S/4 está
> marcado «a confirmar en tenant»**: no hay tenant S/4 disponible y está tomado
> de `sap-api-models/specs/customer/API_BUSINESS_PARTNER.yaml`, no de respuestas
> reales. Lo que hay que comprobar, y en qué orden, está en
> [`../../testing/CHECKLIST-TENANT-SAP.md`](../../testing/CHECKLIST-TENANT-SAP.md) §1-§3.

## 1. Objetivo

Que sincronizar dos veces el mismo cliente **no cree dos clientes en SAP**, y que
cuando no sepamos qué tiene SAP **no escribamos nada**.

Hasta ahora toda escritura era un alta (`POST`). Con un evento repetido —y los
eventos se repiten: la ingesta es *at-least-once*— eso duplica el Business
Partner, la dirección, el número fiscal y la cuenta bancaria. La solución es un
**upsert**: se pregunta primero (**verificación previa**, un `GET`) y se decide
alta o actualización con la respuesta en la mano.

## 2. Alcance

**Dentro**: la verificación previa por feature (qué se pregunta y con qué clave),
la decisión alta / actualización / no escribir, el almacén de las claves que
asigna SAP, y el comportamiento ante un lookup ambiguo o un `412`.

**Fuera** (y por qué):

- La política de reintento y la cabecera `If-Match`: son **transporte** y viven
  en [`resiliencia-cliente-sap.md`](resiliencia-cliente-sap.md) R-1, R-8 y R-9.
- El dedupe por `payloadHash` (no reenviar lo que ya se envió igual): es otra
  capa y vive en [`idempotencia-y-dedupe.md`](idempotencia-y-dedupe.md). Son
  complementarias: el dedupe evita la llamada, la verificación previa evita el
  duplicado cuando la llamada sí ocurre.
- Qué se envía campo a campo: es de cada feature (`customer/sincronizacion-*.md`).
- Qué hace el pipeline con cada resultado: está aquí (§5 y R-7); cómo recorre la línea de estado la feature es de [`maquina-de-estados.md`](maquina-de-estados.md) §6.

## 3. Entrada

La verificación previa la pide el pipeline de la feature antes de escribir:

| Campo | Tipo | Obligatorio | Notas |
|---|---|---|---|
| `entityId` | texto | sí | Identificador de la entidad en el sistema legacy; para el agregado es también la clave en SAP (alta con numeración externa `BPEE`) |
| `payload` | tipado por feature | sí | Por si la clave de búsqueda depende de sus datos |
| `sap.client.lookup.enabled` | booleano | — | Interruptor global. `false` = comportamiento anterior (alta directa); **solo** para el entorno local contra el SAP simulado |

**Decisión D-18**: cuando el adaptador de escritura OData de una feature está
activo, su verificación previa es **obligatoria**. Por eso el adaptador de
lectura del Business Partner ya no depende de `sap.odata.read.enabled` (que por
defecto está en `false`), sino de que lo esté el de escritura: un interruptor de
solo lectura no puede apagar la única pieza que evita duplicar el alta.

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | Antes de escribir se **pregunta a SAP** qué tiene de la subentidad (`GET`). Si la tiene, la operación es **actualización** (`PATCH` con `If-Match`); si no, **alta** (`POST`) | Escribir siempre un alta duplica los datos maestros (hallazgos 2B-6, PRD-11) |
| R-2 | **Solo un `404` significa «SAP no la tiene»**. Un 5xx agotado, un fallo de transporte (`status 0`), el circuito abierto o una respuesta ilegible son «no lo sé» | Confundir «no responde» con «no existe» degrada a alta directa y crea el duplicado que esta spec evita. Es la corrección del hallazgo B13 |
| R-3 | Con «no lo sé» **no se escribe nada**: la parte queda en `COMMUNICATION_ERROR` y el mensaje se puede reentregar sin riesgo, porque SAP no se tocó. **Nunca degrada a alta** | Preferir un `POST` «por si acaso» es exactamente lo que duplica |
| R-4 | Las claves que **asigna SAP** y no podemos deducir (`AddressID`, y el mapa IBAN → `BankIdentification` cuando haya varias cuentas) se persisten en `SapKeyStorePort`, colección `sap_keys`, una fila por *(dominio, entidad, feature)*. **No** van en la imagen de la entidad: la imagen solo se escribe tras el ACK ([`idempotencia-y-dedupe.md`](idempotencia-y-dedupe.md) R-4) y la clave hay que conservarla aunque el ciclo termine en error | Sin la clave, el ciclo siguiente no sabe qué actualizar y da de alta otra vez |
| R-5 | El **`ETag` no se persiste**. Se lee siempre en el lookup inmediatamente anterior al `PATCH`. Un `ETag` guardado envejece y produce `412` sin que se pueda reintentar sin releer | Guardarlo solo añade un caso de error. Matiza el enunciado de PRD-11 («`AddressID` y ETag persistidos»): se recoge la clave, se descarta el ETag |
| R-6 | Una respuesta con **más de un resultado** (navegación o `$filter`) es no concluyente, no «cojo el primero» (`sap.client.lookup.ambiguous-fails`, default `true`) | Elegir al azar escribe sobre la dirección equivocada |
| R-7 | Un **`412 Precondition Failed`** en el `PATCH` (otro proceso modificó el recurso entre nuestro lookup y nuestra escritura) permite repetir lookup + `PATCH` **una** vez (`sap.client.upsert.refetch-on-precondition-failed`). Un segundo `412` termina en `SAP_ERROR` con el motivo explícito | Reintentar en bucle sobre un recurso que alguien está editando no converge |
| R-8 | Los datos de comunicación del cliente (email, teléfono, fax, web) **no son una persona de contacto**: cuelgan del `AddressID` de su dirección. Ver [`../customer/sincronizacion-contacto.md`](../customer/sincronizacion-contacto.md) | `A_BusinessPartnerContact` exige `BusinessPartnerPerson` y `RelationshipNumber`, que no tenemos: el payload salía vacío (hallazgo 2B-5) |
| R-9 | El **presupuesto de reintentos por mensaje** cuenta lecturas y escrituras por separado: las lecturas con su timeout corto y su política completa, las escrituras con el timeout largo y casi sin reintentos. Sumarlas con una sola multiplicación daría un peor caso irreal y cercano a `max.poll.interval.ms` | Es el hallazgo 2A-12. Con la fórmula separada el peor caso **baja** de 307.500 ms a 301.500 ms pese a doblar el número de llamadas |

## 5. Salida

`SapLookup(outcome, key, etag, detail)`:

| `outcome` | Qué significa | Qué hace el pipeline |
|---|---|---|
| `FOUND` | SAP la tiene; `key` es su clave y `etag` la precondición | `update` = `PATCH` con `If-Match` |
| `NOT_FOUND` | 404: SAP no la tiene | `send` = `POST` |
| `UNAVAILABLE` | No se pudo saber; `detail` dice por qué | **no escribe nada**, `COMMUNICATION_ERROR` |
| `NOT_SUPPORTED` | El adaptador no sabe verificar (familias `Btp*`, o `lookup.enabled=false`) | `send` = `POST`, comportamiento anterior |

### 5.1 Lookup por feature: clave, llamada y qué se persiste

**Todo «a confirmar en tenant»**.

| Feature / puerto | Lookup | Clave en SAP | ¿Se persiste? |
|---|---|---|---|
| Agregado — `CustomerSapOutboundPort` | `GET A_BusinessPartner('<entityId>')` | `BusinessPartner` | **No**: con `BusinessPartnerGrouping = "BPEE"` (alta con numeración externa) la clave es nuestro `entityId` |
| ADDRESS — `AddressSapPort` | con clave guardada, `GET A_BusinessPartnerAddress(BusinessPartner,AddressID)`; sin ella, `GET A_BusinessPartner('<id>')/to_BusinessPartnerAddress?$top=2` | `AddressID` | **Sí**: lo asigna SAP y no es determinista |
| FISCAL — `FiscalSapPort` | `GET A_BusinessPartnerTaxNumber(BusinessPartner,BPTaxType)` | compuesta | **No**: el tipo sale de `sap.odata.fiscal.tax-type` |
| BANKING — `BankingSapPort` | `GET A_BusinessPartnerBank(BusinessPartner,BankIdentification='0001')` | compuesta | **No** mientras haya una sola cuenta. **Sí** en cuanto haya varias: haría falta el mapa IBAN → identificación |
| CONTACT — `ContactSapPort` | resuelve el `AddressID` (almacén de claves, o navegación desde el BP) y luego un `GET` por campo de comunicación | `AddressID` (compartida con ADDRESS) | **Sí**, la misma fila que ADDRESS |
| MANDATO — `MandateSapOutboundPort` | `GET SEPAMandateSet(Creditor,SEPAMandate)` | compuesta | **No**: determinista |

## 6. Estados y errores

| Situación | Estado final de la parte | Reintentable |
|---|---|---|
| Lookup 200 → `PATCH` 2xx | `SENT_SAP` | — |
| Lookup 404 → `POST` 2xx | `SENT_SAP` | — |
| Lookup 5xx agotado, transporte o circuito abierto | `COMMUNICATION_ERROR`, **sin tocar SAP** | sí, la reentrega es segura |
| Lookup con más de un resultado | `COMMUNICATION_ERROR`, detail «lookup ambiguo: N resultados» | sí |
| CONTACT sin `AddressID` (el BP aún no tiene dirección en SAP) | `COMMUNICATION_ERROR` | sí: la feature ADDRESS la creará |
| `412` tras el re-lookup | `SAP_ERROR` con el motivo | con un evento nuevo |

### 6.1 Propiedades

| Propiedad | Default | Qué hace |
|---|---|---|
| `sap.client.lookup.enabled` | `true` | Interruptor global de la verificación previa |
| `sap.client.lookup.ambiguous-fails` | `true` | R-6 |
| `sap.client.lookup.timeout-ms` | `5000` | Timeout de un lookup a efectos de presupuesto (R-9). **Brecha**: todavía **no** es un timeout HTTP distinto por llamada; el transporte usa `sap.client.response-timeout-ms` para todas |
| `sap.client.lookup.calls-per-message` | `6` | Lookups que puede provocar un mensaje (R-9) |
| `sap.client.upsert.refetch-on-precondition-failed` | `true` | R-7 |
| `sap.odata.bp-api-base` | `/sap/opu/odata/sap/API_BUSINESS_PARTNER` | Base de las entidades de comunicación de la dirección (CONTACT) |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dado que SAP **no tiene** la subentidad (404 del lookup), entonces se envía un alta (`POST`) | `BusinessPartnerODataAdapterTest#missingBusinessPartnerIsNotFound` · `BusinessPartnerAddressODataAdapterTest#missingAddressIsNotFound` · `it/…/BusinessPartnerUpsertContractTest#realAdapterCreatesWhenSapDoesNotHaveIt` |
| AC-2 | Dado que SAP **sí la tiene**, entonces se envía `PATCH` con `If-Match` y el ETag del lookup, **nunca** un alta | `BusinessPartnerODataAdapterTest#existingBusinessPartnerIsUpdatedNotCreated` · `BusinessPartnerAddressODataAdapterTest#updateUsesPatchWithIfMatch` · `BusinessPartnerTaxODataAdapterTest#updateSendsOnlyTheTaxNumberWithIfMatch` · `it/…/BusinessPartnerUpsertContractTest#realAdapterPatchesWithIfMatchInsteadOfCreatingADuplicate` |
| AC-3 | Dado que el lookup **no responde** (transporte o 5xx agotado), entonces el resultado es `UNAVAILABLE` y **no se escribe nada** | `BusinessPartnerODataAdapterTest#transportFailureIsUnavailableNotNotFound` · `BusinessPartnerAddressODataAdapterTest#transportFailureIsUnavailableNotNotFound` · `BusinessPartnerReadAdapterTest#transportFailureIsNotConfusedWithNotFound` · `#serverErrorIsUnavailable` · `it/…/BusinessPartnerUpsertContractTest#realAdapterReportsUnavailableWhenSapIsDown` |
| AC-4 | Dado un lookup con más de un resultado, entonces se trata como no concluyente | `BusinessPartnerAddressODataAdapterTest#ambiguousLookupNeverGuesses` · `it/…/BusinessPartnerAddressUpsertContractTest#realAdapterRefusesToGuessWhenTheNavigationIsAmbiguous` |
| AC-5 | Dado un puerto sin verificación previa (`NOT_SUPPORTED`), entonces el comportamiento es el anterior: alta directa | `BusinessPartnerODataAdapterTest#lookupDisabledFallsBackToTheOldBehaviour` |
| AC-6 | Dada una dirección ya existente, el `AddressID` usado es el persistido; si no lo hay se resuelve navegando desde el BP y **se persiste** | `BusinessPartnerAddressODataAdapterTest#reusesTheStoredAddressId` · `#resolvesAndStoresTheAddressIdOnFirstUpdate` · `MongoSapKeyStoreTest#storesAndReadsTheKeyPerFeature` · `#missingKeyIsEmpty` · `#blankKeysAreNotStored` · `#deletingTheEntityRemovesEveryFeatureKey` · `it/…/BusinessPartnerAddressUpsertContractTest#realAdapterResolvesTheAddressIdByNavigationAndThenReusesIt` |
| AC-7 | Dado un `412` en el `PATCH`, entonces se repite lookup y `PATCH` **una** vez; un segundo `412` termina en `SAP_ERROR` con el motivo | `FeatureSyncPipelineTest#preconditionFailedIsRefetchedOnce` |
| AC-8 | Los adaptadores OData reales emiten el `GET` de lookup y el `PATCH` con `If-Match` esperados contra WireMock | `it/…/contract/BusinessPartnerUpsertContractTest` · `BusinessPartnerAddressUpsertContractTest` · `S4ContactCommunicationContractTest` |
| AC-9 | El presupuesto de reintentos por mensaje suma lecturas y escrituras por separado, y con la verificación previa activa **baja** respecto al anterior | `RetryBudgetGuardTest#budgetSeparatesLookupsFromWrites` · `#csrfFetchAddsItsOwnCost` |
| AC-10 | El pipeline de la feature **pregunta antes de escribir**: con `NOT_FOUND` da de alta, con `FOUND` actualiza con el ETag del lookup y con `NOT_SUPPORTED` se comporta como antes | `FeatureSyncPipelineTest#lookupNotFoundCreates` · `#lookupFoundUpdatesWithIfMatch` · `#portWithoutLookupStillPostsAsBefore` |
| AC-11 | Con un lookup no concluyente (incluido el ambiguo) la parte queda en `COMMUNICATION_ERROR` con el motivo y **no se llama ni a `send` ni a `update`** | `FeatureSyncPipelineTest#lookupUnavailableStopsBeforeWritingAnything` · `#ambiguousLookupNeverWrites` |
| AC-12 | Con `sap.client.lookup.enabled=false` no se pregunta: alta directa, como antes | `FeatureSyncPipelineTest#lookupDisabledPostsDirectly` |
| AC-13 | `SapLookupUnavailableException` está **declarada** reintentable en el consumo Kafka: el mensaje no va a la DLT al primer intento | `KafkaErrorHandlingConfigTest#lookupUnavailableIsDeclaredRetryable` |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

- El `detail` de un `UNAVAILABLE` es legible y dice la causa («fallo de
  transporte: …», «SAP respondió 503», «lookup ambiguo: 2 resultados», «sin
  AddressID para C-1: la dirección del BP todavía no existe en SAP»), y es lo que
  viaja al estado de la parte y al aviso de `sap.sync.alerts`.
- Cada lookup es una llamada HTTP más y aparece en `sap_client_request_duration`
  con `method=GET`: el coste de la verificación previa es medible sin instrumentar
  nada nuevo.
- `RetryBudgetGuard` deja al arrancar una línea con el peor caso por mensaje
  desglosado en lookups y escrituras.

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| R-1, §5 contrato | `common/domain/port/SapOutboundPort.java` (`lookup`, `update`, `SapLookup`, `SapResponse.etag`) | AC-1..AC-5 |
| R-1 a R-3, R-7 decisión alta/actualización/no escribir | `common/application/FeatureSyncPipeline.write` y `updateWithOneRefetch` (llamadas desde `sync`); interruptores por constructor desde `bootstrap/CustomerUseCaseConfig` | `FeatureSyncPipelineTest` AC-7, AC-10..AC-12 |
| R-2, R-6 | `common/sap/odata/ODataLookups.java` | AC-1..AC-4 |
| R-3 | `common/sap/SapLookupUnavailableException.java` · declarada reintentable en `common/kafka/KafkaErrorHandlingConfig.java` (`RETRYABLE`) | AC-3 · AC-13 |
| R-4 | `common/domain/port/SapKeyStorePort.java` · `common/adapters/persistence/MongoSapKeyStore.java` · `SapKeyDoc` · `SapKeyMongoRepository` | `MongoSapKeyStoreTest` AC-6 |
| R-5 | ausencia deliberada de campo `etag` en `SapKeyDoc` | AC-6 |
| R-9 | `common/sap/RetryBudgetGuard.java` (lecturas, escrituras, CSRF y el backoff de reentrega de Kafka del circuito abierto) | `RetryBudgetGuardTest` AC-9 · `#budgetIncludesTheKafkaCircuitOpenBackoff` |
| §5.1 agregado | `customer/adapters/sap/odata/BusinessPartnerODataAdapter.java` · `customer/adapters/sap/BusinessPartnerReadAdapter.java` (`lookupById`) | `BusinessPartnerODataAdapterTest` · `BusinessPartnerReadAdapterTest` |
| §5.1 ADDRESS | `customer/adapters/sap/odata/BusinessPartnerAddressODataAdapter.java` | `BusinessPartnerAddressODataAdapterTest` |
| §5.1 FISCAL / BANKING / MANDATO | `BusinessPartnerTaxODataAdapter` · `BusinessPartnerBankODataAdapter` · `SepaMandateODataAdapter` | `BusinessPartnerTaxODataAdapterTest` · `BusinessPartnerBankODataAdapterTest` · `SepaMandateODataAdapterTest` |
| §5.1 CONTACT, R-8 | `customer/adapters/sap/odata/BusinessPartnerContactODataAdapter.java` | `BusinessPartnerContactODataAdapterTest` |
| D-18 (activación) | `customer/bootstrap/BusinessPartnerReadEnabled.java` | arranque de la app |
| §6.1 configuración | `common/sap/SapUpsertSettings.java` · `SapIntegrationConfig` · `application-common.yml` | `*ApplicationContextTest` |
| AC-8 | adaptadores reales sobre `RestClientSapClient` | `it/…/contract/*UpsertContractTest` |

## 10. Cambios y lo que falta

**Lo que falta para cerrar la spec**:

1. **Verificación contra el tenant S/4**: todo §5.1 (rutas, claves y formatos)
   sigue sin contrastar contra un S/4 real.
2. **Timeout propio del lookup**: `sap.client.lookup.timeout-ms` solo cuenta a
   efectos de presupuesto; el transporte usa el timeout de escritura para todas
   las llamadas (§6.1).

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-18 | **Cableado en `FeatureSyncPipeline`**: la línea de feature pregunta antes de escribir y decide alta, actualización o no escribir nada; un `412` permite un único re-lookup (AC-7, ya con test). `SapLookupUnavailableException` declarada reintentable en Kafka y el presupuesto de reintentos suma el backoff de reentrega del circuito abierto. Índices `dom_cycle_idx` y `sap_keys` en el init de Mongo | — |
| 2026-09-18 | Spec inicial. Recoge la decisión (3) del propietario —verificación previa antes de escribir, y si no responde no se escribe— y cierra 2B-6 y PRD-11 en la parte de los adaptadores. R-5 matiza PRD-11: el ETag no se persiste. R-8 redirige los datos de comunicación a las entidades de la dirección (2B-5). R-9 rehace el presupuesto de reintentos (2A-12) | — |
