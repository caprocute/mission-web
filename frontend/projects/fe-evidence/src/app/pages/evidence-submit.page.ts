/*
 * SCR-0701 Nộp minh chứng (QT-07 bước 1–6; TL-02 Hình 8.13). AC-06 là người chủ trì nhiệm vụ.
 * Trường: 1 nhiệm vụ, sản phẩm; 2 nguồn minh chứng; 3 văn bản đã ban hành (vOffice, BR-07-13); 4 số phiên
 * bản (n+1); 5 tệp (PDF, DOCX, XLSX, JPG, PNG ≤ 50 MB); 6 SHA-256 tính trên trình duyệt (BR-07-02); 7 kết
 * quả quét (BR-07-04); 8 trùng với; 9 giải trình dùng lại 20–1.000 ký tự (BR-07-03); 10 trạng thái nghiệm
 * thu; 11 cam kết.
 * Chức năng: "Chọn tệp, kéo thả" (`POST /files/uploads`, `PUT …/parts/{n}`, `POST …/complete`, rồi hỏi
 * `GET /files/{id}/scan`), "Chọn văn bản" (`GET /evidences/external-docs?q=`), "Lưu nháp" (`POST
 * /evidences/{id}/versions` hoặc `PUT …/versions/{v}` kèm If-Match), "Nộp để nghiệm thu" (`POST
 * …/versions/{v}/submit`, Idempotency-Key; EVT-07, EVT-06).
 * Quy tắc: BR-07-01 phiên bản đã nộp không sửa, nộp lại tạo phiên bản mới; BR-07-10 trạng thái thực hiện và
 * trạng thái nghiệm thu tách biệt. Thông báo MSG-07-01 … MSG-07-05.
 * Hợp đồng `products.yaml` cho mỗi phiên bản một tệp (`objectId`) cộng một văn bản vOffice (`sourceDocRef`),
 * nên bảng tệp có tối đa hai dòng: văn bản và tệp đính kèm (wiki/14).
 */
import {
  Component,
  DestroyRef,
  InjectionToken,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  EVIDENCE_STATUS_LABEL,
  EvidenceApi,
  EvidenceDuplicateMatch,
  EvidenceExternalDoc,
  EvidenceVersion,
  EvidenceVersionDraft,
  EvidenceVersionStatus,
  FILE_SCAN_LABEL,
  FileApi,
  FileRejectedError,
  FileScanStatus,
  TASK_STATUS_LABEL,
  TaskApi,
  TaskStatus,
  UploadProgress,
  toProblem,
} from '@vt/api-client';
import { permittedOn } from '@vt/auth';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { firstValueFrom, of } from 'rxjs';
import { EVIDENCE_CAN } from '../evidence-permissions';

type Source = 'VAN_BAN_HE_THONG' | 'TAI_LEN';
type Busy = 'save' | 'submit' | 'upload' | null;

/** Tệp đính kèm của phiên bản đang soạn (từ phiên bản đã lưu hoặc vừa tải lên). */
interface Attachment {
  objectId: string;
  fileName: string;
  sizeBytes: number;
  sha256: string;
  scanStatus: FileScanStatus;
  duplicateOf: EvidenceDuplicateMatch | null;
}

/** Phiên bản còn mở thì chưa tạo được phiên bản mới; kết thúc bằng bổ sung hoặc từ chối thì nộp lại. */
const REOPEN: EvidenceVersionStatus[] = ['YEU_CAU_BO_SUNG', 'TU_CHOI'];

/** Chu kỳ hỏi kết quả quét mã độc (SEC-47, quét ≤ 2 phút); kiểm thử đặt ngắn hơn. */
export const EVIDENCE_SCAN_POLL_MS = new InjectionToken<number>('vt.fe-evidence.scan-poll-ms', {
  providedIn: 'root',
  factory: () => 2000,
});

const fmtDate = (iso?: string | null) =>
  iso
    ? new Date(iso.length === 10 ? `${iso}T00:00:00` : iso).toLocaleDateString('vi-VN', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
      })
    : '';

const fmtSize = (n?: number | null) =>
  n == null
    ? '—'
    : n >= 1024 * 1024
      ? `${(n / 1024 / 1024).toLocaleString('vi-VN', { maximumFractionDigits: 1 })} MB`
      : `${Math.max(1, Math.round(n / 1024))} KB`;

