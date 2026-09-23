package com.poc.sap.common.adapters.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Documento Mongo de una clave asignada por SAP (TECH.md §7; spec
 * {@code docs/sdd/common/upsert-idempotente-sap.md} R-4).
 *
 * <p>El {@code _id} es {@code "<domain>:<entityId>:<FEATURE>"}: no hace falta
 * indice adicional para la lectura, que siempre es por clave completa, y el
 * propio {@code _id} garantiza que no haya dos claves para la misma subentidad.
 */
@Document(collection = "sap_keys")
public class SapKeyDoc {

    @Id
    private String id;
    private String domain;
    private String entityId;
    private String feature;
    private String key;
    private Instant updatedAt;

    public SapKeyDoc() {
    }

    public SapKeyDoc(String id, String domain, String entityId, String feature, String key, Instant updatedAt) {
        this.id = id;
        this.domain = domain;
        this.entityId = entityId;
        this.feature = feature;
        this.key = key;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public String getDomain() { return domain; }
    public String getEntityId() { return entityId; }
    public String getFeature() { return feature; }
    public String getKey() { return key; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setId(String id) { this.id = id; }
    public void setDomain(String domain) { this.domain = domain; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public void setFeature(String feature) { this.feature = feature; }
    public void setKey(String key) { this.key = key; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
