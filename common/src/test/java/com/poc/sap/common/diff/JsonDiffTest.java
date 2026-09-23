package com.poc.sap.common.diff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonDiffTest {

    record Address(String street, String city) {}
    record Person(String name, Address address, List<String> tags) {}

    @Test
    void equalObjectsProduceEmptyDiff() {
        Person p = new Person("Ana", new Address("Mayor 1", "Madrid"), List.of("a"));
        Person q = new Person("Ana", new Address("Mayor 1", "Madrid"), List.of("a"));

        assertThat(JsonDiff.diff(p, q)).isEmpty();
    }

    @Test
    void changedNestedFieldIsReportedWithDotPath() {
        Person before = new Person("Ana", new Address("Mayor 1", "Madrid"), List.of());
        Person after = new Person("Ana", new Address("Mayor 1", "Las Palmas"), List.of());

        Map<String, JsonDiff.Change> diff = JsonDiff.diff(before, after);

        assertThat(diff).containsOnlyKeys("address.city");
        assertThat(diff.get("address.city").before()).isEqualTo("Madrid");
        assertThat(diff.get("address.city").after()).isEqualTo("Las Palmas");
    }

    @Test
    void addedAndRemovedFieldsUseNullSide() {
        Person before = new Person("Ana", null, List.of("x"));
        Person after = new Person("Ana", new Address("Mayor 1", "Madrid"), List.of());

        Map<String, JsonDiff.Change> diff = JsonDiff.diff(before, after);

        // address pasa de null a objeto: la ruta plana "address" desaparece y
        // aparecen sus campos; el elemento de lista eliminado queda con after=null
        assertThat(diff.get("address.street")).isEqualTo(new JsonDiff.Change(null, "Mayor 1"));
        assertThat(diff.get("tags[0]")).isEqualTo(new JsonDiff.Change("x", null));
    }

    @Test
    void arrayElementChangesAreIndexed() {
        Person before = new Person("Ana", null, List.of("a", "b"));
        Person after = new Person("Ana", null, List.of("a", "c"));

        Map<String, JsonDiff.Change> diff = JsonDiff.diff(before, after);

        assertThat(diff).containsOnlyKeys("tags[1]");
        assertThat(diff.get("tags[1]")).isEqualTo(new JsonDiff.Change("b", "c"));
    }
}
