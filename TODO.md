# TODO — lo que queda y quién lo desbloquea

> Lista viva de lo pendiente tras ejecutar el plan de la auditoría
> ([`docs/auditorias/2026-09-10-plan-de-accion.md`](docs/auditorias/2026-09-10-plan-de-accion.md)).
> Aquí solo va lo **accionable**; el detalle de cada punto vive en el documento
> que se enlaza. Se tacha al hacerlo y se anota la fecha. El backlog de ideas
> (no compromisos) sigue en [`docs/MEJORAS-Y-PROPUESTAS.md`](docs/MEJORAS-Y-PROPUESTAS.md).

## Bloqueado por el tenant SAP de test (hay que estar en su red)

- [ ] Ejecutar la [checklist del tenant](docs/testing/CHECKLIST-TENANT-SAP.md) con `scripts/env/test.env` (ya generado en local, gitignored). Anotar cada respuesta en el spec afectado (§10).
- [ ] Según §1 de la checklist (¿PATCH parcial?): diseñar e implementar el **upsert idempotente** con `AddressID`/ETag persistidos en la imagen (PRD-11, auditoría B3).
- [ ] Contacto por `A_AddressEmailAddress` / `A_AddressPhoneNumber` (PRD-2): hoy el adaptador OData de CONTACT no envía email ni teléfono.
- [ ] Qué operación es la **baja** en S/4 (flag de bloqueo del BP vs `DELETE`) y ajustar `BusinessPartnerODataAdapter.delete` ([`baja-cliente.md`](docs/sdd/customer/baja-cliente.md) R-2).
- [ ] Validar en el tenant: `SEPAMandateStatus` (1/3/4), `SenderType=BUS1006`, `SEPAMandateApplication=F`, y si `A_BusinessPartnerBank` acepta `BankCountryKey` + IBAN sin `BankNumber` ([`sincronizacion-datos-bancarios.md`](docs/sdd/customer/sincronizacion-datos-bancarios.md) R-5).
- [ ] Configurar `SAP_SEPA_CREDITOR_ID` (identificador de acreedor SEPA de la empresa): sin él el adaptador de mandatos no llama a SAP.

## Keycloak (equipo de identidad)

- [ ] Crear el cliente `sap-integration` y los roles `sap-read`, `sap-write`, `sap-admin`, `sap-superadmin`, `sap-external-read` ([`KEYCLOAK.md`](docs/tools-integrations/KEYCLOAK.md)).
- [ ] Probar la API con un token real (`KEYCLOAK_ISSUER_URI`, `APP_SECURITY_ENABLED=true`): 401 sin token, 403 sin rol, `masked: true` con `sap-external-read`.
- [ ] Decidir si hace falta autorización **por dato** (qué clientes ve cada usuario), no solo por rol (ADR-0007 §4).

## Kubernetes (plataforma)

- [ ] Registro de imágenes corporativo: cambiar `ghcr.io/<organizacion>/...` en `deploy/k8s/overlays/*/kustomization.yaml` y en el job `image` de la CI.
- [ ] Cómo llegan los `Secret` al clúster (sealed-secrets, External Secrets Operator o Vault) — OPS-7. Claves esperadas en [`deploy/README.md`](deploy/README.md).
- [ ] Ajustar los DNS de servicio (`kafka.messaging.svc`, `mongodb.data.svc`, legacy) en `deploy/k8s/base/`.
- [ ] Ingress solo para `/customers/**` y `/articles/**`; `/actuator` no se expone; TLS en el ingress.
- [ ] `ServiceMonitor` si Prometheus va con el Operator (hoy anotaciones `prometheus.io/*`).
- [ ] Copia de seguridad y restauración de Mongo y Elasticsearch con RTO/RPO probados ([`APTITUD-PRODUCCION.md`](docs/operacion/APTITUD-PRODUCCION.md) §5).

## Observabilidad y operación

- [ ] Destino de trazas (Tempo o colector OTLP) y activar `TRACING_ENABLED=true` (ADR-0009, OBS-2).
- [ ] **Consumidor de `sap.sync.alerts`** (correo, ticket o panel) y regla de alerta en Grafana sobre el `WARN` «ALERTA sincronizacion parcial» (OPS-8, ADR-0010).
- [ ] Panel del pipeline en Grafana: entidades por estado, `SAP_ERROR`, `sap_sync_feature_result_total`, profundidad de la DLT (OBS-3).
- [ ] Reproceso desde la DLT (OPS-2): hoy los mensajes se inspeccionan a mano.

## Decisiones de negocio ([`APTITUD-PRODUCCION.md`](docs/operacion/APTITUD-PRODUCCION.md))

- [ ] Plazo de **retención** del histórico y de los topics con PII (RGPD art. 5.1.e); base legal y DPA con SAP.
- [ ] Objetivos de rendimiento con cifra (eventos/día, latencia, ráfagas) y prueba de carga.
- [ ] **Quién opera** el sistema (equipo, horario, escalado).
- [ ] Alcance de `supplier`: entra en el producto o se retira del reactor (PRD-1).

## Deuda técnica acotada

- [ ] E2E con Testcontainers: outbox → Debezium → app → WireMock → Mongo/ES (TEST-1/TEST-4). Dos hallazgos solo se vieron en vivo por no tenerlo ([`docs/incidencias/`](docs/incidencias/README.md)).
- [ ] Resto de duplicación entre dominios: `*HistoryUseCase`, `*HistoryController`, indexers e image stores (DX-8).
- [ ] `dependency:analyze` en `verify` cuando `maven-dependency-plugin` lea class files de Java 25.
- [ ] Enmascarado de PII en **logs** y registro de accesos (SEC-3).
- [ ] Reevaluar el VDM del Cloud SDK cuando soporte Boot 4 (ADR-0001, OPS-6).

## Método

- [ ] **Verificación por una sesión distinta** de las fases cerradas (lo exige el plan): [`cierre-bloqueantes.md`](docs/auditorias/2026-09-12-cierre-bloqueantes.md).
- [ ] Decisiones D-3 (Debezium Server / Event Router SMT) y D-4 (OData V4) antes del compose del e2e y del primer consumo de una API V4.
