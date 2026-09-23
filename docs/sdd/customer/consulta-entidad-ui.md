# Dashboard web de consulta de entidad y búsqueda (UI-001)

<!--
Spec SDD de la feature UI-001.
- Subproyecto: customer (módulo Maven `dashboard-customer`).
- Fichero nombrado por la feature, no por la clase.
- Comportamiento observable; stack en ../../architecture/TECH.md.
-->

| | |
|---|---|
| **Dominio** | `customer` |
| **Estado** | ✅ implementado |
| **Entradas** | Web con JWT de Keycloak (sin CDC ni Kafka). La URL del dashboard es `/customers/...` (puerto **8091**). |
| **Destino SAP** | ninguno (consulta; no escribe) |
| **Última revisión** | 2026-09-23 |

## 1. Objetivo

Web estática por dominio que muestra el estado de sincronización, el histórico
y la búsqueda por clave de negocio de un cliente, **sin pasar por las APIs
REST del `customer-app`**. El operario que hoy abre tres herramientas para
reconstruir la historia de un cliente (Mongo, Elasticsearch, Grafana) abre
**una sola**; además, el botón "Reconocer" sobre una alerta operativa
(`POST /customers/alerts/{id}/ack`) queda registrado en la colección
`alerts` con el rol y el nombre del operario.

## 2. Alcance

**Dentro**:

- Módulo Maven nuevo `dashboard-customer/`, app Spring Boot 4.1 en puerto
  **8091**. Stack: Thymeleaf + HTMX + Alpine.js (sin SPA, sin build JS).
- **Lectura directa** de Mongo (`customers_current`, `sync_state`, `alerts`)
  y Elasticsearch (`customers_history`) con clientes nativos del JDK /
  Spring Data. **No** consume las APIs REST del `customer-app`: el
  aislamiento entre bounded contexts lo vigila `DashboardIsolationTest` con
  tres reglas ArchUnit (no importar `customer.application.*`,
  `customer.bootstrap.*` ni `customer.domain.*`).
- PII enmascarada vía `common.security.PiiMasker` (clase promovida a
  `common.security` en el H-1 de este PR; la antigua
  `customer.bootstrap.web.PiiMasker` queda como fachada `@Deprecated` que
  delega en la nueva).
- **F-9 promoted** — `POST /customers/alerts/{id}/ack` requiere `sap-write`
  (y superiores); persiste `ackedAt`/`ackedBy` en Mongo colección `alerts`
  con TTL 30 días.
- **F-12** — el dashboard emite sus **propias** métricas Prometheus en
  `/actuator/prometheus` con el tag `application="dashboard-customer"` y
  la serie `http_server_requests_seconds{application="dashboard-customer",...}`.
- Job de KPIs `KpiJob` (`@Scheduled`) que publica los gauges
  `business_kpi_mttr_seconds{window="7d"}` y
  `business_kpi_recovery_p95_seconds{cycle="last"}`, cardinalidad ≤ 2 series.
- Template Grafana `customer-pipeline.json` gana la variable
  `templating.list[name=entityId,type=textbox]` para que las consultas del
  panel filtren por `entityId`.

**Fuera**:

- Acciones destructivas (re-sync forzoso, reproceso DLT): pendientes de OPS-2
  (UI-4).
- `dashboard-article/`: otra feature, se aborda cuando llegue UI-005.
- UI-002 vista grafo del flujo: otra feature.
- Ingesta desde el panel: el dashboard es solo lectura + ack de alertas.

## 3. Entrada

El dashboard no ingiere por CDC ni por Kafka: es un portal web de consulta.
Las variables de entorno que espera son las del shared kernel (`common`) +
las suyas propias para los índices y el refresco de KPIs:

