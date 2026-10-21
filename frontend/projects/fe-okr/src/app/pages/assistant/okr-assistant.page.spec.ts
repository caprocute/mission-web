import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  ObjectiveDetail,
  OkrApi,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { firstValueFrom } from 'rxjs';
import { resetMockData } from '../../../../../api-client/src/mock/mock-registry';
import { OKR_CAN, OkrPermissionKey } from '../../okr-permissions';
import { applyRewrite, msg1701, vagueCodes } from './assistant-rules';
import { OkrAssistantPage } from './okr-assistant.page';

/** WEB-049: SCR-1701 trên dữ liệu giả lập: hỏi, soát KR, dùng hoặc bỏ gợi ý, rào chắn, quyền. */
async function render(can: (k: OkrPermissionKey) => boolean = () => true) {
  resetMockData();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'tro-ly', component: OkrAssistantPage },
        { path: '**', children: [] },
      ]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: OKR_CAN, useValue: can },
    ],
  });
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl('/tro-ly', OkrAssistantPage);
  const el = harness.routeNativeElement as HTMLElement;
  harness.fixture.autoDetectChanges();
  await until(el, '[data-objective]');
  return el;
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 100; i++) {
    const e = [...el.querySelectorAll(sel)].find(test);
    if (e) return e;
    await new Promise((r) => setTimeout(r, 20));
  }
  return null;
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

async function ask(el: HTMLElement, q: string) {
  const t = el.querySelector('#as-q') as HTMLTextAreaElement;
  t.value = q;
  t.dispatchEvent(new Event('input'));
  (el.querySelector('[data-act="hoi"]') as HTMLButtonElement).click();
  await until(el, '[data-turn] .who + *', (e) => !/đang soạn/.test(text(e)));
}

describe('SCR-1701 quy tắc (WEB-049)', () => {
  it('MSG-17-01 và KR chờ quyết định', () => {
    expect(msg1701('Tăng cường', 'Kiểm tra 100%')).toBe(
      'KR "Tăng cường" chưa đo được. Gợi ý: Kiểm tra 100%.',
    );
    const s = [
      { id: 'a', kind: 'SOAT_KR' as const, krCode: 'KR 3.3', sources: [] },
      { id: 'b', kind: 'LIEN_KET_NGANG' as const, krCode: 'KR 3.2', sources: [] },
    ];
    expect([...vagueCodes(s, {})]).toEqual(['KR 3.3']);
    expect(vagueCodes(s, { a: 'BO_QUA' }).size).toBe(0);
  });

  it('viết lại KR: lấy tỷ lệ làm chỉ tiêu khi KR chưa có, gắn cờ AI', () => {
    const d = {
      id: 'o',
      keyResults: [
        { code: 'KR 1', title: 'x', targetValue: null, unit: '', contentSource: 'HUMAN' },
      ],
    } as unknown as ObjectiveDetail;
    const k = applyRewrite(d, 'KR 1', 'Kiểm tra 100% chi bộ').keyResults[0];
    expect(k).toMatchObject({ targetValue: 100, unit: '%', contentSource: 'AI_ACCEPTED' });
  });
});

describe('SCR-1701 màn hình (WEB-049)', () => {
  it('soát bản nháp: KR mơ hồ có MSG-17-01, dùng gợi ý ghi vào nháp và hiện MSG-17-04', async () => {
    const el = await render();
    await ask(el, 'Soát giúp các KR trong bản nháp');
    const warn = await until(el, '[data-msg="MSG-17-01"]');
    expect(text(warn)).toContain('Tăng cường công tác kiểm tra, giám sát');
    expect(el.querySelector('[data-kr="KR 3.3"]')?.classList).toContain('vague');
    const card = el.querySelector('[data-kind="SOAT_KR"]')!;
    (card.querySelector('[data-act="dung-goi-y"]') as HTMLButtonElement).click();
    expect(text(await until(el, '[data-msg="MSG-17-04"]'))).toContain('AI hỗ trợ');
    const d = await firstValueFrom(TestBed.inject(OkrApi).objective('o-3'));
    const kr = d.keyResults.find((k) => k.code === 'KR 3.3')!;
    expect(kr.title).toBe('Kiểm tra 100% chi bộ trực thuộc trước 31/3/2027');
    expect(kr.contentSource).toBe('AI_ACCEPTED');
    await until(el, '[data-kr="KR 3.3"]', (e) => !e.classList.contains('vague'));
    expect(el.querySelector('[data-kr="KR 3.3"]')?.classList).not.toContain('vague');
  });

  it('gợi ý mục tiêu: dùng gợi ý tạo bản nháp mới có nhãn AI; bỏ qua ghi quyết định', async () => {
    const el = await render();
    await ask(el, 'Soạn OKR quý cho đơn vị');
    await until(el, '[data-kind="MUC_TIEU"]');
    const cards = [...el.querySelectorAll('[data-kind="MUC_TIEU"]')];
    expect(cards.length).toBe(2);
    expect(text(el.querySelector('[data-turn]'))).toContain('Nội dung do AI tạo');
    (cards[1].querySelector('[data-act="bo-qua"]') as HTMLButtonElement).click();
    await until(el, '[data-decided]');
    (cards[0].querySelector('[data-act="dung-goi-y"]') as HTMLButtonElement).click();
    await until(el, '[data-msg="MSG-17-04"]');
    const n = await until(el, '[data-objective]', (e) => !!e.querySelector('[data-ai]'));
    expect(n).toBeTruthy();
    expect(el.querySelectorAll('[data-decided]').length).toBe(2);
  });

  it('rào chắn chặn câu hỏi về xếp loại (MSG-17-02)', async () => {
    const el = await render();
    await ask(el, 'Đề xuất xếp loại cho đồng chí An');
    const m = await until(el, '[data-msg="MSG-17-02"]');
    expect(text(m)).toContain('không thực hiện chấm điểm');
    expect(el.querySelector('[data-kind]')).toBeNull();
  });

  it('trợ lý bận: trả 202, hiện kết quả sau khi tác vụ xong', async () => {
    // Giả lập đọc cờ `mock-ai` từ URL của trang
    history.replaceState(null, '', '/?mock-ai=ban');
    try {
      const el = await render();
      await ask(el, 'Soạn OKR quý');
      expect(await until(el, '[data-kind="MUC_TIEU"]')).toBeTruthy();
    } finally {
      history.replaceState(null, '', '/');
    }
  });

  it('chỉ có quyền hỏi: không có nút dùng gợi ý, không trình', async () => {
    const el = await render((k) => k === 'SCR-1701.hoi');
    expect(el.querySelector('[data-act="trinh"]')).toBeNull();
    await ask(el, 'Soạn OKR quý');
    await until(el, '[data-kind="MUC_TIEU"]');
    expect(el.querySelector('[data-act="dung-goi-y"]')).toBeNull();
    expect(el.querySelector('[data-act="bo-qua"]')).toBeNull();
  });
});
