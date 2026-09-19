# Contrato del mensaje de cambio (aviso fino, *claim check*)

| | |
|---|---|
| **Dominio** | `common` (capacidad transversal: la cumplen los listeners de `customer` y `article` y los triggers de los legacy) |
| **Estado** | ✅ implementado |
| **Entradas** | CDC (`outbox.CUSTOMER`, `outbox.ARTICLE`) · REST `POST /customers/sync` · `POST /articles/sync` |
| **Destino SAP** | ninguno directo |
| **Última revisión** | 2026-09-19 |

> **Por qué un spec propio y no una sección de
> [`idempotencia-y-dedupe.md`](idempotencia-y-dedupe.md)**: son dos hechos
> distintos con dos dueños distintos. Aquí se describe **qué se publica y quién
> lo produce** (triggers de los legacy, llamantes de la API, compatibilidad entre
> versiones); allí, **qué hace el consumidor con lo que recibe**. Meterlo todo en
> el spec de idempotencia obligaría a quien mantiene un trigger a leer reglas de
> dedupe que no le tocan, y al revés.

## 1. Objetivo

Que un cambio en un legacy se anuncie **sin publicar los datos que cambiaron**:
el aviso dice *qué* entidad cambió y *cuándo*, y quien lo recibe va a buscar el
estado actual. Así no hay datos personales en Kafka, ni versiones viejas
escribiéndose en SAP, ni JSON construido a mano dentro de un trigger.

## 2. Alcance

**Dentro**: los campos del aviso, quién lo produce, cómo se valida, y la
compatibilidad con los avisos antiguos que todavía lleven payload.

**Fuera** (y por qué):
- Qué hace el consumidor con el aviso (releer, deduplicar, enviar):
  [`idempotencia-y-dedupe.md`](idempotencia-y-dedupe.md) y los specs de
  sincronización de cada dominio.
- El transporte (topics, particiones, SMT, DLT):
  [`../../architecture/adr/0006-kafka-connect-debezium-como-cdc.md`](../../architecture/adr/0006-kafka-connect-debezium-como-cdc.md)
  y [`../../../external-services/debezium/README.md`](../../../external-services/debezium/README.md).
- Una **API de lectura** del legacy: no existe ni está en el alcance; la lectura
  es por base de datos (`LegacyRepositoryPort`).

## 3. Entrada

Aviso de cambio, un objeto JSON. Decisión completa en
[ADR-0013](../../architecture/adr/0013-outbox-mensaje-fino-sin-payload.md).

| Campo | Tipo | Obligatorio | Notas |
|---|---|---|---|
| `entityId` | texto | **sí** | Identificador de la entidad en el legacy |
| `operation` | `CREATE` \| `UPDATE` \| `DELETE` | no | Por defecto `UPDATE`; no distingue alta de modificación para el consumidor (releer no lo necesita) |
| `occurredAt` | ISO-8601 UTC | no | Instante del cambio en el legacy; diagnóstico y latencia |
| `version` | entero | no | Secuencia de la outbox. Lo emite Postgres; SQL Server no puede en un trigger multi-fila sin una segunda escritura, y allí el orden lo da la partición de Kafka (clave `entity_id`) |
| `payloadHash` | texto | no | **Obsoleto.** Se acepta y se registra, no decide nada |
| `payload` | objeto o texto | no | **Obsoleto.** Se acepta y **nunca** se usa como fuente de datos |

Ejemplo:

```json
{"entityId":"CUST-001","operation":"UPDATE","occurredAt":"2026-09-19T08:00:00.123Z"}
```

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | El aviso lleva **la identidad del cambio, no los datos**: ni campos de negocio ni PII. Lo producen los triggers de la outbox (`external-services/*/init.sql`, columna `message`) y los llamantes de `POST /<dom>/sync` | Datos personales en topics sin retención (hallazgo 2A-4) y versiones viejas escritas en SAP |
| R-2 | `entityId` es obligatorio; sin él el mensaje no es procesable y se rechaza (`IllegalArgumentException` → error handler → DLT) | Un aviso sin sujeto no se puede atender |
| R-3 | `payloadHash` y `payload` son **opcionales**. Un valor en blanco equivale a ausente. Si vienen, se parsean y se pueden registrar, pero **no son fuente de datos ni deciden el dedupe**: manda el hash calculado sobre el snapshot | Un consumidor que confíe en el payload envía a SAP un estado superado |
| R-4 | **Compatibilidad**: el consumidor acepta a la vez el aviso fino y el formato antiguo con `payloadHash` + `payload`. Es lo que permite vaciar topics y DLT con mensajes viejos | Los mensajes en vuelo durante el despliegue se perderían en la DLT |
| R-5 | Un `null` como valor del registro Kafka es un *tombstone* (compactación), no un aviso: se ignora sin error | Reintentos y DLT por un mensaje que no es de negocio |
| R-6 | La **baja** (`DELETE`) se atiende solo con la identidad: no hay snapshot que releer porque la fila ya no está. Si el aviso no trae hash, se usa uno derivado de la identidad (`PayloadHasher.ofIdentity`), determinista, para que el envío a SAP siga teniendo `Idempotency-Key` | Una baja sin hash rompería la clave de idempotencia del `DELETE` |
| R-7 | Las columnas `payload` y `payload_hash` de las tablas outbox se mantienen **nullables y a NULL durante un ciclo de despliegue** y se retiran después | Romper a un lector que aún las consulte |

