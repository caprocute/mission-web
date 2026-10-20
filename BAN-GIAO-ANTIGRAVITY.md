# Hướng dẫn bàn giao dự án Mission cho Antigravity

> Ngày 20/10/2026. Các luồng Claude tạm dừng toàn bộ vì hết hạn mức sử dụng trong tuần; người dùng giao phần việc còn lại, cả backend lẫn web, cho Antigravity làm tiếp. Tệp này là điểm vào duy nhất: đọc nó trước, rồi đi theo các đường dẫn bên trong. Trạng thái dưới đây được đối chiếu với git lúc bàn giao (main sau lần gộp đóng gói ngày 20/10/2026). Cột trạng thái trong `docs/tasks/README.md` chậm hơn thực tế, nên khi hai nguồn lệch nhau thì tin git và tệp này.

## 1. Đọc gì trước khi làm

Trước khi sửa bất kỳ dòng mã nào, người hoặc agent tiếp nhận cần đọc theo thứ tự sau. Thứ nhất là `AGENTS.md`, gồm mười hai quy tắc bắt buộc; `CLAUDE.md` chỉ trỏ về tệp này. Thứ hai là quy định riêng theo mảng trong `docs/quy-dinh/` (`BACKEND.md` cho task `NEN-`, `CORE-`, `DV-`, `DL-`; `HA-TANG.md` cho `INF-`, `VH-`; `DI-DONG.md` cho `MOB-`). Thứ ba là kế hoạch toàn hệ thống `docs/KE-HOACH-TONG-THE-HE-THONG.md` (đường găng, chia luồng B1..B11, ba đợt) và danh mục task `docs/tasks/DANH-MUC-TOAN-HE-THONG.md`. Thứ tư là `wiki/README.md`, mục lục tri thức; trang 14 ghi các quyết định đã chốt và câu hỏi còn mở, trang 15a là ma trận phân quyền, trang 16 là quy định nội dung giao diện.

Bộ tài liệu thiết kế là nguồn sự thật duy nhất: baseline và TL-01..TL-05 cùng các bổ sung BS-01 (cấu hình Flowable) và BS-02 (ADR-32 API-first). Bản Markdown đọc được của toàn bộ bộ tài liệu này đã được chép vào repo ở `docs/thiet-ke/` (baseline `00-co-so-thiet-ke-chung.md`, TL-01..TL-05, BS-01, BS-02 và các tệp `_ghi-chu-*` ghi điểm cần lưu ý khi viết). `AGENTS.md` và một số trang wiki còn trỏ tới đường dẫn cũ `/mnt/project-files/kien-truc-okr-kpi/src/…` của môi trường Claude; khi gặp đường dẫn đó, hãy đọc tệp cùng tên trong `docs/thiet-ke/`. Bản .docx chính thức không nằm trong repo, người dùng giữ. Danh sách khoảng 130 điểm lệch giữa các tài liệu đã phát hiện khi lập kế hoạch ở `docs/DIEM-LECH-KHI-LAP-KE-HOACH.md`.

## 2. Những quy ước người dùng đã chốt, không được làm khác

Các quy ước dưới đây là quyết định của người dùng, đã áp dụng trong toàn bộ mã hiện có. Làm khác sẽ phải sửa lại.

