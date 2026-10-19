import { AppraisalProfile, AppraisalScoreView } from '@vt/api-client';
import {
  checkProposal,
  complaintOpen,
  conditionGroups,
  conditionText,
  pct,
  todayVn,
  trailChips,
} from './rating-sheet-rules';

describe('quy tắc SCR-0903 (WEB-037)', () => {
  it('trường 10: khác mức thẩm định thì lý do 20 đến 2.000 ký tự; trùng mức thì không đòi', () => {
    const f = { rating: 'HTXSNV' as const, reason: 'ngắn', comment: '' };
    expect(checkProposal(f, 'HTTNV')).toHaveProperty('reason');
    expect(
      checkProposal({ ...f, reason: 'Có giải pháp đột phá được Ban Thường vụ ghi nhận.' }, 'HTTNV'),
    ).toEqual({});
    expect(checkProposal({ ...f, rating: 'HTTNV' }, 'HTTNV')).toEqual({});
    expect(
      checkProposal({ ...f, rating: 'HTTNV', comment: 'x'.repeat(4001) }, 'HTTNV'),
    ).toHaveProperty('comment');
  });

  it('điều kiện nhóm theo mức từ cao xuống thấp, câu điều kiện kèm giá trị thực tế khi cần', () => {
    const view = {
      snapshot: { ruleChecks: [] },
      explanation: {
        systemLevel: 'HTTNV',
        conditions: [
          { ratingCode: 'HTTNV', label: 'Từ 80 điểm trở lên', met: true, actual: '91,2' },
          {
            ratingCode: 'HTXSNV',
            label: 'Từ 30% nhiệm vụ vượt mục tiêu trở lên',
            met: false,
            actual: '22%',
            threshold: '30%',
          },
          {
            ratingCode: 'HTXSNV',
            label: 'Hoàn thành 100% mục tiêu O',
            met: true,
            actual: '100%',
            threshold: '100%',
          },
        ],
      },
    } as unknown as AppraisalScoreView;
    const groups = conditionGroups(view);
    expect(groups.map((g) => [g.title, g.items.length])).toEqual([
      ['Điều kiện mức Hoàn thành xuất sắc nhiệm vụ', 2],
      ['Điều kiện mức Hoàn thành tốt nhiệm vụ', 1],
    ]);
    expect(conditionText(groups[0].items[0])).toBe(
      'Từ 30% nhiệm vụ vượt mục tiêu trở lên (thực tế 22%)',
    );
    expect(conditionText(groups[0].items[1])).toBe('Hoàn thành 100% mục tiêu O');
    expect(pct(0.22)).toBe('22%');
    expect(pct(0.125)).toBe('12,5%');
  });

  it('dải vết: theo thời điểm (kể cả khác múi giờ), chèn giải trình bắt buộc sau mức khác mức trước, kết thúc bằng bước kế tiếp', () => {
    const p = {
      status: 'CHO_QUYET_DINH',
      ratingTrail: [
        {
          levelKind: 'THAM_DINH',
          ratingCode: 'HTTNV',
          actor: { name: 'A' },
          proposedAt: '2027-01-07T10:30:00+07:00',
          isCurrent: true,
          explanationRequired: true,
          reason: 'Lý do',
        },
        {
          levelKind: 'TU_DE_XUAT',
          ratingCode: 'HTXSNV',
          actor: { name: 'B' },
          proposedAt: '2027-01-04T09:12:00+07:00',
          isCurrent: true,
        },
        {
          levelKind: 'DE_XUAT',
          ratingCode: 'HTTNV',
          actor: { name: 'C' },
          proposedAt: '2027-01-07T04:00:00Z',
          isCurrent: true,
        },
      ],
    } as unknown as AppraisalProfile;
    expect(trailChips(p).map((c) => [c.kind, c.text, c.level ?? '', c.date ?? ''])).toEqual([
      ['level', 'Tự đề xuất', 'Hoàn thành xuất sắc nhiệm vụ', '04/01'],
      ['level', 'Thẩm định', 'Hoàn thành tốt nhiệm vụ', '07/01'],
      ['reason', 'Giải trình bắt buộc · đã ghi', '', ''],
      ['level', 'Đề xuất', 'Hoàn thành tốt nhiệm vụ', '07/01'],
      ['next', 'Chờ quyết định', '', ''],
    ]);
  });

  it('gửi khiếu nại chỉ khi đã ký số quyết định và còn hạn', () => {
    expect(
      complaintOpen({ status: 'DA_KY_SO', complaintDeadline: '2026-10-30' }, '2026-10-30'),
    ).toBe(true);
    expect(
      complaintOpen({ status: 'DA_KY_SO', complaintDeadline: '2026-10-29' }, '2026-10-30'),
    ).toBe(false);
    expect(
      complaintOpen({ status: 'DA_KHOA', complaintDeadline: '2026-12-01' }, '2026-10-30'),
    ).toBe(false);
    expect(todayVn(new Date('2026-10-19T18:00:00Z'))).toBe('2026-10-20');
  });
});
