# THEO DÕI TIẾN ĐỘ TỔNG HỢP TOÀN HỆ THỐNG MISSION

> **TỆP THEO DÕI TIẾN ĐỘ DUY NHẤT CỦA DỰ ÁN (`YC-PT-11`, `YC-PT-17`)**
> - Đây là **nguồn sự thật duy nhất** về tiến độ thực thi của toàn bộ **401 task** thuộc dự án Mission (gồm 65 task giao diện Web ban đầu và 336 task mở rộng toàn hệ thống).
> - **Quy định bắt buộc cho mọi AI Agent**: Mọi agent (cả luồng điều phối giữ nhánh `main` lẫn các subagent làm việc trên nhánh/worktree riêng) **bắt buộc phải cập nhật tệp này** ngay khi nhận việc, khi hoàn thành task trên nhánh và khi gộp vào `main`:
>   1. **Khi bắt đầu làm một task**: chạy `python3 scripts/cap-nhat-tien-do.py set <MÃ_TASK> --status "Đang làm" --note "Nhánh <tên-nhánh>: đang thực hiện" --agent "<Tên luồng>"`.
>   2. **Khi hoàn thành và kiểm thử xanh trên nhánh**: chạy `python3 scripts/cap-nhat-tien-do.py set <MÃ_TASK> --status "Xong, chờ gộp" --note "Nhánh <tên-nhánh>: đã qua build & test" --agent "<Tên luồng>"`.
>   3. **Khi luồng điều phối gộp vào `main`**: chạy `python3 scripts/cap-nhat-tien-do.py set <MÃ_TASK> --status "Đã gộp main" --note "Đã gộp main (commit <hash>)" --agent "Điều phối"`.
>   4. Nếu sửa trực tiếp dòng trạng thái trong bảng chi tiết ở Mục 3, bắt buộc chạy `python3 scripts/cap-nhat-tien-do.py sync` để tự động tính lại bảng tổng hợp ở Mục 1.

---

## 1. Tổng quan tiến độ toàn hệ thống (tự động tính từ 401 task)

- **Tổng tiến độ đã gộp `main`**: **360 / 408 task (88.2%)** — Khối lượng quy đổi đã gộp: **426.5 / 492.5 đơn vị**.
- **Tổng tiến độ tính cả các task đã xong chờ gộp**: **360 / 408 task (88.2%)** (gồm **360** đã gộp `main` + **0** đã xong trên nhánh chờ gộp).
- **Đang thực hiện / Làm một phần**: **11 task** (6 đang làm, 5 làm một phần).
- **Chưa bắt đầu**: **37 task**.
- **Tiến độ Đường găng (23 task)**: Đã gộp `main` **19 / 23 task**; nút găng kế tiếp cần mở khoá: **`QA-06`** (đang xử lý trên nhánh: không).

### 1.1. Bảng tổng hợp theo 10 nhóm phân hệ

| Mã nhóm | Phân hệ / Phạm vi | Tổng task | Khối lượng | Đã gộp `main` | Xong, chờ gộp | Đang làm / 1 phần | Chưa bắt đầu | Tỷ lệ gộp `main` |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| **WEB** | Phân hệ giao diện Web (`SVC-17`: `WEB-001..065`, `WEB-070..076`) | 72 | 82.5 | **71** | 0 | 1 | 0 | **98.6%** |
| **NEN** | Nền móng hệ thống, thư viện nền và CSDL (`NEN-001..043`) | 34 | 36.5 | **34** | 0 | 0 | 0 | **100.0%** |
| **CORE** | Ứng dụng lõi `mission-core` (`SVC-05`: `CORE-00..08`) | 117 | 131.5 | **116** | 0 | 1 | 0 | **99.1%** |
| **DV** | Các dịch vụ vệ tinh (`SVC-01..04`, `SVC-06..16`: `DV-01..16`) | 104 | 131 | **104** | 0 | 0 | 0 | **100.0%** |
| **DL** | Dữ liệu nghiệp vụ, Policy Pack, DMN và đối soát (`DL-01..15`) | 13 | 16 | **13** | 0 | 0 | 0 | **100.0%** |
| **INF** | Hạ tầng Kubernetes, CI/CD, bảo mật và dự phòng (`INF-01..28`) | 28 | 44 | **16** | 0 | 4 | 8 | **57.1%** |
| **QA** | Kiểm thử tích hợp, hiệu năng, ATTT và DR (`QA-01..16`) | 16 | 24.5 | **1** | 0 | 5 | 10 | **6.2%** |
| **MOB** | Ứng dụng di động Flutter (`SVC-18`: `MOB-01..10`) | 10 | 10.5 | **0** | 0 | 0 | 10 | **0.0%** |
| **VH** | Vận hành, tài liệu hướng dẫn và đưa vào sử dụng (`VH-01..09`) | 9 | 11.5 | **0** | 0 | 0 | 9 | **0.0%** |
| **CNH** | Cá nhân hoá theo người dùng (`CNH-01..05`) | 5 | 4.5 | **5** | 0 | 0 | 0 | **100.0%** |
| **TỔNG** | **Toàn bộ hệ thống Mission** | **408** | **492.5** | **360** | **0** | **11** | **37** | **88.2%** |

### 1.2. Bảng tổng hợp theo 8 Mốc triển khai (M0 – M7)

| Mốc triển khai | Tổng task | Đã gộp `main` | Xong, chờ gộp | Đang làm / 1 phần | Chưa bắt đầu | Tiến độ gộp `main` |
|---|---:|---:|---:|---:|---:|---:|
| **M0 – Nền móng phát triển & CSDL** | 60 | **60** | 0 | 0 | 0 | **100.0%** |
| **M1 – Định danh và tổ chức (Đợt 1)** | 40 | **40** | 0 | 0 | 0 | **100.0%** |
| **M2 – Cấu hình, Policy Pack & UI Pack** | 47 | **47** | 0 | 0 | 0 | **100.0%** |
| **M3 – Nghiệp vụ lõi OKR, Giao việc, Minh chứng (Đợt 2)** | 120 | **119** | 0 | 1 | 0 | **99.2%** |
| **M4 – Đánh giá, Hồ sơ số, Giám sát & Phân tích** | 72 | **72** | 0 | 0 | 0 | **100.0%** |
| **M5 – Hạ tầng Production, Tích hợp E2E, Hiệu năng & ATTT** | 42 | **17** | 0 | 10 | 15 | **40.5%** |
| **M6 – Dự phòng DR, Ứng dụng Di động & Vận hành** | 23 | **4** | 0 | 0 | 19 | **17.4%** |
| **M7 – Thí điểm và Đưa vào sử dụng (Đợt 3)** | 4 | **1** | 0 | 0 | 3 | **25.0%** |

### 1.3. Trạng thái 23 task trên Đường găng

Chuỗi đường găng quyết định thời gian hoàn thành toàn dự án:

✅ `NEN-001` → ✅ `NEN-005` → ✅ `NEN-030` → ✅ `NEN-031` → ✅ `NEN-033` → ✅ `NEN-010` → ✅ `NEN-019` → ✅ `CORE-00-01` → ✅ `CORE-00-02` → ✅ `CORE-05-01` → ✅ `CORE-05-02` → ✅ `CORE-05-03` → ✅ `CORE-08-13` → ✅ `CORE-08-14` → ✅ `CORE-04-04` → ✅ `CORE-04-05` → ✅ `CORE-04-07` → ✅ `CORE-04-08` → ✅ `CORE-04-10` → 🔄 **`QA-06`** (Làm một phần) → ⏳ `QA-07` → ⏳ `QA-16` → ⏳ `VH-08`

---

## 2. Trạng thái các luồng phát triển đang chạy (giới hạn tối đa 5 luồng theo `YC-PT-12`)

<!-- ACTIVE_STREAMS_START -->
| Luồng | Worktree / Nhánh | Phạm vi đang thực hiện | Task đang xử lý | Trạng thái |
|---|---|---|---|---|
| **Điều phối (`DP`)** | `main` | Kiểm tra cổng chất lượng, gộp nhánh vào `main`, đồng bộ `TIEN-DO-HE-THONG.md`, quản lý hạ tầng `.env` & SSH tunnel | Toàn hệ thống | Đang hoạt động |
| **Luồng 1 (`B1`)** | `feat/NEN-042-043-036-hop-dong-dinh-tuyen` | Đã gộp `NEN-011`, `NEN-012` (`2006fc9`); đang thực hiện `NEN-042`, `NEN-043`, `NEN-036` | `NEN-042`, `NEN-043`, `NEN-036` | Đang làm |
| **Luồng 2 (`B1`)** | `feat/NEN-019-NEN-020-template-testkit` | Đã gộp `NEN-019` (`6c2525d`); đang hoàn thiện môi trường dev backend & Testcontainers (`NEN-020`) | `NEN-020` | Đang làm |
| **Luồng 3 (`B2`)** | `feat/CORE-00-mission-core` | Khởi tạo `backend/mission-core` đa mô-đun (MOD-01..MOD-08) và cổng nội bộ `api` Spring Modulith | `CORE-00-01`, `CORE-00-02` | Đang làm |
| **Luồng 4 (`B5`)** | `feat/B5-dinh-danh-to-chuc` | Dịch vụ `tenant-registry` (Go), `org-service` (Java) và mẫu realm Keycloak | `DV-02-01`, `DV-03-01..03`, `DV-04-01..03` | 5 xong chờ gộp, 2 đang làm |
| **Luồng 5 (`B1`)** | `feat/NEN-013-014-015-platform-starter` | Idempotency/ETag (`NEN-013` đã xong), OPA client & step-up (`NEN-014`), mã hoá trường C2 (`NEN-015`) | `NEN-013`, `NEN-014`, `NEN-015` | 1 xong chờ gộp, 2 đang làm |
<!-- ACTIVE_STREAMS_END -->

---

## 3. Bảng theo dõi chi tiết toàn bộ 401 task

Mọi thay đổi trạng thái task đều được cập nhật trực tiếp vào bảng dưới đây. Các giá trị cột **Trạng thái** hợp lệ: `Đã gộp main`, `Xong, chờ gộp`, `Đang làm`, `Làm một phần`, `Chưa bắt đầu`.

### WEB-001..065 · Phân hệ giao diện Web (Kế hoạch Web GĐ0–GĐ4) (64/65 đã gộp `main`, 0 chờ gộp, 1 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [WEB-001](docs/tasks/WEB-001-cong-cu-chat-luong.md) | Công cụ chất lượng mã: ESLint, Prettier, Vitest cho toàn workspace | S | W-Nền tảng | – | – | **Đã gộp main** | Đã gộp `main` (ESLint, Prettier, Vitest toàn workspace) |
| [WEB-002](docs/tasks/WEB-002-pipeline-jenkins.md) | Pipeline Jenkins cho frontend có cổng chất lượng | M | W-Nền tảng | – | WEB-001 | **Đã gộp main** | Đã gộp `main` (`Jenkinsfile` đủ cổng chất lượng, gitleaks, Semgrep, Trivy, SBOM) |
| [WEB-003](docs/tasks/WEB-003-ngan-sach-hieu-nang.md) | Ngân sách bundle và Lighthouse CI | S | W-Nền tảng | – | WEB-001 | **Đã gộp main** | Đã gộp `main` (cấu hình ngân sách bundle và Lighthouse CI) |
| [WEB-004](docs/tasks/WEB-004-bien-dich-token.md) | Biên dịch Design Token và kiểm tra tương phản | M | W-Nền tảng | – | WEB-001 | **Đã gộp main** | Gộp main 2026-10-22 |
| [WEB-005](docs/tasks/WEB-005-storybook-hoi-quy.md) | Storybook và kiểm thử hồi quy giao diện theo bộ token | M | W-Nền tảng | – | WEB-004 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [WEB-006](docs/tasks/WEB-006-dang-nhap-keycloak.md) | Đăng nhập Keycloak, chọn đơn vị và vai trò (phần web của QT-00) | M | W-Nền tảng | – | WEB-001 | **Đã gộp main** | Đã gộp `main` (OIDC Keycloak, chọn đơn vị/vai trò, step-up MFA) |
| [WEB-007](docs/tasks/WEB-007-api-client-openapi.md) | `@vt/api-client`: sinh client từ OpenAPI, xử lý lỗi, 409, job 202 | M | W-Nền tảng | – | WEB-001 | **Đã gộp main** | Đã gộp `main` (`@vt/api-client`, xử lý lỗi RFC 9457, 409, JobTracker 202) |
| [WEB-008](docs/tasks/WEB-008-mock-api.md) | Tách dữ liệu giả lập thành interceptor và fixture theo module | S | W-Nền tảng | – | WEB-007 | **Đã gộp main** | Đã gộp `main` (interceptor và fixture dữ liệu giả lập theo module) |
| [WEB-009](docs/tasks/WEB-009-sdui-chu-ky.md) | SDUI: kiểm chữ ký UI Pack, kiểm phiên bản registry, telemetry | M | W-Nền tảng | – | WEB-004 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [WEB-010](docs/tasks/WEB-010-form-engine.md) | Form engine: widget, test CEL dùng chung, benchmark | M | W-Nền tảng | – | WEB-001 | **Đã gộp main** | Đã gộp main (ad623eff) |
| [WEB-011](docs/tasks/WEB-011-telemetry-faro.md) | Telemetry: Grafana Faro + OpenTelemetry web | S | W-Nền tảng | – | WEB-001 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [WEB-012](docs/tasks/WEB-012-shell-hoan-thien.md) | Shell: thông báo thời gian thực, tìm kiếm, xử lý lỗi toàn cục | M | W-Nền tảng | – | WEB-006, WEB-007 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [WEB-013](docs/tasks/WEB-013-web-component-nhung.md) | Chế độ nhúng `<vt-okr-app>` cho cổng văn phòng số | M | W-Nền tảng | – | WEB-006 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [WEB-014](docs/tasks/WEB-014-csp-trusted-types.md) | CSP chặt và Trusted Types cho bản phục vụ nginx | S | W-Nền tảng | – | WEB-002 | **Đã gộp main** | Gộp main 2026-10-22 |
| [WEB-015](docs/tasks/WEB-015-khung-remote.md) | Công cụ tạo remote mới và dựng khung 8 remote | M | W-Nền tảng | – | WEB-001 | **Đã gộp main** | Đã gộp `main` (khung 8 remote micro-frontend, cổng 4202–4209) |
| [WEB-016](docs/tasks/WEB-016-danh-sach-tenant.md) | SCR-0101 Danh sách tenant | S | W-Giám sát/CH | – | WEB-015 | **Đã gộp main** | Đã gộp `main` (SCR-0101 Danh sách tenant) |
| [WEB-017](docs/tasks/WEB-017-wizard-tenant.md) | SCR-0102 Khởi tạo tenant (wizard) | M | W-Giám sát/CH | – | WEB-016 | **Đã gộp main** | Đã gộp `main` (SCR-0102 Wizard khởi tạo tenant) |
| [WEB-018](docs/tasks/WEB-018-cay-to-chuc.md) | SCR-0103 Cây tổ chức có lịch sử | L | W-Giám sát/CH | – | WEB-015 | **Đã gộp main** | Đã gộp `main` (SCR-0103 Cây tổ chức có lịch sử hai trục thời gian) |
| [WEB-019](docs/tasks/WEB-019-doi-soat-nhan-su.md) | SCR-0104 Đồng bộ và đối soát nhân sự | M | W-Giám sát/CH | – | WEB-018 | **Đã gộp main** | Đã gộp `main` (SCR-0104 Đồng bộ và đối soát nhân sự) |
| [WEB-020](docs/tasks/WEB-020-theme-keycloak.md) | SCR-0001 Giao diện trang đăng nhập (theme Keycloak) | M | W-Nền tảng | – | WEB-004, WEB-006 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [WEB-021](docs/tasks/WEB-021-cay-okr.md) | SCR-0201 Cây mục tiêu OKR | L | W-OKR/Đánh giá | – | WEB-015 | **Đã gộp main** | Đã gộp `main` (SCR-0201 Cây mục tiêu OKR) |
| [WEB-022](docs/tasks/WEB-022-tao-sua-okr.md) | SCR-0202 Tạo/sửa O và KR | M | W-OKR/Đánh giá | – | WEB-021 | **Đã gộp main** | Đã gộp `main` (SCR-0202 Tạo/sửa O và KR) |
| [WEB-023](docs/tasks/WEB-023-duyet-okr.md) | SCR-0203 Phê duyệt OKR | M | W-OKR/Đánh giá | – | WEB-022 | **Đã gộp main** | Đã gộp `main` (SCR-0203 Phê duyệt OKR) |
| [WEB-024](docs/tasks/WEB-024-check-in-kr.md) | SCR-0301 Check-in KR | M | W-OKR/Đánh giá | – | WEB-021 | **Đã gộp main** | Đã gộp `main` (SCR-0301 Check-in KR) |
| [WEB-025](docs/tasks/WEB-025-danh-muc-cong-viec.md) | SCR-0401 Thư viện danh mục công việc | M | W-Nghiệp vụ | – | WEB-008 | **Đã gộp main** | Đã gộp `main` (SCR-0401 Thư viện danh mục công việc) |
| [WEB-026](docs/tasks/WEB-026-lap-ke-hoach.md) | SCR-0402 Lập kế hoạch công tác quý | L | W-Nghiệp vụ | – | WEB-025 | **Đã gộp main** | Đã gộp `main` (SCR-0402 Lập kế hoạch công tác quý) |
| [WEB-027](docs/tasks/WEB-027-duyet-ke-hoach.md) | SCR-0403 Phê duyệt kế hoạch (so sánh phiên bản) | M | W-Nghiệp vụ | – | WEB-026 | **Đã gộp main** | Đã gộp `main` (SCR-0403 Phê duyệt kế hoạch, so sánh phiên bản) |
| [WEB-028](docs/tasks/WEB-028-giao-viec.md) | SCR-0501 Giao việc | M | W-Nghiệp vụ | – | WEB-008 | **Đã gộp main** | Đã gộp `main` (SCR-0501 Giao việc) |
| [WEB-029](docs/tasks/WEB-029-nhiem-vu-tu-van-ban.md) | SCR-0502 Tạo nhiệm vụ từ văn bản chỉ đạo | M | W-Nghiệp vụ | – | WEB-028 | **Đã gộp main** | Đã gộp `main` (SCR-0502 Tạo nhiệm vụ từ văn bản chỉ đạo) |
| [WEB-030](docs/tasks/WEB-030-viec-cua-toi.md) | SCR-0503 Việc của tôi – hoàn thiện | S | W-Nghiệp vụ | – | WEB-008 | **Đã gộp main** | Đã gộp `main` (SCR-0503 Việc của tôi – hoàn thiện) |
| [WEB-031](docs/tasks/WEB-031-chi-tiet-gia-han.md) | SCR-0601 Chi tiết nhiệm vụ và SCR-0602 Đề nghị gia hạn, điều chỉnh | M | W-Nghiệp vụ | – | WEB-030 | **Đã gộp main** | Đã gộp `main` (SCR-0601 Chi tiết nhiệm vụ & SCR-0602 Đề nghị gia hạn) |
| [WEB-032](docs/tasks/WEB-032-nop-minh-chung.md) | SCR-0701 Nộp minh chứng | L | W-Nghiệp vụ | – | WEB-015 | **Đã gộp main** | Đã gộp `main` (SCR-0701 Nộp minh chứng có băm SHA-256) |
| [WEB-033](docs/tasks/WEB-033-nghiem-thu.md) | SCR-0703 Nghiệm thu chất lượng 5 mức | M | W-Nghiệp vụ | – | WEB-032 | **Đã gộp main** | Đã gộp `main` (SCR-0703 Nghiệm thu chất lượng 5 mức) |
| [WEB-034](docs/tasks/WEB-034-khoa-ky.md) | SCR-0801 Quản lý kỳ và tiến trình khoá kỳ | M | W-Nghiệp vụ | – | WEB-007, WEB-015 | **Đã gộp main** | Đã gộp `main` (SCR-0801 Quản lý kỳ và tiến trình khoá kỳ) |
| [WEB-035](docs/tasks/WEB-035-tu-danh-gia.md) | SCR-0901 Phiếu tự đánh giá | L | W-OKR/Đánh giá | – | WEB-010, WEB-015 | **Đã gộp main** | Đã gộp `main` (SCR-0901 Phiếu tự đánh giá) |
| [WEB-036](docs/tasks/WEB-036-tham-dinh.md) | SCR-0902 Thẩm định | L | W-OKR/Đánh giá | – | WEB-035 | **Đã gộp main** | Đã gộp `main` (SCR-0902 Thẩm định hồ sơ đánh giá) |
| [WEB-037](docs/tasks/WEB-037-phieu-xep-loai.md) | SCR-0903 Phiếu xếp loại có giải thích | M | W-OKR/Đánh giá | – | WEB-036 | **Đã gộp main** | Đã gộp `main` (SCR-0903 Phiếu xếp loại có giải thích) |
| [WEB-038](docs/tasks/WEB-038-quyet-dinh-hang-loat.md) | SCR-0904 Quyết định xếp loại hàng loạt và ký số | L | W-OKR/Đánh giá | – | WEB-037 | **Đã gộp main** | Đã gộp `main` (SCR-0904 Quyết định xếp loại hàng loạt và ký số) |
| [WEB-039](docs/tasks/WEB-039-xep-loai-nam.md) | SCR-1001 Xếp loại năm và SCR-1002 Phân nhóm triển vọng | M | W-OKR/Đánh giá | – | WEB-037 | **Đã gộp main** | Đã gộp `main` (commit `454cbc3` – SCR-1001 Xếp loại năm & SCR-1002 Phân nhóm triển vọng) |
| [WEB-040](docs/tasks/WEB-040-hoi-nghi-kiem-diem.md) | SCR-1101 Hội nghị kiểm điểm | M | W-OKR/Đánh giá | – | WEB-015 | **Đã gộp main** | Đã gộp main (f4fca53) |
| [WEB-041](docs/tasks/WEB-041-goi-ho-so-so.md) | SCR-1102 Gói hồ sơ số và ký số | M | W-OKR/Đánh giá | – | WEB-040 | **Đã gộp main** | Đã gộp main (f4fca53) |
| [WEB-042](docs/tasks/WEB-042-khieu-nai.md) | SCR-1201 Gửi khiếu nại và SCR-1202 Xử lý khiếu nại | L | W-Nghiệp vụ | – | WEB-015 | **Đã gộp main** | Đã gộp `main` (SCR-1201 Gửi khiếu nại & SCR-1202 Xử lý khiếu nại) |
| [WEB-043](docs/tasks/WEB-043-mo-lai-ky-so-cai.md) | SCR-1301 Yêu cầu mở lại kỳ và SCR-1302 Tra cứu sổ cái | M | W-Nghiệp vụ | – | WEB-034 | **Đã gộp main** | Đã gộp `main` (SCR-1301 Yêu cầu mở lại kỳ & SCR-1302 Tra cứu sổ cái) |
| [WEB-044](docs/tasks/WEB-044-trung-tam-canh-bao.md) | SCR-1401 Trung tâm cảnh báo | M | W-Nghiệp vụ | – | WEB-015 | **Đã gộp main** | Đã gộp `main` (SCR-1401 Trung tâm cảnh báo bất thường) |
| [WEB-045](docs/tasks/WEB-045-studio-cong-thuc.md) | SCR-1501 Config Studio – công thức và SCR-1502 Bảng quyết định | L | W-Giám sát/CH | – | WEB-010, WEB-015 | **Đã gộp main** | Đã gộp main (ad623eff) |
| [WEB-046](docs/tasks/WEB-046-test-vang-luong-duyet.md) | SCR-1503 Chạy test case vàng và SCR-1504 Cấu hình luồng duyệt | M | W-Giám sát/CH | – | WEB-045 | **Đã gộp main** | Đã gộp `main` (commit `328ef37` – SCR-1503 Chạy test case vàng & SCR-1504 Cấu hình luồng duyệt) |
| [WEB-047](docs/tasks/WEB-047-bang-dieu-hanh.md) | SCR-1601 Bảng điều hành lãnh đạo và SCR-1603 Đi xuống tới minh chứng | L | W-Nghiệp vụ | – | WEB-015 | **Đã gộp main** | Đã gộp `main` (SCR-1601 Bảng điều hành lãnh đạo & SCR-1603 Đi xuống tới minh chứng) |
| [WEB-048](docs/tasks/WEB-048-trinh-dung-dashboard.md) | SCR-1602 Trình dựng dashboard | M | W-Nghiệp vụ | – | WEB-047 | **Đã gộp main** | Đã gộp `main` (SCR-1602 Trình dựng dashboard) |
| [WEB-049](docs/tasks/WEB-049-tro-ly-ai.md) | SCR-1701 Trợ lý AI soạn OKR và soát KR | M | W-OKR/Đánh giá | – | WEB-022 | **Đã gộp main** | Đã gộp main (f4fca53) |
| [WEB-050](docs/tasks/WEB-050-studio-giao-dien.md) | SCR-1801 Cấu hình giao diện tenant và SCR-1802 Trình dựng biểu mẫu động | L | W-Giám sát/CH | – | WEB-004, WEB-009, WEB-010 | **Đã gộp main** | Đã gộp main (f4fca53) |
| [WEB-051](docs/tasks/WEB-051-plugin-chuyen-nganh.md) | Plugin chuyên ngành mẫu và manifest theo tenant | M | W-Nền tảng | – | WEB-015 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [WEB-052](docs/tasks/WEB-052-toi-uu-hieu-nang.md) | Kiểm thử và tối ưu hiệu năng frontend | M | W-Nền tảng | – | WEB-003, WEB-011 | **Làm một phần** | Đã gộp pha 1 trên `main`; pha 2 đang ở nhánh `feat/WEB-052-pha-2` |
| [WEB-053](docs/tasks/WEB-053-danh-gia-attt.md) | Đánh giá ATTT frontend: OWASP ZAP và ASVS mức 2 | M | W-Nền tảng | – | WEB-014 | **Đã gộp main** | Đã gộp main (984a7ba1) |
| [WEB-054](docs/tasks/WEB-054-bang-quyen-frontend.md) | Bảng quyền phía giao diện sinh từ ma trận (`can()`, `*vtCan`, `permissionGuard`) | M | W-Phân quyền | – | WEB-001, WEB-006 | **Đã gộp main** | Đã gộp `main` (bảng quyền sinh từ `ma-tran.json`: `can()`, `*vtCan`, `permissionGuard`) |
| [WEB-055](docs/tasks/WEB-055-ap-ma-tran-shell-remote.md) | Áp ma trận vào menu, tuyến và nút của shell và các remote hiện có | M | W-Phân quyền | – | WEB-054, WEB-015 | **Đã gộp main** | Đã gộp `main` (áp ma trận phân quyền vào menu, tuyến và nút của shell/remote) |
| [WEB-056](docs/tasks/WEB-056-dong-bo-realm-keycloak.md) | Đồng bộ realm Keycloak dev với ma trận (vai trò, claim, MFA theo vai trò, tài khoản thử) | M | W-Phân quyền | – | WEB-006, WEB-054 | **Đã gộp main** | Đã gộp `main` (đồng bộ realm Keycloak dev với ma trận phân quyền) |
| [WEB-057](docs/tasks/WEB-057-kiem-thu-ma-tran-playwright.md) | Kiểm thử Playwright đối chiếu ma trận: vai trò × tuyến | M | W-Phân quyền | – | WEB-055, WEB-056 | **Đã gộp main** | Đã gộp main (ad623eff) |
| [WEB-058](docs/tasks/WEB-058-quan-tri-vai-tro-uy-quyen.md) | Quản trị phân công vai trò và uỷ quyền (chưa có SCR, cần bổ sung thiết kế) | L | W-Phân quyền | – | WEB-018, WEB-054, quyết định thiết kế | **Đã gộp main** | Đã gộp `main` (SCR-0105..0107 Quản trị phân công vai trò và uỷ quyền trên mock) |
| [WEB-059](docs/tasks/WEB-059-kha-nang-truy-cap-nen.md) | Sửa lỗi khả năng truy cập nền: progressbar, slider, tương phản token | S | W-Nền tảng | – | WEB-001 | **Đã gộp main** | Đã gộp `main` (sửa lỗi khả năng truy cập nền, axe 0 lỗi critical/serious, Lighthouse A11y 1.00) |
| [WEB-060](docs/tasks/WEB-060-format-toan-bo.md) | Chạy Prettier toàn workspace một lần | S | W-Nền tảng | – | WEB-001 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [WEB-061](docs/tasks/WEB-061-trinh-soan-mau-luong.md) | SCR-1505 Thư viện và trình soạn mẫu luồng (BS-01) | L | W-Giám sát/CH | – | WEB-015, WEB-046 | **Đã gộp main** | Đã gộp main (25c670a) |
| [WEB-062](docs/tasks/WEB-062-giam-sat-tien-trinh.md) | SCR-1506 Giám sát tiến trình theo mẫu (BS-01) | M | W-Giám sát/CH | – | WEB-015 | **Đã gộp main** | Đã gộp main (f4fca53) |
| [WEB-063](docs/tasks/WEB-063-hop-dong-openapi.md) | Bộ hợp đồng OpenAPI 3.1 toàn dự án theo hướng API-first (ADR-32, BS-02) | L | W-Nền tảng | – | WEB-007 | **Đã gộp main** | Hợp đồng và màn hình đã gộp main (4f1e4b0) |
| [WEB-064](docs/tasks/WEB-064-sinh-kieu-tu-hop-dong.md) | Sinh kiểu frontend từ bộ hợp đồng `contracts/openapi/`, kiểm model viết tay với hợp đồng (luồng Nền tảng) | M | W-Nền tảng | – | WEB-007, WEB-063 | **Đã gộp main** | Đã gộp `main` (sinh kiểu frontend từ `contracts/openapi/`, `npm run api:check`) |
| [WEB-065](docs/tasks/WEB-065-doi-chieu-ke-hoach-hop-dong.md) | Đối chiếu client kế hoạch (SCR-0402, SCR-0403) với hợp đồng OpenAPI (luồng màn hình nghiệp vụ) | M | W-Nghiệp vụ | – | WEB-026, WEB-027, WEB-064 | **Đã gộp main** | Đã gộp `main` (đối chiếu client kế hoạch SCR-0402, SCR-0403 với hợp đồng OpenAPI) |

