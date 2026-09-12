# 2026-09-12 — La primera escritura en Elasticsearch rompía tras el salto a Boot 4.1

| | |
|---|---|
| **Detectada** | 2026-09-12 11:16, verificación en vivo de la Fase 6 (`POST /customers/sync` devolvía 500) |
| **Introducida** | `299567a` (Fase 2, 2026-09-12): Spring Boot 4.0.0 → 4.1.1 subió `elasticsearch-java` a 9.4.5 |
| **Latencia** | ~9 horas y cinco fases del plan, con `mvn verify` en verde todo el tiempo |
| **Impacto** | Cualquier ciclo que indexara en Elasticsearch terminaba en `ERROR`; en local afectó a todo lo que no fuera dedupe o «sin cambios reales» |
| **Fingerprints** | `test:sin-e2e-nadie-toca-la-infra` (**recurrencia**), `boot4:dependencia-transitiva-rota` |
| **Estado** | cerrada (`9e7fd6c`) |

## Cronología

| Cuándo | Qué pasó / qué se hizo |
|---|---|
| 2026-09-12 02:30 (`299567a`) | Fase 2: Boot 4.1.1. `verify` en verde (293 tests, luego). Nadie escribe en ES en la suite |
| 11:16 | Verificación en vivo de la Fase 6: `POST /customers/sync` → 500. Log: `ClassNotFoundException: io.opentelemetry.semconv.DbAttributes` en `OpenTelemetryForElasticsearch` (instrumentación del cliente ES) |
| 11:17 | Primera hipótesis errónea: culpa de retirar `sdk-core` (Fase 3.1). `dependency:tree` muestra que `elasticsearch-java` 9.4.5 arrastra `opentelemetry-semconv` **1.30.0-rc.1** y la clase existe desde **1.34.0** |
| 11:20 | Falsa alarma intermedia: `pkill` no mató la app antigua en Windows y la nueva no pudo arrancar (puerto ocupado); el 500 siguió viniendo de la instancia vieja |
| 11:21 | Con la app reconstruida y `semconv` 1.34.0 fijado en el parent: `SAP_ERROR`/`SENT_SAP` correctos, histórico indexado |

## Causa raíz

Una dependencia transitiva **inconsistente**: la librería declara una versión
de otra que no tiene la clase que ella misma usa. Solo se manifiesta al ejecutar
el código instrumentado, es decir, al escribir de verdad en Elasticsearch.

## Por qué ningún test lo cazó

Los tests de `ElasticsearchCustomerIndexer` mockean el repositorio; los smoke de
contexto arrancan sin ES (`createIndex=false`) y no indexan; `SyncStateMongoIT`
cubre Mongo, no ES. Es el mismo hueco que dejó vivir 53 días a D1 en la
incidencia anterior.

## Qué habría acortado la detección

- El e2e con Testcontainers (TEST-1): con un solo `sync` contra ES real habría
  fallado en la CI de la Fase 2.
- Un `IT` mínimo del indexer contra `elasticsearch` en Testcontainers (más
  barato que el e2e completo).

## Acciones

| Acción | Dónde | Estado |
|---|---|---|
| Fijar `opentelemetry-semconv` 1.34.0 en el parent | `pom.xml`, `9e7fd6c` | ✅ |
| Anotar el hueco en TEST-1 | [`../MEJORAS-Y-PROPUESTAS.md`](../MEJORAS-Y-PROPUESTAS.md) | ✅ |
| IT del indexer contra ES real (Testcontainers) | TEST-1 (paso intermedio) | 📋 |
