import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ComponentFixture,
  DeferBlockBehavior,
  DeferBlockState,
  TestBed,
} from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { DEFAULT_RUNTIME_CONFIG, MOCK_API_OPTIONS, RUNTIME_CONFIG, idempotencyInterceptor, mockApiInterceptor, mockStepUp } from '@vt/api-client';
import { lintBpmn } from '@vt/api-client/domains';
import { PermissionKey } from '@vt/auth';
import { resetMockData } from '../../../../../api-client/src/mock/mock-registry';
import { buildBpmn } from '../../../../../api-client/src/mock/workflow-templates/workflow-templates.mock';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { STUDIO_STEP_UP } from '../../studio-step-up';
import {
  insertBlock,
  paramErrors,
  paramsFromSchema,
  readDiagram,
  schemaFromParams,
} from './wf-model';
import { WfTemplatesPage } from './wf-templates.page';

/** WEB-061: SCR-1505 trên dữ liệu giả lập; quyền theo ma trận wiki/15a. */
const AUTHOR: PermissionKey[] = [
  'SCR-1505.thu-vien',
  'SCR-1505.soan',
  'SCR-1505.chay-test',
  'SCR-1505.de-nghi-phat-hanh',
  'SCR-1505.trien-khai-cell',
];
const APPROVER: PermissionKey[] = ['SCR-1505.thu-vien', 'SCR-1505.phe-duyet'];
const id = (n: number) => `0192f5f7-8091-72a3-b4c5-${String(n).padStart(12, '0')}`;

let fx: ComponentFixture<WfTemplatesPage>;

