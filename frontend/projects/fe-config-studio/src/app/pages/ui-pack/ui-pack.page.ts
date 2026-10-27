import {
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  linkedSignal,
  signal,
} from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { TextareaModule } from 'primeng/textarea';
import { last } from 'rxjs';
import {
  JobAccepted,
  JobTracker,
  UI_PACK_STATUS_LABEL,
  UiPackApi,
  UiPackRef,
  UiPackVersion,
  UiSduiNode,
  uiVersionLabel,
  uiWidgetLabel,
} from '@vt/api-client';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { STUDIO_STEP_UP } from '../../studio-step-up';
import { errorText, fmtDate, jobErrorText, problemCode } from '../../studio-ui';
import { UiPackPreview } from './ui-pack-preview';
import {
  FONT_OPTIONS,
  MAX_HOME_WIDGETS,
  TOKEN_FIELDS,
  TokenField,
  TokenTree,
  contrastChecks,
  contrastMessage,
  homeWidgets,
  isHex,
  moveItem,
  pxOf,
  ratioText,
  setToken,
  tokenText,
  withHomeWidgets,
} from './ui-pack-tokens';
import { VtSkeletonComponent } from '@vt/design-system';

/*
 * SCR-1801 Cấu hình giao diện tenant (QT-18, TL-02b), đối chiếu đặc tả:
 * - Trường: (1) phiên bản, trạng thái; (2) Policy Pack gắn; (3) token màu (tương phản AA); (4) token chữ
 *   12–16 px, bo góc 0–16 px, khoảng cách; (5) logo; (6) bố cục trang chủ ≤ 12 khối trong danh mục;
 *   (7) chế độ cao tuổi; (8) cờ ít bước (chỉ khi bật chế độ cao tuổi).
 * - Chức năng: Lưu nháp (PUT), Thêm khối từ danh mục (GET components), Xem trước (GET preview),
 *   Gửi duyệt (POST submit, 202), Duyệt (POST publish, AAL2, khác người soạn), Từ chối (POST reject),
 *   Quay lui (POST activate).
 * - Quy tắc: BR-18-01 tương phản, BR-18-02 danh mục, BR-18-03 màn hình lõi chỉ nhận token, BR-18-08
 *   kích thước ≤ 30 KB, BR-18-09 chế độ cao tuổi, BR-18-10 phát hành trong ngày và quay lui.
 * - Thông báo: MSG-18-01 tương phản, MSG-18-02 khối lạ, MSG-18-03 đã phát hành, MSG-18-04 không quay
 *   lui được, MSG-18-05 vượt kích thước.
 * Xem trước song song: bản đang áp dụng và bản đang soạn của cùng đơn vị (suy luận, wiki/14).
 */

type Tab = 'token' | 'home' | 'elder' | 'versions';

const TABS: { id: Tab; label: string }[] = [
  { id: 'token', label: 'Màu sắc và chữ' },
  { id: 'home', label: 'Trang chủ' },
  { id: 'elder', label: 'Người cao tuổi' },
  { id: 'versions', label: 'Phiên bản' },
];

/** Khoảng giữa hai lần đọc lại trạng thái khi kiểm tra tự động hoặc phát hành đang chạy. */
const POLL_MS = 1500;

interface Draft {
  tokens: TokenTree;
  layouts: Record<string, UiSduiNode>;
  elderMode: boolean;
  fewSteps: boolean;
}

const draftOf = (v: UiPackVersion | undefined): Draft | null =>
  v
    ? {
        tokens: structuredClone(v.tokens) as TokenTree,
        layouts: structuredClone(v.layouts),
        elderMode: v.elderMode,
        fewSteps: v.fewSteps,
      }
    : null;

