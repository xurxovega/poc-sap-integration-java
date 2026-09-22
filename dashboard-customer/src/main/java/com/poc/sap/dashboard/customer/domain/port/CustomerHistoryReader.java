package com.poc.sap.dashboard.customer.domain.port;

import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * Puerto de lectura del historico indexado en Elasticsearch (UI-001 H-2).
 * Una version por intento de envio: el id ES es {@code entityId-payloadHash-ms}
 * (misma regla que el customer-app), asi que el dashboard debe agrupar por
 * payloadHash y devolver el mas reciente.
 */
public interface CustomerHistoryReader {

    /** Versiones (CustomerSnapshot) del cliente, de mas reciente a mas antigua. */
    List<HistoryVersion> historyOf(String entityId);

    /** Una version del historico, con su hash y su instante. */
    record HistoryVersion(String entityId, String payloadHash, Instant timestamp, CustomerSnapshot snapshot) {}
}
