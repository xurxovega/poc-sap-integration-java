# Consulta de Business Partner en SAP (GET, sin coste)

| | |
|---|---|
| **Dominio** | `customer` |
| **Estado** | ✅ implementado 2026-09-24 |
| **Entradas** | REST `GET /business-partners/...` (este PR) |
| **Destino SAP** | S/4 nativo (`sap.odata.read.enabled=true` **o** `sap.odata.customer.enabled=true`) |
| **Última revisión** | 2026-09-24 |

## 1. Objetivo

Exponer el `BusinessPartnerReadPort` existente como endpoints REST GET para
**consulta puntual** de Business Partners ya creados en SAP S/4 Public Cloud,
**sin escribir nada y, por tanto, sin consumir unidades SAP** (las lecturas
OData no se facturan; spec [`../sap-api-catalog.md`](../sap-api-catalog.md) y
[`../../architecture/INTEGRATION-PATTERNS.md`](../../architecture/INTEGRATION-PATTERNS.md)).

Resuelve dos casos de uso operativos reales:

1. **Operador interno** que necesita ver si un BP existe en SAP antes de
   forzar un re-sync, o que necesita resolver una duda de datos maestros
   (`code`, `name`, `category`).
2. **Cliente externo** (rol `SAP_EXTERNAL_READ`) que consulta la lista de
   clientes o proveedores que le damos de alta — recibe la información
   personal enmascarada.

No es un sustituto del pipeline de sincronización (CDC + REST `POST /customers/sync`
+ máquina de estados); es una **vía de inspección** complementaria sobre el
mismo S/4.

## 2. Alcance

**Dentro** (este PR):

- `GET /business-partners/{code}` — un BP por clave de negocio. Devuelve
  `{code, name, category}` o `404` si SAP no lo tiene.
- `GET /business-partners?category=<categoria>&top=<n>` — búsqueda por
  categoría OData (`1` proveedores, `2` clientes, etc.) con `$top` como
  cota superior. Devuelve lista (vacía si nada coincide).
- `GET /business-partners/customers?top=<n>` y
  `GET /business-partners/suppliers?top=<n>` — atajos para los dos
  categorías más usadas, ya cableadas en el adaptador.
- Activación condicional (`BusinessPartnerReadEnabled`): el bean solo existe
  si `sap.odata.read.enabled=true` **o** `sap.odata.customer.enabled=true`,
  igual que el adaptador (decisión D-18, spec
  [`../common/upsert-idempotente-sap.md`](../common/upsert-idempotente-sap.md) §3).
- Seguridad: `@PreAuthorize` por endpoint (`SAP_READ` **o**
  `SAP_EXTERNAL_READ`); enmascarado de `name` para `SAP_EXTERNAL_READ`
  según la regla R-4 de [`../common/seguridad-api.md`](../common/seguridad-api.md).
- Contrato OpenAPI en `customer/src/main/resources/openapi.yml` con
  `x-required-role` por endpoint y `x-masked-for-external-read`.
- Tests rojos primero: use case unit + slice web del controller, ambos
  con mocks del puerto o del use case.

**Fuera** (espera a otras features):

- POST/PATCH/DELETE de Business Partners: es PRD-10 y factura unidades SAP.
- Búsqueda libre por nombre o NIF (`$filter=BusinessPartnerFullName
  substringof ...`): se hace desde el `dashboard-customer` que lee Mongo
  y ES directamente; UI-1 ya cubre vista por entidad y búsqueda por NIF.
- Paginación OData real (`$skiptoken`, `$count`): no aporta valor en una
  consulta operativa con `top <= 200`; si hace falta, se hace desde el
  dashboard.
- Búsqueda por `BusinessPartnerGrouping`, `Country`, `City`: filtros
  avanzados OData. Si un operador los necesita, se añaden con el
  detalle del `$filter` cuando aparezca el caso.
- Verificación contra el tenant SAP de test: igual que
  [`sincronizacion-contacto.md`](sincronizacion-contacto.md) y
  [`sincronizacion-datos-bancarios.md`](sincronizacion-datos-bancarios.md),
  queda pendiente hasta que se ejecute
  [`../../testing/CHECKLIST-TENANT-SAP.md`](../../testing/CHECKLIST-TENANT-SAP.md)
  §1-§3 (brecha abierta).

