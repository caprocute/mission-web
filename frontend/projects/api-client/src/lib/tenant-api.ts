import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of, switchMap } from 'rxjs';
import { CursorQuery, MAX_PAGE_LIMIT } from '@vt/api-client';
import { jobLocation } from '@vt/api-client';
import { Job } from '@vt/api-client';
import {
  CellOption,
  PolicyPackOption,
  PolicyPackWithVersions,
  RegistryCell,
  TenantFlow,
  TenantIdpTestRequest,
  TenantIdpTestResult,
  TenantOnboarding,
  TenantOnboardingInput,
  TenantOnboardingPatch,
  UiPackOption,
  cellOptions,
  policyPackOptions,
  registryModel,
} from './models/tenant-onboarding';
import {
  ConfigTenant,
  RegistryTenant,
  TenancyModel,
  TenantExportRequest,
  TenantPage,
  TenantStatus,
} from './models/tenant';
import { UiPackSummary } from '@vt/api-client';
import { apiUrl } from '@vt/api-client';

/** Lọc danh sách tenant (SCR-0101 vùng 1). */
export interface TenantQuery extends CursorQuery {
  q?: string;
  cell?: string;
  model?: TenancyModel;
  status?: TenantStatus;
}

function filterParams(query: Omit<TenantQuery, 'cursor' | 'limit'>): HttpParams {
  let params = new HttpParams();
  if (query.q) params = params.set('q', query.q);
  if (query.cell) params = params.set('cell', query.cell);
  if (query.model) params = params.set('model', query.model);
  if (query.status) params = params.set('status', query.status);
  return params;
}

/**
 * Client cho danh bạ tenant `/api/v1/config/tenants` của MOD-08 ở vùng điều phối toàn cục (QT-01,
 * SCR-0101) theo `config.yaml`, và bản ghi danh bạ `/api/v1/registry/tenants/{id}` của SVC-03 theo
 * `platform.yaml`; chỉ `platform_admin` trong phiên AAL2 được gọi.
 */
@Injectable({ providedIn: 'root' })
export class TenantApi {
  private readonly http = inject(HttpClient);

  /** Một trang tenant kèm `summary`: bốn chỉ số đầu trang và tải theo cell (trường 9). */
  list(query: TenantQuery = {}): Observable<TenantPage> {
    let params = filterParams(query).set('limit', Math.min(query.limit ?? 8, MAX_PAGE_LIMIT));
    if (query.cursor) params = params.set('cursor', query.cursor);
    return this.http.get<TenantPage>(apiUrl('/config/tenants'), { params });
  }

  /** Chi tiết tenant và cấu hình khởi tạo. */
  get(id: string): Observable<ConfigTenant> {
    return this.http.get<ConfigTenant>(apiUrl('/config/tenants/{id}', { id }));
  }

  /** Bản ghi danh bạ: cell hiện hành và các tên miền (`registry.tenant_domain`). */
  registry(id: string): Observable<RegistryTenant> {
    return this.http.get<RegistryTenant>(apiUrl('/registry/tenants/{id}', { id }));
  }

  /** Xuất danh sách theo bộ lọc hiện tại: 202 + `Location` của tác vụ (theo dõi bằng `JobTracker`). */
  export(body: TenantExportRequest): Observable<string> {
    return this.http
      .post(apiUrl('/config/tenants/export'), body, { observe: 'response' })
      .pipe(map((res: HttpResponse<unknown>) => jobLocation(res) ?? ''));
  }

  /** Tải tệp kết quả của tác vụ xuất (`resultLocation` khi job `HOAN_TAT`). */
  download(resultLocation: string): Observable<Blob> {
    return this.http.get(resultLocation.replace(/^https?:\/\/[^/]+/, ''), {
      responseType: 'blob',
    });
  }

  // ---- SCR-0102 Khởi tạo tenant (WEB-017, đối chiếu hợp đồng WEB-072) ----

  /**
   * Bước 1: khai báo tenant (`createConfigTenant`), máy chủ tạo `registry.tenant` ở `KHOI_TAO` và sinh
   * mã `ONB-yyyy-nnnn`; tên miền trùng trả 409 `CONFIG-409-DOMAIN-TAKEN` (MSG-01-04).
   */
  create(body: TenantOnboardingInput): Observable<TenantOnboarding> {
    return this.http.post<TenantOnboarding>(apiUrl('/config/tenants'), body);
  }

  /**
   * Lưu nháp (PATCH, `If-Match`; 409 khi người khác đã sửa). Hợp đồng `config.yaml` và SVC-03
   * chỉ nhận `application/merge-patch+json` (415 với kiểu khác) và từ chối trường lạ (422).
   */
  saveDraft(current: TenantOnboarding, patch: TenantOnboardingPatch): Observable<TenantOnboarding> {
    return this.http.patch<TenantOnboarding>(
      apiUrl('/config/tenants/{id}', { id: current.id }),
      patch,
      {
        headers: {
          'If-Match': `"${current.rowVersion}"`,
          'Content-Type': 'application/merge-patch+json',
        },
      },
    );
  }

