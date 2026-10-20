/*
 * Đường vào SCR-0701 (TL-02: từ SCR-0601 nút "Nộp sản phẩm", menu "Minh chứng", trang chủ).
 * `?task=` liệt kê sản phẩm của nhiệm vụ (`GET /products?task=`) và minh chứng của từng sản phẩm
 * (`GET /evidences?task=`); "Nộp minh chứng" mở minh chứng đã có hoặc tạo mới (`POST /evidences`).
 * `?reviewer=me`: hàng chờ nghiệm thu (`GET /evidences?reviewer=me&status=DA_NOP,DANG_NGHIEM_THU`).
 * Không có tham số: minh chứng do tôi nộp (`GET /evidences?submitter=me`).
 */
import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  Deliverable,
  EVIDENCE_STATUS_LABEL,
  EvidenceApi,
  TaskApi,
  toProblem,
} from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { firstValueFrom, forkJoin, map } from 'rxjs';
import { EVIDENCE_CAN } from '../evidence-permissions';

@Component({
  selector: 'vt-evidence-list-page',
  imports: [RouterLink, ButtonModule],
  template: `
    @if (!can('SCR-0701.xem')) {
      <p class="msg warn">Đồng chí không có quyền xem minh chứng.</p>
    } @else if (task()) {
      @let t = taskRes.value();
      <h1>Sản phẩm, minh chứng của nhiệm vụ</h1>
      @if (t) {
        <p class="muted sub">{{ t.code }} · {{ t.title }}</p>
      }
      @if (error(); as err) {
        <p class="msg err" role="alert">{{ err }}</p>
      }
      @if (data.error()) {
        <p class="msg err" role="alert">{{ loadError() }}</p>
      } @else if (data.value(); as d) {
        @if (!d.deliverables.length) {
          <p class="muted">Nhiệm vụ chưa có sản phẩm cần nộp.</p>
        }
        <ul class="list">
          @for (p of d.deliverables; track p.id) {
            @let ev = evidenceOf(p.id);
            <li class="box">
              <div>
                <strong>{{ p.name }}</strong>
                <div class="muted small">
                  {{ p.quantityPlanned }} {{ p.unit }} ·
                  @if (ev) {
                    phiên bản {{ ev.currentVersionNo ?? 0 }}
                    {{
                      ev.currentStatus
                        ? '· ' + statusLabel[ev.currentStatus]
                        : '· chưa có phiên bản'
                    }}
                  } @else {
                    chưa nộp minh chứng
                  }
                </div>
              </div>
              @if (ev) {
                <a class="p-button p-component lnk" [routerLink]="[ev.id]">
                  {{
                    ev.currentStatus === 'NHAP' || !ev.currentStatus
                      ? 'Tiếp tục nộp'
                      : 'Xem minh chứng'
                  }}
                </a>
              } @else if (canStart()) {
                <p-button
                  label="Nộp minh chứng"
                  icon="pi pi-upload"
                  [loading]="busy() === p.id"
                  [disabled]="!!busy()"
                  (onClick)="start(p)"
                />
              }
            </li>
          }
        </ul>
      } @else {
        <p class="muted" aria-busy="true">Đang tải…</p>
      }
    } @else if (reviewer() === 'me') {
      <h1>Minh chứng chờ tôi nghiệm thu</h1>
      @if (!can('SCR-0703.mo')) {
        <p class="msg warn">Đồng chí không có quyền nghiệm thu minh chứng.</p>
      } @else if (mine.value(); as list) {
        @if (!list.length) {
          <p class="muted">Không có minh chứng nào đang chờ đồng chí nghiệm thu.</p>
        }
        <ul class="list">
          @for (e of list; track e.id) {
            <li class="box">
              <div>
                <strong>{{ e.task.code }} · {{ e.title }}</strong>
                <div class="muted small">
                  {{ e.submittedBy?.name }} · phiên bản {{ e.currentVersionNo }}
                  {{ e.currentStatus ? '· ' + statusLabel[e.currentStatus] : '' }}
                  {{ e.isDuplicateSuspect ? '· có tệp trùng với minh chứng đã nộp' : '' }}
                </div>
              </div>
              <a class="p-button p-component lnk" [routerLink]="[e.id, 'review']">Nghiệm thu</a>
            </li>
          }
        </ul>
      } @else if (mine.error()) {
        <p class="msg err" role="alert">Không tải được hàng chờ nghiệm thu.</p>
      }
    } @else {
      <h1>Minh chứng của tôi</h1>
      @if (mine.value(); as list) {
        @if (!list.length) {
          <p class="muted">
            Đồng chí chưa có minh chứng nào. Mở nhiệm vụ đang chủ trì và bấm "Nộp sản phẩm".
          </p>
        }
        <ul class="list">
          @for (e of list; track e.id) {
            <li class="box">
              <div>
                <strong>{{ e.title }}</strong>
                <div class="muted small">
                  {{ e.task.code }} · phiên bản {{ e.currentVersionNo ?? 0 }}
                  {{ e.currentStatus ? '· ' + statusLabel[e.currentStatus] : '' }}
                </div>
              </div>
              <a class="p-button p-component lnk" [routerLink]="[e.id]">Mở</a>
            </li>
          }
        </ul>
      } @else if (mine.error()) {
        <p class="msg err" role="alert">Không tải được danh sách minh chứng.</p>
      }
    }
  `,
  styles: `
    :host {
      display: block;
      padding: 16px 24px;
      max-width: 960px;
    }
    h1 {
      font-size: 1.5rem;
      margin: 0 0 4px;
    }
    .sub {
      margin: 0 0 12px;
    }
    .list {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .box {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      padding: 12px 16px;
      background: var(--p-content-background, #fff);
      margin-bottom: 10px;
    }
    .lnk {
      text-decoration: none;
      white-space: nowrap;
    }
    .msg {
      margin: 0 0 10px;
      padding: 10px 14px;
      border-radius: 6px;
      border-left: 4px solid;
    }
    .msg.warn {
      color: #78350f;
      background: #fffbeb;
      border-color: #b45309;
    }
    .msg.err {
      color: #7f1d1d;
      background: #fef2f2;
      border-color: #b91c1c;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.9em;
    }
  `,
})
export class EvidenceListPage {
  private readonly api = inject(EvidenceApi);
  private readonly tasks = inject(TaskApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly can = inject(EVIDENCE_CAN);
  protected readonly statusLabel = EVIDENCE_STATUS_LABEL;

  /** Mã nhiệm vụ từ `?task=` (SCR-0601 "Nộp sản phẩm"). */
  readonly task = input<string>();
  /** `?reviewer=me`: hàng chờ nghiệm thu (menu "Đánh giá / Chờ tôi duyệt", SCR-0703). */
  readonly reviewer = input<string>();

  protected readonly taskRes = rxResource({
    params: () => this.task(),
    stream: ({ params }) => this.tasks.detail(params),
  });

  protected readonly data = rxResource({
    params: () => this.task(),
    stream: ({ params }) =>
      forkJoin({
        deliverables: this.api.deliverablesOfTask(params),
        evidences: this.api.list({ task: params }),
      }),
  });

  protected readonly mine = rxResource({
    params: () => (this.task() ? undefined : this.reviewer() === 'me' ? 'reviewer' : 'submitter'),
    stream: ({ params }) =>
      this.api
        .list(
          params === 'reviewer'
            ? { reviewer: 'me', status: ['DA_NOP', 'DANG_NGHIEM_THU'] }
            : { submitter: 'me' },
        )
        .pipe(map((p) => p.items)),
  });

  protected readonly canStart = computed(
    () => this.can('SCR-0701.soan') && this.taskRes.value()?.role === 'CHU_TRI',
  );

  protected readonly busy = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly loadError = computed(() => {
    const p = toProblem(this.data.error());
    return p.status === 404 ? 'Không tìm thấy nhiệm vụ.' : p.detail || p.title;
  });

  protected evidenceOf(deliverableId: string) {
    return this.data.value()?.evidences.items.find((e) => e.deliverableId === deliverableId);
  }

  /** Tạo minh chứng cho sản phẩm rồi mở SCR-0701 để soạn phiên bản 1. */
  protected async start(p: Deliverable) {
    this.busy.set(p.id);
    this.error.set(null);
    try {
      const e = await firstValueFrom(
        this.api.create({ deliverableId: p.id, title: p.name, sourceType: 'TAI_LEN' }),
      );
      await this.router.navigate([e.id], { relativeTo: this.route });
    } catch (err) {
      const pr = toProblem(err);
      this.error.set(pr.detail || pr.title);
    } finally {
      this.busy.set(null);
    }
  }
}
