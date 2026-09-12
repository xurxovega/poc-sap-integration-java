package com.poc.sap.common.sap;

/**
 * El circuit breaker hacia SAP esta abierto y la llamada no se ha intentado
 * (TECH.md §8; auditoria B13). Antes se tragaba como {@code SapResponse(0)} y el
 * use case marcaba {@code SAP_ERROR} "con normalidad": el listener no reintentaba
 * y nadie veia que el circuito estaba abierto. Es un fallo TRANSITORIO: debe
 * propagarse para que la ingesta reintente con backoff.
 */
public class SapCircuitOpenException extends RuntimeException {

    private final SapDestination destination;

    public SapCircuitOpenException(SapDestination destination, Throwable cause) {
        super("Circuito abierto hacia SAP " + destination + ": llamada no permitida", cause);
        this.destination = destination;
    }

    public SapDestination destination() {
        return destination;
    }
}
