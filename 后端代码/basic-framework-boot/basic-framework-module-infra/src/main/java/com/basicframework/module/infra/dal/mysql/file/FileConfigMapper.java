package com.basicframework.module.infra.dal.mysql.file;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FileConfigMapper extends BaseMapperX<FileConfigDO> {

    default PageResult<FileConfigDO> selectPage(
            PageParam pageParam, String name, Integer storage, LocalDateTime[] createTime) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<FileConfigDO>()
                        .likeIfPresent(FileConfigDO::getName, name)
                        .eqIfPresent(FileConfigDO::getStorage, storage)
                        .betweenIfPresent(FileConfigDO::getCreateTime, createTime)
                        .orderByDesc(FileConfigDO::getId));
    }

    default FileConfigDO selectByMaster() {
        return selectOne(FileConfigDO::getMaster, true);
    }

    default FileConfigDO selectByIdForShare(Long id) {
        return selectOne(new LambdaQueryWrapperX<FileConfigDO>()
                .eq(FileConfigDO::getId, id)
                .last("FOR SHARE"));
    }

    default FileConfigDO selectByMasterForShare() {
        return selectOne(new LambdaQueryWrapperX<FileConfigDO>()
                .eq(FileConfigDO::getMaster, true)
                .last("FOR SHARE"));
    }

    default FileConfigDO selectByIdForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<FileConfigDO>()
                .eq(FileConfigDO::getId, id)
                .last("FOR UPDATE"));
    }
}
