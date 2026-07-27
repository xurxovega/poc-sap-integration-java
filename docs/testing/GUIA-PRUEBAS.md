# Guía de pruebas — cómo probar la integración

> Guía práctica, de menos a más: desde la suite automática hasta el flujo CDC
> completo (SQL Server → Debezium → Kafka → app → SAP simulado) y las pruebas
> de resiliencia. Todos los comandos están verificados contra la configuración
> real del repo. Catálogo de la suite en [`TESTING.md`](TESTING.md).

## 0. Requisitos

- **JDK 25** (recomendado; ver README). En WSL sin JDK 25 del sistema:
  `export JAVA_HOME=$HOME/.jdks/jdk-25.0.3+9` (o compilar con
  `-Dmaven.compiler.release=21` si solo tienes JDK 21).
- **Docker** con ~6 GB libres (Kafka, Connect, SQL Server, Postgres, Mongo, ES).
- `curl` y opcionalmente `jq`.

---

## Nivel 1 — Suite automática (sin Docker)

```bash
mvn clean test          # 211 tests: unit + slice + resiliencia + smoke de contexto
```

Qué valida cada bloque y dónde mirar si falla:

| Bloque | Qué prueba | Si falla |
|---|---|---|
| `common` dominio | Máquina de estados (estado inicial, re-sync), `ValidationResult`, dedupe Mongo | Regresión en reglas de transición |
| `WebClientSapClientTest` | Retry en 5xx, no-retry en 4xx, cabeceras, CSRF completo (contra WireMock embebido) | Regresión en la capa de resiliencia/CSRF |
| `customer`/`article` unit | Validadores, use cases, adaptadores (payloads con `BusinessPartner` real) | Regresión funcional del pipeline |
| `*ApplicationContextTest` | **El contexto Spring completo de cada app arranca** sin infraestructura | Bean que falta, YAML roto, incompatibilidad Boot 4 — mirar el primer `Caused by` |

> Los smoke de contexto son los que cazan los errores de arranque que los
> unit tests no ven. Si tocas wiring/POMs/YAML, ejecútalos siempre:
> `mvn -pl customer -am test -Dtest=CustomerApplicationContextTest -Dsurefire.failIfNoSpecifiedTests=false`

---

## Nivel 2 — Levantar el entorno local

### 2.1 Infraestructura

```bash
cd external-services
docker compose up -d
docker compose ps          # espera a que kafka-connect esté healthy
```

Servicios y puertos: Kafka `localhost:9092`, Kafka Connect `8083`,
SQL Server `1433` (BD `poc`, sa / `SqlServer_Pa55w0rd!`), Postgres `5432`
(postgres/postgres, BD `poc`), MongoDB `27017`, Elasticsearch `9200`,
Kibana `5601`.

### 2.2 Mock de SAP (WireMock en Docker)

Las apps necesitan un "SAP" al que llamar. Para pruebas locales:

```bash
docker run --rm -d -p 8090:8080 --name mock-sap wiremock/wiremock:3.13.0

# Stub catch-all: acepta cualquier POST/PATCH y responde 201
curl -s -X POST http://localhost:8090/__admin/mappings -d '{
  "priority": 10,
  "request": { "method": "ANY", "urlPattern": ".*" },
  "response": { "status": 201, "jsonBody": { "d": { "ok": true } } }
}'
```

### 2.3 Arrancar customer-app

```bash
cd <raiz-del-repo>
SAP_BTP_BASE_URL=http://localhost:8090 \
SAP_S4_BASE_URL=http://localhost:8090 \
mvn -pl customer -am spring-boot:run
```

Verifica el arranque: `curl http://localhost:8081/actuator/health` → `{"status":"UP"...}`.

> Por defecto van activos los adaptadores **BTP**. Para probar la ruta OData
> nativa (patrón 1), añade `SAP_ODATA_ADDRESS_ENABLED=true`,
> `SAP_ODATA_FISCAL_ENABLED=true`, etc. — cada feature conmuta su adaptador
> (son excluyentes, no habrá beans duplicados).

