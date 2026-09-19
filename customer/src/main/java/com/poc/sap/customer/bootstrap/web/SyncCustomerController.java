package com.poc.sap.customer.bootstrap.web;

import com.poc.sap.common.domain.ConcurrentTransitionException;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.OperationType;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.customer.application.general.SyncCustomerUseCase;
import com.poc.sap.customer.application.general.ValidateCustomerUseCase;
import com.poc.sap.common.security.ApiRoles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller del dominio Customer (TECH.md §6).
 * Entrada alternativa (ingesta por API). Reusa el mismo use case que CDC/Kafka.
 * Acceso: rol WRITE o superior (sdd/common/seguridad-api.md §5): dispara
 * escrituras facturables en S/4.
 */
@RestController
@RequestMapping("/customers")
public class SyncCustomerController {

    private final SyncCustomerUseCase syncUseCase;
    private final ValidateCustomerUseCase validateUseCase;

    public SyncCustomerController(SyncCustomerUseCase syncUseCase,
                                  ValidateCustomerUseCase validateUseCase) {
        this.syncUseCase = syncUseCase;
        this.validateUseCase = validateUseCase;
    }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('" + ApiRoles.WRITE + "')")
    public ResponseEntity<Map<String, Object>> sync(@RequestBody SyncRequest req) {
        IngestionMessage msg = new IngestionMessage(
                req.entityId(),
                "customer",
                req.operation() != null ? req.operation() : OperationType.UPDATE,
                IngestionOrigin.REST,
                req.payloadHash(),
                req.payload());
        SyncState state = syncUseCase.execute(msg);
        return ResponseEntity.ok(Map.of(
                "entityId", req.entityId(),
                "state", state.name()));
    }

    @PostMapping("/validate")
    @PreAuthorize("hasRole('" + ApiRoles.WRITE + "')")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody ValidateRequest req) {
        SyncState state = validateUseCase.execute(req.entityId(), req.payloadHash());
        return ResponseEntity.ok(Map.of(
                "entityId", req.entityId(),
                "state", state.name()));
    }

    /**
     * Colision de concurrencia sobre la misma entidad (ADR-0011): otro ciclo
     * -Kafka o REST- la esta procesando. No es un fallo del servidor, es un
     * conflicto temporal: 409 y que el llamante reintente. Sin identificadores
     * de negocio en el detalle (minimizacion, seguridad-api.md).
     */
    @ExceptionHandler(ConcurrentTransitionException.class)
    public ProblemDetail onConcurrentTransition(ConcurrentTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "La entidad la esta procesando otro ciclo de sincronizacion; reintentelo.");
        problem.setTitle("Sincronizacion concurrente");
        return problem;
    }

    public record SyncRequest(String entityId, OperationType operation,
                               String payloadHash, String payload) {}

    public record ValidateRequest(String entityId, String payloadHash) {}
}