@Component({
  selector: 'vt-evidence-submit-page',
  imports: [FormsModule, RouterLink, ButtonModule],
  template: `
    @if (!can('SCR-0701.xem')) {
      <p class="msg warn">Đồng chí không có quyền xem minh chứng.</p>
    } @else if (evidence.error()) {
      <p class="msg err" role="alert">{{ loadError() }}</p>
    } @else if (evidence.value(); as e) {
      <header class="top">
        <div>
          <p class="back">
            <a [routerLink]="['..']" [queryParams]="{ task: e.task.id }"
              >← Minh chứng của nhiệm vụ</a
            >
          </p>
          <h1>Nộp sản phẩm, minh chứng</h1>
          <p class="muted sub">
            {{ e.task.code }} · {{ e.task.name }}
            @if (task.value(); as t) {
              · hạn {{ date(t.dueDate) }}
            }
            · sản phẩm: {{ e.title }}
          </p>
        </div>
        @if (editable()) {
          <div class="actions">
            <p-button
              label="Lưu nháp"
              severity="secondary"
              [outlined]="true"
              [loading]="busy() === 'save'"
              [disabled]="!!busy()"
              (onClick)="save()"
            />
            @if (canSubmit()) {
              <p-button
                label="Nộp để nghiệm thu"
                icon="pi pi-send"
                [loading]="busy() === 'submit'"
                [disabled]="!!busy() || blockers().length > 0"
                [attr.aria-describedby]="blockers().length ? 'submit-why' : null"
                (onClick)="submit()"
              />
            }
          </div>
        }
      </header>

      @for (n of notices(); track n) {
        <p class="msg ok" role="status">{{ n }}</p>
      }
      @if (error(); as err) {
        <p class="msg err" role="alert">{{ err }}</p>
      }
      @if (!editable()) {
        @if (latest(); as l) {
          <p class="msg info">
            Phiên bản {{ l.versionNo }} ở trạng thái {{ statusLabel[l.status] }}; minh chứng đã nộp
            không sửa được. Khi người nghiệm thu yêu cầu bổ sung, đồng chí nộp phiên bản mới.
          </p>
        }
        @if (!isLead()) {
          <p class="msg warn">Chỉ người chủ trì nhiệm vụ nộp được minh chứng.</p>
        }
      } @else if (canSubmit() && blockers().length && !busy()) {
        <p class="msg info" id="submit-why">Để nộp: {{ blockers().join('; ') }}.</p>
      }

      <div class="layout">
        <div>
          <section class="box" aria-labelledby="src-h">
            <h2 class="h" id="src-h">
              <span class="num" aria-hidden="true">1</span> Chọn nguồn minh chứng
            </h2>
            <fieldset class="sources" [disabled]="!editable()">
              <legend class="sr-only">Nguồn minh chứng</legend>
              <!-- prettier-ignore -->
              <label class="src" [class.on]="source() === 'VAN_BAN_HE_THONG'">
                <!-- ui-text-ok: giá trị của ô chọn, không hiện lên màn hình -->
                <input type="radio" name="src" value="VAN_BAN_HE_THONG" [ngModel]="source()" (ngModelChange)="source.set($event)" />
                <span>
                  <strong>Văn bản đã ban hành</strong>
                  <span class="muted small">Lấy từ vOffice, không cần tải lại tệp</span>
                </span>
              </label>
              <!-- prettier-ignore -->
              <label class="src" [class.on]="source() === 'TAI_LEN'">
                <!-- ui-text-ok: giá trị của ô chọn, không hiện lên màn hình -->
                <input type="radio" name="src" value="TAI_LEN" [ngModel]="source()" (ngModelChange)="source.set($event)" />
                <span>
                  <strong>Tải tệp lên</strong>
                  <span class="muted small">PDF, DOCX, XLSX, ảnh ≤ 50 MB</span>
                </span>
              </label>
              <label class="src off">
                <!-- ui-text-ok: giá trị của ô chọn, không hiện lên màn hình -->
                <input type="radio" name="src" value="LIEN_KET" disabled />
                <span>
                  <strong>Đường dẫn hệ thống nguồn</strong>
                  <span class="muted small">CSDL đảng viên, LGSP · chưa áp dụng</span>
                </span>
              </label>
            </fieldset>

            @if (source() === 'VAN_BAN_HE_THONG') {
              <p class="lbl-row" id="doc-lbl">Văn bản</p>
              @if (doc(); as d) {
                <div class="doc" aria-labelledby="doc-lbl">
                  <span>{{ d.docNumber }} · {{ date(d.docDate) }} · {{ d.summary }}</span>
                  @if (editable()) {
                    <p-button
                      label="Chọn văn bản khác"
                      [text]="true"
                      size="small"
                      (onClick)="docRef.set(null)"
                    />
                  }
                </div>
                @if (!elderly() && d.signerName) {
                  <p class="muted small">
                    {{ d.signatureValid === false ? 'Chữ ký số không hợp lệ' : 'Đã ký số' }} bởi
                    {{ d.signerName }} · tệp do vOffice cung cấp
                  </p>
                }
              } @else if (editable()) {
                <form class="search" (ngSubmit)="docQuery.set(docInput)" role="search">
                  <label for="doc-q" class="sr-only">Tìm văn bản theo số hoặc trích yếu</label>
                  <input
                    id="doc-q"
                    name="q"
                    class="inp"
                    placeholder="Số hoặc trích yếu văn bản"
                    [(ngModel)]="docInput"
                  />
                  <p-button type="submit" label="Tìm" severity="secondary" [outlined]="true" />
                </form>
                @if (docs.value(); as list) {
                  @if (list.length) {
                    <ul class="docs" aria-label="Văn bản đã ban hành của đơn vị">
                      @for (x of list; track x.ref) {
                        <li>
                          <button type="button" class="pick" (click)="docRef.set(x.ref)">
                            <strong>{{ x.docNumber }}</strong> · {{ date(x.docDate) }} ·
                            {{ x.summary }}
                          </button>
                        </li>
                      }
                    </ul>
                  } @else {
                    <p class="muted">Không tìm thấy văn bản phù hợp.</p>
                  }
                }
              } @else {
                <p class="muted">Chưa chọn văn bản.</p>
              }
            }
          </section>

          <section class="box" aria-labelledby="file-h">
            <h2 class="h" id="file-h">
              <span class="num" aria-hidden="true">2</span> Tệp minh chứng · phiên bản
              {{ versionNo() }}
            </h2>
            @if (doc() || file()) {
              <div class="scroll" tabindex="0" role="region" aria-label="Bảng tệp minh chứng">
                <table class="tbl">
                  <thead>
                    <tr>
                      <th scope="col">Tệp</th>
                      <th scope="col">Dung lượng</th>
                      @if (!elderly()) {
                        <th scope="col">Mã kiểm tra toàn vẹn</th>
                      }
                      <th scope="col">Quét mã độc</th>
                      @if (editable()) {
                        <th scope="col"><span class="sr-only">Thao tác</span></th>
                      }
                    </tr>
                  </thead>
                  <tbody>
                    @if (doc(); as d) {
                      <tr>
                        <td>
                          <i class="pi pi-file" aria-hidden="true"></i> Văn bản {{ d.docNumber }}
                        </td>
                        <td>—</td>
                        @if (!elderly()) {
                          <td class="muted small">tệp gốc vOffice</td>
                        }
                        <td>
                          <span class="chip SACH">{{
                            d.signatureValid === false ? 'Chữ ký lỗi' : 'Ký số hợp lệ'
                          }}</span>
                        </td>
                        @if (editable()) {
                          <td></td>
                        }
                      </tr>
                    }
                    @if (file(); as f) {
                      <tr>
                        <td><i class="pi pi-file" aria-hidden="true"></i> {{ f.fileName }}</td>
                        <td class="nw">{{ size(f.sizeBytes) }}</td>
                        @if (!elderly()) {
                          <td class="hash" [attr.title]="f.sha256">{{ shortHash(f.sha256) }}</td>
                        }
                        <td>
                          <span class="chip" [class]="f.scanStatus">{{
                            scanLabel[f.scanStatus]
                          }}</span>
                        </td>
                        @if (editable()) {
                          <td>
                            <p-button
                              icon="pi pi-times"
                              [text]="true"
                              severity="secondary"
                              size="small"
                              [ariaLabel]="'Gỡ tệp ' + f.fileName"
                              (onClick)="removeFile()"
                            />
                          </td>
                        }
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            } @else {
              <p class="muted">Phiên bản này chưa có tệp hoặc văn bản.</p>
            }

            @if (editable()) {
              <div
                class="drop"
                [class.over]="dragOver()"
                (dragover)="onDragOver($event)"
                (dragleave)="dragOver.set(false)"
                (drop)="onDrop($event)"
              >
                @if (progress(); as p) {
                  <div>
                    <label for="up-prog">{{ progressText(p) }}</label>
                    <progress
                      id="up-prog"
                      [max]="p.totalBytes || 1"
                      [value]="p.sentBytes"
                    ></progress>
                  </div>
                } @else {
                  <span>
                    Kéo thả tệp vào đây hoặc
                    <label for="file-in" class="link">chọn tệp</label>
                    @if (!elderly()) {
                      <span class="muted"> · hệ thống kiểm tra toàn vẹn tệp ngay khi tải lên</span>
                    }
                  </span>
                  <input
                    id="file-in"
                    type="file"
                    class="sr-only"
                    accept=".pdf,.docx,.xlsx,.jpg,.jpeg,.png"
                    (change)="onPick($event)"
                  />
                }
              </div>
              @if (file() && source() === 'VAN_BAN_HE_THONG') {
                <p class="muted small">
                  Tệp tải lên là phụ lục kèm văn bản; mỗi phiên bản giữ một tệp.
                </p>
              }
            }

            @if (file()?.scanStatus === 'NHIEM') {
              <p class="msg err" role="alert">
                Tệp "{{ file()!.fileName }}" không an toàn và đã được cách ly.
              </p>
            }
            @if (file()?.duplicateOf; as m) {
              <div class="msg err dup" role="alert">
                <span>
                  <span class="num" aria-hidden="true">3</span>
                  <strong>Cảnh báo trùng minh chứng:</strong> Tệp "{{ file()!.fileName }}" trùng mã
                  băm với minh chứng đã nộp cho <strong>{{ m.taskCode }}</strong> ({{ m.ownerName
                  }}{{ m.submittedAt ? ', ' + date(m.submittedAt) : '' }}). Vui lòng ghi giải trình;
                  minh chứng chưa được tính điểm cho tới khi người nghiệm thu xác nhận.
                </span>
              </div>
              <label for="reuse" class="lbl-row">
                Giải trình dùng lại minh chứng <span class="req" aria-hidden="true">*</span>
              </label>
              <textarea
                id="reuse"
                class="inp"
                rows="2"
                maxlength="1000"
                required
                [disabled]="!editable()"
                [attr.aria-invalid]="justificationShort()"
                aria-describedby="reuse-help"
                [ngModel]="justification()"
                (ngModelChange)="justification.set($event)"
              ></textarea>
              <p id="reuse-help" class="muted small">
                {{ justification().trim().length }}/1.000 ký tự, tối thiểu 20.
                @if (fieldErrors()['reuseJustification']; as fe) {
                  <span class="err-text">{{ fe }}</span>
                }
              </p>
            }
          </section>
        </div>

        <aside>
          <section class="box" aria-labelledby="ver-h">
            <h2 class="h" id="ver-h">
              <span class="num" aria-hidden="true">4</span> Các phiên bản
            </h2>
            <ol class="versions">
              @for (v of e.versions ?? []; track v.versionNo) {
                <li [class.cur]="v.versionNo === versionNo()">
                  <span class="dot" [class]="v.status" aria-hidden="true"></span>
                  <div>
                    <strong>Phiên bản {{ v.versionNo }}</strong>
                    {{ v.submittedAt ? '· ' + date(v.submittedAt) : '' }} ·
                    {{ statusLabel[v.status] }}{{ v.status === 'NHAP' ? ' (đang soạn)' : '' }}
                    @if (v.decisionNote) {
                      <div class="muted small">"{{ v.decisionNote }}"</div>
                    }
                  </div>
                </li>
              }
              @if (isNewVersion()) {
                <li class="cur">
                  <span class="dot NHAP" aria-hidden="true"></span>
                  <div>
                    <strong>Phiên bản {{ versionNo() }}</strong> · chưa lưu
                  </div>
                </li>
              }
            </ol>
            @if (!elderly()) {
              <p class="muted small">
                Phiên bản cũ không bị xoá. Mỗi phiên bản có trạng thái nghiệm thu riêng; trạng thái
                thực hiện của nhiệm vụ chỉ đổi khi đủ sản phẩm được nộp.
              </p>
            }
          </section>

          <section class="box" aria-labelledby="sep-h">
            <h2 class="h" id="sep-h">Trạng thái nhiệm vụ và minh chứng</h2>
            <dl class="meta">
              <dt>Thực hiện</dt>
              <dd>
                @if (task.value(); as t) {
                  <span class="chip st">{{ taskStatus()! }}</span>
                  @if (editable()) {
                    → {{ taskStatusLabel['CHO_NGHIEM_THU'] }} khi nộp đủ
                  }
                }
              </dd>
              <dt>Nghiệm thu</dt>
              <dd>
                <span class="chip st">{{ statusLabel[currentStatus()] }}</span>
                @if (editable()) {
                  → {{ statusLabel['DA_NOP'] }}
                }
              </dd>
            </dl>
          </section>

          @if (editable()) {
            <section class="box" aria-labelledby="att-h">
              <h2 class="h" id="att-h">Cam kết</h2>
              <label class="att">
                <input
                  type="checkbox"
                  [ngModel]="attested()"
                  (ngModelChange)="attested.set($event)"
                />
                <span>Tôi xác nhận minh chứng phản ánh đúng sản phẩm đã thực hiện.</span>
              </label>
            </section>
          }
        </aside>
      </div>
    } @else {
      <p class="muted" aria-busy="true">Đang tải minh chứng…</p>
    }
  `,
  styleUrl: './evidence-page.scss',
  styles: `
    .sources {
      border: 0;
      margin: 0 0 12px;
      padding: 0;
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
      gap: 10px;
    }
    .doc {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
      border: 1px solid var(--p-content-border-color, #d1d5db);
      border-radius: 6px;
      padding: 8px 12px;
    }
    .search {
      display: flex;
      gap: 8px;
    }
    .docs {
      list-style: none;
      margin: 8px 0 0;
      padding: 0;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 6px;
    }
    .pick {
      all: unset;
      box-sizing: border-box;
      display: block;
      width: 100%;
      padding: 8px 12px;
      cursor: pointer;
    }
    .pick:hover,
    .pick:focus-visible,
    .drop:focus-within .link {
      background: #f3f4f6;
      outline: 2px solid #1d4ed8;
    }
    .drop {
      margin: 12px 0;
      padding: 18px;
      border: 2px dashed #d1d5db;
      border-radius: 8px;
      text-align: center;
    }
    .drop.over {
      border-color: #c8102e;
      background: #fdf2f4;
    }
    .link {
      color: #b00d28;
      font-weight: 600;
      text-decoration: underline;
      cursor: pointer;
    }
    progress {
      width: 100%;
      height: 10px;
      accent-color: #c8102e;
    }
    .versions {
      list-style: none;
      margin: 0 0 8px;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .versions li {
      display: flex;
      gap: 10px;
      align-items: flex-start;
    }
    .dot {
      flex: none;
      width: 14px;
      height: 14px;
      border-radius: 50%;
      margin-top: 4px;
      background: #1d4ed8;
    }
    .dot.NHAP {
      background: #c8102e;
    }
    .dot.YEU_CAU_BO_SUNG {
      background: #d97706;
    }
    .dot.DA_XAC_NHAN {
      background: #15803d;
    }
  `,
})
export class EvidenceSubmitPage {
  private readonly api = inject(EvidenceApi);
  private readonly files = inject(FileApi);
  private readonly tasks = inject(TaskApi);
  private readonly theme = inject(ThemeService);
  protected readonly can = inject(EVIDENCE_CAN);
  private readonly pollMs = inject(EVIDENCE_SCAN_POLL_MS);