---

## Nivel 3 — Probar el flujo REST (el más directo)

El endpoint de ingesta reusa el mismo pipeline que CDC. El use case
**re-lee la entidad de SQL Server por `entityId`**, así que usa un id
seedeado (`CUST-001` o `CUST-002`):

```bash
curl -s -X POST http://localhost:8081/customers/sync \
  -H "Content-Type: application/json" \
  -d '{
    "entityId": "CUST-001",
    "operation": "UPDATE",
    "payloadHash": "prueba-manual-001",
    "payload": "{}"
  }'
# → {"entityId":"CUST-001","state":"SENT_SAP"}
```

Verificaciones (cada una confirma una pieza):

```bash
# 1. ¿Qué recibió el "SAP"? (4 llamadas: address, fiscal, contact, banking)
curl -s http://localhost:8090/__admin/requests | jq '.requests[] | {url: .request.url, body: .request.body}'

# 2. Estado y trazabilidad en Mongo (BD customer: sync_state + imagen)
docker exec mongodb mongosh customer --quiet --eval \
  'db.sync_state.find({entityId:"CUST-001"},{stateCode:1,payloadHash:1,timestamp:1}).sort({timestamp:1})'
docker exec mongodb mongosh customer --quiet --eval \
  'db.customers_current.findOne({_id:"CUST-001"})'

# 3. Histórico en Elasticsearch
curl -s "http://localhost:9200/customers_history/_search?q=customerId:CUST-001" | jq '.hits.total'
```

**Probar el dedupe de idempotencia**: repite el mismo `curl` con el mismo
`payloadHash` → responde `SENT_SAP` al instante y en el log verás el salto por
dedupe; el mock no recibe llamadas nuevas (compara el contador de
`__admin/requests`). Cambia el hash y verás el ciclo completo otra vez
(re-sync `SENT_SAP → RECEIVED`).

---

## Nivel 4 — Probar el flujo CDC end-to-end (patrón 1)

### 4.1 Registrar los conectores Debezium (una vez)

```bash
cd external-services/debezium
curl -i -X POST -H "Content-Type: application/json" \
  http://localhost:8083/connectors/ -d @register-sqlserver-customer.json
curl -i -X POST -H "Content-Type: application/json" \
  http://localhost:8083/connectors/ -d @register-postgres-article.json

curl -s http://localhost:8083/connectors?expand=status | jq   # ambos RUNNING
```

### 4.2 Provocar un cambio en el legacy

```bash
docker exec sqlserver-source /opt/mssql-tools18/bin/sqlcmd -C \
  -S localhost -U sa -P 'SqlServer_Pa55w0rd!' -d poc \
  -Q "UPDATE dbo.customers SET phone = '+34 600 999 000' WHERE id = 'CUST-001'"
```

### 4.3 Observar la cadena completa

```bash
# a) El trigger escribió en la outbox
docker exec sqlserver-source /opt/mssql-tools18/bin/sqlcmd -C \
  -S localhost -U sa -P 'SqlServer_Pa55w0rd!' -d poc \
  -Q "SELECT TOP 3 id, entity_id, operation, created_at FROM dbo.outbox_customer ORDER BY id DESC"

# b) Debezium lo publicó en el topic (key=entityId, value=JSON del contrato)
docker exec kafka-broker kafka-console-consumer \
  --bootstrap-server localhost:9092 --topic outbox.CUSTOMER \
  --from-beginning --property print.key=true --max-messages 5

# c) La app lo procesó (log de customer-app) y el estado avanzó
docker exec mongodb mongosh customer --quiet --eval \
  'db.sync_state.find({entityId:"CUST-001"}).sort({timestamp:-1}).limit(3)'

# d) El mock SAP recibió las llamadas (con el teléfono nuevo en el payload)
curl -s http://localhost:8090/__admin/requests | jq '.requests[0].request.body'
```

**Probar DELETE**: `DELETE FROM dbo.customers WHERE id='CUST-002'` → el evento
`DELETE` enruta a `DeleteCustomerUseCase` (lo verás en el log; borra imagen
Mongo tras avisar a SAP).

