package com.basicframework.module.infra.convert.file;

import com.basicframework.module.infra.controller.admin.file.vo.config.FileConfigSaveReqVO;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 文件配置 Convert
 *
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface FileConfigConvert {

    FileConfigConvert INSTANCE = Mappers.getMapper(FileConfigConvert.class);

    @Mappings({
        @Mapping(target = "config", ignore = true),
        @Mapping(target = "configCiphertext", ignore = true),
        @Mapping(target = "master", ignore = true),
    })
    FileConfigDO convert(FileConfigSaveReqVO bean);
}
