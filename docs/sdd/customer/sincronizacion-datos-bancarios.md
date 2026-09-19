# Sincronización de datos bancarios del cliente

| | |
|---|---|
| **Dominio** | `customer` |
| **Estado** | ⚠️ implementado con brechas: los mandatos no llegan del legacy y los contratos S/4 están pendientes de validar contra el tenant de test |
| **Entradas** | CDC (`outbox.CUSTOMER`) y REST `POST /customers/sync`, siempre a través de `SyncCustomerUseCase` → `SyncBankingUseCase` |
| **Destino SAP** | BTP (por defecto) o S/4 nativo (`sap.odata.banking.enabled=true`); el mandato SEPA siempre va a S/4 (`API_APAR_SEPA_MANDATE_SRV`) |
| **Última revisión** | 2026-09-18 |

## 1. Objetivo

Que la cuenta bancaria principal del cliente y sus mandatos SEPA existan en SAP
con el **contrato real** de cada API, para que Finanzas pueda domiciliar cobros.
Hasta la Fase 3.2 del plan de auditoría el BIC viajaba en un campo que no es el
suyo y los mandatos iban a una API que no existe (B3).

## 2. Alcance

**Dentro**: cuenta principal (IBAN, BIC), lista de identificadores de mandato,
alta de mandato SEPA con sus datos propios (`Mandate`), correspondencia de
estados.

**Fuera** (y por qué):
- La **baja** del mandato: [`baja-mandato-sepa.md`](baja-mandato-sepa.md).
- Varias cuentas por cliente: el legacy expone una; `BankIdentification` está
  preparado para numerar más (`0001`, `0002`...).
- Datos completos del mandato desde el legacy: hoy solo llegan los
  identificadores (`mandateIds`). El alta de mandato es un contrato listo para
  cuando el legacy los emita ([`../README.md`](../README.md) §6).
- Upsert idempotente de la cuenta (lookup + `PATCH`): Fase 3.3 del plan.

## 3. Entrada

`BankingData` dentro del agregado `Customer`, y `Mandate` como entidad propia.

| Campo | Tipo | Obligatorio | Notas |
|---|---|---|---|
| `iban` | texto | sí para enviar | Se valida formato y país en `BankingValidator` |
| `bic` | texto | no | Solo la ruta BTP y el mandato lo transportan |
| `mandateIds` | lista | no | Referencias de mandatos; nunca `null` |
| `Mandate.id` | texto | sí | Referencia única del mandato para el acreedor |
| `Mandate.customerId` | texto | sí | BP deudor (`Sender`) |
| `Mandate.iban` | texto | sí | `SenderIBAN` |
| `Mandate.bic` | texto | no | `SenderBankSWIFTCode` |
| `Mandate.signatureDate` | ISO-8601 | no | `SEPASignatureDate` |
| `Mandate.status` | `ACTIVE` \| `REVOKED` \| `EXPIRED` | no (por defecto `ACTIVE`) | Ver R-5 |

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | La ruta **BTP** envía nuestro contrato propio (`BusinessPartner`, `IBAN`, `BIC`, `Mandates` como lista) al endpoint `CustomerBanking`, con el mismo destino y familia que el resto de features | — |
| R-2 | En **S/4** (`A_BusinessPartnerBank`) `BankIdentification` es el ordinal de la cuenta dentro del BP (`0001`), **nunca el BIC**. El BIC pertenece al maestro de bancos y al mandato, no a esta entidad | SAP rechaza o guarda un identificador sin sentido (auditoría B3) |
| R-3 | `BankCountryKey` se deriva de los dos primeros caracteres del IBAN | SAP exige el país del banco |
| R-4 | El **mandato SEPA** se da de alta en `API_APAR_SEPA_MANDATE_SRV/SEPAMandateSet` con la clave `(Creditor, SEPAMandate)`. `Creditor` es el identificador de acreedor SEPA de la empresa (`sap.sepa.creditor-id`); si no está configurado el adaptador **falla antes de llamar a SAP** con un mensaje que lo dice | Sin acreedor la API no puede identificar el mandato |
| R-5 | Correspondencia de estados hacia `SEPAMandateStatus`: `ACTIVE → 1`, `REVOKED → 3` (cancelado), `EXPIRED → 4` (completado). **Pendiente de validar** contra el tenant de test | — |
| R-6 | Un `Mandate` nulo no se envía: es un error de programación, no una baja | — |


**Claves de lookup de esta feature**: la cuenta bancaria se verifica por
`(BusinessPartner, BankIdentification)` y el mandato por `(Creditor, SEPAMandate)`.
Las dos son **deterministas** hoy, así que no se persiste nada: la identificación
bancaria es el ordinal fijo `0001` y el acreedor es configuración. **En cuanto
haya varias cuentas por cliente dejará de serlo** y habrá que guardar el mapa
IBAN → `BankIdentification` en `sap_keys`. Mecanismo:
[`../common/upsert-idempotente-sap.md`](../common/upsert-idempotente-sap.md).

## 5. Salida

**Ruta BTP** (`BtpBankingAdapter` → `POST /sap/btp/odata/CustomerBanking`):

| Campo origen | Campo BTP | Transformación |
|---|---|---|
| `entityId` | `BusinessPartner` | — |
| `iban` | `IBAN` | `null → ""` |
| `bic` | `BIC` | `null → ""` |
| `mandateIds` | `Mandates` | lista JSON (antes texto separado por comas) |

**Ruta S/4** (`BusinessPartnerBankODataAdapter` → `POST …/A_BusinessPartnerBank`):

