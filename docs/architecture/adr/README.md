# ADRs — decisiones de arquitectura

Registro de decisiones (*Architecture Decision Records*): qué se decidió, entre
qué opciones, por qué, y **cuándo se reevalúa**. Un ADR no se edita para
cambiar la decisión: se escribe otro que lo sustituye y se enlazan.

Formato: contexto → opciones → decisión → consecuencias → disparador de
reevaluación. Numeración correlativa `NNNN-titulo-en-kebab.md`.

| ADR | Decisión | Estado |
|---|---|---|
| [0001](0001-transporte-http-sap-restclient.md) | Transporte HTTP hacia SAP: `RestClient` de Spring ahora; VDM del Cloud SDK cuando soporte Boot 4 | ✅ aceptada 2026-09-12 |
| [0002](0002-mongodb-para-imagen-y-estado.md) | MongoDB para la imagen actual y el estado de sincronización | ✅ retroactiva 2026-09-12 |
| [0003](0003-elasticsearch-para-el-historico.md) | Elasticsearch para el histórico de versiones enviadas (un documento por intento) | ✅ retroactiva 2026-09-12 |
| [0004](0004-dos-familias-de-adaptadores-btp-y-odata.md) | Dos familias de adaptadores hacia SAP (BTP propio y OData nativo) tras el mismo puerto | ✅ retroactiva 2026-09-12 |
| [0005](0005-mysql-para-el-registro-sdd.md) | MySQL para el registro de features SDD | ✅ retroactiva 2026-09-12 |
| [0006](0006-kafka-connect-debezium-como-cdc.md) | Kafka Connect + Debezium (outbox por triggers) como CDC | ✅ retroactiva 2026-09-12 |

Decisiones **pendientes** del plan de acción que acabarán aquí: D-2 (compensación
entre features), D-3 (Debezium Server / Event Router SMT), D-4 (OData V4), D-7
(trazas con OpenTelemetry), D-9 (plataforma de despliegue).
