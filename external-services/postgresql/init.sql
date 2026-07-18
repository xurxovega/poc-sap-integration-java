-- Inicialización de PostgreSQL para el dominio ARTICLE.
-- La base de datos 'poc' ya se crea via POSTGRES_DB.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS articles (
    id VARCHAR(50) PRIMARY KEY,
    sku VARCHAR(50) UNIQUE NOT NULL,
    description VARCHAR(500),
    category VARCHAR(100),
    unit VARCHAR(50),
    status VARCHAR(20) DEFAULT 'ACTIVE'
);

-- Datos de ejemplo
INSERT INTO articles (id, sku, description, category, unit, status)
VALUES
    ('ART-001', 'SKU-001', 'Laptop Dell XPS 15', 'ELECTRONICS', 'UNIT', 'ACTIVE'),
    ('ART-002', 'SKU-002', 'Monitor 27 pulgadas', 'ELECTRONICS', 'UNIT', 'ACTIVE'),
    ('ART-003', 'SKU-003', 'Teclado mecánico', 'ACCESSORIES', 'UNIT', 'INACTIVE')
ON CONFLICT (id) DO NOTHING;
