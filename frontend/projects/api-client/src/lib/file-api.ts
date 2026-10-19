import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';
import {
  EVIDENCE_MAX_BYTES,
  EVIDENCE_MEDIA_TYPES,
  FileObject,
  FileScanResult,
  FileUploadCreate,
  FileUploadPart,
  FileUploadSession,
} from './models/evidence';

const enc = encodeURIComponent;

/** SHA-256 của tệp dạng 64 ký tự hex chữ thường, tính trên trình duyệt bằng WebCrypto (BR-07-02). */
export async function sha256Hex(file: Blob): Promise<string> {
  const buf = await readBytes(file);
  const digest = await crypto.subtle.digest('SHA-256', buf);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Đọc nội dung tệp; `FileReader` dự phòng cho môi trường chưa có `Blob.arrayBuffer` (jsdom). */
function readBytes(file: Blob): Promise<Uint8Array<ArrayBuffer>> {
  if (typeof file.arrayBuffer === 'function')
    return file.arrayBuffer().then((b) => new Uint8Array(b));
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(new Uint8Array(r.result as ArrayBuffer));
    r.onerror = () => reject(r.error);
    r.readAsArrayBuffer(file);
  });
}

/** Kiểu MIME theo phần mở rộng; `null` khi không thuộc danh sách cho phép (SEC-48). */
export function evidenceMediaType(file: { name: string }): string | null {
  const ext = file.name.split('.').pop()?.toLowerCase() ?? '';
  return EVIDENCE_MEDIA_TYPES[ext] ?? null;
}

/** Lỗi kiểm tra trước khi tải (kiểu, dung lượng) để màn hình báo ngay, không gọi máy chủ. */
export class FileRejectedError extends Error {
  constructor(
    readonly reason: 'TYPE' | 'SIZE',
    message: string,
  ) {
    super(message);
  }
}

export interface UploadProgress {
  phase: 'BAM' | 'TAI' | 'HOAN_TAT';
  sentBytes: number;
  totalBytes: number;
  /** Đang thử lại một phần sau khi mất kết nối. */
  retrying: boolean;
}

const RETRIES = 4;
const isTransient = (e: unknown) =>
  e instanceof HttpErrorResponse && (e.status === 0 || e.status >= 500);

/**
 * Client `/api/v1/files` của SVC-08 (hợp đồng `products.yaml`): tải lên nhiều phần có tải tiếp sau khi
 * mất kết nối, hoàn tất (máy chủ băm lại, tra trùng, xếp quét) và đọc kết quả quét.
 */
@Injectable({ providedIn: 'root' })
export class FileApi {
  private readonly http = inject(HttpClient);

  createUpload(body: FileUploadCreate): Observable<FileUploadSession> {
    return this.http.post<FileUploadSession>('/api/v1/files/uploads', body);
  }

  getUpload(uploadId: string): Observable<FileUploadSession> {
    return this.http.get<FileUploadSession>(`/api/v1/files/uploads/${enc(uploadId)}`);
  }

  putPart(uploadId: string, n: number, blob: Blob): Observable<FileUploadPart> {
    return this.http.put<FileUploadPart>(
      `/api/v1/files/uploads/${enc(uploadId)}/parts/${n}`,
      blob,
      {
        // gửi lại cùng phần là an toàn: khoá theo phiên và số phần
        headers: {
          'Content-Type': 'application/octet-stream',
          'Idempotency-Key': `${uploadId}-${n}`,
        },
      },
    );
  }

  complete(uploadId: string, parts: FileUploadPart[]): Observable<FileObject> {
    return this.http.post<FileObject>(`/api/v1/files/uploads/${enc(uploadId)}/complete`, {
      parts,
    });
  }

  scan(objectId: string): Observable<FileScanResult> {
    return this.http.get<FileScanResult>(`/api/v1/files/${enc(objectId)}/scan`);
  }

  /**
   * Kiểm kiểu và dung lượng, băm SHA-256, mở phiên, gửi từng phần (thử lại có giãn cách và hỏi lại phiên
   * để chỉ gửi phần còn thiếu), rồi hoàn tất. Lỗi 422 khi hoàn tất là mã băm không khớp (MSG-07-03).
   */
  async upload(
    file: File,
    opts: { ownerModule: FileUploadCreate['ownerModule']; ownerRefId?: string },
    onProgress: (p: UploadProgress) => void = () => undefined,
    delayMs = 500,
  ): Promise<FileObject> {
    const mediaType = evidenceMediaType(file);
    if (!mediaType)
      throw new FileRejectedError(
        'TYPE',
        `Tệp "${file.name}" không thuộc PDF, DOCX, XLSX, JPG, PNG.`,
      );
    if (file.size > EVIDENCE_MAX_BYTES)
      throw new FileRejectedError('SIZE', `Tệp "${file.name}" vượt 50 MB.`);
    const total = file.size;
    onProgress({ phase: 'BAM', sentBytes: 0, totalBytes: total, retrying: false });
    const declaredSha256 = await sha256Hex(file);
    const session = await firstValueFrom(
      this.createUpload({
        fileName: file.name,
        mediaType,
        declaredSize: total,
        declaredSha256,
        ownerModule: opts.ownerModule,
        ownerRefId: opts.ownerRefId,
        classification: 'C1',
        e2ee: false,
      }),
    );
    const count = session.partCount ?? Math.max(1, Math.ceil(total / session.partSize));
    const done = new Map<number, FileUploadPart>(
      (session.receivedParts ?? []).map((p) => [p.partNo, p]),
    );
    const sent = () =>
      [...done.keys()].reduce(
        (s, n) => s + Math.min(session.partSize, total - (n - 1) * session.partSize),
        0,
      );
    for (let n = 1; n <= count; n++) {
      if (done.has(n)) continue;
      const blob = file.slice((n - 1) * session.partSize, n * session.partSize);
      for (let attempt = 0; ; attempt++) {
        try {
          done.set(n, await firstValueFrom(this.putPart(session.uploadId, n, blob)));
          onProgress({ phase: 'TAI', sentBytes: sent(), totalBytes: total, retrying: false });
          break;
        } catch (e) {
          if (!isTransient(e) || attempt >= RETRIES) throw e;
          onProgress({ phase: 'TAI', sentBytes: sent(), totalBytes: total, retrying: true });
          await new Promise((r) => setTimeout(r, delayMs * 2 ** attempt));
          // tải tiếp: hỏi phiên xem phần nào máy chủ đã nhận
          const s = await firstValueFrom(this.getUpload(session.uploadId)).catch(() => null);
          for (const p of s?.receivedParts ?? []) done.set(p.partNo, p);
          if (done.has(n)) break;
        }
      }
    }
    onProgress({ phase: 'HOAN_TAT', sentBytes: total, totalBytes: total, retrying: false });
    const parts = [...done.values()].sort((a, b) => a.partNo - b.partNo);
    return firstValueFrom(this.complete(session.uploadId, parts));
  }
}
