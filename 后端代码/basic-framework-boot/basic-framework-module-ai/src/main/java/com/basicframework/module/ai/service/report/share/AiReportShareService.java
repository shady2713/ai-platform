package com.basicframework.module.ai.service.report.share;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.report.AiReportShareAccessDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportShareDO;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareCreateDTO;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareCreateResultDTO;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareReadDTO;
import java.util.List;

/**
 * 报表受控分享（X11）：授予者与接收者都是已登录的 USER 主体，可见权与源数据读取权分离。
 *
 * <p>六条不变量（对应卡片逐步实施）：
 * <ol>
 *   <li><b>仅已登录且获授权主体之间分享</b>：授予者必须是报表所有者（越权与不存在同语义 404），
 *       接收者必须是同应用内可用的 USER 主体且不能是自己；</li>
 *   <li><b>可见权不等于读取权</b>：凭据只让接收者"打开这份分享"，内容出库前按接收者
 *       **当前**源权限逐项复核（镜像 A03 历史再鉴权），覆盖不了就是降级态（隐藏统计与快照）；</li>
 *   <li><b>凭据只存摘要</b>：明文令牌 32 字节 SecureRandom → Base64URL，仅在创建响应出现一次；</li>
 *   <li><b>版本在创建时固定</b>：分享内容是签发时刻的版本，报表新增版本不改变分享；</li>
 *   <li><b>撤销/到期/授予者停用立即生效</b>：后续读取一律 404（防枚举，与"分享不存在"同语义）；
 *       到期不设常驻扫描任务，读取时惰性物化（X10 教训：无界扫描伤请求延迟）；</li>
 *   <li><b>每次读取留痕</b>：含拒绝在内，每次读取追加一条访问审计（结论 + 稳定原因码）。</li>
 * </ol>
 */
public interface AiReportShareService {

    /** 创建分享（同报表 + 同接收者的 ACTIVE 分享已存在时 409；明文令牌只在本次结果里）。 */
    AiReportShareCreateResultDTO create(AiReportShareCreateDTO createDTO);

    /** 按令牌读取分享：五类前置失败一律 404（审计 DENIED + 稳定原因码）；源权限覆盖不了则降级态。 */
    AiReportShareReadDTO readByToken(String token);

    /** 撤销分享（CAS）：仅授予者本人 + 仍 ACTIVE + 版本匹配；已撤销按幂等成功返回。 */
    void revoke(Long shareId, Integer expectedVersion);

    /** 授予者视角分页（显示接收范围：接收者标识 + 创建时快照的显示名）。 */
    PageResult<AiReportShareDO> getSharePage(PageParam pageParam);

    /** 某分享的访问审计（仅授予者本人可查，编号倒序）。 */
    List<AiReportShareAccessDO> getAccessRecords(Long shareId);
}
