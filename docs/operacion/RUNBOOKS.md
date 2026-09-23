# Runbooks — qué hacer cuando pasa lo que ya sabemos que pasa

Guías paso a paso para las incidencias **conocidas**. Cada una dice cómo se
detecta, cómo se confirma, qué se hace y cómo se comprueba que quedó bien.
Los comandos son los del entorno local (`scripts/env/local.env`); en test
cambian host y credenciales, no el procedimiento. Quién opera esto y con qué
guardias: pendiente ([`APTITUD-PRODUCCION.md`](APTITUD-PRODUCCION.md)).

> **Broker**: a partir de OPS-010 (2026-09-23) el broker del proyecto ya no
> es Apache Kafka sino **Redpanda** (v25.3.9 LTS, K8s Operator + CRD
> `cluster.redpanda.com/v1alpha2`). La app cliente sigue hablando Kafka 3.x
> sobre el wire — por eso los *listeners* y los *contract tests* no
> cambian. Los comandos operativos pasan de `kafka-*` a `rpk`. Equivalencias
> en [Equivalencias `kafka-*` ↔ `rpk`](#equivalencias-kafka---rpk) más abajo.

| # | Síntoma | Runbook |
|---|---|---|
| 1 | Una entidad no se sincroniza y su estado es intermedio (`SENDING_SAP`, `INDEXING`...) o `SAP_ERROR` | [Entidad atascada o en error](#1-entidad-atascada-o-en-sap_error) |
| 2 | Hay mensajes en `outbox.CUSTOMER-dlt` / `outbox.ARTICLE-dlt` | [Mensaje en la DLT](#2-mensaje-en-la-dlt) |
| 3 | Muchas entidades pasan a `SAP_ERROR`; `resilience4j_circuitbreaker_state` en `open` | [SAP caído o degradado](#3-sap-caído-o-degradado) |
| 4 | La app no arranca | [La app no arranca](#4-la-app-no-arranca) |
| 5 | Rebalanceos del broker en cascada, el mismo mensaje se procesa varias veces | [Presupuesto de reintentos](#5-rebalanceos-y-presupuesto-de-reintentos) |
| 6 | Hay que dar de baja a un cliente o revocar un mandato | [Baja y bloqueo](#6-baja-de-cliente-y-revocación-de-mandato) |
| 7 | `ConcurrentTransitionException` repetida sobre las mismas entidades | [Colisiones de concurrencia sostenidas](#7-concurrenttransitionexception-sostenida) |
| 8 | El broker Redpanda no arranca, no acepta escrituras, o pierde particiones | [Broker Redpanda sano o degradado](#8-broker-redpanda-sano-o-degradado) |
| 9 | No hay conectores o aparecen `FAILED` en Kafka Connect | [Conectores Debezium](#9-conectores-debezium) |

### Equivalencias `kafka-*` ↔ `rpk`

Cuando el comando viejo asumía un broker Kafka + utilidades de Apache,
sustitúyelo por el equivalente nativo de Redpanda. **Los flags cambian de
forma**, por eso la tabla es referencia rápida y no una traducción
mecánica:

| Para… | Kafka (antes) | Redpanda (ahora) |
|---|---|---|
| Listar topics | `kafka-topics --bootstrap-server $MESSAGING_BOOTSTRAP --list` | `rpk topic list --brokers $MESSAGING_BOOTSTRAP` |
| Describir un topic | `kafka-topics --bootstrap-server $MESSAGING_BOOTSTRAP --describe --topic outbox.CUSTOMER` | `rpk topic describe outbox.CUSTOMER --brokers $MESSAGING_BOOTSTRAP` |
| Crear un topic (12 particiones, RF=3 test/prod / RF=1 local) | `kafka-topics --bootstrap-server $MESSAGING_BOOTSTRAP --create --if-not-exists --topic X --partitions 12 --replication-factor 3` | `rpk topic create X --partitions 12 --replication 3 --brokers $MESSAGING_BOOTSTRAP` |
| Consumir desde un topic | `kafka-console-consumer --bootstrap-server $MESSAGING_BOOTSTRAP --topic X --from-beginning --property print.headers=true --max-messages 5` | `rpk topic consume X --brokers $MESSAGING_BOOTSTRAP --num 5 --print-headers` |
| Listar consumer groups | `kafka-consumer-groups --bootstrap-server $MESSAGING_BOOTSTRAP --list` | `rpk group list --brokers $MESSAGING_BOOTSTRAP` |
| Describir un consumer group | `kafka-consumer-groups --bootstrap-server $MESSAGING_BOOTSTRAP --describe --group customer-consumer` | `rpk group describe customer-consumer --brokers $MESSAGING_BOOTSTRAP` |
| Salud del cluster | `kafka-broker-api-versions --bootstrap-server $MESSAGING_BOOTSTRAP` | `rpk cluster health --brokers $MESSAGING_BOOTSTRAP` |
| Info del cluster | `kafka-broker-api-versions`, `kafka-metadata-quorum` | `rpk cluster info --brokers $MESSAGING_BOOTSTRAP` |

En docker compose local el broker es accesible desde el host en
`localhost:19092` (puerto externo del `external listener`); dentro de la
red Docker, los servicios usan `redpanda:9092` (puerto interno). Sustituir
`$MESSAGING_BOOTSTRAP` por el que aplique:

- Local: `MESSAGING_BOOTSTRAP='localhost:19092'` (en `scripts/env/local.env`)
- K8s test: el servicio in-cluster del CRD `Redpanda`
- K8s prod: idem

## 1. Entidad atascada o en `SAP_ERROR`

**Detección**: `sap_sync_state_total{state="SAP_ERROR"}` sube; o alguien pregunta
por un cliente que «no llegó a SAP».

**Confirmar** (con token `sap-read`): `GET /customers/CUST-001/state` devuelve el
estado del agregado y de **cada parte**, más `lastCycle`: la traza paso a paso
del último ciclo de sincronización (una línea por parte, con su estado y
detalle) — la parte en `SAP_ERROR` es donde falló, sin tener que ir a Mongo.
`lastCycle` es `null` si el ciclo es anterior a que existiera la traza. El
`detalle` de cada línea se enmascara para quien no tiene el rol `sap-read`
completo (solo `sap-external-read`). La alerta `SYNC_PARTIAL_FAILURE` del topic
`sap.sync.alerts` (y el `WARN` «ALERTA sincronizacion parcial» en Loki) ya
lista las partes OK y fallidas. Sin token, directo en Mongo:
```bash
MSYS_NO_PATHCONV=1 docker exec mongodb mongosh --quiet customer --eval \
  'db.sync_state.find({entityId:"CUST-001"}).sort({seq:-1}).limit(5).forEach(x=>print(x.seq,x.stateCode,x.payloadHash,x.timestamp))'
```
Estados: 9 = `SENT_SAP`, 10 = `SAP_ERROR`, 99 = `ERROR`; 1-8 son intermedios
([`../sdd/common/maquina-de-estados.md`](../sdd/common/maquina-de-estados.md) §6).

**Qué hacer**: no se compensa ([ADR-0010](../architecture/adr/0010-sin-compensacion-entre-features-marcar-y-avisar.md)): las
partes que entraron se quedan en SAP y el siguiente evento reenvía todas. Desde
la Fase 1 **un evento nuevo siempre abre ciclo**, venga la entidad de `SAP_ERROR`, `ERROR` o de un estado intermedio
(proceso muerto a mitad). Provoca el evento:
- por CDC: un `UPDATE` inocuo en el legacy (`UPDATE dbo.customers SET updated_at = SYSDATETIME() WHERE id='CUST-001'`), o
- por REST: `POST /customers/sync` con un `payloadHash` **nuevo** (uno repetido
  respecto al último `SENT_SAP` se deduplica y no hace nada).

**Comprobar**: el último `sync_state` es `SENT_SAP`; la imagen
(`customers_current`) refleja el legacy; el histórico tiene el intento.

**Si vuelve a `SAP_ERROR`**: mira el log `Error SAP POST ... status=4xx`: un 4xx
es dato o contrato (arreglar el dato en el legacy o el mapeo), no reintentar.

**Si se restauró Mongo desde una copia**: comprobar que la colección `sap_keys`
volvió con ella. Antes de escribir, cada adaptador OData mira en `sap_keys` si
la parte ya existe en SAP (upsert idempotente, PRD-11); si esa colección se
restauró vacía o desde un backup más antiguo que `customers_current`, el
siguiente ciclo no encontrará la clave y **creará duplicados** en SAP en vez de
actualizar, aunque el dato en Mongo sea correcto.

## 2. Mensaje en la DLT

**Detección**: consumidor de `outbox.<DOM>-dlt` con mensajes; log
`DeadLetterPublishingRecoverer`.

**Confirmar** (Redpanda; antes era `kafka-broker`/`kafka-console-consumer`):

```bash
docker exec redpanda rpk topic consume outbox.CUSTOMER-dlt \
  --brokers localhost:19092 --num 5 --print-headers
```

Equivalente si el broker está en K8s y se diagnostica desde un pod de debug:

```bash
kubectl -n sap-integration-test run rpk-debug --rm -it --restart=Never \
  --image=redpandadata/redpanda:v25.3.9 -- rpk topic consume outbox.CUSTOMER-dlt \
  --brokers <redpanda.svc>:9092 --num 5 --print-headers
```

La cabecera `kafka_dlt-exception-message` dice por qué.

**Qué hacer** según la causa:
- `IllegalArgumentException` / JSON malformado / operación desconocida: mensaje
  envenenado. No se reprocesa; se corrige el origen (trigger de la outbox) y se
  anota.
- `IllegalStateException` de la máquina de estados: **no debería pasar** desde la
  Fase 1; es un bug, abre incidencia con el fingerprint
  `sync-state:reentrada-no-permitida`.
- Cualquier otra (Mongo/ES/SAP caídos durante los 3 reintentos): reprocesar
  cuando la infra esté bien, lanzando un evento nuevo (runbook 1). No hay
  reinyección automática desde la DLT todavía (backlog OPS-2).

## 3. SAP caído o degradado

**Detección**: `resilience4j_circuitbreaker_state{name="sap"}` = open;
`sap_client_request_duration{outcome="5xx"|"transport_error"}` dispara;
`SapCircuitOpenException` en el log.

**Qué pasa solo**: con el circuito abierto no se llama a SAP; el listener
reintenta con backoff y, agotado, el mensaje va a la DLT. Las entidades quedan
en `SAP_ERROR` y **se recuperan con el siguiente evento** (runbook 1).

**Qué hacer**: nada en la plataforma mientras dure; al volver SAP, el circuito
se cierra solo (`wait-duration-open-ms`, 30 s). Después: recorrer las entidades
en `SAP_ERROR` y provocarles un evento (runbook 1) o esperar al siguiente
cambio natural. Revisar la DLT (runbook 2).

**No hacer**: subir `max-attempts` o timeouts a ciegas; el presupuesto por
mensaje debe seguir cabiendo en `max.poll.interval.ms` (runbook 5).

## 4. La app no arranca

Leer el **primer** `Caused by` del log. Los tres casos conocidos fallan a
propósito y dicen qué falta:

| Mensaje | Causa | Acción |
|---|---|---|
| `Credenciales ... incompletas y sap.auth.allow-stub=false` | Sin credenciales SAP | Rellenar `SAP_*_CLIENT_ID/SECRET/TOKEN_URL` (test) o `SAP_AUTH_ALLOW_STUB=true` (solo mock) |
| `Credenciales del legacy sin resolver: spring.datasource.username='${POSTGRES_USER}' (placeholder sin resolver)` | El YAML no trae credenciales de BD y no se cargó el `.env` (Boot deja el placeholder literal; sin el guard el síntoma era `password authentication failed for user "${POSTGRES_USER}"`) | Cargar `scripts/env/<env>.env` (`set -a; source ...; set +a`) |
| `Presupuesto de reintentos por mensaje ... alcanza max.poll.interval.ms` | Timeouts/intentos subidos sin subir Kafka | Subir `KAFKA_MAX_POLL_INTERVAL_MS` o bajar `SAP_CLIENT_RESPONSE_TIMEOUT_MS` |
| `Port 8081 was already in use` | Instancia anterior viva (en Windows `pkill` no la mata) | `netstat -ano \| grep :8081` → `taskkill //PID <pid> //F`, o `scripts/stop-all.sh --apps-only` |
| `ddl-auto: validate` falla | El legacy aún no tiene el DDL (SQL Server tarda minutos) | Esperar a `sqlserver-init`; `start-all.sh` ya lo hace |

## 5. Rebalanceos y presupuesto de reintentos

**Detección**: log `Member ... sending LeaveGroup request ... poll interval`,
el mismo `entityId` procesándose varias veces, `SAP_ERROR` en ráfaga.

**Causa**: un mensaje tarda más que `max.poll.interval.ms` (SAP degradado ×
reintentos × features). El arranque ya lo comprueba (`RetryBudgetGuard`); si
pasa en caliente es porque el broker o SAP están más lentos que el peor caso
calculado. **Vale tanto para Kafka como para Redpanda** (la app cliente es la
misma, wire Kafka 3.x).

**Qué hacer**: confirmar el cálculo en el log de arranque («Presupuesto de
reintentos por mensaje: N ms»); si N se acerca a `max.poll.interval.ms`, bajar
`SAP_CLIENT_RESPONSE_TIMEOUT_MS` o `SAP_CLIENT_RETRY_MAX_ATTEMPTS`, o subir
`KAFKA_MAX_POLL_INTERVAL_MS`. Ver
[`../sdd/common/observabilidad.md`](../sdd/common/observabilidad.md) R-4.

**Comprobar el lado broker** (Redpanda) cuando la sospecha es de su parte:
rebalanceos no esperados suelen ir acompañados de un broker que perdió
*leadership* de una partición (`rpk cluster health --brokers $MESSAGING_BOOTSTRAP`
lo muestra) o de un cambio de *advertised listeners* (port-forward, NAT).
`rpk topic describe outbox.CUSTOMER --brokers $MESSAGING_BOOTSTRAP`
incluye el *leader* y el `in-sync-replicas` (ISR) por partición: si es < RF,
una réplica está caída y el ISR se recupera en cuanto vuelve.

## 6. Baja de cliente y revocación de mandato

- **Cliente**: la baja llega por CDC (`operation=DELETE`). Emite `DELETE` en SAP
  y deja la imagen con `status=BLOCKED`; **no borra** nada nuestro (modelo de
  bloqueo, [`../sdd/customer/baja-cliente.md`](../sdd/customer/baja-cliente.md)).
  Comprobar: `db.customers_current.findOne({_id:"CUST-002"}).status == "BLOCKED"`.
  Qué significa la baja en S/4 (bloqueo vs borrado) se valida contra el tenant.
- **Mandato SEPA**: se **cancela** (`PATCH SEPAMandateStatus=3`), nunca se borra
  ([`../sdd/customer/baja-mandato-sepa.md`](../sdd/customer/baja-mandato-sepa.md)).
  Requiere `SAP_SEPA_CREDITOR_ID`. Hoy no hay evento del legacy que lo dispare.

## 7. `ConcurrentTransitionException` sostenida

**Detección**: `WARN`/`ERROR` repetidos con «Transicion concurrente sobre
`<dominio>/<entityId>`», `409 Conflict` frecuentes en `POST /customers/sync`, o
mensajes llegando a la DLT tras agotar los tres reintentos. Una colisión suelta
es **normal** y se recupera sola (es reintentable, ADR-0011); lo que hay que
investigar es la colisión **sostenida** sobre las mismas entidades.

**Confirmar** (Redpanda; antes era `kafka-consumer-groups` / `kafka-topics`):

```bash
# 1) Un consumer group por dominio y por cluster K8s, y que sus miembros
#    sean TODAS las instancias de ESE cluster (si hay dos grupos en el mismo
#    cluster, esa es la causa).
MESSAGING_BOOTSTRAP='<redpanda.svc>:9092'   # o localhost:19092 en compose
rpk group list --brokers "$MESSAGING_BOOTSTRAP"
rpk group describe customer-consumer --brokers "$MESSAGING_BOOTSTRAP"

# 2) Particiones del topic y de su -dlt: deben coincidir y ser >= instancias x concurrency.
rpk topic describe outbox.CUSTOMER --brokers "$MESSAGING_BOOTSTRAP"
rpk topic describe outbox.CUSTOMER-dlt --brokers "$MESSAGING_BOOTSTRAP"

# 3) Traza de la entidad: dos cycleId distintos entrelazados en la misma ventana.
mongosh "$MONGO_URL_CUSTOMER" --eval 'db.sync_state.find({entityId:"CUST-001"}).sort({seq:1})'
```

**Causas y qué hacer**:

| Causa | Señal | Acción |
|---|---|---|
| **Dos consumer groups en el mismo cluster** (uno por réplica) | `rpk group list` devuelve `customer-consumer` más de una vez, o con sufijo de clúster | corregir `CUSTOMER_KAFKA_GROUP` para que sea el **mismo** en todas las réplicas y reiniciar; es la causa que además **duplica escrituras en SAP**. Desde OPS-010 cada cluster K8s ya tiene su **propio** consumer group por dominio ([ADR-0011](../architecture/adr/0011-concurrencia-entre-instancias-fencing-sin-lease.md) revisado 2026-09-23): los offsets NO se comparten entre clusters y eso es lo correcto. |
| **Clave de partición perdida** | mensajes de la misma entidad en particiones distintas | revisar `message.key.columns` / `ExtractField$Key` del conector Debezium: la clave debe ser `entity_id` |
| **REST síncrono concurriendo con CDC** | los 409 coinciden con cargas manuales o de un cliente externo | es el comportamiento esperado: que el llamante reintente. Si es un canal de alto volumen, es **disparador de reevaluación** de ADR-0011 (valorar el *lease*) |
| **Reproceso masivo** (reset de offsets, resincronización) | muchas entidades a la vez, tras una operación conocida | esperar a que drene; si satura, bajar `CUSTOMER_KAFKA_CONCURRENCY` temporalmente |

**Comprobar que quedó bien**: las colisiones desaparecen del log, la DLT no
crece (runbook 2) y las entidades afectadas terminan en `SENT_SAP`. Si hubo
mensajes en la DLT, reprocesarlos según el runbook 2.

**Lo que NO arregla este runbook**: el fencing detecta la colisión al escribir el
estado, cuando la llamada a SAP ya pudo salir. Que un cambio no se escriba dos
veces en SAP depende de la verificación previa (*lookup*), no de esto
([ADR-0011](../architecture/adr/0011-concurrencia-entre-instancias-fencing-sin-lease.md) §4).

## 8. Broker Redpanda sano o degradado

**Detección** (todas opcionales; cualquiera basta):

- `rpk cluster health --brokers $MESSAGING_BOOTSTRAP` ≠ `Healthy`.
- Métricas de la app: `sap_client_request_duration{outcome="transport_error"}`
  sube; `recordStageDuration{stage="fetch"}` sigue normal (la lectura de
  legacy no pasa por el broker).
- Logs del Pod de Redpanda con `ERROR` en
  `vectorized_cluster::archival` (cuestiones de storage) o
  `cluster::partition` (cambios de liderazgo, OOM).

**Confirmar**:

```bash
# Estado del cluster y brokers (leader, réplicas, particiones)
rpk cluster info --brokers $MESSAGING_BOOTSTRAP
rpk cluster health --brokers $MESSAGING_BOOTSTRAP

# Estado de un topic concreto: leader, ISR, RF efectivo
rpk topic describe outbox.CUSTOMER --brokers $MESSAGING_BOOTSTRAP

# Si la infra está en K8s: replicas del StatefulSet
kubectl -n sap-integration-test get redpanda -o jsonpath='{.status.replicas}'
kubectl -n sap-integration-test get pods -l app.kubernetes.io/name=redpanda
```

**Qué hacer**:

| Causa probable | Señal | Acción |
|---|---|---|
| Réplicas en Pod `CrashLoopBackOff` | `kubectl get pods` muestra `redpanda-N` con `Ready=0` | `kubectl describe pod redpanda-N -n <ns>` y revisar `Events` (PVC no provisionado, `StorageClass` inexistente, OOMKilled). El broker se recupera solo cuando la réplica vuelve y el ISR se re-completa: los consumidores seguirán en el broker sano con un breve rebalanceo. |
| `NotEnoughReplicasException` en la app | `KAFKA_BOOTSTRAP` apunta a un broker del que solo 1 réplica está arriba y el topic tiene RF=3 | bajar RF (`rpk topic alter-config --set replication=1`) en local/dev, o traer la réplica caída arriba. En producción la recuperación la hace el controlador. |
| Pod del Operator en `CrashLoopBackOff` | `kubectl logs deploy/redpanda-operator -c operator` | el Operator no puede reconciliar el CRD; los Pods ya levantados siguen funcionando, pero no se aplicarán cambios futuros. Reiniciar el Operator; si persiste, abrir incidencia. |
| Tiered Storage deshabilitado y disco lleno | métrica de disco del Pod al 100 %, write-rejecting | `kubectl exec` en el Pod, liberar o ampliar el PVC. **La desactivación de Tiered Storage es decisión de v1** (ADR-0014); abrir OPS-011 si esto se vuelve operacional. |

**Comprobar que quedó bien**: `rpk cluster health` vuelve a `Healthy`,
las alertas callan, la app retoma el flujo.

## 9. Conectores Debezium

**Detección**: `curl -s http://localhost:8083/connectors?expand=status | jq`
muestra `connector.state=FAILED` o el worker de Connect no responde.

**Confirmar**:

```bash
curl -s http://localhost:8083/connectors/outbox-customer-sqlserver/status | jq
docker exec redpanda rpk topic list --brokers localhost:19092
# Deben existir outbox.CUSTOMER, outbox.CUSTOMER-dlt, connect-configs,
# connect-offsets, connect-statuses
```

**Causas y qué hacer**:

| Causa | Señal | Acción |
|---|---|---|
| Worker sin arrancar | `8083/connectors` devuelve error 5xx | `kubectl logs deploy/debezium-connect -c connect` o `docker logs kafka-connect`; `BOOTSTRAP_SERVERS=redpanda:9092` y los `*_STORAGE_TOPIC` en el ConfigMap deben estar correctos |
| Topic interno `connect-offsets` no existe o RF incompatible | `ERROR topics with replication.factor: 3 > available brokers` | `rpk topic create connect-offsets --partitions 25 --replication 3` (o RF=1 en local); `connect-configs` y `connect-statuses` igual |
| Conector en `FAILED` por cambio de esquema del legacy | `WARN Table definition ... not found` | recrear el conector con el `register-*.json` actualizado; **Detalle completo** en [`../tools-integrations/SERVICIO-AUTENTICACION.md`](../tools-integrations/SERVICIO-AUTENTICACION.md) (placeholder; en su defecto, [`../../external-services/debezium/README.md`](../../external-services/debezium/README.md)) |
