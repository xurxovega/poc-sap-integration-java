# Changelog — dominio customer

> Índice cronológico de los cambios de las features del dominio `customer`. Una línea por cambio;
> el detalle vive en la sección **Cambios** del spec de cada feature, y la
> información ampliada (quién lo pidió, cuándo, estado) en el registro MySQL
> `sdd_registry` — ver [`../README.md`](../README.md) §8.

| Fecha | Feature | Cambio |
|---|---|---|
| 2026-09-11 | [Sincronización del cliente](sincronizacion-cliente.md) | Spec inicial (Fase 1 auditoría). R-5: un evento nuevo siempre abre ciclo, también desde `SAP_ERROR` o un ciclo en vuelo; R-6: un fallo de infraestructura tras `VALID` deja `ERROR` y se propaga. Antes un cliente con un fallo previo no volvía a sincronizarse (B1) |
| 2026-09-11 | [Baja de cliente](baja-cliente.md) | Spec inicial (Fase 1 auditoría). La baja nunca se ejecutaba (B2): abre ciclo por `SENDING_SAP`, emite `DELETE` real y **bloquea** la imagen (`status=BLOCKED`) en vez de borrarla |
| 2026-09-10 | [Sincronización de dirección](sincronizacion-direccion.md) | AC-6: re-sincronización de una dirección ya enviada. La línea de feature quedaba en `SENT_SAP` y `SENT_SAP → VALIDATING` no estaba permitida |
| 2026-09-09 | [Sincronización de dirección](sincronizacion-direccion.md) | Spec inicial. Documenta que la línea de estado de la feature entra por `VALIDATING`; el código no lo registraba y `POST /customers/sync` devolvía 500 |
