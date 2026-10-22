> Nguồn: `AGENTS.md` mục 2 (12 quy tắc), mục 3 (bản đồ repo), mục 4 (cách chạy), mục 5 và 6 (quy trình, mẫu báo cáo); `docs/tasks/README.md` và `WEB-015-khung-remote.md`; TL-01 mục frontend (Native Federation, registry SDUI); mã `frontend/angular.json`, `tsconfig.json`, `package.json`, `.npmrc`, `.prettierrc`, `fe-shell` (`main.ts`, `federation.config.js`, `app.routes.ts`, `remotes.ts`, `widgets/shell-widgets.ts`), `fe-task`, `@vt/sdui-renderer`; `deploy/web/`.

# Quy ước mã nguồn

Trang này dành cho người và AI agent sắp sửa mã trong `frontend/`. Nó mô tả cấu trúc repo, cách viết Angular theo chuẩn của dự án, các bẫy kỹ thuật đã gặp, và hai quy trình hay lặp lại nhất: thêm một remote mới và thêm một widget SDUI mới. Quy định gốc nằm ở `AGENTS.md`; khi trang này và `AGENTS.md` lệch nhau, `AGENTS.md` thắng.

## Cấu trúc repo

Repo `mission-web` gồm `AGENTS.md` (quy định chung, `CLAUDE.md` trỏ về đây và về `wiki/README.md`), `docs/tasks/` (danh mục task WEB-001 đến WEB-053 cho AI agent, mẫu `_MAU-TASK.md`, lời nhắc `_NHAC-QUY-DINH.md` được hook `SessionStart` trong `.claude/settings.json` in ra đầu mỗi phiên Claude Code), `wiki/` (tri thức chi tiết), `frontend/` (workspace Angular), `deploy/web/` (Dockerfile và nginx cho SVC-17), `infra/dev/` (Docker Compose hạ tầng dev), `scripts/agent-task.sh` (ghép `AGENTS.md` với một task thành prompt cho agent) và `.env.example`. Kế hoạch phát triển nằm ở `docs/KE-HOACH-PHAT-TRIEN-PHAN-HE-WEB.md`.

Trong `frontend/projects/` có mười ứng dụng (shell và chín remote, tám remote từ WEB-015 mới có trang khung) và sáu thư viện. Bảng dưới tóm vai trò từng project; chi tiết kiến trúc ở [Kiến trúc frontend](02-kien-truc-frontend.md).

| Project | Loại | Vai trò |
|---|---|---|
| `fe-shell` | Ứng dụng host | Khung, đăng nhập, menu, nạp UI Pack, trang chủ SDUI, nạp remote |
| `fe-task` | Ứng dụng remote | QT-04 đến QT-06 hiện có SCR-0503, SCR-0601 và widget `myTasksToday` |
| `fe-okr`, `fe-evidence`, `fe-appraisal`, `fe-dossier`, `fe-oversight`, `fe-dashboard`, `fe-config-studio`, `fe-admin` | Ứng dụng remote | Khung (WEB-015), mỗi remote một trang khung; màn hình do luồng Màn hình nghiệp vụ dựng |
| `api-client` | Thư viện `@vt/api-client` | Cấu hình chạy, interceptor, Problem Details, phân trang, `TaskApi`, mock |
| `auth` | Thư viện `@vt/auth` | `SessionService`, `authGuard`, `roleGuard`, `authInterceptor` |
| `design-system` | Thư viện `@vt/design-system` | Preset PrimeNG từ token, `ThemeService`, chế độ người cao tuổi |
| `sdui-renderer` | Thư viện `@vt/sdui-renderer` | Registry, `vt-sdui-outlet`, `UiPackLoader`, CEL, lọc thuộc tính |
| `form-engine` | Thư viện `@vt/form-engine` | `<vt-form>` trên Formly, luật CEL cho biểu mẫu |
| `telemetry` | Thư viện `@vt/telemetry` | `traceparentInterceptor`, `observeWebVitals()` |

