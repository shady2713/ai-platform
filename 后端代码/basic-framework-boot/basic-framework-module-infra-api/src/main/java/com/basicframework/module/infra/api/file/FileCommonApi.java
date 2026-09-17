package com.basicframework.module.infra.api.file;

import com.basicframework.module.infra.api.file.dto.FileCreateReqDTO;
import com.basicframework.module.infra.api.file.dto.FileDeleteReqDTO;
import com.basicframework.module.infra.api.file.dto.FileReadReqDTO;
import com.basicframework.module.infra.api.file.dto.FileRespDTO;

/**
 * 受控文件薄契约：供其它业务模块创建、读取与删除私有文件，不暴露 infra 的 Service、Mapper 与 DO。
 *
 * <p>语义约束：
 * <ul>
 *   <li>文件必须带业务绑定（businessType + businessId），读取授权由对应
 *       {@link FileBusinessAccessProvider} 决定，管理权限不得冒充业务授权；</li>
 *   <li>无权限与不存在返回同一语义（文件不存在），避免借编号枚举文件；</li>
 *   <li>删除按引用语义执行：受理后进入 infra 的受控删除流程，调用方不得直接操作存储。</li>
 * </ul>
 */
public interface FileCommonApi {

    /**
     * 创建受控私有文件。
     *
     * @param reqDTO 创建请求（内容、业务绑定与主体）
     * @return 文件编号
     */
    Long createFile(FileCreateReqDTO reqDTO);

    /**
     * 读取文件元数据。
     *
     * @param reqDTO 读取请求
     * @return 文件元数据；无权限或不存在时抛出统一的"文件不存在"业务异常
     */
    FileRespDTO getFileMeta(FileReadReqDTO reqDTO);

    /**
     * 读取文件内容。
     *
     * @param reqDTO 读取请求
     * @return 文件内容；无权限或不存在时抛出统一的"文件不存在"业务异常
     */
    byte[] getFileContent(FileReadReqDTO reqDTO);

    /**
     * 删除文件（引用删除语义）。
     *
     * @param reqDTO 删除请求
     */
    void deleteFile(FileDeleteReqDTO reqDTO);
}
