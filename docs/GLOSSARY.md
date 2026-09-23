# Glosario

> Términos usados en el proyecto `poc-sap-integration-java`.
> Los enlaces internos apuntan a documentos del repo; los externos a recursos oficiales.

## A

### `412 Precondition Failed`

Respuesta de SAP a un `PATCH` cuyo `If-Match` ya no coincide: significa que el recurso **cambió desde nuestra lectura**, y obliga a releer antes de reintentar. No es un error de datos: es la señal de que otro proceso tocó el mismo Business Partner entremedias. Se permite un re-lookup y un segundo `PATCH`; si vuelve a fallar, `SAP_ERROR`. Ver [`upsert-idempotente-sap.md`](sdd/common/upsert-idempotente-sap.md) R-7.

### A2X (Application-to-Cross-Application)

Etiqueta de SAP para las APIs OData de S/4 Public Cloud pensadas para integrarse desde fuera con un communication user (`API_BUSINESS_PARTNER (A2X)`, `API_PRODUCT_SRV (A2X)`). Son las que consume la familia OData de adaptadores. Ver [`sdd/sap-api-catalog.md`](sdd/sap-api-catalog.md).

### `AddressID` / `RelationshipNumber`

Claves que **asigna SAP** a las subentidades de un Business Partner (la dirección y la relación con una persona de contacto). No son deducibles desde nuestros datos, así que hay que persistirlas de nuestro lado —colección `sap_keys`, no la imagen— o cada ciclo crea un duplicado en vez de actualizar. El `AddressID` es además la clave de las cuatro entidades de comunicación (email, teléfono, fax, web). Ver [`upsert-idempotente-sap.md`](sdd/common/upsert-idempotente-sap.md) R-4.

### Adapter (Adaptador)

