# ADR-0013 — La outbox publica un aviso de cambio **fino**, sin datos: el consumidor relee el legacy

| | |
|---|---|
| **Estado** | ✅ aceptada |
| **Fecha** | 2026-09-19 |
| **Decisión del plan** | decisión del propietario (18–19/09/2026): mensaje fino; la lectura del legacy sigue siendo **por base de datos** (`LegacyRepositoryPort`), sin API REST de lectura |
| **Reevaluar cuando** | aparezca un consumidor que **no pueda** leer del legacy (otro equipo, otra red, un histórico que deba reconstruirse sin la fuente), o se decida exponer una API de lectura del legacy: en ambos casos se escribe un ADR aparte |

## 1. Contexto

Cada cambio en un legacy inserta una fila en su tabla outbox y Debezium la
publica en `outbox.CUSTOMER` / `outbox.ARTICLE` ([ADR-0006](0006-kafka-connect-debezium-como-cdc.md)).
Hasta hoy el mensaje llevaba **la entidad entera** más un hash:

```json
{"entityId":"CUST-001","operation":"UPDATE","payloadHash":"ab12…","payload":{"id":"CUST-001","name":"…","address":{…},"contact":{"email":"…","phone":"…"},"banking":{"iban":"…"}}}
```

Cuatro hechos, ninguno discutible, hacen que ese contrato sea el equivocado:

1. **El consumidor ya no usa esos datos.** `SyncCustomerUseCase` y
   `SyncArticleUseCase` **releen el estado actual del legacy**
   (`LegacyRepositoryPort#fetch`) y envían a SAP lo leído. Del mensaje solo
   consumían el `entityId`, la operación y el `payloadHash`. El `payload` viajaba
   por los topics sin que nadie lo abriera: no existe ni un parser de ese campo.
2. **PII en Kafka sin retención.** Ese payload lleva nombre, dirección, email,
   teléfono e IBAN, y vive en `outbox.*`, en `outbox.*-dlt` y en el *schema
   history* tanto tiempo como diga la retención del topic — que es la del
   clúster, porque nadie la fijó (hallazgo **2A-4**: sin retención ni borrado
   RGPD). Un dato personal que no se necesita es un dato que no debe salir.
3. **El JSON se construía concatenando texto en el trigger.** En SQL Server con
   `N'{"entityId":"' + … + N'","payload":' + j.entity_json + N'}'`. Un carácter
   inesperado produce un JSON inválido; el listener lanza al parsear, agota los
   reintentos y el cambio acaba en una DLT **que nadie consume**.
4. **Una versión vieja podía procesarse después de una nueva.** Si dos cambios
   de la misma entidad se reintentan desordenados (DLT, reproceso manual,
   reprocesado de un `snapshot.mode=initial`), el payload del mensaje antiguo
   escribía en SAP un estado ya superado. Con el estado releído esto no puede
   pasar: procesar dos veces el mismo aviso converge al **estado actual**.

Es el patrón *claim check* llevado a su forma más barata: el mensaje es el
resguardo y la "consigna" es el propio legacy, que ya sabemos leer.

## 2. Opciones

| | **Mensaje fino** (elegida) | Payload completo (hoy) | Payload + *claim check* en S3/MinIO |
|---|---|---|---|
| PII en Kafka | **ninguna** | toda la del cliente, en topics sin retención | ninguna en Kafka; toda en el objeto, con su propia retención que también hay que fijar |
| Fuente de verdad al enviar a SAP | el legacy en el instante de procesar | el mensaje (estado congelado, posiblemente viejo) | el objeto (igual de viejo) |
| Reproceso de un mensaje antiguo | converge al estado actual | **escribe un estado superado** | escribe un estado superado |
| Piezas nuevas | ninguna | — | bucket, credenciales, ciclo de vida, limpieza de huérfanos, un fallo más que gestionar |
| Complejidad del trigger | insertar 3 columnas | concatenar JSON a mano (T-SQL) | igual que hoy + subida del objeto fuera de la transacción |
| Dependencia del legacy al procesar | **sí** (ya existía: siempre se relee) | sí (ya se relee igual) | sí (ya se relee igual) |
| Tamaño del mensaje | ~100 B | 1–4 KB | ~200 B |

El *claim check* con almacén de objetos se descarta porque **paga toda la
complejidad y no compra nada**: el consumidor tendría que leer el objeto *y*
seguir releyendo el legacy, que es lo que realmente envía a SAP.

## 3. Decisión

**El mensaje de cambio lleva solo la identidad del cambio.**

```json
{"entityId":"CUST-001","operation":"UPDATE","occurredAt":"2026-09-19T08:00:00.123Z"}
```

- **Campos**: `entityId` (obligatorio), `operation` (`CREATE|UPDATE|DELETE`, por
  defecto `UPDATE`), `occurredAt` (instante del cambio en el legacy) y, donde el
  motor lo permite sin una segunda escritura, `version` (número de secuencia de
  la outbox; Postgres lo emite, SQL Server no — allí el orden lo da la partición
  de Kafka, cuya clave es `entity_id`).
