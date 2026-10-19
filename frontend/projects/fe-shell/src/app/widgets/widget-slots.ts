import {
  AfterViewInit,
  Component,
  ComponentRef,
  Directive,
  Type,
  ViewContainerRef,
  computed,
  effect,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { loadRemote } from '../federation-remotes';

/**
 * Ô giữ chỗ cho widget SDUI ở trang chủ (WEB-059, CLS).
 * Widget thật nạp lười (chunk riêng hoặc remote qua Native Federation) nên xuất hiện lần lượt
 * và đẩy các widget phía dưới xuống (CLS 0,2 đến 0,6 trên điện thoại). Ô giữ chỗ được gói sẵn
 * trong shell, có chiều cao tối thiểu ước theo nội dung (đơn vị rem nên theo cỡ chữ của chế độ
 * cao tuổi), rồi mới nạp widget vào bên trong. Nạp lỗi, hoặc widget không hiện gì (ví dụ vai trò không
 * có quyền xem theo ma trận wiki/15a), thì ô bỏ giữ chỗ và thu về 0, không để khoảng trống.
 */
@Directive({
  host: {
    class: 'vt-widget-slot',
    '[style.display]': '"block"',
    '[style.min-height]': 'collapsed() ? null : reserve()',
  },
})
export abstract class WidgetSlot implements AfterViewInit {
  /** Chiều cao giữ chỗ (CSS), tính theo props của widget. */
  protected abstract readonly reserve: () => string;
  protected abstract loadWidget(): Promise<Type<unknown>>;
  /** Props chuyển tiếp cho widget thật. */
  protected abstract widgetInputs(): Record<string, unknown>;

  protected readonly collapsed = signal(false);
  private readonly outlet = viewChild.required('outlet', { read: ViewContainerRef });
  private readonly ref = signal<ComponentRef<unknown> | null>(null);

  constructor() {
    effect(() => {
      const ref = this.ref();
      const props = this.widgetInputs();
      if (ref) for (const [k, v] of Object.entries(props)) ref.setInput(k, v);
    });
  }

  async ngAfterViewInit(): Promise<void> {
    try {
      const type = await this.loadWidget();
      const ref = this.outlet().createComponent(type);
      for (const [k, v] of Object.entries(this.widgetInputs())) ref.setInput(k, v);
      ref.changeDetectorRef.detectChanges();
      // widget tự ẩn (không dựng phần tử nào) thì không giữ chỗ
      if ((ref.location.nativeElement as HTMLElement).childElementCount === 0)
        this.collapsed.set(true);
      this.ref.set(ref);
    } catch (e) {
      console.warn('[sdui] Không nạp được widget, bỏ qua', e);
      this.collapsed.set(true);
    }
  }
}

const SLOT_TEMPLATE = '<ng-container #outlet />';

/** Nạp widget thật của `vt.widget.myTasksToday` từ remote fe-task (dùng cả để tải trước). */
export function loadMyTasksTodayWidget(): Promise<Type<unknown>> {
  return loadRemote<{ MyTasksTodayWidget: Type<unknown> }>('feTask', './widgets').then(
    (m) => m.MyTasksTodayWidget,
  );
}

/** Nạp widget thật của `vt.widget.announcement` (chunk riêng của shell). */
export function loadAnnouncementWidget(): Promise<Type<unknown>> {
  return import('./announcement.widget').then((m) => m.AnnouncementWidget);
}

/** `vt.widget.myTasksToday` (remote fe-task): đầu thẻ, chân thẻ khoảng 6,5rem và mỗi dòng 4rem. */
@Component({ selector: 'vt-my-tasks-today-slot', template: SLOT_TEMPLATE })
export class MyTasksTodaySlot extends WidgetSlot {
  readonly limit = input(7);
  protected readonly reserve = computed(() => `calc(6.5rem + ${this.limit()} * 4rem)`);
  protected loadWidget() {
    return loadMyTasksTodayWidget();
  }
  protected widgetInputs() {
    return { limit: this.limit() };
  }
}

/** `vt.widget.okrProgress`: ba mục tiêu, khoảng 16rem. */
@Component({ selector: 'vt-okr-progress-slot', template: SLOT_TEMPLATE })
export class OkrProgressSlot extends WidgetSlot {
  readonly scope = input<'me' | 'unit'>('me');
  protected readonly reserve = () => '16.5rem';
  protected loadWidget() {
    return import('./okr-progress.widget').then((m) => m.OkrProgressWidget);
  }
  protected widgetInputs() {
    return { scope: this.scope() };
  }
}

/** `vt.widget.pendingApprovals`: ba dòng, khoảng 13,5rem. */
@Component({ selector: 'vt-pending-approvals-slot', template: SLOT_TEMPLATE })
export class PendingApprovalsSlot extends WidgetSlot {
  protected readonly reserve = () => '13.5rem';
  protected loadWidget() {
    return import('./pending-approvals.widget').then((m) => m.PendingApprovalsWidget);
  }
  protected widgetInputs() {
    return {};
  }
}

/** `vt.widget.announcement`: một dòng thông báo; dài hơn thì vẫn có thể giãn. */
@Component({ selector: 'vt-announcement-slot', template: SLOT_TEMPLATE })
export class AnnouncementSlot extends WidgetSlot {
  readonly i18nKey = input('');
  protected readonly reserve = () => '2.2rem';
  protected loadWidget() {
    return loadAnnouncementWidget();
  }
  protected widgetInputs() {
    return { i18nKey: this.i18nKey() };
  }
}
