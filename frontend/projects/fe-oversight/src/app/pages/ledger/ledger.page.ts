/*
 * SCR-1302 "Tra cứu sổ cái và kiểm tra toàn vẹn" (QT-13 bước 2, 3; TL-02 Hình 11.8) — đối chiếu đặc tả:
 * Tác nhân AC-10 (`inspector`), AC-07 xem trong phạm vi đơn vị; web máy tính. Đường vào: menu "Giám sát ›
 *   Sổ cái"; liên kết từ SCR-1301 (`?aggregateType=&aggregateId=`) và SCR-1401.
 * Trường: 1 đối tượng (loại và mã); 2 khoảng thời gian (mặc định 90 ngày gần nhất, tối đa 2 năm); 3 loại sự
 *   kiện (topic EVT); 4 bảng bản ghi (mã, thời điểm, sự kiện, người, trước, sau; phân trang con trỏ; trường
 *   C2 đã che); 5 kết quả chuỗi (prev, hash, khớp); 6 lần kiểm tra (mã, phạm vi, thời gian chạy, kết quả);
 *   7 niêm phong, bản neo (mã, gốc, chữ ký, dấu thời gian) theo bản ghi chọn.
 * Vùng: 1 thanh tra cứu; 2 bảng bản ghi; 3 kết quả kiểm tra chuỗi băm; 4 thẻ "Lần kiểm tra"; 5 thẻ "Niêm
 *   phong và bản neo".
 * Chức năng: Tìm (GET /ledger/entries); Chạy kiểm tra toàn vẹn (POST /ledger/verify-runs, 202, rồi đọc
 *   GET /ledger/verify-runs/{id} tới khi xong; phá vỡ thì EVT-20); Xem bản ghi (GET /ledger/entries/{id});
 *   Xuất bằng chứng JSON và chữ ký (POST /ledger/exports, 202).
 * Quy tắc, thông báo: BR-13-09, BR-13-10, BR-13-11, BR-13-12; MSG-13-05, MSG-13-06, MSG-13-07.
 * Quyền (wiki/15a): SCR-1302.tra-cuu (AC-07 Đv, AC-10 Tn), kiem-tra (AC-10), xuat (AC-10).
 */
import { Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  LEDGER_ACTOR_KIND_LABEL,
  LEDGER_AGGREGATE_TYPE_LABEL,
  LEDGER_ANCHOR_TARGET_LABEL,
  LEDGER_DEFAULT_RANGE_DAYS,
  LEDGER_EVENT_TYPE_LABEL,
  LEDGER_FIELD_LABEL,
  LEDGER_PARTITIONS,
  LEDGER_VALUE_LABEL,
  LEDGER_VERIFY_KIND_LABEL,
  LedgerApi,
  LedgerEntry,
  LedgerEntryFilter,
  LedgerVerifyKind,
  LedgerVerifyRun,
  PERIOD_STATUS_LABEL,
  RATING_LEVEL_LABEL,
  REOPEN_STATUS_LABEL,
  TASK_STATUS_LABEL,
  shortLedgerHash,
  verifyRunIdOf,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { OVERSIGHT_CAN } from '../../oversight-permissions';
import { errorText, fmtDateTime, fmtInt } from '../../oversight-ui';
import { VERIFY_POLL_MS } from '../../step-up';

const DAY = 86_400_000;
const isoDate = (d: Date) => {
  const p = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
};

/** Topic sự kiện hay tra (danh sách gợi ý theo bảng EVT của baseline, suy luận), hiện bằng tên thao tác. */
const EVENT_TYPES = Object.keys(LEDGER_EVENT_TYPE_LABEL);

/** Nhãn trạng thái theo loại đối tượng (`status` trong `metadata`). */
const STATUS_LABEL_BY_TYPE: Record<string, Record<string, string>> = {
  task: TASK_STATUS_LABEL,
  period: PERIOD_STATUS_LABEL,
  reopen_request: REOPEN_STATUS_LABEL,
};

/** Một giá trị trong `metadata` bằng lời: trạng thái, mức xếp loại, mã kết luận theo bảng nhãn. */
function valueText(aggregateType: string, key: string, x: unknown): string {
  if (x === null || x === undefined) return 'chưa có';
  if (typeof x === 'number') return x.toLocaleString('vi-VN');
  const v = String(x);
  if (key === 'status')
    return STATUS_LABEL_BY_TYPE[aggregateType]?.[v] ?? LEDGER_VALUE_LABEL[v] ?? v;
  if (key === 'level' || key === 'selfLevel')
    return RATING_LEVEL_LABEL[v as keyof typeof RATING_LEVEL_LABEL] ?? v;
  return LEDGER_VALUE_LABEL[v] ?? v;
}

/** Giá trị trước, sau trong `metadata` thành chuỗi ngắn: "Tiến độ (C): 0,85; Chất lượng (B): 0,75". */
function values(aggregateType: string, v: unknown): string {
  if (v === undefined) return '—';
  if (v === null) return 'chưa có';
  if (typeof v !== 'object') return valueText(aggregateType, '', v);
  return Object.entries(v as Record<string, unknown>)
    .map(([k, x]) => `${LEDGER_FIELD_LABEL[k] ?? k}: ${valueText(aggregateType, k, x)}`)
    .join('; ');
}

@Component({
  selector: 'vt-ledger-page',
  imports: [FormsModule, RouterLink, ButtonModule, InputTextModule, MessageModule, SelectModule],
  template: `
    <p class="crumb"><a routerLink="../reopen-requests">Hậu kiểm, mở lại kỳ</a></p>
    <header class="head">
      <div>
        <h1>Sổ cái và kiểm tra toàn vẹn</h1>
        @if (!simple()) {
          <p class="muted">
            Sổ cái ghi lại mọi thay đổi của dữ liệu đánh giá và không cho phép sửa, xoá. Các bản ghi
            được nối thành chuỗi kiểm tra toàn vẹn chia thành {{ partitions }} phân vùng; mỗi giờ sổ
            cái được niêm phong bằng chữ ký số và dấu thời gian được chứng thực, bản sao niêm phong
            (bản neo) do cơ quan độc lập lưu giữ. Mọi lần tra cứu cũng được ghi vào sổ cái.
          </p>
        }
      </div>
      <div class="actions">
        @if (can('SCR-1302.xuat')) {
          <p-button
            label="Xuất tệp bằng chứng (kèm chữ ký số)"
            severity="contrast"
            [outlined]="true"
            [loading]="exporting()"
            [disabled]="exporting()"
            (onClick)="exportEvidence()"
          />
        }
      </div>
    </header>

    @if (notice(); as n) {
      <p-message severity="success" role="status">{{ n }}</p-message>
    }
    @if (error(); as e) {
      <p-message severity="error" role="alert">{{ e }}</p-message>
    }

    <!-- vùng 1 -->
    <form class="box filter" (ngSubmit)="search()" aria-labelledby="f-h">
      <h2 id="f-h" class="h"><span class="num" aria-hidden="true">1</span> Tra cứu</h2>
      <div class="grid">
        <div class="f">
          <label for="lg-type">Loại đối tượng</label>
          <p-select
            inputId="lg-type"
            name="aggregateType"
            [options]="aggregateTypes"
            optionLabel="label"
            optionValue="value"
            [ngModel]="aggregateType()"
            (ngModelChange)="aggregateType.set($event)"
          />
        </div>
        <div class="f">
          <label for="lg-id">Mã đối tượng</label>
          <input
            pInputText
            id="lg-id"
            name="aggregateId"
            maxlength="64"
            [ngModel]="aggregateId()"
            (ngModelChange)="aggregateId.set($event)"
          />
        </div>
        <div class="f">
          <label for="lg-from">Từ ngày</label>
          <input
            pInputText
            type="date"
            id="lg-from"
            name="from"
            [ngModel]="from()"
            (ngModelChange)="from.set($event)"
          />
        </div>
        <div class="f">
          <label for="lg-to">Đến ngày</label>
          <input
            pInputText
            type="date"
            id="lg-to"
            name="to"
            [ngModel]="to()"
            (ngModelChange)="to.set($event)"
          />
        </div>
        <div class="f">
          <label for="lg-evt">Thao tác</label>
          <p-select
            inputId="lg-evt"
            name="eventType"
            [options]="eventTypes"
            optionLabel="label"
            optionValue="value"
            [ngModel]="eventType()"
            (ngModelChange)="eventType.set($event)"
            [filter]="true"
            filterBy="label"
          />
        </div>
        @if (!simple()) {
          <div class="f">
            <label for="lg-part">Phân vùng (0–63)</label>
            <input
              pInputText
              type="number"
              id="lg-part"
              name="partition"
              min="0"
              max="63"
              [ngModel]="partition()"
              (ngModelChange)="partition.set($event === '' || $event === null ? null : +$event)"
            />
          </div>
        }
      </div>
      <div class="actions">
        <p-button type="submit" label="Tìm" icon="pi pi-search" [loading]="page.isLoading()" />
        <p-button label="Xoá bộ lọc" severity="contrast" [text]="true" (onClick)="clearFilter()" />
      </div>
    </form>

    <div class="cols">
      <div>
        <!-- vùng 2 -->
        <section class="box" aria-labelledby="t-h">
          <h2 id="t-h" class="h">
            <span class="num" aria-hidden="true">2</span> Bản ghi sổ cái
            @if (rows().length) {
              <span class="small muted">({{ rows().length }}{{ cursor() ? '+' : '' }})</span>
            }
          </h2>
          @if (page.error()) {
            <p-message severity="error">{{ pageError() }}</p-message>
          } @else {
            <div class="table-wrap">
              <table class="tbl">
                <thead>
                  <tr>
                    <th scope="col">Bản ghi</th>
                    <th scope="col">Thời điểm</th>
                    <th scope="col">Thao tác</th>
                    <th scope="col">Người thực hiện</th>
                    <th scope="col">Giá trị trước</th>
                    <th scope="col">Giá trị sau</th>
                  </tr>
                </thead>
                <tbody>
                  @for (e of rows(); track e.id) {
                    <tr [class.broken]="isBroken(e)" [class.sel]="selectedId() === e.id">
                      <td class="nowrap">
                        <button
                          type="button"
                          class="link"
                          [attr.aria-pressed]="selectedId() === e.id"
                          [attr.aria-label]="
                            'Xem bản ghi số ' + e.seqNo + ', phân vùng ' + e.chainPartition
                          "
                          (click)="select(e)"
                        >
                          Số {{ e.seqNo }}
                        </button>
                        @if (!simple()) {
                          <span class="small muted block">Phân vùng {{ e.chainPartition }}</span>
                        }
                        @if (isBroken(e)) {
                          <span class="bad small block">Nội dung đã bị sửa</span>
                        }
                      </td>
                      <td class="nowrap">{{ dt(e.occurredAt) }}</td>
                      <td class="evt">
                        {{ eventLabel(e.eventType) }}
                        @if (!simple()) {
                          <button
                            type="button"
                            class="link small block"
                            title="Xem các bản ghi của đối tượng này"
                            (click)="filterBy(e)"
                          >
                            {{ subject(e) }}
                          </button>
                        }
                      </td>
                      <td>{{ e.actor?.name ?? actorKind[e.actorKind] }}</td>
                      <td>{{ before(e) }}</td>
                      <td>{{ after(e) }}</td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="6" class="muted">
                        {{ page.isLoading() ? 'Đang tải…' : 'Không có bản ghi nào khớp bộ lọc.' }}
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (cursor()) {
              <p-button
                label="Tải thêm"
                severity="contrast"
                [text]="true"
                [loading]="page.isLoading()"
                (onClick)="more()"
              />
            }
          }
        </section>

        <!-- vùng 3 -->
        @if (run(); as r) {
          <section class="box" aria-labelledby="c-h">
            <h2 id="c-h" class="h">
              <span class="num" aria-hidden="true">3</span> Kết quả kiểm tra toàn vẹn{{
                r.firstMismatch?.chainPartition !== undefined
                  ? ', phân vùng ' + r.firstMismatch?.chainPartition
                  : ''
              }}
            </h2>
            @if (!r.finishedAt) {
              <p class="muted" role="status">
                Đang kiểm tra lần {{ r.code }}: đã kiểm tra {{ int(r.entriesChecked) }} bản ghi…
              </p>
            } @else if (r.mismatches > 0 && r.firstMismatch; as m) {
              <ol class="chain" aria-label="Các bản ghi liền kề vị trí sai lệch">
                @for (c of r.chain ?? []; track c.seqNo) {
                  <li [class.bad-box]="!c.matches">
                    <strong>Bản ghi số {{ c.seqNo }}</strong>
                    <span class="small"
                      >Mã của bản ghi trước: <span class="mono">{{ hash(c.prevHash) }}</span></span
                    >
                    <span class="small"
                      >Mã kiểm tra: <span class="mono">{{ hash(c.storedHash) }}</span>
                      @if (!c.matches) {
                        ≠ tính lại <span class="mono">{{ hash(c.recomputedHash) }}</span>
                      }
                    </span>
                    <span class="small" [class.bad]="!c.matches">{{
                      c.matches ? 'Khớp' : 'Không khớp'
                    }}</span>
                  </li>
                }
              </ol>
              <p-message severity="error">
                Phát hiện sổ cái mất toàn vẹn tại bản ghi số {{ m.seqNo }} của phân vùng
                {{ m.chainPartition }}. Hệ thống đã tạo cảnh báo mức Cao và tạm khoá ghi phân vùng
                này.
              </p-message>
              @if (!simple()) {
                <p class="small">
                  Nội dung bản ghi số {{ m.seqNo }} đã bị sửa sau khi ghi.
                  @if (m.anchorMatches) {
                    Niêm phong chứa bản ghi này vẫn giữ mã kiểm tra ban đầu
                    <span class="mono">{{ hash(m.storedHash) }}</span> và khớp với bản neo, nên vẫn
                    chứng minh được giá trị ban đầu.
                  }
                  @if (m.entryId) {
                    <button type="button" class="link" (click)="selectId(m.entryId)">
                      Xem bản ghi và niêm phong
                    </button>
                  }
                </p>
              }
            } @else {
              <p-message severity="success">
                Kiểm tra toàn vẹn hoàn tất: {{ r.partitionsMatched ?? 0 }}/{{
                  r.partitionsTotal ?? 0
                }}
                phân vùng khớp, {{ int(r.entriesChecked) }} bản ghi, {{ seconds(r) }} giây.
              </p-message>
            }
          </section>
        }
      </div>

      <aside>
        <!-- vùng 4 -->
        <section class="box" aria-labelledby="r-h">
          <h2 id="r-h" class="h"><span class="num" aria-hidden="true">4</span> Lần kiểm tra</h2>
          @if (runs().length > 1) {
            <label for="lg-run" class="sr-only">Chọn lần kiểm tra</label>
            <p-select
              inputId="lg-run"
              [options]="runOptions()"
              optionLabel="label"
              optionValue="value"
              [ngModel]="run()?.id"
              (ngModelChange)="chooseRun($event)"
              styleClass="w-full"
            />
          }
          @if (run(); as r) {
            <dl class="meta">
              <dt>Mã lần kiểm tra</dt>
              <dd>{{ r.code ?? '—' }}</dd>
              <dt>Loại</dt>
              <dd>{{ verifyKind[r.verifyKind] }}</dd>
              <dt>Phạm vi</dt>
              <dd>
                {{
                  (r.chainPartition ?? null) === null
                    ? 'Toàn bộ ' + partitions + ' phân vùng'
                    : 'Phân vùng ' + r.chainPartition
                }}
              </dd>
              <dt>Số bản ghi</dt>
              <dd>{{ int(r.entriesChecked) }}</dd>
              @if (r.sealsChecked !== undefined) {
                <dt>Số niêm phong</dt>
                <dd>{{ int(r.sealsChecked) }}</dd>
              }
              <dt>Thời gian chạy</dt>
              <dd>{{ r.finishedAt ? seconds(r) + ' giây' : 'Đang kiểm tra' }}</dd>
              <dt>Kết quả</dt>
              <dd [class.bad]="r.mismatches > 0">
                @if (r.finishedAt) {
                  {{ r.partitionsMatched ?? 0 }}/{{ r.partitionsTotal ?? 0 }} phân vùng khớp
                } @else {
                  —
                }
              </dd>
              <dt>Bắt đầu</dt>
              <dd>{{ dt(r.startedAt) }}</dd>
            </dl>
          } @else {
            <p class="muted">Chưa có lần kiểm tra nào.</p>
          }
          @if (can('SCR-1302.kiem-tra')) {
            <div class="run-form">
              <div class="f">
                <label for="lg-vkind">Loại kiểm tra</label>
                <p-select
                  inputId="lg-vkind"
                  [options]="verifyKinds"
                  optionLabel="label"
                  optionValue="value"
                  [ngModel]="newKind()"
                  (ngModelChange)="newKind.set($event)"
                />
              </div>
              <div class="f">
                <label for="lg-vpart"
                  >Phân vùng (để trống để kiểm tra cả {{ partitions }} phân vùng)</label
                >
                <input
                  pInputText
                  type="number"
                  id="lg-vpart"
                  min="0"
                  max="63"
                  [ngModel]="newPartition()"
                  (ngModelChange)="
                    newPartition.set($event === '' || $event === null ? null : +$event)
                  "
                />
              </div>
              <p-button
                label="Chạy kiểm tra toàn vẹn"
                [loading]="running()"
                [disabled]="running()"
                (onClick)="startVerify()"
              />
            </div>
          }
        </section>

        <!-- vùng 5 -->
        <section class="box" aria-labelledby="s-h" aria-live="polite">
          <h2 id="s-h" class="h">
            <span class="num" aria-hidden="true">5</span> Niêm phong và bản neo
          </h2>
          @if (detail.isLoading()) {
            <p class="muted">Đang tải bản ghi…</p>
          } @else if (detail.value(); as d) {
            <dl class="meta">
              <dt>Bản ghi</dt>
              <dd>Số {{ d.seqNo }}, phân vùng {{ d.chainPartition }} · {{ dt(d.recordedAt) }}</dd>
              <dt>Mã kiểm tra</dt>
              <dd class="mono" [title]="d.entryHash">{{ hash(d.entryHash, 8) }}</dd>
              @if (!simple()) {
                <dt>Mã của bản ghi trước</dt>
                <dd class="mono" [title]="d.prevHash">{{ hash(d.prevHash, 8) }}</dd>
                <dt>Mã kiểm tra nội dung</dt>
                <dd class="mono" [title]="d.payloadHash">{{ hash(d.payloadHash, 8) }}</dd>
              }
            </dl>
            @if (d.seal; as s) {
              <h3 class="h3">Niêm phong {{ dt(s.windowStart) }}–{{ time(s.windowEnd) }}</h3>
              <dl class="meta">
                <dt>Mã niêm phong</dt>
                <dd class="mono" [title]="s.tenantRoot">{{ hash(s.tenantRoot, 8) }}</dd>
                @if (s.cellRoot && !simple()) {
                  <dt>Mã niêm phong của cụm máy chủ</dt>
                  <dd class="mono" [title]="s.cellRoot">{{ hash(s.cellRoot, 8) }}</dd>
                }
                <dt>Chữ ký số</dt>
                <dd [class.bad]="s.signatureValid === false">
                  {{ s.signatureValid === false ? 'Không hợp lệ' : 'Hợp lệ' }}
                </dd>
                <dt>Dấu thời gian</dt>
                <dd>
                  {{ dt(s.tsaGenTime) }}, được chứng thực
                  @if (s.tsaLate) {
                    <span class="warn">(gắn bù do dịch vụ chứng thực phản hồi chậm)</span>
                  }
                </dd>
              </dl>
              @if (d.anchors?.length) {
                <h3 class="h3">Bản neo</h3>
                <ul class="anchors">
                  @for (a of d.anchors; track a.id ?? $index) {
                    <li>
                      <strong>{{ anchorTarget[a.anchorTarget] }}</strong> ·
                      {{ dt(a.anchoredAt) }}
                      @if (a.receipt && !simple()) {
                        <span class="block small muted">{{ a.receipt }}</span>
                      }
                    </li>
                  }
                </ul>
              }
            } @else {
              <p class="muted">Bản ghi chưa được niêm phong; sổ cái được niêm phong mỗi giờ.</p>
            }
          } @else {
            <p class="muted">
              Chọn một bản ghi trong bảng để xem mã kiểm tra, niêm phong và bản neo.
            </p>
          }
        </section>
      </aside>
    </div>
  `,
  styles: `
    .crumb {
      margin: 0 0 4px;
    }
    .head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 12px;
      flex-wrap: wrap;
    }
    h1 {
      font-size: 1.4rem;
      margin: 4px 0;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.875em;
    }
    .block {
      display: block;
    }
    .nowrap {
      white-space: nowrap;
    }
    .mono {
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 0.875em;
      word-break: break-all;
    }
    .bad {
      color: #b91c1c;
      font-weight: 600;
    }
    .warn {
      color: #92400e;
    }
    .actions {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
      align-items: center;
    }
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
      margin-bottom: 12px;
    }
    .h {
      font-size: 1.05rem;
      margin: 0 0 8px;
    }
    .h3 {
      font-size: 0.95rem;
      margin: 10px 0 4px;
    }
    .num {
      display: inline-flex;
      width: 22px;
      height: 22px;
      border-radius: 50%;
      background: #1e3a8a;
      color: #fff;
      font-size: 0.8rem;
      align-items: center;
      justify-content: center;
      margin-right: 4px;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
      gap: 12px;
      margin-bottom: 8px;
    }
    .f {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    label {
      font-weight: 600;
    }
    .cols {
      display: grid;
      grid-template-columns: minmax(0, 3fr) minmax(300px, 2fr);
      gap: 16px;
      align-items: start;
    }
    @media (max-width: 1100px) {
      .cols {
        grid-template-columns: 1fr;
      }
    }
    .table-wrap {
      overflow-x: auto;
    }
    .tbl {
      width: 100%;
      min-width: 760px;
      border-collapse: collapse;
    }
    .tbl td.evt {
      min-width: 240px;
    }
    .tbl td.evt .mono {
      word-break: normal;
      overflow-wrap: anywhere;
    }
    .tbl th,
    .tbl td {
      text-align: left;
      padding: 6px 8px;
      border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
      vertical-align: top;
    }
    .tbl th {
      font-weight: 600;
    }
    tr.broken td {
      background: #fef2f2;
    }
    tr.sel td {
      outline: 2px solid #1e3a8a;
      outline-offset: -2px;
    }
    .link {
      background: none;
      border: 0;
      padding: 0;
      color: #1d4ed8;
      text-decoration: underline;
      cursor: pointer;
      font: inherit;
      text-align: left;
    }
    .chain {
      list-style: none;
      padding: 0;
      margin: 0 0 8px;
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }
    .chain li {
      flex: 1 1 180px;
      display: flex;
      flex-direction: column;
      gap: 2px;
      border: 1px solid var(--p-content-border-color, #d1d5db);
      border-radius: 6px;
      padding: 8px;
    }
    .chain li.bad-box {
      border: 2px solid #b91c1c;
    }
    .meta {
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: 4px 12px;
      margin: 8px 0;
    }
    .meta dt {
      color: #4b5563;
    }
    .meta dd {
      margin: 0;
    }
    .run-form {
      display: grid;
      gap: 8px;
      border-top: 1px solid var(--p-content-border-color, #e5e7eb);
      padding-top: 8px;
    }
    .anchors {
      margin: 0;
      padding-left: 18px;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
    :host ::ng-deep .p-message-success .p-message-text,
    :host ::ng-deep .p-message-success .p-message-icon {
      color: #0f5132;
    }
    :host ::ng-deep .p-message-error .p-message-text,
    :host ::ng-deep .p-message-error .p-message-icon {
      color: #b91c1c;
    }
  `,
})
export class LedgerPage {
  private readonly api = inject(LedgerApi);
  private readonly theme = inject(ThemeService);
  private readonly pollMs = inject(VERIFY_POLL_MS);
  protected readonly can = inject(OVERSIGHT_CAN);

  protected readonly partitions = LEDGER_PARTITIONS;
  protected readonly actorKind = LEDGER_ACTOR_KIND_LABEL;
  protected readonly anchorTarget = LEDGER_ANCHOR_TARGET_LABEL;
  protected readonly verifyKind = LEDGER_VERIFY_KIND_LABEL;
  protected readonly aggregateTypes = [
    { value: '', label: 'Tất cả' },
    ...Object.entries(LEDGER_AGGREGATE_TYPE_LABEL).map(([value, label]) => ({ value, label })),
  ];
  protected readonly eventTypes = [
    { value: '', label: 'Tất cả' },
    ...EVENT_TYPES.map((e) => ({ value: e, label: LEDGER_EVENT_TYPE_LABEL[e] })),
  ];
  protected readonly verifyKinds = (
    Object.keys(LEDGER_VERIFY_KIND_LABEL) as LedgerVerifyKind[]
  ).map((value) => ({ value, label: LEDGER_VERIFY_KIND_LABEL[value] }));

  // vùng 1: bộ lọc, nhận sẵn đối tượng từ SCR-1301, SCR-1401
  private readonly query = inject(ActivatedRoute).snapshot.queryParamMap;
  protected readonly aggregateType = signal(this.query.get('aggregateType') ?? '');
  protected readonly aggregateId = signal(this.query.get('aggregateId') ?? '');
  protected readonly from = signal(isoDate(new Date(Date.now() - LEDGER_DEFAULT_RANGE_DAYS * DAY)));
  protected readonly to = signal(isoDate(new Date()));
  protected readonly eventType = signal('');
  protected readonly partition = signal<number | null>(null);

  /** Bộ lọc đã áp dụng (khi bấm Tìm) và con trỏ trang kế. */
  private readonly applied = signal<{ filter: LedgerEntryFilter; cursor: string | null }>({
    filter: this.currentFilter(),
    cursor: null,
  });
  protected readonly page = rxResource({
    params: () => this.applied(),
    stream: ({ params }) => this.api.entries(params.filter, params.cursor),
  });
  protected readonly rows = signal<LedgerEntry[]>([]);
  protected readonly cursor = signal<string | null>(null);
  protected readonly pageError = computed(() => errorText(this.page.error()));

  // vùng 4, 3: lần kiểm tra
  protected readonly runs = signal<LedgerVerifyRun[]>([]);
  protected readonly runId = signal<string | null>(null);
  protected readonly run = computed<LedgerVerifyRun | null>(
    () => this.runs().find((r) => r.id === this.runId()) ?? this.runs()[0] ?? null,
  );
  protected readonly runOptions = computed(() =>
    this.runs().map((r) => ({
      value: r.id,
      label: `${r.code ? r.code + ' · ' : ''}${fmtDateTime(r.startedAt)}${r.finishedAt ? (r.mismatches ? ' · phát hiện sai lệch' : ' · toàn vẹn') : ' · đang kiểm tra'}`,
    })),
  );
  protected readonly newKind = signal<LedgerVerifyKind>('TOAN_BO');
  protected readonly newPartition = signal<number | null>(null);
  protected readonly running = signal(false);

  // vùng 5
  protected readonly selectedId = signal<string | null>(null);
  protected readonly detail = rxResource({
    params: () => this.selectedId() ?? undefined,
    stream: ({ params }) => this.api.entry(params),
  });

  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly exporting = signal(false);
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  constructor() {
    // gộp các trang đã tải: trang đầu thay danh sách, trang sau nối thêm
    effect(() => {
      const v = this.page.value();
      if (!v) return;
      const append = !!untracked(this.applied).cursor;
      untracked(() => {
        this.rows.set(append ? [...this.rows(), ...v.items] : v.items);
        this.cursor.set(v.nextCursor ?? null);
      });
    });
    this.api.verifyRuns().subscribe({
      next: (list) => {
        this.runs.set(list);
        const m = list[0]?.firstMismatch?.entryId;
        if (m && !this.selectedId()) this.selectedId.set(m);
      },
      error: (e) => this.error.set(errorText(e)),
    });
    inject(DestroyRef).onDestroy(() => clearTimeout(this.pollTimer));
  }

  private pollTimer: ReturnType<typeof setTimeout> | undefined;

  private currentFilter(): LedgerEntryFilter {
    const f: LedgerEntryFilter = {
      from: new Date(`${this.from()}T00:00:00`).toISOString(),
      to: new Date(`${this.to()}T23:59:59`).toISOString(),
    };
    if (this.aggregateType()) f.aggregateType = this.aggregateType();
    if (this.aggregateId().trim()) f.aggregateId = this.aggregateId().trim();
    if (this.eventType()) f.eventType = this.eventType();
    if (this.partition() !== null && !Number.isNaN(this.partition()))
      f.chainPartition = this.partition()!;
    return f;
  }

  protected search() {
    this.error.set(null);
    this.applied.set({ filter: this.currentFilter(), cursor: null });
  }
  protected clearFilter() {
    this.aggregateType.set('');
    this.aggregateId.set('');
    this.eventType.set('');
    this.partition.set(null);
    this.from.set(isoDate(new Date(Date.now() - LEDGER_DEFAULT_RANGE_DAYS * DAY)));
    this.to.set(isoDate(new Date()));
    this.search();
  }
  protected more() {
    this.applied.set({ filter: this.applied().filter, cursor: this.cursor() });
  }
  protected filterBy(e: LedgerEntry) {
    this.aggregateType.set(e.aggregateType);
    this.aggregateId.set(e.aggregateId);
    this.search();
  }
  protected select(e: LedgerEntry) {
    this.selectedId.set(e.id);
  }
  protected selectId(id: string) {
    this.selectedId.set(id);
  }
  protected chooseRun(id: string) {
    this.runId.set(id);
  }

  protected startVerify() {
    this.error.set(null);
    this.notice.set(null);
    this.running.set(true);
    const p = this.newPartition();
    this.api
      .startVerify({
        verifyKind: this.newKind(),
        ...(p !== null && !Number.isNaN(p) ? { chainPartition: p } : {}),
      })
      .subscribe({
        next: ({ job }) => {
          const id = verifyRunIdOf(job);
          if (!id) {
            this.running.set(false);
            this.notice.set('Đã bắt đầu kiểm tra toàn vẹn; kết quả sẽ hiện ở mục Lần kiểm tra.');
            return;
          }
          this.poll(id);
        },
        error: (e) => {
          this.running.set(false);
          this.error.set(errorText(e));
        },
      });
  }

  /** Đọc lại lần kiểm tra tới khi xong (tác vụ nền vài phút, QT-13 bước 3). */
  private poll(id: string) {
    this.api.verifyRun(id).subscribe({
      next: (r) => {
        this.runs.set([r, ...this.runs().filter((x) => x.id !== r.id)]);
        this.runId.set(r.id);
        if (!r.finishedAt) {
          this.pollTimer = setTimeout(() => this.poll(id), this.pollMs);
          return;
        }
        this.running.set(false);
        if (r.mismatches > 0) {
          if (r.firstMismatch?.entryId) this.selectedId.set(r.firstMismatch.entryId);
        } else {
          // MSG-13-05
          this.notice.set(
            `Kiểm tra toàn vẹn hoàn tất: ${r.partitionsMatched ?? 0}/${r.partitionsTotal ?? 0} phân vùng khớp, ${fmtInt(r.entriesChecked)} bản ghi, ${this.seconds(r)} giây.`,
          );
        }
      },
      error: (e) => {
        this.running.set(false);
        this.error.set(errorText(e));
      },
    });
  }

  protected exportEvidence() {
    this.error.set(null);
    this.notice.set(null);
    this.exporting.set(true);
    const f = this.applied().filter;
    const run = this.run();
    this.api
      .export({
        from: f.from!,
        to: f.to!,
        ...(f.aggregateType ? { aggregateType: f.aggregateType } : {}),
        ...(f.aggregateId ? { aggregateId: f.aggregateId } : {}),
        ...(f.eventType ? { eventType: f.eventType } : {}),
        ...(run?.finishedAt ? { includeVerifyRunId: run.id } : {}),
      })
      .subscribe({
        next: ({ job }) => {
          this.exporting.set(false);
          // MSG-13-07 (bỏ tên công cụ dòng lệnh `ledger-verify` khỏi câu hiển thị); tệp tải về ở
          // `job.resultLocation` khi job hoàn tất
          this.notice.set(
            job.status === 'HOAN_TAT'
              ? 'Tệp bằng chứng đã sẵn sàng. Cơ quan kiểm tra có thể dùng công cụ kiểm tra sổ cái để đối chiếu tệp một cách độc lập.'
              : 'Đang chuẩn bị tệp bằng chứng; hệ thống sẽ thông báo khi hoàn tất.',
          );
        },
        error: (e) => {
          this.exporting.set(false);
          this.error.set(errorText(e));
        },
      });
  }

  protected isBroken(e: LedgerEntry) {
    const m = this.run()?.firstMismatch;
    return !!m && m.chainPartition === e.chainPartition && m.seqNo === e.seqNo;
  }
  protected before(e: LedgerEntry) {
    return values(e.aggregateType, (e.metadata as { before?: unknown } | undefined)?.before);
  }
  protected after(e: LedgerEntry) {
    return values(e.aggregateType, (e.metadata as { after?: unknown } | undefined)?.after);
  }
  protected eventLabel(t: string) {
    return LEDGER_EVENT_TYPE_LABEL[t] ?? 'Thao tác khác';
  }
  /** Loại và tên đối tượng; không hiện `aggregateId` nội bộ, tên lấy từ `metadata.aggregateLabel` khi có. */
  protected subject(e: LedgerEntry) {
    const type = LEDGER_AGGREGATE_TYPE_LABEL[e.aggregateType] ?? 'Đối tượng';
    const name = (e.metadata as { aggregateLabel?: unknown } | undefined)?.aggregateLabel;
    return typeof name === 'string' && name ? `${type}: ${name}` : type;
  }
  protected hash(h?: string | null, n = 4) {
    return shortLedgerHash(h, n);
  }
  protected dt(iso?: string | null) {
    return fmtDateTime(iso);
  }
  protected time(iso?: string | null) {
    return fmtDateTime(iso).slice(11);
  }
  protected int(n?: number | null) {
    return fmtInt(n);
  }
  protected seconds(r: LedgerVerifyRun) {
    if (!r.finishedAt) return 0;
    return Math.round((Date.parse(r.finishedAt) - Date.parse(r.startedAt)) / 1000);
  }
}
