# Conectores Debezium (CDC outbox → Kafka)

Configuración de los dos conectores que leen las tablas outbox de los legacy
y publican en los topics que consumen las apps Java:

| Conector | Fuente | Tabla outbox | Topic destino | Listener |
|---|---|---|---|---|
| `outbox-customer-sqlserver` | SQL Server (`poc`) | `dbo.outbox_customer` | `outbox.CUSTOMER` | `CustomerKafkaListener` |
| `outbox-article-postgres` | PostgreSQL (`poc`) | `public.outbox_article` | `outbox.ARTICLE` | `ArticleKafkaListener` |

## Cómo funciona la cadena

1. Un trigger sobre la tabla de negocio (`dbo.customers` / `articles`) inserta en
   la outbox una fila cuya columna `payload` contiene el **contrato completo**
   que esperan los listeners:

   ```json
   {"entityId":"CUST-001","operation":"UPDATE","payloadHash":"<sha256-hex>","payload":{ ...entidad... }}
   ```

2. Debezium captura los INSERT de la outbox (CDC en SQL Server, replicación
   lógica `pgoutput` en Postgres).

3. SMTs estándar dejan el mensaje limpio, **sin transformación adicional pendiente**:
   - `ExtractNewRecordState` (unwrap): quita el envelope de Debezium (`before`/`after`/`source`).
   - `ExtractField$Value` sobre `payload`: el value pasa a ser el contenido de la columna.
   - `ExtractField$Key` sobre `entity_id` + `message.key.columns`: la key del mensaje
     es el id de la entidad (ordenación por partición por entidad).
   - `RegexRouter`: renombra el topic (`legacy-*.…outbox_*` → `outbox.CUSTOMER`/`outbox.ARTICLE`).
   - `StringConverter` como key/value converter del conector: serializa el value
     como texto plano, así el mensaje es **exactamente** el JSON de la columna
     `payload` (sin comillas extra ni schema envelope).

**Formato resultante en `outbox.CUSTOMER` / `outbox.ARTICLE`:**

- **key** (string): `CUST-001`
- **value** (string = JSON del contrato):

  ```json
  {"entityId":"CUST-001","operation":"UPDATE","payloadHash":"ab12...","payload":{"id":"CUST-001","code":"C001","name":"Acme Corporation","status":"ACTIVE","address":{...},"fiscal":{...},"contact":{...},"banking":{...}}}
  ```

Es lo que parsea `CustomerKafkaListener`/`ArticleKafkaListener` con
`mapper.readTree(record.value())` — no hace falta ningún SMT custom.

> Nota sobre `payloadHash`: en SQL Server se calcula con `HASHBYTES('SHA2_256', ...)`
> sobre `NVARCHAR` (bytes UTF-16) y en Postgres con `digest(..., 'sha256')` sobre
> UTF-8, así que los hashes no son comparables entre motores. No importa: el hash
> solo se usa como clave de deduplicación dentro de cada dominio.

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

# Consumir los topics
docker exec -it kafka-broker kafka-console-consumer \
  --bootstrap-server localhost:9092 --topic outbox.CUSTOMER --from-beginning \
  --property print.key=true

docker exec -it kafka-broker kafka-console-consumer \
  --bootstrap-server localhost:9092 --topic outbox.ARTICLE --from-beginning \
  --property print.key=true
```

## Notas / límites conocidos

- **SQL Server** necesita CDC habilitado sobre la BD y la outbox
  (`sys.sp_cdc_enable_db` + `sys.sp_cdc_enable_table`, ya en
  `sqlserver/init.sql`) y **SQL Server Agent** activo
  (`MSSQL_AGENT_ENABLED=true`, ya en `docker-compose.yml`).
- **Postgres** ya corre con `wal_level=logical`; el conector crea slot
  (`debezium_outbox_article`) y publicación filtrada automáticamente.
- `snapshot.mode=initial`: al registrar el conector se re-publican las filas ya
  existentes en la outbox (los consumidores deduplican por `payloadHash`).
- Los triggers se crean después de los datos de ejemplo, así que el seed inicial
  no genera eventos; usa un `UPDATE` como los de arriba para probar el flujo.
- La outbox no se purga (PoC). En producción habría que borrar filas ya
  capturadas (job de retención) y valorar el SMT `EventRouter` oficial de Debezium.
