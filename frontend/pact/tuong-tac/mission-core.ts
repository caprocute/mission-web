import { Observable } from 'rxjs';
import { OkrApi, TaskApi } from '@vt/api-client';
import { AppraisalApi, EvidenceApi } from '@vt/api-client/domains';
import { giongKieu, moiPhanTu, ngay, soNguyen, uuid } from '../khop';

/**
 * Tương tác của web (SVC-17) với mission-core (SVC-05) cho QA-02: mỗi mục gọi đúng hàm của
 * `@vt/api-client` mà remote đang dùng, mô tả yêu cầu mà hàm đó gửi và phần phản hồi mà màn hình
 * thực sự đọc (không chép toàn bộ OpenAPI, ADR-32 vẫn là nguồn sự thật). Mã cố định trong đường dẫn
 * và tham số do trạng thái bên cung cấp (`providerStates`) dựng sẵn khi xác minh ở mission-core; kỳ
 * là kỳ hiện hành của tenant vì màn hình mở lần đầu không ghi kỳ.
 */
export interface TuongTac {
  mo_ta: string;
  trang_thai: { name: string; params?: Record<string, unknown> }[];
  /** Gọi hàm thật của client; kiểm thử bắt yêu cầu bằng HttpTestingController. */
  goi: (api: {
    task: TaskApi;
    okr: OkrApi;
    evidence: EvidenceApi;
    appraisal: AppraisalApi;
  }) => Observable<unknown>;
  yeu_cau: {
    method: string;
    path: string;
    query?: Record<string, string[]>;
    headers?: Record<string, string>;
    body?: unknown;
  };
  phan_hoi: { status: number; body: unknown };
  /** Các trường màn hình đọc từ kết quả; giá trị `undefined` nghĩa là hợp đồng thiếu trường. */
  doc: (ket_qua: never) => unknown[];
}

export const NHIEM_VU = '01927a02-0000-7000-8000-00000000c502';
export const KR = '01927a02-0000-7000-8000-00000000c513';
export const SAN_PHAM = '01927a02-0000-7000-8000-00000000c521';
export const HO_SO = '01927a02-0000-7000-8000-00000000c531';

/** Một dòng "Việc của tôi" (SCR-0503): các trường danh sách, xem nhanh và cập nhật tiến độ đọc. */
const viecNhiemVu = { id: uuid(NHIEM_VU), code: 'NV-PACT-01', name: 'Báo cáo chuyên đề' };

/** Một hồ sơ đánh giá của tôi (SCR-0901): các trường danh sách và phiếu tự đánh giá đọc. */
const hoSo = { id: uuid(HO_SO), flow: 'DANG', status: 'TU_DANH_GIA', rowVersion: soNguyen(1) };

const viec = {
  id: uuid(NHIEM_VU),
  code: 'NV-0001',
  title: 'Báo cáo chuyên đề',
  status: 'DANG_THUC_HIEN',
  dueDate: ngay('2029-03-15'),
  progress: soNguyen(20),
  role: 'CHU_TRI',
  taskGroup: 'THUONG_XUYEN',
  rowVersion: soNguyen(1),
  openBlockerCount: soNguyen(0),
  assignerName: 'Lê Văn Giao',
};

