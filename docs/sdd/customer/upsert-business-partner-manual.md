# Upsert manual de Business Partner (PUT/PATCH, facturable)

| | |
|---|---|
| **Dominio** | `customer` |
| **Estado** | ✅ implementado 2026-09-24 |
| **Entradas** | REST `PUT /business-partners/{id}`, `PATCH /business-partners/{id}` (este PR) |
| **Destino SAP** | S/4 nativo (`sap.odata.customer.enabled=true`) |
| **Última revisión** | 2026-09-24 |

## 1. Objetivo

Exponer el alta y la actualización manual del Business Partner (BP) en SAP
S/4 Public Cloud vía endpoints REST. **El pipeline es el mismo que el de
CDC** ([`sincronizacion-cliente.md`](sincronizacion-cliente.md)): se dispara
`SyncCustomerUseCase` con la misma máquina de estados, dedupe por hash del
snapshot, lookup previo y `PATCH` con `If-Match` (verificación previa de
[`../common/upsert-idempotente-sap.md`](../common/upsert-idempotente-sap.md)).

La **diferencia con CDC** es la fuente del `Customer`: en CDC el snapshot se
relee del legacy; aquí el `Customer` se construye a partir del body del
request. El resto del pipeline es idéntico — un solo orquestador, un solo
camino.

Resuelve el caso de uso del equipo de gestión de datos maestros:
**alta o actualización inmediata de un cliente desde una consola de
operación**, sin esperar al evento de CDC y sin pasar por la pantalla
clásica de SAP GUI. Cada llamada a `PUT`/`PATCH` es **facturable** (es una
escritura contra S/4, a diferencia del GET de PRD-9), así que el endpoint
queda restringido al rol `SAP_WRITE` y superior.

## 2. Alcance

**Dentro** (este PR):

- `PUT /business-partners/{id}` — upsert completo (alta o actualización).
  Body: `{ "name": "Foo SL", "category": "2" }` (ambos opcionales,
  `category` por defecto `"2"` para cliente). Idempotente: la segunda
  llamada con el mismo `name` no reenvía.
- `PATCH /business-partners/{id}` — update parcial. Body con al menos uno de
  los campos opcionales (`name`, `category`); el `Customer` construido
  lleva solo esos campos. El adaptador S/4 (`BusinessPartnerODataAdapter`)
  hoy solo soporta `name` en el `PATCH` (R-1, ver §6).
- Activación condicional igual que PRD-9: el bean y el controller solo se
  montan si `sap.odata.customer.enabled=true` (la activación del
  adaptador de escritura); sin esto, los endpoints no se registran.
- Seguridad: `@PreAuthorize hasRole('SAP_WRITE')` en ambos. No admitimos
  `SAP_EXTERNAL_READ` (es escritura facturable).
- Contrato OpenAPI en `customer/src/main/resources/openapi.yml` con
  `x-required-role: SAP_WRITE` y los códigos 200/201/400/403/409.
- Tests rojos primero: use case unit + slice web del controller, ambos con
  mocks. `SyncCustomerUseCaseTest` sigue verde sin cambios (AC-10).

**Fuera**:

- `PATCH` de campos no soportados hoy por `BusinessPartnerODataAdapter`
  (`category` dinámica, `address`, `fiscal`, `contact`, `banking`): las
  features sub-entidad se actualizan por su propio flujo de CDC o por
  `POST /customers/sync`. **R-1**: PATCH de `category` se rechaza por
  ahora y queda como ADR futura (ver §6).
- Sincronización desde el legacy: eso es CDC.
- Confirmación contra el tenant SAP de test: mismo criterio que las
  features de contacto y datos bancarios — pendiente hasta que se ejecute
  [`../../testing/CHECKLIST-TENANT-SAP.md`](../../testing/CHECKLIST-TENANT-SAP.md) §1-§3.

## 3. Entrada