Thư viện được tham chiếu qua `paths` trong `tsconfig.json` (`@vt/<lib>` trỏ tới `projects/<lib>/src/public-api.ts`), nên ứng dụng dùng mã nguồn thư viện trực tiếp, không cần build thư viện trước. Chỉ xuất ra ngoài những gì có trong `public-api.ts`. Định dạng mã theo `.prettierrc`: độ rộng 100 ký tự, nháy đơn, parser `angular` cho HTML. `.npmrc` đặt `legacy-peer-deps` vì một số gói chưa khai đúng phụ thuộc ngang hàng với Angular 21.

## Lệnh thường dùng

Cài đặt bằng `npm ci` trong `frontend/`. Chạy remote bằng `npx ng serve fe-task --port 4201`, rồi chạy shell bằng `npx ng serve fe-shell --port 4200`. Định nghĩa "đã xong" của một task theo quy tắc 11 là `npx ng build fe-task && npx ng build fe-shell` (thêm remote mà task đụng tới) thành công, kiểm thử của task chạy xanh và màn hình đã mở thật trên trình duyệt không có lỗi console. Mặc định `public/config.json` tắt đăng nhập và bật mock API, nên chạy được mà không cần hạ tầng.

Các script chất lượng do WEB-001 và WEB-003 thêm vào `frontend/package.json` gồm: `npm run lint` chạy ESLint cho toàn workspace (TS và template); `npm run test:all` chạy Vitest qua `ng test <project> --watch=false` lần lượt cho mọi thư viện, remote và shell có tệp `*.spec.ts`, project chưa có spec thì bỏ qua; `npm run build:all` build mọi remote trong `tools/remotes/remotes.json` rồi `fe-shell` (khoảng hai phút rưỡi cho mười project; đôi khi esbuild in `fatal error: all goroutines are asleep - deadlock!` sau khi một project đã build xong, thông báo này phát ra lúc tiến trình con esbuild đóng, mã thoát vẫn 0 và kết quả build đủ); `npm run format:check` kiểm Prettier (WEB-060 đã định dạng lại toàn bộ mã cũ trong một commit riêng và đưa lệnh này vào pipeline, nên mỗi task phải chạy `npm run format` trước khi commit; commit định dạng hàng loạt không trộn với thay đổi logic); `npm run perf:prepare` build rồi dựng thư mục `dist/lhci` giống bố cục nginx (remote nằm ở `remotes/<project>`), còn `npm run perf:lhci` chạy thêm Lighthouse CI theo `lighthouserc.json` (cần biến `CHROME_PATH` trỏ tới Chromium khi máy không có Chrome).

Kiểu dữ liệu API sinh từ bộ hợp đồng `contracts/openapi/` (WEB-064). Khi sửa hợp đồng, chạy `cd contracts && npm ci && npm run lint` để Redocly kiểm hợp đồng, rồi trong `frontend/` chạy `npm run api:gen` để sinh lại `projects/api-client/src/lib/generated/` (một tệp cho `common.yaml`, một tệp cho mỗi module, cùng `index.ts`) và commit tệp sinh cùng thay đổi hợp đồng; `npm run api:check` báo lỗi khi hai bên lệch nhau. Trong thư viện, import kiểu từ đúng tệp module (`import type { components } from '../generated/tasks'`); ngoài thư viện dùng bí danh của `@vt/api-client` như `TasksComponents['schemas']['TaskDraft']`. Không sửa tay tệp sinh. Model viết tay có lược đồ cùng tên trong hợp đồng phải được thêm vào `models/contract-check.spec.ts` để kiểm tương thích lúc biên dịch.

## Viết Angular theo chuẩn dự án

