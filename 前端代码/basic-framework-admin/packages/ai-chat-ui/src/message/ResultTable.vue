<script setup lang="ts">
/**
 * 表格块渲染（C03）：类型化单元格，取值**原样展示**。
 *
 * <p>为什么不做本地化再格式化：协议允许金额以十进制字符串传输（`docs/contracts/ai/README.md` 的金额精度规则），
 * 一旦在这里过一遍 `Number()` 再 `toLocaleString`，就把"精确文本"降级成"浮点近似显示"。
 * 所以单元格只做取值与空值处理（空值显示 `—`，不补 0），格式化留给报表渲染层。
 *
 * <p>Y05 的授权维度：跨源结果被授权拒绝时（`WITHHELD`），本组件**一个数字都不渲染**——
 * 表格、分页条数、来源计数全部不出现。理由是行数本身就是信道：把表格清空却保留
 * `pageInfo.total`，用户拿它减去自己已知的那部分就能反推被禁来源的规模。
 */
import type { TableBlock } from './blocks';

import { cellDisplay } from './blocks';
import { integrityNotice, rendersNothing } from './cross-source-integrity';

const props = defineProps<{ block: TableBlock }>();

const withheld = rendersNothing(props.block.crossSourceIntegrity);
</script>

<template>
  <figure class="ai-result-table">
    <p
      v-if="withheld"
      data-testid="ai-message-table-withheld"
      class="ai-result-table-withheld"
    >
      {{ integrityNotice(block.crossSourceIntegrity) }}
    </p>
    <table v-else data-testid="ai-message-table">
      <thead>
        <tr>
          <th
            v-for="column in block.columns"
            :key="column.field"
            :data-field="column.field"
            scope="col"
          >
            {{
              column.unit ? `${column.label}（${column.unit}）` : column.label
            }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, index) in block.rows" :key="index">
          <td
            v-for="column in block.columns"
            :key="column.field"
            :data-field="column.field"
          >
            {{ cellDisplay(row[column.field]) }}
          </td>
        </tr>
      </tbody>
    </table>
    <p
      v-if="!withheld && block.rows.length === 0"
      data-testid="ai-message-table-empty"
    >
      没有可显示的数据行
    </p>
    <figcaption
      v-if="
        !withheld && block.completeness && block.completeness !== 'COMPLETE'
      "
      data-testid="ai-message-table-completeness"
    >
      数据完整性：{{ block.completeness }}（结果可能不完整）
    </figcaption>
    <figcaption
      v-if="!withheld && block.crossSourceIntegrity"
      data-testid="ai-message-table-cross-source"
    >
      {{ integrityNotice(block.crossSourceIntegrity) }}
    </figcaption>
    <figcaption
      v-if="!withheld && block.pageInfo"
      data-testid="ai-message-table-page"
    >
      第 {{ block.pageInfo.page }} 页 · 每页 {{ block.pageInfo.size }} 条 · 共
      {{ block.pageInfo.total }} 条
    </figcaption>
  </figure>
</template>
