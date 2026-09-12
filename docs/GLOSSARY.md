# Glosario

> Términos usados en el proyecto `poc-sap-integration-java`.
> Los enlaces internos apuntan a documentos del repo; los externos a recursos oficiales.

## A

### A2X (Application-to-Cross-Application)

Etiqueta de SAP para las APIs OData de S/4 Public Cloud pensadas para integrarse desde fuera con un communication user (`API_BUSINESS_PARTNER (A2X)`, `API_PRODUCT_SRV (A2X)`). Son las que consume la familia OData de adaptadores. Ver [`sdd/sap-api-catalog.md`](sdd/sap-api-catalog.md).

### Adapter (Adaptador)

Implementación de un **port** en la capa de infraestructura. Traduce entre el dominio y tecnologías externas (Kafka, MongoDB, SAP, Elasticsearch). Ver [`TECH.md`](architecture/TECH.md#5-puertos-y-adaptadores).

### ADR (Architecture Decision Record)

Documento corto que registra una decisión de arquitectura: contexto, opciones, decisión, consecuencias y **cuándo se reevalúa**. Viven en [`docs/architecture/adr/`](architecture/adr/README.md); el primero es ADR-0001 (transporte `RestClient` hacia SAP). No se editan para cambiar la decisión: se escribe otro que lo sustituye.

### Apertura de ciclo (`beginCycle`) / avance (`advance`)

Las dos intenciones de la máquina de estados, separadas desde la Fase 1 de la auditoría. **Abrir ciclo** es lo que ocurre cuando llega un evento: legal desde *cualquier* estado actual, por el estado de entrada del pipeline. **Avanzar** es moverse dentro del ciclo abierto y sigue la tabla de transiciones. Mientras fueron la misma operación, cada camino nuevo descubría «una fila que faltaba» y fallaba igual — tres veces. Ver [`maquina-de-estados.md`](sdd/common/maquina-de-estados.md) §3.

### API Business Hub

Portal oficial de SAP para descubrir APIs y especificaciones OData/OpenAPI de SAP. [api.sap.com](https://api.sap.com/)

### ArchUnit

Librería de tests que verifica reglas de arquitectura sobre el bytecode. En el repo, `DomainPurityTest` (common, customer, article) prohíbe que `..domain..` dependa de Spring, Jackson, Mongo, Kafka, Micrometer o JPA. La misma regla sobre `application` está en el backlog (TEST-7, Fase 7). Ver [`TESTING.md`](testing/TESTING.md) §6.

## B

### `BankIdentification` (A_BusinessPartnerBank)

Identificador **secuencial** de cada cuenta bancaria dentro de un Business Partner (`0001`, `0002`...). No es el BIC ni el código de banco: el BIC/SWIFT pertenece al maestro de bancos y al mandato SEPA (`SenderBankSWIFTCode`). El país del banco va en `BankCountryKey`, derivado del IBAN. Auditoría B3.

### BAPI (Business Application Programming Interface)

Interfaz estándar de SAP para acceder a procesos de negocio. En Java se invoca vía JCo/RFC. Ver [BAPI/RFC en SAP Cloud SDK](https://sap.github.io/cloud-sdk/docs/java/features/bapi-and-rfc/overview).

### BIC (SWIFT)

*Bank Identifier Code*: identificador del banco (8 u 11 caracteres). **No** es `BankIdentification` de `A_BusinessPartnerBank`; en S/4 pertenece al maestro de bancos y al mandato (`SenderBankSWIFTCode`). Auditoría B3.

### BTP (Business Technology Platform)

Plataforma cloud de SAP. En este proyecto se usa para desplegar apps y resolver destinos/autenticación vía Destination Service y XSUAA/IAS.

### `$batch` (OData) / changeset

Endpoint OData que agrupa varias operaciones en una sola request HTTP; cada *changeset* interno es atómico (todo o nada). Previsto para las cargas batch masivas (patrón 4 de [`INTEGRATION-PATTERNS.md`](architecture/INTEGRATION-PATTERNS.md)); aún sin soporte en `SapClient`.

### Business Events

Eventos de negocio que S/4HANA Public Cloud publica cuando algo ocurre dentro de SAP (p. ej. movimiento de mercancía, cambio de stock). Base del patrón 5 (SAP → plataforma) de [`INTEGRATION-PATTERNS.md`](architecture/INTEGRATION-PATTERNS.md); pendiente de decisión del equipo SAP.

### Business Partner

Entidad maestra de SAP S/4HANA que agrupa datos de cliente, proveedor y socio. En el dominio `customer` se sincroniza contra la API OData `API_BUSINESS_PARTNER` (adaptadores `BusinessPartner*ODataAdapter`) o contra las APIs BTP (`Btp*Adapter`). Ver [`SAP_CLOUD_SDK.md`](tools-integrations/SAP_CLOUD_SDK.md#1-business-partner--odata-vdm).

## C

### Callback

Endpoint REST propio que recibe notificaciones de SAP. Se modela como entrada alternativa en el puerto `IngestionPort`. Ver [`TECH.md`](architecture/TECH.md#6-entradas).

### CDC (Change Data Capture)

Captura de cambios en bases de datos legacy. En este proyecto: triggers → tabla outbox → Debezium → Kafka. Ver [`TECH.md`](architecture/TECH.md#6-entradas).

### Ciclo en vuelo

Ciclo de sincronización que quedó a medias porque el proceso murió entre `RECEIVED` y `SENDING_SAP`. Antes bloqueaba la entidad para siempre (OPS-1); ahora un evento nuevo abre ciclo igualmente y el repositorio lo registra en log (`isInFlight`). Los estados en vuelo son `RECEIVED`, `FETCHING`, `VALIDATING`, `VALID`, `INDEXING`, `INDEXED`, `SENDING_SAP`.

### Circuit Breaker

Patrón de resiliencia que abre el circuito tras fallos consecutivos para evitar sobrecargar el sistema downstream. Implementado con **Resilience4j**. Ver [`TECH.md`](architecture/TECH.md#8-clientes-sap).

### Cloud Connector

Componente de SAP BTP que permite conectividad segura desde BTP hacia sistemas on-premise.

### Communication arrangement / communication user

Configuración en S/4HANA Public Cloud que habilita un escenario de API (p. ej. `SAP_COM_0008` para Business Partner) y el usuario técnico con el que se autentican las llamadas entrantes.

### `ConcurrentTransitionException`

Dos instancias intentaron escribir la misma secuencia de estado para la misma entidad; la primera gana y la segunda recibe esta excepción en lugar de pisar el estado. La produce el índice único `dom_ent_seq_uk` sobre `(domain, entityId, seq)`. Es transitoria: se reintenta releyendo. Ver **Secuencia de estado**.

### Contract test (test de contrato)

Test que fija **lo que enviamos** a un sistema externo: método, path, cabeceras y cuerpo. En el repo viven en `it/…/contract/*ContractTest`, los ejecuta failsafe y, desde la Fase 2 de la auditoría, construyen el `RestClientSapClient` real contra WireMock y ejercitan el **adaptador de producción**, no el stub. No validan lo que SAP acepta: eso es la validación contra el tenant de test. Ver [`TESTING.md`](testing/TESTING.md) §7.

### Criterio de aceptación (AC-n)

Regla verificable de un spec SDD, numerada `AC-1`, `AC-2`… Cada una debe tener **al menos un test que la cite** en su Javadoc: es la mitad del ancla vista desde el código. Ver [`DESARROLLO.md`](architecture/DESARROLLO.md).

## D

### Debezium

Plataforma CDC de código abierto. Captura cambios del legacy y los publica en Kafka.

### Dependabot

Servicio de GitHub que abre PRs con actualizaciones de dependencias. Configurado en `.github/dependabot.yml` (Maven y GitHub Actions, semanal, agrupando Spring y librerías de test). La CI valida cada PR.

### Destination Service

Servicio de SAP BTP que centraliza URL, autenticación y propiedades de conexión a sistemas SAP. Ver [Destination Service — Cloud SDK](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/destination-service).

### DDD (Domain-Driven Design)

Enfoque de diseño centrado en el dominio. En este proyecto se aplica mediante bounded contexts (`customer`, `article`, `supplier`) y capas por paquete.

### DLT (Dead Letter Topic)

Topic `<original>-dlt` al que el `DefaultErrorHandler` publica un mensaje que sigue fallando tras agotar los reintentos con backoff, para inspección o reproceso manual. Ver [`TECH.md`](architecture/TECH.md#6-entradas).

## E

### ECS (Elastic Common Schema)

Formato JSON estándar de logs de Elastic. Spring Boot lo emite de forma nativa con `logging.structured.format.console=ecs` (variable `LOGGING_STRUCTURED_FORMAT_CONSOLE`); en local se deja la consola legible. Es el primer paso de OBS-4; el `traceId` llegará con las trazas (D-7).

### Elasticsearch (ES)

Motor de búsqueda e indexación. Almacena histórico de sincronizaciones. Port: `HistoryIndexerPort`.

### Estados de entrada (`ENTRY_STATES`)

Los cuatro puntos reales por los que arranca un pipeline y por los que `beginCycle` puede abrir ciclo: `RECEIVED` (agregado), `VALIDATING` (línea de feature), `SENDING_SAP` (baja), `INDEXING` (indexación). Sustituyen a los antiguos «estados iniciales», que solo admitían dos y por eso la baja y la indexación nunca se ejecutaban.

### Event Mesh / Advanced Event Mesh

Broker de eventos de SAP BTP por el que se distribuyen los Business Events de S/4 hacia consumidores externos (webhook o AMQP). Candidato a transporte del patrón 5 de [`INTEGRATION-PATTERNS.md`](architecture/INTEGRATION-PATTERNS.md).

## F

### Feature (spec SDD)

Unidad de trabajo del proyecto y unidad de documentación: un fichero en `docs/sdd/<subproyecto>/<nombre>.md`. No confundir con **Feature (subconjunto)**, que es el enum `CustomerFeature` del dominio. Su ciclo de vida se registra en [`sdd_registry`](sdd/README.md#8-registro-de-features-mysql).

### Feature (subconjunto)

En el dominio `customer`, cada parte del aggregate que puede sincronizarse de forma independiente: `ADDRESS`, `FISCAL`, `CONTACT`, `BANKING`. Ver [`OVERVIEW.md`](architecture/OVERVIEW.md#2-vista-de-dominios-aggregate--features).

### `feature_evento` / `sdd_registry`

Base de datos MySQL (contenedor `mysql-sdd`) donde se registra la información ampliada de cada feature solicitada — quién la pidió, cuándo, en qué estado — y su ciclo de vida: un evento `ALTA`, `MODIFICACION` o `BAJA` por cada cambio. **No es una base de datos de la aplicación**: ningún módulo del reactor se conecta a ella. Ver [`sdd/README.md`](sdd/README.md#8-registro-de-features-mysql).

### Fingerprint (incidencias)

Identificador estable de la *causa raíz* de un defecto, no de su síntoma, para reconocer recurrencias. El primero del proyecto es `sync-state:reentrada-no-permitida`: tres arreglos «fila a fila» de la máquina de estados que fallaron igual (`IllegalStateException` → 3 reintentos → DLT) hasta el rediseño de raíz. Convención propuesta en `docs/incidencias/`.

## H

### Hexagonal Architecture

Arquitectura de puertos y adaptadores. El dominio está en el centro; los adaptadores conectan con el exterior. Ver [`TECH.md`](architecture/TECH.md#4-arquitectura-por-dominio-capas-por-paquete).

## I

### IBAN

*International Bank Account Number*: identificador de cuenta (país + dígitos de control + cuenta). En S/4 va en `A_BusinessPartnerBank.IBAN` y en `SEPAMandate.SenderIBAN`; sus dos primeros caracteres dan `BankCountryKey`. Es PII bancaria.

### Idempotencia

Propiedad que garantiza que reintentar una operación no produce efectos duplicados. En este proyecto se logra con hash de payload + identificador de entidad. Ver [`OVERVIEW.md`](architecture/OVERVIEW.md#9-requisitos-no-funcionales).

### IAS (Identity Authentication Service)

Servicio de autenticación de SAP BTP, alternativa a XSUAA.

### iFlow / Integration Suite

Integration Suite es el iPaaS de SAP BTP; un *iFlow* es un flujo de integración configurado en él (mapeos, orquestación, planificación). Alternativa a una app CAP como intermediario del patrón 2 y como iniciador del patrón 3 de [`INTEGRATION-PATTERNS.md`](architecture/INTEGRATION-PATTERNS.md).

### Imagen (staging) vs histórico

**Imagen** (`customers_current` / `articles_current`, Mongo): lo último que SAP **aceptó**; se guarda solo cuando el ciclo termina en `SENT_SAP`. **Histórico** (`customers_history` / `articles_history`, Elasticsearch): lo que se **intentó enviar**, un documento por intento (id `entityId-hash-epochMillis`). La diferencia entre ambos es lo que SAP no tiene todavía. Ver [`idempotencia-y-dedupe.md`](sdd/common/idempotencia-y-dedupe.md).

### Imagen por digest

Referencia a una imagen Docker por su hash de contenido (`repo:tag@sha256:...`) además de la etiqueta. La etiqueta documenta la versión; el digest garantiza que es exactamente la misma imagen en todos los equipos (una etiqueta como `2022-latest` puede cambiar de contenido). Aplicado a las 11 imágenes de `external-services/docker-compose.yml`.

## J

### JaCoCo / umbral de cobertura

Herramienta de cobertura de tests. El parent Maven declara `prepare-agent`, `report` y **`check`** en `verify`: el build falla si `**/domain/**` baja del **75 % de líneas** (suelo medido el 12-09-2026: common 90 %, customer 78 %, article 88 %). El umbral solo se mueve hacia arriba. Informe en `<módulo>/target/site/jacoco/`.

### JCo (SAP Java Connector)

Librería Java para conectividad RFC/BAPI con sistemas SAP.

## K

### Kafka

Plataforma de eventos. Recibe mensajes CDC (`outbox.<DOMINIO>`) y eventos directos (`events.<DOMINIO>`).

### Kafka Connect

Marco de conectores de Kafka en el que corre Debezium (`kafka-connect`, puerto 8083). Los conectores se registran por REST (`scripts/start-all.sh --with-cdc`). Alternativa evaluable: Debezium Server sin Connect (decisión D-3, [ADR-0006](architecture/adr/0006-kafka-connect-debezium-como-cdc.md)).

## L

### Línea de estado por feature

Historia de estados propia de cada feature de una entidad, con clave `<entityId>:<FEATURE>` (p. ej. `CUST-001:ADDRESS`), independiente de la del agregado. Entra por `VALIDATING` en vez de por `RECEIVED`, porque el pipeline por feature valida y envía sin indexar. Ver [`maquina-de-estados.md`](sdd/common/maquina-de-estados.md).

## M

### Mandato SEPA (`SEPAMandate`, `Creditor`)

Autorización de un deudor para que un acreedor le domicilie cobros. En S/4 vive en `API_APAR_SEPA_MANDATE_SRV` con clave compuesta **`(Creditor, SEPAMandate)`**: `Creditor` es el *Creditor Identification Number* de la empresa (configuración `sap.sepa.creditor-id`, obligatorio) y `SEPAMandate` la referencia única del mandato. Estados (`SEPAMandateStatus`): 0 introducido, 1 activo, 2 bloqueado, 3 cancelado, 4 completado. Un mandato no se borra: se cancela. Ver [`sincronizacion-datos-bancarios.md`](sdd/customer/sincronizacion-datos-bancarios.md) y [`baja-mandato-sepa.md`](sdd/customer/baja-mandato-sepa.md).

### Maven Reactor

Build multi-módulo de Maven que compila `common`, `customer`, `article`, `supplier` e `it` en orden de dependencias. Ver [`TECH.md`](architecture/TECH.md#2-build).

### Maven wrapper (`mvnw`)

Scripts `mvnw` / `mvnw.cmd` y `.mvn/wrapper/` que descargan y usan la versión de Maven fijada por el repo (3.9.9), de modo que todos los equipos y la CI compilan igual. `maven-enforcer` además rechaza Maven < 3.9 y JDK < 25.

### MCP (Model Context Protocol)

Protocolo abierto para que un agente de IA consulte herramientas y datos de un sistema. En este proyecto es una **propuesta** de servidor de solo lectura sobre el estado y el histórico ([`tools-integrations/MCP.md`](tools-integrations/MCP.md), PRD-7), condicionada a autenticación (SEC-1) y ofuscación de PII (SEC-3).

### Micrometer

Librería de métricas. Exposición Prometheus en `/actuator/prometheus`.

### Modelo de bloqueo

Decisión del 2026-09-11 para la baja de cliente: la ficha local pasa a `status = BLOCKED` en lugar de eliminarse, y el histórico y el estado se conservan. Encaja con la obligación de conservar por motivos fiscales y mercantiles y con el propósito de auditoría del histórico. Ver [`baja-cliente.md`](sdd/customer/baja-cliente.md).

### MongoDB

Base de datos NoSQL para almacenar imagen actual de la entidad y estado de sincronización. Port: `SyncStateRepositoryPort` / `ImageStorePort`.

### Multitenancy

Capacidad de atender a múltiples tenants. El SDK gestiona tenant/principal mediante `ThreadContext`. Ver [Thread Context — Cloud SDK](https://sap.github.io/cloud-sdk/docs/java/features/multi-tenancy/thread-context).

## O

### OData

Estándar construido **encima de REST** que fija por contrato lo que REST deja abierto: filtrado (`$filter`), selección (`$select`), paginación, navegación entre entidades, `$batch` y metadatos (`$metadata`). Es el protocolo de las APIs públicas de S/4HANA. Explicación completa (REST vs OData, con ejemplos) en [`SAP_CLOUD_SDK.md` § OData vs REST](tools-integrations/SAP_CLOUD_SDK.md#odata-vs-rest-y-odata-v2-vs-v4).

### OData V2 vs V4

Dos versiones del estándar con formato distinto: **V2** envuelve las respuestas en `{"d":...}` (y `d.results` en listas), pagina con `$skip` y exige fetch de token CSRF en escrituras; **V4** devuelve la entidad en la raíz, usa `value` + `@odata.nextLink` y no usa el CSRF clásico (OAuth2 puro). Las APIs `API_*` del proyecto son V2; las `CE_*` (bancos, activos fijos, números de serie) son V4. Detalle y ejemplos en [`SAP_CLOUD_SDK.md` § OData V2 vs V4](tools-integrations/SAP_CLOUD_SDK.md#odata-v2-vs-v4); versión de cada API en el [catálogo](sdd/sap-api-catalog.md#catálogo).

### OpenAPI

Especificación estándar para APIs REST. SAP publica especificaciones OpenAPI en API Business Hub. Se generan clientes Java con el plugin de Cloud SDK. Ver [`SAP_CLOUD_SDK.md`](tools-integrations/SAP_CLOUD_SDK.md#2-apis-rest-propias-de-sap--callbacks--openapi).

### OpenTelemetry (OTel)

Estándar de observabilidad para trazas distribuidas. Se integra con el **javaagent** en el arranque de la JVM (el starter Spring de OTel 2.x no soporta Boot 4). Ver [`TECH.md`](architecture/TECH.md#9-observabilidad).

### OTLP

*OpenTelemetry Protocol*: protocolo con el que las apps exportan trazas y métricas a un colector (`OTEL_EXPORTER_OTLP_ENDPOINT`). Sin colector ni starter decidido (D-7) no hay trazas distribuidas (OBS-2).

### Outbox Pattern

Patrón que escribe eventos en una tabla outbox transaccionalmente con el cambio de negocio; Debezium lee la outbox y publica en Kafka.

## P

### Parada ordenada (graceful shutdown)

`server.shutdown=graceful`: al detener la app se deja de aceptar trabajo nuevo y se espera (hasta `spring.lifecycle.timeout-per-shutdown-phase`, 30 s) a que terminen las peticiones HTTP y los mensajes Kafka en curso, para no dejar entidades en estados en vuelo.

### payloadHash

Hash del payload del evento de ingesta; clave del dedupe de idempotencia: si ya existe una transición `SENT_SAP` de la entidad con ese hash (`SyncStateRepositoryPort.alreadySent`), el mensaje se descarta sin reprocesar. Viaja también como cabecera `Idempotency-Key`.

### PII (información personal identificable)

Datos que identifican a una persona: NIF, IBAN, email, teléfono, dirección. Viven en el legacy, en la imagen (Mongo), en el histórico (ES, snapshot íntegro por decisión explícita) y en los topics Kafka. Condicionan retención, borrado (modelo de bloqueo), enmascarado en logs (SEC-3) y autenticación de las APIs (B4).

### Pipeline agregado vs pipeline por feature

Dos recorridos sobre la misma máquina de estados. El **agregado** cubre la entidad entera: `RECEIVED → FETCHING → VALIDATING → VALID → INDEXING → INDEXED → SENDING_SAP → SENT_SAP`. El **por feature** solo valida y envía: `VALIDATING → VALID → SENDING_SAP → SENT_SAP`; no indexa, porque la imagen y el histórico son del agregado.

### Port

Interfaz Java en el dominio que define una capacidad externa sin depender de infraestructura. Ver [`TECH.md`](architecture/TECH.md#5-puertos-y-adaptadores).

### Presupuesto de reintentos por mensaje

Peor caso de tiempo que un mensaje Kafka puede pasar en proceso: `llamadas a SAP por mensaje × (intentos × timeout de respuesta + backoff)`. Con los defaults del proyecto, 5 × (3 × 20 s + 1,5 s) = 5,1 min. Debe ser menor que `max.poll.interval.ms` (15 min aquí, `KAFKA_MAX_POLL_INTERVAL_MS`); si no, Kafka expulsa al consumidor mientras aún procesa y rebalancea en cascada. `RetryBudgetGuard` lo comprueba al arrancar (auditoría A10). Ver [`observabilidad.md`](sdd/common/observabilidad.md).

### Prometheus

Sistema de métricas y alertas. Actuator lo expone en `/actuator/prometheus`.

### Published Language (lenguaje publicado)

En DDD, el contrato compartido con el que dos contextos se hablan. Aquí son los contratos SAP (specs OpenAPI oficiales en `sap-api-models`) y el contrato BTP propuesto (`/sap/btp/odata/*`, [ADR-0004](architecture/adr/0004-dos-familias-de-adaptadores-btp-y-odata.md)); los DTOs los traducen desde el dominio.

### Pull (integración)

**Propuesta, no implementada.** Modo de integración donde SAP BTP iniciaría el ciclo: preguntar pendientes (`GET /btp/pending`), procesar en S/4HANA, y notificar resultado (`POST /btp/result`). Ni los endpoints ni el estado asociado existen en el código actual, y la propiedad `sap.integration.mode` está declarada pero no la lee nadie. Ver [`MEJORAS-Y-PROPUESTAS.md`](MEJORAS-Y-PROPUESTAS.md) PRD-4.

### Push (integración)

Modo de integración donde nuestra app empuja datos a SAP activamente vía `SapClient.send/patch()`. Puede ir por BTP o por API OData directa según el adaptador activo (`sap.odata.<feature>.enabled`). Es **el único modo implementado**. Ver [`FLOWS.md`](architecture/FLOWS.md).

## R

### Re-entrada (re-sincronización)

Reabrir el ciclo de una entidad ya procesada cuando llega un evento nuevo. Desde la Fase 1 de la auditoría es una operación propia, **apertura de ciclo** (`beginCycle`), legal desde cualquier estado — cerrado, de error o en vuelo — y no una fila más de la tabla de transiciones. Cada pipeline re-entra por su estado de entrada. La idempotencia la garantiza el dedupe por `payloadHash`, no el bloqueo de la máquina.

### Registro de features

Ver **`feature_evento` / `sdd_registry`**.

### Resilience4j

Librería de resiliencia (circuit breaker, retry, rate limiter) usada en los clientes SAP actuales. Ver [`TECH.md`](architecture/TECH.md#8-clientes-sap).

### `RestClient` (Spring)

Cliente HTTP **síncrono** de Spring Framework 6.1+, con la API fluida de `WebClient` pero sin Reactor. Es el transporte hacia SAP desde [ADR-0001](architecture/adr/0001-transporte-http-sap-restclient.md) (`RestClientSapClient`, sobre el `HttpClient` del JDK: PATCH nativo, timeouts de conexión y lectura). Ver spec [`resiliencia-cliente-sap.md`](sdd/common/resiliencia-cliente-sap.md).

### Retry

Reintentos con backoff exponencial ante fallos transitorios. Ver [`OVERVIEW.md`](architecture/OVERVIEW.md#9-requisitos-no-funcionales).

### RFC (Remote Function Call)

Protocolo de SAP para llamar a funciones remotas, incluidas BAPIs.

## S

### S/4HANA

Suite ERP de SAP. En este proyecto se sincronizan datos maestros con **SAP S/4HANA Public Cloud**.

### Saga / compensación

Patrón para mantener consistencia entre varias operaciones sin transacción distribuida: si una falla, se **compensan** las anteriores. Aplica al cliente con cuatro features enviadas por separado (OPS-5, decisión D-2: Spring Modulith, saga propia o ninguna).

### SAP Cloud SDK for Java

SDK oficial de SAP para conectividad, generación de clientes tipados (VDM) y operaciones en BTP/SAP. En este proyecto **no está en el runtime**: [ADR-0001](architecture/adr/0001-transporte-http-sap-restclient.md) eligió `RestClient` mientras el SDK no soporte Spring Boot 4; solo se usa su generador de modelos OpenAPI en `sap-api-models`. Guía de la opción aparcada: [`SAP_CLOUD_SDK.md`](tools-integrations/SAP_CLOUD_SDK.md).

### `SapCircuitOpenException`

El circuit breaker hacia SAP está abierto y la llamada no se ha intentado. Antes se tragaba como `SapResponse(0)` y el use case marcaba `SAP_ERROR` sin reintento ni señal (auditoría B13). Ahora se propaga como fallo transitorio para que la ingesta reintente con backoff.

### SBOM (CycloneDX)

*Software Bill of Materials*: inventario de todos los componentes (dependencias con versión y licencia) que forman una versión del software, en formato CycloneDX. Lo genera `cyclonedx-maven-plugin` en `package` (`target/bom.json`) y la CI lo publica como artefacto. Sirve para responder «¿nos afecta esta vulnerabilidad?» sin abrir el código.

### SDD (Spec-Driven Development)

Método de trabajo del repositorio: cada feature tiene un spec que define su comportamiento esperado, y spec y código **no pueden divergir**. Si cambia uno, cambia el otro en el mismo PR. Ver [`sdd/README.md`](sdd/README.md) §1.

### SDD anchor (ancla)

La regla bidireccional que sostiene el SDD: un cambio de comportamiento sin spec actualizado está incompleto, y un spec cambiado sin tests que lo respalden también. Del lado del código el ancla son las citas `AC-n` en el Javadoc de los tests.

### Secuencia de estado (`seq`)

Número monótono por entidad que lleva cada transición en `sync_state`. Decide cuál es el estado actual (antes se ordenaba por `timestamp` en milisegundos, y las transiciones en ráfaga empataban) y, con el índice único `dom_ent_seq_uk`, actúa como **versión optimista** entre instancias. Los documentos anteriores a su introducción no lo tienen; por eso el índice es **parcial** (`seq` existe), no `sparse`: un índice compuesto `sparse` indexa el documento si tiene *al menos una* clave, y todos los antiguos colisionaban en `seq = null`.

### SEPA (Single Euro Payments Area)

Zona única de pagos en euros: transferencias y **adeudos domiciliados** con reglas comunes. De aquí salen el IBAN, el BIC y el mandato SEPA (autorización del deudor) que S/4 gestiona en `API_APAR_SEPA_MANDATE_SRV`. Ver *Mandato SEPA*.

### Shared Kernel

Módulo `common` con primitivas de dominio, máquina de estados, clientes SAP y soporte de test compartidos por todos los dominios. Ver [`OVERVIEW.md`](architecture/OVERVIEW.md#1-vista-de-módulos-reactor-maven).

### «Sin cambios reales» (corta-circuito)

Optimización del pipeline agregado: si el ciclo anterior terminó en `SENT_SAP` y el snapshot re-leído del legacy es idéntico a la **imagen** (que desde la Fase 6 es exactamente lo que SAP aceptó), no se reenvía a SAP y se transiciona `VALID → SENT_SAP` directamente. Ese `SENT_SAP` significa «SAP está en sincronía con este payload». Ver [`idempotencia-y-dedupe.md`](sdd/common/idempotencia-y-dedupe.md) R-3.

### Slice Test

Test de una capa aislada (p. ej. REST controller) sin levantar todo el contexto Spring Boot. En Spring Boot 4.0 se hace con `MockMvcBuilders.standaloneSetup`. Ver [`TESTING.md`](testing/TESTING.md#2-tipos-de-tests).

### SMT (Single Message Transform)

Transformación por mensaje aplicada en Kafka Connect. Debezium ofrece el *Event Router* oficial para el patrón outbox; hoy la outbox se rellena por triggers y no se usa el SMT (decisión D-3).

### State Machine

Máquina de estados de sincronización (`common/domain/SyncStateMachine.java`). **Fuente única**: [`maquina-de-estados.md`](sdd/common/maquina-de-estados.md) §6 (tabla de `advance`) y §4 (`beginCycle`: un evento nuevo abre ciclo desde cualquier estado por uno de los cuatro estados de entrada). Las demás descripciones (OVERVIEW §5, AGENTS, MAPA-FUNCIONAL) son resúmenes y enlazan a ella.
Estados de error: `ERROR`, `SAP_ERROR` y `COMMUNICATION_ERROR`. Re-entrada: `SENT_SAP → RECEIVED` e `INVALID → RECEIVED` cuando llega un nuevo evento de la entidad. Ver [`OVERVIEW.md`](architecture/OVERVIEW.md#5-máquina-de-estados-de-sincronización).

### Surefire / Failsafe

Plugins Maven que ejecutan tests: surefire en `test` (`*Test`), failsafe en `integration-test`/`verify` (`*IT`, y en el módulo `it` también `*ContractTest`). Ambos fijados a 3.5.3 en el `pluginManagement` del parent tras la auditoría B7: sin versión ni failsafe, `SyncCustomerControllerIT` no se ejecutó nunca.

## T

### TDD (Test-Driven Development)

Técnica obligatoria en el repositorio: ningún código de producción se escribe sin un test que **falle antes**, y el ciclo es rojo → verde → refactor, de dentro afuera (`domain` → `application` → `adapters` → `bootstrap`). Ver [`DESARROLLO.md`](architecture/DESARROLLO.md).

### Testcontainers

Librería para levantar contenedores Docker en tests de integración. Ver [`TESTING.md`](testing/TESTING.md).

### Tests declarados vs ejecutados

**Declarados**: métodos `@Test` en `src/test/java` de todos los módulos, gateados o no. **Ejecutados**: los que corren en un `mvn` concreto (sin Docker se saltan los `*IT`). La cifra de [`TESTING.md`](testing/TESTING.md) §1 es la de declarados y la vigila `TestCountMatchesDocsTest`: el build falla si un documento se queda atrás.

### Thread Context

Contexto de ejecución del SDK que propaga tenant/principal. Requiere cuidado con operaciones `@Async`. Ver [Thread Context — Cloud SDK](https://sap.github.io/cloud-sdk/docs/java/features/multi-tenancy/thread-context).

### Token stub (`sap.auth.allow-stub`)

Credencial falsa (`stub-btp-token` / `stub-s4-token`) que los `SapAuthProvider` emiten **solo** si `sap.auth.allow-stub=true` (`SAP_AUTH_ALLOW_STUB`) y faltan credenciales reales; sirve únicamente contra el SAP simulado. Con el valor por defecto (`false`) la app no arranca sin credenciales, en vez de fallar con `401` en la primera llamada (auditoría A8). Ver [`autenticacion-sap.md`](sdd/common/autenticacion-sap.md).

## V

### VDM (Virtual Data Model)

Modelo de datos tipado generado por SAP Cloud SDK a partir de metadatos OData de S/4HANA. Ver [`SAP_CLOUD_SDK.md`](tools-integrations/SAP_CLOUD_SDK.md#1-business-partner--odata-vdm).

### Virtual Threads

Hilos ligeros de Java 21+. El proyecto los usa para concurrencia de Kafka/REST. Ver [`TECH.md`](architecture/TECH.md#1-plataforma).

## W

### WireMock

Herramienta para simular servidores HTTP en tests de contrato SAP. Ver [`TESTING.md`](testing/TESTING.md#2-tipos-de-tests).

## X

### XSUAA

Servicio de autorización y autenticación de SAP BTP (OAuth2). Usado junto al Destination Service para consumir APIs protegidas.