- **API-first (ADR-32, YC-PT-09).** Hợp đồng OpenAPI 3.1 ở `contracts/openapi/` (tệp chung và 11 tệp theo mô-đun, khoảng 351 thao tác, Redocly sạch) là ranh giới giữa web và backend. Khi mã và hợp đồng lệch nhau thì sửa mã, không sửa hợp đồng; muốn đổi hợp đồng thì ghi đề nghị vào repo (wiki trang 14 hoặc tệp đề nghị cạnh hợp đồng) và chờ người dùng duyệt. Web sinh kiểu từ hợp đồng (`npm run api:check` kiểm sự khớp).
- **Hệ thống mới hoàn toàn.** Không vá hệ cũ, không chuyển đổi dữ liệu từ hệ hiện hành; nhóm task LEG và DL-09..11 đã bị loại khỏi kế hoạch.
- **Hệ ngoài làm với bản giả lập (YC-PT-13).** Các kết nối ra ngoài (ký số, đồng bộ nhân sự, liên thông…) làm đủ bộ kết nối nhưng kiểm thử với bản giả lập, cấu hình kết nối tách khỏi mã.
- **Công thức là cấu hình động (YC-PT-14).** Công thức tính điểm, bảng xếp loại, tham số và mẫu biểu nằm trong Policy Pack có phiên bản; bản hiện tại là bản giả lập đã đánh dấu rõ (DL-01..03, DL-05).
- **Nội dung giao diện (YC-PT-10, wiki trang 16).** Giao diện cho cán bộ, viên chức chỉ hiện thông tin họ cần, văn phong nghiêm túc; không hiện mã SCR/QT/BR, tên trường, mã trạng thái thô hay mã HTTP. Có hai lớp kiểm tra tự động: `npm run check:ui-text` và `e2e/gia-lap.noi-dung.spec.ts`.
- **Phân quyền.** Ẩn hiện menu, tuyến, nút chỉ theo ma trận `docs/phan-quyen/ma-tran.json` (sinh ra `wiki/15a`), vai trò AC-01..AC-12; máy chủ là nguồn sự thật cuối cùng.
- **Quy mô định cỡ** 6,5 triệu tài khoản, 300.000 người dùng đồng thời; căn cứ pháp lý QĐ 366-QĐ/TW, NĐ 335/2025, NĐ 233/2026.
- **Không commit bí mật.** Thông tin truy cập server dev nằm trong `.env` ở gốc repo và `infra/dev/.env`, cả hai đã bị `.gitignore`; chỉ sửa `.env.example`.

## 3. Tiến độ trên toàn hệ thống

> **Cập nhật ngày 20/10/2026 (`YC-PT-17`)**: Toàn bộ tiến độ thời gian thực của 401 task đã được hợp nhất vào một tệp duy nhất tại gốc repo: [`TIEN-DO-HE-THONG.md`](TIEN-DO-HE-THONG.md). Mọi AI agent bắt buộc đọc và cập nhật tiến độ vào `TIEN-DO-HE-THONG.md` thông qua `python3 scripts/cap-nhat-tien-do.py`. Bảng dưới đây là ảnh chụp tại thời điểm bàn giao từ Claude sang Antigravity.

Kế hoạch có 336 task thuộc danh mục toàn hệ thống (`docs/tasks/danh-muc-he-thong.tsv`) cộng 65 task web WEB-001..065 lập trước đó. Bảng sau tóm tắt theo nhóm; "xong" nghĩa là đã kiểm thử và có trên main hoặc trên nhánh được ghi rõ.

| Nhóm | Số task | Đã xong | Đang dở | Ghi chú |
|---|---|---|---|---|
| WEB (phân hệ web) | 72 | khoảng 48 (đếm theo lịch sử git) | WEB-040, WEB-052 pha 2 | Chi tiết ở mục 4.1 |
| NEN (nền móng backend, CSDL) | 33 | 10 (NEN-002..005, 007, 009, 010, 016, 030..033, 040) | NEN-006, 011, 013, 015, 017 (bản vá) | Đã gộp vào main theo quyết định của người dùng ngày 20/10 |
| DV (dịch vụ vệ tinh) | 104 | 5 (DV-02-01, DV-03-01..03, DV-04-01) | DV-04-02, DV-04-03 | Trên nhánh B5, chưa gộp |
| CORE (mission-core) | 112 | 0 | 0 | Chưa bắt đầu; phụ thuộc NEN |
| DL (nội dung, dữ liệu nền) | 12 | DL-01, 02, 03, 05 (bản giả lập) | DL-04, DL-07 một phần | Đã gộp vào main |
| INF (hạ tầng, CI/CD) | 28 | INF-01 (trên main), INF-05 (trên nhánh) | INF-01 thử trên kind chưa qua | Chi tiết ở mục 4.3 |
| QA, VH, MOB, CNH | 16, 9, 10, 5 | 0 | 0 | Chưa bắt đầu |

Kết quả kiểm tra trên main lúc bàn giao: `lint`, `api:check`, `check:permissions`, `check:ui-text`, `build:all` đều đạt; kiểm thử đơn vị đạt ở 15 dự án, trừ một ca đỏ có sẵn ở `fe-dashboard` (xem mục 7). Chưa chạy lại e2e Playwright sau lần gộp cuối.

Như vậy phần web đã đi được phần lớn các màn hình nghiệp vụ trên dữ liệu giả lập, còn backend mới ở giai đoạn nền móng: chưa có mission-core, chưa có API thật nào chạy, và web chưa nối API thật (WEB-070..072 chưa làm).

