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

Hiện trạng `angular.json` còn dùng ngân sách mặc định của Angular: cảnh báo ở 500 kB và lỗi ở 1 MB cho gói khởi tạo, 4 kB và 8 kB cho style của từng component. Các ngưỡng này lỏng hơn nhiều so với 250 KB gzip, và lưu ý Angular đo kích thước chưa nén. Việc siết ngân sách và dựng Lighthouse CI thuộc task WEB-003; đo và tối ưu tổng thể thuộc WEB-052, task này yêu cầu ghi số liệu trước và sau vào trang này.

## Cách giữ trang nhanh

Các kỹ thuật dưới đây đã có trong mã hoặc được TL-01 yêu cầu. Mỗi remote nạp lười theo tuyến qua `remoteRoutes()`, nên người chỉ xem nhiệm vụ không tải mã OKR hay thẩm định. Widget SDUI nạp lười qua hàm `load()` của `RegistryEntry`, kể cả widget nằm trong remote khác. Thư viện dùng chung là singleton qua Native Federation, nên Angular và PrimeNG chỉ tải một lần. UI Pack được lưu trong IndexedDB và áp ngay khi khởi động, trong khi bản mới được kiểm ở nền; `config.json`, `current.json`, `remoteEntry.json` và manifest có thời gian đệm 60 giây, còn tệp có băm trong tên được đệm một năm với `immutable`.

Danh sách dùng phân trang con trỏ với giới hạn tối đa 200 (`MAX_PAGE_LIMIT`), tìm kiếm có `debounceTime`, và giao diện theo dõi tiến độ dùng SSE thay vì thăm dò dày. Biểu mẫu 50 trường phải phản hồi trong 50 ms; theo TL-01, mỗi biểu thức CEL nên đánh giá trong 5 ms, và `cel-context.ts` giới hạn độ dài biểu thức 512 ký tự, có bộ nhớ đệm kết quả phân tích cú pháp. INP mục tiêu không quá 200 ms theo mục "Ngân sách hiệu năng" của TL-01 (được nhắc lại ở quy tắc 10 của `AGENTS.md`).

## Đo lường người dùng thật

ADR-12 chọn Grafana Faro cho đo lường phía trình duyệt. TL-01 quy định Faro thu LCP, INP, CLS, lỗi JavaScript, thời gian tải từng remote và span truy vết nối với backend; dữ liệu gửi tới receiver Faro của OTel Collector qua APISIX với giới hạn tốc độ riêng; lấy mẫu 10% phiên, riêng phiên có lỗi lấy 100%; không thu nội dung người dùng nhập. Hiện mã mới có `observeWebVitals()` dùng `PerformanceObserver` cho LCP và INP, và `traceparentInterceptor` tự sinh header W3C `traceparent`; chú thích trong mã ghi rằng cả hai sẽ được thay bằng OTel và Faro. `RuntimeConfig` đã có trường tuỳ chọn `telemetry` gồm `otlpEndpoint` và `sampleRatio` để chuẩn bị.

## Khả năng tiếp cận và người cao tuổi

NFR-11 yêu cầu WCAG 2.1 AA cho mọi màn hình, và `AGENTS.md` nhắc lại yêu cầu này cùng chế độ người cao tuổi. Thực hành cụ thể: dùng phần tử HTML có ngữ nghĩa và nhãn cho mọi trường; mọi thao tác dùng được bằng bàn phím; shell có liên kết bỏ qua tới `main#main`; tương phản tối thiểu 4,5:1 (kiểm tĩnh trên token và kiểm bằng axe-core, xem [Kiểm thử và chất lượng](11-kiem-thu-va-chat-luong.md)); trạng thái không chỉ thể hiện bằng màu.

Chế độ người cao tuổi (`elderly`) được bật bằng nút "Chữ lớn" trên thanh trên cùng, có `aria-pressed`. UI Pack mặc định định nghĩa chế độ này bằng chữ thân 18px thay vì 14px, màu chữ đen tuyệt đối, cờ `reducedSteps` và vùng chạm tối thiểu 48px. NFR-11 yêu cầu chữ tối thiểu 17 px ở chế độ này và thao tác thường ngày không quá ba chạm; UI Pack mặc định đáp ứng với 18px. Tuỳ chọn hiện lưu trong `localStorage` khoá `vt.displayMode`; thiết kế dữ liệu có chỗ lưu theo người dùng ở máy chủ nhưng mã chưa dùng (suy luận về nơi lưu đích).

Nội dung do AI gợi ý phải được phân biệt rõ với nội dung do người nhập; TL-02 mô tả gợi ý AI có viền tím và cần người dùng chấp nhận rõ ràng (xem SCR-1701 ở [trang 05b](05b-quy-trinh-va-man-hinh-qt09-18.md)).

## Liên quan

- [Kiến trúc frontend](02-kien-truc-frontend.md)
- [Giao diện theo tenant](03-giao-dien-theo-tenant.md)
- [Kiểm thử và chất lượng](11-kiem-thu-va-chat-luong.md)
- [Bảo mật frontend](08-bao-mat-frontend.md)
- [Tổng quan hệ thống](01-tong-quan-he-thong.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
