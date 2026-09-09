# SDD — Spec-Driven Development

> Carpeta ancla del proyecto. Aquí vive **qué debe hacer** cada feature; en
> [`../architecture/`](../architecture/) vive **cómo está construido** el sistema
> y en [`../development/README.md`](../development/README.md) **cómo se trabaja**.
> Este fichero es además el estado global del proyecto (índice de features +
> changelog).

## 1. La regla del ancla (bidireccional)

El spec y el código son **el mismo hecho contado dos veces**. No pueden divergir:

| Si cambia… | …entonces |
|---|---|
| **el spec** | se cambian los tests y el código en el **mismo PR**; el criterio de aceptación nuevo empieza en rojo (TDD) |
| **el código** (comportamiento observable) | se actualiza el spec de la feature en el **mismo PR**, con su entrada en *Cambios* |
| **un contrato SAP** ([`sap-api-catalog.md`](sap-api-catalog.md)) | se revisan los specs de las features que lo consumen antes de tocar adaptadores |

Un PR que cambia comportamiento sin tocar spec está **incompleto**, igual que un
spec cambiado sin tests que lo respalden. Cada criterio de aceptación del spec
se corresponde con **al menos un test** que lo cita.

## 2. Estructura

```
docs/sdd/
├── README.md              este índice + criterios globales + changelog
├── _template/spec.md      plantilla para una feature nueva
├── sap-api-catalog.md     contratos externos: specs OpenAPI oficiales de SAP
└── <feature>/             una carpeta por feature o flow
    └── spec.md            el spec ancla de esa feature
```

Convenciones:

- Una carpeta **por feature o por flow**, no por clase ni por módulo Maven.
- Nombre en `kebab-case` y orientado a negocio: `customer-address-sync`,
  no `BtpAddressAdapter`.
- Si una feature necesita material extra (ejemplos de payload, tablas de mapeo),
  va dentro de su carpeta, nunca duplicado en `architecture/`.
- El spec describe **comportamiento observable**; los detalles de stack se
  enlazan a [`../architecture/TECH.md`](../architecture/TECH.md) en vez de copiarse.

## 3. Ciclo de trabajo

`spec` → tests en rojo → código → refactor → actualizar estado aquí.
El detalle del ciclo y las reglas de TDD están en
[`../development/README.md`](../development/README.md).

## 4. Criterios de aceptación globales

Aplican a **toda** feature, además de los suyos propios:

- La feature se procesa por el **mismo pipeline** venga de CDC, evento Kafka o REST.
- El envío a SAP es **idempotente** y verificable por `payloadHash`.
- El estado del registro es **consultable**: imagen actual (Mongo) + histórico (ES).
- Cada transición de estado emite **métrica** por dominio y estado.
- Un cambio en la feature **no obliga a desplegar otros dominios** (salvo cambio en `common`).
- Cobertura: 100 % unit sobre las validaciones de `domain`; al menos una
  integración end-to-end con infraestructura real (broker, BD, SAP mock).

## 5. Índice de features

| Feature | Dominio | Spec | Estado del código |
|---|---|---|---|
| customer-sync (aggregate) | customer | ⬜ pendiente de escribir | ✅ implementado (`SyncCustomerUseCase`) |
| customer-address | customer | ⬜ pendiente de escribir | ✅ implementado (BTP + OData) |
| customer-fiscal | customer | ⬜ pendiente de escribir | ✅ implementado |
| customer-contact | customer | ⬜ pendiente de escribir | ⚠️ implementado sin el contrato real de S/4 (ver brechas) |
| customer-banking | customer | ⬜ pendiente de escribir | ⚠️ implementado; mandatos no llegan del legacy |
| customer-delete | customer | ⬜ pendiente de escribir | ✅ implementado |
| article-sync | article | ⬜ pendiente de escribir | ✅ implementado |
| supplier-sync | supplier | ⬜ pendiente de escribir | 🔮 placeholder |

