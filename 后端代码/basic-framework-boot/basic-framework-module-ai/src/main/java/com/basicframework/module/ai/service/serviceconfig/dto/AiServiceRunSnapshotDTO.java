package com.basicframework.module.ai.service.serviceconfig.dto;

import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import com.basicframework.module.ai.domain.runtime.AiRunSnapshot;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 运行的发布快照（S02 建立，S03 补齐固定值）：服务解析出的发布版本及其冻结的资源绑定。
 *
 * <p>运行时只用快照里的内容（提示词、Schema、能力、端点与端点配置版本），不重新读取草稿；
 * 但资源授权与绑定状态始终检查当前值——快照只是"需要哪些资源"的清单，不是权限的副本。
 *
 * <p>{@link #pin} 是给运行落库的版本固定值（releaseId + 内容摘要 + 端点配置版本 + 资源版本）；
 * {@link #pinned} 区分本次解析是"按别名新建"还是"会话沿用固定版本"：回退只改变前者的结果。
 */
@Data
@Accessors(chain = true)
public class AiServiceRunSnapshotDTO {

    /** 生效的发布版本 */
    private AiServiceReleaseDO release;

    /** 该版本冻结的生效中资源绑定 */
    private List<AiServiceResourceDO> bindings;

    /** 本运行固定到的版本（供运行/会话落库并在后续消息中沿用） */
    private AiRunSnapshot pin;

    /** 是否来自会话固定版本（false 表示按别名解析的新运行） */
    private boolean pinned;
}
