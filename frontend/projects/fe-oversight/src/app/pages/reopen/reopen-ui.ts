import { REOPEN_SCOPE_LABEL, ReopenRequest, ReopenRequestStatus } from '@vt/api-client';

/** Màu nhãn trạng thái yêu cầu (PrimeNG `p-tag`). */
export function reopenSeverity(
  s: ReopenRequestStatus,
): 'info' | 'warn' | 'success' | 'danger' | 'secondary' {
  switch (s) {
    case 'CHO_DUYET_1':
    case 'CHO_DUYET_2':
      return 'warn';
    case 'DA_DUYET':
    case 'DA_THUC_HIEN':
      return 'success';
    case 'TU_CHOI':
      return 'danger';
    default:
      return 'secondary';
  }
}

/** "Danh sách hồ sơ cụ thể (1)" như SCR-1301 vùng 2. */
export function scopeSummary(r: ReopenRequest): string {
  const n = r.scopeIds?.length ?? 0;
  return r.scope === 'TOAN_KY' ? 'Toàn kỳ' : `${REOPEN_SCOPE_LABEL[r.scope]} (${n})`;
}

/**
 * Bảng dữ liệu được phép mở (TL-03; danh sách gợi ý của màn hình tạo, suy luận) và cột của từng bảng. Màn
 * hình gọi mỗi bảng là một "nhóm dữ liệu" và mỗi cột là một nội dung được phép sửa, không hiện tên bảng.
 */
export const EDITABLE_TABLES: {
  table: string;
  label: string;
  fields: { key: string; label: string }[];
}[] = [
  {
    table: 'product.acceptance',
    label: 'Kết quả nghiệm thu',
    fields: [
      { key: 'c_factor', label: 'Tiến độ (C)' },
      { key: 'quality_level', label: 'Chất lượng (B)' },
      { key: 'revision_cause', label: 'Nguyên nhân chỉnh sửa' },
      { key: 'is_objective_revision', label: 'Chỉnh sửa do nguyên nhân khách quan' },
    ],
  },
  {
    table: 'appraisal.self_assessment',
    label: 'Phiếu tự đánh giá',
    fields: [{ key: 'structured_data', label: 'Số liệu có cấu trúc của phiếu' }],
  },
];

export function tableLabel(table: string): string {
  return EDITABLE_TABLES.find((t) => t.table === table)?.label ?? 'Dữ liệu khác';
}

export function fieldLabel(table: string, key: string): string {
  return (
    EDITABLE_TABLES.find((t) => t.table === table)?.fields.find((f) => f.key === key)?.label ?? key
  );
}
