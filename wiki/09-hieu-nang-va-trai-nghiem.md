> Nguồn: Baseline mục 3 (NFR-03, NFR-11); TL-01 mục frontend (ngân sách kích thước, thời gian tải ước tính, CDN, Faro, WCAG, chế độ người cao tuổi); TL-04 mục SLO (SLO-PAGE, SLO-LCP), mục PERF (PERF-21 đến PERF-24), OPS-11; mã `angular.json`, `@vt/telemetry`, `@vt/design-system/theme.service.ts`, UI Pack `default/1.0.0/pack.json`.

# Hiệu năng và trải nghiệm

Phân hệ web phục vụ khoảng 6 triệu tài khoản, nhiều người dùng máy cấu hình thấp hoặc mạng 4G, và một phần đáng kể là cán bộ lớn tuổi. Vì vậy hiệu năng và khả năng tiếp cận không phải phần "làm sau" mà là ràng buộc thiết kế có ngân sách đo được và được kiểm ở mỗi bản build.

## Mục tiêu cấp dịch vụ phía người dùng

Bảng dưới gom các chỉ tiêu ảnh hưởng trực tiếp tới giao diện. NFR là yêu cầu gốc trong baseline; SLO là cách đo trong vận hành; PERF là kịch bản thử tải chứng minh trước khi phát hành.

| Mã | Chỉ tiêu |
|---|---|
| NFR-03 | Đọc p95 ≤300 ms, ghi p95 ≤800 ms, tải trang p95 ≤1 s, LCP ≤2,5 s trên 4G, tác vụ nặng trả mã theo dõi ≤200 ms |
| SLO-PAGE | Ít nhất 95% lượt tải trang hoàn tất trong 1 giây, đo bằng RUM |
| SLO-LCP | Ít nhất 75% lượt có LCP không quá 2,5 giây, cửa sổ 28 ngày |
| SLO-READ | Ít nhất 95% GET không quá 300 ms |
| SLO-WRITE | Ghi đồng bộ không quá 800 ms |
| SLO-ASYNC | Ít nhất 99% yêu cầu bất đồng bộ trả 202 trong 200 ms |
| PERF-21 | Lighthouse CI và WebPageTest 4G mỗi bản build: LCP ≤2,5 s, shell ≤250 KB gzip, form 50 trường ≤50 ms |
| PERF-22 | 60.000 kết nối SSE, p95 phân phát ≤2 s, nối lại ≤10 s |
| PERF-23 | Đăng nhập 100/s, làm mới token 500/s, p95 ≤1,5 s |
| PERF-24 | Dashboard 300 truy vấn/s, p95 ≤1 s |
| NFR-11 | WCAG 2.1 AA; chế độ "Đảng viên cao tuổi" (chữ ≥17 px, tương phản cao, ít bước); thao tác thường ngày ≤3 chạm |

SLO-ASYNC giải thích vì sao nhiều thao tác nặng (khoá kỳ, ký lô, sinh hồ sơ) trả 202 kèm mã tác vụ: giao diện phải hiển thị trạng thái đang xử lý và nhận kết quả qua SSE hoặc thăm dò, không chờ đồng bộ. Ngân sách lỗi theo OPS-19: nếu tiêu quá 50% ngân sách trước ngày 15 của tháng thì dừng phát hành tính năng để ưu tiên ổn định.

## Ngân sách kích thước

TL-01 đặt ngân sách: shell cùng design system không quá 250 KB nén gzip (đây cũng là ngưỡng PERF-21), mỗi remote khi tải đầu không quá 180 KB gzip, LCP không quá 2,5 giây trên 4G, INP không quá 200 ms, CSS biên dịch của một UI Pack không quá 30 KB (OPS-11 cũng kiểm ngưỡng này). TL-01 ước tính lần tải đầu trên 4G khoảng 1,4 giây và lưu lượng CDN khoảng 1,2 MB mỗi người dùng mới, tổng khoảng 72 TB khi phủ toàn bộ (các số là ước tính của TL-01, không phải số đo).

