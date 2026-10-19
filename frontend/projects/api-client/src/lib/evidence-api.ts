import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { CursorPage } from './paging';
import {
  Deliverable,
  Evidence,
  EvidenceCreate,
  EvidenceExternalDoc,
  EvidenceSubmitResult,
  EvidenceVersion,
  EvidenceVersionDraft,
  EvidenceAcceptRequest,
  EvidenceDecisionResult,
  EvidenceReturnRequest,
  ScorePreview,
  ScorePreviewInput,
  EvidenceVersionStatus,
} from './models/evidence';

const enc = encodeURIComponent;

/**
 * Client cho `/api/v1/evidences` và `/api/v1/products` của MOD-03 (QT-07) theo hợp đồng
 * `contracts/openapi/modules/products.yaml`: nộp minh chứng SCR-0701 (WEB-032).
 */
@Injectable({ providedIn: 'root' })
export class EvidenceApi {
  private readonly http = inject(HttpClient);

  /** Danh sách minh chứng theo nhiệm vụ, của tôi (`submitter=me`) hoặc hàng chờ nghiệm thu. */
  list(filter: {
    task?: string;
    deliverable?: string;
    submitter?: 'me';
    reviewer?: 'me';
    status?: EvidenceVersionStatus[];
  }): Observable<CursorPage<Evidence>> {
    const params: Record<string, string> = {};
    for (const [k, v] of Object.entries(filter)) {
      if (v === undefined) continue;
      params[k] = Array.isArray(v) ? v.join(',') : v;
    }
    return this.http.get<CursorPage<Evidence>>('/api/v1/evidences', { params });
  }

  /** Sản phẩm của nhiệm vụ (`GET /products/{id}`), hoặc mọi sản phẩm của một nhiệm vụ. */
  deliverable(id: string): Observable<Deliverable> {
    return this.http.get<Deliverable>(`/api/v1/products/${enc(id)}`);
  }

  deliverablesOfTask(taskId: string): Observable<Deliverable[]> {
    return this.http
      .get<CursorPage<Deliverable>>('/api/v1/products', { params: { task: taskId } })
      .pipe(map((p) => p.items));
  }

  /** Tạo minh chứng (chưa có phiên bản) cho sản phẩm của nhiệm vụ mình chủ trì. */
  create(body: EvidenceCreate): Observable<Evidence> {
    return this.http.post<Evidence>('/api/v1/evidences', body);
  }

  /** "Chọn văn bản": văn bản đã ban hành của đơn vị trong vOffice (BR-07-13). */
  externalDocs(q: string): Observable<EvidenceExternalDoc[]> {
    return this.http
      .get<CursorPage<EvidenceExternalDoc>>('/api/v1/evidences/external-docs', { params: { q } })
      .pipe(map((p) => p.items));
  }

  /** Minh chứng kèm lịch sử phiên bản (vùng 4). */
  get(id: string): Observable<Evidence> {
    return this.http.get<Evidence>(`/api/v1/evidences/${enc(id)}`);
  }

  version(id: string, versionNo: number): Observable<EvidenceVersion> {
    return this.http.get<EvidenceVersion>(`/api/v1/evidences/${enc(id)}/versions/${versionNo}`);
  }

  /** "Lưu nháp" lần đầu hoặc nộp lại sau bổ sung: tạo phiên bản n+1 `NHAP`. */
  createVersion(id: string, draft: EvidenceVersionDraft): Observable<EvidenceVersion> {
    return this.http.post<EvidenceVersion>(`/api/v1/evidences/${enc(id)}/versions`, draft);
  }

  /** "Lưu nháp" phiên bản đang `NHAP` (kèm `If-Match`). */
  saveVersion(id: string, v: EvidenceVersion, draft: EvidenceVersionDraft) {
    return this.http.put<EvidenceVersion>(
      `/api/v1/evidences/${enc(id)}/versions/${v.versionNo}`,
      draft,
      { headers: { 'If-Match': `"${v.rowVersion}"` } },
    );
  }

  /** "Nộp để nghiệm thu": `NHAP` → `DA_NOP` (EVT-07); máy chủ kiểm BR-07-02, 03, 04, 09. */
  submit(id: string, v: EvidenceVersion): Observable<EvidenceSubmitResult> {
    return this.http.post<EvidenceSubmitResult>(
      `/api/v1/evidences/${enc(id)}/versions/${v.versionNo}/submit`,
      { attested: true },
      { headers: { 'If-Match': `"${v.rowVersion}"` } },
    );
  }

  // ── Nghiệm thu SCR-0703 ──

  /** Mở nghiệm thu: lần đầu `DA_NOP` → `DANG_NGHIEM_THU`; lần nào cũng kiểm toàn vẹn (MSG-07-13). */
  openReview(id: string, v: EvidenceVersion): Observable<EvidenceVersion> {
    return this.http.post<EvidenceVersion>(this.versionPath(id, v, 'open-review'), null, {
      headers: { 'If-Match': `"${v.rowVersion}"` },
    });
  }

  /** "Xác nhận nghiệm thu" (EVT-08); cần phiên AAL2 trong 15 phút (BR-07-11). */
  accept(
    id: string,
    v: EvidenceVersion,
    body: EvidenceAcceptRequest,
  ): Observable<EvidenceDecisionResult> {
    return this.http.post<EvidenceDecisionResult>(this.versionPath(id, v, 'accept'), body, {
      headers: { 'If-Match': `"${v.rowVersion}"` },
    });
  }

  requestRevision(id: string, v: EvidenceVersion, body: EvidenceReturnRequest) {
    return this.http.post<EvidenceDecisionResult>(
      this.versionPath(id, v, 'request-revision'),
      body,
      {
        headers: { 'If-Match': `"${v.rowVersion}"` },
      },
    );
  }

  reject(id: string, v: EvidenceVersion, body: EvidenceReturnRequest) {
    return this.http.post<EvidenceDecisionResult>(this.versionPath(id, v, 'reject'), body, {
      headers: { 'If-Match': `"${v.rowVersion}"` },
    });
  }

  /** Xem trước A, B, C, KPI theo Policy Pack của kỳ (SVC-07, không lưu). */
  scorePreview(body: ScorePreviewInput): Observable<ScorePreview> {
    return this.http.post<ScorePreview>('/api/v1/appraisals/score-preview', body);
  }

  private versionPath(id: string, v: EvidenceVersion, action: string) {
    return `/api/v1/evidences/${enc(id)}/versions/${v.versionNo}/${action}`;
  }
}
