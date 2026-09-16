package com.basicframework.module.system.controller.admin.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.controller.admin.notice.vo.NoticePageReqVO;
import com.basicframework.module.system.controller.admin.notice.vo.NoticeSaveReqVO;
import com.basicframework.module.system.dal.dataobject.notice.NoticeDO;
import com.basicframework.module.system.enums.notice.NoticeTypeEnum;
import com.basicframework.module.system.service.notice.NoticeService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NoticeControllerTest {

    private final NoticeService noticeService = mock(NoticeService.class);
    private final NoticeController controller = new NoticeController(noticeService);

    @Test
    void mutationsMapRequestsAndDelegateExactTargets() {
        NoticeSaveReqVO request = saveRequest();
        when(noticeService.createNotice(any(NoticeDO.class))).thenReturn(9L);

        assertThat(controller.createNotice(request).getData()).isEqualTo(9L);
        assertThat(controller.updateNotice(request).getData()).isTrue();
        assertThat(controller.deleteNotice(7L).getData()).isTrue();
        assertThat(controller.deleteNoticeList(List.of(7L, 8L)).getData()).isTrue();

        ArgumentCaptor<NoticeDO> captor = ArgumentCaptor.forClass(NoticeDO.class);
        verify(noticeService).createNotice(captor.capture());
        assertThat(captor.getValue())
                .extracting(NoticeDO::getId, NoticeDO::getTitle, NoticeDO::getType, NoticeDO::getStatus)
                .containsExactly(7L, "系统升级通知", NoticeTypeEnum.NOTICE.getType(), 0);
        verify(noticeService).updateNotice(any(NoticeDO.class));
        verify(noticeService).deleteNotice(7L);
        verify(noticeService).deleteNoticeList(List.of(7L, 8L));
    }

    @Test
    void getAndPageMapResultsWhilePreservingEveryFilter() {
        NoticeDO notice = notice();
        NoticePageReqVO request = pageRequest();
        when(noticeService.getNotice(7L)).thenReturn(notice);
        when(noticeService.getNoticePage(same(request), same("升级"), same(0), same(request.getCreateTime())))
                .thenReturn(new PageResult<>(List.of(notice), 1L));

        assertThat(controller.getNotice(7L).getData().getTitle()).isEqualTo("系统升级通知");
        assertThat(controller.getNoticePage(request).getData().getTotal()).isEqualTo(1L);
        verify(noticeService).getNoticePage(same(request), same("升级"), same(0), same(request.getCreateTime()));
    }

    @Test
    void pushDelegatesAndRequiresAuthorization() throws Exception {
        assertThat(controller.pushNotice(7L).getData()).isTrue();

        verify(noticeService).pushNotice(7L);
        assertThat(NoticeController.class
                        .getMethod("pushNotice", Long.class)
                        .isAnnotationPresent(org.springframework.security.access.prepost.PreAuthorize.class))
                .isTrue();
    }

    private static NoticeSaveReqVO saveRequest() {
        NoticeSaveReqVO request = new NoticeSaveReqVO();
        request.setId(7L);
        request.setTitle("系统升级通知");
        request.setType(NoticeTypeEnum.NOTICE.getType());
        request.setContent("今晚升级");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return request;
    }

    private static NoticePageReqVO pageRequest() {
        NoticePageReqVO request = new NoticePageReqVO();
        request.setTitle("升级");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now().minusDays(1), LocalDateTime.now()});
        return request;
    }

    private static NoticeDO notice() {
        return new NoticeDO()
                .setId(7L)
                .setTitle("系统升级通知")
                .setType(NoticeTypeEnum.NOTICE.getType())
                .setContent("今晚升级")
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
    }
}
