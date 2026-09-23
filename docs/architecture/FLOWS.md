# Flujos de integración SAP

> Mapas de proceso para seguir el código desde la entrada hasta la salida.
> Cada flecha (`→`) apunta a la clase/fichero **real** en el repositorio: usa
> estos flujos para navegar con "buscar clase" en el IDE.
>
> Aquí solo hay **lo que existe**. Los flujos propuestos (consulta y alta de
> Business Partner por API, modo pull, actualización por PATCH) viven en
> [`../MEJORAS-Y-PROPUESTAS.md`](../MEJORAS-Y-PROPUESTAS.md), con el detalle de
> las clases que harían falta.
>
> Qué **debe** hacer cada feature: [`../sdd/`](../sdd/README.md). Cómo encaja en
> la arquitectura: [`OVERVIEW.md`](OVERVIEW.md).

---

## Flujo 1 — CDC completo (Kafka → SAP)

Flujo principal de sincronización: llega un mensaje Kafka desde el outbox legacy,
se valida, se persiste imagen y estado, y se envía a SAP.

> El mismo mensaje de Kafka puede terminar en BTP (`BtpAddressAdapter`) o en la
> API OData directa de S/4 (`BusinessPartnerAddressODataAdapter`) según qué
> adaptador esté activo por configuración — ver el mapa de rutas abajo.

```
Kafka topic outbox.CUSTOMER
  │
  ▼
customer/bootstrap/kafka/CustomerKafkaListener.java
  │  recibe ConsumerRecord → IngestionMessage (aviso FINO: entityId, operation,
  │  occurredAt; sin datos ni hash — ADR-0013). Acepta también el formato antiguo
  │  decide qué features ejecutar (todas)
  ▼
customer/application/general/SyncCustomerUseCase.java
  │  aggregateStateOf() calcula el estado del agregado a partir del resultado
  │  de cada feature (sendFeatures); reintentos ya enviados se relanzan igual
  │
  ├─[1] CustomerLegacyRepositoryPort.fetch(entityId)   ← UNICA fuente de datos
  │      └→ customer/adapters/persistence/SqlServerCustomerRepository.java
  │         consulta tabla dbo.customers en SQL Server
  │
  ├─[1b] PayloadHasher.hash(customer)  → payloadHash del ciclo
  │      └→ common/domain/PayloadHasher.java (SHA-256 sobre la forma canonica)
  │         con el se deduplica (alreadySent) y con el se escribe todo el ciclo
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
  └─[5] FeatureSyncPipeline<AddressData>.sync(entityId, cycleId, payloadHash, address)
         │  common/application/FeatureSyncPipeline.java — un pipeline por
         │  feature (ADDRESS/FISCAL/CONTACT/BANKING), la línea de estado es
         │  "<entityId>:<FEATURE>" y hereda el cycleId del agregado
         │
         ├─ validator.apply(address)  (p.ej. AddressValidator) → VALID | INVALID
         ├─ write(entityId, payloadHash, address)   — upsert idempotente
         │   │  (spec docs/sdd/common/upsert-idempotente-sap.md)
         │   │
         │   ├─ AddressSapPort.lookup(entityId, address)   (GET, opcional)
         │   │   └→ SapOutboundPort.SapLookup: FOUND / NOT_FOUND / UNAVAILABLE /
         │   │      NOT_SUPPORTED (los adaptadores Btp* no lo implementan: van
         │   │      directos a send() como antes de 2026-09-18)
         │   │
         │   ├─ si FOUND  → AddressSapPort.update(entityId, hash, address, found)
         │   │                (PATCH con If-Match)
         │   ├─ si NOT_FOUND/NOT_SUPPORTED → AddressSapPort.send(entityId, hash, address)
         │   │                (POST)
         │   └─ si UNAVAILABLE → no se escribe nada; lanza SapLookupUnavailableException
         │       │  customer/domain/port/AddressSapPort.java
         │       │
         │       └→ customer/adapters/sap/BtpAddressAdapter.java  (ruta BTP)
         │          │  customer/adapters/sap/dto/BtpAddressDto.from(address)
         │          │  common/sap/json/SapJsonMapper.write(dto)
         │          │  common/sap/SapClient.send/get/patch(BTP, path, ...)
         │          │
         │          └→ common/sap/RestClientSapClient.java  (ADR-0001)
         │             │  exchange(POST/GET/PATCH, ...) → RestClient.method(...)
         │             │  Resilience4j retry (por método/fase, ver §5.1 de
         │             │  GUIA-PRUEBAS.md) + circuit breaker
         │             │  auth via SapAuthProvider → Bearer token
         │             └→ SAP BTP API
         │
         ├─ resultado → FeatureOutcome(feature, SyncState, detail, instant)
         │   estados: SENT_SAP | SAP_ERROR (HTTP recibido) |
         │   COMMUNICATION_ERROR (circuito abierto / lookup no concluyente /
         │   transporte agotado) — nunca relanza el fallo de SAP (ADR-0010);
         │   la única excepción que sí sube es ConcurrentTransitionException
         │   (otro ciclo con distinto `from`/`cycleId` movió la cabecera antes:
         │   el llamante REST recibe 409, SyncCustomerController)
         │
         └─ common/adapters/persistence/MongoSyncStateRepository.java
            persiste cada transición (con cycleId y detail) en sync_state;
            si el lookup encontró clave/ETag, se guarda en la colección
            sap_keys (MongoSapKeyStore) para el próximo ciclo
```

