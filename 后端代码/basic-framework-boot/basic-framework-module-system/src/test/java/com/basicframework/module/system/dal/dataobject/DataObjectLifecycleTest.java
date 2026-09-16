package com.basicframework.module.system.dal.dataobject;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import com.basicframework.module.system.dal.dataobject.dept.PostDO;
import com.basicframework.module.system.dal.dataobject.dept.UserPostDO;
import com.basicframework.module.system.dal.dataobject.dict.DictDataDO;
import com.basicframework.module.system.dal.dataobject.dict.DictTypeDO;
import com.basicframework.module.system.dal.dataobject.logger.LoginLogDO;
import com.basicframework.module.system.dal.dataobject.logger.OperateLogDO;
import com.basicframework.module.system.dal.dataobject.notice.NoticeDO;
import com.basicframework.module.system.dal.dataobject.notify.NotifyMessageDO;
import com.basicframework.module.system.dal.dataobject.notify.NotifyTemplateDO;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleMenuDO;
import com.basicframework.module.system.dal.dataobject.permission.UserRoleDO;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.dal.dataobject.sms.SmsChannelDO;
import com.basicframework.module.system.dal.dataobject.sms.SmsCodeDO;
import com.basicframework.module.system.dal.dataobject.sms.SmsLogDO;
import com.basicframework.module.system.dal.dataobject.sms.SmsTemplateDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

class DataObjectLifecycleTest {

    @Test
    void softDeleteTables_useExplicitSoftDeleteBase() {
        assertDirectSuperclass(
                SoftDeletableDO.class,
                AdminUserDO.class,
                DeptDO.class,
                PostDO.class,
                DictDataDO.class,
                DictTypeDO.class,
                MenuDO.class,
                RoleDO.class,
                NoticeDO.class,
                NotifyTemplateDO.class,
                SmsChannelDO.class,
                SmsTemplateDO.class);
    }

    @Test
    void hardDeleteAndRetentionTables_doNotInheritLogicalDelete() {
        assertDirectSuperclass(
                BaseDO.class,
                UserPostDO.class,
                RoleMenuDO.class,
                UserRoleDO.class,
                UserSessionDO.class,
                SmsCodeDO.class,
                LoginLogDO.class,
                NotifyMessageDO.class,
                OperateLogDO.class,
                SmsLogDO.class);
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
