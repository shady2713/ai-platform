package com.basicframework.module.ai.dal.mysql.federation;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.federation.AiSubjectFederationDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 跨系统主体联邦映射 Mapper（Y01）：六段身份唯一定位、按来源取已批准映射、CAS 状态迁移。 */
@Mapper
public interface AiSubjectFederationMapper extends BaseMapperX<AiSubjectFederationDO> {

    /**
     * 按六段身份唯一定位（只读事实，不看状态）。
     *
     * <p>查询条件是 6 个字段（超过 ADR 0004 的散参上限），因此收敛为不可变
     * {@link AiSubjectFederationQuery}，Mapper 签名保持单入参。
     */
    default AiSubjectFederationDO selectByIdentity(AiSubjectFederationQuery query) {
        return selectOne(new LambdaQueryWrapperX<AiSubjectFederationDO>()
                .eq(AiSubjectFederationDO::getSourceApplicationId, query.getSourceApplicationId())
                .eq(AiSubjectFederationDO::getSourceSubjectType, query.getSourceSubjectType())
                .eq(AiSubjectFederationDO::getSourceExternalUserId, query.normalizedSourceExternalUserId())
                .eq(AiSubjectFederationDO::getTargetApplicationId, query.getTargetApplicationId())
                .eq(AiSubjectFederationDO::getTargetSubjectType, query.getTargetSubjectType())
                .eq(AiSubjectFederationDO::getTargetExternalUserId, query.normalizedTargetExternalUserId()));
    }

    /** 某来源主体的**已批准**映射（发现只认 APPROVED；编号升序保证顺序稳定）。 */
    default List<AiSubjectFederationDO> selectApprovedBySource(
            Long sourceApplicationId, String sourceSubjectType, String sourceExternalUserId) {
        return selectList(new LambdaQueryWrapperX<AiSubjectFederationDO>()
                .eq(AiSubjectFederationDO::getSourceApplicationId, sourceApplicationId)
                .eq(AiSubjectFederationDO::getSourceSubjectType, sourceSubjectType)
                .eq(
                        AiSubjectFederationDO::getSourceExternalUserId,
                        sourceExternalUserId == null ? "" : sourceExternalUserId)
                .eq(AiSubjectFederationDO::getStatus, AiSubjectFederationDO.STATUS_APPROVED)
                .orderByAsc(AiSubjectFederationDO::getId));
    }

    /** 管理端分页：按来源应用与状态过滤（编号倒序）。 */
    default PageResult<AiSubjectFederationDO> selectPage(PageParam pageParam, Long sourceApplicationId, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiSubjectFederationDO>()
                        .eqIfPresent(AiSubjectFederationDO::getSourceApplicationId, sourceApplicationId)
                        .eqIfPresent(AiSubjectFederationDO::getStatus, status)
                        .orderByDesc(AiSubjectFederationDO::getId));
    }

    /** 乐观锁 CAS：状态迁移（批准/撤销）只允许单赢家。 */
    default int updateWithVersion(AiSubjectFederationDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiSubjectFederationDO>()
                        .eq(AiSubjectFederationDO::getId, update.getId())
                        .eq(AiSubjectFederationDO::getVersion, expectedVersion));
    }

    /**
     * 撤销后重新提交（复用同一行）：必须**清空**上一次的审批痕迹，否则旧批准人与批准时间会伪装成
     * 本次审批。逻辑删除列不参与更新（MyBatis-Plus 逻辑删除语义），审批列显式置 NULL。
     */
    default int resubmitAfterRevoke(AiSubjectFederationDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiSubjectFederationDO>()
                        .eq(AiSubjectFederationDO::getId, update.getId())
                        .eq(AiSubjectFederationDO::getVersion, expectedVersion)
                        .set(AiSubjectFederationDO::getStatus, update.getStatus())
                        .set(AiSubjectFederationDO::getRequestedBy, update.getRequestedBy())
                        .set(AiSubjectFederationDO::getRequestedTime, update.getRequestedTime())
                        .set(AiSubjectFederationDO::getApprovedBy, null)
                        .set(AiSubjectFederationDO::getApprovedTime, null)
                        .set(AiSubjectFederationDO::getApprovalNote, null)
                        .set(AiSubjectFederationDO::getRevision, update.getRevision())
                        .set(AiSubjectFederationDO::getVersion, update.getVersion()));
    }
}
