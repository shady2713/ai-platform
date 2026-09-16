package com.basicframework.module.system.service.logger;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.logger.LoginLogDO;
import com.basicframework.module.system.service.logger.dto.LoginLogCreateReqDTO;
import jakarta.validation.Valid;
import java.time.LocalDateTime;

/**
 * 登录日志 Service 接口
 */
public interface LoginLogService {

    /**
     * 获得登录日志
     *
     * @param id 编号
     * @return 登录日志
     */
    LoginLogDO getLoginLog(Long id);

    /**
     * 获得登录日志分页
     *
     * @param pageParam  分页参数
     * @param userIp     用户 IP，模糊匹配
     * @param username   用户账号，模糊匹配
     * @param createTime 创建时间区间
     * @param status     登录状态
     * @return 登录日志分页
     */
    PageResult<LoginLogDO> getLoginLogPage(
            PageParam pageParam, String userIp, String username, LocalDateTime[] createTime, Boolean status);

    /**
     * 创建登录日志。成功记录与调用方事务同成败，失败记录独立提交以保留审计证据。
     *
     * @param reqDTO 日志信息
     */
    void createLoginLog(@Valid LoginLogCreateReqDTO reqDTO);
}