@Component({
  selector: 'vt-ui-pack-page',
  imports: [
    VtSkeletonComponent,
    FormsModule,
    RouterLink,
    ButtonModule,
    DialogModule,
    TextareaModule,
    UiPackPreview,
  ],
  template: `
    <header class="head">
      <div>
        <h1>Giao diện đơn vị</h1>
        <p class="sub">
          Màu sắc, chữ và bố cục trang chủ của đơn vị; thay đổi được áp dụng sau khi duyệt.
        </p>
      </div>
      @if (ver(); as v) {
        <div class="actions">
          @if (rollbackTarget(); as t) {
            @if (can('SCR-1801.quay-lui')) {
              <p-button
                data-testid="rollback"
                [label]="'Quay lui bản ' + vlabel(t.version)"
                severity="secondary"
                [outlined]="true"
                [disabled]="busy()"
                (onClick)="askRollback(t)"
              />
            }
          }
          @if (v.status === 'NHAP' && can('SCR-1801.soan')) {
            <p-button
              data-testid="save"
              label="Lưu nháp"
              severity="secondary"
              [outlined]="true"
              [disabled]="!dirty() || busy() || !localOk()"
              (onClick)="save()"
            />
          }
          @if (v.status === 'NHAP' && can('SCR-1801.gui-duyet')) {
            <p-button
              data-testid="submit"
              [label]="'Gửi duyệt bản ' + vlabel(v.version)"
              [disabled]="dirty() || busy() || !localOk()"
              (onClick)="submit()"
            />
          }
          @if (v.status === 'CHO_DUYET' && can('SCR-1801.duyet')) {
            <p-button
              data-testid="reject"
              label="Từ chối"
              severity="secondary"
              [outlined]="true"
              [disabled]="busy()"
              (onClick)="rejectOpen.set(true)"
            />
            <p-button
              data-testid="publish"
              label="Duyệt và phát hành"
              [disabled]="busy()"
              (onClick)="publish()"
            />
          }
        </div>
      }
    </header>

    @if (packs.error() || versions.error()) {
      <p class="err" role="alert">Không tải được cấu hình giao diện. Vui lòng thử lại.</p>
    } @else if (!pack()) {
      <vt-skeleton variant="form" [rows]="5" label="Đang tải cấu hình giao diện" />
    }

    @if (notice(); as n) {
      <p class="notice" role="status" data-testid="notice">{{ n }}</p>
    }
    @if (error(); as e) {
      <p class="err" role="alert" data-testid="error">{{ e }}</p>
    }

    @if (pack(); as p) {
      <!-- chờ danh sách phiên bản về rồi mới mời tạo bản nháp, tránh bấm lúc chưa biết có bản đang soạn -->
      @if (!ver() && versions.hasValue() && !versions.isLoading()) {
        <section class="card" data-testid="no-draft">
          <p>
            Đơn vị đang dùng giao diện bản {{ vlabel(currentVersion() ?? p.currentVersion) }}. Tạo
            bản nháp mới để chỉnh màu sắc, chữ hoặc bố cục trang chủ.
          </p>
          @if (can('SCR-1801.soan')) {
            <p-button
              data-testid="create"
              label="Tạo bản nháp từ bản đang dùng"
              [disabled]="busy()"
              (onClick)="createDraft()"
            />
          }
        </section>
      }
      @if (ver(); as v) {
        <div class="bar" data-testid="pack-bar">
          <span class="chip" [attr.data-status]="v.status">
            Bản {{ vlabel(v.version) }} · {{ statusLabel[v.status] }}
          </span>
          @if (published(); as cur) {
            <span class="chip ok">Đang áp dụng: bản {{ vlabel(cur.version) }}</span>
          }
          @if (v.policyPack; as pp) {
            <span class="meta"> Áp dụng cùng chính sách đánh giá bản {{ pp.version }} </span>
          }
        </div>
        @if (v.status === 'DANG_KIEM_TRA') {
          <p class="notice" role="status">Đang kiểm tra tự động bản {{ vlabel(v.version) }}…</p>
        }
        @if (v.reviewComment && v.status === 'NHAP') {
          <p class="warn" data-testid="review-comment">
            Ý kiến của người duyệt: {{ v.reviewComment }}
          </p>
        }
        @if (v.status === 'CHO_DUYET' && !can('SCR-1801.duyet')) {
          <p class="hint">Bản này đang chờ một quản trị khác duyệt và phát hành.</p>
        }
        @if (!editable()) {
          <p class="hint" data-testid="readonly">Bản này không còn là bản nháp nên chỉ xem được.</p>
        }

        <div class="grid">
          <div class="col">
            <section class="card">
              <div class="tabs" role="tablist" aria-label="Nội dung cấu hình">
                @for (t of tabs; track t.id) {
                  <button
                    type="button"
                    role="tab"
                    [id]="'tab-' + t.id"
                    [attr.aria-selected]="tab() === t.id"
                    [attr.aria-controls]="'panel-' + t.id"
                    [class.on]="tab() === t.id"
                    (click)="tab.set(t.id)"
                  >
                    {{ t.label }}
                  </button>
                }
              </div>

              @if (draft(); as d) {
                @switch (tab()) {
                  @case ('token') {
                    <div role="tabpanel" id="panel-token" aria-labelledby="tab-token">
                      <table class="tokens" data-testid="tokens">
                        <caption class="sr-only">
                          Màu sắc và chữ
                        </caption>
                        <tbody>
                          @for (f of tokenFields; track f.path) {
                            <tr [attr.data-token]="f.path">
                              <th scope="row">
                                <label [for]="'t-' + $index">{{ f.label }}</label>
                              </th>
                              <td>
                                @switch (f.kind) {
                                  @case ('color') {
                                    <span class="color">
                                      <input
                                        type="color"
                                        [attr.aria-label]="'Chọn ' + f.label.toLowerCase()"
                                        [value]="hexOr(d.tokens, f.path)"
                                        [disabled]="!editable()"
                                        (input)="setColor(f, $event)"
                                      />
                                      <input
                                        class="native hex"
                                        [id]="'t-' + $index"
                                        maxlength="7"
                                        [value]="text(d.tokens, f.path)"
                                        [attr.aria-invalid]="!hex(text(d.tokens, f.path))"
                                        [disabled]="!editable()"
                                        (change)="setColor(f, $event)"
                                      />
                                    </span>
                                  }
                                  @case ('font') {
                                    <select
                                      class="native"
                                      [id]="'t-' + $index"
                                      [disabled]="!editable()"
                                      (change)="setFont(f, $event)"
                                    >
                                      @for (o of fonts; track o.label) {
                                        <option
                                          [value]="o.label"
                                          [selected]="o.label === text(d.tokens, f.path)"
                                        >
                                          {{ o.label }}
                                        </option>
                                      }
                                    </select>
                                  }
                                  @case ('px') {
                                    <span class="px">
                                      <input
                                        class="native num"
                                        type="number"
                                        [id]="'t-' + $index"
                                        [min]="f.min"
                                        [max]="f.max"
                                        [value]="px(d.tokens, f.path)"
                                        [disabled]="!editable()"
                                        (change)="setPx(f, $event)"
                                      />
                                      <span>điểm ảnh, từ {{ f.min }} đến {{ f.max }}</span>
                                    </span>
                                  }
                                }
                              </td>
                            </tr>
                          }
                          <tr>
                            <th scope="row">Biểu trưng</th>
                            <td>{{ logoName() }}</td>
                          </tr>
                        </tbody>
                      </table>
                      <ul class="checks" data-testid="contrast" aria-live="polite">
                        @for (c of contrast(); track c.label) {
                          <li [class.bad]="!c.ok">
                            @if (c.ok) {
                              {{ c.label }}: tương phản {{ ratio(c.ratio) }}, đạt.
                            } @else {
                              {{ contrastMsg(c) }}
                            }
                          </li>
                        }
                      </ul>
                    </div>
                  }
                  @case ('home') {
                    <div role="tabpanel" id="panel-home" aria-labelledby="tab-home">
                      <p class="hint">
                        Chỉ chọn khối trong danh mục được phép, tối đa {{ maxWidgets }} khối. Các
                        màn hình nghiệp vụ chính chỉ nhận màu sắc và chữ, không đổi bố cục.
                      </p>
                      <ol class="widgets" data-testid="widgets">
                        @for (w of widgets(); track $index; let i = $index, last = $last) {
                          <li [attr.data-widget]="w.component">
                            <span>{{ label(w.component) }}</span>
                            @if (editable()) {
                              <span class="row-act">
                                <button
                                  type="button"
                                  class="icon"
                                  [disabled]="i === 0"
                                  [attr.aria-label]="'Đưa ' + label(w.component) + ' lên trên'"
                                  (click)="move(i, i - 1)"
                                >
                                  ↑
                                </button>
                                <button
                                  type="button"
                                  class="icon"
                                  [disabled]="last"
                                  [attr.aria-label]="'Đưa ' + label(w.component) + ' xuống dưới'"
                                  (click)="move(i, i + 1)"
                                >
                                  ↓
                                </button>
                                <button
                                  type="button"
                                  class="icon"
                                  [attr.aria-label]="'Bỏ ' + label(w.component)"
                                  (click)="removeWidget(i)"
                                >
                                  ✕
                                </button>
                              </span>
                            }
                          </li>
                        }
                      </ol>
                      @if (editable()) {
                        <p-button
                          data-testid="add-widget"
                          label="Thêm khối từ danh mục"
                          severity="secondary"
                          [outlined]="true"
                          [disabled]="widgets().length >= maxWidgets"
                          (onClick)="catalogOpen.set(true)"
                        />
                      }
                    </div>
                  }
                  @case ('elder') {
                    <div role="tabpanel" id="panel-elder" aria-labelledby="tab-elder">
                      <label class="switch">
                        <input
                          type="checkbox"
                          role="switch"
                          data-testid="elder"
                          [checked]="d.elderMode"
                          [disabled]="!editable()"
                          (change)="setElder($event)"
                        />
                        <span>
                          <strong>Bật chế độ người cao tuổi cho cả đơn vị</strong>
                          <small
                            >Chữ từ 17 điểm ảnh, nút cao 48 điểm ảnh, tương phản từ 7 : 1.</small
                          >
                        </span>
                      </label>
                      <label class="switch">
                        <input
                          type="checkbox"
                          role="switch"
                          data-testid="few-steps"
                          [checked]="d.fewSteps"
                          [disabled]="!editable() || !d.elderMode"
                          (change)="setFewSteps($event)"
                        />
                        <span>
                          <strong>Rút gọn các bước</strong>
                          <small>
                            Mỗi màn hình một câu hỏi khi cập nhật tiến độ và tự đánh giá. Chỉ bật
                            được khi đã bật chế độ người cao tuổi.
                          </small>
                        </span>
                      </label>
                      @if (d.elderMode) {
                        <ul class="checks">
                          @for (c of elderContrast(); track c.label) {
                            <li [class.bad]="!c.ok">
                              {{ c.label }} ở chế độ người cao tuổi: {{ ratio(c.ratio) }}
                              {{ c.ok ? ', đạt.' : ', cần chữ đậm hơn để đạt 7 : 1.' }}
                            </li>
                          }
                        </ul>
                      }
                    </div>
                  }
                  @case ('versions') {
                    <div role="tabpanel" id="panel-versions" aria-labelledby="tab-versions">
                      <table data-testid="versions">
                        <caption class="sr-only">
                          Các phiên bản giao diện
                        </caption>
                        <thead>
                          <tr>
                            <th scope="col">Bản</th>
                            <th scope="col">Trạng thái</th>
                            <th scope="col">Phát hành</th>
                            <th scope="col"><span class="sr-only">Thao tác</span></th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (x of versions.value()?.items ?? []; track x.id) {
                            <tr [attr.data-version]="x.version">
                              <td>{{ vlabel(x.version) }}</td>
                              <td>{{ statusLabel[x.status] }}</td>
                              <td>{{ date(x.publishedAt) }}</td>
                              <td>
                                @if (x.status === 'THAY_THE' && can('SCR-1801.quay-lui')) {
                                  <button
                                    type="button"
                                    class="link"
                                    [disabled]="busy()"
                                    (click)="askRollback(x)"
                                  >
                                    Quay lui về bản này
                                  </button>
                                }
                              </td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    </div>
                  }
                }
              }
            </section>

            <section class="card" data-testid="checks">
              <h2>Kiểm tra tự động</h2>
              @if (preview.value(); as pv) {
                <ul class="checks">
                  @for (c of pv.checks; track c.code) {
                    <li [class.bad]="!c.passed" [attr.data-check]="c.code">
                      {{ c.passed ? 'Đạt' : 'Chưa đạt' }}: {{ c.message }}
                    </li>
                  }
                </ul>
                @if (dirty()) {
                  <p class="hint">Kết quả theo bản đã lưu; lưu nháp để kiểm tra lại.</p>
                }
              } @else if (preview.isLoading()) {
                <vt-skeleton variant="list" [rows]="3" label="Đang kiểm tra cấu hình giao diện" />
              }
            </section>
          </div>

          <div class="col">
            <section class="card">
              <div class="pv-head">
                <h2>Xem trước</h2>
                <label class="inline">
                  <input
                    type="checkbox"
                    data-testid="preview-elderly"
                    [checked]="previewElderly()"
                    (change)="previewElderly.set(checked($event))"
                  />
                  Xem ở chế độ người cao tuổi
                </label>
              </div>
              <div class="pvs" data-testid="preview">
                @if (published(); as cur) {
                  @if (cur.id !== v.id) {
                    <vt-ui-pack-preview
                      [tokens]="tokensOf(cur)"
                      [layouts]="cur.layouts"
                      [brand]="packName()"
                      [elderly]="previewElderly()"
                      [caption]="'Bản ' + vlabel(cur.version) + ' đang áp dụng'"
                    />
                  }
                }
                @if (draft(); as d) {
                  <vt-ui-pack-preview
                    [tokens]="d.tokens"
                    [layouts]="d.layouts"
                    [brand]="packName()"
                    [elderly]="previewElderly() && d.elderMode"
                    [caption]="
                      'Bản ' + vlabel(v.version) + ' · ' + statusLabel[v.status].toLowerCase()
                    "
                  />
                }
              </div>
            </section>

            <section class="card">
              <h2>Phạm vi được cấu hình</h2>
              <table class="scope">
                <caption class="sr-only">
                  Phần đơn vị được và không được cấu hình
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Phần giao diện</th>
                    <th scope="col">Đơn vị cấu hình được</th>
                    <th scope="col">Không cấu hình</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td>Màu sắc, biểu trưng, chữ, bo góc</td>
                    <td class="yes">Toàn bộ, trong giới hạn tương phản</td>
                    <td>—</td>
                  </tr>
                  <tr>
                    <td>Trang chủ</td>
                    <td class="yes">Chọn khối, thứ tự, ẩn hiện</td>
                    <td>Nội dung ngoài danh mục khối có sẵn</td>
                  </tr>
                  <tr>
                    <td>Biểu mẫu nghiệp vụ</td>
                    <td class="yes">
                      Trường, nhóm, điều kiện kiểm tra
                      @if (can('SCR-1802.dung')) {
                        (<a routerLink="../bieu-mau">Biểu mẫu động</a>)
                      }
                    </td>
                    <td>Công thức tính điểm</td>
                  </tr>
                  <tr>
                    <td>Cây mục tiêu, cấu hình nghiệp vụ, thẩm định, bảng điều hành</td>
                    <td>Chỉ nhận màu sắc và chữ</td>
                    <td class="no">Giữ nguyên bố cục</td>
                  </tr>
                </tbody>
              </table>
            </section>
          </div>
        </div>
      }
    }

    <p-dialog
      header="Thêm khối vào trang chủ"
      [modal]="true"
      [(visible)]="catalogOpen"
      [style]="{ width: 'min(560px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      @if (components.error()) {
        <p class="err" role="alert">Không tải được danh mục. Vui lòng thử lại.</p>
      } @else if (components.isLoading() || packs.isLoading() || versions.isLoading()) {
        <vt-skeleton variant="list" [rows]="4" label="Đang tải danh mục khối" />
      }
      <ul class="catalog" data-testid="catalog">
        @for (c of catalog(); track c.componentKey) {
          <li>
            <span>
              {{ label(c.componentKey) }}
              @if (c.plugin) {
                <small>thuộc phân hệ chuyên ngành</small>
              }
            </span>
            <p-button
              size="small"
              label="Thêm"
              [attr.data-add]="c.componentKey"
              [ariaLabel]="'Thêm ' + label(c.componentKey)"
              (onClick)="addWidget(c.componentKey)"
            />
          </li>
        }
      </ul>
    </p-dialog>

    <p-dialog
      header="Quay lui phiên bản giao diện"
      [modal]="true"
      [visible]="!!rollback()"
      (visibleChange)="!$event && rollback.set(null)"
      [style]="{ width: 'min(520px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      @if (rollback(); as t) {
        <p>
          Áp dụng lại giao diện bản {{ vlabel(t.version) }} cho toàn đơn vị. Người dùng nhận giao
          diện này ở lần tải trang kế tiếp.
        </p>
      }
      <ng-template #footer>
        <p-button
          label="Huỷ"
          severity="secondary"
          [outlined]="true"
          (onClick)="rollback.set(null)"
        />
        <p-button
          data-testid="rollback-confirm"
          label="Quay lui"
          [disabled]="busy()"
          (onClick)="doRollback()"
        />
      </ng-template>
    </p-dialog>

    <p-dialog
      header="Từ chối phiên bản giao diện"
      [modal]="true"
      [(visible)]="rejectOpen"
      [style]="{ width: 'min(560px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <label class="f">
        <span>Ý kiến <span aria-hidden="true">*</span></span>
        <textarea
          pTextarea
          rows="4"
          maxlength="1000"
          required
          data-testid="reject-comment"
          [ngModel]="rejectComment()"
          (ngModelChange)="rejectComment.set($event)"
        ></textarea>
      </label>
      <ng-template #footer>
        <p-button
          label="Huỷ"
          severity="secondary"
          [outlined]="true"
          (onClick)="rejectOpen.set(false)"
        />
        <p-button
          data-testid="reject-confirm"
          label="Từ chối"
          [disabled]="busy() || !rejectComment().trim()"
          (onClick)="reject()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    :host {
      display: block;
    }
    .head {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      justify-content: space-between;
      align-items: flex-start;
      margin-bottom: 12px;
    }
    h1 {
      font-size: 1.5rem;
      margin: 0;
    }
    h2 {
      font-size: 1.05rem;
      margin: 0 0 10px;
    }
    .sub,
    .hint,
    .meta {
      color: #4b5563;
    }
    .sub {
      margin: 4px 0 0;
      max-width: 72ch;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .bar {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 12px;
      align-items: center;
      margin-bottom: 12px;
    }
    .chip {
      padding: 4px 10px;
      border-radius: 999px;
      background: #fef3c7;
      color: #78350f;
      font-weight: 500;
    }
    .chip.ok {
      background: #dcfce7;
      color: #14532d;
    }
    .grid {
      display: grid;
      grid-template-columns: minmax(0, 5fr) minmax(0, 7fr);
      gap: 16px;
      align-items: start;
    }
    @media (max-width: 1100px) {
      .grid {
        grid-template-columns: 1fr;
      }
    }
    .col {
      display: grid;
      gap: 16px;
    }
    .card {
      background: #fff;
      border: 1px solid #e5e7eb;
      border-radius: 12px;
      padding: 16px;
    }
    .tabs {
      display: flex;
      gap: 4px;
      border-bottom: 1px solid #e5e7eb;
      margin-bottom: 12px;
      overflow-x: auto;
    }
    .tabs button {
      background: none;
      border: 0;
      border-bottom: 2px solid transparent;
      padding: 10px 12px;
      font: inherit;
      color: #374151;
      cursor: pointer;
      white-space: nowrap;
      min-height: 44px;
    }
    .tabs button.on {
      color: #b4001f;
      border-bottom-color: #b4001f;
      font-weight: 600;
    }
    table {
      width: 100%;
      border-collapse: collapse;
    }
    th,
    td {
      text-align: left;
      padding: 8px;
      border-bottom: 1px solid #e5e7eb;
      vertical-align: middle;
    }
    thead th {
      background: #f8fafc;
      font-weight: 500;
    }
    .tokens th {
      font-weight: 500;
      width: 45%;
    }
    .native {
      padding: 6px 10px;
      border: 1px solid #94a3b8;
      border-radius: 6px;
      font: inherit;
      min-height: 40px;
      background: #fff;
    }
    .hex {
      width: 9ch;
      font-family: ui-monospace, Menlo, Consolas, monospace;
    }
    .num {
      width: 7ch;
    }
    .color,
    .px {
      display: inline-flex;
      gap: 8px;
      align-items: center;
      flex-wrap: wrap;
    }
    input[type='color'] {
      width: 40px;
      height: 40px;
      padding: 0;
      border: 1px solid #94a3b8;
      border-radius: 6px;
      background: #fff;
    }
    .checks {
      list-style: none;
      padding: 0;
      margin: 10px 0 0;
      display: grid;
      gap: 6px;
    }
    .checks li {
      padding: 6px 10px;
      border-radius: 6px;
      background: #f0fdf4;
      color: #14532d;
    }
    .checks li.bad {
      background: #fef2f2;
      color: #991b1b;
    }
    .widgets {
      padding: 0;
      list-style: none;
      display: grid;
      gap: 6px;
      margin: 0 0 12px;
    }
    .widgets li {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
      border: 1px solid #e5e7eb;
      border-radius: 999px;
      padding: 4px 8px 4px 14px;
    }
    .row-act {
      display: inline-flex;
      gap: 4px;
    }
    .icon {
      min-width: 36px;
      min-height: 36px;
      border: 1px solid #cbd5e1;
      background: #fff;
      border-radius: 6px;
      cursor: pointer;
    }
    .icon:disabled {
      opacity: 0.45;
      cursor: default;
    }
    .switch {
      display: flex;
      gap: 12px;
      align-items: flex-start;
      padding: 10px 0;
      border-bottom: 1px solid #f1f5f9;
    }
    .switch input {
      width: 22px;
      height: 22px;
      margin-top: 2px;
    }
    .switch span {
      display: grid;
      gap: 2px;
    }
    .switch small {
      color: #4b5563;
    }
    .pv-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px;
      margin-bottom: 10px;
    }
    .pv-head h2 {
      margin: 0;
    }
    .inline {
      display: inline-flex;
      gap: 6px;
      align-items: center;
    }
    .pvs {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 12px;
    }
    .scope td.yes {
      color: #166534;
    }
    .scope td.no {
      color: #991b1b;
    }
    .foot {
      color: #4b5563;
      margin: 10px 0 0;
      font-size: 0.9rem;
    }
    .catalog {
      list-style: none;
      padding: 0;
      margin: 0;
      display: grid;
      gap: 8px;
    }
    .catalog li {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
    }
    .catalog small {
      display: block;
      color: #4b5563;
    }
    .link {
      background: none;
      border: 0;
      color: #1d4ed8;
      text-decoration: underline;
      cursor: pointer;
      font: inherit;
      padding: 4px 0;
    }
    .f {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .notice {
      background: #eff6ff;
      color: #1e3a8a;
      border-left: 4px solid #1d4ed8;
      padding: 8px 12px;
      border-radius: 6px;
    }
    .warn {
      background: #fffbeb;
      color: #78350f;
      padding: 8px 12px;
      border-radius: 6px;
    }
    .err {
      color: #b4001f;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
  `,
})
export class UiPackPage {
  private readonly api = inject(UiPackApi);
  private readonly access = inject(STUDIO_ACCESS);
  private readonly stepUp = inject(STUDIO_STEP_UP);
  private readonly destroyRef = inject(DestroyRef);
  private readonly jobs = inject(JobTracker);