export const TUONG_TAC: TuongTac[] = [
  {
    mo_ta: 'danh sách việc của tôi trong kỳ hiện hành',
    trang_thai: [
      {
        name: 'cán bộ chủ trì một nhiệm vụ đang thực hiện trong kỳ hiện hành',
        params: { taskId: NHIEM_VU },
      },
    ],
    goi: ({ task }) => task.myTasks({ limit: 20 }),
    yeu_cau: { method: 'GET', path: '/api/v1/tasks/mine', query: { limit: ['20'] } },
    phan_hoi: {
      status: 200,
      body: giongKieu({
        items: moiPhanTu(viec),
        nextCursor: null,
        counts: { TAT_CA: soNguyen(1), CHAM: soNguyen(0), SAP_HAN: soNguyen(0) },
      }),
    },
    doc: (p: {
      items: (typeof viec & Record<string, unknown>)[];
      nextCursor: unknown;
      counts: Record<string, unknown>;
    }) => [
      p.nextCursor === undefined ? undefined : 'nextCursor',
      p.counts['TAT_CA'],
      ...[
        'id',
        'code',
        'title',
        'status',
        'dueDate',
        'progress',
        'role',
        'taskGroup',
        'rowVersion',
        'openBlockerCount',
        'assignerName',
      ].map((k) => p.items[0][k]),
    ],
  },
  {
    mo_ta: 'cập nhật tiến độ nhiệm vụ',
    trang_thai: [
      {
        name: 'cán bộ chủ trì một nhiệm vụ đang thực hiện trong kỳ hiện hành',
        params: { taskId: NHIEM_VU },
      },
    ],
    goi: ({ task }) =>
      task.updateProgress(
        { id: NHIEM_VU, rowVersion: 1 } as Parameters<TaskApi['updateProgress']>[0],
        {
          percent: 60,
          note: 'Đã hoàn thành phần khảo sát',
        },
      ),
    yeu_cau: {
      method: 'POST',
      path: `/api/v1/tasks/${NHIEM_VU}/progress`,
      headers: { 'If-Match': '"1"', 'Content-Type': 'application/json' },
      body: { percent: 60, note: 'Đã hoàn thành phần khảo sát' },
    },
    phan_hoi: {
      status: 200,
      body: giongKieu({
        id: uuid(NHIEM_VU),
        status: 'DANG_THUC_HIEN',
        progress: soNguyen(60),
        rowVersion: soNguyen(2),
      }),
    },
    doc: (t: Record<string, unknown>) => [t['id'], t['status'], t['progress'], t['rowVersion']],
  },
  {
    mo_ta: 'kết quả then chốt của tôi',
    trang_thai: [
      {
        name: 'cán bộ sở hữu một kết quả then chốt đã duyệt trong kỳ hiện hành',
        params: { keyResultId: KR },
      },
    ],
    goi: ({ okr }) => okr.myKeyResults(),
    yeu_cau: {
      method: 'GET',
      path: '/api/v1/okr/key-results',
      query: { owner: ['me'], limit: ['200'] },
    },
    phan_hoi: {
      status: 200,
      body: giongKieu({
        items: moiPhanTu({
          id: uuid(KR),
          code: 'KR 1.1',
          title: 'Kết quả kiểm thử',
          unit: 'chi bộ',
          targetValue: 10,
        }),
        nextCursor: null,
      }),
    },
    doc: (p: { items: Record<string, unknown>[]; nextCursor: unknown }) => [
      p.nextCursor === undefined ? undefined : 'nextCursor',
      ...['id', 'code', 'title', 'unit', 'targetValue'].map((k) => p.items[0][k]),
    ],
  },
  {
    mo_ta: 'tạo minh chứng cho sản phẩm của nhiệm vụ',
    trang_thai: [
      {
        name: 'cán bộ chủ trì một nhiệm vụ có sản phẩm chưa có minh chứng',
        params: { taskId: NHIEM_VU, deliverableId: SAN_PHAM },
      },
    ],
    goi: ({ evidence }) =>
      evidence.create({
        deliverableId: SAN_PHAM,
        title: 'Báo cáo chuyên đề',
        sourceType: 'TAI_LEN',
      }),
    yeu_cau: {
      method: 'POST',
      path: '/api/v1/evidences',
      headers: { 'Content-Type': 'application/json' },
      body: { deliverableId: SAN_PHAM, title: 'Báo cáo chuyên đề', sourceType: 'TAI_LEN' },
    },
    phan_hoi: {
      status: 201,
      body: giongKieu({
        id: uuid('01927a02-0000-7000-8000-00000000c522'),
        deliverableId: uuid(SAN_PHAM),
        task: viecNhiemVu,
        title: 'Báo cáo chuyên đề',
        sourceType: 'TAI_LEN',
        rowVersion: soNguyen(0),
      }),
    },
    doc: (e: Record<string, unknown>) => [e['id'], e['deliverableId'], e['task'], e['rowVersion']],
  },
  {
    mo_ta: 'minh chứng của tôi',
    trang_thai: [
      {
        name: 'cán bộ đã tạo một minh chứng cho sản phẩm của nhiệm vụ mình chủ trì',
        params: { taskId: NHIEM_VU, deliverableId: SAN_PHAM },
      },
    ],
    goi: ({ evidence }) => evidence.list({ submitter: 'me' }),
    yeu_cau: { method: 'GET', path: '/api/v1/evidences', query: { submitter: ['me'] } },
    phan_hoi: {
      status: 200,
      body: giongKieu({
        items: moiPhanTu({
          id: uuid('01927a02-0000-7000-8000-00000000c522'),
          deliverableId: uuid(SAN_PHAM),
          task: viecNhiemVu,
          title: 'Báo cáo chuyên đề',
          sourceType: 'TAI_LEN',
          rowVersion: soNguyen(0),
        }),
        nextCursor: null,
      }),
    },
    doc: (p: { items: Record<string, Record<string, unknown>>[]; nextCursor: unknown }) => [
      p.nextCursor === undefined ? undefined : 'nextCursor',
      p.items[0]['task']['code'],
      p.items[0]['task']['name'],
      ...['id', 'deliverableId', 'title', 'sourceType', 'rowVersion'].map((k) => p.items[0][k]),
    ],
  },
  {
    mo_ta: 'hồ sơ đánh giá của tôi',
    trang_thai: [
      {
        name: 'cán bộ có một hồ sơ đánh giá đang tự đánh giá trong kỳ hiện hành',
        params: { profileId: HO_SO },
      },
    ],
    goi: ({ appraisal }) => appraisal.myProfiles(),
    yeu_cau: { method: 'GET', path: '/api/v1/appraisals/profiles/mine', query: { limit: ['50'] } },
    phan_hoi: {
      status: 200,
      body: giongKieu({ items: moiPhanTu(hoSo), nextCursor: null }),
    },
    doc: (p: { items: Record<string, unknown>[]; nextCursor: unknown }) => [
      p.nextCursor === undefined ? undefined : 'nextCursor',
      ...['id', 'flow', 'status', 'rowVersion'].map((k) => p.items[0][k]),
    ],
  },
  {
    mo_ta: 'mở phiếu tự đánh giá',
    trang_thai: [
      {
        name: 'cán bộ có một hồ sơ đánh giá đang tự đánh giá trong kỳ hiện hành',
        params: { profileId: HO_SO },
      },
    ],
    goi: ({ appraisal }) => appraisal.profile(HO_SO),
    yeu_cau: { method: 'GET', path: `/api/v1/appraisals/profiles/${HO_SO}` },
    phan_hoi: { status: 200, body: giongKieu(hoSo) },
    doc: (p: Record<string, unknown>) => [p['id'], p['flow'], p['status'], p['rowVersion']],
  },
];