| Campo origen | Campo SAP | Transformación |
|---|---|---|
| `entityId` | `BusinessPartner` | — |
| — | `BankIdentification` | constante `0001` (R-2) |
| `iban` | `IBAN` | tal cual |
| `iban[0..2]` | `BankCountryKey` | mayúsculas, sin espacios (R-3) |
| `bic` | *(no viaja)* | R-2 |

**Mandato SEPA** (`SepaMandateODataAdapter` → `POST …/SEPAMandateSet`):

| Campo origen | Campo SAP | Transformación |
|---|---|---|
| `sap.sepa.application` | `SEPAMandateApplication` | por defecto `F` |
| `sap.sepa.creditor-id` | `Creditor` | obligatorio (R-4) |
| `id` | `SEPAMandate` | — |
| — | `SenderType` | `BUS1006` (Business Partner) |
| `customerId` | `Sender` | — |
| `iban` | `SenderIBAN` | — |
| `bic` | `SenderBankSWIFTCode` | `null → ""` |
| `signatureDate` | `SEPASignatureDate` | `null → ""` |
| `status` | `SEPAMandateStatus` | R-5 |

API SAP consumida: ver [`../sap-api-catalog.md`](../sap-api-catalog.md).

## 6. Estados y errores

Línea de feature `<<customerId>>:BANKING`, entra por `VALIDATING`
([`../common/maquina-de-estados.md`](../common/maquina-de-estados.md)).

| Situación | Estado final | Reintentable |
|---|---|---|
| IBAN inválido | `INVALID` | con un evento corregido |
| SAP acepta | `SENT_SAP` | — |
| SAP rechaza (4xx/5xx agotados) | `SAP_ERROR` | con el siguiente evento |
| `sap.sepa.creditor-id` ausente al enviar un mandato | `ERROR` (excepción de configuración, se propaga) | tras configurar |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dada una `BankingData`, cuando se envía por la ruta BTP, entonces el cuerpo lleva `BusinessPartner`, `IBAN`, `BIC` y `Mandates` como lista, al destino BTP | `BtpBankingAdapterTest#sendsBankingContractToBtpWithMandatesAsList` · `#emptyMandateListSerializesAsEmptyArray` · `BtpBankingContractTest#realAdapterPostsBankingContractWithMandateList` (HTTP real) |
| AC-2 | Dada una `BankingData` con BIC, cuando se envía por S/4, entonces `BankIdentification` es `0001` y el BIC **no** aparece en el cuerpo | `BusinessPartnerBankODataAdapterTest#bankIdentificationIsAnOrdinalAndBicIsNotSent` · `S4BankODataContractTest#realAdapterPostsBankWithoutBicAndWithCountryFromIban` |
| AC-3 | `BankCountryKey` son los dos primeros caracteres del IBAN, en mayúsculas y sin espacios | `BusinessPartnerBankODataAdapterTest#bankCountryKeyComesFromTheIban` |
| AC-4 | Dado un `Mandate`, cuando se da de alta, entonces va a `SEPAMandateSet` con `Creditor`, `SEPAMandate`, `Sender`, `SenderIBAN`, `SenderBankSWIFTCode`, `SEPASignatureDate` y `SEPAMandateStatus` | `SepaMandateODataAdapterTest#createPostsTheRealSepaMandateContract` · `SepaMandateContractTest#realAdapterCreatesMandateOnSepaMandateSet` |
| AC-5 | Sin `sap.sepa.creditor-id`, cuando se intenta enviar o revocar, entonces falla con `IllegalStateException` que nombra la propiedad y **no** llama a SAP | `SepaMandateODataAdapterTest#missingCreditorFailsFastWithoutCallingSap` |
| AC-6 | La correspondencia de estados es `ACTIVE→1`, `REVOKED→3`, `EXPIRED→4`, `null→1` | `SepaMandateODataAdapterTest#statusMappingFollowsS4Codes` |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

Métricas de estado por dominio (`SyncMetrics`) con `stage=banking`. Log `ERROR`
del cliente SAP con método, destino y `entityId` al agotar reintentos.

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| R-1, §5 BTP | `adapters/sap/BtpBankingAdapter.java` · `adapters/sap/dto/BtpBankingDto.java` | `BtpBankingAdapterTest` · `BtpBankingContractTest` |
| R-2, R-3, §5 S/4 | `adapters/sap/odata/BusinessPartnerBankODataAdapter.java` | `BusinessPartnerBankODataAdapterTest` · `S4BankODataContractTest` |
| R-4, R-5, R-6, §5 mandato | `adapters/sap/odata/SepaMandateODataAdapter.java` · `domain/port/MandateSapOutboundPort.java` | `SepaMandateODataAdapterTest` · `SepaMandateContractTest` |
| Configuración | `application-common.yml` (`sap.odata.mandate-path`, `sap.sepa.*`) | — |
| Orquestación de la feature | `application/banking/SyncBankingUseCase.java` · `ValidateBankingUseCase.java` | `SyncBankingUseCaseTest` · `BankingValidatorTest` |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-18 | Verificación previa de la cuenta bancaria y del mandato SEPA por su clave compuesta: lo que SAP ya tiene se actualiza con `If-Match`. §3 avisa de que con varias cuentas la clave deja de ser determinista | — |
| 2026-09-12 | Spec inicial (plan Fase 3.2, auditoría B3). `S4BankingAdapter` → `BtpBankingAdapter` (misma familia BTP que el resto; antes apuntaba a `API_CUSTOMER_MANDATE`, inexistente). En S/4, `BankIdentification` deja de llevar el BIC y se añade `BankCountryKey`. Alta de mandato SEPA con el contrato real de `API_APAR_SEPA_MANDATE_SRV` y `Creditor` obligatorio por configuración | — |