Quy tắc 8 của `AGENTS.md` yêu cầu Angular hiện đại. Component luôn là standalone và không tạo NgModule mới. Phụ thuộc lấy bằng `inject()` thay vì tham số constructor. Trạng thái dùng signals: `signal`, `computed`, `linkedSignal` cho giá trị phái sinh có thể sửa (ví dụ ô nhập tiến độ khởi tạo từ dữ liệu tải về trong `task-detail.page.ts`), `rxResource` cho dữ liệu tải từ API (dùng `params` và `stream`). `rxResource` có một chỗ dễ sai: khi resource ở trạng thái lỗi, gọi `value()` sẽ ném lỗi, nên template và `computed` phải kiểm `error()` hoặc `hasValue()` trước khi đọc `value()`. Đầu vào và đầu ra của component dùng `input()` và `output()`. Template dùng control flow `@if`, `@for` (luôn kèm `track`), `@switch`. Tuyến dùng `withComponentInputBinding()` nên tham số tuyến như `:id` vào thẳng `input()` cùng tên.

Mọi lời gọi API đi qua `@vt/api-client`; component không gọi `fetch` hay `HttpClient` trực tiếp mà gọi lớp API (như `TaskApi`). Thứ tự interceptor trong `app.config.ts` của shell và remote là `apiBaseUrlInterceptor`, `authInterceptor`, `idempotencyInterceptor`, `traceparentInterceptor`, cuối cùng là `mockApiInterceptor`; interceptor giả lập phải đứng cuối để mọi header đã được gắn như khi gọi máy chủ thật (xem [Quy ước API](06-quy-uoc-api.md)). Mỗi tệp trang ghi mã SCR và QT trong chú thích đầu tệp để truy vết (xem [Mã định danh và truy vết](04-ma-dinh-danh-va-truy-vet.md)). Văn bản giao diện bằng tiếng Việt có dấu, đúng thuật ngữ TL-02; nhãn trạng thái lấy từ `TASK_STATUS_LABEL` thay vì viết lại.

## Bẫy kỹ thuật đã biết

Bẫy thứ nhất là NG0600: ghi vào một signal bên trong `computed` làm Angular báo lỗi. Muốn giá trị phái sinh có thể sửa, dùng `linkedSignal`; muốn tác dụng phụ, dùng `effect`.

Bẫy thứ hai là NG0203 khi bật `features.ignoreUnusedDeps` trong `federation.config.js`. Tuỳ chọn này giúp gói dùng chung nhỏ hơn bằng cách bỏ thư viện mà host không dùng, nhưng nếu remote cần một thư viện dùng chung mà shell không import ở đâu cả, remote sẽ nạp bản riêng và Angular báo lỗi ngữ cảnh tiêm phụ thuộc. Quy tắc là thư viện dùng chung mà remote cần thì shell cũng phải dùng ít nhất một lần.

Bẫy thứ ba là `@primeuix/themes`: gói này được loại khỏi chia sẻ (`skip`) trong `federation.config.js` của shell vì nó có rất nhiều điểm vào con, chia sẻ toàn bộ làm tăng thời gian build và kích thước manifest (suy luận về lý do, cấu hình chỉ ghi quy tắc `startsWith('@primeuix/themes')`). Các gói `rxjs/ajax`, `rxjs/fetch`, `rxjs/testing`, `rxjs/webSocket` cũng bị bỏ qua.

Bẫy thứ tư nằm ở `main.ts`: tệp này chỉ được gọi `initFederation(...)` rồi `import('./bootstrap')`. Mọi import thư viện dùng chung phải nằm trong `bootstrap.ts` hoặc sau đó, nếu không thư viện sẽ được nạp trước khi Native Federation kịp lập bản đồ chia sẻ. Shell khởi tạo với `federation.manifest.json`, còn remote gọi `initFederation()` không đối số.

