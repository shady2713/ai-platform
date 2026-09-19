package com.basicframework.module.ai.service.serviceconfig.dto;

import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 新运行的发布快照（S02）：服务解析出的**唯一 ACTIVE 版本**及其冻结的资源绑定。
 *
 * <p>运行时只用快照里的内容（提示词、Schema、能力、端点与端点配置版本），不重新读取草稿；
 * 但资源授权与绑定状态始终检查当前值——快照只是"需要哪些资源"的清单，不是权限的副本。
 */
@Data
@Accessors(chain = true)
public class AiServiceRunSnapshotDTO {

    /** 生效的发布版本 */
    private AiServiceReleaseDO release;

    /** 该版本冻结的生效中资源绑定 */
    private List<AiServiceResourceDO> bindings;
}
