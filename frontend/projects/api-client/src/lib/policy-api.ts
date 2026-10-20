import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { apiUrl } from './api-path';
import { Job } from './models/job';
import {
  DecisionTable,
  DecisionTableSaved,
  DecisionTableUpdate,
  GoldenTestCaseList,
  PolicyEvaluateResult,
  PolicyPack,
  PolicyPackList,
  PolicyPackSubmit,
  PolicyPackVersion,
  PolicyRule,
  PolicyRuleSaved,
  PolicyRuleUpdate,
  PolicyTestRunList,
  PolicyVersionDiff,
} from './models/policy';
import { ProspectCriteria } from './models/appraisal';

/** Tham số đường dẫn của một phiên bản gói. */
export interface PackVersionRef {
  id: string;
  v: string;
}

/** Bản ghi kèm ETag đọc từ phản hồi, để ghi lại bằng `If-Match`. */
export interface WithEtag<T> {
  value: T;
  etag: string;
}

const etagOf = (res: HttpResponse<unknown>, rowVersion: number) =>
  res.headers.get('ETag') ?? `"${rowVersion}"`;

/**
 * Client Policy Pack của MOD-05 (QT-15, SCR-1501, SCR-1502) theo `policies.yaml`. Máy chủ kiểm tra
 * kiểu CEL bằng cel-java và là nguồn sự thật; ghi kèm `Idempotency-Key` (interceptor tự thêm) và
 * `If-Match` theo ETag của bản ghi.
 */
@Injectable({ providedIn: 'root' })
export class PolicyApi {
  private readonly http = inject(HttpClient);

  packs(): Observable<PolicyPackList> {
    return this.http.get<PolicyPackList>(apiUrl('/policies/packs'), { params: { limit: 50 } });
  }

  /** Tiêu chí bốn nhóm triển vọng theo PL 5 của gói (ngăn "Xem tiêu chí" của SCR-1002). */
  prospectCriteria(id: string, version?: string): Observable<ProspectCriteria> {
    const params: Record<string, string> = version ? { version } : {};
    return this.http.get<ProspectCriteria>(
      apiUrl('/policies/packs/{id}/prospect-criteria', { id }),
      {
        params,
      },
    );
  }

  pack(id: string): Observable<PolicyPack> {
    return this.http.get<PolicyPack>(apiUrl('/policies/packs/{id}', { id }));
  }

  version(r: PackVersionRef): Observable<WithEtag<PolicyPackVersion>> {
    return this.http
      .get<PolicyPackVersion>(apiUrl('/policies/packs/{id}/versions/{v}', r), {
        observe: 'response',
      })
      .pipe(map((res) => ({ value: res.body!, etag: etagOf(res, res.body!.rowVersion) })));
  }

  diff(r: PackVersionRef, base: string): Observable<PolicyVersionDiff> {
    return this.http.get<PolicyVersionDiff>(apiUrl('/policies/packs/{id}/versions/{v}/diff', r), {
      params: { base },
    });
  }

  rules(r: PackVersionRef): Observable<PolicyRule[]> {
    return this.http
      .get<{ items: PolicyRule[] }>(apiUrl('/policies/packs/{id}/versions/{v}/rules', r))
      .pipe(map((b) => b.items));
  }

  /** Lưu nháp quy tắc; 422 `POLICY-422-CEL-TYPE` (MSG-15-01), `POLICY-422-GROUP-SUM` (MSG-15-02). */
  saveRule(
    r: PackVersionRef,
    rule: PolicyRule,
    body: PolicyRuleUpdate,
  ): Observable<PolicyRuleSaved> {
    return this.http.put<PolicyRuleSaved>(
      apiUrl('/policies/packs/{id}/versions/{v}/rules/{key}', { ...r, key: rule.ruleKey }),
      body,
      { headers: { 'If-Match': `"${rule.rowVersion}"` } },
    );
  }

  decisionTable(r: PackVersionRef, key: string): Observable<DecisionTable> {
    return this.http.get<DecisionTable>(
      apiUrl('/policies/packs/{id}/versions/{v}/decision-tables/{key}', { ...r, key }),
    );
  }

  /** Lưu nháp bảng quyết định; dòng bị che trả trong `warnings` (MSG-15-04). */
  saveDecisionTable(
    r: PackVersionRef,
    table: DecisionTable,
    body: DecisionTableUpdate,
  ): Observable<DecisionTableSaved> {
    return this.http.put<DecisionTableSaved>(
      apiUrl('/policies/packs/{id}/versions/{v}/decision-tables/{key}', {
        ...r,
        key: table.tableKey,
      }),
      body,
      { headers: { 'If-Match': `"${table.rowVersion}"` } },
    );
  }

  /** Tệp DMN 1.4 XML của bảng. */
  exportDmn(r: PackVersionRef, key: string): Observable<Blob> {
    return this.http.get(
      apiUrl('/policies/packs/{id}/versions/{v}/decision-tables/{key}/dmn', { ...r, key }),
      { responseType: 'blob' },
    );
  }

  /** Ca vàng đã duyệt của gói, dùng làm hồ sơ mẫu xem trước (SCR-1501 trường 10). */
  goldenCases(packId: string): Observable<GoldenTestCaseList> {
    return this.http.get<GoldenTestCaseList>(apiUrl('/policies/test-cases'), {
      params: { pack: packId, status: 'DA_DUYET', limit: 200 },
    });
  }

  /** Tính thử một ca vàng bằng cel-java và DMN trên máy chủ. */
  evaluate(r: PackVersionRef, caseCode: string): Observable<PolicyEvaluateResult> {
    return this.http.post<PolicyEvaluateResult>(
      apiUrl('/policies/packs/{id}/versions/{v}/evaluate', r),
      { caseCode },
    );
  }

  /** Chạy bộ test case vàng: 202, theo dõi qua danh sách lần chạy. */
  runTests(r: PackVersionRef): Observable<Job> {
    return this.http.post<Job>(apiUrl('/policies/packs/{id}/versions/{v}/test-runs', r), {});
  }

  testRuns(r: PackVersionRef, limit = 1): Observable<PolicyTestRunList> {
    return this.http.get<PolicyTestRunList>(
      apiUrl('/policies/packs/{id}/versions/{v}/test-runs', r),
      { params: { limit } },
    );
  }

  /** Gửi phê duyệt phát hành khi `DAT_KIEM_THU` (BR-15-07, BR-15-10). */
  submit(r: PackVersionRef, etag: string, body: PolicyPackSubmit): Observable<PolicyPackVersion> {
    return this.http.post<PolicyPackVersion>(
      apiUrl('/policies/packs/{id}/versions/{v}/submit', r),
      body,
      { headers: { 'If-Match': etag } },
    );
  }
}
