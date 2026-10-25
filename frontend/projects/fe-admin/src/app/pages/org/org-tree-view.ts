import {
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  model,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OrgTimeQuery, OrgTreeApi, OrgTreeNode, OrgUnitSearchHit } from '@vt/api-client/domains';
import { InputTextModule } from 'primeng/inputtext';
import { Subscription, debounceTime, forkJoin, of, Subject, switchMap } from 'rxjs';
import { errorText } from '../../admin-ui';
import { VtSkeletonComponent } from '@vt/design-system';

interface Row {
  node: OrgTreeNode;
  level: number;
  setSize: number;
  posInSet: number;
}

/** `valid_to` là ngày đầu tiên hết hiệu lực; người dùng đọc "kết thúc" là ngày cuối còn hiệu lực. */
export function prevDay(iso: string): string {
  return new Date(Date.parse(`${iso}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10);
}

/**
 * Vùng 2 SCR-0103: cây đơn vị theo mẫu WAI-ARIA tree: mũi tên lên, xuống chuyển nút; phải mở hoặc
 * vào con; trái đóng hoặc về cha; Enter chọn. Ba tầng đầu đến từ `GET /org/units/tree`, tầng sâu hơn
 * nạp lười từng nhánh bằng `GET /org/units/{id}/subtree?depth=1` (hàng nghìn nút). Hợp đồng không
 * trả hiệu lực hay số phân công của nút: cây chỉ có đơn vị hiệu lực tại ngày xem.
 */
@Component({
  selector: 'vt-org-tree-view',
  imports: [VtSkeletonComponent, FormsModule, InputTextModule],
  template: `
    <label for="org-q" class="sr-only">Tìm đơn vị</label>
    <input
      id="org-q"
      pInputText
      type="search"
      class="full"
      placeholder="Tìm đơn vị…"
      [ngModel]="q()"
      (ngModelChange)="onSearch($event)"
    />
    @if (q().trim().length >= 2) {
      <ul class="results" aria-label="Kết quả tìm đơn vị">
        @for (m of matches(); track m.id) {
          <li>
            <button type="button" class="res" (click)="reveal(m)">
              <strong>{{ m.name }}</strong>
              <span class="muted small block">{{ path(m) || 'Đơn vị cấp cao nhất' }}</span>
            </button>
          </li>
        } @empty {
          <li class="muted small">{{ searching() ? 'Đang tìm…' : 'Không có đơn vị phù hợp.' }}</li>
        }
      </ul>
    }
    @if (error(); as e) {
      <p class="err" role="alert">{{ e }}</p>
    }
    @if (loading().has('') && !rows().length) {
      <vt-skeleton variant="tree" [header]="false" label="Đang tải cây tổ chức" />
    }
    <ul role="tree" aria-label="Cơ cấu tổ chức" class="tree">
      @for (r of rows(); track r.node.id) {
        <li
          role="treeitem"
          [id]="'tn-' + r.node.id"
          [attr.aria-level]="r.level"
          [attr.aria-setsize]="r.setSize"
          [attr.aria-posinset]="r.posInSet"
          [attr.aria-expanded]="r.node.hasChildren ? expanded().has(r.node.id) : null"
          [attr.aria-selected]="selected() === r.node.id"
          [attr.tabindex]="focusId() === r.node.id ? 0 : -1"
          [class.sel]="selected() === r.node.id"
          [style.padding-left.px]="(r.level - 1) * 18 + 4"
          (click)="onClick(r.node, $event)"
          (keydown)="onKey($event)"
          (focus)="focusId.set(r.node.id)"
        >
          <span class="tw" aria-hidden="true">{{
            r.node.hasChildren ? (expanded().has(r.node.id) ? '▾' : '▸') : ''
          }}</span>
          <span class="lbl">
            <span class="nm">{{ r.node.name }}</span>
            @if (loading().has(r.node.id)) {
              <i
                class="pi pi-spin pi-spinner muted small"
                role="img"
                aria-label="Đang mở nhánh"
              ></i>
            }
          </span>
        </li>
      }
    </ul>
  `,
  styles: `
    .full {
      width: 100%;
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
    .err {
      color: #b4001f;
    }
    .results {
      list-style: none;
      margin: 6px 0;
      padding: 4px;
      border: 1px solid #e5e7eb;
      border-radius: 6px;
      max-height: 240px;
      overflow: auto;
    }
    .res {
      width: 100%;
      text-align: left;
      background: none;
      border: 0;
      padding: 6px;
      font: inherit;
      cursor: pointer;
      border-radius: 4px;
    }
    .res:hover,
    .res:focus-visible {
      background: #f3f4f6;
    }
    .tree {
      list-style: none;
      margin: 12px 0 0;
      padding: 0;
      max-height: 640px;
      overflow: auto;
    }
    [role='treeitem'] {
      display: flex;
      align-items: flex-start;
      gap: 4px;
      padding: 6px 4px;
      border-radius: 6px;
      cursor: pointer;
      line-height: 1.35;
    }
    [role='treeitem']:hover {
      background: #f9fafb;
    }
    [role='treeitem']:focus-visible {
      outline: 2px solid #1e40af;
      outline-offset: -2px;
    }
    .sel {
      background: #fdf2f4;
      font-weight: 600;
    }
    .lbl {
      flex: 1;
      min-width: 0;
    }
    .tw {
      flex: none;
      width: 16px;
      color: #4b5563;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class OrgTreeView {
  private readonly api = inject(OrgTreeApi);
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly time = input<OrgTimeQuery>({});
  readonly simple = input(false);
  /** Đơn vị đang chọn (vùng 3–5 hiện theo đơn vị này). */
  readonly selected = model<string | null>(null);

  /** Con đã nạp theo đơn vị cha (`''` là gốc) và cha của từng nút (để về cha bằng phím trái). */
  protected readonly children = signal(new Map<string, OrgTreeNode[]>());
  private readonly parentOf = new Map<string, string | null>();
  protected readonly expanded = signal(new Set<string>());
  protected readonly loading = signal(new Set<string>());
  protected readonly error = signal<string | null>(null);
  protected readonly focusId = signal<string | null>(null);
  protected readonly q = signal('');
  protected readonly matches = signal<OrgUnitSearchHit[]>([]);
  protected readonly searching = signal(false);
  private readonly search$ = new Subject<string>();
  private loads = new Subscription();

  protected readonly rows = computed<Row[]>(() => {
    const out: Row[] = [];
    const kids = this.children();
    const open = this.expanded();
    const walk = (key: string, level: number) => {
      const list = kids.get(key) ?? [];
      list.forEach((node, i) => {
        out.push({ node, level, setSize: list.length, posInSet: i + 1 });
        if (open.has(node.id)) walk(node.id, level + 1);
      });
    };
    walk('', 1);
    return out;
  });

  constructor() {
    // đổi thời điểm xem: dựng lại cây từ gốc, giữ các nhánh đang mở
    effect(() => {
      const t = this.time();
      untracked(() => {
        this.loads.unsubscribe();
        this.loads = new Subscription();
        this.children.set(new Map());
        this.loading.set(new Set());
        this.loadRoot(t, true);
      });
    });
    this.search$
      .pipe(
        debounceTime(250),
        switchMap((q) => (q.trim().length >= 2 ? this.api.search(q.trim(), this.time()) : of([]))),
      )
      .subscribe({
        next: (m) => {
          this.matches.set(m);
          this.searching.set(false);
        },
        error: (e) => this.error.set(errorText(e, 'Không tìm được đơn vị.')),
      });
  }

  protected path(m: OrgUnitSearchHit) {
    return m.ancestors.map((a) => a.name).join(' / ');
  }

  /** Tải lại cây và các nhánh đang mở (sau khi sửa, thay đổi tổ chức, nhập tệp). */
  reload() {
    this.loadRoot(this.time());
  }

  /**
   * Ghi các nút (kèm các tầng con có sẵn trong phản hồi) vào bảng con theo cha; nút `hasChildren`
   * mà `children` rỗng là tầng chưa nạp, để mở thì gọi `/subtree`.
   */
  private ingest(map: Map<string, OrgTreeNode[]>, key: string, nodes: OrgTreeNode[]) {
    map.set(key, nodes);
    for (const n of nodes) {
      this.parentOf.set(n.id, key || null);
      if (n.hasChildren && n.children?.length) this.ingest(map, n.id, n.children);
      else if (!n.hasChildren) map.set(n.id, []);
    }
  }

  private setLoading(key: string, on: boolean) {
    this.loading.update((s) => {
      const n = new Set(s);
      if (on) n.add(key);
      else n.delete(key);
      return n;
    });
  }

  private loadRoot(t: OrgTimeQuery, first = false) {
    this.setLoading('', true);
    this.loads.add(
      this.api.tree(t).subscribe({
        next: (r) => {
          // thay toàn bộ cây đã nạp: nhánh sâu đang mở được nạp lại ngay dưới đây
          const fresh = new Map<string, OrgTreeNode[]>();
          this.parentOf.clear();
          this.ingest(fresh, '', r.roots);
          this.children.set(fresh);
          this.error.set(null);
          this.setLoading('', false);
          // mở sẵn cấp gốc, chọn gốc khi chưa chọn
          const root = r.roots[0];
          if (first && root && !this.expanded().size) this.expand(root.id);
          if (root && !this.selected()) this.selected.set(root.id);
          if (root && !this.focusId()) this.focusId.set(root.id);
          // nhánh đang mở nằm sâu hơn phần cây vừa nhận: nạp tiếp
          for (const id of this.expanded()) if (!this.children().has(id)) this.load(id, t);
        },
        error: (e) => {
          this.error.set(errorText(e, 'Không tải được cây tổ chức.'));
          this.setLoading('', false);
        },
      }),
    );
  }

  private load(id: string, t: OrgTimeQuery) {
    if (this.loading().has(id)) return;
    this.setLoading(id, true);
    this.loads.add(
      this.api.subtree(id, t).subscribe({
        next: (r) => {
          this.children.update((m) => {
            const n = new Map(m);
            this.ingest(n, id, r.roots[0]?.children ?? []);
            return n;
          });
          this.setLoading(id, false);
        },
        error: (e) => {
          this.error.set(errorText(e, 'Không tải được cây tổ chức.'));
          this.setLoading(id, false);
        },
      }),
    );
  }

  private expand(id: string) {
    this.expanded.update((s) => new Set(s).add(id));
    if (!this.children().has(id)) this.load(id, this.time());
  }

  private collapse(id: string) {
    this.expanded.update((s) => {
      const n = new Set(s);
      n.delete(id);
      return n;
    });
  }

  protected toggle(n: OrgTreeNode) {
    if (!n.hasChildren) return;
    if (this.expanded().has(n.id)) this.collapse(n.id);
    else this.expand(n.id);
  }

  /** Bấm vào mũi tên mở hoặc đóng nhánh, bấm vào tên chọn đơn vị. */
  protected onClick(n: OrgTreeNode, ev: MouseEvent) {
    if ((ev.target as HTMLElement).classList.contains('tw')) this.toggle(n);
    else this.choose(n.id);
  }

  protected choose(id: string) {
    this.selected.set(id);
    this.focusId.set(id);
  }

  protected onSearch(v: string) {
    this.q.set(v);
    this.searching.set(true);
    this.search$.next(v);
  }

  /** Mở chuỗi tổ tiên của kết quả tìm (nạp lười từng nhánh) rồi chọn đơn vị. */
  protected reveal(m: OrgUnitSearchHit) {
    const t = this.time();
    const ids = m.ancestors.map((a) => a.id);
    const missing = ids.filter((a) => !this.children().has(a));
    this.expanded.update((s) => new Set([...s, ...ids]));
    const done = () => {
      this.q.set('');
      this.matches.set([]);
      this.choose(m.id);
      this.focus(m.id);
    };
    if (!missing.length) return done();
    forkJoin(missing.map((a) => this.api.subtree(a, t))).subscribe({
      next: (res) => {
        this.children.update((map) => {
          const n = new Map(map);
          res.forEach((r, i) => this.ingest(n, missing[i], r.roots[0]?.children ?? []));
          return n;
        });
        done();
      },
      error: (e) => this.error.set(errorText(e, 'Không mở được nhánh.')),
    });
  }

  private focus(id: string) {
    this.focusId.set(id);
    setTimeout(() =>
      [...(this.host.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('[role=treeitem]')]
        .find((e) => e.id === `tn-${id}`)
        ?.focus(),
    );
  }

  protected onKey(ev: KeyboardEvent) {
    const rows = this.rows();
    const i = rows.findIndex((r) => r.node.id === this.focusId());
    if (i < 0) return;
    const r = rows[i];
    const go = (j: number) => rows[j] && this.focus(rows[j].node.id);
    switch (ev.key) {
      case 'ArrowDown':
        go(i + 1);
        break;
      case 'ArrowUp':
        go(i - 1);
        break;
      case 'Home':
        go(0);
        break;
      case 'End':
        go(rows.length - 1);
        break;
      case 'ArrowRight':
        if (r.node.hasChildren && !this.expanded().has(r.node.id)) this.expand(r.node.id);
        else if (rows[i + 1]?.level === r.level + 1) go(i + 1);
        break;
      case 'ArrowLeft':
        if (this.expanded().has(r.node.id)) this.collapse(r.node.id);
        else if (this.parentOf.get(r.node.id)) this.focus(this.parentOf.get(r.node.id)!);
        break;
      case 'Enter':
      case ' ':
        this.choose(r.node.id);
        break;
      default:
        return;
    }
    ev.preventDefault();
  }
}
