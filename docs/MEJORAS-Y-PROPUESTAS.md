# Mejoras y propuestas

> **Backlog vivo.** Aquí se apunta todo lo que merece la pena hacer pero **no**
> se está haciendo ahora: mejoras del producto, ideas transversales a otros proyectos
> y funcionalidad extra. Sobrevive a cada feature: nada se borra por no haberse
> abordado, solo cambia de estado.
>
> Qué **no** va aquí: los **defectos** del código, que van al changelog de
> [`sdd/README.md`](sdd/README.md) §6 como brechas abiertas. Esto son mejoras
> sobre algo que ya funciona, o cosas que aún no existen.

## Cómo usar este fichero

- **Ámbito** — `proyecto`: solo esta aplicación · `transversal`: aplicable a otros
  proyectos del equipo · `extra`: funcionalidad nueva no comprometida.
- **Estado** — 💡 idea · 📋 acordada (se hará, sin fecha) · 🚧 en curso ·
  ✅ hecha (se deja con la fecha, para tener memoria) · ❌ descartada (con el porqué).
- Al abordar una, se convierte en un spec en [`sdd/`](sdd/README.md) y se marca
  aquí 🚧 con el enlace.

---

## Observabilidad

| # | Mejora | Ámbito | Estado | Notas |
|---|---|---|---|---|
| OBS-1 | **Stack Prometheus + Grafana** en `external-services` | proyecto | 🚧 parcial | Paneles y reglas versionados en `deploy/observability/`; operación del stack de plataforma pendiente por entorno (R-9) |
| OBS-2 | **Trazas distribuidas**: Tempo o colector OTLP y `TRACING_ENABLED=true` | proyecto | 🚧 parcial | Starter OTel listo, apagado; colector OTLP agnóstico (R-9) |
| OBS-3 | Dashboard de estado del pipeline: entidades por estado, tasa de `SAP_ERROR`, profundidad de la DLT | proyecto | 💡 | Depende de OBS-1 |
| OBS-4 | Logs estructurados en JSON con `traceId` correlado | transversal | 🚧 parcial 2026-09-12 | El formato ECS (JSON) ya se activa con `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` (nativo de Boot, sin código). El `traceId` llegará con las trazas (OBS-2, decisión D-7) |

## Calidad y pruebas

| # | Mejora | Ámbito | Estado | Notas |
|---|---|---|---|---|
| TEST-1 | **Test de integración que arranque la app contra Testcontainers reales** (Mongo, ES, Kafka, SQL Server) | transversal | 🚧 parcial 2026-09-12 | **La lección más cara de este proyecto**: seis defectos llegaron a producción local porque los tests unitarios mockean los puertos. Ni la configuración real ni la máquina de estados se ejercían. Un solo IT de humo los habría cazado todos. Fase 2: `SyncStateMongoIT` ya ejercita el repositorio de estado contra Mongo 7 real (re-sync tras `SAP_ERROR`, escritores concurrentes, docs legacy sin `seq`). Falta arrancar la app completa. **Segundo aviso (2026-09-12)**: la primera escritura en ES rompía por una dependencia (`opentelemetry-semconv`) y solo se vio en vivo |
| TEST-2 | Patrón «test de configuración»: afirmar que las propiedades resuelven a lo esperado | transversal | ✅ 2026-09-09 | `CustomerMongoDatabaseConfigTest`. Nació de que Boot 4 ignoraba en silencio `spring.data.mongodb.uri`. **Replicable en cualquier proyecto Spring**: por cada propiedad crítica, un test que compruebe el valor efectivo, no el fichero |
| TEST-3 | Repositorios en memoria con las reglas reales en vez de mocks para los puertos con invariantes | transversal | ✅ 2026-09-09 | `InMemoryStateRepo` en `SyncAddressUseCaseTest`. Mockear un puerto que valida invariantes esconde justo los fallos que importan |
| TEST-4 | Smoke E2E en CI: levantar el compose, lanzar un sync y comprobar `SENT_SAP` | proyecto | 📋 | Sería el guardián de las regresiones que hemos ido encontrando a mano. Las tres verificaciones en vivo de la Fase 1 (entidad atascada, `SAP_ERROR` → re-sync con SAP en 500, baja por CDC con `DELETE` y `BLOCKED`) son exactamente el guion a automatizar |
| TEST-5 | Contract tests contra el tenant SAP de test, no solo contra WireMock | proyecto | 📋 | WireMock devuelve 201 a todo: valida nuestro lado, no el contrato |
| TEST-6 | Cobertura JaCoCo con umbral que rompa el build en `domain` y `common` | proyecto | ✅ 2026-09-12 | `check` en el parent sobre `**/domain/**`, ≥ 75 % de líneas (suelo medido: common 90 %, customer 78 %, article 88 %). El umbral sube conforme suba la cobertura, nunca al revés |

