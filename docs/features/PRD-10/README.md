# Feature: PRD-10 — Upsert manual de Business Partner (PUT/PATCH, facturable)

## Objetivo

Exponer el alta y la actualización manual del Business Partner en SAP S/4
Public Cloud vía endpoints REST (`PUT /business-partners/{id}` y
`PATCH /business-partners/{id}`). El pipeline es **el mismo que el de
CDC**: `SyncCustomerUseCase` con máquina de estados, dedupe por hash del
snapshot, lookup previo y `PATCH` con `If-Match`. La diferencia con CDC
es la fuente del `Customer`: el body REST en lugar del releído del
legacy.

**Cada llamada es facturable** (escritura contra SAP). El rol mínimo es
`sap-write`; `sap-external-read` no puede llamar a estos endpoints.

Resuelve el caso del equipo de gestión de datos maestros que necesita
crear o corregir un cliente al momento, sin esperar al evento de CDC y
sin pasar por SAP GUI.

## Estado

Incorporada 2026-09-24. Spec:
[`docs/sdd/customer/upsert-business-partner-manual.md`](../../sdd/customer/upsert-business-partner-manual.md).
QA verde: `mvn verify` SUCCESS (3:02 min), 584 tests declarados (25
nuevos en este PR), JaCoCo ≥ 75 % en `**/domain/**`, ArchUnit en verde
(incluidos `EndpointsDeclareAccessTest`,
`DomainPurityTest`/`ApplicationPurityTest` y `OpenApiMatchesControllersTest`),
`TestCountMatchesDocsTest` verde (584 en `TESTING.md`).

Pendiente:

- **Verificación contra el tenant SAP de test**
  ([`docs/testing/CHECKLIST-TENANT-SAP.md`](../../testing/CHECKLIST-TENANT-SAP.md)
  §1-§3), mismo criterio que las features de contacto, datos bancarios y
  PRD-9.
- **Ampliar `BusinessPartnerODataAdapter.update`** para soportar `PATCH`
  de `category` (R-1 del spec; anotado como ADR futura en
  [`docs/MEJORAS-Y-PROPUESTAS.md`](../../MEJORAS-Y-PROPUESTAS.md)).

## Alcance

**Dentro**:

- 2 endpoints REST de escritura en `customer-app` (puerto 8081):
  - `PUT /business-partners/{id}` — upsert completo (alta o
    actualización). Body: `{ "name": "Foo SL", "category": "2" }`.
    `category` opcional (default `"2"`).
  - `PATCH /business-partners/{id}` — update parcial. Body con al menos
    uno de los campos opcionales (`name`, `category`). El campo
    `category` se rechaza con 400 en esta versión (R-1).
- `BusinessPartnerUpsertException` (domain, RuntimeException) con dos
  `Kind`: `MandatoryFieldMissing` e `InvalidPayload`. No tiene traza PII.
- `UpsertBusinessPartnerUseCase` (`application/general`) que valida el
  body, construye un `Customer` mínimo desde el `name`/`category`, y
  delega en `syncCustomerUseCase.executeFromPayload` (con el set de
  features vacío: el upsert REST es solo del agregado, las features
  sub-entidad siguen por `POST /customers/sync`).
- Refactor mínimo del orquestador:
  `SyncCustomerUseCase#runPipeline(IngestionMessage, Customer,
  Set<CustomerFeature>)` extrae el esqueleto post-fetch (hash, dedupe,
  máquina, indexar, enviar SAP, imagen). `execute(IngestionMessage)` y
  `executeFromPayload(IngestionMessage, Customer, Set<CustomerFeature>)`
  lo invocan. Cero código duplicado entre los dos puntos de entrada.
- `BusinessPartnerWriteController` (`bootstrap/web`) con `@PreAuthorize
  hasRole('SAP_WRITE')` en ambos endpoints, mapeo a `400` con
  `{"error": "..."}` en `BusinessPartnerUpsertException`, y a
  `409 ProblemDetail` en `ConcurrentTransitionException`.
