package com.poc.sap.common.domain;

/**
 * Dos escrituras concurrentes intentaron registrar la misma secuencia de estado
 * para la misma entidad (sdd/common/maquina-de-estados.md AC-12). La primera
 * gana; la segunda recibe esta excepcion en lugar de pisar el estado en
 * silencio. Ocurre con varias instancias consumiendo el mismo topic, o con REST
 * y Kafka concurriendo sobre la misma entidad.
 *
 * Es transitoria: quien la recibe puede reintentar releyendo el estado.
 */
public class ConcurrentTransitionException extends RuntimeException {

    private final String domain;
    private final String entityId;
    private final long seq;

    public ConcurrentTransitionException(String domain, String entityId, long seq, Throwable cause) {
        super("Transicion concurrente sobre " + domain + "/" + entityId
                + ": la secuencia " + seq + " ya fue escrita por otra instancia", cause);
        this.domain = domain;
        this.entityId = entityId;
        this.seq = seq;
    }

    public String domain() {
        return domain;
    }

    public String entityId() {
        return entityId;
    }

    public long seq() {
        return seq;
    }
}
