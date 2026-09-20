import { RequestClient } from '@vben/request';

/**
 * 开放平台目录与在线调试（O08）。
 *
 * 目录数据来自 O07 冻结的开放 API 规范（`docs/integrations/open-api/ai-open-api.json`）：
 * 只登记**已发布**的开放端点，未发布的接口不在这里出现（Q10 在完整发布目录上复验）。
 *
 * 在线调试走应用端通道（`/app-api`）：用应用客户端凭据换取**短期受限票据**，
 * 票据只驻留内存、请求结束后立即丢弃；`appSecret` 与 `token` 都不写入任何持久化存储，
 * 也不在响应区域回显。
 */

/** 应用端通道：与 admin-api 分离，避免把管理端令牌带到应用端接口上。 */
const appApiClient = new RequestClient({
  baseURL: '/app-api',
  withCredentials: false,
});

export namespace AiOpenPlatformApi {
  /** 开放端点目录条目 */
  export interface Endpoint {
    id: string;
    tag: string;
    method: 'DELETE' | 'GET' | 'POST' | 'PUT';
    path: string;
    summary: string;
    /** 鉴权与归属说明（票据主体、应用客户端凭据等） */
    auth: string;
    /** 限额与校验上限（与实现一致） */
    limits: string[];
    /** 是否异步受理（返回后需订阅事件或查询进度） */
    asynchronous: boolean;
    /** 是否 SSE 事件流 */
    sse: boolean;
    /** 请求示例（不含真实凭据与令牌） */
    requestExample?: string;
    /** 响应示例（不含真实凭据与令牌） */
    responseExample?: string;
    /** 可能返回的状态码与业务错误码说明 */
    errors: string[];
  }

  /** 换票结果（token 只在本次返回一次，平台只保存摘要） */
  export interface DebugTicket {
    token: string;
    expiresTime?: string;
    applicationId?: number;
    subjectType?: string;
    externalUserId?: string;
  }

  /** 调试调用结果 */
  export interface DebugCallResult {
    status: number;
    body: string;
  }
}

