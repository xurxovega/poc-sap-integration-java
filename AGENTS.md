# AGENTS.md — SAP Integration (Java)

> **Léeme primero, en cualquier iteración.** Este fichero es el contrato de
> trabajo del repositorio: cómo está montada la aplicación, cómo se opera sobre
> ella y qué reglas son innegociables. Todo lo demás son documentos de detalle
> enlazados desde aquí.

PoC de sincronización de datos maestros (`customer`, `article`, `supplier`)
desde sistemas legacy hacia **SAP S/4 Public Cloud**. Migración del POC Python
(`../poc-sap-integration`) a **Java 25 + Spring Boot 4.0 + Maven**.

---

# Parte 1 — Operativa (cómo se trabaja aquí)

Dos técnicas gobiernan todo el desarrollo. **No son negociables** y no se saltan
"por ser un cambio pequeño". Detalle completo en
[`docs/architecture/DESARROLLO.md`](docs/architecture/DESARROLLO.md).

## 1.1 SDD anchor — el spec y el código no divergen

Cada feature tiene su spec en `docs/sdd/<subproyecto>/<nombre-de-la-feature>.md`
(una carpeta por módulo Maven, un fichero por feature: p. ej.
`docs/sdd/customer/sincronizacion-direccion.md`). Spec y código son el
mismo hecho contado dos veces, y el ancla es **bidireccional**:

| Si cambia… | …entonces, en el **mismo PR** |
|---|---|
| **el spec** | cambian los tests y el código; el `AC-n` nuevo empieza en rojo |
| **el código** (comportamiento observable) | se actualiza el spec de la feature + su tabla de cambios |
| **un contrato SAP** ([`docs/sdd/sap-api-catalog.md`](docs/sdd/sap-api-catalog.md)) | se revisan los specs de las features que lo consumen **antes** de tocar adaptadores |

Un cambio de comportamiento sin spec actualizado está **incompleto**, y un spec
cambiado sin tests que lo respalden también.

Los specs se van escribiendo **a medida que se toca cada feature**: el índice
vivo con su estado está en [`docs/sdd/README.md`](docs/sdd/README.md) §5. No
escribas specs en masa de features que nadie va a tocar.

**Toda alta, modificación o baja de una feature se registra en tres sitios**, en
el mismo PR: el §10 del spec, el `CHANGELOG.md` de la carpeta del subproyecto y
un evento en la tabla `feature_evento` del registro MySQL `sdd_registry`
([`docs/sdd/README.md`](docs/sdd/README.md) §8). Las bajas son lógicas: una
feature descartada no se borra del registro.

## 1.1 bis Glosario: automático, no opcional

**Cuando aparezca un concepto nuevo, se añade a
[`docs/GLOSSARY.md`](docs/GLOSSARY.md) en el momento**, sin que nadie lo pida.
Cuenta como concepto nuevo cualquier término que un compañero que entre mañana
no podría deducir del código: un estado, un patrón, una pieza de infraestructura,
una sigla, una decisión con nombre propio. Va con una definición de una o dos
frases y un enlace al documento donde se detalla. Si el término ya está pero la
definición se ha quedado vieja, se actualiza.

## 1.2 TDD — ningún código de producción sin test rojo previo

Ciclo **red → green → refactor**:

- 🔴 **Red**: escribe el test, ejecútalo y **lee el fallo**. Debe fallar por la
  razón correcta (no por un `NullPointerException` accidental).
- 🟢 **Green**: el mínimo código que lo pone en verde. Nada de generalidad que
  ningún test pida.
- 🔧 **Refactor**: con la suite en verde, re-ejecutando tras cada paso.

Y siempre **de dentro afuera**, que es también la regla de dependencias:
`domain` → `application` → `adapters` → `bootstrap`.

## 1.3 Secuencia de una iteración

```
1. Leer AGENTS.md (esto) y el spec en docs/sdd/<subproyecto>/<feature>.md
   └─ ¿no existe? se escribe ahora desde docs/sdd/_template/feature.md
2. Traducir los criterios de aceptación (AC-n) del spec a tests → ROJO
3. Implementar de dentro afuera hasta VERDE, refactorizar
4. mvn verify
5. Cerrar el ancla:
   ├─ spec §9 (trazabilidad spec↔código↔test) y §10 (cambios)
   ├─ CHANGELOG.md de la carpeta del subproyecto (una línea)
   ├─ CHANGELOG.md raíz si el cambio se nota en negocio (sin detalle técnico)
   ├─ registro MySQL: evento ALTA / MODIFICACION / BAJA en feature_evento
   ├─ docs/GLOSSARY.md: todo concepto nuevo que haya aparecido
   └─ docs/sdd/README.md §5 (estado) y §6 (changelog, si abre/cierra brecha)
```