Bẫy thứ năm là kiểm thử khung: `app.spec.ts` sinh mặc định của hai ứng dụng từng tìm thẻ `h1` chứa "Hello, ..." không còn tồn tại nên luôn lỗi; WEB-001 đã sửa thành phép thử dựng `App` và `router-outlet`. Khi tạo ứng dụng mới cần nhớ hai điều. Thứ nhất, target `test` của ứng dụng dùng `@angular/build:unit-test` phải trỏ `buildTarget` sang `<app>:esbuild:development`, vì builder này không chạy được với builder `native-federation` của target `build`. Thứ hai, builder `unit-test` báo lỗi khi project không có tệp `*.spec.ts` nào, nên mỗi project mới cần ít nhất một spec, hoặc dựa vào `test:all` đã bỏ qua project chưa có spec.

Bẫy thứ sáu là `rxResource.value()` ném lỗi ở trạng thái lỗi (đã nêu ở mục trên): lỗi này từng làm SCR-0601 ghi ERROR ra console thay vì hiện thông báo "Không tải được nhiệm vụ" khi API trả 404 hoặc 500. Dùng `?mock-error=500` để thử nhanh màn hình mới trong trạng thái lỗi.

Bẫy thứ sáu là đọc `resource.value()` khi `rxResource` đang lỗi: lời gọi ném `ResourceValueError` và làm hỏng cả trang, kể cả khi lỗi đó chỉ là 403 hay 500 cần hiện thông báo. Bẫy hay gặp nhất là dùng thẳng `source: this.page.value` cho `linkedSignal`. Cách tránh là gom một `computed(() => res.hasValue() ? res.value() : null)` rồi mọi `computed`, `linkedSignal` đọc qua nó (ví dụ `firstPage` trong `MyTasksPage`, WEB-030). Thử bằng `?mock-error=403` trên URL trang.

Bẫy thứ bảy là `p-inputnumber` của PrimeNG 21 trong bảng nhập nhanh hoặc hộp thoại: khi rời ô bằng Tab, host binding `data-p` của nó đổi giữa hai vòng phát hiện thay đổi và Angular báo NG0100 ở chế độ dev. Với ô số đơn giản (phần trăm, số lượng), dùng `<input pInputText type="number">` và chuẩn hoá giá trị trong hàm `set…` của component, vừa nhẹ hơn vừa không lỗi (WEB-030).

Bẫy thứ tám là `linkedSignal` đặt lại trạng thái phụ (ghi chú, thông báo thành công) theo cả đối tượng: khi máy chủ trả bản mới của cùng nhiệm vụ, đối tượng đổi tham chiếu và thông báo vừa đặt bị xoá. Hãy lấy nguồn là khoá ổn định, ví dụ `computed(() => this.task().id)`.

Bẫy thứ chín là chạy `ng build` trong lúc `ng serve` của cùng workspace đang chạy: bản build ghi đè bộ đệm gói dùng chung của Native Federation và trang dev báo `ReferenceError: ngDevMode is not defined` ở shell. Chỉ cần dừng rồi chạy lại `ng serve` cả shell lẫn remote. Khi dừng, đừng dùng `pkill -f "ng serve"` hay `pgrep -f "ng serve"` trong cùng dòng lệnh có chữ `ng serve`: mẫu khớp luôn tiến trình shell đang chạy lệnh và giết chính nó; hãy lấy số tiến trình bằng `ps` rồi `kill` từng số, và chạy lệnh dừng ở một lần gọi shell riêng, không chung dòng lệnh với lệnh khởi động lại (dòng lệnh đó cũng chứa chữ `ng serve`).

Bẫy thứ mười là kiểm thử component có `p-tabs` trong Vitest với jsdom: `p-tablist` gọi `ResizeObserver` mà jsdom không có, kiểm thử báo lỗi không bắt được dù các khẳng định đều đúng. Cách xử lý là gán một lớp giả `ResizeObserver` trong `beforeAll` của tệp spec (xem `task-detail.page.spec.ts`), không sửa cấu hình kiểm thử chung.

