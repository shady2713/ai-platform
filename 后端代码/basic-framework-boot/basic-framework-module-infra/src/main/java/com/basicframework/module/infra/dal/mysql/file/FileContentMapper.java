package com.basicframework.module.infra.dal.mysql.file;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.basicframework.module.infra.dal.dataobject.file.FileContentDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FileContentMapper extends BaseMapper<FileContentDO> {

    default void deleteByConfigIdAndPath(Long configId, String path) {
        this.delete(new LambdaQueryWrapper<FileContentDO>()
                .eq(FileContentDO::getConfigId, configId)
                .eq(FileContentDO::getPath, path));
    }

    default List<FileContentDO> selectListByConfigIdAndPath(Long configId, String path) {
        return selectList(new LambdaQueryWrapper<FileContentDO>()
                .eq(FileContentDO::getConfigId, configId)
                .eq(FileContentDO::getPath, path));
    }

    default long selectCountByConfigId(Long configId) {
        return selectCount(new LambdaQueryWrapper<FileContentDO>().eq(FileContentDO::getConfigId, configId));
    }

    @Select(
            """
            SELECT COUNT(*)
            FROM infra_file_content fc
            LEFT JOIN infra_file_config c ON c.id = fc.config_id AND c.deleted = b'0'
            WHERE c.id IS NULL
            """)
    int selectOrphanFileConfigCount();
}