Si el trabajo arranca desde el código (bug, refactor, hallazgo), la secuencia es
la misma al revés: reproducir con un test en rojo, arreglar y **actualizar el
spec** en el mismo PR.

## 1.4 Definición de hecho

- [ ] El spec existe y refleja el comportamiento final.
- [ ] Cada `AC-n` tiene al menos un test que lo cita en su Javadoc.
- [ ] Los tests nuevos se escribieron **antes** que su código.
- [ ] `mvn verify` en verde.
- [ ] Estado e índice de `docs/sdd/README.md` al día.
- [ ] `CHANGELOG.md` del subproyecto y evento en `feature_evento` registrados.
- [ ] `CHANGELOG.md` raíz actualizado si el cambio se percibe en negocio.
- [ ] Conceptos nuevos añadidos a `docs/GLOSSARY.md`.
- [ ] Ningún documento nuevo duplica algo que ya esté en `docs/architecture/` o `docs/sdd/`.

## 1.5 Convenciones de código y test

- **Tests**: clase `<Clase>Test` (unit/slice) o `<Escenario>IT` (integración);
  método en **camelCase que describe la regla** (`missingCityFails`,
  `retriesOn5xxUntilSuccess`), nunca `testX`.
- **El Javadoc del test cita el `AC-n`** del spec; el Javadoc de la clase de
  producción cita la sección de arquitectura (`(OVERVIEW.md §5)`, `(TECH.md §8)`).
  Esa doble cita es el ancla vista desde el código.
- **Sin Spring en `domain` ni `application`**: dominio puro, sin beans ni contexto.
- **Mocks sobre puertos** (interfaces de `domain/port/`), nunca sobre
  implementaciones concretas.
- Resto de convenciones vigentes (fixtures, strict stubs, AssertJ, slice web) en
  [`docs/testing/TESTING.md`](docs/testing/TESTING.md) §4.

---

# Parte 2 — Cómo está montada la aplicación

## 2.1 Reactor Maven

Un artefacto desplegable **por dominio**, más un shared kernel:

| Módulo | Qué es | Artefacto |
|---|---|---|
| `sap-api-models` | specs OpenAPI oficiales de SAP + modelos Java generados | jar de modelos |
| `common` | **shared kernel**: máquina de estados, `SapClient`, auth, observabilidad, soporte de test | jar librería (semver) |
| `customer` | app Spring Boot — puerto **8081** | jar ejecutable |
| `article` | app Spring Boot — puerto **8082** | jar ejecutable |
| `supplier` | placeholder (futuro) | — |
| `it` | integración cross-dominio + contratos SAP (WireMock) | tests |

`sap-sdk-client/` **no es del reactor**: es un repositorio anidado
independiente, spike OpenAPI desechable (Boot 3.5 / Java 17). No tocarlo como si
fuera parte de la app.

## 2.2 Arquitectura hexagonal por dominio

Capas por paquete y regla de dependencias:

```
bootstrap  →  adapters  →  application  →  domain
(Spring)      (infra)      (use cases)     (puro, sin Spring)
```

`domain` no depende de nada. `application` solo de `domain`. `adapters` de
`application` (puertos) y de `common`. **Nada de lógica de negocio en
`bootstrap` ni en `adapters`.**

## 2.3 El pipeline, de punta a punta

```
Kafka outbox.CUSTOMER / outbox.ARTICLE   (CDC: triggers legacy → outbox → Debezium)
  │            REST POST /customers/sync · /articles/sync   (entrada alternativa)
  ▼
<Dominio>KafkaListener / Sync<Dominio>Controller     →  IngestionMessage
  ▼
Sync<Dominio>UseCase        dedupe por payloadHash (idempotencia)
  ├─ LegacyRepositoryPort   → SQL Server (customer) / PostgreSQL (article)
  ├─ <Dominio>Validations   → reglas de negocio (domain puro)
  ├─ ImageStorePort         → MongoDB (imagen actual)
  ├─ HistoryIndexerPort     → Elasticsearch (histórico)
  └─ SapOutboundPort        → SapClient → SAP BTP / S/4 nativo
  ▼
SyncStateMachine (common) — cada transición persistida en Mongo con timestamp, origen y hash
```

