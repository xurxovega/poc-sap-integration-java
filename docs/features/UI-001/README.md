# Feature: UI-001 — Dashboard web: vista por entidad y búsqueda

## Objetivo

Una web estática por dominio (`dashboard-customer/`, `dashboard-article/`).
Lectura directa de **Mongo y Elasticsearch del bounded context customer**:
**no consume las APIs REST del `customer-app`** — el aislamiento entre
bounded contexts lo vigila `DashboardIsolationTest`. Muestra vista por
entidad (UI-1), histórico (UI-2) y búsqueda por clave de dominio (UI-3).
Permite además reconocer alertas operativas con `sap-write`
(F-9 promoted) y emite sus propias métricas Prometheus (F-12) con job de
KPIs propio.

Sin acciones destructivas (UI-4) ni ingesta por formulario; ambos se
mantienen fuera de esta feature.

## Estado

Incorporada 2026-09-23. Spec: [`docs/sdd/customer/consulta-entidad-ui.md`](../../sdd/customer/consulta-entidad-ui.md).
QA verde: `mvn verify` SUCCESS (3:44 min), `mvn -pl it verify -Ddocker.available=true`
SUCCESS (4:40 min), 537 tests declarados, JaCoCo ≥ 75 % en `**/domain/**`,
5 ArchUnit en verde (incluido `DashboardIsolationTest` con 3 reglas),
`TestCountMatchesDocsTest` verde.

## Alcance

**Dentro**:

- Módulo Maven nuevo `dashboard-customer/` (puerto **8091**). App Spring
  Boot 4.1 standalone (no es un sub-módulo del `customer-app`).
- Stack: Thymeleaf + HTMX + Alpine.js (sin SPA, sin build de cliente).
- **Lectura directa Mongo+ES** (no consume `customer-app`); el aislamiento
  entre bounded contexts lo vigila `DashboardIsolationTest` con 3 reglas
  ArchUnit (no importar `customer.application.*`, `customer.bootstrap.*`
  ni `customer.domain.*`).
- Auth Keycloak client-credentials: `sap-read`, `sap-external-read`,
  `sap-write`, `sap-admin`, `sap-superadmin`. Cada endpoint declara el rol
  con `@PreAuthorize`; lo vigila `EndpointsDeclareAccessTest`.
- PII enmascarada via `com.poc.sap.common.security.PiiMasker` (clase
  promovida a `common.security` en este PR — `customer.bootstrap.web.PiiMasker`
  queda como fachada `@Deprecated` que delega en la nueva).
- Reconocimiento de alertas operativas (`POST /customers/alerts/{id}/ack`,
  rol `sap-write`; persiste en Mongo colección `alerts` con TTL 30 días
  vía los índices `alert_id_uk`, `entity_acked_idx`, `acked_ttl`,
  `opened_ttl` de `external-services/mongodb/init.js`).
- Métricas Prometheus del propio dashboard en `/actuator/prometheus` con
  tag `application="dashboard-customer"`. Job de KPIs (`bootstrap/observability/KpiJob`)
  publica `business_kpi_mttr_seconds{window="7d"}` y
  `business_kpi_recovery_p95_seconds{cycle="last"}` con cardinalidad ≤ 2.
- Template Grafana `customer-pipeline.json` gana
  `templating.list[name=entityId,type=textbox]` para que las consultas del
  panel filtren por cliente.
- Contrato REST en `dashboard-customer/src/main/resources/openapi.yml`
  (OpenAPI 3.1); coherencia con controllers vigilada por
  `OpenApiMatchesControllersTest`.

**Fuera** (espera a otras features):

- UI-002 (vista grafo) — feature separada.
- UI-4 (acciones administrativas tipo reproceso DLT) — depende de OPS-2.
- `dashboard-article/` — gemelo cuando se aborde el dominio article.
- Acciones destructivas (re-sync forzoso, reproceso DLT) — no entran.

## Ficheros afectados

| Tipo | Ruta |
|---|---|
| Spec | `docs/sdd/customer/consulta-entidad-ui.md` |
| Reactor | `pom.xml` (`<module>dashboard-customer</module>`) y `dashboard-customer/pom.xml` |
| Módulo nuevo | `dashboard-customer/` completo: app Spring Boot, jar ejecutable, puerto 8091 |
| App entrypoint | `dashboard-customer/src/main/java/com/poc/sap/dashboard/customer/DashboardCustomerApplication.java` |
| Dominio | `dashboard-customer/src/main/java/com/poc/sap/dashboard/customer/domain/` (AddressData, Alert, BankingData, ContactData, CustomerFeature, CustomerSnapshot, CustomerState, CycleTrace, FeatureState, FiscalData + puertos AlertRepository, CustomerHistoryReader, CustomerImageReader, CustomerSearcher, CustomerStateReader) |
| Use cases | `dashboard-customer/src/main/java/com/poc/sap/dashboard/customer/application/` (AcknowledgeAlert, GetCustomerOverview, GetHistory, GetHistoryDiff, GetOpenAlerts, SearchCustomers) |
| Adaptadores | `dashboard-customer/src/main/java/com/poc/sap/dashboard/customer/adapters/persistence/` (MongoAlertRepository + 4 mappers + MongoCustomerImageReader + MongoCustomerSearcher + MongoCustomerStateReader) y `adapters/search/ElasticsearchCustomerHistoryReader.java` |
| Bootstrap | `dashboard-customer/src/main/java/com/poc/sap/dashboard/customer/bootstrap/` (DashboardUseCaseConfig, MongoConfig, ElasticsearchConfig, DashboardPrometheusProbeConfig, web/DashboardController, web/AlertController, observability/KpiJob) |
| Plantillas | `dashboard-customer/src/main/resources/templates/` (alerts.html, customer.html, diff.html, history.html, layout.html, search.html, state.html) |
| Config | `dashboard-customer/src/main/resources/application.yml` + `banner.txt` |
| Contrato | `dashboard-customer/src/main/resources/openapi.yml` |
| Shared kernel | `common/src/main/java/com/poc/sap/common/security/PiiMasker.java` (promovido) |
| Compatibilidad customer | `customer/src/main/java/com/poc/sap/customer/bootstrap/web/PiiMasker.java` (fachada `@Deprecated` que delega) |
| Mongo | `external-services/mongodb/init.js` (colección `alerts` con `alert_id_uk`, `entity_acked_idx`, `acked_ttl` 30d, `opened_ttl` 30d) |
| Grafana | `deploy/observability/grafana/dashboards/customer-pipeline.json` (variable `entityId` textbox) |
| Tests | 80 nuevos: 11 en `common` (`PiiMaskerCommonTest`), 69 en `dashboard-customer` (23 dominio, 21 use cases, 14 adaptadores Mongo/ES con 5 IT Testcontainers, 8 bootstrap web/controllers, 2 KPIs con 2 IT Testcontainers adicionales) |
| Docs | `docs/sdd/customer/consulta-entidad-ui.md` (este cierre) + `docs/features/UI-001/{README.md, quickstart.md, feature-execution-graph.html}` |

## Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | `GET /customers/{id}` renderiza en < 500 ms (medido con `Timer.Sample` desde slice MVC) | `DashboardControllerTest#entityPageRendersUnderBudget` |
| AC-2 | `sap-external-read` recibe el snapshot con PII enmascarada y `piiMasked=true`; `sap-read` recibe el snapshot completo | `DashboardControllerTest#externalReadGetsPiiMaskedInModel` + `DashboardControllerTest#sapReadGetsFullSnapshot` |
| AC-3 | `GET /customers/search?q={taxId}` devuelve resultados correctos desde Mongo, ordenados por `code` | `MongoCustomerSearcherIT` (Testcontainers) |
| AC-4 | `dashboard-customer` no importa `customer.application.*` / `customer.bootstrap.*` / `customer.domain.*` | `DashboardIsolationTest` (3 reglas ArchUnit) |
| AC-5 | `/actuator/prometheus` emite series con tag `application="dashboard-customer"` y la serie `http_server_requests_seconds{application="dashboard-customer",...}` | `DashboardEmitsPrometheusMetricsTest` |
| AC-6 | `POST /customers/alerts/{id}/ack` con `sap-write` persiste `ackedAt`/`ackedBy`; con `sap-read` devuelve 403; alerta inexistente devuelve 404 | `AlertControllerTest#ackPersistsAckedByUsingRoleAndSubject` + `MongoAlertRepositoryIT` |
| AC-7 | `KpiJob` publica `business_kpi_mttr_seconds{window="7d"}` y `business_kpi_recovery_p95_seconds{cycle="last"}` con cardinalidad ≤ 2 | `KpiJobTest` (+ IT Testcontainers) |
| AC-8 | Contrato `openapi.yml` del módulo coincide con los controllers | `OpenApiMatchesControllersTest` |
| AC-9 | `customer.bootstrap.web.PiiMasker` queda como fachada `@Deprecated` que delega en `common.security.PiiMasker`; los tests legacy de `customer` no se rompen | `PiiMaskerCommonTest` + `customer.bootstrap.web.PiiMaskerTest` |
| AC-10 | `external-services/mongodb/init.js` crea los cuatro índices de `alerts`: `alert_id_uk`, `entity_acked_idx`, `acked_ttl` (30 d), `opened_ttl` (30 d) | Inspección manual del init |

## Validación

- `mvn verify` desde raíz: SUCCESS (3:44 min). 537 tests declarados.
- `mvn -pl it verify -Ddocker.available=true`: SUCCESS (4:40 min) con
  Testcontainers Mongo y ES.
- JaCoCo `check`: ≥ 75 % en `**/domain/**` (suelo medido).
- ArchUnit: 5 reglas en verde, incluido `DashboardIsolationTest` (3 reglas).
- `TestCountMatchesDocsTest`: 537 coincide con `docs/testing/TESTING.md` §1.

## Cambios

| Fecha | Cambio |
|---|---|
| 2026-09-22 | Alta de la feature (estado "Pendiente"). Andamiaje en `docs/features/UI-001/`. |
| 2026-09-23 | Pivot: el dashboard pasa de consumir las APIs REST del customer-app a leer directo Mongo+ES. Cierre del hito H-0..H-5 con `PiiMasker` promovido a `common.security` y fachada legacy en customer. |
| 2026-09-23 | **Cierre**: spec [`docs/sdd/customer/consulta-entidad-ui.md`](../../sdd/customer/consulta-entidad-ui.md) (AC-1..10, R-1..R-7, §10 con el resumen de los 7 commits). CHANGELOGs (raíz y `customer/`). GLOSSARY con entradas Dashboard UI, DashboardIsolationTest, KPI retardado de recuperación, MTTR técnico, PiiMasker (common.security), TTL Mongo (alert) y UI-001..UI-007. MEJORAS-Y-PROPUESTAS §"Panel de operación": UI-1/UI-2/UI-3/UI-7 ✅, UI-4 💡, UI-5 📋, UI-6 ❌ (descartado por Thymeleaf+HTMX). Registro MySQL `feature_evento` con acción `MODIFICACION` para el slug `consulta-entidad-ui`. Grafo `feature-execution-graph.html`. |
