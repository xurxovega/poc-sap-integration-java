# 2026-09-23 — Briefing inicial de OPS-010 entregaba `sqlserver-init.sql` con errores de T-SQL sobre TDS

| | |
|---|---|
| **Detectada** | 2026-09-23 (vuelta 2 del bucle QA), al revisar el briefing inicial que se pasó al `dev-implementer` y comparar el `sqlserver-init.sql` con el dialecto real que JDBC envía por TDS a SQL Server. Detectó el propio `dev-implementer` antes de ejecutar `mvn verify`, anulado como test rojo falso durante unas horas. |
| **Introducida** | 2026-09-23, en el briefing inicial de OPS-010 (H-0) que la orquestación pasó al `dev-implementer` para añadir `sqlserver-init.sql` al flujo de Debezium. El briefing asumía T-SQL "estándar" sin reparar en cómo viaja por TDS+Jdbc. |
| **Latencia** | 0 días (detectada el mismo día, antes del primer `mvn verify` con Docker). |
| **Impacto** | 0 tests rojos falsos en el dominio de aplicación (el `DebeziumRedpandaIT` aún estaba rojo — rojo intencionado — por el renombrado del broker), pero unas horas de la vuelta 2 del bucle correctivo se dedicaron a rehacer el script y a reordenar manualmente columnas del `INSERT` para coincidir con el `CREATE TABLE` (commit `85cb657`). Riesgo concreto: si el briefing defectuoso hubiera pasado en un PR donde el IT ya estaba verde, habría dejado `DebeziumRedpandaIT` rojo por causa ajena al refactor del broker y habría mezclado dos investigaciones. |
| **Fingerprints** | `infra-local:contradice-a-la-app` — **4.ª recurrencia**. Causa raíz: el briefing de *infra local* llevaba un esquema o un dialecto distinto del que la app y el conector Debezium realmente consumen a través de TDS. Patrón idéntico a las 3 recurrencias anteriores (ES 8 vs cliente 9, `init.js` con `id` vs `_id`, índice Mongo sin nombre). Estado del fingerprint: **abierto tras la 4.ª recurrencia** → tarea de diseño pendiente (ver §*Acciones*). |
| **Estado** | **Abierta**. El incidente concreto (el `sqlserver-init.sql` corrupto) está cerrado por el commit `6f6f175`; la tarea de diseño que abriría el patrón "briefings SQL sin validar contra el contenedor" queda **abierta** porque la recurrencia 4 demuestra que con detectarlo a tiempo no basta. |

## Cronología (horas absolutas)

| Cuándo | Qué pasó / qué se hizo |
|---|---|
| 2026-09-23 (mañana) | Briefing inicial de OPS-010 H-0: incluye un `external-services/mssql/init.sql` pensado para `sqlcmd`. Lleva `GO` como separador de lotes y `USE poc;` para cambiar de base. |
| 2026-09-23 (mañana) | `dev-implementer` aplica el briefing en el commit `6f6f175` (`test(ops-010): anade init script que faltaba en H-0`). El body del commit ya documenta: «script sin `GO`, sin `USE`, con `poc.dbo.` prefijado — `GO` no es T-SQL (JDBC falla con "Could not find stored procedure 'GO'"); `USE` no persiste entre sentencias por TDS (cada statement corre en su propia transacción sobre la conexión actual); `ALTER DATABASE` debe llevar nombre explícito». Detección antes de ejecutar la suite. |
| 2026-09-23 (tarde, vuelta 2 del bucle) | `qa-tester` levanta `docker compose up -d mssql` y aplica `init.sql` a mano: `GO` rompe el batch con el error esperado, `USE poc` no cambia de base. Bug de briefing confirmado. |
| 2026-09-23 (tarde, vuelta 2 del bucle) | Commit `85cb657` corrige el orden de columnas del `INSERT` para coincidir con el `CREATE TABLE` del briefing (cambio paralelo, derivado de reescribir el script). |
| 2026-09-23 (cierre) | `docs-writer` abre esta incidencia. |

## Causa raíz

El briefing de OPS-010 venía redactado como si el cliente fuera `sqlcmd` o una
sesión interactiva de Management Studio, donde:

