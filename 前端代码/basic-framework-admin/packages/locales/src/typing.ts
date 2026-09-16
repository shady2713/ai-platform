import type { LocaleMessageValue } from 'vue-i18n';

export type SupportedLanguagesType = 'en-US' | 'zh-CN';

export type LocaleMessageMap = Record<string, LocaleMessageValue>;

export type ImportLocaleFn = () => Promise<{ default: LocaleMessageMap }>;

export type LoadMessageFn = (
  lang: SupportedLanguagesType,
) => Promise<LocaleMessageMap | undefined>;

export interface LocaleSetupOptions {
  /**
   * Default language
   * @default zh-CN
   */
  defaultLocale?: SupportedLanguagesType;
  /**
   * Load message function
   * @param lang
   * @returns
   */
  loadMessages?: LoadMessageFn;
  /**
   * Whether to warn when the key is not found
   */
  missingWarn?: boolean;
}