/** 开放端点目录（仅已发布接口；顺序与规范一致） */
export const AI_OPEN_API_CATALOG: AiOpenPlatformApi.Endpoint[] = [
  {
    id: 'auth-ticket',
    tag: '认证',
    method: 'POST',
    path: '/app-api/ai/auth/ticket',
    summary: '换取访问票据（应用客户端凭据 + 失败节流）',
    auth: '应用客户端凭据（appCode + appSecret）；token 只在本次响应返回一次',
    limits: ['appSecret ≤ 128', '连续失败触发 429 节流'],
    asynchronous: false,
    sse: false,
    requestExample:
      '{"appCode":"crm-portal","appSecret":"<APP_SECRET>","subjectType":"USER","externalUserId":"u-1001"}',
    responseExample:
      '{"code":0,"data":{"token":"<TICKET>","expiresTime":"2026-09-20T10:00:00","subjectType":"USER"},"msg":""}',
    errors: ['401 客户端凭据无效', '429 失败节流'],
  },
  {
    id: 'run-accept',
    tag: '运行',
    method: 'POST',
    path: '/app-api/ai/run/accept',
    summary: '受理运行（幂等：同键同请求复用原运行，异请求 409）',
    auth: 'Bearer 票据；归属由票据主体决定',
    limits: [
      '幂等键 16-128',
      '消息 ≤ 16000',
      '附件 ≤ 20 个',
      '业务上下文 ≤ 4000',
    ],
    asynchronous: true,
    sse: false,
    requestExample:
      '{"serviceId":9,"conversationId":31,"idempotencyKey":"idem-0123456789abcdef","message":"帮我查订单 A-1","dataLevel":"L2_INTERNAL"}',
    responseExample:
      '{"code":0,"data":{"runId":41,"runKey":"run_0123456789abcdef01234567","status":"ACCEPTED","reused":false},"msg":""}',
    errors: [
      '400 入参不合法',
      '409 幂等键冲突',
      '502/503/504 上游失败（稳定错误，可重试）',
    ],
  },
  {
    id: 'run-events',
    tag: '运行',
    method: 'GET',
    path: '/app-api/ai/run/events',
    summary: '订阅运行事件（SSE，先鉴权再开流）',
    auth: 'Bearer 票据；归属判定在开流前完成',
    limits: [
      '单批重放 ≤ 200 条',
      '连接时长上限 5 分钟',
      '心跳是注释，不推进 seq',
    ],
    asynchronous: false,
    sse: true,
    requestExample: 'GET /app-api/ai/run/events?runId=41&afterSeq=0',
    responseExample: String.raw`id: 1\nevent: run\ndata: {"schemaVersion":"1.0","seq":1,"runId":"run_0123456789abcdef01234567","status":"RUNNING"}\n\n: heartbeat\n\n`,
    errors: ['409 重放窗口已过期（请读运行进度与快照）'],
  },
  {
    id: 'run-cancel',
    tag: '运行',
    method: 'POST',
    path: '/app-api/ai/run/cancel',
    summary: '取消运行（显式动作：写入终态事件并终止任务）',
    auth: 'Bearer 票据；归属由票据主体决定',
    limits: ['乐观锁版本必填'],
    asynchronous: false,
    sse: false,
    requestExample: '{"runId":41,"version":2}',
    responseExample: '{"code":0,"data":true,"msg":""}',
    errors: ['409 运行已进入终态'],
  },
  {
    id: 'run-get',
    tag: '运行',
    method: 'GET',
    path: '/app-api/ai/run/get',
    summary: '读取运行（越权与不存在同语义）',
    auth: 'Bearer 票据；归属由票据主体决定',
    limits: [],
    asynchronous: false,
    sse: false,
    requestExample: 'GET /app-api/ai/run/get?id=41',
    responseExample:
      '{"code":0,"data":{"id":41,"runKey":"run_0123456789abcdef01234567","status":"SUCCEEDED"},"msg":""}',
    errors: ['404 运行不存在或不属于当前主体'],
  },
  {
    id: 'run-page',
    tag: '运行',
    method: 'GET',
    path: '/app-api/ai/run/page',
    summary: '当前主体的运行分页（按编号倒序）',
    auth: 'Bearer 票据；只返回当前主体的运行',
    limits: ['pageSize ≤ 100'],
    asynchronous: false,
    sse: false,
    requestExample: 'GET /app-api/ai/run/page?pageNo=1&pageSize=20',
    responseExample: '{"code":0,"data":{"list":[],"total":0},"msg":""}',
    errors: ['401/403/429 通用错误'],
  },
  {
    id: 'task-progress',
    tag: '任务',
    method: 'GET',
    path: '/app-api/ai/task/progress',
    summary: '运行进度与结果引用（只给标识与摘要）',
    auth: 'Bearer 票据；归属由票据主体决定',
    limits: [],
    asynchronous: false,
    sse: false,
    requestExample: 'GET /app-api/ai/task/progress?runId=41',
    responseExample:
      '{"code":0,"data":{"runId":41,"status":"FAILED","taskStatus":"FAILED","retryable":true,"resultMessageId":null,"resultDigest":null},"msg":""}',
    errors: ['404 运行不存在或不属于当前主体'],
  },
  {
    id: 'task-retry',
    tag: '任务',
    method: 'POST',
    path: '/app-api/ai/task/retry',
    summary: '人工重试（校验当前权限与可重试性）',
    auth: 'Bearer 票据；重试前按当前权限重建身份',
    limits: ['乐观锁版本必填', '人工重试重置自动重试预算'],
    asynchronous: true,
    sse: false,
    requestExample: '{"runId":41,"version":3}',
    responseExample: '{"code":0,"data":true,"msg":""}',
    errors: ['409 任务不可重试（含结果未知 UNKNOWN）'],
  },
  {
    id: 'task-page',
    tag: '任务',
    method: 'GET',
    path: '/app-api/ai/task/page',
    summary: '当前主体的运行进度分页（按编号倒序）',
    auth: 'Bearer 票据；只返回当前主体的运行',
    limits: ['pageSize ≤ 100'],
    asynchronous: false,
    sse: false,
    requestExample: 'GET /app-api/ai/task/page?pageNo=1&pageSize=20',
    responseExample: '{"code":0,"data":{"list":[],"total":0},"msg":""}',
    errors: ['401/403/429 通用错误'],
  },
  {
    id: 'conversation-create',
    tag: '会话',
    method: 'POST',
    path: '/app-api/ai/conversation/create',
    summary: '创建会话（业务键在同一应用+主体内唯一）',
    auth: 'Bearer 票据；归属由服务端身份决定，请求体不能自报',
    limits: [
      '会话业务键 ^conv_[A-Za-z0-9_-]{3,35}$',
      '标题 ≤ 128',
      '业务上下文 ≤ 4000',
    ],
    asynchronous: false,
    sse: false,
    requestExample: String.raw`{"conversationKey":"conv_order_qa","title":"订单问答","businessContext":"{\"page\":\"order\"}"}`,
    responseExample: '{"code":0,"data":31,"msg":""}',
    errors: ['409 会话业务键已存在'],
  },
  {
    id: 'conversation-messages',
    tag: '会话',
    method: 'GET',
    path: '/app-api/ai/conversation/messages',
    summary: '会话消息（按序号升序，支持 afterSequence 推进）',
    auth: 'Bearer 票据；越权与不存在同语义',
    limits: ['limit 1-100（缺省 20）'],
    asynchronous: false,
    sse: false,
    requestExample:
      'GET /app-api/ai/conversation/messages?conversationId=31&afterSequence=0&limit=20',
    responseExample:
      '{"code":0,"data":[{"id":77,"conversationId":31,"sequenceNo":1,"role":"user","content":"..."}],"msg":""}',
    errors: ['404 会话不存在或不属于当前主体'],
  },
  {
    id: 'conversation-delete',
    tag: '会话',
    method: 'DELETE',
    path: '/app-api/ai/conversation/delete',
    summary: '删除会话（先关闭访问，正文由保留策略清理）',
    auth: 'Bearer 票据；仅本人会话',
    limits: ['乐观锁版本必填'],
    asynchronous: false,
    sse: false,
    requestExample: 'DELETE /app-api/ai/conversation/delete?id=31&version=3',
    responseExample: '{"code":0,"data":true,"msg":""}',
    errors: ['409 乐观锁版本冲突'],
  },
  {
    id: 'file-upload',
    tag: '文件',
    method: 'POST',
    path: '/app-api/ai/file/upload',
    summary: '上传并绑定到业务对象',
    auth: 'Bearer 票据；绑定目标按 A03 授权目录或所有者判定',
    limits: [
      'businessType ∈ {ai_report, ai_knowledge_document, ai_chat_session}',
      'businessKey ≤ 128',
    ],
    asynchronous: false,
    sse: false,
    requestExample:
      'POST /app-api/ai/file/upload (multipart: businessType=ai_chat_session, businessKey=conv_order_qa, file=@demo.pdf)',
    responseExample:
      '{"code":0,"data":{"fileId":88,"businessType":"ai_chat_session","businessKey":"conv_order_qa"},"msg":""}',
    errors: ['404 业务对象不存在或无权绑定'],
  },
  {
    id: 'file-read',
    tag: '文件',
    method: 'GET',
    path: '/app-api/ai/file/{fileId}',
    summary: '按当前归属读取文件内容',
    auth: 'Bearer 票据；每次读取都按当前归属重新判定',
    limits: [],
    asynchronous: false,
    sse: false,
    requestExample: 'GET /app-api/ai/file/88',
    responseExample: '<application/octet-stream 二进制内容>',
    errors: ['404 文件不存在或不属于当前主体'],
  },
  {
    id: 'file-release',
    tag: '文件',
    method: 'DELETE',
    path: '/app-api/ai/file/{fileId}',
    summary: '解除当前主体对该文件的引用（无其他引用时删除文件）',
    auth: 'Bearer 票据；仅所有者可解除引用',
    limits: [],
    asynchronous: false,
    sse: false,
    requestExample: 'DELETE /app-api/ai/file/88',
    responseExample: '{"code":0,"data":true,"msg":""}',
    errors: ['403 非所有者'],
  },
];