## 5. Salida

No produce salida de negocio: es un contrato de entrada. Su efecto observable es
el `IngestionMessage` que construyen los listeners y los controladores REST, con
`payloadHash` y `payload` a `null` en el caso fino.

## 6. Estados y errores

| Situación | Resultado |
|---|---|
| Aviso fino válido | Entra al pipeline del dominio (`RECEIVED → …`) |
| Aviso antiguo con payload | Igual: el payload se ignora como dato |
| `null` (tombstone) | Se ignora, sin ciclo y sin error |
| JSON malformado | Excepción → reintentos del contenedor → DLT |
| Sin `entityId` | `IllegalArgumentException` → DLT |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | El hash del snapshot es determinista, es SHA-256 hex y cambia si cambia cualquier campo de negocio | `PayloadHasherTest#isDeterministicForTheSameSnapshot` · `#isSha256HexLowercase` · `#changesWhenAnyFieldChanges` |
| AC-2 | La forma canónica no depende del orden de iteración de un mapa, respeta el orden de una lista, distingue el nulo del texto `"null"` y no colisiona al mover un valor de campo | `PayloadHasherTest#isStableAgainstMapIterationOrder` · `#listOrderIsSignificant` · `#nullIsNotTheStringNull` · `#doesNotCollideWhenValuesMoveBetweenFields` |
| AC-3 | La baja sin hash usa un hash de identidad determinista | `PayloadHasherTest#identityHashIsDeterministic` · `CustomerKafkaListenerTest#thinDeleteUsesAnIdentityHash` |
| AC-4 | `IngestionMessage` acepta `payloadHash` y `payload` nulos, normaliza el hash en blanco a ausente y sigue aceptando el mensaje antiguo | `IngestionMessageTest#acceptsThinMessageWithoutHashAndWithoutPayload` · `#normalizesBlankPayloadHashToNull` · `#stillAcceptsTheLegacyMessageWithPayload` · `#thinFactoryBuildsMessageWithoutDataFields` |
| AC-7 | Los listeners parsean el aviso fino y también el antiguo con payload | `CustomerKafkaListenerTest#parsesThinMessageWithoutHashAndWithoutPayload` · `#stillParsesTheLegacyMessageCarryingThePayload` · `ArticleKafkaListenerTest#parsesThinMessageWithoutHashAndWithoutPayload` · `#stillParsesTheLegacyMessageCarryingThePayload` |
| AC-9 | El cuerpo REST sin `payloadHash` ni `payload` es válido | `SyncCustomerControllerTest#syncAcceptsBodyWithoutPayloadHashNorPayload` · `SyncArticleControllerTest#syncAcceptsBodyWithoutPayloadHashNorPayload` |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

Log `INFO` por mensaje recibido (topic, key, offset) y por tombstone ignorado.
Log `DEBUG` cuando el `payloadHash` del mensaje difiere del calculado: es la
señal de que alguien sigue publicando el formato antiguo.

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| §3, R-3, R-4 | `common/domain/IngestionMessage` | `IngestionMessageTest` |
| R-1 (hash) , R-6 | `common/domain/PayloadHasher` | `PayloadHasherTest` |
| R-2, R-4, R-5, R-6 | `customer/bootstrap/kafka/CustomerKafkaListener` · `article/bootstrap/kafka/ArticleKafkaListener` | `CustomerKafkaListenerTest` · `ArticleKafkaListenerTest` |
| §3 por REST | `customer/bootstrap/web/SyncCustomerController` · `article/bootstrap/web/SyncArticleController` · `openapi.yml` de cada módulo | `SyncCustomerControllerTest` · `SyncArticleControllerTest` · `OpenApiMatchesControllersTest` |
| R-1, R-7 (producción del aviso) | `external-services/sqlserver/init.sql` · `external-services/postgresql/init.sql` · `external-services/debezium/register-*.json` | sin test automático: requiere Docker (ver §10) |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-19 | Spec inicial. El aviso de cambio pasa de llevar la entidad entera a llevar solo su identidad ([ADR-0013](../../architecture/adr/0013-outbox-mensaje-fino-sin-payload.md)). Los triggers y el conector Debezium se han cambiado y revisado leyendo el SQL, **sin ejecutarlos**: no había Docker disponible en la sesión | — |