| Endpoint | Parámetro | Tipo | Obligatorio | Notas |
|---|---|---|---|---|
| `PUT /business-partners/{id}` | `id` | path | sí | Clave de negocio del BP en SAP (numeración externa `BPEE`). Se usa como `entityId` y como `BusinessPartner` en el alta |
| `PUT /business-partners/{id}` | `name` | body | sí | Razón social. Vacío o ausente → 400 |
| `PUT /business-partners/{id}` | `category` | body | no | Categoría OData (`"2"` por defecto para cliente) |
| `PATCH /business-partners/{id}` | `id` | path | sí | Igual que arriba |
| `PATCH /business-partners/{id}` | `name` | body | no | Si viene, no blank |
| `PATCH /business-partners/{id}` | `category` | body | no | Aceptado por el body pero el adaptador actual solo soporta `name` (R-1) |

Header `Idempotency-Key` opcional: el pipeline ya calcula el `payloadHash`
y lo envía como `Idempotency-Key` en cada `POST`/`PATCH` (ver
[`../common/resiliencia-cliente-sap.md`](../common/resiliencia-cliente-sap.md)
y `SapClient`). Por lo tanto dos `PUT` con el mismo body dan el mismo
hash y el segundo queda en `SENT_SAP` sin reenviar.

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | `PATCH` no actualiza `category` aunque venga en el body: el `BusinessPartnerODataAdapter` actualiza solo `OrganizationBPName1` (es lo que hoy soporta el `PATCH` contra `A_BusinessPartner`). `category` se rechaza con `400` para no dar la falsa sensación de que se aplicó. ADR futura para ampliar el adapter. | `400` con `{"error":"category no se puede modificar por PATCH en esta version"}` |
| R-2 | `PUT` con `name` ausente, vacío o solo blancos → `400` con `{"error":"name obligatorio"}` (la invariante del record `Customer` ya lo cubre, lo mapeamos a `IllegalArgumentException` → 400) | `400` |
| R-3 | `PATCH` sin ningún campo presente → `400` con `{"error":"al menos un campo: name, category"}` | `400` |
| R-4 | El pipeline es el mismo que CDC: `SyncCustomerUseCase` con `IngestionOrigin.REST`. La máquina de estados y el dedupe por hash operan igual. El `Customer` que entra al pipeline es el construido desde el body (no se relee nada). | — |
| R-5 | El hash se calcula sobre el `Customer` construido (no sobre el legacy, porque no hay legacy). Si dos requests traen un body idéntico, el segundo queda en `SENT_SAP` sin reenviar (dedupe honesto, mismo criterio que CDC con `alreadySent`). | `SENT_SAP` sin reenvío |
| R-6 | Si `BusinessPartnerODataAdapter` no está activo (`sap.odata.customer.enabled=false`), el bean y el controller del upsert no se registran. `EndpointsDeclareAccessTest`/`OpenApiMatchesControllersTest` siguen verdes porque no ven endpoints que no existen. | `404` del dispatcher |
| R-7 | `ConcurrentTransitionException` del orquestador → `409 Conflict` con cuerpo `application/problem+json` (mismo patrón que `SyncCustomerController#onConcurrentTransition`). | `409` |
| R-8 | `UpsertBusinessPartnerUseCase` no llama a `legacyRepo.fetch`: el `Customer` ya viene del body. `executeFromPayload` del orquestador evita el paso de fetch y la comprobación de "no existe en el legacy". | — |
| R-9 | Refactor mínimo del orquestador: extraer `runPipeline(IngestionMessage, Customer, Set<CustomerFeature>)` del `SyncCustomerUseCase#execute` (post-fetch). `execute(IngestionMessage)` queda como fuente legacy; nuevo `executeFromPayload(IngestionMessage, Customer, Set<CustomerFeature>)` reusa `runPipeline`. **Cero duplicación**: el pipeline completo vive en un solo método. | AC-10 (tests previos en verde) |

