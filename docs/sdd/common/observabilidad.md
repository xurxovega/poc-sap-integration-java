# Observabilidad y operación del pipeline

| | |
|---|---|
| **Dominio** | `common` (capacidad transversal; la usan los orquestadores de cada dominio y el cliente SAP) |
| **Estado** | ⚠️ implementado con brechas: métricas y parada ordenada sí; trazas distribuidas pendientes de la decisión D-7 (starter OTel oficial) y de un colector |
| **Entradas** | ejecución normal de los use cases y del cliente SAP |
| **Destino SAP** | ninguno |
| **Última revisión** | 2026-09-12 |

## 1. Objetivo

Que operar el sistema no requiera leer código: cuántas entidades hay en cada
estado, cuánto tarda cada etapa, cómo responde SAP y en qué estado está el
cortacircuitos, por dominio y por aplicación. Y que una parada o un SAP
degradado no provoquen daños colaterales (mensajes a medias, rebalanceos de
Kafka en cascada).

## 2. Alcance

**Dentro**: métricas Micrometer expuestas en `/actuator/prometheus`, parada
ordenada, presupuesto de reintentos por mensaje, formato de log estructurado
por configuración.

**Fuera** (y por qué):
- Recolección y paneles (Prometheus + Grafana): aplazado por decisión del
  usuario (backlog OBS-1, OBS-3).
- Trazas distribuidas y `traceId` en logs: requieren la decisión D-7 (starter
  oficial de OpenTelemetry para Boot 4 o javaagent) y un colector (OBS-2).
- Alertas: dependen de OBS-1.

## 3. Entrada

| Propiedad | Variable | Default | Uso |
|---|---|---|---|
| `management.metrics.tags.application` | — | `${spring.application.name}` | Distingue las series de `customer-app` y `article-app` |
| `server.shutdown` | — | `graceful` | Parada ordenada |
| `spring.lifecycle.timeout-per-shutdown-phase` | `SHUTDOWN_TIMEOUT` | `30s` | Tiempo para terminar lo que está en curso |
| `spring.kafka.consumer.properties.max.poll.interval.ms` | `KAFKA_MAX_POLL_INTERVAL_MS` | `900000` | Margen antes de que Kafka expulse al consumidor |
| `sap.client.calls-per-message` | `SAP_CLIENT_CALLS_PER_MESSAGE` | `5` | Llamadas a SAP que puede provocar un mensaje (cabecera + 4 features) |
| `logging.structured.format.console` | `LOGGING_STRUCTURED_FORMAT_CONSOLE` | *(vacío: consola legible)* | `ecs` para JSON compatible con ELK |

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | Cada transición de estado incrementa `sap_sync_state_total{domain,state}` | Sin ella no se sabe cuántas entidades hay en `SAP_ERROR` |
| R-2 | Cada etapa del orquestador (`fetch`, `validate`, `index`, `send`) registra su duración en `sap_sync_stage_duration{domain,stage}` (p50/p95/p99), también cuando la etapa falla | Auditoría A9: el timer existía y nunca se invocaba |
| R-3 | Cada intento HTTP hacia SAP deja una muestra en `sap_client_request_duration{destination,method,outcome}` (`2xx`/`4xx`/`5xx`/`transport_error`; los reintentos cuentan cada uno), y el retry y el circuit breaker `sap` exponen sus métricas Resilience4j (`resilience4j_retry_calls`, `resilience4j_circuitbreaker_state`...) | Auditoría A9: el `WebClient.builder()` estático no registraba nada y el estado del circuito era invisible |
| R-4 | El **presupuesto de reintentos por mensaje** (`calls-per-message × (intentos × timeout + backoff)`) debe ser **menor** que `max.poll.interval.ms`. Se comprueba al arrancar (`RetryBudgetGuard`) y si no cuadra la app no arranca, con el cálculo en el mensaje | Auditoría A10: con los defaults (5,1 min contra 5 min) un SAP degradado provocaba rebalanceos en cascada |
| R-5 | La parada es **ordenada**: se dejan terminar las peticiones HTTP y los mensajes en curso hasta `timeout-per-shutdown-phase` | Un mensaje a medias deja la entidad en un estado en vuelo (que la Fase 1 ya recupera, pero no hace falta provocarlo) |
| R-6 | Las series llevan el tag `application` con el nombre de **su** app | Auditoría A9: `sap-integration` en ambas mezclaba las series |
| R-7 | El formato JSON de log (ECS) se activa **solo por configuración** de entorno, sin código ni perfil: en local la consola sigue siendo legible | — |

