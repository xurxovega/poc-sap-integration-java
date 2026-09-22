package com.poc.sap.dashboard.customer.application;

import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;
import com.poc.sap.dashboard.customer.domain.port.CustomerHistoryReader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Diff entre dos versiones del historico (UI-001 H-2). Implementacion propia
 * (no reusa {@code common.diff.JsonDiff}) para que el dashboard sea
 * completamente autonomo: lo unico que compara son los campos del value
 * object de dashboard-customer.
 */
public class GetHistoryDiff {

    /** Cambio en un campo: valor anterior y nuevo. */
    public record Change(Object from, Object to) {}

    /** Resultado del diff. */
    public record Diff(CustomerSnapshot from, CustomerSnapshot to,
                       String fromHash, String toHash,
                       Map<String, Change> changes,
                       List<String> changedFields) {}

    private final CustomerHistoryReader history;

    public GetHistoryDiff(CustomerHistoryReader history) {
        this.history = history;
    }

    public Diff diff(String entityId, String fromHash, String toHash) {
        List<CustomerHistoryReader.HistoryVersion> versions = history.historyOf(entityId);
        if (versions.isEmpty()) {
            throw new NoSuchElementException("Sin historico para customer " + entityId);
        }
        CustomerHistoryReader.HistoryVersion to = (toHash == null)
                ? versions.get(0)
                : byHash(versions, toHash, entityId);
        CustomerHistoryReader.HistoryVersion from = (fromHash == null)
                ? previousOf(versions, to, entityId)
                : byHash(versions, fromHash, entityId);
        Map<String, Change> changes = compare(from.snapshot(), to.snapshot());
        return new Diff(from.snapshot(), to.snapshot(),
                from.payloadHash(), to.payloadHash(),
                changes, List.copyOf(changes.keySet()));
    }

    private static CustomerHistoryReader.HistoryVersion byHash(
            List<CustomerHistoryReader.HistoryVersion> versions, String hash, String entityId) {
        return versions.stream()
                .filter(v -> hash.equals(v.payloadHash()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "Version " + hash + " no existe en el historico de customer " + entityId));
    }

    private static CustomerHistoryReader.HistoryVersion previousOf(
            List<CustomerHistoryReader.HistoryVersion> versions,
            CustomerHistoryReader.HistoryVersion to, String entityId) {
        int idx = versions.indexOf(to);
        if (idx < 0 || idx + 1 >= versions.size()) {
            throw new NoSuchElementException(
                    "No hay version anterior a " + to.payloadHash() + " para customer " + entityId);
        }
        return versions.get(idx + 1);
    }

    private static Map<String, Change> compare(CustomerSnapshot a, CustomerSnapshot b) {
        Map<String, Change> changes = new LinkedHashMap<>();
        if (!eq(a.code(), b.code())) changes.put("code", new Change(a.code(), b.code()));
        if (!eq(a.name(), b.name())) changes.put("name", new Change(a.name(), b.name()));
        if (!eq(a.status(), b.status())) changes.put("status", new Change(a.status(), b.status()));
        if (!eq(a.address(), b.address())) changes.put("address", new Change(a.address(), b.address()));
        if (!eq(a.fiscal(),  b.fiscal()))  changes.put("fiscal",  new Change(a.fiscal(),  b.fiscal()));
        if (!eq(a.contact(), b.contact())) changes.put("contact", new Change(a.contact(), b.contact()));
        if (!eq(a.banking(), b.banking())) changes.put("banking", new Change(a.banking(), b.banking()));
        return changes;
    }

    private static boolean eq(Object x, Object y) {
        if (x == null) return y == null;
        return x.equals(y);
    }
}
