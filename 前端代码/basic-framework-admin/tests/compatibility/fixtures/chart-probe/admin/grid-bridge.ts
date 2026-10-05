/**
 * `#/adapter/vxe-table` 的探针桥接。
 *
 * <p>三条纪律：
 * 1. `TableAction` 是**真实组件**（`apps/web-ele/src/components/table-action/table-action.vue`），
 *    不是替身。因为"某条动作要不要显示/能不能点"是 `actions.ts:23` 的 `isActionVisible`
 *    用 `auth` 权限码判定的安全语义——桥接自己实现一遍可见性，用例就成了自证。
 *    连带后果：真实组件只认 `dropDownActions` 这一个 prop 名，页面若传 `:drops`
 *    就会在真实浏览器里暴露出来（这正是本卡要钉住的东西）。
 * 2. 数据通路保持生产形状：桥接调用**页面自己**写在 `proxyConfig.ajax.query` 里的查询函数，
 *    该函数再去调 `#/api/ai/*`；被替换的只有 HTTP 传输（Q11 §4 排除真实后端联调）。
 *    因此"页面提交了什么查询事实"也是生产行为。
 * 3. 行内动作插槽 `action` 与 `actions` **两个都渲染**。真实 vxe-grid 只在列上声明了
 *    `slots.default` 时才会调用对应插槽；桥接不去替生产代码猜这个约定，
 *    免得把"页面没声明动作列"这类问题在探针里抹平。
 *
 * <p>【已知偏差，勿当"生产一定渲染"的证据】真实 vxe-grid 只在**声明了** `slots.default`
 * 的列里调用动作插槽。仓库里有 5 个页面在模板里写了 `#actions`/`#action` 却在
 * `data.ts` 里没有对应列（application / authorization / model-endpoint / service / semantic），
 * 线上这些行的动作按钮很可能根本不渲染。桥接为了让**页面自身的 auth 判定**能被真实
 * 组件执行，对没有声明动作列的页面**补了一列**兜底——所以本文件里 authorization、
 * model-endpoint 的动作断言只证明"生产 `actions.ts` 的权限判定是对的"，
 * **不能**证明"这些按钮在生产 grid 里会出现"。该结论属静态分析，见 Q11 证据 §5.2。
 */
import type { Component, Slots, VNode } from 'vue';

import { defineComponent, h, ref } from 'vue';

import TableAction from '../../../../../apps/web-ele/src/components/table-action/table-action.vue';
import { ACTION_ICON } from '../../../../../apps/web-ele/src/components/table-action/icons';
import { getState, recordApiCall, recordGridLoad } from './state';

export { ACTION_ICON, TableAction };
export type * from '@vben/plugins/vxe-table';

/** 桥接读取的列字段（只取渲染必需的三样，不复刻 vxe 的列类型）。 */
interface BridgeColumn {
  field?: string;
  formatter?: unknown;
  slots?: { default?: string };
  title?: string;
}

type GridColumn = BridgeColumn;

interface AjaxQueryContext {
  page: { currentPage: number; pageSize: number };
}

type AjaxQuery = (
  context: AjaxQueryContext,
  formValues: Record<string, unknown>,
) => Promise<{ list?: Array<Record<string, unknown>>; total?: number }>;

interface BridgeGridConfig {
  gridOptions: {
    columns?: GridColumn[];
    proxyConfig?: { ajax?: { query?: AjaxQuery } };
  };
  formOptions?: { schema?: unknown };
}

function readColumns(config: BridgeGridConfig): GridColumn[] {
  const columns = config.gridOptions.columns;
  return Array.isArray(columns) ? columns : [];
}