- **`payloadHash` y `payload` pasan a opcionales y se ignoran como fuente de
  datos.** Si un mensaje antiguo los trae, se parsean y se registran, pero no
  deciden nada: como mucho aparecen en un log de diagnóstico cuando difieren del
  hash calculado.
- **El hash lo calcula el consumidor** sobre el snapshot que acaba de leer, con
  `PayloadHasher` (`common/domain`): SHA-256 hexadecimal sobre una **forma
  canónica** del agregado — componentes del `record` en orden de declaración,
  claves de mapa ordenadas, orden de lista respetado, nulos con marca propia,
  texto entrecomillado y escapado. Función pura, sin Spring y sin Jackson, para
  que dos instancias y dos versiones de la JVM lleguen al mismo número.
- **Ese hash calculado es el que manda**: es el del dedupe (`alreadySent`), el
  que se escribe en cada transición del ciclo, el del documento del histórico y
  el `Idempotency-Key` del envío a SAP.
- **La lectura del legacy sigue siendo por base de datos** (`LegacyRepositoryPort`).
  Este ADR **no** introduce ninguna API REST de lectura.

## 4. Consecuencias

- **Dependencia del legacy en el momento de procesar.** No es nueva —el pipeline
  siempre releía— pero ahora es la única fuente. Si el legacy no responde, el
  ciclo termina en `ERROR` y el mensaje se reintenta: ya está cubierto por el
  error handler del contenedor (backoff + DLT) y por la máquina de estados.
- **Si la fila ya no está en el legacy, el ciclo es `ERROR`.** Es el
  comportamiento que ya había para un `entityId` inexistente.
- **La baja (`DELETE`) no necesita snapshot**: el legacy ya no tiene la fila y la
  identidad basta, que es como funcionaba `DeleteCustomerUseCase`. Cuando el
  aviso de baja no trae hash, se usa uno derivado de la identidad
  (`PayloadHasher.ofIdentity(dominio, entityId, "DELETE")`), determinista, para
  que el `Idempotency-Key` del `DELETE` a SAP siga existiendo.
- **El dedupe cambia de significado, a mejor.** Antes respondía a "¿he visto este
  mensaje?"; ahora a "¿SAP ya tiene **este estado**?". Un aviso repetido, un
  reproceso de la DLT o un `snapshot.mode=initial` dejan de escribir en SAP si
  nada cambió, aunque el mensaje sea distinto.
- **El hash deja de calcularse en la base de datos.** Desaparece la asimetría
  entre `HASHBYTES` (UTF-16 en SQL Server) y `digest` (UTF-8 en Postgres): el
  hash es de la aplicación y comparable siempre.
- **Los hashes históricos no son comparables con los nuevos.** El primer aviso de
  cada entidad tras el despliegue calculará un hash distinto del último
  `SENT_SAP` guardado y **reenviará una vez** a SAP. Es un reenvío idempotente
  (upsert con lookup previo, `upsert-idempotente-sap.md`), no un duplicado.
- **Menos tráfico y menos superficie**: de 1–4 KB a ~100 B por aviso, y ningún
  dato personal en Kafka. No cierra 2A-4 (sigue faltando la retención de Mongo y
  Elasticsearch), pero le quita a Kafka del problema.
- **Cambia el contrato de `IngestionMessage`** (`common`): `payloadHash` y
  `payload` admiten nulo. Es un cambio **menor** compatible para quien construye
  el mensaje, y obliga a re-desplegar los dominios por la regla de `common`.

## 5. Migración

1. **Primero el consumidor**: los listeners aceptan **los dos formatos**, el fino
   y el antiguo con payload. Es lo que permite vaciar los topics y las DLT que
   aún contengan mensajes viejos.
2. **Después los triggers**: dejan de construir el payload y escriben el aviso en
   una columna `message`. Las columnas `payload` y `payload_hash` **se mantienen
   nullables y a NULL un ciclo de despliegue**, para no romper a nadie que las
   lea todavía; se borran en una revisión posterior.
3. **El conector Debezium** apunta su `ExtractField$Value` a `message` en vez de
   a `payload`. El SMT no hubo que rediseñarlo: `StringConverter` exige que el
   valor sea un campo de texto, así que bastó mover el SMT a la columna que
   ahora lleva el aviso. No hay SMT custom.
4. `external-services/*/init.sql` solo se ejecuta sobre un volumen vacío: en
   local hay que recrear los contenedores de datos (o aplicar el cambio a mano)
   para que la outbox tenga la columna `message`.

## 6. Qué NO resuelve

- La retención y el borrado RGPD de Mongo, Elasticsearch y `sap.sync.alerts`
  (hallazgo 2A-4, decisión de negocio pendiente sobre el plazo).
- El consumo de las DLT: sigue sin haber nadie que las lea.
- La purga de las tablas outbox (sigue sin job de retención, ADR-0006).
