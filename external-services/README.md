# External Services

Infraestructura externa necesaria para ejecutar `poc-sap-integration-java` en local.

Equivalente al `docker-compose.yml` del proyecto Python de referencia, pero adaptado a las URLs y credenciales por defecto de las apps Java.

## Servicios incluidos

| Servicio | Puerto | Uso | Credenciales |
|---|---|---|---|
| Zookeeper | `2181` | Coordinación de Kafka | — |
| Kafka | `9092` (`localhost`), `29092` (red Docker) | Eventos CDC y directos | — |
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
- Elasticsearch requiere `vm.max_map_count >= 262144` en Linux/WSL. Si falla: `sudo sysctl -w vm.max_map_count=262144`.
- Kafka expone `localhost:9092` para conexiones desde el host y `kafka-broker:29092` para conexiones entre contenedores.
