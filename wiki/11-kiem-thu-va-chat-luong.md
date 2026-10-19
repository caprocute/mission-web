> Nguồn: TL-01 mục kiểm thử (Pact, Playwright, k6), mục CI/CD (Jenkins, cổng chất lượng, chuỗi cung ứng), mục kiểm thử UI Pack bốn tầng, mục form engine (bộ test CEL dùng chung); TL-04 mục PERF (PERF-01, PERF-02, PERF-21), OPS-10, OPS-11, SEC-37 đến SEC-40; `AGENTS.md` quy tắc 11 và 12; `docs/tasks/README.md`, `WEB-001`, `WEB-002`, `WEB-005`; mã `frontend/angular.json`, `package.json`.

# Kiểm thử và chất lượng

Trang này mô tả các tầng kiểm thử mà phân hệ web phải có, các cổng chất lượng trong pipeline và tiêu chí để một task được coi là xong. Phần lớn công cụ ở đây chưa được dựng trong repo; trang ghi rõ phần nào đã có, phần nào thuộc task nào trong `docs/tasks/`.

## Các tầng kiểm thử

Bảng dưới liệt kê các loại kiểm thử theo TL-01 và TL-04, kèm hiện trạng trong repo.

| Loại | Công cụ | Phạm vi | Hiện trạng |
|---|---|---|---|
| Đơn vị | Vitest qua `@angular/build:unit-test`, jsdom | Hàm thuần, service, component | Đã có (WEB-001): spec cho `@vt/design-system`, `@vt/form-engine`, `@vt/api-client` (giả lập), `app.spec.ts` của hai ứng dụng |
| Hợp đồng | Pact (frontend là bên tiêu dùng) | Hợp đồng frontend với API mission-core | Chưa có |
| Đầu cuối | Playwright, khoảng 40 hành trình chính | Luồng nghiệp vụ trên trình duyệt thật | Chưa có |
| Giao diện theo token | Storybook, Playwright chụp ảnh, axe-core | Component registry × 4 bộ token | Chưa có (WEB-005) |
| Hiệu năng trang | Lighthouse CI, WebPageTest 4G | PERF-21 mỗi bản build | Đã cấu hình (WEB-003), số đo chưa đạt |
| Tải | k6 | PERF-22 đến PERF-24 | Chưa có |
| Bảo mật động | OWASP ZAP | SEC-40, ASVS mức 2 | Chưa có (WEB-053) |

Kiểm thử đơn vị nên tập trung vào phần có logic: `flattenTokens` và `tokensToCss` của `@vt/design-system`, `evaluateRules` và `lintRules` của `@vt/form-engine`, bộ lọc thuộc tính và bộ đánh giá CEL của `@vt/sdui-renderer`, interceptor và `toProblem` của `@vt/api-client`, hàm `statusSeverity` và `daysLeft` của `fe-task`. WEB-001 đã dựng ESLint (`eslint.config.js`) với quy tắc cấm gắn `innerHTML`, `outerHTML`, cấm `bypassSecurityTrust*`, `eval`, `new Function`, NgModule mới, bắt buộc standalone và ưu tiên `inject()`, cùng bộ quy tắc truy cập cho template. Các lệnh kiểm chứng chạy trong `frontend/`: `npm run lint`, `npm run test:all` (Vitest cho mọi project có spec), `npm run build:all`, `npm run format:check` (còn đỏ do mã cũ cho tới WEB-060), `npm run perf:prepare` và `npm run perf:lhci` (Lighthouse CI, xem [Hiệu năng và trải nghiệm](09-hieu-nang-va-trai-nghiem.md)).

Hai bẫy khi viết test cho ứng dụng: target `test` phải trỏ `buildTarget` sang `<app>:esbuild:development` vì builder `unit-test` không chạy với builder `native-federation`, và builder báo lỗi khi project không có spec nào. Test chạm tới HTTP dùng `mockApiInterceptor` với `MOCK_API_OPTIONS` đặt độ trễ 0 và gọi `resetMockData()` trước mỗi test (xem `mock-api.interceptor.spec.ts`); interceptor giả lập luôn đứng cuối chuỗi interceptor. Khi kiểm component dùng `rxResource`, nhớ thử cả trạng thái lỗi vì `value()` ném lỗi ở trạng thái này.

## Kiểm thử luật CEL dùng chung

Luật kiểm tra biểu mẫu chạy ở hai nơi: `cel-js` trong trình duyệt để phản hồi nhanh và `cel-java` ở máy chủ để quyết định. TL-01 yêu cầu một bộ test dùng chung (cùng biểu thức, cùng dữ liệu, cùng kết quả mong đợi) chạy trên cả hai thư viện, để phát hiện sai khác ngữ nghĩa giữa hai bản cài đặt. Luật đánh dấu `serverOnly` không chạy ở trình duyệt và không cần trong bộ test phía web. Benchmark biểu mẫu 50 trường phản hồi trong 50 ms (PERF-21) cũng thuộc phần này; cả hai việc nằm trong task WEB-010.

## Kiểm thử UI Pack bốn tầng

