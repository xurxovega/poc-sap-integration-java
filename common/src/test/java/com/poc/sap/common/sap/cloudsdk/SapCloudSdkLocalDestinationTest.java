package com.poc.sap.common.sap.cloudsdk;

import com.sap.cloud.sdk.cloudplatform.connectivity.Destination;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationProperty;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que el SAP Cloud SDK puede resolver un destino local registrado
 * mediante {@link SapCloudSdkLocalDestinationConfig}.
 */
@SpringBootTest(classes = SapCloudSdkLocalDestinationConfig.class)
@TestPropertySource(properties = "sap.cloud-sdk.local-destination.enabled=true")
class SapCloudSdkLocalDestinationTest {

    @Test
    void localDestinationIsResolvable() {
        Destination destination = DestinationAccessor.getDestination(
                SapCloudSdkLocalDestinationConfig.LOCAL_DESTINATION_NAME);

        assertThat(destination).isNotNull();
        String uri = destination.get(DestinationProperty.URI).getOrElse("");
        assertThat(uri).isEqualTo("http://localhost:8080");
    }
}