### NEN · Nền móng hệ thống: repo, quy định, wiki, khung mã, thư viện nền, nền CSDL (34/34 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| NEN-001 | Chuyển repo thành repo chung toàn hệ thống: cấu trúc thư mục, AGENTS.md mở rộng, quy định theo phân hệ, prompt agent | S | DP | 1 | – | **Đã gộp main** | Đã gộp `main` (cấu trúc monorepo toàn hệ thống, `AGENTS.md`, `docs/quy-dinh/`, `scripts/agent-task.sh`) |
| [NEN-002](docs/tasks/NEN-002-wiki-backend-phan-1.md) | Wiki backend phần 1: trang 16 kiến trúc mission-core và microservice, trang 17 thiết kế CSDL và đa khách thuê | M | B1 | 2 | NEN-001 | **Đã gộp main** | Đã gộp `main` (commit `c00d4f0` – Wiki backend trang 16 kiến trúc, trang 17 CSDL đa khách thuê) |
| [NEN-003](docs/tasks/NEN-003-wiki-backend-phan-2.md) | Wiki backend phần 2: trang 18 sự kiện và đồng bộ dữ liệu, trang 19 hạ tầng production và triển khai, trang 20 bảo mật phía máy chủ | M | B1 | 2 | NEN-001 | **Đã gộp main** | Đã gộp `main` (Wiki backend trang 18 sự kiện, trang 19 hạ tầng K8s, trang 20 bảo mật máy chủ) |
| [NEN-004](docs/tasks/NEN-004-diem-lech-backend.md) | Ghi các điểm lệch và câu hỏi mở phía backend vào wiki trang 14 và lập đề xuất chốt | S | B1 | 2 | NEN-001 | **Đã gộp main** | Đã gộp `main` (tổng hợp điểm lệch thiết kế vào `docs/DIEM-LECH-KHI-LAP-KE-HOACH.md` và wiki trang 14) |
| [NEN-005](docs/tasks/NEN-005-khung-maven-backend.md) | Khung Maven đa module `backend/`: POM cha, BOM phiên bản theo TL-01 PL B, Java 21, Spotless, JaCoCo, PIT, Jib, cấu hình Sonar | M | B1 | 2 | NEN-001 | **Đã gộp main** | Đã gộp `main` (khung Maven đa module `backend/`, `platform-bom`, Java 21, Spotless, JaCoCo, ArchUnit) |
| [NEN-006](docs/tasks/NEN-006-sinh-ma-tu-openapi.md) | Sinh giao diện máy chủ từ hợp đồng OpenAPI (openapi-generator Spring interfaceOnly) và kiểm `openapi-diff` springdoc với `contracts/` | M | B1 | 3 | NEN-005 | **Đã gộp main** | Đã gộp `main` (commit `4ab2545` – sinh giao diện máy chủ từ OpenAPI 3.1 và khung kiểm thử hợp đồng) |
| [NEN-007](docs/tasks/NEN-007-luoc-do-avro-su-kien.md) | Lược đồ Avro cho EVT-01..EVT-26 và topic kỹ thuật, sinh lớp Java, kiểm tương thích bằng plugin Maven Apicurio | M | B1 | 3 | NEN-005 | **Đã gộp main** | Đã gộp `main` (commit `98a320a` – lược đồ Avro cho EVT-01..EVT-26, topic kỹ thuật và kiểm tương thích) |
| [NEN-008](docs/tasks/NEN-008-asyncapi-topic-cong-bo.md) | AsyncAPI 3.0 cho các topic công bố, lint trong CI | S | B1 | 4 | NEN-007 | **Đã gộp main** | Đã gộp `main` (commit `0359745`, gộp tại `275bb0f` – đặc tả AsyncAPI 3.0 và kiểm tra chéo Avro) |
| [NEN-009](docs/tasks/NEN-009-starter-kernel-loi.md) | vt-platform-starter: nhân `kernel` (TenantId, PeriodId, Clock, UUIDv7, Result) và lỗi RFC 9457 theo bảng mã lỗi chuẩn | M | B1 | 3 | NEN-005 | **Đã gộp main** | Đã gộp `main` (commit `4d80c31` – `vt-platform-starter` nhân `kernel` và lỗi RFC 9457 chuẩn) |
| [NEN-010](docs/tasks/NEN-010-starter-ngu-canh-tenant.md) | vt-platform-starter: ngữ cảnh tenant từ JWT, TenantFilter, đặt `app.tenant_id` cục bộ giao dịch qua PgBouncer | M | B1 | 6 | NEN-009, NEN-033 | **Đã gộp main** | Đã gộp `main` (commit `3af0823` – ngữ cảnh tenant từ JWT, `TenantFilter`, `SET LOCAL app.tenant_id`) |
| [NEN-011](docs/tasks/NEN-011-starter-outbox-writer.md) | vt-platform-starter: OutboxWriter, EventEnvelope, tuần tự hoá Avro qua Apicurio | M | B1 | 6 | NEN-007, NEN-032 | **Đã gộp main** | Đã gộp main (2006fc9, 6c2525d) |
| [NEN-012](docs/tasks/NEN-012-starter-consumer-inbox.md) | vt-platform-starter: consumer Kafka chuẩn với inbox idempotent, thử lại có backoff, DLQ | M | B1 | 7 | NEN-011 | **Đã gộp main** | Đã gộp main (2006fc9, 6c2525d) |
| [NEN-013](docs/tasks/NEN-013-starter-idempotency-etag.md) | vt-platform-starter: kho Idempotency-Key 48 giờ, ETag/If-Match, phân trang con trỏ, job 202 | M | B1 | 6 | NEN-009, NEN-032 | **Đã gộp main** | Đã gộp feat/NEN-013-014-015-platform-starter vào main |
| [NEN-014](docs/tasks/NEN-014-starter-opa-step-up.md) | vt-platform-starter: OpaClient, `@RequiresPermission`, đánh giá từng phần thành điều kiện SQL, kiểm step-up `acr`/`auth_time` | L | B1 | 4 | NEN-009, NEN-040 | **Đã gộp main** | Đã gộp feat/NEN-013-014-015-platform-starter vào main |
| [NEN-015](docs/tasks/NEN-015-starter-ma-hoa-truong.md) | vt-platform-starter: CryptoProvider, FieldEncryptor AES-256-GCM phong bì, DekCache 15 phút, chỉ mục mù HMAC, client OpenBao Transit | L | B1 | 6 | NEN-009, NEN-032 | **Đã gộp main** | Đã gộp feat/NEN-013-014-015-platform-starter vào main |
| [NEN-016](docs/tasks/NEN-016-starter-chiu-loi-quan-sat.md) | vt-platform-starter: Resilience4j chuẩn, timeout mặc định, OpenTelemetry, log ECS JSON có che dữ liệu, tách nhật ký kiểm toán | M | B1 | 4 | NEN-009 | **Đã gộp main** | Đã gộp main (vt-platform-starter: Resilience4j, OpenTelemetry, log ECS JSON che dữ liệu nhạy cảm) |
| [NEN-017](docs/tasks/NEN-017-thu-vien-go-dung-chung.md) | Thư viện Go dùng chung: tenant, outbox/inbox với pgx và franz-go, lỗi RFC 9457, OpenTelemetry | M | B1 | 6 | NEN-007, NEN-032, NEN-009 | **Đã gộp main** | Đã gộp main (commit fe40716 – thư viện Go dùng chung vtplatform) |
| [NEN-018](docs/tasks/NEN-018-thu-vien-python-dung-chung.md) | Thư viện Python dùng chung cho ai-gateway và job phân tích: tenant, OTel, lỗi, Kafka consumer | S | B1 | 4 | NEN-007, NEN-009 | **Đã gộp main** | Đã gộp main (commit 3b4e808 – thư viện Python dùng chung vtplatform) |
| [NEN-019](docs/tasks/NEN-019-mau-service-java.md) | Mẫu service Java (lục giác, actuator, probe, Helm values) và lệnh `scripts/new-service.sh` | M | B1 | 8 | NEN-010, NEN-011, NEN-012, NEN-016 | **Đã gộp main** | Đã gộp feat/NEN-019-NEN-020-template-testkit vào main |
| [NEN-020](docs/tasks/NEN-020-moi-truong-dev-backend.md) | Môi trường dev backend: bổ sung profile Kafka, Apicurio, Debezium, OPA, OpenBao, Valkey vào `infra/dev`, chuẩn hoá tài khoản CSDL `svc_/mig_/ro_/cdc_`, cấu hình Testcontainers dùng chung | M | B1 | 6 | NEN-031, NEN-007, NEN-040, NEN-033 | **Đã gộp main** | Đã gộp feat/NEN-019-NEN-020-template-testkit vào main |
| [NEN-030](docs/tasks/NEN-030-quy-uoc-flyway-lint.md) | Quy ước Flyway theo schema, tài khoản `mig_<schema>`, lint tên, kiểu và chú thích phân loại cột trong CI | M | B1 | 3 | NEN-005 | **Đã gộp main** | Đã gộp `main` (quy ước Flyway theo schema, tài khoản `mig_<schema>`, linter kiểm tra migration) |
| [NEN-031](docs/tasks/NEN-031-vai-tro-csdl-theo-schema.md) | Vai trò CSDL và quyền mặc định theo schema (svc, ro, mig, cdc) cùng các ngoại lệ | M | B1 | 4 | NEN-030 | **Đã gộp main** | Đã gộp `main` (vai trò CSDL và quyền mặc định theo schema `svc_`, `ro_`, `mig_`, `cdc_`) |
| [NEN-032](docs/tasks/NEN-032-bang-dung-chung-mau.md) | Bảng dùng chung mẫu: `outbox_event` phân vùng, `inbox_processed`, `inbox_aggregate_version`, `idempotency_key`, schema `modulith`, `keyring.dek` | M | B1 | 5 | NEN-030, NEN-031 | **Đã gộp main** | Đã gộp `main` (bảng dùng chung mẫu: `outbox_event`, `inbox_processed`, `idempotency_key`, `keyring.dek`) |
| [NEN-033](docs/tasks/NEN-033-rls-phan-vung-pgbouncer.md) | Mẫu RLS chuẩn, hàm `fn_create_period_partitions`, cấu hình PgBouncer chế độ giao dịch | L | B1 | 5 | NEN-031 | **Đã gộp main** | Đã gộp `main` (mẫu RLS chuẩn, hàm `fn_create_period_partitions`, cấu hình PgBouncer transaction pooling) |
| [NEN-034](docs/tasks/NEN-034-ro-ri-tenant-csdl.md) | Kiểm thử rò rỉ tenant lớp CSDL: RLS giữa hai tenant, PgBouncer dùng lại kết nối, phân vùng | M | B1 | 7 | NEN-033, NEN-010 | **Đã gộp main** | Đã gộp `main` (commit `9e4b6fc`, gộp tại `275bb0f` – kiểm thử chống rò rỉ tenant lớp CSDL, RLS, PgBouncer, Semgrep) |
| [NEN-035](docs/tasks/NEN-035-ro-ri-tenant-api-cache.md) | Kiểm thử rò rỉ tenant lớp API, bộ đệm Valkey, chỉ mục tìm kiếm và kho phân tích | M | B9 | 11 | NEN-034, CORE-00-02 | **Đã gộp main** | Gộp main 22/10 |
| [NEN-036](docs/tasks/NEN-036-dinh-tuyen-csdl-tenant.md) | Định tuyến tới đúng CSDL theo mô hình silo, bridge, pool (`registry.tenant_cell`, mã 421) | M | B1 | 9 | NEN-033, DV-03-02, NEN-012 | **Đã gộp main** | Đã gộp main: định tuyến CSDL theo khách thuê (POOL/BRIDGE/SILO) và bộ lọc cell (ADR-07) |
| [NEN-040](docs/tasks/NEN-040-goi-chinh-sach-opa.md) | Gói chính sách OPA (Rego) sinh từ `docs/phan-quyen/ma-tran.json`: RBAC, ABAC theo cây tổ chức, luồng, mức dữ liệu, tách vai trò, bốn mắt; kiểm thử `opa test` | L | B1 | 2 | NEN-001 | **Đã gộp main** | Đã gộp `main` (commit `653ce97` – gói chính sách OPA Rego sinh từ `ma-tran.json` và bộ kiểm thử `opa test`) |
| [NEN-041](docs/tasks/NEN-041-thu-vien-cel-dung-chung.md) | Thư viện CEL dùng chung `vt-cel` (cel-java): môi trường, hàm mở rộng `sum`, `ngay_lam_viec`, `min_muc`, bộ ca kiểm thử chung với cel-js | L | B2 | 3 | NEN-005 | **Đã gộp main** | Đã gộp `main` (commit `e22cb71` – thư viện CEL dùng chung `vt-cel` và bộ ca kiểm thử đối chiếu `cel-js`) |
| [NEN-021](docs/tasks/NEN-021-luoc-do-topic-ky-thuat.md) | Lược đồ Avro cho sự kiện kỹ thuật `evidence.object.*` và các topic kỹ thuật còn thiếu | S | B1 | 4 | NEN-007 | **Đã gộp main** | Đã gộp `main` (commit `f32a1d7`, gộp tại `275bb0f` – lược đồ Avro cho các topic sự kiện kỹ thuật) |
| [NEN-022](docs/tasks/NEN-022-phat-lai-outbox-connector.md) | Công cụ phát lại outbox và cấu hình connector Debezium outbox theo schema | M | B1 | 7 | NEN-011 | **Đã gộp main** | Gộp main 22/10 |
| [NEN-042](docs/tasks/NEN-042-sua-hop-dong-nghiep-vu.md) | Sửa hợp đồng OpenAPI phần nghiệp vụ: thêm thao tác còn thiếu (huỷ OKR, phiên bản điều chỉnh mục tiêu, uỷ quyền check-in, kích hoạt danh mục, chấm việc ngoài danh mục, ngoại lệ điều chỉnh, xác nhận dòng kế hoạch, duyệt nhiệm vụ đăng ký, chuyển điểm nghẽn, danh bạ khoá E2EE và chia sẻ CEK, ghi trường hợp đặc thù, gợi ý khiếu nại trùng) và sửa mô tả `acceptComplaint` | M | B1 | 2 | NEN-001 | **Đã gộp main** | Đã gộp main: hợp đồng OpenAPI 3.1 các phân hệ nghiệp vụ |
| [NEN-043](docs/tasks/NEN-043-sua-hop-dong-nen-tang.md) | Sửa hợp đồng OpenAPI phần nền tảng: cờ tính năng cho mọi người dùng, cấu hình tenant, thiết bị nhận thông báo đẩy, tuỳ chọn kênh, mã khôi phục MFA, `POST /org/assignments`, guest token Superset, kiểm quyền theo lô cho tìm kiếm, ký nội bộ cho sổ cái, OCR cho tài khoản dịch vụ, đề nghị chuyển cell | M | B1 | 3 | NEN-001, NEN-042 | **Đã gộp main** | Đã gộp main: bổ sung các thao tác OpenAPI 3.1 cho cấu hình và dịch vụ nền |
| [NEN-044](docs/tasks/NEN-044-hop-dong-ho-so-tap-khach-hang.md) | Sửa hợp đồng OpenAPI theo BS-03: hồ sơ tập khách hàng, khung tham số, điểm công việc và phạm vi phụ trách trong `inputs`, kiểu đo mốc, cây KPI, chế độ phân công, sự việc | M | B1 | 4 | NEN-043; người phụ trách hợp đồng duyệt | **Đã gộp main** | Gộp main 2026-10-22 |

### CORE-00 · mission-core: phần dùng chung (5/5 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [CORE-00-01](docs/tasks/CORE-00-01-khung-mission-core.md) | Khung ứng dụng mission-core: Spring Boot 3.5, luồng ảo, tám DataSource riêng theo module, actuator, cấu hình mẫu | L | B2 | 9 | NEN-019, NEN-031, NEN-006, NEN-013, NEN-030 | **Đã gộp main** | Đã gộp main (1581328): khung SVC-05 mission-core, 8 DataSource mô-đun và định tuyến đọc/ghi |
| [CORE-00-02](docs/tasks/CORE-00-02-tam-module-modulith.md) | Tám module lục giác, khai báo Spring Modulith, ma trận phụ thuộc, ArchUnit, sinh tài liệu module | M | B2 | 10 | CORE-00-01 | **Đã gộp main** | Đã gộp main (e9620db) |
| [CORE-00-03](docs/tasks/CORE-00-03-org-replica-consumer.md) | Schema `org_replica` và consumer EVT-14, EVT-15, EVT-16 | M | B2 | 12 | CORE-00-02, NEN-012, DV-04-03 | **Đã gộp main** | Đã gộp main (e9620db) |
| [CORE-00-04](docs/tasks/CORE-00-04-api-trang-chu-hom-nay.md) | API tổng hợp trang chủ "Việc của tôi hôm nay" (SCR-0002) | M | B3 | 18 | CORE-02-18, CORE-01-08, CORE-08-09, CORE-08-02, NEN-041 | **Đã gộp main** | Đã gộp main (03e9c83): ranh giới 8 mô-đun Spring Modulith, hợp đồng API nội bộ, JobsController và kiểm thử kiến trúc |
| [CORE-00-05](docs/tasks/CORE-00-05-nhat-ky-truy-cap-c2.md) | Nhật ký truy cập dữ liệu C2 và ghi sổ cái lần giải mã | S | B4 | 11 | NEN-015, DV-09-02, NEN-011, NEN-016, CORE-00-02 | **Đã gộp main** | Đã gộp main (e9620db) |