---

## Nivel 5 — Pruebas de resiliencia y errores

### 5.1 Retry + estado de error (SAP caído o devolviendo 5xx)

```bash
# Sustituye el stub por un 503 permanente
curl -s -X POST http://localhost:8090/__admin/mappings -d '{
  "priority": 1,
  "request": { "method": "ANY", "urlPattern": ".*" },
  "response": { "status": 503 }
}'

# Lanza una sync (REST o UPDATE en SQL Server) con hash nuevo
curl -s -X POST http://localhost:8081/customers/sync -H "Content-Type: application/json" \
  -d '{"entityId":"CUST-001","operation":"UPDATE","payloadHash":"prueba-503","payload":"{}"}'
# → state SAP_ERROR
```

Verifica: en `__admin/requests` cada llamada aparece **3 veces** (retry con
backoff exponencial); el estado en Mongo queda `SAP_ERROR` (recuperable:
`SAP_ERROR → SENDING_SAP`). Si insistes ~10 veces, el circuit breaker abre y
las llamadas dejan de salir durante 30 s (configurable en `sap.client.*`).
Borra el stub 503 (`curl -X DELETE http://localhost:8090/__admin/mappings/<id>`)
para restaurar.

### 5.2 DLT (mensaje envenenado en Kafka)

```bash
# Publica un mensaje que no es JSON en el topic
echo 'esto-no-es-json' | docker exec -i kafka-broker kafka-console-producer \
  --bootstrap-server localhost:9092 --topic outbox.CUSTOMER

# Tras 3 reintentos con backoff, acaba en el dead-letter topic:
docker exec kafka-broker kafka-console-consumer \
  --bootstrap-server localhost:9092 --topic outbox.CUSTOMER.DLT \
  --from-beginning --max-messages 1
```

### 5.3 CSRF (solo escrituras a S/4 nativo, OData V2)

Arranca con `SAP_S4_CSRF_ENABLED=true` y un stub del fetch en el mock
(respuesta con cabecera `x-csrf-token`); verás en `__admin/requests` el `GET`
de fetch antes del primer `POST`, y el token+cookies en las escrituras. El
comportamiento exacto (incluido el refresh en 403) está cubierto
automáticamente por `WebClientSapClientTest`.

---

## Nivel 6 — Métricas y observabilidad

```bash
curl -s http://localhost:8081/actuator/prometheus | grep sap_sync_state_total
# contador por dominio y estado: cuántos RECEIVED, SENT_SAP, SAP_ERROR...
```

