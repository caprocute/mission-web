import { BasisKind, Confidence, KeyResult, Objective, ObjectiveStatus } from '@vt/api-client';

export type TagSeverity = 'success' | 'info' | 'warn' | 'danger' | 'secondary' | 'contrast';
export type Tone = 'ok' | 'warn' | 'danger' | 'none';

/** Màu theo mức tự tin như mockup Hình 7.3: Cao xanh, Trung bình vàng, Thấp đỏ. */
export function confidenceTone(c: Confidence | null | undefined): Tone {
  switch (c) {
    case 'CAO':
      return 'ok';
    case 'TRUNG_BINH':
      return 'warn';
    case 'THAP':
      return 'danger';
    default:
      return 'none';
  }
}

export function confidenceSeverity(c: Confidence | null | undefined): TagSeverity {
  const t = confidenceTone(c);
  return t === 'ok' ? 'success' : t === 'warn' ? 'warn' : t === 'danger' ? 'danger' : 'secondary';
}

/** Nhãn ngắn trong bảng lịch sử check-in. */
export const CONFIDENCE_SHORT: Record<Confidence, string> = {
  CAO: 'Cao',
  TRUNG_BINH: 'TB',
  THAP: 'Thấp',
};

/**
 * Màu thanh tiến độ của KR: theo mức tự tin của check-in mới nhất; KR có cờ `RUI_RO` luôn đỏ;
 * chưa check-in thì theo tiến độ (≥ 70% xanh, ≥ 40% vàng, còn lại xám).
 */
export function krTone(kr: Pick<KeyResult, 'confidence' | 'riskFlag' | 'progress'>): Tone {
  if (kr.riskFlag === 'RUI_RO') return 'danger';
  if (kr.confidence) return confidenceTone(kr.confidence);
  return kr.progress >= 0.7 ? 'ok' : kr.progress >= 0.4 ? 'warn' : 'none';
}

/** KR của mục tiêu; `keyResults` không bắt buộc trong `OkrObjective` nên vắng coi là rỗng. */
export function krsOf(o: Pick<Objective, 'keyResults'>): KeyResult[] {
  return o.keyResults ?? [];
}

/** Màu thanh tiến độ mục tiêu: theo KR kém nhất (có KR tự tin Thấp thì đỏ, có Trung bình thì vàng). */
export function objectiveTone(o: Pick<Objective, 'keyResults'>): Tone {
  const tones = krsOf(o).map(krTone);
  if (tones.includes('danger')) return 'danger';
  if (tones.includes('warn')) return 'warn';
  if (tones.includes('ok')) return 'ok';
  return 'none';
}

export function statusSeverity(s: ObjectiveStatus): TagSeverity {
  switch (s) {
    case 'DA_DUYET':
      return 'success';
    case 'CHO_DUYET':
      return 'warn';
    case 'DONG':
      return 'secondary';
    default:
      return 'contrast';
  }
}

const NUMBER = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 4 });

/** Số theo kiểu Việt Nam (dấu phẩy thập phân). */
export function formatNumber(n: number): string {
  return NUMBER.format(n);
}

/** Giá trị kèm đơn vị đo: `2,4%`, `4 mốc`; `null` hiện `—`. */
export function formatValue(n: number | null | undefined, unit: string): string {
  if (n == null) return '—';
  return unit === '%' ? `${formatNumber(n)}%` : `${formatNumber(n)} ${unit}`;
}

/** "hiện tại / chỉ tiêu" như cột giá trị của mockup: `2,4% / 3%`, `4 / 12 mốc`. */
export function valueOverTarget(
  kr: Pick<KeyResult, 'currentValue' | 'targetValue' | 'unit'>,
): string {
  const cur = kr.currentValue == null ? '—' : formatNumber(kr.currentValue);
  if (kr.targetValue == null) return `${cur} / —`;
  return kr.unit === '%'
    ? `${cur}% / ${formatNumber(kr.targetValue)}%`
    : `${cur} / ${formatNumber(kr.targetValue)} ${kr.unit}`.trimEnd();
}

