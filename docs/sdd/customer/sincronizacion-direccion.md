# Sincronización de la dirección del cliente

| | |
|---|---|
| **Dominio** | `customer` |
| **Estado** | ✅ implementado |
| **Entradas** | CDC (`outbox.CUSTOMER`) y REST `POST /customers/sync`, siempre a través de `SyncCustomerUseCase` |
| **Destino SAP** | BTP (por defecto) o S/4 nativo (`sap.odata.address.enabled=true`) |
| **Última revisión** | 2026-09-09 |

## 1. Objetivo

Mantener en SAP la dirección postal del cliente que vive en el legacy, de forma
que quien consulte el Business Partner en S/4 vea la misma dirección que el
sistema origen, sin duplicar envíos cuando el dato no ha cambiado.

## 2. Alcance

**Dentro**: validación de la dirección, envío a SAP y trazabilidad del estado de
esa dirección de forma independiente del resto del cliente.

**Fuera**:

- Direcciones múltiples por cliente: el modelo actual tiene **una** dirección
  por `Customer`.
- Email y teléfono, que en S/4 cuelgan de la dirección
  (`A_AddressEmailAddress`, `A_AddressPhoneNumber`): son de
  [`customer-contact`](../README.md#5-índice-de-features) y hoy no usan ese
  contrato — brecha abierta.
- Borrado de la dirección: `customer-delete` borra el cliente completo.

## 3. Entrada

La feature no se ingesta por su cuenta: recibe el `Customer` ya recuperado del
legacy por el pipeline agregado, más el `payloadHash` del mensaje que lo originó.

| Campo | Tipo | Obligatorio | Notas |
|---|---|---|---|
| `street` | texto | sí | calle y número |
| `city` | texto | sí | |
| `postalCode` | texto | no | si viene, debe cumplir R-4 |
| `country` | texto | sí | ISO-3166 alpha-2 |
| `region` | texto | no | comunidad/provincia |

Contrato común del mensaje de ingesta: [`../../architecture/TECH.md`](../../architecture/TECH.md) §6.

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | `country` presente y en ISO-3166 alpha-2 (`^[A-Z]{2}$`) | `INVALID` |
| R-2 | `city` presente y no en blanco | `INVALID` |
| R-3 | `street` presente y no en blanco | `INVALID` |
| R-4 | `postalCode`, si viene, cumple `^[A-Za-z0-9 \-]{3,12}$` | `INVALID` |
| R-5 | Una dirección nula es inválida | `INVALID` |

Las reglas se acumulan: `ValidationResult` reporta **todos** los motivos, no
solo el primero.

## 5. Salida

Un envío por dirección al destino SAP activo. El adaptador se elige por
configuración y ambos implementan el mismo puerto `AddressSapPort`:

| Destino | Adaptador | Ruta | Activación |
|---|---|---|---|
| BTP | `BtpAddressAdapter` | `sap.customer.btp.address-path` (por defecto `/sap/btp/odata/CustomerAddress`) | por defecto |
| S/4 nativo | `BusinessPartnerAddressODataAdapter` | `sap.odata.address-path` (por defecto `/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartnerAddress`) | `sap.odata.address.enabled=true` |

El payload se serializa **sin envolver** con `SapJsonMapper.write(...)`; el
`payloadHash` viaja como `Idempotency-Key`. API SAP: ver
[`../sap-api-catalog.md`](../sap-api-catalog.md).

## 6. Estados y errores

La dirección tiene **su propia línea de estado**, con clave
`<customerId>:ADDRESS` en el repositorio de estado, separada de la del cliente
agregado. Es un pipeline **por feature**, así que entra por `VALIDATING`
([`../../architecture/OVERVIEW.md`](../../architecture/OVERVIEW.md) §5: una
entidad sin historial entra por `RECEIVED` o por `VALIDATING`).

```
(sin historial) → VALIDATING → VALID → SENDING_SAP → SENT_SAP | SAP_ERROR
                            └→ INVALID
```

| Situación | Estado final | Reintentable |
|---|---|---|
| Dirección válida y SAP responde 2xx | `SENT_SAP` | — |
| Dirección incumple alguna regla de §4 | `INVALID` | sí, con un evento nuevo |
| SAP responde no-2xx | `SAP_ERROR` | sí, `SAP_ERROR → SENDING_SAP` |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dado un cliente con dirección válida, cuando se sincroniza, entonces se envía a SAP y termina en `SENT_SAP` | `SyncAddressUseCaseTest#happyPath` |
| AC-2 | Dado un cliente cuya dirección incumple una regla de §4, cuando se sincroniza, entonces termina en `INVALID` y **no** se llama a SAP | `SyncAddressUseCaseTest#invalidAddressReturnsInvalid` |
| AC-3 | Dado que SAP responde no-2xx, cuando se sincroniza, entonces termina en `SAP_ERROR` | `SyncAddressUseCaseTest#sapErrorReturnsSapError` |
| AC-4 | Dada una dirección **sin historial previo**, cuando se sincroniza, entonces la primera transición registrada es la entrada en `VALIDATING`, de modo que la máquina de estados la acepta | `SyncAddressUseCaseTest#firstSyncEntersThroughValidating` |
| AC-5 | Dada una dirección sin historial previo, cuando se sincroniza contra un repositorio que aplica la máquina de estados real, entonces el ciclo completa sin transiciones ilegales | `SyncAddressUseCaseTest#completesAgainstRealStateMachine` |
| AC-6 | Dada una dirección **ya enviada** (`SENT_SAP`), cuando llega un evento con cambios reales, entonces re-entra por `VALIDATING` y se envía de nuevo a SAP | `SyncAddressUseCaseTest#reSyncOfAnAlreadySentAddress` |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

Cada transición incrementa el contador por dominio y estado vía `SyncMetrics`,
con el estado como etiqueta. El histórico de la dirección es consultable por la
clave de feature `<customerId>:ADDRESS` en `sync_state`.

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| §3 value object | `domain/feature/address/AddressData.java` | — |
| R-1 … R-5 | `domain/feature/address/AddressValidator.java` | `AddressValidatorTest` |
| §5 envío y mapeo BTP | `adapters/sap/BtpAddressAdapter.java` | `BtpAddressContractTest` (módulo `it`) |
| §5 envío y mapeo S/4 | `adapters/sap/odata/BusinessPartnerAddressODataAdapter.java` | — |
| §6 recorrido de estados | `application/address/SyncAddressUseCase.java` | `SyncAddressUseCaseTest` |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-10 | AC-6: re-sincronización de una dirección ya enviada. La línea de feature quedaba en `SENT_SAP` y `SENT_SAP → VALIDATING` no estaba permitida: el segundo evento con cambios reales rompía el pipeline y acababa en la DLT. Regla en [`../common/maquina-de-estados.md`](../common/maquina-de-estados.md) | — |
| 2026-09-09 | Spec inicial, escrito al arreglar el pipeline por feature. Se documenta que la línea de estado de la feature entra por `VALIDATING` (AC-4/AC-5): el código no registraba esa entrada y la máquina rechazaba `null → VALID`, dejando `POST /customers/sync` en 500 | — |