### CORE-08 · MOD-08 config: kỳ, lịch, cờ, UI Pack, biểu mẫu (G1 phía máy chủ) (17/17 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [CORE-08-01](docs/tasks/CORE-08-01-migration-schema-config.md) | Migration schema `config` (period, holiday_calendar, tenant_setting, feature_flag, ui_pack, ui_pack_version, form_schema, component_registry, role_binding, period_lock_order) | M | B2 | 11 | CORE-00-02, NEN-033 | **Đã gộp main** | Migration schema kèm IT RLS và ràng buộc |
| [CORE-08-02](docs/tasks/CORE-08-02-ky-danh-gia-period-api.md) | Kỳ đánh giá: domain, máy trạng thái kỳ, tạo kỳ có lịch và ghim Policy Pack, `PeriodApi` | M | B2 | 14 | CORE-08-01, CORE-05-03 | **Đã gộp main** | Kỳ đánh giá: API tạo/xem/liệt kê, PeriodApi đọc CSDL, EVT-09/26 |
| [CORE-08-03](docs/tasks/CORE-08-03-chan-ghi-ky-khoa.md) | Chặn ghi khi kỳ `DANG_KHOA`/`DA_KHOA` ở mọi module, đệm trạng thái kỳ, lỗi 409 `period-locked` | M | B2 | 15 | CORE-08-02 | **Đã gộp main** | Chặn ghi khi kỳ khoá, đệm trạng thái kỳ, mã 409 theo mô-đun |
| [CORE-08-04](docs/tasks/CORE-08-04-lich-ngay-lam-viec.md) | Lịch ngày làm việc, ngày nghỉ, làm bù và thư viện tính ngày làm việc dùng chung | M | B2 | 12 | CORE-08-01 | **Đã gộp main** | Lịch ngày nghỉ, làm bù, WorkingCalendarApi |
| [CORE-08-05](docs/tasks/CORE-08-05-lenh-khoa-ky.md) | Lệnh khoá kỳ: lập, duyệt có MFA và tách vai trò, khởi chạy saga | M | B2 | 15 | CORE-08-02, DV-06-05 | **Đã gộp main** | Lệnh khoá kỳ, saga chốt kỳ gửi X-Tenant-Id |
| [CORE-08-06](docs/tasks/CORE-08-06-mo-lai-hau-kiem.md) | Mở lại hậu kiểm: trạng thái `MO_LAI_HAU_KIEM`, phạm vi mở, consumer EVT-22, timer hết hạn khoá lại | L | B2 | 16 | CORE-08-03 | **Đã gộp main** | Mở lại kỳ để hậu kiểm theo phạm vi, migration config V5 |
| [CORE-08-07](docs/tasks/CORE-08-07-cau-hinh-tenant-co-tinh-nang.md) | Cấu hình tenant và cờ tính năng (`tenant_setting`, `feature_flag`) kèm API | S | B2 | 12 | CORE-08-01 | **Đã gộp main** | Cờ tính năng, TenantSettingApi, ngưỡng bất thường |
| [CORE-08-08](docs/tasks/CORE-08-08-ui-pack-phien-ban.md) | UI Pack: mô hình có phiên bản, máy trạng thái, người duyệt khác người soạn | M | B2 | 12 | CORE-08-01 | **Đã gộp main** | UI Pack có phiên bản, lược đồ 1.0, gửi duyệt/từ chối |
| [CORE-08-09](docs/tasks/CORE-08-09-component-registry-sdui.md) | Component registry có phiên bản và kiểm schema bố cục SDUI | M | B2 | 13 | CORE-08-08, NEN-041 | **Đã gộp main** | Component registry SDUI có phiên bản, LayoutValidator, migration config V6 |
| [CORE-08-10](docs/tasks/CORE-08-10-kiem-tra-ui-pack.md) | Kiểm tra tự động UI Pack: tương phản AA và 7:1, kích thước ≤ 30 KB, làm sạch SVG | M | B2 | 13 | CORE-08-08, WEB-004 | **Đã gộp main** | Kiểm tra tĩnh UI Pack: tương phản, dung lượng, SVG an toàn |
| [CORE-08-11](docs/tasks/CORE-08-11-phat-hanh-ui-pack.md) | Biên dịch token sang CSS và preset PrimeNG, ký UI Pack, đẩy CDN với ETag, `GET /ui/packs/current`, EVT-23 | L | B2 | 14 | CORE-08-09, CORE-08-10, DV-10-01, WEB-004 | **Đã gộp main** | Phát hành UI Pack: ký ES256, S3, EVT-23, ETag/304 |
| [CORE-08-12](docs/tasks/CORE-08-12-quay-lui-ui-pack.md) | Quay lui UI Pack có kiểm tương thích phiên bản Policy Pack | S | B2 | 15 | CORE-08-11, CORE-05-03 | **Đã gộp main** | Quay lui UI Pack, UiPackCompatibilityApi |
| [CORE-08-13](docs/tasks/CORE-08-13-form-schema-api.md) | API `form_schema`: lưu, phục vụ theo phiên bản, kiểm trường tính điểm theo Policy Pack | M | B2 | 14 | CORE-08-08, CORE-05-03 | **Đã gộp main** | API biểu mẫu động form_schema, migration config V7 |
| [CORE-08-14](docs/tasks/CORE-08-14-kiem-tra-json-schema-cel.md) | Bộ kiểm tra JSON Schema có `x-cel`, `x-classification` dùng chung cho API nghiệp vụ, đối chiếu cel-js | L | B2 | 15 | CORE-08-13, NEN-041 | **Đã gộp main** | FormValidationApi kiểm phiếu tại máy chủ, runUiFormTests hai engine |
| [CORE-08-15](docs/tasks/CORE-08-15-manifest-plugin-tenant.md) | Manifest plugin theo tenant và kiểm chữ ký bản dựng plugin | S | B2 | 15 | CORE-08-11 | **Đã gộp main** | Plugin chuyên ngành theo manifest tenant, cosign + SRI, migration config V8 |
| [CORE-08-16](docs/tasks/CORE-08-16-phan-cong-vai-tro-uy-quyen.md) | Lưu phân công vai trò và uỷ quyền (`config.role_binding`) theo đề xuất WEB-058 | M | B2 | 12 | CORE-08-01, NEN-040, NEN-043 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-08-17](docs/tasks/CORE-08-17-luu-dinh-nghia-dashboard.md) | Lưu định nghĩa dashboard `config.dashboard_def` và cổng `api` cho analytics-api | S | B2 | 12 | CORE-08-01 | **Đã gộp main** | Đã gộp main (f74d95f1) |

### CORE-05 · MOD-05 policy: Policy Pack (12/12 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [CORE-05-01](docs/tasks/CORE-05-01-migration-schema-policy.md) | Migration schema `policy` và trigger đóng băng phiên bản đã phát hành | M | B2 | 11 | CORE-00-02, NEN-033 | **Đã gộp main** | Migration schema kèm IT RLS và ràng buộc |
| [CORE-05-02](docs/tasks/CORE-05-02-phien-ban-goi-chinh-sach.md) | Mô hình phiên bản gói, nhân bản, máy trạng thái, kế thừa theo cờ `overridable` | M | B2 | 12 | CORE-05-01 | **Đã gộp main** | Gộp main; domain Policy Pack MOD-05, 5 thao tác API |
| [CORE-05-03](docs/tasks/CORE-05-03-policy-query-api.md) | `PolicyQueryApi` và API phân giải nội bộ có bộ đệm, cung cấp `task_lifecycle` và tham số theo tenant/khối/kỳ | M | B2 | 13 | CORE-05-02 | **Đã gộp main** | Gộp main; PolicyQueryApi, GET /policies/resolve, bộ đệm |
| [CORE-05-04](docs/tasks/CORE-05-04-quy-tac-cel-tham-so.md) | API quy tắc CEL và tham số, kiểm kiểu theo khai báo ngữ cảnh | M | B2 | 13 | CORE-05-02, NEN-041 | **Đã gộp main** | Gộp main; API quy tắc CEL và tham số, kiểm kiểu |
| [CORE-05-05](docs/tasks/CORE-05-05-bang-quyet-dinh-dmn.md) | Bảng quyết định DMN 1.4: lưu, kiểm FEEL, mẫu câu giải thích, xuất XML | L | B2 | 13 | CORE-05-02 | **Đã gộp main** | Gộp main; bảng quyết định DMN 1.4, kiểm FEEL, xuất XML |
| [CORE-05-06](docs/tasks/CORE-05-06-ca-kiem-thu-vang.md) | Ca kiểm thử vàng và ký duyệt ca | M | B2 | 13 | CORE-05-02, DV-10-01 | **Đã gộp main** | Gộp main; ca kiểm thử vàng, ký ES256 |
| [CORE-05-07](docs/tasks/CORE-05-07-chay-kiem-thu-chinh-sach.md) | Chạy kiểm thử 202: cel-java, đối chiếu cel-js, kiểm thử thuộc tính jqwik | L | B2 | 14 | CORE-05-04, CORE-05-05, CORE-05-06 | **Đã gộp main** | Gộp main 22/10 |
| [CORE-05-08](docs/tasks/CORE-05-08-so-sanh-xem-truoc.md) | So sánh phiên bản và đánh giá xem trước | S | B2 | 14 | CORE-05-04 | **Đã gộp main** | Gộp main 2026-10-22 |
| [CORE-05-09](docs/tasks/CORE-05-09-trinh-phe-duyet-ky-so.md) | Trình và phê duyệt: người duyệt khác người soạn, ký số, ghi sổ cái | M | B2 | 15 | CORE-05-07, DV-10-01 | **Đã gộp main** | Gộp main 2026-10-22 |
| [CORE-05-10](docs/tasks/CORE-05-10-saga-phat-hanh-chinh-sach.md) | Phát hành: saga đa cell có bù trừ, `policy_binding`, EVT-13 | L | B2 | 16 | CORE-05-09, DV-06-05, CORE-08-12 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |
| [CORE-05-11](docs/tasks/CORE-05-11-cong-bo-workflow-bindings.md) | Lớp công bố `/policies/.../workflow-bindings` thuộc MOD-05, gọi API `wf_binding` của workflow-service | S | B2 | 13 | CORE-05-02, DV-06-08 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [CORE-05-12](docs/tasks/CORE-05-12-ho-so-tap-khach-hang.md) | Hồ sơ tập khách hàng có phiên bản (bản kê, ký, hai người), khung tham số kế thừa có giới hạn, ghim phiên bản hồ sơ vào `policy_binding` | L | B2 | 14 | NEN-044, CORE-05-02, CORE-05-04 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |

### CORE-01 · MOD-01 okr (14/14 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [CORE-01-01](docs/tasks/CORE-01-01-migration-schema-okr.md) | Migration schema `okr` (basis, objective, key_result, okr_link, kr_checkin) và phiên bản mục tiêu | M | B2 | 11 | CORE-00-02, NEN-033 | **Đã gộp main** | Migration schema okr V1–V2, IT RLS và phân vùng |
| [CORE-01-02](docs/tasks/CORE-01-02-okrapi-doc-cay-tien-do.md) | `OkrApi` công bố và API đọc cây, bảng, bản đồ liên kết, tính tiến độ KR và mục tiêu | M | B2 | 12 | CORE-01-01 | **Đã gộp main** | Gộp main (cherry-pick 2e2e1cb, chưa kèm DV-08-06); OkrApi, 9 API đọc, ProgressCalculator |
| [CORE-01-03](docs/tasks/CORE-01-03-soan-muc-tieu-kr.md) | API soạn mục tiêu và KR: If-Match, sinh mã, sao chép kỳ trước | M | B2 | 16 | CORE-01-02, CORE-08-03 | **Đã gộp main** | Gộp main 22/10 |
| [CORE-01-04](docs/tasks/CORE-01-04-kiem-tra-quy-tac-okr.md) | Kiểm tra quy tắc BR-02-01..07 ở máy chủ, danh sách từ mơ hồ, trọng số 100% | M | B2 | 17 | CORE-01-03, NEN-041 | **Đã gộp main** | Gộp main 22/10 |
| [CORE-01-05](docs/tasks/CORE-01-05-trinh-duyet-okr-bpmn.md) | Trình duyệt OKR qua BPMN `duyet-okr`, chọn người duyệt và người thay từ `wf_binding` | L | B2 | 18 | CORE-01-03, DV-06-03, CORE-01-04 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-01-06](docs/tasks/CORE-01-06-duyet-tra-lai-okr.md) | Duyệt, trả lại, rút, huỷ, duyệt hàng loạt 202, EVT-01 | M | B2 | 19 | CORE-01-05, NEN-042 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-01-07](docs/tasks/CORE-01-07-dieu-chinh-muc-tieu.md) | Điều chỉnh mục tiêu giữa kỳ bằng phiên bản mới | S | B2 | 20 | CORE-01-06, NEN-042 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-01-08](docs/tasks/CORE-01-08-check-in-kr.md) | Check-in KR: bảng chỉ thêm, nháp và gửi idempotent, tính lại giá trị và tiến độ | M | B2 | 13 | CORE-01-02 | **Đã gộp main** | Gộp main 22/10 |
| [CORE-01-09](docs/tasks/CORE-01-09-co-rui-ro-kr.md) | Cờ rủi ro KR và EVT-02 | S | B2 | 14 | CORE-01-08 | **Đã gộp main** | Gộp main 22/10 |
| [CORE-01-10](docs/tasks/CORE-01-10-so-lieu-nguon-kr.md) | Lấy số liệu nguồn của KR qua cổng tích hợp, ghi đè có lý do | M | B2 | 14 | CORE-01-08, DV-13-08 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [CORE-01-11](docs/tasks/CORE-01-11-nhac-check-in-han-okr.md) | Nhắc check-in, nhắc trước hạn và quá hạn OKR | S | B2 | 14 | CORE-01-08, DV-12-01 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-01-12](docs/tasks/CORE-01-12-uy-quyen-check-in.md) | Uỷ quyền check-in thay và chuyển giá trị khi phiên bản mục tiêu mới được duyệt | S | B2 | 21 | CORE-01-08, CORE-01-07, NEN-042 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-01-13](docs/tasks/CORE-01-13-tro-ly-ai-okr.md) | Trợ lý AI cho OKR qua ai-gateway: soát KR, ghi `ai.suggestion_log`, cờ `content_source` | M | B2 | 18 | CORE-01-04, DV-14-04 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-01-14](docs/tasks/CORE-01-14-kieu-do-moc.md) | Kiểu đo `MOC` với bảng `okr.kr_milestone`, chiều `TOI_DA`, nhãn tử số và mẫu số cho KR tỷ lệ; ghi ngày đạt và số ngày làm việc chậm | M | B2 | 13 | NEN-044, CORE-01-01, CORE-08-04 | **Đã gộp main** | Đã gộp main (f74d95f1) |

### CORE-02 · MOD-02 task: danh mục, kế hoạch, nhiệm vụ (27/28 đã gộp `main`, 0 chờ gộp, 1 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [CORE-02-01](docs/tasks/CORE-02-01-migration-danh-muc-ke-hoach.md) | Migration schema `task` phần danh mục và kế hoạch (work_catalog, catalog_item, plan, plan_version, plan_item) | M | B3 | 11 | CORE-00-02, NEN-033 | **Đã gộp main** | Migration schema task phần danh mục, chương trình, kế hoạch |
| [CORE-02-02](docs/tasks/CORE-02-02-migration-nhiem-vu.md) | Migration schema `task` phần nhiệm vụ (task, task_assignee, task_progress, task_blocker, task_change_request, task_source_doc, task_state_log) có phân vùng | M | B3 | 12 | CORE-02-01 | **Đã gộp main** | Migration schema task phần nhiệm vụ, 7 bảng, EXPLAIN 1 triệu dòng |
| [CORE-02-03](docs/tasks/CORE-02-03-danh-muc-cong-viec.md) | Danh mục công việc có phiên bản, kế thừa cấp trên, API mục danh mục | M | B3 | 16 | CORE-02-01, CORE-08-03 | **Đã gộp main** | Danh mục công việc: 8 thao tác /catalog |
| [CORE-02-04](docs/tasks/CORE-02-04-nhap-danh-muc-excel.md) | Nhập danh mục từ Excel (202, tệp lỗi) | S | B3 | 17 | CORE-02-03 | **Đã gộp main** | Nhập danh mục từ Excel bằng FastExcel (người dùng đã duyệt) |
| [CORE-02-05](docs/tasks/CORE-02-05-ke-hoach-quy.md) | Kế hoạch quý: máy trạng thái `plan_version`, API soạn, sao chép quý trước, gợi ý từ OKR | L | B3 | 17 | CORE-02-03, CORE-01-02 | **Đã gộp main** | Gộp main (cherry-pick 7631de8); kế hoạch quý, 8 thao tác PlanApi |
| [CORE-02-06](docs/tasks/CORE-02-06-kiem-tra-ke-hoach.md) | Kiểm tra kế hoạch BR-04-02..07 và tỷ trọng nhóm theo Policy Pack | M | B3 | 18 | CORE-02-05, CORE-05-03 | **Đã gộp main** | Gộp main; kiểm tra kế hoạch BR-04-02..07, tỷ trọng nhóm |
| [CORE-02-07](docs/tasks/CORE-02-07-han-duyet-ke-hoach.md) | Tính hạn duyệt kế hoạch theo lịch ngày làm việc, nhắc trước hạn và quá hạn | S | B3 | 18 | CORE-02-05, CORE-08-04 | **Đã gộp main** | Gộp main 22/10 |
| [CORE-02-08](docs/tasks/CORE-02-08-gui-duyet-ke-hoach.md) | Gửi duyệt kế hoạch: khoá nội dung, băm SHA-256, BPMN `duyet-ke-hoach`, trả lại | M | B3 | 19 | CORE-02-06, DV-06-03 | **Làm một phần** | Gộp main phần gửi duyệt/trả lại; còn cầu nối duyet-ke-hoach chờ DV-06-03, CORE-01-05 |
| [CORE-02-09](docs/tasks/CORE-02-09-duyet-ke-hoach-ky-so.md) | Duyệt kế hoạch có ký số (EVT-18 rồi EVT-03), hàng đợi thử lại | L | B3 | 20 | CORE-02-08, DV-10-02 | **Đã gộp main** | Gộp main 2026-10-22 |
| [CORE-02-10](docs/tasks/CORE-02-10-dieu-chinh-ke-hoach.md) | Điều chỉnh kế hoạch: phiên bản n+1, so sánh, lý do từng thay đổi, giới hạn số lần | L | B3 | 21 | CORE-02-09, CORE-02-12 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [CORE-02-11](docs/tasks/CORE-02-11-sinh-nhiem-vu-tu-ke-hoach.md) | Consumer EVT-03 sinh, cập nhật, huỷ nhiệm vụ; xác nhận dòng của đơn vị chủ trì khác | M | B3 | 21 | CORE-02-09, CORE-02-13 | **Đã gộp main** | Đã gộp main (fcfdd4ab) |
| [CORE-02-12](docs/tasks/CORE-02-12-taskapi-cong-bo.md) | `TaskApi` công bố và listener nội bộ cho MOD-04 | S | B3 | 16 | CORE-02-13 | **Đã gộp main** | Gộp main; TaskApi công bố, sự kiện nhiệm vụ |
| [CORE-02-13](docs/tasks/CORE-02-13-may-trang-thai-nhiem-vu.md) | Máy trạng thái nhiệm vụ khai báo, bảng chuyển trạng thái từ Policy Pack, guard CEL, `task_state_log`, EVT-06 | L | B3 | 15 | CORE-02-02, CORE-05-03, NEN-041, DL-04 | **Đã gộp main** | Gộp main (cherry-pick 0c363cd); máy trạng thái nhiệm vụ, task_state_log, EVT-06 |
| [CORE-02-14](docs/tasks/CORE-02-14-giao-viec.md) | Giao việc: nháp, sửa, xoá, giao đơn lẻ và đồng loạt 202, kiểm BR-05-01..05, BR-05-09, EVT-04 | L | B3 | 16 | CORE-02-13, CORE-00-03 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-02-15](docs/tasks/CORE-02-15-nhiem-vu-gan-giong.md) | Phát hiện nhiệm vụ gần giống và gộp có lý do | M | B3 | 17 | CORE-02-14, DV-16-01 | **Đã gộp main** | Đã gộp main (74591707) |
| [CORE-02-16](docs/tasks/CORE-02-16-nhiem-vu-tu-van-ban.md) | Nhiệm vụ từ văn bản chỉ đạo: consumer EVT-24, trích bằng AI, xác nhận và khoá đoạn | L | B3 | 17 | CORE-02-14, DV-13-03, DV-14-04 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [CORE-02-17](docs/tasks/CORE-02-17-dang-ky-nhiem-vu.md) | Đăng ký nhiệm vụ, duyệt hoặc từ chối, đưa vào điều chỉnh kế hoạch | M | B3 | 17 | CORE-02-14, NEN-042 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |
| [CORE-02-18](docs/tasks/CORE-02-18-viec-cua-toi.md) | Việc của tôi: danh sách con trỏ, lọc nhanh, nhận việc, xuất 202, cảnh báo khối lượng | M | B3 | 17 | CORE-02-14 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-02-19](docs/tasks/CORE-02-19-cap-nhat-tien-do.md) | Cập nhật tiến độ chỉ thêm, EVT-05, xung đột 409, cập nhật hàng loạt | M | B3 | 16 | CORE-02-13 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-02-20](docs/tasks/CORE-02-20-diem-nghen.md) | Điểm nghẽn: tạo, giao xử lý, chuyển cấp trên, đóng | S | B3 | 17 | CORE-02-19, NEN-042 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-02-21](docs/tasks/CORE-02-21-de-nghi-gia-han.md) | Đề nghị gia hạn, điều chỉnh: máy trạng thái năm trạng thái, gửi, rút, xem trước hệ số C | M | B3 | 17 | CORE-02-19, CORE-08-04 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-02-22](docs/tasks/CORE-02-22-duyet-de-nghi.md) | Duyệt đề nghị, tạm dừng, tiếp tục, huỷ; vượt ngưỡng chuyển BPMN `duyet-de-nghi`, người duyệt thay | L | B3 | 18 | CORE-02-21, DV-06-03 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-02-23](docs/tasks/CORE-02-23-co-qua-han.md) | Tác vụ 00:05 đặt cờ `qua_han`, nhắc, tính lại khi lịch nghỉ đổi | S | B3 | 17 | CORE-02-19, CORE-08-04 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-02-24](docs/tasks/CORE-02-24-noi-dung-mat-c3-nhiem-vu.md) | Nội dung mật C3 của nhiệm vụ: `task.task_secret_content`, `task.e2ee_key_wrap`, chia sẻ và thu hồi khoá | M | B3 | 22 | CORE-02-02, CORE-07-06, NEN-042 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |
| [CORE-02-25](docs/tasks/CORE-02-25-nhac-viec-giao-lai.md) | Nhắc việc, giao lại (`remindTask`, `reassignTask`) và đổi người chủ trì khi điều động | M | B3 | 17 | CORE-02-14, CORE-00-03 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-02-26](docs/tasks/CORE-02-26-cong-ghi-delay-risk.md) | Cột `delay_risk` và cổng ghi nội bộ cho dự báo nguy cơ trễ | S | B3 | 13 | CORE-02-02 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-02-27](docs/tasks/CORE-02-27-danh-muc-pham-vi-khoi-tinh-he-so.md) | Danh mục bốn cấp kế thừa (thêm `KHOI`, `TINH`), khoảng chỉnh điểm chuẩn so với dòng cha, `coefficient_levels` và `axis_code` trên mục danh mục, kế hoạch, nhiệm vụ | M | B3 | 17 | NEN-044, CORE-02-03, CORE-05-12 | **Đã gộp main** | Đã gộp main (ad623eff) |
| [CORE-02-28](docs/tasks/CORE-02-28-cay-kpi-che-do-phan-cong.md) | Cây nhiệm vụ nhiều bậc (`node_kind`), điểm thành phần của công việc con, chế độ phân công `MOT_NGUOI_MOT_VIEC` theo tham số hồ sơ | M | B3 | 16 | NEN-044, CORE-02-13, CORE-05-12 | **Đã gộp main** | Đã gộp main (6afcdf05) |

### CORE-03 · MOD-03 product: sản phẩm, minh chứng, nghiệm thu (8/8 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [CORE-03-01](docs/tasks/CORE-03-01-schema-product.md) | Migration schema `product` và trigger đóng băng phiên bản minh chứng | M | B3 | 11 | CORE-00-02, NEN-033 | **Đã gộp main** | Migration schema product: deliverable, evidence, acceptance, e2ee_key_wrap |
| [CORE-03-02](docs/tasks/CORE-03-02-may-trang-thai-minh-chung.md) | Máy trạng thái phiên bản minh chứng và liên động với máy trạng thái nhiệm vụ | M | B3 | 16 | CORE-03-01, CORE-02-13 | **Đã gộp main** | Gộp main; máy trạng thái phiên bản minh chứng, liên động nhiệm vụ |
| [CORE-03-03](docs/tasks/CORE-03-03-nop-phien-ban-minh-chung.md) | Khai báo sản phẩm, lưu nháp và nộp phiên bản, consumer `evidence.object.stored`, EVT-07 | L | B3 | 17 | CORE-03-02, DV-08-02 | **Đã gộp main** | Gộp main 22/10 |
| [CORE-03-04](docs/tasks/CORE-03-04-minh-chung-van-ban-voffice.md) | Minh chứng là văn bản đã ban hành trên vOffice, kiểm chữ ký số gốc | M | B3 | 18 | CORE-03-03, DV-13-03 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [CORE-03-05](docs/tasks/CORE-03-05-ocr-goi-y-nhiem-vu.md) | OCR và trích thông tin minh chứng, gợi ý nhiệm vụ khớp | M | B3 | 18 | CORE-03-03, DV-08-05 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [CORE-03-06](docs/tasks/CORE-03-06-mo-nghiem-thu-toan-ven.md) | Mở nghiệm thu và kiểm toàn vẹn tệp | S | B3 | 18 | CORE-03-03 | **Đã gộp main** | Gộp main 22/10 |
| [CORE-03-07](docs/tasks/CORE-03-07-nghiem-thu-nam-muc.md) | Nghiệm thu năm mức: AAL2, tách vai trò, nhận xét C2, EVT-08 | L | B3 | 19 | CORE-03-06, NEN-014, NEN-015 | **Đã gộp main** | Gộp main 2026-10-22 |
| [CORE-03-08](docs/tasks/CORE-03-08-nhac-qua-han-nghiem-thu.md) | Nhắc quá hạn nghiệm thu và nghiệm thu thay theo uỷ quyền | S | B3 | 20 | CORE-03-07, CORE-08-04, CORE-08-16 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |

