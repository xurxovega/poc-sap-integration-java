package com.poc.sap.common.sap;

/**
 * Interruptores de la verificacion previa y el upsert (spec
 * {@code docs/sdd/common/upsert-idempotente-sap.md} §6). Viajan juntos porque
 * los seis adaptadores OData necesitan los tres y pasarlos sueltos por
 * constructor multiplicaba los parametros.
 *
 * @param lookupEnabled               interruptor global de la verificacion previa
 *                                    ({@code sap.client.lookup.enabled}). Con
 *                                    {@code false} el adaptador se comporta como
 *                                    antes: alta directa. Solo para el entorno
 *                                    local contra el SAP simulado
 * @param ambiguousFails              un lookup con mas de un resultado es
 *                                    «no concluyente», no «cojo el primero»
 *                                    ({@code sap.client.lookup.ambiguous-fails})
 * @param refetchOnPreconditionFailed un {@code 412} permite repetir el lookup y el
 *                                    PATCH una unica vez
 *                                    ({@code sap.client.upsert.refetch-on-precondition-failed})
 */
public record SapUpsertSettings(boolean lookupEnabled,
                                boolean ambiguousFails,
                                boolean refetchOnPreconditionFailed) {

    /** Los valores por defecto del producto: verificar siempre y no adivinar nunca. */
    public static SapUpsertSettings defaults() {
        return new SapUpsertSettings(true, true, true);
    }
}
