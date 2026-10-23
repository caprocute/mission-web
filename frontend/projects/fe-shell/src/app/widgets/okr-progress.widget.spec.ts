import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { OkrApi } from '@vt/api-client';
import { PermissionService } from '@vt/auth';
import { OkrProgressWidget } from './okr-progress.widget';

/** `vt.widget.okrProgress` đọc mục tiêu thật qua `listOkrObjectives`, ẩn khi không có quyền xem. */
describe('OkrProgressWidget', () => {
  function setup(can: boolean, objectives: ReturnType<typeof vi.fn>) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: PermissionService, useValue: { can: () => can } },
        { provide: OkrApi, useValue: { objectives } },
      ],
    });
    return TestBed.createComponent(OkrProgressWidget);
  }

  it('lấy OKR cá nhân của kỳ đang mở và hiện tiến độ theo phần trăm', async () => {
    const objectives = vi.fn(() =>
      of({
        items: [
          { id: 'a', code: 'O1', title: 'Nâng cao chất lượng phục vụ', progress: 0.645 },
          { id: 'b', code: 'O2', title: 'Chuyển đổi số nội bộ', progress: 0 },
        ],
        nextCursor: null,
      }),
    );
    const fixture = setup(true, objectives);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;
    expect(objectives).toHaveBeenCalledWith({ owner: 'me', limit: 3 });
    expect(host.textContent).toContain('Tiến độ OKR của tôi');
    expect(host.textContent).toContain('O1. Nâng cao chất lượng phục vụ');
    expect(host.querySelectorAll('vt-progress-bar').length).toBe(2);
    expect(host.querySelector('a[href="/okr"]')).not.toBeNull();
  });

  it('phạm vi đơn vị lấy mục tiêu tập thể; lỗi máy chủ hiện như chưa có mục tiêu', async () => {
    const objectives = vi.fn(() => throwError(() => new Error('503')));
    const fixture = setup(true, objectives);
    fixture.componentRef.setInput('scope', 'unit');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(objectives).toHaveBeenCalledWith({ ownerType: 'TAP_THE', limit: 3 });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Tiến độ OKR của đơn vị');
    expect(text).toContain('Chưa có mục tiêu trong kỳ đánh giá đang mở.');
  });

  it('không có quyền xem mục tiêu thì không dựng gì và không gọi API (YC-PT-21)', async () => {
    const objectives = vi.fn();
    const fixture = setup(false, objectives);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(objectives).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).querySelector('p-card')).toBeNull();
  });
});
