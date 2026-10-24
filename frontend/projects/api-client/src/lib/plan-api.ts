import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, map, of, switchMap } from 'rxjs';
import { apiUrl } from '@vt/api-client';
import { Job } from '@vt/api-client';
import { CursorPage, MAX_PAGE_LIMIT } from '@vt/api-client';
import {
  Plan,
  PlanCheck,
  PlanDiff,
  PlanItem,
  PlanItemInput,
  PlanItemRef,
  PlanRemovedItem,
  PlanReview,
  PlanSignRequest,
  PlanVersion,
  PlanVersionInput,
  PlanVersionStatus,
  PlanVersionSummary,
  PlanWorkspace,
  SignMethod,
} from './models/plan';

const ifMatch = (rowVersion: number) => ({ 'If-Match': `"${rowVersion}"` });

/**
 * Bỏ trường chỉ đọc của dòng (`lineNo`, `catalogItemCode`, `pendingScore`) trước khi ghi; dòng tạm của
 * màn hình (`id` bắt đầu bằng `tmp-`) gửi không kèm `id` để máy chủ cấp mã.
 */
export function toPlanItemInput(i: PlanItem | PlanItemInput): PlanItemInput {
  const { lineNo: _n, catalogItemCode: _c, pendingScore: _p, ...rest } = i as PlanItem;
  if (rest.id?.startsWith('tmp-')) delete rest.id;
  return rest;
}

function input(items: PlanItem[], removedItems?: PlanRemovedItem[]): PlanVersionInput {
  const body: PlanVersionInput = { items: items.map(toPlanItemInput) };
  if (removedItems?.length) body.removedItems = removedItems;
  return body;
}

/**
 * Client cho `/api/v1/plans` của MOD-02 (QT-04), theo hợp đồng `contracts/openapi/modules/tasks.yaml`
 * (WEB-065): dòng kế hoạch đã duyệt cho SCR-0501 (WEB-028), lập kế hoạch quý SCR-0402 (WEB-026),
 * phê duyệt SCR-0403 (WEB-027).
 */
@Injectable({ providedIn: 'root' })
export class PlanApi {
  private readonly http = inject(HttpClient);

  /** Dòng của phiên bản kế hoạch `DA_DUYET` hiệu lực trong kỳ, chưa sinh nhiệm vụ đã giao. */
  approvedItems(periodId: string): Observable<CursorPage<PlanItemRef>> {
    const params = new HttpParams()
      .set('period', periodId)
      .set('status', 'DA_DUYET')
      .set('limit', MAX_PAGE_LIMIT);
    return this.http.get<CursorPage<PlanItemRef>>(apiUrl('/plans/items'), { params });
  }

  /** Kế hoạch trong phạm vi người dùng (máy chủ lọc theo ABAC), kỳ mới nhất trước. */
  list(query: { period?: string; unit?: string; status?: PlanVersionStatus } = {}) {
    const params = Object.fromEntries(Object.entries(query).filter(([, v]) => !!v)) as Record<
      string,
      string
    >;
    return this.http.get<CursorPage<Plan>>(apiUrl('/plans'), { params }).pipe(map((p) => p.items));
  }

  /**
   * "Lập kế hoạch": tạo kế hoạch của đơn vị cho kỳ kèm phiên bản 1 `NHAP` (`POST /plans`, `createPlan`);
   * bỏ trống đơn vị thì máy chủ lấy đơn vị của phân công đang chọn. `Idempotency-Key` do interceptor gắn.
   */
  create(periodId: string, unitId?: string): Observable<Plan> {
    return this.http.post<Plan>(apiUrl('/plans'), unitId ? { periodId, unitId } : { periodId });
  }

  /** Phiên bản của kế hoạch, xếp tăng dần theo số phiên bản (hợp đồng trả mới nhất trước). */
  versions(planId: string): Observable<PlanVersionSummary[]> {
    return this.http
      .get<{ items: PlanVersionSummary[] }>(apiUrl('/plans/{id}/versions', { id: planId }))
      .pipe(map((p) => [...p.items].sort((a, b) => a.versionNo - b.versionNo)));
  }

  /** Một phiên bản kèm các dòng và kết quả kiểm tra; ETag là `rowVersion`. */
  version(planId: string, versionNo: number): Observable<PlanVersion> {
    return this.http.get<PlanVersion>(
      apiUrl('/plans/{id}/versions/{v}', { id: planId, v: String(versionNo) }),
    );
  }

  /** SCR-0402: kế hoạch, danh sách phiên bản và phiên bản `v` (mặc định phiên bản mới nhất). */
  workspace(plan: Plan, versionNo?: number): Observable<PlanWorkspace> {
    return forkJoin({
      versions: this.versions(plan.id),
      version: this.version(plan.id, versionNo ?? plan.currentVersion.versionNo),
    }).pipe(map((x) => ({ plan, ...x })));
  }

  /**
   * Kiểm tra tức thì tập dòng đang soạn, kể cả khi chưa lưu (`POST /plans/{id}/versions/{v}/check`,
   * BR-04-01..BR-04-10): chỉ trả vi phạm. Ở bản điều chỉnh, gửi kèm dòng bị bỏ và lý do.
   */
  check(
    planId: string,
    versionNo: number,
    items: PlanItem[],
    removedItems?: PlanRemovedItem[],
  ): Observable<PlanCheck[]> {
    return this.http
      .post<{ checks: PlanCheck[] }>(
        apiUrl('/plans/{id}/versions/{v}/check', { id: planId, v: String(versionNo) }),
        input(items, removedItems),
      )
      .pipe(map((r) => r.checks));
  }

