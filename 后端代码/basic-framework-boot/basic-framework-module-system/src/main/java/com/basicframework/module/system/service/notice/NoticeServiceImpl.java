package com.basicframework.module.system.service.notice;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.NOTICE_NOT_EXISTS;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.notice.NoticeDO;
import com.basicframework.module.system.dal.dataobject.notify.NotifyMessageDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.notice.NoticeMapper;
import com.basicframework.module.system.dal.mysql.notify.NotifyMessageMapper;
import com.basicframework.module.system.service.user.AdminUserService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class NoticeServiceImpl implements NoticeService {

    private static final String NOTICE_PUSH_CODE_PREFIX = "SYSTEM_NOTICE_";
    private static final String NOTICE_PUSH_SENDER = "系统公告";

    private final NoticeMapper noticeMapper;

    private final NotifyMessageMapper notifyMessageMapper;

    private final AdminUserService adminUserService;

    public NoticeServiceImpl(
            NoticeMapper noticeMapper, NotifyMessageMapper notifyMessageMapper, AdminUserService adminUserService) {
        this.noticeMapper = noticeMapper;
        this.notifyMessageMapper = notifyMessageMapper;
        this.adminUserService = adminUserService;
    }

    @Override
    public Long createNotice(NoticeDO notice) {
        noticeMapper.insert(notice);
        return notice.getId();
    }

    @Override
    public void updateNotice(NoticeDO updateObj) {
        validateNoticeExists(updateObj.getId());
        noticeMapper.updateById(updateObj);
    }

    @Override
    public void deleteNotice(Long id) {
        validateNoticeExists(id);
        noticeMapper.deleteById(id);
    }

    @Override
    public void deleteNoticeList(List<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        noticeMapper.deleteByIds(ids);
    }

    @Override
    public NoticeDO getNotice(Long id) {
        return validateNoticeExists(id);
    }

    @Override
    public PageResult<NoticeDO> getNoticePage(
            PageParam pageParam, String title, Integer status, LocalDateTime[] createTime) {
        return noticeMapper.selectPage(pageParam, title, status, createTime);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pushNotice(Long id) {
        NoticeDO notice = validateNoticeExists(id);
        List<AdminUserDO> users = adminUserService.getUserListByStatus(CommonStatusEnum.ENABLE.getStatus());
        if (CollUtil.isEmpty(users)) {
            return;
        }

        String templateCode = NOTICE_PUSH_CODE_PREFIX + notice.getId();
        String templateContent = buildTemplateContent(notice);
        Map<String, Object> templateParams = Map.of(
                "noticeId", notice.getId(),
                "title", notice.getTitle());

        List<NotifyMessageDO> messages = new ArrayList<>(users.size());
        for (AdminUserDO user : users) {
            NotifyMessageDO message = new NotifyMessageDO();
            message.setUserId(user.getId());
            message.setUserType(UserTypeEnum.ADMIN.getValue());
            message.setTemplateId(notice.getId());
            message.setTemplateCode(templateCode);
            message.setTemplateType(notice.getType());
            message.setTemplateNickname(NOTICE_PUSH_SENDER);
            message.setTemplateContent(templateContent);
            message.setTemplateParams(templateParams);
            message.setReadStatus(false);
            messages.add(message);
        }
        notifyMessageMapper.insertBatch(messages);
    }

    private NoticeDO validateNoticeExists(Long id) {
        NoticeDO notice = noticeMapper.selectById(id);
        if (notice == null) {
            throw exception(NOTICE_NOT_EXISTS);
        }
        return notice;
    }

    private String buildTemplateContent(NoticeDO notice) {
        String content = HtmlUtil.cleanHtmlTag(StrUtil.nullToEmpty(notice.getContent()));
        if (StrUtil.isBlank(content)) {
            return notice.getTitle();
        }
        return notice.getTitle() + "\n" + content;
    }
}