Estados: `RECEIVED → FETCHING → VALIDATING → {VALID|INVALID} → INDEXING →
INDEXED → SENDING_SAP → {SENT_SAP|SAP_ERROR}`, más `ERROR` y
`COMMUNICATION_ERROR` recuperables. `SENT_SAP` e `INVALID` cierran el ciclo pero
admiten re-entrada a `RECEIVED` con un evento nuevo.

Detalle con nombres de clase en [`docs/architecture/FLOWS.md`](docs/architecture/FLOWS.md);
esquema completo en [`docs/architecture/OVERVIEW.md`](docs/architecture/OVERVIEW.md) §5.

## 2.4 Puertos clave

| Puerto | Implementaciones |
|---|---|
| `IngestionPort` | `CustomerKafkaListener`/`ArticleKafkaListener` (CDC), `Sync*Controller` (REST) |
| `LegacyRepositoryPort<T>` | `SqlServerCustomerRepository`, `PostgresArticleRepository` |
| `ImageStorePort<T>` | `MongoCustomerImageStore`, `MongoArticleImageStore` |
| `HistoryIndexerPort<T>` | `ElasticsearchCustomerIndexer`, `ElasticsearchArticleIndexer` |
| `SyncStateRepositoryPort` | `MongoSyncStateRepository` (en `common`, compartido — no duplicar) |
| `SapOutboundPort<P>` | por feature: `AddressSapPort`, `FiscalSapPort`, `ContactSapPort`, `BankingSapPort`, `CustomerSapOutboundPort`, `MandateSapOutboundPort` |
| `BusinessPartnerReadPort` | `BusinessPartnerReadAdapter` (GET/search OData, `sap.odata.read.enabled=true`) |

## 2.5 Endpoints REST

| Método | Ruta | Qué hace |
|---|---|---|
| `POST` | `/customers/sync` · `/articles/sync` | ingesta síncrona (mismo pipeline que CDC) |
| `POST` | `/customers/validate` | valida sin enviar |
| `GET` | `/customers/{id}/history` · `/articles/{id}/history` | histórico indexado |
| `GET` | `/customers/{id}/history/diff` · `/articles/{id}/history/diff` | diff entre versiones |

> Sin autenticación todavía — brecha abierta, ver [`docs/sdd/README.md`](docs/sdd/README.md) §6.

## 2.6 Integración con SAP

**Cliente HTTP low-level.** `SapClient` (`common/sap/`) abstrae transporte,
auth, retry y circuit breaker; soporta GET, `send` (POST), PATCH y DELETE.
Implementado por `WebClientSapClient` (WebClient + Resilience4j):

- 5xx y errores de transporte → retry con backoff y cuentan para el circuit
  breaker; **4xx no se reintenta**.
- Timeouts vía `sap.client.connect-timeout-ms` / `sap.client.response-timeout-ms`.
- `Idempotency-Key` = `payloadHash` en cada envío.
- **CSRF OData V2 cableado**: `CsrfTokenProvider`/`S4CsrfTokenProvider` hacen el
  fetch de `x-csrf-token` en escrituras a S/4, con refresh y reintento único
  ante 403 (`sap.s4.csrf.enabled`).
- **OAuth2 client-credentials real** con caché por expiración (`OAuth2TokenClient`;
  xsuaa para BTP, token endpoint o basic para S/4). Cae a token **stub** solo si
  falta configuración — dev local contra mocks.

**Dos familias de adaptadores de salida, sobre los mismos puertos:**

| Familia | Dónde | Activación |
|---|---|---|
| **BTP** | `customer/adapters/sap/Btp*Adapter.java` | siempre activos (sin `@ConditionalOnProperty`) |
| **OData S/4 nativo** | `customer/adapters/sap/odata/BusinessPartner*ODataAdapter.java` | por feature: `sap.odata.<feature>.enabled=true` |

Ambas coexisten; qué adaptador atiende un mensaje depende de la configuración.

**Serialización.** Siempre `SapJsonMapper.write(dto)` (`common/sap/json/`), nunca
`String.format`. El payload va **sin envolver**: el wrapper `{"d":...}` de OData
V2 aparece solo en las *respuestas*, jamás en el body de la petición. DTOs con
`@JsonProperty` en `<dominio>/adapters/sap/dto/`; los adaptadores OData usan los
modelos generados de `sap-api-models`
(`com.poc.sap.integration.api.customer.model`), no DTOs manuales.