## 5. Salida

### 5.1 `PUT /business-partners/{id}` — 200 (actualización) o 201 (alta)

```json
{ "entityId": "C001", "state": "SENT_SAP" }
```

`state` es el estado final del ciclo (`SENT_SAP`, `SAP_ERROR`, `INVALID`,
`ERROR`, …). 200 si la entidad ya existía en SAP (el `lookup` la encontró
y se hizo `PATCH` con `If-Match`); 201 si no existía (se hizo `POST`). La
diferencia la decide el adaptador de S/4 y la refleja el orquestador en el
detalle del aviso; el cuerpo de la respuesta es el mismo.

### 5.2 `PATCH /business-partners/{id}` — 200

```json
{ "entityId": "C001", "state": "SENT_SAP" }
```

Igual forma que `PUT`. La diferencia de método (`PUT` vs `PATCH`) ya está
en la URL; en el cuerpo no aporta repetirla.

### 5.3 Mapeo a SAP

| Campo body | Campo SAP (`A_BusinessPartner`) | Cuándo |
|---|---|---|
| `name` | `OrganizationBPName1` | alta (`POST`) y actualización (`PATCH`) |
| `category` | `BusinessPartnerCategory` | solo alta (`POST`); `PATCH` lo rechaza (R-1) |

Endpoint S/4 consumido:

- Alta: `POST /sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner`
  con `BusinessPartner=<id>`, `BusinessPartnerGrouping=BPEE`,
  `BusinessPartnerCategory=<category>`, `OrganizationBPName1=<name>`.
- Update: `PATCH .../<id>` con `If-Match: <ETag>` y solo
  `OrganizationBPName1`.
- Catálogo: [`../sap-api-catalog.md`](../sap-api-catalog.md).

## 6. Estados y errores

| Situación | HTTP | Cuerpo | Origen |
|---|---|---|---|
| Éxito (alta o update) | 200/201 | §5.1/§5.2 | orquestador: `SENT_SAP` |
| `name` ausente o blanco en `PUT` | 400 | `{"error":"name obligatorio"}` | `Customer` invariante → `IllegalArgumentException` |
| `PATCH` sin campos | 400 | `{"error":"al menos un campo: name, category"}` | use case valida antes de construir el `Customer` |
| `PATCH` con `category` | 400 | `{"error":"category no se puede modificar por PATCH en esta version"}` | use case (R-1) |
| `INVALID` del orquestador (campos agregados faltan) | 400 | `{"error":"<motivo>"}` | `CustomerValidations.validate(...)` |
| `ERROR` del orquestador | 500 | `{"error":"<motivo>"}` | orquestador |
| Conflicto concurrente (ciclo abierto sobre el mismo `entityId`) | 409 | `application/problem+json` (`SincronizacionConcurrente`) | `ConcurrentTransitionException` del orquestador |
| `SAP_ERROR` parcial | 502 | `{"error":"<motivo>"}` | orquestador |
| Sin rol WRITE | 403 | — | Spring Security |
| `BusinessPartnerODataAdapter` inactivo | 404 | (controller no registrado) | `@ConditionalOnBean` |

> **Pendiente — R-1 (ADR futura)**: el `BusinessPartnerODataAdapter`
> extiende `CustomerSapOutboundPort`, que hoy en `update` solo cubre
> `OrganizationBPName1`. Para que `PATCH` acepte `category` hay que
> decidir cómo se modela el cambio de `BusinessPartnerCategory` (no es
> habitual; normalmente se cambia el "rol" del BP, que es otra entidad).
> Se anota como propuesta en [`../../MEJORAS-Y-PROPUESTAS.md`](../../MEJORAS-Y-PROPUESTAS.md).

## 7. Criterios de aceptación

Cada AC es un test. Redactar en formato *dado / cuando / entonces*.

