package com.poc.sap.common.adapters.persistence;

import com.poc.sap.common.domain.port.SapKeyStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Adaptador Mongo del almacen de claves SAP (TECH.md §7; spec
 * {@code docs/sdd/common/upsert-idempotente-sap.md} R-4).
 *
 * <p>Coleccion {@code sap_keys}, una fila por (dominio, entidad, feature).
 */
@Repository
public class MongoSapKeyStore implements SapKeyStorePort {

    private static final Logger log = LoggerFactory.getLogger(MongoSapKeyStore.class);

    private final SapKeyMongoRepository mongo;

    public MongoSapKeyStore(SapKeyMongoRepository mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<String> find(String domain, String entityId, String feature) {
        return mongo.findById(id(domain, entityId, feature))
                .map(SapKeyDoc::getKey)
                .filter(k -> !k.isBlank());
    }

    @Override
    public void save(String domain, String entityId, String feature, String key) {
        if (key == null || key.isBlank()) {
            // Guardar un hueco es peor que no guardar nada: el ciclo siguiente
            // creeria que ya conoce la clave y actualizaria contra la nada.
            log.debug("Clave SAP vacia para domain={} entityId={} feature={}: no se guarda",
                    domain, entityId, feature);
            return;
        }
        mongo.save(new SapKeyDoc(id(domain, entityId, feature), domain, entityId, feature,
                key, Instant.now()));
    }

    @Override
    public void delete(String domain, String entityId) {
        mongo.deleteByDomainAndEntityId(domain, entityId);
    }

    private static String id(String domain, String entityId, String feature) {
        return domain + ":" + entityId + ":" + feature;
    }
}