  protected readonly tabs = TABS;
  protected readonly tokenFields = TOKEN_FIELDS;
  protected readonly fonts = FONT_OPTIONS;
  protected readonly maxWidgets = MAX_HOME_WIDGETS;
  protected readonly statusLabel = UI_PACK_STATUS_LABEL;
  protected readonly vlabel = uiVersionLabel;
  protected readonly label = uiWidgetLabel;
  protected readonly date = fmtDate;
  protected readonly ratio = ratioText;
  protected readonly contrastMsg = contrastMessage;
  protected readonly text = tokenText;
  protected readonly px = pxOf;
  protected readonly hex = isHex;

  protected readonly tab = signal<Tab>('token');
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly catalogOpen = signal(false);
  protected readonly rejectOpen = signal(false);
  protected readonly rejectComment = linkedSignal(() => (this.rejectOpen(), ''));
  protected readonly rollback = signal<UiPackVersion | null>(null);
  protected readonly previewElderly = signal(false);

  protected readonly packs = rxResource({ stream: () => this.api.packs() });
  protected readonly pack = computed(() => this.packs.value()?.items[0]);
  protected readonly packName = computed(
    () => this.pack()?.name.replace(/^Giao diện\s+/, '') ?? '',
  );

  protected readonly versions = rxResource({
    params: () => this.pack()?.id,
    stream: ({ params }) => this.api.versions(params),
  });
  /** Bản đang soạn hoặc chờ duyệt; không có thì trang mời tạo bản nháp. */
  protected readonly ver = computed(() =>
    (this.versions.value()?.items ?? []).find((v) =>
      ['NHAP', 'DANG_KIEM_TRA', 'CHO_DUYET'].includes(v.status),
    ),
  );
  protected readonly published = computed(() =>
    (this.versions.value()?.items ?? []).find((v) => v.status === 'DA_PHAT_HANH'),
  );
  /**
   * Bản đang dùng lấy từ danh sách phiên bản (đọc lại sau mỗi bước), vì `currentVersion` của gói chỉ
   * đúng ở lần mở màn hình; sau phát hành hay quay lui, số bản nháp mới phải tính từ bản mới.
   */
  protected readonly currentVersion = computed(() => this.published()?.version ?? null);
  /** Bản đã thay thế gần nhất, đích của nút "Quay lui" trên đầu trang. */
  protected readonly rollbackTarget = computed(() =>
    (this.versions.value()?.items ?? []).find((v) => v.status === 'THAY_THE'),
  );
  protected readonly ref = computed<UiPackRef | null>(() => {
    const p = this.pack();
    const v = this.ver();
    return p && v ? { id: p.id, v: v.version } : null;
  });
  protected readonly editable = computed(
    () => this.ver()?.status === 'NHAP' && this.can('SCR-1801.soan'),
  );

