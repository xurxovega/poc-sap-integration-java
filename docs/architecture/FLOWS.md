# Flujos de integración SAP

> Mapas de proceso para seguir el código desde la entrada hasta la salida.
> Cada flecha (`→`) apunta a la clase/fichero real en el repositorio.
> Usa estos flujos para navegar con "buscar clase" en el IDE.

---

## Flujo 1 — CDC completo (Kafka → SAP)

Flujo principal de sincronización: llega un mensaje Kafka desde el outbox legacy,
se valida, se persiste imagen y estado, y se envía a SAP.

> **Push vs Pull:** El mismo mensaje de Kafka puede terminar en BTP
> (`BtpAddressAdapter`) o en la API directa SAP (`BusinessPartnerAddressODataAdapter`)
> según qué adaptador esté activo por configuración Spring.
> El modo Pull descrito en el [Flujo 4](#flujo-4--integración-pull-sap-btp-orquesta-el-ciclo-completo)
> (estado `PENDING_SAP` + polling de SAP BTP) es una **propuesta no implementada**:
> ni ese estado ni esos endpoints existen en el código actual.

```
Kafka topic outbox.CUSTOMER
  │
  ▼
customer/bootstrap/kafka/CustomerKafkaListener.java
  │  recibe ConsumerRecord → IngestionMessage
  │  decide qué features ejecutar (todas)
  ▼
customer/application/general/SyncCustomerUseCase.java
  │
  ├─[1] CustomerLegacyRepositoryPort.fetch(entityId)
  │      └→ customer/adapters/persistence/SqlServerCustomerRepository.java
  │         consulta tabla dbo.customers en SQL Server
  │
  ├─[2] CustomerValidations.validate(customer, features)
  │      └→ customer/domain/CustomerValidations.java
  │         orquesta 4 validadores: AddressValidator, FiscalValidator, ContactValidator, BankingValidator
  │
  ├─[3] CustomerImageStorePort.save(id, customer)
  │      └→ customer/adapters/persistence/MongoCustomerImageStore.java
  │         guarda en MongoDB (colección customers_current)
  │
  ├─[4] CustomerHistoryIndexerPort.index(id, customer, hash)
  │      └→ customer/adapters/index/ElasticsearchCustomerIndexer.java
  │         indexa en Elasticsearch
  │
  └─[5] SyncAddressUseCase (ejemplo de feature)
         │  customer/application/address/SyncAddressUseCase.java
         │
         ├─ AddressValidator.validate(address)
         ├─ AddressSapPort.send(entityId, payloadHash, address)
         │   │  customer/domain/port/AddressSapPort.java
         │   │
         │   └→ customer/adapters/sap/BtpAddressAdapter.java  (ruta BTP)
         │      │  customer/adapters/sap/dto/BtpAddressDto.from(address)
         │      │  common/sap/json/SapJsonMapper.write(dto)
         │      │  common/sap/SapClient.send(BTP, path, ...)
         │      │
         │      └→ common/sap/WebClientSapClient.java
         │         │  exchange("POST", ...) → WebClient.post()
         │         │  Resilience4j retry + circuit breaker
         │         │  auth via SapAuthProvider → Bearer token
         │         └→ SAP BTP API
         │
         └─ common/adapters/persistence/MongoSyncStateRepository.java
            persiste transición en colección sync_state
```

**Puntos de entrada para debuggear:**
- `CustomerKafkaListener.java:XX` — breakpoint en el consumer
- `SyncCustomerUseCase.java:89` — inicio del pipeline
- `BtpAddressAdapter.java:31` — antes de enviar a SAP

---

## Flujo 2 — Consulta Customer vía BP API (GET, sin coste SAP)

> ⚠️ **PROPUESTA — no implementado (parcialmente).** `LookupCustomerController` y
> `LookupCustomerUseCase` NO existen en el código. Lo que sí está implementado es
> el adaptador `BusinessPartnerReadAdapter` y su puerto `BusinessPartnerReadPort`.

Lee un Business Partner desde SAP S/4HANA usando la API Business Partner.
Ruta de solo lectura, no modifica estado.

```
REST GET /api/customers/lookup?code=C001
  │
  ▼
customer/bootstrap/web/LookupCustomerController.java  (PROPUESTA — no existe)
  │  @GetMapping("/api/customers/lookup")
  │  recibe código, delega en use case
  ▼
customer/application/general/LookupCustomerUseCase.java  (PROPUESTA — no existe)
  │
  └─ BusinessPartnerReadPort.findById(code)
      │  customer/domain/port/BusinessPartnerReadPort.java
      │
      └→ customer/adapters/sap/BusinessPartnerReadAdapter.java
         │  Activado si sap.odata.read.enabled=true
         │
         ├─ SapClient.get(S4_NATIVE, "A_BusinessPartner('C001')")
         │   │  common/sap/SapClient.java  (método get)
         │   └→ common/sap/WebClientSapClient.java
         │       exchange("GET", ...) → WebClient.get()
         │
         └─ parsea respuesta OData:
            {"d": {"BusinessPartner":"C001", "OrganizationBPName1":"Acme", ...}}
            → BusinessPartnerReadPort.BusinessPartnerSummary
```

**A tener en cuenta:**
- No genera coste SAP (es solo GET).
- No transiciona estado (no usa `SyncStateRepositoryPort`).
- Útil para verificar si un BP ya existe antes de crearlo.

---

## Flujo 3 — Creación BP desde Customer (POST, upsert con coste)

> ⚠️ **PROPUESTA — no implementado (parcialmente).** `CreateBusinessPartnerController`
> y `CreateBusinessPartnerUseCase` NO existen en el código. Lo que sí está
> implementado es `BusinessPartnerODataAdapter` (invocado hoy desde
> `SyncCustomerUseCase` vía `CustomerSapOutboundPort` cuando
> `sap.odata.customer.enabled=true`).

Crea un nuevo Business Partner en SAP vía la API OData directa.
Nota OData v2: el body de la petición POST va **sin envolver**; el wrapper
`{"d": {...}}` solo aparece en las respuestas.

```
REST POST /api/customers/create-bp
  │  body: { "code": "C003", "name": "Nueva Empresa", "status": "ACTIVE" }
  ▼
customer/bootstrap/web/CreateBusinessPartnerController.java  (PROPUESTA — no existe)
  │  recibe payload, construye Customer, delega
  ▼
customer/application/general/CreateBusinessPartnerUseCase.java  (PROPUESTA — no existe)
  │
  ├─[1] CustomerValidations.validate(customer, ALL_FEATURES)
  │
  ├─[2] MongoSyncStateRepository.transition(RECEIVED → VALID → SENDING_SAP)
  │
  └─[3] CustomerSapOutboundPort.send(entityId, hash, customer)
      │  customer/domain/port/CustomerSapOutboundPort.java
      │
      └→ customer/adapters/sap/odata/BusinessPartnerODataAdapter.java  (IMPLEMENTADO)
         │  Activado si sap.odata.customer.enabled=true
         │
         ├─ construye el modelo generado de sap-api-models:
         │   APIBUSINESSPARTNERABusinessPartnerTypeCreate
         │   (BusinessPartner, BusinessPartnerCategory=2, BusinessPartnerGrouping, OrganizationBPName1)
         │
         ├─ common/sap/json/SapJsonMapper.write(modelo)
         │   {"BusinessPartner":"C003","BusinessPartnerCategory":"2",...}  ← sin wrapper "d"
         │
         ├─ common/sap/SapClient.send(S4_NATIVE, ".../A_BusinessPartner", ...)
         │   └→ common/sap/WebClientSapClient.java
         │       exchange("POST", ...) → WebClient.post()
         │
         └─ evalúa respuesta:
            201 Created → transition SENDING_SAP → SENT_SAP
            4xx/5xx    → transition SENDING_SAP → SAP_ERROR
```

**Puntos de entrada para debuggear:**
- `BusinessPartnerODataAdapter.send()` — antes de serializar y enviar
- `SapJsonMapper.write(payload)` — ver el JSON que se envía a SAP

---

## Flujo 4 — Integración Pull (SAP BTP orquesta el ciclo completo)

> ⚠️ **PROPUESTA — no implementado.** Ninguna de las clases de este flujo
> (`BtpPendingController`, `BtpPendingQueryUseCase`, `BtpResultController`,
> `BtpResultProcessingUseCase`) existe en el código, y el estado `PENDING_SAP`
> NO forma parte de `SyncState`/`SyncStateMachine` actuales.

SAP BTP toma el control de la integración: pregunta qué hay pendiente,
procesa en S/4HANA, y notifica el resultado. Nuestra app no empuja datos,
responde a demanda.

```
                         ┌──────────────────────────────────┐
                         │        SAP BTP (orquestador)      │
                         └────────┬───────────┬─────────────┘
                                  │           │
          ┌───────────────────────┘           └───────────────────────┐
          ▼                                                           ▼
  (1) GET /btp/pending?domain=customer                     (3) POST /btp/result
  "¿qué hay pendiente de integrar?"                        "resultado de la operación"
          │                                                           │
          ▼                                                           ▼
  customer/bootstrap/web/BtpPendingController.java    customer/bootstrap/web/BtpResultController.java
  (PROPUESTA — no existe)                            (PROPUESTA — no existe)
          │                                                           │
          ▼                                                           ▼
  customer/application/general/                     customer/application/general/
  BtpPendingQueryUseCase.java                       BtpResultProcessingUseCase.java
  (PROPUESTA — no existe)                           (PROPUESTA — no existe)
          │                                                           │
          ├─ SyncStateRepositoryPort                                   ├─ SyncStateRepositoryPort
          │   .findByDomainAndState(                                    │   .transition(entityId,
          │     "customer", "PENDING_SAP")                              │     PENDING_SAP → SENT_SAP)
          │                                                            │
          └─ responde [                                 ┌──────────────┤
               { entityId, entityType,                  │              │
                 data, features }                       │              │
             ]                                          │              │
                                                        ▼              ▼
                                                200 OK          4xx/5xx → SAP_ERROR
                                                SENT_SAP
```

**El ciclo completo:**

```
                          ┌─────────────────┐
                          │  (Paso 1) BTP    │
                          │  GET /pending    │
                          │  "¿qué hay?"    │
                          └────────┬────────┘
                                   │
                    ┌──────────────┼──────────────┐
                    ▼              ▼              ▼
               [entity1]      [entity2]      [entity3]
               pendiente      pendiente      pendiente
                    │              │              │
                    └──────────────┼──────────────┘
                                   │
                          ┌────────▼────────┐
                          │  (Paso 2) BTP    │
                          │  Procesa en      │
                          │  S/4HANA         │
                          │  (create/update/ │
                          │   delete)        │
                          └────────┬────────┘
                                   │
                    ┌──────────────┼──────────────┐
                    ▼              ▼              ▼
               POST /btp/result  POST /btp/result  POST /btp/result
               entity1=OK        entity2=OK        entity3=ERROR
                    │              │              │
                    ▼              ▼              ▼
               SENT_SAP        SENT_SAP        SAP_ERROR
```

**A tener en cuenta:**
- El CDC/Kafka NO envía a SAP directamente; solo transiciona a `PENDING_SAP` y espera.
- SAP BTP decide **cuándo** procesar (puede ser inmediato o batch nocturno).
- Nuestra app expone dos endpoints: uno para consultar pendientes, otro para recibir resultados.
- El polling lo inicia SAP, no nosotros.
- Si falla, SAP puede reintentar (el estado sigue en `PENDING_SAP` hasta que se resuelva).

**Puntos de entrada para debuggear:**
- `BtpPendingController.java` — breakpoint cuando SAP pregunta (ver quién llama)
- `BtpResultController.java` — breakpoint cuando SAP devuelve resultado
- `MongoSyncStateRepository.java` — filtrar por state=PENDING_SAP

**Ficheros implicados (PROPUESTOS, no existen):**

| Fichero | Rol |
|---|---|
| `customer/bootstrap/web/BtpPendingController.java` | `GET /btp/pending?domain=customer` |
| `customer/application/general/BtpPendingQueryUseCase.java` | Busca `PENDING_SAP` en state repo |
| `customer/bootstrap/web/BtpResultController.java` | `POST /btp/result` |
| `customer/application/general/BtpResultProcessingUseCase.java` | Transiciona `PENDING_SAP → SENT_SAP / SAP_ERROR` |

---

---

## Push vs Pull — Dos modos de integración

> ⚠️ El modo **Pull** de esta sección es una **propuesta no implementada**
> (ver Flujo 4). Hoy solo existe Push; `sap.integration.mode` está definido en
> `application-common.yml` pero el valor `pull`/`both` no tiene efecto en el código.

BTP y API directa no son excluyentes. Son **vías de integración** que pueden
coexistir, incluso para el mismo dominio. La decisión de cuál usar puede ser
técnica (rendimiento), de coste (consultas gratis, upserts no) o de arquitectura
(SAP quiere orquestar en batch).

| Modo | Quién inicia | Canal | Quién paga | Ejemplo |
|---|---|---|---|---|
| **Push** | Nuestra app | `SapClient.send/patch()` → BTP o API directa | Nosotros (upsert) | CDC Kafka → `SyncAddressUseCase` → `BtpAddressAdapter` |
| **Pull** | SAP BTP | Polling `GET /btp/pending` + `POST /btp/result` | SAP (orquesta) | SAP pregunta pendientes, procesa, notifica |

**Convivencia Push + Pull para el mismo dominio:**

```
CDC mensaje "customer actualizado"
  │
  ├─[Push] SapClient.send(BTP, ...) → SAP recibe inmediatamente
  │
  └─[Pull] SyncState.PENDING_SAP → SAP lo recoge después vía polling
```

La decisión es por configuración:

```yaml
# application-common.yml
sap:
  integration:
    mode: ${SAP_INTEGRATION_MODE:push}   # push | pull | both
```

- `push`: CDC → `send()` directo (BTP o API directa según property).
- `pull`: CDC → `PENDING_SAP` → SAP BTP hace polling.
- `both`: hace las dos cosas (push inmediato + queda pendiente para pull).

---

## Flujo 5 — Mapa de rutas (BTP / OData directo / Pull)

Qué adaptador se activa, a qué destino llama, y qué modo de integración usa.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    RUTA POR DEFECTO — Push BTP                           │
│                  sap.odata.*.enabled=false                               │
├──────────────────┬───────────────────────┬──────────────────┬────────────┤
│ Puerto (domain)  │ Adaptador             │ Destino          │ Modo       │
├──────────────────┼───────────────────────┼──────────────────┼────────────┤
│ AddressSapPort   │ BtpAddressAdapter     │ BTP              │ Push       │
│ FiscalSapPort    │ BtpFiscalAdapter      │ BTP              │ Push       │
│ ContactSapPort   │ BtpContactAdapter     │ BTP              │ Push       │
│ BankingSapPort   │ S4BankingAdapter      │ S4_NATIVE        │ Push       │
│ CustomerSapPort  │ BtpCustomerAdapter    │ BTP              │ Push       │
└──────────────────┴───────────────────────┴──────────────────┴────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│                RUTA OData DIRECTA — Push SAP BP API                           │
│               sap.odata.<feature>.enabled=true                                │
├──────────────────┬──────────────────────────────────┬──────────────┬──────────┤
│ Puerto (domain)  │ Adaptador                        │ Destino      │ Modo     │
├──────────────────┼──────────────────────────────────┼──────────────┼──────────┤
│ AddressSapPort   │ BusinessPartnerAddressODataAdapter│ S4_NATIVE    │ Push     │
│ FiscalSapPort    │ BusinessPartnerTaxODataAdapter    │ S4_NATIVE    │ Push     │
│ ContactSapPort   │ BusinessPartnerContactODataAdapter│ S4_NATIVE    │ Push     │
│ BankingSapPort   │ BusinessPartnerBankODataAdapter   │ S4_NATIVE    │ Push     │
│ CustomerSapPort  │ BusinessPartnerODataAdapter       │ S4_NATIVE    │ Push     │
├──────────────────┼──────────────────────────────────┼──────────────┼──────────┤
│ BusinessPartner  │ BusinessPartnerReadAdapter        │ S4_NATIVE    │ Push     │
│ ReadPort         │ (sap.odata.read.enabled=true)     │              │ (GET)    │
└──────────────────┴──────────────────────────────────┴──────────────┴──────────┘

┌────────────────────────────────────────────────────────────────────────────────┐
│                         RUTA PULL — SAP BTP orquesta                            │
│                   sap.integration.mode=pull | both                              │
├─────────────────────────┬──────────────────────────────────────────┬────────────┤
│ Endpoint REST           │ Use case                                │ Modo       │
├─────────────────────────┼──────────────────────────────────────────┼────────────┤
│ GET /btp/pending        │ BtpPendingQueryUseCase                  │ Pull       │
│                         │ (consulta SyncStateRepositoryPort)       │ (poll)     │
│ POST /btp/result        │ BtpResultProcessingUseCase              │ Pull       │
│                         │ (transition PENDING_SAP → SENT_SAP/ERR)  │ (notify)   │
└─────────────────────────┴──────────────────────────────────────────┴────────────┘

Propiedades en application-common.yml:
  sap.odata.address.enabled      (default: false)
  sap.odata.fiscal.enabled       (default: false)
  sap.odata.contact.enabled      (default: false)
  sap.odata.banking.enabled      (default: false)
  sap.odata.customer.enabled     (default: false)
  sap.odata.read.enabled         (default: false)
  sap.integration.mode           (default: push  — push | pull | both)
```

**Cómo seguir una ruta en el código:**
1. Busca el puerto en `customer/domain/port/<Puerto>.java`
2. Abre "Find Usages" (Ctrl+Alt+F7 en IntelliJ)
3. Spring inyecta el adaptador activo según `@ConditionalOnProperty`
4. Sigue la cadena: adapter → SapJsonMapper → SapClient → WebClientSapClient

---

## Flujo 6 — Actualización BP desde Customer (PATCH, upsert con coste)

> ⚠️ **PROPUESTA — no implementado.** `UpdateBusinessPartnerUseCase` NO existe
> en el código; hoy `CustomerSapOutboundPort` solo expone `send` (POST).

Modifica un Business Partner existente en SAP. Solo envía los campos cambiados
(no todo el aggregate).

```
Evento Kafka outbox.CUSTOMER (update parcial)
  │  o REST PATCH /api/customers/{code}
  ▼
customer/application/general/SyncCustomerUseCase.java
  │  detecta cambio (delta vs imagen en MongoDB)
  │  si el BP ya existe en SAP → PATCH
  ▼
customer/application/general/UpdateBusinessPartnerUseCase.java  (PROPUESTA — no existe)
  │
  ├─ buildUpdatePayload(customer, existingBpCode)
  │   compara campos cambiados, construye payload mínimo
  │
  ├─ CustomerSapOutboundPort no se usa para PATCH (solo send=POST)
  │   → se usa SapClient.patch() directamente o un nuevo puerto
  │
  └─ SapClient.patch(S4_NATIVE, "A_BusinessPartner('C001')", ...)
      │  common/sap/SapClient.java
      └→ common/sap/WebClientSapClient.java
          exchange("PATCH", ...) → WebClient.patch()
          body: JSON de la entidad sin envolver (el wrapper "d" es solo de respuestas OData v2)
```

---

## Resumen de ficheros PROPUESTOS (no implementados)

| Fichero | Flujo | Función |
|---|---|---|
| `customer/bootstrap/web/LookupCustomerController.java` | F2 | Endpoint GET consulta BP |
| `customer/application/general/LookupCustomerUseCase.java` | F2 | Caso de uso consulta GET |
| `customer/bootstrap/web/CreateBusinessPartnerController.java` | F3 | Endpoint POST creación BP |
| `customer/application/general/CreateBusinessPartnerUseCase.java` | F3 | Caso de uso creación |
| `customer/bootstrap/web/BtpPendingController.java` | F4 | Endpoint GET /btp/pending |
| `customer/application/general/BtpPendingQueryUseCase.java` | F4 | Busca PENDING_SAP en state repo |
| `customer/bootstrap/web/BtpResultController.java` | F4 | Endpoint POST /btp/result |
| `customer/application/general/BtpResultProcessingUseCase.java` | F4 | Transiciona PENDING_SAP → SENT_SAP / SAP_ERROR |
| `customer/application/general/UpdateBusinessPartnerUseCase.java` | F6 | Caso de uso actualización BP |
