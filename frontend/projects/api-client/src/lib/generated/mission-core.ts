/* eslint-disable */
/**
 * Sinh tự động bởi openapi-typescript từ tools/openapi (WEB-007). Không sửa tay;
 * sửa hợp đồng rồi chạy `npm run api:gen`.
 */
export interface paths {
  '/tasks/mine': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Việc của tôi (SCR-0503) */
    get: operations['listMyTasks'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Chi tiết nhiệm vụ (SCR-0601) */
    get: operations['getTask'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/progress': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Cập nhật tiến độ (QT-06) */
    post: operations['updateTaskProgress'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/jobs/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Trạng thái tác vụ dài (người khởi tạo) */
    get: operations['getJob'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
}
export type webhooks = Record<string, never>;
export interface components {
  schemas: {
    /**
     * @description Trạng thái chuẩn của task.task.status (baseline mục 9.1)
     * @enum {string}
     */
    TaskStatus:
      'NHAP' | 'DA_GIAO' | 'DANG_THUC_HIEN' | 'CHO_NGHIEM_THU' | 'HOAN_THANH' | 'TAM_DUNG' | 'HUY';
    /** @enum {string} */
    AssigneeRole: 'CHU_TRI' | 'PHOI_HOP';
    TaskSummary: {
      id: string;
      code: string;
      title: string;
      status: components['schemas']['TaskStatus'];
      overdue: boolean;
      /** Format: date */
      dueDate: string;
      progress: number;
      role: components['schemas']['AssigneeRole'];
      assignerName: string;
      unitName: string;
      periodId: string;
      /** @description ETag = row_version, gửi lại trong If-Match khi cập nhật */
      rowVersion: number;
    };
    TaskDetail: components['schemas']['TaskSummary'] & {
      description: string;
      sourceDoc?: {
        number: string;
        title: string;
      };
      coAssignees: {
        personId: string;
        name: string;
        role: components['schemas']['AssigneeRole'];
      }[];
      progressLog: {
        /** Format: date-time */
        at: string;
        percent: number;
        note: string;
        by: string;
      }[];
      blockers: {
        id: string;
        content: string;
        resolved: boolean;
      }[];
    };
    TaskSummaryPage: {
      items: components['schemas']['TaskSummary'][];
      nextCursor: string | null;
    };
    ProgressUpdate: {
      percent: number;
      note: string;
      blocker?: string;
    };
    /**
     * @description Trạng thái tác vụ dài. Giả định của WEB-007 khi chưa có hợp đồng thật, dùng lại giá trị đã có trong TL-03 (CHO, DANG_CHAY, HOAN_TAT, THAT_BAI, DA_HUY).
     * @enum {string}
     */
    JobStatus: 'CHO' | 'DANG_CHAY' | 'HOAN_TAT' | 'THAT_BAI' | 'DA_HUY';
    Job: {
      id: string;
      /** @description Loại tác vụ, ví dụ PERIOD_LOCK, ESIGN_BATCH, EXPORT */
      type: string;
      status: components['schemas']['JobStatus'];
      progress?: number;
      /** @description Mô tả bước đang chạy */
      message?: string;
      /** @description Đường dẫn kết quả khi HOAN_TAT */
      resultLocation?: string;
      problem?: components['schemas']['ProblemDetails'];
      /** Format: date-time */
      updatedAt?: string;
    };
    FieldError: {
      field: string;
      message: string;
    };
    /** @description RFC 9457, mở rộng code, errors, traceId theo TL-01 */
    ProblemDetails: {
      type: string;
      title: string;
      status: number;
      detail?: string;
      instance?: string;
      /** @description Dạng <MODULE>-<HTTP>-<TÊN>, ví dụ TASK-409-PERIOD-LOCKED */
      code?: string;
      errors?: components['schemas']['FieldError'][];
      traceId?: string;
    };
  };
  responses: {
    /** @description Lỗi theo RFC 9457 */
    Problem: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
  };
  parameters: {
    IfMatch: string;
    /** @description Khoá duy nhất cho một ý định ghi (TL-01 ghi UUIDv7) */
    IdempotencyKey: string;
  };
  requestBodies: never;
  headers: {
    /** @description row_version của đối tượng, gửi lại trong If-Match */
    ETag: string;
  };
  pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
  listMyTasks: {
    parameters: {
      query?: {
        limit?: number;
        cursor?: string;
        /** @description Danh sách trạng thái, phân tách bằng dấu phẩy */
        status?: string;
        overdue?: boolean;
        q?: string;
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Một trang kết quả */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskSummaryPage'];
        };
      };
      default: components['responses']['Problem'];
    };
  };
  getTask: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Nhiệm vụ */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskDetail'];
        };
      };
      default: components['responses']['Problem'];
    };
  };
  updateTaskProgress: {
    parameters: {
      query?: never;
      header: {
        'If-Match': components['parameters']['IfMatch'];
        /** @description Khoá duy nhất cho một ý định ghi (TL-01 ghi UUIDv7) */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['ProgressUpdate'];
      };
    };
    responses: {
      /** @description Nhiệm vụ sau khi cập nhật */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskDetail'];
        };
      };
      409: components['responses']['Problem'];
      422: components['responses']['Problem'];
      default: components['responses']['Problem'];
    };
  };
  getJob: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Trạng thái hiện tại */
      200: {
        headers: {
          /** @description Số giây nên chờ trước lần thăm dò kế tiếp */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Job'];
        };
      };
      default: components['responses']['Problem'];
    };
  };
}