/** Ngày ISO → `dd/mm` hoặc `dd/mm/yyyy` (theo giờ địa phương của chuỗi, không đổi múi giờ). */
export function formatDate(iso: string, withYear = false): string {
  const [y, m, d] = iso.slice(0, 10).split('-');
  return withYear ? `${d}/${m}/${y}` : `${d}/${m}`;
}

const VN_TIME = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Ho_Chi_Minh',
  day: '2-digit',
  month: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** "dd/mm HH:mm" theo giờ Việt Nam, không phụ thuộc múi giờ của máy (lịch sử luồng duyệt). */
export function formatDateTime(iso: string): string {
  const p = Object.fromEntries(VN_TIME.formatToParts(new Date(iso)).map((x) => [x.type, x.value]));
  return `${p['day']}/${p['month']} ${p['hour']}:${p['minute']}`;
}

export const BASIS_KIND_LABEL: Record<BasisKind, string> = {
  NGHI_QUYET: 'Nghị quyết',
  KET_LUAN: 'Kết luận',
  CHI_THI: 'Chỉ thị',
  CHUONG_TRINH: 'Chương trình',
  KE_HOACH: 'Kế hoạch',
  CHI_TIEU_KTXH: 'Chỉ tiêu kinh tế - xã hội',
};

export interface TreeIndex {
  roots: Objective[];
  children: ReadonlyMap<string, Objective[]>;
}

/** Dựng cây từ danh sách phẳng; mục tiêu có cấp trên ngoài danh sách (ví dụ O-ĐUK-1) là gốc. */
export function indexTree(objectives: readonly Objective[]): TreeIndex {
  const ids = new Set(objectives.map((o) => o.id));
  const children = new Map<string, Objective[]>();
  const roots: Objective[] = [];
  for (const o of objectives) {
    const parentId = o.parentObjective?.id;
    if (parentId && ids.has(parentId)) {
      const list = children.get(parentId) ?? [];
      list.push(o);
      children.set(parentId, list);
    } else {
      roots.push(o);
    }
  }
  return { roots, children };
}

/** Số mục tiêu cấp dưới (mọi tầng) của một nút. */
export function descendantCount(id: string, children: ReadonlyMap<string, Objective[]>): number {
  let n = 0;
  const stack = [...(children.get(id) ?? [])];
  while (stack.length) {
    const o = stack.pop()!;
    n++;
    stack.push(...(children.get(o.id) ?? []));
  }
  return n;
}

/** Một dòng của chế độ "Bảng": mỗi KR một dòng, kèm mục tiêu chứa nó. */
export interface TableRow {
  objective: Objective;
  kr: KeyResult | null;
}

/** Duyệt cây theo chiều sâu để bảng giữ thứ tự cha trước con; mục tiêu chưa có KR vẫn có một dòng. */
export function tableRows(index: TreeIndex): TableRow[] {
  const rows: TableRow[] = [];
  const visit = (o: Objective) => {
    const krs = krsOf(o);
    if (krs.length === 0) rows.push({ objective: o, kr: null });
    for (const kr of krs) rows.push({ objective: o, kr });
    for (const c of index.children.get(o.id) ?? []) visit(c);
  };
  index.roots.forEach(visit);
  return rows;
}

/** KR chọn mặc định cho bảng bên phải: KR có cờ rủi ro hoặc tự tin Thấp đầu tiên, nếu không có thì KR đầu tiên. */
export function defaultKr(objectives: readonly Objective[]): KeyResult | null {
  const krs = objectives.filter((o) => o.tier === 1).flatMap(krsOf);
  return krs.find((k) => k.riskFlag !== 'BINH_THUONG' || k.confidence === 'THAP') ?? krs[0] ?? null;
}