Bẫy thứ mười một là `[(ngModel)]="form().name"` khi `form` là `signal` hoặc `linkedSignal` giữ một đối tượng: `ngModel` ghi thẳng vào thuộc tính của đối tượng nên signal không đổi tham chiếu, mọi `computed` phụ thuộc (ví dụ điều kiện bật nút "Tiếp tục") không tính lại và nút cứ bị khoá dù đã nhập đủ. Hãy tách thành `[ngModel]="form().name" (ngModelChange)="set('name', $event)"` với `set` gọi `form.update((f) => ({ ...f, [key]: value }))` (trình khởi tạo tenant SCR-0102, WEB-017).

Bẫy thứ mười hai là `[name]="'pack-' + flow"` trên phần tử có `ngModel`: `name` lúc này là input của chỉ thị `NgModel` nên Angular không đặt thuộc tính `name` lên DOM, kiểm thử Playwright tìm `[name=pack-DANG]` không thấy. Khi cần tên động trên DOM, dùng `[attr.name]`; ngoài thẻ `<form>` thì `ngModel` không cần `name`.

Bẫy thứ mười ba là `cel-js` 0.8.2 dùng trong `@vt/form-engine`: bản này chưa có hàm chuỗi `contains`, `matches`, `startsWith`, `lowerAscii`, nên biểu thức như `kr.title.contains("tăng cường")` ném lỗi và `evaluateRules` bỏ qua quy tắc trong im lặng (coi như chưa nhập). `size()`, so sánh số, `has()` và toán tử logic vẫn chạy. Quy tắc cần dò chuỗi phải được tính trước thành biến của ngữ cảnh rồi CEL chỉ so sánh biến, ví dụ SCR-0202 tính `kr.vagueWord`, `kr.taskVerb`, `kr.missing` từ danh sách từ của Policy Pack (`fe-okr/src/app/okr-rules.ts`, WEB-022). Viết kiểm thử cho từng quy tắc để phát hiện quy tắc bị bỏ qua.

Bẫy thứ mười bốn là liên kết tương đối từ tuyến có đường dẫn nhiều đoạn như `objectives/:id/edit`: trong shell, `routerLink=".."` chỉ lùi một đoạn URL (về `/okr/objectives/o-3`), không về trang cha của tuyến. Cách làm của SCR-0202 là lùi đúng số đoạn của tuyến, `route.snapshot.url.map(() => '..').join('/')`, và dùng cùng giá trị đó khi dựng URL bằng `createUrlTree`.

Bẫy thứ mười ba là thử màn hình theo vai trò khác `member`, `manager` khi chạy dev không có Keycloak. Người dùng giả lập chỉ có một phân công nên đặt `sessionStorage` `vt.assignment` không có tác dụng; phải mở trang với `?mock-org=kiem-nhiem` để API giả lập trả ba phân công, rồi chọn "Chi bộ Cơ quan" (`leader`, ví dụ SCR-0203) hoặc "Hội đồng thẩm định" (`appraiser`, cần bước lên AAL2). Kịch bản Playwright có thể đặt sẵn `vt.assignment` kèm tham số này để bỏ qua hộp chọn (WEB-023).

Bẫy thứ mười lăm là handler giả lập trả thẳng đối tượng đang giữ trong bộ nhớ của module mock (`ok(item)`): interceptor không sao chép, nên khi handler sau đó sửa tại chỗ (`Object.assign`, `d.status = …`) thì dữ liệu mà màn hình hay kiểm thử đang giữ cũng đổi theo. Hệ quả dễ thấy là kiểm `If-Match` không bao giờ trả 409 vì phiên bản phía client đã bị nâng ngầm, hoặc danh sách tự đổi trạng thái trước khi tải lại. Handler mới nên trả bản sao như khi qua mạng, ví dụ `ok(structuredClone(body))` (giả lập đồng bộ nhân sự SCR-0104, WEB-019).

