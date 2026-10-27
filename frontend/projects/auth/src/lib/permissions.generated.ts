// TỆP SINH TỰ ĐỘNG, KHÔNG SỬA TAY.
// Nguồn: docs/phan-quyen/ma-tran.json; sinh lại bằng `python3 docs/phan-quyen/gen_matrix.py`
// và kiểm tra lệch bằng `npm run check:permissions` (WEB-054).

/** Phiên bản ma trận phân quyền đã sinh ra tệp này. */
export const PERMISSION_MATRIX_VERSION = '1.1 (24/10/2026)';

/** Khoá chức năng web của ma trận (wiki/15a), dạng `SCR-qqnn.<khoá>`. */
export type PermissionKey =
  | 'SCR-0001.dang-nhap'
  | 'SCR-0001.chon-phan-cong'
  | 'SCR-0001.bao-bat-thuong'
  | 'SCR-0001.khoa-thiet-bi'
  | 'SCR-0002.xem'
  | 'SCR-0002.doi-phan-cong'
  | 'SCR-0002.cap-nhat-nhanh'
  | 'SCR-0002.doc-thong-bao'
  | 'SCR-0101.xem'
  | 'SCR-0101.xuat'
  | 'SCR-0102.buoc-1-3'
  | 'SCR-0102.buoc-4-5'
  | 'SCR-0102.kich-hoat'
  | 'SCR-0102.huy'
  | 'SCR-0103.xem'
  | 'SCR-0103.nhap-excel'
  | 'SCR-0103.thay-doi'
  | 'SCR-0103.khoa-phuc-hoi'
  | 'SCR-0103.nhat-ky-phuc-hoi'
  | 'SCR-0104.xem'
  | 'SCR-0104.dong-bo'
  | 'SCR-0104.xu-ly-lech'
  | 'SCR-0104.giao-xu-ly'
  | 'SCR-0105.xem'
  | 'SCR-0105.cap'
  | 'SCR-0105.duyet'
  | 'SCR-0105.thu-hoi'
  | 'SCR-0106.xem'
  | 'SCR-0106.xuat'
  | 'SCR-0107.xem'
  | 'SCR-0107.tao'
  | 'SCR-0107.thu-hoi'
  | 'SCR-0108.xem'
  | 'SCR-0108.them'
  | 'SCR-0108.khoa'
  | 'SCR-0109.xem'
  | 'SCR-0109.sua'
  | 'SCR-0109.phan-cong'
  | 'SCR-0109.dat-lai-mat-khau'
  | 'SCR-0110.xem'
  | 'SCR-0110.sua'
  | 'SCR-0201.xem'
  | 'SCR-0201.them'
  | 'SCR-0201.check-in'
  | 'SCR-0202.soan-don-vi'
  | 'SCR-0202.soan-ca-nhan'
  | 'SCR-0202.ai-soat'
  | 'SCR-0202.trinh'
  | 'SCR-0203.xem'
  | 'SCR-0203.duyet'
  | 'SCR-0203.tra-lai'
  | 'SCR-0203.duyet-lo'
  | 'SCR-0301.check-in'
  | 'SCR-0301.lich-su'
  | 'SCR-0301.giao-check-in-thay'
  | 'SCR-0401.xem'
  | 'SCR-0401.sua'
  | 'SCR-0402.xem'
  | 'SCR-0402.lap'
  | 'SCR-0402.gui-duyet'
  | 'SCR-0402.dieu-chinh'
  | 'SCR-0403.so-sanh'
  | 'SCR-0403.tra-lai'
  | 'SCR-0403.duyet-ky'
  | 'SCR-0501.soan'
  | 'SCR-0501.giao'
  | 'SCR-0501.giao-dong-loat'
  | 'SCR-0501.trung'
  | 'SCR-0501.khoa-nguoi-nhan'
  | 'SCR-0502.nhan-van-ban'
  | 'SCR-0502.ai-trich'
  | 'SCR-0502.xac-nhan'
  | 'SCR-0502.xac-nhan-giao'
  | 'SCR-0503.xem'
  | 'SCR-0503.nhan-viec'
  | 'SCR-0503.tien-do'
  | 'SCR-0503.dang-ky'
  | 'SCR-0601.xem'
  | 'SCR-0601.tien-do'
  | 'SCR-0601.bao-nghen'
  | 'SCR-0601.xu-ly-nghen'
  | 'SCR-0601.duyet-de-nghi'
  | 'SCR-0601.nop-sp'
  | 'SCR-0602.gui'
  | 'SCR-0602.rut'
  | 'SCR-0701.soan'
  | 'SCR-0701.nop'
  | 'SCR-0701.xem'
  | 'SCR-0703.mo'
  | 'SCR-0703.xac-nhan'
  | 'SCR-0703.bo-sung-tu-choi'
  | 'SCR-0801.xem'
  | 'SCR-0801.tao-ky'
  | 'SCR-0801.lap-lenh'
  | 'SCR-0801.duyet-lenh'
  | 'SCR-0801.khoi-chay'
  | 'SCR-0801.dieu-khien'
  | 'SCR-0801.de-nghi-huy'
  | 'SCR-0801.duyet-huy'
  | 'SCR-0901.xem'
  | 'SCR-0901.luu'
  | 'SCR-0901.ky-nop'
  | 'SCR-0902.xem'
  | 'SCR-0902.tra-lai'
  | 'SCR-0902.ky'
  | 'SCR-0902.xuat'
  | 'SCR-0903.xem'
  | 'SCR-0903.de-xuat'
  | 'SCR-0903.tai-mau-1b'
  | 'SCR-0903.khieu-nai'
  | 'SCR-0904.xem'
  | 'SCR-0904.doi-muc'
  | 'SCR-0904.ky-lo'
  | 'SCR-0904.theo-doi-huy'
  | 'SCR-1001.xem'
  | 'SCR-1001.tham-dinh-nam'
  | 'SCR-1001.bo-tri-lai'
  | 'SCR-1002.xem'
  | 'SCR-1002.xep-nhom'
  | 'SCR-1002.trinh'
  | 'SCR-1101.tao'
  | 'SCR-1101.gui-du-thao'
  | 'SCR-1101.gop-y'
  | 'SCR-1101.thu-ky'
  | 'SCR-1101.ket-thuc'
  | 'SCR-1102.chuan-bi'
  | 'SCR-1102.tai-goi'
  | 'SCR-1102.ky-goi'
  | 'SCR-1102.goi-con'
  | 'SCR-1201.soan'
  | 'SCR-1201.ky-gui'
  | 'SCR-1201.rut-bao-luu'
  | 'SCR-1202.tiep-nhan'
  | 'SCR-1202.giai-trinh'
  | 'SCR-1202.tinh-thu'
  | 'SCR-1202.ky-tra-loi'
  | 'SCR-1202.mo-lai'
  | 'SCR-1301.tao'
  | 'SCR-1301.duyet-1'
  | 'SCR-1301.duyet-2'
  | 'SCR-1301.tu-choi'
  | 'SCR-1301.huy'
  | 'SCR-1302.tra-cuu'
  | 'SCR-1302.kiem-tra'
  | 'SCR-1302.xuat'
  | 'SCR-1401.xem'
  | 'SCR-1401.xu-ly'
  | 'SCR-1401.ket-luan'
  | 'SCR-1401.nguong'
  | 'SCR-1501.soan'
  | 'SCR-1501.gui-duyet'
  | 'SCR-1502.soan'
  | 'SCR-1503.chay'
  | 'SCR-1503.xem-duyet'
  | 'SCR-1503.ky-duyet'
  | 'SCR-1504.cau-hinh'
  | 'SCR-1505.thu-vien'
  | 'SCR-1505.soan'
  | 'SCR-1505.chay-test'
  | 'SCR-1505.de-nghi-phat-hanh'
  | 'SCR-1505.phe-duyet'
  | 'SCR-1505.trien-khai-cell'
  | 'SCR-1506.xem'
  | 'SCR-1506.chuyen-nguoi-xu-ly'
  | 'SCR-1506.gia-han-buoc'
  | 'SCR-1506.so-do'
  | 'SCR-1601.xem'
  | 'SCR-1601.nhac'
  | 'SCR-1601.giao-lai'
  | 'SCR-1601.nguon-ai'
  | 'SCR-1602.dung'
  | 'SCR-1602.chia-se'
  | 'SCR-1603.xem'
  | 'SCR-1603.mo-c2'
  | 'SCR-1603.xuat'
  | 'SCR-1701.hoi'
  | 'SCR-1701.dung-goi-y'
  | 'SCR-1701.trinh'
  | 'SCR-1801.soan'
  | 'SCR-1801.gui-duyet'
  | 'SCR-1801.duyet'
  | 'SCR-1801.quay-lui'
  | 'SCR-1802.dung'
  | 'SCR-1802.dua-vao-pack';

