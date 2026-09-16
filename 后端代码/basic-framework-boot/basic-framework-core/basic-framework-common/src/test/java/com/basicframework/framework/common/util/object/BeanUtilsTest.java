package com.basicframework.framework.common.util.object;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.pojo.PageResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class BeanUtilsTest {

    @Test
    void objectAndListConversions_applyOptionalPostProcessing() {
        Source source = new Source().setId(1L).setName("source");

        Target target = BeanUtils.toBean(source, Target.class, value -> value.setName("processed"));
        List<Target> list = BeanUtils.toBean(List.of(source), Target.class, value -> value.setName("listed"));

        assertThat(target).extracting(Target::getId, Target::getName).containsExactly(1L, "processed");
        assertThat(list).singleElement().extracting(Target::getName).isEqualTo("listed");
        assertThat(BeanUtils.toBean((List<Source>) null, Target.class)).isNull();
    }

    @Test
    void pageConversion_preservesTotalAndHandlesNull() {
        PageResult<Source> source = new PageResult<>(List.of(new Source().setId(2L)), 9L);

        PageResult<Target> result = BeanUtils.toBean(source, Target.class, value -> value.setName("page"));

        assertThat(result.getTotal()).isEqualTo(9L);
        assertThat(result.getList()).singleElement().extracting(Target::getName).isEqualTo("page");
        assertThat(BeanUtils.toBean((PageResult<Source>) null, Target.class)).isNull();
    }

    public static class Source {

        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public Source setId(Long id) {
            this.id = id;
            return this;
        }

        public String getName() {
            return name;
        }

        public Source setName(String name) {
            this.name = name;
            return this;
        }
    }

    public static class Target {

        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
