# Quick Start — arrancar el proyecto en local

> Guía de arranque rápido: de repo recién clonado a **customer-app procesando
> un evento y llamando a un SAP simulado** en ~15 minutos (la mayor parte es
> esperar a que arranque SQL Server).
>
> Para entender *qué* hace el proyecto antes de arrancarlo, empieza por
> [`docs/specs/SPEC.md`](specs/SPEC.md) y
> [`docs/architecture/OVERVIEW.md`](architecture/OVERVIEW.md).
> Para probarlo a fondo (resiliencia, DLT, CSRF, tenant real), sigue después con
> [`docs/testing/GUIA-PRUEBAS.md`](testing/GUIA-PRUEBAS.md).

---

## TL;DR

```bash
# 1. Infraestructura local (Kafka, SQL Server, Postgres, Mongo, ES, MinIO)
cd external-services && docker compose up -d && cd ..

# 2. SAP simulado (WireMock) — las apps necesitan un endpoint al que llamar
docker run --rm -d -p 8090:8080 --name mock-sap wiremock/wiremock:3.13.0
curl -s -X POST http://localhost:8090/__admin/mappings -d '{
  "priority": 10,
  "request": { "method": "ANY", "urlPattern": ".*" },
  "response": { "status": 201, "jsonBody": { "ok": true } }
}'

# 3. Arrancar customer-app apuntando al mock
SAP_BTP_BASE_URL=http://localhost:8090 \
SAP_S4_BASE_URL=http://localhost:8090 \
mvn -pl customer -am spring-boot:run

# 4. Smoke test (en otra terminal)
curl -s http://localhost:8081/actuator/health
curl -s -X POST http://localhost:8081/customers/sync \
  -H "Content-Type: application/json" \
  -d '{"entityId":"CUST-001","operation":"UPDATE","payloadHash":"quickstart-1","payload":"{}"}'
# → {"entityId":"CUST-001","state":"SENT_SAP"}
```

Si eso responde `SENT_SAP`, el pipeline completo (SQL Server → validación →
Mongo → Elasticsearch → SAP) está funcionando. El resto de la guía explica cada
paso, cómo verificarlo y qué hacer si falla.

---

## 0. Requisitos previos

| Requisito | Versión | Notas |
|---|---|---|
| JDK | **25 LTS** (recomendado) | Con 23 compila por defecto; con 21 usar `-Dmaven.compiler.release=21` |
| Maven | 3.9+ | No hay wrapper en el repo: usa el `mvn` del sistema |
| Docker | con ~6 GB libres | Kafka, Connect, SQL Server, Postgres, Mongo, ES, MinIO |
| `curl` | — | `jq` opcional pero muy recomendable |

Comprobación rápida:

```bash
java -version    # openjdk 25.x  (o 23/21, ver arriba)
mvn -v
docker info | head -5
```

En WSL sin JDK 25 del sistema, descomprime Temurin 25 en `~/.jdks` y exporta:

```bash
export JAVA_HOME=$HOME/.jdks/jdk-25.0.3+9
export PATH=$JAVA_HOME/bin:$PATH
```

En Linux/WSL, Elasticsearch necesita:

```bash
sudo sysctl -w vm.max_map_count=262144
```

---

## 1. Compilar el reactor

```bash
mvn validate                          # valida el reactor multi-módulo
mvn clean install -DskipTests         # compila e instala todos los módulos
```

Si solo vas a tocar un dominio, basta con instalar el shared kernel:

```bash
mvn -pl common install -DskipTests
```

Opcional pero recomendable antes de arrancar nada — la suite no necesita Docker:

```bash
mvn clean test                        # 211 tests (unit, slice, resiliencia, smoke de contexto)
```

Los smoke `CustomerApplicationContextTest` / `ArticleApplicationContextTest`
levantan el contexto Spring completo sin infraestructura: si pasan, la app
arrancará. Detalle en [`docs/testing/TESTING.md`](testing/TESTING.md).

---

## 2. Levantar los external-services (contenedores)

Toda la infraestructura de terceros se levanta con un único compose:

