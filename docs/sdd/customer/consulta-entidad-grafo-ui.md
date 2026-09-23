# Vista grafo del flujo de integración por entidad (UI-002)

<!--
Spec SDD de la feature UI-002.
- Subproyecto: customer (módulo Maven `dashboard-customer`).
- Estado: PAUSADA. No se ha implementado código. Ver §10.bis para reapertura.
- Fichero nombrado por la feature, no por la clase.
- Comportamiento observable; stack en ../../architecture/TECH.md.
-->

| | |
|---|---|
| **Dominio** | `customer` |
| **Estado** | ⏸ pausada |
| **Entradas** | Web con JWT de Keycloak (sin CDC ni Kafka). Tab nueva en `dashboard-customer/`, ruta `GET /customers/{id}/graph`. |
| **Destino SAP** | ninguno (consulta; no escribe) |
| **Última revisión** | 2026-09-23 |

## 1. Objetivo

Pestaña adicional en `dashboard-customer/` que pinta, server-side, un SVG
con el recorrido del **último ciclo** de sincronización de la entidad:
`Legacy → Debezium → Kafka → SyncCustomerUC → features → SAP`. Reutiliza
`lastCycle.steps` que ya devuelve `/customers/{id}/state`.

El operario que hoy abre tres herramientas distintas para reconstruir la
historia de un cliente (Mongo, Elasticsearch, Grafana) abre **una sola**
y ve, además del estado y el histórico, **cómo llegó hasta ahí**.

## 2. Alcance

**Dentro**:

- Nueva pestaña "Grafo" en la vista de entidad de `dashboard-customer/`.
- Endpoint `GET /customers/{id}/graph` que renderiza HTML con SVG inline.
- Generador de SVG en
  `dashboard-customer/src/main/java/.../application/grafo/`.
- Plantilla `dashboard-customer/src/main/resources/templates/graph.html`.
- SVG generado server-side con la skill `architecture-diagram` del repo
  de agentes (cero JS de cliente).
- Colores por estado final: verde (`SENT_SAP`), ámbar (`RETRY`/circuito
  abierto), rojo (`SAP_ERROR`), gris (`PENDING`/`VALID` sin envío).
- Bordes distintos para BTP vs S/4 nativo.
- Para una entidad sin histórico, vista con mensaje "no hay eventos
  aún" en vez de pantalla rota.

**Fuera**:

- Interactividad de cliente (drill-down al pulsar nodo) — se evalúa en
  una v2 con React Flow si hace falta; no en MVP.
- Línea de tiempo textual — la pinta UI-001 (timeline basada en el mismo
  `lastCycle.steps`).
- Historial de varios ciclos — el spec lo limita al **último** ciclo,
  porque es lo que devuelve `/state` sin sobrecargar el backend.

## 3. Entrada

Variables de entorno que tendría la feature, compartidas con UI-001:

| Variable | Tipo | Obligatorio | Notas |
|---|---|---|---|
| `MONGO_URL_CUSTOMER` | URI | sí | `mongodb://localhost:27017/customer`. Lee `customers_current` y `sync_state` (último `lastCycle.steps`). |
| `ES_URL` | URI | sí | `http://localhost:9200`. Lee `customers_history`. |
| `KEYCLOAK_ISSUER_URI` | URI | sí si `APP_SECURITY_ENABLED=true` | Resource server JWT; mismo realm que `customer-app`. |
| `OBS_ENV`, `OBS_CLUSTER` | string | no | Tags comunes Prometheus (OBS-005). |

Contrato común del mensaje de ingesta: no aplica — el grafo no ingiere.

## 4. Reglas de negocio