/**
 * Vai trò có thể có quyền với từng chức năng, cách nhau bởi dấu cách; hậu tố `*` đánh dấu ô suy luận.
 * Chuỗi rỗng: không vai trò người dùng nào có quyền trên giao diện.
 */
export const PERMISSION_TABLE: Readonly<Record<PermissionKey, string>> = {
  'SCR-0001.dang-nhap':
    'platform_admin tenant_admin policy_author leader manager member appraiser decider second_approver inspector',
  'SCR-0001.chon-phan-cong':
    'platform_admin tenant_admin policy_author leader manager member appraiser decider second_approver inspector',
  'SCR-0001.bao-bat-thuong':
    'platform_admin tenant_admin policy_author leader manager member appraiser decider second_approver inspector',
  'SCR-0001.khoa-thiet-bi':
    'platform_admin* tenant_admin* policy_author* leader* manager* member* appraiser* decider* second_approver* inspector*',
  'SCR-0002.xem':
    'platform_admin* tenant_admin* policy_author* leader manager member appraiser decider* second_approver* inspector*',
  'SCR-0002.doi-phan-cong':
    'platform_admin tenant_admin policy_author leader manager member appraiser decider second_approver inspector',
  'SCR-0002.cap-nhat-nhanh': 'manager member',
  'SCR-0002.doc-thong-bao':
    'platform_admin* tenant_admin* policy_author* leader* manager* member* appraiser* decider* second_approver* inspector*',
  'SCR-0101.xem': 'platform_admin',
  'SCR-0101.xuat': 'platform_admin',
  'SCR-0102.buoc-1-3': 'platform_admin',
  'SCR-0102.buoc-4-5': 'platform_admin* tenant_admin',
  'SCR-0102.kich-hoat': 'platform_admin',
  'SCR-0102.huy': 'platform_admin',
  'SCR-0103.xem': 'tenant_admin appraiser* inspector*',
  'SCR-0103.nhap-excel': 'tenant_admin',
  'SCR-0103.thay-doi': 'tenant_admin',
  'SCR-0103.khoa-phuc-hoi': 'tenant_admin*',
  'SCR-0103.nhat-ky-phuc-hoi': 'tenant_admin* inspector*',
  'SCR-0104.xem': 'tenant_admin inspector*',
  'SCR-0104.dong-bo': 'tenant_admin',
  'SCR-0104.xu-ly-lech': 'tenant_admin',
  'SCR-0104.giao-xu-ly': 'tenant_admin appraiser*',
  'SCR-0105.xem': 'tenant_admin leader* inspector*',
  'SCR-0105.cap': 'tenant_admin',
  'SCR-0105.duyet': 'tenant_admin leader*',
  'SCR-0105.thu-hoi': 'tenant_admin',
  'SCR-0106.xem': 'tenant_admin leader* inspector',
  'SCR-0106.xuat': 'tenant_admin inspector',
  'SCR-0107.xem': 'tenant_admin* leader inspector*',
  'SCR-0107.tao': 'leader',
  'SCR-0107.thu-hoi': 'leader',
  'SCR-0108.xem': 'tenant_admin inspector',
  'SCR-0108.them': 'tenant_admin',
  'SCR-0108.khoa': 'tenant_admin',
  'SCR-0109.xem': 'tenant_admin inspector',
  'SCR-0109.sua': 'tenant_admin',
  'SCR-0109.phan-cong': 'tenant_admin',
  'SCR-0109.dat-lai-mat-khau': 'tenant_admin',
  'SCR-0110.xem': 'tenant_admin inspector',
  'SCR-0110.sua': 'tenant_admin',
  'SCR-0201.xem': 'leader* manager member',
  'SCR-0201.them': 'manager',
  'SCR-0201.check-in': 'manager member',
  'SCR-0202.soan-don-vi': 'manager',
  'SCR-0202.soan-ca-nhan': 'member',
  'SCR-0202.ai-soat': 'manager member',
  'SCR-0202.trinh': 'manager member',
  'SCR-0203.xem': 'leader',
  'SCR-0203.duyet': 'leader',
  'SCR-0203.tra-lai': 'leader',
  'SCR-0203.duyet-lo': 'leader',
  'SCR-0301.check-in': 'manager member',
  'SCR-0301.lich-su': 'leader* manager* member',
  'SCR-0301.giao-check-in-thay': 'leader*',
  'SCR-0401.xem': 'policy_author* leader* manager member',
  'SCR-0401.sua': 'policy_author* manager',
  'SCR-0402.xem': 'leader* manager member* appraiser',
  'SCR-0402.lap': 'manager',
  'SCR-0402.gui-duyet': 'manager',
  'SCR-0402.dieu-chinh': 'manager',
  'SCR-0403.so-sanh': 'leader',
  'SCR-0403.tra-lai': 'leader',
  'SCR-0403.duyet-ky': 'leader',
  'SCR-0501.soan': 'manager',
  'SCR-0501.giao': 'manager',
  'SCR-0501.giao-dong-loat': 'manager*',
  'SCR-0501.trung': 'manager',
  'SCR-0501.khoa-nguoi-nhan':
    'platform_admin* tenant_admin* policy_author* leader* manager* member* appraiser* decider* second_approver* inspector*',
  'SCR-0502.nhan-van-ban': 'manager',
  'SCR-0502.ai-trich': '',
  'SCR-0502.xac-nhan': 'manager',
  'SCR-0502.xac-nhan-giao': 'manager',
  'SCR-0503.xem': 'member',
  'SCR-0503.nhan-viec': 'member',
  'SCR-0503.tien-do': 'member',
  'SCR-0503.dang-ky': 'member',
  'SCR-0601.xem': 'leader manager member appraiser* inspector*',
  'SCR-0601.tien-do': 'member',
  'SCR-0601.bao-nghen': 'member',
  'SCR-0601.xu-ly-nghen': 'manager',
  'SCR-0601.duyet-de-nghi': 'leader* manager',
  'SCR-0601.nop-sp': 'member',
  'SCR-0602.gui': 'member',
  'SCR-0602.rut': 'member',
  'SCR-0701.soan': 'member',
  'SCR-0701.nop': 'member',
  'SCR-0701.xem': 'manager* member appraiser',
  'SCR-0703.mo': 'manager',
  'SCR-0703.xac-nhan': 'manager',
  'SCR-0703.bo-sung-tu-choi': 'manager',
  'SCR-0801.xem': 'tenant_admin leader* second_approver inspector',
  'SCR-0801.tao-ky': 'tenant_admin',
  'SCR-0801.lap-lenh': 'tenant_admin',
  'SCR-0801.duyet-lenh': 'leader*',
  'SCR-0801.khoi-chay': 'tenant_admin',
  'SCR-0801.dieu-khien': 'tenant_admin',
  'SCR-0801.de-nghi-huy': 'tenant_admin',
  'SCR-0801.duyet-huy': 'second_approver',
  'SCR-0901.xem': 'manager member',
  'SCR-0901.luu': 'member',
  'SCR-0901.ky-nop': 'member',
  'SCR-0902.xem': 'appraiser',
  'SCR-0902.tra-lai': 'appraiser',
  'SCR-0902.ky': 'appraiser',
  'SCR-0902.xuat': 'appraiser',
  'SCR-0903.xem': 'manager member appraiser decider',
  'SCR-0903.de-xuat': 'manager',
  'SCR-0903.tai-mau-1b': 'manager* member appraiser* decider*',
  'SCR-0903.khieu-nai': 'member',
  'SCR-0904.xem': 'decider',
  'SCR-0904.doi-muc': 'decider',
  'SCR-0904.ky-lo': 'decider',
  'SCR-0904.theo-doi-huy': 'decider',
  'SCR-1001.xem': 'leader appraiser decider',
  'SCR-1001.tham-dinh-nam': 'appraiser',
  'SCR-1001.bo-tri-lai': 'appraiser',
  'SCR-1002.xem': 'leader appraiser',
  'SCR-1002.xep-nhom': 'leader appraiser',
  'SCR-1002.trinh': 'leader',
  'SCR-1101.tao': 'appraiser*',
  'SCR-1101.gui-du-thao': 'member',
  'SCR-1101.gop-y': 'leader* manager* member appraiser*',
  'SCR-1101.thu-ky': 'member appraiser',
  'SCR-1101.ket-thuc': 'leader*',
  'SCR-1102.chuan-bi': 'appraiser decider',
  'SCR-1102.tai-goi': 'appraiser decider',
  'SCR-1102.ky-goi': 'decider',
  'SCR-1102.goi-con': 'appraiser*',
  'SCR-1201.soan': 'member',
  'SCR-1201.ky-gui': 'member',
  'SCR-1201.rut-bao-luu': 'member',
  'SCR-1202.tiep-nhan': 'appraiser',
  'SCR-1202.giai-trinh': 'manager* appraiser',
  'SCR-1202.tinh-thu': 'appraiser',
  'SCR-1202.ky-tra-loi': 'decider',
  'SCR-1202.mo-lai': 'appraiser',
  'SCR-1301.tao': 'appraiser inspector',
  'SCR-1301.duyet-1': 'decider',
  'SCR-1301.duyet-2': 'second_approver',
  'SCR-1301.tu-choi': 'decider second_approver',
  'SCR-1301.huy': 'appraiser inspector',
  'SCR-1302.tra-cuu': 'appraiser inspector',
  'SCR-1302.kiem-tra': 'inspector',
  'SCR-1302.xuat': 'inspector',
  'SCR-1401.xem': 'appraiser inspector',
  'SCR-1401.xu-ly': 'appraiser inspector*',
  'SCR-1401.ket-luan': 'inspector',
  'SCR-1401.nguong': 'policy_author',
  'SCR-1501.soan': 'tenant_admin* policy_author',
  'SCR-1501.gui-duyet': 'policy_author',
  'SCR-1502.soan': 'policy_author',
  'SCR-1503.chay': 'policy_author',
  'SCR-1503.xem-duyet': 'decider',
  'SCR-1503.ky-duyet': 'decider',
  'SCR-1504.cau-hinh': 'policy_author',
  'SCR-1505.thu-vien': 'wf_template_author second_approver*',
  'SCR-1505.soan': 'wf_template_author',
  'SCR-1505.chay-test': 'wf_template_author',
  'SCR-1505.de-nghi-phat-hanh': 'wf_template_author',
  'SCR-1505.phe-duyet': 'second_approver',
  'SCR-1505.trien-khai-cell': 'wf_template_author',
  'SCR-1506.xem': 'platform_admin tenant_admin',
  'SCR-1506.chuyen-nguoi-xu-ly': 'platform_admin* tenant_admin',
  'SCR-1506.gia-han-buoc': 'platform_admin* tenant_admin',
  'SCR-1506.so-do': 'platform_admin tenant_admin',
  'SCR-1601.xem': 'leader manager inspector*',
  'SCR-1601.nhac': 'leader manager',
  'SCR-1601.giao-lai': 'leader* manager',
  'SCR-1601.nguon-ai': 'leader manager',
  'SCR-1602.dung': 'leader manager',
  'SCR-1602.chia-se': 'leader manager',
  'SCR-1603.xem': 'leader manager appraiser inspector',
  'SCR-1603.mo-c2': 'leader manager appraiser inspector',
  'SCR-1603.xuat': 'leader manager appraiser inspector',
  'SCR-1701.hoi': 'manager member',
  'SCR-1701.dung-goi-y': 'manager member',
  'SCR-1701.trinh': 'manager member',
  'SCR-1801.soan': 'tenant_admin',
  'SCR-1801.gui-duyet': 'tenant_admin',
  'SCR-1801.duyet': 'tenant_admin*',
  'SCR-1801.quay-lui': 'tenant_admin',
  'SCR-1802.dung': 'tenant_admin policy_author',
  'SCR-1802.dua-vao-pack': 'tenant_admin',
};

