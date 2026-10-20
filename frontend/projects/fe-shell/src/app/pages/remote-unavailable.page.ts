import { Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { MessageModule } from 'primeng/message';

@Component({
  selector: 'vt-remote-unavailable-page',
  imports: [MessageModule],
  template: `
    <p-message severity="warn">
      Chức năng này tạm thời chưa sẵn sàng. Đồng chí vui lòng thử lại sau ít phút; các chức năng
      khác vẫn sử dụng bình thường.
    </p-message>
  `,
})
export class RemoteUnavailablePage {
  /** Khoá remote không nạp được (chỉ để chẩn đoán, không hiện cho người dùng). */
  protected readonly name = inject(ActivatedRoute).snapshot.data['remoteName'];
}
