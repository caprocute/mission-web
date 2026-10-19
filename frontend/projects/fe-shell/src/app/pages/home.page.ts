import { Component, computed, inject } from '@angular/core';
import { SessionService } from '@vt/auth';
import { SduiOutletComponent } from '@vt/sdui-renderer';

/** SCR-0002 Trang chủ "Việc của tôi hôm nay" (QT-00); bố cục lấy từ UI Pack của tenant (SDUI). */
@Component({
  selector: 'vt-home-page',
  imports: [SduiOutletComponent],
  template: `
    <h1>Xin chào, {{ fullName() }}</h1>
    <p class="muted">Đây là những việc cần anh/chị quan tâm hôm nay.</p>
    <vt-sdui-outlet layout="home">
      <p class="muted">Chưa nạp được bố cục trang chủ của đơn vị.</p>
    </vt-sdui-outlet>
  `,
  styles: `
    h1 {
      margin: 0;
      font-size: 1.5rem;
    }
    .muted {
      color: var(--p-text-muted-color, #566275);
      margin: 4px 0 20px;
    }
  `,
})
export class HomePage {
  private readonly session = inject(SessionService);
  protected readonly fullName = computed(() => this.session.user()?.name ?? '');
}
