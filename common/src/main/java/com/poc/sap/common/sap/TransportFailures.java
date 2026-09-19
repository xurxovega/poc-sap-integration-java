package com.poc.sap.common.sap;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;

import javax.net.ssl.SSLException;

/**
 * ¿La peticion HTTP llego a salir? De eso depende que reintentar una escritura
 * sea seguro (spec {@code docs/sdd/common/resiliencia-cliente-sap.md} R-8).
 *
 * <p>Un {@code POST} cuya respuesta se pierde por timeout de respuesta pudo
 * haber sido aplicado por SAP: reintentarlo crea un duplicado. Un {@code POST}
 * que ni siquiera abrio la conexion no pudo crear nada y se reintenta sin
 * riesgo. Distinguir los dos casos es lo unico que separa «reintentar» de
 * «duplicar datos maestros».
 *
 * <p>Tres reglas que el orden de comprobaciones respeta y que no se pueden
 * reordenar:
 * <ol>
 *   <li>{@link HttpConnectTimeoutException} <b>hereda</b> de
 *       {@link HttpTimeoutException}: el hijo se comprueba antes que el padre o
 *       el timeout de conexion se clasificaria como «tras enviar».</li>
 *   <li>{@link UnresolvedAddressException} hereda de
 *       {@link IllegalArgumentException}: un fallo de DNS parece un error de
 *       programacion y no lo es. Ver {@link #isConfigurationError(Throwable)}.</li>
 *   <li>Ante la duda, {@link Phase#UNKNOWN}, que las escrituras tratan como
 *       «tras enviar». La asimetria del coste manda.</li>
 * </ol>
 */
public final class TransportFailures {

    private TransportFailures() {
    }

    /** Fase en la que fallo la comunicacion con SAP. */
    public enum Phase {
        /** La peticion no llego a salir: reintentarla no puede duplicar nada. */
        BEFORE_SEND,
        /** La peticion salio y no sabemos que hizo SAP con ella. */
        AFTER_SEND,
        /** No es un fallo de transporte reconocido; en escrituras se trata como AFTER_SEND. */
        UNKNOWN
    }

    /**
     * Recorre la cadena de causas hasta el fondo: Spring envuelve el fallo del
     * {@code HttpClient} del JDK en {@code ResourceAccessException}/{@code IOException}.
     *
     * @param t excepcion tal como la ve quien llama (puede ser {@code null})
     * @return la fase, nunca {@code null}
     */
    public static Phase classify(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            // --- antes de enviar: no hubo peticion ---
            if (c instanceof HttpConnectTimeoutException) return Phase.BEFORE_SEND;   // ANTES que HttpTimeoutException
            if (c instanceof ConnectException) return Phase.BEFORE_SEND;
            if (c instanceof UnknownHostException) return Phase.BEFORE_SEND;
            if (c instanceof UnresolvedAddressException) return Phase.BEFORE_SEND;
            if (c instanceof NoRouteToHostException) return Phase.BEFORE_SEND;
            // --- tras enviar: la peticion salio y no sabemos que paso ---
            if (c instanceof HttpTimeoutException) return Phase.AFTER_SEND;           // timeout de respuesta
            if (c instanceof SocketTimeoutException) return Phase.AFTER_SEND;
            if (c instanceof SocketException) return Phase.AFTER_SEND;                // connection reset
            if (c instanceof SSLException) return Phase.AFTER_SEND;                   // conservador
            if (c instanceof IOException) return Phase.AFTER_SEND;                    // conservador
            if (c == c.getCause()) break;                                             // cadena ciclica
        }
        return Phase.UNKNOWN;
    }

    /** Atajo de lectura: solo esta fase permite reintentar una escritura. */
    public static boolean isBeforeSend(Throwable t) {
        return classify(t) == Phase.BEFORE_SEND;
    }

    /**
     * Error de programacion o de configuracion ({@code Destino SAP no configurado}),
     * que no se reintenta ni cuenta para el circuit breaker. Un fallo de DNS
     * ({@link UnresolvedAddressException}) tambien es un
     * {@link IllegalArgumentException} y aqui <b>no</b> cuenta como tal.
     */
    public static boolean isConfigurationError(Throwable t) {
        if (classify(t) != Phase.UNKNOWN) {
            return false;
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof IllegalArgumentException) return true;
            if (c == c.getCause()) break;
        }
        return false;
    }
}