- Activación condicional:
  `@ConditionalOnBean(BusinessPartnerODataAdapter.class)` en el bean y
  `@ConditionalOnBean(UpsertBusinessPartnerUseCase.class)` en el
  controller. Si `sap.odata.customer.enabled=false`, los endpoints no se
  registran.
- Contrato OpenAPI en `customer/src/main/resources/openapi.yml` (tag
  nuevo `Operacion`, 2 paths nuevos, 2 schemas); coherencia con
  controllers vigilada por `OpenApiMatchesControllersTest`.

**Fuera**:

- PATCH de `category` (R-1): `BusinessPartnerODataAdapter.update` solo
  cubre `OrganizationBPName1`. Ampliación queda como ADR futura; mientras
  tanto, `PATCH` con `category` → 400.
- Subentidades ADDRESS/FISCAL/CONTACT/BANKING por REST: siguen por
  `POST /customers/sync`. El PUT/PATCH de PRD-10 es **solo del agregado**.
- Sincronización desde el legacy: eso es CDC.
- Confirmación contra el tenant SAP de test — pendiente, mismo criterio
  que `sincronizacion-contacto.md`, `sincronizacion-datos-bancarios.md`
  y PRD-9.

## Ficheros afectados

| Tipo | Ruta |
|---|---|
| Spec | `docs/sdd/customer/upsert-business-partner-manual.md` |
| Domain (nuevo) | `customer/src/main/java/com/poc/sap/customer/domain/BusinessPartnerUpsertException.java` |
| Application (refactor) | `customer/src/main/java/com/poc/sap/customer/application/general/SyncCustomerUseCase.java` (extracción de `runPipeline` + nuevo `executeFromPayload`) |
| Application (nuevo) | `customer/src/main/java/com/poc/sap/customer/application/general/UpsertBusinessPartnerUseCase.java` |
| Bootstrap wiring | `customer/src/main/java/com/poc/sap/customer/bootstrap/CustomerUseCaseConfig.java` (`@Bean` condicional sobre `BusinessPartnerODataAdapter`) |
| Controller (nuevo) | `customer/src/main/java/com/poc/sap/customer/bootstrap/web/BusinessPartnerWriteController.java` |
| Test orquestador (extendido) | `customer/src/test/java/com/poc/sap/customer/application/general/SyncCustomerUseCaseTest.java` (+3 tests para `executeFromPayload`) |
| Test use case (nuevo) | `customer/src/test/java/com/poc/sap/customer/application/general/UpsertBusinessPartnerUseCaseTest.java` (12 tests) |
| Test controller (nuevo) | `customer/src/test/java/com/poc/sap/customer/bootstrap/web/BusinessPartnerWriteControllerTest.java` (10 tests) |
| Contrato REST | `customer/src/main/resources/openapi.yml` (tag `Operacion`, 2 paths, 2 schemas) |
| Docs | `docs/sdd/customer/CHANGELOG.md`, `docs/sdd/README.md` §5, `docs/MEJORAS-Y-PROPUESTAS.md` PRD-10, `CHANGELOG.md` (raíz), `docs/testing/TESTING.md` (cifra a 584), `docs/QUICK_START.md`, `docs/testing/GUIA-PRUEBAS.md` |
| Registro SDD | `sdd_registry.feature_evento` — ALTA `upsert-business-partner-manual` |
| Tests totales | 559 → 584 (+25: 3 orquestador, 12 use case, 10 controller) |

## Grafo de ejecución

Pendiente: `feature-execution-graph.html` lo genera `docs-writer` con la
skill `architecture-diagram` del repo de agentes. Lo dejamos para una
iteración posterior — el alcance de la feature (controller → use case →
`SyncCustomerUseCase.executeFromPayload` → `runPipeline` → máquina de
estados → `BusinessPartnerODataAdapter` → SAP S/4) es el mismo camino
que CDC; el [`FLOWS.md`](../../architecture/FLOWS.md) ya documenta el
trayecto `customer-app → SAP OData → A_BusinessPartner`.

## Criterios de aceptación