## Resiliencia y operación

| # | Mejora | Ámbito | Estado | Notas |
|---|---|---|---|---|
| OPS-1 | **Recuperación de entidades atascadas** en estados intermedios | proyecto | ✅ 2026-09-11 | Si el proceso muere entre `FETCHING`/`INDEXING`/`SENDING_SAP`, la entidad queda bloqueada: esos estados no tienen transición de salida hacia `RECEIVED`. Verificado en vivo el 2026-09-09 con `CUST-001`. Resuelto de raíz en la Fase 1 de la auditoría: `beginCycle` abre ciclo desde **cualquier** estado, en vuelo incluido, y lo registra en log. No hace falta lease ni job. UI-4 ya tiene qué invocar |
| OPS-2 | Endpoint o comando de **reproceso desde la DLT** | proyecto | 💡 | Hoy los mensajes de `outbox.<DOM>-dlt` se inspeccionan a mano y no hay forma de reinyectarlos. Lo consumiría UI-4 |
| OPS-3 | Decidir el **sufijo del topic DLT** | proyecto | ✅ 2026-09-11 | Decidido: se mantiene el sufijo por defecto de Spring Kafka, **`<topic>-dlt`**, y se corrige la documentación (23 ocurrencias en 13 ficheros). Motivo: fabricar un sufijo propio obliga a configurar el recoverer en cada dominio para no ganar nada; el topic real ya existía con ese nombre y tenía mensajes |
| OPS-4 | Mapeo fino de errores SAP (código, mensaje, campo) en vez de propagar el HTTP crudo | proyecto | 📋 | Ya listado como brecha; diagnóstico muy pobre cuando SAP rechaza algo |
| OPS-5 | Saga / compensación entre features | proyecto | ❌ descartada 2026-09-14 | [ADR-0010](architecture/adr/0010-sin-compensacion-entre-features-marcar-y-avisar.md): no se compensa; se marca error, se conserva el estado por parte y se avisa (`sap.sync.alerts`). Queda el **consumidor** de esas alertas: OPS-8 |
| OPS-8 | Consumidor de `sap.sync.alerts` (correo / ticket / panel) y regla de alerta en Grafana sobre el `WARN` «ALERTA sincronizacion parcial» | proyecto | 📋 | Hoy la alerta se emite; nadie la escucha todavía |
| OPS-7 | **Gestión de secretos en Kubernetes** (sealed-secrets, External Secrets Operator o Vault) | proyecto | 📋 | D-9 decidida (Kubernetes, ADR-0008, `deploy/k8s`). Queda cómo llegan los `Secret` al clúster (D-9 resuelta: contenedores propios, BTP Cloud Foundry, Kubernetes...). `spring-boot:build-image` ya genera una imagen OCI con buildpacks; los perfiles Spring `local/test/prod` que pedía la auditoría **no** se adoptan: la configuración va por variables de entorno (`scripts/env/*.env`) |
| OPS-6 | Reevaluar el **VDM del SAP Cloud SDK** como transporte hacia S/4 | proyecto | 📋 | Decidido en [ADR-0001](architecture/adr/0001-transporte-http-sap-restclient.md): hoy `RestClient`. Disparador: soporte oficial de Boot 4 por el SDK, o que reimplementar OData V2 (ETag, deep insert, `$batch`) en la Fase 3 cueste más que adoptar el VDM |
| OPS-9 | **Guard de arranque para la familia BTP**: que la app no arranque con un adaptador `Btp*Adapter` activo (`sap.odata.<feature>.enabled=false`, valor por defecto) si `sap.btp.base-url` no está configurada o apunta a `localhost`/vacío | proyecto | 💡 | Riesgo abierto **2B-4** (auditoría 2026-09-18): la familia BTP se mantiene (D-10, [ADR-0004](architecture/adr/0004-dos-familias-de-adaptadores-btp-y-odata.md) §5) porque el servicio del propietario existe y se probó aislado, pero el `ConfigMap` por defecto de `deploy/k8s/base/common.yaml` puede desplegar la app escribiendo contra un destino que nadie ha integrado todavía. Mismo patrón que el guard ya existente para credenciales SAP (`sap.auth.allow-stub`, auditoría A8): fallar pronto y explicado en vez de fallar en caliente en el primer envío |
| OPS-10 | **Broker de mensajería: sustitución Kafka+ZooKeeper por Redpanda** | proyecto | ✅ 2026-09-23 | Redpanda v25.3.9 LTS, Operator + CRD `cluster.redpanda.com/v1alpha2` `kind: Redpanda`, manifests Kustomize puros (no Helm+FluxCD), un cluster por clúster K8s, RF=3 test/prod / RF=1 local. Cero JVM extra por cluster (la del ZooKeeper y la del broker Kafka en Java). La app cliente no se toca (wire Kafka 3.x). [ADR-0014](../architecture/adr/0014-redpanda-como-broker-de-mensajeria.md), [ADR-0011](../architecture/adr/0011-concurrencia-entre-instancias-fencing-sin-lease.md) revisado (consumer group por cluster K8s), spec [`sdd/common/broker-de-mensajeria.md`](../sdd/common/broker-de-mensajeria.md), manifests `deploy/k8s/base/redpanda.yaml` + `kafka-connect.yaml`. 5 commits (H-0..H-5). D-16 cerrado |
| OPS-11 | **Tiered Storage de Redpanda contra S3/MinIO** | proyecto | 💡 | Capacidades OPS-010 incluyen dejarlo listo pero desactivado (`tieredStorage.disabled: true` en el CRD). Activarlo exige plan de capacidad (retención por política de histórico, coste por egress a S3/MinIO, latencia en recuperación de segments). Disparador: que el `P99` de histórico se mantenga en `O(100 ms)` y necesitemos más de 5 días de retención o que el pod del broker avise de presión de disco en menos de una semana |

