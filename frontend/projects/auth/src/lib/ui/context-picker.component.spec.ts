import { TestBed } from '@angular/core/testing';
import { OAuthService } from 'angular-oauth2-oidc';
import { of } from 'rxjs';
import { Assignment, DEFAULT_RUNTIME_CONFIG, OrgApi, RUNTIME_CONFIG } from '@vt/api-client';
import { SessionService } from '../session.service';
import { ContextPickerComponent } from './context-picker.component';
import { StepUpDialogComponent } from './step-up-dialog.component';

/** WEB-006: hộp chọn phân công (SCR-0001 vùng 5) và hộp bước lên ở dev (auth tắt). */
const A = (id: string, name: string, roles: string[], kind: Assignment['kind']): Assignment =>
  ({
    id,
    orgUnitId: id,
    orgUnitName: name,
    jobTitleName: 'Chuyên viên',
    kind,
    branch: 'CHINH_QUYEN',
    roles,
    validFrom: '2026-01-01',
    validTo: null,
  }) as Assignment;

async function setup() {
  sessionStorage.clear();
  const org = {
    me: () =>
      of({
        personId: 'p',
        fullName: 'Thử',
        assignments: [
          A('chinh', 'Phòng Tổng hợp', ['member', 'manager'], 'CHINH'),
          A('td', 'Hội đồng thẩm định', ['member', 'appraiser'], 'KIEM_NHIEM'),
        ],
      }),
    setContext: vi.fn((id: string) => of({ assignmentId: id })),
  };
  TestBed.configureTestingModule({
    providers: [
      { provide: OAuthService, useValue: {} },
      { provide: OrgApi, useValue: org },
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
    ],
  });
  const session = TestBed.inject(SessionService);
  await session.init();
  await new Promise((r) => setTimeout(r, 0));
  const fixture = TestBed.createComponent(ContextPickerComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  return { session, org, fixture, el: fixture.nativeElement as HTMLElement };
}

describe('ContextPickerComponent', () => {
  it('mở hộp, liệt kê phân công, chọn sẵn phân công chính, ghi chú MFA theo MSG-00-05', async () => {
    const { el } = await setup();
    const dialog = el.querySelector('dialog')!;
    expect(dialog.hasAttribute('open')).toBe(true);
    const radios = [...el.querySelectorAll<HTMLInputElement>('input[type=radio]')];
    expect(radios.map((r) => r.value)).toEqual(['chinh', 'td']);
    expect(radios[0].checked).toBe(true);
    expect(el.textContent).toContain('Vai trò Cán bộ thẩm định yêu cầu xác thực nhiều lớp');
    expect(dialog.getAttribute('aria-labelledby')).toBe('vt-ctx-title');
  });

  it('chọn phân công thường rồi xác nhận: ghi ngữ cảnh, đóng hộp', async () => {
    const { el, org, session, fixture } = await setup();
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await new Promise((r) => setTimeout(r, 0));
    fixture.detectChanges();
    expect(org.setContext).toHaveBeenCalledWith('chinh');
    expect(session.contextStatus()).toBe('SAN_SANG');
    expect(el.querySelector('dialog')!.hasAttribute('open')).toBe(false);
  });

  it('chọn phân công AAL2: hộp bước lên hiện lý do, xác thực xong mới dùng', async () => {
    const { el, org, session, fixture } = await setup();
    const step = TestBed.createComponent(StepUpDialogComponent);
    step.detectChanges();
    const td = el.querySelectorAll<HTMLInputElement>('input[type=radio]')[1];
    td.checked = true;
    td.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await new Promise((r) => setTimeout(r, 0));
    step.detectChanges();
    const stepEl = step.nativeElement as HTMLElement;
    expect(stepEl.querySelector('dialog')!.hasAttribute('open')).toBe(true);
    expect(stepEl.textContent).toContain('Vai trò Cán bộ thẩm định');
    expect(org.setContext).not.toHaveBeenCalled();
    await session.confirmStepUp();
    await new Promise((r) => setTimeout(r, 0));
    expect(org.setContext).toHaveBeenCalledWith('td');
    expect(session.state()).toBe('AAL2');
  });
});