  /** Bản sửa tại chỗ, đặt lại khi máy chủ trả phiên bản mới (rowVersion đổi). */
  protected readonly draft = linkedSignal<UiPackVersion | undefined, Draft | null>({
    source: () => this.ver(),
    computation: (v, prev) =>
      prev && prev.source?.id === v?.id && prev.source?.rowVersion === v?.rowVersion
        ? prev.value
        : draftOf(v),
  });
  protected readonly dirty = computed(() => {
    const d = this.draft();
    const v = this.ver();
    return !!d && !!v && JSON.stringify(d) !== JSON.stringify(draftOf(v));
  });

  protected readonly contrast = computed(() => contrastChecks(this.draft()?.tokens ?? {}));
  protected readonly elderContrast = computed(() =>
    contrastChecks(setToken(this.draft()?.tokens ?? {}, TOKEN_FIELDS[3], '#000000'), true).filter(
      (c) => c.need === 7,
    ),
  );
  protected readonly localOk = computed(() => this.contrast().every((c) => c.ok));
  protected readonly widgets = computed(() => homeWidgets(this.draft()?.layouts ?? {}));

  protected readonly preview = rxResource({
    params: () => {
      const r = this.ref();
      const v = this.ver();
      return r && v ? { ...r, row: v.rowVersion } : undefined;
    },
    stream: ({ params }) => this.api.preview({ id: params.id, v: params.v }),
  });