/** Khoá năng lực nền, không mở màn hình: `screenKeys()` bỏ qua khi gom khoá cho menu và tuyến. */
export const NON_ENTRY_KEYS: readonly PermissionKey[] = ['SCR-0501.khoa-nguoi-nhan'];

/** Vai trò phạm vi nền tảng (vùng điều phối toàn cục): không cần phân công đơn vị trong tenant. */
export const PLATFORM_SCOPE_ROLES: readonly string[] = ['platform_admin'];

/** Module API của ma trận (`/api/v1/<module>`). */
export type ApiModule =
  | 'okr'
  | 'tasks'
  | 'plans'
  | 'catalog'
  | 'products'
  | 'evidences'
  | 'appraisals'
  | 'policies'
  | 'dossiers'
  | 'oversight'
  | 'config'
  | 'periods'
  | 'org'
  | 'files'
  | 'ledger'
  | 'esign'
  | 'notifications'
  | 'analytics'
  | 'ai'
  | 'ui'
  | 'registry'
  | 'workflow'
  | 'jobs'
  | 'search'
  | 'iam';

/**
 * Vai trò được gọi từng phương thức của module API, cách nhau bởi dấu cách; hậu tố `*` đánh dấu
 * ô suy luận. Phương thức không có trong bảng: không vai trò người dùng nào được gọi.
 */
