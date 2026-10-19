# WEB-063 · Bộ hợp đồng OpenAPI 3.1 toàn dự án (API-first)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-063` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Xuyên suốt (nền móng cho mọi luồng) |
| Cỡ việc | L |
| Phụ thuộc | WEB-007 |
| Truy vết | Mọi QT → mọi SCR → `/api/v1/*` → MOD-01..08, SVC-03..16 (trừ SVC-13) |
| Trạng thái | Hoàn thành (đã gộp; điểm chốt được chấp nhận 19/10/2026; chuyển `generate.mjs` ở WEB-064) |

## 1. Mục tiêu

Viết bộ hợp đồng OpenAPI 3.1 cho toàn bộ API công khai của nền tảng theo hướng hợp đồng trước (ADR-32, bổ sung BS-02), để frontend sinh kiểu và dữ liệu giả lập, backend sinh interface controller, và mọi luồng dùng chung một bộ đường dẫn, tên trường, giá trị liệt kê.

## 2. Đầu vào bắt buộc đọc

- wiki/06-quy-uoc-api.md, wiki/04-ma-dinh-danh-va-truy-vet.md, wiki/15a-ma-tran-phan-quyen.md
- Baseline mục 9, 9.1, 10, 13; TL-01 mục "Hợp đồng API theo OpenAPI" và Phụ lục C; bảng sự kiện SCR của TL-02a, TL-02b; TL-03 (cột, ràng buộc CHECK)
- `docs/phan-quyen/ma-tran.json`; `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-02-hop-dong-API-truoc.md`

## 3. Phạm vi công việc

1. Tạo `contracts/openapi/common/common.yaml` cho thành phần dùng chung và một tệp cho mỗi module hoặc service trong `contracts/openapi/modules/`.
2. Mỗi thao tác có `operationId` duy nhất, phản hồi lỗi RFC 9457, `Idempotency-Key` khi ghi, `If-Match` khi sửa, `x-mission` để truy vết; lược đồ chính có `examples`.
3. Cấu hình Redocly (`contracts/redocly.yaml`) với hai quy tắc riêng: bắt buộc `x-mission`, bắt buộc tham số trên thao tác ghi; script `bundle.mjs` gộp từng tệp.
4. Ghi ADR-32 và bảng điểm chốt vào BS-02; cập nhật wiki/06 và wiki/14.

## 4. Ngoài phạm vi

API đối tác qua SVC-13 (kho hợp đồng riêng theo TL-01). Sửa `frontend/tools/openapi/generate.mjs` và các model `@vt/api-client` để dùng bộ hợp đồng: việc của thread điều phối, làm sau khi gộp. Sửa TL-03 theo các điểm "cần bổ sung" của BS-02.

## 5. Tệp được phép sửa

- `contracts/**`
- `wiki/06-quy-uoc-api.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`
- `docs/tasks/WEB-063-hop-dong-openapi.md`, `docs/tasks/README.md` (một dòng)
- Ngoài repo: `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-02-hop-dong-API-truoc.md`

## 6. Tiêu chí hoàn thành

- `cd contracts && npm ci && npm run lint`: mọi tệp hợp lệ, không lỗi.
- `npm run bundle` tạo đủ 11 bản gộp trong `dist/`.
- openapi-typescript sinh kiểu không lỗi cho `common.yaml` và từng tệp module.
- Không trùng `operationId` và tên lược đồ trên toàn thư mục.

## 7. Báo cáo

Theo mẫu `AGENTS.md` mục 6.

## Báo cáo WEB-063

- Kết quả: 11 tệp module và `common.yaml`, tổng 351 thao tác (tasks 58, config 54, platform 47, oversight 36, policies 34, dossiers 31, appraisals 24, okr 23, products 22, org 20, jobs 2); `redocly.yaml`, `package.json`, `package-lock.json`, `openapi/bundle.mjs`, `openapi/README.md`; ADR-32 trong BS-02.
- Truy vết: mỗi thao tác ghi `x-mission.qt/scr/owner/evt/roles/scope/source`.
- Kiểm chứng: Redocly 1.34.21 lint toàn bộ hợp lệ (0 lỗi, 5 cảnh báo `no-ambiguous-paths`); bundle ra 11 tệp; openapi-typescript 7 sinh kiểu được 12 tệp; kiểm trùng `operationId` và tên lược đồ toàn thư mục trả rỗng; đối chiếu lược đồ `TaskSummary`, `TaskDetail`, `ProgressUpdate`, `Assignment`, `MyOrgProfile`, `WorkContext`, `Job`, `ProblemDetails` với hợp đồng mẫu WEB-007 và model viết tay của `@vt/api-client`: tương thích (chỉ thêm trường đã có trong model viết tay).
- Giả định đã chọn: các điểm HD-01..HD-07 ở `contracts/openapi/README.md` và các bảng theo module ở BS-02; giá trị tự đặt khi nguồn không ghi đều đánh dấu "(suy luận)" trong tệp.
- Chưa làm / rủi ro: chưa chuyển `generate.mjs`; chưa có kiểm thử hợp đồng tự động (Prism hoặc Schemathesis) và chưa đưa lint vào CI; các điểm chốt đã được anh Hiếu chấp nhận ngày 19/10/2026; TL-03 còn chờ bổ sung theo BS-02.
- Cập nhật wiki: 06 (mục "Bộ hợp đồng OpenAPI dùng chung"), 14 (ADR-32, mục "Điểm chốt khi viết hợp đồng OpenAPI").
