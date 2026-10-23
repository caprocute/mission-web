import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TaskApi, TaskSummary } from '@vt/api-client';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { TASK_CAN } from '../task-permissions';
import { ApprovalsPage } from './approvals.page';

const task = (id: string, title: string): TaskSummary =>
  ({
    id,
    code: `NV-2026-Q4-${id}`,
    title,
    status: 'NHAP',
    dueDate: '2026-11-20',
    plannedQuantity: 2,
    unit: 'báo cáo',
  }) as TaskSummary;

/** Hàng chờ duyệt (QT-05 bước 3, SCR-0601 "Duyệt đề nghị"): lọc đúng và ẩn hàng không có quyền. */
async function render(keys: string[]) {
  const unitTasks = vi.fn((q: { source?: string }) =>
    of({
      items: q.source === 'DANG_KY' ? [task('0201', 'Rà soát hồ sơ đảng viên')] : [],
      nextCursor: null,
    }),
  );
  TestBed.configureTestingModule({
    imports: [ApprovalsPage],
    providers: [
      provideRouter([]),
      { provide: TaskApi, useValue: { unitTasks } },
      { provide: TASK_CAN, useValue: (k: string) => keys.includes(k) },
    ],
  });
  const fixture = TestBed.createComponent(ApprovalsPage);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return { el: fixture.nativeElement as HTMLElement, unitTasks, fixture };
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('ApprovalsPage (hàng chờ duyệt của người giao việc)', () => {
  it('người giao việc thấy đăng ký chờ duyệt, mỗi dòng mở chi tiết nhiệm vụ', async () => {
    const { el, unitTasks } = await render(['SCR-0501.giao', 'SCR-0601.duyet-de-nghi']);
    expect(unitTasks).toHaveBeenCalledWith({ status: ['NHAP'], source: 'DANG_KY', limit: 100 });
    expect(el.querySelector('p-selectbutton')).not.toBeNull();
    const row = el.querySelector('.tbl tbody tr')!;
    expect(text(row)).toContain('Rà soát hồ sơ đảng viên');
    expect(text(row)).toContain('20/11/2026');
    expect(row.querySelector('a')!.getAttribute('href')).toBe('/tasks/0201');
    // không in mã trạng thái thô lên màn hình
    expect(text(el)).not.toContain('NHAP');
  });

  it('chỉ có quyền duyệt đề nghị: không hiện hàng đăng ký, hỏi đề nghị chờ duyệt', async () => {
    const { el, unitTasks } = await render(['SCR-0601.duyet-de-nghi']);
    expect(unitTasks).toHaveBeenCalledWith({
      pendingChangeRequest: true,
      includeSubunits: true,
      limit: 100,
    });
    expect(unitTasks).not.toHaveBeenCalledWith(expect.objectContaining({ source: 'DANG_KY' }));
    expect(el.querySelector('p-selectbutton')).toBeNull();
    expect(text(el)).toContain('Không có đề nghị gia hạn, điều chỉnh nào chờ duyệt.');
  });
});
