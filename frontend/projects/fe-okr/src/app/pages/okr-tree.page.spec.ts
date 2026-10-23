import { HttpResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { OKR_CAN, OkrPermissionKey } from '../okr-permissions';
import { OkrTreePage } from './okr-tree.page';

/** WEB-021: SCR-0201 dựng trên dữ liệu giả lập, ẩn/hiện chức năng theo quyền và điều kiện dữ liệu. */
async function render(
  can: (k: OkrPermissionKey) => boolean = () => true,
  url = '/',
  elderly = false,
) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: '', component: OkrTreePage },
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
  if (elderly) {
    const theme = TestBed.inject(ThemeService);
    theme.mode.set('elderly');
    theme.reducedSteps.set(true);
  }
  await TestBed.inject(Router).navigateByUrl(url);
  const fixture = TestBed.createComponent(OkrTreePage);
  fixture.autoDetectChanges();
  await until(fixture.nativeElement, 'h1');
  return fixture;
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 80; i++) {
    const e = el.querySelector(sel);
    if (e && test(e)) return;
    await new Promise((r) => setTimeout(r, 20));
  }
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const buttons = (el: HTMLElement) => [...el.querySelectorAll('button')].map(text);

describe('OkrTreePage (SCR-0201)', () => {
  it('hiện căn cứ, mục tiêu cấp trên, ba mục tiêu với tiến độ và KR tự tin thấp được chọn sẵn', async () => {
    const el = (await render()).nativeElement as HTMLElement;
    await until(el, 'vt-kr-side-panel table');
    expect(text(el.querySelector('.head'))).toContain(
      'Ban Tổ chức Đảng uỷ khối · Quý IV/2026 · Tuần 4/13',
    );
    expect(text(el.querySelector('.strip'))).toContain('O-ĐUK-1');
    expect(text(el.querySelector('.strip'))).toContain('3/3 mục tiêu trong kỳ');
    const objs = [...el.querySelectorAll('vt-objective-node > .obj')];
    expect(objs).toHaveLength(3);
    expect(text(objs[0])).toContain('64%');
    expect(text(objs[1])).toContain('51%');
    // O3 đang soạn (WEB-022): chỉ mục tiêu NHAP của đơn vị có nút "Sửa"
    expect(text(objs[2])).toContain('Nháp');
    expect(objs.map((o) => !!o.querySelector('.editbtn'))).toEqual([false, false, true]);
    expect(text(el.querySelector('vt-kr-side-panel'))).toContain('KR 1.3 · lịch sử check-in');
    expect(text(el.querySelector('vt-kr-side-panel'))).toContain('Tuần 4 (12/10)');
    expect(buttons(el)).toContain('Check-in KR 1.3');
  });

  it('mở mục tiêu cấp dưới theo yêu cầu (tầng 2, tầng 3)', async () => {
    const el = (await render()).nativeElement as HTMLElement;
    await until(el, '.toggle');
    expect(el.querySelector('.children')).toBeNull();
    (el.querySelector('.toggle') as HTMLButtonElement).click();
    await until(el, '.children');
    expect(text(el.querySelector('.children'))).toContain('Phòng Tổ chức cơ sở đảng');
  });

  it('chỉ có quyền xem: ẩn Thêm mục tiêu, Sửa và Check-in', async () => {
    const el = (await render((k) => k === 'SCR-0201.xem')).nativeElement as HTMLElement;
    await until(el, 'vt-kr-side-panel table');
    expect(buttons(el)).not.toContain('Thêm mục tiêu');
    expect(el.querySelector('.editbtn')).toBeNull();
    expect(buttons(el).some((b) => b.startsWith('Check-in'))).toBe(false);
  });

  it('KR không thuộc người xem thì không có nút Check-in dù có quyền', async () => {
    const el = (await render(() => true, '/?kr=kr-1-2')).nativeElement as HTMLElement;
    await until(el, 'vt-kr-side-panel table');
    expect(text(el.querySelector('vt-kr-side-panel h2'))).toContain('KR 1.2');
    expect(buttons(el).some((b) => b.startsWith('Check-in'))).toBe(false);
  });

  it('kỳ đã khoá: chỉ xem, không thêm mục tiêu, không check-in', async () => {
    const el = (await render(() => true, '/?period=p-2026-q3')).nativeElement as HTMLElement;
    await until(el, 'vt-kr-side-panel');
    expect(text(el.querySelector('.head'))).toContain('kỳ đã khoá');
    expect(buttons(el)).not.toContain('Thêm mục tiêu');
    expect(buttons(el).some((b) => b.startsWith('Check-in'))).toBe(false);
  });

  it('kỳ chưa có mục tiêu hiện MSG-02-02', async () => {
    const el = (await render(() => true, '/?period=p-2027-q1')).nativeElement as HTMLElement;
    await until(el, 'p-message', (e) => text(e).includes('chưa có mục tiêu'));
    expect(text(el)).toContain(
      'Kỳ Quý I/2027 chưa có mục tiêu. Bấm "Thêm mục tiêu" hoặc sao chép từ kỳ trước.',
    );
  });

  it('chế độ Bảng ảo hoá: cây lớn chỉ dựng một phần dòng', async () => {
    const el = (await render(() => true, '/?unit=u-duk&view=table')).nativeElement as HTMLElement;
    await until(el, '.vtbl [role=row] + *');
    await until(el, 'cdk-virtual-scroll-viewport [role=row]');
    const total = Number(el.querySelector('.vtbl')!.getAttribute('aria-rowcount'));
    const rendered = el.querySelectorAll('cdk-virtual-scroll-viewport [role=row]').length;
    expect(total).toBeGreaterThan(900);
    expect(rendered).toBeLessThan(100);
    expect(buttons(el)).not.toContain('Thêm mục tiêu');
  });

  it('chế độ Đảng viên cao tuổi giảm bước: chỉ còn chế độ Cây, ẩn Xuất mẫu', async () => {
    const el = (await render(() => true, '/?view=table', true)).nativeElement as HTMLElement;
    await until(el, 'vt-objective-node');
    expect(el.querySelector('.seg')).toBeNull();
    expect(buttons(el)).not.toContain('Xuất mẫu');
    expect(el.querySelector('vt-objective-node')).not.toBeNull();
  });

  it('máy chủ trả thân rỗng: hiện lỗi kèm nút Thử lại, không treo ở "Đang tải"', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '', component: OkrTreePage }]),
        provideHttpClient(
          withInterceptors([
            (req, next) =>
              req.url.includes('/okr/tree')
                ? of(new HttpResponse({ status: 200, body: null }))
                : next(req),
          ]),
        ),
        { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
        { provide: OKR_CAN, useValue: () => true },
      ],
    });
    await TestBed.inject(Router).navigateByUrl('/');
    const fixture = TestBed.createComponent(OkrTreePage);
    fixture.autoDetectChanges();
    const el = fixture.nativeElement as HTMLElement;
    await until(el, '.msg');
    expect(text(el)).toContain('Chưa nhận được dữ liệu cây mục tiêu');
    expect(text(el)).not.toContain('Đang tải');
    expect(buttons(el)).toContain('Thử lại');
  });
});
