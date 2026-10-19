# Danh mục task cho AI agent – phân hệ web Mission

Mỗi tệp `WEB-xxx-*.md` là một task đủ nhỏ để một AI agent làm trọn trong một phiên. Mọi task đều mở đầu bằng khối **Quy định chung** bắt buộc đọc `AGENTS.md`; khi chạy bằng Claude Code trong repo này, hook `SessionStart` trong `.claude/settings.json` còn tự in lời nhắc `docs/tasks/_NHAC-QUY-DINH.md` ở đầu mỗi phiên. Với agent khác, dùng `./scripts/agent-task.sh WEB-xxx` để sinh prompt gồm quy định chung và nội dung task.

Thứ tự thực hiện đi theo cột Phụ thuộc: một task chỉ bắt đầu khi các task nó phụ thuộc đã xong. Những task cùng giai đoạn không phụ thuộc nhau có thể giao cho nhiều agent chạy song song, mỗi agent một nhánh `feat/WEB-xxx-…`. Cỡ việc S/M/L ước lượng cho một agent; người phụ trách duyệt PR của từng task trước khi gộp.

## Phân luồng phát triển song song (từ 19/10/2026)

Công việc được chia thành ba luồng chạy song song, mỗi luồng là một thread riêng trong dự án. Luồng **Nền tảng** do thread "Kế hoạch và hạ tầng phân hệ web" giữ, sở hữu nhánh `main` trong `/mnt/project-files/mission-web` và là nơi duy nhất gộp code vào `main`. Luồng này làm WEB-001 đến WEB-015 (công cụ, CI, thư viện `@vt/*`, shell, khung remote) và các task xuyên suốt WEB-051 đến WEB-053. Luồng **Phân quyền** làm WEB-054 đến WEB-058. Luồng **Màn hình nghiệp vụ** làm WEB-016 đến WEB-050 theo thứ tự quy trình QT.

Hai luồng Phân quyền và Màn hình nghiệp vụ làm trong git worktree riêng tại `/mnt/project-files/mission-web-worktrees/<tên-luồng>`, mỗi task một nhánh `feat/WEB-xxx-…`, không ghi trực tiếp vào `main`. Khi một task đạt build, kiểm thử và đã mở màn hình thật, luồng đó báo luồng Nền tảng để gộp; trước khi bắt đầu task mới, luồng phải rebase lên `main` mới nhất.

Vì các luồng sau cần nền tảng, luồng Nền tảng làm trước các task mà hai luồng kia phụ thuộc: WEB-001 (lint, test), WEB-008 (mock API), WEB-007 (api-client), WEB-015 (khung remote), WEB-006 (đăng nhập) và WEB-010 (form engine). Các task màn hình của `fe-task` (WEB-025, WEB-028, WEB-030, WEB-031) chỉ cần WEB-008 nên có thể bắt đầu sớm nhất; các remote khác chờ WEB-015. Tệp dùng chung như `fe-shell/src/app/app.routes.ts`, `layout/nav.ts`, `package.json` chỉ được sửa ở mức tối thiểu và ghi rõ trong báo cáo để luồng Nền tảng gộp xung đột.

Mẫu để viết task mới: [`_MAU-TASK.md`](_MAU-TASK.md).

## GĐ0 – Nền tảng phát triển