  protected readonly components = rxResource({
    params: () => (this.catalogOpen() ? (this.ver()?.componentRegistryVersion ?? '') : undefined),
    stream: ({ params }) => this.api.components(params || undefined),
  });
  protected readonly catalog = computed(() =>
    (this.components.value()?.items ?? []).filter((c) => !c.container),
  );
  protected readonly logoName = computed(() => {
    const logo = this.ver()?.assets?.find((a) => a.key === 'logo');
    return logo ? logo.path.split('/').pop() : 'Biểu trưng mặc định';
  });

  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => this.timer && clearTimeout(this.timer));
    // Bản đang kiểm tra tự động do lần gửi duyệt khác (phiên khác, lần mở trước) để lại: trang không theo
    // dõi tác vụ đó nên đọc lại danh sách định kỳ tới khi kiểm tra xong (về nháp hoặc chờ duyệt), thay vì
    // đứng mãi ở "Đang kiểm tra tự động" không có thao tác nào
    effect((onCleanup) => {
      if (this.ver()?.status !== 'DANG_KIEM_TRA' || this.busy() || this.versions.isLoading())
        return;
      const t = setTimeout(() => this.reloadVersions(), POLL_MS);
      onCleanup(() => clearTimeout(t));
    });
  }

  protected can(key: Parameters<typeof this.access.can>[0]) {
    return this.access.can(key);
  }

  protected tokensOf(v: UiPackVersion): TokenTree {
    return v.tokens as TokenTree;
  }

  protected hexOr(tree: TokenTree, path: string) {
    const v = tokenText(tree, path);
    return isHex(v) ? v : '#000000';
  }

  protected checked(e: Event) {
    return (e.target as HTMLInputElement).checked;
  }

  private patch(fn: (d: Draft) => Draft) {
    this.draft.update((d) => (d ? fn(d) : d));
    this.notice.set(null);
  }

  protected setColor(f: TokenField, e: Event) {
    let v = (e.target as HTMLInputElement).value.trim();
    if (/^[0-9a-f]{6}$/i.test(v)) v = '#' + v;
    this.patch((d) => ({ ...d, tokens: setToken(d.tokens, f, v.toUpperCase()) }));
  }

  protected setFont(f: TokenField, e: Event) {
    const o = FONT_OPTIONS.find((x) => x.label === (e.target as HTMLSelectElement).value);
    if (o) this.patch((d) => ({ ...d, tokens: setToken(d.tokens, f, o.value) }));
  }

  protected setPx(f: TokenField, e: Event) {
    const n = Math.round(Number((e.target as HTMLInputElement).value));
    const v = Math.min(f.max ?? n, Math.max(f.min ?? n, Number.isFinite(n) ? n : 0));
    (e.target as HTMLInputElement).value = String(v);
    this.patch((d) => ({ ...d, tokens: setToken(d.tokens, f, `${v}px`) }));
  }

  protected setElder(e: Event) {
    const on = this.checked(e);
    this.patch((d) => ({ ...d, elderMode: on, fewSteps: on ? d.fewSteps : false }));
  }

  protected setFewSteps(e: Event) {
    const on = this.checked(e);
    this.patch((d) => ({ ...d, fewSteps: on }));
  }

  protected move(from: number, to: number) {
    this.patch((d) => ({
      ...d,
      layouts: withHomeWidgets(d.layouts, moveItem(this.widgets(), from, to)),
    }));
  }

  protected removeWidget(i: number) {
    this.patch((d) => ({
      ...d,
      layouts: withHomeWidgets(
        d.layouts,
        this.widgets().filter((_, j) => j !== i),
      ),
    }));
  }

  protected addWidget(component: string) {
    if (this.widgets().length >= MAX_HOME_WIDGETS) return;
    this.patch((d) => ({
      ...d,
      layouts: withHomeWidgets(d.layouts, [...this.widgets(), { component }]),
    }));
    this.catalogOpen.set(false);
  }

  private reloadVersions() {
    this.versions.reload();
  }

  /**
   * Theo dõi tác vụ nền (202 + `Location`) bằng `JobTracker` tới khi kết thúc; xong thì đọc trạng thái
   * phiên bản, thất bại hoặc bị huỷ thì báo lỗi của job.
   */
  private follow(
    accepted: JobAccepted,
    version: string,
    done: (v: UiPackVersion) => boolean,
    then: (v: UiPackVersion) => void,
    failText: string,
  ) {
    this.jobs
      .track(accepted.location)
      .pipe(last(), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (job) => {
          if (job.status === 'HOAN_TAT') return this.poll(version, done, then);
          this.busy.set(false);
          this.error.set(jobErrorText(job, failText));
          this.reloadVersions();
        },
        error: (e) => this.fail(e, 'Không theo dõi được tiến trình xử lý.'),
      });
  }

  private poll(
    version: string,
    done: (v: UiPackVersion) => boolean,
    then: (v: UiPackVersion) => void,
  ) {
    const pack = this.pack();
    if (!pack) return;
    const tick = () => {
      this.api.version({ id: pack.id, v: version }).subscribe({
        next: (v) => {
          if (done(v)) {
            this.busy.set(false);
            this.reloadVersions();
            then(v);
          } else {
            this.timer = setTimeout(tick, POLL_MS);
          }
        },
        error: (e) => {
          this.busy.set(false);
          this.error.set(errorText(e, 'Không đọc được trạng thái phiên bản.'));
        },
      });
    };
    this.timer = setTimeout(tick, POLL_MS);
  }

  private start() {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
  }

  private fail(e: unknown, fallback: string) {
    this.busy.set(false);
    this.error.set(errorText(e, fallback));
  }

  protected createDraft() {
    const p = this.pack();
    if (!p) return;
    const cur = this.currentVersion() ?? p.currentVersion ?? '1.0.0';
    const [a, b] = cur.split('.').map(Number);
    this.start();
    this.api.createVersion(p.id, { version: `${a}.${b + 1}.0`, cloneFromVersion: cur }).subscribe({
      next: (v) => {
        this.busy.set(false);
        this.notice.set(`Đã tạo bản nháp ${uiVersionLabel(v.version)} từ bản đang dùng.`);
        this.reloadVersions();
      },
      error: (e) => this.fail(e, 'Không tạo được bản nháp.'),
    });
  }

  protected save() {
    const r = this.ref();
    const v = this.ver();
    const d = this.draft();
    if (!r || !v || !d) return;
    this.start();
    this.api
      .save(r, v.rowVersion, {
        tokens: d.tokens,
        layouts: d.layouts,
        assets: v.assets,
        componentRegistryVersion: v.componentRegistryVersion,
        elderMode: d.elderMode,
        fewSteps: d.fewSteps,
      })
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.notice.set('Đã lưu bản nháp.');
          this.reloadVersions();
        },
        error: (e) => this.fail(e, 'Không lưu được bản nháp.'),
      });
  }

  protected submit() {
    const r = this.ref();
    const v = this.ver();
    if (!r || !v) return;
    this.start();
    this.api.submit(r, v.rowVersion).subscribe({
      next: (accepted) => {
        this.busy.set(true);
        this.reloadVersions();
        this.follow(
          accepted,
          v.version,
          (x) => x.status !== 'DANG_KIEM_TRA',
          (x) =>
            x.status === 'CHO_DUYET'
              ? this.notice.set(
                  `Bản ${uiVersionLabel(x.version)} đạt kiểm tra tự động và đã được gửi duyệt.`,
                )
              : this.error.set(
                  `Bản ${uiVersionLabel(x.version)} chưa đạt kiểm tra tự động nên đã trở về bản nháp. Xem mục Kiểm tra tự động để sửa.`,
                ),
          'Không chạy được kiểm tra tự động.',
        );
      },
      error: (e) => this.fail(e, 'Không gửi duyệt được.'),
    });
  }

  protected async publish() {
    const r = this.ref();
    const v = this.ver();
    if (!r || !v) return;
    this.start();
    if (!(await this.stepUp('Duyệt và phát hành giao diện đơn vị'))) {
      this.busy.set(false);
      this.error.set('Chưa xác thực nhiều lớp nên chưa phát hành được.');
      return;
    }
    this.api.publish(r, v.rowVersion).subscribe({
      next: (accepted) =>
        this.follow(
          accepted,
          v.version,
          (x) => x.status === 'DA_PHAT_HANH',
          (x) =>
            this.notice.set(
              `Giao diện bản ${uiVersionLabel(x.version)} đã phát hành. Người dùng sẽ nhận giao diện mới ở lần tải trang kế tiếp.`,
            ),
          'Không phát hành được giao diện.',
        ),
      error: (e) => {
        this.busy.set(false);
        this.error.set(
          problemCode(e) === 'UI-403-SAME-PERSON'
            ? 'Người duyệt phải khác người soạn phiên bản này.'
            : errorText(e, 'Không phát hành được.'),
        );
      },
    });
  }

  protected reject() {
    const r = this.ref();
    const v = this.ver();
    if (!r || !v) return;
    this.start();
    this.api.reject(r, v.rowVersion, this.rejectComment().trim()).subscribe({
      next: (x) => {
        this.busy.set(false);
        this.rejectOpen.set(false);
        this.notice.set(`Đã trả bản ${uiVersionLabel(x.version)} về cho người soạn.`);
        this.reloadVersions();
      },
      error: (e) => this.fail(e, 'Không từ chối được.'),
    });
  }

  protected askRollback(v: UiPackVersion) {
    this.error.set(null);
    this.rollback.set(v);
  }

  protected doRollback() {
    const p = this.pack();
    const t = this.rollback();
    if (!p || !t) return;
    this.start();
    this.api.activate({ id: p.id, v: t.version }, t.rowVersion).subscribe({
      next: (x) => {
        this.busy.set(false);
        this.rollback.set(null);
        this.notice.set(
          `Đã quay lui về giao diện bản ${uiVersionLabel(x.version)}. Người dùng nhận giao diện này ở lần tải trang kế tiếp.`,
        );
        this.reloadVersions();
      },
      error: (e) => {
        this.rollback.set(null);
        this.fail(e, 'Không quay lui được.');
      },
    });
  }
}
