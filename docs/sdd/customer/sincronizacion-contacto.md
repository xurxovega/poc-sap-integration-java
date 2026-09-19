# Sincronización de contacto

| | |
|---|---|
| **Dominio** | `customer` |
| **Estado** | ⚠️ implementado con brechas — el destino en S/4 está **sin verificar contra un tenant real** |
| **Entradas** | CDC (`outbox.CUSTOMER`) · REST `POST /customers/sync` |
| **Destino SAP** | BTP (`BtpContactAdapter`) o S/4 nativo (`BusinessPartnerContactODataAdapter`), según `sap.odata.contact.enabled` |
| **Última revisión** | 2026-09-18 |

## 1. Objetivo

Que el email, el teléfono, el fax y la web de un cliente lleguen a SAP y se
puedan usar para comunicarse con él (facturas, avisos, cobros). Es la feature
`CONTACT` del agregado Customer.

## 2. Alcance

**Dentro**: validación de los cuatro datos de comunicación, su mapeo al contrato
de S/4 y su envío como parte del ciclo de sincronización del cliente.

**Fuera** (y por qué):

- **Personas de contacto** (un interlocutor con nombre, cargo y departamento):
  es otra cosa y hoy no se sincroniza. Ver §5.1.
- La verificación previa y el upsert como mecanismo:
  [`../common/upsert-idempotente-sap.md`](../common/upsert-idempotente-sap.md).
- La política de reintento y el transporte:
  [`../common/resiliencia-cliente-sap.md`](../common/resiliencia-cliente-sap.md).

## 3. Entrada

| Campo | Tipo | Obligatorio | Notas |
|---|---|---|---|
| `email` | texto | no | Formato de correo válido |
| `phone` | texto | no | Teléfono de contacto |
| `fax` | texto | no | Fax |
| `website` | texto | no | URL del sitio web |

**Clave de lookup de esta feature**: el **`AddressID`** de la dirección del
Business Partner. No es una clave propia: es la misma que persiste la feature
[ADDRESS](sincronizacion-direccion.md), en `sap_keys` bajo
`customer:<entityId>:ADDRESS`. Si no está guardada se resuelve navegando
`GET A_BusinessPartner('<id>')/to_BusinessPartnerAddress?$top=2`.

Contrato común del mensaje de ingesta:
[`../../architecture/TECH.md`](../../architecture/TECH.md) §6.

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | Un `email` presente debe tener formato de correo válido | `INVALID` |
| R-2 | Un campo **vacío o en blanco no se envía a SAP**: no se escriben cadenas vacías sobre datos que quizá ya estén bien | — |
| R-3 | Los datos de comunicación cuelgan del `AddressID` del cliente. **Sin dirección en SAP no se envía nada**: no es un alta pendiente, es que todavía no toca, y se reintenta con el ciclo siguiente | Escribirlos sin `AddressID` es imposible: es parte de la clave de las cuatro entidades |
| R-4 | Cada campo se verifica y se escribe por separado: el que ya existe se **actualiza** (`PATCH` con `If-Match`), el que falta se **da de alta** (`POST`) | Un alta ciega duplica la entrada de comunicación en cada ciclo |
| R-5 | Si el `GET` de un campo no concluye, se para: no se escriben los campos siguientes a ciegas | Empeorar un fallo parcial no ayuda a nadie |

## 5. Salida

### 5.1 Qué entidad de SAP recibe estos datos (decisión)

**Decisión: los datos van a las entidades de comunicación de la *dirección* del
Business Partner, no a `A_BusinessPartnerContact`.**

Hasta ahora el adaptador OData enviaba a `A_BusinessPartnerContact` un cuerpo con
`BusinessPartnerCompany` y `RelationshipCategory` y **nada más**: ni el email, ni
el teléfono, ni el fax, ni la web (hallazgo 2B-5). Y no podía llevarlos:

- `A_BusinessPartnerContact` modela la **relación con una persona de contacto**.
  Su contrato ([`../sap-api-catalog.md`](../sap-api-catalog.md)) exige
  `RelationshipNumber`, `BusinessPartnerCompany`, **`BusinessPartnerPerson`** y
  `ValidityEndDate`. `BusinessPartnerPerson` es otro Business Partner, de tipo
  persona, que habría que crear antes: no lo tenemos y el legacy no lo da.
- `ContactData` son email, teléfono, fax y web **de la empresa cliente**, no de
  una persona. En `API_BUSINESS_PARTNER` eso vive en cuatro entidades hijas de la
  dirección, con clave `(AddressID, Person, OrdinalNumber)`.

| Campo origen | Entidad SAP | Campo SAP |
|---|---|---|
| `email` | `A_AddressEmailAddress` | `EmailAddress` |
| `phone` | `A_AddressPhoneNumber` | `PhoneNumber` |
| `fax` | `A_AddressFaxNumber` | `FaxNumber` |
| `website` | `A_AddressHomePageURL` | `WebsiteURL` |