### CORE-04 · MOD-04 appraisal: đánh giá, xếp loại (13/13 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [CORE-04-01](docs/tasks/CORE-04-01-schema-appraisal.md) | Migration schema `appraisal` và trigger `period_state` bảo vệ dữ liệu kỳ đã khoá | L | B4 | 11 | CORE-00-02, NEN-033 | **Đã gộp main** | Gộp main 2026-10-22 |
| [CORE-04-02](docs/tasks/CORE-04-02-lap-ho-so-quy.md) | Consumer EVT-09 lập hồ sơ quý theo tổ chức tại thời điểm; consumer EVT-10 lưu `score_snapshot` | M | B4 | 13 | CORE-04-01, CORE-00-03 | **Đã gộp main** | Đã gộp main (984a7ba1) |
| [CORE-04-03](docs/tasks/CORE-04-03-may-trang-thai-ho-so.md) | Máy trạng thái `appraisal_profile` và chặn chuyển bước thiếu lý do | M | B4 | 12 | CORE-04-01 | **Đã gộp main** | Gộp main 2026-10-22 |
| [CORE-04-04](docs/tasks/CORE-04-04-phieu-tu-danh-gia.md) | Phiếu tự đánh giá: lưu nháp If-Match, kiểm tiêu chí chung bằng cel-java, nộp 202 chờ EVT-18 | L | B4 | 16 | CORE-04-03, CORE-08-14, DV-10-02 | **Đã gộp main** | Gộp main 2026-10-22 |
| [CORE-04-05](docs/tasks/CORE-04-05-tham-dinh-de-xuat.md) | Thẩm định, trả lại tối đa hai lần, đề xuất, tách vai trò qua OPA | M | B4 | 17 | CORE-04-04 | **Đã gộp main** | Đã gộp main (984a7ba1) |
| [CORE-04-06](docs/tasks/CORE-04-06-api-dau-vao-tinh-diem.md) | API đọc lô `inputs` cho scoring và listener nhiệm vụ kết thúc | M | B4 | 17 | CORE-04-01, CORE-02-12 | **Đã gộp main** | Đã gộp main (984a7ba1) |
| [CORE-04-07](docs/tasks/CORE-04-07-quyet-dinh-hang-loat.md) | Quyết định xếp loại hàng loạt: lô, step-up, job 202, huỷ và tiếp tục, kiểm phân bố mức | L | B4 | 18 | CORE-04-05, DV-10-06 | **Đã gộp main** | Đã gộp main (74591707) |
| [CORE-04-08](docs/tasks/CORE-04-08-ky-so-khoa-ho-so.md) | Consumer EVT-18 chuyển `DA_KY_SO`, phát EVT-12; timer hạn khiếu nại chuyển `DA_KHOA` | M | B4 | 19 | CORE-04-07, CORE-08-04 | **Đã gộp main** | Đã gộp main (74591707) |
| [CORE-04-09](docs/tasks/CORE-04-09-vet-tinh-diem.md) | Tra cứu vết tính điểm, điểm theo luồng, xuất XLSX | S | B4 | 16 | CORE-04-02, DV-07-03 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |
| [CORE-04-10](docs/tasks/CORE-04-10-xep-loai-nam.md) | Xếp loại năm: lập từ bốn quý, xử lý thiếu quý, chặn khi còn khiếu nại, tính lại | L | B4 | 20 | CORE-04-08, DV-07-07 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |
| [CORE-04-11](docs/tasks/CORE-04-11-co-bo-tri-lai.md) | Cờ "không hoàn thành hai năm liền" và đề xuất bố trí lại sang MOD-02 | S | B4 | 21 | CORE-04-10 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |
| [CORE-04-12](docs/tasks/CORE-04-12-phan-nhom-trien-vong.md) | Phân nhóm triển vọng: lưu nháp, BR-10-07..09, trình tập thể, ký danh sách | M | B4 | 21 | CORE-04-10 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [CORE-04-13](docs/tasks/CORE-04-13-dau-vao-diem-cong-viec-su-viec.md) | Đầu vào `workItems` và `responsibilityScope` trong `inputs`, bảng `appraisal.incident`, bắt buộc lý do khi mức của `rating_proposal` khác mức gợi ý | L | B4 | 18 | NEN-044, CORE-04-06, CORE-02-27, CORE-02-28, CORE-01-14 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |

### CORE-06 · MOD-06 dossier: kiểm điểm, hồ sơ số (8/8 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [CORE-06-01](docs/tasks/CORE-06-01-schema-dossier.md) | Migration schema `dossier` | M | B4 | 11 | CORE-00-02, NEN-033 | **Đã gộp main** | Gộp main 2026-10-22 |
| [CORE-06-02](docs/tasks/CORE-06-02-hoi-nghi-kiem-diem.md) | Hội nghị kiểm điểm: tạo, thành phần, quyền thư ký và chủ trì, gửi dự thảo | M | B4 | 13 | CORE-06-01, CORE-08-04 | **Đã gộp main** | Đã gộp main (984a7ba1) |
| [CORE-06-03](docs/tasks/CORE-06-03-gop-y-an-danh.md) | Góp ý mã hoá C2 và chế độ ẩn danh qua OPA | S | B4 | 14 | CORE-06-02, NEN-015 | **Đã gộp main** | Đã gộp main (984a7ba1) |
| [CORE-06-04](docs/tasks/CORE-06-04-ket-thuc-hoi-nghi.md) | Kết thúc hội nghị: biểu quyết, sinh biên bản 202, mục khắc phục | M | B4 | 15 | CORE-06-03, DV-11-01 | **Đã gộp main** | Đã gộp main (984a7ba1) |
| [CORE-06-05](docs/tasks/CORE-06-05-truong-hop-dac-thu.md) | Phát hiện 13 trường hợp đặc thù theo bảng trong Policy Pack | L | B4 | 14 | CORE-06-01, CORE-05-03, CORE-00-03 | **Đã gộp main** | Đã gộp main (ad623eff) |
| [CORE-06-06](docs/tasks/CORE-06-06-tao-goi-ho-so.md) | Tạo gói hồ sơ khi có EVT-12, theo dõi job sinh biểu mẫu, sinh lại tệp lỗi | M | B4 | 20 | CORE-06-01, CORE-04-08, DV-11-04 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [CORE-06-07](docs/tasks/CORE-06-07-ky-khoa-goi.md) | Manifest SHA-256, danh sách kiểm tra, ký gói, khoá `DA_NOP`, phiên bản gói mới khi EVT-22 | M | B4 | 21 | CORE-06-06, DV-10-02 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [CORE-06-08](docs/tasks/CORE-06-08-xuat-goi-edxml.md) | Xuất gói ZIP có manifest ký và gửi gói con diện Trung ương qua EdXML | M | B4 | 22 | CORE-06-07, DV-13-05 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |

### CORE-07 · MOD-07 oversight: khiếu nại, hậu kiểm, cảnh báo (12/12 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [CORE-07-01](docs/tasks/CORE-07-01-schema-oversight.md) | Migration schema `oversight` | M | B4 | 11 | CORE-00-02, NEN-033 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [CORE-07-02](docs/tasks/CORE-07-02-gui-khieu-nai.md) | Khiếu nại: lưu nháp mã hoá, kiểm thời hạn, nộp có ký, chuyển hồ sơ `KHIEU_NAI`, EVT-25 | M | B4 | 20 | CORE-07-01, CORE-04-08 | **Đã gộp main** | Đã gộp main (fcfdd4ab) |
| [CORE-07-03](docs/tasks/CORE-07-03-tiep-nhan-khieu-nai.md) | Chọn người xử lý qua OPA, máy trạng thái khiếu nại, tiếp nhận, không thụ lý, rút | M | B4 | 21 | CORE-07-02, NEN-040, DV-06-04 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |
| [CORE-07-04](docs/tasks/CORE-07-04-giai-trinh-ket-luan.md) | Yêu cầu giải trình, timer hạn, kết luận, văn bản trả lời có ký, tạo yêu cầu mở lại | L | B4 | 22 | CORE-07-03, DV-07-08, CORE-07-07 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [CORE-07-05](docs/tasks/CORE-07-05-bao-luu-tu-dong.md) | Bảo lưu, tự đóng, nhật ký giải mã, gợi ý khiếu nại trùng | S | B4 | 23 | CORE-07-04, CORE-00-05 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [CORE-07-06](docs/tasks/CORE-07-06-ma-hoa-dau-cuoi-c3.md) | Mã hoá đầu cuối C3 phía máy chủ: lưu bản mã, khoá công khai người nhận, bọc khoá, phục hồi tổ chức Shamir | L | B4 | 21 | CORE-07-02, NEN-015, NEN-042 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |
| [CORE-07-07](docs/tasks/CORE-07-07-yeu-cau-mo-lai.md) | Yêu cầu mở lại kỳ và mô phỏng ảnh hưởng | M | B4 | 18 | CORE-07-01, DV-07-08 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [CORE-07-08](docs/tasks/CORE-07-08-bon-mat-phe-duyet.md) | Bốn mắt: chọn hai người duyệt qua OPA, phê duyệt có step-up và ký số, EVT-22 | M | B4 | 19 | CORE-07-07, NEN-040, DV-10-02, DV-06-04 | **Đã gộp main** | Đã gộp main (984a7ba1) |
| [CORE-07-09](docs/tasks/CORE-07-09-dieu-phoi-tinh-lai.md) | Điều phối tính lại, sinh lại, ký lại sau EVT-22; hậu kiểm có bằng chứng sổ cái | M | B4 | 20 | CORE-07-08, CORE-08-06, DV-09-05 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [CORE-07-10](docs/tasks/CORE-07-10-tiep-nhan-canh-bao.md) | Cảnh báo bất thường: consumer EVT-20, chống trùng, gộp, hành động tự động, cờ chặn minh chứng | M | B4 | 12 | CORE-07-01, DV-15-07 | **Đã gộp main** | Đã gộp main (ad623eff) |
| [CORE-07-11](docs/tasks/CORE-07-11-xu-ly-canh-bao.md) | Xử lý cảnh báo: hợp lệ, giao kiểm tra, kết luận; quyền inspector; ngưỡng phát hiện có phiên bản | M | B4 | 19 | CORE-07-10, CORE-08-07, CORE-07-07 | **Đã gộp main** | Đã gộp main (fcfdd4ab) |
| [CORE-07-12](docs/tasks/CORE-07-12-chi-muc-tra-cuu-nhat-ky-c2.md) | Chỉ mục kiểm toán và API tra cứu nhật ký truy cập dữ liệu C2 | M | B4 | 12 | CORE-00-05 | **Đã gộp main** | Đã gộp main (f74d95f1) |

### DV-01 · SVC-01 api-gateway (4/4 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DV-01-01](docs/tasks/DV-01-01-tuyen-apisix-xac-thuc.md) | Tuyến APISIX theo module (ApisixRoute), xác thực JWT qua JWKS, plugin gán claim tenant | M | B5 | 2 | INF-08, DV-02-01 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [DV-01-02](docs/tasks/DV-01-02-gioi-han-toc-do-waf.md) | Giới hạn tốc độ ba tầng, plugin bảo vệ API, cắt tải, WAF Coraza với OWASP CRS 4 | M | B5 | 3 | DV-01-01 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [DV-01-03](docs/tasks/DV-01-03-tuyen-cong-khai-ci.md) | Danh sách tuyến công khai tường minh và kiểm thử CI quét toàn bộ CRD | S | B5 | 3 | DV-01-01 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [DV-01-04](docs/tasks/DV-01-04-apisix-lop-ngoai.md) | APISIX lớp ngoài ở vùng toàn cục: phân giải tenant sang cell, đệm 5 phút, khám phá cell | M | B5 | 9 | DV-01-01, DV-03-02 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |

### DV-02 · SVC-02 iam (Keycloak) (8/8 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DV-02-01](docs/tasks/DV-02-01-mau-realm-production.md) | Mẫu realm production theo cell với Organizations, PKCE, chính sách mật khẩu, khoá sau 5 lần sai, thời hạn phiên | M | B5 | 1 | – | **Đã gộp main** | Đã gộp feat/B5-dinh-danh-to-chuc vào main |
| [DV-02-02](docs/tasks/DV-02-02-claim-ngu-canh.md) | Protocol mapper claim ngữ cảnh và API chọn đơn vị, vai trò (`PUT /org/me/context`) | M | B5 | 12 | DV-02-01, DV-04-04 | **Đã gộp main** | Đã gộp main (nhánh B1 d598b9e) |
| [DV-02-03](docs/tasks/DV-02-03-mfa-step-up.md) | MFA theo vai trò, thiết bị, rủi ro; step-up `acr` và `auth_time` | M | B5 | 2 | DV-02-01 | **Đã gộp main** | Luồng đăng nhập theo bậc xác thực, MFA theo vai trò, thiết bị, rủi ro |
| [DV-02-04](docs/tasks/DV-02-04-moi-gioi-vneid.md) | Môi giới VNeID: state, nonce, PKCE, liên kết danh tính bằng HMAC | L | B5 | 10 | DV-02-01, DV-04-01, NEN-015 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [DV-02-05](docs/tasks/DV-02-05-su-kien-keycloak.md) | Event listener Keycloak đẩy `iam.auth.event.v1`, báo truy cập bất thường, thu hồi phiên | M | B5 | 4 | DV-02-01, NEN-007 | **Đã gộp main** | Đã gộp main (nhánh B1 37a1606) |
| [DV-02-06](docs/tasks/DV-02-06-dong-bo-nguoi-dung.md) | Consumer EVT-16 đồng bộ người dùng qua Admin API | M | B5 | 12 | DV-02-01, DV-04-03 | **Đã gộp main** | Đã gộp main (nhánh B1 6389317) |
| [DV-02-07](docs/tasks/DV-02-07-consumer-evt14-keycloak.md) | Consumer EVT-14 cập nhật Organizations Keycloak khi tổ chức thay đổi | S | B5 | 12 | DV-02-01, DV-04-03 | **Đã gộp main** | Đã gộp main (nhánh B1 6389317) |
| [DV-02-08](docs/tasks/DV-02-08-ma-khoi-phuc-mfa.md) | Mã khôi phục MFA: cấp, dùng một lần, thu hồi | S | B5 | 4 | DV-02-03, NEN-043 | **Đã gộp main** | Mã khôi phục MFA: cấp, dùng một lần, thu hồi (SPI mission-iam) |

### DV-03 · SVC-03 tenant-registry (Go, toàn cục) (7/7 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DV-03-01](docs/tasks/DV-03-01-khung-tenant-registry.md) | Khung service Go và migration schema `registry` | M | B5 | 7 | NEN-017 | **Đã gộp main** | Đã gộp feat/B5-dinh-danh-to-chuc vào main |
| [DV-03-02](docs/tasks/DV-03-02-phan-giai-tenant.md) | API phân giải tenant theo tên miền, danh sách cell, đệm | M | B5 | 8 | DV-03-01 | **Đã gộp main** | Đã gộp feat/B5-dinh-danh-to-chuc vào main |
| [DV-03-03](docs/tasks/DV-03-03-vong-doi-tenant.md) | Tenant: máy trạng thái bảy trạng thái, API nháp, gợi ý mô hình thuê bao và chọn cell theo tải | L | B5 | 8 | DV-03-01 | **Đã gộp main** | Đã gộp feat/B5-dinh-danh-to-chuc vào main |
| [DV-03-04](docs/tasks/DV-03-04-saga-cap-phat-1.md) | Saga cấp phát tenant phần 1: CSDL hoặc schema, đăng ký pool, KEK trong HSM | L | B5 | 11 | DV-03-03, DV-06-05 | **Đã gộp main** | Saga cấp phát phần 1: bước CSDL và bước khoá mã hoá có bù trừ |
| [DV-03-05](docs/tasks/DV-03-05-saga-cap-phat-2.md) | Saga cấp phát tenant phần 2: Organization Keycloak, tên miền và TLS, sao chép Policy Pack và UI Pack, lịch mặc định, kích hoạt | L | B5 | 12 | DV-03-04, DV-02-01 | **Đã gộp main** | Saga cấp phát phần 2 và kích hoạt tenant |
| [DV-03-06](docs/tasks/DV-03-06-idp-cua-tenant.md) | Khai báo IdP của tenant và kiểm kết nối OIDC, SAML, LDAP | S | B5 | 9 | DV-03-03 | **Đã gộp main** | Đã gộp main (2975c79): khai báo IdP OIDC/SAML/LDAP, kiểm kết nối chống SSRF, NetworkPolicy |
| [DV-03-07](docs/tasks/DV-03-07-chuyen-tenant-cell.md) | Chuyển tenant giữa các cell bằng sao chép logic, hai người duyệt | L | B5 | 13 | DV-03-05, NEN-043 | **Đã gộp main** | Chuyển tenant giữa cell; bảng registry.tenant_move chờ duyệt (wiki 14 mục 34) |

### DV-04 · SVC-04 org-service (8/8 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DV-04-01](docs/tasks/DV-04-01-khung-org-service.md) | Khung service và migration schema `org` hai trục thời gian có ràng buộc loại trừ | L | B5 | 9 | NEN-019, NEN-033 | **Đã gộp main** | Đã gộp feat/B5-dinh-danh-to-chuc vào main |
| [DV-04-02](docs/tasks/DV-04-02-don-vi-asof.md) | Đơn vị và quan hệ: truy vấn `asOf`, `knownAt`, cây con, chặn vòng | M | B5 | 10 | DV-04-01 | **Đã gộp main** | Đã gộp feat/B5-dinh-danh-to-chuc vào main (p95=38.7ms trên 11.111 đơn vị) |
| [DV-04-03](docs/tasks/DV-04-03-thay-doi-to-chuc.md) | Thay đổi tổ chức (sáp nhập, chia tách, đổi tên) có tiền thân, phát EVT-14, EVT-15, EVT-16 | M | B5 | 11 | DV-04-02, NEN-011 | **Đã gộp main** | Đã gộp feat/B5-dinh-danh-to-chuc vào main (65/65 test PASS) |
| [DV-04-04](docs/tasks/DV-04-04-phan-cong.md) | Phân công: một vị trí chính tại một thời điểm, kiêm nhiệm, điều động tương lai | M | B5 | 11 | DV-04-02 | **Đã gộp main** | Đã gộp main (b55b7d1): quản lý phân công 2 trục thời gian, đường báo cáo ReportingLineVersion và tra cứu cán bộ |
| [DV-04-05](docs/tasks/DV-04-05-nhap-cay-excel.md) | Nhập cây tổ chức từ Excel (202, báo cáo lỗi) | S | B5 | 11 | DV-04-02 | **Đã gộp main** | Đã gộp main (a36f8d0) |
| [DV-04-06](docs/tasks/DV-04-06-scim.md) | SCIM 2.0 cho HRM và IdP khách hàng | M | B5 | 12 | DV-04-04 | **Đã gộp main** | Đã gộp main (fcff925) |
| [DV-04-07](docs/tasks/DV-04-07-doi-soat-nhan-su.md) | Đối soát nhân sự: khớp người bằng HMAC và ngày sinh, bảng lệch, phân loại, API giải quyết | L | B5 | 12 | DV-04-04, DV-13-06 | **Đã gộp main** | Đã gộp main (8b84e3a) |
| [DV-04-08](docs/tasks/DV-04-08-org-republish.md) | Lệnh phát lại sự kiện tổ chức (`org-republish`) cho bên nhận bị lệch | S | B5 | 12 | DV-04-03, NEN-022 | **Đã gộp main** | Đã gộp main (nhánh B1 7dca3d2) |

### DV-06 · SVC-06 workflow-service (Flowable) (12/12 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DV-06-01](docs/tasks/DV-06-01-khung-flowable.md) | Khung Flowable 7 nhúng Spring Boot, CSDL `flowable`, lịch sử mức audit, tách API và async executor | L | B6 | 9 | NEN-019 | **Đã gộp main** | Đã gộp main (9e7c761): khung workflow-service Flowable 7.2, tinh chỉnh PostgreSQL, Helm API/Executor + KEDA |
| [DV-06-02](docs/tasks/DV-06-02-hop-viec.md) | `wf_template`, `wf_binding`, `wf_task_projection`; API hộp việc, hoàn thành, hoàn thành hàng loạt | M | B6 | 10 | DV-06-01 | **Đã gộp main** | Gộp main 22/10 |
| [DV-06-03](docs/tasks/DV-06-03-bpmn-duyet-phan-1.md) | Mẫu BPMN luồng duyệt phần 1: OKR, kế hoạch, đề nghị gia hạn, kèm DMN chọn cấp duyệt, kiểm thử và linter | L | B6 | 11 | DV-06-02 | **Đã gộp main** | Gộp main 22/10 |
| [DV-06-04](docs/tasks/DV-06-04-bpmn-duyet-phan-2.md) | Mẫu BPMN luồng duyệt phần 2: hồ sơ đánh giá, khiếu nại, mở lại kỳ, phát hành Policy Pack | L | B6 | 12 | DV-06-03 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [DV-06-05](docs/tasks/DV-06-05-khung-saga.md) | Khung saga tầng 3: bước, bù trừ, trạng thái bền, 202 và theo dõi | L | B6 | 10 | DV-06-01 | **Đã gộp main** | Đã gộp main (nhánh B6 fad5653) |
| [DV-06-06](docs/tasks/DV-06-06-saga-chot-ky.md) | Saga chốt kỳ: tiền điều kiện, khoá, tính điểm, sinh biểu mẫu, ký lô, niêm phong, lập hồ sơ | L | B6 | 19 | DV-06-05, CORE-08-05, DV-07-06, DV-11-04, DV-10-06, DV-09-03 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [DV-06-07](docs/tasks/DV-06-07-bu-tru-chot-ky.md) | Bù trừ saga chốt kỳ, chạy lại hồ sơ lỗi, kiểm tất định bằng chạy lại mẫu | L | B6 | 20 | DV-06-06 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [DV-06-08](docs/tasks/DV-06-08-api-wf-binding.md) | API `wf_binding`: lưu, linter tham số, mô phỏng trên engine thử | M | B6 | 12 | DV-06-03 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |
| [DV-06-09](docs/tasks/DV-06-09-mau-luong-noi-bo.md) | Mẫu luồng nội bộ (BS-01): mở rộng `wf_template`, `wf_template_rollout`, linter, saga phát hành cuốn chiếu | L | B6 | 13 | DV-06-08 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [DV-06-10](docs/tasks/DV-06-10-giam-sat-tien-trinh.md) | API giám sát tiến trình theo mẫu (BS-01): chuyển người xử lý, gia hạn, sơ đồ | M | B6 | 11 | DV-06-02 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [DV-06-11](docs/tasks/DV-06-11-chieu-lich-su-g2.md) | Chiếu lịch sử tiến trình sang kho phân tích và chỉ số giám sát G2 | M | B6 | 11 | DV-06-02, DV-15-01 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [DV-06-12](docs/tasks/DV-06-12-mau-duyet-kep-chi-huy.md) | Mẫu BPMN duyệt kép chỉ huy và cấp uỷ (`duyet-kep-chi-huy-cap-uy`) | M | B6 | 13 | DV-06-04 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |

### DV-07 · SVC-07 scoring-service (8/8 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DV-07-01](docs/tasks/DV-07-01-khung-scoring.md) | Khung service và migration schema `scoring` | M | B4 | 9 | NEN-019, NEN-033 | **Đã gộp main** | Gộp main 2026-10-22 |
| [DV-07-02](docs/tasks/DV-07-02-nap-policy-pack.md) | Nạp Policy Pack, biên dịch sẵn CEL và DMN, đệm theo phiên bản | M | B4 | 14 | DV-07-01, NEN-041, CORE-05-03 | **Đã gộp main** | Đã gộp main (ad623eff) |
| [DV-07-03](docs/tasks/DV-07-03-chuoi-tinh-diem.md) | Chuỗi tính A, B, C, KPI nhóm, điểm và `score_trace` | L | B4 | 15 | DV-07-02 | **Đã gộp main** | Đã gộp main (fcfdd4ab) |
| [DV-07-04](docs/tasks/DV-07-04-tinh-dan-su-kien.md) | Tính dần theo sự kiện EVT-06, EVT-08, EVT-13, EVT-22, phát EVT-10 | M | B4 | 16 | DV-07-03, NEN-012 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [DV-07-05](docs/tasks/DV-07-05-dmn-xep-loai.md) | DMN xếp loại, câu giải thích, snapshot có `input_hash`; luồng Đảng và Nhà nước song song | L | B4 | 16 | DV-07-03 | **Đã gộp main** | Đã gộp main (fcfdd4ab) |
| [DV-07-06](docs/tasks/DV-07-06-tinh-lai-hang-loat.md) | Tính lại hàng loạt cuối kỳ: chia lô, worker co giãn, đọc `inputs`, hàng đợi lỗi (NFR-06) | L | B4 | 18 | DV-07-05, CORE-04-06 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [DV-07-07](docs/tasks/DV-07-07-snapshot-nam.md) | Snapshot năm (băm của các băm quý) và DMN năm | M | B4 | 17 | DV-07-05 | **Đã gộp main** | Đã gộp main (fcfdd4ab) |
| [DV-07-08](docs/tasks/DV-07-08-mo-phong-xem-truoc.md) | API mô phỏng và xem trước với phiên bản Policy Pack gốc | M | B4 | 17 | DV-07-05 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |

### DV-08 · SVC-08 evidence-service (6/6 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DV-08-01](docs/tasks/DV-08-01-khung-evidence-service.md) | Khung WebFlux, migration schema `evidence`, client S3 cho Ceph RGW | M | B3 | 9 | NEN-019, NEN-033 | **Đã gộp main** | Khung evidence-service SVC-08, RLS, Object Lock |
| [DV-08-02](docs/tasks/DV-08-02-tai-len-nhieu-phan.md) | Tải lên nhiều phần có tiếp tục, SHA-256 khi nhận, Object Lock, URL tải ký 5 phút | L | B3 | 10 | DV-08-01 | **Đã gộp main** | Tải tệp minh chứng nhiều phần, khớp hợp đồng products.yaml |
| [DV-08-03](docs/tasks/DV-08-03-khu-trung-theo-bam.md) | Khử trùng theo băm và phát hiện trùng tức thời phát EVT-20 | M | B3 | 11 | DV-08-02 | **Đã gộp main** | Khử trùng minh chứng theo băm; EVT-20 do SVC-15 phát theo hợp đồng |
| [DV-08-04](docs/tasks/DV-08-04-quet-ma-doc.md) | Quét mã độc ClamAV bất đồng bộ, cách ly, thông báo | M | B3 | 11 | DV-08-02 | **Đã gộp main** | Gộp main; quét mã độc nền, KEDA worker, V3 migration |
| [DV-08-05](docs/tasks/DV-08-05-ocr-qua-ai.md) | Gọi OCR qua ai-gateway cho minh chứng | S | B3 | 12 | DV-08-02, DV-14-05, DV-08-04 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |
| [DV-08-06](docs/tasks/DV-08-06-xem-truoc-tep-minh-chung.md) | Xem trước tệp minh chứng (`getFilePreview`) qua Gotenberg, chỉ tệp đã quét sạch | S | B3 | 12 | DV-08-04, DV-11-01 | **Đã gộp main** | Gộp main; xem trước minh chứng theo trang, PDFBox đã duyệt (YC-PT-18) |

### DV-09 · SVC-09 ledger-service (Go) (5/5 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DV-09-01](docs/tasks/DV-09-01-khung-ledger-migration.md) | Khung service và migration CSDL `ledger` chỉ ghi thêm | M | B6 | 7 | NEN-017 | **Đã gộp main** | Đã gộp main (40e7b32): khung SVC-09 ledger-service Go, schema sổ cái bất biến và phân vùng 2 tầng |
| [DV-09-02](docs/tasks/DV-09-02-ghi-chuoi-bam.md) | Consumer ghi chuỗi băm 64 phân vùng theo tenant, chuẩn hoá RFC 8785 | L | B6 | 8 | DV-09-01 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [DV-09-03](docs/tasks/DV-09-03-niem-phong-merkle.md) | Niêm phong Merkle hai cấp mỗi giờ, dấu thời gian RFC 3161, ký qua esign, EVT-19, bản neo | L | B6 | 11 | DV-09-02, DV-10-03 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [DV-09-04](docs/tasks/DV-09-04-kiem-tra-toan-ven.md) | Kiểm tra toàn vẹn định kỳ, chỉ ra bản ghi bị phá, phát EVT-20 | M | B6 | 12 | DV-09-03 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [DV-09-05](docs/tasks/DV-09-05-tra-cuu-xuat-bang-chung.md) | API tra cứu, xuất bằng chứng có ký và công cụ kiểm chứng độc lập `ledger-verify` | L | B6 | 12 | DV-09-03 | **Đã gộp main** | Đợt 10, main c7d74c17 |

### DV-10 · SVC-10 esign-service (6/6 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DV-10-01](docs/tasks/DV-10-01-khung-esign-provider.md) | Khung service, migration schema `esign`, giao diện nhà cung cấp `SignatureProvider` | M | B6 | 9 | NEN-019, NEN-033 | **Đã gộp main** | Đã gộp main (nhánh B6 bd3538e) |
| [DV-10-02](docs/tasks/DV-10-02-nha-cung-cap-mysign.md) | Nhà cung cấp MySign (CSC API) ký từ xa bất đồng bộ, EVT-18 | L | B6 | 10 | DV-10-01 | **Đã gộp main** | Gộp main 22/10 |
| [DV-10-03](docs/tasks/DV-10-03-hsm-pkcs11-tsa.md) | Nhà cung cấp HSM qua PKCS#11 và dấu thời gian TSA | L | B6 | 10 | DV-10-01 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [DV-10-04](docs/tasks/DV-10-04-viettel-ca-ca-chinh-phu.md) | Nhà cung cấp Viettel-CA và CA chuyên dùng Chính phủ | M | B6 | 10 | DV-10-01 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [DV-10-05](docs/tasks/DV-10-05-ban-co-yeu-cach-ly.md) | Nhà cung cấp Ban Cơ yếu cho bản cách ly qua `CryptoProvider` | M | B6 | 10 | DV-10-01 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [DV-10-06](docs/tasks/DV-10-06-ky-lo-quyet-dinh.md) | Ký lô 100.000 phiếu trong 4 giờ: phiên ký một lần xác nhận, worker co giãn | M | B6 | 11 | DV-10-03 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |

### DV-11 · SVC-11 document-service (4/4 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DV-11-01](docs/tasks/DV-11-01-khung-docgen-gotenberg.md) | Khung service, migration schema `docgen`, Gotenberg, job sinh 202 | M | B6 | 9 | NEN-019, NEN-033 | **Đã gộp main** | Dịch vụ sinh tài liệu: docx4j, Gotenberg PDF/A-2b, veraPDF, S3 |
| [DV-11-02](docs/tasks/DV-11-02-mau-1a-1d.md) | Mẫu 1A, 1B, 1C, 1D bằng docx4j | M | B6 | 10 | DV-11-01, DL-05 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [DV-11-03](docs/tasks/DV-11-03-mau-2-mau-3-bang-diem.md) | Mẫu 2, Mẫu 3, bảng chấm điểm | M | B6 | 10 | DV-11-01, DL-05 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [DV-11-04](docs/tasks/DV-11-04-goi-ho-so-pdfa.md) | Gói hồ sơ PDF/A có tem, phiên bản mẫu theo Policy Pack, EVT-17 | M | B6 | 11 | DV-11-02, DV-11-03 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |

### DV-12 · SVC-12 notification-service (Go) (5/5 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DV-12-01](docs/tasks/DV-12-01-khung-notification-consumer.md) | Khung service Go, migration schema `notify`, consumer EVT-21 và các sự kiện cần báo | M | B7 | 7 | NEN-017 | **Đã gộp main** | Gộp main; notification-service Go |
| [DV-12-02](docs/tasks/DV-12-02-thong-bao-sse.md) | Thông báo thời gian thực SSE `/notifications/stream`, bus Valkey Streams giữa các pod | M | B7 | 8 | DV-12-01 | **Đã gộp main** | Gộp main; notification-service Go |
| [DV-12-03](docs/tasks/DV-12-03-kenh-email-sms-day.md) | Kênh email, SMS, FCM, APNs có bulkhead, thử lại, tuỳ chọn kênh, mẫu thông báo | L | B7 | 8 | DV-12-01 | **Đã gộp main** | Gộp main 2026-10-22 |
| [DV-12-04](docs/tasks/DV-12-04-thong-bao-hang-loat.md) | Thông báo hàng loạt cuối kỳ chia theo tenant, giới hạn tốc độ theo kênh | S | B7 | 9 | DV-12-03 | **Đã gộp main** | Gộp main 2026-10-22 |
| [DV-12-05](docs/tasks/DV-12-05-thiet-bi-day-tuy-chon-kenh.md) | Đăng ký thiết bị nhận thông báo đẩy và tuỳ chọn kênh: bảng `notify`, API | S | B7 | 8 | DV-12-01, NEN-043 | **Đã gộp main** | Gộp main; notification-service Go |

### DV-13 · SVC-13 integration-gateway (11/11 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DV-13-01](docs/tasks/DV-13-01-khung-camel-acl.md) | Khung Camel, migration schema `integ`, khung lớp chống ô nhiễm, `mapping_xref`, dead letter | L | B5 | 9 | NEN-019, NEN-033 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |
| [DV-13-02](docs/tasks/DV-13-02-cloudevents-jws-mtls.md) | CloudEvents ra ngoài có JWS và mTLS | M | B5 | 10 | DV-13-01 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [DV-13-03](docs/tasks/DV-13-03-ket-noi-voffice.md) | Bộ kết nối vOffice: văn bản chỉ đạo đến (EVT-24) và văn bản đã ban hành | L | B5 | 10 | DV-13-01 | **Đã gộp main** | Đợt 8–9, main ff7be5b8 |
| [DV-13-04](docs/tasks/DV-13-04-ket-noi-ioffice.md) | Bộ kết nối iOffice | M | B5 | 11 | DV-13-03 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [DV-13-05](docs/tasks/DV-13-05-truc-lien-thong-edxml.md) | Trục liên thông văn bản quốc gia (EdXML, X-Road) | L | B5 | 10 | DV-13-01 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [DV-13-06](docs/tasks/DV-13-06-lgsp-ndxp-cbccvc.md) | LGSP, NDXP và CSDL cán bộ, công chức, viên chức (đồng bộ đêm, `sync_cursor`) | L | B5 | 10 | DV-13-01 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [DV-13-07](docs/tasks/DV-13-07-csdl-dang-vien.md) | CSDL đảng viên | M | B5 | 11 | DV-13-06 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [DV-13-08](docs/tasks/DV-13-08-hrm-so-lieu-kr.md) | HRM và số liệu nguồn cho KR | M | B5 | 10 | DV-13-01 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [DV-13-09](docs/tasks/DV-13-09-ban-neo-chia-se-xep-loai.md) | Gửi bản neo sổ cái cho bên thứ ba và chia sẻ quyết định xếp loại ra ngoài | M | B5 | 12 | DV-13-02, DV-09-03 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [DV-13-10](docs/tasks/DV-13-10-goi-chuyen-ho-so-lien-cell.md) | Gói chuyển hồ sơ cán bộ liên cell có ký số | M | B5 | 11 | DV-13-02, DV-10-01 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| [DV-13-11](docs/tasks/DV-13-11-pact-he-ngoai.md) | Kiểm thử hợp đồng Pact với từng hệ ngoài bằng đối tác giả lập | M | B5 | 11 | DV-13-03, QA-02 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |

### DV-14 · SVC-14 ai-gateway (Python) (7/7 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DV-14-01](docs/tasks/DV-14-01-khung-ai-gateway.md) | Khung FastAPI, migration schema `ai`, client vLLM, đổi token sang vai trò `ai_readonly` | M | B7 | 5 | NEN-018 | **Đã gộp main** | Gộp main 22/10 |
| [DV-14-02](docs/tasks/DV-14-02-chi-muc-doc-chunk.md) | Chỉ mục `doc_chunk` có ACL bằng pgvector, consumer EVT-24 | L | B7 | 6 | DV-14-01 | **Đã gộp main** | Gộp main 22/10 |
| [DV-14-03](docs/tasks/DV-14-03-rao-chan-ai.md) | Rào chắn đầu vào, đầu ra và `guardrail_event`; không gửi dữ liệu C3 | L | B7 | 6 | DV-14-01 | **Đã gộp main** | Gộp main 22/10 |
| [DV-14-04](docs/tasks/DV-14-04-api-tro-ly-ai.md) | API trợ lý: soạn OKR, soát KR, trích nhiệm vụ, giải thích điểm, 202 khi hàng đợi đầy | M | B7 | 7 | DV-14-03 | **Đã gộp main** | Gộp main 22/10 |
| [DV-14-05](docs/tasks/DV-14-05-ocr-minh-chung.md) | OCR minh chứng | M | B7 | 6 | DV-14-01 | **Đã gộp main** | Gộp main 2026-10-22 |
| [DV-14-06](docs/tasks/DV-14-06-nhat-ky-goi-y-tom-tat.md) | Nhật ký gợi ý (chấp nhận, bỏ qua, nguồn) và tóm tắt tuần cho lãnh đạo | M | B7 | 8 | DV-14-04 | **Đã gộp main** | Gộp main 2026-10-22 |
| [DV-14-07](docs/tasks/DV-14-07-chuyen-giong-noi-van-ban.md) | Chuyển giọng nói thành văn bản (`transcribeSpeech`) | M | B7 | 6 | DV-14-01 | **Đã gộp main** | Gộp main 2026-10-22 |

### DV-15 · SVC-15 analytics (11/11 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DV-15-01](docs/tasks/DV-15-01-clickhouse-mo-hinh-sao.md) | ClickHouse `analytics_<cell>` mô hình sao, Kafka engine và materialized view | L | B7 | 4 | INF-11, NEN-007 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [DV-15-02](docs/tasks/DV-15-02-debezium-phan-tich.md) | Connector Debezium phân tích và giả danh hoá cột C2 | M | B7 | 5 | DV-15-01, INF-10 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [DV-15-03](docs/tasks/DV-15-03-cube-chi-so-chuan.md) | Cube: chỉ số chuẩn của bảng điều hành, chiều đơn vị, kỳ, khối, luồng, bộ lọc an ninh | L | B7 | 5 | DV-15-01 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [DV-15-04](docs/tasks/DV-15-04-analytics-api.md) | analytics-api: proxy Cube theo token, đệm 60 giây, API định nghĩa dashboard | M | B7 | 13 | DV-15-03, CORE-08-17 | **Đã gộp main** | Đã gộp main (984a7ba1) |
| [DV-15-05](docs/tasks/DV-15-05-superset-nhung.md) | Superset nhúng với guest token theo phạm vi | M | B7 | 14 | DV-15-03, DV-15-04 | **Đã gộp main** | Đã gộp main (984a7ba1) |
| [DV-15-06](docs/tasks/DV-15-06-chuoi-truy-vet.md) | Đi xuống tới minh chứng: chuỗi truy vết, xuất bằng chứng và báo cáo 202, nhắc và giao lại | M | B7 | 14 | DV-15-04 | **Đã gộp main** | Đã gộp main (6afcdf05) |
| [DV-15-07](docs/tasks/DV-15-07-khung-job-bat-thuong.md) | Khung job phát hiện bất thường hằng đêm và dấu hiệu 1, 2, phát EVT-20 | M | B7 | 5 | DV-15-01 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [DV-15-08](docs/tasks/DV-15-08-dau-hieu-3-5-7.md) | Dấu hiệu bất thường 3, 5, 7 | M | B7 | 6 | DV-15-07 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [DV-15-09](docs/tasks/DV-15-09-dau-hieu-6-8.md) | Dấu hiệu bất thường 6, 8 và tự đóng khi hết dấu hiệu; dấu hiệu tỷ lệ chấp nhận khách quan | M | B7 | 6 | DV-15-07 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [DV-15-10](docs/tasks/DV-15-10-du-bao-tre-han.md) | Dự báo nguy cơ trễ nhiệm vụ | M | B7 | 14 | DV-15-01, CORE-02-26 | **Đã gộp main** | Đã gộp main (f74d95f1) |
| [DV-15-11](docs/tasks/DV-15-11-mirrormaker-kho-quoc-gia.md) | MirrorMaker 2 đẩy dữ liệu tổng hợp lên kho phân tích quốc gia | M | B7 | 5 | DV-15-01, INF-10 | **Đã gộp main** | Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |

### DV-16 · SVC-16 search (2/2 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DV-16-01](docs/tasks/DV-16-01-chi-muc-opensearch.md) | Chỉ mục OpenSearch, alias lọc theo tenant, indexer consumer EVT-04, EVT-24 | M | B7 | 8 | INF-11, NEN-012 | **Đã gộp main** | Gộp main 2026-10-22 |
| [DV-16-02](docs/tasks/DV-16-02-api-tim-kiem.md) | API tìm kiếm toàn cục, kiểm quyền lại ở lõi trước khi trả chi tiết | M | B7 | 11 | DV-16-01, CORE-00-02, NEN-013, NEN-014 | **Đã gộp main** | Gộp main 2026-10-22 |

### DL · Dữ liệu, nội dung nghiệp vụ và chuyển đổi (13/13 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [DL-01](docs/tasks/DL-01-policy-pack-quy-dinh-moi.md) | Policy Pack "quy định mới": công thức CEL tính điểm luồng Đảng và luồng Nhà nước theo QĐ 366, NĐ 335/2025 | L | B11 | 14 | CORE-05-04 | **Đã gộp main** | Đã gộp `main` (commit `cf3eafd` – Policy Pack giả lập cấu hình động theo QĐ 366 và NĐ 335/2025) |
| [DL-02](docs/tasks/DL-02-dmn-xep-loai-dac-thu.md) | Bảng DMN xếp loại quý, năm và bảng 13 trường hợp đặc thù | M | B11 | 15 | DL-01, CORE-05-05 | **Đã gộp main** | Đã gộp `main` (commit `6aaab2d` – bảng DMN xếp loại quý, năm và 13 trường hợp đặc thù giả lập) |
| [DL-03](docs/tasks/DL-03-bo-ca-kiem-thu-vang.md) | Bộ ca kiểm thử vàng đầy đủ do nghiệp vụ ký | L | B11 | 16 | DL-02, CORE-05-06 | **Đã gộp main** | Đã gộp `main` (commit `f00b178` – bộ ca kiểm thử vàng giả lập cho hai gói quy tắc và trình chạy tham chiếu) |
| [DL-04](docs/tasks/DL-04-task-lifecycle-tham-so.md) | Bảng chuyển trạng thái `task_lifecycle` và tham số luồng duyệt mặc định | S | B11 | 14 | CORE-05-03 | **Đã gộp main** | Đã gộp main (e9620db) |
| [DL-05](docs/tasks/DL-05-mau-docx-bieu-mau.md) | Mẫu DOCX 1A–1D, 2, 3 và bảng chấm điểm theo văn bản pháp lý | M | B11 | 1 | – | **Đã gộp main** | Đã gộp `main` (commit `cf3eafd` – mẫu biểu DOCX 1A–1D, 2, 3 và bảng chấm điểm bản giả lập) |
| [DL-06](docs/tasks/DL-06-ui-pack-mac-dinh.md) | UI Pack mặc định, bộ token người cao tuổi, danh mục công việc mẫu theo khối | M | B11 | 18 | CORE-08-11, CORE-02-04 | **Đã gộp main** | Gộp main 2026-10-22 |
| [DL-07](docs/tasks/DL-07-du-lieu-khoi-tao.md) | Dữ liệu khởi tạo: 9 khối đối tượng, lịch nghỉ lễ quốc gia, danh mục chức danh | S | B11 | 13 | DV-04-01, CORE-08-04 | **Đã gộp main** | Gộp main 2026-10-22 |
| [DL-08](docs/tasks/DL-08-sinh-du-lieu-thu-tai.md) | Bộ sinh dữ liệu thử quy mô (1,2 triệu hồ sơ một cell) cho kiểm thử hiệu năng | M | B9 | 13 | CORE-04-01, CORE-02-02, DV-04-01, CORE-03-01 | **Đã gộp main** | Gộp main 22/10 |
| [DL-12](docs/tasks/DL-12-job-doi-soat.md) | Job đối soát R1–R8 và bốn job kiểm tra sổ cái | L | B11 | 14 | CORE-04-02, DV-09-04, DV-15-02 | **Đã gộp main** | Đợt 10, main c7d74c17 |
| [DL-13](docs/tasks/DL-13-luu-giu-xoa-mat-ma.md) | Lưu giữ, lưu trữ lạnh, giữ pháp lý, xoá bằng mật mã theo bảy lớp lưu giữ | M | B11 | 13 | NEN-015, INF-18, CORE-08-07 | **Đã gộp main** | Gộp main 2026-10-22 |
| [DL-14](docs/tasks/DL-14-xoay-vong-dek-kek.md) | Xoay vòng DEK định kỳ và bọc lại khoá theo KEK mới | M | B11 | 7 | NEN-015, INF-13 | **Đã gộp main** | Gộp main 2026-10-22 |
| [DL-15](docs/tasks/DL-15-an-danh-hoa-phi-san-xuat.md) | Tác vụ ẩn danh hoá dữ liệu cho môi trường phi sản xuất (`anonymize`) | M | B11 | 14 | NEN-015, DL-08, NEN-005, NEN-006 | **Đã gộp main** | Gộp main 2026-10-22 |
| [DL-16](docs/tasks/DL-16-ho-so-twd.md) | Hồ sơ TWĐ (giả lập theo UEB): Policy Pack điểm công việc, bảng hệ số, chuỗi xếp loại, ca vàng UEB, danh mục dự thảo số 25, chín quy tắc điểm nghẽn, tham số thẩm quyền và hạn | L | B11 | 19 | CORE-05-12, CORE-02-27, CORE-02-28, CORE-01-14, CORE-04-13, DV-07-03, DV-07-05 | **Đã gộp main** | Đã gộp main (6afcdf05) |

### INF · Hạ tầng, CI/CD, bảo mật nền, quan sát, dự phòng (16/28 đã gộp `main`, 0 chờ gộp, 4 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [INF-01](docs/tasks/INF-01-chart-thu-vien-vt-service.md) | Chart thư viện `vt-service`: probe, PDB, phân bố vùng, HPA, NetworkPolicy, tài nguyên | L | B8 | 1 | – | **Đã gộp main** | Đã gộp `main` (chart thư viện `vt-service`, kịch bản SSH tunnel `scripts/tunnel.sh` và cấu hình `.env` động BE/FE tại `6b5b83f`, `2b58231`) |
| [INF-02](docs/tasks/INF-02-chart-dich-vu-vt-platform.md) | Chart từng service, chart tổng `vt-platform` và values cho ba mô hình phân phối | L | B8 | 2 | INF-01 | **Đã gộp main** | Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| [INF-03](docs/tasks/INF-03-values-dinh-co-cell.md) | Values định cỡ bốn hồ sơ cell, `capacity/assumptions.yaml`, script tính bảng, kiểm ngân sách kết nối trong CI | M | B8 | 3 | INF-02 | **Đã gộp main** | Hồ sơ sizing vt-platform, values CELL-NB/G4, deploy/capacity |
| [INF-04](docs/tasks/INF-04-gitops-argo-cd.md) | Kho GitOps: Argo CD ApplicationSet theo cell và môi trường, sync window, Argo Rollouts canary có phân tích SLI | L | B8 | 3 | INF-02 | **Đã gộp main** | Chart Argo CD, deploy/cells, digests theo môi trường, canary SLI (B8 kiểm trên kind) |
| [INF-05](docs/tasks/INF-05-pipeline-jenkins-java.md) | Thư viện pipeline Jenkins `vt-pipeline` cho Java: build, test, ArchUnit, Sonar, Semgrep, gitleaks, Trivy, openapi-diff, Pact, SBOM, Jib, cosign, provenance SLSA 3 | L | B8 | 3 | NEN-005 | **Đã gộp main** | Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| [INF-06](docs/tasks/INF-06-pipeline-ngon-ngu-noi-dung.md) | Pipeline cho Go, Python, Flutter, Helm và nội dung (Policy Pack, UI Pack) | M | B8 | 4 | INF-05 | **Đã gộp main** | Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| [INF-07](docs/tasks/INF-07-harbor-kyverno.md) | Harbor và chính sách Kyverno: chỉ ảnh có ký, không root, có giới hạn và probe, `vt-require-ha` | M | B8 | 4 | INF-05 | **Đã gộp main** | Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| [INF-08](docs/tasks/INF-08-cum-cell-phan-1.md) | Cụm Kubernetes của cell phần 1: node pool, taint, 13 namespace, NetworkPolicy mặc định từ chối, APISIX ingress | L | B8 | 1 | – | **Đã gộp main** | Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| [INF-09](docs/tasks/INF-09-cum-cell-phan-2.md) | Cụm Kubernetes của cell phần 2: Istio ambient mTLS STRICT, StorageClass Ceph RBD và NVMe cục bộ, egress gateway | L | B8 | 2 | INF-08 | **Đã gộp main** | Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| [INF-10](docs/tasks/INF-10-kafka-strimzi-debezium.md) | Kafka bằng Strimzi, Apicurio, Kafka Connect Debezium, khai báo topic dạng mã | M | B8 | 4 | INF-08, NEN-007, INF-11 | **Đã gộp main** | Kafka KRaft Strimzi, topic/ACL từ contracts/events, Debezium outbox, Apicurio |
| [INF-11](docs/tasks/INF-11-csdl-va-kho-du-lieu.md) | PostgreSQL CloudNativePG sáu cụm, PgBouncer, Valkey, OpenSearch, ClickHouse theo định cỡ | L | B8 | 2 | INF-08 | **Đã gộp main** | Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| [INF-12](docs/tasks/INF-12-ceph-object-lock.md) | Ceph RGW, bucket minh chứng Object Lock compliance, multisite | M | B8 | 3 | INF-09 | **Đã gộp main** | Ceph RGW của cell, multisite, kiểm trên kind |
| [INF-13](docs/tasks/INF-13-openbao-pki-hsm.md) | OpenBao năm node tự mở khoá bằng HSM, PKI nội bộ, cert-manager, External Secrets Operator | L | B8 | 2 | INF-08 | **Đã gộp main** | Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| [INF-14](docs/tasks/INF-14-quan-sat-thu-thap.md) | Quan sát phần 1: OTel Collector lấy mẫu theo đuôi, Tempo, Prometheus và Thanos, Loki, Fluent Bit có bộ đệm đĩa | L | B8 | 2 | INF-08 | **Đã gộp main** | Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| [INF-15](docs/tasks/INF-15-slo-canh-bao-dashboard.md) | Quan sát phần 2: SLI, dashboard Grafana, cảnh báo theo tốc độ tiêu ngân sách lỗi, 24 cảnh báo tối thiểu, ngưỡng nâng bậc NG-01..NG-14 | M | B8 | 3 | INF-14 | **Đã gộp main** | Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| [INF-16](docs/tasks/INF-16-co-gian-tu-dong.md) | Co giãn: Prometheus Adapter cho HPA, KEDA theo độ trễ Kafka, KEDA theo lịch hạn, Cluster Autoscaler có pod giữ chỗ | L | B8 | 5 | INF-14, INF-10, INF-01 | **Đã gộp main** | Gộp main; kiểm tĩnh xanh, kịch bản kind bỏ qua theo người dùng; kèm cấu hình clamav 200M và ingress Gotenberg |
| [INF-17](docs/tasks/INF-17-sao-luu-csdl.md) | Sao lưu phần 1: CNPG Barman Cloud, kho sao lưu bất biến, `restore-check` hằng ngày, Velero và etcd | L | B8 | 3 | INF-11 | **Chưa bắt đầu** |  |
| [INF-18](docs/tasks/INF-18-sao-luu-du-lieu-khac.md) | Sao lưu phần 2: MirrorMaker 2, Ceph multisite, snapshot OpenSearch, ClickHouse, Valkey, giữ outbox 72 giờ, lịch sao lưu | L | B8 | 8 | INF-17, INF-12, NEN-022 | **Chưa bắt đầu** |  |
| [INF-19](docs/tasks/INF-19-bao-mat-nen-tang.md) | Bảo mật nền: Falco, nhật ký kiểm toán API Kubernetes và luồng `sys.audit.v1` tới SIEM, Pod Security, chính sách egress | M | B8 | 3 | INF-09 | **Chưa bắt đầu** |  |
| [INF-20](docs/tasks/INF-20-vung-dieu-phoi-toan-cuc.md) | Vùng điều phối toàn cục: cụm quản trị, PostgreSQL toàn cục, GeoDNS PowerDNS, `dns-sync`, CDN tài nguyên tĩnh | L | B8 | 4 | INF-04 | **Chưa bắt đầu** |  |
| [INF-21](docs/tasks/INF-21-moi-truong-phi-san-xuat.md) | Môi trường phi sản xuất: staging, CELL-PERF, cụm sinh tải, môi trường tạm theo nhánh cho E2E | M | B8 | 4 | INF-04 | **Chưa bắt đầu** | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần tài nguyên hạ tầng |
| [INF-22](docs/tasks/INF-22-backend-tren-server-dev.md) | Triển khai backend lên server dev bằng Docker Compose (profile backend, cổng 35xxx) để kiểm thử tích hợp thật | M | B8 | 10 | CORE-00-01, NEN-020 | **Đang làm** | Giao sau bản phát hành 64952ef6 |
| [INF-23](docs/tasks/INF-23-goi-cai-cach-ly.md) | Gói cài cách ly LTS: đóng gói Helm ngoại tuyến, Harbor nội bộ, kiểm chữ ký | L | B8 | 5 | INF-02, INF-07 | **Chưa bắt đầu** |  |
| [INF-24](docs/tasks/INF-24-audit-archiver.md) | Dịch vụ `audit-archiver`: chuyển nhật ký kiểm toán sang kho lưu trữ bất biến theo thời hạn lưu giữ | M | B8 | 4 | INF-12, INF-19 | **Đang làm** | Giao sau bản phát hành 64952ef6 |
| [INF-25](docs/tasks/INF-25-bo-dieu-khien-suy-giam.md) | Bộ điều khiển suy giảm ba mức khi quá tải (tắt tính năng phụ, cắt tải theo ưu tiên) | M | B8 | 4 | DV-01-02, INF-15 | **Đang làm** | Giao B7 |
| [INF-26](docs/tasks/INF-26-cong-cu-vtctl.md) | Công cụ vận hành `vtctl` cho các thao tác trong runbook (tenant, cell, khoá kỳ, phát lại sự kiện) | L | B8 | 9 | INF-04, DV-03-03 | **Đang làm** | Giao sau bản phát hành 64952ef6 |
| [INF-27](docs/tasks/INF-27-quan-ly-truy-cap-dac-quyen.md) | Quản lý truy cập đặc quyền PAM cho quản trị hạ tầng và CSDL | M | B8 | 3 | INF-13 | **Chưa bắt đầu** | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần giải pháp PAM |
| [INF-28](docs/tasks/INF-28-cum-cho-am-ttdl-hai.md) | Cụm chờ ấm ở trung tâm dữ liệu thứ hai: CNPG replica cluster, Kafka MirrorMaker, Ceph multisite | L | B8 | 9 | INF-11, INF-18, INF-20, INF-04, INF-08, INF-09, INF-17 | **Chưa bắt đầu** | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần TTDL thứ hai |

