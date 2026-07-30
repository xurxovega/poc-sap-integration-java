package com.poc.sap.article.bootstrap.web;

import com.poc.sap.article.application.ArticleHistoryUseCase;
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
 * Consulta REST del historico de versiones enviadas a SAP (Elasticsearch)
 * y diff entre versiones. Mismo contrato que el homologo de customer.
 */
@RestController
@RequestMapping("/articles")
public class ArticleHistoryController {

    private final ArticleHistoryUseCase historyUseCase;

    public ArticleHistoryController(ArticleHistoryUseCase historyUseCase) {
        this.historyUseCase = historyUseCase;
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<Map<String, Object>> history(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean full) {
        List<Map<String, Object>> versions = historyUseCase.history(id).stream()
                .map(s -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("payloadHash", s.payloadHash());
                    v.put("timestamp", s.timestamp());
                    if (full) {
                        v.put("snapshot", s.entity());
                    }
                    return v;
                })
                .toList();
        return ResponseEntity.ok(Map.of(
                "entityId", id,
                "versions", versions));
    }

    @GetMapping("/{id}/history/diff")
    public ResponseEntity<ArticleHistoryUseCase.HistoryDiff> diff(
            @PathVariable String id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(historyUseCase.diff(id, from, to));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage()));
    }
}
