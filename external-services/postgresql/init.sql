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

-- =============================================================================
-- Outbox CDC (Debezium) — dominio ARTICLE
-- Cada cambio en articles inserta una fila cuyo `payload` es EXACTAMENTE
-- el JSON que esperan los listeners Kafka:
--   {"entityId":"...","operation":"CREATE|UPDATE|DELETE","payloadHash":"...","payload":{...}}
-- Requiere wal_level=logical (ya configurado en docker-compose) y pgcrypto
-- (extensión creada arriba) para el hash SHA-256.
-- =============================================================================

CREATE TABLE IF NOT EXISTS outbox_article (
    id           BIGSERIAL PRIMARY KEY,
    entity_id    VARCHAR(50)  NOT NULL,
    operation    VARCHAR(10)  NOT NULL,
    payload_hash VARCHAR(64)  NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION articles_outbox_fn() RETURNS trigger AS $$
DECLARE
    rec         articles%ROWTYPE;
    op          TEXT;
    entity_json TEXT;
    hash        TEXT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        rec := NEW; op := 'CREATE';
    ELSIF TG_OP = 'UPDATE' THEN
        rec := NEW; op := 'UPDATE';
    ELSE
        rec := OLD; op := 'DELETE';
    END IF;

    entity_json := jsonb_build_object(
        'id',          rec.id,
        'sku',         rec.sku,
        'description', rec.description,
        'category',    rec.category,
        'unit',        rec.unit,
        'status',      rec.status
    )::text;
    hash := encode(digest(entity_json, 'sha256'), 'hex');

    INSERT INTO outbox_article (entity_id, operation, payload_hash, payload)
    VALUES (
        rec.id,
        op,
        hash,
        jsonb_build_object(
            'entityId',    rec.id,
            'operation',   op,
            'payloadHash', hash,
            'payload',     entity_json::jsonb
        )::text
    );

    RETURN NULL;  -- trigger AFTER: el valor de retorno se ignora
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_articles_outbox ON articles;
CREATE TRIGGER trg_articles_outbox
    AFTER INSERT OR UPDATE OR DELETE ON articles
    FOR EACH ROW
    EXECUTE FUNCTION articles_outbox_fn();
