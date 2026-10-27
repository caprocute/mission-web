import type { components } from '../generated/platform';

/**
 * Phía đọc của SVC-15 analytics (`/api/v1/analytics`, QT-16): truy vấn chỉ số chuẩn trên lớp ngữ nghĩa,
 * dashboard tự dựng (`config.dashboard_def`), chuỗi truy vết từ điểm tới minh chứng; cùng tóm tắt tuần của
 * SVC-14 (`ai.suggestion_log`, kind `TOM_TAT_TUAN`). Bí danh của lược đồ trong `platform.yaml` (WEB-047,
 * WEB-048). Tên chỉ số, chiều là định danh của lớp ngữ nghĩa, chỉ dùng trong mã; giao diện hiện `title`
 * máy chủ trả về (YC-PT-10).
 */
type S = components['schemas'];

export type AnalyticsFilter = S['AnalyticsFilter'];
/** Thân truy vấn; `compareToPreviousPeriod`, `limit` có mặc định ở máy chủ nên không bắt buộc gửi. */
export type AnalyticsQuery = Omit<S['AnalyticsQuery'], 'compareToPreviousPeriod' | 'limit'> &
  Partial<Pick<S['AnalyticsQuery'], 'compareToPreviousPeriod' | 'limit'>>;
export type AnalyticsColumn = S['AnalyticsColumn'];
export type AnalyticsQueryResult = S['AnalyticsQueryResult'];
export type AnalyticsCell = AnalyticsQueryResult['rows'][number][number];
export type SemanticModel = S['SemanticModel'];
export type SemanticMeasure = SemanticModel['measures'][number];
export type SemanticDimension = SemanticModel['dimensions'][number];
export type DashboardChartType = S['DashboardChartType'];
export type DashboardShareScope = S['DashboardShareScope'];
export type DashboardWidget = S['DashboardWidget'];
export type AnalyticsDashboardInput = S['AnalyticsDashboardInput'];
export type AnalyticsDashboardSummary = S['AnalyticsDashboardSummary'];
export type AnalyticsDashboardPage = S['AnalyticsDashboardPage'];
export type AnalyticsDashboard = S['AnalyticsDashboard'];
export type DashboardWidgetData = NonNullable<
  NonNullable<AnalyticsDashboard['data']>['widgets']
>[number];
export type DashboardShareRequest = S['DashboardShareRequest'];
export type AnalyticsReportRequest = S['AnalyticsReportRequest'];
export type TraceObjectType = S['TraceObjectType'];
export type TraceLink = S['TraceLink'];
export type TraceChain = S['TraceChain'];
export type TraceExportRequest = S['TraceExportRequest'];
export type AiSuggestion = S['AiSuggestion'];
export type AiSuggestionPage = S['AiSuggestionPage'];
export type AiSource = S['AiSource'];
export type AiSourceList = S['AiSourceList'];

/**
 * Chỉ số, chiều mà các trang dùng trực tiếp (SCR-1601, SCR-1603), đúng tên lớp ngữ nghĩa Cube
 * `analytics/cube/model` phiên bản 2026.4. Tên hợp đồng `platform.yaml` có nêu (`org_unit.name`,
 * `period.label`, `subject_group.code`, `flow.code`, `appraisal.phan_bo_muc`) thì Cube công bố đúng tên đó;
 * điểm KPI trung bình nằm ở cube `score` (hợp đồng ví dụ `appraisal.diem_kpi_tb`, wiki/14). Kiểm tĩnh
 * `analytics/cube/tests/test_mo_hinh.py` đối chiếu các hằng số này với mô hình Cube.
 */
export const MEASURE = {
  tienDoO: 'okr.tien_do_o',
  dungHan: 'task.ty_le_dung_han',
  viecCham: 'task.so_viec_cham',
  choThamDinh: 'appraisal.cho_tham_dinh',
  diemKpi: 'score.diem_kpi_trung_binh',
  soNguoiCoDiem: 'score.so_nguoi',
  phanBoMuc: 'appraisal.phan_bo_muc',
} as const;

export const DIMENSION = {
  unitId: 'org_unit.id',
  unitName: 'org_unit.name',
  period: 'period.label',
  flow: 'flow.code',
  subjectGroup: 'subject_group.code',
  subjectGroupName: 'subject_group.ten',
  rating: 'appraisal.muc_xep_loai',
} as const;