## 4. Nhánh và trạng thái từng luồng

Remote là `https://github.com/caprocute/mission-web`. Mọi nhánh dưới đây đã có trên GitHub. Mỗi luồng đã dừng đều để lại một tệp trạng thái; đọc tệp đó trước khi làm tiếp phần của luồng.

### 4.1. Web (Angular 21, Native Federation, PrimeNG 21)

Các task WEB-001..003, 006..008, 010, 015..019, 021..038, 042..045, 047, 048, 052 (pha 1), 054..059, 063..065 đã có trên main. WEB-039 (xếp loại năm, phân nhóm triển vọng) và WEB-046 (test case, luồng duyệt) đã xong, kiểm thử đạt và được gộp vào main trong lần đóng gói này.

| Task | Trạng thái | Nhánh, tệp trạng thái |
|---|---|---|
| WEB-040 Hội nghị kiểm điểm (SCR-1101) | Dở: mới có model và `DossierApi` trong `@vt/api-client`; còn mock, remote `fe-dossier`, kiểm thử | `feat/WEB-040-hoi-nghi-kiem-diem`, tệp `docs/tasks/BAN-GIAO-LUONG-OKR-DANH-GIA.md` trên nhánh đó có kế hoạch từng bước |
| WEB-052 pha 2 Tối ưu hiệu năng | Dở, kiểm thử chưa kiểm chứng | `feat/WEB-052-pha-2` |
| WEB-063 bổ sung hợp đồng quyết định lô | Một commit sửa hợp đồng chưa gộp; cần người dùng duyệt vì là sửa hợp đồng | `fix/WEB-063-quyet-dinh-lo` |
| WEB-041, 049, 050, 061, 062 | Chưa làm | Thẻ task trong `docs/tasks/`; WEB-061 dùng lại kiểu và mock workflow-template của WEB-046 |
| WEB-004, 005, 009, 011..014, 020, 051, 053, 060 | Chưa làm hoặc chỉ có phần khung, xem thẻ task | `docs/tasks/WEB-0xx-*.md` |
| WEB-070..076 | Chưa làm: nối API thật, xử lý cắt tải, traceparent, khám phá cell, cờ tính năng | Phụ thuộc backend có API chạy thật |

Tệp trạng thái của hai luồng màn hình: `docs/ban-giao/luong-giam-sat-dieu-hanh-cau-hinh.md` (fe-config-studio, fe-admin) và `docs/tasks/BAN-GIAO-LUONG-OKR-DANH-GIA.md` (OKR, minh chứng, đánh giá; kèm 6 đề nghị chỉnh hợp đồng còn treo). Báo cáo cuối và ảnh chụp màn hình của từng task web nằm ngoài repo, ở thư mục dùng chung của project Claude (`bao-cao-luong-okr/`, `kiem-thu/`); người dùng có thể cấp riêng nếu cần, nhưng không bắt buộc để làm tiếp.

### 4.2. Backend

| Luồng | Nhánh | Trạng thái | Tệp trạng thái |
|---|---|---|---|
| B1 Nền móng backend và CSDL (Java 21, Maven) | `claude/b1-nen-mong-backend-8pacfm` | Phần đã xong (NEN-002..005, 007, 009, 010, 016, 030..033, 040) đã gộp vào main theo quyết định của người dùng. `./mvnw -B verify` xanh ở NEN-010 | `backend/TRANG-THAI-B1.md` |
| B5 Định danh và tổ chức | `claude/b5-dinh-danh-to-chuc-1o4gth` | Xong trên nhánh: DV-02-01 mẫu realm Keycloak (16/16 kiểm thử trên Keycloak 26.4 thật), DV-03-01..03 dịch vụ Go tenant-registry, DV-04-01. Dở: DV-04-02 (5 bộ kiểm thử chưa xác nhận), DV-04-03 mới có lớp nghiệp vụ. Chưa gộp main | `docs/tasks/BAN-GIAO-B5.md` trên nhánh |
| B6 Quy trình, sổ cái, ký số, tài liệu | `claude/b6-flowable-so-cai-ky-so-t54jlp` | Dừng sớm, chưa có mã riêng (nhánh chỉ chứa commit gộp kế hoạch) | Không có |

