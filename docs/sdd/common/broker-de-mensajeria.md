# Broker de mensajería: Redpanda (sustituye a Apache Kafka + ZooKeeper)

| | |
|---|---|
| **Dominio** | `common` (capacidad transversal; el broker es consumido por `customer`, `article`, `supplier` y por `kafka-connect` para Debezium) |
| **Estado** | ✅ aceptada 2026-09-23 |
| **Entradas** | CDC de las outbox legacy (`outbox.CUSTOMER`, `outbox.ARTICLE`); eventos directos desde Debezium; la app no expone API pública sobre el broker |
| **Destino SAP** | ninguno (es interno al reactor) |
| **Última revisión** | 2026-09-23 |

## 1. Objetivo

Que el broker de mensajes que recibe el CDC (Debezium) y entrega los
eventos a las apps sea **wire-compatible con Kafka 3.x**, sin ZooKeeper,
con un solo binario, y se opere desde Kubernetes con manifests Kustomize
puros. La app cliente se queda exactamente igual: sigue siendo un
consumidor y productor Kafka estándar a través de Spring Kafka.

Beneficio de negocio directo: ahorra dos JVM por clúster (la del
ZooKeeper y la del broker Kafka en Java) y baja la latencia p99 del flujo
CDC→Kafka→listener→SAP. La sustitución se hizo en todos los entornos a la
vez (local, test, prod); la app cliente **no se tocó** salvo el renombrado
cosmético de la variable de entorno `KAFKA_BOOTSTRAP` →
`MESSAGING_BOOTSTRAP` (un único literal).

## 2. Alcance

**Dentro**:

- Selección del broker: Redpanda v25.3.9 LTS, con CRD
  `cluster.redpanda.com/v1alpha2` (`kind: Redpanda`).
- Topología: **un Redpanda por clúster Kubernetes**, sin replicación entre
  clusters. Consumer group por clúster (test y prod cada uno con el suyo).
- Operación: comandos `rpk` (sustituyen a `kafka-*`); equivalencias en
  [`../../operacion/RUNBOOKS.md`](../../operacion/RUNBOOKS.md).
- Manifiestos K8s: `deploy/k8s/base/redpanda.yaml` (Operator + CRD) y
  `deploy/k8s/base/kafka-connect.yaml` (Debezium Connect apuntando a
  Redpanda). Overlays en `deploy/k8s/overlays/{test,prod}`.
- Compose local: sustitución del servicio `kafka-broker` y `zookeeper`
  por un único contenedor `redpanda` (con `redpanda-init-topics`); el
  registro de Debezium apunta a `redpanda:9092`.
- Renombrado `KAFKA_BOOTSTRAP` → `MESSAGING_BOOTSTRAP` en YAML de apps,
  ConfigMap común, `.env` y ADRs. Cero cambios funcionales en
  `customer/`, `article/`, `common/`.
- Tiered Storage **desactivado en v1**. Activación propuesta como
  `OPS-011` en [`../../MEJORAS-Y-PROPUESTAS.md`](../../MEJORAS-Y-PROPUESTAS.md).
- Capacidades del wire: idempotencia por partición (clave
  `entity_id`), 12 particiones en `outbox.CUSTOMER`, `outbox.ARTICLE` y
  sus `-dlt` (mismas particiones que el original para que el publish del
  DLT no falle), RF=3 en test/prod, RF=1 en local.

**Fuera** (y por qué):

- Replicación entre clusters Redpanda (mirror, tiered storage, etc.).
  Hoy cada cluster consume su propio topic; una réplica cruzada añade
  latencia y un punto de fallo sin necesidad de negocio (cero
  consumidores fuera de K8s).
- Tiered Storage contra S3/MinIO. Lo abre `OPS-011`, ligado al plan de
  capacidad que aún no se ha hecho.
- Cambio del operador (otro Helm, otro FluxCD, otro Argo). El Operator
  oficial + manifests Kustomize puros cubre el caso sin dependencias
  nuevas.
- Driver de cliente nativo Redpanda (`librdkafka` / `redpanda-kafka-go`).
  La app cliente sigue con Spring Kafka (`spring-kafka`); wire
  compatible, sin tocar el código.