  /** "Lưu nháp": `PUT /plans/{id}/versions/{v}` kèm `If-Match`; bản điều chỉnh gửi cả dòng bị bỏ. */
  save(
    planId: string,
    version: PlanVersion,
    items: PlanItem[],
    removedItems?: PlanRemovedItem[],
  ): Observable<PlanVersion> {
    const body = input(items, removedItems);
    return this.http.put<PlanVersion>(
      apiUrl('/plans/{id}/versions/{v}', { id: planId, v: String(version.versionNo) }),
      body,
      { headers: ifMatch(version.rowVersion) },
    );
  }

  /** "Sao chép từ quý trước": dòng thường xuyên và dòng chưa hoàn thành, hạn dịch theo kỳ. */
  copyFromPrevious(planId: string, version: PlanVersion): Observable<PlanVersion> {
    return this.http.post<PlanVersion>(
      apiUrl('/plans/{id}/versions/{v}/copy-from', {
        id: planId,
        v: String(version.versionNo),
      }),
      {},
      { headers: ifMatch(version.rowVersion) },
    );
  }

  /** "Gợi ý từ OKR": một dòng đề xuất cho mỗi KR chưa có nhiệm vụ (BR-04-07). */
  suggestions(planId: string): Observable<PlanItemInput[]> {
    return this.http
      .get<{ items: PlanItemInput[] }>(apiUrl('/plans/{id}/suggestions', { id: planId }))
      .pipe(map((p) => p.items));
  }

  /** "Gửi duyệt": kiểm tra lại, khoá, tính mã băm, chuyển `CHO_DUYET` (Idempotency-Key do interceptor gắn). */
  submit(planId: string, version: PlanVersion): Observable<PlanVersion> {
    return this.http.post<PlanVersion>(
      apiUrl('/plans/{id}/versions/{v}/submit', { id: planId, v: String(version.versionNo) }),
      null,
      { headers: ifMatch(version.rowVersion) },
    );
  }

  /**
   * "Tạo điều chỉnh": phiên bản n+1 từ phiên bản đã duyệt, kèm lý do chung của lần điều chỉnh
   * (`task.plan_version.change_reason`, BR-04-10); lý do từng dòng ghi khi soạn.
   */
  createAdjustment(planId: string, changeReason: string): Observable<PlanVersion> {
    return this.http.post<PlanVersion>(apiUrl('/plans/{id}/versions', { id: planId }), {
      changeReason,
    });
  }

  /**
   * "Chỉ hiện thay đổi, Toàn bộ": so sánh phiên bản `v` với `base`; phiên bản đầu so với kế hoạch rỗng
   * (`base=0`, mọi dòng là `THEM`).
   */
  diff(planId: string, version: PlanVersion, base?: number): Observable<PlanDiff> {
    return this.http.get<PlanDiff>(
      apiUrl('/plans/{id}/versions/{v}/diff', { id: planId, v: String(version.versionNo) }),
      { params: { base: base ?? 0, mode: 'all' } },
    );
  }

  /**
   * SCR-0403: phiên bản `v` (mặc định mới nhất), so với phiên bản đã duyệt gần nhất trước nó, hoặc
   * với `base` nếu chỉ định.
   */
  review(planId: string, versionNo?: number, baseNo?: number): Observable<PlanReview> {
    return this.versions(planId).pipe(
      switchMap((list) => {
        const no = versionNo || list[list.length - 1]?.versionNo || 1;
        const base = baseNo
          ? list.find((x) => x.versionNo === baseNo && x.versionNo !== no)
          : [...list].reverse().find((x) => x.versionNo < no && x.status === 'DA_DUYET');
        return forkJoin({
          version: this.version(planId, no),
          baseVersion: base ? this.version(planId, base.versionNo) : of(undefined),
        }).pipe(
          switchMap(({ version, baseVersion }) =>
            this.diff(planId, version, base?.versionNo).pipe(
              map((diff): PlanReview => ({
                planId,
                version,
                summary: list.find((x) => x.versionNo === no),
                base,
                baseGroupWeights: baseVersion?.groupWeights,
                diff,
              })),
            ),
          ),
        );
      }),
    );
  }

  /** "Trả lại": chuyển `TRA_LAI` kèm ý kiến bắt buộc (SCR-0403 trường 6, EVT-21). */
  returnPlan(planId: string, versionNo: number, rowVersion: number, comment: string) {
    return this.http.post<PlanVersion>(
      apiUrl('/plans/{id}/versions/{v}/return', { id: planId, v: String(versionNo) }),
      { comment },
      { headers: ifMatch(rowVersion) },
    );
  }

  /**
   * "Duyệt và ký số": máy chủ kiểm BR-04-08 (403), MFA trong 15 phút (401
   * `insufficient_user_authentication`, RFC 9470), tạo yêu cầu ký và trả 202 + `Location` của job;
   * phiên bản chỉ thành `DA_DUYET` khi ký xong (EVT-18 rồi EVT-03). Mã yêu cầu ký đọc ở
   * `PlanVersion.signRequestId`.
   */
  approve(
    planId: string,
    versionNo: number,
    rowVersion: number,
    body: { signMethod: SignMethod; comment?: string },
  ): Observable<{ location: string | null; job: Job }> {
    return this.http
      .post<Job>(
        apiUrl('/plans/{id}/versions/{v}/approve', { id: planId, v: String(versionNo) }),
        body,
        { headers: ifMatch(rowVersion), observe: 'response' },
      )
      .pipe(
        map((res: HttpResponse<Job>) => ({
          location: res.headers.get('Location'),
          job: res.body!,
        })),
      );
  }

  /** Trạng thái yêu cầu ký (`GET /esign/requests/{id}`, esign-service). */
  signRequest(id: string): Observable<PlanSignRequest> {
    return this.http.get<PlanSignRequest>(apiUrl('/esign/requests/{id}', { id }));
  }
}
