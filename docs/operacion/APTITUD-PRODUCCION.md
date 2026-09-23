# Aptitud para producción — criterios y estado

> La auditoría del 2026-09-10 analizó el proyecto como PoC y su B10 pedía un
> «criterio de fin». El proyecto **es la aplicación final**, así que B10 se
> reformula como esta lista: lo que tiene que ser verdad antes de operar con
> datos reales. Cada fila dice quién decide, y las marcadas «negocio» no las
> puede rellenar el equipo de desarrollo solo.

Estado a 2026-09-12: ✅ hecho · 🚧 en curso · ⬜ pendiente · 🧭 decisión pendiente.

## 1. Funcional

| Criterio | Estado | Evidencia / quién |
|---|---|---|
| Todo el pipeline verificado contra el **tenant SAP de test** (upsert, contacto, bloqueo del BP, mandatos) | ⬜ | Fase 3 del plan; checklist en [`../testing/CHECKLIST-TENANT-SAP.md`](../testing/CHECKLIST-TENANT-SAP.md) |
| Ninguna entidad puede quedar atascada | ✅ | Fase 1, verificado en vivo |
| Un fallo parcial entre features se marca, se localiza por parte y se avisa (no se compensa) | ✅ decidido | ADR-0010; `GET /customers/{id}/state` devuelve `lastCycle` con la traza paso a paso de qué entró y qué no (2026-09-18); falta el consumidor de `sap.sync.alerts` (OPS-8) |
| Antes de escribir en SAP se comprueba si la parte ya existe (upsert idempotente: lookup → alta o `PATCH`) | ✅ | PRD-11; los seis adaptadores OData de `customer`, clave persistida en `sap_keys` |
| Un reintento de escritura solo se repite si es seguro que la petición original nunca llegó a salir (no duplica en SAP) | ✅ | `TransportFailures.isBeforeSend`, `common/src/main/java/com/poc/sap/common/sap/RestClientSapClient.java` (retry `sap-write`) |
| `supplier` entra o sale del alcance | 🧭 | decisión de producto (PRD-1) |

## 2. Seguridad y datos personales

| Criterio | Estado | Evidencia / quién |
|---|---|---|
| Sin credenciales, la app no arranca; sin secretos en el paquete | ✅ | [`../sdd/common/autenticacion-sap.md`](../sdd/common/autenticacion-sap.md) |
| Autenticación en las APIs REST y actuator | ✅ (probar contra el Keycloak corporativo) | [`../sdd/common/seguridad-api.md`](../sdd/common/seguridad-api.md), ADR-0007 |
| TLS en tránsito (ingress) y enmascarado de PII en logs | ⬜ | SEC-3; en respuestas a externos ya se enmascara |
| Plazo de **retención** del histórico y de los topics con PII | 🧭 negocio + legal | plan de acción (RGPD art. 5.1.e) |
| Base legal y DPA con SAP | 🧭 cliente + legal | antes de tratar datos reales |
| Si el tenant de test lleva datos reales, la Fase 4 va antes que la 3 | regla | plan de acción |

## 3. Operación

| Criterio | Estado | Evidencia / quién |
|---|---|---|
| Runbooks de las incidencias conocidas | ✅ | [`RUNBOOKS.md`](RUNBOOKS.md) (incluye los específicos del broker Redpanda desde 2026-09-23: equivalencias `kafka-*` ↔ `rpk`, broker sano o degradado, conectores Debezium) |
| Operación del broker | ✅ | **Redpanda v25.3.9 LTS** ([ADR-0014](../architecture/adr/0014-redpanda-como-broker-de-mensajeria.md)). Operado por Redpanda Operator + CRD `cluster.redpanda.com/v1alpha2` (`kind: Redpanda`); un cluster por cluster K8s; `rpk` en lugar de `kafka-*`; manifests Kustomize puros en `deploy/k8s/base/`. Capacidad de respuesta ante fallos del broker: 3 réplicas en test/prod, RF=3, Tiered Storage desactivado (OPS-011 propuesto); la recuperación de una réplica caída la hace el controlador; el listener lo trata como **transitorio** (reintenta con backoff y circuit breaker se abre si pasa el umbral). |
| Métricas de estado, etapa, SAP y circuito | ✅ | [`../sdd/common/observabilidad.md`](../sdd/common/observabilidad.md) |
| Recolección y paneles (Prometheus/Grafana/Loki) | ✅ plataforma | ya operativos en la empresa; alertas y panel del pipeline pendientes (OBS-3) |
| Trazas distribuidas | ⬜ | ADR-0009: código listo, falta Tempo/colector y activar `TRACING_ENABLED` |
| **Quién opera** (equipo, horario, escalado) | 🧭 negocio | sin definir; la auditoría lo señala como la restricción que más recomendaciones tumba |
| Reproceso desde la DLT | ⬜ | OPS-2; la DLT (`<topic>-dlt`) sigue sin consumidor ni reinyección automática |
| Servicio de autenticación (IdP) definitivo | 🧭 | Keycloak en uso (ADR-0007); [ADR-0012](../architecture/adr/0012-servicio-externo-de-autenticacion-idp.md) propone mantenerlo frente a Zitadel/authentik, decisión pendiente del propietario del proyecto |

