package com.basicframework.module.infra.service.file;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.enums.file.FileAccessTypeEnum;
import com.basicframework.module.infra.service.file.dto.FilePresignedUrlDTO;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件 Service 接口
 *
 */
public interface FileService {

    /**
     * 获得文件分页
     *
     * @param pageParam  分页参数
     * @param path       文件路径，模糊匹配
     * @param type       文件类型，模糊匹配
     * @param createTime 创建时间区间
     * @return 文件分页
     */
    PageResult<FileDO> getFilePage(PageParam pageParam, String path, String type, LocalDateTime[] createTime);

    /**
     * 保存文件，并返回文件的访问路径
     *
     * @param content   文件内容
     * @param name      文件名称，允许空
     * @param directory 目录，允许空
     * @param type      文件的 MIME 类型，允许空
     * @param principal 已认证上传主体
     * @param accessType 文件读取策略
     * @return 文件访问地址
     */
    String createFile(
            @NotEmpty(message = "文件内容不能为空") byte[] content,
            String name,
            String directory,
            String type,
            FileUploadPrincipal principal,
            FileAccessTypeEnum accessType);

    /**
     * 生成文件预签名地址信息，用于上传
     *
     * @param name      文件名
     * @param directory 目录
     * @param size      文件大小
     * @param type      文件 MIME 类型
     * @param principal 已认证上传主体
     * @param accessType 文件读取策略
     * @return 预签名地址信息
     */
    FilePresignedUrlDTO presignPutUrl(
            @NotEmpty(message = "文件名不能为空") String name,
            String directory,
            Long size,
            String type,
            FileUploadPrincipal principal,
            FileAccessTypeEnum accessType);
    /**
     * 生成文件预签名地址信息，用于读取
     *
     * @param url 完整的文件访问地址
     * @param expirationSeconds 访问有效期，单位秒
     * @return 文件预签名地址
     */
    String presignGetUrl(String url, Integer expirationSeconds);

    /**
     * 创建文件
     *
     * @param uploadToken 一次性上传完成凭据
     * @param principal 当前登录主体
     * @return 校验通过后的文件访问地址
     */
    String createPresignedFile(String uploadToken, FileUploadPrincipal principal);

    FileDO getFile(Long id);

    /**
     * 删除文件
     *
     * @param id 编号
     */
    void deleteFile(Long id) throws Exception;

    /**
     * 批量删除文件
     *
     * @param ids 编号列表
     */
    void deleteFileList(List<Long> ids) throws Exception;

    /**
     * 获得文件内容
     *
     * @param configId 配置编号
     * @param path     文件路径
     * @param principal 当前请求主体与文件管理权限
     * @return 文件内容
     */
    byte[] getFileContent(Long configId, String path, FileAccessPrincipal principal) throws Exception;

    /**
     * 创建受业务绑定管控的私有文件，返回文件编号。
     *
     * <p>复用既有路径/MIME/归档/白名单校验链；业务类型必须已注册业务授权实现，否则拒绝创建。
     */
    Long createBusinessFile(
            byte[] content,
            String name,
            String type,
            String businessType,
            Long businessId,
            FileUploadPrincipal principal);

    /**
     * 按主体授权读取文件；无权限与不存在返回同一业务异常。
     */
    FileDO getAuthorizedFile(Long id, FileAccessPrincipal principal);

    /**
     * 删除业务绑定文件：删除授权由业务 Provider 判定，管理权限不适用。
     */
    void deleteBusinessFile(Long id, FileAccessPrincipal principal) throws Exception;
}
