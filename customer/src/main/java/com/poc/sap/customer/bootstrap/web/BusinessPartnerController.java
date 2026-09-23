package com.poc.sap.customer.bootstrap.web;

import com.poc.sap.common.security.AccessScope;
import com.poc.sap.common.security.ApiRoles;
import com.poc.sap.common.security.PiiMasker;
import com.poc.sap.customer.application.general.LookupBusinessPartnerUseCase;
import com.poc.sap.customer.domain.port.BusinessPartnerReadPort.BusinessPartnerSummary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Endpoints REST GET para consulta puntual de Business Partners ya creados en
 * SAP S/4 Public Cloud (PRD-9, spec
 * {@code docs/sdd/customer/consulta-business-partner-sap.md}).
 *
 * <p>Es lectura pura: no toca la maquina de estados ni el pipeline de
 * sincronizacion. Los GET contra la API OData de Business Partner no se
 * facturan en SAP (ver {@code architecture/INTEGRATION-PATTERNS.md}); la
 * pieza se monta sobre {@link BusinessPartnerReadPort}, ya instrumentado.
 *
 * <ul>
 *   <li>{@code GET /business-partners/{code}} — un BP por clave. 200 con
 *       {@code {code, name, category, masked}} o 404 si no existe.</li>
 *   <li>{@code GET /business-partners?category=&top=} — busqueda por
 *       categoria OData con {@code $top}. 200 con
 *       {@code {results, masked}}.</li>
 *   <li>{@code GET /business-partners/customers?top=} — atajo para
 *       categoria 2 (clientes).</li>
 *   <li>{@code GET /business-partners/suppliers?top=} — atajo para
 *       categoria 1 (proveedores).</li>
 * </ul>
 *
 * <p>Seguridad (spec {@code docs/sdd/common/seguridad-api.md} R-3 y R-4):
 * todos los endpoints admiten {@link ApiRoles#READ} (PII completa) o
 * {@link ApiRoles#EXTERNAL_READ} (PII enmascarada por
 * {@link AccessScope#canSeeSensitiveData()} y {@link PiiMasker#maskName}).
 *
 * <p>El {@code BusinessPartnerReadPort} solo se inyecta si
 * {@code BusinessPartnerReadEnabled} esta activo, asi que si nadie tiene
 * habilitado el adaptador de OData S/4 ninguno de los endpoints queda
 * registrado.
 */
@RestController
@RequestMapping("/business-partners")
public class BusinessPartnerController {

    private final LookupBusinessPartnerUseCase useCase;
    private final AccessScope accessScope;

    public BusinessPartnerController(LookupBusinessPartnerUseCase useCase, AccessScope accessScope) {
        this.useCase = useCase;
        this.accessScope = accessScope;
    }

    @GetMapping("/{code}")
    @PreAuthorize("hasAnyRole('" + ApiRoles.READ + "','" + ApiRoles.EXTERNAL_READ + "')")
    public ResponseEntity<Map<String, Object>> findById(@PathVariable String code) {
        BusinessPartnerSummary summary = useCase.findById(code);
        return ResponseEntity.ok(asSingle(summary));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('" + ApiRoles.READ + "','" + ApiRoles.EXTERNAL_READ + "')")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String category,
            @RequestParam int top) {
        List<BusinessPartnerSummary> results = useCase.search(category, top);
        return ResponseEntity.ok(asList(results));
    }

    @GetMapping("/customers")
    @PreAuthorize("hasAnyRole('" + ApiRoles.READ + "','" + ApiRoles.EXTERNAL_READ + "')")
    public ResponseEntity<Map<String, Object>> customers(@RequestParam int top) {
        return ResponseEntity.ok(asList(useCase.findCustomers(top)));
    }

    @GetMapping("/suppliers")
    @PreAuthorize("hasAnyRole('" + ApiRoles.READ + "','" + ApiRoles.EXTERNAL_READ + "')")
    public ResponseEntity<Map<String, Object>> suppliers(@RequestParam int top) {
        return ResponseEntity.ok(asList(useCase.findSuppliers(top)));
    }

    private Map<String, Object> asSingle(BusinessPartnerSummary s) {
        boolean sensitive = accessScope.canSeeSensitiveData();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", s.code());
        m.put("name", sensitive ? s.name() : PiiMasker.maskName(s.name()));
        m.put("category", s.category());
        m.put("masked", !sensitive);
        return m;
    }

    private Map<String, Object> asList(List<BusinessPartnerSummary> results) {
        boolean sensitive = accessScope.canSeeSensitiveData();
        List<Map<String, Object>> mapped = results.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", s.code());
            m.put("name", sensitive ? s.name() : PiiMasker.maskName(s.name()));
            m.put("category", s.category());
            return m;
        }).toList();
        return Map.of("results", mapped, "masked", !sensitive);
    }

    /** {@code NoSuchElementException} del use case → 404 (R-2). */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage()));
    }

    /**
     * {@code IllegalArgumentException} del use case (top fuera de rango, R-1)
     * o de Spring al parsear parametros → 400 con mensaje explicito.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }
}
