package com.poc.sap.common.sap;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.poc.sap.common.sap.TransportFailures.Phase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Spec docs/sdd/common/resiliencia-cliente-sap.md R-8 (AC-15): clasificacion de
 * un fallo de transporte en «antes de enviar» y «tras enviar».
 *
 * <p>Los tres ultimos tests NO son unitarios a proposito: ejercitan el mismo
 * transporte que produccion ({@code RestClient} sobre el {@code HttpClient} del
 * JDK) contra un puerto cerrado y contra un servidor lento, porque la excepcion
 * concreta que sale de esa pila es justo lo que la clasificacion tiene que
 * acertar. Verificarlo suponiendo la jerarquia era el punto fragil del diseno.
 */
class TransportFailuresTest {

    private WireMockServer wiremock;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    /**
     * AC-15: el timeout de CONEXION es «antes de enviar» y el de RESPUESTA «tras
     * enviar», aunque {@code HttpConnectTimeoutException} herede de
     * {@code HttpTimeoutException}: si se comprobara el padre primero, el unico
     * reintento legitimo de una escritura se perderia.
     */
    @Test
    void connectTimeoutIsBeforeSendAndReadTimeoutIsAfterSend() {
        assertThat(HttpTimeoutException.class)
                .isAssignableFrom(HttpConnectTimeoutException.class);

        assertThat(TransportFailures.classify(new HttpConnectTimeoutException("connect")))
                .isEqualTo(Phase.BEFORE_SEND);
        assertThat(TransportFailures.classify(new HttpTimeoutException("read")))
                .isEqualTo(Phase.AFTER_SEND);
    }

    /** AC-15: Spring envuelve el fallo del transporte, asi que se recorre la cadena hasta el fondo. */
    @Test
    void theCauseChainIsWalkedToTheBottom() {
        Throwable wrapped = new IllegalStateException("capa 1",
                new RuntimeException("capa 2", new ConnectException("Connection refused")));

        assertThat(TransportFailures.classify(wrapped)).isEqualTo(Phase.BEFORE_SEND);
    }

    /**
     * AC-15: lo que no se reconoce no se supone seguro. Una clasificacion erronea
     * hacia «antes de enviar» duplica datos en SAP; hacia «tras enviar» solo
     * obliga a reenviar con el siguiente evento.
     */
    @Test
    void unknownFailuresAreTreatedAsAfterSend() {
        assertThat(TransportFailures.classify(new IllegalStateException("bug"))).isEqualTo(Phase.UNKNOWN);
        assertThat(TransportFailures.isBeforeSend(new IllegalStateException("bug"))).isFalse();
        assertThat(TransportFailures.classify(null)).isEqualTo(Phase.UNKNOWN);
    }

    /**
     * AC-15: {@code UnresolvedAddressException} hereda de
     * {@code IllegalArgumentException}, que en este repo significaba «error de
     * programacion»: un fallo de DNS transitorio no se reintentaba y el mensaje
     * acababa en la DLT. Es un fallo de transporte anterior al envio.
     */
    @Test
    void unresolvedAddressIsTransportNotAProgrammingError() {
        assertThat(new UnresolvedAddressException()).isInstanceOf(IllegalArgumentException.class);

        assertThat(TransportFailures.classify(new UnresolvedAddressException())).isEqualTo(Phase.BEFORE_SEND);
        assertThat(TransportFailures.isConfigurationError(new UnresolvedAddressException())).isFalse();
        assertThat(TransportFailures.isConfigurationError(
                new IllegalArgumentException("Destino SAP no configurado"))).isTrue();
    }

    /** AC-15 (verificado contra la pila real): conexion rechazada = antes de enviar. */
    @Test
    void connectionRefusedOnTheRealStackIsBeforeSend() throws IOException {
        int closedPort = closedPort();

        Throwable thrown = catchThrowable(() -> exchange("http://127.0.0.1:" + closedPort, "/x",
                Duration.ofSeconds(2), Duration.ofSeconds(2)));

        assertThat(thrown).as("la pila real debe fallar contra un puerto cerrado").isNotNull();
        assertThat(TransportFailures.classify(thrown))
                .as("cadena: %s", chainOf(thrown))
                .isEqualTo(Phase.BEFORE_SEND);
    }

    /** AC-15 (verificado contra la pila real): timeout de respuesta = tras enviar. */
    @Test
    void responseTimeoutOnTheRealStackIsAfterSend() {
        wiremock.stubFor(get(urlEqualTo("/lento"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2000)));

        Throwable thrown = catchThrowable(() -> exchange(wiremock.baseUrl(), "/lento",
                Duration.ofSeconds(2), Duration.ofMillis(200)));

        assertThat(thrown).as("la pila real debe fallar con el servidor lento").isNotNull();
        assertThat(TransportFailures.classify(thrown))
                .as("cadena: %s", chainOf(thrown))
                .isEqualTo(Phase.AFTER_SEND);
    }

    /**
     * AC-15 (verificado contra la pila real): un host que no resuelve es «antes de
     * enviar». El TLD {@code .invalid} esta reservado y no existe; si el resolver
     * de la maquina lo secuestrase y respondiera, el test se salta en vez de fallar.
     */
    @Test
    void unresolvedHostOnTheRealStackIsBeforeSend() {
        Throwable thrown = catchThrowable(() -> exchange("http://sap-no-existe.invalid", "/x",
                Duration.ofSeconds(2), Duration.ofSeconds(2)));

        assumeTrue(thrown != null, "el resolver DNS respondio a un dominio .invalid: no se puede probar aqui");
        assertThat(TransportFailures.classify(thrown))
                .as("cadena: %s", chainOf(thrown))
                .isEqualTo(Phase.BEFORE_SEND);
    }

    /** Mismo transporte que produccion: RestClient + JdkClientHttpRequestFactory. */
    private static void exchange(String baseUrl, String path, Duration connect, Duration response) {
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(connect)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(response);
        org.springframework.web.client.RestClient.builder()
                .baseUrl(baseUrl).requestFactory(factory).build()
                .get().uri(path).retrieve().toBodilessEntity();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String chainOf(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append(" <- ");
        }
        return sb.toString();
    }
}
