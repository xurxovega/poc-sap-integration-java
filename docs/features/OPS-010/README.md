# Feature: OPS-010 — Broker de mensajería: Kafka → Redpanda

## Objetivo

Sustituir Kafka por Redpanda en **todos los entornos** (local, test,
prod). Wire Kafka 3.x: la app cliente no se toca. Operado por nosotros
vía Redpanda Operator. Beneficios que persigue: cero consumo JVM, latencia
mejorada, simplificación operativa.

## Estado

Pendiente desde 2026-09-22.

## Alcance

**Dentro**:
- Spec nuevo en `docs/sdd/common/broker-de-mensajeria.md`.
- ADR nuevo `docs/architecture/adr/0014-redpanda-como-broker-de-mensajeria.md`.
- Revisión de ADR-0011 (concurrencia entre instancias): ahora un cluster
  Redpanda por cluster K8s; consumer group compartido **dentro** del
  cluster.
- Compose local: sustituye `zookeeper` + `kafka-broker` + `kafka-init-topics`
  por `redpanda` + `redpanda-init-topics`. `kafka-connect` (Debezium) se
  queda, apuntando a `redpanda:9092`.
- Conectores Debezium (`external-services/debezium/register-*.json`):
  `kafka-broker:29092` → `redpanda:9092`.
- Manifiestos K8s nuevos:
  - `deploy/k8s/base/redpanda.yaml` (Operator + CRD `RedpandaCluster`,
    3 nodos HA, Tiered Storage desactivado).
  - `deploy/k8s/base/kafka-connect.yaml` (Debezium Connect apuntando al
    Redpanda del cluster).
- Renombrado `KAFKA_BOOTSTRAP` → `MESSAGING_BOOTSTRAP` en YAML, scripts,
  ConfigMaps y ADRs.
- `deploy/k8s/base/common.yaml`: `MESSAGING_BOOTSTRAP: "redpanda.messaging.svc:9092"`.
- Operación: comandos `rpk` (tabla de equivalencias al principio de
  `RUNBOOKS.md`).
- Cierre de D-16 en `deploy/README.md`.
- Test nuevo `it/.../DebeziumRedpandaIT.java` (Testcontainers).
- Edición de `it/.../InfrastructureSmokeIT.java` (`ConfluentKafkaContainer`
  → `RedpandaContainer`).

**Fuera** (queda como `OPS-011` si surge):
- Tiered Storage con S3/MinIO. Desactivado en v1; propuesta abierta en
  `MEJORAS-Y-PROPUESTAS.md` para activarlo tras la prueba de carga.
- Mirroring entre clústeres Redpanda: hoy no hace falta (cada cluster
  consume su topic).
- Cambio del nombre `KAFKA_*` en variables operativas de los runbooks
  antiguos: se hace al migrar.

## Ficheros afectados

| Tipo | Ruta |
|---|---|
| Spec (NUEVO) | `docs/sdd/common/broker-de-mensajeria.md` |
| ADR (NUEVO) | `docs/architecture/adr/0014-redpanda-como-broker-de-mensajeria.md` |
| ADR (edición) | `docs/architecture/adr/0011-concurrencia-entre-instancias-fencing-sin-lease.md` |
| Índice ADR | `docs/architecture/adr/README.md` |
| Backlog | `docs/MEJORAS-Y-PROPUESTAS.md` (OPS-010 cerrado, OPS-011 propuesto) |
| Compose | `external-services/docker-compose.yml`, `external-services/debezium/register-*.json` |
| Scripts | `scripts/start-all.sh`, `scripts/env/local.env`, `scripts/env/test.env.example` |
| Renombrado app | `customer/src/main/resources/application.yml`, `article/...`, `common/...`, `supplier/...` |
| Manifiestos K8s (NUEVOS) | `deploy/k8s/base/redpanda.yaml`, `deploy/k8s/base/kafka-connect.yaml` |
| Manifiestos K8s (edición) | `deploy/k8s/base/common.yaml`, `deploy/k8s/base/kustomization.yaml`, `deploy/k8s/overlays/{test,prod}/kustomization.yaml` |
| Operación | `docs/operacion/RUNBOOKS.md`, `docs/operacion/APTITUD-PRODUCCION.md` |
| Quickstart | `docs/QUICK_START.md`, `external-services/README.md` |
| Docs raíz | `README.md` raíz, `common/README.md` |
| Glosario | `docs/GLOSSARY.md` (`Redpanda`, `rpk`, `Redpanda Operator`, `RedpandaCluster`, `MESSAGING_BOOTSTRAP`, `Tiered Storage`) |
| CHANGELOG | `CHANGELOG.md` raíz, `docs/sdd/common/CHANGELOG.md` |
| Tests | `it/src/test/java/com/poc/sap/it/InfrastructureSmokeIT.java` (edición), `it/src/test/java/com/poc/sap/it/DebeziumRedpandaIT.java` (NUEVO) |
| Registro | `external-services/mysql/init.sql` |

## Criterios de aceptación

| AC | Criterio | Verificación |
|---|---|---|
| AC-1 | `start-all.sh --with-cdc` levanta Redpanda + Debezium + apps en verde; `rpk topic list` muestra 4 topics con 12 particiones cada uno | smoke E2E local |
| AC-2 | `DebeziumRedpandaIT` ejecuta CDC UPDATE → topic → `KafkaListener` → `SENT_SAP` | `mvn -pl it verify -Ddocker.available=true` |
| AC-3 | `kubectl apply -k deploy/k8s/overlays/test` levanta el Operator y el `RedpandaCluster` en `healthy` | smoke de cluster |
| AC-4 | `mvn verify` en verde | build |
| AC-5 | `git diff customer/ article/ common/ -- '*.java'` muestra solo el renombrado de variable; ningún cambio funcional | revisión de PR |
| AC-6 | `python scripts/sdd-registry-check.py` sin diferencias | script |
| AC-7 | En cluster de test, `rpk topic list` desde un pod de debug muestra los 4 topics con RF=3 | smoke de cluster |

## Validación

- `mvn verify` con los tests existentes sin cambios.
- `DebeziumRedpandaIT` en verde.
- Smoke E2E local: `start-all.sh --with-cdc`, UPDATE en SQL Server,
  mensaje en `outbox.CUSTOMER`, `SENT_SAP` en `/customers/CUST-001/state`.
- (Cuando exista CI con Docker) `mvn -pl it verify -Ddocker.available=true`.
- ADR-0014 firmado y ADR-0011 revisado con D-16 cerrado.

## Cambios

| Fecha | Cambio |
|---|---|
| 2026-09-22 | Alta de la feature (estado "Pendiente"). Andamiaje en `docs/features/OPS-010/`. |