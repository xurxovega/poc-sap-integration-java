# External Services

Infraestructura externa necesaria para ejecutar `poc-sap-integration-java` en local.

Equivalente al `docker-compose.yml` del proyecto Python de referencia, pero adaptado a las URLs y credenciales por defecto de las apps Java.

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

## Arrancar

```bash
cd external-services
docker compose up -d
```

## CDC end-to-end (Debezium)

Los legacy tienen tablas outbox (`dbo.outbox_customer` en SQL Server,
`outbox_article` en Postgres) rellenadas por triggers; Debezium las captura y
publica el contrato JSON en `outbox.CUSTOMER` / `outbox.ARTICLE`.

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
