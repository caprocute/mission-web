import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import {
  ConnectionSettings,
  Edge,
  EdgeSelectChange,
  HtmlTemplateNode,
  Vflow,
  VflowComponent,
} from 'ngx-vflow';
import { KIND_LABEL } from './wf-model';
import {
  addNode,
  connect,
  connectError,
  moveNode,
  readGraph,
  readProps,
  removeElement,
  setProps,
  sizeOf,
  WF_PALETTE,
  WfNodeKind,
  WfProps,
} from './wf-graph';

interface NodeData {
  kind: string;
  w: number;
  h: number;
  name: string;
  label: string;
  bad: boolean;
}

const DRAG_TYPE = 'application/x-vt-wf-kind';
const SYMBOL: Partial<Record<string, string>> = {
  exclusiveGateway: '×',
  parallelGateway: '+',
  intermediateCatchEvent: '◷',
  boundaryEvent: '◷',
};

/**
 * Trình soạn kéo thả mẫu luồng (SCR-1505 hành động `soan`, QT-15 bước soạn). Nạp lười trong `@defer`
 * nên thư viện vẽ đồ thị không vào gói tải đầu. Mọi thao tác sửa thẳng tệp BPMN qua `wf-graph` và phát
 * `xmlChange`; trang cha giữ tệp, chạy bộ luật kiểm tra và truyền lại danh sách phần tử lỗi để tô đỏ.
 * Bàn phím: Tab tới từng bước, phím mũi tên để dời, Delete để xoá, bảng thuộc tính để nối và đổi tên.
 */
