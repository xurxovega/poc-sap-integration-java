package com.poc.sap.dashboard.customer.domain;

import java.util.List;

/**
 * Snapshot actual del cliente leido del customer-app (UI-001 H-2). Cada
 * {@code feature} es el VALUE OBJECT LOCAL de dashboard-customer; se duplica
 * el shape del modulo customer para mantener el aislamiento entre bounded
 * contexts.
 *
 * <p>Las features pueden ser {@code null}: un cliente que en su ultima
 * sincronizacion solo actualizo la direccion no tendra fiscal/contact/banking
 * rellenos. El dashboard los pinta como ausentes sin romper la pagina.
 *
 * @param entityId  identificador legacy (SQL Server) del cliente
 * @param code      codigo de cliente (business key, "CUST-001")
 * @param name      razon social (display)
 * @param status    estado (ACTIVE / INACTIVE / BLOCKED)
 * @param address   datos de direccion (puede ser null)
 * @param fiscal    datos fiscales (puede ser null)
 * @param contact   datos de contacto (puede ser null)
 * @param banking   datos bancarios (puede ser null)
 */
public record CustomerSnapshot(
        String entityId,
        String code,
        String name,
        Status status,
        AddressData address,
        FiscalData fiscal,
        ContactData contact,
        BankingData banking
) {
    public enum Status { ACTIVE, INACTIVE, BLOCKED }

    public CustomerSnapshot {
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("entityId obligatorio");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code obligatorio");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name obligatorio");
        }
        if (status == null) {
            throw new IllegalArgumentException("status obligatorio");
        }
    }

    /** Etiqueta amigable de las features del snapshot (preserva orden estable para la UI). */
    public List<String> nonNullFeatureKeys() {
        List<String> keys = new java.util.ArrayList<>();
        if (address != null) keys.add(CustomerFeature.ADDRESS.name());
        if (fiscal != null)  keys.add(CustomerFeature.FISCAL.name());
        if (contact != null) keys.add(CustomerFeature.CONTACT.name());
        if (banking != null) keys.add(CustomerFeature.BANKING.name());
        return List.copyOf(keys);
    }
}
