> Nguồn: `AGENTS.md` mục 2 (12 quy tắc), mục 3 (bản đồ repo), mục 4 (cách chạy), mục 5 và 6 (quy trình, mẫu báo cáo); `docs/tasks/README.md` và `WEB-015-khung-remote.md`; TL-01 mục frontend (Native Federation, registry SDUI); mã `frontend/angular.json`, `tsconfig.json`, `package.json`, `.npmrc`, `.prettierrc`, `fe-shell` (`main.ts`, `federation.config.js`, `app.routes.ts`, `remotes.ts`, `widgets/shell-widgets.ts`), `fe-task`, `@vt/sdui-renderer`; `deploy/web/`.

# Quy ước mã nguồn

Trang này dành cho người và AI agent sắp sửa mã trong `frontend/`. Nó mô tả cấu trúc repo, cách viết Angular theo chuẩn của dự án, các bẫy kỹ thuật đã gặp, và hai quy trình hay lặp lại nhất: thêm một remote mới và thêm một widget SDUI mới. Quy định gốc nằm ở `AGENTS.md`; khi trang này và `AGENTS.md` lệch nhau, `AGENTS.md` thắng.

## Cấu trúc repo

Repo `mission-web` gồm `AGENTS.md` (quy định chung, `CLAUDE.md` trỏ về đây và về `wiki/README.md`), `docs/tasks/` (danh mục task WEB-001 đến WEB-053 cho AI agent, mẫu `_MAU-TASK.md`, lời nhắc `_NHAC-QUY-DINH.md` được hook `SessionStart` trong `.claude/settings.json` in ra đầu mỗi phiên Claude Code), `wiki/` (tri thức chi tiết), `frontend/` (workspace Angular), `deploy/web/` (Dockerfile và nginx cho SVC-17), `infra/dev/` (Docker Compose hạ tầng dev), `scripts/agent-task.sh` (ghép `AGENTS.md` với một task thành prompt cho agent) và `.env.example`. Kế hoạch phát triển nằm ở `docs/KE-HOACH-PHAT-TRIEN-PHAN-HE-WEB.md`.

Trong `frontend/projects/` có hai ứng dụng và sáu thư viện. Bảng dưới tóm vai trò từng project; chi tiết kiến trúc ở [Kiến trúc frontend](02-kien-truc-frontend.md).

| Project | Loại | Vai trò |
|---|---|---|
| `fe-shell` | Ứng dụng host | Khung, đăng nhập, menu, nạp UI Pack, trang chủ SDUI, nạp remote |
| `fe-task` | Ứng dụng remote | QT-04 đến QT-06 hiện có SCR-0503, SCR-0601 và widget `myTasksToday` |
| `api-client` | Thư viện `@vt/api-client` | Cấu hình chạy, interceptor, Problem Details, phân trang, `TaskApi`, mock |
| `auth` | Thư viện `@vt/auth` | `SessionService`, `authGuard`, `roleGuard`, `authInterceptor` |
| `design-system` | Thư viện `@vt/design-system` | Preset PrimeNG từ token, `ThemeService`, chế độ người cao tuổi |
| `sdui-renderer` | Thư viện `@vt/sdui-renderer` | Registry, `vt-sdui-outlet`, `UiPackLoader`, CEL, lọc thuộc tính |
| `form-engine` | Thư viện `@vt/form-engine` | `<vt-form>` trên Formly, luật CEL cho biểu mẫu |
| `telemetry` | Thư viện `@vt/telemetry` | `traceparentInterceptor`, `observeWebVitals()` |

Thư viện được tham chiếu qua `paths` trong `tsconfig.json` (`@vt/<lib>` trỏ tới `projects/<lib>/src/public-api.ts`), nên ứng dụng dùng mã nguồn thư viện trực tiếp, không cần build thư viện trước. Chỉ xuất ra ngoài những gì có trong `public-api.ts`. Định dạng mã theo `.prettierrc`: độ rộng 100 ký tự, nháy đơn, parser `angular` cho HTML. `.npmrc` đặt `legacy-peer-deps` vì một số gói chưa khai đúng phụ thuộc ngang hàng với Angular 21.

