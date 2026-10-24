/*
 * SCR-0501 "Giao việc" (QT-05 bước 3–7, TL-02 Hình 8.3) — đối chiếu đặc tả:
 * Trường: 1 mã (tự sinh khi lưu); 2 nguồn (KE_HOACH, VAN_BAN, DOT_XUAT ~ "Phát sinh"); 3 dòng kế hoạch (BR-05-04);
 *   4 mục danh mục; 5 tên ≤ 500; 6 nhóm; 7 KR; 8 ưu tiên; 9 chủ trì đúng một (BR-05-01); 10 phối hợp (người hoặc đơn vị,
 *   không trùng chủ trì); 11 hạn trong kỳ (BR-05-03) kèm số ngày làm việc; 12 người nghiệm thu, mặc định người giao (BR-05-02);
 *   13 mốc kiểm soát; 14 sản phẩm, số lượng, đơn vị tính; 15 minh chứng yêu cầu ≤ 500; 16 tiêu chuẩn chất lượng ≤ 1.000;
 *   17 giao đồng loạt. Căn cứ của nhiệm vụ ngoài kế hoạch (BR-05-09).
 * Chức năng: Lưu nháp (POST /tasks hoặc PUT /tasks/{id} + If-Match); Giao việc (POST /tasks/{id}/assign, EVT-04, EVT-21);
 *   Giao đồng loạt (POST /tasks/bulk-assign, 202); Xem nhiệm vụ gần giống; Huỷ (DELETE /tasks/{id}).
 * Kiểm tra tức thì: POST /tasks/assign-check (MOD-02) trả danh sách kiểm tra, nhiệm vụ gần giống, khối lượng người chủ trì.
 * Thông báo: MSG-05-01, MSG-05-02, MSG-05-03 (BR-05-06), MSG-05-04 (BR-05-11), MSG-05-05.
 */
import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { Location } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  AssigneeRef,
  CatalogApi,
  OkrApi,
  OrgApi,
  OrgPerson,
  PlanApi,
  PlanItemRef,
  SimilarTask,
  TASK_GROUP_LABEL,
  TASK_PRIORITY_LABEL,
  TASK_SOURCE_LABEL,
  TASK_STATUS_LABEL,
  TaskApi,
  TaskDraft,
  TaskGroup,
  TaskPriority,
  TaskSource,
  toProblem,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { DrawerModule } from 'primeng/drawer';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { MultiSelectModule } from 'primeng/multiselect';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TextareaModule } from 'primeng/textarea';
import { debounceTime, map, switchMap } from 'rxjs';
import { TASK_CAN } from '../task-permissions';
import { formatDate } from '../task-ui';
import { AssignSidePanel } from './assign/assign-side.panel';

const opts = <K extends string>(m: Record<K, string>) =>
  (Object.keys(m) as K[]).map((value) => ({ value, label: m[value] }));
const SOURCES: TaskSource[] = ['KE_HOACH', 'VAN_BAN', 'DOT_XUAT'];
/** Giá trị lựa chọn trỏ tới nhiệm vụ nháp đã sinh từ kế hoạch, phân biệt với mã dòng kế hoạch. */
const DRAFT_PREFIX = 'nhap:';
const MAX_MILESTONES = 10;

/** Nhóm cán bộ theo đơn vị cho ô chọn người (bản sao cây tổ chức `org_replica`). */
function groupByUnit(persons: OrgPerson[], exclude: string[] = []) {
  const groups = new Map<string, { label: string; items: { label: string; value: string }[] }>();
  for (const p of persons) {
    if (exclude.includes(p.id)) continue;
    const g = groups.get(p.unitId) ?? { label: p.unitName, items: [] };
    g.items.push({ label: `${p.name} · ${p.title}`, value: p.id });
    groups.set(p.unitId, g);
  }
  return [...groups.values()];
}

