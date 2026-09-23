# TODO — lo que queda y quién lo desbloquea

> Lista viva de lo pendiente tras ejecutar el plan de la auditoría
> ([`docs/auditorias/2026-09-10-plan-de-accion.md`](docs/auditorias/2026-09-10-plan-de-accion.md)).
> Aquí solo va lo **accionable**; el detalle de cada punto vive en el documento
> que se enlaza. Se tacha al hacerlo y se anota la fecha. El backlog de ideas
> (no compromisos) sigue en [`docs/MEJORAS-Y-PROPUESTAS.md`](docs/MEJORAS-Y-PROPUESTAS.md).

## Bloqueado por el tenant SAP de test (hay que estar en su red)

- [ ] Ejecutar la [checklist del tenant](docs/testing/CHECKLIST-TENANT-SAP.md) con `scripts/env/test.env` (ya generado en local, gitignored), incluidas las secciones nuevas de la revisión 3: §1-§3 (ETag, `AddressID`, PATCH parcial, contacto), §8 (artículo) y §9 (BTP). Anotar cada respuesta en el spec afectado (§10).
- [x] **Upsert idempotente** (PRD-11, auditoría B3): implementado — lookup antes de escribir, alta o `PATCH` con `If-Match` según lo que SAP tenga, `AddressID` persistido en `sap_keys`. **Falta**: verificar contra el tenant si el `PATCH` parcial funciona como se espera y confirmar el formato del `AddressID` (checklist §1-§2). El ETag no se persiste — se relee en el lookup inmediatamente anterior al `PATCH`.
- [x] Contacto por `A_AddressEmailAddress` / `A_AddressPhoneNumber` (PRD-2): implementado — email, teléfono, fax y web viajan ya a las cuatro entidades de comunicación de la dirección. **Falta**: confirmar `Person`/`OrdinalNumber` y el `PATCH` parcial contra el tenant (checklist §3).
- [ ] Qué operación es la **baja** en S/4 (flag de bloqueo del BP vs `DELETE`) y ajustar `BusinessPartnerODataAdapter.delete` ([`baja-cliente.md`](docs/sdd/customer/baja-cliente.md) R-2).
- [ ] Validar en el tenant: `SEPAMandateStatus` (1/3/4), `SenderType=BUS1006`, `SEPAMandateApplication=F`, y si `A_BusinessPartnerBank` acepta `BankCountryKey` + IBAN sin `BankNumber` ([`sincronizacion-datos-bancarios.md`](docs/sdd/customer/sincronizacion-datos-bancarios.md) R-5).
- [ ] Configurar `SAP_SEPA_CREDITOR_ID` (identificador de acreedor SEPA de la empresa): sin él el adaptador de mandatos no llama a SAP.
- [ ] Ruta real de `API_PRODUCT_SRV`/`A_Product` y numeración interna/externa del artículo (checklist §8); ninguna verificada aún.
- [ ] Integración extremo a extremo de BTP con la documentación de Confluence del propietario (carpeta "S4 Public", requiere sesión que este entorno no tiene) y con el checklist §9.

## Keycloak (equipo de identidad)

- [ ] Crear el cliente `sap-integration` y los roles `sap-read`, `sap-write`, `sap-admin`, `sap-superadmin`, `sap-external-read` ([`KEYCLOAK.md`](docs/tools-integrations/KEYCLOAK.md)).
- [ ] Probar la API con un token real (`KEYCLOAK_ISSUER_URI`, `APP_SECURITY_ENABLED=true`): 401 sin token, 403 sin rol, `masked: true` con `sap-external-read`.
- [ ] Decidir si hace falta autorización **por dato** (qué clientes ve cada usuario), no solo por rol (ADR-0007 §4).
- [ ] Decidir **ADR-0012** (servicio externo de autenticación): la propuesta es mantener Keycloak frente a Zitadel y authentik; lo decide el propietario del proyecto ([`SERVICIO-AUTENTICACION.md`](docs/tools-integrations/SERVICIO-AUTENTICACION.md)).

## Kubernetes (plataforma)