### QA · Kiểm thử tích hợp, hiệu năng, an toàn thông tin, dự phòng (1/16 đã gộp `main`, 0 chờ gộp, 5 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [QA-01](docs/tasks/QA-01-chien-luoc-kiem-thu.md) | Chiến lược kiểm thử toàn hệ thống và danh mục khoảng 40 hành trình đầu cuối | S | B9 | 1 | – | **Đã gộp main** | Gộp 0fb85523 |
| [QA-02](docs/tasks/QA-02-pact-hop-dong.md) | Pact Broker và kiểm thử hợp đồng frontend với API, service với service | M | B9 | 4 | NEN-006, INF-05 | **Đang làm** | Giao B9; Pact bằng tệp, không dùng Broker (quyết định main) |
| [QA-03](docs/tasks/QA-03-e2e-he-thong-that.md) | Bộ kiểm thử đầu cuối với hệ thống thật trên server dev (backend thật, Keycloak, APISIX) | M | B9 | 11 | INF-22, QA-01 | **Làm một phần** | Nhánh claude/project-thread-n43ag0: khung he-thong, lớp hỗ trợ, HT-17 mẫu đã đẩy; bộ giả lập 932 ca xanh; lần chạy thật chờ JWT mission-core (B1), seed hồ sơ (B9), kafka-ui, Remote Control |
| [QA-04](docs/tasks/QA-04-hanh-trinh-okr.md) | Hành trình đầu cuối: đăng nhập, khởi tạo tenant, tổ chức, OKR, check-in | M | B9 | 14 | QA-03, CORE-01-08, DV-03-05 | **Làm một phần** | Nhánh claude/project-thread-n43ag0: HT-01..HT-09 ở qt00-03, giả lập 10 đạt 2 chờ (HT-04 VNeID, HT-17 chỉ he-thong), he-thong ghi chờ khi thiếu biến; lần chạy thật chờ INF-22 |
| [QA-05](docs/tasks/QA-05-hanh-trinh-giao-viec.md) | Hành trình đầu cuối: kế hoạch, giao việc, tiến độ, minh chứng, nghiệm thu | L | B9 | 20 | QA-03, CORE-03-07, CORE-02-22 | **Chưa bắt đầu** |  |
| [QA-06](docs/tasks/QA-06-hanh-trinh-danh-gia.md) | Hành trình đầu cuối: khoá kỳ, tự đánh giá, thẩm định, quyết định ký số, xếp loại năm | L | B9 | 21 | QA-05, DV-06-06, CORE-04-10 | **Làm một phần** | Nhánh claude/project-thread-n43ag0: HT-23..29 ở qt08-10, giả lập 7/7 đạt hai lần; he-thong 7 Chờ vì chưa đặt biến môi trường, chưa chạy trên dev |
| [QA-07](docs/tasks/QA-07-hanh-trinh-giam-sat.md) | Hành trình đầu cuối: kiểm điểm, hồ sơ số, khiếu nại, mở lại kỳ, cảnh báo, Policy Pack, bảng điều hành, AI, UI Pack | L | B9 | 23 | QA-06, CORE-06-08, CORE-07-11, DV-15-06 | **Chưa bắt đầu** |  |
| [QA-08](docs/tasks/QA-08-k6-perf-01-10.md) | Kịch bản k6 PERF-01..PERF-10 | L | B9 | 14 | QA-03, DL-08 | **Chưa bắt đầu** |  |
| [QA-09](docs/tasks/QA-09-gatling-luong-flowable.md) | Kịch bản góp ý G2: PERF-11..PERF-13 (Gatling cho Flowable) | M | B9 | 15 | QA-08, DV-06-04 | **Chưa bắt đầu** |  |
| [QA-10](docs/tasks/QA-10-chi-phi-bao-ve-g3.md) | Kịch bản góp ý G3: PERF-14..PERF-18, PERF-25 | M | B9 | 15 | QA-08, NEN-015 | **Chưa bắt đầu** |  |
| [QA-11](docs/tasks/QA-11-lo-frontend-sse-ai.md) | Kịch bản PERF-19..PERF-24, PERF-26 | M | B9 | 15 | QA-08 | **Chưa bắt đầu** |  |
| [QA-12](docs/tasks/QA-12-chien-dich-cell-perf.md) | Chiến dịch thử tải trên CELL-PERF và hiệu chuẩn bảng định cỡ theo hồ sơ cell | L | B9 | 16 | QA-09, QA-10, QA-11, INF-21 | **Chưa bắt đầu** | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần CELL-PERF |
| [QA-13](docs/tasks/QA-13-asvs-zap.md) | Kiểm thử ATTT: OWASP ASVS 4.0.3 mức 2 (L3 cho xác thực, phiên, mật mã) và OWASP ZAP toàn hệ thống | L | B9 | 12 | QA-03 | **Đang làm** | Giao B4 |
| [QA-14](docs/tasks/QA-14-thu-hong-dr-01-09.md) | Thử hỏng PERF-07 và diễn tập khôi phục DR-01..DR-09 | L | B9 | 15 | INF-17, QA-08 | **Chưa bắt đầu** |  |
| [QA-15](docs/tasks/QA-15-dr-chuyen-vung.md) | Diễn tập khôi phục DR-10..DR-16, chuyển vùng giữa hai trung tâm dữ liệu | L | B9 | 16 | QA-14, INF-18, INF-20 | **Chưa bắt đầu** | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần TTDL thứ hai |
| [QA-16](docs/tasks/QA-16-uat-tenant-thi-diem.md) | Kiểm thử chấp nhận nghiệp vụ: tenant thí điểm chạy trọn một kỳ đánh giá | L | B9 | 24 | QA-07 | **Chưa bắt đầu** | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần tenant và người dùng thật |

### MOB · SVC-18 mobile-app (Flutter) (0/10 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [MOB-01](docs/tasks/MOB-01-khung-flutter.md) | Khung Flutter: token thiết kế sang ThemeData, đăng nhập OIDC PKCE, client API sinh từ hợp đồng | M | B10 | 4 | NEN-006, DV-02-01 | **Chưa bắt đầu** |  |
| [MOB-02](docs/tasks/MOB-02-ngoai-tuyen-sqlcipher.md) | Lưu trữ ngoại tuyến SQLCipher, khoá trong Keystore/Keychain, hàng đợi đồng bộ có Idempotency-Key | M | B10 | 5 | MOB-01 | **Chưa bắt đầu** |  |
| [MOB-03](docs/tasks/MOB-03-viec-cua-toi-di-dong.md) | SCR-0504 Việc của tôi và cập nhật tiến độ trên di động | M | B10 | 18 | MOB-02, CORE-02-19, CORE-02-18 | **Chưa bắt đầu** |  |
| [MOB-04](docs/tasks/MOB-04-check-in-kr-di-dong.md) | SCR-0302 Check-in KR trên di động | M | B10 | 14 | MOB-02, CORE-01-08 | **Chưa bắt đầu** |  |
| [MOB-05](docs/tasks/MOB-05-nop-minh-chung-ocr.md) | SCR-0702 Nộp minh chứng trên di động có OCR, tải theo khối | M | B10 | 19 | MOB-02, DV-08-02, CORE-03-05 | **Chưa bắt đầu** |  |
| [MOB-06](docs/tasks/MOB-06-bieu-mau-dong-tu-danh-gia.md) | Bộ dựng biểu mẫu từ JSON Schema và CEL trên Flutter, phiếu tự đánh giá | L | B10 | 17 | MOB-02, CORE-08-13, CORE-04-04 | **Chưa bắt đầu** |  |
| [MOB-07](docs/tasks/MOB-07-ky-so-mysign.md) | Ký số MySign trên di động | M | B10 | 18 | MOB-06, DV-10-02 | **Chưa bắt đầu** |  |
| [MOB-08](docs/tasks/MOB-08-che-do-cao-tuoi.md) | SCR-1803 Chế độ Đảng viên cao tuổi trên di động | M | B10 | 19 | MOB-03, MOB-06, DL-06 | **Chưa bắt đầu** |  |
| [MOB-09](docs/tasks/MOB-09-thong-bao-day.md) | Thông báo đẩy FCM, APNs | S | B10 | 9 | MOB-01, DV-12-03, DV-12-05 | **Chưa bắt đầu** |  |
| [MOB-10](docs/tasks/MOB-10-pipeline-phat-hanh.md) | Pipeline build, ký và phát hành ứng dụng; kiểm thử đầu cuối và khả năng truy cập trên di động | M | B10 | 19 | MOB-03, INF-06 | **Chưa bắt đầu** | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần tài khoản cửa hàng ứng dụng |

### VH · Vận hành và đưa vào sử dụng (0/9 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [VH-01](docs/tasks/VH-01-runbook-ops-01-13.md) | Runbook OPS-01..OPS-13 | L | B11 | 4 | INF-04 | **Chưa bắt đầu** |  |
| [VH-02](docs/tasks/VH-02-runbook-ops-14-26.md) | Runbook OPS-14..OPS-26 | L | B11 | 4 | INF-17 | **Chưa bắt đầu** |  |
| [VH-03](docs/tasks/VH-03-truc-su-co-cab.md) | Trực vận hành, mức sự cố SEV-1..SEV-4, quy trình thay đổi, đóng băng thay đổi | M | B11 | 5 | VH-01 | **Chưa bắt đầu** |  |
| [VH-04](docs/tasks/VH-04-dien-tap-onboarding.md) | Diễn tập vòng đời tenant: đưa tenant mới vào vận hành trong một ngày (OPS-07) | M | B11 | 13 | DV-03-05, INF-04 | **Chưa bắt đầu** |  |
| [VH-05](docs/tasks/VH-05-huong-dan-su-dung.md) | Hướng dẫn sử dụng theo vai trò (AC-01..AC-10) | L | B11 | 24 | QA-07 | **Chưa bắt đầu** |  |
| [VH-06](docs/tasks/VH-06-tai-lieu-quan-tri.md) | Tài liệu quản trị, cài đặt cho ba mô hình phân phối | M | B11 | 6 | INF-23 | **Chưa bắt đầu** |  |
| [VH-07](docs/tasks/VH-07-ho-so-cap-do-4.md) | Hồ sơ đề xuất cấp độ 4 theo TCVN 11930:2017 | M | B11 | 13 | QA-13, INF-19 | **Chưa bắt đầu** |  |
| [VH-08](docs/tasks/VH-08-thi-diem-chuyen-tenant.md) | Kế hoạch đưa tenant thí điểm vào sử dụng và mở rộng theo nhóm tenant (hệ thống mới độc lập, không chuyển từ hệ cũ) | M | B11 | 25 | QA-16 | **Chưa bắt đầu** |  |
| [VH-09](docs/tasks/VH-09-cham-checklist-quy.md) | Chấm lại checklist 110 tiêu chí và bảng chỉ số chuyển đổi mỗi quý | S | B11 | 6 | VH-03 | **Chưa bắt đầu** |  |

### WEB · Phân hệ web: phần còn thiếu so với toàn hệ thống (7/7 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [WEB-070](docs/tasks/WEB-070-noi-api-that-okr-viec-minh-chung.md) | Nối các màn hình OKR, giao việc, minh chứng với API thật, tắt giả lập theo module bằng cấu hình | L | W | 20 | QA-03, CORE-01-08, CORE-02-19, CORE-03-07 | **Đã gộp main** | Đợt gộp 11; còn ngoại lệ: chạy với backend thật chờ QA-03 |
| [WEB-071](docs/tasks/WEB-071-noi-api-that-danh-gia-giam-sat.md) | Nối các màn hình đánh giá, hồ sơ số, giám sát với API thật | L | W | 23 | WEB-070, CORE-04-10, CORE-06-08, CORE-07-11 | **Đã gộp main** | Đợt gộp 11; còn ngoại lệ: chạy với backend thật chờ QA-03 |
| [WEB-072](docs/tasks/WEB-072-noi-api-that-quan-tri-cau-hinh.md) | Nối các màn hình quản trị, cấu hình, Policy Pack, bảng điều hành với API thật | L | W | 21 | WEB-070, CORE-05-10, CORE-08-11, DV-15-04 | **Đã gộp main** | Đợt gộp 11; còn ngoại lệ: chạy với backend thật chờ QA-03 |
| [WEB-073](docs/tasks/WEB-073-xu-ly-cat-tai-retry-after.md) | Xử lý 429 và 503 có `Retry-After`, thử lại có backoff, giữ bản nháp | S | W | 1 | WEB-007 | **Đã gộp main** | Đã gộp main (f4fca53); máy chủ thật chờ CORE tương ứng |
| [WEB-074](docs/tasks/WEB-074-traceparent-telemetry.md) | Gắn `traceparent` vào mọi lời gọi của `@vt/api-client` và liên kết với telemetry | S | W | 1 | WEB-011 | **Đã gộp main** | Đã gộp main (f4fca53); máy chủ thật chờ CORE tương ứng |
| [WEB-075](docs/tasks/WEB-075-kham-pha-cell.md) | Khám phá cell khi đăng nhập (chuyển hướng 307, mã 421) | S | W | 10 | WEB-006, DV-01-04 | **Đã gộp main** | Đã gộp main (f4fca53); máy chủ thật chờ CORE tương ứng |
| [WEB-076](docs/tasks/WEB-076-doc-co-tinh-nang.md) | Đọc cờ tính năng phía web | S | W | 13 | CORE-08-07, NEN-043 | **Đã gộp main** | Đã gộp main (f4fca53); máy chủ thật chờ CORE tương ứng |

### CNH · Cá nhân hoá theo người dùng (chờ người dùng chốt) (5/5 đã gộp `main`, 0 chờ gộp, 0 đang làm)

| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |
|---|---|:---:|:---:|:---:|---|---|---|
| [CNH-01](docs/tasks/CNH-01-de-xuat-thiet-ke-ca-nhan-hoa.md) | Đề xuất bổ sung thiết kế cá nhân hoá: mã màn hình, API, bảng `config.user_preference` | M | B2 | 1 | – | **Đã gộp main** | Đã gộp `main` (commit `b0fcedd` – đề xuất thiết kế cá nhân hoá trình bày và lược đồ JSON `config.user_preference`) |
| [CNH-02](docs/tasks/CNH-02-luu-tuy-chon-ca-nhan.md) | Backend lưu tuỳ chọn cá nhân: bố cục trang chủ, cột, bộ lọc đã lưu, chế độ người cao tuổi | M | B2 | 15 | CNH-01, CORE-08-01, CORE-08-11 | **Đã gộp main** | Gộp main 22/10 |
| [CNH-03](docs/tasks/CNH-03-bo-cuc-trang-chu-ca-nhan.md) | Web: lưu và khôi phục bố cục trang chủ và dashboard cá nhân | M | W | 16 | CNH-02, WEB-048 | **Đã gộp main** | Gộp main 2026-10-22 |
| [CNH-04](docs/tasks/CNH-04-cot-bo-loc-da-luu.md) | Web: cột và bộ lọc đã lưu trên các danh sách | M | W | 16 | CNH-02 | **Đã gộp main** | Gộp main 2026-10-22 |
| [CNH-05](docs/tasks/CNH-05-dong-bo-che-do-cao-tuoi.md) | Web và di động: đồng bộ chế độ người cao tuổi qua máy chủ thay cho lưu cục bộ | S | W | 16 | CNH-02 | **Đã gộp main** | Gộp main 2026-10-22 |

---

## 4. Nhật ký cập nhật tiến độ

Mọi lần cập nhật trạng thái bằng `python3 scripts/cap-nhat-tien-do.py set ...` hoặc khi điều phối gộp nhánh vào `main` đều được ghi vết tại đây (mới nhất ở dưới).

