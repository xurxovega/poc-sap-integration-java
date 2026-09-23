package com.poc.sap.article.domain;

/**
 * Entidad Article del dominio (OVERVIEW.md §2).
 *
 * @param id           identificador legacy (PostgreSQL)
 * @param sku          codigo de articulo (business key)
 * @param description  descripcion
 * @param category     categoria
 * @param unit         unidad de medida
 * @param status        estado (ACTIVE/INACTIVE/DISCONTINUED)
 */
public record Article(
        String id,
        String sku,
        String description,
        String category,
        String unit,
        Status status
) {
    public enum Status { ACTIVE, INACTIVE, DISCONTINUED }

    public Article {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id obligatorio");
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku obligatorio");
        }
    }
}