# QUY ĐỊNH CHUNG CỦA DỰ ÁN MISSION

> Tệp này là quy định bắt buộc cho mọi người và mọi AI agent làm việc trong repo. Mỗi task trong `docs/tasks/` đều mở đầu bằng lời nhắc đọc tệp này; agent phải đọc hết tệp này **trước khi** đọc task và trước khi sửa bất kỳ dòng mã nào. Khi quy định ở đây mâu thuẫn với nội dung trong một task, quy định ở đây thắng, và agent ghi lại mâu thuẫn trong báo cáo cuối task.

## 1. Dự án này là gì

Mission là phân hệ web (SVC-17 `web-frontend`) của nền tảng giao nhiệm vụ, quản lý OKR/KPI và đánh giá cán bộ theo QĐ 366-QĐ/TW và các quy định đánh giá cán bộ Nhà nước (NĐ 335/2025, NĐ 233/2026), quy mô định cỡ 6,5 triệu tài khoản và 300.000 người dùng đồng thời (NFR-01). Thiết kế đã được chốt trong bộ bốn tài liệu và là **nguồn sự thật duy nhất**:

| Mã | Tài liệu | Bản đọc được (Markdown) |
|---|---|---|
| Baseline | Cơ sở thiết kế chung: mã ADR, SVC, MOD, EVT, QT, SCR, NFR, trạng thái chuẩn | `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` |
| TL-01 | Thiết kế kiến trúc chi tiết (chương 7: giao diện theo tenant) | `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` |
| TL-02 | Đặc tả quy trình nghiệp vụ và giao diện (QT, SCR, BR, MSG) | `/mnt/project-files/kien-truc-okr-kpi/src/TL-02-quy-trinh-nghiep-vu.md` |
| TL-03 | Thiết kế CSDL và đồng bộ dữ liệu | `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` |
| TL-04 | Triển khai, hiệu năng, dự phòng, ATTT (SEC, OPS, PERF, DR) | `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` |

Bản .docx chính thức nằm ở `/mnt/project-files/kien-truc-okr-kpi/`. Wiki tri thức trong `wiki/` tóm tắt các tài liệu này cho người làm frontend; khi wiki và TL lệch nhau, TL thắng và wiki phải được sửa.

### Phạm vi áp dụng: toàn hệ thống

Từ kế hoạch tổng thể (`docs/KE-HOACH-TONG-THE-HE-THONG.md`), repo này là repo chung của toàn hệ thống Mission: phân hệ web, máy chủ (mission-core và các microservice), hợp đồng, hạ tầng, di động, dữ liệu và vận hành. Tệp này áp cho mọi task. Các quy tắc viết riêng cho web (quy tắc 5, 7, 8, 9, 10 và lệnh build ở quy tắc 11) áp cho task web; task phân hệ khác áp thêm quy định riêng: `docs/quy-dinh/BACKEND.md` cho task `NEN-`, `CORE-`, `DV-`, `DL-`; `docs/quy-dinh/HA-TANG.md` cho `INF-`, `VH-`; `docs/quy-dinh/DI-DONG.md` cho `MOB-`; task `QA-` và `LEG-` áp quy định của phần mà task đụng tới. Tài liệu thiết kế còn gồm TL-05 (`TL-05-dinh-co-he-thong.md`, định cỡ theo quy mô) và các bổ sung BS-01, BS-02 trong `/mnt/project-files/kien-truc-okr-kpi/bo-sung/`. Danh mục task toàn hệ thống: `docs/tasks/DANH-MUC-TOAN-HE-THONG.md`.

## 2. Mười hai quy tắc không được vi phạm

