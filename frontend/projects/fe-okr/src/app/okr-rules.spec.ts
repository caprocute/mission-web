import { OkrComposeContext, OkrPolicy } from '@vt/api-client';
import fixture from '../../../api-client/src/mock/okr/okr.json';
import {
  ComposeForm,
  checkOkr,
  evenWeights,
  findTaskVerb,
  findVagueWord,
  missingFields,
} from './okr-rules';

/** WEB-022: quy tắc CEL của Policy Pack trên bản nháp O3 của fixture (Hình 7.4). */
const policy = fixture.compose.policy as OkrPolicy;
const ctx = {
  isTopLevel: false,
  parentOptions: fixture.compose.parentOptions,
  existingCount: 2,
  policy,
} as unknown as OkrComposeContext;
const draft = fixture.drafts['o-3'];

function form(patch: Partial<ComposeForm> = {}): ComposeForm {
  return {
    periodId: draft.periodId,
    basisIds: draft.basisIds,
    parentObjectiveId: draft.parentObjectiveId,
    title: draft.title,
    overLimitReason: null,
    keyResults: draft.keyResults.map((k) => ({
      ...k,
      key: k.id,
      weightPct: Math.round(k.weight * 100),
    })) as ComposeForm['keyResults'],
    ...patch,
  };
}
const code = (i: number) => `KR 3.${i + 1}`;

describe('okr-rules (SCR-0202)', () => {
  it('dò từ mơ hồ, động từ nhiệm vụ và trường thiếu', () => {
    expect(findVagueWord('Tăng cường công tác kiểm tra, giám sát', policy)).toBe('tăng cường');
    expect(findVagueWord('100% chi bộ sinh hoạt chuyên đề', policy)).toBe('');
    expect(findTaskVerb('Tổ chức hội nghị sơ kết', policy)).toBe('tổ chức');
    expect(findTaskVerb('100% tổ chức đảng được kiểm tra', policy)).toBe('');
    const f = form();
    expect(missingFields(f.keyResults[2])).toBe(
      'đơn vị đo, giá trị đầu, chỉ tiêu, hạn, người xác nhận',
    );
    expect(missingFields(f.keyResults[0])).toBe('');
  });

  it('bản nháp O3: KR 3.3 mơ hồ chặn trình, KR 3.4 cảnh báo giống nhiệm vụ', () => {
    const r = checkOkr(form(), ctx, code);
    expect(r.errors.map((e) => e.message)).toEqual([
      'KPI mơ hồ: "tăng cường" không đo được. Vui lòng bổ sung chỉ tiêu và đơn vị đo.',
      'KR 3.3 thiếu đơn vị đo, giá trị đầu, chỉ tiêu, hạn, người xác nhận.',
    ]);
    expect(r.errors.every((e) => e.krIndex === 2)).toBe(true);
    expect(r.warnings.map((w) => w.ruleId)).toEqual(['BR-02-06']);
    expect(r.byKr[3][0].message).toContain('KR 3.4 giống một nhiệm vụ');
    expect(r.checklist.filter((c) => c.state === 'pass').map((c) => c.label)).toEqual([
      'Có căn cứ',
      'Có mục tiêu cấp trên',
      'Tổng trọng số KR = 100%',
    ]);
  });

  it('BR-02-07 chặn khi tổng trọng số khác 100%, BR-02-04 liệt kê trường thiếu', () => {
    const f = form();
    f.keyResults[0] = { ...f.keyResults[0], weightPct: 30, dueDate: null };
    const r = checkOkr(f, ctx, code);
    expect(r.errors.map((e) => e.message)).toContain('Tổng trọng số KR là 90%, cần bằng 100%.');
    expect(r.errors.map((e) => e.message)).toContain('KR 3.1 thiếu hạn.');
  });

  it('BR-02-01 cảnh báo và bắt buộc lý do khi vượt khuyến nghị; MSG-02-03 khi cấp trên chưa duyệt', () => {
    const over = { ...ctx, existingCount: 3 };
    let r = checkOkr(form(), over, code);
    expect(r.warnings.map((w) => w.message)).toContain('4 mục tiêu trong kỳ, vượt khuyến nghị 3.');
    expect(r.errors.map((e) => e.message)).toContain('Vui lòng ghi lý do vượt 3 mục tiêu.');
    r = checkOkr(form({ overLimitReason: 'Nhiệm vụ đột xuất của khối' }), over, code);
    expect(r.errors.map((e) => e.ruleId)).not.toContain('BR-02-01-LD');
    r = checkOkr(form({ parentObjectiveId: 'o-duk-2' }), ctx, code);
    expect(r.errors.map((e) => e.message)).toContain(
      'Mục tiêu cấp trên O-ĐUK-2 chưa được duyệt. Đồng chí có thể soạn nhưng chưa trình được.',
    );
  });

  it('BR-02-02 chặn khi thiếu căn cứ hoặc mục tiêu cấp trên, trừ đơn vị cao nhất', () => {
    const f = form({ basisIds: [], parentObjectiveId: null });
    expect(checkOkr(f, ctx, code).errors.map((e) => e.ruleId)).toEqual(
      expect.arrayContaining(['BR-02-02-CC', 'BR-02-02-CT']),
    );
    expect(
      checkOkr(f, { ...ctx, isTopLevel: true }, code).errors.map((e) => e.ruleId),
    ).not.toContain('BR-02-02-CT');
  });

  it('chia đều trọng số, phần lẻ dồn vào KR đầu', () => {
    expect(evenWeights(3)).toEqual([34, 33, 33]);
    expect(evenWeights(4)).toEqual([25, 25, 25, 25]);
  });
});