Las cuatro llevan además `AddressID` (del almacén de claves), `Person = ""`
(la dirección es de una organización, no de una persona) y `OrdinalNumber = "0"`
(primera entrada de cada tipo). `A_AddressHomePageURL` añade a la clave
`ValidityStartDate = datetime'0001-01-01T00:00:00'` —S/4 solo admite `00010101`—
e `IsDefaultURLAddress = true`.

> **A confirmar en tenant**: el `Person` vacío y el `OrdinalNumber` `"0"` para una
> organización, la clave completa de `A_AddressHomePageURL`, y si S/4 acepta el
> `PATCH` parcial de estas entidades.
> [`../../testing/CHECKLIST-TENANT-SAP.md`](../../testing/CHECKLIST-TENANT-SAP.md) §3.

Por la ruta **BTP** (`BtpContactAdapter`, el adaptador por defecto) el payload
sigue siendo el DTO propio del POC: no hay servicio real detrás.

## 6. Estados y errores

La feature tiene **su propia línea de estado**, con clave `<customerId>:CONTACT`,
y entra por `VALIDATING` como el resto de pipelines de feature.

| Situación | Estado final | Reintentable |
|---|---|---|
| Datos válidos y SAP responde 2xx | `SENT_SAP` | — |
| `email` con formato inválido | `INVALID` | sí, con un evento nuevo |
| Ningún dato de comunicación con valor | `SENT_SAP` (nada que enviar) | — |
| **Sin `AddressID`**: el BP todavía no tiene dirección en SAP | `COMMUNICATION_ERROR` | sí: la feature ADDRESS la creará |
| El `GET` de verificación no concluye (transporte, 5xx, ambiguo) | `COMMUNICATION_ERROR`, **sin escribir nada** | sí |
| SAP responde no-2xx a la escritura | `SAP_ERROR` | sí, `SAP_ERROR → SENDING_SAP` |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dado un cliente con email y teléfono, cuando se sincroniza el contacto por OData, entonces el payload que llega a SAP **lleva esos datos** y el `AddressID` de su dirección | `BusinessPartnerContactODataAdapterTest#payloadCarriesTheContactData` · `it/…/S4ContactCommunicationContractTest#realAdapterPostsEmailAndPhoneUnderTheAddressId` |
| AC-2 | Dado un campo vacío o en blanco, entonces no genera ninguna llamada a SAP | `BusinessPartnerContactODataAdapterTest#emptyFieldsAreNotSent` |
| AC-3 | Dado un `AddressID` ya guardado, entonces se reutiliza sin volver a navegar desde el BP | `BusinessPartnerContactODataAdapterTest#lookupResolvesTheAddressIdOfTheBusinessPartner` |
| AC-4 | Dado un cliente sin dirección en SAP, entonces la verificación previa es no concluyente y **no se escribe nada** | `BusinessPartnerContactODataAdapterTest#withoutAnAddressInSapNothingIsWritten` · `#aFailedFieldLookupStopsTheWrite` |
| AC-5 | Dado un campo que ya existe en SAP y otro que no, entonces el primero se actualiza con `If-Match` y el segundo se da de alta | `BusinessPartnerContactODataAdapterTest#existingCommunicationIsPatchedAndMissingOnesArePosted` |
| AC-6 | Las reglas de validación del contacto se aplican antes de enviar | `ContactValidatorTest` · `ValidateContactUseCaseTest` · `SyncContactUseCaseTest` |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

El `detail` del `COMMUNICATION_ERROR` dice cuál de los dos casos es («sin
AddressID para C-1: la dirección del BP todavía no existe en SAP» frente a un
fallo de transporte), que es lo que decide si hay que mirar SAP o mirar la red.

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| R-1 | `customer/domain/feature/contact/ContactValidator.java` | `ContactValidatorTest` |
| R-2..R-5, §5.1 | `customer/adapters/sap/odata/BusinessPartnerContactODataAdapter.java` | `BusinessPartnerContactODataAdapterTest` AC-1..AC-5 |
| Ruta BTP | `customer/adapters/sap/BtpContactAdapter.java` | `BtpContactAdapterTest` |
| Puerto | `customer/domain/port/ContactSapPort.java` | — |
| Orquestación | `customer/application/contact/SyncContactUseCase.java` | `SyncContactUseCaseTest` |
| §5.1 contrato real | `it/…/contract/S4ContactCommunicationContractTest` | AC-1 |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-18 | Spec inicial (hallazgo 2B-5). Documenta y corrige que el payload OData no llevaba ningún dato de contacto: `A_BusinessPartnerContact` exige una persona de contacto que no tenemos, así que email, teléfono, fax y web pasan a las entidades de comunicación de la dirección, con verificación previa campo a campo | — |