1. **Bám thiết kế, không tự sáng tác kiến trúc.** Mọi màn hình phải truy vết được QT → SCR → API → MOD/SVC → bảng/EVT. Không thêm thư viện lớn, không đổi framework, không đổi kiểu kiến trúc. Stack đã chốt: Angular 21 + Native Federation + PrimeNG 21 (preset Aura qua `@vt/design-system`), Formly 10 + JSON Schema + cel-js, angular-oauth2-oidc với Keycloak 26 (ADR-05, ADR-08, ADR-10). Muốn thêm phụ thuộc mới phải nêu lý do trong PR và được người phụ trách duyệt.
2. **Giữ nguyên mã định danh.** Dùng đúng mã ADR, SVC, MOD, EVT, QT, SCR, BR, MSG, NFR, SEC, tên schema, tên bảng và giá trị trạng thái (`DA_GIAO`, `CHO_NGHIEM_THU`…) như baseline. Không đặt mã mới trong dải đã cấp cho tài liệu khác, không đổi nghĩa mã cũ.
3. **Chỉ làm đúng phạm vi task.** Không sửa tệp ngoài danh sách "Tệp được phép sửa" của task, trừ khi bắt buộc để build chạy; khi đó ghi rõ trong báo cáo. Không "tiện tay" refactor.
4. **Không bao giờ commit bí mật.** `.env` ở mọi cấp đã nằm trong `.gitignore`; không chép mật khẩu, token, khoá vào mã, tài liệu, wiki, log hay thông điệp commit. Chỉ cập nhật `.env.example` (không có giá trị thật).
5. **An toàn giao diện theo TL-01 mục CSP.** Không dùng `innerHTML`, `bypassSecurityTrust*`, `eval`, `new Function`, thư viện sinh mã lúc chạy (Ajv chế độ mặc định). SDUI chỉ dựng component có trong registry. Dữ liệu C2 không lưu cục bộ dạng rõ; dữ liệu C3 chỉ xử lý theo luồng E2EE đã thiết kế (ADR-06).
6. **Máy chủ là nguồn sự thật.** Kiểm tra ở trình duyệt (CEL, vai trò, trạng thái) chỉ để phản hồi nhanh; không bao giờ coi đó là kiểm soát quyền hay kiểm tra nghiệp vụ cuối cùng. Khi ẩn hoặc hiện menu, tuyến, nút theo vai trò, tham chiếu duy nhất là ma trận phân quyền ở `wiki/15a-ma-tran-phan-quyen.md` (sinh từ `docs/phan-quyen/ma-tran.json`); không tự đặt danh sách vai trò trong mã.
7. **Quy ước API mục 13 baseline.** `/api/v1/<module>`, ghi kèm `Idempotency-Key`, cập nhật kèm `If-Match` (409 khi xung đột), phân trang con trỏ `limit ≤ 200`, lỗi RFC 9457, tác vụ dài 202 + `Location`. Mọi lời gọi đi qua `@vt/api-client`, không gọi `fetch` trực tiếp trong component.
8. **Angular hiện đại.** Standalone component, `inject()`, signals (`signal`, `computed`, `linkedSignal`, `rxResource`), `input()`/`output()`, control flow `@if/@for`. Không tạo NgModule mới. Không ghi signal bên trong `computed` (lỗi NG0600). Thư viện dùng chung mà remote cần thì shell cũng phải dùng ít nhất một lần (lỗi NG0203 với `ignoreUnusedDeps`).
9. **Trải nghiệm và khả năng truy cập là yêu cầu chức năng.** WCAG 2.1 AA, thao tác thường ngày ≤ 3 chạm, chế độ "Đảng viên cao tuổi" (`data-mode="elderly"`, cờ `reducedSteps`) phải hoạt động trên mọi màn hình mới (NFR-11). Văn bản giao diện bằng tiếng Việt có dấu, đúng thuật ngữ trong TL-02. Giao diện là sản phẩm cho cán bộ, viên chức: chỉ hiện thông tin người dùng cần, văn phong nghiêm túc, chỉn chu, đơn giản, không kỹ thuật; không in ghi chú giải pháp, mã SCR/QT/BR/MSG/ADR, tên bảng, tên trường, mã trạng thái thô (`DA_GIAO`…) hay tên API lên màn hình, mà dùng nhãn nghiệp vụ OKR/KPI chuẩn (mã chỉ đặt trong chú thích mã nguồn, `data-*` hoặc nhật ký). Quy định chi tiết, bảng từ thay thế và hai lớp kiểm tra tự động (`npm run check:ui-text`, `e2e/gia-lap.noi-dung.spec.ts`) ở `wiki/16-noi-dung-giao-dien.md`.
10. **Ngân sách hiệu năng.** Shell + design system ≤ 250 KB gzip, mỗi remote tải đầu ≤ 180 KB gzip, UI Pack ≤ 30 KB, LCP ≤ 2,5 s trên 4G, INP ≤ 200 ms (TL-01 "Ngân sách hiệu năng"). Component nặng phải nạp lười.
11. **Có kiểm thử và đã chạy thử.** Task chỉ hoàn thành khi `npx ng build fe-task && npx ng build fe-shell` (và remote mà task đụng tới) thành công, kiểm thử của task chạy xanh, và màn hình đã được mở thật trên trình duyệt (Playwright hoặc thủ công) không có lỗi console.
12. **Báo cáo trung thực.** Cuối task, viết báo cáo theo mục 6. Việc chưa làm, kiểm thử chưa chạy, giả định đã chọn: ghi rõ. Không tuyên bố "xong" khi chưa kiểm chứng.

