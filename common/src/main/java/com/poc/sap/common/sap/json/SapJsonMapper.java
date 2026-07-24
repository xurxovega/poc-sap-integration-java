package com.poc.sap.common.sap.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Utilidad de serializacion JSON para clientes SAP.
 *
 * <p>Uso en adaptadores SAP (customer/article/supplier):
 * {@code String json = SapJsonMapper.write(addressDto);}
 */
public final class SapJsonMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