  /** Huỷ yêu cầu khi còn `KHOI_TAO` (DELETE, `If-Match`). */
  cancel(current: TenantOnboarding): Observable<void> {
    return this.http.delete<void>(apiUrl('/config/tenants/{id}', { id: current.id }), {
      headers: { 'If-Match': `"${current.rowVersion}"` },
    });
  }

  /**
   * Cell ứng viên cho mô hình và quy mô dự kiến (bước 2, vùng 3): `GET /registry/cells?tenancyModel=`
   * của `platform.yaml`; khi cell chưa kèm số tài khoản thì lấy `summary.cellLoads` của danh sách
   * tenant. Tải sau khi thêm tính ở trình duyệt, máy chủ kiểm lại khi lưu (BR-01-02).
   */
  cellOptions(model: TenancyModel, accounts: number): Observable<CellOption[]> {
    const params = new HttpParams()
      .set('tenancyModel', registryModel(model))
      .set('limit', MAX_PAGE_LIMIT);
    return this.http
      .get<{ items: RegistryCell[] }>(apiUrl('/registry/cells'), { params })
      .pipe(
        switchMap(({ items }) =>
          items.every((c) => c.accountCount !== undefined)
            ? of(cellOptions(items, model, accounts))
            : this.list({ limit: 1 }).pipe(
                map((page) =>
                  cellOptions(
                    items,
                    model,
                    accounts,
                    Object.fromEntries(
                      (page.summary?.cellLoads ?? []).map((l) => [l.cellCode, l.accountCount]),
                    ),
                  ),
                ),
              ),
        ),
      );
  }

  /**
   * Phiên bản Policy Pack gốc toàn cục đã phát hành theo luồng (bước 3):
   * `GET /policies/packs?flow=&origin=GOC_TOAN_CUC` của `policies.yaml`.
   */
  policyPacks(flow: TenantFlow): Observable<PolicyPackOption[]> {
    const params = new HttpParams()
      .set('flow', flow)
      .set('origin', 'GOC_TOAN_CUC')
      .set('limit', MAX_PAGE_LIMIT);
    return this.http
      .get<{ items: PolicyPackWithVersions[] }>(apiUrl('/policies/packs'), { params })
      .pipe(map((r) => policyPackOptions(r.items).filter((o) => o.flow === flow)));
  }

  /** UI Pack có phiên bản đang phát hành (bước 3): `GET /ui/packs` của `config.yaml`. */
  uiPacks(): Observable<UiPackOption[]> {
    return this.http
      .get<{ items: UiPackSummary[] }>(apiUrl('/ui/packs'), {
        params: { limit: MAX_PAGE_LIMIT },
      })
      .pipe(
        map((r) =>
          r.items
            .filter((u) => !!u.currentVersionId)
            .map((u) => ({
              id: u.currentVersionId!,
              name: u.name,
              version: u.currentVersion ?? '',
            })),
        ),
      );
  }

  /**
   * Cuối bước 3: khởi chạy saga cấp phát, 202 + `Location: /api/v1/jobs/{id}` (theo dõi bằng
   * `JobTracker`). Hợp đồng bắt buộc `If-Match` (428 khi thiếu).
   */
  provision(current: TenantOnboarding): Observable<string> {
    return this.http
      .post(apiUrl('/config/tenants/{id}/provision', { id: current.id }), null, {
        observe: 'response',
        headers: { 'If-Match': `"${current.rowVersion}"` },
      })
      .pipe(map((res: HttpResponse<unknown>) => jobLocation(res) ?? ''));
  }

  /** Địa chỉ theo dõi job saga cấp phát gần nhất (`ConfigTenant.provisionJobId`). */
  provisionLocation(jobId: string): string {
    return apiUrl('/jobs/{id}', { id: jobId });
  }

  /** Trạng thái job saga cấp phát gần nhất, để hiện MSG-01-07 khi mở lại hồ sơ đã bù trừ. */
  provisionJob(jobId: string): Observable<Job> {
    return this.http.get<Job>(this.provisionLocation(jobId));
  }

  /**
   * Bước 4: thử luồng OIDC hoặc liên kết SAML của một IdP đã lưu theo `idpAlias`; máy chủ ghi kết quả
   * vào `TenantIdp.lastTestOk` để bước 6 kiểm "IdP hoạt động".
   */
  testIdp(id: string, idpAlias: string): Observable<TenantIdpTestResult> {
    const body: TenantIdpTestRequest = { idpAlias };
    return this.http.post<TenantIdpTestResult>(
      apiUrl('/config/tenants/{id}/idp/test', { id }),
      body,
    );
  }

  /** Kích hoạt: `SAN_SANG` → `DANG_VAN_HANH` (MFA bước lên, EVT-21); thân tuỳ chọn. */
  activate(current: TenantOnboarding): Observable<TenantOnboarding> {
    return this.http.post<TenantOnboarding>(
      apiUrl('/config/tenants/{id}/activate', { id: current.id }),
      null,
      { headers: { 'If-Match': `"${current.rowVersion}"` } },
    );
  }
}