## Seguridad

| # | Mejora | Ámbito | Estado | Notas |
|---|---|---|---|---|
| SEC-1 | **Autenticación en las APIs REST** de `customer` y `article` | proyecto | ✅ 2026-09-12 | Keycloak (resource server JWT), roles `sap-read/write/admin/superadmin/external-read`, `@PreAuthorize` por endpoint ([`sdd/common/seguridad-api.md`](sdd/common/seguridad-api.md)) |
| SEC-2 | Rotación y gestión de secretos SAP (Vault o equivalente) | transversal | 💡 | Hoy van por variables de entorno; suficiente en local, no en test/producción |
| SEC-3 | Ofuscación de PII en logs (en respuestas a `external-read` ya se enmascara) | proyecto | 🚧 parcial 2026-09-12 | Prerrequisito del MCP ([`tools-integrations/MCP.md`](tools-integrations/MCP.md)) |
| SEC-5 | Sin fallback silencioso a token stub y sin secretos en los YAML empaquetados (auditoría A8) | proyecto | ✅ 2026-09-12 | `sap.auth.allow-stub` (default `false`) hace que la app no arranque sin credenciales SAP; usuario/clave de BD legacy y `trustServerCertificate` salen del jar y los aporta `scripts/env/*.env`. Adelantado de la Fase 4 como prerrequisito del tenant de test |
| SEC-4 | Revisar `sap-sdk-client/`: tiene URL de tenant real, usuario y contraseña en claro | proyecto | 📋 | Es un spike desechable, pero la credencial hay que rotarla igualmente |

