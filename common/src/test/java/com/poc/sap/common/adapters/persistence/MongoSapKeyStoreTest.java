package com.poc.sap.common.adapters.persistence;

import com.poc.sap.common.domain.port.SapKeyStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec docs/sdd/common/upsert-idempotente-sap.md AC-6: almacen de las claves que
 * asigna SAP ({@code AddressID}, {@code RelationshipNumber}...). Mockea el repo
 * de Spring Data para no levantar Testcontainers, como
 * {@link MongoSyncStateRepositoryTest}.
 */
@ExtendWith(MockitoExtension.class)
class MongoSapKeyStoreTest {

    @Mock SapKeyMongoRepository mongo;
    private SapKeyStorePort store;

    @BeforeEach
    void setUp() {
        store = new MongoSapKeyStore(mongo);
    }

    /**
     * AC-6: una clave por (dominio, entidad, feature). La direccion y el contacto
     * del mismo cliente no comparten fila: el {@code _id} las separa.
     */
    @Test
    void storesAndReadsTheKeyPerFeature() {
        store.save("customer", "C-1", "ADDRESS", "0000123456");

        ArgumentCaptor<SapKeyDoc> saved = ArgumentCaptor.forClass(SapKeyDoc.class);
        verify(mongo).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo("customer:C-1:ADDRESS");
        assertThat(saved.getValue().getKey()).isEqualTo("0000123456");
        assertThat(saved.getValue().getUpdatedAt()).isNotNull();

        when(mongo.findById("customer:C-1:ADDRESS"))
                .thenReturn(Optional.of(saved.getValue()));
        assertThat(store.find("customer", "C-1", "ADDRESS")).contains("0000123456");
    }

    /** AC-6: sin clave guardada, el adaptador tiene que resolverla; no se inventa un valor. */
    @Test
    void missingKeyIsEmpty() {
        when(mongo.findById("customer:C-9:CONTACT")).thenReturn(Optional.empty());

        assertThat(store.find("customer", "C-9", "CONTACT")).isEmpty();
    }

    /** AC-6: una clave vacia o nula no se guarda: taparia el hueco sin resolverlo. */
    @Test
    void blankKeysAreNotStored() {
        store.save("customer", "C-1", "ADDRESS", "   ");
        store.save("customer", "C-1", "ADDRESS", null);

        verify(mongo, never()).save(any());
    }

    /** AC-6: la baja de la entidad se lleva por delante todas sus claves. */
    @Test
    void deletingTheEntityRemovesEveryFeatureKey() {
        store.delete("customer", "C-1");

        verify(mongo).deleteByDomainAndEntityId("customer", "C-1");
    }
}