| AC | Criterio | Test |
|---|---|---|
| AC-1 | `PUT /business-partners/{id}` con `name` y `category` nuevos: el use case construye un `Customer` mínimo y llama a `syncCustomerUseCase.executeFromPayload(msg, customer, features)` con `features` igual a `EnumSet.allOf(CustomerFeature.class)`. El orquestador devuelve el `SyncState` final. | `UpsertBusinessPartnerUseCaseTest#putBuildsCustomerAndCallsExecuteFromPayload` |
| AC-2 | `PUT` con el mismo `name` y `category` que ya están en SAP: el orquestador aplica dedupe (`alreadySent`) y devuelve `SENT_SAP` sin reenviar. | `SyncCustomerUseCaseTest#executeFromPayloadDedupesWhenSnapshotMatchesLastSent` (extensión del existente) |
| AC-3 | `PATCH /business-partners/{id}` con solo `name`: el `Customer` construido lleva solo `name` (los demás campos a `null`/`ACTIVO`), y se delega en `executeFromPayload`. | `UpsertBusinessPartnerUseCaseTest#patchWithOnlyNameBuildsPartialCustomer` |
| AC-4 | `PATCH` sin campos → use case lanza `BusinessPartnerUpsertException(kind=MandatoryFieldMissing)`; el controller la mapea a `400` con mensaje específico. | `UpsertBusinessPartnerUseCaseTest#patchWithoutAnyFieldFails` + `BusinessPartnerWriteControllerTest#patchWithoutAnyFieldReturns400` |
| AC-5 | `PUT/PATCH` sin rol WRITE → 403 (vigilado por `EndpointsDeclareAccessTest` y `ApiSecurityTest`). | `EndpointsDeclareAccessTest` + verificación manual en `ApiSecurityTest` |
| AC-6 | `PUT/PATCH` cuando ya hay un ciclo abierto sobre el mismo `entityId`: el orquestador lanza `ConcurrentTransitionException`; el controller la mapea a `409` con `ProblemDetail`. | `BusinessPartnerWriteControllerTest#concurrentTransitionReturns409` |
| AC-7 | El hash se calcula sobre el `Customer` construido del body, **no** sobre una lectura del legacy (no hay releído). El mismo body produce el mismo hash y dedupe. | `SyncCustomerUseCaseTest#executeFromPayloadComputesHashOverPayloadNotLegacy` |
| AC-8 | El `openapi.yml` declara las 2 rutas con `operationId` único, `tags: [Sincronizacion]`, `x-required-role: SAP_WRITE`. `OpenApiMatchesControllersTest` no rompe. | `OpenApiMatchesControllersTest` |
| AC-9 | Refactor: `SyncCustomerUseCase#runPipeline(IngestionMessage, Customer, Set<CustomerFeature>)` reusa toda la lógica post-fetch (validación, index, send, dedupe, máquina de estados). `execute(IngestionMessage)` y `executeFromPayload(...)` solo difieren en la **fuente del `Customer`** (legacy vs payload). Cero código duplicado entre ambos. | `SyncCustomerUseCaseTest` (todos los tests previos en verde) + `UpsertBusinessPartnerUseCaseTest` |
| AC-10 | Los tests previos de `SyncCustomerUseCaseTest` (≥ 21 tests) siguen verdes tras el refactor: ningún test del flujo CDC cambia su comportamiento. | `SyncCustomerUseCaseTest` (sin cambios en `execute(IngestionMessage)`) |
| AC-11 | Activación condicional: el bean `UpsertBusinessPartnerUseCase` y el controller `BusinessPartnerWriteController` solo se registran si `BusinessPartnerODataAdapter` está activo (`sap.odata.customer.enabled=true`). Si no, los endpoints no existen. | `CustomerApplicationContextTest` (smoke) o el test del wiring en `CustomerUseCaseConfig` |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

- Métrica `sap_client_request_duration{method=post|patch, sap_destination=s4_native, http_status=...}`
  ya cubre cada envío a SAP — la emite el `RestClientSapClient`, no hace
  falta cablear nada nuevo.