## Lệnh thường dùng

Cài đặt bằng `npm ci` trong `frontend/`. Chạy remote bằng `npx ng serve fe-task --port 4201`, rồi chạy shell bằng `npx ng serve fe-shell --port 4200`. Định nghĩa "đã xong" của một task theo quy tắc 11 là `npx ng build fe-task && npx ng build fe-shell` (thêm remote mà task đụng tới) thành công, kiểm thử của task chạy xanh và màn hình đã mở thật trên trình duyệt không có lỗi console. Mặc định `public/config.json` tắt đăng nhập và bật mock API, nên chạy được mà không cần hạ tầng.

## Viết Angular theo chuẩn dự án

Quy tắc 8 của `AGENTS.md` yêu cầu Angular hiện đại. Component luôn là standalone và không tạo NgModule mới. Phụ thuộc lấy bằng `inject()` thay vì tham số constructor. Trạng thái dùng signals: `signal`, `computed`, `linkedSignal` cho giá trị phái sinh có thể sửa (ví dụ ô nhập tiến độ khởi tạo từ dữ liệu tải về trong `task-detail.page.ts`), `rxResource` cho dữ liệu tải từ API (dùng `params` và `stream`). Đầu vào và đầu ra của component dùng `input()` và `output()`. Template dùng control flow `@if`, `@for` (luôn kèm `track`), `@switch`. Tuyến dùng `withComponentInputBinding()` nên tham số tuyến như `:id` vào thẳng `input()` cùng tên.

Mọi lời gọi API đi qua `@vt/api-client`; component không gọi `fetch` hay `HttpClient` trực tiếp mà gọi lớp API (như `TaskApi`). Mỗi tệp trang ghi mã SCR và QT trong chú thích đầu tệp để truy vết (xem [Mã định danh và truy vết](04-ma-dinh-danh-va-truy-vet.md)). Văn bản giao diện bằng tiếng Việt có dấu, đúng thuật ngữ TL-02; nhãn trạng thái lấy từ `TASK_STATUS_LABEL` thay vì viết lại.

## Bẫy kỹ thuật đã biết

Bẫy thứ nhất là NG0600: ghi vào một signal bên trong `computed` làm Angular báo lỗi. Muốn giá trị phái sinh có thể sửa, dùng `linkedSignal`; muốn tác dụng phụ, dùng `effect`.

Bẫy thứ hai là NG0203 khi bật `features.ignoreUnusedDeps` trong `federation.config.js`. Tuỳ chọn này giúp gói dùng chung nhỏ hơn bằng cách bỏ thư viện mà host không dùng, nhưng nếu remote cần một thư viện dùng chung mà shell không import ở đâu cả, remote sẽ nạp bản riêng và Angular báo lỗi ngữ cảnh tiêm phụ thuộc. Quy tắc là thư viện dùng chung mà remote cần thì shell cũng phải dùng ít nhất một lần.

Bẫy thứ ba là `@primeuix/themes`: gói này được loại khỏi chia sẻ (`skip`) trong `federation.config.js` của shell vì nó có rất nhiều điểm vào con, chia sẻ toàn bộ làm tăng thời gian build và kích thước manifest (suy luận về lý do, cấu hình chỉ ghi quy tắc `startsWith('@primeuix/themes')`). Các gói `rxjs/ajax`, `rxjs/fetch`, `rxjs/testing`, `rxjs/webSocket` cũng bị bỏ qua.

Bẫy thứ tư nằm ở `main.ts`: tệp này chỉ được gọi `initFederation(...)` rồi `import('./bootstrap')`. Mọi import thư viện dùng chung phải nằm trong `bootstrap.ts` hoặc sau đó, nếu không thư viện sẽ được nạp trước khi Native Federation kịp lập bản đồ chia sẻ. Shell khởi tạo với `federation.manifest.json`, còn remote gọi `initFederation()` không đối số.

