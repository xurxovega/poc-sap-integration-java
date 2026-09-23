package com.poc.sap.common.domain;

/**
 * Dos escrituras concurrentes intentaron avanzar el estado de la misma entidad
 * (sdd/common/maquina-de-estados.md AC-12, AC-20, AC-21). La primera gana; la
 * segunda recibe esta excepcion en lugar de pisar el estado en silencio. Ocurre
 * con varias instancias consumiendo el mismo topic, o con REST y Kafka
 * concurriendo sobre la misma entidad.
 *
 * <p>Tres formas de detectarla, de mas gruesa a mas fina:
 * <ul>
 *   <li><b>secuencia duplicada</b>: ambas leyeron la misma cabecera y el indice
 *       unico rechaza la segunda ({@link #duplicateSeq});</li>
 *   <li><b>cabecera movida</b>: el estado que declara quien llama ya no es el
 *       real, porque otra instancia escribio entremedias ({@link #staleHead});</li>
 *   <li><b>ciclo ajeno</b>: el estado coincide pero la cabecera pertenece a OTRO
 *       ciclo, asi que este perdio la carrera ({@link #foreignCycle}).</li>
 * </ul>
 *
 * <p>Es transitoria: quien la recibe puede reintentar releyendo el estado. Por eso
 * NUNCA extiende {@link IllegalStateException}, que esta declarada no reintentable
 * y mandaria a la DLT un fallo pasajero (auditoria 2026-09-18 N2).
 */
public class ConcurrentTransitionException extends RuntimeException {

    private final String domain;
    private final String entityId;
    private final long seq;
    private final SyncState declaredFrom;
    private final SyncState actualFrom;
    private final String expectedCycle;
    private final String actualCycle;

    public ConcurrentTransitionException(String domain, String entityId, long seq, Throwable cause) {
        this("Transicion concurrente sobre " + domain + "/" + entityId
                        + ": la secuencia " + seq + " ya fue escrita por otra instancia",
                domain, entityId, seq, null, null, null, null, cause);
    }

    private ConcurrentTransitionException(String message, String domain, String entityId, long seq,
                                          SyncState declaredFrom, SyncState actualFrom,
                                          String expectedCycle, String actualCycle, Throwable cause) {
        super(message, cause);
        this.domain = domain;
        this.entityId = entityId;
        this.seq = seq;
        this.declaredFrom = declaredFrom;
        this.actualFrom = actualFrom;
        this.expectedCycle = expectedCycle;
        this.actualCycle = actualCycle;
    }

    /** La secuencia ya estaba escrita: ambas instancias leyeron la misma cabecera. */
    public static ConcurrentTransitionException duplicateSeq(String domain, String entityId, long seq, Throwable cause) {
        return new ConcurrentTransitionException(domain, entityId, seq, cause);
    }

    /** El estado declarado por quien llama ya no es el real: la cabecera se movio. */
    public static ConcurrentTransitionException staleHead(String domain, String entityId,
                                                          SyncState declaredFrom, SyncState actualFrom, long seq) {
        return new ConcurrentTransitionException(
                "Transicion concurrente sobre " + domain + "/" + entityId + ": se declaro venir de "
                        + declaredFrom + " pero el estado real es " + name(actualFrom)
                        + " (otra instancia movio la cabecera antes de la secuencia " + seq + ")",
                domain, entityId, seq, declaredFrom, actualFrom, null, null, null);
    }

    /** La cabecera es de otro ciclo: este perdio la carrera (fencing por cycleId). */
    public static ConcurrentTransitionException foreignCycle(String domain, String entityId,
                                                             String expectedCycle, String actualCycle, long seq) {
        return new ConcurrentTransitionException(
                "Transicion concurrente sobre " + domain + "/" + entityId + ": el ciclo " + expectedCycle
                        + " intento avanzar sobre la cabecera del ciclo " + actualCycle
                        + " (secuencia " + seq + ")",
                domain, entityId, seq, null, null, expectedCycle, actualCycle, null);
    }

    private static String name(SyncState state) {
        return state == null ? "ninguno" : state.name();
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

    /** Estado del que quien llama creia venir; {@code null} si el conflicto fue de ciclo o de secuencia. */
    public SyncState declaredFrom() {
        return declaredFrom;
    }

    /** Estado real de la cabecera al intentar escribir; {@code null} si no habia ninguno. */
    public SyncState actualFrom() {
        return actualFrom;
    }

    /** Ciclo que intentaba avanzar; {@code null} salvo en un conflicto de fencing. */
    public String expectedCycle() {
        return expectedCycle;
    }

    /** Ciclo dueno de la cabecera; {@code null} salvo en un conflicto de fencing. */
    public String actualCycle() {
        return actualCycle;
    }
}