## 3. Bản đồ repo

```
mission-web/
├── AGENTS.md              ← quy định chung (tệp này); CLAUDE.md trỏ về đây
├── docs/
│   ├── KE-HOACH-TONG-THE-HE-THONG.md        ← kế hoạch toàn hệ thống, đường găng, chia luồng, ma trận phủ
│   ├── KE-HOACH-PHAT-TRIEN-PHAN-HE-WEB.md   ← kế hoạch, giai đoạn, mốc
│   ├── quy-dinh/          ← quy định riêng: BACKEND.md, HA-TANG.md, DI-DONG.md
│   ├── phan-quyen/        ← ma-tran.json (nguồn ma trận phân quyền), gen_matrix.py, ma-tran-phan-quyen.xlsx
│   └── tasks/             ← task cho AI agent: README (danh mục, thứ tự), _MAU-TASK.md, WEB-xxx-*.md
├── wiki/                  ← tri thức chi tiết; README.md là mục lục
├── frontend/              ← Angular workspace (fe-shell, fe-task, thư viện @vt/*)
├── deploy/web/            ← Dockerfile + nginx cho SVC-17
├── infra/dev/             ← Docker Compose hạ tầng dùng chung dev (xem README trong đó)
├── scripts/agent-task.sh  ← in quy định chung + task thành một prompt cho agent
└── .env(.example)         ← thông tin truy cập server dev (không commit)
```

## 4. Cách chạy nhanh

```bash
cd frontend && npm ci                 # .npmrc đã đặt legacy-peer-deps
npx ng serve fe-task --port 4201      # remote
npx ng serve fe-shell --port 4200     # host, mở http://localhost:4200
npx ng build fe-task && npx ng build fe-shell
```

Mặc định `public/config.json` bật `useMockApi` và tắt đăng nhập; để thử với Keycloak dev, đặt `auth.enabled = true` và `issuer` trỏ tới realm `mission` trên server dev (cổng xem `infra/dev/.env.example`).

## 5. Quy trình làm một task

1. Đọc tệp này, rồi đọc task `docs/tasks/WEB-xxx-*.md` và các trang wiki/TL mà task liệt kê ở mục "Đầu vào bắt buộc đọc".
2. Tạo nhánh `feat/WEB-xxx-<ten-ngan>` (hoặc `fix/…`, `docs/…`).
3. Làm trong phạm vi tệp được phép; commit nhỏ, thông điệp dạng `WEB-xxx: <mô tả tiếng Việt>`, ghi mã SCR/QT liên quan trong thân commit.
4. Chạy build, kiểm thử và mở màn hình trên trình duyệt.
5. Cập nhật wiki nếu task tạo ra tri thức mới (quy ước, bẫy kỹ thuật, quyết định); cập nhật trạng thái task trong `docs/tasks/README.md`.
6. Viết báo cáo cuối task.

## 6. Mẫu báo cáo cuối task

```
## Báo cáo WEB-xxx
- Kết quả: <đã làm gì, màn hình/tệp nào>
- Truy vết: QT-.. → SCR-.. → API .. → MOD-..
- Kiểm chứng: <lệnh build/test đã chạy và kết quả; ảnh chụp nếu có>
- Giả định đã chọn: <...>
- Chưa làm / rủi ro: <...>
- Cập nhật wiki: <trang nào hoặc "không">
```

## 7. Môi trường và bí mật

Server dev dùng chung chạy hạ tầng bằng Docker Compose (project `mission-dev`, dải cổng 35xxx) cùng máy với hệ thống khác; agent **không** được dừng, xoá hay sửa container ngoài project `mission-dev`. Thông tin SSH nằm trong `.env` ở gốc repo, mật khẩu dịch vụ nằm trong `infra/dev/.env`; cả hai không commit. Môi trường dev không chứa dữ liệu thật hay dữ liệu cá nhân.
