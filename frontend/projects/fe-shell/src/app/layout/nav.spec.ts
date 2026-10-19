import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CanMatchFn, Route, UrlTree } from '@angular/router';
import {
  PermissionKey,
  PermissionService,
  SessionService,
  VT_PERMISSION_OPTIONS,
  VtRole,
  VtUser,
} from '@vt/auth';
import { routes } from '../app.routes';
import { NAV, SECTION_KEYS, visibleNav } from './nav';

/**
 * WEB-055: menu và tuyến cha của shell theo ma trận (wiki/15a). Bảng kỳ vọng đọc từ ma trận, tính cả
 * ô suy luận `*` (mặc định của `VT_PERMISSION_OPTIONS`); khi ma trận đổi có chủ đích thì sửa cùng lúc.
 */
const EXPECTED: Record<Exclude<VtRole, 'ai_readonly'>, string[]> = {
  platform_admin: ['/config-studio', '/admin'],
  tenant_admin: ['/config-studio', '/admin'],
  policy_author: ['/tasks', '/oversight', '/config-studio'],
  leader: ['/tasks', '/okr', '/plans', '/appraisal', '/dossier', '/dashboard', '/admin'],
  manager: [
    '/tasks',
    '/okr',
    '/plans',
    '/evidence',
    '/appraisal',
    '/dossier',
    '/oversight',
    '/dashboard',
  ],
  member: ['/tasks', '/okr', '/plans', '/evidence', '/appraisal', '/dossier', '/oversight'],
  appraiser: [
    '/plans',
    '/evidence',
    '/appraisal',
    '/dossier',
    '/oversight',
    '/dashboard',
    '/admin',
  ],
  decider: ['/appraisal', '/dossier', '/oversight', '/config-studio'],
  second_approver: ['/oversight', '/config-studio', '/admin'],
  inspector: ['/oversight', '/dashboard', '/admin'],
  wf_template_author: ['/config-studio'],
};

function setup(roles: VtRole[] | null) {
  const user: VtUser | null = roles && { id: 'u1', name: 'Thử', tenant: 't', roles };
  TestBed.configureTestingModule({
    providers: [
      { provide: SessionService, useValue: { user: signal(user) } },
      { provide: VT_PERMISSION_OPTIONS, useValue: { allowInferred: true } },
    ],
  });
  return TestBed.inject(PermissionService);
}

const menuOf = (roles: VtRole[]) => {
  TestBed.resetTestingModule();
  const perms = setup(roles);
  return visibleNav((...keys: PermissionKey[]) => perms.canAny(...keys))
    .map((i) => i.link)
    .filter((l) => l !== '/');
};

const shellChildren = routes[0].children as Route[];
const routeOf = (path: string) => shellChildren.find((r) => r.path === path) as Route;

function matches(path: string): boolean | UrlTree {
  const guard = routeOf(path).canMatch?.[0] as CanMatchFn;
  return TestBed.runInInjectionContext(() => guard(routeOf(path), []) as boolean | UrlTree);
}

describe('menu shell theo ma trận phân quyền', () => {
  for (const [role, links] of Object.entries(EXPECTED) as [VtRole, string[]][]) {
    it(`vai trò ${role} thấy đúng các mục`, () => {
      expect(menuOf([role])).toEqual(links);
    });
  }

  it('trang chủ luôn hiện, không cần khoá', () => {
    expect(NAV[0]).toMatchObject({ link: '/' });
    expect(NAV[0].perms).toBeUndefined();
  });

  it('platform_admin không thấy mục nghiệp vụ nào', () => {
    expect(menuOf(['platform_admin'])).not.toContain('/tasks');
    expect(menuOf(['platform_admin'])).not.toContain('/okr');
  });

  it('nhiều vai trò thì hợp các mục (lanhdao: member + leader)', () => {
    expect(menuOf(['member', 'leader'])).toEqual([
      '/tasks',
      '/okr',
      '/plans',
      '/evidence',
      '/appraisal',
      '/dossier',
      '/oversight',
      '/dashboard',
      '/admin',
    ]);
  });

  it('ba tuyến từng chặn quá chặt nay mở đúng TL-02', () => {
    expect(menuOf(['inspector'])).toContain('/admin'); // SCR-0103, SCR-0801
    expect(menuOf(['second_approver'])).toContain('/admin'); // SCR-0801
    expect(menuOf(['decider'])).toContain('/config-studio'); // SCR-1503
    expect(menuOf(['appraiser'])).toContain('/dashboard'); // SCR-1603
  });

  it('chưa đăng nhập thì chỉ còn trang chủ', () => {
    const perms = setup(null);
    expect(visibleNav((...k: PermissionKey[]) => perms.canAny(...k)).map((i) => i.link)).toEqual([
      '/',
    ]);
  });
});

describe('tuyến cha của shell dùng cùng nhóm khoá với menu', () => {
  const pathOf = (link: string) => link.slice(1);

  it('mọi mục menu có khoá đều có canMatch ở tuyến cùng đường dẫn, không còn roleGuard', () => {
    for (const item of NAV.filter((i) => i.perms)) {
      expect(routeOf(pathOf(item.link)).canMatch?.length).toBe(1);
      expect(routeOf(pathOf(item.link)).canActivate).toBeUndefined();
    }
  });

  it('tuyến cho qua đúng khi menu hiện, bị chặn thì chuyển sang trang không có quyền', () => {
    for (const [role, links] of Object.entries(EXPECTED) as [VtRole, string[]][]) {
      TestBed.resetTestingModule();
      setup([role]);
      for (const item of NAV.filter((i) => i.perms && i.link !== '/tasks')) {
        const result = matches(pathOf(item.link));
        if (links.includes(item.link)) expect(result, `${role} ${item.link}`).toBe(true);
        else expect(String(result), `${role} ${item.link}`).toBe('/khong-co-quyen');
      }
    }
  });

  it('/tasks mở cho cả vai trò chỉ xem chi tiết nhiệm vụ dù menu không hiện', () => {
    for (const role of ['inspector', 'appraiser'] as VtRole[]) {
      expect(menuOf([role])).not.toContain('/tasks');
      expect(matches('tasks'), role).toBe(true);
    }
    TestBed.resetTestingModule();
    setup(['platform_admin']);
    expect(String(matches('tasks'))).toBe('/khong-co-quyen');
  });

  it('mục menu dùng tập con của nhóm khoá tuyến cha', () => {
    const all = new Set<string>(Object.values(SECTION_KEYS).flat());
    for (const item of NAV.filter((i) => i.perms)) {
      for (const k of item.perms ?? []) expect(all.has(k)).toBe(true);
    }
  });

  it('nhóm khoá không rỗng và không trùng giữa các mục', () => {
    const seen = new Set<string>();
    for (const keys of Object.values(SECTION_KEYS)) {
      expect(keys.length).toBeGreaterThan(0);
      for (const k of keys) {
        expect(seen.has(k)).toBe(false);
        seen.add(k);
      }
    }
  });

  it('có tuyến trang không có quyền', () => {
    expect(routeOf('khong-co-quyen')).toBeDefined();
  });
});
