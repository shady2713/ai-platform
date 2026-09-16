import type { VNode } from 'vue';

import type { AlertProps, BeforeCloseScope, PromptProps } from './alert';

import { h, nextTick, ref, render } from 'vue';

import { useSimpleLocale } from '@vben-core/composables';
import { Input, VbenRenderContent } from '@vben-core/shadcn-ui';
import { isFunction, isString } from '@vben-core/shared/utils';

import Alert from './alert.vue';

interface AlertRegistration {
  cancel: () => void;
  container: HTMLElement;
}

type AlertInvocationProps = AlertProps & {
  onOpened?: () => Promise<void> | void;
};

type AlertComponentProps = AlertInvocationProps & {
  onClosed: () => void;
  onConfirm: () => void;
  open: boolean;
};

const alerts = new Set<AlertRegistration>();

const { $t } = useSimpleLocale();

export function vbenAlert(options: AlertProps): Promise<void>;
export function vbenAlert(
  message: string,
  options?: Partial<AlertProps>,
): Promise<void>;
export function vbenAlert(
  message: string,
  title?: string,
  options?: Partial<AlertProps>,
): Promise<void>;

export function vbenAlert(
  arg0: AlertProps | string,
  arg1?: Partial<AlertProps> | string,
  arg2?: Partial<AlertProps>,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const options: AlertProps = isString(arg0)
      ? {
          content: arg0,
        }
      : { ...arg0 };
    if (arg1) {
      if (isString(arg1)) {
        options.title = arg1;
      } else if (!isString(arg1)) {
        // 如果第二个参数是对象，则合并到选项中
        Object.assign(options, arg1);
      }
    }

    if (arg2 && !isString(arg2)) {
      Object.assign(options, arg2);
    }
    const container = document.createElement('div');
    document.body.append(container);

    let isConfirmed = false;
    let isSettled = false;
    let registration: AlertRegistration;

    function dispose() {
      alerts.delete(registration);
      render(null, container);
      container.remove();
    }

    function doResolve() {
      if (isSettled) {
        return;
      }
      isSettled = true;
      isConfirmed = true;
      dispose();
      resolve();
    }

    function doReject() {
      if (isSettled) {
        return;
      }
      isSettled = true;
      dispose();
      reject(new Error('dialog cancelled'));
    }

    registration = { cancel: doReject, container };

    const props: AlertComponentProps = {
      onConfirm: doResolve,
      onClosed: () => {
        if (isConfirmed) {
          dispose();
        } else {
          doReject();
        }
      },
      ...options,
      open: true,
      title: options.title ?? $t.value('prompt'),
    };

    const vnode = h(Alert, props);
    alerts.add(registration);
    try {
      render(vnode, container);
    } catch (error) {
      alerts.delete(registration);
      container.remove();
      reject(error);
    }
  });
}

export function vbenConfirm(options: AlertProps): Promise<void>;
export function vbenConfirm(
  message: string,
  options?: Partial<AlertProps>,
): Promise<void>;
export function vbenConfirm(
  message: string,
  title?: string,
  options?: Partial<AlertProps>,
): Promise<void>;

export function vbenConfirm(
  arg0: AlertProps | string,
  arg1?: Partial<AlertProps> | string,
  arg2?: Partial<AlertProps>,
): Promise<void> {
  const defaultProps: Partial<AlertProps> = {
    showCancel: true,
  };
  if (!arg1) {
    return isString(arg0)
      ? vbenAlert(arg0, defaultProps)
      : vbenAlert({ ...defaultProps, ...arg0 });
  } else if (!arg2) {
    return isString(arg1)
      ? vbenAlert(arg0 as string, arg1, defaultProps)
      : vbenAlert(arg0 as string, { ...defaultProps, ...arg1 });
  }
  return vbenAlert(arg0 as string, arg1 as string, {
    ...defaultProps,
    ...arg2,
  });
}

export async function vbenPrompt<T = unknown>(
  options: PromptProps<T>,
): Promise<T | undefined> {
  const {
    component: _component,
    componentProps: _componentProps,
    componentSlots,
    content,
    defaultValue,
    modelPropName: _modelPropName,
    ...delegated
  } = options;

  const modelValue = ref<T | undefined>(defaultValue);
  const inputComponentRef = ref<null | VNode>(null);
  const staticContents: VNode[] = [
    h(VbenRenderContent, { content, renderBr: true }),
  ];

  const modelPropName = _modelPropName || 'modelValue';
  const componentProps = { ..._componentProps };

  const contentRenderer = () => {
    const currentProps = {
      ...componentProps,
      [modelPropName]: modelValue.value,
      [`onUpdate:${modelPropName}`]: (val: T) => {
        modelValue.value = val;
      },
    };

    inputComponentRef.value = h(
      _component || Input,
      currentProps,
      componentSlots,
    );

    return h('div', { class: 'flex flex-col gap-2' }, [
      ...staticContents,
      inputComponentRef.value,
    ]);
  };

  const props: AlertInvocationProps = {
    ...delegated,
    async beforeClose(scope: BeforeCloseScope) {
      if (delegated.beforeClose) {
        return await delegated.beforeClose({
          ...scope,
          value: modelValue.value,
        });
      }
    },
    content: contentRenderer,
    contentMasking: true,
    async onOpened() {
      await nextTick();
      const componentRef: null | VNode = inputComponentRef.value;
      if (componentRef) {
        const exposed = componentRef.component?.exposed as null | {
          focus?: unknown;
        };
        if (isFunction(exposed?.focus)) {
          exposed.focus();
          return;
        }
        const element = componentRef.el;
        if (element instanceof HTMLElement) {
          const focusable = element.matches('button, input, select, textarea')
            ? element
            : element.querySelector<HTMLElement>(
                'input, select, textarea, button',
              );
          const sibling = element.nextElementSibling;
          (
            focusable ?? (sibling instanceof HTMLElement ? sibling : null)
          )?.focus();
        }
      }
    },
  };

  await vbenConfirm(props);
  return modelValue.value;
}

export function clearAllAlerts() {
  [...alerts].forEach(({ cancel }) => cancel());
}
