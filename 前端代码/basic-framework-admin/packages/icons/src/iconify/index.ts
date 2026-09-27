import { addIcon, createIconifyIcon } from '@vben-core/icons';

export * from '@vben-core/icons';

/**
 * 离线注册的 Lucide 图标（本地数据，运行期不请求任何图标 API）。
 *
 * 背景（AT-067 禁公共 CDN）：`@iconify/vue` 对**未在本地注册**的字符串图标名
 * （如 `lucide:refresh-ccw`）会在渲染时回退请求
 * `https://api.iconify.design/<prefix>.json?icons=...`；在禁公网部署下既产生
 * 外发请求，又渲染不出图标。
 *
 * `@vben-core/icons` 已重导出的 Lucide 组件优先直接使用；这里补齐 common-ui
 * 仍需、但上游未重导出的图标：按 Iconify 数据格式就地 `addIcon` 注册后，
 * 再用 `createIconifyIcon` 暴露为组件，用法与重导出的 Lucide 组件一致。
 *
 * 图标路径数据取自 lucide-vue-next v0.553.0（ISC），与该版本重导出的
 * Lucide 组件保持同一套画法。
 */
const LUCIDE_STROKE =
  'fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"';

addIcon('lucide:chevron-up', {
  body: `<path ${LUCIDE_STROKE} d="m18 15-6-6-6 6"/>`,
  height: 24,
  width: 24,
});

addIcon('lucide:refresh-ccw', {
  body: `<g ${LUCIDE_STROKE}><path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"/><path d="M16 16h5v5"/></g>`,
  height: 24,
  width: 24,
});

addIcon('lucide:trending-down', {
  body: `<g ${LUCIDE_STROKE}><path d="M16 17h6v-6"/><path d="m22 17-8.5-8.5-5 5L2 7"/></g>`,
  height: 24,
  width: 24,
});

addIcon('lucide:trending-up', {
  body: `<g ${LUCIDE_STROKE}><path d="M16 7h6v6"/><path d="m22 7-8.5 8.5-5-5L2 17"/></g>`,
  height: 24,
  width: 24,
});

export const ChevronUp = createIconifyIcon('lucide:chevron-up');

export const RefreshCcw = createIconifyIcon('lucide:refresh-ccw');

export const TrendingDown = createIconifyIcon('lucide:trending-down');

export const TrendingUp = createIconifyIcon('lucide:trending-up');

export const MdiKeyboardEsc = createIconifyIcon('mdi:keyboard-esc');

export const MdiGoogle = createIconifyIcon('mdi:google');

export const MdiCheckboxMarkedCircleOutline = createIconifyIcon(
  'mdi:checkbox-marked-circle-outline',
);

export const MsRefresh = createIconifyIcon('material-symbols:refresh-rounded');

export const TMinimize = createIconifyIcon('tabler:arrows-minimize');

export const AntdProfileOutlined = createIconifyIcon(
  'ant-design:profile-outlined',
);

export const FolderOpenOutlined = createIconifyIcon(
  'ant-design:folder-open-outlined',
);

export const DownloadOutlined = createIconifyIcon(
  'ant-design:download-outlined',
);

export const EyeOutlined = createIconifyIcon('ant-design:eye-outlined');

export const ApiOutlined = createIconifyIcon('ant-design:api-outlined');

export const ZoomOutOutlined = createIconifyIcon(
  'ant-design:zoom-out-outlined',
);

export const ZoomInOutlined = createIconifyIcon('ant-design:zoom-in-outlined');

export const UndoOutlined = createIconifyIcon('ant-design:undo-outlined');

export const RedoOutlined = createIconifyIcon('ant-design:redo-outlined');

export const ReloadOutlined = createIconifyIcon('ant-design:reload-outlined');

export const AlignLeftOutlined = createIconifyIcon(
  'ant-design:align-left-outlined',
);

export const WarningOutlined = createIconifyIcon('ant-design:warning-outlined');

export const MenuOutlined = createIconifyIcon('ant-design:menu-outlined');

export const PlusOutlined = createIconifyIcon('ant-design:plus-outlined');

export const CloseCircleFilled = createIconifyIcon(
  'ant-design:close-circle-filled',
);

export const SelectOutlined = createIconifyIcon('ant-design:select-outlined');
