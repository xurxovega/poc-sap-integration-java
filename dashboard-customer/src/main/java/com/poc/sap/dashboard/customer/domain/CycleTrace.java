package com.poc.sap.dashboard.customer.domain;

import com.poc.sap.common.domain.SyncState;

import java.time.Instant;
import java.util.List;

/**
 * Traza de pasos de un envio (UI-001 H-2). Es lo que el operador mira
 * cuando llega una alerta de sincronizacion parcial (ADR-0010).
 *
 * @param cycleId    identificador del ciclo (compartido por la cabecera del
 *                   agregado y las lineas de feature)
 * @param payloadHash hash del payload enviado
 * @param startedAt  instante de la primera transicion del ciclo
 * @param endedAt    instante de la ultima (o null si el ciclo sigue en vuelo)
 * @param steps      lista de pasos del ciclo (incluye la linea agregada y
 *                   las de feature, en orden de escritura)
 */
public record CycleTrace(
        String cycleId,
        String payloadHash,
        Instant startedAt,
        Instant endedAt,
        List<Step> steps
) {
    /**
     * Un paso: la linea del ciclo (la cabecera del agregado o una de sus
     * features), el estado al que paso, el motivo si fue de error, y el
     * instante en que se persistio.
     */
    public record Step(
            String line,
            SyncState state,
            String detail,
            Instant at
    ) {}
}