| Variable | Tipo | Obligatorio | Notas |
|---|---|---|---|
| `MONGO_URL_CUSTOMER` | URI | sí | `mongodb://localhost:27017/customer`. Compartida con `customer-app`: ambos leen `customers_current`, `sync_state` y la nueva colección `alerts`. |
| `ES_URL` | URI | sí | `http://localhost:9200`. Lee `customers_history`. |
| `KEYCLOAK_ISSUER_URI` | URI | sí si `APP_SECURITY_ENABLED=true` | Resource server JWT; mismo realm que `customer-app`. |
| `GRAFANA_BASE_URL` | URI | sí | Enlace "Abrir en Grafana" desde la cabecera; el panel añade `?var-entityId=<id>`. |
| `APP_SECURITY_ENABLED` | bool | no | Default `true`; con `false` el dashboard arranca sin Keycloak para dev local. |
| `DASHBOARD_KPI_REFRESH_SECONDS` | int | no | Default `300`. Periodo del job de KPIs. |
| `DASHBOARD_ALERTS_TTL_DAYS` | int | no | Default `30`. Solo informativo: el TTL real lo aplican los índices Mongo de `external-services/mongodb/init.js`. |
| `OBS_ENV`, `OBS_CLUSTER` | string | no | Tags comunes Prometheus (OBS-005). |

Contrato común del mensaje de ingesta: no aplica — el dashboard no ingiere.

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | El módulo `dashboard-customer` **no importa** clases de `customer.application.*`, `customer.bootstrap.*` ni `customer.domain.*` (bounded context aparte). | Build falla: `DashboardIsolationTest` (ArchUnit, 3 reglas). |
| R-2 | Cada endpoint declara su rol con `@PreAuthorize`. | Build falla: `EndpointsDeclareAccessTest` (ArchUnit, ya vigente en los demás módulos). |
| R-3 | Para un JWT con rol `sap-external-read`, la PII del snapshot (IBAN, NIF, email, teléfono) se enmascara con `common.security.PiiMasker`. | El response lleva `piiMasked=true` y los campos sensibles con asteriscos. |
| R-4 | `POST /customers/alerts/{id}/ack` requiere `sap-write` (o superior). Persiste `ackedAt` y `ackedBy` (formato `<rol>:<subject>`, p. ej. `sap-write:ana`) en `alerts` y respeta el TTL de 30 días. | `403` sin `sap-write`. Sin alerta: `404`. |
| R-5 | El dashboard emite métricas Prometheus **propias**: el tag `application="dashboard-customer"` aparece en cada serie HTTP y se exponen los gauges `business_kpi_*`. Cardinalidad de tags controlada (sin `entityId` como label: cardinalidad = número de clientes). | Métricas mezcladas con `customer-app` o cardinalidad sin control. |
| R-6 | KPIs calculados con cardinalidad ≤ 2 series. Gauges: `business_kpi_mttr_seconds{window="7d"}` y `business_kpi_recovery_p95_seconds{cycle="last"}`. Sin tags por entidad. | Cardinalidad sin cota; riesgo en Prometheus. |
| R-7 | El response del dashboard nunca devuelve `Customer` del bounded context `customer`: serializa con su propio value object `CustomerSnapshot` para mantener R-1. | Acoplamiento entre bounded contexts. |

Las reglas R-1..R-7 se acumulan: una respuesta incorrecta debe salir
**enmascarada y sin acoplamiento**.

## 5. Salida

El dashboard **no escribe a SAP** ni al legacy. Salidas observables:

- Vistas Thymeleaf servidas en HTTP 200: `customer.html`, `history.html`,
  `diff.html`, `state.html`, `search.html`, `alerts.html`, `layout.html`.
- Endpoints REST documentados en `dashboard-customer/src/main/resources/openapi.yml`
  (OpenAPI 3.1). Coherencia con los controllers vigilada por
  `OpenApiMatchesControllersTest`.

| Endpoint | Salida | Notas |
|---|---|---|
| `GET /customers/search?q={q}` | HTML con tabla de resultados (10/página) | `q` se busca en `fiscal.taxId` (`customers_current`). |
| `GET /customers/{id}` | HTML cabecera + tabs (entidad, histórico, estado, alertas) | PII enmascarada para `sap-external-read`. |
| `GET /customers/{id}/history[?full=true]` | HTML con versiones indexadas en ES | Doc id `entityId-hash-epochMillis`. |
| `GET /customers/{id}/history/diff?from=&to=` | HTML diff campo a campo | Calcula diff entre dos snapshots. |
| `GET /customers/{id}/state` | HTML estado del agregado y de cada feature con `lastCycle` | Misma respuesta que `customer-app`. |
| `GET /customers/alerts/open` | HTML lista de alertas pendientes | `sap-read` y superiores. |
| `POST /customers/alerts/{id}/ack` | Redirige a `/customers/alerts/open` con flash | `sap-write` y superiores. |
| `GET /actuator/prometheus` | Texto en formato Prometheus | Incluye series del dashboard y gauges de KPIs. |
| `GET /actuator/health` | JSON estándar | |

