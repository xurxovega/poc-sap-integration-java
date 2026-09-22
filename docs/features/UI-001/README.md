# Feature: UI-001 — Dashboard web: vista por entidad y búsqueda

## Objetivo

Una web estática por dominio (`dashboard-customer/`, `dashboard-article/`)
que consume las APIs REST existentes (`/customers/{id}/state`,
`/customers/{id}/history`, `/customers/{id}/history/diff`) y muestra:
vista por entidad (UI-1), histórico (UI-2) y búsqueda por clave de dominio
(UI-3). **Sólo lectura**: las acciones administrativas (UI-4) requieren
OPS-2 (reproceso DLT) que aún no existe.

## Estado

Pendiente desde 2026-09-22.

## Alcance

**Dentro**:
- Spec nuevo en `docs/sdd/customer/consulta-entidad-ui.md`.
- Módulo Maven nuevo `dashboard-customer/` (puerto 8091).
- Stack: Thymeleaf + HTMX + Alpine.js (justificación en el spec).
- Auth: Keycloak con `ServiceAccount` `dashboard-customer` y roles
  `sap-read`, `sap-external-read`.
- Sin acciones destructivas: no expone `/customers/sync` ni
  `/customers/validate`; consume las APIs de `customer-app` con un cliente
  autenticado.
- Contrato OpenAPI del dashboard en `dashboard-customer/src/main/resources/openapi.yml`.
- Banner ASCII en `dashboard-customer/src/main/resources/banner.txt`.

**Fuera** (espera a otras features):
- UI-002 (vista grafo) — feature separada, se ejecuta después.
- UI-4 (acciones administrativas) — depende de OPS-2.
- `dashboard-article/` — gemelo cuando se aborde el dominio article.
- Acciones destructivas (re-sync forzoso, reproceso DLT) — no entran.

## Ficheros afectados

| Tipo | Ruta |
|---|---|
| Spec (NUEVO) | `docs/sdd/customer/consulta-entidad-ui.md` |
| Backlog | `docs/MEJORAS-Y-PROPUESTAS.md` (UI-1, UI-2, UI-3) |
| Reactor | `pom.xml` (añadir `<module>dashboard-customer</module>`) |
| Módulo nuevo | `dashboard-customer/` |
| Docs raíz | `README.md`, `customer/README.md`, `dashboard-customer/README.md` |
| Glosario | `docs/GLOSSARY.md` |
| CHANGELOG | `CHANGELOG.md` raíz, `docs/sdd/customer/CHANGELOG.md` |
| Registro | `external-services/mysql/init.sql` |

## Criterios de aceptación

| AC | Criterio | Verificación |
|---|---|---|
| AC-1 | `GET /customers/CUST-001` devuelve cabecera + tabs + contenido en <500 ms con datos de seed | slice web + integration |
| AC-2 | Histórico enmascarado para `sap-external-read` (`masked: true` en la respuesta) | test del controller con token `external-read` |
| AC-3 | Búsqueda por `fiscal.taxId` devuelve resultados correctos | integration con WireMock + ES de seed |
| AC-4 | `dashboard-customer` no importa `customer.application` ni `customer.bootstrap` (ArchUnit) | nueva regla ArchUnit |
| AC-5 | Contract test (Spring Cloud Contract) sobre el endpoint interno que llama a `/customers/{id}/state` | test en `dashboard-customer/src/test/...` |
| AC-6 | `mvn verify` en verde | build |
| AC-7 | `python scripts/sdd-registry-check.py` sin diferencias | script |

## Validación

- `mvn verify` con la nueva regla ArchUnit en verde.
- Spring Cloud Contract genera el stub; el test del dashboard contra el stub.
- Smoke manual: `start-all.sh` + arrancar `dashboard-customer` y visitar
  `http://localhost:8091`.

## Cambios

| Fecha | Cambio |
|---|---|
| 2026-09-22 | Alta de la feature (estado "Pendiente"). Andamiaje en `docs/features/UI-001/`. |