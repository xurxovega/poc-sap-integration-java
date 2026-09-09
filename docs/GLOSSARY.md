# Glosario

> Términos usados en el proyecto `poc-sap-integration-java`.
> Los enlaces internos apuntan a documentos del repo; los externos a recursos oficiales.

## A

### Adapter (Adaptador)

Implementación de un **port** en la capa de infraestructura. Traduce entre el dominio y tecnologías externas (Kafka, MongoDB, SAP, Elasticsearch). Ver [`TECH.md`](architecture/TECH.md#5-puertos-y-adaptadores).

### API Business Hub

Portal oficial de SAP para descubrir APIs y especificaciones OData/OpenAPI de SAP. [api.sap.com](https://api.sap.com/)

## B

### BAPI (Business Application Programming Interface)

Interfaz estándar de SAP para acceder a procesos de negocio. En Java se invoca vía JCo/RFC. Ver [BAPI/RFC en SAP Cloud SDK](https://sap.github.io/cloud-sdk/docs/java/features/bapi-and-rfc/overview).

### BTP (Business Technology Platform)

Plataforma cloud de SAP. En este proyecto se usa para desplegar apps y resolver destinos/autenticación vía Destination Service y XSUAA/IAS.

### `$batch` (OData) / changeset

Endpoint OData que agrupa varias operaciones en una sola request HTTP; cada *changeset* interno es atómico (todo o nada). Previsto para las cargas batch masivas (patrón 4 de [`INTEGRATION-PATTERNS.md`](architecture/INTEGRATION-PATTERNS.md)); aún sin soporte en `SapClient`.

### Business Events

Eventos de negocio que S/4HANA Public Cloud publica cuando algo ocurre dentro de SAP (p. ej. movimiento de mercancía, cambio de stock). Base del patrón 5 (SAP → plataforma) de [`INTEGRATION-PATTERNS.md`](architecture/INTEGRATION-PATTERNS.md); pendiente de decisión del equipo SAP.

### Business Partner

Entidad maestra de SAP S/git push -u origin feature/saneamiento-integracion-sap4HANA que agrupa datos de cliente, proveedor y socio. En el dominio `customer` se sincroniza mediante OData VDM. Ver [`SAP_CLOUD_SDK.md`](tools-integrations/SAP_CLOUD_SDK.md#1-business-partner--odata-vdm).

## C

### Callback

Endpoint REST propio que recibe notificaciones de SAP. Se modela como entrada alternativa en el puerto `IngestionPort`. Ver [`TECH.md`](architecture/TECH.md#6-entradas).

### CDC (Change Data Capture)

Captura de cambios en bases de datos legacy. En este proyecto: triggers → tabla outbox → Debezium → Kafka. Ver [`TECH.md`](architecture/TECH.md#6-entradas).

### Circuit Breaker

Patrón de resiliencia que abre el circuito tras fallos consecutivos para evitar sobrecargar el sistema downstream. Implementado con **Resilience4j**. Ver [`TECH.md`](architecture/TECH.md#8-clientes-sap).

### Cloud Connector

Componente de SAP BTP que permite conectividad segura desde BTP hacia sistemas on-premise.

### Communication arrangement / communication user

Configuración en S/4HANA Public Cloud que habilita un escenario de API (p. ej. `SAP_COM_0008` para Business Partner) y el usuario técnico con el que se autentican las llamadas entrantes.

### Criterio de aceptación (AC-n)

Regla verificable de un spec SDD, numerada `AC-1`, `AC-2`… Cada una debe tener **al menos un test que la cite** en su Javadoc: es la mitad del ancla vista desde el código. Ver [`DESARROLLO.md`](architecture/DESARROLLO.md).

## D

### Debezium

Plataforma CDC de código abierto. Captura cambios del legacy y los publica en Kafka.

### Destination Service

Servicio de SAP BTP que centraliza URL, autenticación y propiedades de conexión a sistemas SAP. Ver [Destination Service — Cloud SDK](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/destination-service).

### DDD (Domain-Driven Design)

Enfoque de diseño centrado en el dominio. En este proyecto se aplica mediante bounded contexts (`customer`, `article`, `supplier`) y capas por paquete.

### DLT (Dead Letter Topic)

Topic `<original>.DLT` al que el `DefaultErrorHandler` publica un mensaje que sigue fallando tras agotar los reintentos con backoff, para inspección o reproceso manual. Ver [`TECH.md`](architecture/TECH.md#6-entradas).

## E

### Elasticsearch (ES)

Motor de búsqueda e indexación. Almacena histórico de sincronizaciones. Port: `HistoryIndexerPort`.

### Event Mesh / Advanced Event Mesh

Broker de eventos de SAP BTP por el que se distribuyen los Business Events de S/4 hacia consumidores externos (webhook o AMQP). Candidato a transporte del patrón 5 de [`INTEGRATION-PATTERNS.md`](architecture/INTEGRATION-PATTERNS.md).

## F

### Feature (spec SDD)

Unidad de trabajo del proyecto y unidad de documentación: un fichero en `docs/sdd/<subproyecto>/<nombre>.md`. No confundir con **Feature (subconjunto)**, que es el enum `CustomerFeature` del dominio. Su ciclo de vida se registra en [`sdd_registry`](sdd/README.md#8-registro-de-features-mysql).

### Feature (subconjunto)

En el dominio `customer`, cada parte del aggregate que puede sincronizarse de forma independiente: `ADDRESS`, `FISCAL`, `CONTACT`, `BANKING`. Ver [`OVERVIEW.md`](architecture/OVERVIEW.md#2-vista-de-dominios-aggregate--features).

### `feature_evento` / `sdd_registry`

Base de datos MySQL (contenedor `mysql-sdd`) donde se registra la información ampliada de cada feature solicitada — quién la pidió, cuándo, en qué estado — y su ciclo de vida: un evento `ALTA`, `MODIFICACION` o `BAJA` por cada cambio. **No es una base de datos de la aplicación**: ningún módulo del reactor se conecta a ella. Ver [`sdd/README.md`](sdd/README.md#8-registro-de-features-mysql).

## H

### Hexagonal Architecture

Arquitectura de puertos y adaptadores. El dominio está en el centro; los adaptadores conectan con el exterior. Ver [`TECH.md`](architecture/TECH.md#4-arquitectura-por-dominio-capas-por-paquete).

## I

### Idempotencia

Propiedad que garantiza que reintentar una operación no produce efectos duplicados. En este proyecto se logra con hash de payload + identificador de entidad. Ver [`OVERVIEW.md`](architecture/OVERVIEW.md#9-requisitos-no-funcionales).

### IAS (Identity Authentication Service)

Servicio de autenticación de SAP BTP, alternativa a XSUAA.

### iFlow / Integration Suite

Integration Suite es el iPaaS de SAP BTP; un *iFlow* es un flujo de integración configurado en él (mapeos, orquestación, planificación). Alternativa a una app CAP como intermediario del patrón 2 y como iniciador del patrón 3 de [`INTEGRATION-PATTERNS.md`](architecture/INTEGRATION-PATTERNS.md).

## J

### JCo (SAP Java Connector)

Librería Java para conectividad RFC/BAPI con sistemas SAP.

## K

### Kafka

Plataforma de eventos. Recibe mensajes CDC (`outbox.<DOMINIO>`) y eventos directos (`events.<DOMINIO>`).

## L

### Línea de estado por feature

Historia de estados propia de cada feature de una entidad, con clave `<entityId>:<FEATURE>` (p. ej. `CUST-001:ADDRESS`), independiente de la del agregado. Entra por `VALIDATING` en vez de por `RECEIVED`, porque el pipeline por feature valida y envía sin indexar. Ver [`maquina-de-estados.md`](sdd/common/maquina-de-estados.md).

## M

### Maven Reactor

Build multi-módulo de Maven que compila `common`, `customer`, `article`, `supplier` e `it` en orden de dependencias. Ver [`TECH.md`](architecture/TECH.md#2-build).

### Micrometer

Librería de métricas. Exposición Prometheus en `/actuator/prometheus`.

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

### Outbox Pattern

Patrón que escribe eventos en una tabla outbox transaccionalmente con el cambio de negocio; Debezium lee la outbox y publica en Kafka.

## P

### payloadHash

Hash del payload del evento de ingesta; clave del dedupe de idempotencia: si ya existe una transición `SENT_SAP` de la entidad con ese hash (`SyncStateRepositoryPort.alreadySent`), el mensaje se descarta sin reprocesar. Viaja también como cabecera `Idempotency-Key`.

### Pipeline agregado vs pipeline por feature

Dos recorridos sobre la misma máquina de estados. El **agregado** cubre la entidad entera: `RECEIVED → FETCHING → VALIDATING → VALID → INDEXING → INDEXED → SENDING_SAP → SENT_SAP`. El **por feature** solo valida y envía: `VALIDATING → VALID → SENDING_SAP → SENT_SAP`; no indexa, porque la imagen y el histórico son del agregado.

### Port

Interfaz Java en el dominio que define una capacidad externa sin depender de infraestructura. Ver [`TECH.md`](architecture/TECH.md#5-puertos-y-adaptadores).

### Prometheus

Sistema de métricas y alertas. Actuator lo expone en `/actuator/prometheus`.

### Pull (integración)

**Propuesta, no implementada.** Modo de integración donde SAP BTP iniciaría el ciclo: preguntar pendientes (`GET /btp/pending`), procesar en S/4HANA, y notificar resultado (`POST /btp/result`). Ni los endpoints ni el estado asociado existen en el código actual, y la propiedad `sap.integration.mode` está declarada pero no la lee nadie. Ver [`MEJORAS-Y-PROPUESTAS.md`](MEJORAS-Y-PROPUESTAS.md) PRD-4.

### Push (integración)

Modo de integración donde nuestra app empuja datos a SAP activamente vía `SapClient.send/patch()`. Puede ir por BTP o por API OData directa según el adaptador activo (`sap.odata.<feature>.enabled`). Es **el único modo implementado**. Ver [`FLOWS.md`](architecture/FLOWS.md).

## R

### Re-entrada (re-sincronización)

Capacidad de reabrir el ciclo de una entidad ya procesada cuando llega un evento nuevo. Cada pipeline vuelve por su estado de entrada: el agregado a `RECEIVED`, una línea de feature a `VALIDATING`. Los estados que la admiten son `SENT_SAP`, `INVALID` y `SAP_ERROR`. La idempotencia la garantiza el dedupe por `payloadHash`, no el bloqueo de la máquina.

### Registro de features

Ver **`feature_evento` / `sdd_registry`**.

### Resilience4j

Librería de resiliencia (circuit breaker, retry, rate limiter) usada en los clientes SAP actuales. Ver [`TECH.md`](architecture/TECH.md#8-clientes-sap).

### Retry

Reintentos con backoff exponencial ante fallos transitorios. Ver [`OVERVIEW.md`](architecture/OVERVIEW.md#9-requisitos-no-funcionales).

### RFC (Remote Function Call)

Protocolo de SAP para llamar a funciones remotas, incluidas BAPIs.

## S

### S/4HANA

Suite ERP de SAP. En este proyecto se sincronizan datos maestros con **SAP S/4HANA Public Cloud**.

### SAP Cloud SDK for Java

SDK oficial de SAP para conectividad, generación de clientes y operaciones en BTP/SAP. Ver [`SAP_CLOUD_SDK.md`](tools-integrations/SAP_CLOUD_SDK.md).

### SDD (Spec-Driven Development)

Método de trabajo del repositorio: cada feature tiene un spec que define su comportamiento esperado, y spec y código **no pueden divergir**. Si cambia uno, cambia el otro en el mismo PR. Ver [`sdd/README.md`](sdd/README.md) §1.

### SDD anchor (ancla)

La regla bidireccional que sostiene el SDD: un cambio de comportamiento sin spec actualizado está incompleto, y un spec cambiado sin tests que lo respalden también. Del lado del código el ancla son las citas `AC-n` en el Javadoc de los tests.

### Shared Kernel

Módulo `common` con primitivas de dominio, máquina de estados, clientes SAP y soporte de test compartidos por todos los dominios. Ver [`OVERVIEW.md`](architecture/OVERVIEW.md#1-vista-de-módulos-reactor-maven).

### «Sin cambios reales» (corta-circuito)

Optimización del pipeline agregado: si el ciclo anterior terminó en `SENT_SAP` y el snapshot re-leído del legacy es idéntico a la imagen almacenada, no se reenvía a SAP y se transiciona `VALID → SENT_SAP` directamente. Evita tráfico y coste por eventos que no cambian datos sincronizados.

### Slice Test

Test de una capa aislada (p. ej. REST controller) sin levantar todo el contexto Spring Boot. En Spring Boot 4.0 se hace con `MockMvcBuilders.standaloneSetup`. Ver [`TESTING.md`](testing/TESTING.md#2-tipos-de-tests).

### State Machine

Máquina de estados de sincronización (`common/domain/SyncStateMachine.java`):
`RECEIVED → FETCHING → VALIDATING → VALID | INVALID`; `VALID → INDEXING → INDEXED → SENDING_SAP → SENT_SAP | SAP_ERROR`.
Estados de error: `ERROR`, `SAP_ERROR` y `COMMUNICATION_ERROR`. Re-entrada: `SENT_SAP → RECEIVED` e `INVALID → RECEIVED` cuando llega un nuevo evento de la entidad. Ver [`OVERVIEW.md`](architecture/OVERVIEW.md#5-máquina-de-estados-de-sincronización).

## T

### TDD (Test-Driven Development)

Técnica obligatoria en el repositorio: ningún código de producción se escribe sin un test que **falle antes**, y el ciclo es rojo → verde → refactor, de dentro afuera (`domain` → `application` → `adapters` → `bootstrap`). Ver [`DESARROLLO.md`](architecture/DESARROLLO.md).

### Testcontainers

Librería para levantar contenedores Docker en tests de integración. Ver [`TESTING.md`](testing/TESTING.md).

### Thread Context

Contexto de ejecución del SDK que propaga tenant/principal. Requiere cuidado con operaciones `@Async`. Ver [Thread Context — Cloud SDK](https://sap.github.io/cloud-sdk/docs/java/features/multi-tenancy/thread-context).

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
