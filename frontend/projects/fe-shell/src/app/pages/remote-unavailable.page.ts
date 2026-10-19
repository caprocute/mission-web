import { Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { MessageModule } from 'primeng/message';

@Component({
  selector: 'vt-remote-unavailable-page',
  imports: [MessageModule],
  template: `
    <p-message severity="warn">
      Phân hệ {{ name }} tạm thời chưa sẵn sàng. Vui lòng thử lại sau ít phút; các chức năng khác vẫn dùng bình thường.
    </p-message>
  `,
})
export class RemoteUnavailablePage {
  protected readonly name = inject(ActivatedRoute).snapshot.data['remoteName'];
}
