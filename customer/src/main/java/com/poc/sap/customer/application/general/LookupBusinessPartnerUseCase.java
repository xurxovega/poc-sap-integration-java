package com.poc.sap.customer.application.general;

import com.poc.sap.customer.domain.port.BusinessPartnerReadPort;
import com.poc.sap.customer.domain.port.BusinessPartnerReadPort.BusinessPartnerSummary;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Lectura puntual de Business Partners ya existentes en SAP S/4 Public Cloud.
 *
 * <p>Es la pieza de aplicacion que cubre PRD-9 (consulta de BP, GET, sin
 * coste). No toca la maquina de estados ni el pipeline de sincronizacion:
 * delega tal cual en el puerto de lectura existente y solo aplica dos reglas
 * de la capa de aplicacion:
 *
 * <ul>
 *   <li>Validar que el {@code top} este en {@code [1, 200]} antes de pedirle
 *       nada a S/4 (R-1 del spec
 *       {@code docs/sdd/customer/consulta-business-partner-sap.md}).</li>
 *   <li>Convertir el {@code Optional.empty()} del puerto en
 *       {@link NoSuchElementException} para que el controller mapee a 404
 *       (R-2, mismo patron que {@code CustomerHistoryUseCase.diff}).</li>
 * </ul>
 *
 * <p>El resto (PII enmascarada, formato de respuesta) vive en el controller:
 * aqui no se mira quien llama, solo se valida lo que llega.
 */
public class LookupBusinessPartnerUseCase {

    private static final int TOP_MIN = 1;
    private static final int TOP_MAX = 200;

    private final BusinessPartnerReadPort port;

    public LookupBusinessPartnerUseCase(BusinessPartnerReadPort port) {
        this.port = port;
    }

    /**
     * @throws NoSuchElementException si SAP no tiene ese {@code code}.
     */
    public BusinessPartnerSummary findById(String code) {
        return port.findById(code).orElseThrow(() ->
                new NoSuchElementException("BP " + code + " no existe en SAP"));
    }

    /**
     * @throws IllegalArgumentException si {@code top} esta fuera de {@code [1, 200]}.
     */
    public List<BusinessPartnerSummary> search(String category, int top) {
        validateTop(top);
        return port.searchByCategory(category, top);
    }

    /**
     * @throws IllegalArgumentException si {@code top} esta fuera de {@code [1, 200]}.
     */
    public List<BusinessPartnerSummary> findCustomers(int top) {
        validateTop(top);
        return port.findCustomers(top);
    }

    /**
     * @throws IllegalArgumentException si {@code top} esta fuera de {@code [1, 200]}.
     */
    public List<BusinessPartnerSummary> findSuppliers(int top) {
        validateTop(top);
        return port.findSuppliers(top);
    }

    private static void validateTop(int top) {
        if (top < TOP_MIN || top > TOP_MAX) {
            throw new IllegalArgumentException(
                    "top fuera de rango [" + TOP_MIN + ".." + TOP_MAX + "]: [" + top + "]");
        }
    }
}
