import type { KeyResultDraft, ObjectiveDetail, NewObjectiveDraft, ObjectiveDraft, OkrComposeContext, OkrOwnerType } from '@vt/api-client';
import type { OkrAssistSuggestion } from '@vt/api-client/domains';

/** Thông báo của QT-17 (TL-02b). */
export const MSG_17_03 =
  'Trợ lý đang bận. Gợi ý sẽ hiển thị khi xong, đồng chí có thể tiếp tục soạn.';
export const MSG_17_04 =
  'Đã chèn gợi ý vào bản nháp. Nội dung được đánh dấu "AI hỗ trợ" để người duyệt biết.';
export const MAX_QUESTION = 2000;

export function msg1701(kr: string, suggestion: string): string {
  return `KR "${kr}" chưa đo được. Gợi ý: ${suggestion}.`;
}

/** Nội dung của O/KR có cờ AI hỗ trợ (BR-02-11): chèn từ gợi ý, kể cả khi người dùng sửa tiếp. */
export function aiAssisted(k: Pick<KeyResultDraft, 'contentSource'>): boolean {
  return k.contentSource !== 'HUMAN';
}

/** Mã KR bị trợ lý đánh dấu chưa đo được, còn chờ người dùng quyết định. */
export function vagueCodes(
  suggestions: readonly OkrAssistSuggestion[],
  decided: Readonly<Record<string, unknown>>,
): Set<string> {
  return new Set(
    suggestions
      .filter((s) => s.kind === 'SOAT_KR' && s.krCode && !decided[s.id])
      .map((s) => s.krCode!),
  );
}

/**
 * Bản nháp mục tiêu mới từ gợi ý `MUC_TIEU`: KR mang `AI_ACCEPTED`, trọng số chia đều khi gợi ý thiếu,
 * hạn KR là cuối kỳ và người xác nhận mặc định như SCR-0202 (suy luận từ `blankKr` của WEB-022).
 */
export function objectiveFromSuggestion(
  s: OkrAssistSuggestion,
  ctx: OkrComposeContext,
  ownerType: OkrOwnerType,
  basisIds: string[],
  objectiveNo: number,
): NewObjectiveDraft {
  const krs = s.objective?.keyResults ?? [];
  const verifier = ctx.verifiers.find((v) => v.id !== ctx.me.id)?.id ?? null;
  return {
    periodId: ctx.period.id,
    ownerType,
    ownerUnitId: ctx.unit.id,
    ...(ownerType === 'CA_NHAN' ? { ownerPersonId: ctx.me.id } : {}),
    basisIds,
    parentObjectiveId: null,
    title: s.objective?.title ?? '',
    overLimitReason: null,
    keyResults: krs.map((k, i) => ({
      code: `KR ${objectiveNo}.${i + 1}`,
      title: k.title,
      unit: k.unit ?? '',
      baselineValue: k.baselineValue ?? 0,
      targetValue: k.targetValue ?? null,
      weight: k.weight ?? Math.round((1 / krs.length) * 100) / 100,
      dueDate: ctx.period.endDate,
      verifierId: verifier,
      checkinFrequency: 'TUAN',
      contentSource: 'AI_ACCEPTED',
    })),
  };
}

/**
 * Bản nháp sau khi thay KR mơ hồ bằng câu gợi ý của thẻ `SOAT_KR`. Khi câu gợi ý có tỷ lệ phần trăm và KR chưa
 * có chỉ tiêu, lấy tỷ lệ đó làm chỉ tiêu (suy luận) để KR đo được như lý do của thẻ nêu.
 */
export function applyRewrite(d: ObjectiveDetail, krCode: string, text: string): ObjectiveDraft {
  const pct = /(\d{1,3})\s*%/.exec(text);
  return {
    id: d.id,
    periodId: d.periodId,
    ownerType: d.ownerType,
    basisIds: d.basisIds,
    parentObjectiveId: d.parentObjectiveId,
    title: d.title,
    overLimitReason: d.overLimitReason,
    keyResults: d.keyResults.map((k) =>
      k.code !== krCode
        ? k
        : {
            ...k,
            title: text,
            ...(k.targetValue === null && pct ? { targetValue: Number(pct[1]), unit: '%' } : {}),
            contentSource: 'AI_ACCEPTED',
          },
    ),
  };
}
