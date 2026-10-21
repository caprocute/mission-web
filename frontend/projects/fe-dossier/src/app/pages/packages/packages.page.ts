/*
 * SCR-1102 Gói hồ sơ số: danh sách (QT-11 bước 12 → SCR-1102 → GET /api/v1/dossiers/packages → MOD-06; WEB-041).
 * Đường vào "Hồ sơ số › Gói hồ sơ số": gói của đơn vị người dùng chuẩn bị (AC-07) hoặc ký (AC-08).
 */
import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import {
  DossierApi,
  DossierPackageStatus,
  PACKAGE_KIND_LABEL,
  PACKAGE_STATUS_LABEL,
} from '@vt/api-client';
import { DossierTabs } from '../dossier-tabs';

@Component({
  selector: 'vt-packages-page',
  imports: [DatePipe, RouterLink, DossierTabs],
  templateUrl: './packages.page.html',
  styleUrl: '../meetings/meetings.page.css',
})
export class PackagesPage {
  private readonly api = inject(DossierApi);

  protected readonly statusLabel = PACKAGE_STATUS_LABEL;
  protected readonly kindLabel = PACKAGE_KIND_LABEL;
  protected readonly statuses = Object.keys(PACKAGE_STATUS_LABEL) as DossierPackageStatus[];
  protected readonly status = signal<DossierPackageStatus | ''>('');

  protected readonly list = rxResource({
    params: () => this.status(),
    stream: ({ params }) => this.api.packages(params ? { status: params } : {}),
  });
  protected readonly items = computed(() => (this.list.hasValue() ? this.list.value().items : []));
}
