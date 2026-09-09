# Mejoras y propuestas

> **Backlog vivo.** Aquí se apunta todo lo que merece la pena hacer pero **no**
> se está haciendo ahora: mejoras del PoC, ideas transversales a otros proyectos
> y funcionalidad extra. Sobrevive a cada feature: nada se borra por no haberse
> abordado, solo cambia de estado.
>
> Qué **no** va aquí: los **defectos** del código, que van al changelog de
> [`sdd/README.md`](sdd/README.md) §6 como brechas abiertas. Esto son mejoras
> sobre algo que ya funciona, o cosas que aún no existen.

## Cómo usar este fichero

- **Ámbito** — `proyecto`: solo este PoC · `transversal`: aplicable a otros
  proyectos del equipo · `extra`: funcionalidad nueva no comprometida.
- **Estado** — 💡 idea · 📋 acordada (se hará, sin fecha) · 🚧 en curso ·
  ✅ hecha (se deja con la fecha, para tener memoria) · ❌ descartada (con el porqué).
- Al abordar una, se convierte en un spec en [`sdd/`](sdd/README.md) y se marca
  aquí 🚧 con el enlace.

---

## Observabilidad

| # | Mejora | Ámbito | Estado | Notas |
|---|---|---|---|---|
| OBS-1 | **Stack Prometheus + Grafana** en `external-services` | proyecto | 📋 | Las apps ya exponen `/actuator/prometheus`, pero no hay quien lo recoja ni dashboards. Aplazado a propósito en la sesión del 2026-09-09 |
| OBS-2 | **Colector OTLP + javaagent de OTel** cableado en el arranque | proyecto | 📋 | El starter de OTel no soporta Boot 4; hay que usar el javaagent y `OTEL_EXPORTER_OTLP_ENDPOINT`. Sin esto no hay trazas distribuidas |
| OBS-3 | Dashboard de estado del pipeline: entidades por estado, tasa de `SAP_ERROR`, profundidad de la DLT | proyecto | 💡 | Depende de OBS-1 |
| OBS-4 | Logs estructurados en JSON con `traceId` correlado | transversal | 💡 | Hoy el formato es el de consola por defecto |

## Calidad y pruebas

| # | Mejora | Ámbito | Estado | Notas |
|---|---|---|---|---|
| TEST-1 | **Test de integración que arranque la app contra Testcontainers reales** (Mongo, ES, Kafka, SQL Server) | transversal | 📋 | **La lección más cara de este proyecto**: seis defectos llegaron a producción local porque los tests unitarios mockean los puertos. Ni la configuración real ni la máquina de estados se ejercían. Un solo IT de humo los habría cazado todos |
| TEST-2 | Patrón «test de configuración»: afirmar que las propiedades resuelven a lo esperado | transversal | ✅ 2026-09-09 | `CustomerMongoDatabaseConfigTest`. Nació de que Boot 4 ignoraba en silencio `spring.data.mongodb.uri`. **Replicable en cualquier proyecto Spring**: por cada propiedad crítica, un test que compruebe el valor efectivo, no el fichero |
| TEST-3 | Repositorios en memoria con las reglas reales en vez de mocks para los puertos con invariantes | transversal | ✅ 2026-09-09 | `InMemoryStateRepo` en `SyncAddressUseCaseTest`. Mockear un puerto que valida invariantes esconde justo los fallos que importan |
| TEST-4 | Smoke E2E en CI: levantar el compose, lanzar un sync y comprobar `SENT_SAP` | proyecto | 💡 | Sería el guardián de las regresiones que hemos ido encontrando a mano |
| TEST-5 | Contract tests contra el tenant SAP de test, no solo contra WireMock | proyecto | 📋 | WireMock devuelve 201 a todo: valida nuestro lado, no el contrato |
| TEST-6 | Cobertura JaCoCo con umbral que rompa el build en `domain` y `common` | proyecto | 💡 | Hoy el umbral está documentado pero no forzado |

## Resiliencia y operación

