import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { Component, Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
  resetMockApi,
} from '@vt/api-client';
import { DASHBOARD_CAN, DASHBOARD_USER_ID } from '../dashboard-permissions';
import { DashboardBuilderPage } from './dashboard-builder.page';
import { DashboardViewPage } from './dashboard-view.page';
import { LeaderDashboardPage } from './leader-dashboard.page';
import { MyDashboardsPage } from './my-dashboards.page';
import { TracePage } from './trace.page';
import { UnitDrillPage } from './unit-drill.page';

@Component({ template: '' })
class Blank {}

let instance: unknown;

/** WEB-047, WEB-048: SCR-1601 … SCR-1603 trên dữ liệu giả lập. */
async function render<T>(
  cmp: Type<T>,
  inputs: Record<string, unknown> = {},
  can: (k: string) => boolean = () => true,
): Promise<HTMLElement> {
  await resetMockApi();
  TestBed.configureTestingModule({
    imports: [cmp],
    providers: [
      provideRouter([{ path: '**', component: Blank }]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: DASHBOARD_CAN, useValue: can },
      { provide: DASHBOARD_USER_ID, useValue: () => 'dev-user' },
    ],
  });
  const fixture = TestBed.createComponent(cmp);
  for (const [k, v] of Object.entries(inputs)) fixture.componentRef.setInput(k, v);
  instance = fixture.componentInstance;
  fixture.autoDetectChanges();
  return fixture.nativeElement as HTMLElement;
}

