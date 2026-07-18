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
