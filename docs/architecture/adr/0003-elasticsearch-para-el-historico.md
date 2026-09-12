# ADR-0003 — Elasticsearch para el histórico de versiones enviadas

| | |
|---|---|
| **Estado** | ✅ aceptada (retroactiva: documenta una decisión ya tomada al construir el sistema) |
| **Fecha** | 2026-09-12 (decisión original: julio de 2026) |
| **Reevaluar cuando** | la retención obligue a borrar o anonimizar versiones (RGPD) y el índice no lo soporte con el coste esperado, o el volumen haga preferible un almacén frío (S3/MinIO ya está en el compose) |

## 1. Contexto

El negocio quiere **auditar en cualquier momento qué se envió a SAP**: cada
intento de envío, con el snapshot íntegro (IBAN incluido, decisión explícita
del plan de acción), consultable por entidad y con diff entre versiones
(`GET /customers/{id}/history`, `/history/diff`). Es un flujo de escritura
append-only con lecturas por entidad y, más adelante, búsquedas por campo y
paneles (Kibana ya está en el compose).

## 2. Opciones

| | Elasticsearch | Colección Mongo `history` | Ficheros en MinIO/S3 |
|---|---|---|---|
| Búsqueda por campo y agregaciones | nativa | posible, sin analizadores | ninguna |
| Diff entre versiones | leer dos docs | igual | leer dos objetos |
| Paneles | Kibana incluido | no | no |
| Un almacén menos | no | sí | no |
| Retención / borrado selectivo | ILM por índice; borrado por query | borrado por query | política de bucket |

## 3. Decisión

Elasticsearch, índices `customers_history` y `articles_history`, un documento
por **intento** (id `entityId-payloadHash-epochMillis`, Fase 6) con
`@timestamp`. `createIndex=false`: la app arranca sin ES y el índice lo crea
la primera escritura u operaciones con su plantilla.

## 4. Consecuencias

- Es el único almacén con PII completa fuera del legacy: entra en el modelo de
  bloqueo y necesita **plazo de retención** (pendiente, plan de acción).
- El histórico y la imagen discrepan a propósito: último intento vs último
  éxito.
- La versión del cliente Java debe ir alineada con la del servidor (incidencia
  D1: ES 8 vs cliente 9) y con `opentelemetry-semconv` (incidencia del
  2026-09-12).
- Spec: [`../../sdd/common/idempotencia-y-dedupe.md`](../../sdd/common/idempotencia-y-dedupe.md) R-5.