async function until(
  el: HTMLElement | Document,
  sel: string,
  test: (e: Element) => boolean = () => true,
) {
  for (let i = 0; i < 200; i++) {
    const e = [...el.querySelectorAll(sel)].find(test);
    if (e) return e;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error(`Không thấy ${sel}`);
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const has = (s: string) => (e: Element) => text(e).includes(s);

async function press(el: HTMLElement | Document, label: string) {
  const b = (await until(
    el,
    'button',
    (e) => text(e) === label && !(e as HTMLButtonElement).disabled,
  )) as HTMLButtonElement;
  b.click();
}

/** Không hiện mã màn hình, mã thông báo, tên chỉ số, mã trạng thái hay mã nội bộ (YC-PT-10). */
const TECH =
  /\b(BR|MSG|QT|SCR|TL)-\d|okr\.|task\.|appraisal\.|org_unit|TOM_TAT|DA_XAC_NHAN|DA_DUYET|ledger:|ss-2026|viettel|THE_SO|RIENG|DON_VI/;

describe('LeaderDashboardPage (SCR-1601)', () => {
  it('bốn con số, bảng đơn vị, xu hướng, tóm tắt AI có nhãn và nguồn, việc cần chỉ đạo', async () => {
    const el = await render(LeaderDashboardPage);
    await until(el, '.kpi', has('Việc chậm cần chỉ đạo'));
    const kpis = [...el.querySelectorAll('.kpi')].map(text);
    expect(kpis.length).toBe(4);
    expect(kpis[2]).toContain('14');
    expect(kpis[3]).toContain('9');
    await until(el, 'tr[data-unit-id="u-vp"]');
    expect(el.querySelectorAll('table.units tbody tr').length).toBe(6);
    expect(text(el.querySelector('tr[data-unit-id="u-vp"]'))).toMatch(/41%\s*Chậm tiến độ/);
    expect(el.querySelector('tr[data-unit-id="u-vp"] a')?.getAttribute('href')).toContain(
      'units?id=u-vp',
    );
    await until(el, '.summary li');
    expect(text(el.querySelector('.summary'))).toContain('Nội dung do AI tạo');
    expect(text(el.querySelector('.summary'))).toContain('Nguồn: Số liệu tiến độ ngày 14/11/2026');
    await until(el, '.directives li[data-task-id]');
    expect(el.querySelectorAll('.directives li[data-task-id]').length).toBe(3);
    expect(text(el.querySelector('.directives'))).toContain('Trễ 6 ngày làm việc');
    expect(text(el)).toContain('Chưa có số liệu tiến độ theo từng tuần của kỳ.');
    expect(text(el)).not.toMatch(TECH);
  });

  it('bộ lọc khối, luồng: lọc theo mã của lớp ngữ nghĩa, hiện tên khối và nhãn luồng', async () => {
    const el = await render(LeaderDashboardPage);
    await until(el, 'tr[data-unit-id="u-vp"]');
    const cmp = instance as {
      optionsRes: { hasValue(): boolean };
      groupOptions(): { value: string; label: string }[];
      flowOptions(): { value: string; label: string }[];
      group: { set(v: string | null): void };
    };
    for (let i = 0; i < 200 && !cmp.optionsRes.hasValue(); i++)
      await new Promise((r) => setTimeout(r, 20));
    expect(cmp.groupOptions()).toContainEqual({ value: 'CQ_THAM_MUU', label: 'Cơ quan tham mưu' });
    expect(cmp.flowOptions()).toContainEqual({ value: 'NHA_NUOC', label: 'Khối Nhà nước' });
    cmp.group.set('DANG_BO_CO_SO');
    await until(el, 'table.units tbody', (b) => b.querySelectorAll('tr').length === 2);
    expect(el.querySelector('tr[data-unit-id="u-cs-02"]')).not.toBeNull();
  });

  it('so với quý trước thêm cột và chênh lệch; kỳ đang khoá báo số liệu chậm, chưa có tóm tắt', async () => {
    const el = await render(LeaderDashboardPage);
    await until(el, 'tr[data-unit-id="u-vp"]');
    await press(el, 'So với quý trước');
    await until(el, 'th', has('Quý trước'));
    await until(el, 'tr[data-unit-id="u-vp"]', has('55%'));
    await until(el, '.kpi', has('so với quý trước'));
    (instance as { periodId: { set(v: string): void } }).periodId.set('p-2026q3');
    await until(el, 'p-message', has('chậm hơn thường lệ'));
    await until(el, '.summary', has('Chưa có tóm tắt tuần này'));
  });

  it('nhắc, giao lại thành công; giao lại việc người khác giao bị máy chủ từ chối', async () => {
    const el = await render(LeaderDashboardPage);
    await until(el, '.directives li[data-task-id="t-cd-01"]');
    (el.querySelector('li[data-task-id="t-cd-01"] p-button button') as HTMLButtonElement).click();
    const ok = await until(el, 'p-message', has('Đã gửi nhắc'));
    expect(text(ok)).toContain('Báo cáo tổng kết công tác văn phòng năm 2026');
    const cmp = instance as {
      openReassign(t: unknown): void;
      reassign(t: unknown): void;
      leadId: { set(v: string): void };
      reason: { set(v: string): void };
      directives(): { id: string }[];
    };
    const [t1, t2] = cmp.directives();
    cmp.openReassign(t2);
    cmp.reassign(t2);
    await until(document, '.err', has('Chọn người chủ trì mới'));
    cmp.leadId.set('p-dung');
    cmp.reason.set('Đồng chí chủ trì cũ đi công tác dài ngày.');
    cmp.reassign(t2);
    const denied = await until(document, 'p-message', has('không có quyền giao lại'));
    expect(text(denied)).not.toMatch(TECH);
    cmp.openReassign(t1);
    cmp.leadId.set('p-dung');
    cmp.reason.set('Đồng chí chủ trì cũ đi công tác dài ngày.');
    cmp.reassign(t1);
    await until(el, 'p-message', has('Người được giao cũ đã nhận thông báo'));
  });

  it('chỉ có quyền xem: không có nút nhắc, giao lại, xem dữ liệu đã dùng, giao việc', async () => {
    const el = await render(LeaderDashboardPage, {}, (k) => k === 'SCR-1601.xem');
    await until(el, '.directives li[data-task-id]');
    await until(el, '.summary li');
    const labels = [...el.querySelectorAll('button, a')].map(text);
    expect(labels).not.toContain('Nhắc');
    expect(labels).not.toContain('Giao lại');
    expect(labels).not.toContain('Xem dữ liệu đã dùng');
    expect(labels).not.toContain('Giao việc');
    expect(el.querySelector('tr[data-unit-id="u-vp"] a')).toBeNull();
  });
});

describe('UnitDrillPage và TracePage (SCR-1603)', () => {
  it('đơn vị: số tổng hợp của đơn vị, chưa có danh sách cán bộ', async () => {
    const el = await render(UnitDrillPage, { id: 'u-vp', name: 'Văn phòng Đảng uỷ' });
    await until(el, '.kpi', has('Điểm KPI trung bình'));
    const kpis = [...el.querySelectorAll('.kpi')].map(text);
    expect(kpis).toEqual([
      'Điểm KPI trung bình80,6',
      'Số cán bộ đã có điểm3',
      'Tỷ lệ nhiệm vụ đúng hạn70%',
    ]);
    expect(text(el)).toContain('Bảng điều hành chỉ có số liệu tổng hợp của đơn vị.');
    expect(el.querySelector('a[href*="trace"]')).toBeNull();
    expect(text(el)).not.toMatch(TECH);
  });

  it('chuỗi tám mắt xích; minh chứng có phiên bản và thông báo phiên bản được tính điểm', async () => {
    const el = await render(TracePage, { objectType: 'MINH_CHUNG', id: 'mc-2026q4-bc-so-ket' });
    await until(el, '.chain li');
    expect(el.querySelectorAll('.chain li').length).toBe(8);
    expect(el.querySelector('.chain')?.getAttribute('data-ledger-ref')).toBeTruthy();
    expect(el.querySelector('.chain li.focus')?.getAttribute('data-kind')).toBe('MINH_CHUNG');
    expect(text(el)).toContain('Minh chứng này đã có 3 phiên bản. Phiên bản được tính điểm là v3.');
    expect(el.querySelectorAll('table.versions tbody tr').length).toBe(3);
    expect(text(el)).toContain('Mọi phiên bản được giữ nguyên, không ghi đè.');
    expect(text(el)).toContain('Kết quả này góp phần thực hiện Mục tiêu O1');
    expect(text(el)).not.toMatch(TECH);
    await press(el, 'Xuất chuỗi bằng chứng');
    await until(el, 'p-message', has('Đã nhận yêu cầu xuất chuỗi bằng chứng'));
  });

  it('mắt xích ngoài phạm vi hiện dạng che với thông báo không đủ quyền; KPI có bảng A, B, C', async () => {
    const el = await render(TracePage, { objectType: 'DIEM', id: 'ss-2026q4-le-thu-trang' });
    await until(el, '.chain li.masked');
    expect(text(el)).toContain(
      'Đồng chí không có quyền xem chi tiết minh chứng thuộc Ban Tuyên giáo.',
    );
    TestBed.resetTestingModule();
    const kpi = await render(TracePage, { objectType: 'KPI_NHIEM_VU', id: 'kpi-2026q4-hoang-nv2' });
    await until(kpi, 'td', has('Chất lượng (B)'));
    expect(text(kpi)).toContain('KPI nhiệm vụ = (A + B + C) / 3 = 0,783');
  });
});

describe('Dashboard tự dựng (SCR-1602)', () => {
  it('danh sách của tôi và được chia sẻ; trang xem dựng đủ biểu đồ', async () => {
    const el = await render(MyDashboardsPage);
    await until(el, 'tr[data-dashboard-id]');
    expect(text(el.querySelector('tr[data-dashboard-id="db-bang-bi-thu"]'))).toContain('Của tôi');
    expect(text(el.querySelector('tr[data-dashboard-id="db-khoi-tham-muu"]'))).toContain(
      'Được chia sẻ',
    );
    TestBed.resetTestingModule();
    const v = await render(DashboardViewPage, { id: 'db-bang-bi-thu' });
    await until(v, '[data-widget-id="w4"] .bars li');
    expect(v.querySelectorAll('[data-widget-id]').length).toBe(4);
    expect(text(v.querySelector('[data-widget-id="w3"]'))).toContain('Lọc: Luồng là Khối Đảng');
    expect([...v.querySelectorAll('a')].map(text)).toContain('Sửa');
    expect(text(v)).not.toMatch(TECH);
    TestBed.resetTestingModule();
    const s = await render(DashboardViewPage, { id: 'db-khoi-tham-muu' });
    await until(s, '[data-widget-id="w1"] .bars li');
    expect(text(s)).toContain('Đồng chí đang xem số liệu trong phạm vi quản lý của mình.');
    expect([...s.querySelectorAll('a')].map(text)).not.toContain('Sửa');
  });

  it('dựng mới: thêm chỉ số, kết hợp sai báo lỗi, trùng tên bị từ chối, lưu được', async () => {
    const el = await render(DashboardBuilderPage);
    await until(el, '[data-measure="okr.tien_do_o"]');
    const cmp = instance as {
      addWidget(m: string): void;
      addDimension(d: string): void;
      title: { set(v: string): void };
      save(): void;
      widgets(): { dimensions?: string[]; chartType: string }[];
    };
    cmp.addWidget('okr.tien_do_o');
    await until(el, '[data-widget-id] .stat-v');
    cmp.addDimension('task.nhom');
    await until(
      el,
      '.props .err',
      has('Chỉ số Tiến độ mục tiêu O không hỗ trợ chiều Loại nhiệm vụ.'),
    );
    await until(el, '[data-widget-id] .err', has('không hỗ trợ chiều'));
    await press(el, 'Lưu dashboard');
    await until(el, 'p-message', has('không hỗ trợ chiều Loại nhiệm vụ'));
    // bỏ chiều sai, theo đơn vị
    (instance as { setDimensions(d: string[]): void }).setDimensions(['org_unit.name']);
    expect(cmp.widgets()[0].chartType).toBe('THANH');
    cmp.title.set('Bảng của Bí thư');
    cmp.save();
    await until(el, 'p-message', has('Đồng chí đã có dashboard cùng tên'));
    cmp.title.set('Theo dõi tiến độ các đơn vị');
    cmp.save();
    await until(el, 'p-message', has('Đã lưu dashboard'));
    expect(text(el)).not.toMatch(TECH);
  });

  it('chia sẻ cho đơn vị cần chọn đơn vị; lưu chia sẻ', async () => {
    const el = await render(DashboardBuilderPage, { id: 'db-bang-bi-thu' });
    await press(el, 'Chia sẻ');
    await until(document, 'p-dialog', has('Người được chia sẻ chỉ thấy số liệu'));
    const cmp = instance as {
      shareScope: { set(v: string): void };
      shareUnits: { set(v: string[]): void };
      share(): void;
    };
    cmp.shareScope.set('DON_VI');
    cmp.share();
    await until(document, 'p-message', has('Chọn ít nhất một đơn vị được xem'));
    cmp.shareUnits.set(['u-btc']);
    cmp.share();
    await until(el, 'p-message', has('Đã chia sẻ dashboard'));
  });
});