Bẫy thứ năm là kiểm thử khung: `app.spec.ts` của cả hai ứng dụng còn là mã sinh mặc định, trong đó phép thử "should render title" tìm thẻ `h1` chứa "Hello, fe-task" hoặc "Hello, fe-shell", trong khi `App` của hai ứng dụng không còn thẻ này, nên phép thử nhiều khả năng sẽ lỗi khi chạy (suy luận từ đọc mã, chưa chạy). Thiết lập ESLint và Vitest cho toàn workspace thuộc task WEB-001.

Bẫy thứ sáu là đọc `resource.value()` khi `rxResource` đang lỗi: lời gọi ném `ResourceValueError` và làm hỏng cả trang, kể cả khi lỗi đó chỉ là 403 hay 500 cần hiện thông báo. Bẫy hay gặp nhất là dùng thẳng `source: this.page.value` cho `linkedSignal`. Cách tránh là gom một `computed(() => res.hasValue() ? res.value() : null)` rồi mọi `computed`, `linkedSignal` đọc qua nó (ví dụ `firstPage` trong `MyTasksPage`, WEB-030). Thử bằng `?mock-error=403` trên URL trang.

Bẫy thứ bảy là `p-inputnumber` của PrimeNG 21 trong bảng nhập nhanh hoặc hộp thoại: khi rời ô bằng Tab, host binding `data-p` của nó đổi giữa hai vòng phát hiện thay đổi và Angular báo NG0100 ở chế độ dev. Với ô số đơn giản (phần trăm, số lượng), dùng `<input pInputText type="number">` và chuẩn hoá giá trị trong hàm `set…` của component, vừa nhẹ hơn vừa không lỗi (WEB-030).

Bẫy thứ tám là `linkedSignal` đặt lại trạng thái phụ (ghi chú, thông báo thành công) theo cả đối tượng: khi máy chủ trả bản mới của cùng nhiệm vụ, đối tượng đổi tham chiếu và thông báo vừa đặt bị xoá. Hãy lấy nguồn là khoá ổn định, ví dụ `computed(() => this.task().id)`.

Bẫy thứ chín là chạy `ng build` trong lúc `ng serve` của cùng workspace đang chạy: bản build ghi đè bộ đệm gói dùng chung của Native Federation và trang dev báo `ReferenceError: ngDevMode is not defined` ở shell. Chỉ cần dừng rồi chạy lại `ng serve` cả shell lẫn remote.

## Thêm một remote mới

Task WEB-015 sẽ viết script sinh remote và dựng khung tám remote `fe-okr`, `fe-evidence`, `fe-appraisal`, `fe-dossier`, `fe-oversight`, `fe-dashboard`, `fe-config-studio`, `fe-admin` với cổng dev 4202 đến 4209. Cho tới khi có script, các bước làm tay như dưới đây, lấy `fe-task` làm mẫu.

Bước một, tạo project ứng dụng trong `angular.json` theo cùng cấu trúc với `fe-task`: target `build` dùng `@angular-architects/native-federation:build`, target `esbuild` dùng `@angular/build:application`, `serve` qua Native Federation với cổng riêng, `test` dùng `@angular/build:unit-test`. Đặt ngân sách kích thước theo 180 KB gzip cho lần tải đầu (xem [Hiệu năng và trải nghiệm](09-hieu-nang-va-trai-nghiem.md)).

Bước hai, viết `federation.config.js` với `name` là tên project, `exposes` gồm `'./routes'` trỏ tới tệp tuyến (xuất `default` hoặc hằng `ROUTES`) và `'./widgets'` nếu remote cung cấp widget; dùng `shareAll` singleton giống `fe-task`. Tạo `main.ts`, `bootstrap.ts` và `app.config.ts` để chạy độc lập với `DEFAULT_RUNTIME_CONFIG` và mock API.

