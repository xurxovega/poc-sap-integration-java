package com.poc.sap.common.domain;

/**
 * Estados de sincronizacion por registro.
 * Dominio-agnostico, reside en el shared kernel (SPEC.md §8).
 */
public enum SyncState {
    RECEIVED(1),
    FETCHING(2),
    VALIDATING(3),
    VALID(4),
    INVALID(5),
    INDEXING(6),
    INDEXED(7),
    SENDING_SAP(8),
    SENT_SAP(9),
    SAP_ERROR(10),
    ERROR(99),
    COMMUNICATION_ERROR(98);

    private final int code;

    SyncState(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static SyncState ofCode(int code) {
        for (SyncState s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("Codigo de SyncState desconocido: " + code);
    }
}