No hay contrato SAP: el dashboard no envía datos a SAP.

## 6. Estados y errores

La feature no entra en la máquina de estados de sincronización (`sdd/common/maquina-de-estados.md`): es solo lectura sobre el resultado. Los errores que puede encontrar son:

| Situación | Respuesta | Reintentable |
|---|---|---|
| Cliente sin snapshot en `customers_current` | 404 con mensaje "Sin imagen actual" | — |
| Histórico vacío en ES para `{id}` | Pestaña "Histórico" muestra "Sin envíos todavía" | — |
| Mongo no alcanzable | `5xx` con motivo, métrica `dashboard_mongo_errors_total` | sí |
| Elasticsearch no alcanzable | `5xx` con motivo, métrica `dashboard_es_errors_total` | sí |
| `ack` de alerta inexistente | `404` | — |
| `ack` sin `sap-write` | `403` | — |
| Job de KPIs sin datos en ventana | Gauges emiten `NaN` o `0` | — |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | La página de detalle de un cliente (`GET /customers/{id}` con cliente sembrado en Mongo) renderiza en menos de 500 ms medido con `Timer.Sample` desde el slice web. | `DashboardControllerTest#entityPageRendersUnderBudget` |
| AC-2 | Un JWT con rol `sap-external-read` recibe el snapshot con la PII enmascarada y el flag `piiMasked=true` en el modelo Thymeleaf; un JWT con `sap-read` recibe la PII completa. | `DashboardControllerTest#externalReadGetsPiiMaskedInModel` + `DashboardControllerTest#sapReadGetsFullSnapshot` |
| AC-3 | La búsqueda por NIF (`GET /customers/search?q=A12345678`) devuelve resultados correctos ordenados por `code`, contra `customers_current` de Mongo. | `MongoCustomerSearcherIT` (IT con Testcontainers Mongo). |
| AC-4 | `DashboardIsolationTest` pasa las 3 reglas ArchUnit (no `customer.application.*`, no `customer.bootstrap.*`, no `customer.domain.*`). | `DashboardIsolationTest` |
| AC-5 | `/actuator/prometheus` incluye series con tag `application="dashboard-customer"` y el gauge `http_server_requests_seconds{application="dashboard-customer",...}`. | `DashboardEmitsPrometheusMetricsTest` (PrometheusMeterRegistry real). |
| AC-6 | `POST /customers/alerts/{id}/ack` con `sap-write` persiste `ackedAt`/`ackedBy` en Mongo `alerts`; con `sap-read` devuelve 403; la alerta inexistente devuelve 404. | `AlertControllerTest#ackPersistsAckedByUsingRoleAndSubject` + `MongoAlertRepositoryIT`. |
| AC-7 | `KpiJob` publica `business_kpi_mttr_seconds` y `business_kpi_recovery_p95_seconds` con cardinalidad ≤ 2 (solo tags `window` y `cycle`). | `KpiJobTest` (incluye IT Testcontainers Mongo). |
| AC-8 | El contrato `openapi.yml` del módulo refleja todas las rutas y roles de los controllers; `OpenApiMatchesControllersTest` rompe si divergen. | `OpenApiMatchesControllersTest`. |
| AC-9 | `customer.bootstrap.web.PiiMasker` queda como fachada `@Deprecated` que delega en `common.security.PiiMasker`; los tests legacy de `customer` no se rompen. | `PiiMaskerCommonTest` + `customer.bootstrap.web.PiiMaskerTest` (sin cambios). |
| AC-10 | `external-services/mongodb/init.js` crea los **cuatro** índices de `alerts`: `alert_id_uk` (único), `entity_acked_idx` (compuesto), `acked_ttl` (TTL 30 d), `opened_ttl` (TTL 30 d). | Inspección manual / `DeployObservabilityStructureTest` o test de init (espejo del fix `sync_state` del 2026-09-09). |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

- Métricas HTTP estándar de Spring Boot Actuator con el tag
  `application="dashboard-customer"` inyectado por
  `DashboardPrometheusProbeConfig` (`MeterFilter`).
