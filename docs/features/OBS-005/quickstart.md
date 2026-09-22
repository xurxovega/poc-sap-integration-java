# Quickstart — OBS-005 Recolección y consumo de la observabilidad

> Arranque aislado para entender y verificar OBS-005 sin tener que leer
> todo el proyecto. El QUICK_START general está en
> [`docs/QUICK_START.md`](../../QUICK_START.md).

## 1. Lo que necesitas

- JDK 25, Maven 3.9+ (usar `./mvnw`), Docker.

## 2. Lo que vas a ver

- Las apps exponen `/actuator/prometheus` con **tres tags adicionales**:
  `application`, `env`, `cluster` (antes solo `application`).
- Dashboards Grafana y reglas Prometheus versionados en `deploy/observability/`,
  listos para que la plataforma los aprovisione en cada entorno.

## 3. Pasos

### a) Arrancar las dos apps en local

```bash
cd ../..                              # raíz del repo
./scripts/start-all.sh
```

### b) Provocar un sync (REST o CDC)

```bash
curl -s -X POST http://localhost:8081/customers/sync \
  -H 'Content-Type: application/json' \
  -d '{"entityId":"CUST-001","operation":"UPDATE"}'
```

### c) Verificar los tags en `/actuator/prometheus`

```bash
curl -s http://localhost:8081/actuator/prometheus | grep sap_sync_state_total | head
```

Salida esperada: cada serie lleva `application="customer-app"`,
`env="local"`, `cluster="local"`.

### d) Verificar que los dashboards versionados son JSON válido

```bash
cd ../..                              # raíz del repo
python -c "import json,glob; [json.load(open(f)) for f in glob.glob('deploy/observability/grafana/dashboards/*.json')]; print('OK')"
```

### e) Verificar que las reglas Prometheus referencian series existentes

> TBD al cierre de la feature — pendiente de la primera versión de los
> dashboards y reglas.

## 4. Cómo deshacer / parar

```bash
./scripts/stop-all.sh
```

## 5. Si algo falla

- **Tags `env`/`cluster` ausentes**: revisa `application-common.yml` →
  `management.metrics.tags.env` y `cluster`, y `scripts/env/local.env`.
- **Dashboards no parsean**: JSON mal formado en
  `deploy/observability/grafana/dashboards/`.
- **Reglas Prometheus no encuentran la serie**: actualiza
  `deploy/observability/prometheus/rules/alerts.yml` con el nombre
  correcto publicado por la app.