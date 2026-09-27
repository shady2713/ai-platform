import { describe, expect, it, vi } from 'vitest';

import {
  capabilityLabel,
  imageDimensionText,
  imageStatusLabel,
  isImageTaskActive,
  progressPercentOf,
  resolvePreviewUrl,
  usageText,
} from '../image-generation';

describe('图片生成/编辑展示口径', () => {
  it('状态与能力文案逐一对应，不混用同义词', () => {
    expect(imageStatusLabel('QUEUED')).toBe('排队中');
    expect(imageStatusLabel('RUNNING')).toBe('进行中');
    expect(imageStatusLabel('SUCCEEDED')).toBe('已完成');
    expect(imageStatusLabel('FAILED')).toBe('失败');
    expect(imageStatusLabel('CANCELLED')).toBe('已取消');
    expect(capabilityLabel('IMAGE_GENERATION')).toBe('图片生成');
    expect(capabilityLabel('IMAGE_EDIT')).toBe('图片编辑');
  });

  it('只有排队/进行中是非终态（终态不再给取消入口）', () => {
    expect(isImageTaskActive('QUEUED')).toBe(true);
    expect(isImageTaskActive('RUNNING')).toBe(true);
    expect(isImageTaskActive('SUCCEEDED')).toBe(false);
    expect(isImageTaskActive('FAILED')).toBe(false);
    expect(isImageTaskActive('CANCELLED')).toBe(false);
  });

  it('进度：后端没给就是 null（不编造百分比），越界值收敛到 0..100', () => {
    expect(progressPercentOf(null)).toBeNull();
    expect(progressPercentOf(undefined)).toBeNull();
    expect(progressPercentOf(Number.NaN)).toBeNull();
    expect(progressPercentOf(0)).toBe(0);
    expect(progressPercentOf(42.6)).toBe(43);
    expect(progressPercentOf(100)).toBe(100);
    expect(progressPercentOf(150)).toBe(100);
    expect(progressPercentOf(-5)).toBe(0);
  });

  it('尺寸：宽高都已知才显示，未知显示"尺寸未知"而不是 0×0', () => {
    expect(imageDimensionText(1024, 768)).toBe('1024 × 768');
    expect(imageDimensionText(null, 768)).toBe('尺寸未知');
    expect(imageDimensionText(1024, null)).toBe('尺寸未知');
    expect(imageDimensionText(null, null)).toBe('尺寸未知');
    expect(imageDimensionText(0, 0)).toBe('尺寸未知');
    expect(imageDimensionText(Number.NaN, 100)).toBe('尺寸未知');
  });

  it('用量：未知显示"用量未知"，有值按 unit 显示（0 是真实值，照显示）', () => {
    expect(usageText(null)).toBe('用量未知');
    expect(usageText({ quantity: null, unit: 'IMAGE' })).toBe('用量未知');
    expect(usageText({ quantity: Number.NaN, unit: 'IMAGE' })).toBe('用量未知');
    expect(usageText({ quantity: 3, unit: 'IMAGE' })).toBe('3 IMAGE');
    expect(usageText({ quantity: 2, unit: '  ' })).toBe('2');
    expect(usageText({ quantity: 0, unit: 'IMAGE' })).toBe('0 IMAGE');
  });
});

describe('预览地址受控解析', () => {
  it('解析成功返回平台端点地址', async () => {
    const resolve = vi.fn(
      async (fileId: number) => `/ai/file/${fileId}/content`,
    );
    await expect(
      resolvePreviewUrl(resolve, 7, () => false),
    ).resolves.toStrictEqual({ ok: true, url: '/ai/file/7/content' });
    expect(resolve).toHaveBeenCalledExactlyOnceWith(7);
  });

  it('解析不到地址算失败（不退化成一个猜出来的地址）', async () => {
    await expect(
      resolvePreviewUrl(
        async () => null,
        7,
        () => false,
      ),
    ).resolves.toStrictEqual({ ok: false, reason: 'FAILED' });
    await expect(
      resolvePreviewUrl(
        async () => '',
        7,
        () => false,
      ),
    ).resolves.toStrictEqual({ ok: false, reason: 'FAILED' });
  });

  it('宿主解析异常被吸收成失败结果，不向外抛出（不留未处理的 Promise）', async () => {
    await expect(
      resolvePreviewUrl(
        async () => {
          throw new Error('上游地址已过期');
        },
        7,
        () => false,
      ),
    ).resolves.toStrictEqual({ ok: false, reason: 'FAILED' });
  });

  it('组件已销毁：解析结果一律丢弃，不产生界面可用的地址', async () => {
    const resolve = vi.fn(async () => '/ai/file/7/content');
    await expect(
      resolvePreviewUrl(resolve, 7, () => true),
    ).resolves.toStrictEqual({ ok: false, reason: 'DISCARDED' });
    expect(resolve).toHaveBeenCalledExactlyOnceWith(7);
    await expect(
      resolvePreviewUrl(
        async () => {
          throw new Error('解析失败');
        },
        7,
        () => true,
      ),
    ).resolves.toStrictEqual({ ok: false, reason: 'DISCARDED' });
  });
});
