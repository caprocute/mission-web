import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { SessionService } from '@vt/auth';
import { CelContext, layoutNeedsCel } from './cel-context';

/** WEB-052: cel-js nạp lười ở lần đánh giá đầu, kết quả `visibleWhen` không đổi. */
describe('CelContext', () => {
  function setup(roles: string[]) {
    TestBed.configureTestingModule({
      providers: [
        {
          provide: SessionService,
          useValue: { user: signal({ id: 'u1', roles, tenant: 't', unitId: 'dv1' }) },
        },
      ],
    });
    return TestBed.inject(CelContext);
  }

  it('đánh giá visibleWhen theo vai trò sau khi nạp cel-js', async () => {
    const cel = setup(['manager']);
    expect(await cel.evaluateBool("'manager' in user.roles")).toBe(true);
    expect(await cel.evaluateBool("user.roles.exists(r, r in ['leader'])")).toBe(false);
  });

  it('biểu thức sai cú pháp hoặc quá dài thì ẩn nút', async () => {
    const cel = setup(['manager']);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(await cel.evaluateBool("'manager' in")).toBe(false);
    expect(await cel.evaluateBool(`'${'a'.repeat(600)}' == 'a'`)).toBe(false);
    warn.mockRestore();
  });

  it('preload chỉ tải một lần', async () => {
    const cel = setup([]);
    const a = cel.preload();
    expect(cel.preload()).toBe(a);
    await a;
  });

  it('layoutNeedsCel nhận ra visibleWhen ở nút con', () => {
    expect(layoutNeedsCel(null)).toBe(false);
    expect(layoutNeedsCel({ component: 'vt.layout.grid', children: [{ component: 'a' }] })).toBe(
      false,
    );
    expect(
      layoutNeedsCel({
        component: 'vt.layout.grid',
        children: [{ component: 'a', visibleWhen: "'manager' in user.roles" }],
      }),
    ).toBe(true);
  });
});