**Modelos generados (`sap-api-models`).** Las specs OpenAPI oficiales viven en
`sap-api-models/specs/<dominio>/` (fuera de `src/main/resources`) y el
`openapi-generator-maven-plugin` produce las clases en
`target/generated-sources/` (no commiteadas). Es un **Published Language** (DDD):
lenguaje definido por SAP y consumido por todos los bounded contexts — no
confundir con el Shared Kernel, que es `common`. Catálogo en
[`docs/sdd/sap-api-catalog.md`](docs/sdd/sap-api-catalog.md).

> ⚠️ **Push es lo implementado.** El modo *pull* (SAP BTP orquestando el ciclo,
> estado `PENDING_SAP`, endpoints `/btp/pending` y `/btp/result`) es una
> **propuesta no implementada**: ese estado no está en el enum `SyncState` y esos
> endpoints no existen. La propiedad `sap.integration.mode` sí está declarada en
> `application-common.yml`, pero **ningún código la lee**: es un hueco reservado,
> no un conmutador funcional. Lo mismo aplica al batch D+1 y a los eventos de
> stock desde S/4. Ver
> [`docs/architecture/INTEGRATION-PATTERNS.md`](docs/architecture/INTEGRATION-PATTERNS.md),
> que distingue implementado de propuesto.

## 2.7 Stack

- **Java** 23 mínimo, objetivo **25 LTS** (records, sealed, pattern matching,
  virtual threads). Profile Maven `jdk25` auto-activado con JDK 25+.
- **Spring Boot 4.0**. Cuidado con sus rupturas ya resueltas: usar
  `spring-boot-starter-kafka` (el `spring-kafka` suelto no autoconfigura),
  Jackson 3 por defecto, **sin** starter OTel (incompatible — se usa el
  javaagent), y `@WebMvcTest` eliminado (slice web con
  `MockMvcBuilders.standaloneSetup`).
- **SAP Cloud SDK** 5.32.0 · **Resilience4j** · **Micrometer + Prometheus** ·
  trazas por **OTel javaagent**.
- **Testing**: JUnit 5, Mockito, AssertJ, WireMock, Testcontainers.

## 2.8 Comandos

```bash
./scripts/start-all.sh                # levantar todo (infra + mock SAP + apps)
./scripts/stop-all.sh                 # parar todo
mvn validate                          # validar reactor
mvn test                              # unit + slice (sin Docker)
mvn verify                            # + integración
mvn -pl common install -DskipTests    # publicar shared kernel local
mvn -pl customer test                 # un dominio
mvn -pl customer test -Dtest=AddressValidatorTest#missingCityFails
mvn -pl it verify                     # cross-dominio + contratos SAP
mvn generate-sources -pl sap-api-models   # regenerar modelos SAP
```

Infraestructura local (Kafka, SQL Server, PostgreSQL, Mongo, Elasticsearch,
MinIO): `cd external-services && docker compose up -d`.

---

# Parte 3 — Reglas al modificar código

- **Antes de tocar nada**: localizar (o escribir) el spec de la feature y el test
  que falla. Sin eso no se empieza.
- **Nueva feature**: spec → tests en rojo → value object + validador en
  `domain/feature/<feature>/` → alta en el enum `CustomerFeature` →
  `<Feat>SapPort` + adaptador → `Sync<Feat>UseCase` → dispatch en
  `SyncCustomerUseCase`.
- **Nuevo dominio**: spec → módulo en `<modules>` del parent → dependencia a
  `common` → tests de validación en rojo → aggregate, puertos, use case,
  adaptadores → `@SpringBootApplication` + `@KafkaListener(outbox.<DOM>)` + REST
  → `application.yml` con `spring.config.import=application-common.yml`.
  **Reutilizar** `SyncStateMachine` y `MongoSyncStateRepository`, no duplicarlos.
- **Nuevo puerto**: interfaz en `<dominio>/domain/port/`, adaptador en
  `<dominio>/adapters/`.
- **Nuevo DTO SAP**: record/POJO con `@JsonProperty` en
  `<dominio>/adapters/sap/dto/`, serializado con `SapJsonMapper.write(dto)`.
- **Nuevo adaptador OData**: en `<dominio>/adapters/sap/odata/`, implementa el
  puerto existente, serializa **sin envolver**, activación condicional con
  `@ConditionalOnProperty("sap.odata.<feature>.enabled")`, modelos generados de
  `sap-api-models`.
