package com.poc.sap.article.bootstrap.web;

import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.OperationType;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.article.application.SyncArticleUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/articles")
public class SyncArticleController {

    private final SyncArticleUseCase syncUseCase;

    public SyncArticleController(SyncArticleUseCase syncUseCase) {
        this.syncUseCase = syncUseCase;
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(@RequestBody SyncRequest req) {
        IngestionMessage msg = new IngestionMessage(
                req.entityId(),
                "article",
                req.operation() != null ? req.operation() : OperationType.UPDATE,
                IngestionOrigin.REST,
                req.payloadHash(),
                req.payload());
        SyncState state = syncUseCase.execute(msg);
        return ResponseEntity.ok(Map.of(
                "entityId", req.entityId(),
                "state", state.name()));
    }

    public record SyncRequest(String entityId, OperationType operation,
                               String payloadHash, String payload) {}
}