/** 按 id 查目录条目（未登记的接口返回 undefined：未发布接口不出现） */
export function findEndpoint(id: string) {
  return AI_OPEN_API_CATALOG.find((endpoint) => endpoint.id === id);
}

/**
 * 用应用客户端凭据换取**短期受限票据**（调试专用）。
 *
 * 凭据只作为本次请求体发送；返回的 token 由调用方保存在内存中并尽快丢弃，
 * 本模块不写入任何持久化存储。
 */
export async function issueDebugTicket(
  appCode: string,
  appSecret: string,
  subjectType: string,
  externalUserId?: string,
) {
  return appApiClient.post<AiOpenPlatformApi.DebugTicket>('/ai/auth/ticket', {
    appCode,
    appSecret,
    externalUserId,
    subjectType,
  });
}

/** 使用票据调用一个开放端点（调试）；结果只返回状态码与响应文本，不回显凭据。 */
export async function callDebugEndpoint(
  ticket: string,
  method: AiOpenPlatformApi.Endpoint['method'],
  path: string,
): Promise<AiOpenPlatformApi.DebugCallResult> {
  const url = path.replace('/app-api', '');
  // 只带本次调试票据；不携带管理端令牌，也不把票据写入任何存储
  const response = await appApiClient.request<unknown, undefined>(url, {
    headers: { Authorization: `Bearer ${ticket}` },
    method,
  });
  return {
    body: typeof response === 'string' ? response : JSON.stringify(response),
    status: 200,
  };
}