Bản vá dở của B1 (NEN-006, 011..013, 015, 017) chưa vào nhánh nào; chúng đã được chép vào repo ở `docs/ban-giao/b1-ban-do-dang/`, mỗi bản vá ghi commit gốc để `git apply` trong `TRANG-THAI-B1.md` (thư mục `backend/`). Chưa bản nào chạy `verify` đầy đủ.

### 4.3. Hạ tầng và nội dung

| Luồng | Nhánh | Trạng thái | Tệp trạng thái |
|---|---|---|---|
| B8 Hạ tầng triển khai và CI/CD | `claude/b8-ha-tang-g7g48k` | INF-01 (chart thư viện `vt-service`) đã gộp main, qua kiểm tra tĩnh 23/23 nhưng thử trên kind dừng vì hết thời gian chờ, chưa rõ nguyên nhân. INF-05 (thư viện pipeline Jenkins, Semgrep, gitleaks) xong phần làm được, chưa gộp; wiki chưa viết | `docs/tasks/BAN-GIAO-B8-HA-TANG.md` trên nhánh |
| B11 Nội dung và dữ liệu nền | `claude/b11-noi-dung-26rl4b` | Đã gộp main (DL-01, 02, 03, 05 bản giả lập) | Thẻ task DL đã cập nhật |

Các nhánh `feat/…`, `fix/…`, `claude/…` khác trên GitHub đều đã gộp vào main, có thể bỏ qua.

## 5. Thứ tự làm tiếp đề xuất

Thứ tự dưới đây bám đường găng trong kế hoạch tổng thể (mục 8): backend nền móng chặn mọi thứ phía sau, nên làm trước.

1. **Hoàn tất nền móng backend B1:** gộp hoặc làm lại các bản vá NEN-006, 011, 012, 013, 015, 017, rồi các NEN chưa bắt đầu (NEN-008, 014, 018..022, 034, 036, 042, 043). NEN-006 (sinh mã server từ OpenAPI) và NEN-011 (outbox) là điều kiện để CORE chạy được.
2. **Định danh và tổ chức B5:** xong DV-04-02, DV-04-03, gộp B5, rồi các DV khác theo luồng trong kế hoạch.
3. **mission-core (CORE-00..08)** theo hợp đồng OpenAPI và TL-03; song song là B6 (Flowable, sổ cái, ký số, tài liệu).
4. **Web:** gộp hoặc làm tiếp WEB-040 rồi WEB-041; WEB-050, 061, 062 (cấu hình); WEB-049 (trợ lý AI). Khi backend có API thật thì làm WEB-070..076 để chuyển từ mock sang API thật.
5. **Hạ tầng B8:** tìm nguyên nhân INF-01 treo trên kind, gộp INF-05, rồi các INF còn lại; sau đó QA, VH, MOB, CNH.

Giới hạn làm việc người dùng đặt cho Claude, nên giữ cho mọi công cụ: tối đa 5 luồng chạy đồng thời (YC-PT-12); mỗi luồng một nhánh và worktree riêng; chỉ một bên được gộp main.

## 6. Cách chạy và kiểm thử

**Web** (thư mục `frontend/`):

```bash
cd frontend && npm ci                      # .npmrc đã đặt legacy-peer-deps
npx ng serve fe-task --port 4201           # remote
npx ng serve fe-shell --port 4200          # host, mở http://localhost:4200
```

`public/config.json` mặc định bật `useMockApi` và tắt đăng nhập, nên chạy được không cần backend. Trước khi đẩy main, chạy đủ các bước sau và chỉ đẩy khi tất cả xanh: `npm run lint`, `npm run api:check`, `npm run check:permissions`, `npm run check:ui-text`, kiểm thử đơn vị (chạy riêng từng dự án; `test:all` chạy gộp hay bị treo trong môi trường cloud), `npm run build:all` hoặc `npx ng build fe-task && npx ng build fe-shell` cùng các remote bị đụng tới, và e2e Playwright (`npx playwright test`). Tên script chính xác xem trong `frontend/package.json`. Theo AGENTS.md quy tắc 11, màn hình phải được mở thật trên trình duyệt, không có lỗi console.