## Utillaje y experiencia de desarrollo

| # | Mejora | Ámbito | Estado | Notas |
|---|---|---|---|---|
| DX-1 | Versión **PowerShell** de `start-all.sh` / `stop-all.sh` | transversal | 💡 | Hoy hay que usar Git Bash o WSL en Windows |
| DX-2 | Perfil opcional de compose con **UIs de inspección** (Kafka UI, mongo-express) | transversal | 💡 | Hoy se inspecciona por CLI o con clientes de escritorio |
| DX-3 | `MSYS_NO_PATHCONV=1` en los `docker exec` de las guías | proyecto | 📋 | Git Bash convierte `/opt/...` a ruta Windows y el `exec` falla — justo el shell que recomendamos |
| DX-4 | Maven wrapper (`mvnw`) en el repo | transversal | ✅ 2026-09-12 | Maven 3.9.9 fijado en `.mvn/wrapper`; la CI usa `./mvnw`. Elimina la dependencia del `mvn` del sistema y fija la versión |
| DX-5 | Hook de pre-commit que ejecute `mvn test` sobre los módulos tocados | transversal | 💡 | |
| DX-6 | Comprobador de enlaces de la documentación en CI | transversal | 💡 | Se ha usado a mano en cada reorganización de `docs/`; automatizarlo es barato y evita enlaces muertos |
| DX-8 | Terminar la deduplicación entre dominios: `*HistoryUseCase`, `*HistoryController`, `Elasticsearch*Indexer`, `Mongo*ImageStore` (auditoría A5, resto) | proyecto | 💡 | Lo que queda de las ~900 LOC duplicadas tras `FeatureSyncPipeline`/`SyncCycleRecorder`. Requiere genéricos sobre los documentos ES/Mongo por dominio: menos ganancia, más riesgo |
| TEST-7 | ArchUnit sobre `application`: sin Spring, Micrometer ni Jackson (auditoría A4) | proyecto | ✅ 2026-09-12 | `ApplicationPurityTest` en customer y article. `MetricsPort` en el dominio, `@Service` fuera de los 16 use cases, wiring en `bootstrap/*UseCaseConfig` |
| DX-7 | Generador OpenAPI de `sap-api-models` **falla de forma intermitente en Windows** («Unable to delete original source file» / «Failed to generate data model») si `target/` no está limpio | proyecto | ✅ 2026-09-12 | Causa: tres ejecuciones del generador sobre el mismo `target/generated-sources` y cada una borraba lo de la anterior (`deleteOutputDirectory` por defecto). Fijado `deleteOutputDirectory=false` en la 2.ª y 3.ª ejecución |

## Features

