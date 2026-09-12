# Changelog — shared kernel common

> Índice cronológico de los cambios de las capacidades de `common`. Una línea por cambio;
> el detalle vive en la sección **Cambios** del spec de cada feature, y la
> información ampliada (quién lo pidió, cuándo, estado) en el registro MySQL
> `sdd_registry` — ver [`../README.md`](../README.md) §8.

| Fecha | Capacidad | Cambio |
|---|---|---|
| 2026-09-12 | [Cliente SAP: transporte, resiliencia y CSRF](resiliencia-cliente-sap.md) | Spec inicial al cambiar el transporte a `RestClient` (ADR-0001). Fija R-1..R-7 y AC-1..AC-8; corrige el fetch CSRF (auth del destino) y el tratamiento del 403 (solo `Required` es CSRF) |
| 2026-09-12 | [Máquina de estados](maquina-de-estados.md) | AC-14: secuencia, versión optimista y documentos legacy sin `seq` probados contra Mongo 7 real (`SyncStateMongoIT`, Testcontainers) |
| 2026-09-11 | [Máquina de estados](maquina-de-estados.md) | **Rediseño de raíz** (B1/B11/B14): `beginCycle` (abrir ciclo desde cualquier estado, por 4 estados de entrada) separado de `advance` (la tabla). Test de propiedad estados × entradas. `SENDING_SAP → ERROR`. Repositorio Mongo: orden por `seq` y versión optimista con índice único (`ConcurrentTransitionException`) |
| 2026-09-10 | [Máquina de estados](maquina-de-estados.md) | Spec inicial. Añade la re-entrada de líneas de feature por `VALIDATING` desde `SENT_SAP`, `INVALID` y `SAP_ERROR` |
| 2026-09-09 | [Máquina de estados](maquina-de-estados.md) | `VALID → SENDING_SAP` para el pipeline por feature, que valida y envía sin indexar |
