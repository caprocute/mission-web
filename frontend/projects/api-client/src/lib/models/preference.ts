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

/** Bộ lọc đã lưu của một màn hình danh sách (`config.saved_filter`, CNH-02). */
export type SavedFilter = S['SavedFilter'];

/** Nội dung tạo hoặc sửa bộ lọc đã lưu. */
export type SavedFilterInput = S['SavedFilterInput'];

/** Khoảng ngày của một điều kiện lọc. */
export interface CriteriaDateRange {
  from?: string;
  to?: string;
  relativePreset?: 'TODAY' | 'THIS_WEEK' | 'THIS_MONTH' | 'THIS_QUARTER' | 'THIS_YEAR' | 'OVERDUE';
}

/**
 * Tiêu chí bộ lọc đã lưu (`SavedFilterCriteria` của user-preference.schema.json): chỉ mã, mã định
 * danh, khoảng ngày, khoảng số và cờ; không có văn bản tìm kiếm tự do (tránh dữ liệu C2).
 */
export interface SavedFilterCriteria {
  statusCodes?: string[];
  ratingCodes?: string[];
  orgUnitIds?: string[];
  periodIds?: string[];
  assigneeScope?: 'ME' | 'MY_UNIT' | 'SUBORDINATE_UNITS' | 'ALL_ALLOWED';
  dateRanges?: Record<string, CriteriaDateRange>;
  numberRanges?: Record<string, { min?: number; max?: number }>;
  flags?: Record<string, boolean>;
  sort?: { field: string; direction: 'ASC' | 'DESC' };
}

/** Tuỳ chọn trình bày bảng của một màn hình (`UiTableScreenPreference`), khoá `ui.table.SCR-xxxx`. */
export interface TableScreenPreference {
  visibleColumns?: string[];
  columnOrder?: string[];
  columnWidths?: Record<string, number>;
  pageSize?: 10 | 20 | 50 | 100 | 200;
  defaultSort?: { field: string; direction: 'ASC' | 'DESC' };
  defaultSavedFilterId?: string;
}
