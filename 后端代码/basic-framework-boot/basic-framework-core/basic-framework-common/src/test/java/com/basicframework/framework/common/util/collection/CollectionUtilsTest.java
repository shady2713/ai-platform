package com.basicframework.framework.common.util.collection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollectionUtilsTest {

    @Test
    void filteringAndMembership_preserveExplicitEmptyContracts() {
        assertThat(CollectionUtils.containsAny("active", "draft", "active")).isTrue();
        assertThat(CollectionUtils.containsAny("missing", "draft", "active")).isFalse();
        assertThat(CollectionUtils.filterList(null, value -> true)).isEmpty();
        assertThat(CollectionUtils.filterList(List.of(1, 2, 3), value -> value % 2 == 1))
                .containsExactly(1, 3);
    }

    @Test
    void listConversions_filterNullResultsAndSupportArrays() {
        assertThat(CollectionUtils.convertList((Integer[]) null, String::valueOf))
                .isEmpty();
        assertThat(CollectionUtils.convertList(new Integer[] {1, 2}, String::valueOf))
                .containsExactly("1", "2");
        assertThat(CollectionUtils.convertList((Collection<Integer>) null, String::valueOf))
                .isEmpty();
        assertThat(CollectionUtils.convertList(List.of(1, 2, 3), value -> value == 2 ? null : value * 10))
                .containsExactly(10, 30);
        assertThat(CollectionUtils.convertList(List.of(1, 2, 3), String::valueOf, value -> value > 1))
                .containsExactly("2", "3");
    }

    @Test
    void setAndMapConversions_deduplicateAndKeepFirstValue() {
        assertThat(CollectionUtils.convertSet(null, String::valueOf)).isEmpty();
        assertThat(CollectionUtils.convertSet(List.of(1, 1, 2), String::valueOf))
                .containsExactlyInAnyOrder("1", "2");
        assertThat(CollectionUtils.convertMap(null, Item::key)).isEmpty();

        Map<String, Item> result =
                CollectionUtils.convertMap(List.of(new Item("same", 1), new Item("same", 2)), Item::key);

        assertThat(result).containsOnlyKeys("same");
        assertThat(result.get("same").value()).isEqualTo(1);
    }

    @Test
    void lookupAndConditionalAdd_handleEmptyMissingAndPresentValues() {
        assertThat(CollectionUtils.<Integer>findFirst(null, value -> true)).isNull();
        assertThat(CollectionUtils.findFirst(List.of(1, 2), value -> value == 3))
                .isNull();
        assertThat(CollectionUtils.findFirst(List.of(1, 2), value -> value == 2))
                .isEqualTo(2);

        List<Integer> values = new ArrayList<>();
        CollectionUtils.addIfNotNull(values, null);
        CollectionUtils.addIfNotNull(values, 1);
        assertThat(values).containsExactly(1);
        assertThat(CollectionUtils.singleton(null)).isEmpty();
        assertThat(CollectionUtils.singleton(2)).containsExactly(2);
    }

    private record Item(String key, int value) {}
}
