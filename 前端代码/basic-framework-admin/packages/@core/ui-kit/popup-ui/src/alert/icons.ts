import type { Component, VNode } from 'vue';

import type { IconType } from './alert';

import { h } from 'vue';

import {
  CircleAlert,
  CircleCheckBig,
  CircleHelp,
  CircleX,
  Info,
} from '@vben-core/icons';

export function resolveAlertIcon(
  icon: Component | IconType | undefined,
): Component | null | VNode {
  if (icon === undefined) return null;
  if (typeof icon !== 'string') return icon;

  switch (icon) {
    case 'error': {
      return h(CircleX, {
        style: { color: 'hsl(var(--destructive))' },
      });
    }
    case 'info': {
      return h(Info, { style: { color: 'hsl(var(--info))' } });
    }
    case 'question': {
      return CircleHelp;
    }
    case 'success': {
      return h(CircleCheckBig, {
        style: { color: 'hsl(var(--success))' },
      });
    }
    case 'warning': {
      return h(CircleAlert, {
        style: { color: 'hsl(var(--warning))' },
      });
    }
    default: {
      throw new TypeError(`Unsupported alert icon: ${icon}`);
    }
  }
}
