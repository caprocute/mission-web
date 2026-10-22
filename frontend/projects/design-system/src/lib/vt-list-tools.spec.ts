import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { VtColumnPickerComponent } from './vt-column-picker.component';
import { VtSavedFiltersComponent } from './vt-saved-filters.component';

@Component({
  imports: [VtColumnPickerComponent, VtSavedFiltersComponent],
  template: `
    <vt-column-picker [columns]="cols" [visible]="visible()" (visibleChange)="visible.set($event)" />
    <vt-saved-filters
      [filters]="filters"
      [activeId]="active()"
      (apply)="active.set($event)"
      (save)="saved = $event"
      (remove)="removed = $event"
    />
  `,
})
class Host {
  cols = [
    { key: 'name', label: 'Tên', required: true },
    { key: 'due', label: 'Hạn' },
  ];
  visible = signal(['name', 'due']);
  filters = [{ id: 'f1', name: 'Việc chậm', isDefault: true }];
  active = signal<string | null>(null);
  saved: unknown = null;
  removed: unknown = null;
}

function render() {
  const f = TestBed.createComponent(Host);
  f.autoDetectChanges();
  return { f, el: f.nativeElement as HTMLElement };
}
const btn = (el: HTMLElement, text: string) =>
  [...el.querySelectorAll('button')].find((b) => b.textContent?.includes(text)) as HTMLButtonElement;

describe('vt-column-picker và vt-saved-filters (CNH-04)', () => {
  it('chọn cột: cột bắt buộc không bỏ được, Esc đóng và trả tiêu điểm', async () => {
    const { f, el } = render();
    const trigger = btn(el, 'Chọn cột');
    trigger.click();
    await f.whenStable();
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    const boxes = el.querySelectorAll<HTMLInputElement>('vt-column-picker input[type=checkbox]');
    expect(boxes[0].disabled).toBe(true);
    boxes[1].click();
    expect(f.componentInstance.visible()).toEqual(['name']);
    el.querySelector('vt-column-picker')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await f.whenStable();
    expect(el.querySelector('vt-column-picker fieldset')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it('một chạm áp dụng bộ lọc, lưu theo tên, xoá cần xác nhận', async () => {
    const { f, el } = render();
    btn(el, 'Việc chậm').click();
    expect(f.componentInstance.active()).toBe('f1');
    btn(el, 'Lưu bộ lọc hiện tại').click();
    await f.whenStable();
    const input = el.querySelector<HTMLInputElement>('vt-saved-filters form input[type=text]')!;
    input.value = 'Sắp hạn';
    input.dispatchEvent(new Event('input'));
    await f.whenStable();
    el.querySelector<HTMLFormElement>('vt-saved-filters form')!.dispatchEvent(new Event('submit'));
    expect(f.componentInstance.saved).toEqual({ name: 'Sắp hạn', isDefault: false });
    btn(el, 'Quản lý bộ lọc').click();
    await f.whenStable();
    btn(el, 'Xoá').click();
    await f.whenStable();
    expect(f.componentInstance.removed).toBeNull();
    btn(el, 'Xác nhận xoá').click();
    expect(f.componentInstance.removed).toBe('f1');
  });
});