## 3. Actores

| Actor | Qué hace |
|---|---|
| **Operador K8s** | Aplica `kubectl apply -k deploy/k8s/overlays/<env>` y crea el Operator y el `Redpanda`. Aprovisiona PVCs del StatefulSet con la `StorageClass` que diga el manifest (`local-path` por defecto en k3s) |
| **Plataforma / SRE** | Crea los topics antes del primer despliegue con `rpk topic create`, RF=3 (test/prod) o RF=1 (local) |
| **Debezium / Kafka Connect** | Publica los eventos CDC en `outbox.<DOM>` con clave `entity_id`; usa `BOOTSTRAP_SERVERS=redpanda:9092` dentro del clúster, `localhost:19092` desde el host en local |
| **Customer / Article / Supplier app** | Productores/consumidores Spring Kafka: publican al `bootstrap.servers` que llega por `MESSAGING_BOOTSTRAP` |
| **Operador humano** | Diagnostica con `rpk` (no `kafka-*`); reglas en [`../../operacion/RUNBOOKS.md`](../../operacion/RUNBOOKS.md) |
| **Auditor / desarrollador** | Verifica la invariante "no quedan restos de `kafka-broker` / `zookeeper` / `KAFKA_BOOTSTRAP`" con `TopologyTest` |

## 4. Contrato

### 4.1 Wire protocol

Es **Apache Kafka 3.x** sin desviaciones: una app cliente Kafka se conecta
a Redpanda sin ningún cambio de código, autenticación ni de Serialization
(`JsonConverter`, `JsonDeserializer`). Validado por `DebeziumRedpandaIT`
con `RedpandaContainer` de Testcontainers v25.3.9.

### 4.2 Bootstrap

- **Local (Docker compose)**: `localhost:19092` desde el host (Spring
  Kafka usa `MESSAGING_BOOTSTRAP`). Desde dentro de la red Docker se
  publica a `redpanda:9092` (interfaz interna).
- **Test K8s**: `<release-name>-redpanda.sap-integration-test.svc.cluster.local:9092`.
- **Prod K8s**: `<release-name>-redpanda.sap-integration.svc.cluster.local:9092`.

### 4.3 Topics

| Topic | Particiones | Clave | Réplicas | Notas |
|---|---|---|---|---|
| `outbox.CUSTOMER` | 12 | `entity_id` | 3 (test/prod) / 1 (local) | CDC de SQL Server |
| `outbox.CUSTOMER-dlt` | 12 | `entity_id` | 3 / 1 | mismo partición que el original (publish del DLT) |
| `outbox.ARTICLE` | 12 | `entity_id` | 3 / 1 | CDC de PostgreSQL |
| `outbox.ARTICLE-dlt` | 12 | `entity_id` | 3 / 1 | idem |

Topics internos de Connect (`connect-configs`, `connect-offsets`,
`connect-statuses`) los crea Kafka Connect por su cuenta al arrancar —
`replication.factor` y `partitions` por defecto del broker.

### 4.4 Configuración de la app (no del broker)

La app sigue leyendo estas variables:

| Variable | Significado | Default | Override en |
|---|---|---|---|
| `MESSAGING_BOOTSTRAP` | lista `host:puerto[,host:puerto]` para Spring Kafka | `localhost:9092` | `scripts/env/local.env`, `deploy/k8s/base/common.yaml` |
| `APP_KAFKA_TOPICS_CREATE` | creación de topics desde la app | `false` | overlay de producción |
| `APP_KAFKA_TOPICS_PARTITIONS` | particiones esperadas | `12` | overlay / `.env` |
| `KAFKA_MAX_POLL_INTERVAL_MS` | presupuesto del listener | `900000` (15 min) | overlay |
| `KAFKA_MAX_POLL_RECORDS` | tamaño de lote del listener | `10` | overlay |

Estos nombres **no se renombran**: son propiedad de Spring Kafka, no
literales del bootstrap. La única renombrada es `KAFKA_BOOTSTRAP` →
`MESSAGING_BOOTSTRAP` (la que realmente va al `bootstrap.servers`).

