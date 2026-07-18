package com.poc.sap.article.application;

import com.poc.sap.article.domain.Article;
import com.poc.sap.article.domain.port.ArticleHistoryIndexerPort;
import com.poc.sap.article.domain.port.ArticleImageStorePort;
import com.poc.sap.article.domain.port.ArticleLegacyRepositoryPort;
import com.poc.sap.article.domain.port.ArticleSapOutboundPort;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.OperationType;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.observability.SyncMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncArticleUseCaseTest {

    @Mock ArticleLegacyRepositoryPort legacyRepo;
    @Mock ArticleImageStorePort imageStore;
    @Mock ArticleHistoryIndexerPort historyIndexer;
    @Mock ArticleSapOutboundPort sapOutbound;
    @Mock SyncStateRepositoryPort stateRepo;
    @Mock SyncMetrics metrics;

    private SyncArticleUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SyncArticleUseCase(legacyRepo, imageStore, historyIndexer,
                sapOutbound, stateRepo, metrics);
    }

    private Article validArticle() {
        return new Article("A-1", "SKU-001", "Tornillo M6", "Hardware", "UN",
                Article.Status.ACTIVE);
    }

    private IngestionMessage ingestion() {
        return new IngestionMessage("A-1", "article", OperationType.UPDATE,
                IngestionOrigin.CDC, "hash-a", "{}");
    }

    @Test
    void happyPath() {
        when(legacyRepo.fetch("A-1")).thenReturn(Optional.of(validArticle()));
        when(sapOutbound.send(eq("A-1"), anyString(), any()))
                .thenReturn(new SapResponse(201, "", null));

        SyncState result = useCase.execute(ingestion());

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(imageStore).save(eq("A-1"), any());
        verify(historyIndexer).index(eq("A-1"), any(), eq("hash-a"));
    }

    @Test
    void fetchEmptyReturnsError() {
        when(legacyRepo.fetch("A-1")).thenReturn(Optional.empty());

        SyncState result = useCase.execute(ingestion());

        assertThat(result).isEqualTo(SyncState.ERROR);
        verify(sapOutbound, never()).send(any(), any(), any());
    }

    @Test
    void invalidArticleReturnsInvalid() {
        Article a = new Article("A-1", "SKU-001", "", "Hardware", "UN",
                Article.Status.ACTIVE);
        when(legacyRepo.fetch("A-1")).thenReturn(Optional.of(a));

        SyncState result = useCase.execute(ingestion());

        assertThat(result).isEqualTo(SyncState.INVALID);
        verify(sapOutbound, never()).send(any(), any(), any());
    }

    @Test
    void sapErrorReturnsSapError() {
        when(legacyRepo.fetch("A-1")).thenReturn(Optional.of(validArticle()));
        when(sapOutbound.send(any(), any(), any()))
                .thenReturn(new SapResponse(500, "err", null));

        SyncState result = useCase.execute(ingestion());

        assertThat(result).isEqualTo(SyncState.SAP_ERROR);
    }
}