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
- `CustomerKafkaListener` — breakpoint en el consumer
- `SyncCustomerUseCase.java:89` — inicio del pipeline
- `BtpAddressAdapter.java:31` — antes de enviar a SAP
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
4. Sigue la cadena: adapter → SapJsonMapper → SapClient → WebClientSapClient

---
---

## Qué no está aquí

| Flujo | Estado | Dónde |
|---|---|---|
| Consulta de Business Partner por API (GET) | parcial: existe `BusinessPartnerReadAdapter`, falta el use case y el endpoint | [`../MEJORAS-Y-PROPUESTAS.md`](../MEJORAS-Y-PROPUESTAS.md) PRD-9 |
| Alta de Business Partner (POST) y actualización (PATCH) | propuesta | ídem, PRD-10 |
| Modo pull orquestado por BTP | propuesta | ídem, PRD-4 |
| Batch D+1 y eventos entrantes de S/4 | propuesta | ídem, PRD-5 y PRD-6 |

Estado por patrón de integración, con el detalle de qué está implementado:
[`INTEGRATION-PATTERNS.md`](INTEGRATION-PATTERNS.md).
