import { AppraisalProfile, AppraisalScoreView } from '@vt/api-client';
import { checkReview, compareRows, returnReasonError, selfLevel, shortHash } from './review-rules';

const COMMENT = 'Hoàn thành tốt các nhiệm vụ then chốt.';

describe('quy tắc SCR-0902 (WEB-036)', () => {
  it('trường 7 bắt buộc, tối đa 4.000 ký tự', () => {
    expect(checkReview({ rating: 'HTTNV', comment: ' ', reason: '' }, 'HTTNV')).toHaveProperty(
      'comment',
    );
    expect(
      checkReview({ rating: 'HTTNV', comment: 'x'.repeat(4001), reason: '' }, 'HTTNV'),
    ).toHaveProperty('comment');
    expect(checkReview({ rating: 'HTTNV', comment: COMMENT, reason: '' }, 'HTTNV')).toEqual({});
  });

  it('BR-09-12: khác mức tự đề xuất thì lý do 20 đến 2.000 ký tự; không có mức tự đề xuất thì không đòi', () => {
    expect(checkReview({ rating: 'HTTNV', comment: COMMENT, reason: 'ngắn' }, 'HTXSNV')).toEqual({
      reason: 'Ghi lý do khác mức tự đề xuất, từ 20 đến 2.000 ký tự.',
    });
    expect(
      checkReview(
        { rating: 'HTTNV', comment: COMMENT, reason: 'Tỷ lệ việc vượt 22%, dưới ngưỡng 30%.' },
        'HTXSNV',
      ),
    ).toEqual({});
    expect(checkReview({ rating: 'HTNV', comment: COMMENT, reason: '' }, null)).toEqual({});
  });

  it('lý do trả lại từ 20 ký tự', () => {
    expect(returnReasonError('ngắn')).not.toBeNull();
    expect(returnReasonError('Đề nghị bổ sung biên bản nghiệm thu nhiệm vụ số 2.')).toBeNull();
  });

  it('bảng so sánh: ghép tự nhận với nghiệm thu, đánh dấu khác và căn cứ', () => {
    const profile = {
      ratingTrail: [{ levelKind: 'TU_DE_XUAT', ratingCode: 'HTXSNV', isCurrent: true }],
      selfAssessment: {
        selfRatingCode: 'HTXSNV',
        claims: [
          { taskId: 't1', qualityLevel: 0.75, delayFactor: 0.85 },
          { taskId: 't2', qualityLevel: 1, delayFactor: 1 },
        ],
      },
    } as unknown as AppraisalProfile;
    const view = {
      snapshot: {
        groups: [
          {
            code: 'THEN_CHOT',
            tasks: [
              { taskId: 't1', title: 'A', b: 0.5, c: 0.85, acceptanceNote: 'Chất lượng: phải sửa' },
              { taskId: 't2', title: 'B', b: 1, c: 1 },
              { taskId: 't3', title: 'C', b: 1, c: 0.7 },
            ],
          },
          { code: 'THUONG_XUYEN', tasks: [{ taskId: 't9', title: 'X', b: 1, c: 1 }] },
        ],
      },
    } as unknown as AppraisalScoreView;
    const rows = compareRows(view, profile);
    expect(rows.map((r) => [r.taskId, r.diffB, r.diffC, r.note])).toEqual([
      ['t1', true, false, 'Chất lượng: phải sửa'],
      ['t2', false, false, 'Khớp'],
      ['t3', false, false, 'Khớp'],
    ]);
    expect(rows[2].selfB).toBeNull();
    expect(selfLevel(profile)).toBe('HTXSNV');
    expect(shortHash('9c1ea07bd2f4c6e8a1b3d5f7092c4e6a8b0d2f4a6c8e0b2d4f6a8c0e2b4d6f8a')).toBe(
      '9c1e…6f8a',
    );
  });
});