Vì giao diện tenant được thay bằng dữ liệu mà không phát hành lại mã, TL-01 đặt chiến lược kiểm thử riêng gồm bốn tầng. Tầng một là Storybook: mỗi component trong registry có story, được chụp ảnh với bốn bộ token và quét axe-core. Tầng hai là kiểm tĩnh khi UI Pack được gửi lên: kiểm lược đồ, tương phản màu tối thiểu 4,5:1 tính trực tiếp trên token, kích thước CSS biên dịch không quá 30 KB. Tầng ba là smoke test khoảng 2 phút trên staging với chính UI Pack đó. Tầng bốn là kiểm thử hồi quy trên tập 20 UI Pack đại diện mỗi khi registry hoặc design system thay đổi.

Quy trình phát hành UI Pack theo OPS-11 trong không quá một ngày gồm: kiểm lược đồ, axe-core theo WCAG 2.1 AA, ngưỡng 30 KB, ảnh so sánh trên staging, phát hành, rồi phát sự kiện EVT-23 để vô hiệu cache CDN theo khoá phiên bản. Policy Pack theo OPS-10 trong năm ngày, trong đó ngày thứ hai chạy pipeline với test vàng phải đạt 100% và ngày thứ ba chạy shadow (xem [Giao diện theo tenant](03-giao-dien-theo-tenant.md) và SCR-1503 ở [trang 05b](05b-quy-trinh-va-man-hinh-qt09-18.md)).

## Cổng chất lượng trong pipeline

TL-01 và TL-04 quy định pipeline Jenkins với các cổng sau, và WEB-002 cụ thể hoá thành các stage cho frontend: `npm ci`, lint, test kèm báo cáo độ phủ cho tệp thay đổi, `build:all`, kiểm ngân sách bundle, Semgrep, gitleaks, Trivy (mã nguồn và image), SBOM CycloneDX, build image từ `deploy/web/Dockerfile`, ký bằng cosign. Ngoài ra TL-01 nêu SonarQube, OWASP Dependency-Check, `openapi-diff` để phát hiện thay đổi phá vỡ hợp đồng API và `pact can-i-deploy` trước khi triển khai. Lỗ hổng có điểm CVSS từ 7 trở lên chặn pipeline. Semgrep chặn merge khi vi phạm luật bảo mật (SEC-39); image đạt SLSA mức 3 và được ký (SEC-37, SEC-38).

Mọi cổng thất bại đều dừng pipeline. Jenkinsfile và cách chạy pipeline sẽ được mô tả ở trang này khi WEB-002 hoàn thành, theo tiêu chí của task đó. Theo hướng hợp đồng trước (ADR-32, WEB-063, WEB-064), pipeline phải có thêm hai cổng chạy trước `npm run lint` của frontend: bước `cd contracts && npm ci && npm run lint` kiểm bộ hợp đồng OpenAPI bằng Redocly, và bước `npm run api:check` trong `frontend/` báo lỗi khi kiểu sinh trong `@vt/api-client` lệch với hợp đồng. Repo hiện chưa có Jenkinsfile nên hai bước này được ghi ở đây để WEB-002 đưa vào; trong lúc chờ, người sửa hợp đồng tự chạy cả hai lệnh trước khi commit. Phép kiểm model viết tay với hợp đồng nằm trong `npm run test:all` (`api-client/src/lib/models/contract-check.spec.ts`), nên không cần cổng riêng. PERF-01, PERF-02 và PERF-21 chạy ở mỗi lần phát hành lên staging theo TL-04.

## Tiêu chí hoàn thành một task

Theo quy tắc 11 của `AGENTS.md`, một task chỉ hoàn thành khi ba điều kiện cùng đạt: lệnh `npx ng build fe-task && npx ng build fe-shell` (cùng remote mà task đụng tới) thành công; kiểm thử của task chạy xanh; màn hình đã được mở thật trên trình duyệt, bằng Playwright hoặc thủ công, không có lỗi console. Quy tắc 12 yêu cầu báo cáo trung thực: kiểm thử chưa chạy, việc chưa làm và giả định đã chọn phải ghi rõ trong báo cáo theo mẫu mục 6, không tuyên bố xong khi chưa kiểm chứng.

Ngoài ba điều kiện bắt buộc, người duyệt PR nên kiểm thêm: màn hình hoạt động ở chế độ người cao tuổi, dùng được bằng bàn phím, không vi phạm bảng kiểm ở [Bảo mật frontend](08-bao-mat-frontend.md), không vượt ngân sách kích thước, và chú thích mã ghi đúng SCR, QT (suy luận từ quy tắc 5, 9, 10 của `AGENTS.md`).

## Liên quan

- [Quy ước mã nguồn](10-quy-uoc-ma-nguon.md)
- [Hiệu năng và trải nghiệm](09-hieu-nang-va-trai-nghiem.md)
- [Bảo mật frontend](08-bao-mat-frontend.md)
- [Giao diện theo tenant](03-giao-dien-theo-tenant.md)
- [Hạ tầng dev và triển khai](12-ha-tang-dev-va-trien-khai.md)
