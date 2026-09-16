package com.basicframework.module.infra.convert.config;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.controller.admin.config.vo.ConfigRespVO;
import com.basicframework.module.infra.controller.admin.config.vo.ConfigSaveReqVO;
import com.basicframework.module.infra.dal.dataobject.config.ConfigDO;
import java.util.List;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Mappings;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ConfigConvert {

    ConfigConvert INSTANCE = Mappers.getMapper(ConfigConvert.class);

    PageResult<ConfigRespVO> convertPage(PageResult<ConfigDO> page);

    List<ConfigRespVO> convertList(List<ConfigDO> list);

    @Mapping(source = "configKey", target = "key")
    ConfigRespVO convert(ConfigDO bean);

    /** 不可见配置可能包含敏感值，所有响应形态统一在转换边界清除。 */
    @AfterMapping
    default void redactInvisibleValue(ConfigDO source, @MappingTarget ConfigRespVO target) {
        if (!Boolean.TRUE.equals(source.getVisible())) {
            target.setValue(null);
        }
    }

    @Mappings({
        @Mapping(source = "key", target = "configKey"),
        @Mapping(target = "createTime", ignore = true),
        @Mapping(target = "updateTime", ignore = true),
        @Mapping(target = "creator", ignore = true),
        @Mapping(target = "updater", ignore = true),
        @Mapping(target = "deleted", ignore = true),
        @Mapping(target = "type", ignore = true),
    })
    ConfigDO convert(ConfigSaveReqVO bean);
}
