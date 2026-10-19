import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-path';
import {
  AccessEventPage,
  Delegation,
  DelegationCreate,
  DelegationKind,
  DelegationPage,
  DelegationStatus,
  RoleBinding,
  RoleBindingCreate,
  RoleBindingPage,
  RoleBindingStatus,
  RoleCode,
} from './models/role-binding';
import { MAX_PAGE_LIMIT } from './paging';

const ifMatch = (rowVersion: number) => ({ 'If-Match': `"${rowVersion}"` });

export interface RoleBindingQuery {
  orgUnitId?: string;
  includeSubunits?: boolean;
  personId?: string;
  role?: RoleCode;
  status?: RoleBindingStatus;
  awaitingMe?: boolean;
  cursor?: string;
}

export interface DelegationQuery {
  orgUnitId?: string;
  personId?: string;
  kind?: DelegationKind;
  status?: DelegationStatus;
}

function params(q: object): Record<string, string | number | boolean> {
  const out: Record<string, string | number | boolean> = { limit: MAX_PAGE_LIMIT };
  for (const [k, v] of Object.entries(q))
    if (v !== undefined && v !== '' && v !== false) out[k] = v;
  return out;
}

/**
 * Client quản trị vai trò và uỷ quyền `/api/v1/config/role-bindings`, `/config/access-history`,
 * `/config/delegations` của MOD-08 (SCR-0105 đến SCR-0107, WEB-058) theo
 * `contracts/openapi/modules/config.yaml`. Thao tác ghi cần phiên `aal2` (BR-01-15): máy chủ trả 401
 * `insufficient_user_authentication` thì interceptor của `@vt/auth` mở hộp MFA rồi gửi lại cùng
 * `Idempotency-Key`. Thao tác trên bản ghi đã có gửi `If-Match` là `rowVersion`.
 */
@Injectable({ providedIn: 'root' })
export class RoleAdminApi {
  private readonly http = inject(HttpClient);

  bindings(q: RoleBindingQuery): Observable<RoleBindingPage> {
    return this.http.get<RoleBindingPage>(apiUrl('/config/role-bindings'), { params: params(q) });
  }

  grant(body: RoleBindingCreate): Observable<RoleBinding> {
    return this.http.post<RoleBinding>(apiUrl('/config/role-bindings'), body);
  }

  approve(b: RoleBinding, note?: string): Observable<RoleBinding> {
    return this.http.post<RoleBinding>(
      apiUrl('/config/role-bindings/{id}/approve', { id: b.id }),
      note ? { note } : {},
      { headers: ifMatch(b.rowVersion) },
    );
  }

  reject(b: RoleBinding, reason: string): Observable<RoleBinding> {
    return this.http.post<RoleBinding>(
      apiUrl('/config/role-bindings/{id}/reject', { id: b.id }),
      { reason },
      { headers: ifMatch(b.rowVersion) },
    );
  }

  revoke(b: RoleBinding, reason: string): Observable<RoleBinding> {
    return this.http.post<RoleBinding>(
      apiUrl('/config/role-bindings/{id}/revoke', { id: b.id }),
      { reason },
      { headers: ifMatch(b.rowVersion) },
    );
  }

  history(q: {
    personId?: string;
    orgUnitId?: string;
    cursor?: string;
  }): Observable<AccessEventPage> {
    return this.http.get<AccessEventPage>(apiUrl('/config/access-history'), { params: params(q) });
  }

  /** Xuất CSV lịch sử quyền của một người (step-up, BR-01-15). */
  exportHistory(personId: string): Observable<Blob> {
    return this.http.get(apiUrl('/config/access-history/export'), {
      params: { personId },
      responseType: 'blob',
    });
  }

  delegations(q: DelegationQuery): Observable<DelegationPage> {
    return this.http.get<DelegationPage>(apiUrl('/config/delegations'), { params: params(q) });
  }

  delegate(body: DelegationCreate): Observable<Delegation> {
    return this.http.post<Delegation>(apiUrl('/config/delegations'), body);
  }

  revokeDelegation(d: Delegation, reason: string): Observable<Delegation> {
    return this.http.post<Delegation>(
      apiUrl('/config/delegations/{id}/revoke', { id: d.id }),
      { reason },
      { headers: ifMatch(d.rowVersion) },
    );
  }
}