## 5. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | El broker se identifica como Redpanda: nombre del binario en compose (`redpanda`), nombre del recurso CRD (`kind: Redpanda`), comandos operativos (`rpk`) | Operativos viejos (Kafka/ZK) crean confusión y se reintroduce el doble broker |
| R-2 | Topología: un Redpanda por clúster K8s. Sin replicación entre clusters (no se crea un secreto compartido, no se monta MirrorMaker) | Sobreingeniería; o peor, una réplica cruzada que se replica dos veces si alguien añade una tercera |
| R-3 | Consumer group por cluster K8s: `customer-consumer` y `article-consumer` viven en el broker local de cada cluster. No se comparten offsets entre clusters | Ver [ADR-0011](../../architecture/adr/0011-concurrencia-entre-instancias-fencing-sin-lease.md) revisado 2026-09-23: shared group entre clusters era un supuesto, ahora cerrado por [ADR-0014](../../architecture/adr/0014-redpanda-como-broker-de-mensajeria.md) |
| R-4 | RF=3 en test y prod (3 réplicas del StatefulSet); RF=1 en local para no exigir quorum en single-node | Sin quorum suficiente el broker no acepta escrituras y la ingesta queda en cero |
| R-5 | Tiered Storage desactivado en v1 (`tieredStorage.disabled: true` en el CRD) | La activación abre `OPS-011` y un plan de capacidad |
| R-6 | Los conectores Debezium usan `BOOTSTRAP_SERVERS=redpanda:9092` (interfaz interna del servicio en K8s; `redpanda:9092` también dentro de docker compose) | El worker no arranca o publica a un broker equivocado |
| R-7 | Renombrado `KAFKA_BOOTSTRAP` → `MESSAGING_BOOTSTRAP` en todos los manifiestos, `.env`, YAML de apps y ADRs | Mezclan ambos nombres y un futuro lector no sabe qué bootstrap es el vivo |
| R-8 | La aplicación **no crea topics** (`APP_KAFKA_TOPICS_CREATE=false`): los crea la plataforma antes del primer despliegue, con RF y particiones correctas | Auto-creación deja 1 partición y rompe el orden por entidad |
| R-9 | `particiones ≥ instancias × concurrency`: 2 clusters × 2 réplicas × `KAFKA_CONCURRENCY=3` = 12 particiones por topic. El `-dlt` igual | Particiones sin consumir (concurrency ocioso) o, peor, particiones que rebalsean en cascada |
| R-10 | Cero restos operativos de `kafka-broker`, `zookeeper` o `KAFKA_BOOTSTRAP`: lo vigila `TopologyTest` en el build (no se queda ninguno en compose, K8s, JSON de conectores, .env, .yaml, .sh, .properties) | Falsas configuraciones operativas con el broker antiguo |

## 6. Estado del broker y errores

| Situación | Estado | Reintentable |
|---|---|---|
| Cluster Redpanda no saludable (`rpk cluster health != Healthy`) | la app no arranca contra un broker muerto: `SynchronousConnectionException` en el listener, reintento con backoff; eventualmente DLT si los reintentos se agotan | sí (transitorio) |
| Réplicas insuficientes (RF objetivo > réplicas vivas) | `NotEnoughReplicasException`/`RecordTooLargeException` dependiendo; el listener lo trata como reintentable con el backoff normal | sí |
| Topic no existe (auto-creación desactivada) | `UnknownTopicOrPartitionException`. El listener la declara **no reintentable** y va a la DLT | no (configurar topic antes) |

## 7. Criterios de aceptación

