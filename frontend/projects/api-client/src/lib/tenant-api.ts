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
  CellLoad,
  TenancyModel,
  TenantDetail,
  TenantOverview,
  TenantPage,
  TenantStatus,
} from './models/tenant';

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
 * Client cho danh bạ tenant `/api/v1/config/tenants` của SVC-03 tenant-registry (QT-01, SCR-0101),
 * đọc qua MOD-08 ở vùng điều phối toàn cục; chỉ `platform_admin` trong phiên AAL2 được gọi.
 */
@Injectable({ providedIn: 'root' })
export class TenantApi {
  private readonly http = inject(HttpClient);

  /** Bốn thẻ tổng quan, tenant đang khởi tạo và việc cần xử lý. */
  overview(): Observable<TenantOverview> {
    return this.http.get<TenantOverview>('/api/v1/config/tenants/overview');
  }

  /** `GET /api/v1/config/tenants?cell=&model=&status=&cursor=`. */
  list(query: TenantQuery = {}): Observable<TenantPage> {
    let params = filterParams(query).set('limit', Math.min(query.limit ?? 8, MAX_PAGE_LIMIT));
    if (query.cursor) params = params.set('cursor', query.cursor);
    return this.http.get<TenantPage>('/api/v1/config/tenants', { params });
  }

  /** Tải theo cell (trường 9). */
  cells(): Observable<CellLoad[]> {
    return this.http
      .get<{ items: CellLoad[] }>('/api/v1/config/cells', { params: { limit: MAX_PAGE_LIMIT } })
      .pipe(map((r) => r.items));
  }

  get(id: string): Observable<TenantDetail> {
    return this.http.get<TenantDetail>(`/api/v1/config/tenants/${encodeURIComponent(id)}`);
  }

  /** Xuất danh sách theo bộ lọc hiện tại: 202 + `Location` của tác vụ (theo dõi bằng `JobTracker`). */
  export(query: Omit<TenantQuery, 'cursor' | 'limit'>): Observable<string> {
    return this.http
      .post('/api/v1/config/tenants/export', null, {
        params: filterParams(query),
        observe: 'response',
      })
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

  /** Lưu nháp (PATCH, `If-Match`; 409 khi người khác đã sửa). */
  saveDraft(current: TenantOnboarding, patch: TenantOnboardingPatch): Observable<TenantOnboarding> {
    return this.http.patch<TenantOnboarding>(
      `/api/v1/config/tenants/${encodeURIComponent(current.id)}`,
      patch,
      { headers: { 'If-Match': `"${current.rowVersion}"` } },
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

  /** Cuối bước 3: khởi chạy saga cấp phát, 202 + `Location` (theo dõi bằng `JobTracker`). */
  provision(id: string): Observable<string> {
    return this.http
      .post(`/api/v1/config/tenants/${encodeURIComponent(id)}/provision`, null, {
        observe: 'response',
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
