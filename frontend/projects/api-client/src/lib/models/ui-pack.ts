import type { components } from '../generated/config';

type S = components['schemas'];

/** Trạng thái phiên bản UI Pack (`config.ui_pack_version.status`, QT-18). */
export type UiPackVersionStatus = S['UiPackVersionStatus'];
/** Một UI Pack của tenant kèm phiên bản đang dùng và bản đang soạn. */
export type UiPackSummary = S['UiPackSummary'];
export type UiPackPage = S['UiPackPage'];
/** Một phiên bản UI Pack: token, bố cục, cờ chế độ cao tuổi (`config.ui_pack_version`). */
export type UiPackVersion = S['UiPackVersion'];
export type UiPackVersionPage = S['UiPackVersionPage'];
export type UiPackVersionCreate = S['UiPackVersionCreate'];
export type UiPackVersionUpdate = S['UiPackVersionUpdate'];
export type UiPackPreview = S['UiPackPreview'];
export type UiPackCheck = S['UiPackCheck'];
export type UiPackFormAttach = S['UiPackFormAttach'];
export type UiSduiNode = S['SduiNode'];
/** Component SDUI được phép trong danh mục có phiên bản (`config.component_registry`). */
export type UiComponent = S['UiComponent'];
export type UiComponentPage = S['UiComponentPage'];
export type UiFormSummary = S['UiFormSummary'];
export type UiFormPage = S['UiFormPage'];
export type UiFormVersionUpdate = S['UiFormVersionUpdate'];
export type UiFormVersionCreate = S['UiFormVersionCreate'];
export type UiFormTestCaseInput = S['UiFormTestCaseInput'];
export type UiFormTestRunRequest = S['UiFormTestRunRequest'];
export type UiFormTestRunResult = S['UiFormTestRunResult'];

export const UI_PACK_STATUS_LABEL: Record<UiPackVersionStatus, string> = {
  NHAP: 'Bản nháp',
  DANG_KIEM_TRA: 'Đang kiểm tra',
  CHO_DUYET: 'Chờ duyệt',
  DA_PHAT_HANH: 'Đang áp dụng',
  THAY_THE: 'Đã thay thế',
};

export const UI_FORM_STATUS_LABEL: Record<S['UiFormStatus'], string> = {
  NHAP: 'Bản nháp',
  DA_PHAT_HANH: 'Đã phát hành',
  NGUNG: 'Ngừng sử dụng',
};

/** Tên hiển thị của widget trang chủ theo khoá trong danh mục; khoá lạ hiện "Thành phần khác". */
export const UI_WIDGET_LABEL: Record<string, string> = {
  'vt.widget.myTasksToday': 'Việc của tôi hôm nay',
  'vt.widget.kpiTile': 'Ô chỉ số',
  'vt.widget.krCheckinDue': 'Kết quả then chốt cần cập nhật',
  'vt.widget.periodMilestones': 'Mốc của kỳ đánh giá',
  'vt.widget.weeklySummaryAi': 'Tóm tắt tuần',
  'vt.widget.okrProgress': 'Tiến độ mục tiêu',
  'vt.widget.pendingApprovals': 'Việc chờ duyệt',
  'vt.widget.announcement': 'Thông báo của đơn vị',
  'yte.widget.chuyenMonKpi': 'Chỉ số chuyên môn y tế',
};

export const uiWidgetLabel = (key: string) => UI_WIDGET_LABEL[key] ?? 'Thành phần khác';

/** Phiên bản ngữ nghĩa `1.8.0` hiển thị gọn `1.8`. */
export const uiVersionLabel = (v: string | null | undefined) =>
  v ? v.replace(/^(\d+\.\d+)\.0$/, '$1') : '—';