## 3. Entrada

| Endpoint | Parámetro | Tipo | Obligatorio | Notas |
|---|---|---|---|---|
| `GET /business-partners/{code}` | `code` | path | sí | Clave de negocio del BP en SAP (`BusinessPartner`); escapado contra comillas simples en OData |
| `GET /business-partners` | `category` | query | sí | Literal OData del filtro `BusinessPartnerCategory eq '...'`. El `findByCategory` interno mapea valores conocidos (`1`, `2`); valores arbitrarios también pasan si el `filter` es legal |
| `GET /business-partners` | `top` | query | sí | `1..200`; fuera de rango el use case rechaza con 400 |
| `GET /business-partners/customers` | `top` | query | sí | `1..200`; idem |
| `GET /business-partners/suppliers` | `top` | query | sí | `1..200`; idem |

Contrato del mensaje de error del 404: el cuerpo es
`{"error":"BP <code> no existe en SAP"}` (mismo formato que
`CustomerHistoryController`, sin detalle sensible).

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | `top` dentro de `[1, 200]` en los tres endpoints con listado | `400` con `{"error":"top fuera de rango [1..200]: <n>"}` |
| R-2 | Un `findById` que SAP no resuelve lanza `NoSuchElementException` (mismo criterio que `CustomerHistoryUseCase.diff`) | `404` con `{"error":"BP <code> no existe en SAP"}` |
| R-3 | `findByCategory`, `findCustomers` y `findSuppliers` con respuesta vacía de SAP devuelven lista vacía (no es 404) | `200` con `[]` |
| R-4 | Si el llamador tiene `SAP_EXTERNAL_READ` y NO tiene `SAP_READ` (o superior por jerarquía), el campo `name` se enmascara: asteriscos cubriendo todo menos los **últimos 4 caracteres**. Si la longitud efectiva (sin espacios) es ≤ 4, se enmascara completo. **Implementación**: helper nuevo `com.poc.sap.common.security.PiiMasker.maskName(...)` — siempre enmascara con `keep=4` aunque no tenga dígitos (a diferencia del `mask(String)` general, que respeta BIC y deja pasar cadenas sin dígitos porque no son PII; el `name` de un BP sí lo es). `code` y `category` no se enmascaran (no son PII). Regla común: R-4 de [`seguridad-api.md`](../common/seguridad-api.md). | respuesta 200 con `masked: true` |
| R-5 | Si el `BusinessPartnerReadPort` no está activo (ningún `sap.odata.*.enabled` encendido), el endpoint no se registra (404 desde Spring, no 503). | `404` del dispatcher |
| R-6 | No se factura: los endpoints son GET puros, sin `Idempotency-Key` ni `If-Match`. El retry ante 5xx lo aporta `SapClient` (`get` es idempotente y siempre se reintenta, ver [`resiliencia-cliente-sap.md`](../common/resiliencia-cliente-sap.md)). | — |

## 5. Salida

### 5.1 `GET /business-partners/{code}` — 200

```json
{
  "code": "C001",
  "name": "Cliente de ejemplo S.L.",
  "category": "2",
  "masked": false
}
```

`masked` es `true` cuando el `name` se ha enmascarado por R-4.

### 5.2 `GET /business-partners/{code}` — 404

```json
{ "error": "BP C001 no existe en SAP" }
```

### 5.3 `GET /business-partners?category=2&top=10` — 200

```json
{
  "results": [
    { "code": "C001", "name": "Cliente de ejemplo S.L.", "category": "2" },
    { "code": "C002", "name": "Otro cliente S.A.", "category": "2" }
  ],
  "masked": false
}
```

`results` es lista vacía si SAP no devuelve nada (R-3). `masked` es
`true` si el lote se ha enmascarado por R-4.

### 5.4 `GET /business-partners/customers?top=5` y `/suppliers?top=5`

Misma forma que §5.3 pero el `category` del filtro va implícito (`2` y `1`
respectivamente).

### 5.5 Mapeo a SAP

| Campo respuesta | Campo SAP (`A_BusinessPartner`) |
|---|---|
| `code` | `BusinessPartner` |
| `name` | `BusinessPartnerFullName` |
| `category` | `BusinessPartnerCategory` |

