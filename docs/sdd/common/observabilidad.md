# Observabilidad y operación del pipeline

| | |
|---|---|
| **Dominio** | `common` (capacidad transversal; la usan los orquestadores de cada dominio y el cliente SAP) |
| **Estado** | ⚠️ implementado con brechas: métricas y parada ordenada sí; trazas distribuidas pendientes de la decisión D-7 (starter OTel oficial) y de un colector |
| **Entradas** | ejecución normal de los use cases y del cliente SAP |
| **Destino SAP** | ninguno |
| **Última revisión** | 2026-09-18 |

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
- Operación del stack de recolección (Prometheus, Grafana, Loki, Keycloak): de
  plataforma en cada entorno. El código publica series con los tags
  `application`, `env`, `cluster` (`OBS-005`, R-8).
- Destino de las trazas (Tempo o colector OTLP): plataforma. El código ya
  exporta con `TRACING_ENABLED=true` ([ADR-0009](../../architecture/adr/0009-trazas-con-el-starter-oficial-de-opentelemetry.md)).
- Alertas activas: dependen de la operación del stack; los artefactos viven en
  `deploy/observability/prometheus/rules/alerts.yml` (`OBS-005`, R-9).

## 3. Entrada

| Propiedad | Variable | Default | Uso |
|---|---|---|---|
| `management.metrics.tags.application` | — | `${spring.application.name}` | Distingue las series de `customer-app` y `article-app` |
| `management.metrics.tags.env` | `OBS_ENV` | `local` | Entorno (`local`/`test`/`prod`) inyectado a cada serie Prometheus (R-8) |
| `management.metrics.tags.cluster` | `OBS_CLUSTER` | `local` | Cluster Kubernetes inyectado a cada serie Prometheus (R-8) |
| `server.shutdown` | — | `graceful` | Parada ordenada |
| `spring.lifecycle.timeout-per-shutdown-phase` | `SHUTDOWN_TIMEOUT` | `30s` | Tiempo para terminar lo que está en curso |
| `spring.kafka.consumer.properties.max.poll.interval.ms` | `KAFKA_MAX_POLL_INTERVAL_MS` | `900000` | Margen antes de que Kafka expulse al consumidor |
| `sap.client.calls-per-message` | `SAP_CLIENT_CALLS_PER_MESSAGE` | `5` | Escrituras a SAP que puede provocar un mensaje (cabecera + 4 features) |
| `sap.client.lookup.calls-per-message` | `SAP_CLIENT_LOOKUP_CALLS_PER_MESSAGE` | `6` | Lookups (`GET` de verificación previa) que puede provocar un mensaje |
| `sap.client.lookup.timeout-ms` | `SAP_CLIENT_LOOKUP_TIMEOUT_MS` | `5000` | Timeout de respuesta de un lookup, más corto que el de una escritura |
| `sap.client.retry.write.max-attempts` | `SAP_CLIENT_RETRY_WRITE_MAX_ATTEMPTS` | `2` | Intentos de una escritura no idempotente (solo si el fallo fue antes de enviar) |
| `app.kafka.retry.max-attempts` | `APP_KAFKA_RETRY_MAX_ATTEMPTS` | `3` | Entregas del mensaje que hace el `DefaultErrorHandler` |
| `app.kafka.retry.circuit-open-backoff-ms` | `APP_KAFKA_RETRY_CIRCUIT_OPEN_BACKOFF_MS` | `30000` | Backoff entre entregas cuando el circuito SAP está abierto; cuenta para el presupuesto de reintentos (R-4) |
| `logging.structured.format.console` | `LOGGING_STRUCTURED_FORMAT_CONSOLE` | *(vacío: consola legible)* | `ecs` para JSON (Loki/ELK) |
| `app.notifications.topic` / `app.notifications.kafka.enabled` | `SYNC_ALERTS_TOPIC` / `SYNC_ALERTS_KAFKA_ENABLED` | `sap.sync.alerts` / `true` | destino de las alertas de sincronización parcial |
| `management.tracing.enabled` | `TRACING_ENABLED` | `false` | trazas OTLP con el starter oficial de OTel (ADR-0009) |
| `management.otlp.tracing.endpoint` | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | Tempo o colector |

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | Cada transición de estado incrementa `sap_sync_state_total{domain,state}` | Sin ella no se sabe cuántas entidades hay en `SAP_ERROR` |
| R-2 | Cada etapa del orquestador (`fetch`, `validate`, `index`, `send`) registra su duración en `sap_sync_stage_duration{domain,stage}` (p50/p95/p99), también cuando la etapa falla | Auditoría A9: el timer existía y nunca se invocaba |
| R-2b | Cada parte enviada por separado cuenta en `sap_sync_feature_result_total{domain,feature,result}`; un ciclo con alguna parte fallida emite además un `WARN` «ALERTA sincronizacion parcial» y un mensaje JSON en `sap.sync.alerts` con `cycleId`, `aggregateState` y `attempts` (la traza paso a paso de qué parte entró y cuál no) ([ADR-0010](../../architecture/adr/0010-sin-compensacion-entre-features-marcar-y-avisar.md)) | Sin ello, un cliente a medias en SAP pasa desapercibido, o el aviso no dice **qué** parte falló |
| R-3 | Cada intento HTTP hacia SAP deja una muestra en `sap_client_request_duration{destination,method,outcome}` (`2xx`/`4xx`/`5xx`/`transport_error`; los reintentos cuentan cada uno), y el retry y el circuit breaker `sap` exponen sus métricas Resilience4j (`resilience4j_retry_calls`, `resilience4j_circuitbreaker_state`...) | Auditoría A9: el `WebClient.builder()` estático no registraba nada y el estado del circuito era invisible |
| R-4 | El **presupuesto de reintentos por mensaje** debe ser **menor** que `max.poll.interval.ms`. Desde el 18-09-2026 la fórmula separa lecturas (lookup) de escrituras y suma el backoff de reentrega de Kafka con el circuito abierto: `lecturas × (intentos_lectura × timeout_lookup + backoff) + escrituras × (intentos_escritura × timeout_respuesta + backoff) + (csrf ? 2 × timeout_respuesta : 0) + intentos_kafka × backoff_circuito_abierto`. Se comprueba al arrancar (`RetryBudgetGuard`) y si no cuadra la app no arranca, con el cálculo en el mensaje | Auditoría A10: con los defaults (5,1 min contra 5 min) un SAP degradado provocaba rebalanceos en cascada; sin el sumando de Kafka el presupuesto declaraba 90 s de menos |
| R-5 | La parada es **ordenada**: se dejan terminar las peticiones HTTP y los mensajes en curso hasta `timeout-per-shutdown-phase` | Un mensaje a medias deja la entidad en un estado en vuelo (que la Fase 1 ya recupera, pero no hace falta provocarlo) |
| R-6 | Las series llevan el tag `application` con el nombre de **su** app | Auditoría A9: `sap-integration` en ambas mezclaba las series |
| R-7 | El formato JSON de log (ECS) se activa **solo por configuración** de entorno, sin código ni perfil: en local la consola sigue siendo legible | — |
| R-8 | Cada serie lleva los tags `application`, `env`, `cluster`. El tag `env` distingue `local`/`test`/`prod`; `cluster` identifica el cluster K8s; `application` es `${spring.application.name}`. Los defaults son `local`/`local`/`${spring.application.name}`; valores reales se inyectan vía `OBS_ENV`/`OBS_CLUSTER`/`spring.application.name` (variables de entorno, `ConfigMap` o `application*.yml`). Sin ellos, la app no falla: los valores por defecto dan una señal obvia de entorno sin provisionar | Sin la dimensión `env`/`cluster`, las series de `local`, `test` y `prod` se mezclan en los paneles y no se puede distinguir el entorno de origen |
| R-9 | Los artefactos consumibles por la plataforma (dashboards Grafana y reglas Prometheus) viven **versionados en el repo** bajo `deploy/observability/`. La plataforma los aprovisiona en cada entorno. Variables de datasource: `${DS_PROMETHEUS}` (UID `prometheus`) y `${DS_LOKI}` (UID `loki`) | Si los dashboards/alertas viven fuera del repo, divergen del código que publica las series que referencian |

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
| AC-4 | El peor caso por mensaje suma lecturas, escrituras, CSRF opcional y backoff de reentrega Kafka; si alcanza `max.poll.interval.ms` la app no arranca, y con los defaults del proyecto (15 min) sí | `RetryBudgetGuardTest` (6 tests) |
| AC-5 | El contexto de cada app arranca con la configuración de operación (`graceful`, `max.poll.interval`, tag `application`) | `CustomerApplicationContextTest` · `ArticleApplicationContextTest` |
| AC-6 | Cada serie de R-1, R-2, R-2b, R-3 lleva los tags `application`, `env`, `cluster` correctos en local y test, incluyendo el caso "variable no definida → default `local`" | `CustomerApplicationContextTest#contextExposesPrometheusEndpointWithApplicationEnvClusterTags` · `#defaultClusterTagIsLocalWhenObsClusterMissing`, y los gemelos en `ArticleApplicationContextTest` |
| AC-7 | Los artefactos de `deploy/observability/` parsean como JSON/YAML válido, declaran las variables de datasource correctas, y cada regla Prometheus referencia una serie que la app publica | `DeployObservabilityStructureTest` (6 tests) |

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
| R-8 | `common/src/main/resources/application-common.yml` (`management.metrics.tags.application`/`env`/`cluster`) · `deploy/k8s/base/common.yaml` (`OBS_ENV`/`OBS_CLUSTER`) · `scripts/env/*.env` | `*ApplicationContextTest` (`…#contextExposesPrometheusEndpointWithApplicationEnvClusterTags`, `…#defaultClusterTagIsLocalWhenObsClusterMissing`) |
| R-9 | `deploy/observability/{grafana/dashboards,prometheus/rules}/` | `DeployObservabilityStructureTest` (6 tests) |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-23 | **OBS-005**: tags `application`/`env`/`cluster` en las series Prometheus (R-8); dashboards Grafana y reglas Prometheus versionados en `deploy/observability/` para que la plataforma los aprovisione (R-9). AC-6 y AC-7 con sus tests. §2 reduce "Fuera" a la operación del stack de plataforma | — |
| 2026-09-18 | R-4 reescrita: la verificación previa (upsert) añade lookups con timeout y política de reintento propios, y faltaba el backoff de reentrega Kafka con el circuito abierto (auditoría 2A-12); la alerta de R-2b ahora lleva `cycleId`/`aggregateState`/`attempts` | — |
| 2026-09-14 | R-2b (ADR-0010): métrica por feature y alerta de sincronización parcial (`SyncNotificationPort`, `KafkaSyncNotificationAdapter`) | — |
| 2026-09-12 | D-7 (ADR-0009): `spring-boot-starter-opentelemetry` apagado por defecto; `TRACING_ENABLED`/`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`; exportación OTLP de métricas desactivada (Prometheus por scraping) | — |
| 2026-09-12 | Fase 7: `MetricsPort` en el dominio; `application` deja de importar Micrometer | — |
| 2026-09-12 | Spec inicial (plan Fase 5 parcial, auditoría A9/A10). Timer de etapas cableado en ambos orquestadores; timer por intento HTTP y binder de Resilience4j en el cliente SAP; tag `application` por app; `RetryBudgetGuard` con `max.poll.interval.ms` a 15 min; parada ordenada; formato ECS de log por variable de entorno. Trazas: pendientes de D-7 | — |
