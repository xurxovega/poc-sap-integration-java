# Observabilidad — artefactos versionados (OBS-005)

Dashboards Grafana y reglas Prometheus que la plataforma de observabilidad
aprovecha en cada entorno. Agnóstico del entorno: la app publica
`/actuator/prometheus` con los tags `application`, `env`, `cluster` y aquí
se versiona lo que la plataforma tiene que provisionar.

## Estructura

```
deploy/observability/
├── grafana/dashboards/         # JSON de dashboards (provisioning filesystem)
│   ├── customer-pipeline.json
│   ├── article-pipeline.json
│   └── sap-resilience.json
├── prometheus/rules/
│   └── alerts.yml              # PrometheusRule para el Prometheus Operator
└── README.md
```

## Convención de datasources

Cada panel usa una variable de templating, **no** una instancia Prometheus/Loki
hardcodeada. UIDs reservados:

- `${DS_PROMETHEUS}` → UID `prometheus`
- `${DS_LOKI}` → UID `loki`

Asigna esos UIDs en el `datasource` de Grafana (Helm chart, Operator o UI) y
los dashboards los encontrarán al provisionarse.

## Cómo los consume la plataforma (referencia)

Estos YAMLs no los aplica la app. La plataforma (Prometheus Operator +
Grafana) los carga con un `ConfigMap` y los expone como CRDs:

```bash
# Prometheus Operator (PrometheusRule)
kubectl apply -n monitoring -f deploy/observability/prometheus/rules/alerts.yml

# Grafana provisioning filesystem (montar el ConfigMap en /etc/grafana/provisioning/dashboards)
kubectl create configmap grafana-dashboards \
  --from-file=deploy/observability/grafana/dashboards/ -n monitoring

# Grafana Operator (GrafanaDashboard CRD)
kubectl apply -n monitoring -f deploy/observability/grafana/dashboards/customer-pipeline.json
```

## Coherencia con la app

`DeployObservabilityStructureTest` (módulo `common`) rompe el build si:
- un dashboard no parsea como JSON o no declara `${DS_PROMETHEUS}`/`${DS_LOKI}`;
- una alerta referencia una serie que la app **no** publica;
- un ejemplo de entorno (`scripts/env/*.env*`) no declara `OBS_ENV` y `OBS_CLUSTER`.

Si añades una métrica nueva a `common/src/main/java/com/poc/sap/common/observability/SyncMetrics.java`,
actualiza la whitelist del test y `alerts.yml` en el mismo PR.