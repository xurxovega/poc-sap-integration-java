# 2026-09-09 — Primer arranque end-to-end: seis defectos de 53 días

| | |
|---|---|
| **Detectada** | 2026-09-09 y 2026-09-10, primer arranque completo del entorno local con CDC y el SAP simulado |
| **Introducida** | `d1eba38` (2026-07-18, primer commit) cinco de los seis; `1539899` el sexto |
| **Latencia** | 53 días (cinco defectos), 5 días (uno). Ninguno vivió menos de 5 |
| **Impacto** | Nada llegaba a SAP; cuando llegó, cada cliente se sincronizaba una sola vez; solo un cliente y un artículo cabían en Mongo; `article-app` no arrancaba; el estado no estaba donde la documentación decía; los bloques de dirección, fiscal, contacto y banco no se enviaban |
| **Fingerprints** | `sync-state:reentrada-no-permitida` (D2, D6; **recurrió** como B1/B2 en la auditoría del 2026-09-10), `test:puerto-mockeado-oculta-invariante` (D2, D6), `test:sin-e2e-nadie-toca-la-infra` (los seis), `infra-local:contradice-a-la-app` (D1, D3, D4), `boot4:propiedad-movida-en-silencio` (D5) |
| **Estado** | cerrada. Los seis se arreglaron en `fa9b4e9`; la **causa raíz** de D2/D6 (la máquina de estados) se atacó de verdad en la Fase 1 de la auditoría (`84a9da4`, 2026-09-11) tras su tercera recurrencia |

> Escrito el 2026-09-12 a partir de la sección §14 del informe de auditoría
> ([`../auditorias/2026-09-10-auditoria-agentes.md`](../auditorias/2026-09-10-auditoria-agentes.md)),
> que reconstruyó la cronología desde el historial de git. El repo no tenía
> carpeta de incidencias: esta es la primera.

## Cronología (fechas de commit)

| Cuándo | Qué pasó / qué se hizo |
|---|---|
| 2026-07-18 (`d1eba38`) | Nace el proyecto **directamente en Spring Boot 4.0.0**, con configuración y compose escritos al estilo Boot 3 (ES 8.11, `spring.data.mongodb.*`). No hubo «migración»: la lección de versión mayor aplica también al arrancar en una |
| 2026-07-25 (`69ba587`) | Se corrige la propiedad de Elasticsearch del bloque `spring.data.*`; **no** la de Mongo, del mismo bloque |
| 2026-09-04 (`1539899`) | Índice `sync_state` sin nombre que choca con `dom_ent_idx` |
| 2026-09-0x (`d9904cc`) | Se arregla la máquina de estados «a medias»: `SENT_SAP → RECEIVED` solo para el agregado |
| 2026-09-09 | Primer arranque completo. `docker compose up` + apps + CDC. Nada llega a SAP |
| 2026-09-09/10 | Diagnóstico y arreglo de los seis (`fa9b4e9`); verificación en vivo del ciclo completo y del re-sync |
| 2026-09-10 | Auditoría externa: detecta que D2/D6 vuelven como B1/B2 (tercera recurrencia del mismo fingerprint) |
| 2026-09-11 | Fase 1 del plan: `beginCycle`/`advance`, test de propiedad, baja ejecutable (`84a9da4`) |

## Los seis defectos

| # | Defecto | Causa raíz | Por qué ningún test lo cazó |
|---|---|---|---|
| D1 | Nada llegaba a SAP | Compose con ES 8.11 contra el cliente ES 9 de Boot 4 (`media_type_header_exception`); el indexer no capturaba y el agregado quedaba en `INDEXING` | Indexer mockeado; `createIndex=false` deja arrancar sin ES |
| D2 | Un cliente solo se sincronizaba una vez | `SENT_SAP → RECEIVED` modelado solo para el agregado, no para las líneas de feature | Mocks del puerto de estado; **`ErrorStateRecoveryTest` consagraba el defecto** |
| D3 | Solo un cliente y un artículo | `init.js` creaba `{id:1} unique` y los documentos usan `_id` | `init.js` no se ejecuta en ningún test; enmascarado por D5 |
| D4 | `article-app` no arrancaba | Índice `sync_state` sin nombre vs `dom_ent_idx`; `mvn -pl article -am spring-boot:run` sobre el POM padre | Tests de contexto con `auto-index-creation=false` |
| D5 | Estado no consultable donde debía | Boot 4 movió `spring.data.mongodb.*` a `spring.mongodb.*`; la antigua se ignora en silencio; ambas apps escribían en `test` | Ningún test afirmaba el nombre de la base |
| D6 | Dirección, fiscal, contacto y banco no se enviaban | Los `Sync<Feature>UseCase` no registraban la entrada en `VALIDATING` y `VALID → SENDING_SAP` no existía | Mocks del puerto de estado en los 4 tests |

## Causa raíz (la de verdad)

No «faltaba la fila X». Cuatro prácticas:

1. **Mocks de puertos con invariantes** (4 de 6): el test pasaba porque el mock
   aceptaba cualquier transición. La máquina real nunca se ejecutó en un test de
   use case hasta `InMemoryStateRepo`.
2. **Ausencia de e2e** (6 de 6): nada tocaba Mongo, ES o Kafka reales antes de
   la primera demo.
3. **Máquina de estados extendida fila a fila** (D2, D6 y luego B1, B2): cada use
   case fijaba su estado de entrada y el repositorio recalculaba el `from`. El
   diseño invitaba a la cuarta recurrencia.
4. **Versión mayor sin checklist** (D1, D5) e **infra local que contradice a la
   app** (D1, D3, D4).

## Qué habría acortado la detección

- Un solo test de integración que arrancara la app contra Testcontainers y
  lanzara un sync (TEST-1). Habría visto los seis el primer día.
- Un test de configuración por propiedad crítica (TEST-2). Habría visto D5.
- Repositorio en memoria con la máquina real en vez de mocks (TEST-3). Habría
  visto D2 y D6.
- Un check de arranque que compare el compose con las versiones que espera la
  app. Habría visto D1.

## Acciones

| Acción | Dónde | Estado |
|---|---|---|
| Máquina de estados de raíz: `beginCycle`/`advance`, test de propiedad | [`../sdd/common/maquina-de-estados.md`](../sdd/common/maquina-de-estados.md), `84a9da4` | ✅ |
| `InMemoryStateRepo` en los tests de use case | TEST-3, `84a9da4` | ✅ |
| `CustomerMongoDatabaseConfigTest` (test de configuración) | TEST-2 | ✅ |
| Compose ES 9.2.1, `init.js` con `_id`, índice parcial nombrado, imágenes por digest | `fa9b4e9`, Fase 8 | ✅ |
| `SyncStateMongoIT` contra Mongo real | Fase 2 | ✅ parcial |
| E2E completo outbox → Debezium → app → WireMock → Mongo | TEST-1 / TEST-4 | 📋 abierto |