Bẫy thứ mười sáu (WEB-036) là gán `[value]` cho `<select>` mà các `<option>` sinh bằng `@for`: Angular đặt `value` của `select` trước khi các `option` được tạo, nên trình duyệt chọn option đầu tiên trong khi signal vẫn giữ giá trị khác (SCR-0902 từng hiện "HTXSNV" trong lúc mức thẩm định thực là "HTTNV"). Cách tránh là đánh dấu từng option bằng `[selected]="opt === value()"` như `vt-self-rating` của WEB-035, và chỉ đọc giá trị mới qua `(change)`.

## Thêm một remote mới

Từ WEB-015, remote mới được sinh bằng `./scripts/new-remote.sh <project> <cổng> <tuyến> "<tiêu đề>" "<QT>" "<SCR>"` (chạy từ gốc repo), ví dụ `./scripts/new-remote.sh fe-okr 4202 okr "Mục tiêu và OKR" "QT-02, QT-03" "SCR-0201, SCR-0202"`. Script gọi `frontend/tools/remotes/workspace.mjs new`, sinh `projects/<project>` từ mẫu `frontend/tools/remotes/template/` (gồm `federation.config.js` công bố `./routes` và `./widgets` với `shareAll` singleton, `main.ts` chỉ gọi `initFederation`, `bootstrap.ts`, `app.config.ts` chạy độc lập với `DEFAULT_RUNTIME_CONFIG` và mock API, tệp tuyến xuất `ROUTES` và `default`, trang khung, `widgets/index.ts`, `styles.scss` và `app.spec.ts` mẫu), nhân bản cấu hình `fe-task` trong `angular.json` (build Native Federation, `esbuild` có ngân sách 180 kB, `serve` với cổng riêng, `test` trỏ `esbuild:development`), thêm tham chiếu vào `tsconfig.json`, đăng ký remote trong `frontend/tools/remotes/remotes.json` và khoá camelCase (như `feOkr`) trong `fe-shell/public/federation.manifest.json` trỏ tới cổng dev.

`remotes.json` là danh mục remote duy nhất: `npm run build:all` build mọi remote trong danh mục rồi tới `fe-shell`, `npm run test:all` chạy spec của thư viện, remote và shell, `perf:prepare` và `deploy/web/Dockerfile` dùng `workspace.mjs assemble` để ghép shell ở gốc và mỗi remote ở `remotes/<project>` như nginx phục vụ. Lúc container khởi động, `40-runtime-config.sh` đọc danh sách remote sinh lúc build (`/etc/mission-web/remotes.tsv`) và lấy URL của từng remote từ biến môi trường dạng `FE_<TEN>_URL` (ví dụ `FE_TASK_URL`, `FE_CONFIG_STUDIO_URL`), mặc định là `/remotes/<project>` cùng nguồn. Vì vậy thêm remote không cần sửa Dockerfile hay tập lệnh khởi động.

Việc còn làm tay sau khi sinh: trong `app.routes.ts` của shell thay trang "planned" của tuyến bằng `loadChildren: remoteRoutes('<khoá>')`, giữ nguyên `canMatch: [permissionGuard(...K.<nhóm>)]` và chú thích QT, SCR; `remoteRoutes()` đã xử lý lỗi nạp bằng `RemoteUnavailablePage`, nên remote hỏng không làm hỏng shell (NFR-04). Nhóm khoá của tuyến cha và mục menu khai báo ở `SECTION_KEYS` trong `layout/nav.ts`; remote mới thì thêm một nhóm bằng `screenKeys('SCR-…')` (xem mục "Gắn khoá quyền" bên dưới). Lưu ý `initFederation()` của shell tải `remoteEntry.json` của mọi remote trong manifest ngay khi khởi động để dựng import map, khoảng 2 KB gzip mỗi remote, nên mỗi remote thêm vào cũng tính vào ngân sách trang chủ.