/** Hậu tố cột so sánh kỳ trước khi `compareToPreviousPeriod` (ví dụ của hợp đồng: `okr.tien_do_o.previous`). */
export const PREVIOUS_SUFFIX = '.previous';

/** SCR-1602 trường 1, 3, 5: tên ≤ 100 ký tự, tối đa 20 widget, mỗi widget tối đa 2 chiều. */
export const DASHBOARD_LIMITS = { titleMax: 100, maxWidgets: 20, maxDimensions: 2 } as const;

export const CHART_TYPES: readonly DashboardChartType[] = [
  'THE_SO',
  'COT',
  'DUONG',
  'THANH',
  'BANG',
];

export const CHART_TYPE_LABEL: Record<DashboardChartType, string> = {
  THE_SO: 'Thẻ số',
  COT: 'Biểu đồ cột',
  DUONG: 'Biểu đồ đường',
  THANH: 'Biểu đồ thanh',
  BANG: 'Bảng số liệu',
};

export const SHARE_SCOPE_LABEL: Record<DashboardShareScope, string> = {
  RIENG: 'Chỉ mình tôi',
  DON_VI: 'Các đơn vị được chọn',
  TENANT: 'Toàn cơ quan, tổ chức',
};

export const TRACE_OBJECT_LABEL: Record<TraceObjectType, string> = {
  DIEM: 'Điểm đánh giá',
  CAU_PHAN: 'Cấu phần điểm',
  KPI_NHIEM_VU: 'KPI nhiệm vụ',
  KET_QUA_THEN_CHOT: 'Kết quả then chốt',
  SAN_PHAM: 'Sản phẩm',
  MINH_CHUNG: 'Minh chứng',
  KE_HOACH: 'Kế hoạch',
  MUC_TIEU: 'Mục tiêu',
  CAN_CU: 'Căn cứ',
};

/** Đối tượng trong câu MSG-16-06 ("không có quyền xem chi tiết {doi_tuong} thuộc {don_vi}"). */
export const TRACE_OBJECT_NOUN: Record<TraceObjectType, string> = {
  DIEM: 'điểm đánh giá',
  CAU_PHAN: 'cấu phần điểm',
  KPI_NHIEM_VU: 'KPI nhiệm vụ',
  KET_QUA_THEN_CHOT: 'kết quả then chốt',
  SAN_PHAM: 'sản phẩm',
  MINH_CHUNG: 'minh chứng',
  KE_HOACH: 'kế hoạch',
  MUC_TIEU: 'mục tiêu',
  CAN_CU: 'căn cứ',
};

/** Nhãn trạng thái chuẩn (baseline mục 9.1) của các mắt xích chuỗi truy vết. */
export const TRACE_STATUS_LABEL: Record<string, string> = {
  NHAP: 'Nháp',
  CHO_NGHIEM_THU: 'Chờ nghiệm thu',
  YEU_CAU_BO_SUNG: 'Yêu cầu bổ sung',
  DA_XAC_NHAN: 'Đã xác nhận',
  TU_CHOI: 'Từ chối',
  CHO_DUYET: 'Chờ duyệt',
  DA_DUYET: 'Đã duyệt',
  HIEU_LUC: 'Đang hiệu lực',
  DA_KHOA: 'Đã khoá',
  HOAN_THANH: 'Hoàn thành',
};

/** Một dòng kết quả truy vấn dạng đối tượng, khoá là tên cột (`name`). */
export type AnalyticsRow = Record<string, AnalyticsCell>;

export function resultRows(r: Pick<AnalyticsQueryResult, 'columns' | 'rows'>): AnalyticsRow[] {
  return r.rows.map((row) => Object.fromEntries(r.columns.map((c, i) => [c.name, row[i] ?? null])));
}

/** Giá trị số của một ô (null khi trống hoặc không phải số). */
export function cellNumber(v: AnalyticsCell | undefined): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

/** Định dạng số theo kiểu Việt Nam, kèm đơn vị của cột (`%`). */
export function formatCell(
  v: AnalyticsCell | undefined,
  col?: Pick<AnalyticsColumn, 'type' | 'unit'>,
) {
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'number') {
    const s = v.toLocaleString('vi-VN', { maximumFractionDigits: 2 });
    return col?.unit === '%' ? `${s}%` : col?.unit ? `${s} ${col.unit}` : s;
  }
  return String(v);
}
