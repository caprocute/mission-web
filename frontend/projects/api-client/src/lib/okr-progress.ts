import { KeyResult } from './models/okr';

type KrValues = Pick<KeyResult, 'baselineValue' | 'targetValue' | 'currentValue'>;

/**
 * Tiến độ KR theo TL-02 SCR-0201: (hiện tại − đầu) / (chỉ tiêu − đầu), giới hạn trong [0, 1].
 * Máy chủ là nơi tính chính thức (`okr.key_result.progress`); hàm này dùng cho dữ liệu giả lập và kiểm thử.
 */
export function krProgress(kr: KrValues): number {
  if (kr.currentValue == null || kr.targetValue == null) return 0;
  const base = kr.baselineValue ?? 0;
  const span = kr.targetValue - base;
  if (span === 0) return 0;
  return Math.min(1, Math.max(0, (kr.currentValue - base) / span));
}

/** Tiến độ mục tiêu: trung bình có trọng số của tiến độ KR, trong [0, 1] như `OkrObjective.progress`. */
export function objectiveProgress(krs: readonly (KrValues & Pick<KeyResult, 'weight'>)[]): number {
  const totalWeight = krs.reduce((s, k) => s + k.weight, 0);
  if (totalWeight === 0) return 0;
  const sum = krs.reduce((s, k) => s + k.weight * krProgress(k), 0);
  return Math.round((sum / totalWeight) * 10000) / 10000;
}

/** Đổi tiến độ 0..1 của hợp đồng sang phần trăm làm tròn để hiển thị. */
export function progressPct(progress: number | null | undefined): number {
  return Math.round((progress ?? 0) * 100);
}