| # | Mejora | Ámbito | Estado | Notas |
|---|---|---|---|---|
| OPS-1 | **Recuperación de entidades atascadas** en estados intermedios | proyecto | 📋 | Si el proceso muere entre `FETCHING`/`INDEXING`/`SENDING_SAP`, la entidad queda bloqueada: esos estados no tienen transición de salida hacia `RECEIVED`. Verificado en vivo el 2026-09-09 con `CUST-001`. Hace falta una política de estado obsoleto (por antigüedad) o una transición de recuperación explícita. Es lo que invocaría UI-4 |
| OPS-2 | Endpoint o comando de **reproceso desde la DLT** | proyecto | 💡 | Hoy los mensajes de `outbox.<DOM>-dlt` se inspeccionan a mano y no hay forma de reinyectarlos. Lo consumiría UI-4 |
| OPS-3 | Decidir el **sufijo del topic DLT** | proyecto | 📋 | El real es `outbox.CUSTOMER-dlt` (default de Spring Kafka) y toda la documentación dice `<topic>.DLT`. O se configura el recoverer o se corrige la documentación — pero hay que elegir |
| OPS-4 | Mapeo fino de errores SAP (código, mensaje, campo) en vez de propagar el HTTP crudo | proyecto | 📋 | Ya listado como brecha; diagnóstico muy pobre cuando SAP rechaza algo |
| OPS-5 | Saga / compensación entre features | proyecto | 📋 | Un fallo parcial deja SAP a medias. Es la brecha estructural más grande del PoC |

## Seguridad

| # | Mejora | Ámbito | Estado | Notas |
|---|---|---|---|---|
| SEC-1 | **Autenticación en las APIs REST** de `customer` y `article` | proyecto | 📋 | Bloqueante para exponerlas a terceros o a un MCP |
| SEC-2 | Rotación y gestión de secretos SAP (Vault o equivalente) | transversal | 💡 | Hoy van por variables de entorno; suficiente en local, no en test/producción |
| SEC-3 | Ofuscación de PII en logs y en respuestas de consulta | proyecto | 💡 | Prerrequisito del MCP ([`tools-integrations/MCP.md`](tools-integrations/MCP.md)) |
| SEC-4 | Revisar `sap-sdk-client/`: tiene URL de tenant real, usuario y contraseña en claro | proyecto | 📋 | Es un spike desechable, pero la credencial hay que rotarla igualmente |

## Utillaje y experiencia de desarrollo

| # | Mejora | Ámbito | Estado | Notas |
|---|---|---|---|---|
| DX-1 | Versión **PowerShell** de `start-all.sh` / `stop-all.sh` | transversal | 💡 | Hoy hay que usar Git Bash o WSL en Windows |
| DX-2 | Perfil opcional de compose con **UIs de inspección** (Kafka UI, mongo-express) | transversal | 💡 | Hoy se inspecciona por CLI o con clientes de escritorio |
| DX-3 | `MSYS_NO_PATHCONV=1` en los `docker exec` de las guías | proyecto | 📋 | Git Bash convierte `/opt/...` a ruta Windows y el `exec` falla — justo el shell que recomendamos |
| DX-4 | Maven wrapper (`mvnw`) en el repo | transversal | 💡 | Elimina la dependencia del `mvn` del sistema y fija la versión |
| DX-5 | Hook de pre-commit que ejecute `mvn test` sobre los módulos tocados | transversal | 💡 | |
| DX-6 | Comprobador de enlaces de la documentación en CI | transversal | 💡 | Se ha usado a mano en cada reorganización de `docs/`; automatizarlo es barato y evita enlaces muertos |

## Features

| # | Mejora | Ámbito | Estado | Notas |
|---|---|---|---|---|
| PRD-1 | Dominio **`supplier`** real | extra | 📋 | Hoy es un placeholder no desplegable |
| PRD-2 | `CONTACT` con el contrato real de S/4 (`A_AddressEmailAddress`, `A_AddressPhoneNumber`) | proyecto | 📋 | Email y teléfono cuelgan de la dirección en S/4; hoy no se modela así |
| PRD-3 | Mandatos SEPA desde el legacy para completar `BANKING` | proyecto | 📋 | El adaptador existe pero no le llegan datos |
| PRD-4 | **Modo pull**: BTP orquesta el ciclo (`PENDING_SAP`, `/btp/pending`, `/btp/result`) | extra | 💡 | Propuesta no implementada: ni el estado ni los endpoints existen |
| PRD-5 | Batch fin de día / D+1 disparado por topic | extra | 💡 | Reutilizaría los mecanismos existentes |
| PRD-6 | Eventos entrantes desde S/4 (stock) | extra | 💡 | Pendiente de decisión del equipo SAP |
| PRD-7 | **Servidor MCP** de consulta para agentes IA | extra | 💡 | Requiere SEC-1 y SEC-3. Propuesta en [`tools-integrations/MCP.md`](tools-integrations/MCP.md) |
| PRD-8 | `S3ImageStoreAdapter` sobre MinIO | extra | 💡 | MinIO está levantado y sin uso |
| PRD-9 | **Consulta de Business Partner desde SAP** (GET, sin coste) | extra | 💡 | Medio hecho: existen `BusinessPartnerReadPort` y `BusinessPartnerReadAdapter` (`sap.odata.read.enabled=true`). Faltan `LookupCustomerUseCase` y un endpoint que los exponga. Detalle abajo |
| PRD-10 | **Alta y actualización de Business Partner** desde nuestro lado (POST/PATCH, upsert con coste) | extra | 💡 | Ninguna de las clases existe. Detalle abajo |