Cổng dev đã cấp: `fe-shell` 4200, `fe-task` 4201, `fe-okr` 4202, `fe-evidence` 4203, `fe-appraisal` 4204, `fe-dossier` 4205, `fe-oversight` 4206, `fe-dashboard` 4207, `fe-config-studio` 4208, `fe-admin` 4209. Remote mới sau này lấy cổng kế tiếp; script từ chối cổng hoặc tên đã dùng.

## Gắn khoá quyền cho menu, tuyến và nút

Từ WEB-055, mọi điểm giao diện mở ra một chức năng phải gắn khoá của ma trận phân quyền ([trang 15a](15a-ma-tran-phan-quyen.md)) thay vì tự đặt danh sách vai trò (`roleGuard` đã `@deprecated`). Nút thao tác dùng `*vtCan="'SCR-qqnn.<khoá>'"` hoặc `can()` của `PermissionService` (remote có thể bọc lại như `TASK_CAN` của `fe-task` để kiểm thử thay được), kết hợp điều kiện theo trạng thái và người phụ trách của bảng chức năng TL-02 ngay trong template. Tuyến con của từng SCR trong remote gắn `canActivate: [permissionGuard(...screenKeys('SCR-qqnn'))]`; dùng `canActivate` chứ không dùng `canMatch` ở tuyến con, vì tuyến tĩnh như `assign` bị `canMatch` từ chối sẽ rơi xuống khớp tuyến động `:id`. Tuyến cha trong shell dùng `canMatch` để không tải remote khi bị chặn. Bị chặn thì guard chuyển sang `/khong-co-quyen`; remote chạy riêng đặt `VT_FORBIDDEN_URL` là `null` để guard chỉ trả `false`. Nếu chức năng cần gắn chưa có khoá trong ma trận thì bổ sung dòng vào `docs/phan-quyen/ma-tran.json`, chạy `python3 docs/phan-quyen/gen_matrix.py` rồi mới dùng khoá mới, không viết khoá tự đặt trong mã.

Khi chạy giả lập (`auth.enabled = false`), vai trò mặc định là `member` và `manager`; muốn thử giao diện của vai trò khác thì đặt `sessionStorage.setItem('vt.devRoles', 'inspector')` (nhiều vai trò cách nhau dấu phẩy) rồi tải lại trang. Kiểm thử đầu cuối của WEB-057 dùng cùng cơ chế này.

## Thêm một widget SDUI mới

Widget là component mà UI Pack của tenant đặt lên trang qua tên trong registry (xem [Giao diện theo tenant](03-giao-dien-theo-tenant.md)). Bước một, viết component standalone, nhận cấu hình bằng `input()`, không tự gọi API ngoài `@vt/api-client`, chịu được thiếu dữ liệu và hoạt động ở chế độ người cao tuổi. Nếu widget thuộc một remote, xuất nó từ tệp `widgets/index.ts` của remote, được công bố qua `'./widgets'`.

Bước hai, khai một `RegistryEntry` với `name` dạng `vt.widget.<tenWidget>`, hàm `load()` nạp lười component (từ remote thì dùng `loadRemoteModule('<khoa>', './widgets')`), `propsSchema` liệt kê đủ thuộc tính kèm kiểu, giới hạn, enum hoặc mẫu và đặt `additionalProperties: false`, và `since` là phiên bản registry đầu tiên có widget. Widget của remote được khai trong `SHELL_WIDGETS` của shell để shell biết tên trước khi nạp remote; widget dùng chung đặt trong thư viện thì khai bằng `provideSduiComponents([...])`.

Bước ba, tăng `REGISTRY_VERSION` trong `component-registry.ts` theo semver (thêm widget là tăng số giữa) và đăng ký widget vào bảng `config.component_registry` qua phía máy chủ, vì đây là danh mục dùng chung toàn nền tảng. UI Pack nào dùng widget mới phải khai `requires.registry` tương ứng. Bước bốn, thêm widget vào bố cục của UI Pack mặc định nếu cần, viết kiểm thử cho bộ lọc thuộc tính và cho biểu thức `visibleWhen`, rồi chạy kiểm thử giao diện theo bộ token (xem [Kiểm thử và chất lượng](11-kiem-thu-va-chat-luong.md)).