| # | Mejora | Ámbito | Estado | Notas |
|---|---|---|---|---|
| PRD-1 | Dominio **`supplier`** real | extra | 📋 | Hoy es un placeholder no desplegable |
| PRD-2 | `CONTACT` con el contrato real de S/4 (`A_AddressEmailAddress`, `A_AddressPhoneNumber`) | proyecto | 🚧 en curso | **Hecho**: el adaptador OData envía email, teléfono, fax y web a las cuatro entidades de comunicación de la dirección, colgadas del `AddressID` ([`sdd/customer/sincronizacion-contacto.md`](sdd/customer/sincronizacion-contacto.md)). Antes el payload iba vacío (2B-5). **Falta**: confirmar `Person`/`OrdinalNumber` y el `PATCH` parcial contra el tenant ([`testing/CHECKLIST-TENANT-SAP.md`](testing/CHECKLIST-TENANT-SAP.md) §3) |
| PRD-3 | Mandatos SEPA desde el legacy para completar `BANKING` | proyecto | 📋 | El adaptador existe pero no le llegan datos |
| PRD-4 | **Modo pull**: BTP orquesta el ciclo (`PENDING_SAP`, `/btp/pending`, `/btp/result`) | extra | 💡 | Propuesta no implementada: ni el estado ni los endpoints existen |
| PRD-5 | Batch fin de día / D+1 disparado por topic | extra | 💡 | Reutilizaría los mecanismos existentes |
| PRD-6 | Eventos entrantes desde S/4 (stock) | extra | 💡 | Pendiente de decisión del equipo SAP |
| PRD-7 | **Servidor MCP** de consulta para agentes IA | extra | 💡 | Requiere SEC-1 y SEC-3. Propuesta en [`tools-integrations/MCP.md`](tools-integrations/MCP.md) |
| PRD-8 | `S3ImageStoreAdapter` sobre MinIO | extra | 💡 | MinIO está levantado y sin uso |
| PRD-9 | **Consulta de Business Partner desde SAP** (GET, sin coste) | extra | ✅ 2026-09-24 | Implementado: [`sdd/customer/consulta-business-partner-sap.md`](sdd/customer/consulta-business-partner-sap.md). 4 endpoints GET (`/{code}`, `?category=&top=`, `/customers`, `/suppliers`), `LookupBusinessPartnerUseCase`, `BusinessPartnerController`, helper `PiiMasker.maskName`, OpenAPI al dia, 16 `@Test` nuevos. Pendiente: verificacion contra el tenant SAP de test (mismo criterio que `customer-contact` y `customer-banking`). |
| PRD-11 | **Upsert idempotente** contra S/4: lookup → alta / `PATCH` con `If-Match`, con el `AddressID` persistido (auditoría B3) | proyecto | 🚧 en curso | **Hecho**: spec [`sdd/common/upsert-idempotente-sap.md`](sdd/common/upsert-idempotente-sap.md) y los seis adaptadores OData con `lookup`/`update`; el `AddressID` se guarda en la colección `sap_keys`, no en la imagen. **Matiz al enunciado**: el **ETag no se persiste** — envejece y produce `412` sin poder reintentar sin releer; se lee en el lookup inmediatamente anterior al `PATCH` (R-5). **Falta**: cablear `FeatureSyncPipeline.write(...)` y verificar contra el tenant ([`testing/CHECKLIST-TENANT-SAP.md`](testing/CHECKLIST-TENANT-SAP.md) §1-§3) |
| PRD-10 | **Alta y actualización de Business Partner** desde nuestro lado (PUT/PATCH, upsert con coste) | extra | ✅ 2026-09-24 | Implementado: [`sdd/customer/upsert-business-partner-manual.md`](sdd/customer/upsert-business-partner-manual.md). Refactor del orquestador (`SyncCustomerUseCase#runPipeline` + `executeFromPayload`, cero duplicación), `BusinessPartnerUpsertException` (domain), `UpsertBusinessPartnerUseCase` + `BusinessPartnerWriteController` (PUT/PATCH, `@PreAuthorize hasRole(SAP_WRITE)`), OpenAPI al día con `x-required-role`, 25 `@Test` nuevos. Activación condicional (`@ConditionalOnBean(BusinessPartnerODataAdapter.class)`). R-1 anotada: PATCH de `category` se rechaza hasta ampliar el adapter. Pendiente: verificación contra el tenant SAP de test. |

### Detalle de los flujos propuestos

Rescatado de `architecture/FLOWS.md`, que ahora solo documenta lo implementado.
Son **clases que no existen**: el valor está en el diseño esbozado, no en tomarlo
como plan cerrado.

**PRD-9 · Consulta de BP (GET)** — ✅ implementado 2026-09-24, ver fila de la
tabla arriba y spec [`sdd/customer/consulta-business-partner-sap.md`](sdd/customer/consulta-business-partner-sap.md).
Era: leer un Business Partner de SAP sin escribir, para comprobar si existe
antes de dar de alta o para resolver dudas de datos.