### Detalle de los flujos propuestos

Rescatado de `architecture/FLOWS.md`, que ahora solo documenta lo implementado.
Son **clases que no existen**: el valor está en el diseño esbozado, no en tomarlo
como plan cerrado.

**PRD-9 · Consulta de BP (GET)** — leer un Business Partner de SAP sin escribir,
para comprobar si existe antes de dar de alta o para resolver dudas de datos.

- Existe: `BusinessPartnerReadPort` → `BusinessPartnerReadAdapter` → `SapClient.get()`
  con query params OData (`$top`, `$filter`), tras `sap.odata.read.enabled=true`.
- Faltaría: `LookupCustomerUseCase` (application) y `LookupCustomerController`
  (bootstrap/web) para exponerlo.

**PRD-10 · Alta y actualización de BP (POST/PATCH)** — hoy solo empujamos
features sueltas; esto sería crear o modificar el Business Partner completo.

- Faltaría: `CreateBusinessPartnerUseCase` y `UpdateBusinessPartnerUseCase`
  (application), con sus controllers, sobre `A_BusinessPartner` de la API
  `API_BUSINESS_PARTNER`.
- Ojo al coste: cada upsert contra S/4 se factura, a diferencia del GET.

**PRD-4 · Modo pull** — SAP BTP orquesta el ciclo y nosotros publicamos lo
pendiente en vez de empujarlo.

- Faltaría: estado `PENDING_SAP` en `SyncState`, `GET /btp/pending`
  (`BtpPendingQueryUseCase`, consulta `SyncStateRepositoryPort`) y
  `POST /btp/result` (`BtpResultProcessingUseCase`, transiciona
  `PENDING_SAP → SENT_SAP`/error).
- La propiedad `sap.integration.mode` (`push|pull|both`) ya está declarada en
  `application-common.yml` pero **ningún código la lee**: es un hueco reservado,
  no un conmutador funcional.

## Panel de operación (dashboard web)

Idea amplia, aún sin acotar. Hoy, para ver cómo estaba una entidad en un momento
dado hay que entrar a Mongo o a Elasticsearch a mano, y para desatascar un
registro no hay más vía que tocar la base de datos. La propuesta es una **web
sencilla y lo más liviana posible** que cubra consulta y operación.

| # | Pieza | Ámbito | Estado | Notas |
|---|---|---|---|---|
| UI-1 | Vista por entidad: estado actual, línea de tiempo de transiciones y última imagen | proyecto | 💡 | Es lo que hoy se mira a mano en `sync_state` y `*_current` |
| UI-2 | **Imagen en un momento dado**: elegir una versión del histórico y verla, o comparar dos | proyecto | 💡 | El histórico ya está indexado en ES y hay `/history/diff`; falta la vista |
| UI-3 | **Buscador**: por id interno, por id externo (SAP) y por clave de dominio | proyecto | 💡 | NIF / NIE / CIF / cartão para el Business Partner; código de producto de la empresa para artículos. Cada dominio declara sus claves buscables |
| UI-4 | **Acciones administrativas**: desatascar una entidad, forzar re-sync, reprocesar un mensaje de la DLT | proyecto | 💡 | Depende de OPS-1 y OPS-2; sin ellas no hay nada que invocar |
| UI-5 | Organizada **por dominios** (`customer`, `article`, `supplier`) | proyecto | 💡 | `common` no aparece: es infraestructura, no tiene entidades que mostrar |
| UI-6 | Stack: SPA estática, previsiblemente **React + Vite**, consumiendo las APIs REST | extra | 💡 | Criterio: lo más liviano posible y sin servidor propio. Alternativa aún más ligera si el alcance se queda en consulta: HTML + JS sin framework |
| UI-7 | Backend de soporte: endpoints de búsqueda y de acciones | proyecto | 💡 | Hoy solo existen `/sync`, `/validate`, `/history` y `/history/diff` |

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
