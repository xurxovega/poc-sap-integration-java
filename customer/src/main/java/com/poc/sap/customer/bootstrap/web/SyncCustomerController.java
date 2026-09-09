package com.poc.sap.customer.bootstrap.web;

import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.OperationType;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.customer.application.general.SyncCustomerUseCase;
import com.poc.sap.customer.application.general.ValidateCustomerUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller del dominio Customer (TECH.md §6).
 * Entrada alternativa (ingesta por API). Reusa el mismo use case que CDC/Kafka.
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
    public ResponseEntity<Map<String, Object>> validate(@RequestBody ValidateRequest req) {
        SyncState state = validateUseCase.execute(req.entityId(), req.payloadHash());
        return ResponseEntity.ok(Map.of(
                "entityId", req.entityId(),
                "state", state.name()));
    }

    public record SyncRequest(String entityId, OperationType operation,
                               String payloadHash, String payload) {}

    public record ValidateRequest(String entityId, String payloadHash) {}
}