Bước ba, khai remote trong `fe-shell/public/federation.manifest.json` với khoá dạng camelCase (như `feTask`) trỏ tới `remoteEntry.json` của remote, rồi trong `app.routes.ts` của shell thay trang "planned" của tuyến tương ứng bằng `loadChildren: () => remoteRoutes('<khoa>')`, giữ nguyên `roleGuard` và chú thích QT, SCR. `remoteRoutes()` đã xử lý lỗi nạp bằng `RemoteUnavailablePage`, nên remote hỏng không làm hỏng shell (NFR-04). Kiểm tra mục menu trong `layout/nav.ts` có đúng vai trò.

Bước bốn, cập nhật triển khai: thêm lệnh build vào `deploy/web/Dockerfile` và chép kết quả vào `html/remotes/<project>`, thêm biến URL của remote vào `40-runtime-config.sh` để sinh manifest lúc khởi động container (giống `FE_TASK_URL`). Cấu hình đệm của nginx đã áp chung cho mọi `remoteEntry.json`.

## Thêm một widget SDUI mới

Widget là component mà UI Pack của tenant đặt lên trang qua tên trong registry (xem [Giao diện theo tenant](03-giao-dien-theo-tenant.md)). Bước một, viết component standalone, nhận cấu hình bằng `input()`, không tự gọi API ngoài `@vt/api-client`, chịu được thiếu dữ liệu và hoạt động ở chế độ người cao tuổi. Nếu widget thuộc một remote, xuất nó từ tệp `widgets/index.ts` của remote, được công bố qua `'./widgets'`.

Bước hai, khai một `RegistryEntry` với `name` dạng `vt.widget.<tenWidget>`, hàm `load()` nạp lười component (từ remote thì dùng `loadRemoteModule('<khoa>', './widgets')`), `propsSchema` liệt kê đủ thuộc tính kèm kiểu, giới hạn, enum hoặc mẫu và đặt `additionalProperties: false`, và `since` là phiên bản registry đầu tiên có widget. Widget của remote được khai trong `SHELL_WIDGETS` của shell để shell biết tên trước khi nạp remote; widget dùng chung đặt trong thư viện thì khai bằng `provideSduiComponents([...])`.

Bước ba, tăng `REGISTRY_VERSION` trong `component-registry.ts` theo semver (thêm widget là tăng số giữa) và đăng ký widget vào bảng `config.component_registry` qua phía máy chủ, vì đây là danh mục dùng chung toàn nền tảng. UI Pack nào dùng widget mới phải khai `requires.registry` tương ứng. Bước bốn, thêm widget vào bố cục của UI Pack mặc định nếu cần, viết kiểm thử cho bộ lọc thuộc tính và cho biểu thức `visibleWhen`, rồi chạy kiểm thử giao diện theo bộ token (xem [Kiểm thử và chất lượng](11-kiem-thu-va-chat-luong.md)).

## Quy trình task, nhánh và commit

Theo `AGENTS.md` mục 5, mỗi task làm trên nhánh `feat/WEB-xxx-<ten-ngan>`, thông điệp commit dạng `WEB-xxx: <mô tả>` và ghi mã SCR, QT liên quan trong thân. Chỉ sửa tệp trong mục "Tệp được phép sửa" của task. Task kết thúc bằng báo cáo theo mẫu mục 6 gồm Kết quả, Truy vết (QT, SCR, API, MOD), Kiểm chứng, Giả định đã chọn, Chưa làm hoặc rủi ro, Cập nhật wiki; sau đó cập nhật trạng thái task trong `docs/tasks/README.md`. Task tạo ra tri thức mới (quy ước, bẫy, quyết định) phải cập nhật trang wiki tương ứng.

## Liên quan

- [Kiến trúc frontend](02-kien-truc-frontend.md)
- [Giao diện theo tenant](03-giao-dien-theo-tenant.md)
- [Mã định danh và truy vết](04-ma-dinh-danh-va-truy-vet.md)
- [Quy ước API](06-quy-uoc-api.md)
- [Kiểm thử và chất lượng](11-kiem-thu-va-chat-luong.md)
- [Hạ tầng dev và triển khai](12-ha-tang-dev-va-trien-khai.md)
