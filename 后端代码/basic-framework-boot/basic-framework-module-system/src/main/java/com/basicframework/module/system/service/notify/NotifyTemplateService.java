package com.basicframework.module.system.service.notify;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.notify.NotifyTemplateDO;
import com.basicframework.module.system.dal.mysql.notify.NotifyTemplateQuery;
import java.util.List;
import java.util.Map;

/**
 * 站内信模版 Service 接口
 *
 */
public interface NotifyTemplateService {

    /**
     * 创建站内信模版
     *
     * @param notifyTemplate 创建信息
     * @return 编号
     */
    Long createNotifyTemplate(NotifyTemplateDO notifyTemplate);

    /**
     * 更新站内信模版
     *
     * @param notifyTemplate 更新信息
     */
    void updateNotifyTemplate(NotifyTemplateDO notifyTemplate);

    /**
     * 删除站内信模版
     *
     * @param id 编号
     */
    void deleteNotifyTemplate(Long id);

    /**
     * 批量删除站内信模版
     *
     * @param ids 编号列表
     */
    void deleteNotifyTemplateList(List<Long> ids);

    /**
     * 获得站内信模版
     *
     * @param id 编号
     * @return 站内信模版
     */
    NotifyTemplateDO getNotifyTemplate(Long id);

    /**
     * 获得站内信模板，从缓存中
     *
     * @param code 模板编码
     * @return 站内信模板
     */
    NotifyTemplateDO getNotifyTemplateByCodeFromCache(String code);

    /**
     * 获得站内信模版分页
     *
     * @param pageParam 分页参数
     * @param query     查询条件
     * @return 站内信模版分页
     */
    PageResult<NotifyTemplateDO> getNotifyTemplatePage(PageParam pageParam, NotifyTemplateQuery query);

    /**
     * 格式化站内信内容
     *
     * @param content 站内信模板的内容
     * @param params 站内信内容的参数
     * @return 格式化后的内容
     */
    String formatNotifyTemplateContent(String content, Map<String, Object> params);
}
