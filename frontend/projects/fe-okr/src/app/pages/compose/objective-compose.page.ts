/*
 * SCR-0202 "Tạo, sửa mục tiêu và KR" (QT-02 bước 2–6; TL-02 Hình 7.4) — đối chiếu đặc tả:
 * Trường: 1 căn cứ (nhiều lựa chọn, `okr.basis`, BR-02-02); 2 mục tiêu cấp trên (bắt buộc trừ cấp cao nhất, cùng kỳ);
 *   3 tên mục tiêu ≤ 200; 4 đơn vị sở hữu (chỉ đọc); 5 kỳ (chưa khoá); 6 lý do vượt 3 mục tiêu ≤ 500 (BR-02-01);
 *   7 mã KR `KR {o}.{n}` tự sinh; 8 nội dung KR ≤ 300 (BR-02-05, BR-02-06); 9 đơn vị đo; 10 giá trị đầu (mặc định 0);
 *   11 chỉ tiêu (khác giá trị đầu); 12 trọng số % (chia đều, tổng 100%, BR-02-07); 13 hạn (mặc định cuối kỳ);
 *   14 người xác nhận (mặc định người đứng đầu đơn vị, khác chủ sở hữu KR); 15 tần suất check-in (mặc định Tuần);
 *   16 nguồn nội dung ẩn `HUMAN`/`AI_ACCEPTED`/`AI_EDITED` (BR-02-11).
 * Vùng: 1 cảnh báo vượt khuyến nghị; 2 mục tiêu; 3 bảng KR soạn trực tiếp; 4 trợ lý AI viền tím "Chỉ đọc · không ghi";
 *   5 kiểm tra trước khi trình, phân rã ba tầng.
 * Hành động: Lưu nháp (luôn, tự động mỗi 30 giây; POST /api/v1/okr/objectives khi tạo, PUT .../{id} với If-Match);
 *   Thêm KR (dưới 5 KR); Trợ lý AI soát KR (tenant bật AI, POST /api/v1/ai/okr/review, không gửi dữ liệu C3);
 *   Dùng gợi ý vào ô (viền tím, chỉ ghi khi người soạn lưu); Trình phê duyệt (không còn lỗi chặn;
 *   POST .../{id}/submit, Idempotency-Key, máy chủ kiểm lại toàn bộ quy tắc, chuyển `CHO_DUYET`).
 * Quy tắc: BR-02-01..07, BR-02-10 (đã trình hoặc đã duyệt thì không sửa tại chỗ), BR-02-11; CEL từ Policy Pack.
 * Thông báo: MSG-02-03 (cấp trên chưa duyệt), MSG-02-04 (vượt 3 mục tiêu), MSG-02-05 (KPI mơ hồ),
 *   MSG-02-06 (KR giống nhiệm vụ), MSG-02-07 (tổng trọng số), MSG-02-08 (đã trình).
 * Quyền (wiki/15a): SCR-0202.soan-don-vi (AC-05), SCR-0202.soan-ca-nhan (AC-06), SCR-0202.ai-soat, SCR-0202.trinh.
 */
import { Location } from '@angular/common';
import { Component, DestroyRef, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  AiKrSuggestion,
  CHECKIN_FREQUENCY_LABEL,
  CheckinFrequency,
  ConflictField,
  ConflictNoticeComponent,
  OBJECTIVE_STATUS_LABEL,
  ObjectiveDetail,
  ObjectiveDraft,
  OkrApi,
  OkrComposeContext,
  OkrSubmitResult,
  ProblemPresenter,
} from '@vt/api-client';
import { permittedOn } from '@vt/auth';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { MultiSelectModule } from 'primeng/multiselect';
import { SelectModule } from 'primeng/select';
import { Observable, combineLatest, interval, map, of, switchMap } from 'rxjs';
import { OKR_CAN } from '../../okr-permissions';
import { AiReviewPanel } from './ai-review.panel';
import { ComposeForm, ComposeKr, checkOkr, evenWeights, totalWeightPct } from '../../okr-rules';

const MAX_KR = 5;
const AUTOSAVE_MS = 30_000;
const FREQUENCIES = Object.entries(CHECKIN_FREQUENCY_LABEL).map(([value, label]) => ({
  value: value as CheckinFrequency,
  label,
}));

interface Loaded {
  ctx: OkrComposeContext;
  detail: ObjectiveDetail | null;
}

/** Thông tin máy chủ cấp cho bản nháp đã lưu. */
interface SavedMeta {
  id: string;
  code: string;
  rowVersion: number;
  draftNo: number;
  status: ObjectiveDetail['status'];
  updatedAt: string;
  authorName: string;
}

let seq = 0;
const newKey = () => `k${++seq}`;