function cellText(column: GridColumn, row: Record<string, unknown>): string {
  const cellValue = column.field ? row[column.field] : undefined;
  if (typeof column.formatter === 'function') {
    // 真实 formatter 契约：({ cellValue, column, row }) => string
    const formatter = column.formatter as (params: {
      cellValue: unknown;
      column: unknown;
      row: Record<string, unknown>;
    }) => string;
    return String(formatter({ cellValue, column, row }));
  }
  if (cellValue === null || cellValue === undefined) {
    return '';
  }
  if (Array.isArray(cellValue)) {
    return cellValue.map((item) => String(item)).join('、');
  }
  return String(cellValue);
}

/**
 * 网格桥接。
 *
 * <p>挂载时按生产默认（`proxyConfig.autoLoad: true`）发起一次查询，
 * 页面自己的查询函数会把它翻译成 `PageParam` 调到被桥接的 API 上。
 */
export function useVbenVxeGrid(config: BridgeGridConfig): [Component, unknown] {
  const rows = ref<Array<Record<string, unknown>>>([]);
  const total = ref(0);
  const queried = ref(false);

  async function load() {
    const query = config.gridOptions.proxyConfig?.ajax?.query;
    if (!query) {
      return;
    }
    const result = await query({ page: { currentPage: 1, pageSize: 20 } }, {});
    rows.value = result?.list ?? [];
    total.value = result?.total ?? rows.value.length;
    queried.value = true;
    recordGridLoad();
  }

  const Grid = defineComponent({
    name: 'ProbeGrid',
    props: {
      tableTitle: { default: '', type: String },
    },
    setup(props, { slots }: { slots: Slots }) {
      void load();

      const actionSlotNames = ['action', 'actions'] as const;

      /** 页面自己在列上声明了动作插槽时，渲染进那一列，不另开一列。 */
      const declaredActionIndex = readColumns(config).findIndex((column) => {
        const slot = column.slots?.default;
        return (
          slot !== undefined &&
          actionSlotNames.includes(slot as (typeof actionSlotNames)[number])
        );
      });

      const renderActionSlots = (row: Record<string, unknown>): VNode[] => {
        const nodes: VNode[] = [];
        for (const name of actionSlotNames) {
          const scoped = slots[name];
          if (!scoped) {
            continue;
          }
          const rendered = scoped({ row });
          nodes.push(
            h(
              'div',
              { 'data-action-slot': name },
              Array.isArray(rendered) ? rendered : [rendered],
            ),
          );
        }
        return nodes;
      };

      const renderHead = (): VNode => {
        const columns = readColumns(config);
        const headers = columns.map((column) =>
          h('th', { scope: 'col' }, String(column.title ?? column.field ?? '')),
        );
        if (declaredActionIndex < 0) {
          headers.push(h('th', { scope: 'col' }, '操作'));
        }
        return h('tr', headers);
      };

      const renderRow = (row: Record<string, unknown>): VNode => {
        const cells = readColumns(config).map((column, index) =>
          h(
            'td',
            { 'data-field': column.field ?? '' },
            index === declaredActionIndex
              ? renderActionSlots(row)
              : cellText(column, row),
          ),
        );
        if (declaredActionIndex < 0) {
          cells.push(
            h('td', { 'data-field': '__actions' }, renderActionSlots(row)),
          );
        }
        return h('tr', { 'data-row-id': String(row.id ?? '') }, cells);
      };

      return () =>
        h('section', { 'data-testid': 'probe-page' }, [
          h('h1', { 'data-testid': 'probe-table-title' }, props.tableTitle),
          h('div', { 'data-testid': 'probe-grid-toolbar' }, [
            slots['toolbar-tools']?.({}),
          ]),
          h('table', { 'data-testid': 'probe-grid' }, [
            h('thead', [renderHead()]),
            h(
              'tbody',
              queried.value ? rows.value.map((row) => renderRow(row)) : [],
            ),
          ]),
          h('p', { 'data-testid': 'probe-grid-total' }, `共 ${total.value} 条`),
        ]);
    },
  });

  const gridApi = {
    query: () => {
      recordApiCall('grid.query', { pageNo: 1, pageSize: 20 });
      return load();
    },
  };

  return [Grid, gridApi];
}
