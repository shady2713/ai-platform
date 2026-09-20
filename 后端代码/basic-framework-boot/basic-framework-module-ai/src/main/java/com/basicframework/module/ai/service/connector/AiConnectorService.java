package com.basicframework.module.ai.service.connector;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorProbeDO;
import com.basicframework.module.ai.service.connector.dto.AiConnectorProbeResultDTO;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import java.util.List;

/**
 * 连接器配置与秘密管理（D01）。
 *
 * <p>约束：
 * <ul>
 *   <li><b>声明式配置</b>：只接受白名单键与严格取值，整段连接串与未知参数一律拒绝；</li>
 *   <li><b>秘密不回显</b>：秘密经 CredentialCipher 加密后只存密文，接口只回"是否已配置"，
 *       轮换只递增秘密版本；日志与异常不出现凭据、主机与连接串；</li>
 *   <li><b>探测走统一网络策略</b>：HTTP 探测经平台受控出站客户端（默认拒绝一切目标），
 *       MySQL 只连接由已校验字段拼出的地址；结论只记录稳定原因码；</li>
 *   <li><b>引用保护</b>：被数据集/工具引用的连接器不能删除。</li>
 * </ul>
 */
public interface AiConnectorService {

    /** 新增连接器（标识唯一；秘密加密存储）。 */
    Long create(AiConnectorSaveDTO saveDTO);

    /** 修改连接器（乐观锁；留空秘密表示保留已有秘密）。 */
    void update(AiConnectorSaveDTO saveDTO);

    /** 轮换秘密（乐观锁）。 */
    void rotateCredential(Long id, Integer version, String credential);

    /** 启用/停用（乐观锁）。 */
    void updateStatus(Long id, Integer version, Boolean enabled);

    /** 删除（被引用时拒绝）。 */
    void delete(Long id, Integer version);

    /** 连接测试（HTTP 经统一出站策略；MySQL 直连已校验地址）。 */
    AiConnectorProbeResultDTO probe(Long id);

    /** 某连接器的探测记录（最新在前）。 */
    List<AiConnectorProbeDO> listProbes(Long id);

    AiConnectorDO getConnector(Long id);

    PageResult<AiConnectorDO> getPage(PageParam pageParam, String connectorType, String status);
}
