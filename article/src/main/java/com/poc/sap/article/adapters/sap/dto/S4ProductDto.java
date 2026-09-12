package com.poc.sap.article.adapters.sap.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.poc.sap.article.domain.Article;

/**
 * DTO de serializacion del producto para API_PRODUCT
 * (sdd/article/sincronizacion-articulo.md §5). Los campos nulos se omiten
 * (SapJsonMapper, NON_NULL): S/4 valida dominios y una cadena vacia no es
 * "sin valor" (auditoria A19/C10).
 */
public record S4ProductDto(
        @JsonProperty("Product")     String product,
        @JsonProperty("Description") String description,
        @JsonProperty("Category")    String category,
        @JsonProperty("BaseUnit")    String baseUnit,
        @JsonProperty("Status")      String status
) {
    public static S4ProductDto from(Article a) {
        return new S4ProductDto(a.sku(), a.description(), a.category(), a.unit(),
                a.status() != null ? a.status().name() : null);
    }
}
