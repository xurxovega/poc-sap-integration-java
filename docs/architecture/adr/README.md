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
| [0007](0007-keycloak-como-proveedor-de-identidad-de-las-apis.md) | Keycloak (resource server OAuth2) como identidad de las APIs; acceso declarado por endpoint; PII enmascarada para externos | ✅ aceptada 2026-09-12 |
| [0008](0008-kubernetes-como-plataforma-de-despliegue.md) | Kubernetes, dos clústeres (test y prod), Kustomize, imagen con buildpacks | ✅ aceptada 2026-09-12 |
| [0009](0009-trazas-con-el-starter-oficial-de-opentelemetry.md) | Trazas con el starter oficial de OTel de Boot 4, apagadas hasta tener Tempo; Prometheus/Loki los aporta la plataforma | ✅ aceptada 2026-09-12 |
| [0010](0010-sin-compensacion-entre-features-marcar-y-avisar.md) | Sin compensación entre features: el agregado queda en error, cada parte conserva su estado y se avisa (log, topic `sap.sync.alerts`, métrica) | ✅ aceptada 2026-09-14 |
| [0011](0011-concurrencia-entre-instancias-fencing-sin-lease.md) | Concurrencia entre instancias: fencing por `cycleId` y consumer group por clúster K8s (sin *lease* por entidad) | ✅ aceptada 2026-09-18; revisada 2026-09-23 por OPS-010 |
| [0012](0012-servicio-externo-de-autenticacion-idp.md) | Servicio externo de autenticación: Keycloak como IdP; Zitadel y authentik evaluadas | 🟡 propuesta 2026-09-18 |
| [0013](0013-outbox-mensaje-fino-sin-payload.md) | La outbox publica un aviso de cambio **fino** (identidad, sin datos ni PII); el consumidor relee el legacy y calcula el hash sobre el snapshot | ✅ aceptada 2026-09-19 |
| [0014](0014-redpanda-como-broker-de-mensajeria.md) | Broker de mensajería: Redpanda v25.3.9 LTS, Operator y CRD `Redpanda`, un cluster por clúster K8s, sin ZooKeeper, Tiered Storage desactivado | ✅ aceptada 2026-09-23 (OPS-010) |

Decisiones **pendientes** del plan de acción que acabarán aquí: D-3 (Debezium
Server / Event Router SMT), D-4 (OData V4).