- **Nueva spec SAP**: YAML en `sap-api-models/specs/<dominio>/` + `<execution>` en
  el `openapi-generator-maven-plugin`, y alta en
  [`docs/sdd/sap-api-catalog.md`](docs/sdd/sap-api-catalog.md).
- **Cambio en `SapClient`**: nuevo método HTTP → implementar en
  `WebClientSapClient` vía su `exchange()` interno.
- **Cambio en `common`**: bump semver y ejecutar `it/` **antes**; un
  `minor`/`major` obliga a re-desplegar todos los dominios.
- **Secretos fuera del código**: variables de entorno / Vault, nunca en YAML
  commiteados.
- **Al cerrar**: spec §9 y §10, e índice/changelog de `docs/sdd/README.md`.

---

# Parte 4 — Mapa de documentación

Qué leer según lo que necesites. **No dupliques contenido entre estos ficheros**:
si algo ya está escrito, enlázalo.

| Necesitas… | Documento |
|---|---|
| **qué debe hacer** el sistema, estado por feature, brechas | [`docs/sdd/README.md`](docs/sdd/README.md) |
| el spec de una feature concreta | `docs/sdd/<subproyecto>/<feature>.md` — p. ej. [`docs/sdd/customer/sincronizacion-direccion.md`](docs/sdd/customer/sincronizacion-direccion.md) (plantilla: [`docs/sdd/_template/feature.md`](docs/sdd/_template/feature.md)) |
| contratos SAP (APIs OpenAPI oficiales) | [`docs/sdd/sap-api-catalog.md`](docs/sdd/sap-api-catalog.md) |
| **cómo se desarrolla**: ciclo SDD+TDD, capas, DoD | [`docs/architecture/DESARROLLO.md`](docs/architecture/DESARROLLO.md) |
| **cómo está construido**: módulos, dominios, estados, deployment, NFR | [`docs/architecture/OVERVIEW.md`](docs/architecture/OVERVIEW.md) |
| stack y decisiones técnicas | [`docs/architecture/TECH.md`](docs/architecture/TECH.md) |
| flujos con nombres de clase para navegar el código | [`docs/architecture/FLOWS.md`](docs/architecture/FLOWS.md) |
| patrones de integración SAP (implementado vs propuesto) | [`docs/architecture/INTEGRATION-PATTERNS.md`](docs/architecture/INTEGRATION-PATTERNS.md) |
| mapa funcional navegable (HTML, doble clic) | [`docs/architecture/MAPA-FUNCIONAL.html`](docs/architecture/MAPA-FUNCIONAL.html) |
| arrancar en local en ~15 min, o contra servidores de test | [`docs/QUICK_START.md`](docs/QUICK_START.md) |
| levantar/parar todo con un comando | [`scripts/start-all.sh`](scripts/start-all.sh) · [`scripts/stop-all.sh`](scripts/stop-all.sh) |
| probar la API a mano (colección Postman) | [`scripts/postman/`](scripts/postman/) |
| catálogo de la suite de tests y convenciones | [`docs/testing/TESTING.md`](docs/testing/TESTING.md) |
| probar a fondo (CDC, resiliencia, tenant real) | [`docs/testing/GUIA-PRUEBAS.md`](docs/testing/GUIA-PRUEBAS.md) |
| SAP Cloud SDK (OData VDM, OpenAPI, destinations) | [`docs/tools-integrations/SAP_CLOUD_SDK.md`](docs/tools-integrations/SAP_CLOUD_SDK.md) |
| propuesta de servidor MCP para agentes IA | [`docs/tools-integrations/MCP.md`](docs/tools-integrations/MCP.md) |
| mejoras e ideas pendientes (backlog, no defectos) | [`docs/MEJORAS-Y-PROPUESTAS.md`](docs/MEJORAS-Y-PROPUESTAS.md) |
| **qué cambia para negocio** en cada revisión | [`CHANGELOG.md`](CHANGELOG.md) (raíz) |
| cambios de las features de un subproyecto | `docs/sdd/<subproyecto>/CHANGELOG.md` |
| quién pidió una feature, cuándo y en qué estado está | registro MySQL `sdd_registry` — [`docs/sdd/README.md`](docs/sdd/README.md) §8 |
| terminología del proyecto (**se actualiza siempre**) | [`docs/GLOSSARY.md`](docs/GLOSSARY.md) |
| levantar la infraestructura local | [`external-services/README.md`](external-services/README.md) |
| proyecto Python de referencia | `../poc-sap-integration` |
