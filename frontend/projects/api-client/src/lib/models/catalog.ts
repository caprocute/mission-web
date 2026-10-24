import { TaskGroup } from '@vt/api-client';

/** Trạng thái phiên bản danh mục (`task.work_catalog.status`, suy luận: TL-03 chỉ ghi "một phiên bản áp dụng mỗi kỳ"). */
export type WorkCatalogStatus = 'NHAP' | 'AP_DUNG' | 'HET_HIEU_LUC';

export const WORK_CATALOG_STATUS_LABEL: Record<WorkCatalogStatus, string> = {
  NHAP: 'nháp',
  AP_DUNG: 'đang áp dụng',
  HET_HIEU_LUC: 'hết hiệu lực',
};

/** Trạng thái mục danh mục (`task.catalog_item.status`, SCR-0401 trường 14). */
export type CatalogItemStatus = 'AP_DUNG' | 'NGUNG';

export const CATALOG_ITEM_STATUS_LABEL: Record<CatalogItemStatus, string> = {
  AP_DUNG: 'Đang áp dụng',
  NGUNG: 'Ngừng',
};

/** Một phiên bản danh mục công việc theo vị trí việc làm (`task.work_catalog`, SCR-0401 trường 1, 2). */
export interface WorkCatalog {
  id: string;
  name: string;
  versionNo: number;
  status: WorkCatalogStatus;
  /** Kỳ bắt đầu áp dụng, ví dụ "Quý IV/2026". */
  effectiveFromLabel?: string;
  /** Ngày phiên bản này thay phiên bản trước. */
  effectiveFromDate?: string;
  parentCatalogName?: string;
  itemCount: number;
  positionCount: number;
  rowVersion: number;
  /**
   * Tham số Policy Pack: hệ số mức khó (chỉ để gợi ý điểm khi xây dựng danh mục, không nhân khi tính điểm)
   * và điểm gợi ý cho từng mức khó.
   */
  difficultyFactors: Record<string, number>;
  scoreSuggestion: Record<string, number>;
}

/** Vị trí việc làm (`org.position`). */
export interface Position {
  id: string;
  name: string;
}

/** Mục danh mục công việc theo Bảng 3 (`task.catalog_item`, TL-03). */
export interface CatalogItem {
  id: string;
  catalogId?: string;
  code: string;
  name: string;
  positionId?: string;
  positionName?: string;
  productName: string;
  unit: string;
  /** Mức khó năm mức (BR-04-14). */
  difficulty: number;
  /** Điểm chuẩn 0–100 (`base_score`, BR-04-14). */
  baseScore: number;
  qualityCriteria: string;
  evidenceRequired?: string;
  defaultGroup: TaskGroup;
  /** Thời hạn chuẩn, ngày làm việc. */
  standardDays?: number;
  status?: CatalogItemStatus;
  /** Số nhiệm vụ và số kỳ đã dùng mục này. */
  usageCount?: number;
  usagePeriods?: number;
  /** Đã dùng ở kỳ đã khoá: sửa sẽ sang phiên bản mới (BR-04-13, MSG-04-01). */
  usedInLockedPeriod?: boolean;
  rowVersion?: number;
}

/** Nội dung thêm, sửa mục (SCR-0401 trường 3–14); `code` trống thì máy chủ tự sinh. */
export type CatalogItemInput = Pick<
  CatalogItem,
  | 'positionId'
  | 'name'
  | 'productName'
  | 'unit'
  | 'difficulty'
  | 'baseScore'
  | 'qualityCriteria'
  | 'evidenceRequired'
  | 'defaultGroup'
  | 'standardDays'
  | 'status'
> & { code?: string };

/** Kết quả thêm, sửa: khi mục đã dùng ở kỳ khoá hoặc phiên bản đang áp dụng, máy chủ ghi vào phiên bản nháp mới. */
export interface CatalogItemSaveResult {
  item: CatalogItem;
  catalog: WorkCatalog;
  /** `true` khi thay đổi được lưu ở phiên bản khác phiên bản đang xem. */
  movedToNewVersion: boolean;
}

/** Trang mục danh mục có tổng số để hiển thị "n công việc · m vị trí việc làm". */
export interface CatalogItemsPage {
  items: CatalogItem[];
  nextCursor: string | null;
  total: number;
  positionCount: number;
}

/** Tác vụ nhập Excel (`/api/v1/catalog/imports/{jobId}`). */
export interface CatalogImportJob {
  id: string;
  fileName: string;
  status: 'DANG_XU_LY' | 'XONG' | 'LOI';
  totalRows: number;
  validRows: number;
  /** Nhóm lỗi theo lý do để hiển thị vùng 5. */
  errorGroups: { reason: string; count: number; kind: 'THIEU' | 'MO_HO' | 'KHAC' }[];
  finishedAt?: string;
}
