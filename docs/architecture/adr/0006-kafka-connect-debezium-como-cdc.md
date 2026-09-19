# ADR-0006 — Kafka Connect + Debezium como plataforma de captura de cambios

| | |
|---|---|
| **Estado** | ✅ aceptada (retroactiva: documenta una decisión ya tomada al construir el sistema) |
| **Fecha** | 2026-09-12 (decisión original: julio de 2026) |
| **Reevaluar cuando** | decisión D-3 del plan (Debezium Server sin Kafka Connect; Event Router SMT oficial en vez de la outbox con triggers) antes del compose del e2e |

## 1. Contexto

Los cambios nacen en dos bases legacy (SQL Server para clientes, PostgreSQL
para artículos) sobre las que no podemos añadir lógica de aplicación. Hace
falta capturarlos en casi tiempo real, en orden por entidad, y con la garantía
de no perder ninguno aunque la plataforma esté parada.

## 2. Opciones

| | Kafka Connect + Debezium (outbox por triggers) | Debezium Server → Kafka | Polling propio de tablas |
|---|---|---|---|
| Orden y durabilidad | Kafka | Kafka | a mano |
| Infra | Kafka + Connect (compose) | Kafka + un proceso | ninguna extra |
| Transformación del evento | SMT; hoy tabla outbox rellenada por triggers | igual | código propio |
| Operación | REST de conectores, estado visible | proceso único, menos superficie | cron y estado propios |
| Backpressure / DLT | de serie (`<topic>-dlt`) | de serie | a mano |

## 3. Decisión

Kafka Connect con los conectores Debezium (`debezium/connect` 2.7.3), tablas
**outbox** rellenadas por triggers en cada legacy y topics `outbox.CUSTOMER` /
`outbox.ARTICLE`; consumo con `@KafkaListener`, reintentos con backoff y DLT
`<topic>-dlt`. Los conectores se registran con `scripts/start-all.sh --with-cdc`.

## 4. Consecuencias

- Un mensaje puede tardar hasta el **presupuesto de reintentos** en procesarse:
  `max.poll.interval.ms` se fija a 15 min y `RetryBudgetGuard` lo vigila
  ([`../../sdd/common/observabilidad.md`](../../sdd/common/observabilidad.md) R-4).
- El evento lleva `payloadHash` y el use case **re-lee** la entidad del legacy:
  el payload de la outbox no es la fuente de verdad.
- **Desde [ADR-0013](0013-outbox-mensaje-fino-sin-payload.md) (2026-09-19) el
  evento ya no lleva datos ni hash**: solo la identidad del cambio (columna
  `message` de la outbox), y el hash lo calcula el consumidor sobre el snapshot
  releído del legacy.
- Schema Registry para la outbox: decisión D-8, después.
- Topología: **un solo `consumer group` por dominio compartido entre clústeres**
  y 12 particiones por topic, también en el `-dlt`, que recibe el registro en la
  misma partición que el original ([ADR-0011](0011-concurrencia-entre-instancias-fencing-sin-lease.md)).
- Verificado en vivo (CDC UPDATE y DELETE) el 2026-09-12.
