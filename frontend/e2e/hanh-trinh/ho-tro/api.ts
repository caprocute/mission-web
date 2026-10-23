import { APIRequestContext, request, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { MOI_TRUONG } from './moi-truong';

/**
 * Client API gọi thẳng APISIX dev (SVC-01 → mission-core) bằng access token của tài khoản thử, để
 * dựng và dọn dữ liệu đầu vào của hành trình. Theo quy ước API mục 13 baseline: mọi lệnh ghi gửi
 * `Idempotency-Key` mới, lệnh cập nhật gửi `If-Match` lấy từ `ETag` vừa đọc. Mỗi phản hồi lỗi được
 * ghi vào báo cáo kèm `X-Request-Id` để tra nhật ký (không ghi token hay thân yêu cầu).
 */
export interface KetQua<T = unknown> {
  status: number;
  body: T;
  etag: string | null;
  requestId: string | null;
}

export class LoiApi extends Error {
  constructor(
    readonly method: string,
    readonly path: string,
    readonly kq: KetQua,
  ) {
    const code = (kq.body as { code?: string } | null)?.code ?? '';
    super(
      `${method} ${path} → ${kq.status}${code ? ` ${code}` : ''}` +
        (kq.requestId ? ` (X-Request-Id ${kq.requestId})` : ''),
    );
  }
}

export class ApiHeThong {
  private constructor(
    private readonly ctx: APIRequestContext,
    readonly nhan: string,
  ) {}

  /** Client của một tài khoản; `nhan` chỉ để ghi báo cáo (tên tài khoản thử, không phải token). */
  static async tao(token: string, nhan: string): Promise<ApiHeThong> {
    const ctx = await request.newContext({
      baseURL: `${MOI_TRUONG.apisix}/api/v1/`,
      extraHTTPHeaders: { authorization: `Bearer ${token}`, accept: 'application/json' },
    });
    return new ApiHeThong(ctx, nhan);
  }

  async goi<T>(
    method: 'GET' | 'POST' | 'PUT' | 'DELETE',
    path: string,
    opts: {
      body?: unknown;
      ifMatch?: string | null;
      query?: Record<string, string>;
      /** Gửi lại đúng khoá chống lặp của một lệnh trước (kiểm idempotency). */
      idempotencyKey?: string;
    } = {},
  ): Promise<KetQua<T>> {
    const headers: Record<string, string> = {};
    if (method !== 'GET') headers['idempotency-key'] = opts.idempotencyKey ?? randomUUID();
    if (opts.ifMatch) headers['if-match'] = opts.ifMatch;
    const r = await this.ctx.fetch(path.replace(/^\//, ''), {
      method,
      headers,
      params: opts.query,
      data: opts.body,
    });
    const text = await r.text();
    let body: unknown = null;
    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      body = text;
    }
    const kq: KetQua<T> = {
      status: r.status(),
      body: body as T,
      etag: r.headers()['etag'] ?? null,
      requestId: r.headers()['x-request-id'] ?? null,
    };
    if (kq.status >= 400) {
      test.info().annotations.push({
        type: 'x-request-id',
        description: `${this.nhan}: ${method} /api/v1/${path.replace(/^\//, '')} → ${kq.status}${
          kq.requestId ? ` · ${kq.requestId}` : ''
        }`,
      });
    }
    return kq;
  }

  /** Như `goi` nhưng ném `LoiApi` khi mã trả về không thuộc `ok`. */
  async can<T>(
    method: 'GET' | 'POST' | 'PUT' | 'DELETE',
    path: string,
    opts: { body?: unknown; ifMatch?: string | null; query?: Record<string, string> } = {},
    ok: number[] = [200, 201, 202, 204],
  ): Promise<KetQua<T>> {
    const kq = await this.goi<T>(method, path, opts);
    if (!ok.includes(kq.status)) throw new LoiApi(method, `/api/v1/${path.replace(/^\//, '')}`, kq);
    return kq;
  }

  async dong() {
    await this.ctx.dispose();
  }
}
