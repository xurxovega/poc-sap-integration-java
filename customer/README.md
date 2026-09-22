# customer — dominio de cliente

App Spring Boot (puerto 8081). Sincroniza clientes legacy (SQL Server)
con SAP S/4 vía BTP u OData nativo.

## Pipeline (resumen)

`Kafka outbox.CUSTOMER` (CDC por Debezium) o `POST /customers/sync` (REST)
→ `SyncCustomerUseCase` → legacy → validación → imagen Mongo → histórico
ES → envío por feature a SAP. Detalle por feature en
[`docs/sdd/customer/`](../docs/sdd/customer/) y flujo con clases en
[`docs/architecture/FLOWS.md`](../docs/architecture/FLOWS.md).

## Features

| Feature | Spec | Estado |
|---|---|---|
| Sincronización del cliente (agregado) | [sincronizacion-cliente.md](../docs/sdd/customer/sincronizacion-cliente.md) | ✅ |
| Sincronización de dirección | [sincronizacion-direccion.md](../docs/sdd/customer/sincronizacion-direccion.md) | ✅ |
| Sincronización de datos fiscales | [sincronizacion-datos-fiscales.md](../docs/sdd/customer/sincronizacion-datos-fiscales.md) | ✅ |
| Sincronización de datos de contacto | [sincronizacion-contacto.md](../docs/sdd/customer/sincronizacion-contacto.md) | ⚠️ |
| Sincronización de datos bancarios | [sincronizacion-datos-bancarios.md](../docs/sdd/customer/sincronizacion-datos-bancarios.md) | ⚠️ |
| Baja de cliente | [baja-cliente.md](../docs/sdd/customer/baja-cliente.md) | ✅ |
| Baja de mandato SEPA | [baja-mandato-sepa.md](../docs/sdd/customer/baja-mandato-sepa.md) | ⚠️ |

Estado completo y brechas:
[`docs/sdd/README.md`](../docs/sdd/README.md) §5 y §6.

## Cómo trabajar en este módulo

- Spec primero: cada cambio de comportamiento **empieza** por el spec de su
  feature, usando la plantilla
  [`docs/sdd/_template/feature.md`](../docs/sdd/_template/feature.md).
- Tests en rojo antes que código (TDD,
  [`docs/architecture/DESARROLLO.md`](../docs/architecture/DESARROLLO.md) §2).
- Capas: `bootstrap → adapters → application → domain`. **Sin Spring en
  `domain`**.
- Mocks sobre los **puertos** (`domain/port/`), nunca sobre los
  adaptadores.
- Cobertura `**/domain/**` ≥ 75 % (JaCoCo `check`); medido 2026-09-12:
  78 %.

## Endpoints REST

Contrato completo en
[`src/main/resources/openapi.yml`](src/main/resources/openapi.yml) (se
importa en Postman/Bruno/Swagger UI; `OpenApiMatchesControllersTest` rompe
el build si contrato y controladores divergen):

| Endpoint | Rol mínimo |
|---|---|
| `POST /customers/sync` | `sap-write` |
| `POST /customers/validate` | `sap-write` |
| `GET /customers/{id}/history[?full=true]` | `sap-read` o `sap-external-read` (enmascarado) |
| `GET /customers/{id}/history/diff` | `sap-read` |
| `GET /customers/{id}/state` | `sap-read` o `sap-external-read` (enmascarado) |

Cada endpoint declara el rol con `@PreAuthorize`; el test
`EndpointsDeclareAccessTest` rompe el build si falta. Spec completo en
[`docs/sdd/common/seguridad-api.md`](../docs/sdd/common/seguridad-api.md).

## Arranque local

Con [`external-services/`](../external-services/) levantado y
[`scripts/env/local.env`](../scripts/env/local.env) cargado:

```bash
mvn -pl customer spring-boot:run
# API:          http://localhost:8081/customers
# Health:       http://localhost:8081/actuator/health
# Prometheus:   http://localhost:8081/actuator/prometheus
```

## Ver también

- [`docs/architecture/OVERVIEW.md`](../docs/architecture/OVERVIEW.md) §2
  (módulos) y §3 (flujos).
- [`docs/features/UI-001/`](../docs/features/UI-001/) y
  [`docs/features/UI-002/`](../docs/features/UI-002/) — el dashboard que
  consume los endpoints REST de este módulo.