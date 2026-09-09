# Quick Start — arrancar el proyecto

> De repo recién clonado a **las dos apps procesando eventos y llamando a SAP**.
> Con el script del [anexo](#anexo--arranque-con-un-solo-comando), un comando y
> ~15 minutos (la mayor parte es esperar a SQL Server). El resto de la guía
> explica cada paso por si algo falla o quieres levantar solo una parte.
>
> Para entender *qué* hace el proyecto antes de arrancarlo, empieza por
> [`docs/architecture/OVERVIEW.md`](architecture/OVERVIEW.md) y los specs por
> feature en [`docs/sdd/`](sdd/README.md).
> Para probarlo a fondo (resiliencia, DLT, CSRF, tenant real), sigue después con
> [`docs/testing/GUIA-PRUEBAS.md`](testing/GUIA-PRUEBAS.md).

---

## TL;DR

```bash
./scripts/start-all.sh              # infra + SAP simulado + compilación + apps
./scripts/start-all.sh --with-cdc   # ...y además registra los conectores Debezium
./scripts/stop-all.sh               # parar todo
```

El script espera a que cada pieza esté sana antes de seguir con la siguiente:
detalle de fases y tiempos en el [anexo](#anexo--arranque-con-un-solo-comando).

Cuando termine, el smoke test:

```bash
curl -s -X POST http://localhost:8081/customers/sync \
  -H "Content-Type: application/json" \
  -d '{"entityId":"CUST-001","operation":"UPDATE","payloadHash":"quickstart-1","payload":"{}"}'
# → {"entityId":"CUST-001","state":"SENT_SAP"}
```

Si responde `SENT_SAP`, el pipeline completo (legacy → validación → Mongo →
Elasticsearch → SAP) está funcionando.

### Qué levanta y qué no

| | Estado |
|---|---|
| Kafka + Zookeeper, SQL Server, PostgreSQL, MongoDB, Elasticsearch, Kibana, MinIO | ✅ `docker compose` de `external-services/` |
| Seeds y DDL del legacy (`sqlserver-init`, `minio-init`) | ✅ contenedores one-shot del compose |
| SAP simulado (WireMock) con stub catch-all | ✅ lo levanta el script (no está en el compose) |
| `customer-app` (8081) y `article-app` (8082) | ✅ compiladas y arrancadas por el script |
| Conectores Debezium (CDC real) | ⚠️ solo con `--with-cdc`; sin ellos la ingesta es REST/Kafka manual |
| `supplier-app` | ❌ placeholder no desplegable (`SupplierApplicationPlaceholder` lanza `UnsupportedOperationException`) |
| Prometheus / Grafana / colector OTLP | 🔜 **aplazado a propósito**. Las apps ya exponen `/actuator/prometheus` y aceptan el javaagent de OTel; el stack de observabilidad se montará más adelante |

---

## Modos de arranque: local vs test

Las **apps Java siempre corren en local** (`mvn spring-boot:run` en tu equipo).
Lo que cambia entre modos es **contra qué external-services hablan**:

| | `local` | `test` |
|---|---|---|
| Apps `customer` / `article` | en tu equipo | en tu equipo |
| Kafka, BD, Mongo, ES | contenedores de `external-services/` | servidores de test |
| SAP | WireMock local (`:8090`) | WireMock local **o** tenant SAP de test |
| Comando | `./scripts/start-all.sh` | `./scripts/start-all.sh --env test` |
| Configuración | [`scripts/env/local.env`](../scripts/env/local.env) (commiteado) | `scripts/env/test.env` (a partir de [`test.env.example`](../scripts/env/test.env.example), **gitignored**) |

La migración a test es **servicio a servicio**: cada bloque que dejes comentado
en `test.env` seguirá usando el default local. Puedes mover primero Kafka,
después las bases de datos, y así sucesivamente.

> Las apps leen toda su configuración de variables de entorno con default local
> (ver [`common/src/main/resources/application-common.yml`](../common/src/main/resources/application-common.yml)
> y el `application.yml` de cada dominio), así que **no hay perfiles Spring que
> mantener**: apuntar a test es solo exportar variables distintas.

---

## 0. Requisitos previos

| Requisito | Versión | Notas |
|---|---|---|
| JDK | **25 LTS** (recomendado) | Con 23 compila por defecto; con 21 usar `-Dmaven.compiler.release=21` |
| Maven | 3.9+ | No hay wrapper en el repo: usa el `mvn` del sistema |
| Docker | con ~6 GB libres | Solo en modo `local` |
| `curl` | — | `jq` opcional pero muy recomendable |
| Bash | — | En Windows, **Git Bash** o WSL: los scripts y los ejemplos son POSIX |

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

Recomendable antes de arrancar nada — la suite no necesita Docker:

```bash
mvn clean test                        # 240 tests (unit, slice, resiliencia, smoke de contexto)
```

Los smoke `CustomerApplicationContextTest` / `ArticleApplicationContextTest`
levantan el contexto Spring completo sin infraestructura: si pasan, la app
arrancará. Detalle en [`docs/testing/TESTING.md`](testing/TESTING.md).

---

## 2. Levantar los external-services (modo local)

Toda la infraestructura de terceros se levanta con un único compose:

```bash
cd external-services
docker compose up -d
docker compose ps                     # espera a que todo esté healthy
```

> `docker compose up -d` espera solo a lo que tiene `depends_on ...
> service_healthy` (SQL Server y PostgreSQL, por `kafka-connect`). **Kibana, los
> contenedores `*-init` y el resto siguen arrancando por detrás**, y arrancar las
> apps antes de que `sqlserver-init` cargue el DDL falla. El script del anexo
> espera a todos y aborta con un mensaje claro si alguno no llega.

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
| MySQL | `mysql-sdd` | `3306` | **Registro de features SDD** (BD `sdd_registry`), no es una BD de la app | `sdd` / `sdd` |

Las apps ya traen estos valores como **defaults**: en local no hace falta
configurar nada.

> **SQL Server tarda**: el primer arranque puede llegar a varios minutos
> (`start_period: 600s` en su healthcheck, por el upgrade interno de `msdb`).
> El contenedor `sqlserver-init` ejecuta el DDL cuando el motor está sano —
> hasta entonces `customer-app` fallará al arrancar (`ddl-auto: validate`).

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

## 3. Conectar contra los servidores de test

Cuando un servicio deje de estar en tu equipo y pase a un servidor de test:

```bash
cp scripts/env/test.env.example scripts/env/test.env
# rellenar hosts y credenciales de los servicios ya migrados
./scripts/start-all.sh --env test
```

`scripts/env/test.env` está en `.gitignore`. **Nunca commitees credenciales.**

Qué hace el script en modo `test`:

- **No** levanta contenedores ni el mock de SAP.
- Carga `scripts/env/test.env` y exporta todo al entorno de las apps.
- Comprueba que Elasticsearch responde antes de arrancar nada.
- Compila y arranca las dos apps en local, ya apuntando a test.

Variables por bloque:

| Bloque | Variables |
|---|---|
| Legacy customer | `SQLSERVER_URL`, `SQLSERVER_USER`, `SQLSERVER_PASSWORD` |
| Legacy article | `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` |
| Imagen + estado | `MONGO_URL_CUSTOMER`, `MONGO_URL_ARTICLE` — una base por dominio (con `?authSource=admin` si el Mongo de test tiene auth) |
| Histórico | `ES_URL` |
| Mensajería | `KAFKA_BOOTSTRAP`, `KAFKA_CONNECT_URL` |
| SAP BTP | `SAP_BTP_BASE_URL`, `SAP_BTP_TOKEN_URL`, `SAP_BTP_CLIENT_ID`, `SAP_BTP_CLIENT_SECRET` |
| SAP S/4 | `SAP_S4_BASE_URL`, `SAP_S4_AUTH_TYPE`, `SAP_S4_TOKEN_URL`, `SAP_S4_CLIENT_ID`, `SAP_S4_CLIENT_SECRET`, `SAP_S4_CSRF_ENABLED` |
| Timeouts | `SAP_CLIENT_CONNECT_TIMEOUT_MS`, `SAP_CLIENT_RESPONSE_TIMEOUT_MS` (súbelos contra remotos) |

> **Sin credenciales OAuth2 los auth providers caen a un token stub.** Sirve
> contra el mock, pero contra un tenant real devolverá 401: si apuntas a SAP de
> test, rellena las credenciales **y** pon `SAP_S4_CSRF_ENABLED=true` (las
> escrituras OData V2 lo exigen).

---

## 4. Simular SAP (WireMock)

Las apps envían a SAP en cada sync. Sin tenant real, levanta el mock:

```bash
docker run --rm -d -p 8090:8080 --name mock-sap wiremock/wiremock:3.13.0

curl -s -X POST http://localhost:8090/__admin/mappings \
  -H 'Content-Type: application/json' -d '{
  "priority": 10,
  "request": { "method": "ANY", "urlPattern": ".*" },
  "response": { "status": 201, "jsonBody": { "ok": true } }
}'
```

Para ver qué recibió el "SAP":

```bash
curl -s http://localhost:8090/__admin/requests | jq '.requests[] | {url: .request.url, body: .request.body}'
```

En modo `local` el script hace estos dos pasos por ti.

---

## 5. Arrancar las aplicaciones

Cada dominio es una app Spring Boot independiente. Desde la raíz del repo:

```bash
# customer-app (8081)
SAP_BTP_BASE_URL=http://localhost:8090 \
SAP_S4_BASE_URL=http://localhost:8090 \
mvn -pl customer spring-boot:run

# article-app (8082)
SAP_S4_BASE_URL=http://localhost:8090 \
mvn -pl article spring-boot:run
```

> **Sin `-am`.** Con `-am`, el goal `spring-boot:run` se ejecuta también sobre el
> POM padre, que no tiene main class, y el build falla con *«Unable to find a
> suitable main class»*. Las dependencias las aporta el `mvn install` del paso 1.

```bash
curl -s http://localhost:8081/actuator/health        # {"status":"UP",...}
curl -s http://localhost:8082/actuator/health
```

> `supplier` está en el reactor solo como placeholder estructural: **no es
> desplegable** todavía.

### Variables de entorno útiles

| Variable | Default | Para qué |
|---|---|---|
| `CUSTOMER_SERVER_PORT` / `ARTICLE_SERVER_PORT` | `8081` / `8082` | Cambiar puerto de la app |
| `SAP_S4_BASE_URL` / `SAP_BTP_BASE_URL` | tenant de ejemplo | Base URL de S/4 y BTP (o del mock) |
| `SQLSERVER_URL` / `POSTGRES_URL` | `localhost:1433` / `localhost:5432` | Legacy source |
| `MONGO_URL_CUSTOMER` / `MONGO_URL_ARTICLE` | `mongodb://localhost:27017/customer` / `.../article` | Imagen + estado. Una base **por dominio**: `MONGO_URL` sigue valiendo como fallback, pero serviría la misma a las dos apps |
| `ES_URL` | `http://localhost:9200` | Histórico |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Broker |
| `SAP_ODATA_*_ENABLED` | `false` | Conmuta cada adaptador OData S/4 (por defecto se usa la ruta BTP) |

Lista completa con sus defaults en [`scripts/env/local.env`](../scripts/env/local.env).

---

## 6. Primer smoke test (ingesta REST)

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

## 7. CDC end-to-end con Debezium

Para ver el flujo real legacy → outbox → Debezium → Kafka → app → SAP. Con
`./scripts/start-all.sh --with-cdc` el paso 1 ya está hecho.

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

La app, si está arrancada, consume el topic y repite el pipeline del paso 6.
Formato del mensaje, SMTs y límites conocidos en
[`external-services/debezium/README.md`](../external-services/debezium/README.md).

---

## 8. Herramientas de inspección

Además de los contenedores, estas herramientas te dejan mirar dentro de cada
pieza. Las de escritorio (Postman, DBeaver, Compass) se instalan en tu equipo y
se conectan a los puertos publicados; sirven igual en modo `local` y en `test`
cambiando el host.

### Postman — API de las apps y del SAP simulado

Colección lista para importar:
[`scripts/postman/poc-sap-integration.postman_collection.json`](../scripts/postman/poc-sap-integration.postman_collection.json)

*Import → File →* selecciona el JSON. Incluye 18 peticiones en cuatro carpetas:

| Carpeta | Qué trae |
|---|---|
| **Customer** | sync (genera un `payloadHash` único en cada envío para no toparse con el dedupe), validate, histórico, diff, health, métricas |
| **Article** | equivalentes del dominio article |
| **SAP simulado** | ver peticiones recibidas, borrar el registro, stub catch-all 201, **stub de error 500** para provocar retry/circuit breaker, reset |
| **Infraestructura** | estado de los conectores Debezium, salud de ES, búsqueda del histórico |

Variables de la colección: `customerUrl`, `articleUrl`, `mockSapUrl`,
`connectUrl`, `esUrl`, `customerId`, `articleId`. Para apuntar a test, duplica
el entorno y cambia los hosts.

Alternativas con el mismo contrato: `curl` (todos los ejemplos de esta guía),
Insomnia o Bruno importando la misma colección.

### DBeaver (o cualquier cliente SQL) — los legacy

Un único cliente vale para las dos bases:

| Conexión | Driver | Host / Puerto | BD | Usuario / Clave |
|---|---|---|---|---|
| Legacy **customer** | SQL Server | `localhost:1433` | `poc` | `sa` / `SqlServer_Pa55w0rd!` |
| Legacy **article** | PostgreSQL | `localhost:5432` | `poc` | `postgres` / `postgres` |
| Registro de features SDD | MySQL | `localhost:3306` | `sdd_registry` | `sdd` / `sdd` |

En SQL Server marca **Trust server certificate** (la conexión usa
`encrypt=true;trustServerCertificate=true`). Tablas interesantes:
`dbo.customers` y `dbo.outbox_customer` (SQL Server), `articles` y
`outbox_article` (PostgreSQL): ahí se ve el efecto de los triggers antes de que
Debezium publique.

Sin instalar nada, los clientes ya van dentro de los contenedores:

```bash
docker exec -it sqlserver-source /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P 'SqlServer_Pa55w0rd!' -d poc
docker exec -it postgres-source psql -U postgres -d poc
```

Equivalentes gráficos: Azure Data Studio (SQL Server), pgAdmin (PostgreSQL).

### Kibana / ELK — el histórico indexado

Ya levantado en http://localhost:5601 (sin auth). Para consultarlo:

1. **Dev Tools** (lo más rápido, no necesita configuración):
   ```
   GET customers_history/_search
   { "query": { "term": { "customerId": "CUST-001" } } }
   ```
2. **Discover**: crea antes un *data view* sobre `customers_history` (y
   `articles_history`), campo de tiempo `timestamp`.

> Los índices se crean con `createIndex=false`: **no existen hasta la primera
> escritura**. Si Kibana no los ve, lanza un sync primero.

Logstash no está en el compose (se omitió por simplicidad); el "ELK" aquí es
Elasticsearch + Kibana.

### MongoDB Compass / mongosh — imagen actual y estado de sync

Cadena de conexión: `mongodb://localhost:27017` (sin auth). Bases de datos
`customer` y `article`, con:

| Colección | Contenido |
|---|---|
| `customers_current` / `articles_current` | imagen actual de la entidad |
| `sync_state` | una fila por transición de la máquina de estados, con `timestamp`, origen y `payloadHash` |

```bash
docker exec mongodb mongosh customer --quiet --eval \
  'db.sync_state.find({entityId:"CUST-001"}).sort({timestamp:-1}).limit(5)'
```

### Kafka — topics, mensajes y DLT

Sin UI en el compose; las herramientas del broker bastan:

```bash
docker exec kafka-broker kafka-topics --bootstrap-server localhost:9092 --list
docker exec kafka-broker kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic outbox.CUSTOMER --from-beginning --max-messages 5
docker exec kafka-broker kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic outbox.CUSTOMER.DLT --from-beginning     # mensajes que agotaron los reintentos
```

Si prefieres UI, cualquier cliente externo apuntando a `localhost:9092`
(Offset Explorer, Redpanda Console, AKHQ) funciona sin tocar el compose.

### MinIO — consola S3

http://localhost:9001, `minioadmin` / `minioadmin123`. El bucket
`sap-integration` lo crea `minio-init`. **Hoy no lo usa ninguna app**: está
preparado para un futuro `S3ImageStoreAdapter`.

### Métricas y trazas

- `http://localhost:8081/actuator/prometheus` y `:8082/...` — métricas por
  dominio y estado, en formato Prometheus.
- `http://localhost:8081/actuator/health` — salud con detalle.
- **No hay Prometheus ni Grafana en el compose**: para graficarlas necesitas
  levantarlos aparte y apuntarlos a esos endpoints.
- Trazas: arrancar la JVM con el **javaagent de OpenTelemetry** y
  `OTEL_EXPORTER_OTLP_ENDPOINT` (el starter Spring de OTel no soporta Boot 4).
  Tampoco hay colector en el compose.

---

## 9. Referencia rápida de puertos

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
| `localhost:9092` | Kafka (broker) |
| `localhost:1433` / `localhost:5432` / `localhost:3306` | SQL Server / PostgreSQL / MySQL (registro SDD) |
| `localhost:27017` | MongoDB |

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

## 10. Parar todo

```bash
./scripts/stop-all.sh              # apps + mock SAP + contenedores
./scripts/stop-all.sh --apps-only  # solo las apps Java
./scripts/stop-all.sh --purge      # además borra volúmenes (reset total de datos y seeds)
```

A mano: `Ctrl+C` en cada terminal de `spring-boot:run`, `docker stop mock-sap` y
`docker compose down [-v]` en `external-services/`.

---

## 11. Problemas frecuentes

| Síntoma | Causa / solución |
|---|---|
| `customer-app` falla al arrancar con error de esquema JPA | SQL Server aún no ha ejecutado el DDL. `docker compose ps` → espera a `sqlserver-source` healthy y a que `sqlserver-init` termine |
| `elasticsearch` se reinicia en bucle | `sudo sysctl -w vm.max_map_count=262144` |
| Puerto `8083` ocupado | Lo usa Kafka Connect. Es también el default de `supplier` (no desplegable); si necesitas ese puerto, para el compose o cambia el mapeo |
| Error de compilación por versión de Java | `mvn compile -Dmaven.compiler.release=21` (o instala JDK 25) |
| `SENT_SAP` inmediato sin llamadas al mock | Dedupe por `payloadHash` idéntico: usa un hash distinto |
| Estado `SAP_ERROR` | El mock no responde 2xx o no está levantado: revisa el stub y `SAP_*_BASE_URL` |
| `401` contra SAP de test | Faltan credenciales OAuth2 → se usó el token stub. Rellena `SAP_S4_CLIENT_ID`/`SECRET`/`TOKEN_URL` en `test.env` |
| `403` en escrituras contra S/4 real | CSRF: pon `SAP_S4_CSRF_ENABLED=true` |
| Los conectores Debezium no publican nada | Los triggers se crean después del seed: el estado inicial no genera eventos. Lanza un `UPDATE` |
| Kibana no encuentra `customers_history` | El índice se crea en la primera escritura (`createIndex=false`): lanza un sync antes |
| El script falla en `wait_healthy sqlserver-source` | Primer arranque de SQL Server con poca RAM. Sube la memoria de Docker (~6 GB) y reintenta; el volumen conserva el trabajo hecho |
| Tests de integración fallan sin Docker | Necesitan daemon Docker: `mvn -pl it verify -Ddocker.available=true` |

---

## 12. Siguientes pasos

1. **Cómo desarrollar aquí** — [`AGENTS.md`](../AGENTS.md) y [`docs/architecture/DESARROLLO.md`](architecture/DESARROLLO.md): ciclo SDD + TDD.
2. **Entender el dominio** — [`docs/architecture/OVERVIEW.md`](architecture/OVERVIEW.md) y [`docs/GLOSSARY.md`](GLOSSARY.md).
3. **Entender el código** — [`docs/architecture/FLOWS.md`](architecture/FLOWS.md): flujos con nombres de clase.
4. **Patrones de integración** — [`docs/architecture/INTEGRATION-PATTERNS.md`](architecture/INTEGRATION-PATTERNS.md).
5. **Probar a fondo** — [`docs/testing/GUIA-PRUEBAS.md`](testing/GUIA-PRUEBAS.md): resiliencia, DLT, CSRF, métricas, tenant real.
6. **Estado del proyecto** — [`docs/sdd/README.md`](sdd/README.md): índice de features, brechas resueltas y pendientes.

---

# Anexo — Arranque con un solo comando

[`scripts/start-all.sh`](../scripts/start-all.sh) hace todo lo anterior en
orden, **esperando a que cada pieza esté sana antes de seguir**. No usa esperas
fijas: consulta el healthcheck de cada contenedor cada 5 s hasta un tope, y
aborta con el `docker logs` a mirar si algo no llega.

```bash
./scripts/start-all.sh                  # todo en local (contenedores + apps)
./scripts/start-all.sh --env test       # apps en local contra servicios de test
./scripts/start-all.sh --with-cdc       # además registra los conectores Debezium
./scripts/start-all.sh --no-apps        # solo infraestructura
./scripts/start-all.sh --skip-build     # no recompila el reactor
```

## Fases y tiempos

Medido en un equipo con 8 CPU y 8 GB asignados a Docker.

| # | Fase | Espera a | Tope | 1ª vez | Ya cacheado |
|---|---|---|---|---|---|
| 1 | Requisitos | `curl`, `mvn`, `docker`, daemon vivo | — | instantáneo | instantáneo |
| 2 | `docker compose up -d` | **descarga de imágenes** (~3 GB) y arranque | — | **5–7 min** | ~10 s |
| 3-12 | Healthchecks de los 8 servicios + los dos `*-init` | `healthy` / exit 0 | 120–900 s según servicio | ES ~40 s, Kibana ~65 s, resto ya sanos | 0 s |
| 13 | WireMock | `/__admin/mappings` + alta del stub | 60 s | ~20 s (incluye pull) | ~5 s |
| 14 | *(`--with-cdc`)* conectores | alta + 10 s a `RUNNING` | — | ~15 s | ~10 s |
| 15 | `mvn clean install -DskipTests` | build del reactor | — | 2–3 min | ~1,5 min |
| 16 | `customer-app`, `article-app` | `/actuator/health` responde | 300 s c/u | ~30 s c/u | ~30 s c/u |

**Total en frío: ~12–15 min**, dominado por la descarga de imágenes. Con las
imágenes y los volúmenes ya creados baja a **~3 min**, y con `--skip-build` a
poco más de un minuto.

> Los healthchecks individuales suelen marcar 0 s porque `docker compose up -d`
> **no vuelve enseguida**: `kafka-connect` declara `depends_on ... service_healthy`
> sobre SQL Server y PostgreSQL, así que el propio `up` ya espera a que esos dos
> estén sanos. Las esperas del script siguen siendo necesarias para Kibana, los
> contenedores `*-init` y las apps, y para que el fallo sea explícito.

## Qué imprime

Conforme cada servicio pasa a *healthy*, el script muestra **dónde está**:

```
  … sqlserver-source     healthy (312s)
      → jdbc:sqlserver://localhost:1433;databaseName=poc;...  ·  sa / SqlServer_Pa55w0rd!
  … kibana               healthy (58s)
      → http://localhost:5601  ·  Dev Tools para consultar los índices
```

Y al terminar, un **inventario completo agrupado**: aplicaciones (con sus
endpoints), SAP, bases de datos (URL JDBC + host/puerto/BD/usuario/clave, listos
para pegar en DBeaver), mensajería, ELK y almacenamiento — más el smoke test en
una línea y cómo parar todo. En modo `test` el inventario muestra los hosts de
test y **no imprime las contraseñas**, solo remite a `scripts/env/test.env`.

## Detalles de comportamiento

- **Idempotente**: se puede relanzar. Si `mock-sap` ya corre lo reutiliza, y si
  una app ya está viva (por su PID en `logs/run/`) no la duplica.
- **Apps en segundo plano**: salida a `logs/customer-app.log` y
  `logs/article-app.log` (`logs/` está en `.gitignore`). Sigue el arranque con
  `tail -f logs/customer-app.log`.
- **Fallo temprano**: `set -euo pipefail`. Si un contenedor muere o agota su
  tope, el script para y dice qué `docker logs` mirar, en vez de dejar las apps
  arrancando contra una infraestructura a medias.
- **Modo `test`**: se salta las fases 2–14 (no hay contenedores), verifica que
  Elasticsearch responde y arranca solo las apps con las variables de
  `scripts/env/test.env`. Si quieres seguir usando el WireMock local, levántalo
  a mano (paso [4](#4-simular-sap-wiremock)) y apunta ahí `SAP_*_BASE_URL`.
- **Windows**: ejecútalo desde **Git Bash** o WSL.
- **Los ficheros de entorno se cargan con `source`**: los valores van entre
  comillas simples. Sin ellas, el `;` de la URL JDBC de SQL Server la truncaría
  a la mitad y la app no arrancaría.

Parada: [`scripts/stop-all.sh`](../scripts/stop-all.sh) — mata las apps por su
PID, para el mock y baja el compose (`--purge` para borrar también los
volúmenes y regenerar los seeds en el siguiente arranque).