| Mã | Task | Cỡ | Phụ thuộc | Trạng thái |
|---|---|---|---|---|
| [WEB-001](WEB-001-cong-cu-chat-luong.md) | Công cụ chất lượng mã: ESLint, Prettier, Vitest cho toàn workspace | S | – | Hoàn thành (format:check còn đỏ do mã cũ) |
| [WEB-002](WEB-002-pipeline-jenkins.md) | Pipeline Jenkins cho frontend có cổng chất lượng | M | WEB-001 | Chưa bắt đầu |
| [WEB-003](WEB-003-ngan-sach-hieu-nang.md) | Ngân sách bundle và Lighthouse CI | S | WEB-001 | Hoàn thành cấu hình; số đo chưa đạt, tối ưu ở WEB-052 |
| [WEB-004](WEB-004-bien-dich-token.md) | Biên dịch Design Token và kiểm tra tương phản | M | WEB-001 | Chưa bắt đầu |
| [WEB-005](WEB-005-storybook-hoi-quy.md) | Storybook và kiểm thử hồi quy giao diện theo bộ token | M | WEB-004 | Chưa bắt đầu |
| [WEB-006](WEB-006-dang-nhap-keycloak.md) | Đăng nhập Keycloak, chọn đơn vị và vai trò (phần web của QT-00) | M | WEB-001 | Chưa bắt đầu |
| [WEB-007](WEB-007-api-client-openapi.md) | `@vt/api-client`: sinh client từ OpenAPI, xử lý lỗi, 409, job 202 | M | WEB-001 | Chưa bắt đầu |
| [WEB-008](WEB-008-mock-api.md) | Tách dữ liệu giả lập thành interceptor và fixture theo module | S | WEB-007 | Hoàn thành (axe còn lỗi có sẵn, xem WEB-059) |
| [WEB-009](WEB-009-sdui-chu-ky.md) | SDUI: kiểm chữ ký UI Pack, kiểm phiên bản registry, telemetry | M | WEB-004 | Chưa bắt đầu |
| [WEB-010](WEB-010-form-engine.md) | Form engine: widget, test CEL dùng chung, benchmark | M | WEB-001 | Chưa bắt đầu |
| [WEB-011](WEB-011-telemetry-faro.md) | Telemetry: Grafana Faro + OpenTelemetry web | S | WEB-001 | Chưa bắt đầu |
| [WEB-012](WEB-012-shell-hoan-thien.md) | Shell: thông báo thời gian thực, tìm kiếm, xử lý lỗi toàn cục | M | WEB-006, WEB-007 | Chưa bắt đầu |
| [WEB-013](WEB-013-web-component-nhung.md) | Chế độ nhúng `<vt-okr-app>` cho cổng văn phòng số | M | WEB-006 | Chưa bắt đầu |
| [WEB-014](WEB-014-csp-trusted-types.md) | CSP chặt và Trusted Types cho bản phục vụ nginx | S | WEB-002 | Chưa bắt đầu |
| [WEB-015](WEB-015-khung-remote.md) | Công cụ tạo remote mới và dựng khung 8 remote | M | WEB-001 | Chưa bắt đầu |

## GĐ1 – Định danh, tổ chức (đợt 1 TL-04)

| Mã | Task | Cỡ | Phụ thuộc | Trạng thái |
|---|---|---|---|---|
| [WEB-016](WEB-016-danh-sach-tenant.md) | SCR-0101 Danh sách tenant | S | WEB-015 | Chưa bắt đầu |
| [WEB-017](WEB-017-wizard-tenant.md) | SCR-0102 Khởi tạo tenant (wizard) | M | WEB-016 | Chưa bắt đầu |
| [WEB-018](WEB-018-cay-to-chuc.md) | SCR-0103 Cây tổ chức có lịch sử | L | WEB-015 | Chưa bắt đầu |
| [WEB-019](WEB-019-doi-soat-nhan-su.md) | SCR-0104 Đồng bộ và đối soát nhân sự | M | WEB-018 | Chưa bắt đầu |
| [WEB-020](WEB-020-theme-keycloak.md) | SCR-0001 Giao diện trang đăng nhập (theme Keycloak) | M | WEB-004, WEB-006 | Chưa bắt đầu |

## GĐ2 – Lõi nghiệp vụ (đợt 2 TL-04)

| Mã | Task | Cỡ | Phụ thuộc | Trạng thái |
|---|---|---|---|---|
| [WEB-021](WEB-021-cay-okr.md) | SCR-0201 Cây mục tiêu OKR | L | WEB-015 | Chưa bắt đầu |
| [WEB-022](WEB-022-tao-sua-okr.md) | SCR-0202 Tạo/sửa O và KR | M | WEB-021 | Chưa bắt đầu |
| [WEB-023](WEB-023-duyet-okr.md) | SCR-0203 Phê duyệt OKR | M | WEB-022 | Chưa bắt đầu |
| [WEB-024](WEB-024-check-in-kr.md) | SCR-0301 Check-in KR | M | WEB-021 | Chưa bắt đầu |
| [WEB-025](WEB-025-danh-muc-cong-viec.md) | SCR-0401 Thư viện danh mục công việc | M | WEB-008 | Chưa bắt đầu |
| [WEB-026](WEB-026-lap-ke-hoach.md) | SCR-0402 Lập kế hoạch công tác quý | L | WEB-025 | Chưa bắt đầu |
| [WEB-027](WEB-027-duyet-ke-hoach.md) | SCR-0403 Phê duyệt kế hoạch (so sánh phiên bản) | M | WEB-026 | Chưa bắt đầu |
| [WEB-028](WEB-028-giao-viec.md) | SCR-0501 Giao việc | M | WEB-008 | Chưa bắt đầu |
| [WEB-029](WEB-029-nhiem-vu-tu-van-ban.md) | SCR-0502 Tạo nhiệm vụ từ văn bản chỉ đạo | M | WEB-028 | Chưa bắt đầu |
| [WEB-030](WEB-030-viec-cua-toi.md) | SCR-0503 Việc của tôi – hoàn thiện | S | WEB-008 | Xong, chờ gộp (luồng Màn hình) |
| [WEB-031](WEB-031-chi-tiet-gia-han.md) | SCR-0601 Chi tiết nhiệm vụ và SCR-0602 Đề nghị gia hạn, điều chỉnh | M | WEB-030 | Chưa bắt đầu |
| [WEB-032](WEB-032-nop-minh-chung.md) | SCR-0701 Nộp minh chứng | L | WEB-015 | Chưa bắt đầu |
| [WEB-033](WEB-033-nghiem-thu.md) | SCR-0703 Nghiệm thu chất lượng 5 mức | M | WEB-032 | Chưa bắt đầu |
| [WEB-034](WEB-034-khoa-ky.md) | SCR-0801 Quản lý kỳ và tiến trình khoá kỳ | M | WEB-007, WEB-015 | Chưa bắt đầu |

