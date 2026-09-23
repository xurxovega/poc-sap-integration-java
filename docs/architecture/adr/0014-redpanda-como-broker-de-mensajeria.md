# ADR-0014 — Broker de mensajería: Redpanda en lugar de Apache Kafka

| | |
|---|---|
| **Estado** | ✅ aceptada |
| **Fecha** | 2026-09-23 |
| **Decisión del plan** | OPS-010; cierre de **D-16** (supuesto abierto sobre Kafka multi-AZ compartido por clústeres) y de las decisiones del plan `plan-f4-features.md` §6 |
| **Specs afectados** | [`../../sdd/common/broker-de-mensajeria.md`](../../sdd/common/broker-de-mensajeria.md) (NUEVO), [`../../sdd/sap-api-catalog.md`](../../sdd/sap-api-catalog.md) (Kafka Connect sigue siendo Debezium) |
| **ADR revisitado** | [ADR-0011](0011-concurrencia-entre-instancias-fencing-sin-lease.md) (consumidor group por clúster) |
| **Reevaluar cuando** | Redpanda rompa la compatibilidad wire con Kafka 3.x; fin de soporte de la rama LTS v25.3.x; o si el Tiered Storage deja de ser opcional por motivos de capacidad |

## 1. Contexto

El broker de eventos que recibe el CDC (`outbox.CUSTOMER`, `outbox.ARTICLE`)
y por el que Kafka Connect publica los eventos de Debezium lleva un Apache
Kafka 3.x con ZooKeeper. ZooKeeper añade **dos JVM extra por clúster**
(el ensemble + el broker), escala mal más allá de 3 brokers, y exige un
quórum separado: una caída o un *split brain* del ensemble tira el broker
aunque el broker esté sano. Sus equivalentes JVM pesan (≈ 2 GB heap entre
ZooKeeper y el broker) y son la fuente dominante de latencia p99 en
operaciones de coordinación.

A esto se suma que un único Kafka multi-AZ visible desde los dos clústeres
de Kubernetes (test y prod) no se podía confirmar como **hecho**: era un
supuesto abierto, **D-16** ([ADR-0011 §5](0011-concurrencia-entre-instancias-fencing-sin-lease.md) §5).

La auditoría preguntó qué broker debía ser el definitivo en todos los
entornos (local, test, prod), buscando (a) eliminar ZooKeeper y (b)
mantener la app cliente inalterada — el wire Kafka 3.x está fijado por
Spring Kafka y por los modelos Java de `sap-api-models`.

## 2. Opciones

| | **Seguir con Kafka 3.x + ZooKeeper** | Kafka 3.x + KRaft (sin ZooKeeper) | **Redpanda v25.3.9 LTS** (elegida) | NATS JetStream |
|---|---|---|---|---|
| ZooKeeper | sí, 2 JVM extra por clúster | no (GA parcial desde Kafka 3.3) | no, broker en C++ | no, meta propia |
| Wire Kafka 3.x | sí | sí | **sí, 100 % wire-compatible** | no |
| Compatibilidad con Spring Kafka 4.x y Debezium 2.7.3 | sí | sí | **verificado por** `DebeziumRedpandaIT` | no (cliente JetStream propio) |
| Latencia p99 | baseline JVM | baseline JVM | **mejor** (C++ vs JVM, sin GC pauses) | propia |
| Madurez del operador K8s | muy alta | media (KRaft aún no es GA total en operadores) | **media** (< 2 años del Operator oficial; helm chart del Operator con FluxCD por defecto — excluido en este PR por manifests Kustomize puros) | alta |
| Riesgos propios | ZooKeeper operativo (ensemble, quórum) | KRaft en GA parcial, validar serialización | madurez del Operator; detalles de offsets internos en Debezium `connect-*` (cubierto por `DebeziumRedpandaIT`) | incompatibilidad con Debezium — descartado en cuanto se plantea |
| Coste de adopción | — | ninguno en la app cliente; cambio en IaC | ninguno en la app cliente (`MESSAGING_BOOTSTRAP` apunta al nuevo DNS); cambio en IaC | migrar CDC, los listeners y todos los contratos |

> **Por qué NATS JetStream queda fuera**: Debezium publica conectores para
> Kafka, no para NATS; migrar eso es rehacer la pieza CDC. Wire
> incompatible con Spring Kafka 4.x — habría que cambiar la integración.

## 3. Decisión

**Redpanda v25.3.9 LTS, operado por el Redpanda Operator oficial**.

