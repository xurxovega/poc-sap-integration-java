# Despliegue en Kubernetes (decisión D-9)

Dos clústeres de Kubernetes, uno de **test** y otro de **producción**
([ADR-0008](../docs/architecture/adr/0008-kubernetes-como-plataforma-de-despliegue.md)).
Los manifiestos son Kustomize: una base con las dos apps y un overlay por
entorno. Sin Helm por ahora: dos apps y una decena de variables no lo justifican.

```
deploy/k8s/
├── base/            # Deployment + Service + ConfigMap por app, ServiceAccount, ConfigMap común
└── overlays/
    ├── test/        # namespace, imagen/tag, réplicas, Keycloak y S/4 de test
    └── prod/
```

## Imagen

La construye Cloud Native Buildpacks vía el plugin de Boot (sin Dockerfile):

```bash
./mvnw -pl customer,article spring-boot:build-image -DskipTests   # poc-sap/<app>:<version>
```

La CI (`.github/workflows/ci.yml`, job `image`) la construye y publica en el
registro al etiquetar `v*`. El nombre del registro se cambia en
`overlays/*/kustomization.yaml` (`images:`).

## Secretos

Ningún secreto vive en el repo. Cada overlay espera dos `Secret` **externos**
(`sap-integration-secrets` y `<app>-secrets`) con estas claves:

| Secret | Claves |
|---|---|
| `sap-integration-secrets` | `SAP_S4_CLIENT_ID`, `SAP_S4_CLIENT_SECRET`, `SAP_S4_TOKEN_URL`, `SAP_BTP_*` (si aplica), `SAP_SEPA_CREDITOR_ID` |
| `customer-secrets` | `SQLSERVER_USER`, `SQLSERVER_PASSWORD` |
| `article-secrets` | `POSTGRES_USER`, `POSTGRES_PASSWORD` |

Cómo llegan al clúster (sealed-secrets, External Secrets Operator, Vault) es
una decisión de plataforma pendiente. Si faltan, **la app no arranca** y dice
qué falta ([`../docs/sdd/common/autenticacion-sap.md`](../docs/sdd/common/autenticacion-sap.md)).

Además de estos secretos, la colección Mongo `sap_keys` (la clave que asigna
SAP por dominio/entidad/feature, p. ej. `AddressID`) debe entrar en el backup
del clúster igual que `sync_state`: si se pierde, el upsert idempotente
(PRD-11) no puede saber que la parte ya existe en SAP y el siguiente ciclo
**duplica** en vez de actualizar. Ver
[`../docs/operacion/APTITUD-PRODUCCION.md`](../docs/operacion/APTITUD-PRODUCCION.md) §5.

## Aplicar

```bash
kubectl apply -k deploy/k8s/overlays/test
kubectl -n sap-integration-test rollout status deploy/customer-app
```

### Validación local con k3s

Antes de empujar a un clúster corporativo, se recomienda validar el operador
Redpanda y el cluster CRD en un k3s local con `~/.kube/config`. Pasos:

```bash
# 1. dry-run del lado cliente: kubectl solo imprime YAML, no toca nada
kubectl --context=<k3s> apply -k deploy/k8s/overlays/test --dry-run=client

# 2. instalación real y espera a que el operador cree el cluster
kubectl --context=<k3s> apply -k deploy/k8s/overlays/test
kubectl --context=<k3s> -n sap-integration-test get redpanda -w
# esperar a phase=Running y condition.ready=True (puede tardar 3-5 min la 1.ª vez)

# 3. operator y CRD
kubectl --context=<k3s> -n sap-integration-test get pods
kubectl --context=<k3s> -n sap-integration-test logs deploy/redpanda-operator -c operator

# 4. smoke del broker desde un pod de debug
kubectl --context=<k3s> -n sap-integration-test run rpk-debug --rm -it --restart=Never \
  --image=redpandadata/redpanda:v25.3.9 --command -- rpk cluster health
```

Si `StorageClass` por defecto del clúster no es `local-path` (k3s), editar
`deploy/k8s/base/redpanda.yaml` y descomentar el `storageClassName` en
`clusterSpec.statefulset.podTemplate.persistence` antes de aplicar.

## Topología del broker: quién crea los topics (ADR-0011, OPS-010)

**La aplicación no crea topics.** `app.kafka.topics.create` es `false` (y el
overlay de producción lo fija explícitamente); la auto-creación del broker deja
topics con la configuración por defecto —1 partición— y eso impide escalar y
rompe el orden por entidad. Los topics los crea la **plataforma** (IaC o el
script de alta del entorno) **antes** del primer despliegue:

