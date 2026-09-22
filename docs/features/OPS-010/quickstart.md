# Quickstart — OPS-010 Broker de mensajería: Kafka → Redpanda

> Verifica la sustitución del broker en local, sin tocar el cluster K8s.
> El QUICK_START general está en [`docs/QUICK_START.md`](../../QUICK_START.md).

## 1. Lo que necesitas

- JDK 25, Maven 3.9+ (usar `./mvnw`), Docker.

## 2. Lo que vas a ver

- Un único contenedor `redpanda` (en lugar de `zookeeper` + `kafka-broker`
  + `kafka-init-topics`).
- Arranque más rápido: ~2 s vs ~30 s del stack Kafka+Zookeeper.
- `MESSAGING_BOOTSTRAP` en lugar de `KAFKA_BOOTSTRAP` en logs y variables.

## 3. Pasos

### a) Arrancar todo el stack con el nuevo broker

```bash
cd ../..                              # raíz del repo
./scripts/start-all.sh --with-cdc
```

### b) Comprobar el broker

```bash
docker exec redpanda rpk cluster health
docker exec redpanda rpk topic list
```

Salida esperada: cluster `Healthy`; 4 topics (`outbox.CUSTOMER`,
`outbox.CUSTOMER-dlt`, `outbox.ARTICLE`, `outbox.ARTICLE-dlt`) con 12
particiones cada uno.

### c) Comprobar el renombrado de variable

```bash
set -a; source scripts/env/local.env; set +a
env | grep -E '^(MESSAGING_BOOTSTRAP|KAFKA_BOOTSTRAP)='
```

Salida esperada: `MESSAGING_BOOTSTRAP=localhost:9092`. `KAFKA_BOOTSTRAP`
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

Salida esperada: `RUNNING` (es el equivalente del `DebeziumRedpandaIT`).

## 4. Cómo deshacer / parar

```bash
./scripts/stop-all.sh
```

## 5. Si algo falla

- **`rpk cluster health` no termina**: el listener no está listo; revisar
  `docker logs redpanda`.
- **Debezium en `FAILED`**: tema de offsets internos (`connect-configs`,
  `connect-offsets`, `connect-statuses`). Ver ADR-0014 §3: con
  `kafka_internal_topic_replication_factor=1` en single-node local
  debería bastar.
- **App no arranca**: `MESSAGING_BOOTSTRAP` no se ha cargado; revisar
  `scripts/env/local.env`.
- **`docker compose` levanta Kafka en vez de Redpanda**: ¿el compose
  está actualizado? Verificar `external-services/docker-compose.yml`.