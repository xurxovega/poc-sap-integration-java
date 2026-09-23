# Conectores Debezium (CDC outbox → Kafka)

Configuración de los dos conectores que leen las tablas outbox de los legacy
y publican en los topics que consumen las apps Java:

| Conector | Fuente | Tabla outbox | Topic destino | Listener |
|---|---|---|---|---|
| `outbox-customer-sqlserver` | SQL Server (`poc`) | `dbo.outbox_customer` | `outbox.CUSTOMER` | `CustomerKafkaListener` |
| `outbox-article-postgres` | PostgreSQL (`poc`) | `public.outbox_article` | `outbox.ARTICLE` | `ArticleKafkaListener` |

## Cómo funciona la cadena

1. Un trigger sobre la tabla de negocio (`dbo.customers` / `articles`) inserta en
   la outbox una fila cuya columna `message` es el **aviso de cambio fino**
   ([ADR-0013](../../docs/architecture/adr/0013-outbox-mensaje-fino-sin-payload.md)):
   dice **qué** cambió, no **qué datos** hay. Sin PII y sin JSON construido a mano:

   ```json
   {"entityId":"CUST-001","operation":"UPDATE","occurredAt":"2026-09-19T08:00:00.123Z"}
   ```

   (Postgres añade `"version"`, el número de secuencia de la outbox; SQL Server no
   puede hacerlo en un trigger multi-fila sin una segunda escritura, así que allí
   el orden lo da la partición de Kafka y la columna `id` de la outbox.)

   Las columnas `payload` y `payload_hash` **siguen existiendo, nullables y a
   NULL** durante un ciclo de despliegue, para no romper a nadie que aún las lea.

2. Debezium captura los INSERT de la outbox (CDC en SQL Server, replicación
   lógica `pgoutput` en Postgres).

3. SMTs estándar dejan el mensaje limpio, **sin transformación adicional pendiente**:
   - `ExtractNewRecordState` (unwrap): quita el envelope de Debezium (`before`/`after`/`source`).
   - `ExtractField$Value` sobre **`message`** (antes era `payload`): el value pasa a
     ser el contenido de esa columna. El SMT no necesitaba rediseño: basta con
     apuntarlo a la columna que ahora lleva el aviso, porque `StringConverter`
     exige que el value sea un campo de texto, no un `Struct`.
   - `ExtractField$Key` sobre `entity_id` + `message.key.columns`: la key del mensaje
     es el id de la entidad (ordenación por partición por entidad).
   - `RegexRouter`: renombra el topic (`legacy-*.…outbox_*` → `outbox.CUSTOMER`/`outbox.ARTICLE`).
   - `StringConverter` como key/value converter del conector: serializa el value
     como texto plano, así el mensaje es **exactamente** el JSON de la columna
     `message` (sin comillas extra ni schema envelope).

**Formato resultante en `outbox.CUSTOMER` / `outbox.ARTICLE`:**

- **key** (string): `CUST-001`
- **value** (string = JSON del aviso):

  ```json
  {"entityId":"CUST-001","operation":"UPDATE","occurredAt":"2026-09-19T08:00:00.123Z"}
  ```

Es lo que parsea `CustomerKafkaListener`/`ArticleKafkaListener` con
`mapper.readTree(record.value())` — no hace falta ningún SMT custom. Los listeners
**siguen aceptando el formato antiguo** (con `payloadHash` y `payload`) para poder
vaciar los topics y las DLT que aún lo contengan; el payload se parsea pero nunca
se usa como fuente de datos: el estado actual se relee del legacy.

> El hash de deduplicación **ya no lo calcula la base de datos**. Lo calcula la
> aplicación (`PayloadHasher`, SHA-256 sobre una forma canónica del agregado)
> sobre el snapshot que acaba de leer, así que la vieja advertencia de que
> `HASHBYTES` (UTF-16) y `digest` (UTF-8) no eran comparables deja de aplicar.

## Particiones de los topics (ADR-0011)

`outbox.CUSTOMER`, `outbox.ARTICLE` y sus `-dlt` se crean con **12 particiones**
por el servicio `kafka-init-topics` del `docker-compose.yml`, y los conectores
declaran `topic.creation.default.partitions: 12` por si Connect llegara antes.
Dos razones:

- La clave del mensaje es `entity_id`, así que todos los eventos de una entidad
  caen en la misma partición y se procesan **en orden y sin solaparse**; con una
  sola partición (lo que daba la auto-creación) no se puede pasar de un
  consumidor y el orden global de hoy era accidental, no diseñado.