- `sap_sync_stage_duration{stage=fetch|validate|index|send}` ya se
  registra en `SyncCustomerUseCase`. En `executeFromPayload` el `fetch`
  se omite (no hay releído); el resto de etapas se mide igual.
- Logs: el orquestador ya loguea `SyncCustomer inicio entityId=… origin=…`.
  En `executeFromPayload` se ve `origin=rest` (vs `cdc`).

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| AC-1, R-2 | `UpsertBusinessPartnerUseCase#put` | `UpsertBusinessPartnerUseCaseTest#putBuildsCustomerAndCallsExecuteFromPayload` |
| AC-3, R-1 | `UpsertBusinessPartnerUseCase#patch` | `UpsertBusinessPartnerUseCaseTest#patchWithOnlyNameBuildsPartialCustomer` + `#patchWithCategoryFails` |
| AC-4, R-3 | `UpsertBusinessPartnerUseCase#patch` (validación `MandatoryFieldMissing`) | `UpsertBusinessPartnerUseCaseTest#patchWithoutAnyFieldFails` |
| AC-5 | `@PreAuthorize("hasRole('" + ApiRoles.WRITE + "')")` en `BusinessPartnerWriteController#put/#patch` | `EndpointsDeclareAccessTest` |
| AC-6, R-7 | `BusinessPartnerWriteController#onConcurrentTransition` (`@ExceptionHandler`) | `BusinessPartnerWriteControllerTest#concurrentTransitionReturns409` |
| AC-7, R-5 | `SyncCustomerUseCase#runPipeline` + `PayloadHasher.hash(Customer)` | `SyncCustomerUseCaseTest#executeFromPayloadComputesHashOverPayloadNotLegacy` |
| AC-8 | `customer/src/main/resources/openapi.yml` (PUT + PATCH `/business-partners/{id}`) | `OpenApiMatchesControllersTest` |
| AC-9, R-9 | `SyncCustomerUseCase#runPipeline` extraído; `execute` y `executeFromPayload` lo invocan | `SyncCustomerUseCaseTest` (todos los 26 tests previos en verde tras el refactor) |
| AC-10 | Mismo | `SyncCustomerUseCaseTest` (sin cambios en `execute(IngestionMessage)`; 3 tests nuevos para `executeFromPayload`) |
| AC-11 | `@ConditionalOnBean(BusinessPartnerODataAdapter.class)` en `CustomerUseCaseConfig`; `@ConditionalOnBean(UpsertBusinessPartnerUseCase.class)` en `BusinessPartnerWriteController` | `CustomerApplicationContextTest` (smoke) |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-24 | Spec inicial + implementación (PRD-10): `BusinessPartnerUpsertException` (domain) con dos `Kind` (`MandatoryFieldMissing`, `InvalidPayload`); refactor mínimo del orquestador (`SyncCustomerUseCase#runPipeline` extrae el esqueleto post-fetch; `executeFromPayload(IngestionMessage, Customer, Set<CustomerFeature>)` reusa `runPipeline` con el `Customer` del body); `UpsertBusinessPartnerUseCase` (application); `BusinessPartnerWriteController` (bootstrap) con `PUT/PATCH /business-partners/{id}`, `@PreAuthorize hasRole('SAP_WRITE')`, mapeo a `409 ProblemDetail` en `ConcurrentTransitionException` y a `400` en `BusinessPartnerUpsertException`. OpenAPI al día con tag `Operacion` y `x-required-role: SAP_WRITE`. Activación condicional: el bean y el controller solo se montan si `BusinessPartnerODataAdapter` está activo (`sap.odata.customer.enabled=true`). 25 `@Test` nuevos (12 use case, 10 controller, 3 orquestador). R-1 anotada: `PATCH` de `category` se rechaza con 400 hasta ampliar el adapter. | este |
