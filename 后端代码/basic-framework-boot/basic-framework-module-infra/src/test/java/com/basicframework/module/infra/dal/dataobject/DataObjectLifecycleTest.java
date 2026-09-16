package com.basicframework.module.infra.dal.dataobject;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import com.basicframework.module.infra.dal.dataobject.config.ConfigDO;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.dal.dataobject.file.FileContentDO;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.dal.dataobject.job.JobDO;
import com.basicframework.module.infra.dal.dataobject.job.JobLogDO;
import com.basicframework.module.infra.dal.dataobject.logger.ApiAccessLogDO;
import com.basicframework.module.infra.dal.dataobject.logger.ApiErrorLogDO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

class DataObjectLifecycleTest {

    @Test
    void softDeleteTables_useExplicitSoftDeleteBase() {
        assertDirectSuperclass(SoftDeletableDO.class, ConfigDO.class, FileConfigDO.class, JobDO.class);
    }

    @Test
    void hardDeleteAndRetentionTables_doNotInheritLogicalDelete() {
        assertDirectSuperclass(
                BaseDO.class,
                FileDO.class,
                FileContentDO.class,
                ApiAccessLogDO.class,
                ApiErrorLogDO.class,
                JobLogDO.class);
    }

    private static void assertDirectSuperclass(Class<?> expected, Class<?>... types) {
        boolean expectedLogicalDelete = expected == SoftDeletableDO.class;
        assertThat(List.of(types)).allSatisfy(type -> {
            assertThat(type.getSuperclass()).isEqualTo(expected);
            assertThat(tableInfo(type).isWithLogicDelete()).isEqualTo(expectedLogicalDelete);
        });
    }

    private static TableInfo tableInfo(Class<?> type) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), type.getName());
        assistant.setCurrentNamespace(type.getName());
        return TableInfoHelper.initTableInfo(assistant, type);
    }
}
