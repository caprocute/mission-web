import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { CursorQuery, MAX_PAGE_LIMIT } from './paging';
import { jobLocation } from './job-tracker';
import {
  CellOption,
  OnboardingCheck,
  PolicyPackOption,
  TenantFlow,
  TenantIdp,
  TenantOnboarding,
  TenantOnboardingInput,
  TenantOnboardingPatch,
  UiPackOption,
} from './models/tenant-onboarding';
import {
  ConfigTenant,
  RegistryTenant,
  TenancyModel,
  TenantExportRequest,
  TenantPage,
  TenantStatus,
} from './models/tenant';
import { apiUrl } from './api-path';

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

  // ---- SCR-0102 Khởi tạo tenant (WEB-017) ----

  /** Bước 1: khai báo tenant, máy chủ tạo `registry.tenant` ở `KHOI_TAO` và sinh mã `ONB-yyyy-nnnn`. */
  create(body: TenantOnboardingInput): Observable<TenantOnboarding> {
    return this.http.post<TenantOnboarding>('/api/v1/config/tenants', body);
  }

  /** Hồ sơ khởi tạo để mở lại bản nháp theo mã tenant. */
  onboarding(id: string): Observable<TenantOnboarding> {
    return this.http.get<TenantOnboarding>(
      `/api/v1/config/tenants/${encodeURIComponent(id)}/onboarding`,
    );
  }

  /**
   * Lưu nháp (PATCH, `If-Match`; 409 khi người khác đã sửa). Hợp đồng `config.yaml` và SVC-03
   * chỉ nhận `application/merge-patch+json` (415 với kiểu khác).
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
    return this.http.delete<void>(`/api/v1/config/tenants/${encodeURIComponent(current.id)}`, {
      headers: { 'If-Match': `"${current.rowVersion}"` },
    });
  }

  /** Cell ứng viên cho mô hình và quy mô dự kiến (bước 2, vùng 3). */
  cellOptions(model: TenancyModel, accounts: number): Observable<CellOption[]> {
    return this.http
      .get<{ items: CellOption[] }>('/api/v1/config/tenants/cell-options', {
        params: { model, accounts },
      })
      .pipe(map((r) => r.items));
  }

  /** Tên miền còn dùng được không (MSG-01-04); `exceptTenant` bỏ qua tên miền của chính tenant. */
  domainAvailable(domain: string, exceptTenant?: string): Observable<boolean> {
    let params = new HttpParams().set('domain', domain);
    if (exceptTenant) params = params.set('tenant', exceptTenant);
    return this.http
      .get<{ available: boolean }>('/api/v1/config/tenants/domain-check', { params })
      .pipe(map((r) => r.available));
  }

  /** Policy Pack đã phát hành theo luồng (bước 3). */
  policyPacks(flow: TenantFlow): Observable<PolicyPackOption[]> {
    return this.http
      .get<{ items: PolicyPackOption[] }>('/api/v1/config/policy-packs', {
        params: { flow, status: 'DA_PHAT_HANH' },
      })
      .pipe(map((r) => r.items));
  }

  uiPacks(): Observable<UiPackOption[]> {
    return this.http
      .get<{ items: UiPackOption[] }>('/api/v1/config/ui-packs', {
        params: { status: 'DA_PHAT_HANH' },
      })
      .pipe(map((r) => r.items));
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

  /** Bước 4: thử luồng OIDC, metadata SAML hoặc bind LDAP; trả kết quả từng IdP. */
  testIdp(id: string, idps: TenantIdp[]): Observable<TenantIdp[]> {
    return this.http
      .post<{ items: TenantIdp[] }>(`/api/v1/config/tenants/${encodeURIComponent(id)}/idp/test`, {
        idps,
      })
      .pipe(map((r) => r.items));
  }

  /** Bước 6: kiểm tra tổng thể trước khi kích hoạt (QT-01 bước 9). */
  checks(id: string): Observable<OnboardingCheck[]> {
    return this.http
      .get<{ items: OnboardingCheck[] }>(`/api/v1/config/tenants/${encodeURIComponent(id)}/checks`)
      .pipe(map((r) => r.items));
  }

  /** Kích hoạt: `SAN_SANG` → `DANG_VAN_HANH` (MFA bước lên, EVT-21). */
  activate(current: TenantOnboarding): Observable<TenantOnboarding> {
    return this.http.post<TenantOnboarding>(
      `/api/v1/config/tenants/${encodeURIComponent(current.id)}/activate`,
      null,
      { headers: { 'If-Match': `"${current.rowVersion}"` } },
    );
  }
}
