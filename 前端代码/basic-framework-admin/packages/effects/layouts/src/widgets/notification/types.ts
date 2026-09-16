import type { HistoryState, LocationQueryRaw } from 'vue-router';

interface NotificationItem {
  id: number;
  avatar: string;
  date: string;
  isRead?: boolean;
  message: string;
  title: string;
  /**
   * 跳转链接，只允许以 / 开头的站内绝对路径或无账号信息的 HTTPS URL
   * @example '/dashboard' 或 'https://example.com'
   */
  link?: string;
  query?: LocationQueryRaw;
  state?: HistoryState;
}

export type { NotificationItem };
