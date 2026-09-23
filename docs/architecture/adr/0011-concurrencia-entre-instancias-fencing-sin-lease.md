# ADR-0011 — Concurrencia entre instancias: fencing por `cycleId` y un solo consumer group, sin *lease* por entidad

| | |
|---|---|
| **Estado** | ✅ aceptada, revisada 2026-09-23 por OPS-010 |
| **Fecha** | 2026-09-18; revisada 2026-09-23 |
| **Decisión del plan** | revisión 3, bloque 3 del diseño «envío a SAP sin duplicar ni perder»; supuesto **D-16** cerrado por [ADR-0014](0014-redpanda-como-broker-de-mensajeria.md) (un broker Redpanda por clúster K8s) |
| **Reevaluar cuando** | `POST /customers/sync` pase a ser un canal de producción de alto volumen, se cambie la **clave de partición**, o se observen `ConcurrentTransitionException` sostenidas en la métrica (runbook «ConcurrentTransitionException sostenida») |

## 1. Contexto

El sistema se despliega en **dos clústeres de Kubernetes con varias instancias
cada uno** (ADR-0008). Dos instancias pueden recibir eventos de la **misma
entidad** y trabajar a la vez sobre su cabecera de estado en Mongo. Con lecturas
desfasadas, la máquina de estados lanzaba `IllegalStateException`, que está
declarada **no reintentable**: el mensaje iba a la DLT sin un solo reintento, y
**nadie consume la DLT** — pérdida silenciosa de un cambio de negocio
(auditoría 2B-2, anexo 05 §5).

> **Revisión 2026-09-23 (OPS-010, [ADR-0014](0014-redpanda-como-broker-de-mensajeria.md))**.
> Con Redpanda per-cluster, cada clúster K8s (test, prod) tiene su **propio**
> consumer group (`customer-consumer` en cada clúster, `article-consumer` en
> cada uno). Los offsets no se comparten entre clústeres. Esto cierra el
> supuesto abierto D-16 de esta misma ADR (§5) y elimina la necesidad de un
> Kafka multi-AZ accesible desde los dos clústeres. El fencing por `cycleId`
> sigue protegiendo el estado en Mongo **dentro** de un clúster; entre
> clústeres no hay nada que proteger porque cada cluster escribe contra su
> propio S/4.

Además, los topics se auto-creaban con **una sola partición** (anexo 05 §1): el
orden por entidad que hoy se observa es accidental, no diseñado, y con una
partición no se puede pasar de un consumidor.

Hay que decidir dos cosas: **cómo se serializa el trabajo sobre una entidad** y
**cómo se reparten los mensajes entre clústeres e instancias**.

Términos: un [*fencing token*](../../GLOSSARY.md) es un testigo que impide que un
proceso que perdió la carrera sin enterarse siga escribiendo sobre el trabajo de
otro; un [*lease*](../../GLOSSARY.md) (arrendamiento) es un bloqueo con caducidad
que una instancia toma sobre una entidad para trabajar en exclusiva.

## 2. Opciones

| | **Fencing por `cycleId`** (elegida) | *Lease* con TTL en Mongo | Sin nada (hoy) |
|---|---|---|---|
| Coste por mensaje | 0 operaciones extra: un campo más en un documento que ya se leía | +2 escrituras Mongo (tomar y soltar) y +1 por renovación | 0 |
| Evita el trabajo duplicado | **no**: lo detecta al escribir el estado, cuando SAP ya pudo recibir la llamada | **sí**: la segunda instancia ni empieza | no |
| Modo de fallo propio | ninguno | TTL mal elegido: con el presupuesto SAP de ~5 min el TTL debe superarlo, y una instancia caída bloquea la entidad ese tiempo; un TTL corto produce **dos dueños simultáneos**, peor que no tener *lease* | pérdida de mensajes en la DLT |
| Renovación | — | hace falta un *heartbeat* mientras se habla con SAP: un hilo y un modo de fallo más | — |
| Código | ~30 líneas | ~150 líneas + IT con Testcontainers + runbook de liberación manual | — |

Y en topología, la opción real es **cuántos consumer groups**: uno por dominio
compartido entre clústeres, o uno por clúster.

## 3. Decisión

1. **Fencing por `cycleId`, no *lease*.** La cabecera de estado lleva el ciclo
   que la escribió y una instancia solo avanza si la cabecera es **de su propio
   ciclo**; si no, `ConcurrentTransitionException`, que es **transitoria**.
2. **Un `consumer group` por dominio y por clúster K8s**
   (`customer-consumer` y `article-consumer`), **un grupo en test y otro en
   prod**. Un grupo compartido entre clústeres requería un broker multi-AZ
   único (supuesto D-16, ahora cerrado por [ADR-0014](0014-redpanda-como-broker-de-mensajeria.md)):
   con un Redpanda per-cluster (OPS-010) los offsets no se comparten y cada
   clúster consume su propio topic. **Dentro de un clúster, grupos distintos
   sí serían problemáticos**: cada consumidor procesaría todos los mensajes y
   haría dos escrituras en el **mismo** S/4 por cada cambio, sin fencing que
   valga (los dos ciclos serían legítimos).
