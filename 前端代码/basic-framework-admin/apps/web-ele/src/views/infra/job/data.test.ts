import { describe, expect, it, vi } from 'vitest';

import {
  useDetailSchema,
  useFormSchema,
  useGridColumns,
  useGridFormSchema,
} from './data';

vi.mock('@vben/constants', () => ({
  DICT_TYPE: { INFRA_JOB_STATUS: 'infra_job_status' },
}));

vi.mock('@vben/hooks', () => ({ getDictOptions: vi.fn(() => []) }));

vi.mock('@vben/utils', () => ({
  formatDateTime: (value: Date) => value.toISOString(),
}));

vi.mock('element-plus', () => ({
  ElTimeline: 'ElTimeline',
  ElTimelineItem: 'ElTimelineItem',
}));

vi.mock('#/components/cron-tab', () => ({ CronTab: { name: 'CronTab' } }));
vi.mock('#/components/dict-tag', () => ({ DictTag: { name: 'DictTag' } }));

type ValuesPredicate = (values: Record<string, unknown>) => boolean;
type Renderer = (value: unknown) => unknown;

describe('infra job schemas', () => {
  it('protects handler identity after creation and defines complete grids', () => {
    const form = useFormSchema();
    const handler = form.find(({ fieldName }) => fieldName === 'handlerName');
    const disabled = handler?.dependencies?.disabled as ValuesPredicate;
    expect(disabled({})).toBe(false);
    expect(disabled({ id: 1 })).toBe(true);
    expect(
      form.find(({ fieldName }) => fieldName === 'retryCount'),
    ).toMatchObject({
      componentProps: { min: 0 },
      rules: 'required',
    });

    expect(useGridFormSchema().map(({ fieldName }) => fieldName)).toEqual([
      'name',
      'status',
      'handlerName',
    ]);
    expect(
      useGridColumns()?.some(({ field }) => field === 'cronExpression'),
    ).toBe(true);
  });

  it('renders timeout, retry and next-run edge cases explicitly', () => {
    const details = useDetailSchema();
    const render = (field: string, value: unknown) =>
      (details.find((item) => item.field === field)?.render as Renderer)(value);

    expect(render('retryInterval', 0)).toBe('无间隔');
    expect(render('retryInterval', 500)).toBe('500 毫秒');
    expect(render('monitorTimeout', null)).toBe('未开启');
    expect(render('monitorTimeout', 1000)).toBe('1000 毫秒');
    expect(render('nextTimes', [])).toBe('无后续执行时间');
    expect(render('nextTimes', 'invalid')).toBe('无后续执行时间');
    expect(render('status', 1)).toMatchObject({ props: { value: 1 } });
    expect(
      render('nextTimes', [new Date('2026-01-01T00:00:00Z')]),
    ).toMatchObject({ type: 'ElTimeline' });
  });
});
