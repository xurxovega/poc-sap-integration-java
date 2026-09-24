# Feature: PRD-9 — Consulta de Business Partner en SAP (GET, sin coste)

## Objetivo

Exponer el `BusinessPartnerReadPort` existente como endpoints REST GET
para **consulta puntual** de Business Partners ya creados en SAP S/4
Public Cloud. Las lecturas OData **no se facturan**, así que es una
consulta operativa sin coste — complemento del pipeline de sincronización
(CDC + REST `POST /customers/sync` + máquina de estados), no un sustituto.

Resuelve dos casos reales:

1. **Operador interno** que necesita ver si un BP existe en SAP antes de
   forzar un re-sync, o resolver una duda de datos maestros (`code`,
   `name`, `category`). Rol `sap-read`.
2. **Cliente externo** (rol `sap-external-read`) que consulta la lista de
   clientes o proveedores — recibe el `name` enmascarado.

## Estado

Incorporada 2026-09-24. Spec:
[`docs/sdd/customer/consulta-business-partner-sap.md`](../../sdd/customer/consulta-business-partner-sap.md).
QA verde: `mvn verify` SUCCESS (4:47 min), 559 tests declarados
(16 nuevos en este PR), JaCoCo ≥ 75 % en `**/domain/**`, 5 ArchUnit
en verde (incluidos `EndpointsDeclareAccessTest` y
`DomainPurityTest`/`ApplicationPurityTest`), `TestCountMatchesDocsTest`
verde. Pendiente: verificación contra el tenant SAP de test
([`docs/testing/CHECKLIST-TENANT-SAP.md`](../../testing/CHECKLIST-TENANT-SAP.md) §1-§3),
mismo criterio que las features de contacto y datos bancarios.

## Alcance

**Dentro**:

- 4 endpoints REST GET nuevos en `customer-app` (puerto 8081):
  - `GET /business-partners/{code}` — un BP por clave; 404 si no existe.
  - `GET /business-partners?category=<n>&top=<n>` — búsqueda por
    categoría OData con `$top`.
  - `GET /business-partners/customers?top=<n>` — atajo para clientes
    (categoría 2).
  - `GET /business-partners/suppliers?top=<n>` — atajo para
    proveedores (categoría 1).
- `LookupBusinessPartnerUseCase` (`application/general`) que valida
  `top ∈ [1, 200]` y traduce el `Optional.empty()` del puerto en
  `NoSuchElementException` para que el controller mapee a 404.
- `BusinessPartnerController` (`bootstrap/web`) con `@PreAuthorize
  hasAnyRole(SAP_READ, SAP_EXTERNAL_READ)` y enmascarado del `name`
  para `sap-external-read` vía `PiiMasker.maskName`.
- Helper nuevo `com.poc.sap.common.security.PiiMasker.maskName(String)`:
  enmascara siempre con `keep=4` aunque no haya dígitos (a diferencia
  del `mask(String)` general, que respeta BIC).
- Activación condicional del bean y del controller vía
  `@ConditionalOnBean(BusinessPartnerReadPort.class)` /
  `@ConditionalOnBean(LookupBusinessPartnerUseCase.class)`. El puerto
  solo se monta si `sap.odata.read.enabled=true` o
  `sap.odata.customer.enabled=true` (decisión D-18).
- Contrato OpenAPI en `customer/src/main/resources/openapi.yml`
  (4 paths nuevos); coherencia con controllers vigilada por
  `OpenApiMatchesControllersTest`.

**Fuera**:

- POST/PATCH/DELETE de Business Partners — eso es PRD-10 y factura
  unidades SAP.
- Búsqueda libre por nombre o NIF con `$filter` avanzado — ya la hace
  el `dashboard-customer` leyendo Mongo + ES directamente.
- Paginación OData real (`$skiptoken`, `$count`) — sin valor en
  consulta operativa con `top ≤ 200`.
- Filtros OData avanzados (`BusinessPartnerGrouping`, `Country`, `City`)
  — sin caso de uso todavía.
- Verificación contra el tenant SAP de test — pendiente, mismo
  criterio que `sincronizacion-contacto.md` y
  `sincronizacion-datos-bancarios.md`.

## Ficheros afectados

