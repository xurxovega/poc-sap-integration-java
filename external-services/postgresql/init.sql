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
-- MENSAJE FINO (ADR-0013): la outbox NO publica datos del articulo. Cada cambio
-- inserta una fila cuyo `message` es el aviso que leen los listeners Kafka:
--   {"entityId":"...","operation":"CREATE|UPDATE|DELETE","occurredAt":"...","version":N}
-- El consumidor relee el estado actual del legacy y calcula el hash sobre el
-- snapshot leido. Las columnas `payload` y `payload_hash` se conservan un ciclo,
-- NULLABLE y siempre NULL, para no romper lo que aun las lea.
-- Requiere wal_level=logical (ya configurado en docker-compose).
-- =============================================================================

CREATE TABLE IF NOT EXISTS outbox_article (
    id           BIGSERIAL PRIMARY KEY,
    entity_id    VARCHAR(50)  NOT NULL,
    operation    VARCHAR(10)  NOT NULL,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    message      TEXT         NOT NULL,
    payload_hash VARCHAR(64)  NULL,   -- ADR-0013: deja de emitirse
    payload      TEXT         NULL,   -- ADR-0013: deja de emitirse
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION articles_outbox_fn() RETURNS trigger AS $$
DECLARE
    rec        articles%ROWTYPE;
    op         TEXT;
    next_id    BIGINT;
    now_utc    TIMESTAMPTZ := now();
BEGIN
    IF TG_OP = 'INSERT' THEN
        rec := NEW; op := 'CREATE';
    ELSIF TG_OP = 'UPDATE' THEN
        rec := NEW; op := 'UPDATE';
    ELSE
        rec := OLD; op := 'DELETE';
    END IF;

    next_id := nextval('outbox_article_id_seq');

    -- Mensaje fino: identidad del cambio y nada mas. Ni datos, ni hash.
    INSERT INTO outbox_article (id, entity_id, operation, occurred_at, message)
    VALUES (
        next_id,
        rec.id,
        op,
        now_utc,
        jsonb_build_object(
            'entityId',   rec.id,
            'operation',  op,
            'occurredAt', to_char(now_utc AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
            'version',    next_id
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
