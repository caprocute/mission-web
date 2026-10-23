import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { ADMIN_CAN } from '../../admin-permissions';
import { OrgTreePage } from './org-tree.page';

/**
 * WEB-018, WEB-072: SCR-0103 trên dữ liệu giả lập theo hợp đồng `org.yaml`: cây nạp lười, tìm đơn vị,
 * hai trục thời gian, lịch sử phiên bản, quyền, thay đổi tổ chức, BR-01-07.
 */
async function render(can: (k: string) => boolean = () => true) {
  TestBed.configureTestingModule({
    imports: [OrgTreePage],
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: ADMIN_CAN, useValue: can },
    ],
  });
  const fixture = TestBed.createComponent(OrgTreePage);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  await until(el, '#z3');
  return { fixture, el };
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 100; i++) {
    const e = el.querySelector(sel);
    if (e && test(e)) return;
    await new Promise((r) => setTimeout(r, 20));
  }
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const button = (el: HTMLElement, label: string) =>
  [...el.querySelectorAll('button')].find((b) => text(b).includes(label));
const items = (el: HTMLElement) => [...el.querySelectorAll('[role=treeitem]')].map(text);

async function open(el: HTMLElement, q: string, name: string) {
  const input = el.querySelector('#org-q') as HTMLInputElement;
  input.value = q;
  input.dispatchEvent(new Event('input'));
  await until(el, '.res', () => !!button(el, name));
  button(el, name)!.click();
  await until(el, '#z3', (e) => text(e).startsWith(name));
}

describe('OrgTreePage (SCR-0103)', () => {
  it('cây mở sẵn cấp gốc, chỉ có đơn vị đang hiệu lực', async () => {
    const { el } = await render();
    await until(el, '[role=treeitem]', () => items(el).length > 3);
    expect(items(el)[0]).toContain('Đảng bộ tỉnh Thái Nguyên');
    expect(items(el).some((t) => t.includes('UBND tỉnh'))).toBe(true);
    expect(items(el).some((t) => t.includes('Huyện Phú Bình'))).toBe(false);
    expect(text(el.querySelector('#z3'))).toBe('Đảng bộ tỉnh Thái Nguyên');
  });

  it('tìm đơn vị mở nhánh, hiện chi tiết, tiền thân, lịch sử và phân công', async () => {
    const { el } = await render();
    await open(el, 'so noi vu', 'Sở Nội vụ');
    await until(el, '.tl li', () => text(el.querySelector('.tl')).includes('Hợp nhất'));
    const card = () => text(el.querySelector('section[aria-labelledby=z3]'));
    await until(el, '#z3', () => card().includes('Sở Lao động'));
    expect(card()).toContain('000.20.21.H56');
    expect(card()).toContain('Khối 4 · cơ quan hành chính');
    expect(card()).toContain('UBND tỉnh');
    expect(card()).toContain('Sở Nội vụ (cũ); Sở Lao động – Thương binh và Xã hội');
    expect(text(el.querySelector('.tl'))).toContain('512/QĐ-UBND');
    await until(el, '.tbl tbody tr', () => text(el.querySelector('.tbl')).includes('Biệt phái'));
    expect(text(el.querySelector('.tbl'))).toContain('Điều động');
    expect(el.querySelector('[role=treeitem][aria-selected=true]')?.textContent).toContain(
      'Sở Nội vụ',
    );
  });

  it('nhánh sâu nạp lười; lịch sử đổi tên ghi muộn', async () => {
    const { el } = await render();
    await open(el, 'cong chuc', 'Phòng Công chức, viên chức');
    expect(items(el).some((t) => t.includes('Phòng Công chức, viên chức'))).toBe(true);
    await until(el, '.tl li', () => text(el.querySelector('.tl')).includes('Đổi tên'));
    expect(text(el.querySelector('.tl'))).toContain('96/QĐ-SNV');
    expect(text(el.querySelector('.tl'))).toContain('ghi muộn 1 ngày');
    // đơn vị không lộ mã trạng thái hay tên trường thô
    expect(text(el.querySelector('.tl'))).not.toMatch(/DOI_TEN|changedFields|name/);
  });

  it('xem tại ngày: MSG-01-08, chỉ đọc, cơ cấu trước sắp xếp', async () => {
    const { el } = await render();
    button(el, 'Xem tại ngày')!.click();
    await until(el, '.warnbox', (e) => text(e).startsWith('Đang xem'));
    expect(text(el.querySelector('.warnbox'))).toBe(
      'Đang xem cơ cấu tại ngày 30/06/2025. Chế độ này chỉ đọc.',
    );
    expect(button(el, 'Thay đổi tổ chức')).toBeUndefined();
    expect(button(el, 'Nhập từ Excel')).toBeUndefined();
    await until(el, '[role=treeitem]', () =>
      items(el).some((t) => t === 'Huyện Phú Bình ▸' || t.includes('Huyện Phú Bình')),
    );
    expect(items(el).some((t) => t.includes('Đảng uỷ xã Phú Bình'))).toBe(false);
  });

  it('chỉ có quyền xem: không có Sửa, Thay đổi tổ chức, Nhập từ Excel', async () => {
    const { el } = await render((k) => k === 'SCR-0103.xem');
    await open(el, 'tu phap', 'Sở Tư pháp');
    expect(button(el, 'Sửa')).toBeUndefined();
    expect(button(el, 'Thay đổi tổ chức')).toBeUndefined();
    expect(button(el, 'Nhập từ Excel')).toBeUndefined();
  });

  it('đổi tên gửi quyết định có ngày ban hành, báo đã ghi quyết định', async () => {
    const { el } = await render();
    await open(el, 'tu phap', 'Sở Tư pháp');
    button(el, 'Thay đổi tổ chức')!.click();
    await until(el, '#oc-names');
    const type = (sel: string, v: string) => {
      const i = el.querySelector(sel) as HTMLInputElement;
      i.value = v;
      i.dispatchEvent(new Event('input'));
    };
    expect((el.querySelector('#oc-order-date') as HTMLInputElement).value).toBeTruthy();
    type('#oc-names', 'Sở Tư pháp tỉnh Thái Nguyên');
    type('#oc-order', '10/QĐ-UBND');
    await until(el, 'button', () => button(el, 'Ghi thay đổi')?.disabled === false);
    button(el, 'Ghi thay đổi')!.click();
    await until(el, 'p-message', () => text(el).includes('Đã ghi quyết định 10/QĐ-UBND'));
    expect(text(el)).toContain('Đã ghi quyết định 10/QĐ-UBND (đổi tên), có hiệu lực từ ngày');
  });

  it('sáp nhập thiếu đơn vị tiền thân hiện MSG-01-09 ngay trong hộp', async () => {
    const { el } = await render();
    await open(el, 'tu phap', 'Sở Tư pháp');
    button(el, 'Thay đổi tổ chức')!.click();
    await until(el, 'input[name=kind]');
    const merge = [...el.querySelectorAll<HTMLInputElement>('input[name=kind]')].find(
      (i) => i.value === 'SAP_NHAP',
    )!;
    merge.click();
    const order = el.querySelector('#oc-order') as HTMLInputElement;
    order.value = '9/QĐ';
    order.dispatchEvent(new Event('input'));
    await until(el, 'button', () => button(el, 'Ghi thay đổi')?.disabled === false);
    button(el, 'Ghi thay đổi')!.click();
    await until(el, 'small.err');
    expect(text(el.querySelector('small.err'))).toBe(
      'Sáp nhập cần khai báo ít nhất một đơn vị tiền thân.',
    );
  });
});
