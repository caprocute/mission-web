import type { components } from '../generated/config';

/**
 * Kỳ đánh giá và saga khoá kỳ (`/api/v1/periods`, QT-08, SCR-0801): bí danh của lược đồ trong
 * `contracts/openapi/modules/config.yaml` (WEB-034), tên trường theo `config.period` của TL-03 và các
 * bảng đề xuất `config.period_lock_order`, `config.period_lock_step` của TL-02.
 */
type S = components['schemas'];

export type PeriodStatus = S['PeriodStatus'];
export type PeriodKind = S['PeriodKind'];
export type PeriodFlow = S['PeriodFlow'];
export type PeriodMilestones = S['PeriodMilestones'];
export type PeriodPinnedPolicyPack = S['PeriodPinnedPolicyPack'];
export type Period = S['Period'];
export type PeriodPage = S['PeriodPage'];
export type PeriodCreate = S['PeriodCreate'];
export type PeriodLockOrderStatus = S['PeriodLockOrderStatus'];
export type PeriodLockOrder = S['PeriodLockOrder'];
export type PeriodLockOrderCreate = S['PeriodLockOrderCreate'];
export type SagaStatus = S['SagaStatus'];
export type PeriodLockStepStatus = S['PeriodLockStepStatus'];
export type PeriodLockStep = S['PeriodLockStep'];
export type PeriodLockCancelState = S['PeriodLockCancelState'];
export type PeriodLockProgress = S['PeriodLockProgress'];
export type ScoreBatchStatus = S['ScoreBatchStatus'];
export type PeriodLockBatch = S['PeriodLockBatch'];
export type PeriodLockFailure = S['PeriodLockFailure'];
export type PeriodLockEvent = S['PeriodLockEvent'];
export type PeriodLockDiagram = S['PeriodLockDiagram'];

/** Trạng thái kỳ (baseline 9.1); kỳ `MO` chưa tới ngày bắt đầu hiện "Đã tạo" (`notStarted`). */
export const PERIOD_STATUS_LABEL: Record<PeriodStatus, string> = {
  MO: 'Đang mở',
  DANG_KHOA: 'Đang khoá',
  DA_KHOA: 'Đã khoá',
  MO_LAI_HAU_KIEM: 'Mở lại hậu kiểm',
};

export const PERIOD_KIND_LABEL: Record<PeriodKind, string> = {
  THANG: 'Tháng',
  QUY: 'Quý',
  NAM: 'Năm',
  NAM_HOC: 'Năm học',
};

export const PERIOD_FLOW_LABEL: Record<PeriodFlow, string> = {
  DANG: 'Khối Đảng',
  NHA_NUOC: 'Khối Nhà nước',
  CA_HAI: 'Cả hai luồng',
};

export const LOCK_ORDER_STATUS_LABEL: Record<PeriodLockOrderStatus, string> = {
  NHAP: 'Nháp',
  CHO_DUYET: 'Chờ phê duyệt',
  DA_DUYET: 'Đã phê duyệt',
  DANG_CHAY: 'Đang chạy',
  HOAN_TAT: 'Hoàn tất',
  DA_HUY: 'Đã huỷ',
};

export const SAGA_STATUS_LABEL: Record<SagaStatus, string> = {
  DANG_CHAY: 'Đang chạy',
  CHO_SU_KIEN: 'Đang tạm dừng',
  HOAN_TAT: 'Hoàn tất',
  DANG_BU_TRU: 'Đang bù trừ',
  DA_BU_TRU: 'Đã bù trừ',
  LOI_CAN_XU_LY: 'Lỗi cần xử lý',
};

export const LOCK_STEP_STATUS_LABEL: Record<PeriodLockStepStatus, string> = {
  CHO: 'Chờ',
  DANG_CHAY: 'Đang chạy',
  DAT: 'Đạt',
  DAT_CO_CANH_BAO: 'Đạt, có cảnh báo',
  LOI: 'Lỗi',
  DA_BU_TRU: 'Đã bù trừ',
};

export const SCORE_BATCH_STATUS_LABEL: Record<ScoreBatchStatus, string> = {
  CHO: 'Chờ',
  DANG_CHAY: 'Đang tính',
  XONG: 'Xong',
  LOI: 'Lỗi',
};

/** Saga còn chạy hoặc chờ: màn hình tiếp tục hỏi tiến trình. */
export const SAGA_ACTIVE_STATUSES: readonly SagaStatus[] = [
  'DANG_CHAY',
  'CHO_SU_KIEN',
  'DANG_BU_TRU',
];
