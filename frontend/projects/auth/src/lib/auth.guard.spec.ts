import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { VT_FORBIDDEN_URL, permissionGuard } from './auth.guard';
import { PERMISSION_KEYS, VT_PERMISSION_OPTIONS, screenKeys } from './permission.service';
import { SessionService, VtRole } from './session.service';

describe('screenKeys', () => {
  it('lấy mọi khoá của các SCR, không lẫn SCR có tiền tố giống', () => {
    const keys = screenKeys('SCR-0103', 'SCR-0801');
    expect(keys).toContain('SCR-0103.xem');
    expect(keys).toContain('SCR-0801.duyet-lenh');
    expect(keys.every((k) => k.startsWith('SCR-0103.') || k.startsWith('SCR-0801.'))).toBe(true);
    expect(keys.length).toBe(PERMISSION_KEYS.filter((k) => /^SCR-(0103|0801)\./.test(k)).length);
  });

  it('SCR không có trong ma trận thì rỗng', () => {
    expect(screenKeys('SCR-9999')).toEqual([]);
  });
});

describe('permissionGuard', () => {
  function run(roles: VtRole[], forbidden?: string | null) {
    TestBed.configureTestingModule({
      providers: [
        {
          provide: SessionService,
          useValue: { user: signal({ id: 'u', name: 'n', tenant: 't', roles }) },
        },
        { provide: VT_PERMISSION_OPTIONS, useValue: { allowInferred: true } },
        ...(forbidden === undefined ? [] : [{ provide: VT_FORBIDDEN_URL, useValue: forbidden }]),
      ],
    });
    const guard = permissionGuard('SCR-0203.duyet');
    return TestBed.runInInjectionContext(() => guard({}, []) as boolean | UrlTree);
  }

  it('có quyền thì cho qua', () => {
    expect(run(['leader'])).toBe(true);
  });

  it('không có quyền thì chuyển sang trang không có quyền', () => {
    const r = run(['member']);
    expect(r).toBeInstanceOf(UrlTree);
    expect(TestBed.inject(Router).serializeUrl(r as UrlTree)).toBe('/khong-co-quyen');
  });

  it('remote chạy riêng (VT_FORBIDDEN_URL = null) thì chỉ trả false', () => {
    expect(run(['member'], null)).toBe(false);
  });
});