Từ WEB-003, cấu hình `production` trong `angular.json` đặt ngân sách: `fe-shell` cảnh báo ở 200 kB và lỗi ở 250 kB cho gói khởi tạo (`anyScript` lỗi ở 250 kB), `fe-task` cảnh báo ở 150 kB và lỗi ở 180 kB (`anyScript` lỗi ở 180 kB), style của từng component vẫn là 4 kB và 8 kB. Angular đo kích thước thô chưa nén nên các ngưỡng này chặt hơn ngưỡng gzip của TL-01. Tuy vậy ngân sách của Angular không tính các gói dùng chung do Native Federation tách riêng (Angular, PrimeNG, RxJS…), mà đây lại là phần lớn nhất của lần tải đầu. Vì thế chốt chặn thật là Lighthouse CI: `lighthouserc.json` giả lập 4G (CPU chậm 4 lần, chạy 3 lần cho `/` và `/tasks`) và đặt ngưỡng LCP ≤ 2,5 s, CLS ≤ 0,1, điểm accessibility ≥ 0,95, `resource-summary:total:size` ≤ 250 KB cho `/` và ≤ 430 KB cho `/tasks` (250 KB của shell cộng 180 KB của remote). Chạy bằng `npm run perf:lhci`.

Số đo mốc trước tối ưu, lấy khi hoàn thành WEB-003 (bản build production, Lighthouse giả lập 4G): trang chủ của shell tải 546 KB gzip với 162 tệp JS; `/tasks` tải thêm 86 KB của `fe-task`; LCP 8,5 s ở `/` và 9,1 s ở `/tasks`; điểm accessibility 0,82 ở `/` và 0,84 ở `/tasks`; CLS 0,199 ở `/` do `p-card` của widget trang chủ chưa giữ chỗ chiều cao. Các số này chưa đạt ngân sách; sửa khả năng truy cập nền thuộc WEB-059, tối ưu kích thước và LCP thuộc WEB-052, task này yêu cầu ghi số liệu sau tối ưu vào trang này.

Số đo trước và sau WEB-059 (bản build production gộp bằng `workspace.mjs assemble`, Lighthouse CI theo `lighthouserc.json`, Chromium 1194, số của lần chạy đại diện). Cột "trước" đo trên main `c4f67db` cộng WEB-006 và WEB-010; cột "sau" đo sau khi rebase lên main `8784429`, nên tổng tải tăng thêm khoảng 32 KB là do các task khác gộp vào main trong lúc đó, không do WEB-059:

| Chỉ số | `/` trước | `/` sau | `/tasks` trước | `/tasks` sau |
|---|---|---|---|---|
| Accessibility | 0,82 | 1,00 | 0,97 | 1,00 |
| CLS | 0,427 (ba lần: 0,235; 0,463; 0,427) | 0,001 | 0,000 | 0,000 |
| LCP | 7,9 s | 8,1 s | 8,6 s | 8,8 s |
| Tổng tải | 651 KB | 682 KB | 718 KB | 751 KB |

Quét axe-core (wcag2a, wcag2aa, wcag21a, wcag21aa, best-practice) trên trang chủ, Việc của tôi và Chi tiết nhiệm vụ ở cả chế độ thường và cao tuổi: trước có 52 nút lỗi critical hoặc serious (`aria-allowed-attr`, `aria-valid-attr-value`, `aria-progressbar-name` của `p-progressbar` và `color-contrast`), sau còn 0. Khung xem nhanh (SCR-0601) và hộp Cập nhật tiến độ có `p-slider` với `aria-label` từ trước, axe không báo lỗi. LCP và tổng tải vẫn vượt ngân sách như số mốc WEB-003; phần này thuộc WEB-052. Một nguồn tải đáng chú ý cho WEB-052: `app.config.ts` của shell gọi `provideFormEngine()` nên trang chủ đã tải Formly, `@ngx-formly/core/json-schema` và `@vt/form-engine` (khoảng 32 KB gzip) dù chưa có biểu mẫu nào; cel-js (47 KB gzip) thì trang chủ vẫn cần cho `visibleWhen` của SDUI.

