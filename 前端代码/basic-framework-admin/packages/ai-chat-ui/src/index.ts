export { default as AiChatPanel } from './components/AiChatPanel.vue';
export type { ChatMessage } from './components/AiChatPanel.vue';
export { default as ChartRenderer } from './components/ChartRenderer.vue';
export { default as AiReportView } from './report/AiReportView.vue';
export {
  buildChartSpec,
  cellText,
  chartSeries,
  containsScriptMarker,
  formatMetric,
  parseJsonText,
  parseReportData,
  parseReportSpec,
  safeParseReportData,
  safeParseReportSpec,
} from './report/reportSpec';
export type {
  ReportBlock,
  ReportData,
  ReportResultColumn,
  ReportSpec,
} from './report/reportSpec';
