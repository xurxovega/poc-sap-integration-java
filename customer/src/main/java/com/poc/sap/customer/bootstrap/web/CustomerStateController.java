package com.poc.sap.customer.bootstrap.web;

import com.poc.sap.common.security.ApiRoles;
import com.poc.sap.customer.application.general.CustomerStateUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /customers/{id}/state}: estado del agregado y de cada parte
 * (ADDRESS, FISCAL, CONTACT, BANKING) con su ultimo hash e instante. Sin PII:
 * lo puede consultar tambien la lectura externa. Es la respuesta a "donde ha
 * dado el error" tras una alerta de sincronizacion parcial (ADR-0010).
 */
@RestController
@RequestMapping("/customers")
public class CustomerStateController {

    private final CustomerStateUseCase stateUseCase;

    public CustomerStateController(CustomerStateUseCase stateUseCase) {
        this.stateUseCase = stateUseCase;
    }

    @GetMapping("/{id}/state")
    @PreAuthorize("hasAnyRole('" + ApiRoles.READ + "','" + ApiRoles.EXTERNAL_READ + "')")
    public ResponseEntity<CustomerStateUseCase.EntityState> state(@PathVariable String id) {
        return ResponseEntity.ok(stateUseCase.of(id));
    }
}
