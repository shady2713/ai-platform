package com.basicframework.module.system.service.sms;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.sms.SmsChannelDO;
import com.basicframework.module.system.framework.sms.core.client.SmsClient;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 短信渠道 Service 接口
 *
 * @since 2021/1/25 9:24
 */
public interface SmsChannelService {

    /**
     * 创建短信渠道
     *
     * @param channel 创建信息
     * @return 编号
     */
    Long createSmsChannel(SmsChannelDO channel);

    /**
     * 更新短信渠道
     *
     * @param channel 更新信息
     */
    void updateSmsChannel(SmsChannelDO channel);

    /**
     * 删除短信渠道
     *
     * @param id 编号
     */
    void deleteSmsChannel(Long id);

    /**
     * 批量删除短信渠道
     *
     * @param ids 编号数组
     */
    void deleteSmsChannelList(List<Long> ids);

    /**
     * 获得短信渠道
     *
     * @param id 编号
     * @return 短信渠道
     */
    SmsChannelDO getSmsChannel(Long id);

    /**
     * 获得所有短信渠道列表
     *
     * @return 短信渠道列表
     */
    List<SmsChannelDO> getSmsChannelList();

    /**
     * 获得短信渠道分页
     *
     * @param pageParam  分页参数
     * @param signature  短信签名，模糊匹配
     * @param code       渠道编码
     * @param status     渠道状态
     * @param createTime 创建时间区间
     * @return 短信渠道分页
     */
    PageResult<SmsChannelDO> getSmsChannelPage(
            PageParam pageParam, String signature, String code, Integer status, LocalDateTime[] createTime);

    /**
     * 获得短信客户端
     *
     * @param id 编号
     * @return 短信客户端
     */
    SmsClient getSmsClient(Long id);

    /**
     * 按给定渠道快照创建不注册到共享缓存的临时短信客户端。
     *
     * @param channel 渠道配置快照
     * @return 临时短信客户端
     */
    SmsClient createTransientSmsClient(SmsChannelDO channel);

    /**
     * 获得短信客户端
     *
     * @param code 编码
     * @return 短信客户端
     */
    SmsClient getSmsClient(String code);
}
