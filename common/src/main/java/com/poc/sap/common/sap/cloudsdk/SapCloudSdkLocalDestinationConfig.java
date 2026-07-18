package com.poc.sap.common.sap.cloudsdk;

import com.sap.cloud.sdk.cloudplatform.connectivity.AuthenticationType;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultHttpDestination;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración de destino SAP local para pruebas y desarrollo sin BTP.
 *
 * <p>Registra un destino HTTP llamado {@code local-s4} en el
 * {@link DestinationAccessor} estático del SAP Cloud SDK. Solo se activa cuando
 * la propiedad {@code sap.cloud-sdk.local-destination.enabled=true}.
 *
 * <p>En producción (BTP) este bean NO debe estar activo; los destinos se resuelven
 * desde el BTP Destination Service.
 */
@Configuration
@ConditionalOnProperty(name = "sap.cloud-sdk.local-destination.enabled", havingValue = "true")
public class SapCloudSdkLocalDestinationConfig {

    static final String LOCAL_DESTINATION_NAME = "local-s4";

    @PostConstruct
    public void registerLocalDestination() {
        var destination = DefaultHttpDestination.builder("http://localhost:8080")
                .authenticationType(AuthenticationType.NO_AUTHENTICATION)
                .build();

        DestinationAccessor.prependDestinationLoader((name, options) -> {
            if (LOCAL_DESTINATION_NAME.equals(name)) {
                return Try.success(destination);
            }
            return Try.failure(new IllegalArgumentException("Destination not found: " + name));
        });
    }

    @PreDestroy
    public void resetDestinationAccessor() {
        DestinationAccessor.setLoader(null);
    }
}