  /** Mã minh chứng từ tuyến `/evidence/:id`. */
  readonly id = input.required<string>();

  protected readonly statusLabel = EVIDENCE_STATUS_LABEL;
  protected readonly scanLabel = FILE_SCAN_LABEL;
  protected readonly taskStatusLabel = TASK_STATUS_LABEL;

  protected readonly evidence = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.get(params),
  });

  protected readonly task = rxResource({
    params: () => this.evidence.value()?.task.id,
    stream: ({ params }) => this.tasks.detail(params),
  });

  /** Phiên bản mới nhất đã có (đầy đủ trường), để soạn tiếp khi `NHAP` hoặc làm nền cho phiên bản n+1. */
  protected readonly latestRes = rxResource({
    params: () => {
      const e = this.evidence.value();
      // khoá dạng chuỗi: tải lại minh chứng mà phiên bản không đổi thì không nạp lại phiên bản
      return e?.currentVersionNo ? `${e.id}/${e.currentVersionNo}` : undefined;
    },
    stream: ({ params }) => {
      const [id, v] = params.split('/');
      return this.api.version(id, Number(v));
    },
  });

  protected readonly latest = computed(() => {
    const e = this.evidence.value();
    const v = this.latestRes.value();
    return e?.currentVersionNo && v?.versionNo === e.currentVersionNo ? v : null;
  });

  /** Chưa có phiên bản, hoặc phiên bản cuối bị yêu cầu bổ sung hay từ chối: soạn phiên bản n+1 chưa lưu. */
  protected readonly isNewVersion = computed(() => {
    const e = this.evidence.value();
    if (!e) return false;
    if (!e.currentVersionNo) return true;
    return !!e.currentStatus && REOPEN.includes(e.currentStatus);
  });

  protected readonly isLead = computed(() => this.task.value()?.role === 'CHU_TRI');

  /** Phiên bản đang soạn: đã lưu (`NHAP`) hoặc n+1 chưa lưu. Đã nộp thì chỉ đọc (BR-07-01). */
  protected readonly editable = computed(() => {
    const e = this.evidence.value();
    if (!e || !this.can('SCR-0701.soan') || !this.isLead()) return false;
    if (this.isNewVersion()) return true;
    const v = this.latest();
    return e.currentStatus === 'NHAP' && !!v && permittedOn(v, 'SCR-0701.soan');
  });

  /** Quyền nộp theo vai trò, cộng điều kiện máy chủ trả trên phiên bản nháp đã lưu (YC-PT-21). */
  protected readonly canSubmit = computed(
    () =>
      this.can('SCR-0701.nop') &&
      (this.isNewVersion() || permittedOn(this.latest(), 'SCR-0701.nop')),
  );

  protected readonly versionNo = computed(() => {
    const e = this.evidence.value();
    const n = e?.currentVersionNo ?? 0;
    return this.isNewVersion() ? n + 1 : n;
  });

  protected readonly currentStatus = computed<EvidenceVersionStatus>(() =>
    this.isNewVersion() ? 'NHAP' : (this.evidence.value()?.currentStatus ?? 'NHAP'),
  );

  /** Nội dung đang hiển thị: phiên bản đang soạn, hoặc phiên bản cuối khi chỉ đọc. */
  private readonly base = computed<EvidenceVersion | null>(() =>
    this.isNewVersion() ? null : this.latest(),
  );

  protected readonly source = linkedSignal<Source>(() => {
    const b = this.base();
    if (b) return b.source === 'VAN_BAN_HE_THONG' ? 'VAN_BAN_HE_THONG' : 'TAI_LEN';
    return this.evidence.value()?.sourceType === 'VAN_BAN' ? 'VAN_BAN_HE_THONG' : 'TAI_LEN';
  });
  protected readonly docRef = linkedSignal<string | null>(() => this.base()?.sourceDocRef ?? null);
  protected readonly justification = linkedSignal(() => this.base()?.reuseJustification ?? '');
  /** Cam kết giữ qua các lần lưu nháp của cùng phiên bản, bỏ chọn khi sang phiên bản khác. */
  protected readonly attested = linkedSignal(() => {
    this.versionNo();
    return false;
  });
  protected readonly file = linkedSignal<Attachment | null>(() => {
    const b = this.base();
    return b?.objectId && b.contentSha256
      ? {
          objectId: b.objectId,
          fileName: b.fileName ?? 'tệp',
          sizeBytes: b.sizeBytes ?? 0,
          sha256: b.contentSha256,
          scanStatus: b.scanStatus,
          duplicateOf: b.isDuplicateSuspect ? (b.duplicateOf ?? null) : null,
        }
      : null;
  });

  protected docInput = '';
  protected readonly docQuery = signal<string | null>(null);
  protected readonly docs = rxResource({
    params: () => this.docQuery() ?? undefined,
    stream: ({ params }) => this.api.externalDocs(params),
  });
  /** Văn bản đang chọn: tra trong danh sách vOffice (lần đầu nạp theo số văn bản của phiên bản). */
  private readonly docLookup = rxResource({
    params: () => this.docRef() ?? undefined,
    stream: ({ params }) => (params ? this.api.externalDocs('') : of([])),
  });
  protected readonly doc = computed<EvidenceExternalDoc | null>(() => {
    const ref = this.docRef();
    if (!ref || this.source() !== 'VAN_BAN_HE_THONG') return null;
    const found = [...(this.docs.value() ?? []), ...(this.docLookup.value() ?? [])].find(
      (d) => d.ref === ref,
    );
    const b = this.base();
    return (
      found ??
      (b?.sourceDocRef === ref
        ? {
            ref,
            docNumber: b.docNumber ?? ref,
            docDate: b.docDate ?? '',
            summary: b.docSummary ?? '',
          }
        : null)
    );
  });

  protected readonly busy = signal<Busy>(null);
  protected readonly progress = signal<UploadProgress | null>(null);
  protected readonly dragOver = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly fieldErrors = signal<Record<string, string>>({});
  protected readonly notices = signal<string[]>([]);

  protected readonly elderly = computed(() => this.theme.mode() === 'elderly');
  /** Trạng thái nhiệm vụ máy chủ trả khi nộp (EVT-06), trước khi đọc lại nhiệm vụ. */
  private readonly taskChanged = signal<TaskStatus | null>(null);
  protected readonly taskStatus = computed(() => {
    const s = this.taskChanged() ?? this.task.value()?.status;
    return s ? TASK_STATUS_LABEL[s] : null;
  });

  protected readonly justificationShort = computed(() => {
    const n = this.justification().trim().length;
    return !!this.file()?.duplicateOf && (n < 20 || n > 1000);
  });

  /** Điều kiện nộp hiển thị sẵn (máy chủ vẫn kiểm lại BR-07-02 … BR-07-04). */
  protected readonly blockers = computed(() => {
    const out: string[] = [];
    const f = this.file();
    if (this.source() === 'VAN_BAN_HE_THONG' ? !this.doc() : !f) {
      out.push(this.source() === 'VAN_BAN_HE_THONG' ? 'chọn văn bản' : 'tải tệp minh chứng');
    }
    if (f?.scanStatus === 'CHO_QUET') out.push('chờ quét mã độc xong');
    if (f && (f.scanStatus === 'NHIEM' || f.scanStatus === 'LOI'))
      out.push('gỡ tệp không an toàn và tải tệp khác');
    if (this.justificationShort()) out.push('ghi giải trình dùng lại từ 20 ký tự');
    if (!this.attested()) out.push('tích ô cam kết');
    return out;
  });

  protected readonly loadError = computed(() => {
    const p = toProblem(this.evidence.error());
    return p.status === 404 ? 'Không tìm thấy minh chứng.' : p.detail || p.title;
  });

  private scanTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopScan());
    // hỏi kết quả quét bất đồng bộ (SEC-47) tới khi có kết luận
    effect(() => {
      const f = this.file();
      this.stopScan();
      if (f?.scanStatus === 'CHO_QUET')
        this.scanTimer = setTimeout(() => this.pollScan(f), this.pollMs);
    });
  }

  protected date(iso?: string | null) {
    return fmtDate(iso);
  }

  protected size(n?: number | null) {
    return fmtSize(n);
  }

  protected shortHash(h: string) {
    return `${h.slice(0, 4)} ${h.slice(4, 8)} … ${h.slice(-4)}`;
  }

  protected progressText(p: UploadProgress) {
    if (p.phase === 'BAM') return 'Đang tính mã kiểm tra toàn vẹn của tệp…';
    if (p.phase === 'HOAN_TAT') return 'Hệ thống đang kiểm tra lại tính toàn vẹn của tệp…';
    const pct = p.totalBytes ? Math.round((p.sentBytes / p.totalBytes) * 100) : 0;
    return p.retrying
      ? `Mất kết nối, đang tải tiếp (${pct}%)…`
      : `Đang tải lên ${pct}% (${fmtSize(p.sentBytes)}/${fmtSize(p.totalBytes)})`;
  }

  protected onDragOver(ev: DragEvent) {
    ev.preventDefault();
    this.dragOver.set(true);
  }

  protected onDrop(ev: DragEvent) {
    ev.preventDefault();
    this.dragOver.set(false);
    const f = ev.dataTransfer?.files?.[0];
    if (f) void this.upload(f);
  }

  protected onPick(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const f = input.files?.[0];
    input.value = '';
    if (f) void this.upload(f);
  }

  protected removeFile() {
    this.file.set(null);
    this.justification.set('');
  }

  private async upload(f: File) {
    if (this.busy()) return;
    this.busy.set('upload');
    this.error.set(null);
    this.notices.set([]);
    try {
      const o = await this.files.upload(
        f,
        { ownerModule: 'product', ownerRefId: this.evidence.value()?.id },
        (p) => this.progress.set(p),
      );
      this.file.set({
        objectId: o.objectId,
        fileName: o.fileName ?? f.name,
        sizeBytes: o.sizeBytes,
        sha256: o.sha256,
        scanStatus: o.scanStatus,
        duplicateOf: o.dedup?.duplicate ? (o.dedup.matches?.[0] ?? null) : null,
      });
    } catch (e) {
      if (e instanceof FileRejectedError) this.error.set(e.message);
      else {
        const p = toProblem(e);
        this.error.set(
          p.code === 'FILE-422-HASH-MISMATCH'
            ? 'Mã băm kiểm tra ở máy chủ không khớp. Vui lòng tải lại tệp.'
            : p.detail || p.title,
        );
      }
    } finally {
      this.progress.set(null);
      this.busy.set(null);
    }
  }

  private stopScan() {
    if (this.scanTimer) clearTimeout(this.scanTimer);
    this.scanTimer = null;
  }

  private async pollScan(f: Attachment) {
    try {
      const r = await firstValueFrom(this.files.scan(f.objectId));
      if (this.file()?.objectId !== f.objectId) return;
      if (r.scanStatus === 'CHO_QUET') {
        this.scanTimer = setTimeout(() => this.pollScan(f), this.pollMs);
      } else this.file.set({ ...f, scanStatus: r.scanStatus });
    } catch {
      this.scanTimer = setTimeout(() => this.pollScan(f), this.pollMs * 2);
    }
  }

  private draft(): EvidenceVersionDraft {
    const f = this.file();
    const ref = this.source() === 'VAN_BAN_HE_THONG' ? this.docRef() : null;
    return {
      source: this.source(),
      ...(ref ? { sourceDocRef: ref } : {}),
      ...(f ? { objectId: f.objectId } : {}),
      ...(f?.duplicateOf && this.justification().trim()
        ? { reuseJustification: this.justification().trim() }
        : {}),
    };
  }

  /** Lưu phiên bản đang soạn: tạo n+1 hoặc ghi đè nháp kèm If-Match. */
  private async persist(): Promise<EvidenceVersion> {
    const e = this.evidence.value()!;
    const cur = this.latest();
    return this.isNewVersion() || !cur
      ? firstValueFrom(this.api.createVersion(e.id, this.draft()))
      : firstValueFrom(this.api.saveVersion(e.id, cur, this.draft()));
  }

  protected async save() {
    this.busy.set('save');
    this.reset();
    try {
      const v = await this.persist();
      this.reload(v);
      this.notices.set([`Đã lưu nháp phiên bản ${v.versionNo}.`]);
    } catch (err) {
      this.showError(err);
    } finally {
      this.busy.set(null);
    }
  }

  protected async submit() {
    if (!this.canSubmit() || this.blockers().length) return;
    this.busy.set('submit');
    this.reset();
    try {
      const v = await this.persist();
      const res = await firstValueFrom(this.api.submit(this.evidence.value()!.id, v));
      const msgs = [
        `Đã nộp phiên bản ${res.version.versionNo}.${res.reviewer ? ` Người nghiệm thu: đồng chí ${res.reviewer.name}.` : ''}`,
      ];
      if (res.taskStatusChanged === 'CHO_NGHIEM_THU') {
        msgs.push('Nhiệm vụ đã chuyển sang Chờ nghiệm thu.');
        this.taskChanged.set('CHO_NGHIEM_THU');
      }
      this.reload(res.version);
      this.task.reload();
      this.notices.set(msgs);
    } catch (err) {
      this.showError(err);
      this.evidence.reload();
    } finally {
      this.busy.set(null);
    }
  }

  private reset() {
    this.error.set(null);
    this.fieldErrors.set({});
    this.notices.set([]);
  }

  private reload(v: EvidenceVersion) {
    this.latestRes.set(v);
    this.evidence.reload();
  }

  private showError(err: unknown) {
    const p = toProblem(err);
    const fe: Record<string, string> = {};
    for (const x of p.errors ?? []) fe[x.field] = x.message;
    this.fieldErrors.set(fe);
    if (p.code === 'FILE-422-INFECTED' && this.file())
      this.file.set({ ...this.file()!, scanStatus: 'NHIEM' });
    this.error.set(
      p.status === 409 && p.code === 'PRODUCT-409-VERSION-CONFLICT'
        ? 'Minh chứng vừa được cập nhật ở nơi khác. Đồng chí tải lại trang rồi thao tác lại.'
        : [p.detail || p.title, ...Object.values(fe)].join(' '),
    );
  }
}
