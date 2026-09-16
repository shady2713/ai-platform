import type { App, SetupContext, VNode } from 'vue';

import { isVNode } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { initTippy, Tippy } from './index';

const mocks = vi.hoisted(() => ({
  dark: false,
  directive: { mounted: vi.fn() },
  setDefaultProps: vi.fn(),
  useTippyDirective: vi.fn(),
}));

vi.mock('@vben-core/preferences', () => ({
  usePreferences: () => ({
    isDark: {
      get value() {
        return mocks.dark;
      },
    },
  }),
}));

vi.mock('vue-tippy', () => ({
  setDefaultProps: mocks.setDefaultProps,
  Tippy: 'mock-tippy',
}));

vi.mock('./directive', () => ({
  default: mocks.useTippyDirective,
}));

function renderTippy(theme?: string): VNode {
  const result = Tippy({ content: 'help' }, {
    attrs: theme === undefined ? {} : { theme },
    emit: vi.fn(),
    expose: vi.fn(),
    slots: {},
  } as SetupContext);
  expect(isVNode(result)).toBe(true);
  return result as VNode;
}

describe('tippy integration', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.dark = false;
    mocks.useTippyDirective.mockReturnValue(mocks.directive);
  });

  it('registers reactive light defaults and the directive', () => {
    const app = { directive: vi.fn() } as unknown as App<Element>;

    initTippy(app);

    expect(mocks.setDefaultProps).toHaveBeenNthCalledWith(1, {
      allowHTML: true,
      delay: [500, 200],
      theme: 'light',
    });
    expect(mocks.setDefaultProps).toHaveBeenNthCalledWith(2, {
      theme: 'light',
    });
    expect(mocks.useTippyDirective).toHaveBeenCalledOnce();
    expect(app.directive).toHaveBeenCalledWith('tippy', mocks.directive);
  });

  it('respects an explicit theme without installing a theme watcher', () => {
    const app = { directive: vi.fn() } as unknown as App<Element>;
    mocks.dark = true;

    initTippy(app, { theme: 'custom' });

    expect(mocks.setDefaultProps).toHaveBeenCalledOnce();
    expect(mocks.setDefaultProps).toHaveBeenCalledWith({
      allowHTML: true,
      delay: [500, 200],
      theme: 'custom',
    });
  });

  it('normalizes automatic and dark component themes', () => {
    expect(renderTippy().props).toMatchObject({
      content: 'help',
      theme: 'light',
    });

    mocks.dark = true;
    expect(renderTippy('auto').props).toMatchObject({ theme: '' });
    expect(renderTippy('dark').props).toMatchObject({ theme: '' });
    expect(renderTippy('light').props).toMatchObject({ theme: 'light' });
  });
});
