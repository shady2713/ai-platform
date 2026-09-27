/**
 * Q07 韧性探针（vite 打包），与 Q06 的 `shell-bundle.js` 同法：**跑真实产品代码**，
 * 夹具只补"本机没有后端"这一段。
 *
 * 两件事：
 *  1. `mountTable(block)`：用真实契约解析（`blocks.ts` 的 `parseMessageBlock`）+ 真实渲染组件
 *     （`ResultTable.vue`，`MessageBlockView` 对表格块的同一实现）渲染一个表格块，
 *     把完整性标注与单元格文本回给用例——AT-039/AT-060 的"部分结果/未知必须显式标注、
 *     空值不得补 0"就在这个界面上判定；
 *  2. `reconnectStream(baseUrl)` / `windowExpired(baseUrl)`：直接调用**版本化 SDK 客户端**
 *     （`@vben/ai-embed-sdk` 的 `createAiChatClient`），在真实浏览器里跑真实 SSE 读取，
 *     验证 AT-014/AT-017 的"断线按 afterSeq 重连 + 按 seq 去重 + 窗口过期转快照"。
 *
 * 为什么不做成 Node 侧模拟：这两件事的判定对象都是**浏览器里的行为**（真实 fetch 流、
 * 真实 DOM 渲染、真实浏览器错误），Node 侧复现不了。
 */
import { createApp, h } from 'vue';

import { createAiChatClient } from '../../../../packages/ai-embed-sdk/src/index.ts';
import { parseMessageBlock } from '../../../../packages/ai-chat-ui/src/message/blocks.ts';
import ResultTable from '../../../../packages/ai-chat-ui/src/message/ResultTable.vue';

const RUN_KEY = 'run_4001';

let current = null;

function mountHost() {
  const host = document.querySelector('#probe-mount');
  if (host === null) {
    throw new Error('探针页缺少 #probe-mount');
  }
  return host;
}

/** 解析 + 渲染一个表格块；返回界面上的实际文本（标注、单元格、表头）。 */
function mountTable(input) {
  const host = mountHost();
  const parsed = parseMessageBlock(input);
  if (parsed.kind !== 'table') {
    throw new Error(`不是表格块：${parsed.kind}`);
  }
  if (current !== null) {
    current.unmount();
    current = null;
  }
  host.replaceChildren();
  current = createApp({ render: () => h(ResultTable, { block: parsed }) });
  current.mount(host);

  const caption = host.querySelector(
    '[data-testid="ai-message-table-completeness"]',
  );
  const page = host.querySelector('[data-testid="ai-message-table-page"]');
  return {
    caption: caption === null ? null : (caption.textContent ?? '').trim(),
    cells: [...host.querySelectorAll('tbody td')].map((cell) =>
      (cell.textContent ?? '').trim(),
    ),
    emptyNote:
      host.querySelector('[data-testid="ai-message-table-empty"]') === null
        ? null
        : '没有可显示的数据行',
    headers: [...host.querySelectorAll('thead th')].map((th) =>
      (th.textContent ?? '').trim(),
    ),
    page: page === null ? null : (page.textContent ?? '').trim(),
    rowCount: host.querySelectorAll('tbody tr').length,
    supported: true,
  };
}

function createClient(baseUrl) {
  return createAiChatClient({ baseUrl });
}

/**
 * 断线重连：第一次订阅被服务端提前关闭，第二次带 `afterSeq = lastSeq` 续读，
 * 服务端故意**重放**已发过的事件 —— 客户端必须按 seq 丢弃（不重复渲染）。
 */
async function reconnectStream(baseUrl) {
  const client = createClient(baseUrl);
  const delivered = [];
  const collect = (call) => (event) => {
    delivered.push({
      call,
      seq: event.seq,
      status: event.status,
      text: event.block?.kind === 'text' ? event.block.text : null,
    });
  };
  const first = await client.streamRunEvents(RUN_KEY, {
    onEvent: collect(1),
  });
  const second = await client.streamRunEvents(RUN_KEY, {
    afterSeq: first.lastSeq,
    onEvent: collect(2),
  });
  return {
    delivered,
    first: { lastSeq: first.lastSeq, reason: first.reason },
    second: { lastSeq: second.lastSeq, reason: second.reason },
  };
}

/** 重放窗口过期：客户端必须读取运行快照（`/ai/run/get`），而不是重新发起运行。 */
async function windowExpired(baseUrl) {
  const client = createClient(baseUrl);
  try {
    const result = await client.streamRunEvents(RUN_KEY, { onEvent: () => {} });
    return {
      error: null,
      reason: result.reason,
      snapshot: result.snapshot ?? null,
    };
  } catch (error) {
    return {
      error: {
        code: error?.code ?? null,
        message: error?.message ?? String(error),
        name: error?.name ?? null,
        status: error?.status ?? null,
      },
      reason: null,
      snapshot: null,
    };
  }
}

globalThis.__q07Probe = {
  mountTable,
  reconnectStream,
  windowExpired,
};

const status = document.querySelector('#probe-status');
if (status !== null) {
  status.textContent = 'q07-probe-ready';
}
