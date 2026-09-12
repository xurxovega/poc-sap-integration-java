# ADRs — decisiones de arquitectura

Registro de decisiones (*Architecture Decision Records*): qué se decidió, entre
qué opciones, por qué, y **cuándo se reevalúa**. Un ADR no se edita para
cambiar la decisión: se escribe otro que lo sustituye y se enlazan.

Formato: contexto → opciones → decisión → consecuencias → disparador de
reevaluación. Numeración correlativa `NNNN-titulo-en-kebab.md`.

| ADR | Decisión | Estado |
|---|---|---|
| [0001](0001-transporte-http-sap-restclient.md) | Transporte HTTP hacia SAP: `RestClient` de Spring ahora; VDM del Cloud SDK cuando soporte Boot 4 | ✅ aceptada 2026-09-12 |

Pendientes de escribir (plan de acción, Fase 9): los cinco ADRs retroactivos de
lo que ya existe (Mongo para el estado, Elasticsearch para el histórico, dos
familias de adaptadores BTP/OData, MySQL para el registro SDD, Kafka Connect
como plataforma CDC).