<!-- PROGRESS_LOG_START -->
| Thời gian | Luồng / Agent | Task | Nội dung cập nhật |
|---|---|---|---|
| 20/10/2026 17:55 | Điều phối (`main`) | Toàn bộ 401 task | Khởi tạo tệp theo dõi tiến độ tổng hợp duy nhất `TIEN-DO-HE-THONG.md` theo yêu cầu `YC-PT-17`; đồng bộ trạng thái 74 task đã gộp `main` (bao gồm `NEN-006`, `008`, `017`, `018`, `021`, `022`, `034`, `041`, `CNH-01`, `INF-01` mới nhất). |
| 20/10/2026 22:09 | Điều phối | `NEN-011`, `NEN-012`, `NEN-019` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (2006fc9, 6c2525d) |
| 20/10/2026 22:15 | Luồng 2 (B1) | `NEN-019` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh feat/NEN-019-NEN-020-template-testkit (commit 7d0fc19 – mẫu service-template Java lục giác, cổng 8081 và scripts/new-service.sh) |
| 20/10/2026 22:15 | Luồng 3 (B2) | `CORE-00-01` | Chuyển trạng thái sang **Đang làm** — Đang phát triển trên nhánh feat/CORE-00-mission-core (Luồng 3 – khởi tạo backend/mission-core 8 mô-đun & kiểm thử ArchUnit/Modulith) |
| 20/10/2026 22:15 | Luồng 3 (B2) | `CORE-00-02` | Chuyển trạng thái sang **Đang làm** — Đang phát triển trên nhánh feat/CORE-00-mission-core (Luồng 3 – cổng nội bộ api giữa 8 mô-đun và sự kiện nội bộ Modulith) |
| 20/10/2026 22:20 | Điều phối | `WEB-020` | Chuyển trạng thái sang **Đang làm** — Xây dựng theme Keycloak SCR-0001 và tái cấu trúc UX/UI nền tảng |
| 20/10/2026 22:44 | Điều phối + WEB | `WEB-020` | Chuyển trạng thái sang **Đã gộp main** — Theme Keycloak mission (SCR-0001) đã triển khai live trên 14.232.210.125:35080 |
| 20/10/2026 22:44 | Luồng 2 - B2 CEL & Thực thể nền | `NEN-019` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp feat/NEN-019-NEN-020-template-testkit vào main |
| 20/10/2026 22:44 | Luồng 2 - B2 CEL & Thực thể nền | `NEN-020` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp feat/NEN-019-NEN-020-template-testkit vào main |
| 20/10/2026 22:44 | Luồng 2 - B2 CEL & Thực thể nền | `DV-06-01` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-06-workflow-service: đang thực hiện |
| 20/10/2026 22:44 | Luồng 2 - B2 CEL & Thực thể nền | `DV-06-02` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-06-workflow-service: đang thực hiện |
| 20/10/2026 22:44 | Luồng 2 - B2 CEL & Thực thể nền | `DV-06-03` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-06-workflow-service: đang thực hiện |
| 20/10/2026 22:46 | Luồng 4 - B5 Định danh & Tổ chức | `DV-02-01` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp feat/B5-dinh-danh-to-chuc vào main |
| 20/10/2026 22:46 | Luồng 4 - B5 Định danh & Tổ chức | `DV-02-02` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp feat/B5-dinh-danh-to-chuc vào main |
| 20/10/2026 22:46 | Luồng 4 - B5 Định danh & Tổ chức | `DV-02-03` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp feat/B5-dinh-danh-to-chuc vào main |
| 20/10/2026 22:46 | Luồng 4 - B5 Định danh & Tổ chức | `DV-03-01` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp feat/B5-dinh-danh-to-chuc vào main |
| 20/10/2026 22:46 | Luồng 4 - B5 Định danh & Tổ chức | `DV-03-02` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp feat/B5-dinh-danh-to-chuc vào main |
| 20/10/2026 22:46 | Luồng 4 - B5 Định danh & Tổ chức | `DV-03-03` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp feat/B5-dinh-danh-to-chuc vào main |
| 20/10/2026 22:46 | Luồng 4 - B5 Định danh & Tổ chức | `DV-03-04` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp feat/B5-dinh-danh-to-chuc vào main |
| 20/10/2026 22:46 | Luồng 4 - B5 Định danh & Tổ chức | `DV-04-01` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp feat/B5-dinh-danh-to-chuc vào main |
| 20/10/2026 22:46 | Luồng 4 - B5 Định danh & Tổ chức | `DV-04-02` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp feat/B5-dinh-danh-to-chuc vào main (p95=38.7ms trên 11.111 đơn vị) |
| 20/10/2026 22:46 | Luồng 4 - B5 Định danh & Tổ chức | `DV-04-03` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp feat/B5-dinh-danh-to-chuc vào main (65/65 test PASS) |
| 20/10/2026 22:46 | Luồng 4 - B5 Định danh & Tổ chức | `DV-04-04` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-04-phan-cong-scim: đang thực hiện |
| 20/10/2026 22:46 | Luồng 4 - B5 Định danh & Tổ chức | `DV-04-05` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-04-phan-cong-scim: đang thực hiện |
| 20/10/2026 22:46 | Luồng 4 - B5 Định danh & Tổ chức | `DV-04-06` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-04-phan-cong-scim: đang thực hiện |
| 20/10/2026 22:49 | Luồng 5 - B1 Starter Platform | `NEN-013` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp feat/NEN-013-014-015-platform-starter vào main |
| 20/10/2026 22:49 | Luồng 5 - B1 Starter Platform | `NEN-014` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp feat/NEN-013-014-015-platform-starter vào main |
| 20/10/2026 22:49 | Luồng 5 - B1 Starter Platform | `NEN-015` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp feat/NEN-013-014-015-platform-starter vào main |
| 20/10/2026 22:49 | Luồng 5 - B1 Starter Platform | `NEN-016` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/NEN-016-017-018-starter-libs: đang thực hiện |
| 20/10/2026 22:49 | Luồng 5 - B1 Starter Platform | `NEN-017` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/NEN-016-017-018-starter-libs: đang thực hiện |
| 20/10/2026 22:49 | Luồng 5 - B1 Starter Platform | `NEN-018` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/NEN-016-017-018-starter-libs: đang thực hiện |
| 20/10/2026 22:50 | Luồng 5 - B1 Starter Platform | `NEN-016` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (vt-platform-starter: Resilience4j, OpenTelemetry, log ECS JSON che dữ liệu nhạy cảm) |
| 20/10/2026 22:50 | Luồng 5 - B1 Starter Platform | `NEN-017` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (commit fe40716 – thư viện Go dùng chung vtplatform) |
| 20/10/2026 22:50 | Luồng 5 - B1 Starter Platform | `NEN-018` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (commit 3b4e808 – thư viện Python dùng chung vtplatform) |
| 20/10/2026 22:50 | Luồng 5 - B6 Sổ cái & Ký số nền tảng | `DV-09-01` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-09-01-02-DV-10-01-ledger-esign: đang thực hiện |
| 20/10/2026 22:50 | Luồng 5 - B6 Sổ cái & Ký số nền tảng | `DV-09-02` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-09-01-02-DV-10-01-ledger-esign: đang thực hiện |
| 20/10/2026 22:50 | Luồng 5 - B6 Sổ cái & Ký số nền tảng | `DV-10-01` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-09-01-02-DV-10-01-ledger-esign: đang thực hiện |
| 21/10/2026 10:24 | Luồng 1 | `NEN-042` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main: hợp đồng OpenAPI 3.1 các phân hệ nghiệp vụ |
| 21/10/2026 10:24 | Luồng 1 | `NEN-043` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main: bổ sung các thao tác OpenAPI 3.1 cho cấu hình và dịch vụ nền |
| 21/10/2026 10:24 | Luồng 1 | `NEN-036` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main: định tuyến CSDL theo khách thuê (POOL/BRIDGE/SILO) và bộ lọc cell (ADR-07) |
| 21/10/2026 10:25 | Luồng 1 | `DV-03-06` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-03-05-06-07-tenant-registry: đang thực hiện |
| 21/10/2026 10:25 | Luồng 1 | `DV-03-05` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-03-05-06-07-tenant-registry: đang thực hiện |
| 21/10/2026 10:25 | Luồng 1 | `DV-03-07` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-03-05-06-07-tenant-registry: đang thực hiện |
| 21/10/2026 12:49 | Luồng 1 (Tenant Registry) | `DV-03-06` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (2975c79): khai báo IdP OIDC/SAML/LDAP, kiểm kết nối chống SSRF, NetworkPolicy |
| 21/10/2026 12:49 | Luồng 1 (Tenant Registry) | `DV-03-05` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-03-05-06-07-tenant-registry: tạm dừng chờ phiên tiếp theo (sau DV-03-06) |
| 21/10/2026 12:49 | Luồng 1 (Tenant Registry) | `DV-03-07` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-03-05-06-07-tenant-registry: tạm dừng chờ phiên tiếp theo (sau DV-03-05) |
| 21/10/2026 12:49 | Luồng 2 (Workflow) | `DV-06-01` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (9e7c761): khung workflow-service Flowable 7.2, tinh chỉnh PostgreSQL, Helm API/Executor + KEDA |
| 21/10/2026 12:49 | Luồng 2 (Workflow) | `DV-06-02` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-06-workflow-service: tạm dừng chờ phiên tiếp theo (sau DV-06-01) |
| 21/10/2026 12:49 | Luồng 2 (Workflow) | `DV-06-03` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-06-workflow-service: tạm dừng chờ phiên tiếp theo (sau DV-06-02) |
| 21/10/2026 12:49 | Luồng 3 (Mission Core) | `CORE-00-01` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (1581328): khung SVC-05 mission-core, 8 DataSource mô-đun và định tuyến đọc/ghi |
| 21/10/2026 12:49 | Luồng 3 (Mission Core) | `CORE-00-02` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (03e9c83): ranh giới 8 mô-đun Spring Modulith, hợp đồng API nội bộ, JobsController và kiểm thử kiến trúc |
| 21/10/2026 12:49 | Luồng 3 (Mission Core) | `CORE-00-03` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/CORE-00-mission-core: tạm dừng chờ phiên tiếp theo (sau CORE-00-02) |
| 21/10/2026 12:49 | Luồng 3 (Mission Core) | `CORE-00-04` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/CORE-00-mission-core: tạm dừng chờ phiên tiếp theo |
| 21/10/2026 12:49 | Luồng 3 (Mission Core) | `CORE-00-05` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/CORE-00-mission-core: tạm dừng chờ phiên tiếp theo |
| 21/10/2026 12:49 | Luồng 4 (Org & SCIM) | `DV-04-04` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (b55b7d1): quản lý phân công 2 trục thời gian, đường báo cáo ReportingLineVersion và tra cứu cán bộ |
| 21/10/2026 12:49 | Luồng 4 (Org & SCIM) | `DV-04-05` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-04-phan-cong-scim: tạm dừng chờ phiên tiếp theo (sau DV-04-04) |
| 21/10/2026 12:49 | Luồng 4 (Org & SCIM) | `DV-04-06` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-04-phan-cong-scim: tạm dừng chờ phiên tiếp theo (sau DV-04-05) |
| 21/10/2026 12:49 | Luồng 5 (Ledger & ESign) | `DV-09-01` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (40e7b32): khung SVC-09 ledger-service Go, schema sổ cái bất biến và phân vùng 2 tầng |
| 21/10/2026 12:49 | Luồng 5 (Ledger & ESign) | `DV-09-02` | Chuyển trạng thái sang **Đang làm** — Đã commit & gộp main WIP (74f2130, skip QA): đã xong canon.go RFC 8785 JCS + 6 golden vectors + ingest.go 64 phân vùng |
| 21/10/2026 12:49 | Luồng 5 (Ledger & ESign) | `DV-10-01` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/DV-09-01-02-DV-10-01-ledger-esign: tạm dừng chờ phiên tiếp theo (sau DV-09-02) |
| 21/10/2026 12:49 | Luồng 3 (Mission Core) | `CORE-00-04` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (03e9c83): ranh giới 8 mô-đun Spring Modulith, hợp đồng API nội bộ, JobsController và kiểm thử kiến trúc |
| 21/10/2026 12:49 | Luồng 3 (Mission Core) | `CORE-00-02` | Chuyển trạng thái sang **Đang làm** — Nhánh feat/CORE-00-mission-core: tạm dừng chờ phiên tiếp theo |
| 21/10/2026 20:48 | B1 (Claude) | `CORE-00-02` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b1-nen-mong-backend-8pacfm: sửa cancelJob đảo tham số, EPR dùng chung TM JPA, trạng thái kỳ theo hợp đồng; mission-core verify xanh |
| 21/10/2026 21:31 | B1 (Claude) | `CORE-00-03` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b1-nen-mong-backend-8pacfm: schema org_replica, consumer EVT-14/15/16 + bootstrap, bao đóng 2 cây, OrgDirectoryApi; IT xanh, 10k sự kiện p99 ~0,3 s |
| 21/10/2026 21:36 | B1 (Claude) | `CORE-00-05` | Chuyển trạng thái sang **Đang làm** — Nhánh claude/b1-nen-mong-backend-8pacfm: điểm móc FieldEncryptor, gom sự kiện sys.audit.v1 |
| 21/10/2026 22:17 | B1 (Claude) | `CORE-00-05` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b1-nen-mong-backend-8pacfm: DecryptListener + C2AccessAuditor gom sys.audit.v1 qua outbox, @C2Access; IT xanh, chi phí thêm 0,08 ms p95; V0_24 nới ck_outbox_event__topic |
| 21/10/2026 22:20 | B11 | `DL-04` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b11-noi-dung-26rl4b: kiểm thử nạp TaskLifecycleLoadTest 10/10 trên vt-cel; kiểm thử chạy thử với Testcontainers chờ CORE-02-13 |
| 21/10/2026 22:20 | B11 | `DL-07` | Chuyển trạng thái sang **Làm một phần** — Lịch nghỉ 2026–2027 có nguồn trên main; migration lặp chờ tên 9 khối (PL 2A), danh mục chức danh (PL 2B) và bảng config.holiday_calendar của mission-core (CORE-08-01) |
| 21/10/2026 22:51 | Điều phối | `WEB-040` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f4fca53) |
| 21/10/2026 22:51 | Điều phối | `WEB-041` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f4fca53) |
| 21/10/2026 22:51 | Điều phối | `WEB-049` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f4fca53) |
| 21/10/2026 22:51 | Điều phối | `WEB-050` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f4fca53) |
| 21/10/2026 22:51 | Điều phối | `WEB-062` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f4fca53) |
| 21/10/2026 22:51 | Điều phối | `WEB-073` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f4fca53); máy chủ thật chờ CORE tương ứng |
| 21/10/2026 22:51 | Điều phối | `WEB-074` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f4fca53); máy chủ thật chờ CORE tương ứng |
| 21/10/2026 22:51 | Điều phối | `WEB-075` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f4fca53); máy chủ thật chờ CORE tương ứng |
| 21/10/2026 22:51 | Điều phối | `WEB-076` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f4fca53); máy chủ thật chờ CORE tương ứng |
| 21/10/2026 22:51 | Điều phối | `WEB-061` | Chuyển trạng thái sang **Làm một phần** — Phần 1 đã gộp main (f4fca53); trình soạn bpmn-js chờ duyệt phụ thuộc |
| 21/10/2026 22:51 | Điều phối | `CORE-00-02` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (e9620db) |
| 21/10/2026 22:51 | Điều phối | `CORE-00-03` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (e9620db) |
| 21/10/2026 22:51 | Điều phối | `CORE-00-05` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (e9620db) |
| 21/10/2026 22:51 | Điều phối | `DL-04` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (e9620db) |
| 21/10/2026 22:55 | Điều phối | `DV-04-08` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (nhánh B1 7dca3d2) |
| 21/10/2026 23:30 | B1 (Claude) | `DV-04-07` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b1-nen-mong-backend-8pacfm: đối soát nhân sự, 9 API + cổng nội bộ; verify org-service xanh 99 kiểm thử |
| 21/10/2026 23:36 | Điều phối | `DV-04-07` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (8b84e3a) |
| 21/10/2026 23:37 | B1 (Claude) | `DV-04-05` | Chuyển trạng thái sang **Đang làm** — Nhánh claude/b1-nen-mong-backend-8pacfm: đang thực hiện |
| 22/10/2026 00:02 | B1 (Claude) | `DV-04-05` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b1-nen-mong-backend-8pacfm: nhập cây Excel 202 + /jobs/{id}, 1.000 đơn vị ~9 s; verify org-service xanh 105 kiểm thử |
| 22/10/2026 00:07 | Điều phối | `DV-04-05` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (a36f8d0) |
| 22/10/2026 00:08 | B1 (Claude) | `DV-04-06` | Chuyển trạng thái sang **Đang làm** — Nhánh claude/b1-nen-mong-backend-8pacfm: đang thực hiện |
| 22/10/2026 00:37 | B1 (Claude) | `DV-04-06` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b1-nen-mong-backend-8pacfm: SCIM 2.0 trên org-service, verify org-service xanh (110 IT), lint scim.yaml đạt |
| 22/10/2026 00:44 | Điều phối | `DV-04-06` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (fcff925) |
| 22/10/2026 00:47 | B11 | `DL-07` | Chuyển trạng thái sang **Đang làm** — Nhánh claude/b11-noi-dung-26rl4b: người dùng chấp nhận khối, chức danh giả lập (22/10); đang viết migration lặp org |
| 22/10/2026 00:59 | B11 | `DL-07` | Chuyển trạng thái sang **Làm một phần** — Nhánh claude/b11-noi-dung-26rl4b: khối, chức danh giả lập + R__ org (NationalCatalogSeedIT xanh); lịch nghỉ chờ CORE-08-01; tenant thường chưa đọc được danh mục hệ thống (wiki 14) |
| 22/10/2026 01:59 | Điều phối | `DV-02-05` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (nhánh B1 37a1606) |
| 22/10/2026 01:59 | Điều phối | `DL-07` | Chuyển trạng thái sang **Làm một phần** — Phần org (9 khối, 23 chức danh) đã gộp main |
| 22/10/2026 01:59 | Điều phối | `WEB-061` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (25c670a) |
| 22/10/2026 01:59 | Điều phối | `WEB-063` | Chuyển trạng thái sang **Đã gộp main** — Hợp đồng và màn hình đã gộp main (4f1e4b0) |
| 22/10/2026 02:07 | Điều phối | `DV-02-06` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (nhánh B1 6389317) |
| 22/10/2026 02:07 | Điều phối | `DV-02-07` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (nhánh B1 6389317) |
| 22/10/2026 02:12 | Điều phối | `DV-06-05` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (nhánh B6 fad5653) |
| 22/10/2026 02:25 | Điều phối | `DV-02-02` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (nhánh B1 d598b9e) |
| 22/10/2026 02:38 | Điều phối | `DV-10-01` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (nhánh B6 bd3538e) |
| 22/10/2026 03:03 | Điều phối | `DV-02-03` | Chuyển trạng thái sang **Đã gộp main** — Luồng đăng nhập theo bậc xác thực, MFA theo vai trò, thiết bị, rủi ro |
| 22/10/2026 03:16 | Điều phối | `DV-11-01` | Chuyển trạng thái sang **Đã gộp main** — Dịch vụ sinh tài liệu: docx4j, Gotenberg PDF/A-2b, veraPDF, S3 |
| 22/10/2026 03:30 | B1 (Claude) | `DV-02-08` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b1-nen-mong-backend-8pacfm: SPI mission-iam cấp/thu hồi mã, authenticator mission-recovery-code; verify iam xanh, helm lint xanh |
| 22/10/2026 03:36 | Điều phối | `DV-02-08` | Chuyển trạng thái sang **Đã gộp main** — Mã khôi phục MFA: cấp, dùng một lần, thu hồi (SPI mission-iam) |
| 22/10/2026 03:47 | Điều phối | `CORE-08-01` | Chuyển trạng thái sang **Đã gộp main** — Migration schema kèm IT RLS và ràng buộc |
| 22/10/2026 03:47 | Điều phối | `CORE-05-01` | Chuyển trạng thái sang **Đã gộp main** — Migration schema kèm IT RLS và ràng buộc |
| 22/10/2026 03:51 | Điều phối | `DV-03-05` | Chuyển trạng thái sang **Đã gộp main** — Saga cấp phát phần 2 và kích hoạt tenant |
| 22/10/2026 03:51 | Điều phối | `DV-03-07` | Chuyển trạng thái sang **Đã gộp main** — Chuyển tenant giữa cell; bảng registry.tenant_move chờ duyệt (wiki 14 mục 34) |
| 22/10/2026 03:51 | Điều phối | `DV-03-04` | Chuyển trạng thái sang **Làm một phần** — Mở lại: bước CSDL và KEK của saga chưa có mã (B1 phát hiện khi gộp DV-03-05) |
| 22/10/2026 03:56 | Điều phối | `CORE-01-01` | Chuyển trạng thái sang **Đã gộp main** — Migration schema okr V1–V2, IT RLS và phân vùng |
| 22/10/2026 04:03 | Điều phối | `DV-03-04` | Chuyển trạng thái sang **Đã gộp main** — Saga cấp phát phần 1: bước CSDL và bước khoá mã hoá có bù trừ |
| 22/10/2026 04:15 | B1 (Claude) | `CORE-02-01` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b1-nen-mong-backend-8pacfm: migration task V1 (danh mục, chương trình, kế hoạch), lint, RLS pool và ràng buộc xanh; mvn verify vt-db-migration + mission-core xanh |
| 22/10/2026 04:21 | Điều phối | `CORE-02-01` | Chuyển trạng thái sang **Đã gộp main** — Migration schema task phần danh mục, chương trình, kế hoạch |
| 22/10/2026 04:29 | B2 (luồng B6) | `CORE-08-02` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b6-flowable-so-cai-ky-so-t54jlp: kỳ đánh giá, API tạo/xem/liệt kê kỳ, PeriodApi đọc CSDL, tác vụ mở kỳ; mission-core verify xanh |
| 22/10/2026 04:38 | Điều phối | `CORE-08-02` | Chuyển trạng thái sang **Đã gộp main** — Kỳ đánh giá: API tạo/xem/liệt kê, PeriodApi đọc CSDL, EVT-09/26 |
| 22/10/2026 04:59 | Điều phối | `CORE-08-03` | Chuyển trạng thái sang **Đã gộp main** — Chặn ghi khi kỳ khoá, đệm trạng thái kỳ, mã 409 theo mô-đun |
| 22/10/2026 05:08 | Luồng B8 hạ tầng | `INF-08` | Chuyển trạng thái sang **Xong, chờ gộp** |
| 22/10/2026 05:08 | Luồng B8 hạ tầng | `INF-14` | Chuyển trạng thái sang **Xong, chờ gộp** |
| 22/10/2026 05:08 | Luồng B8 hạ tầng | `INF-15` | Chuyển trạng thái sang **Xong, chờ gộp** |
| 22/10/2026 05:08 | Luồng B8 hạ tầng | `INF-02` | Chuyển trạng thái sang **Xong, chờ gộp** |
| 22/10/2026 05:08 | Luồng B8 hạ tầng | `INF-07` | Chuyển trạng thái sang **Xong, chờ gộp** |
| 22/10/2026 05:08 | Luồng B8 hạ tầng | `INF-06` | Chuyển trạng thái sang **Xong, chờ gộp** |
| 22/10/2026 05:08 | Luồng B8 hạ tầng | `INF-09` | Chuyển trạng thái sang **Xong, chờ gộp** |
| 22/10/2026 05:08 | Luồng B8 hạ tầng | `INF-11` | Chuyển trạng thái sang **Xong, chờ gộp** |
| 22/10/2026 05:08 | Luồng B8 hạ tầng | `INF-13` | Chuyển trạng thái sang **Xong, chờ gộp** |
| 22/10/2026 05:18 | Điều phối | `INF-02` | Chuyển trạng thái sang **Đã gộp main** — Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| 22/10/2026 05:18 | Điều phối | `INF-05` | Chuyển trạng thái sang **Đã gộp main** — Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| 22/10/2026 05:18 | Điều phối | `INF-06` | Chuyển trạng thái sang **Đã gộp main** — Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| 22/10/2026 05:18 | Điều phối | `INF-07` | Chuyển trạng thái sang **Đã gộp main** — Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| 22/10/2026 05:18 | Điều phối | `INF-08` | Chuyển trạng thái sang **Đã gộp main** — Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| 22/10/2026 05:18 | Điều phối | `INF-09` | Chuyển trạng thái sang **Đã gộp main** — Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| 22/10/2026 05:18 | Điều phối | `INF-11` | Chuyển trạng thái sang **Đã gộp main** — Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| 22/10/2026 05:18 | Điều phối | `INF-13` | Chuyển trạng thái sang **Đã gộp main** — Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| 22/10/2026 05:18 | Điều phối | `INF-14` | Chuyển trạng thái sang **Đã gộp main** — Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| 22/10/2026 05:18 | Điều phối | `INF-15` | Chuyển trạng thái sang **Đã gộp main** — Gộp nhánh claude/b8-ha-tang-g7g48k đến dab319d (B8 đã kiểm trên kind) |
| 22/10/2026 05:22 | Điều phối | `CORE-08-04` | Chuyển trạng thái sang **Đã gộp main** — Lịch ngày nghỉ, làm bù, WorkingCalendarApi |
| 22/10/2026 05:40 | Điều phối | `CORE-02-02` | Chuyển trạng thái sang **Đã gộp main** — Migration schema task phần nhiệm vụ, 7 bảng, EXPLAIN 1 triệu dòng |
| 22/10/2026 05:52 | Điều phối | `CORE-08-05` | Chuyển trạng thái sang **Đã gộp main** — Lệnh khoá kỳ, migration config V4, client SVC-06 |
| 22/10/2026 06:17 | Điều phối | `CORE-08-05` | Chuyển trạng thái sang **Đã gộp main** — Lệnh khoá kỳ, saga chốt kỳ gửi X-Tenant-Id |
| 22/10/2026 06:24 | Điều phối | `CORE-08-06` | Chuyển trạng thái sang **Đã gộp main** — Mở lại kỳ để hậu kiểm theo phạm vi, migration config V5 |
| 22/10/2026 06:33 | Điều phối | `CORE-02-03` | Chuyển trạng thái sang **Đã gộp main** — Danh mục công việc: 8 thao tác /catalog |
| 22/10/2026 06:57 | B2 (luồng B6) | `CORE-08-07` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b6-flowable-so-cai-ky-so-t54jlp: cờ tính năng, TenantSettingApi, ngưỡng bất thường; mission-core verify xanh |
| 22/10/2026 07:07 | Điều phối | `CORE-08-07` | Chuyển trạng thái sang **Đã gộp main** — Cờ tính năng, TenantSettingApi, ngưỡng bất thường |
| 22/10/2026 07:19 | Điều phối | `INF-04` | Chuyển trạng thái sang **Đã gộp main** — Chart Argo CD, deploy/cells, digests theo môi trường, canary SLI (B8 kiểm trên kind) |
| 22/10/2026 07:21 | Luồng B8 hạ tầng | `INF-03` | Chuyển trạng thái sang **Đang làm** — Nhánh claude/b8-ha-tang-g7g48k: đang thực hiện |
| 22/10/2026 07:30 | Luồng B8 hạ tầng | `INF-03` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b8-ha-tang-g7g48k: 4 tệp hồ sơ sizing, values CELL-NB/G4, deploy/capacity (GD-01..26, tính định cỡ, kiểm ngân sách kết nối); CI 10/10, pytest 18/18 |
| 22/10/2026 07:37 | Điều phối | `INF-03` | Chuyển trạng thái sang **Đã gộp main** — Hồ sơ sizing vt-platform, values CELL-NB/G4, deploy/capacity |
| 22/10/2026 07:48 | B2 (luồng B6) | `CORE-08-08` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b6-flowable-so-cai-ky-so-t54jlp: UI Pack phiên bản, lược đồ 1.0, gửi duyệt/từ chối; mission-core verify xanh |
| 22/10/2026 07:55 | Điều phối | `CORE-08-08` | Chuyển trạng thái sang **Đã gộp main** — UI Pack có phiên bản, lược đồ 1.0, gửi duyệt/từ chối |
| 22/10/2026 08:17 | B2 (luồng B6) | `CORE-08-09` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b6-flowable-so-cai-ky-so-t54jlp: registry SDUI có phiên bản, lệnh nạp manifest, listUiComponents, LayoutValidator; mission-core verify xanh |
| 22/10/2026 08:24 | Điều phối | `CORE-08-09` | Chuyển trạng thái sang **Đã gộp main** — Component registry SDUI có phiên bản, LayoutValidator, migration config V6 |
| 22/10/2026 08:42 | Điều phối | `CORE-02-04` | Chuyển trạng thái sang **Đã gộp main** — Nhập danh mục từ Excel bằng FastExcel (người dùng đã duyệt) |
| 22/10/2026 08:51 | Điều phối | `CORE-08-10` | Chuyển trạng thái sang **Đã gộp main** — Kiểm tra tĩnh UI Pack: tương phản, dung lượng, SVG an toàn |
| 22/10/2026 09:01 | Luồng B8 hạ tầng | `INF-10` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b8-ha-tang-g7g48k: chart Kafka/Debezium/Apicurio sinh từ hợp đồng; tĩnh 7/7, vt-platform 32/32, kind 18/18 |
| 22/10/2026 09:06 | Điều phối | `INF-10` | Chuyển trạng thái sang **Đã gộp main** — Kafka KRaft Strimzi, topic/ACL từ contracts/events, Debezium outbox, Apicurio |
| 22/10/2026 09:18 | Điều phối | `CORE-03-01` | Chuyển trạng thái sang **Đã gộp main** — Migration schema product: deliverable, evidence, acceptance, e2ee_key_wrap |
| 22/10/2026 09:28 | Điều phối | `CORE-08-11` | Chuyển trạng thái sang **Đã gộp main** — Phát hành UI Pack: ký ES256, S3, EVT-23, ETag/304 |
| 22/10/2026 09:43 | B1 (Claude) | `DV-08-01` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b1-nen-mong-backend-8pacfm: evidence-service khung, schema evidence, S3 Object Lock; verify mô-đun xanh |
| 22/10/2026 09:50 | Điều phối | `DV-08-01` | Chuyển trạng thái sang **Đã gộp main** — Khung evidence-service SVC-08, RLS, Object Lock |
| 22/10/2026 10:06 | Điều phối | `INF-12` | Chuyển trạng thái sang **Đã gộp main** — Ceph RGW của cell, multisite, kiểm trên kind |
| 22/10/2026 10:12 | Điều phối | `CORE-08-12` | Chuyển trạng thái sang **Đã gộp main** — Quay lui UI Pack, UiPackCompatibilityApi |
| 22/10/2026 10:19 | B2 (luồng B6) | `CORE-08-13` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b6-flowable-so-cai-ky-so-t54jlp: API biểu mẫu động, migration V7 (ngoại lệ phạm vi), đã qua verify mission-core |
| 22/10/2026 10:28 | Điều phối | `CORE-08-13` | Chuyển trạng thái sang **Đã gộp main** — API biểu mẫu động form_schema, migration config V7 |
| 22/10/2026 10:43 | B2 (luồng B6) | `CORE-08-14` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b6-flowable-so-cai-ky-so-t54jlp: FormValidationApi, runUiFormTests, bộ ca data/golden/form-tests; đã qua verify mission-core |
| 22/10/2026 10:50 | Điều phối | `CORE-08-14` | Chuyển trạng thái sang **Đã gộp main** — FormValidationApi kiểm phiếu tại máy chủ, runUiFormTests hai engine |
| 22/10/2026 11:02 | Điều phối | `DV-08-02` | Chuyển trạng thái sang **Đã gộp main** — Tải tệp minh chứng nhiều phần, khớp hợp đồng products.yaml |
| 22/10/2026 11:13 | Điều phối | `CORE-08-15` | Chuyển trạng thái sang **Đã gộp main** — Plugin chuyên ngành theo manifest tenant, cosign + SRI, migration config V8 |
| 22/10/2026 11:23 | Điều phối | `DV-08-03` | Chuyển trạng thái sang **Đã gộp main** — Khử trùng minh chứng theo băm; EVT-20 do SVC-15 phát theo hợp đồng |
| 22/10/2026 11:43 | B2 (luồng B6) | `CORE-05-02` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b6-flowable-so-cai-ky-so-t54jlp: đã qua build & test (mission-core verify xanh) |
| 22/10/2026 11:51 | Điều phối | `CORE-05-02` | Chuyển trạng thái sang **Đã gộp main** — Gộp main; domain Policy Pack MOD-05, 5 thao tác API |
| 22/10/2026 12:04 | Điều phối | `DV-08-04` | Chuyển trạng thái sang **Đã gộp main** — Gộp main; quét mã độc nền, KEDA worker, V3 migration |
| 22/10/2026 12:11 | B2 (luồng B6) | `CORE-05-03` | Chuyển trạng thái sang **Đang làm** — Nhánh claude/b6-flowable-so-cai-ky-so-t54jlp: phân giải Policy Pack theo kỳ |
| 22/10/2026 12:27 | B2 (luồng B6) | `CORE-05-03` | Chuyển trạng thái sang **Đang làm** — Nhánh claude/b6-flowable-so-cai-ky-so-t54jlp: kiểm thử phạm vi xanh, đang chạy verify đầy đủ |
| 22/10/2026 12:39 | B2 (luồng B6) | `CORE-05-03` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b6-flowable-so-cai-ky-so-t54jlp: đã qua build & test (verify mission-core xanh) |
| 22/10/2026 12:44 | Điều phối | `CORE-05-03` | Chuyển trạng thái sang **Đã gộp main** — Gộp main; PolicyQueryApi, GET /policies/resolve, bộ đệm |
| 22/10/2026 12:57 | Điều phối | `CORE-05-04` | Chuyển trạng thái sang **Đã gộp main** — Gộp main; API quy tắc CEL và tham số, kiểm kiểu |
| 22/10/2026 13:31 | Điều phối | `CORE-01-02` | Chuyển trạng thái sang **Đã gộp main** — Gộp main (cherry-pick 2e2e1cb, chưa kèm DV-08-06); OkrApi, 9 API đọc, ProgressCalculator |
| 22/10/2026 13:40 | Điều phối | `CORE-05-05` | Chuyển trạng thái sang **Đã gộp main** — Gộp main; bảng quyết định DMN 1.4, kiểm FEEL, xuất XML |
| 22/10/2026 14:15 | Điều phối | `CORE-02-13` | Chuyển trạng thái sang **Đã gộp main** — Gộp main (cherry-pick 0c363cd); máy trạng thái nhiệm vụ, task_state_log, EVT-06 |
| 22/10/2026 14:28 | Điều phối | `CORE-05-06` | Chuyển trạng thái sang **Đã gộp main** — Gộp main; ca kiểm thử vàng, ký ES256 |
| 22/10/2026 14:28 | Điều phối | `CORE-02-13` | Chuyển trạng thái sang **Đã gộp main** — Gộp main (cherry-pick 0c363cd); máy trạng thái nhiệm vụ, task_state_log, EVT-06 |
| 22/10/2026 14:39 | Điều phối | `INF-16` | Chuyển trạng thái sang **Đã gộp main** — Gộp main; kiểm tĩnh xanh, kịch bản kind bỏ qua theo người dùng; kèm cấu hình clamav 200M và ingress Gotenberg |
| 22/10/2026 14:43 | Điều phối | `CORE-02-05` | Chuyển trạng thái sang **Đã gộp main** — Gộp main (cherry-pick 7631de8); kế hoạch quý, 8 thao tác PlanApi |
| 22/10/2026 14:53 | Điều phối | `DV-08-06` | Chuyển trạng thái sang **Đã gộp main** — Gộp main; xem trước minh chứng theo trang, PDFBox đã duyệt (YC-PT-18) |
| 22/10/2026 15:04 | Điều phối | `CORE-02-06` | Chuyển trạng thái sang **Đã gộp main** — Gộp main; kiểm tra kế hoạch BR-04-02..07, tỷ trọng nhóm |
| 22/10/2026 15:16 | Điều phối | `CORE-02-12` | Chuyển trạng thái sang **Đã gộp main** — Gộp main; TaskApi công bố, sự kiện nhiệm vụ |
| 22/10/2026 15:16 | Điều phối | `CORE-03-02` | Chuyển trạng thái sang **Đã gộp main** — Gộp main; máy trạng thái phiên bản minh chứng, liên động nhiệm vụ |
| 22/10/2026 15:36 | Điều phối | `DV-12-01` | Chuyển trạng thái sang **Đã gộp main** — Gộp main; notification-service Go |
| 22/10/2026 15:36 | Điều phối | `DV-12-02` | Chuyển trạng thái sang **Đã gộp main** — Gộp main; notification-service Go |
| 22/10/2026 15:36 | Điều phối | `DV-12-05` | Chuyển trạng thái sang **Đã gộp main** — Gộp main; notification-service Go |
| 22/10/2026 16:03 | Điều phối | `CNH-02` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:03 | Điều phối | `CORE-03-03` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:03 | Điều phối | `CORE-01-03` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:03 | Điều phối | `CORE-01-04` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:03 | Điều phối | `CORE-01-08` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:03 | Điều phối | `CORE-01-09` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:03 | Điều phối | `DV-14-01` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:20 | Điều phối | `CORE-02-07` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:20 | Điều phối | `NEN-022` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:20 | Điều phối | `CORE-05-07` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:20 | Điều phối | `NEN-035` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:20 | Điều phối | `DL-08` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:20 | Điều phối | `DV-14-03` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:20 | Điều phối | `CORE-02-08` | Chuyển trạng thái sang **Làm một phần** — Gộp main phần gửi duyệt/trả lại; còn cầu nối duyet-ke-hoach chờ DV-06-03, CORE-01-05 |
| 22/10/2026 16:39 | Điều phối | `DV-06-02` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:39 | Điều phối | `DV-14-02` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:39 | Điều phối | `DV-14-03` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:46 | Điều phối | `DV-06-03` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 16:46 | Điều phối | `DV-10-02` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 17:03 | Điều phối | `CORE-03-06` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 17:03 | Điều phối | `DV-14-04` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 22/10 |
| 22/10/2026 17:03 | Điều phối | `CORE-01-05` | Chuyển trạng thái sang **Làm một phần** — Gộp main phần trình duyệt; còn listOkrReviews và bộ chuyển HTTP |
| 22/10/2026 17:17 | B4 | `CORE-04-01` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-uusb7v: đã qua build & test, chờ gộp main |
| 22/10/2026 17:17 | B4 | `CORE-04-03` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-uusb7v: đã qua build & test, chờ gộp main |
| 22/10/2026 17:17 | B4 | `CORE-04-04` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-uusb7v: đã qua build & test, chờ gộp main |
| 22/10/2026 17:17 | B4 | `CORE-06-01` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-uusb7v: đã qua build & test, chờ gộp main |
| 22/10/2026 17:17 | B4 | `CORE-07-01` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-uusb7v: đã qua build & test, chờ gộp main |
| 22/10/2026 17:17 | B4 | `DV-07-01` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-uusb7v: đã qua build & test, chờ gộp main |
| 22/10/2026 18:44 | Điều phối | `CORE-04-01` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `CORE-04-03` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `CORE-04-04` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `CORE-06-01` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `CORE-07-01` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `DV-07-01` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `CORE-02-09` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `CORE-05-08` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `CORE-05-09` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `CORE-03-07` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `DV-14-05` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `DV-14-06` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `DV-14-07` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `DV-12-03` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `DV-12-04` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `DV-16-01` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `DV-16-02` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `CNH-03` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `CNH-04` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `CNH-05` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `DL-06` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `DL-07` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `WEB-004` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `WEB-014` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `NEN-044` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `DL-15` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `DL-14` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 18:44 | Điều phối | `DL-13` | Chuyển trạng thái sang **Đã gộp main** — Gộp main 2026-10-22 |
| 22/10/2026 19:20 | B3 (Claude) | `CORE-02-26` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-gmqu4z: cổng ghi dự báo trễ hạn, migration task V6; đã qua build & test |
| 22/10/2026 19:20 | B3 (Claude) | `CORE-02-19` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-gmqu4z: cập nhật tiến độ, EVT-05; đã qua build & test |
| 22/10/2026 19:20 | B3 (Claude) | `CORE-02-14` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-gmqu4z: soạn, giao, giao đồng loạt; migration task V7; đã qua build & test |
| 22/10/2026 19:20 | B3 (Claude) | `CORE-02-18` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-gmqu4z: việc của tôi, nhận việc, xuất Excel; đã qua build & test |
| 22/10/2026 19:20 | B3 (Claude) | `CORE-02-20` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-gmqu4z: điểm nghẽn, migration task V8; đã qua build & test |
| 22/10/2026 19:41 | B3 (Claude) | `CORE-02-21` | Chuyển trạng thái sang **Đang làm** — Nhánh claude/project-thread-gmqu4z: đề nghị gia hạn, điều chỉnh |
| 22/10/2026 19:48 | B3 (Claude) | `CORE-02-21` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-gmqu4z: gửi, xem trước, rút đề nghị; migration task V9; đã qua build & test |
| 22/10/2026 19:58 | B3 (Claude) | `CORE-02-22` | Chuyển trạng thái sang **Đang làm** — Nhánh claude/project-thread-gmqu4z: duyệt đề nghị |
| 22/10/2026 20:06 | B3 (Claude) | `CORE-02-22` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-gmqu4z: quyết định, áp dụng bốn loại, tiếp tục sau tạm dừng, vượt ngưỡng qua cổng khởi tạo tiến trình; đã qua build & test |
| 22/10/2026 20:16 | B3 (Claude) | `CORE-02-23` | Chuyển trạng thái sang **Đang làm** — Nhánh claude/project-thread-gmqu4z: cờ quá hạn |
| 22/10/2026 20:21 | B3 (Claude) | `CORE-02-23` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-gmqu4z: cờ quá hạn hằng ngày, EVT-21, 7 unit test + TaskOverdueIT |
| 22/10/2026 20:31 | B3 (Claude) | `CORE-02-25` | Chuyển trạng thái sang **Đang làm** — Nhánh claude/project-thread-gmqu4z: nhắc việc, giao lại, consumer EVT-15 |
| 22/10/2026 20:38 | B3 (Claude) | `CORE-02-25` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-gmqu4z: remindTask, reassignTask, consumer EVT-15; 8 unit test + TaskDirectionApiIT |
| 22/10/2026 21:26 | Điều phối | `CORE-01-05` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-01-06` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-01-07` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-01-11` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-01-12` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-01-13` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-01-14` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-02-14` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-02-18` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-02-19` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-02-20` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-02-21` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-02-22` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-02-23` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-02-25` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-02-26` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-08-16` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-08-17` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-07-12` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-03-08` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `CORE-07-01` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `DV-15-01` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `DV-15-02` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `DV-15-03` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `DV-15-07` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `DV-15-08` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `DV-15-09` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `DV-15-10` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `WEB-005` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `WEB-009` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `WEB-011` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `WEB-012` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `WEB-013` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `WEB-051` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 21:26 | Điều phối | `WEB-060` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (f74d95f1) |
| 22/10/2026 22:20 | Điều phối | `CORE-06-02` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (984a7ba1) |
| 22/10/2026 22:20 | Điều phối | `CORE-06-03` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (984a7ba1) |
| 22/10/2026 22:20 | Điều phối | `CORE-06-04` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (984a7ba1) |
| 22/10/2026 22:20 | Điều phối | `CORE-04-05` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (984a7ba1) |
| 22/10/2026 22:20 | Điều phối | `CORE-04-06` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (984a7ba1) |
| 22/10/2026 22:20 | Điều phối | `CORE-04-02` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (984a7ba1) |
| 22/10/2026 22:20 | Điều phối | `CORE-07-08` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (984a7ba1) |
| 22/10/2026 22:20 | Điều phối | `DV-15-04` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (984a7ba1) |
| 22/10/2026 22:20 | Điều phối | `DV-15-05` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (984a7ba1) |
| 22/10/2026 22:20 | Điều phối | `WEB-053` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (984a7ba1) |
| 22/10/2026 22:20 | Điều phối | `CORE-07-07` | Chuyển trạng thái sang **Làm một phần** — Phần lõi đã gộp main (8d459e44); mô phỏng chờ DV-07-08 |
| 22/10/2026 22:57 | Điều phối | `CORE-02-15` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (74591707) |
| 22/10/2026 22:57 | Điều phối | `CORE-04-07` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (74591707) |
| 22/10/2026 22:57 | Điều phối | `CORE-04-08` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (74591707) |
| 22/10/2026 23:31 | Điều phối | `CORE-02-28` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (6afcdf05) |
| 22/10/2026 23:31 | Điều phối | `DV-15-06` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (6afcdf05) |
| 22/10/2026 23:31 | Điều phối | `DL-16` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (6afcdf05) |
| 22/10/2026 23:46 | Điều phối | `CORE-06-05` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (ad623eff) |
| 22/10/2026 23:46 | Điều phối | `CORE-02-27` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (ad623eff) |
| 22/10/2026 23:46 | Điều phối | `DV-07-02` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (ad623eff) |
| 22/10/2026 23:46 | Điều phối | `CORE-07-10` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (ad623eff) |
| 22/10/2026 23:46 | Điều phối | `WEB-057` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (ad623eff) |
| 22/10/2026 23:46 | Điều phối | `WEB-045` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (ad623eff) |
| 22/10/2026 23:46 | Điều phối | `WEB-010` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (ad623eff) |
| 23/10/2026 00:50 | Điều phối | `CORE-02-11` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (fcfdd4ab) |
| 23/10/2026 00:50 | Điều phối | `CORE-07-02` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (fcfdd4ab) |
| 23/10/2026 00:50 | Điều phối | `DV-07-03` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (fcfdd4ab) |
| 23/10/2026 00:50 | Điều phối | `DV-07-05` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (fcfdd4ab) |
| 23/10/2026 00:50 | Điều phối | `DV-07-07` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (fcfdd4ab) |
| 23/10/2026 00:50 | Điều phối | `CORE-07-11` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (fcfdd4ab) |
| 23/10/2026 00:50 | Điều phối | `CORE-05-11` | Chuyển trạng thái sang **Đã gộp main** — Đã gộp main (fcfdd4ab) |
| 23/10/2026 00:50 | Điều phối | `WEB-070` | Chuyển trạng thái sang **Làm một phần** — Công tắc giả lập và kịch bản e2e đã gộp main (fcfdd4ab); chạy với máy chủ thật chờ QA-03 |
| 23/10/2026 02:34 | Điều phối | `CORE-05-10`, `DV-06-08`, `CORE-05-12`, `CORE-04-13`, `DV-11-04`, `CORE-02-17`, `DV-13-01`, `DV-13-03`, `DV-08-05`, `CORE-04-09`, `CORE-04-10`, `CORE-04-11`, `DV-07-08`, `CORE-07-03`, `CORE-07-06`, `CORE-02-24` | Chuyển trạng thái sang **Đã gộp main** — Đợt 8–9, main ff7be5b8 |
| 23/10/2026 02:40 | B4 | `CORE-04-12` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-uusb7v: đã qua build & test; migration appraisal V6__appraisal_danh_sach_phan_nhom.sql |
| 23/10/2026 02:40 | B4 | `DV-07-04` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-uusb7v: đã qua build & test (verify scoring-service 197 unit + 20 IT, helm lint); chưa đo tải (YC-PT-19) |
| 23/10/2026 02:40 | B4 | `DV-07-06` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-uusb7v: đã qua build & test (verify scoring-service 197 unit + 20 IT, helm lint); chưa đo tải (YC-PT-19) |
| 23/10/2026 03:18 | B1 (Claude) | `DV-06-06` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b1-nen-mong-backend-8pacfm: saga CHOT_KY 6 bước theo TL-02a, cổng cho SVC-07/11/10/09 và lệnh nội bộ MOD-08; verify workflow-service xanh |
| 23/10/2026 03:33 | B1 (Claude) | `DV-06-07` | Chuyển trạng thái sang **Đang làm** — Nhánh claude/b1-nen-mong-backend-8pacfm: bù trừ saga chốt kỳ trên khung saga |
| 23/10/2026 03:49 | B1 (Claude) | `DV-06-07` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b1-nen-mong-backend-8pacfm: bù trừ saga chốt kỳ, chạy lại hồ sơ lỗi, kiểm tất định; verify xanh (175 unit, 38 IT) |
| 23/10/2026 05:49 | Điều phối | `DV-09-02`, `DV-09-03`, `DV-09-04`, `DV-09-05`, `DL-12`, `CORE-07-07`, `CORE-01-10`, `DV-13-05`, `DV-13-06`, `DV-13-07`, `DV-13-08`, `CORE-03-04`, `CORE-03-05`, `CORE-02-10`, `CORE-04-12`, `DV-07-04`, `DV-07-06`, `DV-11-02`, `DV-11-03`, `DV-13-02`, `DV-13-09`, `CORE-07-04`, `CORE-07-05`, `CORE-06-06`, `CORE-06-07`, `DV-06-04`, `DV-06-06`, `DV-06-07`, `DV-06-12` | Chuyển trạng thái sang **Đã gộp main** — Đợt 10, main c7d74c17 |
| 23/10/2026 05:52 | B1 (Claude) | `DV-01-01` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b1-nen-mong-backend-8pacfm: bảng tuyến theo module, vt-claims ES256, kiểm tĩnh và APISIX thật xanh |
| 23/10/2026 05:52 | B1 (Claude) | `DV-01-03` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b1-nen-mong-backend-8pacfm: public-routes.yaml sinh từ hợp đồng, kiểm CRD và fixture âm tính |
| 23/10/2026 05:52 | B1 (Claude) | `DV-01-04` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b1-nen-mong-backend-8pacfm: chart api-gateway-global, APISIX thật 19 ca, p95 trúng đệm 2,9 ms; mã cell và /jobs theo registry |
| 23/10/2026 06:11 | B2 (luồng B6) | `DV-01-02` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b6-flowable-so-cai-ky-so-t54jlp: ba tầng giới hạn, lớp ưu tiên, bộ điều khiển suy giảm, Coraza CRS 4; đã hợp nhất mô hình tuyến của DV-01-01; kiểm thử tĩnh và APISIX thật trên Docker xanh |
| 23/10/2026 06:58 | B1 (Claude) | `DV-01-04` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/b1-nen-mong-backend-8pacfm: lớp toàn cục + mã tác vụ có tiền tố ở 6 service (org-, wf-, score-, doc-, ledger-, ana-), kiểm thử xanh |
| 23/10/2026 07:49 | B9 | `DV-13-10` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-z7s8x3: nối đủ cổng thu thập/nhập MOD-04, MOD-06, SVC-04, org gọi đóng gói, esign ký tổ chức |
| 23/10/2026 08:50 | Điều phối (main) | `WEB-020`, `CORE-02-16`, `CORE-03-08`, `CORE-06-08`, `CORE-07-09`, `DV-01-01`, `DV-01-02`, `DV-01-03`, `DV-01-04`, `DV-02-04`, `DV-06-09`, `DV-06-10`, `DV-06-11`, `DV-06-12`, `DV-10-03`, `DV-10-04`, `DV-10-05`, `DV-10-06`, `DV-13-04`, `DV-13-07`, `DV-13-09`, `DV-13-10`, `DV-13-11`, `DV-15-11`, `CORE-05-11`, `DV-09-02` | Chuyển trạng thái sang **Đã gộp main** — Đợt gộp 11, bản phát hành v0.11.0; kiểm thử đơn vị 9 module Java và Go xanh |
| 23/10/2026 08:50 | Điều phối (main) | `WEB-070`, `WEB-071`, `WEB-072` | Chuyển trạng thái sang **Đã gộp main** — Đợt gộp 11; còn ngoại lệ: chạy với backend thật chờ QA-03 |
| 23/10/2026 10:52 | Điều phối (main) | `INF-22`, `QA-01`, `QA-03`, `INF-24`, `INF-26` | Chuyển trạng thái sang **Đang làm** — Giao sau bản phát hành 64952ef6 |
| 23/10/2026 10:53 | B4 | `QA-01` | Chuyển trạng thái sang **Đang làm** — Nhánh claude/project-thread-uusb7v: đang thực hiện |
| 23/10/2026 11:00 | B4 | `QA-01` | Chuyển trạng thái sang **Xong, chờ gộp** — Nhánh claude/project-thread-uusb7v: chiến lược kiểm thử trong wiki 11, danh mục 42 hành trình (38 web, 4 di động) đã kiểm khớp hợp đồng |
| 23/10/2026 11:02 | Điều phối (main) | `QA-01` | Chuyển trạng thái sang **Đã gộp main** — Gộp 0fb85523 |
| 23/10/2026 11:02 | Điều phối (main) | `QA-13` | Chuyển trạng thái sang **Đang làm** — Giao B4 |
| 23/10/2026 11:03 | Điều phối (main) | `QA-02` | Chuyển trạng thái sang **Đang làm** — Giao B9; Pact bằng tệp, không dùng Broker (quyết định main) |
| 23/10/2026 11:22 | Điều phối (main) | `INF-25` | Chuyển trạng thái sang **Đang làm** — Giao B7 |
| 23/10/2026 11:41 | B2 backend | `QA-03` | Chuyển trạng thái sang **Làm một phần** — Nhánh claude/project-thread-n43ag0: khung he-thong, lớp hỗ trợ, HT-17 mẫu đã đẩy; bộ giả lập 932 ca xanh; lần chạy thật chờ JWT mission-core (B1), seed hồ sơ (B9), kafka-ui, Remote Control |
| 23/10/2026 11:42 | B2 backend | `QA-04` | Chuyển trạng thái sang **Đang làm** — Nhánh claude/project-thread-n43ag0: hành trình QT-00..QT-03, giả lập và he-thong |
| 23/10/2026 12:07 | B2 backend | `QA-04` | Chuyển trạng thái sang **Làm một phần** — Nhánh claude/project-thread-n43ag0: HT-01..HT-09 ở qt00-03, giả lập 10 đạt 2 chờ (HT-04 VNeID, HT-17 chỉ he-thong), he-thong ghi chờ khi thiếu biến; lần chạy thật chờ INF-22 |
| 23/10/2026 12:09 | B2 backend | `QA-06` | Chuyển trạng thái sang **Đang làm** — Nhánh claude/project-thread-n43ag0: hành trình QT-08..QT-10, giả lập và he-thong |
| 23/10/2026 12:52 | B2 backend | `QA-06` | Chuyển trạng thái sang **Làm một phần** — Nhánh claude/project-thread-n43ag0: HT-23..29 ở qt08-10, giả lập 7/7 đạt hai lần; he-thong 7 Chờ vì chưa đặt biến môi trường, chưa chạy trên dev |
<!-- PROGRESS_LOG_END -->
