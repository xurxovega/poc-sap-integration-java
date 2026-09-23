# Feature: UI-002 — Vista grafo del flujo de integración por entidad

## Objetivo

Pestaña adicional en `dashboard-customer/` que pinta, server-side, un SVG
con el recorrido del último ciclo de sincronización de la entidad:
`Legacy → Debezium → Kafka → SyncCustomerUC → features → SAP`. Reutiliza
`lastCycle.steps` que ya devuelve `/customers/{id}/state`.

## Estado

**⏸ Pausada 2026-09-23.** Caso de negocio y spec escritos; pendiente de
cerrar el bloqueo **B-1** (aplanamiento del tag `uri` en Prometheus en
las tres apps). Sin código de producción ni de test. Reapertura
condicional en el spec §10.bis.

## Alcance

**Dentro** (registrado para futuro):
- Spec en `docs/sdd/customer/consulta-entidad-grafo-ui.md` (redactado,
  estado ⏸ pausada).
- Generador de SVG en
  `dashboard-customer/src/main/java/.../application/grafo/`.
- Plantilla `dashboard-customer/src/main/resources/templates/graph.html`.
- Enlace en la pestaña "Grafo" de la vista de entidad.
- SVG generado server-side con la skill `architecture-diagram` del repo
  de agentes (cero JS de cliente).
- Colores por estado final: verde (`SENT_SAP`), ámbar (`RETRY`/circuito
  abierto), rojo (`SAP_ERROR`), gris (`PENDING`/`VALID` sin envío).
- Bordes distintos para BTP vs S/4 nativo.

**Fuera**:
- Interactividad de cliente (drill-down al pulsar nodo) — se evalúa en
  una v2 con React Flow si hace falta; no en MVP.
- Línea de tiempo textual — la pinta UI-001 (timeline basada en el mismo
  `lastCycle.steps`).
- Historial de varios ciclos — el spec lo limita al **último** ciclo,
  porque es lo que devuelve `/state` sin sobrecargar el backend.

## Ficheros afectados**

**Ninguno.** La feature está pausada antes de Fase 3 (implementación).
El spec, los KPIs y el dictamen del auditor están en el spec §9/§10
([`consulta-entidad-grafo-ui.md`](../../sdd/customer/consulta-entidad-grafo-ui.md))
y en esta misma carpeta.

Las rutas previstas (a materializar cuando se cierre B-1):

| Tipo | Ruta |
|---|---|
| Spec (✅ redactado) | `docs/sdd/customer/consulta-entidad-grafo-ui.md` |
| Código nuevo | `dashboard-customer/src/main/java/.../application/grafo/` |
| Plantilla nueva | `dashboard-customer/src/main/resources/templates/graph.html` |
| Plantilla editada | `dashboard-customer/src/main/resources/templates/customer.html` |
| Docs | `dashboard-customer/README.md` (edición), `customer/README.md` (edición) |
| CHANGELOG | `CHANGELOG.md` raíz, `docs/sdd/customer/CHANGELOG.md` |
| Registro | `external-services/mysql/init.sql` (al reabrir: `ALTA` en `feature` + `feature_evento`) |

## Criterios de aceptación

Bloqueados por B-1 (no implementables hasta que se cierre el
aplanamiento del tag `uri` en Prometheus; ver spec §8 y §10.bis).
Detalle en [`docs/sdd/customer/consulta-entidad-grafo-ui.md`](../../sdd/customer/consulta-entidad-grafo-ui.md).

| AC | Criterio | Estado |
|---|---|---|
| AC-1 | `GET /customers/CUST-001/graph` devuelve HTML con SVG inline | 🔴 bloqueado |
| AC-2 | El SVG es válido (`xmllint --noout`) y tiene `<title>` y `<desc>` | 🔴 bloqueado |
| AC-3 | Los colores reflejan el estado final (verde/ámbar/rojo/gris) | 🔴 bloqueado |
| AC-4 | Para una entidad sin histórico, la vista dice "no hay eventos aún" en vez de pantalla rota | 🔴 bloqueado |
| AC-5 | Sin JavaScript de cliente (inspeccionar la página: `<script>` count = 0) | 🔴 bloqueado |
| AC-6 | Las series Prometheus del endpoint `GET /customers/{id}/graph` exponen `uri="/customers/{id}/graph"` (template), no la ruta interpolada | 🔴 bloqueado |
| AC-7 | `mvn verify` en verde | 🔴 bloqueado |

## Validación

No procede mientras la feature esté pausada. Cuando se cierre B-1:

- `mvn verify` con todos los tests en verde.
- Inspección manual del SVG con un lector de pantalla (accesibilidad).
- Smoke E2E: `start-all.sh --with-cdc`, provocar UPDATE en SQL Server,
  visitar la pestaña "Grafo" del dashboard.
- Verificación explícita de la métrica
  `http_server_requests_seconds{application="dashboard-customer",uri="/customers/{id}/graph",...}`
  en `/actuator/prometheus` (AC-6).

## Cambios

| Fecha | Cambio |
|---|---|
| 2026-09-22 | Alta de la feature (estado "Pendiente"). Andamiaje en `docs/features/UI-002/`. |
| 2026-09-23 | **Pausada** por dictamen 🟡 de `auditor-business`. Bloqueo B-1: `WebMvcTagsContributor` que aplane `uri` a su template no implementado en `customer-app` / `article-app` / `dashboard-customer`. KPI-6 ❌ no apto (la métrica propuesta no existe en el stack); KPI-7/KPI-8 ⚠️ con reservas materiales (cardinalidad `uri` no aplanada, choca con R-5/R-6 de UI-001). Reapertura condicional cuando B-1 cierre (ver spec §10.bis). |