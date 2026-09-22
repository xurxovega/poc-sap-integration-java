# article — dominio de artículo

App Spring Boot (puerto 8082). Sincroniza artículos legacy (PostgreSQL)
con SAP S/4 vía la API nativa de producto (`API_PRODUCT`).

## Pipeline (resumen)

`Kafka outbox.ARTICLE` (CDC por Debezium) o `POST /articles/sync` (REST)
→ `SyncArticleUseCase` → legacy → validación → imagen Mongo → histórico
ES → envío a SAP. Detalle en
[`docs/sdd/article/sincronizacion-articulo.md`](../docs/sdd/article/sincronizacion-articulo.md)
y flujo con clases en
[`docs/architecture/FLOWS.md`](../docs/architecture/FLOWS.md).

A diferencia de `customer`, este dominio **no tiene pipeline por feature**:
un único flujo sobre el agregado.

## Features

| Feature | Spec | Estado |
|---|---|---|
| Sincronización del artículo | [sincronizacion-articulo.md](../docs/sdd/article/sincronizacion-articulo.md) | ✅ |

## Cómo trabajar en este módulo

Igual que [`customer`](../customer/README.md): spec primero, TDD estricto,
capas `bootstrap → adapters → application → domain`, mocks sobre los
puertos, cobertura `**/domain/**` ≥ 75 % (medido 2026-09-12: 88 %).

## Endpoints REST

Contrato en [`src/main/resources/openapi.yml`](src/main/resources/openapi.yml):

| Endpoint | Rol mínimo |
|---|---|
| `POST /articles/sync` | `sap-write` |
| `GET /articles/{id}/history[?full=true]` | `sap-read` o `sap-external-read` |
| `GET /articles/{id}/history/diff` | `sap-read` |

`EndpointsDeclareAccessTest` vigila que cada endpoint declare su rol.

## Arranque local

```bash
mvn -pl article spring-boot:run
# API:          http://localhost:8082/articles
# Health:       http://localhost:8082/actuator/health
# Prometheus:   http://localhost:8082/actuator/prometheus
```

## Ver también

- [`docs/architecture/OVERVIEW.md`](../docs/architecture/OVERVIEW.md) §2 y §3.
- [`docs/MEJORAS-Y-PROPUESTAS.md`](../docs/MEJORAS-Y-PROPUESTAS.md) §Features
  — el gemelo para `dashboard-article` cuando se aborde.