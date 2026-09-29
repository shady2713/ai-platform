package com.basicframework.module.ai.dal.mysql.report;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.report.AiReportShareDO;
import org.apache.ibatis.annotations.Mapper;

/** 报表受控分享 Mapper（X11）。 */
@Mapper
public interface AiReportShareMapper extends BaseMapperX<AiReportShareDO> {

    /** 按凭据摘要定位（唯一索引兜底；明文令牌绝不作为查询条件出现）。 */
    default AiReportShareDO selectByTokenHash(String tokenHash) {
        return selectOne(new LambdaQueryWrapperX<AiReportShareDO>().eq(AiReportShareDO::getTokenHash, tokenHash));
    }

    /**
     * 同报表 + 同接收者的 ACTIVE 分享（去重判定用）：撤销后允许重新分享，
     * 因此这里显式过滤状态，而不是靠数据库唯一索引表达"不重复"。
     */
    default AiReportShareDO selectActiveByReportAndGrantee(
            Long applicationId, Long reportId, String granteeSubjectType, String granteeExternalUserId) {
        return selectOne(new LambdaQueryWrapperX<AiReportShareDO>()
                .eq(AiReportShareDO::getApplicationId, applicationId)
                .eq(AiReportShareDO::getReportId, reportId)
                .eq(AiReportShareDO::getGranteeSubjectType, granteeSubjectType)
                .eq(AiReportShareDO::getGranteeExternalUserId, granteeExternalUserId)
                .eq(AiReportShareDO::getStatus, AiReportShareDO.STATUS_ACTIVE));
    }

    /** 授予者视角分页：只返回当前授予者的分享（接收范围在 DO 上，展示层映射）。 */
    default PageResult<AiReportShareDO> selectGrantorPage(
            PageParam pageParam, Long applicationId, String grantorSubjectType, String grantorExternalUserId) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiReportShareDO>()
                        .eq(AiReportShareDO::getApplicationId, applicationId)
                        .eq(AiReportShareDO::getGrantorSubjectType, grantorSubjectType)
                        .eq(AiReportShareDO::getGrantorExternalUserId, grantorExternalUserId)
                        .orderByDesc(AiReportShareDO::getId));
    }

    /** 撤销 CAS：仅授予者本人 + 仍 ACTIVE + 乐观锁版本匹配才会更新（0 行由调用方按当前事实回读）。 */
    default int revokeIfActive(
            Long id, String grantorSubjectType, String grantorExternalUserId, Integer expectedVersion) {
        return update(
                new AiReportShareDO()
                        .setId(id)
                        .setStatus(AiReportShareDO.STATUS_REVOKED)
                        .setVersion(expectedVersion + 1),
                new LambdaUpdateWrapper<AiReportShareDO>()
                        .eq(AiReportShareDO::getId, id)
                        .eq(AiReportShareDO::getGrantorSubjectType, grantorSubjectType)
                        .eq(AiReportShareDO::getGrantorExternalUserId, grantorExternalUserId)
                        .eq(AiReportShareDO::getStatus, AiReportShareDO.STATUS_ACTIVE)
                        .eq(AiReportShareDO::getVersion, expectedVersion));
    }

    /** 过期惰性物化 CAS：仅仍 ACTIVE 的行置为 EXPIRED（并发撤销时 0 行，调用方按回读结果判定）。 */
    default int expireIfActive(Long id, Integer expectedVersion) {
        return update(
                new AiReportShareDO()
                        .setId(id)
                        .setStatus(AiReportShareDO.STATUS_EXPIRED)
                        .setVersion(expectedVersion + 1),
                new LambdaUpdateWrapper<AiReportShareDO>()
                        .eq(AiReportShareDO::getId, id)
                        .eq(AiReportShareDO::getStatus, AiReportShareDO.STATUS_ACTIVE)
                        .eq(AiReportShareDO::getVersion, expectedVersion));
    }
}