| AC | Criterio | Test / verificación |
|---|---|---|
| AC-1 | `start-all.sh --with-cdc` levanta el compose; `docker exec redpanda rpk topic list -X brokers=localhost:19092` muestra 4 topics (`outbox.CUSTOMER`, `-dlt`, `outbox.ARTICLE`, `-dlt`) con 12 particiones cada uno y RF=1 en local | smoke E2E local documentado en `docs/features/OPS-010/quickstart.md` §3 |
| AC-2 | `DebeziumRedpandaIT` levanta un Redpanda real (Testcontainers), arranca Debezium Connect, ejecuta un UPDATE sobre el legacy, recibe el mensaje en el topic y el listener termina la entidad en `SENT_SAP` | `mvn -pl it verify -Ddocker.available=true` |
| AC-3 | `kubectl apply -k deploy/k8s/overlays/test` con el `kubectl kustomize` validado en local; en un k3s levanta Operator + CRD `Redpanda` 3 réplicas en `phase=Running` y el broker acepta escrituras | `kubectl --context=<k3s> apply -k deploy/k8s/overlays/test` + `kubectl get redpanda -w` (CI o humano) |
| AC-4 | `mvn verify` en verde: la sustitución de Kafka por Redpanda no rompe ninguna regla ArchUnit ni el umbral JaCoCo | `mvn verify` |
| AC-5 | `git diff customer/ article/ common/ -- '*.java'` muestra **únicamente** el renombrado de la variable (literal `KAFKA_BOOTSTRAP` → `MESSAGING_BOOTSTRAP`). Ningún cambio funcional en Java | revisión de PR |
| AC-6 | `TopologyTest#renombradoDelBrokerCompletado`, `TopologyTest#serviciosKafkaSustituidosPorRedpanda`, `TopologyTest#zookeeperEliminado` están verdes: cero restos operativos de `KAFKA_BOOTSTRAP`, `kafka-broker` o `zookeeper` en compose, K8s, JSON, `.env`, YAML, scripts | `mvn -pl it verify` (sin Docker) |
| AC-7 | En el cluster K8s de test, un pod de debug con `rpk` muestra los 4 topics con RF=3 y 12 particiones cada uno | `kubectl --context=<cluster> -n sap-integration-test run rpk-debug --rm -it --image=redpandadata/redpanda:v25.3.9 -- rpk topic list` |

Aplica además AC-1..AC-7 de [`../../sdd/README.md`](../README.md) §4 (criterios
globales).

## 8. Observabilidad

Métricas que la app cliente expone (sin cambios desde [OBS-005](observabilidad.md)):

- `sap_sync_state_total{state="..."}` (estado final del agregado/feature)
- `sap_client_request_duration{outcome="2xx|4xx|5xx|transport_error|circuit_open"}`
- `resilience4j_circuitbreaker_state{name="sap"}`
- `recordStageDuration{stage="fetch|validate|index|send"}`

Métricas que el broker Redpanda expone de fábrica (vía `/admin/v2` con
autenticación, no scrape directo):

- `redpanda_cluster_*` (tamaño, particiones, throughput)
- `redpanda_kafka_*` (latencia request, errores)
- `redpanda_storage_*` (uso de disco, *backlog* por topic)

La integración Prometheus de Redpanda es externa al broker: la
configurar `deploy/observability/` cuando exista el stack de observabilidad
de plataforma (OPS-1 en [`MEJORAS-Y-PROPUESTAS.md`](../../MEJORAS-Y-PROPUESTAS.md)).

Logs: `startup_message` y errores del broker van a stdout del Pod (ECS).
Ya hay un dashboard esbozado [`deploy/observability/grafana/dashboards/redpanda-cluster.json`](../../observability/grafana/dashboards/redpanda-cluster.json) (si existe) para test/prod; lo opera plataforma.

## 9. Trazabilidad spec ↔ código ↔ test

