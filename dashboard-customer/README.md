# dashboard-customer — panel de operación del dominio customer

> **Placeholder**. Módulo Maven **no creado todavía**: se materializa con
> la feature [UI-001](../docs/features/UI-001/) (panel de operación del
> dominio customer).

## Resumen previsto (UI-001 + UI-002)

App Spring Boot (puerto 8091). Sirve la UI web de consulta de entidades
y subentidades, **en lectura**. **Sin escrituras**: no expone
`/customers/sync` ni `/customers/validate`; consume las APIs REST de
[`customer`](../customer) con un cliente autenticado por Keycloak.

Features que pinta:

- **UI-001** — vista por entidad, histórico y búsqueda.
  Spec: [`docs/sdd/customer/consulta-entidad-ui.md`](../docs/sdd/customer/consulta-entidad-ui.md).
- **UI-002** — vista grafo del flujo de integración.
  Spec: [`docs/sdd/customer/consulta-entidad-grafo-ui.md`](../docs/sdd/customer/consulta-entidad-grafo-ui.md).

## Stack previsto

Thymeleaf + HTMX + Alpine.js; SVG server-side para el grafo (cero JS
de cliente en la vista grafo).

## Auth

Mismo Keycloak que las APIs; el cliente usa `client_credentials` con un
`ServiceAccount` propio (`dashboard-customer`). Roles leídos:
`sap-read`, `sap-external-read` (la PII se enmascara en la API).

## Cómo se ejecuta

Pendiente de UI-001. Cuando exista, el quickstart específico estará en
[`docs/features/UI-001/quickstart.md`](../docs/features/UI-001/quickstart.md).