# ADR-0008 — Kubernetes (dos clústeres: test y producción) como plataforma de despliegue

| | |
|---|---|
| **Estado** | ✅ aceptada |
| **Fecha** | 2026-09-12 |
| **Decisión del plan** | D-9; decisión del usuario del 2026-09-12 |
| **Reevaluar cuando** | haya más de dos entornos o más de dos apps y la configuración por Kustomize deje de escalar (Helm), o la empresa adopte GitOps (Argo/Flux) con otra convención |

## 1. Contexto

No había artefacto de despliegue (auditoría A21). La empresa dispone de dos
clústeres de Kubernetes, uno de test y otro de producción, con Prometheus,
Grafana y Loki ya operativos.

## 2. Opciones

| | Kustomize (base + overlays) | Helm chart | BTP Cloud Foundry (`mta.yaml`) |
|---|---|---|---|
| Curva | plana; YAML plano | plantillas y valores | otra plataforma que no se usa |
| Dos entornos | un overlay cada uno | un `values` cada uno | — |
| Herramientas | `kubectl -k`, nada más | Helm | cf CLI |
| Escala | bien hasta unas pocas apps | mejor con muchas variantes | — |

## 3. Decisión

Imagen OCI con buildpacks (`spring-boot:build-image`, sin Dockerfile),
publicada por la CI al etiquetar `v*`. Manifiestos **Kustomize** en
`deploy/k8s/` (base con las dos apps; overlays `test` y `prod`). Secretos
fuera del repo (`Secret` externos que la app exige al arrancar). Sondas de Boot,
parada ordenada > `terminationGracePeriodSeconds`, logs ECS para Loki,
`/actuator/prometheus` para el *scraping*, ingress solo para la API.

## 4. Consecuencias

- Cómo llegan los secretos al clúster (sealed-secrets, ESO, Vault) queda como
  decisión de plataforma; los manifiestos solo nombran los `Secret`.
- Réplicas > 1 desde el primer día: el estado ya tolera varias instancias
  (versión optimista, ADR-0002).
- Copia de seguridad y restauración de Mongo/ES pasan a ser tarea de la
  plataforma ([`../../operacion/APTITUD-PRODUCCION.md`](../../operacion/APTITUD-PRODUCCION.md) §5).
- Guía: [`../../../deploy/README.md`](../../../deploy/README.md).