export const API_PERMISSION_TABLE: Readonly<
  Record<ApiModule, Readonly<Partial<Record<string, string>>>>
> = {
  okr: {
    GET: 'leader manager member inspector*',
    POST: 'leader manager member',
    PUT: 'manager member',
    DELETE: 'manager* member*',
  },
  tasks: {
    GET: 'platform_admin* tenant_admin* policy_author* leader manager member appraiser* decider* second_approver* inspector*',
    POST: 'tenant_admin* leader manager member',
    PUT: 'leader* manager member*',
    PATCH: 'leader* manager',
    DELETE: 'leader* manager',
  },
  plans: {
    GET: 'leader manager member* appraiser',
    POST: 'leader manager appraiser*',
    PUT: 'manager',
  },
  catalog: {
    GET: 'policy_author leader* manager member appraiser*',
    POST: 'policy_author manager',
    PUT: 'policy_author* manager',
  },
  products: {
    GET: 'manager* member',
    POST: 'member',
  },
  evidences: {
    GET: 'leader manager member appraiser inspector',
    POST: 'tenant_admin* manager member',
    PUT: 'member',
  },
  appraisals: {
    GET: 'leader manager member appraiser decider inspector',
    POST: 'leader manager member appraiser decider inspector*',
    PUT: 'leader member appraiser*',
  },
  policies: {
    GET: 'tenant_admin* policy_author leader* appraiser* decider',
    POST: 'policy_author decider',
    PUT: 'policy_author',
  },
  dossiers: {
    GET: 'leader manager member appraiser decider',
    POST: 'leader* manager* member appraiser decider',
    PUT: 'leader* manager* member*',
    PATCH: 'leader* member* appraiser*',
  },
  oversight: {
    GET: 'manager* member appraiser decider second_approver inspector',
    POST: 'manager* member appraiser decider second_approver inspector',
    PUT: 'member appraiser*',
  },
  config: {
    GET: 'platform_admin tenant_admin policy_author* leader manager member appraiser decider second_approver inspector',
    POST: 'platform_admin tenant_admin leader second_approver',
    PUT: 'tenant_admin policy_author',
    PATCH: 'platform_admin tenant_admin',
    DELETE: 'platform_admin tenant_admin*',
  },
  periods: {
    GET: 'tenant_admin policy_author* leader manager member appraiser decider second_approver inspector',
    POST: 'tenant_admin leader second_approver',
  },
  org: {
    GET: 'platform_admin* tenant_admin policy_author* leader manager member appraiser decider second_approver inspector',
    POST: 'platform_admin* tenant_admin policy_author* leader* manager* member* appraiser* decider* second_approver* inspector*',
    PUT: 'platform_admin* tenant_admin policy_author* leader manager member appraiser decider second_approver inspector',
    PATCH: 'tenant_admin',
  },
  files: {
    GET: 'leader manager member appraiser decider inspector',
    POST: 'tenant_admin* manager member',
    PUT: 'member',
    DELETE: 'member*',
  },
  ledger: {
    GET: 'appraiser inspector',
    POST: 'inspector',
  },
  esign: {
    GET: 'leader member appraiser decider second_approver*',
    POST: 'leader member appraiser decider second_approver',
    DELETE: 'leader* member* appraiser* decider* second_approver*',
  },
  notifications: {
    GET: 'platform_admin tenant_admin policy_author leader manager member appraiser decider second_approver inspector',
    POST: 'platform_admin* tenant_admin policy_author leader manager member appraiser decider second_approver inspector',
    PUT: 'platform_admin* tenant_admin* policy_author* leader* manager* member* appraiser* decider* second_approver* inspector*',
    DELETE: 'platform_admin* tenant_admin* policy_author* leader* manager* member* appraiser* decider* second_approver* inspector*',
  },
  analytics: {
    GET: 'leader manager appraiser inspector',
    POST: 'leader manager appraiser inspector',
    PUT: 'leader manager',
  },
  ai: {
    GET: 'leader manager member',
    POST: 'manager member',
  },
  ui: {
    GET: 'platform_admin* tenant_admin policy_author leader manager member appraiser decider second_approver inspector',
    POST: 'tenant_admin policy_author*',
    PUT: 'tenant_admin policy_author*',
  },
  registry: {
    GET: 'platform_admin',
    POST: 'platform_admin',
  },
  workflow: {
    GET: 'platform_admin* tenant_admin policy_author* leader manager member appraiser decider second_approver inspector',
    POST: 'platform_admin* tenant_admin* leader manager member appraiser decider second_approver inspector',
    PUT: 'platform_admin*',
  },
  jobs: {
    GET: 'platform_admin tenant_admin policy_author leader manager member appraiser decider second_approver inspector',
    DELETE: 'platform_admin* tenant_admin* policy_author* leader* manager* member* appraiser* decider* second_approver* inspector*',
  },
  search: {
    GET: 'leader manager member appraiser decider second_approver inspector',
  },
  iam: {
    POST: 'tenant_admin*',
  },
};