| Topic | Particiones | Clave | Notas |
|---|---|---|---|
| `outbox.CUSTOMER` | 12 | `entity_id` | lo alimenta el conector Debezium de SQL Server |
| `outbox.CUSTOMER-dlt` | 12 | ídem | el registro fallido va a **la misma partición** que el original |
| `outbox.ARTICLE` | 12 | `entity_id` | conector Debezium de PostgreSQL |
| `outbox.ARTICLE-dlt` | 12 | ídem | — |

```bash
# Antes con Kafka: kafka-topics --bootstrap-server "$KAFKA_BOOTSTRAP" --create ...
# Con Redpanda (broker Kafka 3.x wire-compatible), el comando nativo es `rpk`:
for t in outbox.CUSTOMER outbox.CUSTOMER-dlt outbox.ARTICLE outbox.ARTICLE-dlt; do
  rpk topic create "$t" --partitions 12 --replication 3 \
    --brokers "$MESSAGING_BOOTSTRAP"
done

# Comprobación:
rpk topic list --brokers "$MESSAGING_BOOTSTRAP"
# outbox.CUSTOMER        12  3
# outbox.CUSTOMER-dlt    12  3
# outbox.ARTICLE         12  3
# outbox.ARTICLE-dlt     12  3
```

Otras variables `APP_KAFKA_*`/de aplicación relevantes al desplegar
(`deploy/k8s/base/common.yaml`):

| Variable | Qué hace |
|---|---|
| `APP_KAFKA_TOPICS_CREATE` | ver arriba: `false` en todos los overlays |
| `APP_KAFKA_TOPICS_PARTITIONS` | particiones esperadas de los topics (`12`); usada para validar el arranque, no para crearlos |
| `APP_KAFKA_RETRY_CIRCUIT_OPEN_BACKOFF_MS` | backoff del listener cuando el fallo es `SapCircuitOpenException` (circuito abierto hacia SAP): arranca en la ventana de apertura del circuit breaker en vez del backoff normal, para no agotar los reintentos con SAP todavía caído |
| `CUSTOMER_KAFKA_CONCURRENCY` / `ARTICLE_KAFKA_CONCURRENCY` | hilos del listener Kafka por instancia; ver la regla de particiones ≥ instancias × concurrency más abajo |
| `SAP_CLIENT_LOOKUP_ENABLED` | activa el lookup previo a escritura (upsert idempotente contra S/4, PRD-11); por defecto `true`, no está en el `ConfigMap` porque no hay motivo para desactivarlo fuera de pruebas |

Reglas que hay que respetar al dimensionar:

- **Un solo `consumer group` por dominio** (`customer-consumer`, `article-consumer`)
  **compartido por los dos clústeres**. Grupos distintos por clúster harían que
  cada clúster procesara todos los mensajes y escribiera **dos veces** el mismo
  cambio en el mismo S/4.
- **particiones ≥ instancias × `concurrency`**: 2 clústeres × 2 réplicas ×
  `CUSTOMER_KAFKA_CONCURRENCY=3` = 12. Más hilos que particiones son hilos
  ociosos; menos, particiones sin consumir a pleno.
- El `-dlt` siempre con **las mismas particiones** que su topic de entrada.
- **[Supuesto pendiente de confirmar por plataforma (D-16)]**: un único Kafka
  multi-AZ visible desde los dos clústeres. Si acabara habiendo un Kafka por
  clúster con MirrorMaker 2, esta topología **no es suficiente** y hay que
  rediseñar la idempotencia frente a SAP — ver
  [ADR-0011](../docs/architecture/adr/0011-concurrencia-entre-instancias-fencing-sin-lease.md).

## Qué esperan los manifiestos de la plataforma

- Sondas en `/actuator/health/liveness` y `/readiness` (Boot las activa al
  detectar Kubernetes); `startupProbe` de hasta 3 min porque la app valida el
  esquema del legacy al arrancar.
- Prometheus por *scraping* de `/actuator/prometheus` (anotaciones
  `prometheus.io/*`; si se usa el Operator, añadir un `ServiceMonitor`).
- Logs en JSON (ECS) por stdout para Loki.
- `terminationGracePeriodSeconds` 45 s > parada ordenada de Boot (30 s).
- Ingress **solo** para `/customers/**` y `/articles/**`; `/actuator` no se
  expone fuera del clúster (health y prometheus van sin token).
- Kafka, Mongo, Elasticsearch y los legacy se referencian por DNS de servicio
  (`*.svc`); ajustar en `base/common.yaml` y `base/<app>-app.yaml`.
