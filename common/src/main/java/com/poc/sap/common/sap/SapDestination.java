package com.poc.sap.common.sap;

/**
 * Destino SAP al que se envia un payload (SPEC.md §5).
 */
public enum SapDestination {
    /** APIs BTP via Destination Service / xsuaa. */
    BTP,
    /** APIs nativas S/4 Public Cloud (OData/REST propio). */
    S4_NATIVE;
}
