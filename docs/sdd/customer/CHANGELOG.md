# Changelog — dominio customer

> Índice cronológico de los cambios de las features del dominio `customer`. Una línea por cambio;
> el detalle vive en la sección **Cambios** del spec de cada feature, y la
> información ampliada (quién lo pidió, cuándo, estado) en el registro MySQL
> `sdd_registry` — ver [`../README.md`](../README.md) §8.

| Fecha | Feature | Cambio |
|---|---|---|
| 2026-09-10 | [Sincronización de dirección](sincronizacion-direccion.md) | AC-6: re-sincronización de una dirección ya enviada. La línea de feature quedaba en `SENT_SAP` y `SENT_SAP → VALIDATING` no estaba permitida |
| 2026-09-09 | [Sincronización de dirección](sincronizacion-direccion.md) | Spec inicial. Documenta que la línea de estado de la feature entra por `VALIDATING`; el código no lo registraba y `POST /customers/sync` devolvía 500 |