- **Imagen**: `redpandadata/redpanda:v25.3.9` (`docker.redpanda.com/redpandadata/redpanda-operator:v25.3.9` para el Operator). El **digest sha256 se pinea tras un primer `docker pull` + `docker images --digests`** en el clúster de referencia (criterio A22 del proyecto — pendiente al primer despliegue en cluster real).
- **CRD**: `cluster.redpanda.com/v1alpha2` `kind: Redpanda`, **NO** `RedpandaCluster` legacy. Esquema `spec.clusterSpec.*`. Aplicado por el Operator.
- **Manifests Kustomize puros**, sin Helm con FluxCD: el Helm chart del Operator de Redpanda activa FluxCD por defecto (issue #23083 del repo de Redpanda). Se descartó explícitamente por el coste de un operador de GitOps extra para dos apps y un broker ([ADR-0008](0008-kubernetes-como-plataforma-de-despliegue.md)).
- **Un cluster Redpanda por clúster de K8s** (test y prod), cada uno en su namespace (`sap-integration-test` y `sap-integration-prod`). **Sin replicación entre clusters**: un Redpanda per-cluster.
- **Tiered Storage desactivado en v1** (`tieredStorage.disabled: true`). La activación es **OPS-011** en [`MEJORAS-Y-PROPUESTAS.md`](../../MEJORAS-Y-PROPUESTAS.md), pendiente del plan de capacidad.
- **StorageClass**: `local-path` (k3s por defecto). En otro clúster se ajusta descomentando `storageClassName` en `deploy/k8s/base/redpanda.yaml`.
- **Versions y operaciones**: rpk en lugar de `kafka-*` (tabla de equivalencias en [`../../operacion/RUNBOOKS.md`](../../operacion/RUNBOOKS.md)).
- **Consumer group por clúster**: ahora cada clúster K8s (test, prod) tiene el suyo propio (`customer-consumer`, `article-consumer`). Esto obliga a **revisar [ADR-0011](0011-concurrencia-entre-instancias-fencing-sin-lease.md)** porque su §3 asume un único consumer group compartido entre clústeres — eso ya no es cierto.

## 4. Consecuencias

- **Lo que ganamos ya**:
  - Dos JVM menos por clúster (la del ZooKeeper y la del broker Kafka en Java).
  - Latencia p99 menor: broker en C++ sin pausas de GC.
  - Cero cambios en la app cliente: el wire Kafka 3.x se respeta; la única variable renombrada es la genérica `KAFKA_BOOTSTRAP` → `MESSAGING_BOOTSTRAP`.
  - Instalación Kustomize pura, sin Helm con FluxCD, encaja con la decisión del proyecto.
- **Lo que asumimos**:
  - Madurez del Operator (< 2 años del Operator oficial); mitigada por: cluster de 3 réplicas, Tiered Storage desactivado, validación contra k3s local documentada en `deploy/README.md` §4.
  - Incompatibilidades menores de offsets internos con Debezium 2.7.3 (`connect-*` topics). Cubiertas por el `DebeziumRedpandaIT` (Testcontainers `RedpandaContainer` v25.3.9) + el `InfrastructureSmokeIT` migrado al mismo `RedpandaContainer`. Si en un futuro aparece un caso no cubierto, ampliar tests antes de tocar operator ni versiones.
  - El **supuesto D-16 se cierra**: ya no hace falta un Kafka multi-AZ compartido por clústeres; cada cluster tiene el suyo. ADR-0011 se reescribe por separado para reflejar "un consumer group por clúster K8s".
- **Lo que NO arregla**:
  - La idempotencia frente a SAP (**sigue dependiendo de** `payloadHash` y del `If-Match` del upsert idempotente, [ADR-0011](0011-concurrencia-entre-instancias-fencing-sin-lease.md) §2).
  - La operación del broker (sondas, dashboards, runbooks): pasan a `rpk`. Mientras rpk no esté disponible, los runbooks viejos siguen valiendo pero aplicados a la nueva herramienta.

## 5. Supuestos cerrados (D-16)

Antes de esta decisión, ADR-0011 §5 asumía un único Kafka multi-AZ visible desde los dos clústeres K8s: si esa hipótesis caía, había que rehacer la idempotencia frente a SAP.

Con un Redpanda por clúster K8s, **cada consumer group pertenece a su clúster**: test y prod consumen su propio topic, y un error de replicación entre clústeres ya no es una variable de diseño (porque no hay replicación). El **fencing por `cycleId`** ([ADR-0011](0011-concurrencia-entre-instancias-fencing-sin-lease.md)) sigue protegiendo el estado en Mongo dentro de un clúster; entre clústeres no hay nada que proteger porque cada uno escribe contra su propio S/4 — si existieran dos S/4 compartidos habría que reconsiderar.

## 6. Disparador de reevaluación

Esta decisión **se reabre** cuando:

1. Redpanda rompa la compatibilidad wire con Kafka 3.x (cambio incompatible con `spring-kafka` o con Debezium 2.7.3): entonces hay que cambiar el cliente o el conector, lo que toca todo el flujo CDC.
2. La rama LTS `v25.3.x` llegue a su fin de soporte: en ese momento se reabre la decisión de versión, no la del broker.
3. El Tiered Storage se vuelva requisito de capacidad (p.ej. histórico Kafka-style > 5 días, con latencia p99 de O(100 ms)): OPS-011 se cierra y este ADR se retitula añadiendo Tiered Storage.
4. La auditoría A22 pida el digest sha256 de TODAS las imágenes: el tag se sustituye por `repo:tag@sha256:...` tras el primer pull al cluster de referencia.
