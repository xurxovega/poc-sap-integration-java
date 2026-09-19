# Auditoría 2 — Seguridad, PII, despliegue y operabilidad

| | |
|---|---|
| **id** | `poc-sap:auditoria-2:seguridad-operacion-despliegue` |
| **Agente** | `auditor-monitor` (ampliado a seguridad e identidad y a datos personales) |
| **Repo / HEAD** | `A:\Documentos\Code\poc-sap-integration-java` — `ffd86e7` (2026-09-14) |
| **Fecha del informe** | 2026-09-18 |
| **Modo** | solo lectura (0 escrituras, 0 builds, 0 comandos de infraestructura) |

Terminología: [PII](https://docs) = datos de carácter personal; [DLT/DLQ] = *dead letter topic* (cola de mensajes que agotaron reintentos); [MDC] = contexto de log por hilo; [TTL] = plazo de expiración automática; [PDB] = *PodDisruptionBudget*; [HPA] = autoescalado horizontal.

---

## 1. Seguridad de las APIs

### 1.1 Matriz real (leída de las anotaciones, no del spec)

| Endpoint | `@PreAuthorize` real | Fichero:línea |
|---|---|---|
| `POST /customers/sync` | `hasRole(SAP_WRITE)` | `customer/.../web/SyncCustomerController.java:36` |
| `POST /customers/validate` | `hasRole(SAP_WRITE)` | `SyncCustomerController.java:52` |
| `GET /customers/{id}/history` | `hasAnyRole(SAP_READ, SAP_EXTERNAL_READ)` | `CustomerHistoryController.java:50` |
| `GET /customers/{id}/history/diff` | `hasRole(SAP_READ)` | `CustomerHistoryController.java:74` |
| `GET /customers/{id}/state` | `hasAnyRole(SAP_READ, SAP_EXTERNAL_READ)` | `CustomerStateController.java:29` |
| `POST /articles/sync` | `hasRole(SAP_WRITE)` | `article/.../web/SyncArticleController.java:26` |
| `GET /articles/{id}/history` | `hasAnyRole(SAP_READ, SAP_EXTERNAL_READ)` | `ArticleHistoryController.java:35` |
| `GET /articles/{id}/history/diff` | `hasAnyRole(SAP_READ, SAP_EXTERNAL_READ)` | `ArticleHistoryController.java:56` |
| `/actuator/health`, `/health/**`, `/info`, `/prometheus` | `permitAll()` | `ApiSecurityConfig.java:62` |
| resto de `/actuator/**` | `hasRole(SAP_ADMIN)` | `ApiSecurityConfig.java:63` |
| cualquier otra ruta | `authenticated()` | `ApiSecurityConfig.java:64` |

**Comparación con `docs/sdd/common/seguridad-api.md` §5 (líneas 57-65): coincide en las nueve filas.** No hay divergencia spec↔código en la matriz. La regla R-3 está forzada por ArchUnit en los dos módulos (`customer/src/test/.../EndpointsDeclareAccessTest.java:25-30`, `article/.../EndpointsDeclareAccessTest.java:25-30`), así que un endpoint nuevo sin declaración rompe el build. Es el control más sólido de todo el bloque de seguridad.

### 1.2 Modo abierto (`APP_SECURITY_ENABLED=false`)

`ApiSecurityConfig.java:70-82`: con la propiedad a `false` se activa una cadena alternativa que hace `permitAll()` y, además, **concede al usuario anónimo `ROLE_SAP_SUPERADMIN` y `ROLE_SAP_EXTERNAL_READ`** (`:79`). No es "sin roles": es el rol máximo. Cualquiera que alcance el puerto tiene escritura facturable en S/4 y lectura de PII sin enmascarar.

¿Qué lo impide en producción?
- El default de la propiedad es `true` (`common/src/main/resources/application-common.yml:15`) y, con la seguridad activa, **sin `KEYCLOAK_ISSUER_URI` la app no arranca** (`ApiSecurityConfig.java:50-54`). Eso cierra el caso "me olvidé de configurar Keycloak".
- El overlay de producción **no** fija `APP_SECURITY_ENABLED`: lo fija la base, `deploy/k8s/base/common.yaml:18` (`APP_SECURITY_ENABLED: "true"`), y los overlays hacen `behavior: merge` sin tocarla (`overlays/prod/kustomization.yaml:18-26`). Efectivamente queda a `true` en prod y test.
- **Lo que no hay:** ninguna comprobación en código que impida `false` en un entorno no local. Basta una variable de entorno en el Deployment, o un `configMapGenerator` con esa clave, para abrir todo sin que nada avise más allá de un `WARN` (`:73`). No existe *fail-closed* por perfil ni por hostname.

### 1.3 Conversor de Keycloak, issuer y audiencia

`KeycloakRoleConverter.java:33-38`: lee **ambos**, `realm_access.roles` y `resource_access.<client-id>.roles`, con `client-id` de `app.security.keycloak.client-id` (default `sap-integration`, `application-common.yml:19`). Normaliza a `ROLE_<MAYÚSCULAS_CON_GUION_BAJO>` (`:52`).

Validación: **solo issuer**. `spring.security.oauth2.resourceserver.jwt.issuer-uri` (`application-common.yml:125`) da firma + `iss` + expiración. No hay ningún `OAuth2TokenValidator` ni validación de `aud` en todo el repo (grep de `audience|JwtValidators|OAuth2TokenValidator` en `*.java`: 0 resultados). Consecuencia: **cualquier token del mismo realm sirve**, aunque se haya emitido para otra aplicación, si su portador tiene el rol de realm `sap-read`/`sap-write`. En un realm corporativo compartido eso amplía la superficie mucho más de lo que sugiere ADR-0007. Tampoco se filtran los roles: se convierte todo lo que venga (incluidos `default-roles-*`, `offline_access`), lo que es inocuo hoy pero convierte el nombre de un rol ajeno en una autorización si alguien crea un `sap-admin` en otro contexto.

Jerarquía: `ApiRoles.java:30-36` — superadmin ⊃ admin ⊃ write ⊃ read; `external-read` fuera de la cadena. Coincide con el spec R-2.

### 1.4 Actuator

Expuestos: `health,info,prometheus,metrics` (`application-common.yml:89`, repetido en los dos `application.yml`). Públicos sin token: `health`, `health/**`, `info`, `prometheus`. `metrics` exige ADMIN.
- Detalle de health: `customer/src/main/resources/application.yml:47` `show-details: when-authorized` → sin token no se ven componentes. **`article/src/main/resources/application.yml` no declara `show-details`**, por lo que aplica el default de Boot (`never`): más restrictivo, no es un hallazgo, pero es una asimetría no documentada.
- `/actuator/prometheus` público devuelve nombres de host de destino SAP en el tag `destination`, versiones de JVM y el inventario de métricas. No contiene PII (ver §5). Sin `NetworkPolicy` (§3) cualquier pod del clúster lo lee.

### 1.5 Enmascarado de PII

`customer/.../web/PiiMasker.java:17-26` enmascara:

| Campo | Trato | Línea |
|---|---|---|
| `fiscal.taxId` (NIF) | últimos 4 | `:20` |
| `fiscal.vatNumber` | últimos 4 | `:20` |
| `contact.email` | inicial + dominio | `:22`, `:34-38` |
| `contact.phone`, `contact.fax` | últimos 3 | `:22` |
| `banking.iban` | últimos 4 | `:24` |
| `banking.mandateIds` | últimos 2 | `:24` |

**NO enmascara** (se devuelven tal cual, decisión declarada en el javadoc `:11-13` como "dominio B2B"): `id`, `code`, `name` (razón social), `status`, `address` **completa** (`AddressData`), `fiscal.legalName`, `fiscal.taxResidency`, `contact.website`, `banking.bic`. Contrastado con los campos de `Customer.java:25-34` y `CustomerHistoryDoc.java:20-51`: la cobertura es exactamente esa. En un cliente autónomo o empresario individual, `name` + `address` **son** datos personales; la decisión B2B es razonable pero es una decisión de negocio, no un hecho técnico, y no está firmada por nadie de legal.

Dónde se aplica:
- `GET /customers/{id}/history?full=true`: sí, `CustomerHistoryController.java:62`, con bandera `masked` en la respuesta (`:70`).
- `GET /customers/{id}/history/diff`: **no se aplica**, pero no hace falta: el endpoint exige `SAP_READ` (`:74`), que ya implica lectura completa.
- `GET /customers/{id}/state`: **no aplica ni le hace falta**. `CustomerStateUseCase.EntityState` solo lleva `entityId`, estado, `payloadHash` e instante (`CustomerStateUseCase.java:30,33`). Sin PII directa; el `payloadHash` es un seudónimo.
- Artículos: sin PII por definición, coherente con que su `diff` acepte `external-read`.

**Fallo abierto:** `AccessScope.java:27-30` — si `Authentication` es `null`, devuelve `true` (ve PII). Es deliberado (tests standalone y modo abierto), pero es un *fail-open*: cualquier ruta futura que llegue al controller sin contexto de seguridad devolverá la PII sin enmascarar. Lo correcto sería `false` por defecto y que el modo abierto conceda el rol, que ya lo hace (`ApiSecurityConfig.java:79`).

---

## 2. Secretos

| Secreto | Local | CI | Kubernetes |
|---|---|---|---|
| SAP S/4 y BTP (`SAP_*_CLIENT_ID/SECRET/TOKEN_URL`, `SAP_S4_PASSWORD`) | no hay: `SAP_AUTH_ALLOW_STUB=true` contra WireMock (`scripts/env/local.env:46`) | no se usan (no hay job que llame a SAP) | `Secret` externo `sap-integration-secrets`, referenciado por `secretRef` (`base/customer-app.yaml:41-43`, `base/article-app.yaml:41-43`) |
| Keycloak (issuer, client-id) | seguridad desactivada (`local.env:51`) | — | ConfigMap, no secreto: `base/common.yaml:18-19`, issuer en cada overlay (`prod/kustomization.yaml:22`) |
| BD legacy (SQL Server / PostgreSQL) | `scripts/env/local.env:21-26` — **fichero trackeado en git** | — | `Secret` externo `customer-secrets` / `article-secrets` (`customer-app.yaml:43-44`) |
| Mongo, Elasticsearch, Kafka | sin credenciales: URLs planas (`local.env:30-35`) | — | **sin credenciales**: `mongodb://mongodb.data.svc:27017/customer` (`customer-app.yaml:97`), `http://elasticsearch.data.svc:9200` y `kafka.messaging.svc:9092` (`common.yaml:15-16`) |
| Registro de imágenes (CI) | — | `secrets.GITHUB_TOKEN` (`.github/workflows/ci.yml:77,86`) | `imagePullSecrets`: **no declarado** |

Mecanismo de `Secret` en `deploy/k8s`: **ninguno en el repo**. No hay objeto `Secret` (ni literal, ni `stringData`, ni `secretGenerator`, ni placeholder): los Deployments solo los *referencian* por nombre y la decisión de cómo llegan (sealed-secrets / ESO / Vault) está explícitamente pendiente (`deploy/README.md:39-41`, `TODO.md:27`, OPS-7). Correcto como postura (nada sensible en el repo) e incompleto como entrega: hoy `kubectl apply -k` no levanta nada.

`LegacyCredentialsGuard.java`: se ejecuta como `EnvironmentPostProcessor` antes de crear beans (`:24-29`) y **exige exactamente dos propiedades**, `spring.datasource.username` y `spring.datasource.password`, fallando si están vacías o contienen un `${...}` sin resolver (`:50-60`). **No comprueba** Mongo, Elasticsearch, Kafka, Keycloak ni SAP; las de SAP las cubre `BtpAuthProvider.java:45-49` (y su homólogo nativo) con `sap.auth.allow-stub`. Si la app no tiene datasource JPA, el guard se salta entero (`:25-27`).

`scripts/env/test.env` **existe en disco** y está correctamente ignorado (`git check-ignore`: `.gitignore:37`). No lo he abierto. `scripts/env/local.env` **sí está trackeado** por la excepción `.gitignore:38` (`!scripts/env/local.env`) y contiene usuario y contraseña de las BD locales, que coinciden con las del compose (`external-services/docker-compose.yml:105,136,256,280`). Son contenedores desechables, pero el patrón "un fichero de credenciales versionado con una excepción explícita" es el que suele acabar llevando una credencial real.

### Estado de B5 / SEC-4 (credencial del spike)

Confirmado, sin abrir el fichero:
- `sap-sdk-client/` **sigue en disco** (`ls -d` positivo en la raíz del repo).
- **Sigue ignorado**: `git check-ignore -v` → `.gitignore:49`, con comentario explícito en `.gitignore:47-48` diciendo que contiene una credencial real de tenant S/4 en su historial.
- **No rotada**: `docs/auditorias/2026-09-12-cierre-bloqueantes.md:14` — «`sap-sdk-client/` en `.gitignore`, sin remoto; **no rotada**. Riesgo residual documentado»; `docs/auditorias/2026-09-10-plan-de-accion.md:36` — «B5 se mitiga con `.gitignore`, sin rotar»; `docs/MEJORAS-Y-PROPUESTAS.md:64` (SEC-4) — «la credencial hay que rotarla igualmente», estado 📋 (pendiente).

**Riesgo residual:** una credencial de *communication user* de un tenant S/4 sigue siendo válida y vive en el historial de un repositorio git anidado en la máquina de desarrollo. La mitigación por `.gitignore` protege este repositorio, no la credencial: cubre el vector "se sube a GitHub" y no cubre robo o copia del equipo, backup, ni un `git push` desde dentro del repo anidado. El `.gitignore` afirma que no tiene remoto; no lo he verificado (no ejecuté git dentro de ese directorio).

---

## 3. Despliegue en Kubernetes

Solo existen tres manifiestos base y dos overlays. No hay más ficheros bajo `deploy/`.

| Control | `customer-app.yaml` | `article-app.yaml` | Evidencia |
|---|---|---|---|
| `resources.requests` (cpu+mem) | ✅ | ✅ | `:50-53` / `:50-53` |
| `resources.limits` memoria | ✅ 1Gi | ✅ 1Gi | `:54-55` |
| `resources.limits` cpu | ❌ (ausente) | ❌ | — (omisión razonable en Java, pero no documentada) |
| `startupProbe` | ✅ 3 min | ✅ | `:56-61` |
| `livenessProbe` | ✅ `/actuator/health/liveness` | ✅ | `:62-66` |
| `readinessProbe` | ✅ `/actuator/health/readiness` | ✅ | `:67-71` |
| Sondas coherentes con `show-details` | ✅ (grupos `liveness`/`readiness` son `permitAll`, `ApiSecurityConfig.java:62`) | ✅ | — |
| `securityContext.runAsNonRoot` | ✅ pod | ✅ | `:24-27` |
| `seccompProfile: RuntimeDefault` | ✅ | ✅ | `:26-27` |
| `allowPrivilegeEscalation: false` | ✅ | ✅ | `:73` |
| `capabilities.drop: ALL` | ✅ | ✅ | `:75-76` |
| `readOnlyRootFilesystem` | ❌ `false` explícito | ❌ | `:74` (justificado: buildpacks escriben en /tmp; se resuelve con un `emptyDir` en /tmp) |
| `runAsUser`/`fsGroup` explícitos | ❌ | ❌ | — |
| ServiceAccount dedicado | ⚠️ uno compartido por las dos apps, `sap-integration` | ⚠️ | `common.yaml:1-4`, `:23` |
| `automountServiceAccountToken: false` | ❌ | ❌ | — |
| NetworkPolicy | ❌ no existe | ❌ | grep en `deploy/`: 0 |
| PodDisruptionBudget | ❌ | ❌ | grep: 0 |
| HPA | ❌ (réplicas fijas: 2 prod, 1 test) | ❌ | `overlays/prod/kustomization.yaml:13-17` |
| Ingress con TLS | ❌ **no existe ningún Ingress** | ❌ | solo prosa en `deploy/README.md:59-60` y `TODO.md:29` |
| Imagen por digest | ❌ tag mutable (`0.1.0`, `0.1.0-SNAPSHOT`) | ❌ | `overlays/prod/kustomization.yaml:9`, `test:9,12` |
| `imagePullPolicy` | ⚠️ `IfNotPresent` con tag mutable | ⚠️ | `:32` |
| `imagePullSecrets` | ❌ | ❌ | — |
| Exposición de `/actuator` | ✅ no se expone (no hay Ingress que lo exponga) | ✅ | — |
| `terminationGracePeriodSeconds` > shutdown | ✅ 45s > 30s | ✅ | `:28` |
| Anotaciones Prometheus | ✅ | ✅ | `:18-21` |

Dos observaciones de método: `base/kustomization.yaml:7` usa `commonLabels`, deprecado en Kustomize a favor de `labels`; y el registro sigue con el placeholder `ghcr.io/<organizacion>` en los dos overlays, que no es un valor aplicable (`TODO.md:26`).

**Cifrado en tránsito dentro del clúster: ninguno.** `ES_URL` es `http://` (`common.yaml:16`), Kafka es texto plano (`:15`), Mongo es `mongodb://` sin `tls=true` ni credenciales (`customer-app.yaml:97`, `article-app.yaml:97`). Solo SQL Server lleva `encrypt=true` (`customer-app.yaml:98`); la URL de PostgreSQL no lleva `sslmode` (`article-app.yaml:98`). Con PII en esos tres almacenes y sin NetworkPolicy, cualquier pod del clúster puede leer el tráfico o conectarse directamente a Mongo y Elasticsearch.

---

## 4. PII y RGPD

| Almacén | Contenido personal | Retención / TTL | Cifrado en tránsito | Borrado RGPD |
|---|---|---|---|---|
| Mongo `customers_current` (imagen) | NIF, IBAN, email, teléfono, dirección | ❌ ninguna; `init.js:5-20` no crea índice TTL | ❌ compose sin auth ni TLS (`docker-compose.yml:178-195`); k8s `mongodb://` plano | ❌ la baja marca `status=BLOCKED`, **no borra** (`RUNBOOKS.md:121-124`) |
| Mongo `sync_state` | `payloadHash` + `entityId` (seudónimos) | ❌ ninguna; índices en `init.js:15-20`, ninguno TTL | ❌ | ❌ |
| Elasticsearch `customers_history` | **snapshot completo por intento**: fiscal, contacto, bancario, dirección | ❌ sin ILM ni plantilla; `@Document(createIndex=false)` (`CustomerHistoryDoc.java:17`) delega en operaciones, que no existe | ❌ `xpack.security.enabled=false` en local (`docker-compose.yml:210`); `http://` en k8s | ❌ ningún camino lo implementa |
| Topics `outbox.CUSTOMER` / `-dlt` | payload del legacy (con PII) | ❌ sin configuración de retención en el repo; `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` (`docker-compose.yml:51`) → retención por default del broker | ❌ PLAINTEXT (`:45-47`) | ❌ |
| Topic `sap.sync.alerts` | ver abajo | ❌ | ❌ | ❌ |
| Logs (stdout → Loki/ELK) | ver abajo | plataforma, fuera del repo | — | — |

Este es el punto más débil del sistema desde el RGPD: **crece sin límite y no sabe olvidar**. El histórico de Elasticsearch es especialmente severo porque guarda **un documento por intento** (`CustomerHistoryDoc.java:55-57`), no por versión: un cliente que reintente diez veces deja diez copias de su IBAN y su NIF. El propio proyecto lo reconoce como decisión de negocio pendiente (`APTITUD-PRODUCCION.md:27`, `TODO.md:42`, RGPD art. 5.1.e), pero no hay ni siquiera una medida técnica provisional (un TTL conservador) mientras llega.

**¿La alerta y el WARN llevan PII?** No directamente. `KafkaSyncNotificationAdapter.java:48-49` (log) y `:54-63` (evento JSON) publican `domain`, `entityId`, `payloadHash`, listas de *features* OK/fallidas e instante. `entityId` es el identificador del cliente en el legacy: seudónimo, no dato personal en sí, pero **reidentificable** por cualquiera con acceso al legacy o a la API. Es tratamiento de datos personales en sentido RGPD, con una retención de topic no definida y sin consumidor (OPS-8, `TODO.md:36`). El enmascarado de PII en logs está declarado fuera de alcance (SEC-3, `seguridad-api.md:28`, `TODO.md:52`), lo que significa que un `Error SAP POST` con cuerpo de respuesta puede acabar volcando datos: no lo he verificado línea a línea en `RestClientSapClient`.

---

## 5. Observabilidad real

Métricas registradas **en código**:

| Métrica | Tipo | Tags | Fichero:línea |
|---|---|---|---|
| `sap_sync_state_total` | Counter | `domain`, `state` | `SyncMetrics.java:35-38` |
| `sap_sync_stage_duration` | Timer (p50/p95/p99) | `domain`, `stage` | `SyncMetrics.java:49-53` |
| `sap_sync_feature_result_total` | Counter | `domain`, `feature`, `result` | `SyncMetrics.java:61-65` |
| `sap_client_request_duration` | Timer | `destination`, `method`, `outcome` | `RestClientSapClient.java:211` |
| `resilience4j_retry_*`, `resilience4j_circuitbreaker_*` | binders | los de Resilience4j | `SapIntegrationConfig.java:113-117` |
| tag global `application` | — | `${spring.application.name}` | `application-common.yml:97` |

**Coincide 1:1 con `docs/sdd/common/observabilidad.md` R-1, R-2, R-2b, R-3 y R-6.** El spec no promete nada que el código no registre: es el documento más fiel del repositorio. Ningún contador de PII ni de `entityId` en los tags, correcto.

- **Alertas:** **ninguna definida en el repositorio.** No hay reglas de Prometheus, ni `PrometheusRule`, ni ficheros de Grafana. El propio proyecto lo admite (`observabilidad.md:31`, `APTITUD-PRODUCCION.md:37`, OBS-3/OPS-8). Hoy las métricas existen y nadie las mira: un `sap_sync_state_total{state="SAP_ERROR"}` disparado no despierta a nadie.
- **Trazas sin cambio de código:** sí. `management.tracing.enabled` / `management.otlp.tracing.*` (`application-common.yml:101-109`) se activan con `TRACING_ENABLED=true` y un endpoint OTLP. Hoy están a `false` también en el ConfigMap de despliegue (`base/common.yaml:30`). Falta el destino (Tempo/colector), `TODO.md:35`.
- **MDC / traceId en logs:** **no hay ninguna referencia a `MDC` ni a `traceId` en todo el código Java** (grep: 0 resultados). Con `TRACING_ENABLED=false`, los logs ECS (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, `common.yaml:27`) **no llevan identificador de correlación**: no se puede seguir un `entityId` entre customer-app y article-app, ni entre pods de la misma app. La correlación depende por completo de activar OTel.
- **`RetryBudgetGuard`:** **rompe el arranque**, no avisa. `@PostConstruct` que lanza `IllegalStateException` si el peor caso alcanza `max.poll.interval.ms` (`RetryBudgetGuard.java:42-51`), con el cálculo en el mensaje; si cuadra, deja un `INFO` (`:52-53`). Es el tipo de control que quiero ver: falla pronto, ruidoso y explicado.

---

## 6. Operabilidad: runbook por runbook

| Runbook | Herramienta que manda usar | ¿Existe? |
|---|---|---|
| 1 — Entidad atascada o `SAP_ERROR` | `GET /customers/{id}/state` con token `sap-read`; `sap_sync_state_total`; `mongosh`; reinyección por `POST /customers/sync` con hash nuevo | ✅ todo existe (`CustomerStateController.java:28-31`, `SyncCustomerController.java:35`) |
| 2 — Mensaje en la DLT | `kafka-console-consumer` sobre `outbox.*-dlt` | ⚠️ inspección sí; **reproceso no existe** y el propio runbook lo dice (`RUNBOOKS.md:69-70`, OPS-2, `TODO.md:38`). El camino real es provocar un evento nuevo |
| 3 — SAP caído | `resilience4j_circuitbreaker_state`, `sap_client_request_duration{outcome}` | ✅ métricas registradas (`SapIntegrationConfig.java:113-117`, `RestClientSapClient.java:211`) — pero sin alerta que las vigile |
| 4 — La app no arranca | mensajes de arranque de los tres guards | ✅ los tres existen y el texto del runbook coincide con el del código (`LegacyCredentialsGuard.java:54-58`, `BtpAuthProvider.java:46-49`, `RetryBudgetGuard.java:46-50`) |
| 5 — Rebalanceos de Kafka | log de arranque con el presupuesto; `KAFKA_MAX_POLL_INTERVAL_MS` | ✅ (`RetryBudgetGuard.java:52`, `common.yaml:28`) |
| 6 — Baja de cliente / mandato | CDC `DELETE` → `status=BLOCKED`; `PATCH SEPAMandateStatus=3`, requiere `SAP_SEPA_CREDITOR_ID` | ⚠️ el camino de cliente existe; el de mandato **no tiene disparador**: el propio runbook admite que «hoy no hay evento del legacy que lo dispare» (`RUNBOOKS.md:128`) y `SAP_SEPA_CREDITOR_ID` está vacío por defecto (`application-common.yml:46`) |

Los runbooks son honestos: donde no hay herramienta, lo dicen. Eso vale más que un runbook completo y falso. Pero dos de seis dependen de algo que no existe, y el runbook 6 es justamente el camino que el RGPD convertiría en obligatorio.

---

## 7. Riesgos priorizados

Criterio: `skills/agent-routing/SKILL.md` §1 — bloqueante = funcionalidad crítica (pagos, autenticación, venta) o dependencia global; atención = degradación local que acabará comprometiendo; observación = deuda a vigilar.

### Bloqueantes (3)

| # | Riesgo | Evidencia |
|---|---|---|
| B-1 | **Credencial real de tenant S/4 sin rotar** desde el 2026-09-10. La mitigación aplicada (`.gitignore`) protege el repositorio, no la credencial; un *communication user* de S/4 es autenticación contra un sistema de facturación | `.gitignore:47-49` · `docs/auditorias/2026-09-12-cierre-bloqueantes.md:14` · `docs/MEJORAS-Y-PROPUESTAS.md:64` |
| B-2 | **PII (IBAN, NIF, email, teléfono) sin cifrado en tránsito ni autenticación en los tres almacenes**, y sin NetworkPolicy que limite quién habla con ellos dentro del clúster | `deploy/k8s/base/common.yaml:15-16` · `customer-app.yaml:97` · `docker-compose.yml:210` · sin NetworkPolicy en `deploy/` |
| B-3 | **Sin plazo de retención ni camino de borrado en ningún almacén con PII**, con el histórico creciendo un documento por *intento*. Incumple RGPD art. 5.1.e antes de tratar el primer dato real | `CustomerHistoryDoc.java:17,55-57` · `external-services/mongodb/init.js:5-37` (sin TTL) · `RUNBOOKS.md:121-124` · `APTITUD-PRODUCCION.md:27` |

### Atención (7)

| # | Riesgo | Evidencia |
|---|---|---|
| A-1 | Sin validación de `aud`: cualquier token del realm con rol `sap-*` entra | `ApiSecurityConfig.java:48-65` (solo `issuer-uri`); grep de `audience`: 0 |
| A-2 | Nada impide técnicamente `APP_SECURITY_ENABLED=false` fuera de local; el anónimo recibe superadmin | `ApiSecurityConfig.java:71-80` |
| A-3 | `AccessScope` falla abierto: sin autenticación devuelve PII sin enmascarar | `AccessScope.java:27-30` |
| A-4 | No hay Ingress ni TLS en los manifiestos; todo el plano norte-sur es prosa | `deploy/README.md:59-60` · `TODO.md:29` |
| A-5 | Cero alertas definidas: métricas correctas que nadie vigila | `observabilidad.md:31` · `APTITUD-PRODUCCION.md:37` |
| A-6 | Sin `traceId`/MDC en los logs mientras el trazado esté apagado: no hay correlación entre servicios | grep `MDC|traceId`: 0 · `base/common.yaml:30` |
| A-7 | Sin reproceso desde la DLT (OPS-2) y sin PDB/HPA: la operación degradada es manual | `RUNBOOKS.md:69-70` · sin PDB/HPA en `deploy/` |

### Observación (6)

| # | Riesgo | Evidencia |
|---|---|---|
| O-1 | `scripts/env/local.env` versionado por excepción explícita, con credenciales (locales) dentro | `.gitignore:37-38` · `scripts/env/local.env:21-26` |
| O-2 | Imagen por tag mutable + `imagePullPolicy: IfNotPresent`: dos pods pueden correr binarios distintos | `overlays/test/kustomization.yaml:9,12` · `customer-app.yaml:32` |
| O-3 | `readOnlyRootFilesystem: false`; se resuelve con un `emptyDir` en `/tmp` | `customer-app.yaml:74` |
| O-4 | ServiceAccount compartido por ambas apps y token automontado | `common.yaml:1-4` · `customer-app.yaml:23` |
| O-5 | `name` y `address` fuera del enmascarado: decisión "B2B" sin firma de legal | `PiiMasker.java:11-13,25` |
| O-6 | `alerts` y logs con `entityId` reidentificable, topic sin retención ni consumidor | `KafkaSyncNotificationAdapter.java:48,54-63` · `TODO.md:36` |

---

## 8. Lo que NO verifiqué

- **No ejecuté nada**: ni `mvn`, ni `kubectl`, ni `docker`, ni los tests. Todo lo anterior es lectura estática de `ffd86e7`. Que `ApiSecurityTest` exista no prueba que pase.
- **No abrí `scripts/env/test.env`** (gitignored, puede tener credenciales reales): solo confirmé que existe y está ignorado.
- **No entré en `sap-sdk-client/`**: no comprobé si tiene remoto configurado ni el estado de su historial. La afirmación "sin remoto" viene de `docs/auditorias/2026-09-12-cierre-bloqueantes.md:14`, no de una verificación mía.
- **No revisé el cuerpo de `RestClientSapClient` línea a línea** para determinar si algún log vuelca payloads de SAP con PII; solo la métrica. Es el hueco más relevante que dejo abierto para SEC-3.
- **No hay clúster ni Keycloak accesibles** (supuesto del orquestador): no pude comprobar el comportamiento real con un token, ni si el realm corporativo emite `aud` utilizable.
- **No audité** `supplier/`, `it/`, los adaptadores OData ni la máquina de estados: fuera del alcance de este handoff.
- **No verifiqué la retención real** de los topics Kafka en ningún broker: solo constaté que el repositorio no la configura.
