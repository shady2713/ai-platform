package com.basicframework.framework.common.util.collection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ArrayUtilsTest {

    @Test
    void appendsConsumersInOrderAndPreservesExistingArrayWhenHeadIsNull() {
        List<String> calls = new ArrayList<>();
        Consumer<String> second = value -> calls.add("second:" + value);
        Consumer<String> third = value -> calls.add("third:" + value);
        Consumer<String>[] existing = consumerArray(second, third);

        assertThat(ArrayUtils.append(null, existing)).isSameAs(existing);

        Consumer<String> first = value -> calls.add("first:" + value);
        Consumer<String>[] combined = ArrayUtils.append(first, existing);
        for (Consumer<String> consumer : combined) {
            consumer.accept("value");
        }
        assertThat(calls).containsExactly("first:value", "second:value", "third:value");
    }

    @Test
    void convertsCollectionsAndHandlesEmptyInput() {
        Object[] mapped = ArrayUtils.toArray(List.of(1, 2), String::valueOf);
        Object[] empty = ArrayUtils.toArray(List.<Integer>of(), String::valueOf);

        assertThat(mapped).containsExactly("1", "2");
        assertThat(empty).isEmpty();
    }

    @Test
    void getsOnlyValidArrayIndexes() {
        String[] values = {"first", "second"};

        assertThat(ArrayUtils.get(values, 1)).isEqualTo("second");
        assertThat(ArrayUtils.get(values, -1)).isNull();
        assertThat(ArrayUtils.get(values, 2)).isNull();
        assertThat(ArrayUtils.get((String[]) null, 0)).isNull();
    }

    @SafeVarargs
    private static <T> Consumer<T>[] consumerArray(Consumer<T>... consumers) {
        return consumers;
    }
}