Endpoint S/4 consumido: `GET /sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner`
(configurable vía `sap.odata.bp-path`), con query params
`$top` y `$filter=BusinessPartnerCategory eq '<n>'` en los listados, y
`A_BusinessPartner('<code>')` en la consulta por id. Catálogo en
[`../sap-api-catalog.md`](../sap-api-catalog.md).

## 6. Estados y errores

El endpoint no pasa por la máquina de estados: es una consulta directa a
S/4 a través del puerto existente. Lo que se propaga al cliente:

| Situación | HTTP | Cuerpo | Origen |
|---|---|---|---|
| BP existe, sin PII a enmascarar | 200 | §5.1 / §5.3 con `masked: false` | adaptador + use case |
| BP existe, PII enmascarada para `sap-external-read` | 200 | §5.1 / §5.3 con `masked: true` | enmascarado por controller (R-4) |
| BP no existe | 404 | §5.2 | `findById` devuelve `Optional.empty()` → use case lanza `NoSuchElementException` (R-2) |
| Listado sin coincidencias | 200 | `{"results":[], "masked":false}` | `findByCategory` devuelve lista vacía (R-3) |
| `top` fuera de `[1, 200]` | 400 | `{"error":"top fuera de rango [1..200]: <n>"}` | use case valida y lanza `IllegalArgumentException` (R-1) |
| SAP no responde (timeout, 5xx) | 502 | `{"error":"SAP no responde: <resumen>"}` | `BusinessPartnerReadAdapter` propaga el fallo del `SapClient`; el controller lo mapea |
| Puerto no activado | 404 | (no se registra el endpoint; `Bean` condicional no existe) | `BusinessPartnerReadEnabled` (R-5) |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dado un `code` con respuesta válida del puerto, `LookupBusinessPartnerUseCase.findById` devuelve un `BusinessPartnerSummary` con `code`, `name` y `category` rellenos. | `LookupBusinessPartnerUseCaseTest#findByIdDelegatesToPort` |
| AC-2 | Dado un `code` que SAP no tiene, `findById` lanza `NoSuchElementException` con mensaje que contiene el `code`. El controller lo mapea a 404 con cuerpo `{"error":"BP <code> no existe en SAP"}`. | `LookupBusinessPartnerUseCaseTest#findByIdThrowsWhenPortReturnsEmpty` + `BusinessPartnerControllerTest#findByIdReturns404WhenAbsent` |
| AC-3 | `search(category, top)` con respuesta no vacía del puerto devuelve la misma lista. Con respuesta vacía devuelve lista vacía (sin lanzar). | `LookupBusinessPartnerUseCaseTest#searchReturnsPortResults` + `#searchReturnsEmptyListWhenPortEmpty` |
| AC-4 | `findCustomers(top)` y `findSuppliers(top)` delegan en el puerto con `category=2` y `category=1` respectivamente. | `LookupBusinessPartnerUseCaseTest#findCustomersAndSuppliersUseFixedCategories` |
| AC-5 | `top` fuera de `[1, 200]` en cualquier método que lo reciba lanza `IllegalArgumentException` con mensaje que menciona el rango y el valor. | `LookupBusinessPartnerUseCaseTest#topOutOfRangeFails` (parametrizado) |
| AC-6 | `GET /business-partners/{code}` con `AccessScope.canSeeSensitiveData()=true` (rol `SAP_READ`) devuelve el `name` completo y `masked: false`. | `BusinessPartnerControllerTest#findByIdReturnsFullNameForReadRole` |
| AC-7 | `GET /business-partners/{code}` con `AccessScope.canSeeSensitiveData()=false` (rol `SAP_EXTERNAL_READ` aislado) devuelve el `name` enmascarado y `masked: true`. | `BusinessPartnerControllerTest#findByIdMasksNameForExternalRead` |
| AC-8 | `GET /business-partners?category=2&top=10` con respuesta no vacía devuelve `results` con la lista del puerto y `masked` coherente con `AccessScope`. | `BusinessPartnerControllerTest#searchReturnsListAndMasksWhenExternalRead` |
| AC-9 | `GET /business-partners/customers?top=5` y `/suppliers?top=5` delegan en `findCustomers` y `findSuppliers` con el `top` recibido. | `BusinessPartnerControllerTest#customersAndSuppliersDelegateToUseCase` |
| AC-10 | `top=0`, `top=-1`, `top=201` y `top=999` devuelven 400 con `{"error":"top fuera de rango [1..200]: <n>"}`. | `BusinessPartnerControllerTest#topOutOfRangeReturns400` (parametrizado) |
| AC-11 | El `openapi.yml` declara los 4 endpoints con `operationId` único, `tags: [Consulta]`, `x-required-role: [SAP_READ, SAP_EXTERNAL_READ]` y `x-masked-for-external-read: true` en los que apliquen. `OpenApiMatchesControllersTest` no rompe. | `OpenApiMatchesControllersTest` (ya existe) |
| AC-12 | `EndpointsDeclareAccessTest` no rompe: cada endpoint tiene `@PreAuthorize` declarando los roles. | `EndpointsDeclareAccessTest` (ya existe) |