```bash
cd external-services
docker compose up -d
docker compose ps                     # espera a que todo esté healthy
```

| Servicio | Contenedor | Puerto host | Uso | Credenciales |
|---|---|---|---|---|
| Kafka | `kafka-broker` | `9092` | Topics `outbox.CUSTOMER` / `outbox.ARTICLE` | — |
| Zookeeper | `zookeeper` | `2181` | Coordinación de Kafka | — |
| Kafka Connect (Debezium) | `kafka-connect` | `8083` | CDC de las outbox legacy | — |
| SQL Server | `sqlserver-source` | `1433` | Legacy de **customer** (BD `poc`) | `sa` / `SqlServer_Pa55w0rd!` |
| PostgreSQL | `postgres-source` | `5432` | Legacy de **article** (BD `poc`) | `postgres` / `postgres` |
| MongoDB | `mongodb` | `27017` | Imagen actual + `sync_state` | sin auth |
| Elasticsearch | `elasticsearch` | `9200` | Histórico de versiones enviadas | sin auth |
| Kibana | `kibana` | `5601` | UI de Elasticsearch | sin auth |
| MinIO (S3) | `minio` | `9000` / `9001` | Objetos (uso futuro) | `minioadmin` / `minioadmin123` |

Las apps Java ya traen estos valores como **defaults**, así que no hace falta
configurar nada para el arranque local.

> **SQL Server tarda**: el primer arranque puede llegar a varios minutos
> (`start_period: 600s` en el healthcheck). El contenedor `sqlserver-init`
> ejecuta el DDL cuando el motor está sano — hasta entonces `customer-app`
> fallará al arrancar (`ddl-auto: validate`).

Comprobación de que el seed está cargado:

```bash
# Customers de ejemplo: CUST-001, CUST-002
docker exec sqlserver-source /opt/mssql-tools18/bin/sqlcmd -C \
  -S localhost -U sa -P 'SqlServer_Pa55w0rd!' -d poc \
  -Q "SELECT id, name FROM dbo.customers"

# Articles de ejemplo: ART-001, ART-002, ART-003
docker exec postgres-source psql -U postgres -d poc -c "SELECT id, description FROM articles"
```

Detalle completo (CDC, triggers, notas por servicio) en
[`external-services/README.md`](../external-services/README.md).

---

## 3. Simular SAP (WireMock)

Las apps envían a SAP en cada sync. Sin un tenant real, levanta un mock:

```bash
docker run --rm -d -p 8090:8080 --name mock-sap wiremock/wiremock:3.13.0

curl -s -X POST http://localhost:8090/__admin/mappings -d '{
  "priority": 10,
  "request": { "method": "ANY", "urlPattern": ".*" },
  "response": { "status": 201, "jsonBody": { "ok": true } }
}'
```

Para ver qué recibió el "SAP":

```bash
curl -s http://localhost:8090/__admin/requests | jq '.requests[] | {url: .request.url, body: .request.body}'
```

Contra un tenant real, en vez del mock exporta las credenciales OAuth2
(`SAP_S4_TOKEN_URL`, `SAP_S4_CLIENT_ID`, `SAP_S4_CLIENT_SECRET`, …); todas las
claves están en [`common/src/main/resources/application-common.yml`](../common/src/main/resources/application-common.yml)
con placeholders `${...}`. **Nunca commitear secretos.**

---

## 4. Arrancar las aplicaciones

Cada dominio es una app Spring Boot independiente. Desde la raíz del repo:

### customer-app (puerto 8081)

```bash
SAP_BTP_BASE_URL=http://localhost:8090 \
SAP_S4_BASE_URL=http://localhost:8090 \
mvn -pl customer -am spring-boot:run
```

```bash
curl -s http://localhost:8081/actuator/health        # {"status":"UP",...}
```

### article-app (puerto 8082)

```bash
SAP_S4_BASE_URL=http://localhost:8090 \
mvn -pl article -am spring-boot:run
```

```bash
curl -s http://localhost:8082/actuator/health
```

