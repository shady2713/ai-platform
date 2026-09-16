package com.basicframework.module.system.service.notify;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.notify.NotifyMessageDO;
import com.basicframework.module.system.dal.dataobject.notify.NotifyTemplateDO;
import com.basicframework.module.system.dal.mysql.notify.NotifyMessageMapper;
import com.basicframework.module.system.dal.mysql.notify.NotifyMessageQuery;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * 站内信 Service 实现类
 *
 */
@Service
@Validated
@RequiredArgsConstructor
public class NotifyMessageServiceImpl implements NotifyMessageService {

    private final NotifyMessageMapper notifyMessageMapper;

    @Override
    public Long createNotifyMessage(
            Long userId,
            Integer userType,
            NotifyTemplateDO template,
            String templateContent,
            Map<String, Object> templateParams) {
        NotifyMessageDO message = new NotifyMessageDO()
                .setUserId(userId)
                .setUserType(userType)
                .setTemplateId(template.getId())
                .setTemplateCode(template.getCode())
                .setTemplateType(template.getType())
                .setTemplateNickname(template.getNickname())
                .setTemplateContent(templateContent)
                .setTemplateParams(templateParams)
                .setReadStatus(false);
        notifyMessageMapper.insert(message);
        return message.getId();
    }

    @Override
    public PageResult<NotifyMessageDO> getNotifyMessagePage(PageParam pageParam, NotifyMessageQuery query) {
        return notifyMessageMapper.selectPage(pageParam, query);
    }

    @Override
    public PageResult<NotifyMessageDO> getMyNotifyMessagePage(
            PageParam pageParam, Boolean readStatus, LocalDateTime[] createTime, Long userId, Integer userType) {
        return notifyMessageMapper.selectPage(pageParam, readStatus, createTime, userId, userType);
    }

    @Override
    public NotifyMessageDO getNotifyMessage(Long id) {
        return notifyMessageMapper.selectById(id);
    }

    @Override
    public List<NotifyMessageDO> getUnreadNotifyMessageList(Long userId, Integer userType, Integer size) {
        return notifyMessageMapper.selectUnreadListByUserIdAndUserType(userId, userType, size);
    }

    @Override
    public Long getUnreadNotifyMessageCount(Long userId, Integer userType) {
        return notifyMessageMapper.selectUnreadCountByUserIdAndUserType(userId, userType);
    }

    @Override
    public int updateNotifyMessageRead(Collection<Long> ids, Long userId, Integer userType) {
        return notifyMessageMapper.updateListRead(ids, userId, userType);
    }

    @Override
    public int updateAllNotifyMessageRead(Long userId, Integer userType) {
        return notifyMessageMapper.updateListRead(userId, userType);
    }
}