- [ ] Registro de imágenes corporativo: cambiar `ghcr.io/<organizacion>/...` en `deploy/k8s/overlays/*/kustomization.yaml` y en el job `image` de la CI.
- [ ] Cómo llegan los `Secret` al clúster (sealed-secrets, External Secrets Operator o Vault) — OPS-7. Claves esperadas en [`deploy/README.md`](deploy/README.md).
- [ ] Ajustar los DNS de servicio (`kafka.messaging.svc`, `mongodb.data.svc`, legacy) en `deploy/k8s/base/`.
- [ ] Ingress solo para `/customers/**` y `/articles/**`; `/actuator` no se expone; TLS en el ingress.
- [ ] `ServiceMonitor` si Prometheus va con el Operator (hoy anotaciones `prometheus.io/*`).
- [ ] Copia de seguridad y restauración de Mongo y Elasticsearch con RTO/RPO probados ([`APTITUD-PRODUCCION.md`](docs/operacion/APTITUD-PRODUCCION.md) §5).
- [ ] Confirmar con plataforma **D-16**: el diseño de concurrencia entre instancias (ADR-0011) asume **un único Kafka multi-AZ visible desde los dos clústeres**. Si en realidad hay un Kafka por clúster, la decisión no basta y hay que rediseñar la idempotencia frente a SAP asumiendo dos consumidores legítimos.
- [ ] **OPS-9** — guard de arranque para la familia BTP: que la app no arranque con un adaptador `Btp*Adapter` activo si `sap.btp.base-url` no está configurada o apunta a `localhost`/vacío (mismo patrón que el guard ya existente para credenciales SAP).

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
- [ ] **D-17**: si el motivo de un fallo parcial (guardado para explicar «qué entró y qué no») puede llevar datos personales, y si eso obliga a acortar su retención frente al resto del histórico.

## Deuda técnica acotada

- [ ] E2E con Testcontainers: outbox → Debezium → app → WireMock → Mongo/ES (TEST-1/TEST-4). Dos hallazgos solo se vieron en vivo por no tenerlo ([`docs/incidencias/`](docs/incidencias/README.md)).
- [ ] Resto de duplicación entre dominios: `*HistoryUseCase`, `*HistoryController`, indexers e image stores (DX-8).
- [ ] `dependency:analyze` en `verify` cuando `maven-dependency-plugin` lea class files de Java 25.
- [ ] Enmascarado de PII en **logs** y registro de accesos (SEC-3).
- [ ] Reevaluar el VDM del Cloud SDK cuando soporte Boot 4 (ADR-0001, OPS-6).
- [ ] `sap.client.lookup.timeout-ms` todavía **no** es un timeout HTTP distinto por llamada: el transporte usa el mismo `sap.client.response-timeout-ms` para todas (brecha declarada en [`upsert-idempotente-sap.md`](docs/sdd/common/upsert-idempotente-sap.md) §6.1).
- [ ] Consumidor de `sap.sync.alerts` (OPS-8): sigue sin nadie que lo escuche (correo, ticket o panel), aunque el aviso ya lleva la traza completa del ciclo.
- [ ] `article` sigue con el puerto de estado mockeado en sus tests (5.ª recurrencia del fingerprint `test:puerto-mockeado-oculta-invariante`, tarea de diseño C2-4: mover `InMemoryStateRepo` a `common`).
- [ ] Event Mesh (SAP → app): decidir el mecanismo (Event Mesh vs webhook de iFlow); sin diseño cerrado todavía.

## Ejecución y registro de la revisión 3 (2026-09-18)

- [x] ~~Ejecutar `./mvnw -pl it -am verify -Ddocker.available=true`~~ — hecho el 2026-09-19 con Docker: 26/26 IT de `it/` en verde, ninguno omitido (`SyncStateMongoIT` con traza por ciclo, lectura desfasada y fencing contra Mongo real).
- [x] ~~Registrar en `feature_evento` los eventos del 18-09 y del 19-09~~ — hecho el 2026-09-19: 14 eventos (4 ALTA, 10 MODIFICACION), `sdd-registry-check.py` sin diferencias (18 features).
- [x] ~~Probar triggers y Debezium en vivo (ADR-0013)~~ — hecho el 2026-09-19: los dos triggers emiten el aviso fino (`message`, `payload` NULL) y llega a `outbox.CUSTOMER` / `outbox.ARTICLE` vía Debezium sin datos personales. Dos trampas documentadas en `external-services/debezium/README.md`: offsets viejos del conector al recrear la base, y topics existentes que `--if-not-exists` no reparticiona.
- [ ] Arrancar las apps contra el entorno local y verificar un ciclo completo con el mensaje fino (relectura del legacy, hash calculado, lookup previo contra el mock): no hecho todavía.

## Método

- [ ] **Verificación por una sesión distinta** de las fases cerradas (lo exige el plan): [`cierre-bloqueantes.md`](docs/auditorias/2026-09-12-cierre-bloqueantes.md) y, para el ciclo 2, [`2026-09-18-auditoria-verificacion-y-delta.md`](docs/auditorias/2026-09-18-auditoria-verificacion-y-delta.md) §10.
- [ ] Decisiones D-3 (Debezium Server / Event Router SMT) y D-4 (OData V4) antes del compose del e2e y del primer consumo de una API V4.
