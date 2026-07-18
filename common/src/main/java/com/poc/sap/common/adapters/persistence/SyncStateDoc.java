package com.poc.sap.common.adapters.persistence;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "sync_state")
@CompoundIndex(name = "dom_ent_idx", def = "{'domain':1,'entityId':1,'timestamp':-1}")
public class SyncStateDoc {

    @Id
    private String id;
    private String domain;
    private String entityId;
    private int stateCode;
    private String origin;
    private String payloadHash;
    private Instant timestamp;

    public static SyncStateDoc from(String domain, String entityId,
                                     SyncStateTransition t, int code) {
        SyncStateDoc d = new SyncStateDoc();
        d.id = domain + ":" + entityId + ":" + System.nanoTime();
        d.domain = domain;
        d.entityId = entityId;
        d.stateCode = code;
        d.origin = t.origin();
        d.payloadHash = t.payloadHash();
        d.timestamp = t.timestamp() != null ? t.timestamp() : Instant.now();
        return d;
    }

    public SyncStateTransition toTransition() {
        return new SyncStateTransition(
                entityId, domain, null, SyncState.ofCode(stateCode),
                origin, payloadHash, timestamp);
    }

    public int stateCode() { return stateCode; }
    public Instant timestamp() { return timestamp; }
    public String getDomain() { return domain; }
    public String getEntityId() { return entityId; }
    public String getOrigin() { return origin; }
    public String getPayloadHash() { return payloadHash; }
    public String getId() { return id; }
}