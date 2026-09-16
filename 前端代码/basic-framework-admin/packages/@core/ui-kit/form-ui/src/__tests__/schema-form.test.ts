import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import SchemaForm from '../schema-form.vue';
import { injectFormProps } from '../use-form-context';

/**
 * Form 使用真实组件时依赖 reka-ui 等渲染管线，这里用同名 stub 替换，
 * stub 内部通过 injectFormProps 读取提供者上下文，以断言 props 与 form 实例。
 */
const FormStub = defineComponent({
  name: 'FormStub',
  setup(_, { attrs, slots }) {
    const context = injectFormProps();
    return () =>
      h('form', { ...attrs, id: 'form-stub' }, [
        h('span', { 'data-has-form': String(Boolean(context?.[1])) }),
        slots.default?.({}),
        ...Object.entries(slots)
          .filter(([name]) => name !== 'default')
          .map(([name, fn]) => h('div', { key: name }, fn?.({}))),
      ]);
  },
});

const FormActionsStub = defineComponent({
  name: 'FormActionsStub',
  props: { modelValue: { type: Boolean, default: false } },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () =>
      h(
        'button',
        {
          id: 'toggle-actions',
          onClick: () => emit('update:modelValue', !props.modelValue),
        },
        'toggle',
      );
  },
});

function mountSchemaForm(props = {}, slots = {}) {
  return mount(SchemaForm, {
    global: {
      stubs: {
        Form: FormStub,
        FormActions: FormActionsStub,
      },
    },
    props,
    slots,
  });
}

describe('schemaForm', () => {
  it('provides the form instance to the Form renderer', () => {
    const wrapper = mountSchemaForm();
    expect(wrapper.find('#form-stub').exists()).toBe(true);
    expect(wrapper.find('[data-has-form]').attributes('data-has-form')).toBe(
      'true',
    );
  });

  it('renders the default actions fallback', () => {
    const wrapper = mountSchemaForm();
    expect(wrapper.find('#toggle-actions').exists()).toBe(true);
  });

  it('uses the passed default slot instead of the fallback actions', () => {
    const wrapper = mountSchemaForm({}, { default: 'custom-default' });
    expect(wrapper.find('#toggle-actions').exists()).toBe(false);
    expect(wrapper.text()).toContain('custom-default');
  });

  it('notifies handleCollapsedChange when the actions toggle collapses', async () => {
    const handleCollapsedChange = vi.fn();
    const wrapper = mountSchemaForm({ handleCollapsedChange });
    await wrapper.get('#toggle-actions').trigger('click');
    expect(handleCollapsedChange).toHaveBeenCalledWith(true);
    await wrapper.get('#toggle-actions').trigger('click');
    expect(handleCollapsedChange).toHaveBeenCalledWith(false);
  });

  it('initializes the collapsed state from the collapsed prop', async () => {
    const handleCollapsedChange = vi.fn();
    const wrapper = mountSchemaForm({ collapsed: true, handleCollapsedChange });
    expect(wrapper.get('#form-stub').attributes('collapsed')).toBe('true');
    await wrapper.get('#toggle-actions').trigger('click');
    expect(handleCollapsedChange).toHaveBeenCalledWith(false);
    expect(wrapper.get('#form-stub').attributes('collapsed')).toBe('false');
  });

  it('forwards custom named slots to the Form renderer', () => {
    const wrapper = mountSchemaForm({}, { extra: 'extra-slot-content' });
    expect(wrapper.text()).toContain('extra-slot-content');
  });
});
