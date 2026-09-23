-- Seed para DebeziumRedpandaIT (OPS-010, AC-2).
--
-- Se ejecuta contra el SQL Server de Testcontainers al arrancar
-- MSSQLServerContainer (ver DebeziumRedpandaIT.java:65). El usuario `sa` tiene
-- permisos DDL/DML sobre la base. NO inserta filas: el test provoca los
-- cambios en el UPDATE posterior — este script solo garantiza que el schema
-- requerido por `external-services/debezium/register-sqlserver-customer.json`
-- existe cuando el conector de Debezium arranca.
--
-- Contrato:
--   database.names  = `poc`
--   table.include.list = `dbo.outbox_customer`
--   message.key.columns = `poc.dbo.outbox_customer:entity_id`
--
-- IMPORTANTE:
--   * NO usar `GO` — es separador de sqlcmd, no T-SQL. MSSQLServerContainer
--     reenvia cada sentencia por JDBC (TDS) y "GO" falla como SP inexistente.
--   * NO usar `USE poc` para dirigir las siguientes sentencias. TDS pierde el
--     contexto de base entre sentencias (cada una se ejecuta en su propia
--     transaccion sobre la conexion actual). En su lugar, calificar el nombre
--     de la base en cada ALTER (`ALTER DATABASE poc`) y crear objetos
--     prefijados con `poc.dbo.`.

CREATE DATABASE poc;

-- BD-level change tracking: opciones validas son AUTO_CLEANUP y CHANGE_RETENTION
-- (ver docs SQL Server <change_tracking_option_list>). TRACK_COLUMNS_UPDATED
-- NO existe aqui, es solo de ALTER TABLE ... ENABLE CHANGE_TRACKING WITH (...).
ALTER DATABASE poc SET CHANGE_TRACKING = ON (AUTO_CLEANUP = ON, CHANGE_RETENTION = 2 DAYS);

CREATE TABLE poc.dbo.outbox_customer (
    entity_id   VARCHAR(50)    NOT NULL PRIMARY KEY,
    payload     NVARCHAR(MAX)  NOT NULL,
    created_at  DATETIME2      NOT NULL DEFAULT SYSUTCDATETIME()
);

ALTER TABLE poc.dbo.outbox_customer ENABLE CHANGE_TRACKING WITH (TRACK_COLUMNS_UPDATED = ON);
