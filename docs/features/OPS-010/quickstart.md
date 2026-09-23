# Quickstart — OPS-010 Broker de mensajería: Kafka → Redpanda

> Verifica la sustitución del broker en local, sin tocar el cluster K8s.
> El QUICK_START general está en [`docs/QUICK_START.md`](../../QUICK_START.md).

## 1. Lo que necesitas

- JDK 25, Maven 3.9+ (usar `./mvnw`), Docker.
- Mismo entorno que `docs/QUICK_START.md` §0 (Docker con ≥ 6 GB libres).

## 2. Lo que vas a ver

- Un único contenedor `redpanda` (en lugar de `zookeeper` + `kafka-broker`
  + `kafka-init-topics`).
- Arranque más rápido: ~2 s vs ~30 s del stack Kafka+Zookeeper.
- `MESSAGING_BOOTSTRAP` en lugar de `KAFKA_BOOTSTRAP` en logs y
  variables (`MESSAGING_BOOTSTRAP='localhost:19092'` por defecto en
  compose, no `:9092`: el broker publica `9092` *interno* y `19092`
  *externo*; el host habla por el externo).
- `rpk` en lugar de `kafka-*`.

## 3. Pasos

### a) Arrancar todo el stack con el nuevo broker

```bash
cd ../..                              # raíz del repo
./scripts/start-all.sh --with-cdc
```

### b) Comprobar el broker

```bash
docker exec redpanda rpk cluster health --brokers localhost:19092
docker exec redpanda rpk topic list --brokers localhost:19092
```

Salida esperada: cluster `Healthy`; 4 topics (`outbox.CUSTOMER`,
`outbox.CUSTOMER-dlt`, `outbox.ARTICLE`, `outbox.ARTICLE-dlt`) con 12
particiones cada uno y RF=1 (single-node local).

### c) Comprobar el renombrado de variable

```bash
set -a; source scripts/env/local.env; set +a
env | grep -E '^(MESSAGING_BOOTSTRAP|KAFKA_BOOTSTRAP)='
```

Salida esperada: `MESSAGING_BOOTSTRAP=localhost:19092`. `KAFKA_BOOTSTRAP`
no debe existir.

### d) Provocar CDC end-to-end

```bash
MSYS_NO_PATHCONV=1 docker exec sqlserver-source \
  /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'SqlServer_Pa55w0rd!' -C -Q \
  "UPDATE dbo.customers SET name='X' WHERE id='CUST-001'"
```

Salida esperada: mensaje en `outbox.CUSTOMER`, consumo por el listener,
`SENT_SAP` en `GET /customers/CUST-001/state`.

### e) Verificar Debezium contra Redpanda

```bash
curl -s http://localhost:8083/connectors/outbox-customer-sqlserver/status | jq .connector.state
```

Salida esperada: `RUNNING` (es el equivalente smoke del
`DebeziumRedpandaIT`; el IT corre en Testcontainers con
`RedpandaContainer` v25.3.9).

### f) Verificar manifiestos K8s (sin tocar el cluster)

```bash
# Equivale a `kubectl apply -k deploy/k8s/overlays/test --dry-run=client`,
# pero solo construye el YAML. Sirve para revisar la salida sin cluster.
kubectl kustomize deploy/k8s/overlays/test > /tmp/redpanda-rendered.yaml
grep -c "^kind:" /tmp/redpanda-rendered.yaml   # 15 (Namespace, Redpanda CRD, Services, ConfigMaps...)
grep "^  name:" /tmp/redpanda-rendered.yaml | head -20
```

La aplicación real de los manifiestos K8s (`kubectl apply -k …`) queda
para la CI o un humano con acceso al k3s/cluster de test. Procedimiento
completo en [`deploy/README.md`](../../deploy/README.md#validación-local-con-k3s).

## 4. Cómo deshacer / parar

```bash
./scripts/stop-all.sh
```

## 5. Si algo falla

- **`rpk cluster health` no termina**: el listener no está listo; revisar
  `docker logs redpanda`.
- **Debezium en `FAILED`**: tema de offsets internos (`connect-configs`,
  `connect-offsets`, `connect-statuses`). Con `RedpandaContainer` /
  `redpanda` en single-node, RF=1 en esos topics internos basta
  (`redpanda-init-topics` los crea así); ver
  [`broker-de-mensajeria.md`](../../sdd/common/broker-de-mensajeria.md) §4.
- **App no arranca**: `MESSAGING_BOOTSTRAP` no se ha cargado; revisar
  `scripts/env/local.env` y que se haya hecho `source`.
- **`docker compose` levanta Kafka en vez de Redpanda**: ¿el compose
  está actualizado? Verificar `external-services/docker-compose.yml` —
  debe tener `redpanda` y NO `zookeeper`/`kafka-broker`. Lo vigila
  `TopologyTest` en el build.