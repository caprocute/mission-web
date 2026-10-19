import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { VtCanDirective } from './can.directive';
import {
  PERMISSION_KEYS,
  PermissionService,
  VT_PERMISSION_OPTIONS,
  rolesCan,
  rolesFor,
} from './permission.service';
import { PERMISSION_TABLE } from './permissions.generated';
import { SessionService, VtRole, VtUser } from './session.service';

/**
 * WEB-054: bảng quyền phía giao diện sinh từ docs/phan-quyen/ma-tran.json (wiki/15a).
 * Kỳ vọng dưới đây đọc trực tiếp từ ma trận; khi ma trận đổi có chủ đích thì sửa test cùng lúc.
 */
const user = (...roles: VtRole[]): VtUser => ({ id: 'u1', name: 'Thử', tenant: 't', roles });

function setup(current: VtUser | null, allowInferred = true) {
  const session = { user: signal<VtUser | null>(current) };
  TestBed.configureTestingModule({
    providers: [
      { provide: SessionService, useValue: session },
      { provide: VT_PERMISSION_OPTIONS, useValue: { allowInferred } },
    ],
  });
  return { session, perms: TestBed.inject(PermissionService) };
}

describe('bảng quyền sinh từ ma trận', () => {
  it('có đủ 171 khoá chức năng web, đúng dạng SCR-qqnn.<khoá>', () => {
    expect(PERMISSION_KEYS.length).toBe(171);
    for (const k of PERMISSION_KEYS) expect(k).toMatch(/^SCR-\d{4}\.[a-z0-9-]+$/);
  });

  it('không chứa vai trò ngoài 10 vai trò người dùng AC-01..AC-10 và vai trò con', () => {
    const allowed = new Set([
      'platform_admin',
      'tenant_admin',
      'policy_author',
      'leader',
      'manager',
      'member',
      'appraiser',
      'decider',
      'second_approver',
      'inspector',
      'wf_template_author',
    ]);
    for (const cell of Object.values(PERMISSION_TABLE)) {
      for (const t of cell.split(' ').filter(Boolean))
        expect(allowed).toContain(t.replace('*', ''));
    }
  });

  it('SCR-0203.duyet chỉ dành cho leader', () => {
    expect(rolesFor('SCR-0203.duyet')).toEqual(['leader']);
  });

  it('SCR-0801.duyet-huy chỉ dành cho second_approver', () => {
    expect(rolesFor('SCR-0801.duyet-huy')).toEqual(['second_approver']);
  });

  it('SCR-0801.duyet-lenh dành cho leader (người phê duyệt lệnh khoá kỳ)', () => {
    expect(rolesCan(['leader'], 'SCR-0801.duyet-lenh')).toBe(true);
    expect(rolesCan(['tenant_admin'], 'SCR-0801.duyet-lenh')).toBe(false);
  });

  it('SCR-1505 chỉ dành cho vai trò con wf_template_author, không cho mọi platform_admin', () => {
    expect(rolesCan(['platform_admin'], 'SCR-1505.soan')).toBe(false);
    expect(rolesCan(['platform_admin', 'wf_template_author'], 'SCR-1505.soan')).toBe(true);
    expect(rolesFor('SCR-1505.phe-duyet')).toEqual(['second_approver']);
    expect(rolesCan(['platform_admin'], 'SCR-1506.xem')).toBe(true);
  });

  it('chức năng chỉ hệ thống hoặc AI tham gia không cho vai trò người dùng nào', () => {
    expect(rolesFor('SCR-0502.ai-trich')).toEqual([]);
  });

  it('tắt ô suy luận thì bỏ vai trò mang dấu *', () => {
    const inferredKey = PERMISSION_KEYS.find((k) => PERMISSION_TABLE[k].includes('*'));
    expect(inferredKey).toBeDefined();
    const k = inferredKey!;
    const role = PERMISSION_TABLE[k]
      .split(' ')
      .find((t) => t.endsWith('*'))!
      .slice(0, -1) as VtRole;
    expect(rolesCan([role], k, true)).toBe(true);
    expect(rolesCan([role], k, false)).toBe(false);
  });
});

describe('PermissionService', () => {
  it('người chưa đăng nhập không có quyền nào', () => {
    const { perms } = setup(null);
    expect(perms.granted().size).toBe(0);
    expect(perms.can('SCR-0503.xem')).toBe(false);
  });

  it('member: xem việc của tôi, không phê duyệt OKR, không vào Config Studio', () => {
    const { perms } = setup(user('member'));
    expect(perms.can('SCR-0503.xem')).toBe(true);
    expect(perms.can('SCR-0203.duyet')).toBe(false);
    expect(perms.canAny('SCR-1501.soan', 'SCR-1503.ky-duyet')).toBe(false);
  });

  it('platform_admin: quản trị tenant, không có thao tác nghiệp vụ', () => {
    const { perms } = setup(user('platform_admin'));
    expect(perms.can('SCR-0101.xem')).toBe(true);
    expect(perms.can('SCR-0102.kich-hoat')).toBe(true);
    expect(perms.canAny('SCR-0503.xem', 'SCR-0901.luu', 'SCR-0203.duyet')).toBe(false);
  });

  it('decider: ký phê duyệt phát hành gói chính sách ở SCR-1503', () => {
    const { perms } = setup(user('decider'));
    expect(perms.can('SCR-1503.ky-duyet')).toBe(true);
    expect(perms.can('SCR-1501.soan')).toBe(false);
  });

  it('người nhiều vai trò có hợp quyền của các vai trò', () => {
    const { perms } = setup(user('member', 'leader'));
    expect(perms.canAll('SCR-0503.xem', 'SCR-0203.duyet')).toBe(true);
    expect(perms.can('SCR-0801.duyet-huy')).toBe(false);
  });

  it('tính lại khi đổi phân công (vai trò của phiên đổi)', () => {
    const { session, perms } = setup(user('member'));
    expect(perms.can('SCR-0203.duyet')).toBe(false);
    session.user.set(user('leader'));
    expect(perms.can('SCR-0203.duyet')).toBe(true);
    session.user.set(null);
    expect(perms.can('SCR-0203.duyet')).toBe(false);
  });

  it('canAll với danh sách rỗng trả false', () => {
    const { perms } = setup(user('leader'));
    expect(perms.canAll()).toBe(false);
  });
});

@Component({
  imports: [VtCanDirective],
  template: `
    <button *vtCan="'SCR-0203.duyet'; else khong" id="duyet">Phê duyệt</button>
    <ng-template #khong><span id="khong">Không có quyền</span></ng-template>
    <span *vtCan="['SCR-0103.xem', 'SCR-0801.xem']" id="quan-tri">Quản trị</span>
  `,
})
class HostComponent {}

describe('*vtCan', () => {
  it('ẩn nút và hiện nhánh else khi không có quyền, hiện lại khi đổi vai trò', async () => {
    const { session } = setup(user('member'));
    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('#duyet')).toBeNull();
    expect(el.querySelector('#khong')).not.toBeNull();
    expect(el.querySelector('#quan-tri')).toBeNull();

    session.user.set(user('leader'));
    await fixture.whenStable();
    expect(el.querySelector('#duyet')).not.toBeNull();
    expect(el.querySelector('#khong')).toBeNull();
  });

  it('mảng khoá: hiện khi có ít nhất một chức năng', async () => {
    setup(user('inspector'));
    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('#quan-tri')).not.toBeNull();
  });
});
