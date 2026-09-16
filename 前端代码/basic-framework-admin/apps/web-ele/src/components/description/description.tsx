import type { CSSProperties, PropType, Slot, Slots } from 'vue';

import type { DescriptionItemSchema, DescriptionProps } from './typing';

import { computed, defineComponent, unref, useAttrs } from 'vue';

import { get, getNestedValue, isFunction, logWarn } from '@vben/utils';

import { ElDescriptions, ElDescriptionsItem } from 'element-plus';

const props = {
  border: { default: true, type: Boolean },
  column: {
    default: () => {
      return { lg: 3, md: 3, sm: 2, xl: 3, xs: 1, xxl: 4 };
    },
    type: [Number, Object],
  },
  data: { type: Object },
  schema: {
    default: () => [],
    type: Array as PropType<DescriptionItemSchema[]>,
  },
  size: {
    default: 'default',
    type: String,
    validator: (v: string) =>
      ['default', 'middle', 'small', undefined].includes(v),
  },
  title: { default: '', type: String },
  direction: { default: 'horizontal', type: String },
};

function getSlot(slots: Slots, slot: string, data?: Record<string, unknown>) {
  if (!Reflect.has(slots, slot)) {
    return null;
  }
  if (!isFunction(slots[slot])) {
    logWarn('description:slot', `${slot} is not a function!`);
    return null;
  }
  const slotFn = slots[slot];
  if (!slotFn) return null;
  return slotFn({ data });
}

export default defineComponent({
  name: 'SchemaDescription',
  props,
  setup(props, { slots }) {
    const prefixCls = 'description';
    const attrs = useAttrs();

    const getProps = computed(() => {
      const opt = {
        ...props,
      };
      return opt as DescriptionProps;
    });

    const getDescriptionsProps = computed(() => {
      const elementProps = { ...unref(attrs), ...unref(getProps) };
      Reflect.deleteProperty(elementProps, 'data');
      Reflect.deleteProperty(elementProps, 'schema');
      return elementProps;
    });

    function renderLabel({
      label,
      labelMinWidth,
      labelStyle,
    }: DescriptionItemSchema) {
      if (!labelStyle && !labelMinWidth) {
        return label;
      }

      const labelStyles: CSSProperties = {
        ...labelStyle,
        ...(labelMinWidth === undefined
          ? {}
          : { minWidth: `${labelMinWidth}px` }),
      };
      return <div style={labelStyles}>{label}</div>;
    }

    function renderItem() {
      const { data, schema } = unref(getProps);
      return unref(schema)
        .map((item) => {
          const { contentMinWidth, field, render, show, span } = item;

          if (show && isFunction(show) && !show(data)) {
            return null;
          }

          function getContent() {
            const _data = unref(getProps)?.data;
            if (!_data) {
              return null;
            }
            const getField = field.includes('.')
              ? (getNestedValue(_data, field) ?? get(_data, field))
              : get(_data, field);
            return isFunction(render)
              ? render(getField, _data)
              : (getField ?? '');
          }

          const width = contentMinWidth;
          return (
            <ElDescriptionsItem key={field} span={span}>
              {{
                label: () => {
                  return renderLabel(item);
                },
                default: () => {
                  if (item.slot) {
                    return getSlot(slots, item.slot, data);
                  }
                  if (!contentMinWidth) {
                    return getContent();
                  }
                  const style: CSSProperties = {
                    minWidth: `${width}px`,
                  };
                  return <div style={style}>{getContent()}</div>;
                },
              }}
            </ElDescriptionsItem>
          );
        })
        .filter((item) => !!item);
    }

    function renderDesc() {
      const extraSlot = getSlot(slots, 'extra');
      const slotsObj: Record<string, Slot> = {
        default: () => renderItem(),
      };
      if (extraSlot) {
        slotsObj.extra = () => extraSlot;
      }
      return (
        <ElDescriptions
          class={`${prefixCls}`}
          title={unref(getProps).title}
          {...unref(getDescriptionsProps)}
        >
          {slotsObj}
        </ElDescriptions>
      );
    }

    return () => renderDesc();
  },
});
