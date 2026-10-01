/**
 * 跨源结果的完整性口径（Y05）：授权维度上的"这份结果能不能给你看"。
 *
 * <p>与 `TableBlock.completeness` 分工不同，两者不可互相替代：
 * - `completeness` 回答"口径内有没有来源没跑成"（**技术**完整性，Y04 产出）；
 * - 这里的 `CrossSourceIntegrity` 回答"有没有来源你无权"（**授权**完整性，Y05 产出）。
 *
 * <p>把两者混成一个字段的后果是：授权拒绝会被显示成"结果可能不完整"，
 * 用户会以为是临时故障、稍后重试，而实际上重试永远不会成功——它需要去申请授权。
 * 处置动作不同，字段就必须分开。
 *
 * <p>三种状态刻意用**互斥的联合**而不是一个可选的 `state?: string`：
 * `WITHHELD` 携带的理由必填，而 `COMPLETE` 没有任何理由字段可填，
 * 拼装代码无法为"已放行"编一个理由出来。
 */
export type CrossSourceIntegrity =
  | { reason: string; state: 'PARTIAL' }
  | { reason: string; state: 'WITHHELD' }
  | { reason?: undefined; state: 'COMPLETE' };

/** 授权拒绝：整份结果不可交付。 */
export const CROSS_SOURCE_WITHHELD: CrossSourceIntegrity = {
  reason: '部分来源不在你的授权范围内，该结果未出具。',
  state: 'WITHHELD',
};

/**
 * 跨源结果**未声明口径**时的规定口径（Y07）：按 `WITHHELD` 处理，绝不按 `COMPLETE`。
 *
 * <p>这是本文件里最重要的一条常量。Y05 交付时本字段是可选的，缺失即按普通表格渲染——
 * 一旦后端在跨源场景漏发该字段，界面就会静默呈现本该被隐藏的数字（fail-open 缺口，
 * 记录在 Y05 证据文档 §9.1）。Y07 起，跨源响应带独立的 `crossSource` 标记，
 * 于是"这是跨源响应"与"口径是什么"成为两件可分别观测的事：
 * 前者缺失说明后端没接线（按单系统处理，保持 Y06 的零影响），
 * 后者缺失则说明后端没能证明调用方有权看——按 `WITHHELD` 处理。
 *
 * <p>为什么不能降级成"看起来完整"：那等于由前端替后端宣布"你有权看这份结果"，
 * 与 Y05 的 fail-closed 原则正面冲突。也不能降级成 `PARTIAL`——那会把一次
 * 契约缺陷显示成"数据可能不完整"，把排查方向从"后端没发口径"带偏到"某个来源挂了"。
 */
export const CROSS_SOURCE_INTEGRITY_MISSING: CrossSourceIntegrity = {
  reason: '服务端未声明本次跨源合并的授权完整性，该结果不出具。',
  state: 'WITHHELD',
};

/**
 * 这次结果是否**一个数字都不该渲染**。
 *
 * <p>`WITHHELD` 下必须同时做到三件事：不出数字、不出条数、不出"还剩几个来源"。
 * 只把表格清空而保留 `pageInfo.total` 是不够的——行数本身就是计数，
 * 用户拿它减去自己已知的那部分就能反推被禁来源的规模（Y05 专项一的"条数可数"）。
 */
export function rendersNothing(
  integrity: CrossSourceIntegrity | undefined,
): boolean {
  return integrity?.state === 'WITHHELD';
}

/**
 * 完整性提示文案。
 *
 * <p>三条约束：
 * 1. `WITHHELD` 必须说清"这不是暂时故障"——否则用户会无谓重试；
 * 2. 任何状态都**不得**回显被禁来源的名字或数量（后端已在服务端拒绝，前端不重造）；
 * 3. `PARTIAL` 的理由原样透传，但限定长度，避免把长文本塞进图注。
 */
export function integrityNotice(
  integrity: CrossSourceIntegrity | undefined,
): string {
  if (!integrity) {
    return '';
  }
  if (integrity.state === 'COMPLETE') {
    return '全部来源均在你的授权范围内';
  }
  if (integrity.state === 'WITHHELD') {
    return `该结果未出具：${integrity.reason}这不是临时故障，重试不会改变结果，请联系管理员开通授权。`;
  }
  const reason = integrity.reason.slice(0, 80);
  return `数据完整性：PARTIAL（${reason}）`;
}
