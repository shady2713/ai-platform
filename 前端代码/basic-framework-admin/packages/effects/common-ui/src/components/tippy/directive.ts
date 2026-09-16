import type { Instance, Placement } from 'tippy.js';

import type { ComputedRef, Directive, VNode } from 'vue';
import type { TippyOptions } from 'vue-tippy';

import { useTippy } from 'vue-tippy';

const TIPPY_PLACEMENTS = new Set<Placement>([
  'auto',
  'auto-end',
  'auto-start',
  'bottom',
  'bottom-end',
  'bottom-start',
  'left',
  'left-end',
  'left-start',
  'right',
  'right-end',
  'right-start',
  'top',
  'top-end',
  'top-start',
]);

type TippyDirectiveValue = null | string | TippyOptions | undefined;
type TippyController = Pick<
  ReturnType<typeof useTippy>,
  'destroy' | 'setProps'
>;

const tippyControllers = new WeakMap<HTMLElement, TippyController>();

function isPlacement(value: string): value is Placement {
  return TIPPY_PLACEMENTS.has(value as Placement);
}

export function normalizeTippyOptions(
  value: unknown,
  theme: string,
): TippyOptions {
  if (value === null || value === undefined) {
    return { theme };
  }
  if (typeof value === 'string') {
    return { content: value, theme };
  }
  if (typeof value === 'object' && !Array.isArray(value)) {
    return { theme, ...value } as TippyOptions;
  }
  throw new TypeError('v-tippy binding must be a string or an options object');
}

function applyModifiers(
  options: TippyOptions,
  modifiers: Partial<Record<string, boolean>>,
) {
  const placement = Object.keys(modifiers).find((value) => isPlacement(value));
  if (placement && options.placement === undefined) {
    options.placement = placement;
  }
  if (modifiers.arrow && options.arrow === undefined) {
    options.arrow = true;
  }
}

function invokeVNodeHandler(handler: unknown, instance: Instance): unknown {
  if (typeof handler === 'function') {
    return handler(instance);
  }
  if (Array.isArray(handler)) {
    let result: unknown;
    for (const candidate of handler) {
      if (typeof candidate === 'function' && candidate(instance) === false) {
        result = false;
      }
    }
    return result;
  }
  return undefined;
}

function hasVNodeHandler(vnode: VNode, name: string): boolean {
  const handler = vnode.props?.[name];
  return (
    typeof handler === 'function' ||
    (Array.isArray(handler) &&
      handler.some((item) => typeof item === 'function'))
  );
}

function applyLifecycleHooks(options: TippyOptions, vnode: VNode) {
  if (hasVNodeHandler(vnode, 'onTippyShow')) {
    options.onShow = (instance) =>
      invokeVNodeHandler(vnode.props?.onTippyShow, instance) === false
        ? false
        : undefined;
  }
  if (hasVNodeHandler(vnode, 'onTippyShown')) {
    options.onShown = (instance) => {
      invokeVNodeHandler(vnode.props?.onTippyShown, instance);
    };
  }
  if (hasVNodeHandler(vnode, 'onTippyHidden')) {
    options.onHidden = (instance) => {
      invokeVNodeHandler(vnode.props?.onTippyHidden, instance);
    };
  }
  if (hasVNodeHandler(vnode, 'onTippyHide')) {
    options.onHide = (instance) =>
      invokeVNodeHandler(vnode.props?.onTippyHide, instance) === false
        ? false
        : undefined;
  }
  if (hasVNodeHandler(vnode, 'onTippyMount')) {
    options.onMount = (instance) => {
      invokeVNodeHandler(vnode.props?.onTippyMount, instance);
    };
  }
}

function applyAttributeContent(element: HTMLElement, options: TippyOptions) {
  const title = element.getAttribute('title');
  if (title) {
    if (options.content === undefined) {
      options.content = title;
    }
    element.removeAttribute('title');
  }
  const content = element.getAttribute('content');
  if (content && options.content === undefined) {
    options.content = content;
  }
}

export default function useTippyDirective(
  isDark: ComputedRef<boolean>,
): Directive<HTMLElement, TippyDirectiveValue> {
  return {
    mounted(element, binding, vnode) {
      const options = normalizeTippyOptions(
        binding.value,
        isDark.value ? '' : 'light',
      );
      applyModifiers(options, binding.modifiers);
      applyLifecycleHooks(options, vnode);
      applyAttributeContent(element, options);
      tippyControllers.set(element, useTippy(element, options));
    },
    unmounted(element) {
      tippyControllers.get(element)?.destroy();
      tippyControllers.delete(element);
    },
    updated(element, binding) {
      const options = normalizeTippyOptions(
        binding.value,
        isDark.value ? '' : 'light',
      );
      applyAttributeContent(element, options);
      tippyControllers.get(element)?.setProps(options);
    },
  };
}
