package com.poc.sap.common.domain.port;

import java.util.Optional;

/**
 * Almacen de las claves que <b>asigna SAP</b> y que no podemos deducir
 * (spec {@code docs/sdd/common/upsert-idempotente-sap.md} R-4).
 *
 * <p>{@code AddressID} y {@code RelationshipNumber} los genera S/4 al dar de
 * alta la subentidad. Sin guardarlos, el ciclo siguiente no sabria QUE
 * actualizar y crearia un duplicado.
 *
 * <p>No van en la imagen de la entidad a proposito: la imagen significa «lo que
 * SAP tiene, persistido <b>solo tras el ACK</b>» ({@code idempotencia-y-dedupe.md}
 * R-4), y la clave se conoce ANTES del ACK y hay que conservarla aunque el ciclo
 * termine en error.
 *
 * <p>El ETag <b>no</b> se guarda aqui: envejece y produce 412. Se lee siempre en
 * el lookup inmediatamente anterior al PATCH.
 */
public interface SapKeyStorePort {

    /**
     * @param domain   dominio ({@code customer}, {@code article}...)
     * @param entityId identificador de la entidad en el sistema legacy
     * @param feature  feature que escribe la subentidad ({@code ADDRESS}, {@code CONTACT}...)
     * @return la clave que SAP asigno, si ya se conoce
     */
    Optional<String> find(String domain, String entityId, String feature);

    /** Guarda (o reemplaza) la clave. Una clave vacia no se guarda. */
    void save(String domain, String entityId, String feature, String key);

    /** Baja de la entidad: se olvidan todas sus claves, de todas las features. */
    void delete(String domain, String entityId);
}
