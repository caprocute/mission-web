# @vt/api-client

Cấu hình chạy, interceptor, Problem Details (RFC 9457), phân trang con trỏ và client API của mission-core.

## Hợp đồng OpenAPI, lỗi, xung đột, tác vụ dài (WEB-007)

- Kiểu sinh từ `frontend/tools/openapi/mission-core.openapi.yaml` bằng openapi-typescript (chỉ kiểu, không mã chạy):
  `npm run api:gen` ghi `src/lib/generated/mission-core.ts`, `npm run api:check` báo lệch (CI). Không sửa tay tệp sinh.
- `apiUrl('/tasks/{id}', { id })` dựng `/api/v1/...` có kiểm kiểu theo hợp đồng.
- `toProblem`, `isConcurrencyConflict`, `fieldErrors`; `ProblemPresenter` (toast qua `MessageService`, MSG theo `code`
  khai bằng `provideProblemMessages`); `<vt-field-error>`; `<vt-conflict-notice>` cho 409/412.
- `JobTracker.track(location)` theo dõi 202 + `Location: /api/v1/jobs/{id}` (thăm dò giãn cách, `Retry-After`,
  trộn được luồng SSE); `<vt-job-progress>` hiển thị tiến trình. Chi tiết ở wiki/06.

## Dữ liệu giả lập (WEB-008)

Client (như `TaskApi`) luôn gọi HTTP thật. Khi `useMockApi` bật trong `/config.json`,
`mockApiInterceptor` (đặt cuối chuỗi interceptor) trả fixture JSON theo đường dẫn `/api/v1/<module>/...`,
nên interceptor `Idempotency-Key`, `If-Match`, lỗi RFC 9457 chạy như với máy chủ thật.

- Fixture theo module: `src/mock/<module>/<module>.json` (dữ liệu) và `<module>.mock.ts` (tuyến, hành vi).
  Đăng ký module trong `src/mock/mock-registry.ts`. Ngày dạng `@today+2`, `@today-3T08:30:00Z` được quy đổi theo ngày chạy.
- Hành vi mô phỏng: `If-Match` cũ trả 409; dữ liệu sai trả 422 kèm `errors[]`; cùng `Idempotency-Key` trả lại
  kết quả lần đầu, khác nội dung trả 422; tăng `rowVersion`; `DA_GIAO` → `DANG_THUC_HIEN` khi có tiến độ đầu tiên.
- Độ trễ 100–200 ms (đổi bằng `MOCK_API_OPTIONS` hoặc `?mock-delay=<ms>` trên URL trang).
- Lỗi giả lập: thêm `?mock-error=409|422|500` vào URL trang (hoặc tham số `mock-error` của request).
  409, 412, 422 chỉ áp cho lệnh ghi (POST/PUT/PATCH/DELETE); mã khác (500, 503...) áp cho mọi request `/api/v1/`.
- Mã giả lập nằm trong chunk nạp lười `mock-registry`, không vào gói tải đầu. Dữ liệu sống trong bộ nhớ,
  tải lại trang là về fixture ban đầu. Chưa giả lập WebSocket/SSE.

---

# ApiClient

This project was generated using [Angular CLI](https://github.com/angular/angular-cli) version 21.2.0.

## Code scaffolding

Angular CLI includes powerful code scaffolding tools. To generate a new component, run:

```bash
ng generate component component-name
```

For a complete list of available schematics (such as `components`, `directives`, or `pipes`), run:

```bash
ng generate --help
```

## Building

To build the library, run:

```bash
ng build api-client
```

This command will compile your project, and the build artifacts will be placed in the `dist/` directory.

### Publishing the Library

Once the project is built, you can publish your library by following these steps:

1. Navigate to the `dist` directory:

   ```bash
   cd dist/api-client
   ```

2. Run the `npm publish` command to publish your library to the npm registry:
   ```bash
   npm publish
   ```

## Running unit tests

To execute unit tests with the [Karma](https://karma-runner.github.io) test runner, use the following command:

```bash
ng test
```

## Running end-to-end tests

For end-to-end (e2e) testing, run:

```bash
ng e2e
```

Angular CLI does not come with an end-to-end testing framework by default. You can choose one that suits your needs.

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.