## Cách giữ trang nhanh

Các kỹ thuật dưới đây đã có trong mã hoặc được TL-01 yêu cầu. Mỗi remote nạp lười theo tuyến qua `remoteRoutes()`, nên người chỉ xem nhiệm vụ không tải mã OKR hay thẩm định. Widget SDUI nạp lười qua hàm `load()` của `RegistryEntry`, kể cả widget nằm trong remote khác. Thư viện dùng chung là singleton qua Native Federation, nên Angular và PrimeNG chỉ tải một lần. UI Pack được lưu trong IndexedDB và áp ngay khi khởi động, trong khi bản mới được kiểm ở nền; `config.json`, `current.json`, `remoteEntry.json` và manifest có thời gian đệm 60 giây, còn tệp có băm trong tên được đệm một năm với `immutable`.

Danh sách dùng phân trang con trỏ với giới hạn tối đa 200 (`MAX_PAGE_LIMIT`), tìm kiếm có `debounceTime`, và giao diện theo dõi tiến độ dùng SSE thay vì thăm dò dày. Biểu mẫu 50 trường phải phản hồi trong 50 ms; theo TL-01, mỗi biểu thức CEL nên đánh giá trong 5 ms, và `cel-context.ts` giới hạn độ dài biểu thức 512 ký tự, có bộ nhớ đệm kết quả phân tích cú pháp. INP mục tiêu không quá 200 ms theo mục "Ngân sách hiệu năng" của TL-01 (được nhắc lại ở quy tắc 10 của `AGENTS.md`).

**Đo biểu mẫu động (WEB-010).** Trên Chromium 1194 trong container phát triển, bản build production, biểu mẫu thử 50 trường (5 nhóm, đủ năm loại widget) dựng hết khoảng 92 đến 102 ms (trung vị 8 lần, bỏ lần đầu), tức CHƯA ĐẠT ngân sách 50 ms của TL-01 và PERF-21; khi giả lập CPU chậm 4 lần là khoảng 380 đến 540 ms. Cùng điều kiện, Angular dựng 50 ô nhập thuần mất khoảng 2,3 ms và `FormlyJsonschema.toFieldConfig` mất 0,15 ms, nên gần hết thời gian nằm ở lúc Formly dựng trường (mỗi trường có nhiều bộ quan sát thuộc tính và đăng ký `valueChanges`), không ở schema hay widget. Bỏ wrapper của trường không cải thiện rõ. Hướng xử lý cần nhóm quyết: dựng dần theo nhóm trường, hoặc bộ dựng nhẹ không qua Formly cho biểu mẫu lớn (ADR-05 đang chọn Formly). Đánh giá 50 quy tắc CEL mất khoảng 0,3 đến 0,4 ms một lần (đạt ngân sách 5 ms). Test `benchmark.spec.ts` chạy trong jsdom, vốn chậm hơn trình duyệt nhiều lần (khoảng 220 đến 350 ms cho cùng biểu mẫu), nên chỉ dùng để chặn hồi quy.

## Đo lường người dùng thật

ADR-12 chọn Grafana Faro cho đo lường phía trình duyệt. TL-01 quy định Faro thu LCP, INP, CLS, lỗi JavaScript, thời gian tải từng remote và span truy vết nối với backend; dữ liệu gửi tới receiver Faro của OTel Collector qua APISIX với giới hạn tốc độ riêng; lấy mẫu 10% phiên, riêng phiên có lỗi lấy 100%; không thu nội dung người dùng nhập. Hiện mã mới có `observeWebVitals()` dùng `PerformanceObserver` cho LCP và INP, và `traceparentInterceptor` tự sinh header W3C `traceparent`; chú thích trong mã ghi rằng cả hai sẽ được thay bằng OTel và Faro. `RuntimeConfig` đã có trường tuỳ chọn `telemetry` gồm `otlpEndpoint` và `sampleRatio` để chuẩn bị.