> `supplier` está en el reactor solo como placeholder estructural: **no es
> desplegable** todavía (`SupplierApplicationPlaceholder` lanza
> `UnsupportedOperationException`).

### Variables de entorno útiles

| Variable | Default | Para qué |
|---|---|---|
| `CUSTOMER_SERVER_PORT` / `ARTICLE_SERVER_PORT` | `8081` / `8082` | Cambiar puerto de la app |
| `SAP_S4_BASE_URL` | tenant de ejemplo | Base URL de S/4 (o del mock) |
| `SAP_BTP_BASE_URL` | tenant de ejemplo | Base URL de BTP (o del mock) |
| `SQLSERVER_URL` / `POSTGRES_URL` | `localhost:1433` / `localhost:5432` | Legacy source |
| `MONGO_URL` | `mongodb://localhost:27017/<dominio>` | Imagen + estado |
| `ES_URL` | `http://localhost:9200` | Histórico |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Broker |
| `SAP_ODATA_*_ENABLED` | `false` | Conmuta cada adaptador OData S/4 (por defecto se usa la ruta BTP) |

---

## 5. Primer smoke test (ingesta REST)

El endpoint REST reusa exactamente el mismo pipeline que CDC. El use case
**re-lee la entidad del legacy por `entityId`**, así que usa un id seedeado:

```bash
curl -s -X POST http://localhost:8081/customers/sync \
  -H "Content-Type: application/json" \
  -d '{"entityId":"CUST-001","operation":"UPDATE","payloadHash":"quickstart-1","payload":"{}"}'
# → {"entityId":"CUST-001","state":"SENT_SAP"}
```

Verifica cada eslabón de la cadena:

```bash
# 1. Llamadas recibidas por el SAP simulado
curl -s http://localhost:8090/__admin/requests | jq '.requests | length'

# 2. Estado e imagen en Mongo
docker exec mongodb mongosh customer --quiet --eval \
  'db.sync_state.find({entityId:"CUST-001"}).sort({timestamp:-1}).limit(3)'
docker exec mongodb mongosh customer --quiet --eval \
  'db.customers_current.findOne({_id:"CUST-001"})'

# 3. Histórico en Elasticsearch
curl -s "http://localhost:9200/customers_history/_search?q=customerId:CUST-001" | jq '.hits.total'

# 4. Histórico y diff por REST
curl -s http://localhost:8081/customers/CUST-001/history | jq
curl -s http://localhost:8081/customers/CUST-001/history/diff | jq
```

Repetir el mismo `curl` con el **mismo** `payloadHash` responde `SENT_SAP` al
instante sin llamar a SAP (dedupe por idempotencia). Cambia el hash para forzar
el ciclo completo otra vez.

Equivalente para article:

```bash
curl -s -X POST http://localhost:8082/articles/sync \
  -H "Content-Type: application/json" \
  -d '{"entityId":"ART-001","operation":"UPDATE","payloadHash":"quickstart-art-1","payload":"{}"}'
```

---

## 6. (Opcional) CDC end-to-end con Debezium

Para ver el flujo real legacy → outbox → Debezium → Kafka → app → SAP:

```bash
# 1. Registrar los conectores (una sola vez, con kafka-connect healthy)
cd external-services/debezium
curl -i -X POST -H "Content-Type: application/json" \
  http://localhost:8083/connectors/ -d @register-sqlserver-customer.json
curl -i -X POST -H "Content-Type: application/json" \
  http://localhost:8083/connectors/ -d @register-postgres-article.json

curl -s "http://localhost:8083/connectors?expand=status" | jq   # ambos RUNNING

# 2. Provocar un cambio en el legacy (el trigger rellena la outbox)
docker exec sqlserver-source /opt/mssql-tools18/bin/sqlcmd -C \
  -S localhost -U sa -P 'SqlServer_Pa55w0rd!' -d poc \
  -Q "UPDATE dbo.customers SET phone = '+34 600 999 000' WHERE id = 'CUST-001'"

# 3. Ver el mensaje en el topic
docker exec kafka-broker kafka-console-consumer \
  --bootstrap-server localhost:9092 --topic outbox.CUSTOMER \
  --from-beginning --property print.key=true --max-messages 5
```

