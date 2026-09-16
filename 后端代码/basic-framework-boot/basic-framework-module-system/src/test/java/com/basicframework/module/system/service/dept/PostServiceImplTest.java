package com.basicframework.module.system.service.dept;

import static com.basicframework.module.system.testutil.ServiceExceptionAssert.assertServiceException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.module.system.dal.dataobject.dept.PostDO;
import com.basicframework.module.system.dal.dataobject.dept.UserPostDO;
import com.basicframework.module.system.dal.mysql.dept.PostMapper;
import com.basicframework.module.system.dal.mysql.dept.UserPostMapper;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 岗位 Service 单元测试
 *
 * 覆盖岗位的创建/更新/删除主链路与三处业务校验：
 * 名称唯一、编码唯一、禁用与删除时的引用保护（POST_IS_REFERENCED）。
 */
@ExtendWith(MockitoExtension.class)
class PostServiceImplTest {

    @InjectMocks
    private PostServiceImpl postService;

    @Mock
    private PostMapper postMapper;

    @Mock
    private UserPostMapper userPostMapper;

    private PostDO post;

    @BeforeEach
    void setUp() {
        post = new PostDO();
        post.setId(1L);
        post.setName("测试岗位");
        post.setCode("TEST_POST");
        post.setStatus(CommonStatusEnum.ENABLE.getStatus());
    }

    @Test
    void createPost_insertsAndReturnsId() {
        when(postMapper.selectByName(post.getName())).thenReturn(null);
        when(postMapper.selectByCode(post.getCode())).thenReturn(null);

        Long id = postService.createPost(post);

        verify(postMapper).insert(post);
        assertThat(id).isEqualTo(post.getId());
    }

    @Test
    void createPost_nameDuplicateThrows() {
        when(postMapper.selectByName(post.getName())).thenReturn(otherPost(post.getName(), "CODE_1"));

        assertServiceException(ErrorCodeConstants.POST_NAME_DUPLICATE.getCode(), () -> postService.createPost(post));
        verify(postMapper, never()).insert(any(PostDO.class));
    }

    @Test
    void createPost_codeDuplicateThrows() {
        when(postMapper.selectByName(post.getName())).thenReturn(null);
        when(postMapper.selectByCode(post.getCode())).thenReturn(otherPost("其他岗位", post.getCode()));

        assertServiceException(ErrorCodeConstants.POST_CODE_DUPLICATE.getCode(), () -> postService.createPost(post));
        verify(postMapper, never()).insert(any(PostDO.class));
    }

    @Test
    void updatePost_notExistsThrows() {
        when(postMapper.selectById(post.getId())).thenReturn(null);

        assertServiceException(ErrorCodeConstants.POST_NOT_EXISTS.getCode(), () -> postService.updatePost(post));
    }

    @Test
    void updatePost_nameTakenByOtherThrows() {
        when(postMapper.selectById(post.getId())).thenReturn(post);
        when(postMapper.selectByName(post.getName())).thenReturn(otherPost(post.getName(), "CODE_2"));

        assertServiceException(ErrorCodeConstants.POST_NAME_DUPLICATE.getCode(), () -> postService.updatePost(post));
    }

    @Test
    void updatePost_disableWhileReferencedThrows() {
        PostDO disabledPost = new PostDO();
        disabledPost.setId(post.getId());
        disabledPost.setName(post.getName());
        disabledPost.setCode(post.getCode());
        disabledPost.setStatus(CommonStatusEnum.DISABLE.getStatus());
        when(postMapper.selectById(post.getId())).thenReturn(post);
        when(postMapper.selectByName(post.getName())).thenReturn(null);
        when(postMapper.selectByCode(post.getCode())).thenReturn(null);
        when(userPostMapper.selectListByPostIds(List.of(post.getId()))).thenReturn(List.of(new UserPostDO()));

        assertServiceException(
                ErrorCodeConstants.POST_IS_REFERENCED.getCode(), () -> postService.updatePost(disabledPost));
        verify(postMapper, never()).updateById(any(PostDO.class));
    }

    @Test
    void updatePost_disableWithoutReferenced_succeeds() {
        PostDO disabledPost = new PostDO();
        disabledPost.setId(post.getId());
        disabledPost.setName(post.getName());
        disabledPost.setCode(post.getCode());
        disabledPost.setStatus(CommonStatusEnum.DISABLE.getStatus());
        when(postMapper.selectById(post.getId())).thenReturn(post);
        when(postMapper.selectByName(post.getName())).thenReturn(null);
        when(postMapper.selectByCode(post.getCode())).thenReturn(null);
        when(userPostMapper.selectListByPostIds(List.of(post.getId()))).thenReturn(List.of());

        postService.updatePost(disabledPost);

        verify(postMapper).updateById(disabledPost);
    }

    @Test
    void deletePost_notExistsThrows() {
        when(postMapper.selectByIds(List.of(post.getId()))).thenReturn(List.of());

        assertServiceException(
                ErrorCodeConstants.POST_NOT_EXISTS.getCode(), () -> postService.deletePost(post.getId()));
    }

    @Test
    void deletePost_success() {
        when(postMapper.selectByIds(List.of(post.getId()))).thenReturn(List.of(post));
        when(userPostMapper.selectListByPostIds(List.of(post.getId()))).thenReturn(List.of());

        postService.deletePost(post.getId());

        verify(postMapper).deleteById(post.getId());
    }

    @Test
    void deletePost_referencedThrows() {
        when(postMapper.selectByIds(List.of(post.getId()))).thenReturn(List.of(post));
        when(userPostMapper.selectListByPostIds(List.of(post.getId())))
                .thenReturn(List.of(new UserPostDO().setPostId(post.getId())));

        assertServiceException(
                ErrorCodeConstants.POST_IS_REFERENCED.getCode(), () -> postService.deletePost(post.getId()));

        verify(postMapper, never()).deleteById(post.getId());
    }

    @Test
    void deletePostList_batchDeletes() {
        List<Long> ids = List.of(1L, 2L);
        PostDO secondPost = otherPost("其他岗位", "OTHER");
        secondPost.setId(2L);
        when(postMapper.selectByIds(ids)).thenReturn(List.of(post, secondPost));
        when(userPostMapper.selectListByPostIds(ids)).thenReturn(List.of());

        postService.deletePostList(ids);

        verify(postMapper).deleteByIds(ids);
    }

    @Test
    void deletePostList_missingPostThrowsBeforeDelete() {
        List<Long> ids = List.of(1L, 2L);
        when(postMapper.selectByIds(ids)).thenReturn(List.of(post));

        assertServiceException(ErrorCodeConstants.POST_NOT_EXISTS.getCode(), () -> postService.deletePostList(ids));

        verify(postMapper, never()).deleteByIds(ids);
    }

    // ---------- helpers ----------

    private PostDO otherPost(String name, String code) {
        PostDO other = new PostDO();
        other.setId(99L);
        other.setName(name);
        other.setCode(code);
        return other;
    }
}
