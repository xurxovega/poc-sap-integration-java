# it — pruebas de integración cross-dominio + contratos SAP

Módulo **sin código de producción**. Contiene las pruebas que cruzan
módulos o ejercitan la infraestructura real:

- **IT cross-dominio** con Testcontainers (Mongo, Kafka): reproducen
  escenarios que los tests unit/slice no cazan (ver
  [`docs/testing/TESTING.md`](../docs/testing/TESTING.md) §TEST-1).
- **Contract tests** contra los adaptadores SAP reales, usando WireMock
  como stub del backend S/4. Antes los contracts stubbeaban el cliente
  HTTP y nunca tocaban código de producción (auditoría B6); ahora
  ejercitan `RestClientSapClient` end-to-end.
- **Smoke IT** de infraestructura: `InfrastructureSmokeIT` levanta
  Kafka/Redpanda y Mongo con Testcontainers.

## Tests incluidos

| Clase | Tipo |
|---|---|
| `InfrastructureSmokeIT` | IT (Testcontainers) |
| `SyncStateMongoIT` | IT (Testcontainers Mongo) |
| `TestCountMatchesDocsTest` | unit (vigila que la cifra declarada de `@Test` en `docs/testing/TESTING.md` §1 coincide con la real; rompe el build si no) |
| `contract/AbstractSapContractTest` | base de los contratos |
| `contract/BtpAddressContractTest` | contract |
| `contract/BtpBankingContractTest` | contract |
| `contract/BtpContactContractTest` | contract |
| `contract/BtpCustomerContractTest` | contract |
| `contract/BtpFiscalContractTest` | contract |
| `contract/BusinessPartnerAddressUpsertContractTest` | contract |
| `contract/BusinessPartnerUpsertContractTest` | contract |
| `contract/S4ArticleContractTest` | contract |
| `contract/S4BankODataContractTest` | contract |
| `contract/S4ContactCommunicationContractTest` | contract |
| `contract/SepaMandateContractTest` | contract |

## Comandos

```bash
mvn -pl it verify                            # contracts + IT sin Docker
mvn -pl it verify -Ddocker.available=true    # + Testcontainers (Mongo, Kafka/Redpanda)
```

> La CI tiene dos jobs (`build` sin Docker, `e2e-docker` con Docker); ver
> [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

## Cómo añadir un IT

- Si es contrato SAP: extender `AbstractSapContractTest` y registrar el
  caso (path, método, payload, código esperado).
- Si es integración cross-dominio: usar `@Testcontainers` con
  `RedpandaContainer` (no `ConfluentKafkaContainer`, deprecado) y
  `MongoDBContainer`.
- Si toca la API REST: usar `MockMvcBuilders.standaloneSetup` (Boot 4
  eliminó `@WebMvcTest`).