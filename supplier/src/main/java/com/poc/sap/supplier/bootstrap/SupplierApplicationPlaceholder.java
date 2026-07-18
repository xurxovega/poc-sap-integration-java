package com.poc.sap.supplier.bootstrap;

/**
 * Placeholder del dominio SUPPLIER (SPEC.md §3, §10 futuro).
 * Pendiente de implementar siguiendo el patron de customer/article:
 * domain (Supplier, SupplierValidations, ports) → application (SyncSupplierUseCase)
 * → adapters (kafka, repos, sap) → bootstrap (Spring wiring).
 *
 * <p>Se incluye en el reactor para fijar la estructura pero no es desplegable
 * hasta que se implemente.
 */
public final class SupplierApplicationPlaceholder {

    private SupplierApplicationPlaceholder() {}

    public static void main(String[] args) {
        throw new UnsupportedOperationException(
            "Dominio SUPPLIER pendiente de implementar (SPEC.md §3)");
    }
}