import type {
  ContentCompactType,
  LayoutHeaderModeType,
  LayoutType,
  ThemeModeType,
} from '@vben-core/typings';

interface VbenLayoutProps {
  /**
   * 鍐呭鍖哄煙瀹氬
   * @default 'wide'
   */
  contentCompact?: ContentCompactType;
  /**
   * 瀹氬甯冨眬瀹藉害
   * @default 1200
   */
  contentCompactWidth?: number;
  /**
   * padding
   * @default 16
   */
  contentPadding?: number;
  /**
   * paddingBottom
   * @default 16
   */
  contentPaddingBottom?: number;
  /**
   * paddingLeft
   * @default 16
   */
  contentPaddingLeft?: number;
  /**
   * paddingRight
   * @default 16
   */
  contentPaddingRight?: number;
  /**
   * paddingTop
   * @default 16
   */
  contentPaddingTop?: number;
  /**
   * footer 鏄惁鍙
   * @default false
   */
  footerEnable?: boolean;
  /**
   * footer 鏄惁鍥哄畾
   * @default true
   */
  footerFixed?: boolean;
  /**
   * footer 楂樺害
   * @default 32
   */
  footerHeight?: number;

  /**
   * header楂樺害
   * @default 48
   */
  headerHeight?: number;
  /**
   * 椤舵爮鏄惁闅愯棌
   * @default false
   */
  headerHidden?: boolean;
  /**
   * header 鏄剧ず妯″紡
   * @default 'fixed'
   */
  headerMode?: LayoutHeaderModeType;
  /**
   * header 椤舵爮涓婚
   */
  headerTheme?: ThemeModeType;
  /**
   * 鏄惁鏄剧ずheader鍒囨崲渚ц竟鏍忔寜閽?   * @default
   */
  headerToggleSidebarButton?: boolean;
  /**
   * header鏄惁鏄剧ず
   * @default true
   */
  headerVisible?: boolean;
  /**
   * 鏄惁绉诲姩绔樉绀?   * @default false
   */
  isMobile?: boolean;
  /**
   * 甯冨眬鏂瑰紡
   * sidebar-nav 渚ц竟鑿滃崟甯冨眬
   * header-nav 椤堕儴鑿滃崟甯冨眬
   * mixed-nav 渚ц竟&椤堕儴鑿滃崟甯冨眬
   * sidebar-mixed-nav 渚ц竟娣峰悎鑿滃崟甯冨眬
   * full-content 鍏ㄥ睆鍐呭甯冨眬
   * @default sidebar-nav
   */
  layout?: LayoutType;
  /**
   * 渚ц竟鑿滃崟鎶樺彔鐘舵€?   * @default false
   */
  sidebarCollapse?: boolean;
  /**
   * 渚ц竟鑿滃崟鎶樺彔鎸夐挳
   * @default true
   */
  sidebarCollapsedButton?: boolean;
  /**
   * 渚ц竟鑿滃崟鏄惁鎶樺彔鏃讹紝鏄惁鏄剧ずtitle
   * @default true
   */
  sidebarCollapseShowTitle?: boolean;
  /**
   * 渚ц竟鏍忔槸鍚﹀彲瑙?   * @default true
   */
  sidebarEnable?: boolean;
  /**
   * 渚ц竟鑿滃崟鎶樺彔棰濆瀹藉害
   * @default 48
   */
  sidebarExtraCollapsedWidth?: number;
  /**
   * 渚ц竟鑿滃崟鎶樺彔鎸夐挳鏄惁鍥哄畾
   * @default true
   */
  sidebarFixedButton?: boolean;
  /**
   * 渚ц竟鏍忔槸鍚﹂殣钘?   * @default false
   */
  sidebarHidden?: boolean;
  /**
   * 娣峰悎渚ц竟鏍忓搴?   * @default 80
   */
  sidebarMixedWidth?: number;
  /**
   * 渚ц竟鏍?   * @default dark
   */
  sidebarTheme?: ThemeModeType;
  /**
   * 渚ц竟鏍忓搴?   * @default 210
   */
  sidebarWidth?: number;
  /**
   *  渚ц竟鑿滃崟鎶樺彔瀹藉害
   * @default 48
   */
  sideCollapseWidth?: number;
  /**
   * tab鏄惁鍙
   * @default true
   */
  tabbarEnable?: boolean;
  /**
   * tab楂樺害
   * @default 30
   */
  tabbarHeight?: number;
  /**
   * zIndex
   * @default 100
   */
  zIndex?: number;
}
export type { VbenLayoutProps };
