# Changelog — shared kernel common

> Índice cronológico de los cambios de las capacidades de `common`. Una línea por cambio;
> el detalle vive en la sección **Cambios** del spec de cada feature, y la
> información ampliada (quién lo pidió, cuándo, estado) en el registro MySQL
> `sdd_registry` — ver [`../README.md`](../README.md) §8.

| Fecha | Capacidad | Cambio |
|---|---|---|
| 2026-09-10 | [Máquina de estados](maquina-de-estados.md) | Spec inicial. Añade la re-entrada de líneas de feature por `VALIDATING` desde `SENT_SAP`, `INVALID` y `SAP_ERROR` |
| 2026-09-09 | [Máquina de estados](maquina-de-estados.md) | `VALID → SENDING_SAP` para el pipeline por feature, que valida y envía sin indexar |