**PRD-10 · Alta y actualización de BP (PUT/PATCH)** — ✅ implementado
2026-09-24, ver fila de la tabla arriba y spec
[`sdd/customer/upsert-business-partner-manual.md`](sdd/customer/upsert-business-partner-manual.md).
Era: alta o modificación inmediata del Business Partner completo desde la
API REST, con el mismo pipeline que el CDC. Como en PRD-9, el lookup
previo + `PATCH` con `If-Match` decide si es alta o actualización; el
cuerpo REST es la fuente del `Customer` (no se relee nada del legacy).

Pendiente: ampliar `BusinessPartnerODataAdapter.update` para soportar
`PATCH` de `category` (R-1 del spec) y verificación contra el tenant SAP
de test ([`testing/CHECKLIST-TENANT-SAP.md`](testing/CHECKLIST-TENANT-SAP.md)
§1-§3, mismo criterio que las features de contacto y datos bancarios).

**PRD-4 · Modo pull** — SAP BTP orquesta el ciclo y nosotros publicamos lo
pendiente en vez de empujarlo.

- Faltaría: estado `PENDING_SAP` en `SyncState`, `GET /btp/pending`
  (`BtpPendingQueryUseCase`, consulta `SyncStateRepositoryPort`) y
  `POST /btp/result` (`BtpResultProcessingUseCase`, transiciona
  `PENDING_SAP → SENT_SAP`/error).
- La propiedad `sap.integration.mode` (`push|pull|both`) **se retiró** (Fase 7,
  A18): estaba declarada y nadie la leía. Se reintroducirá con el primer
  consumidor real.

## Panel de operación (dashboard web)

Idea amplia, aún sin acotar. Hoy, para ver cómo estaba una entidad en un momento
dado hay que entrar a Mongo o a Elasticsearch a mano, y para desatascar un
registro no hay más vía que tocar la base de datos. La propuesta es una **web
sencilla y lo más liviana posible** que cubra consulta y operación.

| # | Pieza | Ámbito | Estado | Notas |
|---|---|---|---|---|
| UI-1 | Vista por entidad: estado actual, línea de tiempo de transiciones y última imagen | proyecto | ✅ 2026-09-23 | Cubierto por `dashboard-customer` con `GET /customers/{id}` (cabecera + tabs + contenido) y `GET /customers/{id}/state` con `lastCycle`. Spec [`sdd/customer/consulta-entidad-ui.md`](sdd/customer/consulta-entidad-ui.md) AC-1 |
| UI-2 | **Imagen en un momento dado**: elegir una versión del histórico y verla, o comparar dos | proyecto | ✅ 2026-09-23 | Cubierto por `dashboard-customer` con `GET /customers/{id}/history[?full=true]` y `GET /customers/{id}/history/diff?from=&to=` (consume ES directamente). Spec [`sdd/customer/consulta-entidad-ui.md`](sdd/customer/consulta-entidad-ui.md) AC-3 |
| UI-002 | **Vista grafo del flujo de integración por entidad** (pestaña "Grafo" en `dashboard-customer/`, SVG server-side del último ciclo) | proyecto | ⏸ pausada 2026-09-23 | Caso de negocio y AC escritos (`docs/sdd/customer/consulta-entidad-grafo-ui.md`); 3 KPIs propuestos (KPI-6, KPI-7, KPI-8) pero KPI-6 no aprobado por `auditor-business`. Bloqueo B-1: `WebMvcTagsContributor` que aplane `uri` a template no implementado en `customer-app` / `article-app` / `dashboard-customer`. Reapertura condicional cuando B-1 cierre (ver spec §10.bis) |
| UI-3 | **Buscador**: por id interno, por id externo (SAP) y por clave de dominio | proyecto | ✅ 2026-09-23 | Cubierto por `dashboard-customer` con `GET /customers/search?q={taxId}` contra `customers_current` (Mongo) y orden por `code`. Spec [`sdd/customer/consulta-entidad-ui.md`](sdd/customer/consulta-entidad-ui.md) AC-3 |
| UI-4 | **Acciones administrativas**: desatascar una entidad, forzar re-sync, reprocesar un mensaje de la DLT | proyecto | 💡 | Depende de OPS-1 y OPS-2; sin ellas no hay nada que invocar |
| UI-5 | Organizada **por dominios** (`customer`, `article`, `supplier`) | proyecto | 📋 | Acordada (preparada): `dashboard-customer/` es el patrón, `dashboard-article/` será otra feature cuando llegue el momento. Spec [`sdd/customer/consulta-entidad-ui.md`](sdd/customer/consulta-entidad-ui.md) §2 (fuera) |
| UI-6 | Stack: SPA estática, previsiblemente **React + Vite**, consumiendo las APIs REST | extra | ❌ descartada 2026-09-23 | El stack Thymeleaf + HTMX + Alpine.js cubre la lectura sin necesidad de SPA, sin build de cliente ni servidor adicional. El dashboard lee directo de Mongo y ES (R-1 del spec); React+Vite habría añadido una capa sin ganancia |
| UI-7 | Backend de soporte: endpoints de búsqueda y de acciones | proyecto | ✅ 2026-09-23 | Cubierto por `dashboard-customer` con cliente Mongo y ES nativos (no REST síncrono al `customer-app`). Las acciones se reducen al `POST /customers/alerts/{id}/ack` de F-9 promoted (TTL 30d). Spec [`sdd/customer/consulta-entidad-ui.md`](sdd/customer/consulta-entidad-ui.md) |