### 3-bis. Baseline del broker — pre y post OPS-010

Antes de cerrar OPS-010 (sustituir Kafka + ZooKeeper por Redpanda) se
captura el **baseline** de la fase local con el broker antiguo (Kafka 3.x
+ ZooKeeper + Kafka Connect con Debezium 2.7.3). Las cifras se vuelven a
medir en local con Redpanda y se comparan al cierre definitivo del
desmantelamiento del broker antiguo (próximo PR, ya fuera de OPS-010):

| Métrica | Pre Redpanda (Kafka + ZK) | Post Redpanda |
|---|---|---|
| **JVM heap** del broker (ZK ensemble + Kafka broker) | tbd (medir antes del cierre de OPS-010) | una sola binaria, sin JVM; heap = 0 |
| **Throughput CDC** (`outbox.CUSTOMER` end-to-end `RECEIVED → SENT_SAP`, msgs/s sostenidos 5 min) | tbd | tbd |
| **Latencia p99** end-to-end `RECEIVED → SENT_SAP` (ms) | tbd | tbd |
| **Tiempo de arranque del broker** (cold start compose) | ~30 s (ZK + Kafka) | ~2 s (Redpanda en contenedor único) |
| **Imagen base del compose** | `confluentinc/cp-kafka:7.7.1`, `confluentinc/cp-zookeeper`, `debezium/connect:2.7.3.Final` | `redpandadata/redpanda:v25.3.9`, `redpandadata/redpanda-init:v25.3.9`, mismo `debezium/connect:2.7.3.Final` (solo cambia `BOOTSTRAP_SERVERS`) |

> **Fecha del baseline**: `tbd` — se mide tras el cierre de OPS-010 en el
> arranque real del compose con `start-all.sh --with-cdc` y un UPDATE de
> prueba en SQL Server. La columna "pre" se rellena si se levanta Kafka
> en local; con Redpanda ya en compose, la línea de referencia histórica
> sale de `docs/incidencias/` o del registro de la release.
>
> **Origen de las cifras**: arranque local (Docker Desktop / `compose up`),
> mensajes de ejemplo del seed (`CUST-001`, `ART-001`). Para una
> comparación significativa hay que levantar el mismo escenario con Kafka
> 3.x (la rama anterior a OPS-010): comandos y procedimiento en
> [`docs/testing/GUIA-PRUEBAS.md`](../testing/GUIA-PRUEBAS.md) (sección de
> *performance* / *smoke de carga*).

## 4. Rendimiento y capacidad

| Criterio | Estado | Evidencia / quién |
|---|---|---|
| Objetivos con cifra: eventos/día, latencia legacy → SAP, tamaño de ráfagas (cierre de mes) | 🧭 negocio | sin cifra hoy |
| Prueba de carga que los verifique | ⬜ | tras tener la cifra |
| Plan de capacidad (instancias por dominio, particiones Kafka, tamaño de Mongo/ES) | ⬜ | tras la prueba de carga; los topics `outbox.CUSTOMER`/`outbox.ARTICLE` y sus `-dlt` ya llevan 12 particiones (`kafka-init-topics` en local, `APP_KAFKA_TOPICS_PARTITIONS` en despliegue) |
| Varias instancias por dominio sin pisarse | ✅ | versión optimista por `seq` (Fase 1), `SyncStateMongoIT`; entre instancias y clústeres, fencing por `cycleId` con un único consumer group compartido por dominio (ADR-0011): una colisión da `409 Conflict`/reintento, no duplicado |

## 5. Continuidad

| Criterio | Estado | Evidencia / quién |
|---|---|---|
| Copia de seguridad de Mongo (imagen + estado + `sap_keys`) y ES (histórico) | ⬜ | plataforma Kubernetes (ADR-0008). **`sap_keys`** (una fila por dominio/entidad/feature con la clave que asigna SAP, p. ej. `AddressID`) es tan crítica como `sync_state`: si se pierde o se restaura desatrasada, el siguiente ciclo no sabrá que la parte ya existe en SAP y **creará duplicados** en vez de actualizar (PRD-11) |
| Restauración probada, con **RTO** y **RPO** acordados | 🧭 negocio (cifras) + equipo (prueba) | incluir `sap_keys` en la prueba de restauración, no solo `sync_state`/`*_current` |
| Reconstrucción del estado desde el legacy si se pierde Mongo | ⬜ | posible por diseño (el use case re-lee el legacy), no probado |
| Parada ordenada | ✅ | `server.shutdown=graceful` |

## 6. Entrega

| Criterio | Estado | Evidencia / quién |
|---|---|---|
| Build reproducible (wrapper, enforcer, SBOM, CI) | ✅ | Fase 8 |
| Artefacto de despliegue y entornos test/prod | ✅ | `deploy/k8s` (Kustomize, dos clústeres), imagen con buildpacks en la CI. Pendiente: cómo llegan los secretos al clúster |
| Versionado y tags de release | ✅ norma | [`../../AGENTS.md`](../../AGENTS.md) |
| Auditoría de cierre de bloqueantes | ✅ | [`../auditorias/2026-09-12-cierre-bloqueantes.md`](../auditorias/2026-09-12-cierre-bloqueantes.md) |
