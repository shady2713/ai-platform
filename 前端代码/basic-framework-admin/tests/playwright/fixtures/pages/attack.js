/**
 * Q06 未授权第三方页夹具。
 *
 * 它模拟"页面里另一个 frame / 未授权站点"能做的事：向自己的 parent 发任意报文，
 * 并记录自己收到了什么（用来断言宿主**没有**把票据/INIT 发给它）。
 *
 * 注意：它只能 `parent.postMessage`，无法伪造 `event.source`——
 * 这正是浏览器安全模型的边界，也正是 AT-051 要区分"错误 origin"与"伪 source"的原因：
 *  - 不同 Origin 的 frame ⇒ 宿主收到 origin 不匹配；
 *  - 同 Origin 但非注册 frame ⇒ 宿主收到 source 不匹配。
 */
const observed = { received: [] };

globalThis.addEventListener('message', (event) => {
  const data = event.data;
  const type = data !== null && typeof data === 'object' ? data.type : null;
  observed.received.push({
    origin: event.origin,
    type: typeof type === 'string' ? type : null,
  });
});

globalThis.__q06Attack = {
  /**
   * 向 parent 发一条报文。默认 `'*'`（攻击者的典型写法）；
   * 用例也可以指定精确 targetOrigin 来证明"即使知道宿主 Origin 也照样被拒"。
   */
  send(payload, targetOrigin = '*') {
    globalThis.parent.postMessage(payload, targetOrigin);
    return true;
  },
  /** 直接向宿主后端要票据（跨源 POST）：应当被宿主后端的 Origin 白名单拒绝。 */
  async fetchTicket(url, body) {
    const response = await fetch(url, {
      body: JSON.stringify(body ?? {}),
      headers: { 'content-type': 'application/json' },
      method: 'POST',
    });
    return { status: response.status, text: await response.text() };
  },
  received: () => [...observed.received],
  reset() {
    observed.received = [];
    return true;
  },
};

const status = document.querySelector('#attack-status');
if (status !== null) status.textContent = 'attack-fixture-ready';