Implementación de un **port** en la capa de infraestructura. Traduce entre el dominio y tecnologías externas (Kafka, MongoDB, SAP, Elasticsearch). Ver [`TECH.md`](architecture/TECH.md#5-puertos-y-adaptadores).

### ADR (Architecture Decision Record)

Documento corto que registra una decisión de arquitectura: contexto, opciones, decisión, consecuencias y **cuándo se reevalúa**. Viven en [`docs/architecture/adr/`](architecture/adr/README.md); el primero es ADR-0001 (transporte `RestClient` hacia SAP). No se editan para cambiar la decisión: se escribe otro que lo sustituye.

### Apertura de ciclo (`beginCycle`) / avance (`advance`)

Las dos intenciones de la máquina de estados, separadas desde la Fase 1 de la auditoría. **Abrir ciclo** es lo que ocurre cuando llega un evento: legal desde *cualquier* estado actual, por el estado de entrada del pipeline. **Avanzar** es moverse dentro del ciclo abierto y sigue la tabla de transiciones. Mientras fueron la misma operación, cada camino nuevo descubría «una fila que faltaba» y fallaba igual — tres veces. Ver [`maquina-de-estados.md`](sdd/common/maquina-de-estados.md) §3.

### API Business Hub

Portal oficial de SAP para descubrir APIs y especificaciones OData/OpenAPI de SAP. [api.sap.com](https://api.sap.com/)

### ArchUnit

Librería de tests que verifica reglas de arquitectura sobre el bytecode. En el repo, `DomainPurityTest` (common, customer, article) prohíbe que `..domain..` dependa de Spring, Jackson, Mongo, Kafka, Micrometer o JPA; `ApplicationPurityTest` hace lo propio con `..application..` y `EndpointsDeclareAccessTest` exige `@PreAuthorize` en cada endpoint. Con JUnit Platform 6 (Spring Boot 4) hay que usar el módulo `archunit-junit6` y surefire ≥ 3.6.0: con `archunit-junit5` las reglas no se ejecutaban (hallazgo del 2026-09-18). Ver [`TESTING.md`](testing/TESTING.md) §6.

### `aud` (audiencia)

Claim del token JWT que dice **para qué servicio** se emitió. Hoy el resource server (`ApiSecurityConfig`) solo valida `issuer-uri`: no hay ningún `OAuth2TokenValidator` de audiencia en el repo (hallazgo 2A-1). Sin validarlo, cualquier token del mismo emisor con un rol `sap-*` entra, aunque se haya emitido para otra aplicación. Ver [`SERVICIO-AUTENTICACION.md`](tools-integrations/SERVICIO-AUTENTICACION.md) y [ADR-0012](architecture/adr/0012-servicio-externo-de-autenticacion-idp.md).

## B

### `BusinessPartnerReadPort`

Puerto de lectura (GET/search OData) del Business Partner, implementado por `BusinessPartnerReadAdapter`. Se activa (`BusinessPartnerReadEnabled`) cuando `sap.odata.read.enabled=true` **o** cuando `sap.odata.customer.enabled=true`: el upsert idempotente por OData necesita el lector aunque nadie haya pedido consultas explícitas, así que un interruptor de "solo lectura" ya no puede ser el único que lo controla. Ver [`upsert-idempotente-sap.md`](sdd/common/upsert-idempotente-sap.md) §3.

### `BankIdentification` (A_BusinessPartnerBank)

Identificador **secuencial** de cada cuenta bancaria dentro de un Business Partner (`0001`, `0002`...). No es el BIC ni el código de banco: el BIC/SWIFT pertenece al maestro de bancos y al mandato SEPA (`SenderBankSWIFTCode`). El país del banco va en `BankCountryKey`, derivado del IBAN. Auditoría B3.

### BAPI (Business Application Programming Interface)

Interfaz estándar de SAP para acceder a procesos de negocio. En Java se invoca vía JCo/RFC. Ver [BAPI/RFC en SAP Cloud SDK](https://sap.github.io/cloud-sdk/docs/java/features/bapi-and-rfc/overview).

### BIC (SWIFT)

*Bank Identifier Code*: identificador del banco (8 u 11 caracteres). **No** es `BankIdentification` de `A_BusinessPartnerBank`; en S/4 pertenece al maestro de bancos y al mandato (`SenderBankSWIFTCode`). Auditoría B3.

### BTP (Business Technology Platform)

Plataforma cloud de SAP. En este proyecto, una de las tres vías hacia SAP (ver [`INTEGRATION-PATTERNS.md`](architecture/INTEGRATION-PATTERNS.md) "Las tres vías con SAP"): el servicio BTP intermedio existe y **se ha probado en el espacio del propietario**, pero no se ha integrado con esta aplicación — ni SAP llamando a nuestro servicio ni nuestro servicio llamando a SAP a través de BTP se ha probado extremo a extremo (D-10, [ADR-0004](architecture/adr/0004-dos-familias-de-adaptadores-btp-y-odata.md) §5). El lado plataforma (`Btp*Adapter`) sí está implementado y activo por defecto (riesgo abierto 2B-4, [`MEJORAS-Y-PROPUESTAS.md`](MEJORAS-Y-PROPUESTAS.md)).

### `$batch` (OData) / changeset

Endpoint OData que agrupa varias operaciones en una sola request HTTP; cada *changeset* interno es atómico (todo o nada). Previsto para las cargas batch masivas (patrón 4 de [`INTEGRATION-PATTERNS.md`](architecture/INTEGRATION-PATTERNS.md)); aún sin soporte en `SapClient`.

### Business Events

Eventos de negocio que S/4HANA Public Cloud publica cuando algo ocurre dentro de SAP (p. ej. movimiento de mercancía, cambio de stock). Base del patrón 5 (SAP → plataforma) de [`INTEGRATION-PATTERNS.md`](architecture/INTEGRATION-PATTERNS.md); pendiente de decisión del equipo SAP.

### Business Partner

Entidad maestra de SAP S/4HANA que agrupa datos de cliente, proveedor y socio. En el dominio `customer` se sincroniza contra la API OData `API_BUSINESS_PARTNER` (adaptadores `BusinessPartner*ODataAdapter`) o contra las APIs BTP (`Btp*Adapter`). Ver [`SAP_CLOUD_SDK.md`](tools-integrations/SAP_CLOUD_SDK.md#1-business-partner--odata-vdm).

## C

### Callback

Endpoint REST propio que recibiría notificaciones de SAP. Sería una entrada alternativa al mismo caso de uso que hoy alimentan CDC y REST síncrono (`IngestionMessage`); no hay puerto `IngestionPort` (borrado, auditoría A18) ni callback implementado. Ver [`TECH.md`](architecture/TECH.md#6-entradas).

### CDC (Change Data Capture)

Captura de cambios en bases de datos legacy. En este proyecto: triggers → tabla outbox → Debezium → Kafka. Ver [`TECH.md`](architecture/TECH.md#6-entradas).

### Cero confianza (estado del agregado)

Convención por la que un ciclo que no dejó a SAP exactamente como se pidió termina en `SAP_ERROR` aunque parte del envío haya ido bien, para obligar a revisión y reenvío en vez de afirmar una sincronía que no existe. Solo si **ninguna** parte llegó a llamar a SAP el agregado puede decir `INVALID`. Decisión D-14; ver [`sincronizacion-cliente.md`](sdd/customer/sincronizacion-cliente.md) R-7.

### Ciclo de sincronización (`cycleId`)

Identificador único del intento completo de llevar una entidad a SAP, compartido por la línea del agregado y las de sus features, y que es lo que permite reconstruir la **traza de pasos** de un envío con una sola consulta. Se genera al abrir ciclo y viaja en cada transición y en el aviso de fallo parcial. Ver [`maquina-de-estados.md`](sdd/common/maquina-de-estados.md) §5.

### Ciclo en vuelo

Ciclo de sincronización que quedó a medias porque el proceso murió entre `RECEIVED` y `SENDING_SAP`. Antes bloqueaba la entidad para siempre (OPS-1); ahora un evento nuevo abre ciclo igualmente y el repositorio lo registra en log (`isInFlight`). Los estados en vuelo son `RECEIVED`, `FETCHING`, `VALIDATING`, `VALID`, `INDEXING`, `INDEXED`, `SENDING_SAP`.

### Circuit Breaker

Patrón de resiliencia que abre el circuito tras fallos consecutivos para evitar sobrecargar el sistema downstream. Implementado con **Resilience4j**. Ver [`TECH.md`](architecture/TECH.md#8-clientes-sap).

### `client_credentials`

Flujo OAuth2 en el que un **sistema** (no una persona) obtiene un token con su identificador y su secreto, sin usuario de por medio. Es el grant que usarían el CDC, SAP BTP en modo pull y un lector externo con Keycloak. Ver [`KEYCLOAK.md`](tools-integrations/KEYCLOAK.md) §3 y [`SERVICIO-AUTENTICACION.md`](tools-integrations/SERVICIO-AUTENTICACION.md).

### Cloud Connector

Componente de SAP BTP que permite conectividad segura desde BTP hacia sistemas on-premise.

### Communication arrangement / communication user

Configuración en S/4HANA Public Cloud que habilita un escenario de API (p. ej. `SAP_COM_0008` para Business Partner) y el usuario técnico con el que se autentican las llamadas entrantes.

### `ConcurrentTransitionException`

Dos escrituras concurrentes intentaron avanzar el estado de la misma entidad; la primera gana y la segunda recibe esta excepción en lugar de pisar el estado. Se detecta de tres formas: secuencia duplicada (índice único `dom_ent_seq_uk` sobre `(domain, entityId, seq)`), **cabecera movida** (el `from` declarado ya no es el real) y **ciclo ajeno** (**fencing token**). Es transitoria y está **declarada reintentable** en `KafkaErrorHandlingConfig.RETRYABLE` (no por omisión): se reintenta releyendo, en el REST síncrono se traduce a `409 Conflict`, y por eso nunca extiende `IllegalStateException`, que está declarada no reintentable y mandaría el mensaje a la DLT. Ver **Secuencia de estado**, **Fencing token** y [ADR-0011](architecture/adr/0011-concurrencia-entre-instancias-fencing-sin-lease.md).

### Clave de partición (`entity_id`)

Campo por el que Kafka reparte los mensajes en particiones. Al ser el identificador de la entidad, garantiza que todos los eventos de un cliente o artículo caen en la **misma partición** y se procesan en orden y sin solaparse; es la pieza que hace innecesario un *lease* por entidad. La fija el conector Debezium (`message.key.columns` + `ExtractField$Key`). Ver [ADR-0011](architecture/adr/0011-concurrencia-entre-instancias-fencing-sin-lease.md).

### Consumer group compartido entre clústeres

Un único grupo de consumo por dominio (`customer-consumer`, `article-consumer`) para todas las instancias de **todos** los clústeres, de modo que cada mensaje lo procese exactamente un consumidor. Grupos separados por clúster harían que cada clúster escribiera el mismo cambio en el mismo S/4. Ver [ADR-0011](architecture/adr/0011-concurrencia-entre-instancias-fencing-sin-lease.md).

### Contract test (test de contrato)

Test que fija **lo que enviamos** a un sistema externo: método, path, cabeceras y cuerpo. En el repo viven en `it/…/contract/*ContractTest`, los ejecuta failsafe y, desde la Fase 2 de la auditoría, construyen el `RestClientSapClient` real contra WireMock y ejercitan el **adaptador de producción**, no el stub. No validan lo que SAP acepta: eso es la validación contra el tenant de test. Ver [`TESTING.md`](testing/TESTING.md) §7.

### Criterio de aceptación (AC-n)

Regla verificable de un spec SDD, numerada `AC-1`, `AC-2`… Cada una debe tener **al menos un test que la cite** en su Javadoc: es la mitad del ancla vista desde el código. Ver [`DESARROLLO.md`](architecture/DESARROLLO.md).

## D

### Dashboard UI

Web estática por dominio que muestra el estado de sincronización, el histórico y la búsqueda por clave de negocio de una entidad. Stack: Thymeleaf + HTMX + Alpine.js (sin SPA, sin build de cliente). Lee directo de Mongo y Elasticsearch del bounded context; **no** consume las APIs REST del `customer-app`/`article-app` — el aislamiento entre bounded contexts lo vigila `DashboardIsolationTest`. La pestaña "Grafo" / UI-002 está **planificada pero pausada** (bloqueo B-1: aplanamiento de `uri` en Prometheus no implementado en las tres apps); ver [`sdd/customer/consulta-entidad-grafo-ui.md`](sdd/customer/consulta-entidad-grafo-ui.md) §10.bis. Ver [`sdd/customer/consulta-entidad-ui.md`](sdd/customer/consulta-entidad-ui.md), [`features/UI-001/`](features/UI-001/README.md).

### DashboardIsolationTest

Regla ArchUnit (3 reglas) sobre el módulo `dashboard-customer`: no importa clases de `customer.application.*`, `customer.bootstrap.*` ni `customer.domain.*`. Refuerza el principio de bounded context: la lectura se hace por driver nativo de Mongo y ES, no reutilizando use cases ni controllers del `customer-app`. Ver [`sdd/customer/consulta-entidad-ui.md`](sdd/customer/consulta-entidad-ui.md) R-1 y AC-4.

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

### ETag / `If-Match`

Huella de la versión de un recurso OData que SAP devuelve en el `GET` (cabecera `ETag` o `__metadata.etag`) y que se reenvía en la cabecera `If-Match` del `PATCH`, para que la actualización falle con [`412`](#412-precondition-failed) si alguien lo modificó entretanto. Tiene un efecto colateral importante: **un `PATCH` con `If-Match` es idempotente** —un segundo intento con el ETag ya consumido da 412, no una doble escritura— y por eso se puede reintentar como una lectura. El ETag **no se persiste**: envejece. Ver [`resiliencia-cliente-sap.md`](sdd/common/resiliencia-cliente-sap.md) R-9.

### ECS (Elastic Common Schema)

Formato JSON estándar de logs de Elastic. Spring Boot lo emite de forma nativa con `logging.structured.format.console=ecs` (variable `LOGGING_STRUCTURED_FORMAT_CONSOLE`); en local se deja la consola legible. Es el primer paso de OBS-4; el `traceId` llegará con las trazas (D-7).

### Elasticsearch (ES)

Motor de búsqueda e indexación. Almacena histórico de sincronizaciones. Port: `HistoryIndexerPort`.

### Estados de entrada (`ENTRY_STATES`)

Los cuatro puntos reales por los que arranca un pipeline y por los que `beginCycle` puede abrir ciclo: `RECEIVED` (agregado), `VALIDATING` (línea de feature), `SENDING_SAP` (baja), `INDEXING` (indexación). Sustituyen a los antiguos «estados iniciales», que solo admitían dos y por eso la baja y la indexación nunca se ejecutaban.

### Event Mesh / Advanced Event Mesh

Broker de eventos de SAP BTP por el que se distribuirían los Business Events de S/4 hacia consumidores externos (webhook o AMQP). Es la tercera vía con SAP, junto a BTP y la API OData directa (ver tabla en [`INTEGRATION-PATTERNS.md`](architecture/INTEGRATION-PATTERNS.md)): **sentido SAP → plataforma** (a diferencia de las otras dos, que son *push*), **sin código** en el reactor y sin diseño cerrado — Patrón 5, propuesta pendiente de que el equipo SAP confirme el mecanismo de publicación.

## F

### Fail-open / fail-closed

Cómo se comporta un control de seguridad cuando le falta información para decidir. **Fail-open** concede el acceso por defecto; es lo que hoy hace `AccessScope.canSeeSensitiveData(null)`, que devuelve `true` y sirve PII sin enmascarar cuando no hay autenticación (hallazgo 2A-2). **Fail-closed** deniega por defecto, que es la regla correcta en seguridad. Ver [`SERVICIO-AUTENTICACION.md`](tools-integrations/SERVICIO-AUTENTICACION.md).

### Fencing token

Testigo monótono —aquí, el identificador del ciclo de sincronización que escribió la cabecera de estado— que impide que un proceso «zombi», uno que perdió la carrera sin enterarse, siga escribiendo sobre el trabajo de otro. Se eligió frente al *lease* por coste cero por mensaje. Ver [ADR-0011](architecture/adr/0011-concurrencia-entre-instancias-fencing-sin-lease.md).

### Fallo antes de enviar / tras enviar

Clasificación de un error de transporte según si la petición HTTP llegó a salir. **Antes de enviar**: conexión rechazada, timeout de *conexión*, host que no resuelve, sin ruta — la petición no salió, así que reintentarla no puede duplicar nada. **Tras enviar**: timeout de *respuesta*, conexión reseteada, error de E/S — la petición salió y no sabemos qué hizo SAP con ella. Solo la primera permite reintentar una escritura. Ante la duda, se trata como «tras enviar»: equivocarse hacia el otro lado duplica datos maestros. Ver [`resiliencia-cliente-sap.md`](sdd/common/resiliencia-cliente-sap.md) R-8.

### Feature (spec SDD)

Unidad de trabajo del proyecto y unidad de documentación: un fichero en `docs/sdd/<subproyecto>/<nombre>.md`. No confundir con **Feature (subconjunto)**, que es el enum `CustomerFeature` del dominio. Su ciclo de vida se registra en [`sdd_registry`](sdd/README.md#8-registro-de-features-mysql).

### Feature (subconjunto)

En el dominio `customer`, cada parte del aggregate que puede sincronizarse de forma independiente: `ADDRESS`, `FISCAL`, `CONTACT`, `BANKING`. Ver [`OVERVIEW.md`](architecture/OVERVIEW.md#2-vista-de-dominios-aggregate--features).

### `feature_evento` / `sdd_registry`

Base de datos MySQL (contenedor `mysql-sdd`) donde se registra la información ampliada de cada feature solicitada — quién la pidió, cuándo, en qué estado — y su ciclo de vida: un evento `ALTA`, `MODIFICACION` o `BAJA` por cada cambio. **No es una base de datos de la aplicación**: ningún módulo del reactor se conecta a ella. Ver [`sdd/README.md`](sdd/README.md#8-registro-de-features-mysql).

### Fencing token

Testigo que impide que un proceso «zombi» —uno que perdió la carrera sin enterarse— siga escribiendo sobre el trabajo de otro. Aquí es el `cycleId` de la cabecera de estado: una instancia solo avanza si la cabecera es de **su** ciclo; si no, recibe una `ConcurrentTransitionException` reintentable. Protege *nuestro* estado, no SAP: que no se escriba dos veces en SAP depende de la verificación previa. Ver [`maquina-de-estados.md`](sdd/common/maquina-de-estados.md) R-8.

### Fingerprint (incidencias)

Identificador estable de la *causa raíz* de un defecto, no de su síntoma, para reconocer recurrencias. El primero del proyecto es `sync-state:reentrada-no-permitida`: tres arreglos «fila a fila» de la máquina de estados que fallaron igual (`IllegalStateException` → 3 reintentos → DLT) hasta el rediseño de raíz. Convención propuesta en `docs/incidencias/`.

## H

### Hash del snapshot (`payloadHash`)

Huella SHA-256 (hexadecimal) que el consumidor calcula sobre una **forma canónica** del agregado que acaba de leer del legacy: componentes del `record` en orden de declaración, claves de mapa ordenadas, orden de lista respetado y nulos marcados. Identifica **el contenido**, no el intento, y es lo que decide el dedupe, lo que se escribe en cada transición del ciclo y el `Idempotency-Key` hacia SAP. Desde [ADR-0013](architecture/adr/0013-outbox-mensaje-fino-sin-payload.md) ya no viaja en el mensaje. Implementación: `common/domain/PayloadHasher`.

### Hexagonal Architecture

Arquitectura de puertos y adaptadores. El dominio está en el centro; los adaptadores conectan con el exterior. Ver [`TECH.md`](architecture/TECH.md#4-arquitectura-por-dominio-capas-por-paquete).

## I

### IBAN

*International Bank Account Number*: identificador de cuenta (país + dígitos de control + cuenta). En S/4 va en `A_BusinessPartnerBank.IBAN` y en `SEPAMandate.SenderIBAN`; sus dos primeros caracteres dan `BankCountryKey`. Es PII bancaria.

### Idempotencia

Propiedad que garantiza que reintentar una operación no produce efectos duplicados. En este proyecto se logra con hash de payload + identificador de entidad. Ver [`OVERVIEW.md`](architecture/OVERVIEW.md#9-requisitos-no-funcionales).

### IAS (Identity Authentication Service)

Servicio de autenticación de SAP BTP, alternativa a XSUAA.

### IdP (Identity Provider, proveedor de identidad)

Servicio externo que autentica a personas y a sistemas, y emite tokens firmados que las aplicaciones verifican sin autenticar a nadie ellas mismas (ver **Resource server**). Hoy es Keycloak, ya operado por la empresa; la app lo consume como resource server OAuth2 ([ADR-0007](architecture/adr/0007-keycloak-como-proveedor-de-identidad-de-las-apis.md)). [ADR-0012](architecture/adr/0012-servicio-externo-de-autenticacion-idp.md) (estado: propuesta) evalúa mantenerlo o sustituirlo. Ver [`SERVICIO-AUTENTICACION.md`](tools-integrations/SERVICIO-AUTENTICACION.md).

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

### Keycloak

Proveedor de identidad corporativo (OpenID Connect). Emite los JWT que las APIs validan como *resource server*; los roles `sap-read`, `sap-write`, `sap-admin`, `sap-superadmin` y `sap-external-read` viajan en el token. Ver [`KEYCLOAK.md`](tools-integrations/KEYCLOAK.md) y [ADR-0007](architecture/adr/0007-keycloak-como-proveedor-de-identidad-de-las-apis.md).

### Kustomize

Herramienta nativa de `kubectl` (`kubectl apply -k`) para componer manifiestos: una **base** común y **overlays** por entorno (`deploy/k8s/overlays/test`, `prod`) que cambian namespace, imagen, réplicas y configuración. Elegida frente a Helm por tamaño del proyecto ([ADR-0008](architecture/adr/0008-kubernetes-como-plataforma-de-despliegue.md)).

### KPI retardado de recuperación (KPI-5 UI-001)

Porcentaje de alertas que terminan con un `SENT_SAP` posterior en menos de 24 h. En el dashboard se publica como `business_kpi_recovery_p95_seconds{cycle="last"}` (percentil 95 del tiempo de recuperación del último ciclo cerrado en éxito). Cardinalidad acotada por diseño: la etiqueta solo es `cycle` (`first` o `last`). Ver [`sdd/customer/consulta-entidad-ui.md`](sdd/customer/consulta-entidad-ui.md) §8.

### KRI (Key Risk Indicator) vs KPI

[Sigla que aparece mal en los documentos] Un KRI mide el nivel de riesgo de que algo vaya mal; un KPI mide si algo va bien. El término "KPI retardado de recuperación" es operativo y se mide contra el sistema de sincronización, no contra el negocio; consúltese con [`auditor-business`](../../agents/auditor-business.md) si se quiere formalizar como KRI en lugar de KPI. Ver [`glosario.md`](glosario.md#kpi) y [`glosario.md`](glosario.md#kri).

## L

### Lease (arrendamiento) por entidad

Bloqueo con caducidad (TTL) que una instancia toma sobre una entidad para trabajar en exclusiva. **Descartado** en este proyecto: su TTL debería superar el presupuesto de reintentos a SAP (~5 min) y una instancia caída bloquearía la entidad ese tiempo, mientras que un TTL corto produciría dos dueños simultáneos. En su lugar se usa el **fencing token**. Ver [ADR-0011](architecture/adr/0011-concurrencia-entre-instancias-fencing-sin-lease.md) §2.

### Línea de estado por feature

Historia de estados propia de cada feature de una entidad, con clave `<entityId>:<FEATURE>` (p. ej. `CUST-001:ADDRESS`), independiente de la del agregado. Entra por `VALIDATING` en vez de por `RECEIVED`, porque el pipeline por feature valida y envía sin indexar. Ver [`maquina-de-estados.md`](sdd/common/maquina-de-estados.md).

## M

### Mensaje fino / notificación de cambio (*claim check*)

Aviso que publica la outbox cuando algo cambia en un legacy: dice **qué entidad cambió y cuándo** (`entityId`, `operation`, `occurredAt`), y **no lleva datos ni PII**. Quien lo recibe va a buscar el estado actual al legacy: el mensaje es el resguardo (*claim check*) y la consigna es la propia base de datos de origen. Ver [`contrato-mensaje-de-cambio.md`](sdd/common/contrato-mensaje-de-cambio.md) y [ADR-0013](architecture/adr/0013-outbox-mensaje-fino-sin-payload.md).

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

### MTTR técnico (KPI-1 UI-001)

Tiempo entre la apertura de una alerta `sap.sync.alerts` y el próximo `SENT_SAP` para la misma `entityId`/`cycleId`. En el dashboard se publica como `business_kpi_mttr_seconds{window="7d"}`. Cardinalidad acotada: la etiqueta es solo `window` (`7d`, `30d`...). Ver [`sdd/customer/consulta-entidad-ui.md`](sdd/customer/consulta-entidad-ui.md) §8.

### Multitenancy

Capacidad de atender a múltiples tenants. El SDK gestiona tenant/principal mediante `ThreadContext`. Ver [Thread Context — Cloud SDK](https://sap.github.io/cloud-sdk/docs/java/features/multi-tenancy/thread-context).

## O

### OData

Estándar construido **encima de REST** que fija por contrato lo que REST deja abierto: filtrado (`$filter`), selección (`$select`), paginación, navegación entre entidades, `$batch` y metadatos (`$metadata`). Es el protocolo de las APIs públicas de S/4HANA. Explicación completa (REST vs OData, con ejemplos) en [`SAP_CLOUD_SDK.md` § OData vs REST](tools-integrations/SAP_CLOUD_SDK.md#odata-vs-rest-y-odata-v2-vs-v4).

### Observability tags (env, cluster)

Tags comunes que la app añade a cada serie Prometheus. `application` distingue
`customer-app` y `article-app`; `env` toma valores `local`/`test`/`prod`;
`cluster` identifica el cluster K8s. Se inyectan por variables de entorno
(`OBS_ENV`, `OBS_CLUSTER`, `spring.application.name`). Default si no se
definen: `local`/`local`/`${spring.application.name}`.

Ver: [docs/sdd/common/observabilidad.md](../sdd/common/observabilidad.md) R-8.

### OData V2 vs V4

Dos versiones del estándar con formato distinto: **V2** envuelve las respuestas en `{"d":...}` (y `d.results` en listas), pagina con `$skip` y exige fetch de token CSRF en escrituras; **V4** devuelve la entidad en la raíz, usa `value` + `@odata.nextLink` y no usa el CSRF clásico (OAuth2 puro). Las APIs `API_*` del proyecto son V2; las `CE_*` (bancos, activos fijos, números de serie) son V4. Detalle y ejemplos en [`SAP_CLOUD_SDK.md` § OData V2 vs V4](tools-integrations/SAP_CLOUD_SDK.md#odata-v2-vs-v4); versión de cada API en el [catálogo](sdd/sap-api-catalog.md#catálogo).

### OIDC (OpenID Connect)

Capa de identidad construida sobre OAuth2: además del token de acceso, define cómo autenticar usuarios (`id_token`) y cómo publicar las claves de verificación (ver **JWKS** en `SERVICIO-AUTENTICACION.md`, sección "Requisitos"). Keycloak, Zitadel y authentik lo implementan; es el protocolo que usa esta aplicación como resource server. Ver [`SERVICIO-AUTENTICACION.md`](tools-integrations/SERVICIO-AUTENTICACION.md).

### OpenAPI

Especificación estándar para APIs REST. SAP publica especificaciones OpenAPI en API Business Hub. Se generan clientes Java con el plugin de Cloud SDK. Ver [`SAP_CLOUD_SDK.md`](tools-integrations/SAP_CLOUD_SDK.md#2-apis-rest-propias-de-sap--callbacks--openapi).

### OpenAPI (contrato REST)

Aquí, además de lo anterior, el **contrato de las APIs propias**: cada módulo con controladores REST publica su `openapi.yml` (OpenAPI 3.1) en `src/main/resources/`, de modo que viaja dentro del jar. Dice rutas, métodos, parámetros, cuerpos, códigos y ejemplos, y añade por operación `x-required-role` (el rol del `@PreAuthorize`) y si la respuesta se enmascara para lectura externa. No se genera en runtime ni se sirve por HTTP: se importa en Postman, Bruno o Swagger UI. `OpenApiMatchesControllersTest` rompe el build si el contrato y los controladores divergen. Ver [`contrato-openapi-rest.md`](sdd/common/contrato-openapi-rest.md).

### OpenTelemetry (OTel)

Estándar de observabilidad para trazas distribuidas. El proyecto usa el **starter oficial** de Boot 4, `spring-boot-starter-opentelemetry` (`common/pom.xml`), no el javaagent: decisión [ADR-0009](architecture/adr/0009-trazas-con-el-starter-oficial-de-opentelemetry.md), apagado por defecto (`TRACING_ENABLED=true` cuando exista un destino). Ver [`TECH.md`](architecture/TECH.md#9-observabilidad).

### OTLP

*OpenTelemetry Protocol*: protocolo con el que las apps exportarían trazas y métricas a un colector (`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`). El starter ya está decidido y en el `pom.xml` (ADR-0009, D-7 cerrada); lo que falta es el **destino** (Tempo, OBS-2): sin él, `TRACING_ENABLED` sigue en `false` y no hay trazas ni `traceId` en los logs.

### Outbox Pattern

Patrón que escribe eventos en una tabla outbox transaccionalmente con el cambio de negocio; Debezium lee la outbox y publica en Kafka.

## P

### `@PreAuthorize` (acceso por endpoint)

Anotación de Spring Security que declara, en el propio método del controller, quién puede llamarlo (`hasRole('SAP_WRITE')`). Es el equivalente Java de los atributos de autorización de .NET. Obligatoria en todo endpoint: lo vigila `EndpointsDeclareAccessTest` (ArchUnit).

### Parada ordenada (graceful shutdown)

`server.shutdown=graceful`: al detener la app se deja de aceptar trabajo nuevo y se espera (hasta `spring.lifecycle.timeout-per-shutdown-phase`, 30 s) a que terminen las peticiones HTTP y los mensajes Kafka en curso, para no dejar entidades en estados en vuelo.

### payloadHash

Hash del payload del evento de ingesta; clave del dedupe de idempotencia: si ya existe una transición `SENT_SAP` de la entidad con ese hash (`SyncStateRepositoryPort.alreadySent`), el mensaje se descarta sin reprocesar. Viaja también como cabecera `Idempotency-Key`.

### PII (información personal identificable)

Datos que identifican a una persona: NIF, IBAN, email, teléfono, dirección. Viven en el legacy, en la imagen (Mongo), en el histórico (ES, snapshot íntegro por decisión explícita) y en los topics Kafka. Condicionan retención, borrado (modelo de bloqueo), enmascarado en logs (SEC-3) y autenticación de las APIs (B4).

### PiiMasker (`common.security`)

Utilidad de enmascarado de datos personales (IBAN, NIF, email, teléfono) promovida a `common.security.PiiMasker` en el H-1 de UI-001 (2026-09-23). La antigua clase `customer.bootstrap.web.PiiMasker` queda como fachada `@Deprecated` que delega en la nueva para no romper los controllers existentes del `customer-app`. Ver [`sdd/customer/consulta-entidad-ui.md`](sdd/customer/consulta-entidad-ui.md) R-3, AC-9.

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

**Propuesta, no implementada.** Modo de integración donde SAP BTP iniciaría el ciclo: preguntar pendientes (`GET /btp/pending`), procesar en S/4HANA, y notificar resultado (`POST /btp/result`). Ni los endpoints ni el estado asociado existen en el código actual, y la propiedad `sap.integration.mode` se retiró en la Fase 7 (nadie la leía). Ver [`MEJORAS-Y-PROPUESTAS.md`](MEJORAS-Y-PROPUESTAS.md) PRD-4.

### Push (integración)

Modo de integración donde nuestra app empuja datos a SAP activamente vía `SapClient.send/patch()`. Puede ir por BTP o por API OData directa según el adaptador activo (`sap.odata.<feature>.enabled`). Es **el único modo implementado**. Ver [`FLOWS.md`](architecture/FLOWS.md).

## R

### Re-entrada (re-sincronización)

Reabrir el ciclo de una entidad ya procesada cuando llega un evento nuevo. Desde la Fase 1 de la auditoría es una operación propia, **apertura de ciclo** (`beginCycle`), legal desde cualquier estado — cerrado, de error o en vuelo — y no una fila más de la tabla de transiciones. Cada pipeline re-entra por su estado de entrada. La idempotencia la garantiza el dedupe por `payloadHash`, no el bloqueo de la máquina.

### Registro de features

Ver **`feature_evento` / `sdd_registry`**.

### Resilience4j

Librería de resiliencia (circuit breaker, retry, rate limiter) usada en los clientes SAP actuales. Ver [`TECH.md`](architecture/TECH.md#8-clientes-sap).

### Resource server (OAuth2)

Papel de una API que **valida** tokens emitidos por otro (Keycloak) en vez de autenticar usuarios ella misma: comprueba firma, expiración e issuer y convierte los roles en permisos. Es como se protegen `/customers/**` y `/articles/**` ([`seguridad-api.md`](sdd/common/seguridad-api.md)).

### `RestClient` (Spring)

Cliente HTTP **síncrono** de Spring Framework 6.1+, con la API fluida de `WebClient` pero sin Reactor. Es el transporte hacia SAP desde [ADR-0001](architecture/adr/0001-transporte-http-sap-restclient.md) (`RestClientSapClient`, sobre el `HttpClient` del JDK: PATCH nativo, timeouts de conexión y lectura). Ver spec [`resiliencia-cliente-sap.md`](sdd/common/resiliencia-cliente-sap.md).

### Retry

Reintentos con backoff exponencial ante fallos transitorios. La política **no es uniforme**: lecturas (GET/DELETE) reintentan cualquier 5xx o error de transporte; escrituras (POST/PATCH, `sap-write`) solo reintentan el fallo de transporte **anterior al envío** — un 5xx ya recibido por SAP no se reintenta a ciegas, para no duplicar el alta. La calcula `RetryBudgetGuard` por tipo de llamada. Ver [`resiliencia-cliente-sap.md`](sdd/common/resiliencia-cliente-sap.md) y [`OVERVIEW.md`](architecture/OVERVIEW.md#9-requisitos-no-funcionales).

### `RetryBudgetGuard`

Calcula, para cada tipo de llamada a SAP (lectura, escritura) y para el backoff de reentrega de Kafka, el presupuesto de tiempo que puede consumir un mensaje antes de que expire el `max.poll.interval.ms` del consumidor. Ver **Presupuesto de reintentos** y [`observabilidad.md`](sdd/common/observabilidad.md).

### `SapOutboundPort`

Puerto de envío a SAP por feature (`domain/port`). Además de `send` (alta), define `lookup`/`update` como métodos `default` para el upsert idempotente: antes de escribir se pregunta a SAP qué tiene (`lookup`); si lo tiene se actualiza (`update`, `PATCH` con `If-Match`); si no, se da de alta (`send`); si el lookup no concluye, no se escribe nada. Ver [`upsert-idempotente-sap.md`](sdd/common/upsert-idempotente-sap.md).

### `SapResponse`

Respuesta normalizada de una llamada a SAP (`httpStatus`, `body`, `location`, `etag`). `httpStatus=0` significa fallo de transporte (no hubo respuesta de SAP). Vive en `SapOutboundPort`.

### RFC (Remote Function Call)

Protocolo de SAP para llamar a funciones remotas, incluidas BAPIs.

## S

### S/4HANA

Suite ERP de SAP. En este proyecto se sincronizan datos maestros con **SAP S/4HANA Public Cloud**.

### Saga / compensación

Patrón para mantener consistencia entre varias operaciones sin transacción distribuida: si una falla, se **compensan** las anteriores. **Descartado** aquí (D-2, [ADR-0010](architecture/adr/0010-sin-compensacion-entre-features-marcar-y-avisar.md)): deshacer en SAP sería otra modificación con su rastro y su propio riesgo de fallo; se marca, se localiza y se avisa.

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

### Sincronización parcial (alerta `SYNC_PARTIAL_FAILURE`)

Ciclo de un cliente en el que alguna de sus partes (dirección, fiscal, contacto, banco) no llegó a `SENT_SAP` mientras otras sí. No se compensa ([ADR-0010](architecture/adr/0010-sin-compensacion-entre-features-marcar-y-avisar.md)): el agregado queda en error, cada parte conserva su línea de estado (`GET /customers/{id}/state`) y se emite un `WARN` y un mensaje JSON en el topic `sap.sync.alerts` con las partes OK y fallidas. El siguiente evento reenvía todo.

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

### Tempo (Grafana)

Almacén de trazas distribuidas de Grafana, destino natural de las trazas OTLP junto a Prometheus (métricas) y Loki (logs). El código exporta trazas cuando `TRACING_ENABLED=true` apunta a él ([ADR-0009](architecture/adr/0009-trazas-con-el-starter-oficial-de-opentelemetry.md)).

### Testcontainers

Librería para levantar contenedores Docker en tests de integración. Ver [`TESTING.md`](testing/TESTING.md).

### Tests declarados vs ejecutados

**Declarados**: métodos `@Test` en `src/test/java` de todos los módulos, gateados o no. **Ejecutados**: los que corren en un `mvn` concreto (sin Docker se saltan los `*IT`). La cifra de [`TESTING.md`](testing/TESTING.md) §1 es la de declarados y la vigila `TestCountMatchesDocsTest`: el build falla si un documento se queda atrás.

### Thread Context

Contexto de ejecución del SDK que propaga tenant/principal. Requiere cuidado con operaciones `@Async`. Ver [Thread Context — Cloud SDK](https://sap.github.io/cloud-sdk/docs/java/features/multi-tenancy/thread-context).

### Token stub (`sap.auth.allow-stub`)

Credencial falsa (`stub-btp-token` / `stub-s4-token`) que los `SapAuthProvider` emiten **solo** si `sap.auth.allow-stub=true` (`SAP_AUTH_ALLOW_STUB`) y faltan credenciales reales; sirve únicamente contra el SAP simulado. Con el valor por defecto (`false`) la app no arranca sin credenciales, en vez de fallar con `401` en la primera llamada (auditoría A8). Ver [`autenticacion-sap.md`](sdd/common/autenticacion-sap.md).

### Traza de pasos de un envío

Secuencia ordenada de las transiciones que comparten un mismo `cycleId` a lo largo de todas las líneas de la entidad (agregado y features), con el estado y el **motivo** de cada parte. Es lo que devuelve `GET /customers/{id}/state` en `lastCycle` y lo que viaja en el aviso `SYNC_PARTIAL_FAILURE` de `sap.sync.alerts`. Responde a «¿dónde ha dado el error?» sin ir a los logs. Ver [`sincronizacion-cliente.md`](sdd/customer/sincronizacion-cliente.md) R-9.

### TTL Mongo (alert)

Índice de Mongo con `expireAfterSeconds` que borra automáticamente los documentos pasados N días desde el campo fecha del índice. UI-001 (2026-09-23) define dos índices TTL en la colección `alerts`: `acked_ttl` (30 días sobre `ackedAt`) y `opened_ttl` (30 días sobre `openedAt`), más el índice único `alert_id_uk` y el compuesto `entity_acked_idx` (`entityId`, `ackedAt`). Definidos en [`external-services/mongodb/init.js`](../../external-services/mongodb/init.js). Ver [`sdd/customer/consulta-entidad-ui.md`](sdd/customer/consulta-entidad-ui.md) AC-10.

## U

### UI-001..UI-007

Filas del backlog [MEJORAS-Y-PROPUESTAS.md](../../MEJORAS-Y-PROPUESTAS.md) §"Panel de operación (dashboard web)". UI-001 ("Dashboard web: vista por entidad y búsqueda") es el primer spec formal del panel; spec en [`sdd/customer/consulta-entidad-ui.md`](sdd/customer/consulta-entidad-ui.md), quickstart en [`features/UI-001/quickstart.md`](features/UI-001/quickstart.md). UI-002 ("Vista grafo del flujo de integración") está **pausada desde el 2026-09-23** por bloqueo B-1 (aplanamiento del tag `uri` en Prometheus no implementado en las tres apps); spec redactado en [`sdd/customer/consulta-entidad-grafo-ui.md`](sdd/customer/consulta-entidad-grafo-ui.md), sin código. UI-006 (stack React + Vite) se descartó el 2026-09-23: el stack Thymeleaf+HTMX cubre la lectura sin necesidad de SPA.

### Verificación previa (lookup)

`GET` a SAP **inmediatamente antes** de escribir, para saber si la subentidad ya existe y decidir alta o actualización. La regla que la hace útil: solo un `404` significa «SAP no la tiene»; un 5xx, un fallo de transporte o una respuesta ambigua son «no lo sé», y con «no lo sé» **no se escribe nada**. Ver [`upsert-idempotente-sap.md`](sdd/common/upsert-idempotente-sap.md).

### VDM (Virtual Data Model)

Modelo de datos tipado generado por SAP Cloud SDK a partir de metadatos OData de S/4HANA. Ver [`SAP_CLOUD_SDK.md`](tools-integrations/SAP_CLOUD_SDK.md#1-business-partner--odata-vdm).

### Virtual Threads

Hilos ligeros de Java 21+. El proyecto los usa para concurrencia de Kafka/REST. Ver [`TECH.md`](architecture/TECH.md#1-plataforma).

### Upsert

Escritura que **crea si no existe y actualiza si existe**. Aquí no es una operación de SAP, sino una secuencia nuestra: [verificación previa](#verificación-previa-lookup) + `POST` o `PATCH` con [`If-Match`](#etag--if-match). Es lo que impide que sincronizar dos veces el mismo cliente cree dos clientes. Ver [`upsert-idempotente-sap.md`](sdd/common/upsert-idempotente-sap.md).

## W

### WireMock

Herramienta para simular servidores HTTP en tests de contrato SAP. Ver [`TESTING.md`](testing/TESTING.md#2-tipos-de-tests).

## X

### XSUAA

Servicio de autorización y autenticación de SAP BTP (OAuth2). Usado junto al Destination Service para consumir APIs protegidas.
