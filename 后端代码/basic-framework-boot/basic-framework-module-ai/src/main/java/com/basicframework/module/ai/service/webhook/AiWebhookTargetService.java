package com.basicframework.module.ai.service.webhook;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookTargetDO;
import com.basicframework.module.ai.service.webhook.dto.AiWebhookTargetSaveDTO;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Webhook 目标服务（X10）：登记"把哪些运行终态事件投给谁"。
 *
 * <p>约束：
 * <ul>
 *   <li><b>应用内标识唯一</b>：同应用下 {@code code} 重复直接拒绝；创建后不可修改；</li>
 *   <li><b>事件白名单</b>：只接受运行终态三种事件，空集合与非法取值都拒绝（不会"默认订阅一切"）；</li>
 *   <li><b>密钥边界</b>：明文只在写入的入参里出现，落库是 CredentialCipher 密文（AAD 绑本行编号），
 *       任何读取路径都不返回明文与密文，只返回 {@code secretConfigured}/{@code secretRevision}；</li>
 *   <li><b>停用即停发</b>：DISABLED 的目标不再入队新投递、发送前复检被拒、人工重投被拒；
 *       在途投递按当时事实收尾（既不谎报成功，也不回写运行结果）。</li>
 * </ul>
 */
public interface AiWebhookTargetService {

    /** 新增目标（首次写入签名密钥）；返回目标编号。 */
    Long create(AiWebhookTargetSaveDTO saveDTO);

    /** 修改目标（名称/地址/事件/尝试上限；携带密钥时一并轮换，留空表示保留）。 */
    void update(AiWebhookTargetSaveDTO saveDTO);

    /** 轮换签名密钥（旧密钥立即作废，只递增版本；响应不含任何密钥材料）。 */
    void rotateSecret(Long id, Integer version, String secret);

    /** 启用/停用（停用即停发）。 */
    void updateStatus(Long id, Integer version, Boolean enabled);

    /** 删除目标（逻辑删除；在途投递会在发送前复检失败并按事实收尾）。 */
    void delete(Long id, Integer version);

    /** 读取目标（不存在即 404，不区分"无权"以避免枚举）。 */
    AiWebhookTargetDO get(Long id);

    /** 分页（按应用过滤；标识模糊、状态精确）。 */
    PageResult<AiWebhookTargetDO> getPage(PageParam pageParam, Long applicationId, String code, String status);

    /**
     * 解密签名密钥（**只给投递器**）：密钥不进日志、不进响应、不进 toString。
     * 目标不存在、未配置密钥或密文不可解密时返回 {@code null}（投递器按"密钥不可用"确定失败，不发请求）。
     */
    String decryptSecret(Long targetId);

    /** 启用中的目标（补漏扫描的驱动集合，按编号升序；停用期间不扫描，重新启用后从原水位继续补齐）。 */
    List<AiWebhookTargetDO> listEnabled();

    /**
     * 单调推进目标行的补漏水位（**只给投递 Job**）。水位是作业内部进度，不参与乐观锁、不改变控制面版本；
     * 传更大的值才会写入，回退请求被忽略。
     */
    void advanceEnqueueWatermark(Long targetId, LocalDateTime watermark);
}
