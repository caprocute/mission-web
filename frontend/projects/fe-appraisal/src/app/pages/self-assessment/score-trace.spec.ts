import { TestBed } from '@angular/core/testing';
import { AppraisalScoreTrace } from '@vt/api-client/domains';
import { ScoreTrace } from './score-trace';

const TRACE: AppraisalScoreTrace = {
  profileId: '0192a1b0-3c4d-7e5f-8a9b-000000000001',
  scoreResultId: '0192a1b0-3c4d-7e5f-8a9b-000000000002',
  policyVersion: '2.1.0',
  steps: [
    {
      stepNo: 1,
      ruleCode: 'kpi_nhiem_vu',
      legalRef: 'NĐ 335/2025; PL A5; BR-09-11',
      inputs: {},
      output: {},
    },
    { stepNo: 2, ruleCode: 'kpi_nhom', legalRef: 'BR-06-06', inputs: {}, output: {} },
    { stepNo: 3, ruleCode: 'diem_ket_qua', inputs: {}, output: {} },
  ],
};

describe('Vết tính: dòng căn cứ', () => {
  it('chỉ hiện căn cứ pháp lý, ẩn dòng khi chỉ có mã nội bộ', async () => {
    const fixture = TestBed.createComponent(ScoreTrace);
    fixture.componentRef.setInput('trace', TRACE);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const refs = [...el.querySelectorAll('li')].map((li) =>
      [...li.querySelectorAll('.src')].map((p) => p.textContent?.trim()),
    );
    expect(refs).toEqual([['Căn cứ: NĐ 335/2025; PL A5'], [], []]);
    expect(el.textContent).not.toMatch(/BR-\d/);
  });
});