Las reglas se registran para futuro. **Ninguna puede verificarse hoy**
(ver §10.bis sobre el bloqueo B-1).

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | El módulo `dashboard-customer` **no importa** clases de `customer.application.*`, `customer.bootstrap.*` ni `customer.domain.*` (bounded context aparte). | Build falla: `DashboardIsolationTest` (ArchUnit, 3 reglas). |
| R-2 | Cada endpoint declara su rol con `@PreAuthorize`. | Build falla: `EndpointsDeclareAccessTest`. |
| R-3 | Para un JWT con rol `sap-external-read`, el SVG no expone PII (sin nombre, sin NIF, sin dirección en los rótulos). | Render con `piiMasked=true` o sin nombre del cliente en `<title>`. |
| R-4 | El SVG es válido (`xmllint --noout`), con `<title>` y `<desc>` que describen el flujo en lenguaje natural. | Test del generador. |
| R-5 | El SVG no incluye JavaScript de cliente (inspección del HTML: `<script>` count = 0). | Test del HTML generado. |
| R-6 | Las series Prometheus que emita el grafo (`http_server_requests_seconds{...,uri=…}`) llevan `uri` **aplanado** a su template (`/customers/{id}/graph`), **nunca** la ruta interpolada con el id real. | Cardinalidad sin cota; riesgo en Prometheus (ver §8). |
| R-7 | Para una entidad sin `lastCycle`, la vista dice "no hay eventos aún" en vez de pantalla rota. | Test del caso vacío. |
| R-8 | Colores del SVG por estado final del último ciclo: verde (`SENT_SAP`), ámbar (`RETRY`/circuito abierto), rojo (`SAP_ERROR`), gris (`PENDING`/`VALID` sin envío). | Test del mapeo estado→color. |

## 5. Salida

El grafo **no escribe a SAP** ni al legacy. Salida observable:

- Vista Thymeleaf servida en HTTP 200: `graph.html`.
- Endpoint documentado en `dashboard-customer/src/main/resources/openapi.yml`.

| Endpoint | Salida | Notas |
|---|---|---|
| `GET /customers/{id}/graph` | HTML con SVG inline, nodos coloreados por estado | `sap-read` y superiores; `sap-external-read` con rótulos sin PII. |
| `GET /actuator/prometheus` | Texto en formato Prometheus | La serie `http_server_requests_seconds` aparece con `uri="/customers/{id}/graph"` (template), **no** con la ruta interpolada (R-6). |

No hay contrato SAP: el grafo no envía datos a SAP.

## 6. Estados y errores

La feature no entra en la máquina de estados de sincronización
([sdd/common/maquina-de-estados.md](common/maquina-de-estados.md)): es
solo lectura sobre el resultado. Los errores que puede encontrar:

| Situación | Respuesta | Reintentable |
|---|---|---|
| Cliente sin snapshot en `customers_current` | 404 con mensaje "Sin imagen actual" | — |
| Cliente sin `lastCycle` (entidad nunca sincronizada) | 200 con mensaje "no hay eventos aún" (no pantalla rota) | — |
| Mongo no alcanzable | `5xx` con motivo, métrica `dashboard_mongo_errors_total` | sí |
| PII solicitada por `sap-external-read` con rótulos sin enmascarar | 500 con log de auditoría (no debe ocurrir si R-3 se cumple) | — |

## 7. Criterios de aceptación

Cada AC está **bloqueado por B-1** (aplanamiento del tag `uri` en
Prometheus no implementado; ver §8 y §10.bis). La implementación no
puede arrancar hasta que se cierre el bloqueo. Si se reabre la feature
sin cerrar B-1, las métricas del grafo expondrían cardinalidad alta
hasta el punto de romper la operativa de Prometheus.

| AC | Criterio | Estado | Bloqueo |
|---|---|---|---|
| AC-1 | `GET /customers/CUST-001/graph` devuelve HTML con SVG inline. | 🔴 bloqueado | B-1 |
| AC-2 | El SVG es válido (`xmllint --noout`) y tiene `<title>` y `<desc>`. | 🔴 bloqueado | B-1 |
| AC-3 | Los colores reflejan el estado final (verde/ámbar/rojo/gris). | 🔴 bloqueado | B-1 |
| AC-4 | Para una entidad sin histórico, la vista dice "no hay eventos aún" en vez de pantalla rota. | 🔴 bloqueado | B-1 |
| AC-5 | Sin JavaScript de cliente (inspeccionar la página: `<script>` count = 0). | 🔴 bloqueado | B-1 |
| AC-6 | Las series Prometheus del endpoint `GET /customers/{id}/graph` exponen `uri="/customers/{id}/graph"` (template), **no** la ruta interpolada con el id real. | 🔴 bloqueado | B-1 |
| AC-7 | `mvn verify` en verde. | 🔴 bloqueado | B-1 |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