## GĐ3 – Đánh giá, hồ sơ, giám sát

| Mã | Task | Cỡ | Phụ thuộc | Trạng thái |
|---|---|---|---|---|
| [WEB-035](WEB-035-tu-danh-gia.md) | SCR-0901 Phiếu tự đánh giá | L | WEB-010, WEB-015 | Chưa bắt đầu |
| [WEB-036](WEB-036-tham-dinh.md) | SCR-0902 Thẩm định | L | WEB-035 | Chưa bắt đầu |
| [WEB-037](WEB-037-phieu-xep-loai.md) | SCR-0903 Phiếu xếp loại có giải thích | M | WEB-036 | Chưa bắt đầu |
| [WEB-038](WEB-038-quyet-dinh-hang-loat.md) | SCR-0904 Quyết định xếp loại hàng loạt và ký số | L | WEB-037 | Chưa bắt đầu |
| [WEB-039](WEB-039-xep-loai-nam.md) | SCR-1001 Xếp loại năm và SCR-1002 Phân nhóm triển vọng | M | WEB-037 | Chưa bắt đầu |
| [WEB-040](WEB-040-hoi-nghi-kiem-diem.md) | SCR-1101 Hội nghị kiểm điểm | M | WEB-015 | Chưa bắt đầu |
| [WEB-041](WEB-041-goi-ho-so-so.md) | SCR-1102 Gói hồ sơ số và ký số | M | WEB-040 | Chưa bắt đầu |
| [WEB-042](WEB-042-khieu-nai.md) | SCR-1201 Gửi khiếu nại và SCR-1202 Xử lý khiếu nại | L | WEB-015 | Chưa bắt đầu |
| [WEB-043](WEB-043-mo-lai-ky-so-cai.md) | SCR-1301 Yêu cầu mở lại kỳ và SCR-1302 Tra cứu sổ cái | M | WEB-034 | Chưa bắt đầu |

## GĐ4 – Điều hành, cấu hình, mở rộng

| Mã | Task | Cỡ | Phụ thuộc | Trạng thái |
|---|---|---|---|---|
| [WEB-044](WEB-044-trung-tam-canh-bao.md) | SCR-1401 Trung tâm cảnh báo | M | WEB-015 | Chưa bắt đầu |
| [WEB-045](WEB-045-studio-cong-thuc.md) | SCR-1501 Config Studio – công thức và SCR-1502 Bảng quyết định | L | WEB-010, WEB-015 | Chưa bắt đầu |
| [WEB-046](WEB-046-test-vang-luong-duyet.md) | SCR-1503 Chạy test case vàng và SCR-1504 Cấu hình luồng duyệt | M | WEB-045 | Chưa bắt đầu |
| [WEB-047](WEB-047-bang-dieu-hanh.md) | SCR-1601 Bảng điều hành lãnh đạo và SCR-1603 Đi xuống tới minh chứng | L | WEB-015 | Chưa bắt đầu |
| [WEB-048](WEB-048-trinh-dung-dashboard.md) | SCR-1602 Trình dựng dashboard | M | WEB-047 | Chưa bắt đầu |
| [WEB-049](WEB-049-tro-ly-ai.md) | SCR-1701 Trợ lý AI soạn OKR và soát KR | M | WEB-022 | Chưa bắt đầu |
| [WEB-050](WEB-050-studio-giao-dien.md) | SCR-1801 Cấu hình giao diện tenant và SCR-1802 Trình dựng biểu mẫu động | L | WEB-004, WEB-009, WEB-010 | Chưa bắt đầu |
| [WEB-051](WEB-051-plugin-chuyen-nganh.md) | Plugin chuyên ngành mẫu và manifest theo tenant | M | WEB-015 | Chưa bắt đầu |
| [WEB-052](WEB-052-toi-uu-hieu-nang.md) | Kiểm thử và tối ưu hiệu năng frontend | M | WEB-003, WEB-011 | Chưa bắt đầu |
| [WEB-053](WEB-053-danh-gia-attt.md) | Đánh giá ATTT frontend: OWASP ZAP và ASVS mức 2 | M | WEB-014 | Chưa bắt đầu |