3. **12 particiones** en `outbox.CUSTOMER`, `outbox.ARTICLE` **y en sus `-dlt`**,
   con clave `entity_id`. 12 divide entre 1, 2, 3, 4, 6 y 12, y cumple la regla
   **particiones ≥ instancias × `concurrency`** (2 clústeres × 2 instancias × 3).
   El `-dlt` recibe el registro en **la misma partición** que el original, así
   que necesita al menos las mismas o la publicación falla.
4. **`concurrency` declarado** en los listeners
   (`${customer.kafka.concurrency:3}` / `${article.kafka.concurrency:3}`) y
   **`max.poll.records: 10`**: con *virtual threads* y hasta 11 llamadas a SAP
   por mensaje, un lote grande alarga el `poll` y presiona `max.poll.interval.ms`.
5. **`ConcurrentTransitionException` y `SapCircuitOpenException` declaradas
   reintentables** en `KafkaErrorHandlingConfig`, con un **backoff propio para el
   circuito abierto** (30 s, ×1,5): con el backoff normal (1+2+4 s) el mensaje
   llegaba a la DLT con SAP todavía caído. Hasta ahora eran reintentables *por
   omisión* y nadie lo probaba.
6. **`409 Conflict` en el REST síncrono.** `POST /customers/sync` es el único
   camino que puede romper el orden por entidad; ante una colisión responde
   `409` con `ProblemDetail` («lo está procesando otro, reinténtalo»), no un 500.
7. **La aplicación no crea topics.** `app.kafka.topics.create` es `false`; los
   crea la plataforma (IaC o script), ver [`../../../deploy/README.md`](../../../deploy/README.md).

**Por qué el *lease* no compensa ahora**: con un consumer group por clúster
(revisión 2026-09-23) y clave `entity_id`, dentro de un clúster dos mensajes
de la misma entidad van a la misma partición y los consume **el mismo hilo,
en orden**. El caso que el *lease* resolvería —dos ciclos en vuelo de verdad a
la vez, en el mismo clúster— queda reducido a la concurrencia entre el REST
síncrono y Kafka, que es de baja tasa y se cubre con el fencing y el 409.
El *lease* pagaría un coste permanente por un riesgo residual.

## 4. Consecuencias

- **El fencing protege nuestro estado, no SAP.** Detecta la colisión al escribir
  el estado, y para entonces la llamada HTTP ya pudo salir. Lo único que evita el
  duplicado en SAP es la verificación previa (*lookup*) antes de escribir. Si
  esta decisión se implanta sin ese bloque, el sistema **sigue duplicando**.
- **El REST síncrono puede concurrir con Kafka**: se detecta (409), no se
  serializa.
- La entrega sigue siendo *at-least-once*: **exactly-once no existe** en este
  diseño.
- Un mensaje **sigue pudiendo llegar a la DLT** (tres colisiones seguidas, o
  cualquier no reintentable) y **nadie la consume** (OPS-2). Esta decisión no lo
  arregla.
- El circuito abierto añade hasta **3 × 30 s** al peor caso por mensaje;
  `RetryBudgetGuard` debe incluirlo en su cálculo (hoy no lo cuenta).
- Subir las particiones de `outbox.*` obliga a subir las del `-dlt` en el mismo
  cambio.
- Cambiar la clave de partición invalida el punto 1 de esta decisión y obliga a
  reevaluarla (es uno de los disparadores).

## 5. Supuesto D-16, **cerrado por ADR-0014**

Esta decisión asumió desde 2026-09-18 un único Kafka multi-AZ visible desde
los dos clústeres. Un Kafka por clúster habría obligado a MirrorMaker 2 con
sincronización de *offsets*, y un fallo de esa sincronización era reproceso
masivo.

> **Revisión 2026-09-23 (OPS-010)**: ADR-0014 sustituye Kafka por Redpanda
> con **un cluster por clúster K8s**. Los offsets ya no se comparten y el
> supuesto D-16 **se cierra**. Esta ADR se ha actualizado §2 para reflejar
> que el consumer group es **uno por dominio y por clúster**; lo demás
> (fencing, `409 Conflict`, 12 particiones, `concurrency`, `max.poll.records`,
> la política de reintentos, la guarda del presupuesto) sigue idéntico y
> sigue válido.

Ver también: [ADR-0006](0006-kafka-connect-debezium-como-cdc.md) (CDC),
[ADR-0008](0008-kubernetes-como-plataforma-de-despliegue.md) (dos clústeres),
[`../../sdd/common/resiliencia-cliente-sap.md`](../../sdd/common/resiliencia-cliente-sap.md) R-3,
runbook «`ConcurrentTransitionException` sostenida» de
[`../../operacion/RUNBOOKS.md`](../../operacion/RUNBOOKS.md).
