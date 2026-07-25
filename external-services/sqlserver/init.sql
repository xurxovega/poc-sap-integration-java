-- Inicialización de SQL Server para el dominio CUSTOMER.

CREATE DATABASE poc;
GO
USE poc;
GO

CREATE TABLE dbo.customers (
    id VARCHAR(50) PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL,
    name NVARCHAR(255) NOT NULL,
    status NVARCHAR(20) DEFAULT 'ACTIVE',
    street NVARCHAR(255),
    city NVARCHAR(100),
    postal_code NVARCHAR(20),
    country NVARCHAR(100),
    region NVARCHAR(100),
    tax_id NVARCHAR(50),
    vat_number NVARCHAR(50),
    legal_name NVARCHAR(255),
    tax_residency NVARCHAR(100),
    email NVARCHAR(255),
    phone NVARCHAR(50),
    fax NVARCHAR(50),
    website NVARCHAR(255),
    iban NVARCHAR(50),
    bic NVARCHAR(50)
);
GO

INSERT INTO dbo.customers (
    id, code, name, status, street, city, postal_code, country, region,
    tax_id, vat_number, legal_name, tax_residency,
    email, phone, website, iban, bic
)
VALUES
    ('CUST-001', 'C001', 'Acme Corporation', 'ACTIVE',
     'Calle Mayor 1', 'Madrid', '28001', 'ES', 'Madrid',
     'B12345678', 'ESB12345678', 'Acme Corporation S.L.', 'ES',
     'info@acme.com', '+34 600 111 222', 'https://acme.com',
     'ES9121000418450200051332', 'CAIXESBB'),
    ('CUST-002', 'C002', 'Globex S.A.', 'ACTIVE',
     'Avenida Diagonal 100', 'Barcelona', '08008', 'ES', 'Cataluña',
     'A87654321', 'ESA87654321', 'Globex S.A.', 'ES',
     'contacto@globex.es', '+34 600 333 444', 'https://globex.es',
     'ES8023100001180000012345', 'BBVAESMM')
GO

-- =============================================================================
-- Outbox CDC (Debezium) — dominio CUSTOMER
-- Cada cambio en dbo.customers inserta una fila cuyo `payload` es EXACTAMENTE
-- el JSON que esperan los listeners Kafka:
--   {"entityId":"...","operation":"CREATE|UPDATE|DELETE","payloadHash":"...","payload":{...}}
-- =============================================================================

CREATE TABLE dbo.outbox_customer (
    id           BIGINT IDENTITY(1,1) PRIMARY KEY,
    entity_id    VARCHAR(50)   NOT NULL,
    operation    VARCHAR(10)   NOT NULL,
    payload_hash VARCHAR(64)   NOT NULL,
    payload      NVARCHAR(MAX) NOT NULL,
    created_at   DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME()
);
GO

CREATE TRIGGER dbo.trg_customers_outbox
ON dbo.customers
AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    DECLARE @op VARCHAR(10) =
        CASE
            WHEN EXISTS (SELECT 1 FROM inserted) AND EXISTS (SELECT 1 FROM deleted) THEN 'UPDATE'
            WHEN EXISTS (SELECT 1 FROM inserted) THEN 'CREATE'
            ELSE 'DELETE'
        END;

    INSERT INTO dbo.outbox_customer (entity_id, operation, payload_hash, payload)
    SELECT
        s.id,
        @op,
        CONVERT(VARCHAR(64), HASHBYTES('SHA2_256', j.entity_json), 2),
        N'{"entityId":"' + STRING_ESCAPE(s.id, 'json')
            + N'","operation":"' + @op
            + N'","payloadHash":"' + CONVERT(VARCHAR(64), HASHBYTES('SHA2_256', j.entity_json), 2)
            + N'","payload":' + j.entity_json + N'}'
    FROM (
        SELECT * FROM inserted
        UNION ALL
        SELECT * FROM deleted WHERE @op = 'DELETE'
    ) s
    CROSS APPLY (
        SELECT (
            SELECT s.id            AS id,
                   s.code          AS code,
                   s.name          AS name,
                   s.status        AS status,
                   s.street        AS [address.street],
                   s.city          AS [address.city],
                   s.postal_code   AS [address.postalCode],
                   s.country       AS [address.country],
                   s.region        AS [address.region],
                   s.tax_id        AS [fiscal.taxId],
                   s.vat_number    AS [fiscal.vatNumber],
                   s.legal_name    AS [fiscal.legalName],
                   s.tax_residency AS [fiscal.taxResidency],
                   s.email         AS [contact.email],
                   s.phone         AS [contact.phone],
                   s.fax           AS [contact.fax],
                   s.website       AS [contact.website],
                   s.iban          AS [banking.iban],
                   s.bic           AS [banking.bic]
            FOR JSON PATH, WITHOUT_ARRAY_WRAPPER
        ) AS entity_json
    ) j;
END;
GO

-- =============================================================================
-- CDC requerido por Debezium (SQL Server): habilitar en la BD y en la outbox.
-- Requiere SQL Server Agent activo (MSSQL_AGENT_ENABLED=true en docker-compose).
-- =============================================================================

EXEC sys.sp_cdc_enable_db;
GO

EXEC sys.sp_cdc_enable_table
    @source_schema        = N'dbo',
    @source_name          = N'outbox_customer',
    @role_name            = NULL,
    @supports_net_changes = 0;
GO
