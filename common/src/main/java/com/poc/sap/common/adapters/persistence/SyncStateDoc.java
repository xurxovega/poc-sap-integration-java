package com.poc.sap.common.adapters.persistence;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Documento Mongo de una transicion de estado (OVERVIEW.md §5; TECH.md §7).
 *
 * <p>La coleccion es append-only: una fila por transicion. El estado actual de
 * una entidad es su ultima transicion, y "ultima" se decide por {@code seq}, no
 * por {@code timestamp}: las transiciones se escriben en rafaga y el orden por
 * milisegundos empataba (auditoria B11/C1).
 *
 * <p>El indice unico sobre {@code (domain, entityId, seq)} es ademas la version
 * optimista del estado: dos instancias que lean la misma cabecera intentaran
 * escribir la misma secuencia y solo una lo conseguira (AC-12). Es un indice
 * PARCIAL ({@code seq} existe) para excluir las transiciones anteriores a la
 * secuencia, que no tienen el campo. No sirve {@code sparse}: en un indice
 * compuesto, sparse indexa el documento si tiene AL MENOS UNA clave, y todos
 * los docs antiguos tienen domain y entityId, asi que colisionaban en
 * seq = null (E11000 al construir el indice, 2026-09-12).
 */
@Document(collection = "sync_state")
@CompoundIndex(name = "dom_ent_idx", def = "{'domain':1,'entityId':1,'timestamp':-1}")
@CompoundIndex(name = "dom_ent_seq_uk", def = "{'domain':1,'entityId':1,'seq':1}", unique = true,
               partialFilter = "{ 'seq': { '$exists': true } }")
public class SyncStateDoc {

    @Id
    private String id;
    private String domain;
    private String entityId;
    private int stateCode;
    private String origin;
    private String payloadHash;
    private Instant timestamp;
    /** Secuencia monotona por entidad. {@code null} en documentos anteriores a su introduccion. */
    private Long seq;

    public static SyncStateDoc from(String domain, String entityId,
                                     SyncStateTransition t, int code, long seq) {
        SyncStateDoc d = from(domain, entityId, t, code);
        d.seq = seq;
        return d;
    }

    /** Sin secuencia: solo para tests y lecturas de documentos antiguos. */
    public static SyncStateDoc from(String domain, String entityId,
                                     SyncStateTransition t, int code) {
        SyncStateDoc d = new SyncStateDoc();
        // id null → Mongo genera un ObjectId único; nanoTime colisionaba entre instancias
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
    /** Secuencia almacenada, o 0 si el documento es anterior a la secuencia. */
    public long seq() { return seq == null ? 0L : seq; }
    public String getDomain() { return domain; }
    public String getEntityId() { return entityId; }
    public String getOrigin() { return origin; }
    public String getPayloadHash() { return payloadHash; }
    public String getId() { return id; }
}
