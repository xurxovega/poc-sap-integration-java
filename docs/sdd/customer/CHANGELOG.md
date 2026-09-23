# Changelog — dominio customer

> Índice cronológico de los cambios de las features del dominio `customer`. Una línea por cambio;
> el detalle vive en la sección **Cambios** del spec de cada feature, y la
> información ampliada (quién lo pidió, cuándo, estado) en el registro MySQL
> `sdd_registry` — ver [`../README.md`](../README.md) §8.

| Fecha | Feature | Cambio |
|---|---|---|
| 2026-09-19 | [Sincronizacion de cliente](sincronizacion-cliente.md) | La entrada pasa a ser el aviso **fino**: ni el payload ni el hash del mensaje son fuente de nada. El use case relee SQL Server y calcula el hash sobre ese snapshot, que es el del dedupe, el del ciclo y el que va a SAP (ADR-0013) |
| 2026-09-18 | [Sincronización de contacto](sincronizacion-contacto.md) | Spec inicial (2B-5). El payload que se enviaba a SAP no llevaba **ni un solo dato** de contacto. Email, teléfono, fax y web pasan a las entidades de comunicación de la dirección del cliente (`A_AddressEmailAddress`, `A_AddressPhoneNumber`, `A_AddressFaxNumber`, `A_AddressHomePageURL`), colgadas del `AddressID` |
| 2026-09-18 | [Sincronización de dirección](sincronizacion-direccion.md) | Verificación previa y `AddressID` persistido: una dirección ya existente se **actualiza** en vez de crear una nueva en cada ciclo (2B-6) |
| 2026-09-18 | [Sincronización del cliente](sincronizacion-cliente.md) | Verificación previa del Business Partner: si SAP ya lo tiene se actualiza con `If-Match`; si el `GET` no responde no se escribe nada |
| 2026-09-18 | [Sincronización del cliente](sincronizacion-cliente.md) | El fallo parcial se marca y se avisa siempre, con el motivo de cada parte y la traza de pasos del ciclo en `sap.sync.alerts` y en `GET /customers/{id}/state`; el agregado adopta la regla de cero confianza (D-14) y solo se reintenta el mensaje cuando es demostrable que nada llegó a SAP (D-15) |
| 2026-09-14 | [Sincronización del cliente](sincronizacion-cliente.md) | D-2 (ADR-0010): sin compensación; aviso de sincronización parcial (log, `sap.sync.alerts`, métrica) y `GET /customers/{id}/state` con el estado de cada parte |
| 2026-09-12 | [Sincronización del cliente](sincronizacion-cliente.md) | Fase 6: R-1 dedupe contra el último `SENT_SAP`; R-8 imagen solo tras `SENT_SAP`, histórico con un documento por intento (AC-7) |
| 2026-09-12 | [Sincronización de datos bancarios](sincronizacion-datos-bancarios.md) | Spec inicial (Fase 3.2, B3). `S4BankingAdapter` → `BtpBankingAdapter`; en S/4 el BIC sale de `BankIdentification` (ordinal `0001`) y se añade `BankCountryKey`; alta de mandato SEPA con el contrato real de `API_APAR_SEPA_MANDATE_SRV` |
| 2026-09-12 | [Baja de mandato SEPA](baja-mandato-sepa.md) | Spec inicial (Fase 3.2, B3/A18). La baja revoca por `PATCH` de estado (`SEPAMandateStatus=3`) vía `MandateSapOutboundPort.revoke`; antes enviaba un payload bancario ficticio a una API inexistente |
| 2026-09-12 | [Baja de cliente](baja-cliente.md) | AC-2 cubierto además por un contract test con HTTP real: `DELETE /sap/btp/odata/Customer('C-1')` sin ningún POST (Fase 2, auditoría B6) |
| 2026-09-11 | [Sincronización del cliente](sincronizacion-cliente.md) | Spec inicial (Fase 1 auditoría). R-5: un evento nuevo siempre abre ciclo, también desde `SAP_ERROR` o un ciclo en vuelo; R-6: un fallo de infraestructura tras `VALID` deja `ERROR` y se propaga. Antes un cliente con un fallo previo no volvía a sincronizarse (B1) |
| 2026-09-11 | [Baja de cliente](baja-cliente.md) | Spec inicial (Fase 1 auditoría). La baja nunca se ejecutaba (B2): abre ciclo por `SENDING_SAP`, emite `DELETE` real y **bloquea** la imagen (`status=BLOCKED`) en vez de borrarla |
| 2026-09-10 | [Sincronización de dirección](sincronizacion-direccion.md) | AC-6: re-sincronización de una dirección ya enviada. La línea de feature quedaba en `SENT_SAP` y `SENT_SAP → VALIDATING` no estaba permitida |
| 2026-09-09 | [Sincronización de dirección](sincronizacion-direccion.md) | Spec inicial. Documenta que la línea de estado de la feature entra por `VALIDATING`; el código no lo registraba y `POST /customers/sync` devolvía 500 |
| 2026-09-23 | [Dashboard de consulta de entidad (UI-001)](consulta-entidad-ui.md) | Spec inicial. Módulo `dashboard-customer` (puerto 8091). Thymeleaf+HTMX+Alpine.js. Lectura directa Mongo+ES. PII enmascarada (`PiiMasker` promovido a `common.security`). F-9 promoted: `POST /customers/alerts/{id}/ack` con TTL 30d. F-12: métricas Prometheus propias. Job KPIs cardinalidad ≤ 2. Template Grafana gana `var-entityId`. 7 commits, 80 tests nuevos, 537 totales. |
| 2026-09-23 | [Vista grafo del flujo de integración (UI-002)](consulta-entidad-grafo-ui.md) | Spec inicial. Pausada por dictamen 🟡 de `auditor-business`: bloqueo B-1 (aplanamiento de `uri` en Prometheus no implementado en `customer-app`/`article-app`/`dashboard-customer`). 3 KPIs propuestos (KPI-6, KPI-7, KPI-8); KPI-6 no apto, KPI-7/KPI-8 con reservas materiales. Reapertura condicional cuando B-1 esté cerrado. |
