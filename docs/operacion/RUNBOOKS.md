# Runbooks — qué hacer cuando pasa lo que ya sabemos que pasa

Guías paso a paso para las incidencias **conocidas**. Cada una dice cómo se
detecta, cómo se confirma, qué se hace y cómo se comprueba que quedó bien.
Los comandos son los del entorno local (`scripts/env/local.env`); en test
cambian host y credenciales, no el procedimiento. Quién opera esto y con qué
guardias: pendiente ([`APTITUD-PRODUCCION.md`](APTITUD-PRODUCCION.md)).

| # | Síntoma | Runbook |
|---|---|---|
| 1 | Una entidad no se sincroniza y su estado es intermedio (`SENDING_SAP`, `INDEXING`...) o `SAP_ERROR` | [Entidad atascada o en error](#1-entidad-atascada-o-en-sap_error) |
| 2 | Hay mensajes en `outbox.CUSTOMER-dlt` / `outbox.ARTICLE-dlt` | [Mensaje en la DLT](#2-mensaje-en-la-dlt) |
| 3 | Muchas entidades pasan a `SAP_ERROR`; `resilience4j_circuitbreaker_state` en `open` | [SAP caído o degradado](#3-sap-caído-o-degradado) |
| 4 | La app no arranca | [La app no arranca](#4-la-app-no-arranca) |
| 5 | Rebalanceos de Kafka en cascada, el mismo mensaje se procesa varias veces | [Presupuesto de reintentos](#5-rebalanceos-de-kafka-y-presupuesto-de-reintentos) |
| 6 | Hay que dar de baja a un cliente o revocar un mandato | [Baja y bloqueo](#6-baja-de-cliente-y-revocación-de-mandato) |

## 1. Entidad atascada o en `SAP_ERROR`

**Detección**: `sap_sync_state_total{state="SAP_ERROR"}` sube; o alguien pregunta
por un cliente que «no llegó a SAP».

**Confirmar**:
```bash
MSYS_NO_PATHCONV=1 docker exec mongodb mongosh --quiet customer --eval \
  'db.sync_state.find({entityId:"CUST-001"}).sort({seq:-1}).limit(5).forEach(x=>print(x.seq,x.stateCode,x.payloadHash,x.timestamp))'
```
Estados: 9 = `SENT_SAP`, 10 = `SAP_ERROR`, 99 = `ERROR`; 1-8 son intermedios
([`../sdd/common/maquina-de-estados.md`](../sdd/common/maquina-de-estados.md) §6).

**Qué hacer**: nada especial. Desde la Fase 1 **un evento nuevo siempre abre
ciclo**, venga la entidad de `SAP_ERROR`, `ERROR` o de un estado intermedio
(proceso muerto a mitad). Provoca el evento:
- por CDC: un `UPDATE` inocuo en el legacy (`UPDATE dbo.customers SET updated_at = SYSDATETIME() WHERE id='CUST-001'`), o
- por REST: `POST /customers/sync` con un `payloadHash` **nuevo** (uno repetido
  respecto al último `SENT_SAP` se deduplica y no hace nada).

**Comprobar**: el último `sync_state` es `SENT_SAP`; la imagen
(`customers_current`) refleja el legacy; el histórico tiene el intento.

**Si vuelve a `SAP_ERROR`**: mira el log `Error SAP POST ... status=4xx`: un 4xx
es dato o contrato (arreglar el dato en el legacy o el mapeo), no reintentar.

## 2. Mensaje en la DLT

**Detección**: consumidor de `outbox.<DOM>-dlt` con mensajes; log
`DeadLetterPublishingRecoverer`.

**Confirmar**:
```bash
docker exec kafka-broker kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic outbox.CUSTOMER-dlt --from-beginning --max-messages 5 --property print.headers=true
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
| `Could not resolve placeholder 'SQLSERVER_PASSWORD'` (o `POSTGRES_*`) | El YAML no trae credenciales de BD | Cargar `scripts/env/<env>.env` (`set -a; source ...; set +a`) |
| `Presupuesto de reintentos por mensaje ... alcanza max.poll.interval.ms` | Timeouts/intentos subidos sin subir Kafka | Subir `KAFKA_MAX_POLL_INTERVAL_MS` o bajar `SAP_CLIENT_RESPONSE_TIMEOUT_MS` |
| `Port 8081 was already in use` | Instancia anterior viva (en Windows `pkill` no la mata) | `netstat -ano \| grep :8081` → `taskkill //PID <pid> //F`, o `scripts/stop-all.sh --apps-only` |
| `ddl-auto: validate` falla | El legacy aún no tiene el DDL (SQL Server tarda minutos) | Esperar a `sqlserver-init`; `start-all.sh` ya lo hace |

## 5. Rebalanceos de Kafka y presupuesto de reintentos

**Detección**: log `Member ... sending LeaveGroup request ... poll interval`,
el mismo `entityId` procesándose varias veces, `SAP_ERROR` en ráfaga.

**Causa**: un mensaje tarda más que `max.poll.interval.ms` (SAP degradado ×
reintentos × features). El arranque ya lo comprueba (`RetryBudgetGuard`); si
pasa en caliente es porque Kafka o SAP están más lentos que el peor caso
calculado.

**Qué hacer**: confirmar el cálculo en el log de arranque («Presupuesto de
reintentos por mensaje: N ms»); si N se acerca a `max.poll.interval.ms`, bajar
`SAP_CLIENT_RESPONSE_TIMEOUT_MS` o `SAP_CLIENT_RETRY_MAX_ATTEMPTS`, o subir
`KAFKA_MAX_POLL_INTERVAL_MS`. Ver
[`../sdd/common/observabilidad.md`](../sdd/common/observabilidad.md) R-4.

## 6. Baja de cliente y revocación de mandato

- **Cliente**: la baja llega por CDC (`operation=DELETE`). Emite `DELETE` en SAP
  y deja la imagen con `status=BLOCKED`; **no borra** nada nuestro (modelo de
  bloqueo, [`../sdd/customer/baja-cliente.md`](../sdd/customer/baja-cliente.md)).
  Comprobar: `db.customers_current.findOne({_id:"CUST-002"}).status == "BLOCKED"`.
  Qué significa la baja en S/4 (bloqueo vs borrado) se valida contra el tenant.
- **Mandato SEPA**: se **cancela** (`PATCH SEPAMandateStatus=3`), nunca se borra
  ([`../sdd/customer/baja-mandato-sepa.md`](../sdd/customer/baja-mandato-sepa.md)).
  Requiere `SAP_SEPA_CREDITOR_ID`. Hoy no hay evento del legacy que lo dispare.
