import type { DescriptionProps as ElDescriptionProps } from 'element-plus';
import type { JSX } from 'vue/jsx-runtime';

import type { CSSProperties, VNode } from 'vue';

import type { Recordable } from '@vben/types';

export interface DescriptionItemSchema {
  labelMinWidth?: number;
  contentMinWidth?: number;
  labelStyle?: CSSProperties;
  field: string;
  label: JSX.Element | string | VNode;
  span?: number;
  show?: (data?: Recordable<unknown>) => boolean;
  slot?: string;
  render?: (
    val: unknown,
    data?: Recordable<unknown>,
  ) => Element | JSX.Element | number | string | undefined | VNode;
}

export interface DescriptionProps extends ElDescriptionProps {
  schema: DescriptionItemSchema[];
  data: Recordable<unknown>;
}

export interface DescInstance {
  setDescProps(descProps: Partial<DescriptionProps>): void;
}
