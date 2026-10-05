/**
 * `@vben/common-ui` 的探针桥接。
 *
 * <p>只桥接"三样东西"，其余一律用真实组件：
 *  - `Page`：**真实** `packages/effects/common-ui/src/components/page/page.vue`（无应用外壳依赖）；
 *  - `z`：**真实** zod（`data.ts` 的 `rules: z.string().regex(...)` 真的会被求值，
 *    用替身会让"表单校验文案"变成自证）。zod 的实际路径由 `global-setup.mjs` 用
 *    `createRequire` 从 `@vben-core/form-ui` 现场解析后以 alias 注入，此处只按包名引入；
 *  - `useVbenModal`：桥接。真实实现依赖 popup-ui 的 teleport 层与应用级注入，
 *    弹窗可见性不是本卡的安全语义（Q11 §4 排除需要真实凭据的联调）。
 *
 * <p>为什么不整体 alias 到假 `@vben/common-ui`：真实包 `index.ts` 会
 * `export * from './icon-picker'`，而图标选择器**运行期主动拉取**
 * `api.iconify.design/collection`（Q06 §4.b 已登记）。桥接避免探针发出任何外部请求。
 */
import type { Component } from 'vue';

import { defineComponent, h, reactive } from 'vue';

import Page from '../../../../../packages/effects/common-ui/src/components/page/page.vue';
import { getState } from './state';

export { Page };

/** 真实 zod：`data.ts` 的表单规则依赖它求值。 */
export * as z from 'zod';

interface ModalOptions {
  connectedComponent?: Component;
  destroyOnClose?: boolean;
  onConfirm?: () => Promise<void>;
  onOpenChange?: (isOpen: boolean) => void;
}

interface ModalApi {
  close: () => void;
  getData: <T>() => T | undefined;
  lock: () => void;
  open: () => ModalApi;
  setData: (data: unknown) => ModalApi;
  setState: (state: Record<string, unknown>) => void;
  unlock: () => void;
}

/** 按注册顺序排列的弹窗实例；供探针按序号打开（Q11 不覆盖弹窗内流程，但需要时能打开）。 */
const modalApis: ModalApi[] = [];

export function getModalApis(): ModalApi[] {
  return modalApis;
}

/**
 * 弹窗桥接：容器默认**不渲染** `connectedComponent`，只有显式 `open()` 才挂载。
 *
 * <p>这一点是刻意的：AI 页面的弹窗子组件（`modules/versions.vue` 等）会继续拉起
 * 判定/发布流程，浏览器验收不覆盖它们（无后端）。默认不挂载保证用例失败时
 * 原因一定来自列表页本身，而不是弹窗子组件的缺依赖。
 */
export function useVbenModal(
  options: ModalOptions = {},
): [Component, ModalApi] {
  const view = reactive({
    data: undefined as unknown,
    locked: false,
    open: false,
    state: {} as Record<string, unknown>,
    title: '',
  });

  const api: ModalApi = {
    close: () => {
      view.open = false;
      getState().modalOpen = false;
      options.onOpenChange?.(false);
    },
    getData: <T>() => view.data as T | undefined,
    lock: () => {
      view.locked = true;
    },
    open: () => {
      view.open = true;
      getState().modalOpen = true;
      options.onOpenChange?.(true);
      return api;
    },
    setData: (data: unknown) => {
      view.data = data;
      return api;
    },
    setState: (state: Record<string, unknown>) => {
      view.state = { ...view.state, ...state };
      view.title = String(state.title ?? view.title);
    },
    unlock: () => {
      view.locked = false;
    },
  };

  const Container = defineComponent({
    name: 'ProbeModal',
    setup(_props, { attrs, slots }) {
      return () => {
        const children: unknown[] = [];
        if (view.title) {
          children.push(
            h('h2', { 'data-testid': 'probe-modal-title' }, view.title),
          );
        }
        if (view.open) {
          // 默认插槽与 connectedComponent 一样只在打开时渲染：真实弹窗未打开时表单不在 DOM 里，
          // 若这里无条件渲染，"只读字段不可编辑"的断言就建立在错误的结构前提上。
          if (options.connectedComponent) {
            children.push(
              h(options.connectedComponent, {
                ...attrs,
                'data-testid': 'probe-modal-body',
              }),
            );
          }
          children.push(slots.default?.());
        }
        return h(
          'section',
          {
            'data-locked': String(view.locked),
            'data-open': String(view.open),
            'data-testid': 'probe-modal',
          },
          children,
        );
      };
    },
  });

  if (options.onConfirm) {
    // 真实 `useVbenModal` 把 onConfirm 挂在确认按钮上；桥接挂到 window 供 Playwright 触发。
    Object.assign(globalThis, {
      __q11ConfirmModal: async () => {
        await options.onConfirm?.();
      },
    });
  }

  modalApis.push(api);
  return [Container, api];
}
