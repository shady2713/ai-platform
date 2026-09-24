<script setup lang="ts">
/**
 * 表格块渲染（C03）：类型化单元格，取值**原样展示**。
 *
 * <p>为什么不做本地化再格式化：协议允许金额以十进制字符串传输（`docs/contracts/ai/README.md` 的金额精度规则），
 * 一旦在这里过一遍 `Number()` 再 `toLocaleString`，就把"精确文本"降级成"浮点近似显示"。
 * 所以单元格只做取值与空值处理（空值显示 `—`，不补 0），格式化留给报表渲染层。
 */
import type { TableBlock } from './blocks';

import { cellDisplay } from './blocks';

defineProps<{ block: TableBlock }>();
</script>

<template>
  <figure class="ai-result-table">
    <table data-testid="ai-message-table">
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
    <p v-if="block.rows.length === 0" data-testid="ai-message-table-empty">
      没有可显示的数据行
    </p>
    <figcaption
      v-if="block.completeness && block.completeness !== 'COMPLETE'"
      data-testid="ai-message-table-completeness"
    >
      数据完整性：{{ block.completeness }}（结果可能不完整）
    </figcaption>
    <figcaption v-if="block.pageInfo" data-testid="ai-message-table-page">
      第 {{ block.pageInfo.page }} 页 · 每页 {{ block.pageInfo.size }} 条 · 共
      {{ block.pageInfo.total }} 条
    </figcaption>
  </figure>
</template>