## Phân quyền (xuyên giai đoạn)

Nhóm này hiện thực ma trận phân quyền ở [wiki/15a](../../wiki/15a-ma-tran-phan-quyen.md) cho phía giao diện và môi trường dev. Nguồn dữ liệu duy nhất là `docs/phan-quyen/ma-tran.json`; wiki/15a và tệp `docs/phan-quyen/ma-tran-phan-quyen.xlsx` được sinh từ đó bằng `python3 docs/phan-quyen/gen_matrix.py`. WEB-054 nên làm ngay sau WEB-006 để các task màn hình từ GĐ1 trở đi dùng được `can()` và `*vtCan`; mọi task màn hình đều có tiêu chí ẩn hiện chức năng đúng ma trận.

| Mã | Task | Cỡ | Phụ thuộc | Trạng thái |
|---|---|---|---|---|
| [WEB-054](WEB-054-bang-quyen-frontend.md) | Bảng quyền phía giao diện sinh từ ma trận (`can()`, `*vtCan`, `permissionGuard`) | M | WEB-001, WEB-006 | Xong, chờ gộp (nhánh `feat/WEB-054-bang-quyen-frontend`) |
| [WEB-055](WEB-055-ap-ma-tran-shell-remote.md) | Áp ma trận vào menu, tuyến và nút của shell và các remote hiện có | M | WEB-054, WEB-015 | Chưa bắt đầu |
| [WEB-056](WEB-056-dong-bo-realm-keycloak.md) | Đồng bộ realm Keycloak dev với ma trận (vai trò, claim, MFA theo vai trò, tài khoản thử) | M | WEB-006, WEB-054 | Chưa bắt đầu |
| [WEB-057](WEB-057-kiem-thu-ma-tran-playwright.md) | Kiểm thử Playwright đối chiếu ma trận: vai trò × tuyến | M | WEB-055, WEB-056 | Chưa bắt đầu |
| [WEB-058](WEB-058-quan-tri-vai-tro-uy-quyen.md) | Quản trị phân công vai trò và uỷ quyền (chưa có SCR, cần bổ sung thiết kế) | L | WEB-018, WEB-054, quyết định thiết kế | Chờ thiết kế |

## Phát sinh trong quá trình làm

| Mã | Task | Cỡ | Phụ thuộc | Trạng thái |
|---|---|---|---|---|
| [WEB-059](WEB-059-kha-nang-truy-cap-nen.md) | Sửa lỗi khả năng truy cập nền: progressbar, slider, tương phản token | S | WEB-001 | Chưa bắt đầu |
| [WEB-060](WEB-060-format-toan-bo.md) | Chạy Prettier toàn workspace một lần | S | WEB-001 | Chưa bắt đầu |
| [WEB-061](WEB-061-trinh-soan-mau-luong.md) | SCR-1505 Thư viện và trình soạn mẫu luồng (BS-01) | L | WEB-015, WEB-046 | Chưa bắt đầu (luồng Màn hình nghiệp vụ) |
| [WEB-062](WEB-062-giam-sat-tien-trinh.md) | SCR-1506 Giám sát tiến trình theo mẫu (BS-01) | M | WEB-015 | Chưa bắt đầu (luồng Màn hình nghiệp vụ) |

## Màn hình không thuộc phân hệ web

SCR-0302, SCR-0504, SCR-0702 và SCR-1803 là màn hình di động của ứng dụng Flutter (SVC-18) nên không có task ở đây; phân hệ web chỉ bảo đảm dùng chung API, UI Pack và token với ứng dụng di động.
