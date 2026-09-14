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
| Un fallo parcial entre features se marca, se localiza por parte y se avisa (no se compensa) | ✅ decidido | ADR-0010; falta el consumidor de `sap.sync.alerts` (OPS-8) |
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
| Runbooks de las incidencias conocidas | ✅ | [`RUNBOOKS.md`](RUNBOOKS.md) |
| Métricas de estado, etapa, SAP y circuito | ✅ | [`../sdd/common/observabilidad.md`](../sdd/common/observabilidad.md) |
| Recolección y paneles (Prometheus/Grafana/Loki) | ✅ plataforma | ya operativos en la empresa; alertas y panel del pipeline pendientes (OBS-3) |
| Trazas distribuidas | ⬜ | ADR-0009: código listo, falta Tempo/colector y activar `TRACING_ENABLED` |
| **Quién opera** (equipo, horario, escalado) | 🧭 negocio | sin definir; la auditoría lo señala como la restricción que más recomendaciones tumba |
| Reproceso desde la DLT | ⬜ | OPS-2 |

## 4. Rendimiento y capacidad

| Criterio | Estado | Evidencia / quién |
|---|---|---|
| Objetivos con cifra: eventos/día, latencia legacy → SAP, tamaño de ráfagas (cierre de mes) | 🧭 negocio | sin cifra hoy |
| Prueba de carga que los verifique | ⬜ | tras tener la cifra |
| Plan de capacidad (instancias por dominio, particiones Kafka, tamaño de Mongo/ES) | ⬜ | tras la prueba de carga |
| Varias instancias por dominio sin pisarse | ✅ | versión optimista por `seq` (Fase 1), `SyncStateMongoIT` |

## 5. Continuidad

| Criterio | Estado | Evidencia / quién |
|---|---|---|
| Copia de seguridad de Mongo (imagen + estado) y ES (histórico) | ⬜ | plataforma Kubernetes (ADR-0008) |
| Restauración probada, con **RTO** y **RPO** acordados | 🧭 negocio (cifras) + equipo (prueba) | |
| Reconstrucción del estado desde el legacy si se pierde Mongo | ⬜ | posible por diseño (el use case re-lee el legacy), no probado |
| Parada ordenada | ✅ | `server.shutdown=graceful` |

## 6. Entrega

| Criterio | Estado | Evidencia / quién |
|---|---|---|
| Build reproducible (wrapper, enforcer, SBOM, CI) | ✅ | Fase 8 |
| Artefacto de despliegue y entornos test/prod | ✅ | `deploy/k8s` (Kustomize, dos clústeres), imagen con buildpacks en la CI. Pendiente: cómo llegan los secretos al clúster |
| Versionado y tags de release | ✅ norma | [`../../AGENTS.md`](../../AGENTS.md) |
| Auditoría de cierre de bloqueantes | ✅ | [`../auditorias/2026-09-12-cierre-bloqueantes.md`](../auditorias/2026-09-12-cierre-bloqueantes.md) |
