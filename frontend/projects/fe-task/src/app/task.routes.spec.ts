import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  CanActivateFn,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { SessionService, VT_PERMISSION_OPTIONS, VtRole } from '@vt/auth';
import { TASK_ROUTES } from './task.routes';

/** WEB-055: tuyến con của fe-task chặn theo đúng SCR (wiki/15a). */
function check(roles: VtRole[], path: string, url: string): string {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      {
        provide: SessionService,
        useValue: { user: signal({ id: 'u', name: 'n', tenant: 't', roles }) },
      },
      { provide: VT_PERMISSION_OPTIONS, useValue: { allowInferred: true } },
    ],
  });
  const route = TASK_ROUTES.find((r) => r.path === path);
  const guard = route?.canActivate?.[0] as CanActivateFn;
  const result = TestBed.runInInjectionContext(() =>
    guard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
  ) as boolean | UrlTree;
  return result instanceof UrlTree ? TestBed.inject(Router).serializeUrl(result) : String(result);
}

describe('tuyến fe-task theo ma trận phân quyền', () => {
  it('mọi tuyến con đều có guard', () => {
    for (const r of TASK_ROUTES) expect(r.canActivate?.length, r.path).toBe(1);
  });

  it('Việc của tôi (SCR-0503) cho member', () => {
    expect(check(['member'], '', '/tasks')).toBe('true');
    expect(check(['member', 'manager'], '', '/tasks')).toBe('true');
  });

  it('manager không kèm member vào thẳng Giao việc', () => {
    expect(check(['manager'], '', '/tasks')).toBe('/tasks/assign');
    expect(check(['manager'], '', '/tasks?loc=1')).toBe('/tasks/assign');
    expect(check(['manager'], '', '/')).toBe('/assign');
  });

  it('vai trò chỉ có thư viện danh mục vào thẳng SCR-0401', () => {
    expect(check(['policy_author'], '', '/tasks')).toBe('/tasks/catalog');
    expect(check(['leader'], '', '/tasks')).toBe('/tasks/catalog');
  });

  it('Giao việc (SCR-0501) chỉ cho manager', () => {
    expect(check(['manager'], 'assign', '/tasks/assign')).toBe('true');
    expect(check(['manager'], 'assign/:id', '/tasks/assign/1')).toBe('true');
    expect(check(['member'], 'assign', '/tasks/assign')).toBe('/khong-co-quyen');
    expect(check(['inspector'], 'assign/:id', '/tasks/assign/1')).toBe('/khong-co-quyen');
  });

  it('Thư viện danh mục (SCR-0401) cho manager, member, leader và policy_author (suy luận)', () => {
    expect(check(['member'], 'catalog', '/tasks/catalog')).toBe('true');
    expect(check(['policy_author'], 'catalog', '/tasks/catalog')).toBe('true');
    expect(check(['inspector'], 'catalog', '/tasks/catalog')).toBe('/khong-co-quyen');
  });

  it('Chờ duyệt cho người giao việc và lãnh đạo duyệt đề nghị, không cho cán bộ', () => {
    expect(check(['manager'], 'approvals', '/tasks/approvals')).toBe('true');
    expect(check(['leader'], 'approvals', '/tasks/approvals')).toBe('true');
    expect(check(['member'], 'approvals', '/tasks/approvals')).toBe('/khong-co-quyen');
    expect(check(['inspector'], 'approvals', '/tasks/approvals')).toBe('/khong-co-quyen');
  });

  it('Văn bản đến (SCR-0502) chỉ cho manager', () => {
    expect(check(['manager'], 'documents', '/tasks/documents')).toBe('true');
    expect(check(['manager'], 'documents/:id', '/tasks/documents/1')).toBe('true');
    expect(check(['member'], 'documents', '/tasks/documents')).toBe('/khong-co-quyen');
  });

  it('Chi tiết nhiệm vụ (SCR-0601) cho cả vai trò chỉ xem như inspector', () => {
    expect(check(['inspector'], ':id', '/tasks/1')).toBe('true');
    expect(check(['platform_admin'], ':id', '/tasks/1')).toBe('/khong-co-quyen');
    expect(check(['platform_admin'], '', '/tasks')).toBe('/khong-co-quyen');
  });
});
