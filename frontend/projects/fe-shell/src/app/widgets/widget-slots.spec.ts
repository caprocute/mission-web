import { Component, Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { OkrProgressSlot, WidgetSlot } from './widget-slots';

@Component({
  selector: 'vt-hidden-widget',
  template: `@if (false) {
    <p>không hiện</p>
  }`,
})
class HiddenWidget {}

@Component({ selector: 'vt-hidden-slot', template: '<ng-container #outlet />' })
class HiddenSlot extends WidgetSlot {
  protected readonly reserve = () => '20rem';
  protected loadWidget() {
    return Promise.resolve(HiddenWidget as Type<unknown>);
  }
  protected widgetInputs() {
    return {};
  }
}

/** WEB-059: ô giữ chỗ có chiều cao ngay từ đầu rồi nạp widget thật vào bên trong. */
describe('WidgetSlot', () => {
  it('giữ chiều cao tối thiểu, chuyển tiếp props và dựng widget bên trong', async () => {
    const fixture = TestBed.createComponent(OkrProgressSlot);
    fixture.componentRef.setInput('scope', 'unit');
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;
    expect(host.style.minHeight).toBe('16.5rem');
    await vi.waitFor(() => expect(host.querySelector('vt-okr-progress-widget')).not.toBeNull());
    await fixture.whenStable();
    expect(host.textContent).toContain('Tiến độ OKR của đơn vị');
    expect(host.querySelectorAll('vt-progress-bar[role="progressbar"][aria-label]').length).toBe(3);
    expect(host.style.minHeight).toBe('16.5rem');
  });

  it('bỏ giữ chỗ khi widget không hiện gì (vai trò không có quyền xem)', async () => {
    const fixture = TestBed.createComponent(HiddenSlot);
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;
    expect(host.style.minHeight).toBe('20rem');
    await vi.waitFor(() => expect(host.querySelector('vt-hidden-widget')).not.toBeNull());
    await fixture.whenStable();
    fixture.detectChanges();
    expect(host.style.minHeight).toBe('');
  });
});
