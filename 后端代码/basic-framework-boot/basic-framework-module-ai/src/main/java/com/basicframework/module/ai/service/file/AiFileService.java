package com.basicframework.module.ai.service.file;

import com.basicframework.module.ai.adapter.file.AiFileSubjectResolver;
import com.basicframework.module.ai.service.file.dto.AiFileUploadResultDTO;
import java.util.List;

/**
 * AI 业务文件服务（A07）：上传/读取/解除引用，全部走业务 ACL。
 *
 * <p>约束：
 * <ul>
 *   <li>上传必须声明业务类型与业务对象标识；未知业务类型一律拒绝（400）；</li>
 *   <li>读取按**当前**归属判定（不缓存）：绑定被解除、授权被回收、主体被停用后立即拒绝，
 *       无权限与不存在返回同一 404 语义，避免借编号枚举文件；</li>
 *   <li>删除是"解除引用 + 所有者判定"：只有所有者能解除；文件只有在没有其他有效引用时才真正删除
 *       （共享引用未释放不得误删）；管理权限不参与业务判定；</li>
 *   <li>文件内容与存储校验（魔数、大小、压缩包安全）由 infra 的受控文件接口负责，本层不直接访问存储。</li>
 * </ul>
 */
public interface AiFileService {

    /** 上传并绑定到业务对象（自动记录上传主体为所有者）。 */
    AiFileUploadResultDTO upload(
            String businessType, String businessKey, String name, String contentType, byte[] content);

    /** 读取文件内容（按当前归属判定）。 */
    byte[] read(Long fileId);

    /** 解除当前主体对该文件的引用；没有其他有效引用时同时删除文件。 */
    void release(Long fileId);

    /** 某业务对象当前的有效引用（供业务侧展示与审计）。 */
    List<AiFileUploadResultDTO> listByBusiness(String businessType, String businessKey);

    /** 供适配层复用的主体解析器（Provider 与控制器共用同一套可信解析）。 */
    AiFileSubjectResolver subjectResolver();
}
