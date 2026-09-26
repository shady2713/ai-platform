import type { ResultBlock } from '@vben/ai-contracts';

/** 会话测试共用的结果块夹具（冻结 v1：文本/图表）。 */
export const chartBlock: ResultBlock = {
  kind: 'chart',
  spec: {
    categories: ['一月', '二月'],
    series: [{ data: [120, 200], name: '销售额' }],
    title: '月度销售额',
    type: 'bar',
  },
};

export const textBlock: ResultBlock = {
  kind: 'text',
  text: '华东前十如下',
};
