/**
 * `#/adapter/form` 的探针桥接。
 *
 * <p>渲染的是**页面自己的** `useFormSchema()` / `useGridFormSchema()` 产物，
 * 桥接只负责把 schema 变成真实 DOM 控件（`<input>`/`<select>`/`<textarea>`），
 * 好让"只读字段不可编辑"成为可断言的结构事实。
 *
 * <p>两种"只读"要分清，断言也分开写：
 *  - **隐藏**：`dependencies.show` 返回 `false`（如乐观锁 `version`、隐藏主键 `id`）→ 不渲染；
 *  - **禁用**：`componentProps.disabled === true` → 渲染但带 `disabled`，不可输入。
 * 生产 vben 表单两者都不可编辑，但结构事实不同，混断言会掩盖回归。
 */
import type { Component } from 'vue';

import { defineComponent, h, reactive } from 'vue';

interface SchemaRule {
  message?: string;
  required?: boolean;
  type?: string;
}

interface FormSchemaItem {
  component?: string;
  componentProps?: Record<string, unknown>;
  dependencies?: { show?: () => boolean };
  fieldName?: string;
  label?: string;
  rules?: SchemaRule | string;
}

interface BridgeFormOptions {
  schema?: FormSchemaItem[];
}

interface FormApi {
  getValues: () => Promise<Record<string, unknown>>;
  resetForm: () => void;
  setState: (state: Record<string, unknown>) => void;
  setValues: (values: Record<string, unknown>) => void;
  validate: () => Promise<{ valid: boolean }>;
}

function isHidden(item: FormSchemaItem): boolean {
  const show = item.dependencies?.show;
  return typeof show === 'function' ? !show() : false;
}

function isDisabled(item: FormSchemaItem): boolean {
  return item.componentProps?.disabled === true;
}

function requiredMark(item: FormSchemaItem): string {
  if (item.rules === 'required') {
    return '*';
  }
  return item.rules && typeof item.rules === 'object' && item.rules.required
    ? '*'
    : '';
}

function renderControl(item: FormSchemaItem): ReturnType<typeof h> {
  const field = item.fieldName ?? '';
  const component = String(item.component ?? 'Input');
  const disabled = isDisabled(item);
  const placeholder = String(item.componentProps?.placeholder ?? '');
  const maxlength = item.componentProps?.maxlength;

  if (component === 'Select') {
    const options = item.componentProps?.options;
    return h(
      'select',
      {
        'data-field': field,
        'data-required': requiredMark(item),
        disabled,
        name: field,
      },
      Array.isArray(options)
        ? options.map((option) => {
            const entry = option as { label?: unknown; value?: unknown };
            return h(
              'option',
              { value: String(entry.value) },
              String(entry.label),
            );
          })
        : [],
    );
  }

  if (component === 'Textarea') {
    return h('textarea', {
      'data-field': field,
      'data-required': requiredMark(item),
      disabled,
      name: field,
      placeholder,
    });
  }

  return h('input', {
    'data-field': field,
    'data-required': requiredMark(item),
    disabled,
    maxlength,
    name: field,
    placeholder,
    type: component === 'Switch' ? 'checkbox' : 'text',
  });
}

export function useVbenForm(
  options: BridgeFormOptions = {},
): [Component, FormApi] {
  const values = reactive<Record<string, unknown>>({});

  const Form = defineComponent({
    name: 'ProbeForm',
    setup() {
      return () =>
        h(
          'form',
          { 'data-testid': 'probe-form' },
          (options.schema ?? [])
            .filter((item) => !isHidden(item))
            .map((item) =>
              h('label', { 'data-label-for': item.fieldName ?? '' }, [
                h(
                  'span',
                  {},
                  `${item.label ?? item.fieldName ?? ''}${requiredMark(item)}`,
                ),
                renderControl(item),
              ]),
            ),
        );
    },
  });

  const api: FormApi = {
    getValues: async () => ({ ...values }),
    resetForm: () => {
      for (const key of Object.keys(values)) {
        delete values[key];
      }
    },
    setState: () => undefined,
    setValues: (next: Record<string, unknown>) => {
      Object.assign(values, next);
    },
    validate: async () => ({ valid: true }),
  };

  return [Form, api];
}
