package com.poc.sap.customer.bootstrap.web;

import com.poc.sap.common.security.AccessScope;
import com.poc.sap.common.security.ApiRoles;
import com.poc.sap.customer.application.general.CustomerStateUseCase;
import com.poc.sap.customer.application.general.CustomerStateUseCase.CycleTrace;
import com.poc.sap.customer.application.general.CustomerStateUseCase.EntityState;
import com.poc.sap.customer.application.general.CustomerStateUseCase.LineState;
import com.poc.sap.customer.application.general.CustomerStateUseCase.Step;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /customers/{id}/state}: estado del agregado y de cada parte
 * (ADDRESS, FISCAL, CONTACT, BANKING) con su ultimo hash, su motivo y su
 * instante, mas la traza de pasos del ultimo ciclo. Es la respuesta a "donde ha
 * dado el error" tras una alerta de sincronizacion parcial (ADR-0010).
 *
 * <p>El motivo puede arrastrar el cuerpo de error de SAP, que en un 400 repite el
 * valor rechazado (IBAN, NIF, email). Para la lectura externa va enmascarado
 * (sdd/common/seguridad-api.md R-4).
 */
@RestController
@RequestMapping("/customers")
public class CustomerStateController {

    private final CustomerStateUseCase stateUseCase;
    private final AccessScope accessScope;

    public CustomerStateController(CustomerStateUseCase stateUseCase, AccessScope accessScope) {
        this.stateUseCase = stateUseCase;
        this.accessScope = accessScope;
    }

    @GetMapping("/{id}/state")
    @PreAuthorize("hasAnyRole('" + ApiRoles.READ + "','" + ApiRoles.EXTERNAL_READ + "')")
    public ResponseEntity<EntityState> state(@PathVariable String id) {
        EntityState state = stateUseCase.of(id);
        return ResponseEntity.ok(accessScope.canSeeSensitiveData() ? state : masked(state));
    }

    private static EntityState masked(EntityState s) {
        Map<String, LineState> features = new LinkedHashMap<>();
        s.features().forEach((name, line) -> features.put(name, mask(line)));
        return new EntityState(s.entityId(), mask(s.aggregate()), features, mask(s.lastCycle()));
    }

    private static LineState mask(LineState l) {
        return l == null ? null
                : new LineState(l.state(), l.payloadHash(), l.cycleId(), PiiMasker.maskDetail(l.detail()), l.at());
    }

    private static CycleTrace mask(CycleTrace t) {
        return t == null ? null
                : new CycleTrace(t.cycleId(), t.payloadHash(), t.startedAt(), t.endedAt(),
                        t.steps().stream()
                                .map(p -> new Step(p.line(), p.state(), PiiMasker.maskDetail(p.detail()), p.at()))
                                .toList());
    }
}