## 5. Salida

Endpoint `/actuator/prometheus` de cada app con las series de R-1, R-2, R-3 y
las de Boot (JVM, Kafka, Mongo). Log `INFO` al arrancar con el presupuesto de
reintentos calculado.

## 6. Estados y errores

No aplica a la máquina de estados. Un presupuesto que no cabe es un fallo de
**arranque**.

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Cada estado alcanzado incrementa su contador por dominio | `SyncMetricsTest#incrementStateRegistersCounter` · `#incrementStateTwiceAccumulates` |
| AC-2 | En un ciclo completo, el orquestador registra la duración de `fetch`, `validate`, `index` y `send` | `SyncCustomerUseCaseTest#happyPathRecordsTheDurationOfEveryStage` · `SyncArticleUseCaseTest#happyPathRecordsTheDurationOfEveryStage` · `SyncMetricsTest#recordStageDurationRegistersTimer` |
| AC-3 | Cada intento HTTP deja una muestra con destino, método y resultado; el retry y el circuit breaker `sap` exponen métricas | `RestClientSapClientTest#recordsOneTimerSamplePerHttpAttemptWithDestinationMethodAndOutcome` · `SapResilienceMetricsTest#retryAndCircuitBreakerMetricsAreBoundForTheSapInstances` |
| AC-4 | El peor caso por mensaje se calcula como `calls × (intentos × timeout + backoff exponencial)`; si alcanza `max.poll.interval.ms` la app no arranca, y con los defaults del proyecto (15 min) sí | `RetryBudgetGuardTest` (3 tests) |
| AC-5 | El contexto de cada app arranca con la configuración de operación (`graceful`, `max.poll.interval`, tag `application`) | `CustomerApplicationContextTest` · `ArticleApplicationContextTest` |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

Este spec **es** la observabilidad del sistema. Lo que falta está en §2 (fuera).

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| R-1, R-2 (puerto y fachada) | `common/domain/port/MetricsPort.java` · `common/observability/SyncMetrics.java` (implementación Micrometer) | `SyncMetricsTest` |
| R-2 (etapas) | `customer/application/general/SyncCustomerUseCase.timed` · `article/application/SyncArticleUseCase.timed` | `SyncCustomerUseCaseTest` · `SyncArticleUseCaseTest` |
| R-3 (HTTP) | `common/sap/RestClientSapClient.recordRequest` | `RestClientSapClientTest` |
| R-3 (Resilience4j) | `common/sap/SapIntegrationConfig.sapResilienceMetrics` | `SapResilienceMetricsTest` |
| R-4 | `common/sap/RetryBudgetGuard.java` · `application-common.yml` (`sap.client.calls-per-message`, `max.poll.interval.ms`) | `RetryBudgetGuardTest` |
| R-5, R-6, R-7 | `application-common.yml` (`server.shutdown`, `spring.lifecycle`, `management.metrics.tags`) · `scripts/env/test.env.example` (`LOGGING_STRUCTURED_FORMAT_CONSOLE`) | `*ApplicationContextTest` |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-12 | Fase 7: `MetricsPort` en el dominio; `application` deja de importar Micrometer | — |
| 2026-09-12 | Spec inicial (plan Fase 5 parcial, auditoría A9/A10). Timer de etapas cableado en ambos orquestadores; timer por intento HTTP y binder de Resilience4j en el cliente SAP; tag `application` por app; `RetryBudgetGuard` con `max.poll.interval.ms` a 15 min; parada ordenada; formato ECS de log por variable de entorno. Trazas: pendientes de D-7 | — |
