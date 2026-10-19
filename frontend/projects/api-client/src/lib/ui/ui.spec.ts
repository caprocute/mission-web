import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Job } from '../models/job';
import { ConflictField, ConflictNoticeComponent } from './conflict-notice.component';
import { JobProgressComponent } from './job-progress.component';

interface Draft {
  percent: number;
  note: string;
}

@Component({
  imports: [ConflictNoticeComponent],
  template: `<vt-conflict-notice
    [mine]="mine"
    [theirs]="theirs()"
    [fields]="fields"
    (reload)="reloads = reloads + 1"
  />`,
})
class ConflictHost {
  mine: Draft = { percent: 70, note: 'Xong 7/8 phòng' };
  theirs = signal<Draft | null>(null);
  fields: ConflictField<Draft>[] = [
    { label: 'Tiến độ', value: (d) => `${d.percent}%` },
    { label: 'Ghi chú', value: (d) => d.note },
  ];
  reloads = 0;
}

describe('vt-conflict-notice', () => {
  it('hiện khác biệt giữa hai bản, chỉ các trường đổi; nút tải lại phát sự kiện', async () => {
    const f = TestBed.createComponent(ConflictHost);
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('[role="alert"]')?.textContent).toMatch(/Đang tải bản mới nhất/);
    f.componentInstance.theirs.set({ percent: 80, note: 'Xong 7/8 phòng' });
    f.detectChanges();
    const rows = Array.from(el.querySelectorAll('tbody tr')).map((r) => r.textContent?.trim());
    expect(rows).toEqual([expect.stringMatching(/Tiến độ\s*70%\s*80%/)]);
    (el.querySelector('button') as HTMLButtonElement).click();
    expect(f.componentInstance.reloads).toBe(1);
  });
});

@Component({
  imports: [JobProgressComponent],
  template: `<vt-job-progress [job]="job()" label="Khoá kỳ Quý IV/2026" />`,
})
class JobHost {
  job = signal<Job | null>(null);
}

describe('vt-job-progress', () => {
  it('thanh tiến trình có tên, giá trị và trạng thái bằng chữ', () => {
    const f = TestBed.createComponent(JobHost);
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    const bar = el.querySelector('[role="progressbar"]') as HTMLElement;
    expect(bar.getAttribute('aria-labelledby')).toBe('vt-job-label');
    expect(bar.hasAttribute('aria-valuenow')).toBe(false);
    expect(bar.hasAttribute('aria-level')).toBe(false);
    f.componentInstance.job.set({
      id: 'J',
      type: 'PERIOD_LOCK',
      status: 'DANG_CHAY',
      progress: 45,
    });
    f.detectChanges();
    expect(bar.getAttribute('aria-valuenow')).toBe('45');
    expect(el.textContent).toMatch(/Đang xử lý/);
    expect(el.textContent).toMatch(/có thể đóng màn hình/);
  });
});