@Component({
  selector: 'vt-objective-compose-page',
  imports: [
    FormsModule,
    RouterLink,
    ButtonModule,
    MessageModule,
    MultiSelectModule,
    SelectModule,
    ConflictNoticeComponent,
    AiReviewPanel,
  ],
  template: `
    @if (load.error()) {
      <p-message severity="error" styleClass="msg">
        <span>{{ loadError() }}</span>
        <a [routerLink]="treeLink" class="linkbtn">Về cây mục tiêu</a>
      </p-message>
    } @else if (!ctx() || !form()) {
      <p class="muted" role="status">Đang tải dữ liệu soạn mục tiêu…</p>
    } @else {
      @let c = ctx()!;
      @let f = form()!;
      @let r = check()!;
      <header class="head">
        <div>
          <h1>{{ heading() }}</h1>
          <p class="muted small" aria-live="polite">
            {{ c.unit.name }}
            @if (meta(); as m) {
              · bản nháp {{ m.draftNo }} · {{ saveText() }} · người soạn {{ m.authorName }}
            } @else {
              · chưa lưu · người soạn {{ c.me.name }}
            }
          </p>
        </div>
        @if (editable()) {
          <div class="actions">
            @if (showAi()) {
              <p-button
                label="Trợ lý AI soát KR"
                icon="pi pi-sparkles"
                severity="secondary"
                [loading]="aiLoading()"
                (onClick)="runAi()"
              />
            }
            <p-button
              label="Lưu nháp"
              severity="secondary"
              [loading]="saving() && !submitting()"
              [disabled]="saving()"
              (onClick)="saveDraft()"
            />
            @if (canSubmit()) {
              <p-button
                label="Trình phê duyệt"
                styleClass="primary"
                [disabled]="r.errors.length > 0 || saving()"
                [loading]="submitting()"
                [ariaLabel]="
                  r.errors.length
                    ? 'Trình phê duyệt, chưa trình được: còn ' + r.errors.length + ' lỗi cần sửa'
                    : 'Trình phê duyệt'
                "
                (onClick)="submit()"
              />
            }
          </div>
        }
      </header>

      @if (submitted(); as s) {
        <p-message severity="success" styleClass="msg">
          <span role="status">
            Đã trình phê duyệt. Người duyệt: {{ s.reviewerName }}, hạn {{ fmtDate(s.dueDate) }}.
          </span>
          <a [routerLink]="treeLink" [queryParams]="{ period: f.periodId }" class="linkbtn">
            Về cây mục tiêu
          </a>
        </p-message>
      }
      @if (readonlyReason(); as why) {
        <p-message severity="warn" styleClass="msg">{{ why }}</p-message>
      }
      @if (actionError(); as e) {
        <p-message severity="error" styleClass="msg">
          <div role="alert">
            <strong>{{ e.message }}</strong>
            @if (e.details.length) {
              <ul>
                @for (d of e.details; track $index) {
                  <li>{{ d }}</li>
                }
              </ul>
            }
          </div>
        </p-message>
      }
      @if (conflict(); as cf) {
        <vt-conflict-notice
          class="msg"
          [mine]="cf.mine"
          [theirs]="cf.theirs"
          [fields]="conflictFields"
          message="Mục tiêu vừa được người khác cập nhật trong lúc đồng chí đang soạn."
          (reload)="reloadFromServer()"
          (overwrite)="overwrite()"
        />
      }
      @if (overLimit() && editable()) {
        <div class="banner" role="note">
          <span>
            Kỳ {{ c.period.label }} đã có <strong>{{ c.existingCount }} mục tiêu</strong>. Phụ lục 1
            khuyến nghị từ 1 đến {{ c.policy.recommendedMax }} mục tiêu mỗi kỳ; vui lòng ghi lý do
            nếu vẫn thêm.
          </span>
        </div>
      }

      <div class="cols">
        <div>
          <fieldset class="box" [disabled]="!editable()">
            <legend class="sec">Mục tiêu (O)</legend>
            <div class="grid2">
              <div class="field">
                <label for="f-basis">Căn cứ <span class="req" aria-hidden="true">*</span></label>
                <p-multiselect
                  inputId="f-basis"
                  [options]="basisOptions()"
                  optionLabel="label"
                  optionValue="id"
                  display="chip"
                  placeholder="Chọn căn cứ"
                  [ngModel]="f.basisIds"
                  (ngModelChange)="patch({ basisIds: $event ?? [] })"
                  [disabled]="!editable()"
                  [ariaLabel]="'Căn cứ, bắt buộc'"
                  appendTo="body"
                  styleClass="w100"
                />
                <small class="muted">Có thể chọn nhiều căn cứ trong thư viện căn cứ.</small>
              </div>
              @if (!c.isTopLevel) {
                <div class="field">
                  <label for="f-parent">
                    Mục tiêu cấp trên <span class="req" aria-hidden="true">*</span>
                  </label>
                  <p-select
                    inputId="f-parent"
                    [options]="parentOptions()"
                    optionLabel="label"
                    optionValue="id"
                    placeholder="Chọn mục tiêu cấp trên"
                    [ngModel]="f.parentObjectiveId"
                    (ngModelChange)="patch({ parentObjectiveId: $event })"
                    [disabled]="!editable()"
                    appendTo="body"
                    styleClass="w100"
                  />
                </div>
              }
            </div>
            <div class="field">
              <label for="f-title"
                >Tên mục tiêu <span class="req" aria-hidden="true">*</span></label
              >
              <input
                id="f-title"
                class="inp"
                maxlength="200"
                required
                aria-describedby="f-title-hint"
                [ngModel]="f.title"
                (ngModelChange)="patch({ title: $event })"
              />
              <small id="f-title-hint" class="muted">
                Mục tiêu thể hiện chuyển biến cần đạt, định tính, ngắn gọn (tối đa 200 ký tự) ·
                {{ f.title.length }}/200
              </small>
            </div>
            <div class="grid3">
              <div class="field">
                <label for="f-unit">Đơn vị sở hữu</label>
                <input id="f-unit" class="inp" [value]="ownerName()" readonly />
              </div>
              <div class="field">
                <label for="f-period">Kỳ</label>
                @if (editable() && !meta()) {
                  <p-select
                    inputId="f-period"
                    [options]="c.periods"
                    optionLabel="label"
                    optionValue="id"
                    [ngModel]="f.periodId"
                    (ngModelChange)="changePeriod($event)"
                    appendTo="body"
                    styleClass="w100"
                  />
                } @else {
                  <input id="f-period" class="inp" [value]="c.period.label" readonly />
                }
              </div>
              @if (overLimit()) {
                <div class="field">
                  <label for="f-reason">
                    Lý do vượt {{ c.policy.recommendedMax }} mục tiêu
                    <span class="req" aria-hidden="true">*</span>
                  </label>
                  <textarea
                    id="f-reason"
                    class="inp"
                    rows="2"
                    maxlength="500"
                    required
                    [class.bad]="touched() && !(f.overLimitReason ?? '').trim()"
                    placeholder="Bắt buộc khi vượt khuyến nghị"
                    [ngModel]="f.overLimitReason ?? ''"
                    (ngModelChange)="patch({ overLimitReason: $event })"
                  ></textarea>
                </div>
              }
            </div>
            @if (touched() && objectiveFindings().length) {
              <ul class="findings">
                @for (x of objectiveFindings(); track x.ruleId) {
                  <li [class]="x.severity === 'CHAN' ? 'f-err' : 'f-warn'">{{ x.message }}</li>
                }
              </ul>
            }
          </fieldset>

          <fieldset class="box" [disabled]="!editable()">
            <legend class="sec">Kết quả then chốt (KR)</legend>
            @if (editable() && f.keyResults.length < maxKr) {
              <button type="button" class="linkbtn add" (click)="addKr()">+ Thêm KR</button>
            }
            <div class="tblwrap">
              <table class="kr">
                <caption class="sr-only">
                  Bảng KR của mục tiêu, soạn trực tiếp từng ô
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Mã</th>
                    <th scope="col">Nội dung KR</th>
                    <th scope="col">Đơn vị đo</th>
                    <th scope="col">Giá trị đầu</th>
                    <th scope="col">Chỉ tiêu</th>
                    <th scope="col">Trọng số (%)</th>
                    <th scope="col">Hạn</th>
                    <th scope="col">Người xác nhận</th>
                    <th scope="col">Check-in</th>
                    <th scope="col"><span class="sr-only">Xoá</span></th>
                  </tr>
                </thead>
                <tbody>
                  @for (k of f.keyResults; track k.key; let i = $index) {
                    @let code = krCode(i);
                    @let fs = touched() ? r.byKr[i] : [];
                    <tr [class.row-err]="hasError(fs)">
                      <th scope="row" class="code">{{ code }}</th>
                      <td class="title-cell">
                        <textarea
                          class="inp"
                          rows="2"
                          maxlength="300"
                          [id]="'kr-title-' + k.key"
                          [class.ai]="aiMarked().has(k.key)"
                          [class.bad]="hasError(fs)"
                          [attr.aria-label]="'Nội dung ' + code"
                          [attr.aria-describedby]="fs.length ? 'kr-msg-' + k.key : null"
                          [attr.aria-invalid]="hasError(fs)"
                          [ngModel]="k.title"
                          (ngModelChange)="patchKr(i, { title: $event })"
                        ></textarea>
                        @if (aiMarked().has(k.key)) {
                          <small class="ai-note"
                            >◆ Nội dung do AI gợi ý · cần người soạn xác nhận</small
                          >
                        }
                        @if (fs.length) {
                          <ul class="findings" [id]="'kr-msg-' + k.key">
                            @for (x of fs; track x.ruleId) {
                              <li [class]="x.severity === 'CHAN' ? 'f-err' : 'f-warn'">
                                {{ x.message }}
                              </li>
                            }
                          </ul>
                        }
                      </td>
                      <td>
                        <input
                          class="inp s"
                          list="okr-units"
                          [attr.aria-label]="'Đơn vị đo ' + code"
                          [ngModel]="k.unit"
                          (ngModelChange)="patchKr(i, { unit: $event })"
                        />
                      </td>
                      <td>
                        <input
                          class="inp s"
                          type="number"
                          step="any"
                          [attr.aria-label]="'Giá trị đầu ' + code"
                          [ngModel]="k.baselineValue"
                          (ngModelChange)="patchKr(i, { baselineValue: num($event) })"
                        />
                      </td>
                      <td>
                        <input
                          class="inp s"
                          type="number"
                          step="any"
                          [attr.aria-label]="'Chỉ tiêu ' + code"
                          [ngModel]="k.targetValue"
                          (ngModelChange)="patchKr(i, { targetValue: num($event) })"
                        />
                      </td>
                      <td>
                        <input
                          class="inp s"
                          type="number"
                          min="0"
                          max="100"
                          step="1"
                          [attr.aria-label]="'Trọng số phần trăm ' + code"
                          [ngModel]="k.weightPct"
                          (ngModelChange)="patchKr(i, { weightPct: num($event) })"
                        />
                      </td>
                      <td>
                        <input
                          class="inp"
                          type="date"
                          [min]="c.period.startDate"
                          [max]="c.period.endDate"
                          [attr.aria-label]="'Hạn ' + code"
                          [ngModel]="k.dueDate"
                          (ngModelChange)="patchKr(i, { dueDate: $event || null })"
                        />
                      </td>
                      <td>
                        <select
                          class="inp"
                          [attr.aria-label]="'Người xác nhận ' + code"
                          [ngModel]="k.verifierId"
                          (ngModelChange)="patchKr(i, { verifierId: $event || null })"
                        >
                          <option [ngValue]="null">Chọn</option>
                          @for (v of c.verifiers; track v.id) {
                            <option [ngValue]="v.id">{{ v.title }} · {{ v.name }}</option>
                          }
                        </select>
                      </td>
                      <td>
                        <select
                          class="inp"
                          [attr.aria-label]="'Tần suất check-in ' + code"
                          [ngModel]="k.checkinFrequency"
                          (ngModelChange)="patchKr(i, { checkinFrequency: $event })"
                        >
                          @for (q of frequencies; track q.value) {
                            <option [ngValue]="q.value">{{ q.label }}</option>
                          }
                        </select>
                      </td>
                      <td>
                        @if (editable()) {
                          <button
                            type="button"
                            class="iconbtn"
                            [attr.aria-label]="'Xoá ' + code"
                            (click)="removeKr(i)"
                          >
                            <i class="pi pi-trash" aria-hidden="true"></i>
                          </button>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
              <datalist id="okr-units">
                @for (u of c.policy.units; track u) {
                  <option [value]="u"></option>
                }
              </datalist>
            </div>
            <div class="foot">
              <span>
                Tổng trọng số:
                <strong [class]="totalWeight() === 100 ? 'ok' : 'err'">{{ totalWeight() }}%</strong>
                @if (editable() && f.keyResults.length > 1) {
                  <button type="button" class="linkbtn" (click)="splitEvenly()">Chia đều</button>
                }
              </span>
              <span class="badges">
                @if (r.errors.length) {
                  <span class="badge b-error"
                    >{{ r.errors.length }} lỗi cần sửa trước khi trình</span
                  >
                }
                @if (r.warnings.length) {
                  <span class="badge b-warn">{{ r.warnings.length }} cảnh báo</span>
                }
              </span>
            </div>
          </fieldset>
        </div>

        <aside>
          @if (showAi() && editable()) {
            <vt-ai-review-panel
              [suggestions]="aiOpen()"
              [reviewed]="!!aiResult()"
              [error]="aiError()"
              (dismissed)="dismiss($event)"
              (applied)="apply($event.suggestion, $event.choice)"
            />
          }
          <section class="box" aria-labelledby="chk-h">
            <h2 id="chk-h" class="sec">Kiểm tra trước khi trình</h2>
            <ul class="check">
              @for (it of r.checklist; track $index) {
                <li>
                  <span class="badge" [class]="'b-' + it.state"
                    >{{ stateLabel[it.state] }}<span class="sr-only">: </span></span
                  >
                  <span>{{ it.label }}</span>
                </li>
              }
            </ul>
            <p class="muted small">
              Khi trình, hệ thống kiểm tra lại toàn bộ quy tắc, kể cả quy tắc người xác nhận phải
              khác chủ sở hữu KR. Bộ quy tắc OKR phiên bản {{ c.policy.version }}.
            </p>
          </section>
          <section class="box" aria-labelledby="pr-h">
            <h2 id="pr-h" class="sec">Phân rã</h2>
            <p class="small">
              Sau khi duyệt, mỗi KR có thể gắn chương trình (Tầng 2) và nhiệm vụ trong Kế hoạch quý
              (Tầng 3). Một nhiệm vụ chỉ tính điểm ở một nhóm.
            </p>
          </section>
        </aside>
      </div>
    }
  `,
  styles: `
    /* Đỏ thương hiệu đậm hơn token chính để đạt WCAG AA (xem WEB-059); tím AI theo mockup */
    :host {
      --okr-accent: #c4002a;
      --okr-ai: #6d28d9;
    }
    .head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      margin-bottom: 12px;
    }
    h1 {
      font-size: 1.4rem;
      margin: 4px 0;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.875rem;
    }
    .err {
      color: #b91c1c;
    }
    .ok {
      color: #15803d;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }
    :host ::ng-deep .primary .p-button,
    :host ::ng-deep .p-button.primary {
      background: var(--okr-accent);
      border-color: var(--okr-accent);
    }
    :host ::ng-deep .msg {
      margin-bottom: 12px;
      display: block;
    }
    .banner {
      display: flex;
      gap: 10px;
      align-items: center;
      padding: 10px 14px;
      margin-bottom: 12px;
      border-left: 4px solid #b45309;
      background: #fef6e4;
      border-radius: 6px;
    }
    .num {
      display: inline-grid;
      place-items: center;
      width: 22px;
      height: 22px;
      border-radius: 50%;
      background: #111827;
      color: #fff;
      font-size: 0.75rem;
      font-weight: 700;
      flex: none;
    }
    .cols {
      display: grid;
      grid-template-columns: minmax(0, 2.6fr) minmax(260px, 1fr);
      gap: 16px;
      align-items: start;
    }
    @media (max-width: 1100px) {
      .cols {
        grid-template-columns: 1fr;
      }
    }
    .box {
      position: relative;
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
      margin: 0 0 12px;
      min-width: 0;
    }
    .sec {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 1.05rem;
      font-weight: 700;
      margin: 0 0 12px;
      padding: 0;
    }
    fieldset > legend.sec {
      float: left;
      width: 100%;
    }
    fieldset > :not(legend) {
      clear: both;
    }
    .grid2 {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
    }
    .grid3 {
      display: grid;
      grid-template-columns: 1fr 1fr 1fr;
      gap: 12px;
    }
    @media (max-width: 800px) {
      .grid2,
      .grid3 {
        grid-template-columns: 1fr;
      }
    }
    .field {
      display: flex;
      flex-direction: column;
      gap: 4px;
      margin-bottom: 12px;
      min-width: 0;
    }
    .field label {
      font-weight: 600;
    }
    .req {
      color: #b91c1c;
    }
    .inp {
      font: inherit;
      padding: 7px 9px;
      border: 1px solid #9ca3af;
      border-radius: 6px;
      background: #fff;
      color: #111827;
      width: 100%;
      box-sizing: border-box;
    }
    .inp:focus-visible,
    .linkbtn:focus-visible,
    .iconbtn:focus-visible {
      outline: 2px solid var(--okr-accent);
      outline-offset: 1px;
    }
    .inp[readonly] {
      background: #f3f4f6;
    }
    .inp.s {
      width: 4.75rem;
    }
    .inp.bad {
      border: 1.5px solid #b91c1c;
    }
    .inp.ai {
      border: 2px solid var(--okr-ai);
      background: #f5f3ff;
    }
    :host ::ng-deep .w100 {
      width: 100%;
    }
    .linkbtn {
      background: none;
      border: 0;
      padding: 0;
      color: var(--okr-accent);
      font: inherit;
      font-weight: 600;
      cursor: pointer;
      margin-left: 8px;
    }
    .add {
      position: absolute;
      right: 16px;
      top: 16px;
    }
    .iconbtn {
      background: none;
      border: 0;
      color: #4b5563;
      cursor: pointer;
      padding: 6px;
    }
    .tblwrap {
      overflow-x: auto;
    }
    table.kr {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.875rem;
    }
    .kr th,
    .kr td {
      padding: 8px 6px;
      border-bottom: 1px solid #e5e7eb;
      vertical-align: top;
      text-align: left;
    }
    .kr thead th {
      background: #f9fafb;
    }
    .kr .code {
      white-space: nowrap;
      font-weight: 600;
    }
    .title-cell {
      min-width: 220px;
    }
    .findings {
      list-style: none;
      margin: 6px 0 0;
      padding: 0;
      font-size: 0.8125rem;
    }
    .findings li {
      border-radius: 4px;
      padding: 3px 8px;
      margin-top: 4px;
    }
    .foot {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px;
      margin-top: 10px;
    }
    .badges {
      display: flex;
      gap: 6px;
    }
    .badge {
      display: inline-block;
      border-radius: 4px;
      padding: 2px 8px;
      font-size: 0.8125rem;
      font-weight: 700;
      white-space: nowrap;
    }
    .f-err,
    .b-error {
      background: #fdecea;
      color: #991b1b;
    }
    .f-warn,
    .b-warn {
      background: #fef6e4;
      color: #7c4a03;
    }
    .b-pass {
      background: #e7f6ec;
      color: #166534;
    }
    .check {
      list-style: none;
      padding: 0;
      margin: 0 0 8px;
    }
    .check li {
      display: flex;
      gap: 8px;
      align-items: flex-start;
      margin-bottom: 8px;
    }
    .ai-note {
      display: block;
      color: var(--okr-ai);
      font-size: 0.8125rem;
      font-weight: 600;
      margin-top: 4px;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      padding: 0;
      margin: -1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      white-space: nowrap;
      border: 0;
    }
  `,
})
export class ObjectiveComposePage {
  private readonly api = inject(OkrApi);
  private readonly problems = inject(ProblemPresenter);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
  private readonly theme = inject(ThemeService);
  protected readonly can = inject(OKR_CAN);

