import { EVIDENCE_STATUS_LABEL, EvidenceVersionStatus, FilterCodec, ListColumnDef } from '@vt/api-client/domains';

/** Mã màn hình dùng cho tuỳ chọn cột và bộ lọc đã lưu của "Minh chứng của tôi" (CNH-04). */
export const EVIDENCE_LIST_SCREEN = 'SCR-0701';

/** Thông tin hiển thị trên mỗi dòng minh chứng; tên minh chứng bắt buộc. */
export const EVIDENCE_LIST_COLUMNS: ListColumnDef[] = [
  { key: 'title', label: 'Tên minh chứng', required: true },
  { key: 'taskCode', label: 'Mã nhiệm vụ' },
  { key: 'versionNo', label: 'Phiên bản' },
  { key: 'status', label: 'Tình trạng' },
];

export interface EvidenceListFilterState {
  statuses: EvidenceVersionStatus[];
}

/** Tình trạng phiên bản → `statusCodes` của tiêu chí. */
export const EVIDENCE_LIST_CODEC: FilterCodec<EvidenceListFilterState> = {
  toCriteria: (s) => (s.statuses.length ? { statusCodes: [...s.statuses] } : {}),
  fromCriteria: (c) => {
    const statuses = (c.statusCodes ?? []).filter(
      (x): x is EvidenceVersionStatus => x in EVIDENCE_STATUS_LABEL,
    );
    const others = Object.entries(c).filter(([k, v]) => k !== 'statusCodes' && v !== undefined);
    return {
      state: { statuses },
      dropped: (c.statusCodes?.length ?? 0) - statuses.length + others.length,
    };
  },
};
