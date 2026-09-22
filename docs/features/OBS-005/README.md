# Feature: OBS-005 — Recolección y consumo de la observabilidad

## Objetivo

Que la aplicación publique lo que la plataforma de observabilidad necesita
recoger — métricas Prometheus, logs estructurados y trazas OTLP — **sin
decidir dónde corre esa plataforma**. Agnóstico del entorno (local, test,
prod): mismas series, mismas convenciones, mismas URLs configurables.

## Estado

En curso desde 2026-09-22.

## Alcance

**Dentro**:
- Enmienda in-place de `docs/sdd/common/observabilidad.md` (§2 reduce
  "Fuera", §3-bis con el contrato, §7 AC-6/AC-7, §10 con la fecha).
- Tags `application`, `env`, `cluster` configurables en
  `management.metrics.tags` (`common/src/main/resources/application-common.yml`).
- Variables `OBS_ENV`, `OBS_CLUSTER` en `scripts/env/*.env` y en
  `deploy/k8s/base/common.yaml`.
- Dashboards Grafana versionados en `deploy/observability/grafana/dashboards/`
  (datasources `${DS_PROMETHEUS}` / `${DS_LOKI}` resueltos al desplegar).
- Reglas de alerta Prometheus versionadas en
  `deploy/observability/prometheus/rules/alerts.yml`.
- `deploy/observability/README.md` con cómo los consume la plataforma.

**Fuera** (no es feature, es plataforma):
- Levantar Prometheus / Grafana / Loki / Keycloak en `external-services/`.
  Eso es decisión y operación de plataforma en cada entorno.
- Compose override `observability.compose.yml`.
- Operador del cluster de observabilidad (Prometheus Operator, Grafana
  Operator, etc.).
- Provisioning del realm Keycloak (`poc-sap` lo define `KEYCLOAK.md`).

## Ficheros afectados

| Tipo | Ruta |
|---|---|
| Spec (edición) | `docs/sdd/common/observabilidad.md` |
| Backlog | `docs/MEJORAS-Y-PROPUESTAS.md` (OBS-1, OBS-2) |
| Config común | `common/src/main/resources/application-common.yml` |
| Variables de entorno | `scripts/env/local.env`, `scripts/env/test.env.example`, `scripts/env/prod.env.example` |
| Despliegue | `deploy/k8s/base/common.yaml`, `deploy/observability/` (NUEVOS) |
| Docs raíz | `README.md`, `external-services/README.md`, `docs/QUICK_START.md` |
| Glosario | `docs/GLOSSARY.md` |
| Registro | `external-services/mysql/init.sql` |

## Criterios de aceptación

| AC | Criterio | Verificación |
|---|---|---|
| AC-1 | `/actuator/prometheus` lleva los tags `application`, `env`, `cluster` con valores correctos en local y test | `MetricsTagsTest` |
| AC-2 | `deploy/observability/grafana/dashboards/*.json` parsean como JSON válido y referencian `${DS_PROMETHEUS}` o `${DS_LOKI}` | Test JSON parser |
| AC-3 | `deploy/observability/prometheus/rules/alerts.yml` referencia series que la app publica (R-1, R-2, R-2b, R-3 del spec) | Inspección + test de coherencia |
| AC-4 | `mvn verify` en verde | build |
| AC-5 | `python scripts/sdd-registry-check.py` sin diferencias | script del repo |
| AC-6 | `external-services/README.md` documenta que la plataforma aporta el stack de recolección | revisión |

## Validación

- `mvn verify` tras los cambios.
- `start-all.sh` arranca con los nuevos tags visibles en `/actuator/prometheus`.
- JSON parser test en verde.
- Runbook actualizado con cómo provisionar los dashboards en la plataforma.

## Cambios

| Fecha | Cambio |
|---|---|
| 2026-09-22 | Alta de la feature (estado "En curso"). Andamiaje en `docs/features/OBS-005/`. |