- `GO` es un **separador de lotes propio del cliente `sqlcmd`**, no una
  sentencia T-SQL. JDBC, que envía cada bloque por TDS como un lote completo,
  lo recibe literalmente y lanza `Could not find stored procedure 'GO'`.
- `USE poc;` cambia la base **solo dentro del lote actual**; en una conexión
  JDBC sin pool persistente entre sentencias, no garantiza persistencia.
  Además, `ALTER DATABASE` exige nombre explícito de tres partes si se ejecuta
  desde otra base.

El patrón de fondo es el mismo que las 3 recurrencias previas del fingerprint
`infra-local:contradice-a-la-app`: el material de *infra local* (compose,
scripts `init.*`, manifiestos) describe un esquema o un dialecto distinto del
que la app y el conector reales consumen. Aquí no era una versión de imagen
sino un **dialecto de SQL**.

## Por qué ningún test lo cazó

El test que debería haberlo detectado —`DebeziumRedpandaIT` con
`SqlServerContainer` y Debezium Connect leyendo `outbox.CUSTOMER`— estaba
**rojo intencionadamente** por H-0 (era un test rojo del propio renombrado de
broker que violaba AGENTS.md §1.2 a propósito para forzar la reescritura). En
esa ventana el IT no era una red de seguridad: era ruido.

El briefing defectuoso **no se validó** contra ningún MSSQL real antes de
pasarlo al `dev-implementer`. La revisión entre orquestación y dev-implementer
se centró en el contrato del broker (la novedad de OPS-010), no en el script
de init que se reutilizaba del flujo anterior de Debezium/Kafka.

## Qué habría acortado la detección

- **Ejecutar el briefing contra el contenedor real antes de pasarlo**, aunque
  solo fuera un `docker run --rm -v init.sql:/tmp/init.sql mcr.microsoft.com/mssql-tools18 bash -c "/opt/mssql-tools18/bin/sqlcmd ... -i /tmp/init.sql"` (con `MSYS_NO_PATHCONV=1` en Git Bash — el §6 de `docs/sdd/README.md` ya
  documenta este *gotcha*). En este caso el `dev-implementer` habría visto el
  error de `GO` en 30 segundos y el briefing habría llegado ya corregido.
- **CI con Docker en cada PR** (`mvn -pl it verify -Ddocker.available=true`):
  el plan §6 ya lo recoge como AC-2. Aquí falló en la práctica por el bug de
  red de Testcontainers (commit `b78c301`), pero ese mismo bug también impedía
  validar el briefing contra un MSSQL vivo, así que ambas cosas habrían caído
  el mismo día.
- **Una regla de revisión** ("todo SQL pegado en un briefing debe llevar la
  marca *SQL no validado* si nadie lo ha ejecutado") habría hecho explícita
  la duda y obligado al reviewer a tirar de contenedor.

## Acciones

| Acción | Dónde | Estado |
|---|---|---|
| Reescribir `sqlserver-init.sql` sin `GO`, sin `USE`, con `poc.dbo.` prefijado | commit `6f6f175` (`test(ops-010): anade init script que faltaba en H-0`) — el propio `dev-implementer` documenta el motivo en el body | cerrada |
| Corregir orden de columnas del `INSERT` para coincidir con el `CREATE TABLE` | commit `85cb657` (`fix(ops-010): corrige orden columnas INSERT en init.sql...`) | cerrada |
| Documentar la validación manual del init si la BD ya estaba inicializada | commit `3ff6bd6` (`docs(ops-010): H-5-bis init.sql con nota de ejecucion manual...`) | cerrada |
| **Abrir tarea de diseño**: briefings que lleven SQL deben ejecutarse contra el contenedor real antes de pasarse al `dev-implementer`, o el reviewer los marca explícitamente como "SQL no validado" | `TODO.md` raíz, sección "tareas de diseño abiertas por recurrencia de fingerprint" | **abierta** |
| **Trazabilidad**: registrar esta 4.ª recurrencia en `infra-local:contradice-a-la-app` | `docs/incidencias/README.md` índice de fingerprints | abierta (este PR la incluye) |
