/*
 * SCR-0402 "Lập kế hoạch quý" (QT-04 bước 2–4, TL-02 Hình 7.13) — đối chiếu đặc tả:
 * Trường: 1 kỳ; 2 đơn vị; 3 phiên bản, trạng thái; 4 hạn phê duyệt (BR-04-01); 5 nội dung ≤ 500 (BR-04-03);
 *   6 nhóm; 7 KR liên kết; 8 chủ trì đúng một (BR-04-02); 9 phối hợp; 10 mục danh mục; 11 sản phẩm, số lượng (BR-04-04);
 *   12 hạn hoặc "Hằng tháng"; 13 điểm từ danh mục (BR-04-05); 14 luồng (ẩn); 15 tỷ trọng nhóm (BR-04-06).
 * Chức năng: Sao chép từ quý trước (POST /plans/{id}/versions/{v}/copy-from); Gợi ý từ OKR (/plans/{id}/suggestions);
 *   Thêm từ danh mục; Thêm việc ngoài danh mục; Lưu nháp (PUT /plans/{id}/versions/{v} + If-Match, kèm lưu tự động);
 *   Gửi duyệt (POST …/submit + Idempotency-Key); Tạo điều chỉnh (POST /plans/{id}/versions, BR-04-10).
 * Dữ liệu (WEB-065): GET /plans, GET /plans/{id}/versions, GET /plans/{id}/versions/{v} ghép ở client.
 * Kiểm tra tức thì: POST /plans/{id}/versions/{v}/check (MOD-02, chưa có trong hợp đồng; xem wiki/14).
 * Thông báo: MSG-04-03 … MSG-04-08.
 * Quyền (wiki/15a): SCR-0402.xem, SCR-0402.lap, SCR-0402.gui-duyet, SCR-0402.dieu-chinh.
 */
import {
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  linkedSignal,
  signal,
} from '@angular/core';
import { rxResource, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { OkrApi, OrgApi, TASK_GROUP_LABEL, TaskGroup, toProblem } from '@vt/api-client';
import { CatalogApi, CatalogItem, PLAN_CHECK_RULES, PLAN_RECURRENCE_LABEL, PLAN_VERSION_STATUS_LABEL, PeriodApi, PlanApi, PlanItem, PlanItemInput, PlanRemovedItem, PlanVersion, PlanWorkspace } from '@vt/api-client/domains';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TagModule } from 'primeng/tag';
import { catchError, debounceTime, filter, of, switchMap } from 'rxjs';
import { TASK_CAN } from '../task-permissions';
import { formatDate } from '../task-ui';
import { CatalogPickDialog } from './plan/catalog-pick.dialog';
import { PlanItemEditor } from './plan/plan-item-editor';

const AUTOSAVE_MS = 3000;
type Busy = 'save' | 'submit' | 'copy' | 'adjust' | null;

