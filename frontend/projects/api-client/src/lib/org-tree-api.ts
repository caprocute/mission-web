import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { JobAccepted, acceptedJob } from './job-tracker';
import { Job } from './models/job';
import {
  OrgChangeOrder,
  OrgChangeRequest,
  OrgTree,
  OrgUnitAssignment,
  OrgUnitDetail,
  OrgUnitInput,
  OrgUnitSearchHit,
  OrgUnitVersion,
  SubjectGroup,
} from './models/org-tree';
import { CursorPage, CursorQuery, MAX_PAGE_LIMIT } from './paging';

/**
 * Hai trục thời gian khi xem: `asOf` (ngày hiệu lực, `Date`) và `recordedAsOf` (đã ghi nhận đến,
 * `DateTime`). `recordedAsOf` nhận cả ngày `YYYY-MM-DD` từ ô chọn ngày: client đổi thành cuối ngày
 * đó theo giờ trình duyệt trước khi gửi, vì hợp đồng yêu cầu `date-time`.
 */
export interface OrgTimeQuery {
  asOf?: string;
  recordedAsOf?: string;
}

const DAY = /^\d{4}-\d{2}-\d{2}$/;

/** Ngày `YYYY-MM-DD` → thời điểm cuối ngày (giờ trình duyệt) dạng ISO 8601; `date-time` giữ nguyên. */
export function recordedAsOfParam(value: string): string {
  return DAY.test(value) ? new Date(`${value}T23:59:59.999`).toISOString() : value;
}

function timeParams(t: OrgTimeQuery, params = new HttpParams()): HttpParams {
  if (t.asOf) params = params.set('asOf', t.asOf);
  if (t.recordedAsOf) params = params.set('recordedAsOf', recordedAsOfParam(t.recordedAsOf));
  return params;
}

function pageParams(p: CursorQuery, params: HttpParams): HttpParams {
  params = params.set('limit', Math.min(p.limit ?? MAX_PAGE_LIMIT, MAX_PAGE_LIMIT));
  return p.cursor ? params.set('cursor', p.cursor) : params;
}

/** Một dòng lỗi của tệp nhập (`Job.problem.errors[]`, `field = rows.<số dòng>.<cột>`). */
export interface OrgImportError {
  /** Số dòng trong tệp; `null` khi lỗi chung của tệp. */
  row: number | null;
  message: string;
}

/** Lỗi từng dòng của job nhập Excel thất bại (máy chủ ghi ở `problem.errors`, DV-04-05). */
export function orgImportErrors(job: Job | null | undefined): OrgImportError[] {
  return (job?.problem?.errors ?? []).map((e) => {
    const m = /^rows\.(\d+)\./.exec(e.field);
    const row = m ? Number(m[1]) : null;
    return { row: row && row > 0 ? row : null, message: e.message };
  });
}

const unitPath = (id: string) => `/api/v1/org/units/${encodeURIComponent(id)}`;

/**
 * Client cây tổ chức có lịch sử `/api/v1/org/units/*` của SVC-04 (SCR-0103, QT-01 bước 6), theo
 * hợp đồng `contracts/openapi/modules/org.yaml`. Không có `asOf` là ngày hôm nay, không có
 * `recordedAsOf` là hiện tại.
 */
@Injectable({ providedIn: 'root' })
export class OrgTreeApi {
  private readonly http = inject(HttpClient);

  /** Cây từ gốc (`getOrgTree`): mặc định ba tầng; tầng sâu hơn nạp bằng {@link subtree}. */
  tree(t: OrgTimeQuery = {}, depth?: number): Observable<OrgTree> {
    let params = timeParams(t);
    if (depth) params = params.set('depth', depth);
    return this.http.get<OrgTree>('/api/v1/org/units/tree', { params });
  }

  /** Cây con của một đơn vị (`getOrgSubtree`): `roots[0]` là chính đơn vị, kèm `children`. */
  subtree(id: string, t: OrgTimeQuery = {}, depth = 1): Observable<OrgTree> {
    return this.http.get<OrgTree>(`${unitPath(id)}/subtree`, {
      params: timeParams(t).set('depth', depth),
    });
  }

  /** Tìm đơn vị theo tên hoặc mã (tối thiểu 2 ký tự), trả kèm tổ tiên để mở nhánh. */
  search(q: string, t: OrgTimeQuery = {}, limit = 50): Observable<OrgUnitSearchHit[]> {
    return this.http
      .get<{
        items: OrgUnitSearchHit[];
      }>('/api/v1/org/units/search', { params: timeParams(t).set('q', q).set('limit', limit) })
      .pipe(map((r) => r.items));
  }

  unit(id: string, t: OrgTimeQuery = {}): Observable<OrgUnitDetail> {
    return this.http.get<OrgUnitDetail>(unitPath(id), { params: timeParams(t) });
  }

  /**
   * Các phiên bản của đơn vị, mới nhất trước (`listOrgUnitHistory`). Hợp đồng chỉ nhận
   * `recordedAsOf`: lịch sử là toàn bộ trục hiệu lực nên không lọc theo `asOf`.
   */
  history(
    id: string,
    t: Pick<OrgTimeQuery, 'recordedAsOf'> = {},
    page: CursorQuery = {},
  ): Observable<CursorPage<OrgUnitVersion>> {
    const params = pageParams(page, timeParams({ recordedAsOf: t.recordedAsOf }));
    return this.http.get<CursorPage<OrgUnitVersion>>(`${unitPath(id)}/history`, { params });
  }

  /** Phân công thuộc đơn vị tại thời điểm xem (vùng 5). */
  assignments(
    id: string,
    t: OrgTimeQuery = {},
    page: CursorQuery = {},
  ): Observable<CursorPage<OrgUnitAssignment>> {
    return this.http.get<CursorPage<OrgUnitAssignment>>(`${unitPath(id)}/assignments`, {
      params: pageParams(page, timeParams(t)),
    });
  }

  subjectGroups(asOf?: string): Observable<SubjectGroup[]> {
    return this.http
      .get<{ items: SubjectGroup[] }>('/api/v1/org/subject-groups', {
        params: asOf ? { asOf } : {},
      })
      .pipe(map((r) => r.items));
  }

  /** Sửa: tạo phiên bản mới từ `validFrom` (PUT, `If-Match` theo `rowVersion`; BR-01-06, EVT-14). */
  update(
    current: Pick<OrgUnitDetail, 'id' | 'rowVersion'>,
    body: OrgUnitInput,
  ): Observable<OrgUnitDetail> {
    return this.http.put<OrgUnitDetail>(unitPath(current.id), body, {
      headers: { 'If-Match': `"${current.rowVersion}"` },
    });
  }

  /** Thay đổi tổ chức theo quyết định: thành lập, đổi tên, sáp nhập, hợp nhất, chia tách, giải thể. */
  change(id: string, body: OrgChangeRequest): Observable<OrgChangeOrder> {
    return this.http.post<OrgChangeOrder>(`${unitPath(id)}/changes`, body);
  }

  /**
   * Nhập cây tổ chức từ Excel: 202 + `Location: /api/v1/jobs/{id}`, theo dõi bằng `JobTracker`.
   * Lỗi từng dòng nằm ở `problem.errors` của job thất bại (xem {@link orgImportErrors}).
   */
  import(file: File, effectiveFrom?: string): Observable<JobAccepted> {
    const form = new FormData();
    form.append('file', file, file.name);
    if (effectiveFrom) form.append('effectiveFrom', effectiveFrom);
    return this.http
      .post<Job>('/api/v1/org/imports', form, { observe: 'response' })
      .pipe(map(acceptedJob));
  }
}