  /** Về trang cây SCR-0201: lùi đúng số đoạn URL của tuyến này (`objectives/new`, `objectives/:id/edit`). */
  protected readonly treeLink = this.route.snapshot.url.map(() => '..').join('/') || '.';
  protected readonly maxKr = MAX_KR;
  protected readonly frequencies = FREQUENCIES;
  protected readonly fmtDate = (iso: string) =>
    new Date(iso).toLocaleDateString('vi-VN', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
    });
  protected readonly stateLabel = { pass: 'Đạt', error: 'Lỗi', warn: 'Cảnh báo' } as const;

  private readonly params = toSignal(
    combineLatest([this.route.paramMap, this.route.queryParamMap]).pipe(
      map(([p, q]) => ({ id: p.get('id'), period: q.get('period') ?? undefined })),
    ),
    { initialValue: { id: null as string | null, period: undefined as string | undefined } },
  );

  protected readonly load = rxResource({
    params: () => this.params(),
    stream: ({ params }): Observable<Loaded> =>
      params.id
        ? this.api
            .objective(params.id)
            .pipe(
              switchMap((detail) =>
                this.api
                  .composeContext({ period: detail.periodId, objectiveId: detail.id })
                  .pipe(map((ctx) => ({ ctx, detail }))),
              ),
            )
        : this.api
            .composeContext({ period: params.period })
            .pipe(map((ctx) => ({ ctx, detail: null }))),
  });
  private readonly loaded = computed(() => (this.load.hasValue() ? this.load.value() : null));
  protected readonly ctx = computed(() => this.loaded()?.ctx ?? null);
  protected readonly loadError = computed(() =>
    this.load.error() ? this.problems.describe(this.load.error()).message : '',
  );

  /** Bản đang soạn; tải lại từ máy chủ thì dựng lại. */
  protected readonly form = linkedSignal<ComposeForm | null>(() => {
    const l = this.loaded();
    return l ? this.initialForm(l) : null;
  });
  protected readonly meta = linkedSignal<SavedMeta | null>(() => {
    const d = this.loaded()?.detail;
    return d ? toMeta(d) : null;
  });
  private readonly dirty = signal(false);
  /**
   * Báo lỗi cạnh từng ô sau khi bản đã lưu hoặc người soạn bắt đầu nhập; biểu mẫu mới để trống chỉ
   * hiện danh sách kiểm tra, tránh dồn lỗi đỏ khi chưa nhập gì.
   */
  protected readonly touched = computed(() => !!this.meta() || this.dirty());
  /** KR đang mang nội dung AI chưa được lưu (viền tím, BR-02-11). */
  protected readonly aiMarked = signal<ReadonlySet<string>>(new Set());

  protected readonly saving = signal(false);
  protected readonly submitting = signal(false);
  protected readonly submitted = signal<OkrSubmitResult | null>(null);
  protected readonly actionError = signal<{ message: string; details: string[] } | null>(null);
  protected readonly conflict = signal<{
    mine: ObjectiveDraft;
    theirs: ObjectiveDraft | null;
  } | null>(null);

  protected readonly aiLoading = signal(false);
  protected readonly aiError = signal<string | null>(null);
  protected readonly aiResult = signal<AiKrSuggestion[] | null>(null);
  private readonly aiClosed = signal<ReadonlySet<string>>(new Set());
  protected readonly aiOpen = computed(() =>
    (this.aiResult() ?? []).filter((s) => !this.aiClosed().has(s.id)),
  );

  /** Chế độ "Đảng viên cao tuổi" giảm bước: ẩn trợ lý AI, giữ thao tác chính (NFR-11). */
  private readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );
  protected readonly showAi = computed(
    () => !!this.ctx()?.aiEnabled && this.can('SCR-0202.ai-soat') && !this.simple(),
  );

  /** Mục tiêu đã lưu: thêm điều kiện theo đối tượng máy chủ trả (YC-PT-21). */
  private readonly detail = computed(() => this.loaded()?.detail ?? null);
  private readonly canCompose = computed(
    () =>
      (this.can('SCR-0202.soan-don-vi') && permittedOn(this.detail(), 'SCR-0202.soan-don-vi')) ||
      (this.can('SCR-0202.soan-ca-nhan') && permittedOn(this.detail(), 'SCR-0202.soan-ca-nhan')),
  );
  protected readonly canSubmit = computed(
    () => this.can('SCR-0202.trinh') && permittedOn(this.detail(), 'SCR-0202.trinh'),
  );
  private readonly periodLocked = computed(() => {
    const s = this.ctx()?.period.status;
    return s === 'DANG_KHOA' || s === 'DA_KHOA';
  });
  /** Lý do không sửa được tại chỗ: BR-02-10, kỳ khoá, hoặc không có quyền soạn. */
  protected readonly readonlyReason = computed(() => {
    const c = this.ctx();
    const m = this.meta();
    if (!c || this.submitted()) return null;
    if (!this.canCompose()) return 'Đồng chí không có quyền soạn mục tiêu này.';
    if (this.periodLocked()) return `Kỳ ${c.period.label} đã khoá, không soạn thêm mục tiêu.`;
    if (m && m.status !== 'NHAP') {
      return `Mục tiêu đang ở trạng thái "${OBJECTIVE_STATUS_LABEL[m.status]}" nên không sửa trực tiếp được.`;
    }
    return null;
  });
  protected readonly editable = computed(
    () => !!this.ctx() && !this.readonlyReason() && !this.submitted(),
  );

  /** Số thứ tự mục tiêu dùng cho mã KR `KR {o}.{n}`. */
  private readonly objectiveNo = computed(() => {
    const code = this.meta()?.code;
    const n = code ? Number(code.replace(/\D/g, '')) : NaN;
    return Number.isFinite(n) && n > 0 ? n : (this.ctx()?.existingCount ?? 0) + 1;
  });
  protected readonly krCode = (i: number) => `KR ${this.objectiveNo()}.${i + 1}`;

  protected readonly check = computed(() => {
    const f = this.form();
    const c = this.ctx();
    return f && c ? checkOkr(f, c, this.krCode) : null;
  });
  protected readonly objectiveFindings = computed(() =>
    (this.check()?.errors ?? [])
      .concat(this.check()?.warnings ?? [])
      .filter((x) => x.krIndex === undefined),
  );
  protected readonly totalWeight = computed(() => {
    const f = this.form();
    return f ? totalWeightPct(f) : 0;
  });
  protected readonly overLimit = computed(() => {
    const c = this.ctx();
    return !!c && c.existingCount + 1 > c.policy.recommendedMax;
  });

  protected readonly heading = computed(() => {
    const c = this.ctx();
    if (!c) return '';
    const period = c.period.label.charAt(0).toLocaleLowerCase('vi') + c.period.label.slice(1);
    const m = this.meta();
    return m ? `Sửa mục tiêu ${m.code} ${period}` : `Soạn mục tiêu ${period}`;
  });
  protected readonly saveText = computed(() => {
    const m = this.meta();
    if (!m) return '';
    if (this.saving()) return 'đang lưu…';
    const t = new Date(m.updatedAt).toLocaleTimeString('vi-VN', {
      hour: '2-digit',
      minute: '2-digit',
    });
    return this.dirty() ? `đã lưu ${t}, có thay đổi chưa lưu` : `lưu tự động ${t}`;
  });
  protected readonly ownerName = computed(() => {
    const c = this.ctx();
    if (!c) return '';
    const own = this.loaded()?.detail?.ownerType ?? this.defaultOwnerType();
    return own === 'CA_NHAN' ? `${c.me.name} (OKR cá nhân)` : c.unit.name;
  });
  protected readonly basisOptions = computed(() =>
    (this.ctx()?.bases ?? []).map((b) => ({
      id: b.id,
      label: b.docNumber ? `${b.title} (${b.docNumber})` : b.title,
    })),
  );
  protected readonly parentOptions = computed(() =>
    (this.ctx()?.parentOptions ?? []).map((p) => ({
      id: p.id,
      label: `${p.code} · ${p.title}${p.status === 'DA_DUYET' ? '' : ' (chưa duyệt)'}`,
    })),
  );

  protected readonly conflictFields: ConflictField<ObjectiveDraft>[] = [
    { label: 'Tên mục tiêu', value: (d) => d.title },
    { label: 'Số KR', value: (d) => d.keyResults.length },
    {
      label: 'Nội dung KR',
      value: (d) => d.keyResults.map((k) => `${k.code}: ${k.title}`).join('; '),
    },
    {
      label: 'Trọng số KR',
      value: (d) => d.keyResults.map((k) => `${Math.round(k.weight * 100)}%`).join(', '),
    },
  ];

  constructor() {
    // Lưu tự động mỗi 30 giây khi có thay đổi (SCR-0202 "Lưu nháp")
    interval(AUTOSAVE_MS)
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe(() => {
        const f = this.form();
        if (
          this.dirty() &&
          this.editable() &&
          !this.saving() &&
          !this.conflict() &&
          f?.title.trim()
        ) {
          this.save().subscribe({ error: () => undefined });
        }
      });
  }

  protected num(v: unknown): number | null {
    if (v === null || v === undefined || v === '') return null;
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }

  protected hasError(fs: { severity: string }[]) {
    return fs.some((x) => x.severity === 'CHAN');
  }

  protected patch(p: Partial<ComposeForm>) {
    this.form.update((f) => (f ? { ...f, ...p } : f));
    this.dirty.set(true);
  }

  protected patchKr(i: number, p: Partial<ComposeKr>) {
    this.form.update((f) => {
      if (!f) return f;
      const krs = f.keyResults.slice();
      const cur = krs[i];
      const next = { ...cur, ...p };
      // Sửa tay nội dung AI vừa điền: ghi vết AI_EDITED (BR-02-11)
      if (p.title !== undefined && p.title !== cur.title && cur.contentSource === 'AI_ACCEPTED') {
        next.contentSource = 'AI_EDITED';
      }
      krs[i] = next;
      return { ...f, keyResults: krs };
    });
    this.dirty.set(true);
  }

  protected addKr() {
    const c = this.ctx();
    this.form.update((f) => {
      if (!f || !c || f.keyResults.length >= MAX_KR) return f;
      const rest = Math.max(0, 100 - totalWeightPct(f));
      return { ...f, keyResults: [...f.keyResults, this.blankKr(c, rest)] };
    });
    this.dirty.set(true);
  }

  protected removeKr(i: number) {
    this.form.update((f) => (f ? { ...f, keyResults: f.keyResults.filter((_, j) => j !== i) } : f));
    this.dirty.set(true);
  }

  protected splitEvenly() {
    this.form.update((f) => {
      if (!f) return f;
      const w = evenWeights(f.keyResults.length);
      return { ...f, keyResults: f.keyResults.map((k, i) => ({ ...k, weightPct: w[i] })) };
    });
    this.dirty.set(true);
  }

  protected changePeriod(periodId: string) {
    if (!periodId || periodId === this.form()?.periodId) return;
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { period: periodId },
      replaceUrl: true,
    });
  }

  protected saveDraft() {
    this.save().subscribe({ error: () => undefined });
  }

  /** Lưu nháp: tạo khi chưa có id, cập nhật với If-Match khi đã có. */
  private save(): Observable<SavedMeta> {
    const draft = this.toDraft();
    const m = this.meta();
    if (!draft) return of();
    this.saving.set(true);
    this.actionError.set(null);
    const req = m
      ? this.api.saveObjective(m.id, m.rowVersion, draft)
      : this.api.createObjective(draft);
    return new Observable<SavedMeta>((sub) => {
      const s = req.subscribe({
        next: (d) => {
          const created = !m;
          const saved = toMeta(d);
          this.meta.set(saved);
          this.dirty.set(false);
          this.aiMarked.set(new Set());
          // Gắn id máy chủ cấp cho KR mới mà không ghi đè chỉnh sửa vừa nhập trong lúc lưu
          this.form.update((f) =>
            f
              ? {
                  ...f,
                  keyResults: f.keyResults.map((k, i) => ({
                    ...k,
                    id: k.id ?? d.keyResults[i]?.id,
                    contentSource: d.keyResults[i]?.contentSource ?? k.contentSource,
                  })),
                }
              : f,
          );
          if (created) {
            const url = this.router.createUrlTree([this.treeLink, 'objectives', d.id, 'edit'], {
              relativeTo: this.route,
            });
            this.location.replaceState(this.router.serializeUrl(url));
          }
          this.saving.set(false);
          sub.next(saved);
          sub.complete();
        },
        error: (e) => {
          this.saving.set(false);
          this.handleError(e, draft);
          sub.error(e);
        },
      });
      return () => s.unsubscribe();
    });
  }

  protected submit() {
    if ((this.check()?.errors.length ?? 1) > 0 || this.saving()) return;
    this.submitting.set(true);
    const m = this.meta();
    const saved$ = this.dirty() || !m ? this.save() : of(m);
    saved$.pipe(switchMap((s) => this.api.submitObjective(s.id))).subscribe({
      next: (res) => {
        this.submitting.set(false);
        this.meta.update((x) => (x ? { ...x, status: res.status } : x));
        this.submitted.set(res);
      },
      error: (e) => {
        this.submitting.set(false);
        if (!this.actionError() && !this.conflict()) this.handleError(e, this.toDraft());
      },
    });
  }

  private handleError(e: unknown, mine: ObjectiveDraft | null) {
    const p = this.problems.describe(e);
    const m = this.meta();
    if (p.conflict && m && mine && p.problem.status === 409 && p.problem.code === undefined) {
      this.conflict.set({ mine, theirs: null });
      this.api.objective(m.id).subscribe({
        next: (d) => this.conflict.set({ mine, theirs: { ...d } }),
        error: () => undefined,
      });
      return;
    }
    this.actionError.set({
      message: p.message,
      details: (p.problem.errors ?? []).map((x) => x.message),
    });
  }

  protected reloadFromServer() {
    this.conflict.set(null);
    this.dirty.set(false);
    this.aiMarked.set(new Set());
    this.load.reload();
  }

  protected overwrite() {
    const theirs = this.conflict()?.theirs as ObjectiveDetail | null | undefined;
    if (!theirs) return;
    this.meta.update((m) => (m ? { ...m, rowVersion: theirs.rowVersion } : m));
    this.conflict.set(null);
    this.saveDraft();
  }

  protected runAi() {
    const draft = this.toDraft();
    if (!draft) return;
    this.aiLoading.set(true);
    this.aiError.set(null);
    this.api.aiReview(draft).subscribe({
      next: (r) => {
        this.aiResult.set(r.suggestions);
        this.aiClosed.set(new Set());
        this.aiLoading.set(false);
      },
      error: (e) => {
        this.aiError.set(this.problems.describe(e).message);
        this.aiLoading.set(false);
      },
    });
  }

  protected dismiss(s: AiKrSuggestion) {
    this.aiClosed.update((x) => new Set([...x, s.id]));
  }

  /** "Dùng gợi ý vào ô": điền nội dung, viền tím, chỉ ghi khi người soạn lưu (BR-02-11). */
  protected apply(s: AiKrSuggestion, choice: number) {
    const f = this.form();
    if (!f) return;
    const i = f.keyResults.findIndex((_, j) => this.krCode(j) === s.krCode);
    if (i < 0) return;
    const key = f.keyResults[i].key;
    this.patchKr(i, { title: s.rewrites[choice], contentSource: 'AI_ACCEPTED' });
    this.aiMarked.update((x) => new Set([...x, key]));
    this.dismiss(s);
    queueMicrotask(() => document.getElementById(`kr-title-${key}`)?.focus());
  }

  private defaultOwnerType() {
    return this.can('SCR-0202.soan-don-vi') ? 'TAP_THE' : 'CA_NHAN';
  }

  private blankKr(c: OkrComposeContext, weightPct: number): ComposeKr {
    const head = c.verifiers.find((v) => v.id !== c.me.id);
    return {
      key: newKey(),
      code: '',
      title: '',
      unit: '',
      baselineValue: 0,
      targetValue: null,
      weightPct,
      dueDate: c.period.endDate,
      verifierId: head?.id ?? null,
      checkinFrequency: 'TUAN',
      contentSource: 'HUMAN',
    };
  }

  private initialForm({ ctx, detail }: Loaded): ComposeForm {
    if (detail) {
      return {
        periodId: detail.periodId,
        basisIds: detail.basisIds,
        parentObjectiveId: detail.parentObjectiveId,
        title: detail.title,
        overLimitReason: detail.overLimitReason,
        keyResults: detail.keyResults.map((k) => ({
          ...k,
          key: newKey(),
          weightPct: Math.round(k.weight * 10000) / 100,
        })),
      };
    }
    const w = evenWeights(2);
    return {
      periodId: ctx.period.id,
      basisIds: [],
      parentObjectiveId: null,
      title: '',
      overLimitReason: null,
      keyResults: w.map((x) => this.blankKr(ctx, x)),
    };
  }

  private toDraft(): ObjectiveDraft | null {
    const f = this.form();
    if (!f) return null;
    return {
      ...(this.meta() ? { id: this.meta()!.id } : {}),
      periodId: f.periodId,
      ownerType: this.loaded()?.detail?.ownerType ?? this.defaultOwnerType(),
      basisIds: f.basisIds,
      parentObjectiveId: f.parentObjectiveId,
      title: f.title.trim(),
      overLimitReason: this.overLimit() ? (f.overLimitReason ?? '').trim() || null : null,
      keyResults: f.keyResults.map(({ key: _key, weightPct, ...k }, i) => ({
        ...k,
        code: this.krCode(i),
        title: k.title.trim(),
        unit: k.unit.trim(),
        weight: (weightPct ?? 0) / 100,
      })),
    };
  }
}

function toMeta(d: ObjectiveDetail): SavedMeta {
  return {
    id: d.id,
    code: d.code,
    rowVersion: d.rowVersion,
    draftNo: d.draftNo,
    status: d.status,
    updatedAt: d.updatedAt,
    authorName: d.authorName,
  };
}