### Prerrequisitos

Antes de que esto sea viable hay que resolver cuatro cosas, y **dos no son obvias**:

1. **Autenticación (SEC-1)** — bloqueante. El panel expone datos personales y
   acciones destructivas; las APIs REST hoy no tienen auth.
2. **Tratamiento de PII (SEC-3)** — NIF, CIF y equivalentes son datos personales:
   hay que decidir qué se muestra, a quién y qué se registra en los logs.
3. **Persistir el id que devuelve SAP** — hoy `SapResponse.location` trae el
   identificador del recurso creado, pero **no lo consume nadie** y `sync_state`
   no guarda ningún id externo. Sin esto, «buscar por id de SAP» no se puede
   implementar: no tenemos ese dato en ninguna parte.
4. **Claves de dominio buscables** — el histórico de ES ya lleva `fiscal.taxId`,
   pero la búsqueda por clave de negocio hay que definirla por dominio y
   asegurarse de que esos campos están indexados.

### A decidir cuando se aborde

- ¿Una web por dominio o una sola con selector? Afecta al despliegue
  independiente por dominio, que es un principio del proyecto.
- ¿Solo lectura primero y acciones después? Reduce el alcance del prerrequisito
  de seguridad y permite entregar valor antes.
- ¿Se despliega junto a una app o como artefacto propio?

## Método de trabajo

| # | Mejora | Ámbito | Estado | Notas |
|---|---|---|---|---|
| MET-1 | Escribir los specs pendientes según se toquen las features | proyecto | 🚧 | Regla ya vigente; índice en [`sdd/README.md`](sdd/README.md) §5 |
| MET-2 | **Checklist de migración de versión mayor** de framework | transversal | 💡 | Boot 4 movió propiedades de conexión y descartó `@WebMvcTest` sin fallar en el arranque. Un checklist («¿qué propiedades cambiaron de namespace? ¿qué se ignora en silencio?») ahorra días. Aplicable a cualquier migración, no solo a Spring |
| MET-3 | Alinear versiones de cliente y servidor de la infraestructura local con las que trae el framework | transversal | ✅ 2026-09-09 | ES 8 contra cliente 9 rompía la indexación en silencio. Regla: el compose local declara las versiones **que el framework espera**, con el porqué anotado |
| MET-4 | Revisar que los scripts de init de la infraestructura no contradigan lo que declara la app | transversal | ✅ 2026-09-09 | Dos índices de `init.js` chocaban con los del código. Regla: si la app declara un índice, el init no lo duplica; y si lo hace, con el mismo nombre |