Copiados literalmente del spec
[`docs/sdd/customer/upsert-business-partner-manual.md`](../../sdd/customer/upsert-business-partner-manual.md) §7
(no son resúmenes, son los AC del spec):

| AC | Criterio |
|---|---|
| AC-1 | `PUT /business-partners/{id}` con `name` y `category` nuevos: el use case construye un `Customer` mínimo y llama a `syncCustomerUseCase.executeFromPayload(msg, customer, features)` con `features` igual a `EnumSet.allOf(CustomerFeature.class)`. El orquestador devuelve el `SyncState` final. |
| AC-2 | `PUT` con el mismo `name` y `category` que ya están en SAP: el orquestador aplica dedupe (`alreadySent`) y devuelve `SENT_SAP` sin reenviar. |
| AC-3 | `PATCH /business-partners/{id}` con solo `name`: el `Customer` construido lleva solo `name` (los demás campos a `null`/`ACTIVO`), y se delega en `executeFromPayload`. |
| AC-4 | `PATCH` sin campos → use case lanza `BusinessPartnerUpsertException(kind=MandatoryFieldMissing)`; el controller la mapea a `400` con mensaje específico. |
| AC-5 | `PUT/PATCH` sin rol WRITE → 403 (vigilado por `EndpointsDeclareAccessTest` y `ApiSecurityTest`). |
| AC-6 | `PUT/PATCH` cuando ya hay un ciclo abierto sobre el mismo `entityId`: el orquestador lanza `ConcurrentTransitionException`; el controller la mapea a `409` con `ProblemDetail`. |
| AC-7 | El hash se calcula sobre el `Customer` construido del body, **no** sobre una lectura del legacy (no hay releído). El mismo body produce el mismo hash y dedupe. |
| AC-8 | El `openapi.yml` declara las 2 rutas con `operationId` único, `tags: [Operacion]`, `x-required-role: SAP_WRITE`. `OpenApiMatchesControllersTest` no rompe. |
| AC-9 | Refactor: `SyncCustomerUseCase#runPipeline(IngestionMessage, Customer, Set<CustomerFeature>)` reusa toda la lógica post-fetch (validación, index, send, dedupe, máquina de estados). `execute(IngestionMessage)` y `executeFromPayload(...)` solo difieren en la **fuente del `Customer`** (legacy vs payload). Cero código duplicado entre ambos. |
| AC-10 | Los tests previos de `SyncCustomerUseCaseTest` (≥ 21 tests) siguen verdes tras el refactor: ningún test del flujo CDC cambia su comportamiento. |
| AC-11 | Activación condicional: el bean `UpsertBusinessPartnerUseCase` y el controller `BusinessPartnerWriteController` solo se registran si `BusinessPartnerODataAdapter` está activo (`sap.odata.customer.enabled=true`). Si no, los endpoints no existen. |

## Validación

```bash
# Local rápido (sin Docker, sin arrancar la app):
./mvnw -B -ntp -pl customer test \
  -Dtest='UpsertBusinessPartnerUseCaseTest,BusinessPartnerWriteControllerTest,SyncCustomerUseCaseTest,OpenApiMatchesControllersTest,EndpointsDeclareAccessTest'

# Reactor entero (lo que rompe si algo va mal):
./mvnw -B -ntp verify
# -> BUILD SUCCESS, Tests run: 584 (25 nuevos en PRD-10)

# IT con Docker (los contract tests contra SAP simulado con WireMock):
./mvnw -B -ntp -pl it verify -Ddocker.available=true
# -> BUILD SUCCESS, Tests run: 27

# Verificar la fila en el registro SDD (necesita docker compose levantado):
python3 scripts/sdd-registry-check.py
# -> "specs en docs/sdd: 9 | filas en feature: 9 | diferencias: 0 | ..."

# Confirmar la cifra declarada de tests (vigila la sincronía TESTING.md):
./mvnw -B -ntp -pl it test -Dtest=TestCountMatchesDocsTest
# -> Tests run: 1, Failures: 0
```
