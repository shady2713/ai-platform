package com.basicframework.module.system.service.dept;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.collection.CollectionUtils.convertMap;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import cn.hutool.core.collection.CollUtil;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.dept.PostDO;
import com.basicframework.module.system.dal.dataobject.dept.UserPostDO;
import com.basicframework.module.system.dal.mysql.dept.PostMapper;
import com.basicframework.module.system.dal.mysql.dept.UserPostMapper;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * 岗位 Service 实现类
 *
 */
@Service
@Validated
public class PostServiceImpl implements PostService {

    private final PostMapper postMapper;
    private final UserPostMapper userPostMapper;

    public PostServiceImpl(PostMapper postMapper, UserPostMapper userPostMapper) {
        this.postMapper = postMapper;
        this.userPostMapper = userPostMapper;
    }

    @Override
    public Long createPost(PostDO post) {
        // 校验正确性
        validatePostForCreateOrUpdate(null, post.getName(), post.getCode());

        // 插入岗位
        postMapper.insert(post);
        return post.getId();
    }

    @Override
    public void updatePost(PostDO updateObj) {
        // 校验正确性
        validatePostForCreateOrUpdate(updateObj.getId(), updateObj.getName(), updateObj.getCode());

        // 校验是否被用户引用，不允许禁用
        PostDO post = getPost(updateObj.getId());
        if (CommonStatusEnum.isEnable(post.getStatus()) && CommonStatusEnum.isDisable(updateObj.getStatus())) {
            if (CollUtil.isNotEmpty(userPostMapper.selectListByPostIds(Collections.singletonList(updateObj.getId())))) {
                throw exception(POST_IS_REFERENCED, post.getName());
            }
        }

        // 更新岗位
        postMapper.updateById(updateObj);
    }

    @Override
    public void deletePost(Long id) {
        validatePostsDeletable(Collections.singletonList(id));
        postMapper.deleteById(id);
    }

    @Override
    public void deletePostList(List<Long> ids) {
        validatePostsDeletable(ids);
        postMapper.deleteByIds(ids);
    }

    private void validatePostsDeletable(Collection<Long> ids) {
        Map<Long, PostDO> posts = convertMap(postMapper.selectByIds(ids), PostDO::getId);
        for (Long id : ids) {
            if (!posts.containsKey(id)) {
                throw exception(POST_NOT_EXISTS);
            }
        }
        List<UserPostDO> references = userPostMapper.selectListByPostIds(ids);
        if (CollUtil.isNotEmpty(references)) {
            throw exception(
                    POST_IS_REFERENCED, posts.get(references.get(0).getPostId()).getName());
        }
    }

    private void validatePostForCreateOrUpdate(Long id, String name, String code) {
        // 校验自己存在
        validatePostExists(id);
        // 校验岗位名的唯一性
        validatePostNameUnique(id, name);
        // 校验岗位编码的唯一性
        validatePostCodeUnique(id, code);
    }

    private void validatePostNameUnique(Long id, String name) {
        PostDO post = postMapper.selectByName(name);
        if (post == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的岗位
        if (id == null) {
            throw exception(POST_NAME_DUPLICATE);
        }
        if (!post.getId().equals(id)) {
            throw exception(POST_NAME_DUPLICATE);
        }
    }

    private void validatePostCodeUnique(Long id, String code) {
        PostDO post = postMapper.selectByCode(code);
        if (post == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的岗位
        if (id == null) {
            throw exception(POST_CODE_DUPLICATE);
        }
        if (!post.getId().equals(id)) {
            throw exception(POST_CODE_DUPLICATE);
        }
    }

    private void validatePostExists(Long id) {
        if (id == null) {
            return;
        }
        if (postMapper.selectById(id) == null) {
            throw exception(POST_NOT_EXISTS);
        }
    }

    @Override
    public List<PostDO> getPostList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return postMapper.selectByIds(ids);
    }

    @Override
    public List<PostDO> getPostList(Collection<Long> ids, Collection<Integer> statuses) {
        return postMapper.selectList(ids, statuses);
    }

    @Override
    public PageResult<PostDO> getPostPage(PageParam pageParam, String code, String name, Integer status) {
        return postMapper.selectPage(pageParam, code, name, status);
    }

    @Override
    public PostDO getPost(Long id) {
        return postMapper.selectById(id);
    }

    @Override
    public void validatePostList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        // 获得岗位信息
        List<PostDO> posts = postMapper.selectByIds(ids);
        Map<Long, PostDO> postMap = convertMap(posts, PostDO::getId);
        // 校验
        ids.forEach(id -> {
            PostDO post = postMap.get(id);
            if (post == null) {
                throw exception(POST_NOT_EXISTS);
            }
            if (!CommonStatusEnum.ENABLE.getStatus().equals(post.getStatus())) {
                throw exception(POST_NOT_ENABLE, post.getName());
            }
        });
    }
}
