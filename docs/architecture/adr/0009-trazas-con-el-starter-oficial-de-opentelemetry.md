# ADR-0009 — Trazas con el starter oficial de OpenTelemetry de Spring Boot 4, sobre la observabilidad existente

| | |
|---|---|
| **Estado** | ✅ aceptada |
| **Fecha** | 2026-09-12 |
| **Decisión del plan** | D-7; decisión del usuario del 2026-09-12 (Grafana + Prometheus + Loki ya instalados) |
| **Reevaluar cuando** | se despliegue Tempo (o un colector OTLP) y haya que fijar muestreo, propagación con SAP y retención de trazas |

## 1. Contexto

La empresa ya recoge **métricas** (Prometheus → Grafana) y **logs** (Loki).
Faltan las **trazas** distribuidas y el `traceId` en los logs. El starter de
OpenTelemetry de terceros (2.x) solo soporta Boot 3; Boot 4 trae uno oficial
(`spring-boot-starter-opentelemetry`), lo que resuelve la duda que dejó la
auditoría (javaagent vs starter).

## 2. Opciones

| | Starter oficial de Boot 4 | Javaagent de OTel | Nada |
|---|---|---|---|
| Integración con Micrometer/Boot | nativa (Micrometer Tracing) | externa, sin ver los `Observation` propios | — |
| Despliegue | una dependencia y variables | fichero `-javaagent` en la imagen | — |
| `traceId` en logs ECS | automático | automático | no |
| Coste hoy sin Tempo | ninguno si va apagado | ninguno | — |

## 3. Decisión

`spring-boot-starter-opentelemetry` en `common`, **apagado por defecto**
(`management.tracing.enabled=false`, exportación OTLP de métricas desactivada
porque Prometheus ya hace *scraping*). Se activa con `TRACING_ENABLED=true` y
`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` cuando exista Tempo o un colector. Métricas
y logs siguen como están: Prometheus por *scraping* y Loki leyendo el JSON ECS
de stdout (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, ya en los manifiestos).

## 4. Consecuencias

- Prometheus + Grafana dejan de ser tarea del proyecto (OBS-1): la plataforma
  los aporta; el proyecto solo expone `/actuator/prometheus` y documenta las
  series ([`../../sdd/common/observabilidad.md`](../../sdd/common/observabilidad.md)).
- Las trazas y el `traceId` en logs llegan el día que se active el flag; no
  hace falta tocar código.
- Muestreo al 100 % por defecto (`TRACING_SAMPLING`): revisar con volumen real.
