package com.basicframework.framework.common.util.collection;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.core.KeyValue;
import com.google.common.collect.ArrayListMultimap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MapUtilsTest {

    @Test
    void getsValuesForRequestedKeysInOrder() {
        ArrayListMultimap<String, Integer> values = ArrayListMultimap.create();
        values.put("first", 1);
        values.put("first", 2);
        values.put("second", 3);

        assertThat(MapUtils.getList(values, List.of("missing", "second", "first")))
                .containsExactly(3, 1, 2);
    }

    @Test
    void invokesConsumerOnlyForPresentValues() {
        List<String> consumed = new ArrayList<>();
        Map<String, String> values = Map.of("known", "value");

        MapUtils.findAndThen(values, "known", consumed::add);
        MapUtils.findAndThen(values, "missing", consumed::add);
        MapUtils.findAndThen(values, null, consumed::add);
        MapUtils.<String, String>findAndThen(Map.of(), "known", consumed::add);

        assertThat(consumed).containsExactly("value");
    }

    @Test
    void convertsKeyValuesToInsertionOrderedMap() {
        Map<String, Integer> result = MapUtils.convertMap(
                List.of(new KeyValue<>("first", 1), new KeyValue<>("second", 2), new KeyValue<>("first", 3)));

        assertThat(result).isInstanceOf(LinkedHashMap.class);
        assertThat(result).containsEntry("first", 3).containsEntry("second", 2);
        assertThat(result.keySet()).containsExactly("first", "second");
    }
}