- El registro que va a la DLT se publica en **la misma partición** que el
  original, así que `-dlt` necesita al menos tantas particiones como el topic de
  entrada. Si un día se suben las de `outbox.*`, hay que subir también las del
  `-dlt`.

Regla de dimensionado: **particiones ≥ instancias × `concurrency` del listener**
(12 = 2 clústeres × 2 instancias × 3). En producción los topics **no** los crea
la aplicación: los crea la plataforma, ver [`../../deploy/README.md`](../../deploy/README.md).

## Registrar los conectores

Con la infraestructura levantada (`docker compose up -d` en `external-services/`)
y `kafka-connect` sano (`curl http://localhost:8083/`):

```bash
cd external-services/debezium

curl -i -X POST -H "Content-Type: application/json" \
  http://localhost:8083/connectors/ -d @register-sqlserver-customer.json

curl -i -X POST -H "Content-Type: application/json" \
  http://localhost:8083/connectors/ -d @register-postgres-article.json
```

## Verificar

```bash
# Estado de los conectores (RUNNING)
curl -s http://localhost:8083/connectors/outbox-customer-sqlserver/status | jq
curl -s http://localhost:8083/connectors/outbox-article-postgres/status | jq

# Provocar un evento (los triggers rellenan la outbox)
docker exec -it sqlserver-source /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa \
  -P 'SqlServer_Pa55w0rd!' -C -Q "UPDATE poc.dbo.customers SET name = 'Acme Corp v2' WHERE id = 'CUST-001'"

docker exec -it postgres-source psql -U postgres -d poc \
  -c "UPDATE articles SET description = 'Laptop Dell XPS 15 (2026)' WHERE id = 'ART-001'"

# Consumir los topics (Redpanda sustituye a kafka-broker desde OPS-010)
docker exec redpanda rpk topic consume outbox.CUSTOMER \
  --brokers localhost:19092 --num 10 --print-key

docker exec redpanda rpk topic consume outbox.ARTICLE \
  --brokers localhost:19092 --num 10 --print-key
```

## Notas / límites conocidos

- **SQL Server** necesita CDC habilitado sobre la BD y la outbox
  (`sys.sp_cdc_enable_db` + `sys.sp_cdc_enable_table`, ya en
  `sqlserver/init.sql`) y **SQL Server Agent** activo
  (`MSSQL_AGENT_ENABLED=true`, ya en `docker-compose.yml`).
- **Postgres** ya corre con `wal_level=logical`; el conector crea slot
  (`debezium_outbox_article`) y publicación filtrada automáticamente.
- `snapshot.mode=initial`: al registrar el conector se re-publican las filas ya
  existentes en la outbox (los consumidores deduplican por el hash del snapshot
  releído, así que un aviso repetido no vuelve a escribir en SAP).
- Los triggers se crean después de los datos de ejemplo, así que el seed inicial
  no genera eventos; usa un `UPDATE` como los de arriba para probar el flujo.
- **`init.sql` solo se ejecuta sobre un volumen de datos vacío.** Si ya tenías
  los contenedores levantados de antes, la outbox no tendrá la columna `message`
  y el conector fallará: recrea los volúmenes (`docker compose down -v`) o
  añade la columna y recrea el trigger a mano.
- **Base recreada ⇒ offsets viejos del conector.** Kafka Connect guarda el
  último LSN/LSN de commit en su topic de offsets, no en la base. Si recreas el
  volumen de SQL Server pero no el de Kafka, el conector arranca `RUNNING`,
  ve «snapshot ya completado», reanuda desde un LSN que la base nueva no ha
  alcanzado y **no emite nada** (comprobado el 2026-09-19). Arreglo sin tocar
  Kafka: `PUT /connectors/<nombre>/stop` → `DELETE /connectors/<nombre>/offsets`
  → `DELETE /connectors/<nombre>` → borrar el topic `schemahistory.<outbox>` →
  registrar de nuevo. Si además la tarea sale `FAILED` con «Unable to get last
  available log position», es este mismo caso.
- **Topics ya existentes no se reparticionan.** `kafka-init-topics` usa
  `--create --if-not-exists`: un topic creado antes con 1 partición se queda
  con 1. Para subirlo: `rpk topic add-partitions outbox.CUSTOMER --partitions 12 --brokers localhost:19092`
  (y lo mismo para `-dlt` y `outbox.ARTICLE`); comprueba con `--describe`.
- La outbox no se purga (PoC). En producción habría que borrar filas ya
  capturadas (job de retención) y valorar el SMT `EventRouter` oficial de Debezium.
