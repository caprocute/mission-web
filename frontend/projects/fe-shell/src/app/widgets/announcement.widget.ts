import { Component, computed, inject, input } from '@angular/core';
import { UiPackLoader } from '@vt/sdui-renderer';
import { MessageModule } from 'primeng/message';

/** `vt.widget.announcement`: thông báo của đơn vị; nội dung lấy từ i18n của UI Pack, hiển thị dạng văn bản thuần. */
@Component({
  selector: 'vt-announcement-widget',
  imports: [MessageModule],
  template: `<p-message severity="info" [text]="text()" />`,
})
export class AnnouncementWidget {
  readonly i18nKey = input('');
  private readonly loader = inject(UiPackLoader);
  protected readonly text = computed(() => this.loader.t(this.i18nKey()));
}