Kibana (`http://localhost:5601`) sobre el índice `customers_history` para ver
el histórico; trazas distribuidas: arrancar la JVM con el javaagent de
OpenTelemetry (ver [`TECH.md` §9](../specs/TECH.md#9-observabilidad)).

---

## Nivel 7 — Contra SAP real (cuando haya tenant)

1. En S/4 Public Cloud: activar el communication arrangement del API
   (p. ej. `SAP_COM_0008` para Business Partner) y obtener credenciales del
   communication user o el servicio OAuth2.
2. Arrancar con las variables reales (nunca commitear valores):

```bash
SAP_S4_BASE_URL=https://<tenant>-api.s4hana.cloud.sap \
SAP_S4_AUTH_TYPE=basic \
SAP_S4_USERNAME=<comm-user> SAP_S4_PASSWORD=<pass> \
SAP_S4_CSRF_ENABLED=true \
SAP_ODATA_ADDRESS_ENABLED=true ... \
mvn -pl customer -am spring-boot:run
```

3. Empezar por **lecturas** (`GET /A_BusinessPartner?$top=1` vía
   `BusinessPartnerReadAdapter` o curl con las mismas credenciales) antes de
   probar escrituras, y las escrituras primero contra el tenant de test.

> El sandbox de [api.sap.com](https://api.sap.com/api/API_BUSINESS_PARTNER/overview)
> también sirve para lecturas con API key (cabecera `APIKey`), pero no ejercita
> OAuth2/CSRF reales.

---

## Plan de pruebas (con todos los servicios levantados)

> Precondición común a todos los casos: infraestructura arriba (§2.1), mock de
> SAP en `:8090` con el stub catch-all 201 (§2.2), conectores Debezium
> registrados (§4.1) y `customer-app` corriendo en `:8081` (§2.3). Ejecutar en
> orden: algunos casos dependen del anterior. Tras cada caso, la columna
> "verificar en" indica dónde mirar y qué debe verse.

| ID | Caso | Resultado esperado |
|----|------|--------------------|
| CP-01 | Salud del entorno | Todo UP / RUNNING |
| CP-02 | Sync REST happy path | `SENT_SAP` + 4 llamadas al mock |
| CP-03 | Idempotencia (mismo hash) | Salta el pipeline, 0 llamadas nuevas |
| CP-04 | Re-sincronización (hash nuevo) | Ciclo completo otra vez |
| CP-05 | Validación negativa | `INVALID` con errores, sin llamadas a SAP |
| CP-06 | CDC — alta (INSERT) | Cadena completa hasta `SENT_SAP` |
| CP-07 | CDC — modificación (UPDATE) | Payload actualizado llega al mock |
| CP-08 | CDC — borrado (DELETE) | Delete a SAP + imagen Mongo eliminada |
| CP-09 | Dominio article (Postgres) | Cadena article hasta el mock |
| CP-10 | SAP caído (503) | 3 reintentos + `SAP_ERROR` |
| CP-11 | Mensaje envenenado | Acaba en `outbox.CUSTOMER.DLT` |
| CP-12 | Ruta OData nativa | URLs `/sap/opu/odata/...` en el mock |
| CP-13 | Métricas | Contadores por estado incrementados |

### CP-01 — Salud del entorno

1. `docker compose ps` en `external-services/` → todos `running/healthy`.
2. `curl -s localhost:8083/connectors?expand=status | jq` → ambos conectores `RUNNING`.
3. `curl -s localhost:8081/actuator/health` → `"status":"UP"`.

### CP-02 — Sync REST happy path

**Dato de entrada**: cliente seedeado `CUST-001` (Acme Corporation, ya en SQL Server).

1. Llama al API de ingesta:
   ```bash
   curl -s -X POST http://localhost:8081/customers/sync -H "Content-Type: application/json" \
     -d '{"entityId":"CUST-001","operation":"UPDATE","payloadHash":"cp02-'$(date +%s)'","payload":"{}"}'
   ```
2. **Verificar en** la respuesta: `"state":"SENT_SAP"`.
3. **Verificar en** el mock (`curl -s localhost:8090/__admin/requests | jq '.meta.total'`):
   +4 llamadas (address, fiscal, contact, banking) con `"BusinessPartner":"CUST-001"` en el body.
4. **Verificar en** Mongo la traza completa de estados (RECEIVED → FETCHING →
   VALIDATING → VALID → INDEXING → INDEXED → SENDING_SAP → SENT_SAP):
   ```bash
   docker exec mongodb mongosh customer --quiet --eval \
     'db.sync_state.find({entityId:"CUST-001"},{stateCode:1,timestamp:1}).sort({timestamp:1})'
   ```
5. **Verificar en** Mongo la imagen (`db.customers_current.findOne({_id:"CUST-001"})`)
   y en ES el histórico (`curl -s "localhost:9200/customers_history/_search?q=customerId:CUST-001"`).

### CP-03 — Idempotencia (mismo payloadHash)

1. Repite el `curl` de CP-02 con **el mismo** `payloadHash` (cópialo, no uses `date`).
2. **Verificar en** la respuesta: `SENT_SAP` inmediato.
3. **Verificar en** el mock: el contador `meta.total` **no aumenta**.
4. **Verificar en** el log de customer-app: mensaje de dedupe/salto por hash ya enviado.

### CP-04 — Re-sincronización (hash nuevo sobre entidad ya enviada)

1. Repite el `curl` de CP-02 con un `payloadHash` distinto.
2. **Verificar**: ciclo completo de nuevo (la re-entrada `SENT_SAP → RECEIVED`
   es legal) — nueva tanda de estados en `sync_state` y +4 llamadas al mock.

### CP-05 — Validación negativa

**Dato de entrada**: corromper el email de `CUST-002` en el legacy.

1. ```bash
   docker exec sqlserver-source /opt/mssql-tools18/bin/sqlcmd -C \
     -S localhost -U sa -P 'SqlServer_Pa55w0rd!' -d poc \
     -Q "UPDATE dbo.customers SET email = 'no-es-un-email' WHERE id = 'CUST-002'"
   ```
   (el propio UPDATE dispara CDC; espera 2-3 s, o lanza además un sync REST con hash nuevo)
2. **Verificar en** Mongo: estado final `INVALID` para `CUST-002` (o la feature
   CONTACT inválida), y errores de validación en el log de la app.
3. **Verificar en** el mock: **ninguna** llamada nueva para la feature inválida.
4. Restaura el dato para no contaminar el resto del plan:
   `UPDATE dbo.customers SET email = 'contacto@globex.es' WHERE id = 'CUST-002'`.

### CP-06 — CDC: alta de un cliente nuevo

**Dato de entrada**: cliente nuevo `CUST-100` (Weyland Yutani).

1. ```bash
   docker exec sqlserver-source /opt/mssql-tools18/bin/sqlcmd -C \
     -S localhost -U sa -P 'SqlServer_Pa55w0rd!' -d poc \
     -Q "INSERT INTO dbo.customers (id, code, name, status, street, city, postal_code, country, region, tax_id, vat_number, legal_name, tax_residency, email, phone, website, iban, bic) VALUES ('CUST-100','C100','Weyland Yutani','ACTIVE','Gran Via 1','Madrid','28013','ES','Madrid','B99999999','ESB99999999','Weyland Yutani S.L.','ES','info@weyland.example','+34 600 555 666','https://weyland.example','ES9121000418450200051332','CAIXESBB')"
   ```
2. **Verificar en** la outbox: fila nueva con `operation='CREATE'`
   (query de §4.3a).
3. **Verificar en** Kafka: mensaje en `outbox.CUSTOMER` con key `CUST-100`
   (consumer de §4.3b).
4. **Verificar en** Mongo: `sync_state` de `CUST-100` termina en `SENT_SAP`
   y existe `customers_current` con `_id:"CUST-100"`.
5. **Verificar en** el mock: llamadas con `"BusinessPartner":"CUST-100"`.

### CP-07 — CDC: modificación

1. ```bash
   docker exec sqlserver-source /opt/mssql-tools18/bin/sqlcmd -C \
     -S localhost -U sa -P 'SqlServer_Pa55w0rd!' -d poc \
     -Q "UPDATE dbo.customers SET phone = '+34 600 777 888' WHERE id = 'CUST-100'"
   ```
2. **Verificar en** el mock: la última llamada de contact lleva el teléfono nuevo:
   `curl -s localhost:8090/__admin/requests | jq '.requests[0].request.body'`.
3. **Verificar en** Mongo: la imagen refleja el teléfono nuevo.

### CP-08 — CDC: borrado

1. ```bash
   docker exec sqlserver-source /opt/mssql-tools18/bin/sqlcmd -C \
     -S localhost -U sa -P 'SqlServer_Pa55w0rd!' -d poc \
     -Q "DELETE FROM dbo.customers WHERE id = 'CUST-100'"
   ```
2. **Verificar en** el log de customer-app: el evento `DELETE` enruta a
   `DeleteCustomerUseCase`.
3. **Verificar en** el mock: llamada de borrado para `CUST-100`.
4. **Verificar en** Mongo: `db.customers_current.findOne({_id:"CUST-100"})` → `null`.

### CP-09 — Dominio article (Postgres → Kafka → SAP)

**Precondición extra**: article-app corriendo
(`SAP_S4_BASE_URL=http://localhost:8090 SAP_BTP_BASE_URL=http://localhost:8090 mvn -pl article -am spring-boot:run`, puerto 8082).

1. ```bash
   docker exec postgres-source psql -U postgres -d poc -c \
     "INSERT INTO articles (id, sku, description, category, unit, status) VALUES ('ART-100','SKU-100','Detector de movimiento','ELECTRONICA','UN','ACTIVE')"
   ```
2. **Verificar en** Kafka: mensaje en `outbox.ARTICLE` con key `ART-100`.
3. **Verificar en** Mongo (BD `article`): `sync_state` en `SENT_SAP` y doc en
   `articles_current`.
4. **Verificar en** el mock: llamada de producto para `ART-100`.

### CP-10 — SAP caído (5xx): retry + estado de error

1. Añade el stub 503 prioritario (§5.1) al mock.
2. Lanza un sync REST de `CUST-001` con hash nuevo.
3. **Verificar en** la respuesta: `"state":"SAP_ERROR"`.
4. **Verificar en** el mock: cada URL aparece **3 veces** (reintentos con backoff).
5. **Verificar en** Mongo: última transición `SAP_ERROR` (estado recuperable).
6. Borra el stub 503 y **verifica la recuperación**: nuevo sync con hash nuevo → `SENT_SAP`.

### CP-11 — Mensaje envenenado → DLT

1. Publica basura en el topic (§5.2).
2. **Verificar en** el log: 3 intentos de parseo fallidos con backoff.
3. **Verificar en** Kafka: el mensaje aparece en `outbox.CUSTOMER.DLT`.
4. **Verificar**: el listener sigue vivo (repite CP-02 y funciona).

### CP-12 — Ruta OData nativa (patrón 1) en lugar de BTP

1. Reinicia customer-app añadiendo:
   `SAP_ODATA_ADDRESS_ENABLED=true SAP_ODATA_FISCAL_ENABLED=true SAP_ODATA_CONTACT_ENABLED=true SAP_ODATA_BANKING_ENABLED=true`.
2. Lanza un sync REST de `CUST-001` con hash nuevo.
3. **Verificar en** el mock: las URLs ahora son
   `/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartnerAddress`, `A_BusinessPartnerTaxNumber`, etc.,
   y el body usa los campos oficiales (`StreetName`, `CityName`, `BPTaxType`...)
   **sin** envoltura `{"d":...}`.

### CP-13 — Métricas

1. `curl -s localhost:8081/actuator/prometheus | grep sap_sync_state_total`
2. **Verificar**: contadores por estado coherentes con lo ejecutado
   (p. ej. `state="SENT_SAP"` ≥ nº de casos felices, `state="SAP_ERROR"` ≥ 1
   por CP-10, `state="INVALID"` ≥ 1 por CP-05).

> Registro sugerido: apunta por caso ✅/❌ + evidencia (respuesta, query o
> captura). Si un caso falla, la tabla de troubleshooting de abajo cubre las
> causas más comunes.

## Troubleshooting rápido

| Síntoma | Causa probable | Solución |
|---|---|---|
| App no arranca: `DuplicateKeyException`, `NoSuchBeanDefinitionException` | Regresión de wiring/YAML | Ejecuta el smoke de contexto y mira el primer `Caused by` |
| `Connection refused` a `localhost:9092/27017/9200` | Infra no levantada | `docker compose ps` en `external-services/` |
| El UPDATE en SQL Server no llega al topic | Conector no registrado o parado | `curl localhost:8083/connectors?expand=status` |
| Estado se queda en `ERROR` tras fetch | El `entityId` no existe en la BD legacy | Usa `CUST-001`/`CUST-002` o inserta la fila antes |
| Mensajes repetidos no reprocesan | Es el dedupe por `payloadHash` (correcto) | Cambia el hash para forzar re-sync |
| 403 contra S/4 real en escrituras | CSRF desactivado | `SAP_S4_CSRF_ENABLED=true` |
| Tests de `it/` duplicados o `SyncCustomerControllerIT` inerte | Issues conocidos §8 de [`TESTING.md`](TESTING.md) | Pendientes de fix |
