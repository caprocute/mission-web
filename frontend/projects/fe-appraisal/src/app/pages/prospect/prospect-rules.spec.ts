import {
  ProspectCard,
  eligibilityError,
  moveCard,
  rankOf,
  reasonMissing,
  toInput,
} from './prospect-rules';

/** WEB-039: kéo thả, BR-10-08, BR-10-09 của SCR-1002 trên trình duyệt. */
const card = (personId: string, group: ProspectCard['group'], p: Partial<ProspectCard> = {}) =>
  ({
    personId,
    name: personId,
    summary: '',
    level: 'HTTNV',
    reassignFlag: false,
    group,
    suggested: group,
    reason: '',
    plan: '',
    ...p,
  }) as ProspectCard;

describe('prospect-rules (WEB-039)', () => {
  const cards = [
    card('a', 'NHOM_1'),
    card('b', 'NHOM_2'),
    card('c', 'NHOM_2'),
    card('d', 'NHOM_3'),
  ];

  it('chuyển nhóm, đổi thứ tự và đánh lại số thứ tự liền mạch', () => {
    const up = moveCard(cards, 'c', 'NHOM_2', 0);
    expect(up.filter((c) => c.group === 'NHOM_2').map((c) => c.personId)).toEqual(['c', 'b']);
    const across = moveCard(cards, 'd', 'NHOM_1', 0);
    expect(across.filter((c) => c.group === 'NHOM_1').map((c) => c.personId)).toEqual(['d', 'a']);
    expect(
      rankOf(
        across,
        across.find((c) => c.personId === 'a')!,
      ),
    ).toBe(2);
    expect(toInput(across).entries.map((e) => [e.personId, e.groupCode, e.rankInGroup])).toEqual([
      ['d', 'NHOM_1', 1],
      ['a', 'NHOM_1', 2],
      ['b', 'NHOM_2', 1],
      ['c', 'NHOM_2', 2],
    ]);
  });

  it('BR-10-09: không hoàn thành nhiệm vụ năm hoặc cờ bố trí lại không vào Nhóm 1, 2', () => {
    expect(eligibilityError(card('Khánh', 'NHOM_4', { level: 'KHTNV' }), 'NHOM_2')).toBe(
      'Cán bộ Khánh xếp loại năm Không hoàn thành nhiệm vụ nên không thể đưa vào Nhóm 2.',
    );
    expect(eligibilityError(card('Long', 'NHOM_3', { reassignFlag: true }), 'NHOM_1')).toContain(
      'hai quý liên tiếp',
    );
    expect(eligibilityError(card('Long', 'NHOM_3', { reassignFlag: true }), 'NHOM_3')).toBeNull();
  });

  it('BR-10-08: khác gợi ý cần lý do 20 đến 1.000 ký tự; trùng gợi ý thì không gửi lý do', () => {
    const moved = card('e', 'NHOM_1', { suggested: 'NHOM_2', reason: 'ngắn' });
    expect(reasonMissing(moved)).toBe(true);
    expect(reasonMissing({ ...moved, reason: 'x'.repeat(20) })).toBe(false);
    expect(reasonMissing({ ...moved, reason: 'x'.repeat(1001) })).toBe(true);
    expect(toInput([card('f', 'NHOM_2', { reason: 'cũ', plan: ' KH-1 ' })]).entries[0]).toEqual({
      personId: 'f',
      groupCode: 'NHOM_2',
      rankInGroup: 1,
      trainingPlanRef: 'KH-1',
    });
  });
});
