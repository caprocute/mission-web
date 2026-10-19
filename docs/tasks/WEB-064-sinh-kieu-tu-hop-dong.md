# WEB-064 · Sinh kiểu frontend từ bộ hợp đồng OpenAPI (API-first)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-064` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Xuyên suốt (luồng Nền tảng) |
| Cỡ việc | M |
| Phụ thuộc | WEB-007, WEB-063 |
| Truy vết | Mọi QT → mọi SCR → `/api/v1/*` (`contracts/openapi/`) → `@vt/api-client` |
| Trạng thái | Hoàn thành (chờ luồng điều phối gộp) |

## 1. Mục tiêu

Kiểu TypeScript của `@vt/api-client` được sinh từ bộ hợp đồng `contracts/openapi/` của WEB-063 thay cho bản mẫu `frontend/tools/openapi/mission-core.openapi.yaml`, để frontend bám đúng một nguồn hợp đồng theo ADR-32 và YC-PT-05: hợp đồng đổi mà chưa sinh lại thì `npm run api:check` báo lỗi, model viết tay lệch hợp đồng thì kiểm thử báo lỗi biên dịch.

## 2. Đầu vào bắt buộc đọc

- `wiki/06-quy-uoc-api.md` mục "Bộ hợp đồng OpenAPI dùng chung", `wiki/10-quy-uoc-ma-nguon.md`, `wiki/11-kiem-thu-va-chat-luong.md` mục "Cổng chất lượng trong pipeline".
- `contracts/openapi/README.md` (quy ước viết hợp đồng, bảng điểm đã chốt HD-01 đến HD-07), `docs/tasks/WEB-063-hop-dong-openapi.md`.
- `frontend/tools/openapi/generate.mjs` và `frontend/projects/api-client/src`.

## 3. Phạm vi công việc

1. Sửa `generate.mjs` đọc `contracts/openapi/`: sinh `lib/generated/common.ts` từ `common/common.yaml` và `lib/generated/<module>.ts` từ mỗi `modules/<module>.yaml`, mỗi tệp hợp đồng một tệp kiểu, không gộp không gian tên (tên cục bộ như tham số `VersionNo` trùng giữa `tasks.yaml` và `products.yaml`). Sinh thêm `lib/generated/index.ts` xuất bí danh theo module (`TasksPaths`, `TasksComponents`, `TasksOperations`, `CommonComponents`…) và `ApiPaths` là hợp mọi đường dẫn. Giữ `npm run api:gen` và `npm run api:check`; `api:check` báo cả tệp sinh lệch lẫn tệp sinh thừa không còn hợp đồng.
2. Bỏ bản mẫu `mission-core.openapi.yaml` và tệp sinh `generated/mission-core.ts`; thay export `MissionCorePaths`, `MissionCoreComponents` trong `public-api.ts` bằng export theo module (đã kiểm bằng grep trên mọi worktree: ngoài thư viện không nơi nào dùng hai tên cũ).
3. Nối model viết tay với kiểu sinh: `Job`, `JobStatus`, `ProblemDetails`, `FieldError` lấy từ `common.ts`; `TaskSummary`, `TaskDetail`, `ProgressUpdate`, `TaskStatus`, `AssigneeRole` từ `tasks.ts`; `Assignment`, `AssignmentKind`, `MyOrgProfile`, `WorkContext` từ `org.ts`. Các model viết tay còn lại có cùng tên trong hợp đồng được kiểm tương thích hai chiều lúc biên dịch trong `models/contract-check.spec.ts`. Không đổi tên trường mà màn hình đang dùng.
4. `apiUrl()` kiểm đường dẫn theo `ApiPaths` của toàn bộ hợp đồng. `TaskApi.draft()` gọi `GET /tasks/{id}/draft` (`getTaskDraft`) thay cho `GET /tasks/{id}?view=assign`, mock đổi theo; chữ ký hàm giữ nguyên nên màn hình không phải sửa.
5. Ghi bước `cd contracts && npm ci && npm run lint` vào cổng chất lượng CI (chưa có Jenkinsfile nên ghi ở `wiki/11`); cập nhật `wiki/06`, `wiki/10` và README của `@vt/api-client`.

## 4. Ngoài phạm vi

- Mã màn hình trong `fe-task` và các remote khác (luồng Màn hình nghiệp vụ). Vị trí mã cần đổi theo hợp đồng được liệt kê trong báo cáo dưới đây để chuyển cho luồng đó.
- Sửa hợp đồng trong `contracts/openapi/` (mọi lệch hợp đồng và model được ghi lại, không tự sửa một bên).
- Đổi `PlanApi`, `TenantApi`, `OkrApi` và model kế hoạch, OKR, tenant sang lược đồ của hợp đồng: các model này còn lệch tên trường hoặc tên lược đồ, cần chốt và sửa cùng màn hình.
- Viết Jenkinsfile (WEB-002).

## 5. Tệp được phép sửa

- `frontend/tools/openapi/**`
- `frontend/projects/api-client/src/lib/generated/**`, `src/lib/models/{task,job,org}.ts`, `src/lib/models/contract-check.spec.ts`, `src/lib/problem-details.ts`, `src/lib/api-path.ts`, `src/lib/api-path.spec.ts`, `src/lib/task-api.ts`, `src/mock/tasks/{tasks,assign}.mock.ts`, `src/public-api.ts`, `frontend/projects/api-client/README.md`
- `wiki/06-quy-uoc-api.md`, `wiki/07-xac-thuc-phan-quyen.md` (một câu về nguồn lược đồ), `wiki/10-quy-uoc-ma-nguon.md`, `wiki/11-kiem-thu-va-chat-luong.md`
- `docs/tasks/WEB-064-sinh-kieu-tu-hop-dong.md`, `docs/tasks/README.md` (một dòng)

## 6. Tiêu chí hoàn thành

- Trong `frontend/`: `npm run api:gen && npm run api:check`, `npm run lint`, `npm run test:all`, `npm run build:all` đều xanh.
- `cd contracts && npm ci && npm run lint` xanh.
- Sửa thử một tệp hợp đồng mà không sinh lại thì `npm run api:check` thoát mã 1; làm lệch một model viết tay thì `ng test api-client` báo lỗi biên dịch ở `contract-check.spec.ts`.
- Không thay đổi hành vi màn hình: chữ ký `TaskApi` giữ nguyên, tên trường model không đổi.

## 7. Báo cáo

Theo mẫu `AGENTS.md` mục 6.