## Gắn cột tuỳ chọn và bộ lọc đã lưu cho một danh sách

Từ CNH-04, mọi danh sách dùng chung một cơ chế: `createListPersonalization(screenCode, columns, codec)` của `@vt/api-client` (gọi trong ngữ cảnh tiêm, thường là trường của component) giữ cột đang hiện và bộ lọc đã lưu của người dùng, lưu qua API tuỳ chọn cá nhân của CNH-02 (khoá `ui.table.SCR-xxxx`, `/config/me/saved-filters`) kèm `If-Match`; giao diện là `<vt-column-picker>` và `<vt-saved-filters>` của `@vt/design-system`. Màn hình chỉ phải làm ba việc. Thứ nhất, khai danh mục cột (`ListColumnDef`: mã cột, nhãn, `required` cho cột luôn hiện) và bọc từng ô `th`, `td` bằng `@if (list.shows('ma'))`. Thứ hai, viết `FilterCodec` chuyển trạng thái lọc sang `SavedFilterCriteria` và ngược lại; tiêu chí chỉ chứa mã trạng thái, mã định danh, khoảng ngày, khoảng số, cờ và sắp xếp, nên từ khoá tìm kiếm tự do không bao giờ được lưu (tránh dữ liệu C2), và `sanitizeCriteria` còn lọc lại lần nữa trước khi gửi. Điều kiện không còn hợp lệ được đếm vào `dropped` để báo nhẹ cho người dùng. Thứ ba, đưa trạng thái lọc lên URL bằng tham số `loc` (`encodeCriteria`, `decodeCriteria`) để chia sẻ đường dẫn trong nội bộ; đường dẫn chia sẻ thắng bộ lọc mặc định đã lưu, còn quyền xem dữ liệu vẫn do máy chủ quyết định. Ba màn hình đã gắn: "Việc của tôi" (SCR-0503), "Minh chứng của tôi" (SCR-0701) và danh sách đơn vị triển khai (SCR-0101).

Bẫy đã gặp: không đặt `[disabled]` theo trạng thái đang lưu cho ô chọn cột, vì ô đang có tiêu điểm bị vô hiệu hoá sẽ đẩy tiêu điểm ra `body` và phím Esc không còn đóng được bảng chọn. Ở chế độ giả lập, tuỳ chọn và bộ lọc chỉ sống trong bộ nhớ của trang nên tải lại trang là mất; trên máy chủ thật chúng theo người dùng sang mọi thiết bị.

## Quy trình task, nhánh và commit

Theo `AGENTS.md` mục 5, mỗi task làm trên nhánh `feat/WEB-xxx-<ten-ngan>`, thông điệp commit dạng `WEB-xxx: <mô tả>` và ghi mã SCR, QT liên quan trong thân. Chỉ sửa tệp trong mục "Tệp được phép sửa" của task. Task kết thúc bằng báo cáo theo mẫu mục 6 gồm Kết quả, Truy vết (QT, SCR, API, MOD), Kiểm chứng, Giả định đã chọn, Chưa làm hoặc rủi ro, Cập nhật wiki; sau đó cập nhật trạng thái task trong `docs/tasks/README.md`. Task tạo ra tri thức mới (quy ước, bẫy, quyết định) phải cập nhật trang wiki tương ứng.

## Liên quan

- [Kiến trúc frontend](02-kien-truc-frontend.md)
- [Giao diện theo tenant](03-giao-dien-theo-tenant.md)
- [Mã định danh và truy vết](04-ma-dinh-danh-va-truy-vet.md)
- [Quy ước API](06-quy-uoc-api.md)
- [Kiểm thử và chất lượng](11-kiem-thu-va-chat-luong.md)
- [Hạ tầng dev và triển khai](12-ha-tang-dev-va-trien-khai.md)