- Job de KPIs (`bootstrap/observability/KpiJob`):
  - `business_kpi_mttr_seconds{window="7d"}`: tiempo medio entre la apertura
    de una alerta `sap.sync.alerts` y el próximo `SENT_SAP` para la misma
    `entityId`/`cycleId`, sobre la ventana de 7 días.
  - `business_kpi_recovery_p95_seconds{cycle="last"}`: percentil 95 del
    tiempo de recuperación del último ciclo cerrado en éxito.
- Logs estructurados ECS (los mismos `application-common.yml` que el resto del
  reactor): nivel INFO al servir una página, WARN al fallar, ERROR al
  fallar el job de KPIs.
- Trazas OTel apagadas por defecto (mismo criterio que el resto, ADR-0009);
  cuando `TRACING_ENABLED=true`, el cliente HTTP saliente queda trazado.

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| R-1 (aislamiento entre bounded contexts) | `dashboard-customer/src/test/.../DashboardIsolationTest.java` | `DashboardIsolationTest` |
| R-2 (`@PreAuthorize` en cada endpoint) | `EndpointsDeclareAccessTest` + `DashboardController`, `AlertController` | `EndpointsDeclareAccessTest` |
| R-3 (PII enmascarada) | `common/src/main/java/com/poc/sap/common/security/PiiMasker.java` (promovido en H-1) + `customer/src/main/java/com/poc/sap/customer/bootstrap/web/PiiMasker.java` (fachada `@Deprecated`) | `PiiMaskerCommonTest` + `customer.bootstrap.web.PiiMaskerTest` (legado) + `DashboardControllerTest#externalReadGetsPiiMaskedInModel` |
| R-4 (`POST /customers/alerts/{id}/ack`) | `bootstrap/web/AlertController#ack` + `application/AcknowledgeAlert` + `adapters/persistence/MongoAlertRepository` | `AlertControllerTest#ackPersistsAckedByUsingRoleAndSubject` + `MongoAlertRepositoryIT` |
| R-5 (métricas propias del dashboard) | `bootstrap/DashboardPrometheusProbeConfig` + `bootstrap/web/*` | `DashboardEmitsPrometheusMetricsTest` |
| R-6 (KPIs ≤ 2 series) | `bootstrap/observability/KpiJob` | `KpiJobTest` |
| §3 `MONGO_URL_CUSTOMER` / `ES_URL` | `bootstrap/MongoConfig`, `bootstrap/ElasticsearchConfig`, `application.yml` | contexto Spring levantado por smoke tests |
| §5 endpoints | `dashboard-customer/src/main/resources/openapi.yml` + `bootstrap/web/*` | `OpenApiMatchesControllersTest` |
| §5 plantillas | `src/main/resources/templates/*.html` | render en slice MVC |
| AC-10 índices `alerts` | `external-services/mongodb/init.js` | inspección manual / test de init |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-23 | Spec inicial. Módulo `dashboard-customer` (puerto 8091) con Thymeleaf + HTMX + Alpine.js. Lectura directa de Mongo y Elasticsearch (no consume las APIs REST del `customer-app`; el aislamiento lo vigila `DashboardIsolationTest` con 3 reglas ArchUnit). PII enmascarada con `common.security.PiiMasker` (promovido en este PR, fachada `@Deprecated` en `customer`). F-9 promoted: `POST /customers/alerts/{id}/ack` con `sap-write` y TTL 30 días sobre `alerts`. F-12: métricas Prometheus propias con tag `application="dashboard-customer"`. Job de KPIs `KpiJob` con cardinalidad ≤ 2 (`business_kpi_mttr_seconds{window="7d"}` y `business_kpi_recovery_p95_seconds{cycle="last"}`). Template Grafana `customer-pipeline.json` gana variable `entityId` (textbox). 7 commits (`d04b860` andamiaje + ArchUnit, `2622178` PiiMasker promoted, `9ec59d9` dominio + use cases, `b6fc4e5` adaptadores Mongo/ES + init.js, `3464e86` controllers Thymeleaf + Grafana var, `83b511b` job KPIs + gauges, `2df602c` docs), 80 tests nuevos (11 en `common`, 69 en `dashboard-customer`), 537 tests totales declarados. |
