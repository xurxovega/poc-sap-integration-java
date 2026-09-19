# External Services

Infraestructura externa necesaria para ejecutar `poc-sap-integration-java` en local.

Equivalente al `docker-compose.yml` del proyecto Python de referencia, pero adaptado a las URLs y credenciales por defecto de las apps Java.

> Arranque de punta a punta (infra + apps + primer smoke test):
> [`../docs/QUICK_START.md`](../docs/QUICK_START.md).
>
> Para levantar todo esto **esperando a cada healthcheck**, usa
> [`../scripts/start-all.sh`](../scripts/start-all.sh): `docker compose up -d`
> vuelve al instante y los contenedores siguen arrancando por detrás.

## Servicios incluidos

| Servicio | Puerto | Uso | Credenciales |
|---|---|---|---|
| Zookeeper | `2181` | Coordinación de Kafka | — |
| Kafka | `9092` (`localhost`), `29092` (red Docker) | Eventos CDC y directos | — |
| Kafka Connect (Debezium) | `8083` | CDC outbox legacy → topics `outbox.*` | — |
| PostgreSQL | `5432` | Legacy source (artículos) | `postgres` / `postgres` |
| SQL Server | `1433` | Legacy source (clientes) | `sa` / `SqlServer_Pa55w0rd!` |
| MongoDB | `27017` | Imagen actual + estado | sin auth |
| Elasticsearch | `9200` | Histórico / búsqueda | sin auth |
| Kibana | `5601` | UI de Elasticsearch | sin auth |
| MinIO (S3) | `9000` (API), `9001` (console) | Almacenamiento de objetos | `minioadmin` / `minioadmin123` |
| MySQL | `3306` | Registro de features SDD (`sdd_registry`) — **no** es una BD de la aplicación | `sdd` / `sdd` |

## Arrancar

```bash
cd external-services
docker compose up -d
```

El servicio `kafka-init-topics` crea `outbox.CUSTOMER`, `outbox.CUSTOMER-dlt`,
`outbox.ARTICLE` y `outbox.ARTICLE-dlt` con 12 particiones
(`KAFKA_TOPIC_PARTITIONS`, por defecto 12) en cuanto `kafka-broker` está sano,
y termina (`restart: "no"`); las apps **no** crean topics
(`APP_KAFKA_TOPICS_CREATE=false`, ver [`../deploy/README.md`](../deploy/README.md)).

## CDC end-to-end (Debezium)

Los legacy tienen tablas outbox (`dbo.outbox_customer` en SQL Server,
`outbox_article` en Postgres) rellenadas por triggers; Debezium las captura y
publica en `outbox.CUSTOMER` / `outbox.ARTICLE` el **aviso de cambio fino**
(columna `message`: identidad del cambio, sin datos ni PII —
[ADR-0013](../docs/architecture/adr/0013-outbox-mensaje-fino-sin-payload.md)).

1. Levantar todo y esperar a que `kafka-connect` esté sano:

   ```bash
   docker compose up -d
   curl -s http://localhost:8083/ | jq .version
   ```

2. Registrar los dos conectores:

   ```bash
   cd debezium
   curl -i -X POST -H "Content-Type: application/json" \
     http://localhost:8083/connectors/ -d @register-sqlserver-customer.json
   curl -i -X POST -H "Content-Type: application/json" \
     http://localhost:8083/connectors/ -d @register-postgres-article.json
   ```

3. Verificar estado y topics:

   ```bash
   curl -s http://localhost:8083/connectors/outbox-customer-sqlserver/status | jq .connector.state
   curl -s http://localhost:8083/connectors/outbox-article-postgres/status | jq .connector.state

   docker exec -it kafka-broker kafka-topics --bootstrap-server localhost:9092 --list
   docker exec -it kafka-broker kafka-console-consumer \
     --bootstrap-server localhost:9092 --topic outbox.CUSTOMER --from-beginning
   ```

Detalle de los conectores, formato de mensaje y cómo provocar eventos de prueba:
[`debezium/README.md`](debezium/README.md).

## Parar

```bash
docker compose down
```

## Parar y borrar volúmenes

```bash
docker compose down -v
```

## Conexiones desde las apps Java

Las aplicaciones ya tienen valores por defecto que apuntan a estos servicios:

- PostgreSQL: `jdbc:postgresql://localhost:5432/poc`
- SQL Server: `jdbc:sqlserver://localhost:1433;databaseName=poc;...`
- MongoDB: `mongodb://localhost:27017/customer` / `mongodb://localhost:27017/article`
- Elasticsearch: `http://localhost:9200`
- Kafka: `localhost:9092`

## Buckets S3 (MinIO)

Al arrancar se crea automáticamente el bucket `sap-integration` con acceso público.

Consola: http://localhost:9001

## Notas

- SQL Server tarda ~30-60s en arrancar. El contenedor `sqlserver-init` ejecuta el script DDL cuando SQL Server está sano.
- SQL Server corre con `MSSQL_AGENT_ENABLED=true` (el Agent es necesario para los jobs de captura CDC de Debezium) y el `init.sql` habilita CDC sobre la BD y la tabla `dbo.outbox_customer`.
- Elasticsearch requiere `vm.max_map_count >= 262144` en Linux/WSL. Si falla: `sudo sysctl -w vm.max_map_count=262144`.
- Kafka expone `localhost:9092` para conexiones desde el host y `kafka-broker:29092` para conexiones entre contenedores.
- `mongodb/init.js` crea, además de las colecciones de imagen/estado por dominio,
  `sync_state` con el índice `dom_cycle_idx` (`{domain, cycleId, seq}`, sustenta
  la traza de `GET /customers/{id}/state`) y `sap_keys` con `dom_ent_key_idx`
  (`{domain, entityId}`): guarda la clave que asigna SAP (p. ej. `AddressID`)
  para el upsert idempotente (PRD-11); si se pierde, el siguiente ciclo duplica
  en vez de actualizar.
- `scripts/start-all.sh` registra en el mock SAP (WireMock) un stub más
  específico que hace que el `GET` de verificación previa (lookup) devuelva
  `404` ("no existe en SAP") en vez del `201` genérico: así el flujo local
  ejercita también la rama de alta del upsert idempotente, no solo la de
  actualización.
