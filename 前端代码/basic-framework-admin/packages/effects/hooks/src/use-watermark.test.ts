import { createApp, defineComponent, h } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import { useWatermark } from './use-watermark';

const testState = vi.hoisted(() => {
  const create = vi.fn().mockResolvedValue(undefined);
  const changeOptions = vi.fn().mockResolvedValue(undefined);
  const destroy = vi.fn();
  const instances: unknown[] = [];

  class Watermark {
    changeOptions = changeOptions;
    create = create;
    destroy = destroy;

    constructor(options: unknown) {
      instances.push(options);
    }
  }

  return { Watermark, changeOptions, create, destroy, instances };
});

vi.mock('watermark-js-plus', () => ({
  Watermark: testState.Watermark,
}));

describe('useWatermark', () => {
  it('创建、更新并在卸载时清理同一个水印实例', async () => {
    let watermarkApi: ReturnType<typeof useWatermark> | undefined;
    const TestComponent = defineComponent({
      setup() {
        watermarkApi = useWatermark();
        return () => h('div');
      },
    });
    const host = document.createElement('div');
    const app = createApp(TestComponent);
    app.mount(host);
    if (!watermarkApi) {
      throw new Error('水印 API 未初始化');
    }

    await watermarkApi.updateWatermark({ content: '管理员' });
    expect(testState.instances).toEqual([
      expect.objectContaining({
        content: '管理员',
        contentType: 'multi-line-text',
        globalAlpha: 0.25,
      }),
    ]);
    expect(testState.create).toHaveBeenCalledOnce();

    await watermarkApi.updateWatermark({ content: '审计用户' });
    expect(testState.instances).toHaveLength(1);
    expect(testState.changeOptions).toHaveBeenCalledWith(
      expect.objectContaining({ content: '审计用户' }),
    );

    watermarkApi.destroyWatermark();
    watermarkApi.destroyWatermark();
    expect(testState.destroy).toHaveBeenCalledOnce();

    app.unmount();
    host.remove();
    expect(testState.destroy).toHaveBeenCalledOnce();
  });
});
