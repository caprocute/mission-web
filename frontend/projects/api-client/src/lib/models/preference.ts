import type { components } from '../generated/config';

type S = components['schemas'];

/** Một khoá tuỳ chọn cá nhân đã lưu (`config.user_preference`, CNH-02). */
export type UserPreferenceItem = S['UserPreferenceItem'];

/** Mọi tuỳ chọn đã lưu của người dùng, theo khoá. */
export type UserPreferenceMap = S['UserPreferenceMap'];

/** Bố cục trang chủ đã ghép lớp phủ cá nhân (`getHomeLayout`). */
export type HomeLayout = S['HomeLayout'];

/** Nút SDUI của bố cục trang chủ. */
export type HomeLayoutNode = S['SduiNode'];

/** Khoá tuỳ chọn được phép (khớp `PrefKeyPath` của `config.yaml`). */
export type PrefKey =
  | 'ui.display'
  | 'ui.home.layout'
  | 'ui.dashboard.default'
  | 'notify.channel_pref'
  | `ui.table.SCR-${string}`;

/** Vị trí một widget trong lớp phủ trang chủ (`UiHomeWidgetPlacement`). */
export interface HomeWidgetPlacement {
  id: string;
  order: number;
  colSpan?: 4 | 6 | 8 | 12;
}

/** Lớp phủ trang chủ (`UiHomeLayoutOverlay` của user-preference.schema.json). */
export interface HomeLayoutOverlay {
  baseUiPackVersion?: string;
  pinnedWidgets?: string[];
  hiddenWidgets?: string[];
  widgetOrder?: HomeWidgetPlacement[];
  collapsedSections?: string[];
}

/** Bảng điều khiển mặc định (`UiDashboardDefaultPreference`). */
export interface DashboardDefaultPreference {
  defaultDashboardId?: string;
  pinnedKpiCodes?: string[];
  defaultPeriodType?: 'THANG' | 'QUY' | 'NAM';
}