## Khả năng tiếp cận và người cao tuổi

NFR-11 yêu cầu WCAG 2.1 AA cho mọi màn hình, và `AGENTS.md` nhắc lại yêu cầu này cùng chế độ người cao tuổi. Thực hành cụ thể: dùng phần tử HTML có ngữ nghĩa và nhãn cho mọi trường; mọi thao tác dùng được bằng bàn phím; shell có liên kết bỏ qua tới `main#main`; tương phản tối thiểu 4,5:1 (kiểm tĩnh trên token và kiểm bằng axe-core, xem [Kiểm thử và chất lượng](11-kiem-thu-va-chat-luong.md)); trạng thái không chỉ thể hiện bằng màu.

Từ WEB-059 có ba quy ước nền cho màn hình mới. Một là thanh tiến độ dùng `<vt-progress-bar [value] [label]>` của `@vt/design-system`, không dùng `p-progressbar`: PrimeNG 21 gắn `aria-level="72%"` lên phần tử `role=progressbar` và không nhận tên, axe báo lỗi critical. Thành phần mới có `aria-valuenow`, `aria-valuetext`, `aria-label` và hiện phần trăm bằng chữ tối bên cạnh thanh. Hai là màu chữ và nền nút dùng `--vt-color-primary-strong` (xem [Giao diện theo tenant](03-giao-dien-theo-tenant.md)); `--vt-color-primary` chỉ dùng cho viền, thanh, điểm nhấn không mang chữ. Ba là widget SDUI ở trang chủ được bọc trong ô giữ chỗ `widget-slots.ts` của shell: ô gói sẵn trong shell, có `min-height` ước theo nội dung bằng rem (nên tự tăng ở chế độ cao tuổi), rồi mới nạp widget thật vào bên trong. Nhờ vậy các widget nạp lười hoặc nạp từ remote xuất hiện cùng lúc mà không đẩy nhau, CLS trang chủ trên điện thoại giảm từ khoảng 0,44 xuống 0,001. Widget mới cho trang chủ cần thêm một lớp giữ chỗ tương tự, và widget có danh sách nên giữ chỗ đủ số dòng tối đa (như `vt.widget.myTasksToday`) để liên kết cuối thẻ không nhảy khi dữ liệu về.

Chế độ người cao tuổi (`elderly`) được bật bằng nút "Chữ lớn" trên thanh trên cùng, có `aria-pressed`. UI Pack mặc định định nghĩa chế độ này bằng chữ thân 18px thay vì 14px, màu chữ đen tuyệt đối, cờ `reducedSteps` và vùng chạm tối thiểu 48px. NFR-11 yêu cầu chữ tối thiểu 17 px ở chế độ này và thao tác thường ngày không quá ba chạm; UI Pack mặc định đáp ứng với 18px. Tuỳ chọn hiện lưu trong `localStorage` khoá `vt.displayMode`; thiết kế dữ liệu có chỗ lưu theo người dùng ở máy chủ nhưng mã chưa dùng (suy luận về nơi lưu đích).

Nội dung do AI gợi ý phải được phân biệt rõ với nội dung do người nhập; TL-02 mô tả gợi ý AI có viền tím và cần người dùng chấp nhận rõ ràng (xem SCR-1701 ở [trang 05b](05b-quy-trinh-va-man-hinh-qt09-18.md)).

## Liên quan

- [Kiến trúc frontend](02-kien-truc-frontend.md)
- [Giao diện theo tenant](03-giao-dien-theo-tenant.md)
- [Kiểm thử và chất lượng](11-kiem-thu-va-chat-luong.md)
- [Bảo mật frontend](08-bao-mat-frontend.md)
- [Tổng quan hệ thống](01-tong-quan-he-thong.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