async function render(keys: PermissionKey[], templateId: string, ready: string) {
  resetMockData();
  await TestBed.configureTestingModule({
    imports: [WfTemplatesPage],
    // Trình soạn kéo thả nạp lười; các ca ở đây dùng phần giữ chỗ là sơ đồ chỉ xem.
    deferBlockBehavior: DeferBlockBehavior.Manual,
    providers: [
      provideRouter([]),
      provideNoopAnimations(),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      {
        provide: STUDIO_ACCESS,
        useValue: {
          can: (k: PermissionKey) => keys.includes(k),
          author: (k: PermissionKey) => keys.includes(k),
        },
      },
      {
        provide: STUDIO_STEP_UP,
        useValue: async () => {
          mockStepUp();
          return true;
        },
      },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(WfTemplatesPage);
  fx = fixture;
  fixture.componentRef.setInput('templateId', templateId);
  fixture.autoDetectChanges();
  await until(fixture.nativeElement, ready);
  return fixture.nativeElement as HTMLElement;
}

async function until(
  el: ParentNode,
  sel: string,
  test: (e: Element) => boolean = () => true,
  tries = 500,
) {
  for (let i = 0; i < tries; i++) {
    const e = el.querySelector(sel);
    if (e && test(e)) return e;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error(`Không thấy ${sel}`);
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const button = (el: ParentNode, tid: string) =>
  el.querySelector(`[data-testid=${tid}] button`) as HTMLButtonElement;
const type = (el: HTMLInputElement | HTMLTextAreaElement, v: string) => {
  el.value = v;
  el.dispatchEvent(new Event('input'));
};

describe('Trình soạn mẫu quy trình · logic', () => {
  it('tham số hai chiều với JSON Schema và bắt lỗi khai báo', () => {
    const schema = {
      type: 'object',
      properties: {
        soCap: { type: 'integer', title: 'Số cấp duyệt', default: 2, minimum: 1, maximum: 4 },
        cach: { type: 'string', title: 'Cách nghiệm thu', default: 'A', enum: ['A', 'B'] },
        hoiDong: { type: 'boolean', title: 'Có hội đồng', default: false },
      },
    };
    const ps = paramsFromSchema(schema);
    expect(ps.map((p) => p.type)).toEqual(['integer', 'choice', 'boolean']);
    expect(schemaFromParams(ps)).toEqual(schema);
    expect(paramErrors(ps)).toEqual({});
    expect(Object.values(paramErrors([{ ...ps[0], def: '9' }]))[0]).toContain('ngoài miền');
    expect(Object.values(paramErrors([ps[0], { ...ps[0] }]))[0]).toContain('trùng');
  });

  it('khối dựng sẵn chèn vào sơ đồ vẫn đạt luật và có hình vẽ', () => {
    const base = buildBpmn('p', 'Thử', [{ id: 'a', name: 'Duyệt', kind: 'user' }]);
    for (const b of ['ky-so', 'ky-lo', 'leo-thang'] as const) {
      const next = insertBlock(base, b)!;
      expect(lintBpmn(next)).toEqual([]);
      expect(readDiagram(next).shapes.length).toBeGreaterThan(readDiagram(base).shapes.length);
    }
  });
});

describe('SCR-1505 Thư viện và trình soạn mẫu quy trình', () => {
  it('người soạn: sửa sơ đồ thấy lỗi tô đỏ, lưu, chạy kiểm thử rồi đề nghị phát hành', async () => {
    const el = await render(AUTHOR, id(2), '[data-testid=save]');
    expect(text(el.querySelector('[data-testid=library]'))).toContain('Duyệt hồ sơ đánh giá');
    const src = el.querySelector('#wf-src') as HTMLTextAreaElement;
    const ok = src.value;
    type(src, ok.replace('flowable:type="external-worker"', ''));
    await until(el, '[data-testid=lint] li');
    expect(text(el.querySelector('[data-testid=lint]'))).toContain('tác vụ nhận việc từ hàng đợi');
    expect(el.querySelector('[data-el=kySo].bad')).not.toBeNull();
    expect(button(el, 'save').disabled).toBe(true);

    type(src, ok.replace('name="Hội đồng xem xét"', 'name="Hội đồng thẩm định"'));
    await until(el, '[data-testid=save] button', (b) => !(b as HTMLButtonElement).disabled);
    button(el, 'save').click();
    await until(el, '[role=status]', (e) => text(e).includes('Đã lưu'));
    button(el, 'run-tests').click();
    await until(el, '[data-testid=submit] button', (b) => !(b as HTMLButtonElement).disabled, 600);
    button(el, 'submit').click();
    await until(el, '[role=status]', (e) => text(e).includes('đề nghị phát hành'));
    expect(text(el.querySelector('[data-testid=tpl-bar]'))).toContain('Chờ phê duyệt');
    expect(el.querySelector('[data-testid=approve]')).toBeNull();
  }, 30000);

  it('người soạn: trình soạn kéo thả thêm bước, nối, đổi tên và xoá trên tệp sơ đồ', async () => {
    // jsdom không có ResizeObserver mà thư viện vẽ đồ thị cần.
    const noop = () => undefined;
    globalThis.ResizeObserver ??= class {
      observe = noop;
      unobserve = noop;
      disconnect = noop;
    } as unknown as typeof ResizeObserver;
    // jsdom không có `width.baseVal` của <svg>; d3-zoom đọc nó khi tính khung nhìn trong chuyển động,
    // thiếu thì ném lỗi trong vòng hẹn giờ của d3 và tiến trình kiểm thử không thoát.
    for (const dim of ['width', 'height'] as const) {
      if (!Object.getOwnPropertyDescriptor(SVGSVGElement.prototype, dim)) {
        Object.defineProperty(SVGSVGElement.prototype, dim, {
          configurable: true,
          get: () => ({ baseVal: { value: dim === 'width' ? 800 : 600 } }),
        });
      }
    }
    const el = await render(AUTHOR, id(2), '[data-testid=save]');
    const [editor] = await fx.getDeferBlocks();
    await editor.render(DeferBlockState.Complete);
    const src = () => (el.querySelector('#wf-src') as HTMLTextAreaElement).value;
    const nodes = () => el.querySelectorAll('[data-testid^=node-]').length;
    await until(el, '[data-testid=node-batDau]');
    const start = nodes();
    (el.querySelector('[data-testid=pal-userTask]') as HTMLButtonElement).click();
    await until(el, '[data-testid=p-name]');
    expect(nodes()).toBe(start + 1);
    expect(src()).toContain('id="buoc1"');
    const name = el.querySelector('[data-testid=p-name]') as HTMLInputElement;
    name.value = 'Bước mới';
    name.dispatchEvent(new Event('change'));
    await until(el, '[data-testid=node-buoc1]', (e) =>
      e.getAttribute('aria-label')!.includes('Bước mới'),
    );
    // Bước mới chưa có đường vào, đường ra: vẫn đạt luật; xoá bằng phím Delete.
    (el.querySelector('[data-testid=node-buoc1]') as HTMLElement).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Delete', bubbles: true }),
    );
    await until(el, '[data-testid=palette]', () => nodes() === start);
    expect(src()).not.toContain('buoc1');
    // Tệp đã ghi lại nên khác bản lưu; sơ đồ vẫn đạt luật nên lưu được.
    expect(button(el, 'save').disabled).toBe(false);
    // huỷ trình soạn ngay để hẹn giờ chuyển động của d3 dừng trước ca sau
    fx.destroy();
  });

  it('người phê duyệt: chỉ đọc, phê duyệt có xác thực nhiều lớp, thấy tiến độ triển khai', async () => {
    const el = await render(APPROVER, id(4), '[data-testid=approve]');
    expect(el.querySelector('#wf-src')).toBeNull();
    expect(el.querySelector('[data-testid=save]')).toBeNull();
    button(el, 'approve').click();
    await until(el, '[data-testid=rollouts] tbody tr', () => true, 600);
    expect(text(el.querySelector('[role=status]'))).toContain('đã phát hành');
    expect(text(el.querySelector('[data-testid=rollouts]'))).toContain('Thí điểm nội bộ');
  }, 30000);
});
