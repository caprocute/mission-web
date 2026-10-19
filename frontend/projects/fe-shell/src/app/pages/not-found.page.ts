import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'vt-not-found-page',
  imports: [RouterLink],
  template: `<main style="padding: 48px; text-align: center"><h1>Không tìm thấy trang</h1><a routerLink="/">Về trang chủ</a></main>`,
})
export class NotFoundPage {}