| Tipo | Ruta |
|---|---|
| Spec | `docs/sdd/customer/consulta-business-partner-sap.md` |
| Dominio (existente, sin cambios) | `customer/src/main/java/com/poc/sap/customer/domain/port/BusinessPartnerReadPort.java` |
| Shared kernel | `common/src/main/java/com/poc/sap/common/security/PiiMasker.java` (helper nuevo `maskName`) |
| Use case (nuevo) | `customer/src/main/java/com/poc/sap/customer/application/general/LookupBusinessPartnerUseCase.java` |
| Bootstrap wiring | `customer/src/main/java/com/poc/sap/customer/bootstrap/CustomerUseCaseConfig.java` (`@Bean` condicional) |
| Controller (nuevo) | `customer/src/main/java/com/poc/sap/customer/bootstrap/web/BusinessPartnerController.java` |
| Test use case (nuevo) | `customer/src/test/java/com/poc/sap/customer/application/general/LookupBusinessPartnerUseCaseTest.java` |
| Test controller (nuevo) | `customer/src/test/java/com/poc/sap/customer/bootstrap/web/BusinessPartnerControllerTest.java` |
| Test PiiMasker (extendido) | `common/src/test/java/com/poc/sap/common/security/PiiMaskerCommonTest.java` (5 tests nuevos para `maskName`) |
| Contrato REST | `customer/src/main/resources/openapi.yml` (4 paths + 2 schemas) |
| Docs | `docs/sdd/customer/CHANGELOG.md`, `docs/sdd/README.md` §5, `docs/MEJORAS-Y-PROPUESTAS.md` PRD-9, `docs/architecture/FLOWS.md`, `CHANGELOG.md` (raíz), `docs/testing/TESTING.md` (cifra a 559), `docs/QUICK_START.md`, `docs/testing/GUIA-PRUEBAS.md` |
| Registro SDD | `sdd_registry.feature_evento` — 2 eventos (ALTA, MODIFICACION) |
| Reactor fix | `pom.xml` + 7 poms hijos (bump de parent `0.1.0 → 0.2.0-SNAPSHOT` para alinear con el root tras el tag `v0.1.0`) |

## Grafo de ejecución

Pendiente: `feature-execution-graph.html` lo genera `docs-writer` con la
skill `architecture-diagram` del repo de agentes. Lo dejamos para una
iteración posterior — el alcance de la feature (4 endpoints → use case →
puerto → SAP S/4 GET OData) es directo y el [FLOWS.md](../../architecture/FLOWS.md)
ya documenta el camino `customer-app → SAP OData → A_BusinessPartner` en
los flujos implementados.

## Criterios de aceptación

Copiados literalmente del spec
[`docs/sdd/customer/consulta-business-partner-sap.md`](../../sdd/customer/consulta-business-partner-sap.md) §7
(no son resúmenes, son los AC del spec):

| AC | Criterio |
|---|---|
| AC-1 | Dado un `code` con respuesta válida del puerto, `LookupBusinessPartnerUseCase.findById` devuelve un `BusinessPartnerSummary` con `code`, `name` y `category` rellenos. |
| AC-2 | Dado un `code` que SAP no tiene, `findById` lanza `NoSuchElementException` con mensaje que contiene el `code`. El controller lo mapea a 404 con cuerpo `{"error":"BP <code> no existe en SAP"}`. |
| AC-3 | `search(category, top)` con respuesta no vacía del puerto devuelve la misma lista. Con respuesta vacía devuelve lista vacía (sin lanzar). |
| AC-4 | `findCustomers(top)` y `findSuppliers(top)` delegan en el puerto con `category=2` y `category=1` respectivamente. |
| AC-5 | `top` fuera de `[1, 200]` en cualquier método que lo reciba lanza `IllegalArgumentException` con mensaje que menciona el rango y el valor. |
| AC-6 | `GET /business-partners/{code}` con `AccessScope.canSeeSensitiveData()=true` (rol `SAP_READ`) devuelve el `name` completo y `masked: false`. |
| AC-7 | `GET /business-partners/{code}` con `AccessScope.canSeeSensitiveData()=false` (rol `SAP_EXTERNAL_READ` aislado) devuelve el `name` enmascarado y `masked: true`. |
| AC-8 | `GET /business-partners?category=2&top=10` con respuesta no vacía devuelve `results` con la lista del puerto y `masked` coherente con `AccessScope`. |
| AC-9 | `GET /business-partners/customers?top=5` y `/suppliers?top=5` delegan en `findCustomers` y `findSuppliers` con el `top` recibido. |
| AC-10 | `top=0`, `top=-1`, `top=201` y `top=999` devuelven 400 con `{"error":"top fuera de rango [1..200]: <n>"}`. |
| AC-11 | El `openapi.yml` declara los 4 endpoints con `operationId` único, `tags: [Consulta]`, `x-required-role: [SAP_READ, SAP_EXTERNAL_READ]` y `x-masked-for-external-read: true` en los que apliquen. `OpenApiMatchesControllersTest` no rompe. |
| AC-12 | `EndpointsDeclareAccessTest` no rompe: cada endpoint tiene `@PreAuthorize` declarando los roles. |

## Validación

```bash
# Local rápido (sin Docker, sin arrancar la app):
./mvnw -B -ntp -pl customer test \
  -Dtest='LookupBusinessPartnerUseCaseTest,BusinessPartnerControllerTest,OpenApiMatchesControllersTest,EndpointsDeclareAccessTest'

# Reactor entero (lo que rompe si algo va mal):
./mvnw -B -ntp verify
# -> BUILD SUCCESS, Tests run: 559 (16 nuevos en PRD-9), BUILD SUCCESS

# Verificar la fila en el registro SDD:
docker exec -i mysql-sdd mysql -u sdd -psdd sdd_registry \
  -e "SELECT id, estado, spec_path FROM feature WHERE slug='consulta-business-partner-sap';"
# -> 16, implementada, docs/sdd/customer/consulta-business-partner-sap.md

# Confirmar la cifra declarada de tests:
python3 scripts/sdd-registry-check.py 2>&1 | grep "diferencias:"
# -> diferencias: 15   (PRD-9 ya no aparece; las 15 restantes son pre-existentes del repo)
```
