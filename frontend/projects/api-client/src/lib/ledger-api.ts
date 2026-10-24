import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { apiUrl } from '@vt/api-client';
import { Job } from '@vt/api-client';
import {
  LedgerEntry,
  LedgerEntryDetail,
  LedgerEntryFilter,
  LedgerExportInput,
  LedgerVerifyKind,
  LedgerVerifyRun,
  LedgerVerifyRunInput,
} from './models/ledger';
import { CursorPage, MAX_PAGE_LIMIT } from '@vt/api-client';

/** Tác vụ nền của sổ cái (202): địa chỉ theo dõi `/api/v1/jobs/{id}` và job ban đầu. */
export interface LedgerJobAccepted {
  location: string | null;
  job: Job;
}

const accepted = (res: HttpResponse<Job>): LedgerJobAccepted => ({
  location: res.headers.get('Location'),
  job: res.body!,
});

/**
 * Client cho sổ cái chỉ ghi thêm của SVC-09 (QT-13, SCR-1302) theo `contracts/openapi/modules/oversight.yaml`
 * (WEB-043): tra cứu bản ghi, xem chi tiết kèm niêm phong và bản neo, chạy kiểm tra toàn vẹn và xuất bằng
 * chứng (cả hai trả 202). Mọi lần tra cứu được máy chủ ghi sổ cái.
 */
@Injectable({ providedIn: 'root' })
export class LedgerApi {
  private readonly http = inject(HttpClient);

  /** "Tìm": một trang bản ghi theo bộ lọc; trường C2 đã được máy chủ che. */
  entries(filter: LedgerEntryFilter, cursor?: string | null): Observable<CursorPage<LedgerEntry>> {
    let params = new HttpParams().set('limit', MAX_PAGE_LIMIT);
    for (const [k, v] of Object.entries(filter))
      if (v !== undefined && v !== null && v !== '') params = params.set(k, v);
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<CursorPage<LedgerEntry>>(apiUrl('/ledger/entries'), { params });
  }

  entry(id: string): Observable<LedgerEntryDetail> {
    return this.http.get<LedgerEntryDetail>(apiUrl('/ledger/entries/{id}', { id }));
  }

  /** Các lần kiểm tra, gần nhất trước (SCR-1302 trường 6). */
  verifyRuns(verifyKind?: LedgerVerifyKind): Observable<LedgerVerifyRun[]> {
    let params = new HttpParams().set('limit', 20);
    if (verifyKind) params = params.set('verifyKind', verifyKind);
    return this.http
      .get<CursorPage<LedgerVerifyRun>>(apiUrl('/ledger/verify-runs'), { params })
      .pipe(map((p) => p.items));
  }

  verifyRun(id: string): Observable<LedgerVerifyRun> {
    return this.http.get<LedgerVerifyRun>(apiUrl('/ledger/verify-runs/{id}', { id }));
  }

  /** "Chạy kiểm tra toàn vẹn" (202); `job.resultLocation` trỏ tới lần kiểm tra. */
  startVerify(input: LedgerVerifyRunInput): Observable<LedgerJobAccepted> {
    return this.http
      .post<Job>(apiUrl('/ledger/verify-runs'), input, { observe: 'response' })
      .pipe(map(accepted));
  }

  /** "Xuất bằng chứng (JSON và chữ ký)" (202); `job.resultLocation` trỏ tới tệp tải về. */
  export(input: LedgerExportInput): Observable<LedgerJobAccepted> {
    return this.http
      .post<Job>(apiUrl('/ledger/exports'), input, { observe: 'response' })
      .pipe(map(accepted));
  }
}

/** Mã lần kiểm tra lấy từ `resultLocation` dạng `/api/v1/ledger/verify-runs/{id}`. */
export function verifyRunIdOf(job: Job): string | null {
  const m = /\/ledger\/verify-runs\/([^/?#]+)/.exec(job.resultLocation ?? '');
  return m ? decodeURIComponent(m[1]) : null;
}
