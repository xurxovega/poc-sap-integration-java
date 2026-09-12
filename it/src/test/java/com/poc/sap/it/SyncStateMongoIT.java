package com.poc.sap.it;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.poc.sap.common.adapters.persistence.MongoSyncStateRepository;
import com.poc.sap.common.adapters.persistence.SyncStateDoc;
import com.poc.sap.common.adapters.persistence.SyncStateMongoRepository;
import com.poc.sap.common.domain.ConcurrentTransitionException;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * El repositorio de estado contra un Mongo REAL (sdd/common/maquina-de-estados.md
 * AC-11..AC-13; TEST-1 del backlog). Lo que ningun mock puede probar: el indice
 * unico parcial con documentos antiguos sin secuencia, y dos escritores
 * concurrentes sin pisarse.
 *
 * <p>Se activa con {@code -Ddocker.available=true}, como InfrastructureSmokeIT.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "docker.available", matches = "true")
class SyncStateMongoIT {

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    private MongoClient client;
    private MongoTemplate template;
    private SyncStateMongoRepository springRepo;
    private MongoSyncStateRepository repo;

    @BeforeEach
    void setUp() {
        client = MongoClients.create(mongo.getReplicaSetUrl("customer"));
        template = new MongoTemplate(client, "customer");
        template.dropCollection(SyncStateDoc.class);
        // Los mismos indices que declara SyncStateDoc y crea external-services/mongodb/init.js.
        template.indexOps(SyncStateDoc.class).createIndex(
                new CompoundIndexDefinition(new Document("domain", 1).append("entityId", 1).append("timestamp", -1))
                        .named("dom_ent_idx"));
        template.indexOps(SyncStateDoc.class).createIndex(
                new CompoundIndexDefinition(new Document("domain", 1).append("entityId", 1).append("seq", 1))
                        .named("dom_ent_seq_uk").unique()
                        .partial(PartialIndexFilter.of(Criteria.where("seq").exists(true))));
        springRepo = new MongoRepositoryFactory(template).getRepository(SyncStateMongoRepository.class);
        repo = new MongoSyncStateRepository(springRepo);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private static SyncStateTransition t(SyncState to, String hash) {
        return new SyncStateTransition("C-1", "customer", null, to, "it", hash, Instant.now());
    }

    /** AC-4 del agregado, contra Mongo real: desde SAP_ERROR se abre ciclo nuevo. */
    @Test
    void resyncsAfterSapErrorAgainstRealMongo() {
        repo.beginCycle("customer", "C-1", t(SyncState.RECEIVED, "h-1"));
        for (SyncState s : List.of(SyncState.FETCHING, SyncState.VALIDATING, SyncState.VALID,
                SyncState.INDEXING, SyncState.INDEXED, SyncState.SENDING_SAP, SyncState.SAP_ERROR)) {
            repo.transition("customer", "C-1", t(s, "h-1"));
        }
        assertThat(repo.currentState("customer", "C-1")).contains(SyncState.SAP_ERROR);

        assertThatCode(() -> repo.beginCycle("customer", "C-1", t(SyncState.RECEIVED, "h-2")))
                .doesNotThrowAnyException();

        assertThat(repo.currentState("customer", "C-1")).contains(SyncState.RECEIVED);
        assertThat(repo.history("customer", "C-1")).hasSize(9);
    }

    /**
     * AC-12: dos escritores sobre la misma entidad NUNCA se pisan en silencio.
     * El resultado depende del entrelazado, asi que se afirma el invariante: o
     * ambos ganan con secuencias distintas, o exactamente uno recibe
     * ConcurrentTransitionException; y las secuencias almacenadas nunca se repiten.
     */
    @Test
    void concurrentWritersNeverOverwriteEachOther() throws Exception {
        repo.beginCycle("customer", "C-1", t(SyncState.RECEIVED, "h-1"));
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int round = 0; round < 10; round++) {
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger conflicts = new AtomicInteger();
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        try {
                            repo.transition("customer", "C-1", t(SyncState.ERROR, "h-race"));
                        } catch (ConcurrentTransitionException e) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> f : futures) {
                    f.get();
                }
                assertThat(conflicts.get()).as("ronda %d", round).isBetween(0, 1);
                // vuelve a un estado desde el que ERROR sea legal para la siguiente ronda
                repo.beginCycle("customer", "C-1", t(SyncState.RECEIVED, "h-" + round));
            }
        } finally {
            pool.shutdown();
        }
        List<Long> seqs = springRepo.findByDomainAndEntityIdOrderBySeqAscTimestampAsc("customer", "C-1")
                .stream().map(SyncStateDoc::seq).toList();
        assertThat(seqs).doesNotHaveDuplicates();
        assertThat(seqs).isSorted();
    }

    /**
     * AC-13 y la leccion del 2026-09-12: los documentos anteriores a la secuencia
     * no tienen el campo; el indice parcial no debe chocar con ellos (sparse
     * compuesto si chocaba: E11000 al arrancar).
     */
    @Test
    void legacyDocumentsWithoutSeqCoexistWithTheUniqueIndex() {
        for (int i = 0; i < 3; i++) {
            springRepo.save(SyncStateDoc.from("customer", "C-1", t(SyncState.SENT_SAP, "old-" + i), SyncState.SENT_SAP.code()));
        }
        assertThatCode(() -> repo.beginCycle("customer", "C-1", t(SyncState.RECEIVED, "h-new")))
                .doesNotThrowAnyException();

        SyncStateDoc head = springRepo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("customer", "C-1").orElseThrow();
        assertThat(head.seq()).isEqualTo(1L);
        assertThat(SyncState.ofCode(head.stateCode())).isEqualTo(SyncState.RECEIVED);
    }
}
