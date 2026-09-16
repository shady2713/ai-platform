package com.basicframework.module.system.service.notice;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.notice.NoticeDO;
import java.time.LocalDateTime;
import java.util.List;

public interface NoticeService {

    Long createNotice(NoticeDO notice);

    void updateNotice(NoticeDO notice);

    void deleteNotice(Long id);

    void deleteNoticeList(List<Long> ids);

    NoticeDO getNotice(Long id);

    PageResult<NoticeDO> getNoticePage(PageParam pageParam, String title, Integer status, LocalDateTime[] createTime);

    void pushNotice(Long id);
}
