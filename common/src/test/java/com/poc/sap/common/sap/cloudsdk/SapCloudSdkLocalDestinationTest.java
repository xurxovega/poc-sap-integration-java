package com.poc.sap.common.sap.cloudsdk;

import com.sap.cloud.sdk.cloudplatform.connectivity.Destination;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
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
        assertThat(destination.getUri().toString()).isEqualTo("http://localhost:8080");
    }
}