Si una o varias features fallan (`SyncPartialFailure`), el agregado queda
marcado para revisión con la traza completa de qué parte llegó y cuál no —
consultable con `GET /customers/{id}/state`
(`CustomerStateController` → `CustomerStateUseCase`, que expone `LineState`
por feature con su `CycleTrace` de `Step`s).

**Puntos de entrada para debuggear:**
- `CustomerKafkaListener` — breakpoint en el consumer
- `SyncCustomerUseCase.java` — inicio del pipeline (`sendFeatures`/`aggregateStateOf`)
- `FeatureSyncPipeline.sync()` — antes/después de `write()` (lookup + send/update)
- `BtpAddressAdapter` — antes de enviar a SAP
---

## Flujo 2 — Mapa de rutas (BTP / OData directo)

Qué adaptador se activa y a qué destino SAP llama. Ambas rutas conviven: se
elige por configuración, feature a feature.

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
│ BankingSapPort   │ BtpBankingAdapter     │ BTP              │ Push       │
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

Propiedades en application-common.yml:
  sap.odata.address.enabled      (default: false)
  sap.odata.fiscal.enabled       (default: false)
  sap.odata.contact.enabled      (default: false)
  sap.odata.banking.enabled      (default: false)
  sap.odata.customer.enabled     (default: false)
  sap.odata.read.enabled         (default: false)
```

**Cómo seguir una ruta en el código:**
1. Busca el puerto en `customer/domain/port/<Puerto>.java`
2. Abre "Find Usages" (Ctrl+Alt+F7 en IntelliJ)
3. Spring inyecta el adaptador activo según `@ConditionalOnProperty`
4. Sigue la cadena: adapter → SapJsonMapper → SapClient → RestClientSapClient

---
---

## Qué no está aquí

| Flujo | Estado | Dónde |
|---|---|---|
| Alta de Business Partner (POST) y actualización (PATCH) | propuesta | [`../MEJORAS-Y-PROPUESTAS.md`](../MEJORAS-Y-PROPUESTAS.md) PRD-10 |
| Consulta de Business Partner por API (GET) | ✅ implementado 2026-09-24: `LookupBusinessPartnerUseCase` + `BusinessPartnerController` + spec [`../sdd/customer/consulta-business-partner-sap.md`](../sdd/customer/consulta-business-partner-sap.md) | ya no en backlog |
| Modo pull orquestado por BTP | propuesta | ídem, PRD-4 |
| Batch D+1 y eventos entrantes de S/4 | propuesta | ídem, PRD-5 y PRD-6 |

Estado por patrón de integración, con el detalle de qué está implementado:
[`INTEGRATION-PATTERNS.md`](INTEGRATION-PATTERNS.md).
