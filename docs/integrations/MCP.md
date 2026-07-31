# MCP — Servidor de consulta para agentes IA (PROPUESTA — no implementado)

> **Estado**: propuesta a futuro. Nada de este documento existe en el código.
> Recoge la decisión de diseño y los prerrequisitos para no improvisarlos
> cuando llegue el momento.

## 1. Motivación

Casi todo el valor consultable de la plataforma ya existe y está estructurado:

| Fuente | Qué responde |
|---|---|
| Máquina de estados (Mongo, `sync_state`) | "¿en qué estado está el cliente X?", "¿qué entidades están en `SAP_ERROR`?" |
| Histórico ELK + diff (`GET /{dominio}/{id}/history[/diff]`) | "¿qué cambió en el último envío a SAP?" |
| Imagen staging (Mongo `*_current`) | "¿qué dato vigente tenemos de X?" |
| Métricas (`/actuator/prometheus`) | contadores y latencias por estado y dominio |
| DLT Kafka (`outbox.*.DLT`) | mensajes descartados y su motivo |

Hoy responder "¿por qué este cliente no está en SAP?" exige mirar Mongo,
Kibana y logs. Un servidor **MCP (Model Context Protocol)** delante de las
APIs REST permite que cualquier agente IA (Claude, chatbot interno, n8n)
responda encadenando 2-3 consultas — sin acoplar ningún chatbot concreto a la
plataforma.

## 2. Encaje arquitectónico

- **Servicio separado** (`mcp-server`), NO dentro de las apps de dominio.
  Es un adaptador de entrada más, coherente con la arquitectura hexagonal, y
  mantiene la independencia de despliegue de `customer-app`/`article-app`.
- **Envoltorio fino sobre las APIs REST existentes**: el MCP no accede a
  Mongo/ES/Kafka directamente. Consume los mismos endpoints que cualquier otro
  cliente. Sin lógica de negocio propia.
- **Stack sugerido**: Spring AI trae starter de servidor MCP (encaja con el
  equipo); alternativa: servicio ligero TypeScript con el SDK oficial de MCP.

## 3. Herramientas propuestas

### Fase 1 — solo lectura

| Tool | Fuente REST | Descripción |
|---|---|---|
| `get_sync_state(domain, entityId)` | estado actual + historial de transiciones | estado y trazabilidad de una entidad |
| `get_history(domain, entityId, full?)` | `GET /{dominio}/{id}/history` | versiones enviadas a SAP (hash + timestamp) |
| `get_diff(domain, entityId, from?, to?)` | `GET /{dominio}/{id}/history/diff` | qué campos cambiaron entre dos versiones |
| `search_errors(domain, state?)` | consulta por estado (`SAP_ERROR`, `INVALID`, `COMMUNICATION_ERROR`) | entidades atascadas |
| `get_metrics(domain?)` | `/actuator/prometheus` (filtrado) | volumetría y salud del pipeline |

> Nota: `get_sync_state` y `search_errors` requieren exponer antes por REST la
> consulta de estado (`SyncStateRepositoryPort.currentState/history` ya lo
> soportan; falta el controller).

### Fase 2 — mutación (solo con autorización explícita)

| Tool | Descripción | Guardarraíl |
|---|---|---|
| `resync(domain, entityId)` | relanza el pipeline de una entidad | confirmación humana / rol específico |
| `retry_feature(entityId, feature)` | reintenta solo la feature en `SAP_ERROR` | ídem; depende del reprocesador (pendiente) |

## 4. Prerrequisito 1 — Autenticación (BLOQUEANTE)

Las APIs REST de las apps hoy **no tienen autenticación**. Antes de poner un
MCP delante (una puerta para agentes) es obligatorio:

1. **Autenticación en las apps de dominio**: Spring Security con API key o
   OAuth2 client-credentials (resource server). Mínimo viable: API key por
   header con rotación; deseable: OAuth2 contra el IdP corporativo.
2. **Credenciales propias y de solo lectura para el MCP** en fase 1: un
   cliente/rol `mcp-readonly` que solo alcance los endpoints GET. Las tools de
   fase 2 exigirían un rol distinto y auditoría de quién invocó qué.
3. **Autenticación también en el propio servidor MCP** (token de acceso por
   cliente MCP), y transporte TLS si sale del localhost.
4. **Auditoría**: log estructurado por tool invocada (quién, qué entidad,
   cuándo) — imprescindible cuando el consumidor es un agente autónomo.

## 5. Prerrequisito 2 — Ofuscación de datos sensibles (BLOQUEANTE)

Los snapshots del histórico y la imagen staging llevan PII y datos bancarios:
IBAN, BIC, NIF/CIF (`taxId`, `vatNumber`), email, teléfono, dirección postal.
Un agente IA no debe recibirlos en claro por defecto.

Política propuesta (en el MCP, no en las apps — la plataforma sigue necesitando
el dato íntegro para sincronizar con SAP):

| Campo | Máscara por defecto | Ejemplo |
|---|---|---|
| `banking.iban` | primeros 4 + últimos 4 | `ES91········1332` |
| `banking.bic` | completo oculto | `····` |
| `fiscal.taxId`, `fiscal.vatNumber` | últimos 3 visibles | `·····678` |
| `contact.email` | inicial + dominio | `i···@acme.com` |
| `contact.phone` / `fax` | últimos 3 visibles | `+34 ··· ··· 222` |
| `address.street` | oculto (ciudad/país visibles) | — |

- La máscara se aplica **en la capa MCP** al serializar la respuesta de las
  tools (`get_history full`, `get_diff` — el diff puede revelar el dato en
  `before`/`after`: se enmascaran ambos lados).
- Un rol elevado (`mcp-pii`) podría recibir el dato en claro para casos de
  soporte justificados, siempre auditado.
- Los identificadores de negocio (`entityId`, `code`, `payloadHash`, estados,
  timestamps) se devuelven en claro: son la clave del diagnóstico y no son PII.

## 6. Orden recomendado

1. Reprocesador de `SAP_ERROR` y validación contra tenant real (prioridad
   actual de la plataforma — el MCP no la adelanta).
2. Autenticación en las APIs REST (§4.1) — vale por sí misma, sin MCP.
3. Endpoint REST de consulta de estado de sincronización (§3, nota).
4. Servidor MCP fase 1 (solo lectura + máscaras §5).
5. Fase 2 (mutación) solo cuando exista el reprocesador y la auditoría.

## 7. Referencias

- Model Context Protocol: https://modelcontextprotocol.io
- Spring AI MCP Server: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html
- Endpoints actuales: `docs/architecture/FLOWS.md`, controllers en
  `customer/.../bootstrap/web/` y `article/.../bootstrap/web/`.
