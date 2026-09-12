# Despliegue en Kubernetes (decisión D-9)

Dos clústeres de Kubernetes, uno de **test** y otro de **producción**
([ADR-0008](../docs/architecture/adr/0008-kubernetes-como-plataforma-de-despliegue.md)).
Los manifiestos son Kustomize: una base con las dos apps y un overlay por
entorno. Sin Helm por ahora: dos apps y una decena de variables no lo justifican.

```
deploy/k8s/
├── base/            # Deployment + Service + ConfigMap por app, ServiceAccount, ConfigMap común
└── overlays/
    ├── test/        # namespace, imagen/tag, réplicas, Keycloak y S/4 de test
    └── prod/
```

## Imagen

La construye Cloud Native Buildpacks vía el plugin de Boot (sin Dockerfile):

```bash
./mvnw -pl customer,article spring-boot:build-image -DskipTests   # poc-sap/<app>:<version>
```

La CI (`.github/workflows/ci.yml`, job `image`) la construye y publica en el
registro al etiquetar `v*`. El nombre del registro se cambia en
`overlays/*/kustomization.yaml` (`images:`).

## Secretos

Ningún secreto vive en el repo. Cada overlay espera dos `Secret` **externos**
(`sap-integration-secrets` y `<app>-secrets`) con estas claves:

| Secret | Claves |
|---|---|
| `sap-integration-secrets` | `SAP_S4_CLIENT_ID`, `SAP_S4_CLIENT_SECRET`, `SAP_S4_TOKEN_URL`, `SAP_BTP_*` (si aplica), `SAP_SEPA_CREDITOR_ID` |
| `customer-secrets` | `SQLSERVER_USER`, `SQLSERVER_PASSWORD` |
| `article-secrets` | `POSTGRES_USER`, `POSTGRES_PASSWORD` |

Cómo llegan al clúster (sealed-secrets, External Secrets Operator, Vault) es
una decisión de plataforma pendiente. Si faltan, **la app no arranca** y dice
qué falta ([`../docs/sdd/common/autenticacion-sap.md`](../docs/sdd/common/autenticacion-sap.md)).

## Aplicar

```bash
kubectl apply -k deploy/k8s/overlays/test
kubectl -n sap-integration-test rollout status deploy/customer-app
```

## Qué esperan los manifiestos de la plataforma

- Sondas en `/actuator/health/liveness` y `/readiness` (Boot las activa al
  detectar Kubernetes); `startupProbe` de hasta 3 min porque la app valida el
  esquema del legacy al arrancar.
- Prometheus por *scraping* de `/actuator/prometheus` (anotaciones
  `prometheus.io/*`; si se usa el Operator, añadir un `ServiceMonitor`).
- Logs en JSON (ECS) por stdout para Loki.
- `terminationGracePeriodSeconds` 45 s > parada ordenada de Boot (30 s).
- Ingress **solo** para `/customers/**` y `/articles/**`; `/actuator` no se
  expone fuera del clúster (health y prometheus van sin token).
- Kafka, Mongo, Elasticsearch y los legacy se referencian por DNS de servicio
  (`*.svc`); ajustar en `base/common.yaml` y `base/<app>-app.yaml`.
