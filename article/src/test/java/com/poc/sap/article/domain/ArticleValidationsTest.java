package com.poc.sap.article.domain;

import com.poc.sap.common.domain.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleValidationsTest {

    @Test
    void validArticlePasses() {
        Article a = new Article("A-1", "SKU-001", "Tornillo M6",
                "Hardware", "UN", Article.Status.ACTIVE);
        assertThat(ArticleValidations.validate(a).valid()).isTrue();
    }

    @Test
    void missingDescriptionFails() {
        Article a = new Article("A-1", "SKU-001", "", "Hardware", "UN",
                Article.Status.ACTIVE);
        ValidationResult r = ArticleValidations.validate(a);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch("description obligatoria"::equals);
    }

    @Test
    void missingUnitFails() {
        Article a = new Article("A-1", "SKU-001", "Tornillo M6", null, "",
                Article.Status.ACTIVE);
        assertThat(ArticleValidations.validate(a).valid()).isFalse();
    }

    @Test
    void nullStatusFails() {
        Article a = new Article("A-1", "SKU-001", "Tornillo M6", "Hardware", "UN",
                null);
        assertThat(ArticleValidations.validate(a).valid()).isFalse();
    }

    @Test
    void nullArticleFails() {
        assertThat(ArticleValidations.validate((Article) null).valid()).isFalse();
    }

    @Test
    void nullCategoryIsAllowed() {
        Article a = new Article("A-1", "SKU-001", "Tornillo M6",
                null, "UN", Article.Status.ACTIVE);
        assertThat(ArticleValidations.validate(a).valid()).isTrue();
    }

    @Test
    void blankCategoryFails() {
        Article a = new Article("A-1", "SKU-001", "Tornillo M6",
                "   ", "UN", Article.Status.ACTIVE);
        ValidationResult r = ArticleValidations.validate(a);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch("category invalida"::equals);
    }
}