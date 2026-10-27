import { test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ApiHeThong, LoiApi } from './api';
import { DanhTinh } from './phien';
import { MA_LAN_CHAY, cho } from './moi-truong';

/**
 * Dựng và dọn dữ liệu đầu vào của hành trình qua API thật (không chèn thẳng CSDL), từ
 * `data/seed/e2e/he-thong.json`. Mọi bản ghi tạo ra mang tiền tố `MA_LAN_CHAY` trong tên để dọn.
 */
const SEED = JSON.parse(
  readFileSync(join(__dirname, '..', '..', '..', '..', 'data/seed/e2e/he-thong.json'), 'utf8'),
) as {
  ky: { code: string; name: string; kind: string; flow: string };
  nhiemVu: Record<string, unknown> & {
    title: string;
    hanSauNgay: number;
    mocSauNgay: number;
    noiDungMoc: string;
  };
  tienDo: { buocTang: number; ghiChu: string };
};
export const TIEN_DO = SEED.tienDo;

interface Ky {
  id: string;
  code: string;
  startDate: string;
  endDate: string;
  status: string;
  kind?: string;
}
interface Trang<T> {
  items: T[];
  nextCursor: string | null;
}
export interface NhiemVuTom {
  id: string;
  title: string;
  status: string;
  progress: number;
  rowVersion: number | string;
}

const ngay = (d: Date) => d.toISOString().slice(0, 10);
const sauNgay = (n: number) => ngay(new Date(Date.now() + n * 86_400_000));
const thay = (s: string) =>
  s.replace('{MA}', MA_LAN_CHAY).replaceAll('{THANG}', ngay(new Date()).slice(0, 7));

/**
 * Phản hồi lỗi do việc khác chưa xong thì đánh dấu chờ thay vì báo đỏ: 401 là mission-core chưa nhận
 * JWT của Keycloak (resource server, B1); 403 hoặc 404/422 nhắc hồ sơ cán bộ là chưa seed hồ sơ, đơn vị
 * và claim tenant của tài khoản thử (B9).
 */
export function choNeuPhuThuoc(e: unknown, buoc: string): never {
  if (e instanceof LoiApi) {
    const ma = (e.kq.body as { code?: string } | null)?.code ?? '';
    if (e.kq.status === 401) cho(`${buoc}: mission-core chưa nhận JWT Keycloak (resource server)`);
    if (e.kq.status === 403 || /PERSON|PROFILE|UNIT|TENANT/.test(ma))
      cho(`${buoc}: tài khoản thử chưa có hồ sơ cán bộ, đơn vị hoặc claim tenant (${e.message})`);
  }
  throw e;
}

/**
 * Kỳ đang mở loại `kind` bao trùm hôm nay; không có thì `quantri` tạo kỳ đó (tháng hiện tại theo seed, hoặc quý
 * hiện tại). Kế hoạch công tác lập theo quý: máy chủ lấy kỳ quý hiện hành khi màn hình kế hoạch không chọn kỳ,
 * nên hành trình kế hoạch (HT-11, HT-12) cần kỳ `QUY`; các hành trình khác dùng kỳ tháng của seed.
 */
export async function kyDangMo(
  quanTri: ApiHeThong,
  kind: 'THANG' | 'QUY' = SEED.ky.kind as 'THANG',
): Promise<Ky> {
  const hom = ngay(new Date());
  const ds = await quanTri.can<Trang<Ky>>('GET', 'periods', {
    query: { status: 'MO', kind, limit: '200' },
  });
  const co = ds.body.items.find(
    (k) => k.startDate <= hom && hom <= k.endDate && (k.kind ?? kind) === kind,
  );
  if (co) return co;
  const d = new Date();
  const thangDau = kind === 'QUY' ? Math.floor(d.getUTCMonth() / 3) * 3 : d.getUTCMonth();
  const soThang = kind === 'QUY' ? 3 : 1;
  const dau = ngay(new Date(Date.UTC(d.getUTCFullYear(), thangDau, 1)));
  const cuoi = ngay(new Date(Date.UTC(d.getUTCFullYear(), thangDau + soThang, 0)));
  const tao = await quanTri.can<Ky>('POST', 'periods', {
    body: {
      code: thay(SEED.ky.code) + (kind === 'QUY' ? '-QUY' : ''),
      name: thay(SEED.ky.name) + (kind === 'QUY' ? ' (quý)' : ''),
      kind,
      flow: SEED.ky.flow,
      startDate: dau,
      endDate: cuoi,
    },
  });
  return tao.body;
}