La app, si está arrancada, consume el topic y repite el pipeline del paso 5.
Formato del mensaje, SMTs y límites conocidos en
[`external-services/debezium/README.md`](../external-services/debezium/README.md).

---

## 7. Referencia rápida de puertos

| URL | Qué es |
|---|---|
| http://localhost:8081/customers | API de customer-app |
| http://localhost:8081/actuator/health | Salud de customer-app |
| http://localhost:8081/actuator/prometheus | Métricas de customer-app |
| http://localhost:8082/articles | API de article-app |
| http://localhost:8083/connectors | Kafka Connect (Debezium) |
| http://localhost:8090/__admin/requests | Peticiones recibidas por el SAP simulado |
| http://localhost:9200 | Elasticsearch |
| http://localhost:5601 | Kibana |
| http://localhost:9001 | Consola de MinIO |

### Endpoints de la aplicación

| Método | Ruta | Qué hace |
|---|---|---|
| `POST` | `/customers/sync` | Ingesta REST: dispara el pipeline completo |
| `POST` | `/customers/validate` | Solo valida y devuelve el estado resultante |
| `GET` | `/customers/{id}/history[?full=true]` | Versiones enviadas a SAP |
| `GET` | `/customers/{id}/history/diff?from=&to=` | Diff entre dos versiones (sin params: últimas dos) |
| `POST` | `/articles/sync` | Ingesta REST del dominio article |
| `GET` | `/articles/{id}/history`, `/articles/{id}/history/diff` | Equivalentes para article |

---

## 8. Parar todo

```bash
# Apps: Ctrl+C en cada terminal de spring-boot:run

docker stop mock-sap

cd external-services
docker compose down        # para los contenedores
docker compose down -v     # ...y borra volúmenes (reset total de datos y seeds)
```

---

## 9. Problemas frecuentes

| Síntoma | Causa / solución |
|---|---|
| `customer-app` falla al arrancar con error de esquema JPA | SQL Server aún no ha ejecutado el DDL. `docker compose ps` → espera a `sqlserver-source` healthy y a que `sqlserver-init` termine |
| `elasticsearch` se reinicia en bucle | `sudo sysctl -w vm.max_map_count=262144` |
| Puerto `8083` ocupado | Lo usa Kafka Connect. Es también el default de `supplier` (no desplegable); si necesitas ese puerto, para el compose o cambia el mapeo |
| Error de compilación por versión de Java | `mvn compile -Dmaven.compiler.release=21` (o instala JDK 25) |
| `SENT_SAP` inmediato sin llamadas al mock | Dedupe por `payloadHash` idéntico: usa un hash distinto |
| Estado `SAP_ERROR` | El mock no responde 2xx o no está levantado: revisa el stub y `SAP_*_BASE_URL` |
| Los conectores Debezium no publican nada | Los triggers se crean después del seed: el estado inicial no genera eventos. Lanza un `UPDATE` |
| Tests de integración fallan sin Docker | Necesitan daemon Docker: `mvn -pl it verify -Ddocker.available=true` |

---

## 10. Siguientes pasos

1. **Entender el dominio** — [`docs/specs/SPEC.md`](specs/SPEC.md) y [`docs/GLOSSARY.md`](GLOSSARY.md).
2. **Entender el código** — [`docs/architecture/OVERVIEW.md`](architecture/OVERVIEW.md) (módulos y capas) y [`docs/architecture/FLOWS.md`](architecture/FLOWS.md) (flujos con nombres de clase).
3. **Patrones de integración** — [`docs/architecture/INTEGRATION-PATTERNS.md`](architecture/INTEGRATION-PATTERNS.md).
4. **Probar a fondo** — [`docs/testing/GUIA-PRUEBAS.md`](testing/GUIA-PRUEBAS.md): resiliencia, DLT, CSRF, métricas, tenant real.
5. **Estado de la integración** — [`docs/integration-guide/README.md`](integration-guide/README.md): brechas resueltas y pendientes.
