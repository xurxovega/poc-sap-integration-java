# Feature: UI-001 — Dashboard web: vista por entidad y búsqueda

## Objetivo

Una web estática por dominio (`dashboard-customer/`, `dashboard-article/`).
Lectura directa de **Mongo y Elasticsearch del bounded context customer**:
no consume las APIs REST del `customer-app`. Muestra vista por entidad
(UI-1), histórico (UI-2) y búsqueda por clave de dominio (UI-3).
Sin acciones destructivas (UI-4) ni ingesta por formulario; ambos se
mantienen fuera de esta feature.

## Estado

En desarrollo (2026-09-23): `dashboard-customer` arranca (puerto 8091),
exhibe los endpoints de lectura de `customer-app` con la misma matriz de
permisos y la PII enmascarada para `sap-external-read`, y emite sus
propias métricas Prometheus (`/actuator/prometheus` con tag
`application=dashboard-customer`).

## Alcance

**Dentro**:

- Módulo Maven nuevo `dashboard-customer/` (puerto 8091).
- Stack: Thymeleaf + HTMX + Alpine.js (justificación en el spec).
- **Lectura directa Mongo+ES** (no consume `customer-app`); el aislamiento
  entre bounded contexts lo vigila `DashboardIsolationTest`.
- Auth Keycloak client-credentials: `sap-read`, `sap-external-read`,
  `sap-write`, `sap-admin`, `sap-superadmin`. La matriz completa del
  dashboard es la misma que ya documenta `docs/sdd/common/seguridad-api.md`.
- PII enmascarada via `com.poc.sap.common.security.PiiMasker` (promovido
  a `common.security` en este PR; el modulo `customer` conserva la
  fachada legacy).
- Reconocimiento de alertas operativas (`POST /customers/alerts/{id}/ack`,
  rol `sap-write`, persiste en Mongo colección `alerts` con TTL 30 dias
  via los indices de `external-services/mongodb/init.js`).
- Métricas Prometheus del propio dashboard en `/actuator/prometheus`.
- Job de KPIs (MTTR técnico y recuperación p95) corre como `@Scheduled`
  en `bootstrap/observability/KpiJob`, dentro de `dashboard-customer`.
- Template Grafana `customer-pipeline.json` gana `templating.list[name=entityId,type=textbox]`
  para que las queries del dashboard puedan filtrar por cliente.

**Fuera** (espera a otras features):

- UI-002 (vista grafo) — feature separada, se ejecuta después.
- UI-4 (acciones administrativas tipo reproceso DLT) — depende de OPS-2.
- `dashboard-article/` — gemelo cuando se aborde el dominio article.
- Acciones destructivas (re-sync forzoso, reproceso DLT) — no entran.

## Ficheros afectados

| Tipo | Ruta |
|---|---|
| Spec | `docs/sdd/customer/consulta-entidad-ui.md` (pendiente de cerrar en otro PR) |
| Reactor | `pom.xml` (`<module>dashboard-customer</module>`) y `dashboard-customer/pom.xml` |
| Módulo nuevo | `dashboard-customer/` completo (jar ejecutable Spring Boot, puerto 8091) |
| Shared kernel | `common/src/main/java/com/poc/sap/common/security/PiiMasker.java` (promovido) |
| Compatibilidad customer | `customer/src/main/java/com/poc/sap/customer/bootstrap/web/PiiMasker.java` (fachada `@Deprecated`) |
| Mongo | `external-services/mongodb/init.js` (colección `alerts` con TTL 30 dias) |
| Grafana | `deploy/observability/grafana/dashboards/customer-pipeline.json` (variable `entityId`) |
| Docs | `docs/features/UI-001/{README.md, quickstart.md}` (este fichero) |

## Criterios de aceptación

| AC | Criterio | Verificación |
|---|---|---|
| AC-1 | `GET /customers/{id}` devuelve cabecera + tabs + contenido (< 500 ms con Mongo real) | `DashboardControllerTest#entityPageRendersForSapRead` (slice con standalone MVC). IT Testcontainers Mongo en `MongoCustomerImageReaderIT` verifica que la lectura directa cabe en margen generoso. |
| AC-2 | Histórico enmascarado para `sap-external-read` (`piiMasked=true` en el modelo) | `DashboardControllerTest#externalReadGetsPiiMaskedInModel` |
| AC-3 | Búsqueda por `fiscal.taxId` devuelve resultados correctos | `MongoCustomerSearcherIT` (+ seed por taxId, orden por `code`) |
| AC-4 | `dashboard-customer` NO importa `customer.application.*` ni `customer.bootstrap.*` ni `customer.domain.*` | `DashboardIsolationTest` (ArchUnit) |
| AC-5 | `/actuator/prometheus` emite series con el tag `application="dashboard-customer"` y los gauges de KPIs | `DashboardEmitsPrometheusMetricsTest` (PrometheusMeterRegistry real) + smoke del job de KPIs |
| AC-6 | `POST /customers/alerts/{id}/ack` con `sap-write` persiste `ackedAt`/`ackedBy` en Mongo `alerts`; con `sap-read` no ejecuta | `AlertControllerTest#ackPersistsAckedByUsingRoleAndSubject` + `MongoAlertRepositoryIT` |
| AC-7 | `mvn verify` en verde (incluido JaCoCo `check >= 75 %` en `**/domain/**`, ArchUnit, `TestCountMatchesDocsTest`) | build |
| AC-8 | Dashboard Grafana `customer-pipeline.json` añade la variable `entityId` (textbox) y parsea como JSON valido | inspeccion manual + `DeployObservabilityStructureTest` (`common`, parsea Grafana dashboards) |

## Validación

- `mvn verify -pl dashboard-customer` (8 test H-0 + 36 H-2/H-3 + 11 H-4 = 55
  unit/slice + 2 IT Mongo + 2 IT ES Testcontainers en total al sumar H-3;
  + 2 IT KpiJob con Docker). Salida esperada: 57 verde + 2 skipped sin
  `-Ddocker.available=true`.
- `mvn verify` desde raiz: todo el reactor en verde.
- AC-12 (F-9 promoted): la coleccion `alerts` se crea con TTL 30 dias
  en el `external-services/mongodb/init.js`.

## Cambios

| Fecha | Cambio |
|---|---|
| 2026-09-22 | Alta de la feature (estado "Pendiente"). Andamiaje en `docs/features/UI-001/`. |
| 2026-09-23 | Pivot: el dashboard pasa de consumir las APIs REST del customer-app a leer directo Mongo+ES. Cierre del hito H-0..H-5 con `PiiMasker` promovido a `common.security` y fachada legacy en customer. |