@Component({
  selector: 'vt-wf-flow-editor',
  imports: [Vflow],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ed">
      <div class="pal" role="toolbar" aria-label="Bảng phần tử" data-testid="palette">
        <p class="cap">Kéo vào sơ đồ hoặc bấm để thêm</p>
        @for (p of palette; track p.kind) {
          <button
            type="button"
            draggable="true"
            [attr.data-kind]="p.kind"
            [attr.data-testid]="'pal-' + p.kind"
            (dragstart)="dragStart($event, p.kind)"
            (click)="addAtEnd(p.kind)"
          >
            <span class="ico" [attr.data-kind]="p.kind" aria-hidden="true">{{
              symbol[p.kind]
            }}</span>
            {{ p.label }}
          </button>
        }
      </div>

      <div
        class="canvas"
        data-testid="flow-canvas"
        role="region"
        [attr.aria-label]="'Sơ đồ ' + name() + ', có thể kéo thả'"
        (dragover)="dragOver($event)"
        (drop)="drop($event)"
      >
        <vflow
          view="auto"
          [nodes]="nodes()"
          [edges]="edges()"
          [connection]="connection"
          [background]="{ type: 'dots', color: '#c8cdd4' }"
          [minZoom]="0.4"
          [maxZoom]="2"
          (connect)="onConnect($event.source, $event.target)"
          (nodeDragEnd)="onDragEnd($event.node.id)"
          (edgesChanges.select)="onEdgeSelect($event)"
        >
          <ng-template nodeHtml let-ctx>
            <div
              class="n"
              selectable
              tabindex="0"
              role="button"
              [attr.data-kind]="ctx.data().kind"
              [style.width.px]="ctx.data().w"
              [style.height.px]="ctx.data().h"
              [attr.data-testid]="'node-' + ctx.node.id"
              [class.bad]="ctx.data().bad"
              [class.sel]="selected() === ctx.node.id"
              [attr.aria-pressed]="selected() === ctx.node.id"
              [attr.aria-label]="ctx.data().label"
              [title]="ctx.data().label"
              (click)="selected.set(ctx.node.id)"
              (focus)="selected.set(ctx.node.id)"
              (keydown)="nodeKey($event, ctx.node.id)"
            >
              @if (ctx.data().kind !== 'startEvent' && ctx.data().kind !== 'boundaryEvent') {
                <handle type="target" position="left" />
              }
              <span class="lb">{{
                symbol[ctx.data().kind] ?? (isBox(ctx.data().kind) ? ctx.data().name : '')
              }}</span>
              @if (ctx.data().kind !== 'endEvent') {
                <handle
                  type="source"
                  [position]="ctx.data().kind === 'boundaryEvent' ? 'bottom' : 'right'"
                />
              }
            </div>
          </ng-template>
        </vflow>
      </div>

      <aside class="props" aria-label="Thuộc tính" data-testid="props">
        @if (props(); as p) {
          <h3>{{ kindLabel(p.kind) }}</h3>
          <div class="f">
            <label for="wf-p-name">Tên hiển thị</label>
            <input
              id="wf-p-name"
              data-testid="p-name"
              [value]="p.name"
              (change)="patch({ name: value($event) })"
            />
          </div>
          @if (p.kind === 'userTask') {
            <div class="f">
              <label for="wf-p-role">Nhóm người xử lý</label>
              <input id="wf-p-role" [value]="p.role" (change)="patch({ role: value($event) })" />
            </div>
          }
          @if (p.kind === 'serviceTask') {
            <div class="f">
              <label for="wf-p-topic">Hàng đợi việc của máy</label>
              <input id="wf-p-topic" [value]="p.topic" (change)="patch({ topic: value($event) })" />
            </div>
          }
          @if (p.kind === 'callActivity') {
            <div class="f">
              <label for="wf-p-called">Mẫu quy trình được gọi</label>
              <input
                id="wf-p-called"
                [value]="p.called"
                (change)="patch({ called: value($event) })"
              />
            </div>
          }
          @if (days(p.duration) !== null) {
            <div class="f">
              <label for="wf-p-days">Số ngày chờ</label>
              <input
                id="wf-p-days"
                type="number"
                min="1"
                max="365"
                [value]="days(p.duration)"
                (change)="patch({ duration: 'P' + value($event) + 'D' })"
              />
            </div>
          }
          @if (p.kind === 'sequenceFlow') {
            <div class="f">
              <label for="wf-p-cond">Điều kiện đi theo đường này</label>
              <input
                id="wf-p-cond"
                [value]="p.condition"
                placeholder="Để trống nếu luôn đi"
                (change)="patch({ condition: value($event) })"
              />
            </div>
          } @else if (targets().length) {
            <div class="f">
              <label for="wf-p-to">Nối tới bước</label>
              <div class="row">
                <select id="wf-p-to" #to data-testid="p-to">
                  @for (t of targets(); track t.id) {
                    <option [value]="t.id">{{ t.label }}</option>
                  }
                </select>
                <button
                  type="button"
                  data-testid="p-connect"
                  (click)="onConnect(selected()!, to.value)"
                >
                  Nối
                </button>
              </div>
            </div>
          }
          @if (p.kind !== 'startEvent') {
            <button type="button" class="del" data-testid="p-delete" (click)="remove(selected()!)">
              Xoá {{ p.kind === 'sequenceFlow' ? 'đường nối' : 'bước này' }}
            </button>
          }
        } @else {
          <p class="cap">
            Chọn một bước hoặc đường nối để sửa. Kéo từ chấm tròn bên phải một bước sang bước khác
            để nối hai bước.
          </p>
        }
        <p class="msg" role="status">{{ msg() }}</p>
      </aside>
    </div>
  `,
  styles: `
    .ed {
      display: grid;
      grid-template-columns: 1fr;
      gap: 0.5rem;
      container-type: inline-size;
    }
    .pal {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.35rem;
    }
    .pal .cap {
      flex-basis: 100%;
    }
    .props {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(14rem, 1fr));
      gap: 0.25rem 0.75rem;
      align-items: end;
      padding: 0.5rem;
      border: 1px solid var(--p-content-border-color, #cbd5e1);
      border-radius: 8px;
    }
    .props h3,
    .props .cap,
    .props .msg {
      grid-column: 1 / -1;
    }
    .f {
      display: flex;
      flex-direction: column;
      gap: 0.2rem;
    }
    .pal button,
    .props button {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      min-height: 2.25rem;
      padding: 0.3rem 0.5rem;
      border: 1px solid var(--p-content-border-color, #cbd5e1);
      border-radius: 6px;
      background: var(--p-content-background, #fff);
      color: inherit;
      font: inherit;
      text-align: left;
      cursor: grab;
    }
    .props button {
      cursor: pointer;
    }
    .pal button:focus-visible,
    .props button:focus-visible,
    .n:focus-visible {
      outline: 3px solid #1d4ed8;
      outline-offset: 2px;
    }
    .ico {
      display: inline-grid;
      place-items: center;
      width: 1.4rem;
      height: 1.1rem;
      border: 1.5px solid #334155;
      border-radius: 3px;
      font-size: 0.8rem;
    }
    .ico[data-kind='exclusiveGateway'],
    .ico[data-kind='parallelGateway'] {
      width: 1rem;
      height: 1rem;
      transform: rotate(45deg) scale(0.85);
    }
    .ico[data-kind='intermediateCatchEvent'],
    .ico[data-kind='endEvent'] {
      width: 1.1rem;
      border-radius: 50%;
    }
    .ico[data-kind='endEvent'] {
      border-width: 3px;
    }
    .cap {
      margin: 0 0 0.25rem;
      color: #475569;
      font-size: 0.85rem;
    }
    .canvas {
      height: 26rem;
      border: 1px solid var(--p-content-border-color, #cbd5e1);
      border-radius: 8px;
      overflow: hidden;
      background: #fafbfc;
    }
    .n {
      position: relative;
      display: grid;
      place-items: center;
      border: 2px solid #334155;
      border-radius: 10px;
      background: #fff;
      color: #0f172a;
      font-size: 0.8rem;
      line-height: 1.2;
      text-align: center;
      padding: 0.2rem;
      overflow: hidden;
    }
    .n[data-kind='serviceTask'] {
      background: #eef2ff;
    }
    .n[data-kind='callActivity'] {
      border-width: 4px;
    }
    .n[data-kind='startEvent'],
    .n[data-kind='endEvent'],
    .n[data-kind='intermediateCatchEvent'],
    .n[data-kind='boundaryEvent'] {
      border-radius: 50%;
      padding: 0;
    }
    .n[data-kind='startEvent'] {
      background: #ecfdf5;
    }
    .n[data-kind='endEvent'] {
      border-width: 4px;
    }
    .n[data-kind='exclusiveGateway'],
    .n[data-kind='parallelGateway'] {
      border: 0;
      background: transparent;
      font-size: 1.3rem;
      overflow: visible;
    }
    .n[data-kind='exclusiveGateway']::before,
    .n[data-kind='parallelGateway']::before {
      content: '';
      position: absolute;
      inset: 15%;
      border: 2px solid #334155;
      background: #fffbeb;
      transform: rotate(45deg);
    }
    .lb {
      position: relative;
      display: -webkit-box;
      -webkit-line-clamp: 3;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }
    .n.sel {
      box-shadow: 0 0 0 3px #93c5fd;
    }
    .n.bad {
      border-color: #b91c1c;
      background: #fef2f2;
    }
    .props label {
      font-size: 0.85rem;
      font-weight: 600;
    }
    .props input,
    .props select {
      min-height: 2.25rem;
      padding: 0.25rem 0.4rem;
      border: 1px solid #94a3b8;
      border-radius: 6px;
      font: inherit;
    }
    .props h3 {
      margin: 0;
      font-size: 1rem;
    }
    .row {
      display: flex;
      gap: 0.35rem;
    }
    .row select {
      flex: 1;
      min-width: 0;
    }
    .del {
      justify-self: start;
      color: #991b1b !important;
    }
    .msg {
      min-height: 1.2em;
      margin: 0.25rem 0 0;
      color: #991b1b;
      font-size: 0.85rem;
    }
    :host-context([data-mode='elderly']) .n {
      font-size: 0.95rem;
    }
  `,
})
export class WfFlowEditor {
  readonly xml = input.required<string>();
  /** `id` phần tử đang vi phạm bộ luật kiểm tra. */
  readonly issues = input<string[]>([]);
  readonly name = input('');
  readonly xmlChange = output<string>();

  protected readonly palette = WF_PALETTE;
  protected readonly symbol = SYMBOL;
  protected readonly selected = signal<string | null>(null);
  protected readonly msg = signal('');
  private readonly flow = viewChild(VflowComponent);

  protected readonly graph = computed(() => readGraph(this.xml()));

  constructor() {
    // Vừa khung một lần khi sơ đồ đã dựng, để thấy cả quy trình trước khi kéo thả.
    let fitted = false;
    effect(() => {
      const flow = this.flow();
      if (!flow?.initialized() || fitted) return;
      fitted = true;
      untracked(() => flow.fitView({ padding: 0.1 }));
    });
  }

  protected readonly nodes = computed<HtmlTemplateNode<NodeData>[]>(() => {
    const bad = new Set(this.issues());
    return (this.graph()?.nodes ?? []).map((n) => ({
      id: n.id,
      type: 'html-template',
      point: signal({ x: n.x, y: n.y }),
      width: signal(n.w),
      height: signal(n.h),
      draggable: signal(n.kind !== 'boundaryEvent'),
      data: signal({
        kind: n.kind,
        w: n.w,
        h: n.h,
        name: n.name,
        label: `${this.kindLabel(n.kind)}: ${n.name || 'chưa đặt tên'}${bad.has(n.id) ? ', có lỗi' : ''}`,
        bad: bad.has(n.id),
      }),
    }));
  });

  protected readonly edges = computed<Edge[]>(() =>
    (this.graph()?.flows ?? []).map((f) => ({
      id: f.id,
      source: f.source,
      target: f.target,
      curve: signal('smooth-step'),
      markers: signal({ end: { type: 'arrow-closed', color: '#334155' } }),
      edgeLabels: signal(f.name ? { center: { type: 'default', text: f.name } } : {}),
      selected: signal(this.selected() === f.id),
    })),
  );

  protected readonly props = computed(() => {
    const id = this.selected();
    return id ? readProps(this.xml(), id) : null;
  });

  /** Các bước có thể nối tới từ bước đang chọn (cho người dùng bàn phím). */
  protected readonly targets = computed(() => {
    const g = this.graph();
    const id = this.selected();
    if (!g || !id || !g.nodes.some((n) => n.id === id)) return [];
    return g.nodes
      .filter((t) => !connectError(g, id, t.id))
      .map((t) => ({ id: t.id, label: `${t.name || this.kindLabel(t.kind)}` }));
  });

  protected readonly connection: ConnectionSettings = {
    mode: 'strict',
    curve: 'smooth-step',
    validator: (c) => {
      const g = this.graph();
      return !!g && !connectError(g, c.source, c.target);
    },
  };

  protected kindLabel(kind: string): string {
    return kind === 'sequenceFlow' ? 'Đường nối' : (KIND_LABEL[kind] ?? 'Phần tử');
  }

  protected isBox(kind: string): boolean {
    return sizeOf(kind)[0] > 50;
  }

  protected days(duration: string): number | null {
    const m = /^P(\d+)D$/.exec(duration);
    return m ? Number(m[1]) : null;
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  private emit(next: string | null | undefined, fail = ''): boolean {
    if (!next) {
      if (fail) this.msg.set(fail);
      return false;
    }
    this.msg.set('');
    this.xmlChange.emit(next);
    return true;
  }

  protected dragStart(e: DragEvent, kind: WfNodeKind) {
    e.dataTransfer?.setData(DRAG_TYPE, kind);
    if (e.dataTransfer) e.dataTransfer.effectAllowed = 'copy';
  }

  protected dragOver(e: DragEvent) {
    if (e.dataTransfer?.types.includes(DRAG_TYPE)) e.preventDefault();
  }

  protected drop(e: DragEvent) {
    const kind = e.dataTransfer?.getData(DRAG_TYPE) as WfNodeKind | undefined;
    const flow = this.flow();
    if (!kind || !flow) return;
    e.preventDefault();
    const p = flow.documentPointToFlowPoint({ x: e.clientX, y: e.clientY });
    const [w, h] = sizeOf(kind);
    this.add(kind, p.x - w / 2, p.y - h / 2);
  }

  /** Bấm (hoặc Enter) trên bảng phần tử: đặt phần tử mới dưới sơ đồ hiện có rồi vừa khung lại. */
  protected addAtEnd(kind: WfNodeKind) {
    const nodes = this.graph()?.nodes ?? [];
    const bottom = nodes.reduce((m, n) => Math.max(m, n.y + n.h), 0);
    const left = nodes.reduce((m, n) => Math.min(m, n.x), Infinity);
    this.add(kind, Number.isFinite(left) ? left + 90 : 40, bottom + 50);
    setTimeout(() => this.flow()?.fitView({ padding: 0.1, duration: 200 }));
  }

  private add(kind: WfNodeKind, x: number, y: number) {
    const r = addNode(this.xml(), kind, x, y);
    if (this.emit(r?.xml, 'Không thêm được phần tử vào tệp sơ đồ này.')) this.selected.set(r!.id);
  }

  protected onConnect(source: string, target: string) {
    const g = this.graph();
    const err = g ? connectError(g, source, target) : 'Tệp sơ đồ không đọc được.';
    if (err) {
      this.msg.set(err);
      return;
    }
    const r = connect(this.xml(), source, target);
    if (this.emit(r?.xml)) this.selected.set(r!.id);
  }

  protected onDragEnd(id: string) {
    const p = this.flow()?.getNode(id)?.point();
    if (p) this.emit(moveNode(this.xml(), id, p.x, p.y));
  }

  protected onEdgeSelect(changes: EdgeSelectChange[]) {
    const on = changes.find((c) => c.selected);
    if (on) this.selected.set(on.id);
  }

  protected nodeKey(e: KeyboardEvent, id: string) {
    const step = e.shiftKey ? 50 : 10;
    const d: Record<string, [number, number]> = {
      ArrowLeft: [-step, 0],
      ArrowRight: [step, 0],
      ArrowUp: [0, -step],
      ArrowDown: [0, step],
    };
    if (d[e.key]) {
      const n = this.graph()?.nodes.find((x) => x.id === id);
      if (!n || n.kind === 'boundaryEvent') return;
      e.preventDefault();
      this.emit(
        moveNode(this.xml(), id, Math.max(0, n.x + d[e.key][0]), Math.max(0, n.y + d[e.key][1])),
      );
    } else if (e.key === 'Delete' || e.key === 'Backspace') {
      e.preventDefault();
      this.remove(id);
    }
  }

  protected remove(id: string) {
    if (readProps(this.xml(), id)?.kind === 'startEvent') {
      this.msg.set('Không xoá điểm bắt đầu của quy trình.');
      return;
    }
    if (this.emit(removeElement(this.xml(), id))) this.selected.set(null);
  }

  protected patch(p: Partial<WfProps>) {
    const id = this.selected();
    if (id) this.emit(setProps(this.xml(), id, p));
  }
}
