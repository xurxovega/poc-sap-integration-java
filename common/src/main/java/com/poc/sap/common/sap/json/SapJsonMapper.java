package com.poc.sap.common.sap.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Utilidad de serializacion JSON para clientes SAP.
 *
 * <p>Serializa omitiendo nulls: los modelos generados de la spec OData tienen
 * cientos de propiedades opcionales y S/4 rechaza {@code null} explicitos en
 * propiedades no anulables.
 *
 * <p>Uso en adaptadores SAP (customer/article/supplier):
 * {@code String json = SapJsonMapper.write(addressDto);}
 */
public final class SapJsonMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SapJsonMapper() {}

    public static String write(Object dto) {
        try {
            return MAPPER.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error serializando DTO SAP: " + dto.getClass().getSimpleName(), e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
