# Changelog — dominio article

> Índice cronológico de los cambios de las features del dominio `article`. Una línea por cambio;
> el detalle vive en la sección **Cambios** del spec de cada feature, y la
> información ampliada (quién lo pidió, cuándo, estado) en el registro MySQL
> `sdd_registry` — ver [`../README.md`](../README.md) §8.

| Fecha | Feature | Cambio |
|---|---|---|
| 2026-09-19 | [Sincronizacion de articulo](sincronizacion-articulo.md) | La entrada pasa a ser el aviso **fino**: el use case relee PostgreSQL y calcula el hash sobre ese snapshot (ADR-0013) |
| 2026-09-12 | [Sincronización del artículo](sincronizacion-articulo.md) | Fase 7 (A19/C10): serialización con `SapJsonMapper`, nulos omitidos |
| 2026-09-12 | [Sincronización del artículo](sincronizacion-articulo.md) | Spec inicial (Fase 6). Imagen solo tras el ACK de SAP, histórico por intento, dedupe contra el último `SENT_SAP` |
