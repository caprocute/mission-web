import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { apiUrl } from '@vt/api-client';
import { JobAccepted, acceptedJob } from '@vt/api-client';
import { Job } from '@vt/api-client';
import {
  DecisionTable,
  DecisionTableSaved,
  DecisionTableUpdate,
  GoldenTestCase,
  GoldenTestCaseCreate,
  GoldenTestCaseList,
  PolicyDecisionReason,
  PolicyPackApprove,
  PolicyTestRun,
  PolicyTestRunCreate,
  PolicyWorkflowTemplateList,
  PolicyWorkflowTemplateVersion,
  WorkflowBinding,
  WorkflowBindingUpdate,
  WorkflowSimulateRequest,
  WorkflowSimulateResult,
  PolicyEvaluateResult,
  PolicyPack,
  PolicyPackList,
  PolicyPackSubmit,
  PolicyPackVersion,
  PolicyPackVersionCreate,
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
 * Client Policy Pack của MOD-05 (QT-15, SCR-1501 … SCR-1504) theo `policies.yaml`. Máy chủ kiểm tra
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

  /**
   * Nhân bản thành phiên bản nháp mới (`createPolicyPackVersion`, QT-15 bước 1): kế thừa quy tắc, bảng
   * quyết định, tham số và tham số luồng duyệt của `baseVersion`; bỏ trống `version` thì máy chủ tăng
   * số phụ. Lối duy nhất để soạn tiếp khi mọi phiên bản của gói đã khoá nội dung.
   */
  createVersion(id: string, body: PolicyPackVersionCreate): Observable<PolicyPackVersion> {
    return this.http.post<PolicyPackVersion>(apiUrl('/policies/packs/{id}/versions', { id }), body);
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

  /**
   * Chạy bộ test case vàng trên hai engine cùng kiểm thử thuộc tính: 202 + `Location` của job, theo
   * dõi bằng `JobTracker` (BR-15-07). 409 `POLICY-409-TEST-RUNNING` khi đang có lần chạy.
   */
  runTests(
    r: PackVersionRef,
    etag: string,
    body: PolicyTestRunCreate = {},
  ): Observable<JobAccepted> {
    return this.http
      .post<Job>(apiUrl('/policies/packs/{id}/versions/{v}/test-runs', r), body, {
        headers: { 'If-Match': etag },
        observe: 'response',
      })
      .pipe(map(acceptedJob));
  }

  /** Kết quả đầy đủ một lần chạy: từng ca, kiểm thử thuộc tính, khác biệt cấu hình, tác động. */
  testRun(r: PackVersionRef, runId: string): Observable<PolicyTestRun> {
    return this.http.get<PolicyTestRun>(
      apiUrl('/policies/packs/{id}/versions/{v}/test-runs/{runId}', { ...r, runId }),
    );
  }

  /** Ca kiểm thử vàng của gói theo trạng thái, kèm tóm tắt bộ (SCR-1503 trường 1). */
  testCases(packId: string, status?: 'NHAP' | 'DA_DUYET'): Observable<GoldenTestCaseList> {
    return this.http.get<GoldenTestCaseList>(apiUrl('/policies/test-cases'), {
      params: { pack: packId, limit: 200, ...(status ? { status } : {}) },
    });
  }

  /** Thêm ca kiểm thử ở trạng thái chờ ký duyệt (BR-15-08). */
  createTestCase(body: GoldenTestCaseCreate): Observable<GoldenTestCase> {
    return this.http.post<GoldenTestCase>(apiUrl('/policies/test-cases'), body);
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

  /**
   * Ký số phê duyệt và phát hành (AAL2, người phê duyệt khác người soạn, BR-15-09): 202, saga phát
   * hành chạy nền, theo dõi bằng `JobTracker`; xong thì phiên bản `DA_PHAT_HANH` (MSG-15-08).
   */
  approve(r: PackVersionRef, etag: string, body: PolicyPackApprove = {}): Observable<JobAccepted> {
    return this.http
      .post<Job>(apiUrl('/policies/packs/{id}/versions/{v}/approve', r), body, {
        headers: { 'If-Match': etag },
        observe: 'response',
      })
      .pipe(map(acceptedJob));
  }

  /** Từ chối phát hành có ý kiến: phiên bản trở về bản nháp. */
  reject(
    r: PackVersionRef,
    etag: string,
    body: PolicyDecisionReason,
  ): Observable<PolicyPackVersion> {
    return this.http.post<PolicyPackVersion>(
      apiUrl('/policies/packs/{id}/versions/{v}/reject', r),
      body,
      { headers: { 'If-Match': etag } },
    );
  }

  /** Danh mục mẫu luồng đã phát hành để chọn (SCR-1504 vùng 1). */
  workflowTemplates(processKind?: string): Observable<PolicyWorkflowTemplateList> {
    return this.http.get<PolicyWorkflowTemplateList>(apiUrl('/policies/workflow-templates'), {
      params: { limit: 50, ...(processKind ? { processKind } : {}) },
    });
  }

  /** Khai báo tham số (JSON Schema) và các bước xem trước của một phiên bản mẫu. */
  workflowTemplate(key: string, v: number): Observable<PolicyWorkflowTemplateVersion> {
    return this.http.get<PolicyWorkflowTemplateVersion>(
      apiUrl('/policies/workflow-templates/{key}/versions/{v}', { key, v: String(v) }),
    );
  }

  /** Tham số luồng duyệt đã gắn với phiên bản gói. */
  workflowBindings(r: PackVersionRef): Observable<WorkflowBinding[]> {
    return this.http
      .get<{ items: WorkflowBinding[] }>(
        apiUrl('/policies/packs/{id}/versions/{v}/workflow-bindings', r),
      )
      .pipe(map((b) => b.items));
  }

  /** Lưu tham số luồng duyệt theo loại quy trình; 422 `POLICY-422-WF-PARAMS` (MSG-15-09). */
  saveWorkflowBinding(
    r: PackVersionRef,
    processKind: string,
    rowVersion: number | null,
    body: WorkflowBindingUpdate,
  ): Observable<WorkflowBinding> {
    return this.http.put<WorkflowBinding>(
      apiUrl('/policies/packs/{id}/versions/{v}/workflow-bindings/{key}', {
        ...r,
        key: processKind,
      }),
      body,
      // hợp đồng bắt buộc If-Match; lần lưu đầu dùng "0" (policies.yaml saveWorkflowBinding)
      { headers: { 'If-Match': `"${rowVersion ?? 0}"` } },
    );
  }

  /** Mô phỏng luồng với tham số trên engine thử: các bước, linter, test của mẫu, hồ sơ mẫu. */
  simulate(body: WorkflowSimulateRequest): Observable<WorkflowSimulateResult> {
    return this.http.post<WorkflowSimulateResult>(
      apiUrl('/policies/workflow-bindings/simulate'),
      body,
    );
  }
}