> Las features ya implementadas se escribieron antes de adoptar SDD. Regla de
> transición: **la primera vez que se toca una feature, se escribe su spec** a
> partir de [`_template/spec.md`](_template/spec.md). No se escriben todos los
> specs de golpe para no generar documentación muerta.

## 6. Changelog global

Estado de las brechas detectadas sobre el código real.

### Resueltas

| Brecha | Dónde se resolvió | Fecha |
|---|---|---|
| Auth SAP era stub | `common/sap/auth/` — `OAuth2TokenClient` con client-credentials real; `BtpAuthProvider`/`S4NativeAuthProvider` caen a token stub solo si falta configuración | 2026-07-25 |
| Retry/circuit breaker Resilience4j no disparaba | `WebClientSapClient` decora las llamadas con `Retry` + `CircuitBreaker` de los registries | 2026-07-25 |
| Sin DLQ Kafka | `KafkaErrorHandlingConfig` (customer y article): reintentos con backoff + `DeadLetterPublishingRecoverer` → topic `<topic>.DLT` | 2026-07-25 |
| Sin timeout WebClient SAP | `sap.client.connect-timeout-ms` / `sap.client.response-timeout-ms` en `application-common.yml` | 2026-07-25 |
| CSRF no cableado | `common/sap/odata/CsrfTokenProvider` + `S4CsrfTokenProvider` (fetch de `x-csrf-token` para POST/PATCH/DELETE) | 2026-07-25 |
| Sin idempotencia de consumo | dedupe por `payloadHash` en `SyncCustomerUseCase` (`stateRepo.alreadySent(...)`) | 2026-07-25 |
| Debezium/outbox no cableado | `external-services/`: tablas outbox + triggers (`sqlserver/init.sql`, `postgresql/init.sql`), servicio `kafka-connect` y conectores en `debezium/` | 2026-07-25 |

### Pendientes

| Brecha | Dónde | Impacto |
|---|---|---|
| Sin transacción distribuida / saga entre features | `SyncCustomerUseCase` (envíos por feature independientes) | fallos parciales dejan SAP a medias, sin compensación |
| Contactos no usan `A_AddressEmailAddress`/`A_AddressPhoneNumber` | adaptadores de CONTACT | el contrato real de S/4 para email/teléfono es por dirección |
| Mandatos no llegan desde el legacy | `S4BankingAdapter` (mandates) | BANKING incompleto |
| Atomicidad Mongo / race condition en estado sync | `MongoSyncStateRepository.transition` | inconsistencias bajo concurrencia |
| Sin mapeo fino de errores SAP | `WebClientSapClient` | diagnóstico deficiente |
| `supplier` vacío + MinIO sin uso | `SupplierApplicationPlaceholder`, compose | dominio/infra no operativos |
| APIs REST sin autenticación | controllers de `customer`/`article` | bloqueante para exponer las APIs a terceros o a un MCP ([`../integrations/MCP.md`](../integrations/MCP.md) §4) |
| Servidor MCP para agentes IA (propuesta) | servicio `mcp-server` futuro | requiere autenticación + ofuscación de PII — ver [`../integrations/MCP.md`](../integrations/MCP.md) |

## 7. Supuestos vigentes del PoC

- **Auth SAP**: OAuth2 client-credentials vía variables de entorno; con
  configuración incompleta se usa token stub (solo para mocks locales).
- **Cliente HTTP SAP definitivo**: `WebClientSapClient`. `sap-sdk-client/` es un
  spike OpenAPI **desechable**, no forma parte del reactor.
- **Debezium/outbox**: cableado en `external-services/` (triggers + Kafka
  Connect); la operación en entornos reales sigue siendo externa.
- **`supplier`**: futuro, patrón simple como `article`.
- **MinIO**: sin uso; posible futuro `S3ImageStoreAdapter` si hace falta blob storage.
