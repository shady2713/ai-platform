package com.basicframework.module.infra.dal.mysql.file;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 文件操作 Mapper
 *
 */
@Mapper
public interface FileMapper extends BaseMapperX<FileDO> {

    int DELETE_STATUS_ACTIVE = 0;
    int DELETE_STATUS_PENDING = 1;
    int UPLOAD_STATUS_PENDING = 0;
    int UPLOAD_STATUS_COMPLETE = 1;
    int UPLOAD_STATUS_VALIDATING = 2;

    default PageResult<FileDO> selectPage(PageParam pageParam, String path, String type, LocalDateTime[] createTime) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<FileDO>()
                        .likeIfPresent(FileDO::getPath, path)
                        .likeIfPresent(FileDO::getType, type)
                        .betweenIfPresent(FileDO::getCreateTime, createTime)
                        .eq(FileDO::getDeleteStatus, DELETE_STATUS_ACTIVE)
                        .eq(FileDO::getUploadStatus, UPLOAD_STATUS_COMPLETE)
                        .orderByDesc(FileDO::getId));
    }

    default FileDO selectActiveById(Long id) {
        return selectOne(
                FileDO::getId,
                id,
                FileDO::getDeleteStatus,
                DELETE_STATUS_ACTIVE,
                FileDO::getUploadStatus,
                UPLOAD_STATUS_COMPLETE);
    }

    default FileDO selectActiveByConfigIdAndPath(Long configId, String path) {
        return selectOne(new LambdaQueryWrapperX<FileDO>()
                .eq(FileDO::getConfigId, configId)
                .eq(FileDO::getPath, path)
                .eq(FileDO::getDeleteStatus, DELETE_STATUS_ACTIVE)
                .eq(FileDO::getUploadStatus, UPLOAD_STATUS_COMPLETE));
    }

    @Select(
            """
            <script>
            SELECT * FROM infra_file
            WHERE delete_status = 0 AND upload_status = 1 AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            FOR UPDATE
            </script>
            """)
    List<FileDO> selectActiveByIdsForUpdate(@Param("ids") List<Long> ids);

    @Update(
            """
            <script>
            UPDATE infra_file
            SET delete_status = 1, delete_attempts = 0, delete_next_retry_time = NOW(),
                delete_last_error = NULL, update_time = NOW()
            WHERE delete_status = 0 AND upload_status = 1 AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int markDeletePending(@Param("ids") List<Long> ids);

    default List<FileDO> selectPendingDeletion(LocalDateTime now, int limit) {
        LambdaQueryWrapperX<FileDO> query = new LambdaQueryWrapperX<>();
        query.eq(FileDO::getDeleteStatus, DELETE_STATUS_PENDING)
                .le(FileDO::getDeleteNextRetryTime, now)
                .orderByAsc(FileDO::getDeleteNextRetryTime)
                .orderByAsc(FileDO::getId);
        query.last("LIMIT " + limit);
        return selectList(query);
    }

    @Delete("DELETE FROM infra_file WHERE id = #{id} AND delete_status = 1")
    int deletePendingById(@Param("id") Long id);

    @Update(
            """
            UPDATE infra_file
            SET delete_attempts = delete_attempts + 1,
                delete_next_retry_time = #{nextRetryTime},
                delete_last_error = #{lastError},
                update_time = NOW()
            WHERE id = #{id} AND delete_status = 1
            """)
    int recordDeleteFailure(
            @Param("id") Long id,
            @Param("nextRetryTime") LocalDateTime nextRetryTime,
            @Param("lastError") String lastError);

    @Update(
            """
            UPDATE infra_file
            SET upload_status = 2, update_time = NOW()
            WHERE upload_token_hash = #{tokenHash} AND upload_status = 0 AND delete_status = 0
              AND upload_user_id = #{userId} AND upload_user_type = #{userType}
              AND upload_expires_at > #{now}
            """)
    int claimPendingUpload(
            @Param("tokenHash") String tokenHash,
            @Param("userId") Long userId,
            @Param("userType") Integer userType,
            @Param("now") LocalDateTime now);

    default FileDO selectClaimedUpload(String tokenHash) {
        return selectOne(
                FileDO::getUploadTokenHash,
                tokenHash,
                FileDO::getUploadStatus,
                UPLOAD_STATUS_VALIDATING,
                FileDO::getDeleteStatus,
                DELETE_STATUS_ACTIVE);
    }

    /** 发布与过期清理共享文件行锁；返回空表示记录已被清理或状态已改变。 */
    default FileDO selectValidatingUploadForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<FileDO>()
                .eq(FileDO::getId, id)
                .eq(FileDO::getUploadStatus, UPLOAD_STATUS_VALIDATING)
                .eq(FileDO::getDeleteStatus, DELETE_STATUS_ACTIVE)
                .last("FOR UPDATE"));
    }

    default FileDO selectCompletedUpload(String tokenHash, Long userId, Integer userType) {
        return selectOne(new LambdaQueryWrapperX<FileDO>()
                .eq(FileDO::getUploadTokenHash, tokenHash)
                .eq(FileDO::getUploadStatus, UPLOAD_STATUS_COMPLETE)
                .eq(FileDO::getUploadUserId, userId)
                .eq(FileDO::getUploadUserType, userType)
                .eq(FileDO::getDeleteStatus, DELETE_STATUS_ACTIVE));
    }

    @Update(
            """
            UPDATE infra_file
            SET type = #{type}, size = #{size}, upload_status = 1,
                upload_staging_path = NULL, update_time = NOW()
            WHERE id = #{id} AND upload_status = 2 AND delete_status = 0
            """)
    int completeUpload(@Param("id") Long id, @Param("type") String type, @Param("size") long size);

    @Update(
            """
            UPDATE infra_file
            SET delete_status = 1, delete_attempts = 0, delete_next_retry_time = NOW(),
                delete_last_error = NULL, update_time = NOW()
            WHERE id = #{id} AND upload_status IN (0, 2) AND delete_status = 0
            """)
    int markIncompleteUploadDeletePending(@Param("id") Long id);

    @Update(
            """
            UPDATE infra_file
            SET delete_status = 1, delete_attempts = 0, delete_next_retry_time = NOW(),
                delete_last_error = NULL, update_time = NOW()
            WHERE upload_status IN (0, 2) AND delete_status = 0 AND upload_expires_at <= #{now}
            ORDER BY id
            LIMIT #{limit}
            """)
    int markExpiredUploadsDeletePending(@Param("now") LocalDateTime now, @Param("limit") int limit);

    default long selectCountByConfigId(Long configId) {
        return selectCount(FileDO::getConfigId, configId);
    }

    @Select(
            """
            SELECT COUNT(*)
            FROM infra_file f
            LEFT JOIN infra_file_config c ON c.id = f.config_id AND c.deleted = b'0'
            WHERE f.config_id IS NOT NULL AND c.id IS NULL
            """)
    int selectOrphanFileConfigCount();
}