**Cardinalidad Prometheus con `uri` no aplanado.**

La métrica estándar de Spring Boot Actuator
(`http_server_requests_seconds`) etiqueta cada petición HTTP con un tag
`uri` cuyo **default** es la ruta interpolada — p.ej.
`uri="/customers/CUST-001/graph"` por cada cliente distinto. Con N
clientes, N series. Multiplicado por los endpoints de UI-001
(`/customers/{id}`, `/customers/{id}/history`, etc.) y los del grafo, la
cardinalidad explota y rompe Prometheus (límite típico: pocos miles de
series activas).

El stack actual del proyecto **no aplana** ese tag: ni `customer-app`,
ni `article-app`, ni `dashboard-customer` tienen un
`WebMvcTagsContributor` que reescriba `uri` a su template
(`/customers/{id}/graph`, no `/customers/CUST-001/graph`). Esto choca
con R-5/R-6 de UI-001 (cardinalidad acotada en KPIs) y con el
funcionamiento de cualquier endpoint parametrizado de las tres apps.

**Bloqueo B-1**: hasta que exista ese `WebMvcTagsContributor` en
`common` con un test explícito en las tres apps que verifique
`uri="/customers/{id}/graph"` en `/actuator/prometheus` (no la ruta
interpolada), no se puede implementar UI-002 sin romper la operativa
de Prometheus. Detalle en §10.bis.

## 9. Trazabilidad spec ↔ código

Vacía. La feature está pausada antes de Fase 3 (implementación). No
hay código de producción, ni de test, que respalde este spec.

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-23 | Spec inicial redactado. Caso de negocio cerrado (MoSCoW F-1..F-12). 3 KPIs propuestos (KPI-6 diagnóstico, KPI-7 latencia, KPI-8 adopción). | — |
| 2026-09-23 | Feature **pausada** por dictamen 🟡 de `auditor-business`. Bloqueo **B-1**: el `WebMvcTagsContributor` que aplane `uri` a su template **no existe** en `customer-app` / `article-app` / `dashboard-customer`. Las métricas Prometheus de UI-002 expondrían cardinalidad alta hasta que se solucione. Reapertura condicional en §10.bis. | — |

### 10.bis Condiciones de reapertura

La feature se reabre cuando se cierren las tres condiciones siguientes,
**en este orden**:

1. **B-1 cerrado**: `WebMvcTagsContributor` en `common` que aplane el
   tag `uri` a su template, con un test explícito en cada una de las
   tres apps (`customer-app`, `article-app`, `dashboard-customer`) que
   verifique que `/actuator/prometheus` expone
   `http_server_requests_seconds{...,uri="/customers/{id}/graph"}`
   (template), **no** la ruta interpolada con el id real.

2. **Auditoría independiente** (`auditor-business`) del nuevo cuadro de
   KPIs reformulado con el aplanamiento verificado. El cuadro original
   proponía 3 KPIs (KPI-6 diagnóstico, KPI-7 latencia, KPI-8 adopción);
   KPI-6 fue ❌ no apto y KPI-7/KPI-8 ⚠️ con reservas materiales. Sin
   esta auditoría, el cuadro no es firme.

3. **Al menos 1 sprint de backlog dedicado a UI-002** (no como
   side-effect de otro trabajo). La feature ha de entrar al backlog
   activo con su propio sprint y sus criterios de aceptación listos
   para TDD.

> **Nota sobre el registro SDD**: la fila de UI-002 **no se inserta** en
> la tabla `feature` de `sdd_registry` mientras esté pausada. Insertarla
> como `ALTA` crearía una fila fantasma: el spec existe en disco pero
> la feature no está ni en `en_diseno` ni en `solicitada` — está en
> pausa, sin entrada al backlog. `sdd-registry-check.py` puede quejarse
> de que `docs/sdd/customer/consulta-entidad-grafo-ui.md` existe en
> disco sin fila correspondiente. **Esa queja es esperada** mientras
> UI-002 siga pausada y se resuelve al reabrir la feature (insertar la
> fila `feature` + `feature_evento(ALTA)`).