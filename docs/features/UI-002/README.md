# Feature: UI-002 — Vista grafo del flujo de integración por entidad

## Objetivo

Pestaña adicional en `dashboard-customer/` que pinta, server-side, un SVG
con el recorrido del último ciclo de sincronización de la entidad:
`Legacy → Debezium → Kafka → SyncCustomerUC → features → SAP`. Reutiliza
`lastCycle.steps` que ya devuelve `/customers/{id}/state`.

## Estado

Pendiente desde 2026-09-22. **Se ejecuta después de UI-001**.

## Alcance

**Dentro**:
- Spec nuevo en `docs/sdd/customer/consulta-entidad-grafo-ui.md`.
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

## Ficheros afectados

| Tipo | Ruta |
|---|---|
| Spec (NUEVO) | `docs/sdd/customer/consulta-entidad-grafo-ui.md` |
| Código nuevo | `dashboard-customer/src/main/java/.../application/grafo/` |
| Plantilla nueva | `dashboard-customer/src/main/resources/templates/graph.html` |
| Plantilla editada | `dashboard-customer/src/main/resources/templates/customer.html` |
| Docs | `dashboard-customer/README.md` (edición), `customer/README.md` (edición) |
| CHANGELOG | `CHANGELOG.md` raíz, `docs/sdd/customer/CHANGELOG.md` |
| Registro | `external-services/mysql/init.sql` |

## Criterios de aceptación

| AC | Criterio | Verificación |
|---|---|---|
| AC-1 | `GET /customers/CUST-001/graph` devuelve HTML con SVG inline | slice web |
| AC-2 | El SVG es válido (`xmllint --noout`) y tiene `<title>` y `<desc>` | test del generador |
| AC-3 | Los colores reflejan el estado final (verde/ámbar/rojo/gris) | inspección visual + test de mapeo estado→color |
| AC-4 | Para una entidad sin histórico, la vista dice "no hay eventos aún" en vez de pantalla rota | test del caso vacío |
| AC-5 | Sin JavaScript de cliente (inspeccionar la página: `<script>` count = 0) | test del HTML generado |
| AC-6 | `mvn verify` en verde | build |

## Validación

- `mvn verify` con todos los tests en verde.
- Inspección manual del SVG con un lector de pantalla (accesibilidad).
- Smoke E2E: `start-all.sh --with-cdc`, provocar UPDATE en SQL Server,
  visitar la pestaña "Grafo" del dashboard.

## Cambios

| Fecha | Cambio |
|---|---|
| 2026-09-22 | Alta de la feature (estado "Pendiente"). Andamiaje en `docs/features/UI-002/`. |