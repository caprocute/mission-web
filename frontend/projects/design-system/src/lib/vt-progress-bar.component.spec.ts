import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { VtProgressBarComponent } from './vt-progress-bar.component';

@Component({
  imports: [VtProgressBarComponent],
  template: `<vt-progress-bar [value]="value()" label="O1. Chất lượng phục vụ" />`,
})
class HostComponent {
  readonly value = signal(72);
}

/** WEB-059: thanh tiến độ có role, tên và giá trị hợp lệ, không có aria-level. */
describe('VtProgressBarComponent', () => {
  it('gắn role=progressbar, aria-label và aria-valuenow, không có aria-level', async () => {
    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement.querySelector('vt-progress-bar');
    expect(el.getAttribute('role')).toBe('progressbar');
    expect(el.getAttribute('aria-label')).toBe('O1. Chất lượng phục vụ');
    expect(el.getAttribute('aria-valuenow')).toBe('72');
    expect(el.getAttribute('aria-valuetext')).toBe('72%');
    expect(el.hasAttribute('aria-level')).toBe(false);
    expect(el.textContent?.trim()).toBe('72%');
  });

  it('kẹp giá trị ngoài 0..100', async () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.value.set(140);
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement.querySelector('vt-progress-bar');
    expect(el.getAttribute('aria-valuenow')).toBe('100');
    expect((el.querySelector('.vt-progress__fill') as HTMLElement).style.width).toBe('100%');
  });
});
