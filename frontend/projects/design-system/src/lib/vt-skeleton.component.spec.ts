import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { VtSkeletonComponent, VtSkeletonVariant } from './vt-skeleton.component';

@Component({
  imports: [VtSkeletonComponent],
  template: `<vt-skeleton [variant]="variant()" [rows]="rows()" [columns]="5" label="Đang tải danh sách" />`,
})
class HostComponent {
  readonly variant = signal<VtSkeletonVariant>('table');
  readonly rows = signal<number | undefined>(3);
}

/** YC-PT-28: khung xương có tên trợ năng, đúng số dòng, cột và không có chữ hiển thị. */
describe('VtSkeletonComponent', () => {
  it('gắn role=status, aria-busy và câu đọc cho trình đọc màn hình', async () => {
    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement.querySelector('vt-skeleton');
    expect(el.getAttribute('role')).toBe('status');
    expect(el.getAttribute('aria-busy')).toBe('true');
    expect(el.querySelector('.vt-sk-sr')?.textContent?.trim()).toBe('Đang tải danh sách');
    expect(el.querySelector('.vt-sk')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('bảng vẽ đầu bảng cộng số dòng yêu cầu, mỗi dòng đủ số cột', async () => {
    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    const rows = fixture.nativeElement.querySelectorAll('.vt-sk-row');
    expect(rows.length).toBe(4);
    expect(rows[1].querySelectorAll('.b').length).toBe(5);
  });

  it('mọi bố cục đều dựng được và dùng số dòng mặc định khi bỏ trống', async () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.rows.set(undefined);
    const variants: VtSkeletonVariant[] = [
      'page',
      'table',
      'list',
      'cards',
      'stats',
      'dashboard',
      'chart',
      'form',
      'detail',
      'tree',
      'text',
    ];
    for (const v of variants) {
      fixture.componentInstance.variant.set(v);
      await fixture.whenStable();
      const el: HTMLElement = fixture.nativeElement.querySelector('vt-skeleton');
      expect(el.getAttribute('data-variant')).toBe(v);
      expect(el.querySelectorAll('.b').length).toBeGreaterThan(0);
    }
  });
});
