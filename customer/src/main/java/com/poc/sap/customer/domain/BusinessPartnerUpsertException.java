package com.poc.sap.customer.domain;

/**
 * Fallo de validacion o de payload al recibir un PUT/PATCH manual de Business
 * Partner (PRD-10, spec {@code docs/sdd/customer/upsert-business-partner-manual.md}).
 *
 * <p>Es una excepcion de aplicacion: nunca se traduce en un ciclo de la maquina
 * de estados ni en una alerta SAP. El controller la mapea a 400 (BadRequest)
 * salvo {@link Kind#MandatoryFieldMissing}, que tambien va a 400.
 *
 * <p>No se considera PII: el mensaje que devuelve al cliente es la razon del
 * fallo, no datos personales.
 */
public class BusinessPartnerUpsertException extends RuntimeException {

    /** Por que se rechazo el upsert. El controller elige el HTTP a partir del kind. */
    public enum Kind {
        /** Falta un campo obligatorio (PUT sin name; PATCH sin campos). */
        MandatoryFieldMissing,
        /** El body trae algo no soportado en esta version (p.ej. category en PATCH, R-1). */
        InvalidPayload
    }

    private final Kind kind;

    public BusinessPartnerUpsertException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
