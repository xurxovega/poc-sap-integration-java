# Incidencias y post-mortems

Rastro de lo que se rompió, por qué, cuánto tardó en verse y qué habría
acortado la detección. Un fichero por incidencia, fechado; el formato está en
[`_plantilla.md`](_plantilla.md). No es un registro de bugs (eso es el §6 de
[`../sdd/README.md`](../sdd/README.md)): es el análisis de los fallos que
**llegaron a ejecución** y de los patrones que se repiten.

## Índice

| Fecha | Incidencia | Fingerprints | Estado |
|---|---|---|---|
| 2026-09-09 | [Primer arranque end-to-end: seis defectos de 53 días](2026-09-09-primer-arranque-e2e.md) | `sync-state:reentrada-no-permitida` ×2, `infra-local:contradice-a-la-app` ×3, `boot4:propiedad-movida-en-silencio`, `test:puerto-mockeado-oculta-invariante` | cerrada (causa raíz atacada en la Fase 1 de la auditoría) |
| 2026-09-12 | [La primera escritura en Elasticsearch rompía tras el salto a Boot 4.1](2026-09-12-es-semconv-classnotfound.md) | `test:sin-e2e-nadie-toca-la-infra`, `boot4:dependencia-transitiva-rota` | cerrada (`opentelemetry-semconv` 1.34.0 fijado) |

## Índice de fingerprints

Un *fingerprint* es un identificador estable de **causa raíz**, no de síntoma:
si vuelve a aparecer, no es un bug nuevo, es una recurrencia, y la respuesta
correcta es atacar el diseño, no la fila. Regla: a la **segunda** recurrencia
se abre una tarea de diseño, no un arreglo puntual.

| Fingerprint | Causa raíz | Recurrencias | Última | Cerrado por |
|---|---|---|---|---|
| `sync-state:reentrada-no-permitida` | La máquina de estados se modeló para un pipeline y se extendió fila a fila; cada use case fijaba su estado de entrada y el repositorio recalculaba el `from` | **3** (`d1eba38→d9904cc`, `d9904cc→fa9b4e9`, auditoría B1/B2/B14) | 2026-09-10 | `beginCycle`/`advance` separados + test de propiedad estados × entradas ([`../sdd/common/maquina-de-estados.md`](../sdd/common/maquina-de-estados.md), Fase 1, `84a9da4`) |
| `test:puerto-mockeado-oculta-invariante` | Mockear un puerto que valida invariantes esconde justo los fallos que importan (D2, D6, V1, V2) | 4 defectos | 2026-09-10 | `InMemoryStateRepo` con la máquina real en los tests de use case (TEST-3) |
| `test:sin-e2e-nadie-toca-la-infra` | Los slices y los smoke de contexto no escriben en Mongo/ES/Kafka; lo que falla al tocar la infraestructura solo se ve en vivo (D1, D3, D4, D5, semconv) | 6 + 1 | 2026-09-12 | **Abierto**: `SyncStateMongoIT` cubre Mongo; falta el e2e completo (TEST-1/TEST-4) |
| `infra-local:contradice-a-la-app` | El compose y los scripts de init describían otra versión o otro esquema que la app (ES 8 vs cliente 9, `init.js` con `id` vs `_id`, índice sin nombre) | 3 | 2026-09-10 | Compose 9.2.1, `init.js` con `_id` e índice parcial nombrado; imágenes por digest (Fase 8) |
| `boot4:propiedad-movida-en-silencio` | Boot 4 movió `spring.data.mongodb.*` a `spring.mongodb.*` y la antigua se ignoraba sin aviso | 1 | 2026-09-10 | `CustomerMongoDatabaseConfigTest` (patrón «test de configuración», TEST-2) |
| `boot4:dependencia-transitiva-rota` | Una librería arrastra una versión de otra que no tiene la clase que usa (`elasticsearch-java` 9.4 + `semconv` 1.30.0-rc.1) | 1 | 2026-09-12 | Versión fijada en el parent; Dependabot avisará del siguiente salto |
| `test:seguridad-solo-probada-con-token` | `ApiSecurityTest` cubría la cadena activa; nadie probaba el modo abierto y en local todos los endpoints devolvían 403 | 1 | 2026-09-14 | `ApiSecurityDisabledTest`; anónimo con todos los roles en modo abierto |
| `openapi-generator:target-sucio` | Tres ejecuciones del generador borraban el `target` de la anterior | 3 (DX-7) | 2026-09-12 | `deleteOutputDirectory=false` en la 2.ª y 3.ª ejecución |