@Component({
  selector: 'vt-assign-task-page',
  imports: [
    FormsModule,
    RouterLink,
    ButtonModule,
    CheckboxModule,
    DrawerModule,
    InputTextModule,
    MessageModule,
    MultiSelectModule,
    SelectModule,
    SelectButtonModule,
    TextareaModule,
    AssignSidePanel,
  ],
  template: `
    <a routerLink="/tasks" class="back">← Việc của tôi</a>
    @if (can('SCR-0501.giao') || can('SCR-0601.duyet-de-nghi')) {
      <a routerLink="/tasks/approvals" class="back approvals">Chờ duyệt</a>
    }
    @if (loaded.error()) {
      <p-message severity="error" styleClass="msg">
        <span>{{ loadError() }}</span>
        <button type="button" class="link" (click)="loaded.reload()">Thử lại</button>
      </p-message>
    } @else if (done(); as d) {
      <section class="box done" role="status">
        <h1>{{ d.title }}</h1>
        <p>{{ d.text }}</p>
        <div class="actions">
          <p-button label="Giao nhiệm vụ khác" icon="pi pi-plus" (onClick)="startNew()" />
          <p-button
            label="Về Việc của tôi"
            severity="contrast"
            [outlined]="true"
            routerLink="/tasks"
          />
        </div>
      </section>
    } @else if (form(); as f) {
      <header class="head">
        <div>
          <h1>Giao nhiệm vụ</h1>
          <p class="muted">
            @if (f.code) {
              {{ f.code }} ·
            }
            {{ f.unitName }} · {{ periodLabel() }} · người giao {{ f.assignerName }}
            @if (f.status !== 'NHAP') {
              · {{ statusLabel(f) }}
            }
          </p>
        </div>
        <div class="actions">
          @if (can('SCR-0501.soan')) {
            <p-button
              label="Huỷ"
              severity="contrast"
              [outlined]="true"
              (onClick)="cancel()"
              [disabled]="busy()"
            />
            <p-button
              label="Lưu nháp"
              severity="contrast"
              [outlined]="true"
              (onClick)="saveDraft()"
              [loading]="busy() === 'save'"
              [disabled]="!!busy() || !f.title.trim()"
            />
          }
          @if (bulk()) {
            @if (can('SCR-0501.giao-dong-loat')) {
              <p-button
                [label]="'Giao đồng loạt ' + bulkUnits().length + ' đơn vị'"
                icon="pi pi-send"
                (onClick)="bulkAssign()"
                [loading]="busy() === 'assign'"
                [disabled]="!canBulk()"
              />
            }
          } @else if (can('SCR-0501.giao')) {
            <p-button
              label="Giao việc"
              icon="pi pi-send"
              (onClick)="assign()"
              [loading]="busy() === 'assign'"
              [disabled]="!canAssign()"
            />
          }
        </div>
      </header>

      @if (confirmCancel()) {
        <div class="confirm" role="alertdialog" aria-labelledby="cc-text">
          <span id="cc-text">Huỷ bản nháp {{ f.code }}? Nhiệm vụ nháp sẽ bị xoá.</span>
          <p-button
            label="Huỷ bản nháp"
            severity="danger"
            size="small"
            (onClick)="deleteDraft()"
            [loading]="busy() === 'delete'"
          />
          <p-button
            label="Giữ lại"
            severity="contrast"
            [outlined]="true"
            size="small"
            (onClick)="confirmCancel.set(false)"
          />
        </div>
      }
      @if (notice(); as n) {
        <p-message severity="success" [text]="n" styleClass="msg" role="status" />
      }
      @if (errors().length) {
        <div class="errbox" role="alert">
          <strong>Chưa thực hiện được:</strong>
          <ul>
            @for (e of errors(); track $index) {
              <li>{{ e }}</li>
            }
          </ul>
          @if (conflict()) {
            <button type="button" class="link" (click)="reload()">Tải lại</button>
          }
        </div>
      }

      <div class="cols">
        <div>
          <section class="box" aria-labelledby="s1">
            <h2 id="s1" class="h">
              <span class="num" aria-hidden="true">1</span> Nguồn và nội dung
            </h2>
            <span class="lbl" id="src-lbl">Nguồn nhiệm vụ <span class="req">*</span></span>
            <p-selectbutton
              [options]="sourceOptions"
              optionLabel="label"
              optionValue="value"
              [allowEmpty]="false"
              ariaLabelledBy="src-lbl"
              [ngModel]="f.source"
              (ngModelChange)="setSource($event)"
              styleClass="seg"
            />

            @switch (f.source) {
              @case ('KE_HOACH') {
                <div class="grid2">
                  <div>
                    <label for="as-plan">Dòng kế hoạch <span class="req">*</span></label>
                    <p-select
                      inputId="as-plan"
                      [options]="planOptions()"
                      optionLabel="label"
                      optionValue="value"
                      placeholder="Chọn dòng kế hoạch đã duyệt"
                      [filter]="true"
                      filterBy="label"
                      [ngModel]="f.planItemId"
                      (ngModelChange)="applyPlanItem($event)"
                      styleClass="full"
                      appendTo="body"
                      [loading]="plans.isLoading()"
                    />
                  </div>
                  <div>
                    <label for="as-cat">Danh mục công việc</label>
                    <p-select
                      inputId="as-cat"
                      [options]="catalogOptions()"
                      optionLabel="label"
                      optionValue="value"
                      placeholder="Chọn mục danh mục"
                      [filter]="true"
                      filterBy="label"
                      [showClear]="true"
                      [ngModel]="f.catalogItemId"
                      (ngModelChange)="applyCatalog($event)"
                      styleClass="full"
                      appendTo="body"
                    />
                  </div>
                </div>
              }
              @case ('VAN_BAN') {
                <div class="grid2">
                  <div>
                    <label for="as-docno">Số, ký hiệu văn bản <span class="req">*</span></label>
                    <input
                      id="as-docno"
                      pInputText
                      class="full"
                      maxlength="100"
                      [ngModel]="f.sourceDoc?.number ?? ''"
                      (ngModelChange)="setDoc('number', $event)"
                    />
                  </div>
                  <div>
                    <label for="as-doctitle">Trích yếu</label>
                    <input
                      id="as-doctitle"
                      pInputText
                      class="full"
                      maxlength="500"
                      [ngModel]="f.sourceDoc?.title ?? ''"
                      (ngModelChange)="setDoc('title', $event)"
                    />
                  </div>
                </div>
                <small class="muted"
                  >Văn bản đến từ vOffice, iOffice được xử lý ở màn hình Tạo nhiệm vụ từ văn bản chỉ
                  đạo.</small
                >
              }
              @default {
                <label for="as-basis">Căn cứ giao việc <span class="req">*</span></label>
                <input
                  id="as-basis"
                  pInputText
                  class="full"
                  maxlength="500"
                  placeholder="Ghi rõ văn bản hoặc ý kiến chỉ đạo làm căn cứ"
                  [ngModel]="f.basis ?? ''"
                  (ngModelChange)="set('basis', $event)"
                />
              }
            }
            @if (f.source !== 'KE_HOACH') {
              <label for="as-cat2">Danh mục công việc</label>
              <p-select
                inputId="as-cat2"
                [options]="catalogOptions()"
                optionLabel="label"
                optionValue="value"
                placeholder="Chọn mục danh mục (trừ việc ngoài danh mục)"
                [filter]="true"
                filterBy="label"
                [showClear]="true"
                [ngModel]="f.catalogItemId"
                (ngModelChange)="applyCatalog($event)"
                styleClass="full"
                appendTo="body"
              />
            }

            <label for="as-title">Tên nhiệm vụ <span class="req">*</span></label>
            <input
              id="as-title"
              pInputText
              class="full"
              maxlength="500"
              [ngModel]="f.title"
              (ngModelChange)="set('title', $event)"
            />
            <div class="grid3">
              <div>
                <label for="as-group">Nhóm <span class="req">*</span></label>
                <p-select
                  inputId="as-group"
                  [options]="groupOptions"
                  optionLabel="label"
                  optionValue="value"
                  [ngModel]="f.taskGroup"
                  (ngModelChange)="set('taskGroup', $event)"
                  styleClass="full"
                  appendTo="body"
                />
              </div>
              <div>
                <label for="as-kr">KR liên kết</label>
                <p-select
                  inputId="as-kr"
                  [options]="krOptions()"
                  optionLabel="label"
                  optionValue="value"
                  placeholder="Không liên kết"
                  [showClear]="true"
                  [ngModel]="f.keyResultId"
                  (ngModelChange)="set('keyResultId', $event ?? undefined)"
                  styleClass="full"
                  appendTo="body"
                />
              </div>
              <div>
                <label for="as-prio">Mức ưu tiên <span class="req">*</span></label>
                <p-select
                  inputId="as-prio"
                  [options]="priorityOptions"
                  optionLabel="label"
                  optionValue="value"
                  [ngModel]="f.priority"
                  (ngModelChange)="set('priority', $event)"
                  styleClass="full"
                  appendTo="body"
                />
              </div>
            </div>
          </section>

          <section class="box" aria-labelledby="s2">
            <h2 id="s2" class="h"><span class="num" aria-hidden="true">2</span> Phân công</h2>
            @if (!bulk()) {
              <div class="grid2">
                <div>
                  <label for="as-lead">Người chủ trì <span class="req">*</span></label>
                  <p-select
                    inputId="as-lead"
                    [options]="leadOptions()"
                    [group]="true"
                    optionLabel="label"
                    optionValue="value"
                    placeholder="Chọn người chủ trì"
                    [filter]="true"
                    filterBy="label"
                    [ngModel]="f.leadPersonId"
                    (ngModelChange)="set('leadPersonId', $event)"
                    styleClass="full"
                    appendTo="body"
                  />
                  @if (check.value()?.workload; as w) {
                    <small class="muted"
                      >Đang chủ trì {{ w.leadCount }} việc, phối hợp {{ w.coCount }} việc trong
                      quý</small
                    >
                  }
                </div>
                <div>
                  <label for="as-co">Phối hợp</label>
                  <p-multiselect
                    inputId="as-co"
                    [options]="coOptions()"
                    [group]="true"
                    optionLabel="label"
                    optionValue="value"
                    placeholder="Chọn người hoặc đơn vị"
                    display="chip"
                    [filter]="true"
                    filterBy="label"
                    [ngModel]="coKeys()"
                    (ngModelChange)="setCo($event)"
                    styleClass="full"
                    appendTo="body"
                  />
                </div>
              </div>
            }
            <div class="grid3">
              <div>
                <label for="as-due">Hạn hoàn thành <span class="req">*</span></label>
                <input
                  id="as-due"
                  pInputText
                  type="date"
                  class="full"
                  [min]="today"
                  [max]="periodEnd()"
                  [ngModel]="f.dueDate ?? ''"
                  (ngModelChange)="set('dueDate', $event || undefined)"
                />
                @if (check.value()?.workingDays; as n) {
                  <small class="muted">{{ n }} ngày làm việc (đã trừ ngày nghỉ, ngày lễ)</small>
                }
              </div>
              <div>
                <label for="as-acc">Người nghiệm thu <span class="req">*</span></label>
                <p-select
                  inputId="as-acc"
                  [options]="acceptorOptions()"
                  [group]="true"
                  optionLabel="label"
                  optionValue="value"
                  placeholder="Chọn người nghiệm thu"
                  [filter]="true"
                  filterBy="label"
                  [ngModel]="f.acceptorId"
                  (ngModelChange)="set('acceptorId', $event)"
                  styleClass="full"
                  appendTo="body"
                />
                <small class="muted"
                  >Khác người chủ trì và người phối hợp (Phụ lục 4, mục V.1)</small
                >
              </div>
              @if (!simple()) {
                <div>
                  <span class="lbl" id="ms-lbl">Mốc kiểm soát</span>
                  @for (m of f.milestones; track $index; let i = $index) {
                    <div class="ms" role="group" [attr.aria-label]="'Mốc ' + (i + 1)">
                      <input
                        pInputText
                        type="date"
                        [min]="today"
                        [max]="f.dueDate ?? periodEnd()"
                        [attr.aria-label]="'Ngày mốc ' + (i + 1)"
                        [ngModel]="m.date"
                        (ngModelChange)="setMilestone(i, 'date', $event)"
                      />
                      <input
                        pInputText
                        maxlength="200"
                        placeholder="Nội dung"
                        [attr.aria-label]="'Nội dung mốc ' + (i + 1)"
                        [ngModel]="m.content"
                        (ngModelChange)="setMilestone(i, 'content', $event)"
                      />
                      <p-button
                        icon="pi pi-times"
                        [text]="true"
                        severity="contrast"
                        [ariaLabel]="'Xoá mốc ' + (i + 1)"
                        (onClick)="removeMilestone(i)"
                      />
                    </div>
                  }
                  @if (f.milestones.length < maxMilestones) {
                    <p-button
                      label="Thêm mốc"
                      icon="pi pi-plus"
                      [text]="true"
                      size="small"
                      (onClick)="addMilestone()"
                    />
                  }
                </div>
              }
            </div>
            @if (!simple() && can('SCR-0501.giao-dong-loat') && !f.code) {
              <div class="bulk">
                <p-checkbox
                  inputId="as-bulk"
                  [binary]="true"
                  [ngModel]="bulk()"
                  (ngModelChange)="bulk.set($event)"
                />
                <label for="as-bulk" class="inline"
                  >Giao đồng loạt cho nhiều đơn vị (mỗi đơn vị một nhiệm vụ riêng, cùng nội
                  dung)</label
                >
              </div>
              @if (bulk()) {
                <label for="as-units">Đơn vị nhận <span class="req">*</span></label>
                <p-multiselect
                  inputId="as-units"
                  [options]="childUnitOptions()"
                  optionLabel="label"
                  optionValue="value"
                  placeholder="Chọn đơn vị cấp dưới"
                  display="chip"
                  [ngModel]="bulkUnits()"
                  (ngModelChange)="bulkUnits.set($event)"
                  styleClass="full"
                  appendTo="body"
                />
                <small class="muted"
                  >Mỗi đơn vị nhận là chủ trì nhiệm vụ của mình; người đứng đầu đơn vị phân công
                  tiếp.</small
                >
              }
            }
          </section>

          <section class="box" aria-labelledby="s3">
            <h2 id="s3" class="h">
              <span class="num" aria-hidden="true">3</span> Sản phẩm và tiêu chuẩn
            </h2>
            <div class="grid3">
              <div>
                <label for="as-prod">Sản phẩm <span class="req">*</span></label>
                <input
                  id="as-prod"
                  pInputText
                  class="full"
                  maxlength="500"
                  [ngModel]="f.productName"
                  (ngModelChange)="set('productName', $event)"
                />
              </div>
              <div class="qty">
                <div>
                  <label for="as-qty">Số lượng <span class="req">*</span></label>
                  <input
                    id="as-qty"
                    pInputText
                    type="number"
                    min="1"
                    class="full"
                    [ngModel]="f.quantity"
                    (ngModelChange)="set('quantity', toNum($event))"
                  />
                </div>
                <div>
                  <label for="as-unit">Đơn vị tính <span class="req">*</span></label>
                  <input
                    id="as-unit"
                    pInputText
                    class="full"
                    maxlength="50"
                    [ngModel]="f.unit"
                    (ngModelChange)="set('unit', $event)"
                  />
                </div>
              </div>
              <div>
                <label for="as-ev">Minh chứng yêu cầu <span class="req">*</span></label>
                <input
                  id="as-ev"
                  pInputText
                  class="full"
                  maxlength="500"
                  [ngModel]="f.evidenceRequired"
                  (ngModelChange)="set('evidenceRequired', $event)"
                />
              </div>
            </div>
            <label for="as-qc">Tiêu chuẩn chất lượng <span class="req">*</span></label>
            <textarea
              id="as-qc"
              pTextarea
              rows="2"
              maxlength="1000"
              class="full"
              [ngModel]="f.qualityCriteria"
              (ngModelChange)="set('qualityCriteria', $event)"
            ></textarea>
          </section>
        </div>

        <aside>
          <vt-assign-side-panel
            [result]="check.value()"
            [loading]="check.isLoading()"
            [simple]="simple()"
            [bulk]="bulk()"
            [canViewSimilar]="can('SCR-0501.trung')"
            [(similarReason)]="similarReason"
            (viewSimilar)="similarOpen.set($event)"
          />
        </aside>
      </div>

      <p-drawer
        [visible]="!!similarOpen()"
        (visibleChange)="$event || similarOpen.set(null)"
        position="right"
        header="Nhiệm vụ gần giống"
        [style]="{ width: 'min(420px, 96vw)' }"
        closeAriaLabel="Đóng"
      >
        @if (similarOpen(); as s) {
          <h3>{{ s.code }}</h3>
          <p>{{ s.title }}</p>
          <dl>
            <dt>Đơn vị</dt>
            <dd>{{ s.unitName }}</dd>
            <dt>Chủ trì</dt>
            <dd>{{ s.leadName }}</dd>
            <dt>Trạng thái</dt>
            <dd>{{ similarStatus(s) }}</dd>
            <dt>Hạn</dt>
            <dd>{{ date(s.dueDate) }}</dd>
            <dt>Tiến độ</dt>
            <dd>{{ s.progress }}%</dd>
            <dt>Độ tương đồng</dt>
            <dd>{{ s.similarity.toFixed(2).replace('.', ',') }}</dd>
          </dl>
          <p class="muted small">
            Nếu là cùng một công việc, không giao nhiệm vụ mới; muốn đổi hạn hoặc sản phẩm của nhiệm
            vụ cũ thì gửi đề nghị điều chỉnh nhiệm vụ đó.
          </p>
        }
      </p-drawer>
    } @else {
      <p class="muted" aria-busy="true">Đang tải…</p>
    }
  `,
  styles: `
    .back {
      text-decoration: none;
      color: color-mix(in srgb, var(--vt-color-primary) 82%, black);
    }
    .approvals {
      margin-left: 16px;
    }
    .head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      margin-top: 8px;
    }
    h1 {
      font-size: 1.4rem;
      margin: 4px 0;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.9em;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .cols {
      display: grid;
      grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr);
      gap: 16px;
      margin-top: 12px;
    }
    @media (max-width: 1000px) {
      .cols {
        grid-template-columns: 1fr;
      }
    }
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
      margin-bottom: 12px;
    }
    .done h1 {
      margin-top: 0;
    }
    .h {
      font-size: 1.05rem;
      margin: 0 0 4px;
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .num {
      display: inline-grid;
      place-items: center;
      width: 24px;
      height: 24px;
      border-radius: 50%;
      background: #111827;
      color: #fff;
      font-size: 0.8rem;
    }
    label,
    .lbl {
      display: block;
      margin: 12px 0 6px;
      font-weight: 500;
    }
    label.inline {
      display: inline;
      margin: 0 0 0 8px;
      font-weight: 400;
    }
    .req {
      color: #b91c1c;
    }
    .full,
    :host ::ng-deep .full {
      width: 100%;
    }
    .grid2 {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      gap: 0 12px;
    }
    .grid3 {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0 12px;
    }
    @media (max-width: 700px) {
      .grid2,
      .grid3 {
        grid-template-columns: 1fr;
      }
    }
    .qty {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      gap: 0 8px;
    }
    .ms {
      display: grid;
      grid-template-columns: 150px 1fr auto;
      gap: 6px;
      margin-bottom: 6px;
    }
    .ms input {
      min-width: 0;
    }
    .bulk {
      display: flex;
      align-items: center;
      margin-top: 16px;
    }
    :host ::ng-deep .seg {
      --p-togglebutton-color: #374151;
    }
    :host ::ng-deep .seg .p-togglebutton {
      min-height: var(--vt-min-touch, 40px);
    }
    :host ::ng-deep .msg {
      display: block;
      margin: 8px 0;
    }
    .errbox {
      margin: 8px 0;
      padding: 10px 14px;
      border: 1px solid #fca5a5;
      background: #fef2f2;
      color: #7f1d1d;
      border-radius: 6px;
    }
    .errbox ul {
      margin: 4px 0 0;
      padding-left: 18px;
    }
    .confirm {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
      margin: 8px 0;
      padding: 10px 14px;
      border: 1px solid #fcd34d;
      background: #fffbeb;
      color: #78350f;
      border-radius: 6px;
    }
    .link {
      background: none;
      border: 0;
      color: inherit;
      text-decoration: underline;
      cursor: pointer;
      margin-left: 8px;
      font: inherit;
    }
    dl {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: 6px 12px;
    }
    dt {
      color: #4b5563;
    }
    dd {
      margin: 0;
    }
  `,
})
export class AssignTaskPage {
  /** Mã nhiệm vụ `NHAP` khi mở từ kế hoạch hoặc bản nháp; trống khi soạn mới. */
  readonly id = input<string>();
  private readonly api = inject(TaskApi);
  private readonly org = inject(OrgApi);
  private readonly planApi = inject(PlanApi);
  private readonly okrApi = inject(OkrApi);
  private readonly catalogApi = inject(CatalogApi);
  private readonly theme = inject(ThemeService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
  protected readonly can = inject(TASK_CAN);

  protected readonly sourceOptions = SOURCES.map((value) => ({
    value,
    label: TASK_SOURCE_LABEL[value],
  }));
  protected readonly groupOptions = opts<TaskGroup>(TASK_GROUP_LABEL);
  protected readonly priorityOptions = opts<TaskPriority>(TASK_PRIORITY_LABEL);
  protected readonly today = new Date().toISOString().slice(0, 10);
  protected readonly maxMilestones = MAX_MILESTONES;

  private readonly reloadKey = signal(0);
  /** Bản nháp vừa lưu lần đầu trên trang soạn mới: "Tải lại" lấy bản này thay vì khung trống. */
  private readonly reloadId = signal<string | undefined>(undefined);
  protected readonly loaded = rxResource({
    params: () => ({ id: this.reloadId() ?? this.id(), k: this.reloadKey() }),
    stream: ({ params }) => (params.id ? this.api.draft(params.id) : this.api.newDraft()),
  });
  protected readonly loadError = computed(() => {
    const p = toProblem(this.loaded.error());
    return p.status === 404
      ? 'Không tìm thấy nhiệm vụ nháp.'
      : p.status === 403
        ? 'Đồng chí không có quyền giao việc ở đơn vị này.'
        : 'Không tải được nhiệm vụ.';
  });
  /** Bản đang soạn: đặt lại mỗi khi tải từ máy chủ. */
  protected readonly form = linkedSignal<TaskDraft | null>(() =>
    this.loaded.hasValue() ? structuredClone(this.loaded.value().draft) : null,
  );
  private readonly period = computed(() =>
    this.loaded.hasValue() ? this.loaded.value().period : null,
  );
  protected readonly periodLabel = computed(() => this.period()?.label ?? '');
  protected readonly periodEnd = computed(() => this.period()?.endDate ?? '');

  protected readonly persons = rxResource({
    stream: () => this.org.persons().pipe(map((p) => p.items)),
  });
  private readonly units = rxResource({
    stream: () => this.org.units('unit').pipe(map((p) => p.items)),
  });
  private readonly childUnits = rxResource({
    stream: () => this.org.units('children').pipe(map((p) => p.items)),
  });
  protected readonly plans = rxResource({
    params: () => this.period()?.id,
    stream: ({ params }) => this.planApi.approvedItems(params).pipe(map((p) => p.items)),
  });
  private readonly krs = rxResource({
    params: () => this.period()?.id,
    stream: ({ params }) => this.okrApi.keyResults(params).pipe(map((p) => p.items)),
  });
  private readonly catalog = rxResource({
    stream: () => this.catalogApi.items({ limit: 200 }).pipe(map((p) => p.items)),
  });

  /**
   * Nhiệm vụ nháp đã sinh từ kế hoạch đã duyệt (EVT-03, QT-05 bước 3): dòng kế hoạch đó không còn trong
   * `approvedItems` nên đưa nháp vào cùng danh sách chọn, chọn thì mở nháp để giao (chỉ khi soạn mới).
   */
  private readonly planDrafts = rxResource({
    params: () => (!this.id() && this.period() ? this.period()!.id : undefined),
    stream: ({ params }) =>
      this.api
        .unitTasks({ status: ['NHAP'], source: 'KE_HOACH', period: params, limit: 200 })
        .pipe(map((p) => p.items)),
  });
  protected readonly planOptions = computed(() => [
    ...(this.plans.value() ?? []).map((i) => ({
      value: i.id,
      label: `${i.planCode} · dòng ${i.lineNo} · ${i.title} (phiên bản ${i.version})`,
    })),
    ...(this.planDrafts.hasValue() ? this.planDrafts.value() : []).map((t) => ({
      value: DRAFT_PREFIX + t.id,
      label: `${t.code} · ${t.title} (nhiệm vụ nháp từ kế hoạch)`,
    })),
  ]);
  protected readonly catalogOptions = computed(() =>
    (this.catalog.value() ?? []).map((c) => ({
      value: c.id,
      label: `${c.code} · ${c.name} · mức khó ${c.difficulty} · ${c.baseScore} điểm`,
    })),
  );
  protected readonly krOptions = computed(() =>
    (this.krs.value() ?? []).map((k) => ({ value: k.id, label: `${k.code} · ${k.title}` })),
  );
  protected readonly leadOptions = computed(() => groupByUnit(this.persons.value() ?? []));
  protected readonly acceptorOptions = computed(() => groupByUnit(this.persons.value() ?? []));
  protected readonly coOptions = computed(() => {
    const lead = this.form()?.leadPersonId;
    const people = groupByUnit(this.persons.value() ?? [], lead ? [lead] : []).map((g) => ({
      label: `Cán bộ · ${g.label}`,
      items: g.items.map((i) => ({
        ...i,
        // ui-text-ok: khoá giá trị của ô chọn, không hiện lên màn hình
        value: `PERSON:${i.value}`,
        label: i.label.split(' · ')[0],
      })),
    }));
    const units = (this.units.value() ?? []).map((u) => ({
      // ui-text-ok: khoá giá trị của ô chọn, không hiện lên màn hình
      value: `UNIT:${u.id}`,
      label: u.name,
    }));
    return [{ label: 'Đơn vị', items: units }, ...people];
  });
  protected readonly coKeys = computed(() =>
    (this.form()?.coAssignees ?? []).map((c) => `${c.kind}:${c.id}`),
  );
  protected readonly childUnitOptions = computed(() =>
    (this.childUnits.value() ?? []).map((u) => ({ value: u.id, label: u.name })),
  );

  /** Chế độ người cao tuổi rút gọn: bỏ mốc kiểm soát, giao đồng loạt, giải thích và bảng khối lượng (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );
  protected readonly bulk = signal(false);
  protected readonly bulkUnits = signal<string[]>([]);
  protected readonly similarReason = signal('');
  protected readonly similarOpen = signal<SimilarTask | null>(null);
  protected readonly busy = signal<'save' | 'assign' | 'delete' | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly errors = signal<string[]>([]);
  protected readonly conflict = signal(false);
  protected readonly confirmCancel = signal(false);
  protected readonly done = signal<{ title: string; text: string } | null>(null);

  /** Kiểm tra tức thì của máy chủ, trễ 400 ms sau lần sửa cuối. */
  private readonly checkInput = toSignal(
    toObservable(computed(() => (this.form()?.status === 'NHAP' ? this.form() : null))).pipe(
      debounceTime(400),
    ),
    { initialValue: null },
  );
  /** Máy chủ bắt buộc tên nhiệm vụ (422 khi trống), nên chỉ kiểm khi đã có tên. */
  private readonly check$ = rxResource({
    params: () => {
      const f = this.checkInput();
      return f && f.title.trim() ? f : undefined;
    },
    stream: ({ params }) => this.api.assignCheck(params),
  });
  /** Đọc an toàn: `value()` của resource lỗi sẽ ném lỗi (lỗi console khi máy chủ trả 4xx). */
  protected readonly check = {
    value: computed(() => (this.check$.hasValue() ? this.check$.value() : undefined)),
    isLoading: this.check$.isLoading,
  };

  private readonly blocking = computed(() => {
    const r = this.check.value();
    if (!r || this.check.isLoading()) return true;
    return r.items.some(
      (i) => i.level === 'CHAN' && !i.passed && !(this.bulk() && i.rule === 'BR-05-01'),
    );
  });
  protected readonly canAssign = computed(
    () =>
      !this.busy() &&
      this.form()?.status === 'NHAP' &&
      !this.blocking() &&
      (!(this.check.value()?.similar.length ?? 0) || !!this.similarReason().trim()),
  );
  protected readonly canBulk = computed(
    () => !this.busy() && !this.blocking() && this.bulkUnits().length > 0,
  );

  protected set<K extends keyof TaskDraft>(k: K, v: TaskDraft[K]) {
    this.form.update((f) => (f ? { ...f, [k]: v } : f));
  }
  protected setSource(s: TaskSource) {
    this.form.update((f) =>
      f ? { ...f, source: s, planItemId: s === 'KE_HOACH' ? f.planItemId : undefined } : f,
    );
  }
  protected setDoc(k: 'number' | 'title', v: string) {
    this.form.update((f) =>
      f ? { ...f, sourceDoc: { number: '', title: '', ...f.sourceDoc, [k]: v } } : f,
    );
  }
  /** Chọn dòng kế hoạch: sao chép sản phẩm, số lượng, hạn, tiêu chuẩn từ dòng kế hoạch (QT-05 bước 1a). */
  protected applyPlanItem(id: string) {
    if (id?.startsWith(DRAFT_PREFIX)) {
      void this.router.navigate(['/tasks/assign', id.slice(DRAFT_PREFIX.length)]);
      return;
    }
    const i: PlanItemRef | undefined = this.plans.value()?.find((x) => x.id === id);
    this.form.update((f) =>
      f && i
        ? {
            ...f,
            planItemId: i.id,
            catalogItemId: i.catalogItemId,
            title: i.title,
            taskGroup: i.taskGroup,
            keyResultId: i.keyResultId,
            productName: i.productName,
            quantity: i.quantity,
            unit: i.unit,
            dueDate: i.dueDate,
            evidenceRequired: i.evidenceRequired,
            qualityCriteria: i.qualityCriteria,
          }
        : f,
    );
  }
  /** Chọn mục danh mục: điền sản phẩm, đơn vị tính, tiêu chuẩn còn trống theo danh mục. */
  protected applyCatalog(id: string | null) {
    const c = this.catalog.value()?.find((x) => x.id === id);
    this.form.update((f) =>
      !f
        ? f
        : !c
          ? { ...f, catalogItemId: undefined }
          : {
              ...f,
              catalogItemId: c.id,
              title: f.title || c.name,
              taskGroup: c.defaultGroup,
              productName: f.productName || c.productName,
              unit: f.unit || c.unit,
              qualityCriteria: f.qualityCriteria || c.qualityCriteria,
            },
    );
  }
  protected setCo(keys: string[]) {
    const persons = this.persons.value() ?? [];
    const units = this.units.value() ?? [];
    const refs: AssigneeRef[] = keys.map((k) => {
      const [kind, id] = k.split(':') as ['PERSON' | 'UNIT', string];
      const name =
        kind === 'PERSON'
          ? persons.find((p) => p.id === id)?.name
          : units.find((u) => u.id === id)?.name;
      return { kind, id, name: name ?? id };
    });
    this.set('coAssignees', refs);
  }
  protected setMilestone(i: number, k: 'date' | 'content', v: string) {
    this.form.update((f) =>
      f ? { ...f, milestones: f.milestones.map((m, j) => (j === i ? { ...m, [k]: v } : m)) } : f,
    );
  }
  protected addMilestone() {
    this.form.update((f) =>
      f ? { ...f, milestones: [...f.milestones, { date: '', content: '' }] } : f,
    );
  }
  protected removeMilestone(i: number) {
    this.form.update((f) => (f ? { ...f, milestones: f.milestones.filter((_, j) => j !== i) } : f));
  }
  protected toNum(v: unknown): number | null {
    const n = Number(v);
    return v === '' || v === null || !Number.isFinite(n) ? null : n;
  }
  protected statusLabel(f: TaskDraft) {
    return TASK_STATUS_LABEL[f.status];
  }
  protected similarStatus(s: SimilarTask) {
    return TASK_STATUS_LABEL[s.status];
  }
  protected date(d: string) {
    return formatDate(d, true);
  }

  /** Mốc chưa đủ ngày và nội dung không gửi lên. */
  private payload(): TaskDraft {
    const f = this.form()!;
    return { ...f, milestones: f.milestones.filter((m) => m.date && m.content.trim()) };
  }

  private persist() {
    const f = this.payload();
    return f.id ? this.api.saveDraft(f) : this.api.createDraft(f);
  }

  /** Sau lần lưu đầu, đổi địa chỉ sang `/tasks/assign/{id}` mà không tải lại trang. */
  private afterSave(saved: TaskDraft) {
    const first = !this.form()?.id;
    this.form.update((f) =>
      f
        ? {
            ...f,
            id: saved.id,
            code: saved.code,
            rowVersion: saved.rowVersion,
            status: saved.status,
          }
        : f,
    );
    if (first && saved.id) {
      const url = this.router.serializeUrl(
        this.router.createUrlTree([saved.id], { relativeTo: this.route }),
      );
      this.location.replaceState(url);
    }
  }

  private fail(e: unknown) {
    const p = toProblem(e);
    this.busy.set(null);
    this.notice.set(null);
    this.conflict.set(p.status === 409);
    this.errors.set(p.errors?.length ? p.errors.map((x) => x.message) : [p.title]);
  }

  private begin(kind: 'save' | 'assign' | 'delete') {
    this.busy.set(kind);
    this.errors.set([]);
    this.conflict.set(false);
    this.notice.set(null);
  }

  protected saveDraft(): void {
    this.begin('save');
    this.persist().subscribe({
      next: (saved) => {
        this.afterSave(saved);
        this.busy.set(null);
        this.notice.set(`Đã lưu nháp ${saved.code}.`);
      },
      error: (e) => this.fail(e),
    });
  }

  protected assign(): void {
    this.begin('assign');
    this.persist()
      .pipe(
        switchMap((saved) => {
          this.afterSave(saved);
          return this.api.assign(saved, {
            similarReason: this.similarReason().trim() || undefined,
          });
        }),
      )
      .subscribe({
        next: (r) => {
          this.busy.set(null);
          this.done.set({
            title: 'Đã giao nhiệm vụ',
            text: `Đã giao nhiệm vụ ${r.code} cho đồng chí ${r.leadName}.`,
          });
        },
        error: (e) => this.fail(e),
      });
  }

  protected bulkAssign(): void {
    this.begin('assign');
    const n = this.bulkUnits().length;
    this.api.bulkAssign({ draft: this.payload(), unitIds: this.bulkUnits() }).subscribe({
      next: () => {
        this.busy.set(null);
        this.done.set({
          title: 'Đã gửi giao đồng loạt',
          text: `Đã gửi yêu cầu giao cho ${n} đơn vị; hệ thống đang tạo nhiệm vụ cho từng đơn vị và sẽ thông báo khi xong.`,
        });
      },
      error: (e) => this.fail(e),
    });
  }

  protected cancel(): void {
    if (this.form()?.id && this.form()?.status === 'NHAP') this.confirmCancel.set(true);
    else void this.router.navigateByUrl('/tasks');
  }

  protected deleteDraft(): void {
    this.begin('delete');
    this.api.deleteDraft(this.form()!).subscribe({
      next: () => {
        this.busy.set(null);
        void this.router.navigateByUrl('/tasks');
      },
      error: (e) => {
        this.confirmCancel.set(false);
        this.fail(e);
      },
    });
  }

  protected reload(): void {
    this.reloadId.set(this.form()?.id);
    this.errors.set([]);
    this.conflict.set(false);
    this.reloadKey.update((k) => k + 1);
  }

  protected startNew(): void {
    this.done.set(null);
    this.bulk.set(false);
    this.bulkUnits.set([]);
    this.similarReason.set('');
    if (this.id()) {
      void this.router.navigate(['/tasks/assign']);
      return;
    }
    this.location.replaceState('/tasks/assign');
    this.reloadId.set(undefined);
    this.reloadKey.update((k) => k + 1);
  }
}