| Elemento | Código / manifiesto | Test |
|---|---|---|
| R-1 broker Redpanda, sin Kafka/ZK en compose | `external-services/docker-compose.yml` | `TopologyTest#serviciosKafkaSustituidosPorRedpanda`, `TopologyTest#zookeeperEliminado` |
| R-1 manifests K8s | `deploy/k8s/base/redpanda.yaml`, `deploy/k8s/base/kafka-connect.yaml`, `deploy/k8s/base/kustomization.yaml` | `kubectl kustomize deploy/k8s/overlays/test` |
| R-2, R-3 un cluster por cluster K8s, consumer group por cluster | ADR-0014, ADR-0011 revisado 2026-09-23 | — |
| R-4 RF configurable | `deploy/k8s/base/redpanda.yaml`, `external-services/docker-compose.yml` | `rpk topic describe` |
| R-6 BOOTSTRAP_SERVERS=kafka-connect → redpanda | `deploy/k8s/base/kafka-connect.yaml`, `external-services/debezium/register-*.json` | smoke E2E local |
| R-7 renombrado `KAFKA_BOOTSTRAP` → `MESSAGING_BOOTSTRAP` | `customer/.../application.yml`, `article/...`, `common/...`, `deploy/k8s/base/common.yaml`, `scripts/env/*.env` | `CustomerApplicationContextTest#contextStartsWithMessagingBootstrapEnv`, `ArticleApplicationContextTest`, `TopologyTest#renombradoDelBrokerCompletado` |
| R-8 app no crea topics | `APP_KAFKA_TOPICS_CREATE=false` en `common` y en el overlay prod | contract tests / smoke de contexto |
| R-10 cero restos operativos | escaneo `TopologyTest` | `TopologyTest` (3 tests `@Test`) |
| AC-1 topics con 12 particiones y RF=1 | `external-services/redpanda-init-topics/*` | `rpk topic list` |
| AC-2 CDC end-to-end contra Redpanda | `it/.../DebeziumRedpandaIT` | `DebeziumRedpandaIT` |
| AC-3 manifests K8s válidos y funcionales | `deploy/k8s/base/*` + overlays | `kubectl apply -k deploy/k8s/overlays/test` en k3s |
| AC-4 mvn verify en verde | — | `mvn verify` |
| AC-5 cero cambios funcionales en Java | — | `git diff customer/ article/ common/ -- '*.java'` |
| AC-6 cero restos de KAFKA_BOOTSTRAP | escaneo `TopologyTest` | `TopologyTest#renombradoDelBrokerCompletado` |
| AC-7 RF=3 en cluster | `deploy/k8s/base/redpanda.yaml` (`replicas: 3`) | `rpk topic list --brokers <cluster>` |

## 10. Cambios

| Fecha | Cambio | Hito | Commit |
|---|---|---|---|
| 2026-09-22 | Andamiaje `docs/features/OPS-010/{README,quickstart}.md` con la plantilla del plan §7.3/§7.4 | pre-H-0 | (rama previa) |
| 2026-09-23 | Tests rojos: `TopologyTest`,`*ApplicationContextTest#contextStartsWithMessagingBootstrapEnv` (×2), `DebeziumRedpandaIT`, `InfrastructureSmokeIT` (migrado a `RedpandaContainer`) | H-0 | `da09f34` |
| 2026-09-23 | Renombrado `KAFKA_BOOTSTRAP` → `MESSAGING_BOOTSTRAP` en `customer/`, `article/`, `common/` y `.env` | H-1 | `4f41596` |
| 2026-09-23 | Compose local con Redpanda sustituyendo `kafka-broker`/`zookeeper`, `redpanda-init-topics`, conectores Debezium apuntando a `redpanda:9092` | H-2 | `c2e425e` |
| 2026-09-23 | Manifiestos K8s: `redpanda.yaml` (Operator + CRD `Redpanda` 3 réplicas) y `kafka-connect.yaml` (Debezium 2.7.3.Final, BOOTSTRAP_SERVERS=in-cluster), sección de validación k3s en `deploy/README.md` | H-3 | `50e1273` |
| 2026-09-23 | ADR-0014 (Redpanda) + revisión ADR-0011 (consumer group per cluster K8s), D-16 cerrado | H-4 | `5cdc987` |
| 2026-09-23 | Spec inicial de la feature `common/broker-de-mensajeria` (este documento); RUNBOOKS con tabla de equivalencias `kafka-*` ↔ `rpk`; GLOSSARY (`Redpanda`, `rpk`, Redpanda Operator, `MESSAGING_BOOTSTRAP`, Tiered Storage); CHANGELOGs raíz y `common/CHANGELOG.md`; fila en `docs/sdd/README.md` §5 y `MEJORAS-Y-PROPUESTAS.md` (OPS-010 cerrada, OPS-11 propuesta); evento `ALTA` en `sdd_registry.feature_evento`; baseline pre-Redpanda medido y anotado | H-5 | (este commit) |
