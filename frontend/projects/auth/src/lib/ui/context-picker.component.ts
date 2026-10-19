import {
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  linkedSignal,
  signal,
  viewChild,
} from '@angular/core';
import { ASSIGNMENT_KIND_LABEL, Assignment, toProblem } from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { ROLE_LABEL } from '../messages';
import { SessionService, requiresAal2 } from '../session.service';
import { closeModal, openModal } from './modal';

/**
 * Hộp chọn phân công (đơn vị, vai trò) khi người dùng có từ hai phân công còn hiệu lực
 * (QT-00 bước 7, SCR-0001 vùng 5; SCR-0002 "Đổi phân công"). Phân công dùng lần trước được chọn
 * sẵn; phân công có vai trò AAL2 cần MFA bước lên trước khi dùng (BR-00-01, MSG-00-05).
 * Lựa chọn ghi bằng `PUT /api/v1/org/me/context` và nhớ trong phiên.
 */
@Component({
  selector: 'vt-context-picker',
  imports: [ButtonModule],
  template: `
    <dialog
      #dlg
      class="vt-modal"
      aria-labelledby="vt-ctx-title"
      aria-describedby="vt-ctx-desc"
      (cancel)="onCancel($event)"
    >
      <form method="dialog" (submit)="confirm($event)">
        <h2 id="vt-ctx-title">Chọn phân công làm việc</h2>
        <p id="vt-ctx-desc">
          Đồng chí có {{ session.assignments().length }} phân công còn hiệu lực. Chọn đơn vị và vai
          trò cho phiên làm việc này; có thể đổi lại trên thanh trên.
        </p>
        <fieldset>
          <legend class="sr-only">Phân công</legend>
          @for (a of session.assignments(); track a.id) {
            <label class="option" [class.selected]="selected() === a.id">
              <input
                type="radio"
                name="assignment"
                [value]="a.id"
                [checked]="selected() === a.id"
                (change)="selected.set(a.id)"
                [attr.aria-describedby]="'vt-ctx-' + a.id"
              />
              <span class="text">
                <strong>{{ a.orgUnitName }}</strong> · {{ a.jobTitleName }}
                <span class="meta" [id]="'vt-ctx-' + a.id">
                  {{ kindLabel(a) }} · {{ rolesLabel(a) }}
                  @if (needsMfa(a)) {
                    <span class="mfa"
                      ><i class="pi pi-lock" aria-hidden="true"></i>
                      {{ session.aal2Reason(a) }}</span
                    >
                  }
                </span>
              </span>
            </label>
          }
        </fieldset>
        @if (error(); as e) {
          <p class="error" role="alert">{{ e }}</p>
        }
        <div class="actions">
          @if (session.assignment()) {
            <p-button
              type="button"
              label="Giữ phân công hiện tại"
              severity="secondary"
              (onClick)="close()"
            />
          }
          <p-button
            type="submit"
            [label]="selectedNeedsMfa() ? 'Xác thực và vào làm việc' : 'Vào làm việc'"
            icon="pi pi-check"
            [loading]="saving()"
            [disabled]="!selected()"
          />
        </div>
      </form>
    </dialog>
  `,
  styleUrl: './modal.scss',
})
export class ContextPickerComponent {
  protected readonly session = inject(SessionService);
  private readonly dlg = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');

  protected readonly selected = linkedSignal(() =>
    this.session.contextStatus() === 'CAN_CHON'
      ? (this.session.assignment()?.id ?? this.session.preselectedAssignmentId())
      : null,
  );
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly selectedNeedsMfa = computed(() => {
    const a = this.session.assignments().find((x) => x.id === this.selected());
    return !!a && this.needsMfa(a);
  });

  constructor() {
    effect(() => {
      const d = this.dlg().nativeElement;
      if (this.session.contextStatus() === 'CAN_CHON') openModal(d);
      else closeModal(d);
    });
  }

  protected kindLabel(a: Assignment): string {
    return ASSIGNMENT_KIND_LABEL[a.kind];
  }
  protected rolesLabel(a: Assignment): string {
    return a.roles.map((r) => ROLE_LABEL[r] ?? r).join(', ');
  }
  protected needsMfa(a: Assignment): boolean {
    return requiresAal2(a) && !this.session.canUse(a);
  }

  protected async confirm(ev: Event): Promise<void> {
    ev.preventDefault();
    const id = this.selected();
    if (!id) return;
    this.saving.set(true);
    this.error.set(null);
    try {
      await this.session.selectAssignment(id);
    } catch (e) {
      const msg = e instanceof Error && e.message.startsWith('step-up') ? null : toProblem(e).title;
      this.error.set(msg ?? 'Chưa xác thực nhiều lớp nên chưa dùng được phân công này.');
    } finally {
      this.saving.set(false);
    }
  }

  /** Esc: đóng khi đã có phân công; lần đầu thì dùng phân công được chọn sẵn. */
  protected onCancel(ev: Event): void {
    ev.preventDefault();
    if (this.session.assignment()) this.close();
    else void this.confirm(ev);
  }

  protected close(): void {
    this.session.cancelSwitch();
  }
}