**Backend Java** (thư mục `backend/`): `./mvnw -B verify`; kiểm thử tích hợp dùng Testcontainers nên cần Docker. **Dịch vụ Go** (`services-go/`): `go test ./...`. **Hợp đồng:** `npx @redocly/cli lint contracts/openapi/...` theo cấu hình trong repo. **Hạ tầng:** chart ở `deploy/` và `infra/`, hạ tầng dev dùng chung chạy bằng Docker Compose theo `infra/dev/README.md` (project `mission-dev`, dải cổng 35xxx; không đụng container ngoài project này).

Bài học khi gộp nhánh: luôn xem `git diff --stat main...<nhánh>` trước khi gộp và đối chiếu với phạm vi task, vì đã từng có commit vô tình hoàn tác tệp của task khác mà kiểm thử vẫn xanh. Sau khi giải xung đột, tìm dấu xung đột còn sót bằng `git grep -n '^<<<<<<<'`.

## 7. Lỗi có sẵn trên main

- Kiểm thử đơn vị `fe-dashboard` có 1 ca đỏ ổn định: "so với quý trước thêm cột và chênh lệch…" (SCR-1601) không thấy `55%` ở dòng đơn vị `u-vp`. Ca này đỏ cả trên hai bản main trước lần gộp đóng gói (17c96d6 và d7828ad), nên không do lần gộp gây ra; nhiều khả năng dữ liệu giả lập phụ thuộc ngày hiện tại (suy luận).
- `ng test fe-config-studio` chạy xong 16/16 nhưng tiến trình không tự thoát; đặt timeout khi chạy. Dự án `telemetry` chưa có tệp kiểm thử nên `ng test telemetry` báo lỗi "No tests found".
- e2e có 2 lỗi lặp lại ổn định: tuyến `/config-studio/goi/E2E-1/E2E-1` của tenant_admin và policy_author bị chuyển hướng sang `/cong-thuc`.
- `check:ui-text` còn một số chỗ cũ ở analytics, policies, config-studio.
- `esignSessionId` khi ký phê duyệt chưa nối luồng ký số thật; client tổ chức của WEB-017/018 chưa đưa hết về đúng hợp đồng.

## 8. Việc còn chờ người dùng quyết định

1. Sáu đề nghị chỉnh hợp đồng của luồng OKR, đánh giá (liệt kê trong `docs/tasks/BAN-GIAO-LUONG-OKR-DANH-GIA.md`) và commit hợp đồng ở `fix/WEB-063-quyet-dinh-lo`.
2. Có gộp B5 vào main hay không (mặc định: chưa gộp). Ba điểm B5 chờ chốt: tên realm dùng chung, cách đếm số lần sai mật khẩu để khoá tài khoản, nâng Go lên 1.25 để vá lỗ hổng; 16 ghi chú của B5 chưa đưa vào wiki trang 14 (nằm trong `docs/tasks/BAN-GIAO-B5.md`).
3. Thư viện `disruptor` 4.0.0 trong BOM backend nằm ngoài danh sách thư viện được phép.
4. Tên claim tenant lệch giữa TL-01 (`tenant_id`), TL-03 (`tid`) và realm dev (`tenant`); mã hiện dùng cấu hình `okrkpi.tenant.claim`, mặc định `tenant_id`.
5. Dùng `ThreadLocal` thay `ScopedValue` (preview trên Java 21) cho ngữ cảnh tenant.
6. Cột header của outbox (`trace_parent`, `classification`, `schema_id`) chưa có trong DDL TL-03; topic `sys.audit.v1` không qua CHECK bốn đoạn.
7. Các câu hỏi mở cũ ở wiki trang 14, trong đó có 409 hay 412 khi xung đột `If-Match` và thời gian sống token.
8. Kiểm thử tích hợp thật trên server dev (E2E với Keycloak và backend thật) chưa được người dùng trả lời.

## 9. Ghi chép yêu cầu của người dùng

Mọi yêu cầu thường trực của người dùng được ghi trong sổ tay yêu cầu (mã YC-GT, YC-TK, YC-PT, YC-HT; 32 quy tắc lúc bàn giao). Bản chụp lúc bàn giao nằm ở `docs/ban-giao/SO-TAY-YEU-CAU.md`. Bên tiếp nhận nên đọc sổ tay này trước khi làm và tiếp tục ghi yêu cầu mới theo đúng mẫu: mỗi quy tắc có mã, nội dung, lý do, cách kiểm tra, nguồn, kèm một dòng trong bảng nhật ký bổ sung.
