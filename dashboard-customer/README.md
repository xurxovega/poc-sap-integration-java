# dashboard-customer — panel de operación del dominio customer

App Spring Boot 4.1 (puerto **8091**). Sirve la UI web de consulta de
entidades y subentidades del dominio `customer`, **en lectura**. Lee
directamente de Mongo y Elasticsearch del bounded context; **no consume
las APIs REST del `customer-app`** — el aislamiento entre bounded
contexts lo vigila `DashboardIsolationTest` con 3 reglas ArchUnit.

## Pipeline (resumen)

```
Navegador del operario (HTML)
  │
  ▼
Thymeleaf + HTMX + Alpine.js    (plantillas server-side, sin build JS)
  │
  ▼
DashboardController / AlertController    @PreAuthorize por rol
  │                  │
  │                  └─ MongoAlertRepository        (ack de alertas, sap-write)
  ▼
Use cases de aplicación (AcknowledgeAlert, GetCustomerOverview, GetHistory,
GetHistoryDiff, GetOpenAlerts, SearchCustomers)
  │
  ▼
Puertos de dominio: CustomerImageReader, CustomerStateReader,
CustomerHistoryReader, CustomerSearcher, AlertRepository
  │
  ▼
Adaptadores: MongoCustomerImageReader / MongoCustomerStateReader /
MongoCustomerSearcher / ElasticsearchCustomerHistoryReader / MongoAlertRepository
  │
  ▼
Mongo (customers_current, sync_state, alerts) + Elasticsearch (customers_history)
```

Detalle por endpoint en
[`docs/sdd/customer/consulta-entidad-ui.md`](../docs/sdd/customer/consulta-entidad-ui.md)
y arquitectura del módulo en
[`docs/architecture/OVERVIEW.md`](../docs/architecture/OVERVIEW.md).

## Features

| Feature | Spec | Estado |
|---|---|---|
| UI-001 — vista por entidad, histórico y búsqueda | [consulta-entidad-ui.md](../docs/sdd/customer/consulta-entidad-ui.md) | ✅ |
| UI-002 — vista grafo del flujo de integración | (pendiente) | 🔮 |

Estado completo y brechas:
[`docs/sdd/README.md`](../docs/sdd/README.md) §5 y §6.

## Cómo trabajar en este módulo

- Spec primero: cada cambio de comportamiento **empieza** por el spec de su
  feature, usando la plantilla
  [`docs/sdd/_template/feature.md`](../docs/sdd/_template/feature.md).
- Tests en rojo antes que código (TDD,
  [`docs/architecture/DESARROLLO.md`](../docs/architecture/DESARROLLO.md) §2).
- Capas: `bootstrap → adapters → application → domain`. **Sin Spring en
  `domain`** (lo vigila `DomainPurityTest`).
- **Aislamiento entre bounded contexts**: este módulo **no importa** clases
  de `customer.application.*`, `customer.bootstrap.*` ni `customer.domain.*`.
  Lo vigila `DashboardIsolationTest`. Si necesitas un tipo del `customer-app`,
  reimplementa lo necesario en `com.poc.sap.dashboard.customer.*`.
- Mocks sobre los **puertos** (`domain/port/`), nunca sobre los
  adaptadores.
- Cobertura `**/domain/**` ≥ 75 % (JaCoCo `check`); medido 2026-09-23.

## Endpoints REST

Contrato completo en
[`src/main/resources/openapi.yml`](src/main/resources/openapi.yml) (se
importa en Postman/Bruno/Swagger UI; `OpenApiMatchesControllersTest` rompe
el build si contrato y controladores divergen):

| Endpoint | Rol mínimo |
|---|---|
| `GET /customers/search?q=...` | `sap-read` (y superiores) o `sap-external-read` (enmascarado) |
| `GET /customers/{id}` | `sap-read` o `sap-external-read` (enmascarado) |
| `GET /customers/{id}/history[?full=true]` | `sap-read` o `sap-external-read` (enmascarado) |
| `GET /customers/{id}/history/diff` | `sap-read` o `sap-external-read` (enmascarado) |
| `GET /customers/{id}/state` | `sap-read` o `sap-external-read` (enmascarado) |
| `GET /customers/alerts/open` | `sap-read` (y superiores) o `sap-external-read` (enmascarado) |
| `POST /customers/alerts/{id}/ack` | `sap-write` (y superiores) |
| `GET /actuator/prometheus` | abierto en local; admin en K8s (igual que el resto) |
| `GET /actuator/health` | abierto (estándar) |

Cada endpoint declara el rol con `@PreAuthorize`; el test
`EndpointsDeclareAccessTest` rompe el build si falta. Spec completo en
[`docs/sdd/common/seguridad-api.md`](../docs/sdd/common/seguridad-api.md).

## Arranque local

Con [`external-services/`](../external-services/) levantado y
[`scripts/env/local.env`](../scripts/env/local.env) cargado:

```bash
mvn -pl dashboard-customer spring-boot:run
# UI:           http://localhost:8091/customers
# Health:       http://localhost:8091/actuator/health
# Prometheus:   http://localhost:8091/actuator/prometheus
```

Quickstart detallado y verificación de AC-1..AC-10 en
[`docs/features/UI-001/quickstart.md`](../docs/features/UI-001/quickstart.md).

## Ver también

- [`docs/architecture/OVERVIEW.md`](../docs/architecture/OVERVIEW.md) §2
  (módulos).
- [`docs/sdd/customer/consulta-entidad-ui.md`](../docs/sdd/customer/consulta-entidad-ui.md) — spec.
- [`docs/features/UI-001/`](../docs/features/UI-001/) — README, quickstart
  y grafo de ejecución del cierre.
