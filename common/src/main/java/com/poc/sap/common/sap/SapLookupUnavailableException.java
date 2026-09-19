package com.poc.sap.common.sap;

/**
 * La verificacion previa a una escritura no pudo concluir: no sabemos que tiene
 * SAP, asi que <b>no se escribe nada</b> (spec
 * {@code docs/sdd/common/upsert-idempotente-sap.md} R-3).
 *
 * <p>Es un fallo <b>transitorio</b>: la parte queda en {@code COMMUNICATION_ERROR}
 * y, como SAP no se toco, el mensaje se puede reentregar sin riesgo de duplicar.
 * Nunca degrada a alta directa: preferir un POST «por si acaso» es justo lo que
 * crea Business Partners duplicados.
 */
public class SapLookupUnavailableException extends RuntimeException {

    private final String feature;

    /**
     * @param feature feature cuya verificacion previa fallo ({@code ADDRESS}, {@code CONTACT}...)
     * @param detail  motivo legible (transporte, 5xx agotado, lookup ambiguo...)
     */
    public SapLookupUnavailableException(String feature, String detail) {
        super("Verificacion previa en SAP no concluyente para " + feature
                + (detail == null || detail.isBlank() ? "" : ": " + detail) + "; no se escribe nada");
        this.feature = feature;
    }

    public String feature() {
        return feature;
    }
}
