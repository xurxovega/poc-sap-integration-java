package com.poc.sap.common.domain;

/**
 * Un ciclo termino sin que NINGUNA de sus partes llegara a SAP, y todas las
 * fallidas lo hicieron por comunicacion (circuito abierto, transporte)
 * (sdd/customer/sincronizacion-cliente.md R-10; decision D-15).
 *
 * <p>El estado y el aviso ya se han escrito cuando se lanza: la traza no se
 * pierde. Se propaga para que la ingesta reintente el mensaje entero, porque es
 * <b>demostrable</b> que este ciclo no escribio nada en SAP y reintentar no
 * duplica. Si alguna parte hubiera llegado a SAP no se lanza: ahi reintentar SI
 * duplicaria mientras no exista la verificacion previa.
 *
 * <p>Es transitoria. No extiende {@link IllegalStateException} —la clase
 * declarada no reintentable— asi que Kafka la reintenta con backoff.
 */
public class NothingReachedSapException extends RuntimeException {

    private final String domain;
    private final String entityId;
    private final String cycleId;

    public NothingReachedSapException(String domain, String entityId, String cycleId, String detail) {
        super("Ciclo " + cycleId + " de " + domain + "/" + entityId
                + " no llego a SAP en ninguna de sus partes: " + detail
                + ". Se reintenta el mensaje completo");
        this.domain = domain;
        this.entityId = entityId;
        this.cycleId = cycleId;
    }

    public String domain() {
        return domain;
    }

    public String entityId() {
        return entityId;
    }

    public String cycleId() {
        return cycleId;
    }
}
