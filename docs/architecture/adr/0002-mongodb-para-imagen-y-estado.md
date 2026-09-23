# ADR-0002 — MongoDB para la imagen actual y el estado de sincronización

| | |
|---|---|
| **Estado** | ✅ aceptada (retroactiva: documenta una decisión ya tomada al construir el sistema) |
| **Fecha** | 2026-09-12 (decisión original: julio de 2026, primer commit) |
| **Reevaluar cuando** | haya más de una instancia por dominio escribiendo estado y la versión optimista por índice único no baste (contención), o se necesite consulta relacional del estado |

## 1. Contexto

Cada entidad necesita dos cosas persistidas fuera del legacy: su **imagen**
(el último snapshot que SAP aceptó) y la **secuencia de transiciones** de la
máquina de estados (una fila por transición, append-only). Ambas son documentos
con forma variable por dominio (`Customer` con cuatro features, `Article`
plano), se escriben por entidad y se leen por entidad; no hay joins.

## 2. Opciones

| | MongoDB | Tabla JPA en el propio legacy | Redis |
|---|---|---|---|
| Forma del dato | documento libre por dominio | esquema por dominio, migraciones | clave-valor, sin consulta por campos |
| Append-only del estado | natural (una colección) | natural | incómodo |
| Independencia del legacy | total (SQL Server y PostgreSQL son de solo lectura para nosotros) | acopla a dos motores distintos | total |
| Concurrencia entre instancias | índice único parcial sobre `seq` = versión optimista | bloqueo o versión JPA | atomicidad por clave |
| Coste operativo | un servicio más (ya en el compose) | ninguno adicional | uno más, sin durabilidad por defecto |

## 3. Decisión

MongoDB, una base por dominio (`customer`, `article`), colecciones
`<dominio>s_current` (imagen) y `sync_state` (transiciones con `seq` monótona e
índice único parcial `dom_ent_seq_uk`). El legacy no se toca: solo lectura.

## 4. Consecuencias

- El estado actual se resuelve por `seq`, no por `timestamp` (auditoría B11), y
  dos instancias que escriban a la vez fallan limpio con
  `ConcurrentTransitionException` en vez de pisarse (Fase 1).
- La imagen es «lo que SAP tiene» y solo se escribe tras el ACK (Fase 6).
- Boot 4 movió las propiedades a `spring.mongodb.*`: hay un test de
  configuración que lo vigila (incidencia del 2026-09-09, D5).
- Specs: [`../../sdd/common/maquina-de-estados.md`](../../sdd/common/maquina-de-estados.md),
  [`../../sdd/common/idempotencia-y-dedupe.md`](../../sdd/common/idempotencia-y-dedupe.md).
