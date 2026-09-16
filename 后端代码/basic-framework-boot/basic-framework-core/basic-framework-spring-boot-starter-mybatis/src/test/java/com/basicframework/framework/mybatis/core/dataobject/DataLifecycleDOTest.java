package com.basicframework.framework.mybatis.core.dataobject;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableLogic;
import org.junit.jupiter.api.Test;

class DataLifecycleDOTest {

    @Test
    void baseDo_doesNotOwnDeletionPolicy() {
        assertThat(BaseDO.class.getDeclaredFields())
                .noneMatch(field -> field.isAnnotationPresent(TableLogic.class)
                        || field.getName().equals("deleted"));
    }

    @Test
    void softDeletableDo_explicitlyOwnsLogicalDeleteField() throws NoSuchFieldException {
        assertThat(SoftDeletableDO.class.getDeclaredField("deleted").getAnnotation(TableLogic.class))
                .isNotNull();
    }
}
