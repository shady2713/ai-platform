package com.basicframework.module.system.controller.admin.dept;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.excel.core.util.ExcelUtils;
import com.basicframework.module.system.controller.admin.dept.vo.post.PostPageReqVO;
import com.basicframework.module.system.controller.admin.dept.vo.post.PostRespVO;
import com.basicframework.module.system.controller.admin.dept.vo.post.PostSaveReqVO;
import com.basicframework.module.system.dal.dataobject.dept.PostDO;
import com.basicframework.module.system.service.dept.PostService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletResponse;

class PostControllerTest {

    private final PostService postService = mock(PostService.class);
    private final PostController controller = new PostController(postService);

    @Test
    void mutationsMapRequestsAndDelegateExactTargets() {
        PostSaveReqVO request = saveRequest();
        when(postService.createPost(any(PostDO.class))).thenReturn(9L);

        assertThat(controller.createPost(request).getData()).isEqualTo(9L);
        assertThat(controller.updatePost(request).getData()).isTrue();
        assertThat(controller.deletePost(7L).getData()).isTrue();
        assertThat(controller.deletePostList(List.of(7L, 8L)).getData()).isTrue();

        ArgumentCaptor<PostDO> captor = ArgumentCaptor.forClass(PostDO.class);
        verify(postService).createPost(captor.capture());
        assertThat(captor.getValue())
                .extracting(PostDO::getId, PostDO::getName, PostDO::getCode, PostDO::getStatus)
                .containsExactly(7L, "技术负责人", "TECH_LEAD", CommonStatusEnum.ENABLE.getStatus());
        verify(postService).updatePost(any(PostDO.class));
        verify(postService).deletePost(7L);
        verify(postService).deletePostList(List.of(7L, 8L));
    }

    @Test
    void getAndSimpleListMapPublishedFieldsAndSortByOrder() {
        PostDO first = post(7L, 10);
        PostDO second = post(8L, 20);
        when(postService.getPost(7L)).thenReturn(first);
        when(postService.getPostList(null, null)).thenReturn(new ArrayList<>(List.of(second, first)));

        assertThat(controller.getPost(7L).getData().getCode()).isEqualTo("POST-7");
        assertThat(controller.getSimplePostList().getData()).extracting("id").containsExactly(7L, 8L);
        verify(postService).getPostList(null, null);
    }

    @Test
    void pagePreservesEveryFilter() {
        PostPageReqVO request = pageRequest();
        when(postService.getPostPage(same(request), same("TECH"), same("负责人"), same(0)))
                .thenReturn(new PageResult<>(List.of(post(7L, 10)), 1L));

        assertThat(controller.getPostPage(request).getData().getTotal()).isEqualTo(1L);
        verify(postService).getPostPage(same(request), same("TECH"), same("负责人"), same(0));
    }

    @Test
    void exportAppliesHardLimitAndWritesThePublishedSpreadsheet() throws Exception {
        PostPageReqVO request = pageRequest();
        PostDO post = post(7L, 10);
        List<PostRespVO> expectedRows = BeanUtils.toBean(List.of(post), PostRespVO.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(postService.getPostPage(any(), any(), any(), any())).thenReturn(new PageResult<>(List.of(post), 1L));

        try (MockedStatic<ExcelUtils> excelUtils = mockStatic(ExcelUtils.class)) {
            controller.export(response, request);

            excelUtils.verify(() -> ExcelUtils.write(response, "岗位数据.xls", "岗位列表", PostRespVO.class, expectedRows));
        }
        assertThat(request.getPageSize()).isEqualTo(PageParam.EXPORT_MAX_PAGE_SIZE);
    }

    private static PostSaveReqVO saveRequest() {
        PostSaveReqVO request = new PostSaveReqVO();
        request.setId(7L);
        request.setName("技术负责人");
        request.setCode("TECH_LEAD");
        request.setSort(10);
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return request;
    }

    private static PostPageReqVO pageRequest() {
        PostPageReqVO request = new PostPageReqVO();
        request.setCode("TECH");
        request.setName("负责人");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return request;
    }

    private static PostDO post(long id, int sort) {
        return new PostDO()
                .setId(id)
                .setName("岗位-" + id)
                .setCode("POST-" + id)
                .setSort(sort)
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
    }
}