/**
 * Người giao (`quanly`) soạn nhiệm vụ đột xuất cho người chủ trì `canBo` rồi giao (QT-05: SCR-0501,
 * `createTaskDraft` → `assignTask`). Trả nhiệm vụ đã giao.
 */
/** Số nhiệm vụ đã giao trong lần chạy: từ việc thứ hai thêm hậu tố để tên không trùng giữa các hành trình. */
let soViecDaGiao = 0;

export async function giaoNhiemVu(
  quanLy: ApiHeThong,
  nguoiGiao: DanhTinh,
  canBo: DanhTinh,
  ky: Ky,
): Promise<NhiemVuTom & { code: string }> {
  const { hanSauNgay, mocSauNgay, noiDungMoc, title, ...con } = SEED.nhiemVu;
  const han = sauNgay(hanSauNgay) <= ky.endDate ? sauNgay(hanSauNgay) : ky.endDate;
  const moc = sauNgay(mocSauNgay) <= han ? sauNgay(mocSauNgay) : han;
  const nhap = await quanLy.can<{ id: string; code: string; rowVersion: number }>('POST', 'tasks', {
    body: {
      ...con,
      status: 'NHAP',
      periodId: ky.id,
      unitName: '',
      assignerId: nguoiGiao.sub,
      assignerName: nguoiGiao.ten,
      title: ++soViecDaGiao > 1 ? `${thay(title)} (${soViecDaGiao})` : thay(title),
      leadPersonId: canBo.sub,
      coAssignees: [],
      dueDate: han,
      milestones: [{ date: moc, content: noiDungMoc }],
    },
  });
  const etag = nhap.etag ?? `"${nhap.body.rowVersion}"`;
  await quanLy.can('POST', `tasks/${nhap.body.id}/assign`, {
    ifMatch: etag,
    body: { similarReason: 'Kiểm thử đầu cuối tự động' },
  });
  const ct = await quanLy.can<NhiemVuTom>('GET', `tasks/${nhap.body.id}`);
  return { ...ct.body, code: nhap.body.code };
}

/** Nhiệm vụ hiện hành theo mã (đọc lại phần trăm sau khi cập nhật trên giao diện). */
export async function docNhiemVu(api: ApiHeThong, id: string): Promise<NhiemVuTom> {
  return (await api.can<NhiemVuTom>('GET', `tasks/${id}`)).body;
}

/**
 * Dọn theo tiền tố của lần chạy: bản nháp còn `NHAP` thì huỷ; nhiệm vụ đã giao không có lệnh xoá
 * (huỷ phải qua đề nghị có duyệt, SCR-0602) nên giữ lại và ghi chú để biết dữ liệu nào còn sót.
 * Không ném lỗi: dọn hỏng không được che kết quả của hành trình.
 */
export async function donTheoTienTo(quanLy: ApiHeThong, kyId: string | null) {
  try {
    const query: Record<string, string> = { q: MA_LAN_CHAY, limit: '200' };
    if (kyId) query['period'] = kyId;
    const ds = await quanLy.goi<Trang<NhiemVuTom>>('GET', 'tasks', { query });
    if (ds.status !== 200) return;
    const conLai: string[] = [];
    for (const t of ds.body.items.filter((x) => x.title.startsWith(MA_LAN_CHAY))) {
      if (t.status === 'NHAP') {
        await quanLy.goi('DELETE', `tasks/${t.id}`, { ifMatch: `"${t.rowVersion}"` });
      } else {
        conLai.push(t.id);
      }
    }
    if (conLai.length) {
      test.info().annotations.push({
        type: 'du-lieu-con-lai',
        description: `${conLai.length} nhiệm vụ đã giao mang tiền tố ${MA_LAN_CHAY} (không có lệnh xoá)`,
      });
    }
  } catch {
    // bỏ qua: dọn là phần phụ
  }
}
