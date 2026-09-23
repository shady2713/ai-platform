import type { ChartSpec } from '@vben/ai-contracts';

import { onBeforeUnmount, ref, shallowRef, watch } from 'vue';

import { toVendorOptions } from './chartOptions';
import { fallbackReason } from './fallback';
import { specToRows } from './specToRows';
import { themeTokens } from './theme';

/**
 * 图表实例生命周期（R02）：懒加载、resize、主题、destroy。
 *
 * 四条约束（对应卡片第 3 步与 AT-055）：
 *  1. **懒加载**：`@antv/g2` 用动态 import 拉取，首屏不为图表付出体积；
 *  2. **resize**：用 ResizeObserver 跟随容器尺寸（窄屏/侧栏折叠），卸载时断开；
 *  3. **主题**：主题变化重新渲染，令牌来自 `theme.ts`；
 *  4. **destroy 幂等且彻底**：卸载、规格变化、主题变化都先销毁旧实例；
 *     对外只暴露 resize/destroy，宿主拿不到厂商实例（也就无法"漏掉销毁"）。
 */
export interface ChartInstanceHandle {
  destroy(): void;
  resize(): void;
}

export function useChartInstance(
  container: () => HTMLDivElement | undefined,
  spec: () => ChartSpec,
  theme: () => string | undefined,
) {
  const vendor = shallowRef<{
    changeSize(w: number, h: number): void;
    destroy(): void;
    options(o: unknown): void;
    render(): void;
  }>();
  const loading = ref(false);
  const failed = ref(false);
  let observer: ResizeObserver | undefined;
  let disposed = false;

  async function render(): Promise<void> {
    destroy();
    const element = container();
    const currentSpec = spec();
    if (!element || currentSpec.categories.length === 0) {
      return;
    }
    if (fallbackReason(currentSpec, specToRows(currentSpec)) !== null) {
      // 降级为表格：不加载厂商代码
      return;
    }
    loading.value = true;
    try {
      const { Chart } = await import('@antv/g2');
      if (disposed) {
        // 加载期间组件已卸载：不创建实例
        return;
      }
      const instance = new Chart({ autoFit: true, container: element });
      // 厂商选项在适配层内构造，这里按 unknown 传入（不把厂商类型扩散到调用方）
      (instance.options as (options: unknown) => void)(
        toVendorOptions(
          currentSpec,
          specToRows(currentSpec),
          themeTokens(theme()),
        ),
      );
      instance.render();
      vendor.value = instance as unknown as typeof vendor.value;
      observe(element);
    } catch {
      // 懒加载失败（离线/被裁剪）：降级为表格，而不是白屏
      failed.value = true;
    } finally {
      loading.value = false;
    }
  }

  function observe(element: HTMLDivElement): void {
    if (typeof ResizeObserver === 'undefined') {
      return;
    }
    observer = new ResizeObserver(() => resize());
    observer.observe(element);
  }

  function resize(): void {
    const element = container();
    if (!vendor.value || !element) {
      return;
    }
    vendor.value.changeSize(element.clientWidth, element.clientHeight);
  }

  function destroy(): void {
    observer?.disconnect();
    observer = undefined;
    vendor.value?.destroy();
    vendor.value = undefined;
  }

  onBeforeUnmount(() => {
    disposed = true;
    destroy();
  });

  watch([spec, theme], () => {
    void render();
  });

  return { destroy, failed, loading, render, resize };
}
