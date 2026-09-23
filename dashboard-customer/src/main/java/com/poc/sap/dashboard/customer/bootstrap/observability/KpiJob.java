package com.poc.sap.dashboard.customer.bootstrap.observability;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Job de KPIs operativos del dashboard-customer (UI-001 H-5 F-12). Se
 * ejecuta periodicamente (default 5 min) contra el {@code sync_state} de
 * Mongo y mantiene dos gauges que el dashboard expone en su
 * {@code /actuator/prometheus}:
 *
 * <ul>
 *   <li>{@code business_kpi_mttr_seconds{window="7d"}}: tiempo medio de
 *       recuperacion tras un fallo parcial o total: cuanto tarda el
 *       sistema en pasar de un estado de error (SAP_ERROR, ERROR,
 *       COMMUNICATION_ERROR) a un SENT_SAP del mismo agregado, dentro
 *       de los ultimos 7 dias.</li>
 *   <li>{@code business_kpi_recovery_p95_seconds{cycle="last"}}: p95
 *       del mismo calculo sobre el set completo de recuperaciones (no
 *       ventana) -> la p95 del ultimo ciclo de calculo.</li>
 * </ul>
 *
 * <p>El job vive dentro del dashboard-customer (no en {@code common}) por
 * dos razones:
 * <ol>
 *   <li>Los gauges son nombres especificos del dashboard; mezclarlos en
 *       common haria aparecer series similares en apps que no aplican
 *       (article, supplier).</li>
 *   <li>El acceso a Mongo es directo: el job no reutiliza los use cases
 *       (no es parte del bounded context de aplicacion: es observabilidad
 *       operativa) ni expone puertos en el dominio.</li>
 * </ol>
 */
@Component
public class KpiJob {

    /** Una alerta abierta contada en la ultima tick. La expone {@code openAlerts()}. */
    public record AlertRow(String alertId, String entityId, Instant openedAt) {
        /** Proveedor trivial para el test sin levantar el bus de alertas real. */
        public interface Provider {
            java.util.List<AlertRow> open();
        }
    }

    private final MeterRegistry registry;
    private final Supplier<MongoDatabase> database;
    private final Duration window;
    private final AtomicReference<Double> mttrValue = new AtomicReference<>(0.0);
    private final AtomicReference<Double> p95Value = new AtomicReference<>(0.0);

    public KpiJob(MeterRegistry registry, Supplier<MongoDatabase> database, Duration window) {
        this.registry = registry;
        this.database = database;
        this.window = window;

        Gauge.builder("business_kpi_mttr_seconds", mttrValue, ref -> ref.get())
                .tag("window", window.toDays() + "d")
                .description("Tiempo medio de recuperacion post-fallo en la ventana del dashboard")
                .register(registry);
        Gauge.builder("business_kpi_recovery_p95_seconds", p95Value, ref -> ref.get())
                .tag("cycle", "last")
                .description("p95 del tiempo de recuperacion en el ultimo calculo")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${app.dashboard.kpi.refresh-seconds:300}000")
    public void recompute() {
        recompute(List::of);
    }

    /**
     * Recalculo explicito: lee {@code sync_state}, busca los pares
     * {@code (entityId, cycleId)} que acaban en SENT_SAP despues de un estado
     * de error, mide la diferencia entre el primer error y el siguiente
     * SENT_SAP del mismo ciclo y publica los gauges.
     */
    public void recompute(AlertRow.Provider alerts) {
        Instant cutoff = Instant.now().minus(window);
        MongoCollection<Document> sync = database.get().getCollection("sync_state");

        // Lineas en estado de error dentro de la ventana, agrupadas por (entityId, cycleId).
        List<Document> errorLines = new ArrayList<>();
        sync.find(Filters.and(
                        Filters.eq("domain", "customer"),
                        Filters.in("stateCode",
                                com.poc.sap.common.domain.SyncState.SAP_ERROR.code(),
                                com.poc.sap.common.domain.SyncState.ERROR.code(),
                                com.poc.sap.common.domain.SyncState.COMMUNICATION_ERROR.code()),
                        Filters.gte("timestamp", Date.from(cutoff))))
                .sort(Sorts.orderBy(Sorts.ascending("entityId"), Sorts.ascending("cycleId"), Sorts.ascending("seq")))
                .forEach(errorLines::add);

        List<Double> recoveries = new ArrayList<>();
        for (Document err : errorLines) {
            String entityId = err.getString("entityId");
            String cycleId = err.getString("cycleId");
            Date errAt = err.getDate("timestamp");
            if (errAt == null || cycleId == null) continue;
            // El siguiente SENT_SAP del mismo ciclo indica la recuperacion.
            Document sent = sync.find(Filters.and(
                            Filters.eq("domain", "customer"),
                            Filters.eq("cycleId", cycleId),
                            Filters.eq("stateCode",
                                    com.poc.sap.common.domain.SyncState.SENT_SAP.code()),
                            Filters.gt("timestamp", errAt)))
                    .sort(Sorts.ascending("seq"))
                    .first();
            if (sent == null) continue;
            Date sentAt = sent.getDate("timestamp");
            if (sentAt == null) continue;
            long millis = sentAt.toInstant().toEpochMilli() - errAt.toInstant().toEpochMilli();
            if (millis >= 0) {
                recoveries.add(millis / 1000.0);
            }
        }
        double mttr = recoveries.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double p95 = percentile(recoveries, 0.95);
        mttrValue.set(mttr);
        p95Value.set(p95);
    }

    static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int idx = (int) Math.floor(p * (sorted.size() - 1));
        return sorted.get(idx);
    }
}