@Component({
  selector: 'vt-plan-page',
  imports: [
    DatePipe,
    FormsModule,
    ButtonModule,
    CheckboxModule,
    DialogModule,
    InputTextModule,
    SelectModule,
    TagModule,
    CatalogPickDialog,
    PlanItemEditor,
  ],
  template: `
    @if (!can('SCR-0402.xem')) {
      <p class="msg warn">Đồng chí không có quyền xem kế hoạch quý của đơn vị.</p>
    } @else if (plans.error()) {
      <p class="msg err" role="alert">{{ plansError() }}</p>
    } @else if (plans.value()?.length === 0) {
      <section class="empty" aria-labelledby="plan-empty-title">
        <h1 id="plan-empty-title">Kế hoạch công tác quý</h1>
        <p>Đơn vị chưa có kế hoạch công tác quý.</p>
        @if (can('SCR-0402.lap')) {
          @if (openPeriods().length) {
            <div class="create">
              <label for="plan-period">Kỳ kế hoạch</label>
              <p-select
                inputId="plan-period"
                [options]="openPeriods()"
                optionLabel="name"
                optionValue="id"
                [ngModel]="newPeriod()"
                (ngModelChange)="newPeriod.set($event)"
              />
              <p-button
                label="Lập kế hoạch"
                icon="pi pi-plus"
                [loading]="creating()"
                [disabled]="!newPeriod() || creating()"
                (onClick)="createPlan()"
              />
            </div>
          } @else if (periodsRes.isLoading()) {
            <p class="muted">Đang tải kỳ kế hoạch…</p>
          } @else {
            <p class="muted">Hiện chưa có kỳ kế hoạch quý đang mở để lập kế hoạch.</p>
          }
          @if (createError(); as e) {
            <p class="msg err" role="alert">{{ e }}</p>
          }
        } @else {
          <p class="muted">Khi đơn vị lập kế hoạch, nội dung kế hoạch sẽ hiện tại đây.</p>
        }
      </section>
    } @else if (ws.error()) {
      <p class="msg err" role="alert">{{ loadError() }}</p>
    } @else if (ws.value(); as w) {
      @let v = w.version;
      <header class="top">
        <div>
          <h1>Kế hoạch công tác {{ v.period.label }}</h1>
          <p class="muted sub">
            {{ v.unitName }} · Bảng 1, Phụ lục 1 · phiên bản {{ v.versionNo }} ·
            <p-tag [value]="statusLabel(v.status)" [severity]="statusSeverity(v.status)" />
          </p>
          <div class="pickers">
            <label for="pl-period" class="sr-only">Kỳ</label>
            <p-select
              inputId="pl-period"
              [options]="planOptions()"
              optionLabel="label"
              optionValue="value"
              [ngModel]="planId()"
              (ngModelChange)="choosePlan($event)"
              size="small"
            />
            @if (w.versions.length > 1) {
              <label for="pl-version" class="sr-only">Phiên bản</label>
              <p-select
                inputId="pl-version"
                [options]="versionOptions()"
                optionLabel="label"
                optionValue="value"
                [ngModel]="v.versionNo"
                (ngModelChange)="chooseVersion($event)"
                size="small"
              />
            }
          </div>
        </div>
        <div class="actions">
          @if (editable() && can('SCR-0402.lap')) {
            @if (v.versionNo === 1 && !items().length && !simple() && w.plan.previousPeriod) {
              <p-button
                [label]="'Sao chép từ ' + w.plan.previousPeriod.label"
                severity="contrast"
                [outlined]="true"
                [loading]="busy() === 'copy'"
                [disabled]="!!busy()"
                (onClick)="copyPrevious()"
              />
            }
            @if (krMissing() > 0 && !simple()) {
              <p-button
                label="Gợi ý từ OKR"
                severity="contrast"
                [outlined]="true"
                (onClick)="openSuggestions()"
              />
            }
            <p-button
              label="Lưu nháp"
              severity="contrast"
              [outlined]="true"
              [loading]="busy() === 'save'"
              [disabled]="!!busy() || !dirty()"
              (onClick)="save()"
            />
          }
          @if (editable() && can('SCR-0402.gui-duyet')) {
            <p-button
              label="Gửi duyệt"
              [loading]="busy() === 'submit'"
              [disabled]="!!busy() || blocking().length > 0 || !items().length || checking()"
              [attr.aria-describedby]="blocking().length ? 'chk-title' : null"
              (onClick)="submit()"
            />
          }
          @if ((v.status === 'CHO_DUYET' || v.versionNo > 1) && can('SCR-0403.so-sanh')) {
            <p-button
              [label]="v.status === 'CHO_DUYET' ? 'Xem xét và duyệt' : 'So sánh phiên bản'"
              severity="contrast"
              [outlined]="true"
              (onClick)="openReview(v.versionNo)"
            />
          }
          @if (canAdjust() && can('SCR-0402.dieu-chinh')) {
            <p-button
              label="Tạo điều chỉnh"
              [outlined]="true"
              [disabled]="adjust().used >= adjust().max || !!busy()"
              (onClick)="openAdjust()"
            />
          }
        </div>
      </header>

      @if (editable()) {
        @if ((v.workingDaysToDeadline ?? 0) < 0) {
          <p class="msg err banner" role="note">
            <span class="num" aria-hidden="true">1</span>
            Đã quá hạn phê duyệt kế hoạch {{ date(deadline()) }}. Việc này đã được ghi nhận và báo
            người đứng đầu.
          </p>
        } @else {
          <p class="msg warn banner" role="note">
            <span class="num" aria-hidden="true">1</span>
            <span
              >Hạn phê duyệt kế hoạch là <strong>{{ date(deadline()) }}</strong
              >. Còn {{ v.workingDaysToDeadline }} ngày làm việc.
              @if (!simple()) {
                (Hạn là hết tuần thứ 2 của tháng đầu quý theo bộ quy định đánh giá của đơn vị. Nếu
                quá hạn, hệ thống báo người đứng đầu và ghi nhận là dấu hiệu bất thường.)
              }
            </span>
          </p>
        }
        @if (v.status === 'TRA_LAI' && v.reviewComment) {
          <p class="msg err">Kế hoạch bị trả lại: {{ v.reviewComment }}</p>
        }
      } @else if (v.status === 'CHO_DUYET') {
        <p class="msg info">
          Đã gửi duyệt lúc {{ summary()?.submittedAt | date: 'dd/MM/yyyy HH:mm' }}; nội dung đã
          khoá. Mã kiểm tra toàn vẹn: <code class="hash">{{ v.contentHash }}</code>
        </p>
      } @else if (v.status === 'DA_DUYET') {
        <p class="msg ok">
          Phiên bản {{ v.versionNo }} đã duyệt và ký số lúc
          {{ summary()?.approvedAt | date: 'dd/MM/yyyy HH:mm' }}; phiên bản đã duyệt không sửa được.
          @if (isCurrent() && adjust().used >= adjust().max) {
            Kế hoạch đã điều chỉnh {{ adjust().used }} lần trong quý, đạt giới hạn.
          }
        </p>
      }
      @if (v.versionNo > 1) {
        <p class="muted">
          Phiên bản điều chỉnh lần {{ v.versionNo - 1 }}/{{ adjust().max }}.
          @if (v.changeReason) {
            Lý do điều chỉnh: <strong>{{ v.changeReason }}</strong
            >.
          }
          Lý do ghi thêm ở từng dòng thêm, sửa, bỏ.
        </p>
        @if (removed().length) {
          <div class="removed">
            <p id="rm-title" class="small">Dòng bỏ so với phiên bản đã duyệt</p>
            <ul aria-labelledby="rm-title">
              @for (r of removed(); track r.originItemId) {
                <li>
                  {{ removedTitle(r.originItemId) }}: {{ r.changeReason }}
                  @if (editable() && can('SCR-0402.lap')) {
                    <p-button
                      label="Khôi phục"
                      [text]="true"
                      size="small"
                      [ariaLabel]="'Khôi phục dòng ' + removedTitle(r.originItemId)"
                      (onClick)="restoreItem(r.originItemId)"
                    />
                  }
                </li>
              }
            </ul>
          </div>
        }
      }

      <div aria-live="polite">
        @if (notice()) {
          <p class="msg ok" role="status">{{ notice() }}</p>
        }
        @if (error()) {
          <p class="msg err" role="alert">
            {{ error() }}
            @if (conflict()) {
              <p-button label="Tải lại" [link]="true" (onClick)="reload()" />
            }
          </p>
        }
      </div>

      <div class="layout">
        <section class="box" aria-labelledby="tbl-title">
          <h2 id="tbl-title" class="h">
            <span class="num" aria-hidden="true">2</span> Danh sách công việc
            <span class="muted small"
              >· mỗi việc đúng một đơn vị chủ trì, có thể nhiều đơn vị phối hợp</span
            >
            @if (editable()) {
              <span class="save-state small" aria-live="polite">{{ saveState() }}</span>
            }
          </h2>
          <div class="scroll" tabindex="0" role="region" aria-label="Bảng công việc trong kế hoạch">
            <table class="tbl">
              <thead>
                <tr>
                  <th scope="col">STT</th>
                  <th scope="col">Nội dung công việc</th>
                  <th scope="col">Nhóm</th>
                  @if (!simple()) {
                    <th scope="col">KR</th>
                  }
                  <th scope="col">Chủ trì</th>
                  @if (!simple()) {
                    <th scope="col">Phối hợp</th>
                  }
                  <th scope="col" class="cat">Danh mục · sản phẩm, số lượng</th>
                  <th scope="col">Hạn</th>
                  <th scope="col" class="r">Điểm</th>
                  @if (editable() && can('SCR-0402.lap')) {
                    <th scope="col"><span class="sr-only">Sửa</span></th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (i of items(); track i.id) {
                  @let errs = lineErrors().get(i.lineNo) ?? [];
                  <tr [class.bad]="blockedLines().has(i.lineNo)" [class.sel]="i.id === editingId()">
                    <td>{{ i.lineNo }}</td>
                    <td class="title">
                      <strong>{{ i.title || '(chưa có nội dung)' }}</strong>
                      @for (m of errs; track $index) {
                        <div class="row-err">{{ m }}</div>
                      }
                    </td>
                    <td class="nw"><p-tag [value]="group(i.taskGroup)" severity="secondary" /></td>
                    @if (!simple()) {
                      <td class="nw">{{ krCode(i.keyResultId) }}</td>
                    }
                    <td class="nw">{{ i.leadAssignee.name || '—' }}</td>
                    @if (!simple()) {
                      <td>{{ coNames(i) || '—' }}</td>
                    }
                    <td class="cat">
                      @if (i.productName || i.catalogItemCode) {
                        {{ i.catalogItemCode ? i.catalogItemCode + ' · ' : ''
                        }}{{ i.quantity || '' }}
                        {{ i.unit || i.productName }}
                      } @else {
                        <span class="missing">chưa chọn</span>
                      }
                    </td>
                    <td class="nw">{{ due(i) }}</td>
                    <td class="r">{{ i.standardScore ?? '' }}</td>
                    @if (editable() && can('SCR-0402.lap')) {
                      <td>
                        <p-button
                          icon="pi pi-pencil"
                          [text]="true"
                          severity="contrast"
                          [ariaLabel]="'Sửa dòng ' + i.lineNo"
                          (onClick)="edit(i)"
                        />
                      </td>
                    }
                  </tr>
                } @empty {
                  <tr>
                    <td [attr.colspan]="12" class="muted">Kế hoạch chưa có công việc nào.</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>

          @if (editing(); as e) {
            <vt-plan-item-editor
              [item]="e"
              [units]="units()"
              [keyResults]="keyResultOptions()"
              [periodStart]="v.period.startDate"
              [periodEnd]="v.period.endDate"
              [simple]="simple()"
              [adjusting]="v.versionNo > 1"
              [messages]="lineErrors().get(e.lineNo) ?? []"
              (changed)="update($event)"
              (remove)="removeItem(e)"
              (done)="editingId.set(null)"
            />
          }

          @if (editable() && can('SCR-0402.lap')) {
            <div class="add">
              <p-button
                label="Thêm từ danh mục"
                icon="pi pi-plus"
                severity="contrast"
                [outlined]="true"
                (onClick)="pickOpen.set(true)"
              />
              <p-button
                label="Thêm việc ngoài danh mục"
                icon="pi pi-plus"
                severity="contrast"
                [outlined]="true"
                (onClick)="addBlank()"
              />
              <span class="muted small"
                >Điểm của việc lấy từ danh mục (Bảng 3, Phụ lục 1); việc ngoài danh mục cần cấp
                duyệt chấm điểm.</span
              >
            </div>
          }
        </section>

        <aside>
          <section class="box" aria-labelledby="w-title">
            <h2 id="w-title" class="h">
              <span class="num" aria-hidden="true">3</span> Tỷ trọng nhóm
            </h2>
            <table class="weights">
              <tbody>
                @for (g of groups; track g) {
                  <tr>
                    <th scope="row">{{ group(g) }}</th>
                    <td>
                      <strong>{{ v.groupWeights[g] }}</strong> điểm tối đa
                      <div class="small" [class.over]="total(g) > v.groupWeights[g]">
                        đang lập {{ total(g) }}
                      </div>
                    </td>
                  </tr>
                }
                <tr>
                  <th scope="row">Tổng</th>
                  <td>
                    <strong>{{ v.groupWeights.THUONG_XUYEN + v.groupWeights.THEN_CHOT }}</strong>
                  </td>
                </tr>
              </tbody>
            </table>
            @if (v.groupWeights.policyPackName) {
              <p class="small">
                Bộ quy định đánh giá <strong>{{ v.groupWeights.policyPackName }}</strong>
                @if (v.groupWeights.policyPackVersion) {
                  · phiên bản {{ v.groupWeights.policyPackVersion }}
                }
                @if (v.groupWeights.flow) {
                  · đánh giá theo quy định của
                  {{ v.groupWeights.flow === 'DANG' ? 'Đảng' : 'Nhà nước' }}
                }
              </p>
            }
            @if (!simple()) {
              <p class="muted small">
                Tỷ trọng do cấp có thẩm quyền quyết định trong bộ quy định đánh giá của đơn vị.
              </p>
            }
          </section>

          @if (editable()) {
            <section class="box" aria-labelledby="chk-title" aria-live="polite">
              <h2 id="chk-title" class="h">
                <span class="num" aria-hidden="true">4</span> Kiểm tra kế hoạch
              </h2>
              @if (checks()) {
                <ul class="checks">
                  @for (x of checkRows(); track $index) {
                    <li>
                      <span class="chip" [class]="'chip ' + tone(x)">{{ tag(x) }}</span>
                      <span>{{ x.message }}</span>
                    </li>
                  }
                </ul>
                @if (blocking().length) {
                  <p class="small err-text">Gửi duyệt bị chặn cho tới khi sửa hết lỗi.</p>
                }
              } @else {
                <p class="muted">Đang kiểm tra…</p>
              }
            </section>
          }
        </aside>
      </div>

      <vt-catalog-pick-dialog
        [(visible)]="pickOpen"
        [items]="catalogItems()"
        [used]="usedCatalog()"
        (added)="addFromCatalog($event)"
      />

      <p-dialog
        header="Gợi ý từ OKR"
        [modal]="true"
        [(visible)]="suggestOpen"
        closeAriaLabel="Đóng"
        [style]="{ width: 'min(600px, 95vw)' }"
      >
        <!-- BR-04-07 -->
        <p class="muted">Một dòng đề xuất cho mỗi KR đã duyệt chưa có nhiệm vụ.</p>
        @for (s of suggestions(); track $index) {
          <div class="sg">
            <p-checkbox
              [inputId]="'sg-' + $index"
              [binary]="true"
              [ngModel]="suggestPicked().has($index)"
              (ngModelChange)="toggleSuggestion($index)"
            />
            <label [for]="'sg-' + $index">{{ s.title }}</label>
          </div>
        } @empty {
          <p class="muted">Đang tải gợi ý…</p>
        }
        <ng-template #footer>
          <p-button
            label="Đóng"
            severity="contrast"
            [outlined]="true"
            (onClick)="suggestOpen.set(false)"
          />
          <p-button
            [label]="'Thêm ' + suggestPicked().size + ' dòng'"
            [disabled]="!suggestPicked().size"
            (onClick)="addSuggestions()"
          />
        </ng-template>
      </p-dialog>

      <p-dialog
        header="Tạo điều chỉnh kế hoạch"
        [modal]="true"
        [(visible)]="adjustOpen"
        closeAriaLabel="Đóng"
        [style]="{ width: 'min(520px, 95vw)' }"
      >
        <p>
          Tạo phiên bản {{ w.versions.length + 1 }} từ phiên bản đã duyệt. Lần điều chỉnh
          {{ adjust().used + 1 }}/{{ adjust().max }} trong quý; hiệu lực từ ngày duyệt, không hồi
          tố. Mỗi dòng thêm, sửa hoặc bỏ cần ghi lý do thay đổi.
        </p>
        <div class="field">
          <label for="adj-reason">Lý do điều chỉnh (bắt buộc)</label>
          <textarea
            pInputText
            id="adj-reason"
            rows="3"
            maxlength="1000"
            [ngModel]="adjustReason()"
            (ngModelChange)="adjustReason.set($event)"
          ></textarea>
        </div>
        @if (dialogError(); as e) {
          <p class="msg err" role="alert">{{ e }}</p>
        }
        <ng-template #footer>
          <p-button
            label="Đóng"
            severity="contrast"
            [outlined]="true"
            (onClick)="adjustOpen.set(false)"
          />
          <p-button
            label="Tạo điều chỉnh"
            [loading]="busy() === 'adjust'"
            [disabled]="!adjustReason().trim()"
            (onClick)="createAdjustment()"
          />
        </ng-template>
      </p-dialog>

      <p-dialog
        header="Bỏ dòng khỏi kế hoạch điều chỉnh"
        [modal]="true"
        [visible]="!!removing()"
        (visibleChange)="$event || removing.set(null)"
        closeAriaLabel="Đóng"
        [style]="{ width: 'min(520px, 95vw)' }"
      >
        <p>
          <!-- BR-04-10 -->
          Dòng "{{ removing()?.title }}" có trong phiên bản đã duyệt; bỏ dòng cần ghi lý do.
        </p>
        <div class="field">
          <label for="rm-reason">Lý do bỏ dòng (bắt buộc)</label>
          <textarea
            pInputText
            id="rm-reason"
            rows="3"
            maxlength="500"
            [ngModel]="removeReason()"
            (ngModelChange)="removeReason.set($event)"
          ></textarea>
        </div>
        <ng-template #footer>
          <p-button
            label="Đóng"
            severity="contrast"
            [outlined]="true"
            (onClick)="removing.set(null)"
          />
          <p-button
            label="Bỏ dòng"
            [disabled]="!removeReason().trim()"
            (onClick)="confirmRemove()"
          />
        </ng-template>
      </p-dialog>
    } @else {
      <p class="muted">Đang tải kế hoạch…</p>
    }
  `,
  styles: `
    :host {
      display: block;
      padding: 16px 24px;
      --p-togglebutton-color: #374151;
    }
    .top {
      display: flex;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
      align-items: flex-start;
      margin-bottom: 12px;
    }
    h1 {
      font-size: 1.5rem;
      margin: 0 0 4px;
    }
    .sub {
      margin: 0 0 8px;
      display: flex;
      align-items: center;
      gap: 6px;
      flex-wrap: wrap;
    }
    .pickers {
      display: flex;
      gap: 8px;
    }
    .actions {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }
    .layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 300px;
      gap: 16px;
      align-items: start;
    }
    .box {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      padding: 12px 16px;
      background: var(--p-content-background, #fff);
      margin-bottom: 12px;
    }
    .h {
      font-size: 1.05rem;
      margin: 0 0 10px;
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
    }
    .num {
      display: inline-grid;
      place-items: center;
      flex: none;
      width: 24px;
      height: 24px;
      border-radius: 50%;
      background: #111827;
      color: #fff;
      font-size: 0.8rem;
    }
    .save-state {
      margin-left: auto;
      color: #4b5563;
      font-weight: 400;
    }
    .scroll {
      overflow-x: auto;
    }
    .tbl {
      width: 100%;
      border-collapse: collapse;
    }
    .tbl th {
      text-align: left;
      color: #4b5563;
      font-weight: 500;
      background: #f9fafb;
    }
    .tbl th,
    .tbl td {
      padding: 10px 8px;
      border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
      vertical-align: middle;
    }
    .tbl .title {
      min-width: 220px;
    }
    .tbl .nw {
      white-space: nowrap;
    }
    .tbl .cat {
      min-width: 130px;
    }
    .banner {
      flex-wrap: nowrap;
      align-items: flex-start;
    }
    .tbl tr.bad {
      background: #fef2f2;
    }
    .tbl tr.sel {
      outline: 2px solid #6d28d9;
      outline-offset: -2px;
    }
    .row-err {
      margin-top: 6px;
      padding: 6px 8px;
      border-radius: 4px;
      background: #fffbeb;
      color: #78350f;
      font-size: 0.9em;
    }
    .missing {
      color: #b91c1c;
    }
    .r {
      text-align: right;
    }
    .add {
      display: flex;
      gap: 8px;
      align-items: center;
      flex-wrap: wrap;
      margin-top: 12px;
    }
    .weights {
      width: 100%;
      border-collapse: collapse;
    }
    .weights th {
      text-align: left;
      font-weight: 400;
      color: #4b5563;
      padding: 4px 0;
      vertical-align: top;
    }
    .over {
      color: #b91c1c;
      font-weight: 600;
    }
    .checks {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .checks li {
      display: flex;
      gap: 8px;
      align-items: flex-start;
      padding: 4px 0;
      line-height: 1.5;
    }
    .chip {
      flex: none;
      font-size: 0.8rem;
      font-weight: 600;
      padding: 1px 8px;
      border-radius: 4px;
    }
    .chip.ok {
      background: #dcfce7;
      color: #14532d;
    }
    .chip.bad {
      background: #fee2e2;
      color: #7f1d1d;
    }
    .chip.warn {
      background: #fef3c7;
      color: #78350f;
    }
    .err-text {
      color: #b91c1c;
    }
    .empty {
      max-width: 640px;
    }
    .create {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
      margin: 12px 0;
    }
    .msg {
      margin: 0 0 10px;
      padding: 10px 14px;
      border-radius: 6px;
      border-left: 4px solid;
      display: flex;
      gap: 10px;
      align-items: center;
      flex-wrap: wrap;
    }
    .msg.ok {
      color: #14532d;
      background: #f0fdf4;
      border-color: #15803d;
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
    .msg.info {
      color: #1e3a8a;
      background: #eff6ff;
      border-color: #1d4ed8;
    }
    .hash {
      word-break: break-all;
      font-size: 0.85em;
    }
    .sg {
      display: flex;
      gap: 8px;
      align-items: center;
      padding: 6px 0;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.9em;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
    @media (max-width: 1100px) {
      .layout {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class PlanPage {
  private readonly api = inject(PlanApi);
  private readonly periodApi = inject(PeriodApi);
  private readonly orgApi = inject(OrgApi);
  private readonly okrApi = inject(OkrApi);
  private readonly catalogApi = inject(CatalogApi);
  private readonly theme = inject(ThemeService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly can = inject(TASK_CAN);

  private readonly query = toSignal(this.route.queryParamMap);
  protected readonly groups: TaskGroup[] = ['THUONG_XUYEN', 'THEN_CHOT'];

  protected readonly plans = rxResource({
    params: () => (this.can('SCR-0402.xem') ? true : undefined),
    stream: () => this.api.list(),
  });
  protected readonly plansError = computed(() =>
    this.plans.error()
      ? toProblem(this.plans.error()).title ||
        'Chưa tải được danh sách kế hoạch. Vui lòng thử lại sau.'
      : '',
  );

  // Đơn vị chưa có kế hoạch: người lập chọn kỳ quý đang mở rồi "Lập kế hoạch" (`createPlan`, SCR-0402).
  protected readonly periodsRes = rxResource({
    params: () => (this.plans.value()?.length === 0 && this.can('SCR-0402.lap') ? true : undefined),
    stream: () => this.periodApi.list({ kind: 'QUY', status: 'MO' }),
  });
  protected readonly openPeriods = computed(() => this.periodsRes.value() ?? []);
  protected readonly newPeriod = linkedSignal<string | undefined>(() => this.openPeriods()[0]?.id);
  protected readonly creating = signal(false);
  protected readonly createError = signal('');
  /** Kỳ đang xem: `?plan=`, mặc định kế hoạch còn đang soạn, nếu không có thì kỳ mới nhất. */
  protected readonly planId = computed(() => {
    const list = this.plans.value() ?? [];
    const q = this.query()?.get('plan');
    if (q && list.some((p) => p.id === q)) return q;
    const open = (p: (typeof list)[number]) =>
      p.currentVersion.status === 'NHAP' || p.currentVersion.status === 'TRA_LAI';
    return (list.find(open) ?? list[0])?.id;
  });
  private readonly plan = computed(() => this.plans.value()?.find((p) => p.id === this.planId()));
  private readonly versionNo = computed(() => Number(this.query()?.get('v')) || undefined);

  protected readonly ws = rxResource({
    params: () => {
      const plan = this.plan();
      return plan ? { plan, v: this.versionNo() } : undefined;
    },
    stream: ({ params }) => this.api.workspace(params.plan, params.v),
  });

  private readonly unitsRes = rxResource({ stream: () => this.orgApi.units('unit') });
  private readonly krRes = rxResource({
    params: () => this.ws.value()?.version.period.id,
    stream: ({ params }) => this.okrApi.keyResults(params),
  });
  private readonly catalogRes = rxResource({
    params: () => (this.editable() ? true : undefined),
    stream: () => this.catalogApi.items({ limit: 200 }),
  });

  /** Bản làm việc của các dòng: sửa tại chỗ, lưu nháp tự động. */
  protected readonly items = linkedSignal<PlanItem[]>(() => this.ws.value()?.version.items ?? []);
  /** Phiên bản đã lưu gần nhất (rowVersion cho If-Match). */
  private readonly saved = linkedSignal<PlanVersion | undefined>(() => this.ws.value()?.version);
  /** Dòng gốc bị bỏ trong phiên bản điều chỉnh, mỗi dòng kèm lý do (BR-04-10). */
  protected readonly removed = linkedSignal<PlanRemovedItem[]>(
    () => this.ws.value()?.version.removedItems ?? [],
  );
  /** Dòng của phiên bản hiện hành, để hiện tên và khôi phục dòng đã bỏ khi điều chỉnh. */
  private readonly baseRes = rxResource({
    params: () => {
      const w = this.ws.value();
      const no = w?.plan.effectiveVersionNo;
      return w && no && w.version.versionNo > no ? { id: w.plan.id, no } : undefined;
    },
    stream: ({ params }) => this.api.version(params.id, params.no),
  });
  private readonly baseItems = computed(() => this.baseRes.value()?.items ?? []);
  protected readonly removing = signal<PlanItem | null>(null);
  protected readonly removeReason = signal('');
  protected readonly adjustReason = signal('');
  protected readonly dirty = linkedSignal<boolean>(() => {
    this.ws.value();
    return false;
  });
  protected readonly editingId = signal<string | null>(null);
  protected readonly editing = computed(
    () => this.items().find((i) => i.id === this.editingId()) ?? null,
  );
  protected readonly editable = computed(() => {
    const s = this.ws.value()?.version.status;
    return s === 'NHAP' || s === 'TRA_LAI';
  });
  protected readonly canAdjust = computed(() => {
    const w = this.ws.value();
    if (!w) return false;
    const last = w.versions[w.versions.length - 1];
    return this.isCurrent() && last.versionNo === w.version.versionNo && last.status === 'DA_DUYET';
  });
  /** Phiên bản đang xem là phiên bản `DA_DUYET` hiệu lực. */
  protected readonly isCurrent = computed(() => {
    const w = this.ws.value();
    return !!w && w.plan.effectiveVersionNo === w.version.versionNo;
  });
  protected readonly summary = computed(() => {
    const w = this.ws.value();
    return w?.versions.find((x) => x.versionNo === w.version.versionNo);
  });
  protected readonly adjust = computed(() => ({
    used: this.ws.value()?.plan.adjustmentsUsed ?? 0,
    max: this.ws.value()?.plan.adjustmentsMax ?? 0,
  }));
  protected readonly deadline = computed(() => {
    const w = this.ws.value();
    return w?.version.approvalDeadline ?? w?.plan.approvalDeadline ?? '';
  });

  protected readonly busy = signal<Busy>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly conflict = signal(false);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly savedAt = signal<Date | null>(null);
  protected readonly pickOpen = signal(false);
  protected readonly suggestOpen = signal(false);
  protected readonly adjustOpen = signal(false);
  protected readonly suggestions = signal<PlanItemInput[]>([]);
  protected readonly suggestPicked = signal<Set<number>>(new Set());

  /** Chế độ người cao tuổi: ẩn cột KR, phối hợp, sao chép, gợi ý OKR và phần giải thích (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  /** Kiểm tra tức thì của máy chủ, gọi lại khi dòng thay đổi (debounce). */
  protected readonly checking = signal(false);
  protected readonly check = toSignal(
    toObservable(
      computed(() => ({
        items: this.items(),
        removed: this.removed(),
        w: this.ws.value(),
        on: this.editable(),
      })),
    ).pipe(
      filter((x) => !!x.w && x.on),
      debounceTime(300),
      switchMap(({ items, removed, w }) => {
        this.checking.set(true);
        return this.api
          .check(w!.plan.id, w!.version.versionNo, items, removed)
          .pipe(catchError(() => of(undefined)));
      }),
    ),
  );
  /** Vi phạm còn lại: lần kiểm tra gần nhất, hoặc `checks` của phiên bản vừa tải. */
  protected readonly checks = computed(() => this.check() ?? this.ws.value()?.version.checks);
  protected readonly blocking = computed(() =>
    (this.checks() ?? []).filter((i) => i.level === 'CHAN'),
  );
  protected readonly blockedLines = computed(
    () =>
      new Set(
        this.blocking()
          .map((x) => x.lineNo)
          .filter((n): n is number => !!n),
      ),
  );
  protected readonly lineErrors = computed(() => {
    const m = new Map<number, string[]>();
    for (const x of this.checks() ?? []) {
      if (!x.lineNo) continue;
      m.set(x.lineNo, [...(m.get(x.lineNo) ?? []), x.message]);
    }
    return m;
  });
  /** Danh sách vùng 4: mỗi luật hiện vi phạm của nó, không có vi phạm thì hiện "Đạt". */
  protected readonly checkRows = computed(() => {
    const list = this.checks() ?? [];
    const adjusting = (this.ws.value()?.version.versionNo ?? 1) > 1;
    const rows: { level: string; passed: boolean; message: string }[] = [];
    for (const r of PLAN_CHECK_RULES) {
      if (r.rule === 'BR-04-10' && !adjusting) continue;
      const hits = list.filter((x) => x.rule === r.rule);
      if (hits.length) rows.push(...hits.map((x) => ({ ...x, passed: false })));
      else rows.push({ level: r.level, passed: true, message: r.passed });
    }
    const known = new Set(PLAN_CHECK_RULES.map((r) => r.rule));
    rows.push(...list.filter((x) => !known.has(x.rule)).map((x) => ({ ...x, passed: false })));
    return rows;
  });
  protected readonly krMissing = computed(
    () => (this.checks() ?? []).filter((x) => x.rule === 'BR-04-07').length,
  );

  protected readonly units = computed(() =>
    (this.unitsRes.value()?.items ?? []).map((u) => ({ value: u.id, label: u.name })),
  );
  protected readonly keyResultOptions = computed(() =>
    (this.krRes.value()?.items ?? []).map((k) => ({
      value: k.id,
      label: `${k.code} · ${k.title}`,
    })),
  );
  protected readonly catalogItems = computed<CatalogItem[]>(
    () => this.catalogRes.value()?.items ?? [],
  );
  protected readonly usedCatalog = computed(() =>
    this.items()
      .map((i) => i.catalogItemId)
      .filter((x): x is string => !!x),
  );
  protected readonly planOptions = computed(() =>
    (this.plans.value() ?? []).map((p) => ({
      value: p.id,
      label: `${p.period.label} · phiên bản ${p.currentVersion.versionNo} · ${PLAN_VERSION_STATUS_LABEL[p.currentVersion.status]}`,
    })),
  );
  protected readonly versionOptions = computed(() =>
    (this.ws.value()?.versions ?? []).map((v) => ({
      value: v.versionNo,
      label: `Phiên bản ${v.versionNo} · ${PLAN_VERSION_STATUS_LABEL[v.status]}${v.versionNo === this.ws.value()?.plan.effectiveVersionNo ? ' (hiện hành)' : ''}`,
    })),
  );
  protected readonly saveState = computed(() => {
    if (this.busy() === 'save') return 'Đang lưu nháp…';
    if (this.dirty()) return 'Có thay đổi chưa lưu';
    const t = this.savedAt();
    return t
      ? `Đã lưu nháp lúc ${t.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })}`
      : '';
  });
  protected readonly loadError = computed(() => {
    const p = toProblem(this.ws.error());
    return p.status === 404 ? 'Không tìm thấy kế hoạch.' : p.title;
  });

  constructor() {
    // Lưu nháp tự động sau khi ngừng sửa.
    let timer: ReturnType<typeof setTimeout> | undefined;
    effect(() => {
      const d = this.dirty();
      clearTimeout(timer);
      if (d && this.editable() && this.can('SCR-0402.lap'))
        timer = setTimeout(() => this.busy() || this.save(true), AUTOSAVE_MS);
    });
    inject(DestroyRef).onDestroy(() => clearTimeout(timer));
    // Nhận mục danh mục chuyển từ SCR-0401 ("Đưa vào kế hoạch"), `?catalogItems=id1,id2`.
    effect(() => {
      const ids = this.query()?.get('catalogItems')?.split(',').filter(Boolean) ?? [];
      const cat = this.catalogItems();
      if (!ids.length || !cat.length || !this.editable() || !this.can('SCR-0402.lap')) return;
      const chosen = cat.filter((c) => ids.includes(c.id) && !this.usedCatalog().includes(c.id));
      this.addFromCatalog(chosen);
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { catalogItems: null },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
    });
    effect(() => this.check() !== undefined && this.checking.set(false));
  }

  protected statusLabel(s: keyof typeof PLAN_VERSION_STATUS_LABEL) {
    return PLAN_VERSION_STATUS_LABEL[s];
  }
  protected statusSeverity(s: string) {
    return s === 'DA_DUYET'
      ? 'success'
      : s === 'TRA_LAI'
        ? 'danger'
        : s === 'CHO_DUYET'
          ? 'info'
          : 'secondary';
  }
  protected group(g: TaskGroup) {
    return TASK_GROUP_LABEL[g];
  }
  protected date(d: string) {
    return formatDate(d, true);
  }
  protected due(i: PlanItem) {
    if (i.recurrence) return PLAN_RECURRENCE_LABEL[i.recurrence];
    return formatDate(i.dueDate);
  }
  /** Mở SCR-0403 cho phiên bản đang xem (so sánh với phiên bản đã duyệt trước đó). */
  protected openReview(v: number) {
    void this.router.navigate(['/plans', this.planId(), 'review'], { queryParams: { v } });
  }

  protected coNames(i: PlanItem) {
    return (i.coAssignees ?? []).map((c) => c.name).join(', ');
  }
  protected krCode(id?: string) {
    return id
      ? (this.keyResultOptions()
          .find((k) => k.value === id)
          ?.label.split(' · ')[0] ?? '')
      : '—';
  }
  protected total(g: TaskGroup) {
    return this.items()
      .filter((i) => i.taskGroup === g)
      .reduce((s, i) => s + (i.standardScore ?? 0), 0);
  }
  protected tag(x: { passed: boolean; level: string }) {
    return x.passed ? 'Đạt' : x.level === 'CHAN' ? 'Lỗi' : 'Cảnh báo';
  }
  protected tone(x: { passed: boolean; level: string }) {
    return x.passed ? 'ok' : x.level === 'CHAN' ? 'bad' : 'warn';
  }

  protected createPlan() {
    const periodId = this.newPeriod();
    if (!periodId || this.creating()) return;
    this.creating.set(true);
    this.createError.set('');
    this.api.create(periodId).subscribe({
      next: (plan) => {
        this.creating.set(false);
        this.plans.reload();
        this.choosePlan(plan.id);
      },
      error: (e) => {
        this.creating.set(false);
        this.createError.set(toProblem(e).title || 'Chưa lập được kế hoạch. Vui lòng thử lại sau.');
      },
    });
  }

  protected choosePlan(id: string) {
    void this.router.navigate([], { relativeTo: this.route, queryParams: { plan: id, v: null } });
  }
  protected chooseVersion(v: number) {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { plan: this.planId(), v },
    });
  }
  protected reload() {
    this.error.set(null);
    this.conflict.set(false);
    this.ws.reload();
  }

  private setItems(next: PlanItem[]) {
    this.items.set(next.map((i, n) => ({ ...i, lineNo: n + 1 })));
    this.dirty.set(true);
  }
  protected edit(i: PlanItem) {
    this.editingId.set(i.id ?? null);
  }
  protected update(i: PlanItem) {
    this.setItems(this.items().map((x) => (x.id === i.id ? i : x)));
  }
  protected removeItem(i: PlanItem) {
    if (i.originItemId && (this.ws.value()?.version.versionNo ?? 1) > 1) {
      this.removeReason.set('');
      this.removing.set(i);
      return;
    }
    this.drop(i);
  }
  protected confirmRemove() {
    const i = this.removing();
    if (!i?.originItemId) return;
    this.removed.set([
      ...this.removed().filter((r) => r.originItemId !== i.originItemId),
      { originItemId: i.originItemId, changeReason: this.removeReason().trim() },
    ]);
    this.removing.set(null);
    this.drop(i);
  }
  /** Khôi phục dòng gốc đã bỏ, lấy lại từ phiên bản hiện hành (đã duyệt). */
  protected restoreItem(originItemId: string) {
    const line = this.baseItems().find((x) => x.id === originItemId);
    this.removed.set(this.removed().filter((r) => r.originItemId !== originItemId));
    if (line)
      this.setItems([...this.items(), { ...line, id: `tmp-${crypto.randomUUID()}`, originItemId }]);
    else this.dirty.set(true);
  }
  protected removedTitle(originItemId: string) {
    const line = this.baseItems().find((x) => x.id === originItemId);
    return line?.title ?? 'Dòng đã duyệt';
  }
  private drop(i: PlanItem) {
    this.setItems(this.items().filter((x) => x.id !== i.id));
    this.editingId.set(null);
    this.notice.set(`Đã bỏ dòng "${i.title || i.lineNo}". Bấm Lưu nháp hoặc chờ lưu tự động.`);
  }
  /** Dòng mới: chủ trì là đơn vị lập, hạn mặc định cuối kỳ vì hợp đồng bắt buộc `dueDate`. */
  private blank(patch: Partial<PlanItem> = {}): PlanItem {
    const w = this.ws.value();
    return {
      id: `tmp-${crypto.randomUUID()}`,
      lineNo: this.items().length + 1,
      title: '',
      taskGroup: 'THUONG_XUYEN',
      leadAssignee: { kind: 'UNIT', id: w?.plan.unitId ?? '', name: w?.plan.unitName ?? '' },
      coAssignees: [],
      productName: '',
      quantity: 0,
      unit: '',
      dueDate: w?.version.period.endDate ?? '',
      standardScore: null,
      ...patch,
    };
  }
  protected addBlank() {
    const i = this.blank();
    this.setItems([...this.items(), i]);
    this.editingId.set(i.id ?? null);
  }
  protected addFromCatalog(list: CatalogItem[]) {
    if (!list.length) return;
    const added = list.map((c) =>
      this.blank({
        title: c.name,
        taskGroup: c.defaultGroup,
        catalogItemId: c.id,
        catalogItemCode: c.code,
        productName: c.productName,
        quantity: 1,
        unit: c.unit,
        standardScore: c.baseScore,
      }),
    );
    this.setItems([...this.items(), ...added]);
    this.notice.set(`Đã thêm ${added.length} công việc từ danh mục; chọn hạn cho từng dòng.`);
  }
  protected openSuggestions() {
    const w = this.ws.value()!;
    this.suggestions.set([]);
    this.suggestPicked.set(new Set());
    this.suggestOpen.set(true);
    this.api.suggestions(w.plan.id).subscribe({
      next: (s) => {
        this.suggestions.set(s);
        this.suggestPicked.set(new Set(s.map((_, n) => n)));
      },
      error: (e) => this.fail(e),
    });
  }
  protected toggleSuggestion(n: number) {
    this.suggestPicked.update((s) => {
      const next = new Set(s);
      if (next.has(n)) next.delete(n);
      else next.add(n);
      return next;
    });
  }
  protected addSuggestions() {
    const picked = this.suggestions().filter((_, n) => this.suggestPicked().has(n));
    this.setItems([
      ...this.items(),
      ...picked.map((s) => this.blank({ ...s, id: `tmp-${crypto.randomUUID()}` })),
    ]);
    this.suggestOpen.set(false);
    this.notice.set(
      `Đã thêm ${picked.length} dòng gợi ý từ OKR; bổ sung sản phẩm, đơn vị tính và hạn.`,
    );
  }

  private fail(e: unknown, dialog = false) {
    const p = toProblem(e);
    this.busy.set(null);
    if (dialog) {
      this.dialogError.set(p.errors?.[0]?.message ?? p.title);
      return;
    }
    this.conflict.set(p.status === 409 || p.status === 412);
    this.error.set(p.errors?.length ? p.errors.map((x) => x.message).join(' ') : p.title);
  }
  private begin(kind: Busy, quiet = false) {
    this.busy.set(kind);
    this.error.set(null);
    this.conflict.set(false);
    if (!quiet) this.notice.set(null);
  }

  /** Thay dòng tạm (`tmp-…`) bằng dòng máy chủ trả, giữ dòng đang sửa. */
  private applySaved(v: PlanVersion) {
    const editingNo = this.editing()?.lineNo;
    this.saved.set(v);
    this.items.set(v.items);
    this.dirty.set(false);
    this.savedAt.set(new Date());
    if (editingNo) this.editingId.set(v.items.find((i) => i.lineNo === editingNo)?.id ?? null);
  }

  protected save(auto = false) {
    const w = this.ws.value();
    const base = this.saved();
    if (!w || !base) return;
    this.begin('save', auto);
    this.api.save(w.plan.id, base, this.items(), this.removed()).subscribe({
      next: (v) => {
        this.busy.set(null);
        this.applySaved(v);
      },
      error: (e) => this.fail(e),
    });
  }

  protected copyPrevious() {
    const w = this.ws.value();
    const base = this.saved();
    if (!w || !base) return;
    this.begin('copy');
    this.api.copyFromPrevious(w.plan.id, base).subscribe({
      next: (v) => {
        this.busy.set(null);
        this.applySaved(v);
        this.notice.set(
          `Đã sao chép ${v.items.length} dòng từ ${w.plan.previousPeriod?.label ?? 'kỳ trước'}, hạn đã dịch theo kỳ.`,
        );
      },
      error: (e) => this.fail(e),
    });
  }

  protected submit() {
    const w = this.ws.value();
    const base = this.saved();
    if (!w || !base) return;
    this.begin('submit');
    const run = (v: PlanVersion) =>
      this.api.submit(w.plan.id, v).subscribe({
        next: (sent) => {
          this.busy.set(null);
          this.editingId.set(null);
          this.ws.set({
            ...w,
            version: sent,
            versions: w.versions.map((x) =>
              x.versionNo === sent.versionNo
                ? { ...x, status: sent.status, submittedAt: new Date().toISOString() }
                : x,
            ),
          } as PlanWorkspace);
          this.notice.set('Đã gửi duyệt kế hoạch; lãnh đạo được thông báo để phê duyệt.');
          this.plans.reload();
        },
        error: (e) => this.fail(e),
      });
    if (this.dirty()) {
      this.api
        .save(w.plan.id, base, this.items(), this.removed())
        .pipe(
          switchMap((v) => {
            this.applySaved(v);
            return of(v);
          }),
        )
        .subscribe({ next: run, error: (e) => this.fail(e) });
    } else run(base);
  }

  protected openAdjust() {
    this.dialogError.set(null);
    this.adjustReason.set('');
    this.adjustOpen.set(true);
  }
  protected createAdjustment() {
    const w = this.ws.value();
    if (!w) return;
    this.begin('adjust');
    this.api.createAdjustment(w.plan.id, this.adjustReason().trim()).subscribe({
      next: (v) => {
        this.busy.set(null);
        this.adjustOpen.set(false);
        this.plans.reload();
        this.chooseVersion(v.versionNo);
        this.notice.set(
          `Đã tạo phiên bản ${v.versionNo} để điều chỉnh. Ghi lý do ở từng dòng thêm, sửa, bỏ.`,
        );
      },
      error: (e) => this.fail(e, true),
    });
  }
}