Aplican además los
[criterios globales](../README.md#4-criterios-de-aceptación-globales); los
cubriríamos ya (la feature no toca `common`, no altera el pipeline, no
rompe el contrato de SAP porque solo consume `GET`).

## 8. Observabilidad

- Métrica `sap_client_request_duration{method=get, sap_destination=s4_native, http_status=...}`
  ya cubre cada GET contra SAP — la emite el `RestClientSapClient`, no hace
  falta cablear nada nuevo.
- Logs: el `BusinessPartnerReadAdapter` ya loguea a nivel `DEBUG` cuando
  parsea; no añadimos logs nuevos.
- Sin métricas nuevas ni trazas: la feature es 100 % delegación a un puerto
  ya instrumentado.

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| R-1, AC-5 | `LookupBusinessPartnerUseCase#search/findCustomers/findSuppliers` (validación `top`) | `LookupBusinessPartnerUseCaseTest#topOutOfRangeFails` |
| R-2, AC-1, AC-2 | `LookupBusinessPartnerUseCase#findById` | `LookupBusinessPartnerUseCaseTest#findByIdDelegatesToPort` + `#findByIdThrowsWhenPortReturnsEmpty` |
| R-3, AC-3, AC-4 | `LookupBusinessPartnerUseCase#search/findCustomers/findSuppliers` | `LookupBusinessPartnerUseCaseTest#searchReturnsPortResults` + `#findCustomersAndSuppliersUseFixedCategories` |
| R-4, AC-6, AC-7, AC-8 | `BusinessPartnerController` (enmascarado vía `AccessScope`) | `BusinessPartnerControllerTest#findByIdReturnsFullNameForReadRole` + `#findByIdMasksNameForExternalRead` + `#searchReturnsListAndMasksWhenExternalRead` |
| R-5 | `BusinessPartnerReadEnabled` (ya existía) + `@ConditionalOnBean(BusinessPartnerReadPort.class)` implícito al inyectar el puerto en el use case | (lo verifica `EndpointsDeclareAccessTest` indirectamente: si no hay bean, el `@PreAuthorize` no se monta y el test no detecta endpoints nuevos) |
| AC-9 | `BusinessPartnerController#customers` y `#suppliers` | `BusinessPartnerControllerTest#customersAndSuppliersDelegateToUseCase` |
| AC-10 | `BusinessPartnerController` + `GlobalExceptionHandler` para `IllegalArgumentException` | `BusinessPartnerControllerTest#topOutOfRangeReturns400` |
| AC-11 | `customer/src/main/resources/openapi.yml` (4 paths nuevos) | `OpenApiMatchesControllersTest` (4/4 verde) |
| AC-12 | `@PreAuthorize` en los 4 endpoints | `EndpointsDeclareAccessTest` (1/1 verde) |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-24 | Spec inicial + implementación: `LookupBusinessPartnerUseCase`, `BusinessPartnerController` (4 endpoints GET), helper `PiiMasker.maskName`, OpenAPI al día, 16 `@Test` nuevos (`LookupBusinessPartnerUseCaseTest`, `BusinessPartnerControllerTest`, 5 tests de `maskName` en `PiiMaskerCommonTest`). Activación condicional (`@ConditionalOnBean` sobre `BusinessPartnerReadPort`). | este |
