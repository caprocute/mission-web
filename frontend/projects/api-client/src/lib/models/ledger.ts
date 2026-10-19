import type { components } from '../generated/oversight';

/**
 * Sổ cái chuỗi băm của SVC-09 (`/api/v1/ledger`, QT-13, SCR-1302): bí danh của lược đồ trong
 * `contracts/openapi/modules/oversight.yaml` (WEB-043), tên trường theo schema `ledger` của TL-03
 * (`ledger_entry`, `merkle_seal`, `anchor_receipt`, `verify_run`).
 */
type S = components['schemas'];

export type LedgerHashHex = S['LedgerHashHex'];
export type LedgerEntry = S['LedgerEntry'];
export type LedgerEntryDetail = S['LedgerEntryDetail'];
export type LedgerSeal = S['LedgerSeal'];
export type LedgerAnchorReceipt = S['LedgerAnchorReceipt'];
export type LedgerVerifyKind = S['LedgerVerifyKind'];
export type LedgerChainLink = S['LedgerChainLink'];
export type LedgerVerifyRun = S['LedgerVerifyRun'];
export type LedgerActorKind = LedgerEntry['actorKind'];

/** Bộ lọc tra cứu (SCR-1302 trường 1–3). */
export interface LedgerEntryFilter {
  aggregateType?: string;
  aggregateId?: string;
  from?: string;
  to?: string;
  eventType?: string;
  chainPartition?: number;
}

export interface LedgerVerifyRunInput {
  verifyKind: LedgerVerifyKind;
  /** Bỏ trống để kiểm tra cả 64 phân vùng. */
  chainPartition?: number;
  rangeFrom?: string;
  rangeTo?: string;
}

export interface LedgerExportInput {
  aggregateType?: string;
  aggregateId?: string;
  eventType?: string;
  from: string;
  to: string;
  includeVerifyRunId?: string;
}

/** Loại kiểm tra (TL-03 mục 8: đầu chuỗi 15 phút, niêm phong mỗi giờ, toàn bộ hằng tuần, lưu trữ hằng quý). */
export const LEDGER_VERIFY_KIND_LABEL: Record<LedgerVerifyKind, string> = {
  DAU_CHUOI: 'Các bản ghi mới nhất',
  NIEM_PHONG: 'Các bản niêm phong',
  TOAN_BO: 'Toàn bộ dữ liệu',
  LUU_TRU: 'Dữ liệu lưu trữ',
};

export const LEDGER_ACTOR_KIND_LABEL: Record<LedgerActorKind, string> = {
  NGUOI: 'Người dùng',
  DICH_VU: 'Hệ thống (xử lý tự động)',
  HE_THONG: 'Hệ thống',
};

/** Nơi giữ bản neo (`anchor_target`): `CELL_KHAC` là cell khác, `OBJECT_LOCK` là kho đối tượng khoá ghi. */
export const LEDGER_ANCHOR_TARGET_LABEL: Record<LedgerAnchorReceipt['anchorTarget'], string> = {
  CELL_KHAC: 'Trung tâm dữ liệu dự phòng',
  KHO_VPTW: 'Kho lưu trữ VPTW',
  TSA_DOC_LAP: 'Dịch vụ cấp dấu thời gian độc lập',
  OBJECT_LOCK: 'Kho lưu trữ chống sửa, xoá',
};

/** Loại đối tượng hay tra cứu (`ledger_entry.aggregate_type`, TL-03; danh sách gợi ý, suy luận). */
export const LEDGER_AGGREGATE_TYPE_LABEL: Record<string, string> = {
  appraisal_profile: 'Hồ sơ đánh giá',
  task: 'Nhiệm vụ',
  evidence: 'Minh chứng',
  period: 'Kỳ đánh giá',
  reopen_request: 'Yêu cầu mở lại kỳ',
};

/** Số phân vùng chuỗi mỗi tenant (ADR-09, BR-13-09). */
export const LEDGER_PARTITIONS = 64;
/** Khoảng tra cứu tối đa mỗi lần: 2 năm (SCR-1302 trường 2). */
export const LEDGER_MAX_RANGE_DAYS = 731;
/** Khoảng mặc định: 90 ngày gần nhất (SCR-1302 trường 2). */
export const LEDGER_DEFAULT_RANGE_DAYS = 90;

/** Băm rút gọn để hiển thị: `4b07…e7f8`. */
export const shortLedgerHash = (h?: string | null, n = 4) =>
  h ? (h.length > n * 2 ? `${h.slice(0, n)}…${h.slice(-n)}` : h) : '—';
