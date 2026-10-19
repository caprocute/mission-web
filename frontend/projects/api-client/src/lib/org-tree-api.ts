import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { jobLocation } from './job-tracker';
import {
  OrgChangeInput,
  OrgChangeResult,
  OrgHistoryEntry,
  OrgTreeResponse,
  OrgUnitDetail,
  OrgUnitMatch,
  OrgUnitUpdate,
  SubjectGroup,
  UnitAssignment,
} from './models/org-tree';

/** Hai trục thời gian khi xem: `asOf` (ngày hiệu lực) và `recordedAsOf` (đã ghi nhận đến). */
export interface OrgTimeQuery {
  asOf?: string;
  recordedAsOf?: string;
}

function timeParams(t: OrgTimeQuery, params = new HttpParams()): HttpParams {
  if (t.asOf) params = params.set('asOf', t.asOf);
  if (t.recordedAsOf) params = params.set('recordedAsOf', t.recordedAsOf);
  return params;
}

const unitPath = (id: string) => `/api/v1/org/units/${encodeURIComponent(id)}`;

/**
 * Client cây tổ chức có lịch sử `/api/v1/org/units/*` của SVC-04 (SCR-0103, QT-01 bước 6).
 * Không có `asOf` là chế độ "Hiện tại": máy chủ trả cả đơn vị đã kết thúc hiệu lực để hiện mờ.
 */
@Injectable({ providedIn: 'root' })
export class OrgTreeApi {
  private readonly http = inject(HttpClient);

  /** Một nhánh của cây: `parent` vắng là gốc (nạp lười theo nhánh, hàng nghìn nút). */
  tree(parentId: string | null, t: OrgTimeQuery = {}): Observable<OrgTreeResponse> {
    let params = timeParams(t);
    if (parentId) params = params.set('parent', parentId);
    return this.http.get<OrgTreeResponse>('/api/v1/org/units/tree', { params });
  }

  /** Tìm đơn vị theo tên hoặc mã, trả kèm tổ tiên để mở nhánh. */
  search(q: string, t: OrgTimeQuery = {}): Observable<OrgUnitMatch[]> {
    return this.http
      .get<{
        items: OrgUnitMatch[];
      }>('/api/v1/org/units/search', { params: timeParams(t).set('q', q).set('limit', 50) })
      .pipe(map((r) => r.items));
  }

  unit(id: string, t: OrgTimeQuery = {}): Observable<OrgUnitDetail> {
    return this.http.get<OrgUnitDetail>(unitPath(id), { params: timeParams(t) });
  }

  /** Lịch sử thay đổi, gồm thay đổi ghi muộn và thay đổi tương lai đã duyệt. */
  history(id: string, t: OrgTimeQuery = {}): Observable<OrgHistoryEntry[]> {
    return this.http
      .get<{ items: OrgHistoryEntry[] }>(`${unitPath(id)}/history`, { params: timeParams(t) })
      .pipe(map((r) => r.items));
  }

  /** Nhân sự và phân công còn hiệu lực hoặc sắp có hiệu lực ở đơn vị. */
  assignments(id: string, t: OrgTimeQuery = {}): Observable<UnitAssignment[]> {
    return this.http
      .get<{
        items: UnitAssignment[];
      }>(`${unitPath(id)}/assignments`, { params: timeParams(t).set('limit', 200) })
      .pipe(map((r) => r.items));
  }

  subjectGroups(): Observable<SubjectGroup[]> {
    return this.http
      .get<{ items: SubjectGroup[] }>('/api/v1/org/subject-groups')
      .pipe(map((r) => r.items));
  }

  /** Sửa: tạo phiên bản mới từ `validFrom` (PUT, `If-Match`; BR-01-06). */
  update(current: OrgUnitDetail, body: OrgUnitUpdate): Observable<OrgUnitDetail> {
    return this.http.put<OrgUnitDetail>(unitPath(current.id), body, {
      headers: { 'If-Match': `"${current.rowVersion}"` },
    });
  }

  /** Thay đổi tổ chức: thành lập, đổi tên, sáp nhập, hợp nhất, chia tách, giải thể (EVT-14, EVT-15). */
  change(id: string, body: OrgChangeInput): Observable<OrgChangeResult> {
    return this.http.post<OrgChangeResult>(`${unitPath(id)}/changes`, body);
  }

  /** Nhập cây tổ chức từ Excel: 202 + `Location` (theo dõi bằng `JobTracker`). */
  import(file: File): Observable<string> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http
      .post('/api/v1/org/imports', form, { observe: 'response' })
      .pipe(map((res: HttpResponse<unknown>) => jobLocation(res) ?? ''));
  }

  /** Tải báo cáo lỗi nhập (`resultLocation` của job). */
  report(resultLocation: string): Observable<Blob> {
    return this.http.get(resultLocation.replace(/^https?:\/\/[^/]+/, ''), {
      responseType: 'blob',
    });
  }
}
