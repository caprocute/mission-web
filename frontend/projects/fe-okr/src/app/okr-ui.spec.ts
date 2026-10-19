import { KeyResult, Objective, krProgress, objectiveProgress } from '@vt/api-client';
import {
  formatDateTime,
  defaultKr,
  descendantCount,
  formatValue,
  indexTree,
  krTone,
  objectiveTone,
  tableRows,
  valueOverTarget,
} from './okr-ui';

const kr = (over: Partial<KeyResult> = {}): KeyResult => ({
  id: 'k',
  objectiveId: 'o',
  code: 'KR 1.1',
  title: 'KR',
  unit: '%',
  baselineValue: 0,
  targetValue: 100,
  currentValue: 50,
  weight: 0.5,
  progress: 0.5,
  dueDate: '2026-12-31',
  verifier: { id: 'p-v', name: 'Trưởng ban' },
  owner: { id: 'p-a', name: 'A' },
  confidence: 'CAO',
  status: 'DA_DUYET',
  riskFlag: 'BINH_THUONG',
  viewerIsOwner: false,
  latestBlocker: null,
  linkedWork: [],
  linkedTaskCount: 0,
  rowVersion: 1,
  ...over,
});

const obj = (
  id: string,
  parent: string | null,
  krs: KeyResult[] = [],
  tier: 1 | 2 | 3 = 1,
): Objective => ({
  id,
  periodId: 'p',
  code: id,
  title: id,
  tier,
  ownerType: 'TAP_THE',
  ownerUnit: { id: 'u', name: 'Ban' },
  ...(parent ? { parentObjective: { id: parent, code: parent, title: parent } } : {}),
  status: 'DA_DUYET',
  progress: 0,
  versionNo: 1,
  keyResults: krs,
  rowVersion: 1,
});

describe('okr-ui (SCR-0201)', () => {
  it('tính tiến độ O1 của mockup Hình 7.3 bằng 0,64 (64%)', () => {
    const krs = [
      { baselineValue: 0, targetValue: 3, currentValue: 2.4, weight: 0.4 },
      { baselineValue: 0, targetValue: 100, currentValue: 72, weight: 0.3 },
      { baselineValue: 0, targetValue: 90, currentValue: 32.6, weight: 0.3 },
    ];
    expect(objectiveProgress(krs)).toBeCloseTo(0.64, 2);
    expect(
      objectiveProgress([
        { baselineValue: 0, targetValue: 100, currentValue: 68, weight: 0.5 },
        { baselineValue: 0, targetValue: 12, currentValue: 4, weight: 0.5 },
      ]),
    ).toBeCloseTo(0.51, 2);
  });

  it('tiến độ KR giới hạn trong [0, 1], hỗ trợ chiều giảm và chưa có giá trị', () => {
    expect(krProgress({ baselineValue: 0, targetValue: 10, currentValue: 15 })).toBe(1);
    expect(krProgress({ baselineValue: 5, targetValue: 1, currentValue: 3 })).toBe(0.5);
    expect(krProgress({ baselineValue: 0, targetValue: 10, currentValue: null })).toBe(0);
  });

  it('màu theo mức tự tin; cờ RUI_RO luôn đỏ; mục tiêu theo KR kém nhất', () => {
    expect(krTone(kr({ confidence: 'TRUNG_BINH' }))).toBe('warn');
    expect(krTone(kr({ confidence: 'CAO', riskFlag: 'RUI_RO' }))).toBe('danger');
    expect(krTone(kr({ confidence: undefined, progress: 0.8 }))).toBe('ok');
    expect(objectiveTone(obj('o', null, [kr(), kr({ confidence: 'THAP' })]))).toBe('danger');
  });

  it('định dạng giá trị kiểu Việt Nam', () => {
    expect(formatValue(32.6, '%')).toBe('32,6%');
    expect(formatValue(4, 'mốc')).toBe('4 mốc');
    expect(valueOverTarget(kr({ currentValue: 2.4, targetValue: 3 }))).toBe('2,4% / 3%');
    expect(valueOverTarget(kr({ unit: 'mốc', currentValue: 4, targetValue: 12 }))).toBe(
      '4 / 12 mốc',
    );
  });

  it('dựng cây ba tầng, đếm cấp dưới và bảng theo thứ tự cha trước con', () => {
    const list = [
      obj('a', 'ngoai-cay', [kr({ id: 'a1' })]),
      obj('a-1', 'a', [kr({ id: 'a11' }), kr({ id: 'a12' })], 2),
      obj('a-1-1', 'a-1', [], 3),
      obj('b', null, [kr({ id: 'b1' })]),
    ];
    const idx = indexTree(list);
    expect(idx.roots.map((o) => o.id)).toEqual(['a', 'b']);
    expect(descendantCount('a', idx.children)).toBe(2);
    expect(tableRows(idx).map((r) => r.kr?.id ?? r.objective.id)).toEqual([
      'a1',
      'a11',
      'a12',
      'a-1-1',
      'b1',
    ]);
  });

  it('KR mặc định là KR tự tin Thấp đầu tiên của tầng 1', () => {
    const list = [obj('a', null, [kr({ id: 'x' }), kr({ id: 'y', confidence: 'THAP' })])];
    expect(defaultKr(list)?.id).toBe('y');
    expect(defaultKr([])).toBeNull();
  });
});

describe('formatDateTime (SCR-0203 lịch sử luồng duyệt)', () => {
  it('hiện giờ Việt Nam, không phụ thuộc múi giờ máy', () => {
    expect(formatDateTime('2026-10-05T09:20:00+07:00')).toBe('05/10 09:20');
    expect(formatDateTime('2026-10-05T02:20:00Z')).toBe('05/10 09:20');
  });
});
