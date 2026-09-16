package com.basicframework.module.system.dal.mysql.sms;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.QueryWrapperX;
import com.basicframework.module.system.dal.dataobject.sms.SmsCodeDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SmsCodeMapper extends BaseMapperX<SmsCodeDO> {

    /**
     * 获得手机号的最后一个手机验证码
     *
     * @param mobile 手机号
     * @param scene 发送场景，选填
     * @param code 验证码 选填
     * @return 手机验证码
     */
    default SmsCodeDO selectLastByMobile(String mobile, String code, Integer scene) {
        return selectOne(new QueryWrapperX<SmsCodeDO>()
                .eq("mobile", mobile)
                .eqIfPresent("scene", scene)
                .eqIfPresent("code", code)
                .orderByDesc("id")
                .limitN(1));
    }

    /**
     * 条件更新核销验证码：仅当记录仍未使用时才置为已用，消除先查后改的并发复用窗口。
     *
     * @param id 编号
     * @param usedTime 使用时间
     * @param usedIp 使用 IP
     * @return 影响行数；0 表示已被并发请求核销
     */
    @Update(
            """
            UPDATE system_sms_code
            SET used = 1, used_time = #{usedTime}, used_ip = #{usedIp}
            WHERE id = #{id} AND used = 0
            """)
    int markUsedIfUnused(
            @Param("id") Long id, @Param("usedTime") LocalDateTime usedTime, @Param("usedIp") String usedIp);

    /**
     * 分批物理删除早于保留期的验证码记录；无论是否已使用，超过保留期的验证码必然已失效。
     *
     * @param createTime 创建时间上限（不含）
     * @param limit 单批最大删除行数
     * @return 本批实际删除行数
     */
    @Delete("DELETE FROM system_sms_code WHERE create_time < #{createTime} ORDER BY id LIMIT #{limit}")
    int deleteByCreateTimeLt(@Param("createTime") LocalDateTime createTime, @Param("limit") int limit);
}
