import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { MAX_PAGE_LIMIT, apiUrl } from '@vt/api-client';
import {
  JobTitle,
  OrgAssignmentCreateInput,
  PasswordResetResult,
  PersonAssignment,
  UserAccount,
  UserAccountCreate,
  UserAccountCreated,
  UserAccountPage,
  UserAccountStatus,
  UserAccountUpdate,
} from './models/user-account';
import { OrgUnitAssignment } from './models/org-tree';

const ifMatch = (rowVersion: number | string) => ({ 'If-Match': `"${rowVersion}"` });

export interface UserAccountQuery {
  unit?: string;
  includeSubunits?: boolean;
  q?: string;
  status?: UserAccountStatus;
  cursor?: string;
}

/** Phân công kèm phiên bản đọc từ ETag, để kết thúc phân công gửi `If-Match` đúng. */
export interface VersionedAssignment {
  assignment: OrgUnitAssignment;
  etag: string | null;
}

/**
 * Client quản trị tài khoản người dùng `/api/v1/org/users`, chức danh `/org/job-titles` và phân công
 * `/org/assignments` của org-service (SCR-0108, SCR-0109, BS-04) theo
 * `contracts/openapi/modules/org.yaml`. Tạo, khoá, mở khoá và đặt lại mật khẩu cần phiên `aal2`;
 * interceptor của `@vt/auth` mở hộp MFA khi máy chủ trả 401 rồi gửi lại cùng `Idempotency-Key`.
 * Mật khẩu tạm chỉ có trong phản hồi tạo và đặt lại mật khẩu, không lưu ở đâu khác.
 */
@Injectable({ providedIn: 'root' })
export class UserAdminApi {
  private readonly http = inject(HttpClient);

  list(q: UserAccountQuery): Observable<UserAccountPage> {
    const params: Record<string, string | number | boolean> = { limit: MAX_PAGE_LIMIT };
    for (const [k, v] of Object.entries(q))
      if (v !== undefined && v !== '' && v !== false) params[k] = v as string | boolean;
    return this.http.get<UserAccountPage>(apiUrl('/org/users'), { params });
  }

  get(id: string): Observable<UserAccount> {
    return this.http.get<UserAccount>(apiUrl('/org/users/{id}', { id }));
  }

  create(body: UserAccountCreate): Observable<UserAccountCreated> {
    return this.http.post<UserAccountCreated>(apiUrl('/org/users'), body);
  }

  update(u: UserAccount, body: UserAccountUpdate): Observable<UserAccount> {
    return this.http.patch<UserAccount>(apiUrl('/org/users/{id}', { id: u.id }), body, {
      headers: ifMatch(u.rowVersion),
    });
  }

  lock(u: UserAccount, reason: string): Observable<UserAccount> {
    return this.http.post<UserAccount>(
      apiUrl('/org/users/{id}/lock', { id: u.id }),
      { reason },
      { headers: ifMatch(u.rowVersion) },
    );
  }

  unlock(u: UserAccount, reason: string): Observable<UserAccount> {
    return this.http.post<UserAccount>(
      apiUrl('/org/users/{id}/unlock', { id: u.id }),
      { reason },
      { headers: ifMatch(u.rowVersion) },
    );
  }

  resetPassword(u: UserAccount): Observable<PasswordResetResult> {
    return this.http.post<PasswordResetResult>(
      apiUrl('/org/users/{id}/reset-password', { id: u.id }),
      null,
    );
  }

  jobTitles(q?: string): Observable<JobTitle[]> {
    return this.http
      .get<{ items: JobTitle[] }>(apiUrl('/org/job-titles'), { params: q ? { q } : {} })
      .pipe(map((r) => r.items ?? []));
  }

  /** Phân công đang hiệu lực của một người (chính và kiêm nhiệm). */
  assignments(personId: string): Observable<PersonAssignment[]> {
    return this.http
      .get<{ items: PersonAssignment[] }>(apiUrl('/org/persons/{id}/assignments', { id: personId }))
      .pipe(map((r) => r.items ?? []));
  }

  addAssignment(body: OrgAssignmentCreateInput): Observable<VersionedAssignment> {
    return this.http
      .post<OrgUnitAssignment>(apiUrl('/org/assignments'), body, { observe: 'response' })
      .pipe(map(versioned));
  }

  /** Kết thúc phân công từ ngày `validTo` (không gồm ngày đó), `rowVersion` đọc từ danh sách phân công. */
  endAssignment(
    a: PersonAssignment,
    validTo: string,
    reason: string,
  ): Observable<VersionedAssignment> {
    return this.http
      .post<OrgUnitAssignment>(
        apiUrl('/org/assignments/{id}/end', { id: a.id }),
        { validTo, reason },
        { headers: ifMatch(a.rowVersion ?? 0), observe: 'response' },
      )
      .pipe(map(versioned));
  }
}

function versioned(r: HttpResponse<OrgUnitAssignment>): VersionedAssignment {
  return { assignment: r.body as OrgUnitAssignment, etag: r.headers.get('ETag') };
}
