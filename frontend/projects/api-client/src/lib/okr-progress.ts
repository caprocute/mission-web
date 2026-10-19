import { KeyResult } from './models/okr';

/**
 * Tiến độ KR theo TL-02 SCR-0201: (hiện tại − đầu) / (chỉ tiêu − đầu), giới hạn trong [0, 1].
 * Máy chủ là nơi tính chính thức (`okr.key_result.progress`); hàm này dùng cho dữ liệu giả lập và kiểm thử.
 */
export function krProgress(
  kr: Pick<KeyResult, 'baselineValue' | 'targetValue' | 'currentValue'>,
): number {
  const span = kr.targetValue - kr.baselineValue;
  if (kr.currentValue === null || span === 0) return 0;
  return Math.min(1, Math.max(0, (kr.currentValue - kr.baselineValue) / span));
}

/** Tiến độ mục tiêu: trung bình có trọng số của tiến độ KR, đổi sang phần trăm làm tròn. */
export function objectiveProgressPct(
  krs: readonly Pick<KeyResult, 'baselineValue' | 'targetValue' | 'currentValue' | 'weight'>[],
): number {
  const totalWeight = krs.reduce((s, k) => s + k.weight, 0);
  if (totalWeight === 0) return 0;
  const sum = krs.reduce((s, k) => s + k.weight * krProgress(k), 0);
  return Math.round((sum / totalWeight) * 100);
}
