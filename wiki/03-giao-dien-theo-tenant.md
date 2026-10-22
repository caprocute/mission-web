> Nguồn: Baseline mục 4 (ADR-05), mục 14 (G1); TL-01 chương 7 (chuyên đề G1: bốn lớp, cấu trúc UI Pack, cơ chế bộ đệm, chống XSS, ngân sách hiệu năng, kiểm thử), Phụ lục A (ADR-05, ADR-25); TL-02 QT-18 (BR-18-01 đến BR-18-12, SCR-1801 đến SCR-1803); TL-03 mục "Bảng config.ui_pack và config.ui_pack_version", "config.form_schema", "config.component_registry"; mã `@vt/design-system`, `@vt/sdui-renderer`, `fe-shell/public/ui-packs/`.

# Giao diện theo tenant

Trang này giải thích cách một bản build web duy nhất phục vụ hàng nghìn tenant với giao diện khác nhau. Đây là câu trả lời của thiết kế cho góp ý G1 của lãnh đạo ("mỗi tenant có thể có giao diện riêng, kiến trúc đề xuất là SDUI + Micro-frontends, nghiên cứu xem có áp dụng được không"). Kết luận đã chốt ở baseline mục 14 là áp dụng được nhưng có chọn lọc theo lớp, không biến toàn bộ frontend thành một bộ máy dựng giao diện thuần tuý từ dữ liệu.

## Bốn lớp của ADR-05

ADR-05 chia khả năng tuỳ biến thành bốn lớp với mức áp dụng khác nhau. Lý do chia lớp là mỗi lớp có chi phí, rủi ro an toàn và rủi ro hiệu năng khác nhau: đổi màu gần như không có rủi ro, còn cho tenant tự sắp màn hình nghiệp vụ lõi sẽ làm mất khả năng kiểm thử và kiểm tra khả năng truy cập một lần cho mọi tenant.

| Lớp | Nội dung | Phạm vi áp dụng | Công nghệ | Chi phí ước tính (TL-01) |
|---|---|---|---|---|
| 1. Design Token | Màu, chữ, khoảng cách, bo góc, biểu tượng, logo | Toàn bộ giao diện | W3C Design Tokens biên dịch thành CSS custom properties và preset PrimeNG | 3 đến 4 tuần người |
| 2. SDUI có giới hạn | Bố cục trang chủ và widget, nhóm trường và thứ tự thẻ ở trang chi tiết, bố cục dashboard | Chỉ "bề mặt cấu hình được" | `@vt/sdui-renderer` với danh mục component có phiên bản | 6 đến 8 tuần người |
| 3. Biểu mẫu động | Biểu mẫu nghiệp vụ của tenant | Biểu mẫu khai báo trong UI Pack | Formly + JSON Schema 2020-12 + CEL (cel-js ở trình duyệt, cel-java ở máy chủ) | 4 đến 6 tuần người |
| 4. Plugin | Module chuyên ngành Y tế, Giáo dục, LLVT | Tenant có plugin trong manifest | Native Federation, bản dựng ký số và quét | 2 đến 3 tuần người |

TL-01 cộng tổng chi phí khoảng 15 đến 21 tuần người (số ước tính) và ghi rằng việc biên dịch CEL sang WebAssembly là không cần thiết, tiết kiệm khoảng 3 tuần, vì biểu thức kiểm tra biểu mẫu ngắn và cel-js thông dịch dưới 0,1 ms mỗi biểu thức (baseline mục 14).

Phần không cấu hình được quan trọng ngang phần cấu hình được. BR-18-03 quy định các màn hình lõi (cây OKR, Config Studio, thẩm định so sánh, quyết định hàng loạt, bảng điều hành có đi xuống, sổ cái) chỉ nhận token và không cấu hình bố cục. TL-02 đánh dấu rõ ở thuộc tính thiết bị của SCR-0902 và SCR-1501 là "màn hình lõi viết mã, không cấu hình SDUI". Công thức tính điểm cũng không nằm trong UI Pack mà trong Policy Pack (QT-15).

## Cấu trúc UI Pack

UI Pack là một tệp JSON có phiên bản ngữ nghĩa. TL-01 chương 7 định nghĩa các khoá cấp cao sau, và tệp mẫu `fe-shell/public/ui-packs/default/1.0.0/pack.json` trong mã theo đúng cấu trúc này.

| Khoá | Nội dung |
|---|---|
| `$schema` | URI lược đồ UI Pack phiên bản 1.0 |
| `pack`, `version` | Mã gói (ví dụ `default/ui`) và phiên bản ngữ nghĩa |
| `requires` | `registry` là dải phiên bản danh mục component tương thích (ví dụ `^2.4.0`); `policyPack` là dải phiên bản Policy Pack tương thích (ADR-25) |
| `tokens` | Cây token W3C với `$type` và `$value` cho màu, phông, cỡ chữ, bo góc, logo |
| `modes.elderly` | Bộ token phụ cho chế độ Đảng viên cao tuổi (ví dụ chữ thân 18px) và `flags` gồm `reducedSteps`, `minTouchTarget` (48px) |
| `layouts` | Cây nút SDUI theo tên bề mặt, ví dụ `home` dùng `vt.layout.grid` chứa các widget |
| `forms` | Biểu mẫu tham chiếu lược đồ trong Policy Pack (`schemaRef` dạng `policy://...`), phần trình bày gồm `ui.sections` và `widgets` như `vt.input.textarea`, `vt.input.score` |
| `plugins` | Danh sách plugin chuyên ngành tenant được nạp |
| `i18n` | Chuỗi hiển thị theo ngôn ngữ, ví dụ thông báo trang chủ |

Mỗi nút SDUI chỉ gồm bốn trường: `component` (tên trong registry), `props`, `children` và `visibleWhen` (biểu thức CEL chỉ đọc ngữ cảnh người dùng). Ví dụ trong bố cục `home` của TL-01 và của tệp mẫu: widget `vt.widget.myTasksToday` với `limit: 7`, widget `vt.widget.okrProgress` hiện khi người dùng có vai trò `leader` hoặc `manager`, widget `vt.widget.pendingApprovals` hiện khi có vai trò `manager`, và widget `vt.widget.announcement` đọc chuỗi từ `i18n`.

Ở phía dữ liệu, TL-03 lưu UI Pack trong `config.ui_pack` (mã, tên, `current_version_id`) và `config.ui_pack_version`. Bảng phiên bản có các cột token (`tokens`, `tokens_json`), bố cục (`layouts`, `layout_json`), tài nguyên (`assets_json`), `component_registry_version`, `policy_pack_version_id` và `policy_pack_ref`, khoá đối tượng gói đã biên dịch `bundle_object_key`, băm `bundle_sha256` dùng làm ETag và thuộc tính `integrity`, chữ ký `signature`, cờ `elder_mode` và `few_steps`, `published_at` và `status`. TL-03 giữ cả tên cột của baseline và tên cột do TL-02b đề xuất (ví dụ `tokens` và `tokens_json`), nên khi viết client cho API `ui/packs` cần đối chiếu hợp đồng OpenAPI thay vì đoán tên cột.

## Vòng đời phát hành (QT-18)

Trạng thái chuẩn của `config.ui_pack_version.status` là `NHAP`, `DANG_KIEM_TRA`, `CHO_DUYET`, `DA_PHAT_HANH`, `THAY_THE`. Quản trị tenant (AC-02 `tenant_admin`) tạo phiên bản nháp ở SCR-1801, chọn token, sắp bố cục, dựng biểu mẫu ở SCR-1802, xem trước song song hai tenant và chế độ cao tuổi, rồi gửi duyệt. Hệ thống chạy kiểm tra tự động: lược đồ hợp lệ, mọi component nằm trong registry, tương phản đạt AA, kích thước gói ≤ 30 KB, biểu thức CEL cho cùng kết quả trên cel-js và cel-java. Lỗi thì phiên bản về `NHAP`; đạt thì sang `CHO_DUYET`. Người duyệt phải khác người soạn; sau khi duyệt, MOD-08 biên dịch token thành CSS và preset PrimeNG, lưu phiên bản `DA_PHAT_HANH` và phát EVT-23 `UiPackDaPhatHanh`. Phiên bản cũ tự chuyển `THAY_THE` và có thể kích hoạt lại khi quay lui, với điều kiện tương thích Policy Pack đang gắn (MSG-18-04 khi không tương thích).

Bảng dưới tóm tắt các quy tắc của QT-18 mà người làm web phải tôn trọng khi viết renderer, Config Studio hoặc màn hình mới.

| Mã | Nội dung tóm tắt |
|---|---|
| BR-18-01 | Token theo W3C Design Tokens, biên dịch thành CSS custom properties và preset PrimeNG; chữ trên nền phải đạt tương phản ≥ 4,5 : 1 |
| BR-18-02 | Bố cục SDUI chỉ tham chiếu component trong danh mục cho phép có phiên bản; không chứa HTML hay JavaScript tuỳ ý |
| BR-18-03 | Màn hình lõi chỉ nhận token, không cấu hình bố cục |
| BR-18-04 | Biểu mẫu động dùng Formly và JSON Schema; quy tắc và điều kiện hiển thị viết bằng CEL; trình duyệt kiểm tra tức thời, máy chủ kiểm tra lại |
| BR-18-05 | Bộ test biểu mẫu phải cho cùng kết quả trên cel-js và cel-java |
| BR-18-06 | Phiên bản biểu mẫu tham chiếu phiên bản Policy Pack; trường được tính điểm phải khớp khai báo của Policy Pack |
| BR-18-07 | Thay đổi thuần giao diện không phải chạy lại test case tính điểm |
| BR-18-08 | UI Pack ≤ 30 KB gzip; dựng biểu mẫu 50 trường ≤ 50 ms |
| BR-18-09 | Chế độ cao tuổi: chữ cơ sở ≥ 17 px, nút cao ≥ 48 px, tương phản ≥ 7 : 1, một câu hỏi mỗi màn hình, ≤ 3 chạm |
| BR-18-10 | Phát hành ≤ 1 ngày, không phát hành lại mã; quay lui được; EVT-23 |
| BR-18-11 | Trường biểu mẫu khai báo lớp dữ liệu (C1, C2) bằng `x-classification`; trường C2 được mã hoá trường khi lưu |
| BR-18-12 | Plugin chỉ nạp khi có trong manifest của tenant, bản dựng đã ký và quét |

TL-02 nêu ba ngoại lệ có ảnh hưởng trực tiếp tới mã. Khi người dùng đang mở ứng dụng lúc phát hành, giao diện không đổi đột ngột mà hiện gợi ý "Có giao diện mới, tải lại khi thuận tiện"; biểu mẫu đang nhập dở dùng schema cũ cho tới khi nộp, và máy chủ chấp nhận cả hai phiên bản trong thời gian chuyển tiếp nếu cùng tham chiếu một phiên bản Policy Pack. Khi widget của plugin không tải được, renderer hiển thị khung giữ chỗ và ghi lỗi, phần còn lại của trang vẫn chạy. Khi quay lui về phiên bản có biểu mẫu tham chiếu Policy Pack đã ngừng, hệ thống chặn quay lui.

## Liên kết phiên bản với Policy Pack (ADR-25)

Biểu mẫu tự đánh giá thuộc UI Pack phải khớp đầu vào của quy tắc tính điểm thuộc Policy Pack của cùng kỳ. ADR-25 chọn cách để UI Pack khai báo dải phiên bản Policy Pack tương thích (`requires.policyPack`), và công cụ phát hành kiểm trường biểu mẫu khớp `inputs` của gói quy tắc. Phương án gộp hai gói bị loại vì khi đó mọi thay đổi giao diện phải qua phê duyệt quy tắc. Hệ quả là giao diện thay được độc lập nhưng vẫn an toàn, đổi lại mỗi lần phát hành có thêm một bước kiểm tra. MSG-18-07 chặn việc đổi kiểu hoặc xoá một trường mà Policy Pack dùng để tính điểm.

## Bộ đệm: CDN, ETag và IndexedDB

TL-01 chương 7 thiết kế cơ chế hai đường dẫn để UI Pack vừa được lưu đệm lâu vừa đổi được trong vòng một phút. Gói `/{tenant}/{version}/pack.json` là bất biến, phục vụ với `max-age` một năm và `immutable`. Con trỏ `/{tenant}/current.json` chỉ chứa phiên bản hiện hành (và chữ ký), phục vụ với `max-age=60, stale-while-revalidate=600` kèm ETag. Khi tenant phát hành, EVT-23 kích hoạt vô hiệu bộ đệm CDN cho đúng tenant đó; TL-02 nhấn mạnh rằng dù có tới 5.000 tenant, mỗi lần phát hành chỉ vô hiệu bộ đệm của một tenant.

Phía trình duyệt, thiết kế quy định: khi khởi động, hiển thị ngay bản đã lưu trong IndexedDB; đồng thời gửi yêu cầu có `If-None-Match`; nếu nhận 304 thì giữ nguyên; nếu có bản mới thì tải, kiểm chữ ký và áp ở lần điều hướng sau. TL-01 ước 30 KB tương đương khoảng 60 ms trên mạng 4G 4 Mbit/s (số ước tính), nên ngân sách 30 KB không làm chậm đáng kể lần tải đầu. TL-02 ghi số đo trên bản dựng thử là 11,2 KB gzip với tenant mẫu.

Mã hiện tại (`UiPackLoader` và `PackCache` trong `@vt/sdui-renderer`) làm như sau: đọc bản lưu trong cơ sở dữ liệu IndexedDB `vt-ui-pack` và áp ngay nếu có; đọc `current.json` với `cache: 'no-cache'`; nếu phiên bản khác bản lưu thì tải `pack.json`, ghi vào IndexedDB, và chỉ áp ngay khi chưa có bản lưu nào (nếu đã có bản lưu, giao diện mới có hiệu lực ở lần tải sau). Bước kiểm chữ ký gói chưa được hiện thực và đang đánh dấu `TODO(ADR-05)`; đây là việc bắt buộc trước khi lên môi trường thật vì chữ ký là lớp thứ năm của phòng thủ XSS.

## Lớp token và preset PrimeNG

`@vt/design-system` cung cấp `VtPreset`, dựng bằng `definePreset(Aura, ...)` từ `@primeuix/themes`. Thang màu `primary` 50 đến 950 được tính bằng `color-mix()` từ biến `--vt-color-primary`; `surface` và `text` trỏ tới `--vt-color-surface`, `--vt-color-text`; bo góc thẻ trỏ tới `--vt-radius-card`. Nhờ vậy đổi tenant chỉ cần đổi tệp CSS token, không phải dựng lại preset.

Màu nhận diện #EE0033 chỉ đạt 4,48:1 với chữ trắng và 3,6:1 trên nền hồng nhạt của mục menu đang chọn, chưa tới 4,5:1 của WCAG 2.1 AA. Từ WEB-059 UI Pack có thêm hai token: `color.primaryStrong` (#D1002D, 5,6:1 với chữ trắng) cho chữ màu nhấn và nền nút có chữ trắng, và `color.textMuted` (#566275, 5,7:1 trên nền #F5F6F8) thay cho #64748B mặc định của Aura (4,4:1). Chế độ cao tuổi ghi đè `primaryStrong` thành #A30023 và `textMuted` thành #3D4756 để chữ trên nút và chữ phụ đạt 7:1 theo BR-18-09. Preset đặt `colorScheme.light.primary.color` (nút, liên kết, tab, ô chọn) và `text.mutedColor` theo hai token này, có giá trị dự phòng cho remote chạy độc lập; thang 50 đến 950 vẫn pha từ `--vt-color-primary` để giữ sắc hồng, viền đỏ thanh trên và thanh tiến độ vẫn là #EE0033. Tệp `fe-shell/src/app/ui-pack-contrast.spec.ts` kiểm tĩnh các cặp màu của UI Pack mặc định bằng `contrastRatio()` của `@vt/design-system`; UI Pack của tenant khác cần đạt cùng các cặp này ở bước kiểm tra tự động của QT-18 (mã kiểm tra phía máy chủ chưa có). `provideDesignSystem()` đăng ký PrimeNG với lớp CSS (`cssLayer`) thứ tự `base, primeng, app` và tắt chế độ tối.

Theo thiết kế, CSS token do MOD-08 biên dịch ở máy chủ bằng Style Dictionary khi phát hành, sinh khối `:root[data-tenant]` và `:root[data-mode="elderly"]` cùng JSON preset. Trong mã, nếu gói không có trường `compiledCss` thì shell dùng hàm dự phòng `tokensToCss()` để làm phẳng cây token thành biến `--vt-<đường-dẫn>`. `ThemeService.applyTenant()` gắn thuộc tính `data-tenant` lên phần tử gốc và ghi CSS vào một thẻ `<style>` có `nonce` (nếu có `CSP_NONCE`) bằng `textContent`, không dùng `innerHTML`.

TL-02 phần quy ước giao diện chung đặt thêm một giới hạn quan trọng: tenant đổi được màu nhấn và logo nhưng không đổi được bảng màu trạng thái (xanh lá, xanh dương, vàng cam, đỏ, xám, tím cho nội dung AI), và trạng thái luôn kèm chữ để không chỉ dựa vào màu.

## Chế độ Đảng viên cao tuổi

NFR-11 yêu cầu chế độ "Đảng viên cao tuổi" với chữ ≥ 17 px, tương phản cao, ít bước. Baseline mục 14 mô tả chế độ này là một bộ token phụ cộng cờ "ít bước" bật luồng rút gọn. BR-18-09 cụ thể hoá: chữ cơ sở ≥ 17 px, nút cao ≥ 48 px, tương phản ≥ 7 : 1, luồng rút gọn một câu hỏi mỗi màn hình, thao tác thường ngày ≤ 3 chạm. Ví dụ trong TL-01 và tệp mẫu đặt chữ thân 18px và `minTouchTarget` 48px. Theo TL-01, tuỳ chọn của người dùng được lưu trong `config.tenant_setting` và có nút chuyển nhanh trên thanh trên; SCR-1803 là màn hình tiêu biểu của chế độ này (xác nhận sinh hoạt chi bộ, tự đánh giá cuối năm, nhập bằng giọng nói).

Trong mã, `ThemeService` giữ signal `mode` (`default` hoặc `elderly`), gắn `data-mode` lên phần tử gốc và nhớ lựa chọn trong `localStorage` khoá `vt.displayMode` (chỉ là tiện ích cục bộ; việc đồng bộ với `config.tenant_setting` chưa làm). Signal `reducedSteps` lấy từ `modes.elderly.flags.reducedSteps` để màn hình biết cần chuyển sang luồng ít bước. Nút "Chữ lớn" trên `ShellLayout` gọi `toggleElderly()`. Mọi màn hình mới phải được kiểm thử ở cả hai chế độ (xem [Kiểm thử và chất lượng](11-kiem-thu-va-chat-luong.md)).

## Lớp SDUI và danh mục component

`@vt/sdui-renderer` gồm `ComponentRegistry`, `SduiOutletComponent`, `SduiNodeComponent`, `PropsValidator` và `CelContext`. Mỗi mục đăng ký (`RegistryEntry`) có `name`, hàm nạp lười `load`, `propsSchema`, cờ `container` và `since` (phiên bản registry đưa component vào), đúng như TL-01 mô tả. Shell và remote đăng ký thêm mục bằng `provideSduiComponents()`. `SduiNodeComponent` tra registry, bỏ qua component lạ, đánh giá `visibleWhen` bằng cel-js, lọc thuộc tính theo `propsSchema`, rồi tạo component bằng `ViewContainerRef.createComponent`.

TL-01 mô tả kiểm tra thuộc tính bằng Ajv. Mã hiện tại cố ý không dùng Ajv ở chế độ biên dịch lúc chạy vì CSP cấm `unsafe-eval`; `PropsValidator` tự lọc theo tập con JSON Schema (kiểu, `minimum`, `maximum`, `enum`, `pattern`) và loại chuỗi bắt đầu bằng `javascript:` hoặc `data:`. Nếu cần JSON Schema đầy đủ, hướng xử lý ghi trong mã là dùng mã kiểm tra Ajv biên dịch sẵn lúc build.

Phiên bản registry là một điểm lệch cần lưu ý. TL-01 và mã dùng dạng ngữ nghĩa (`REGISTRY_VERSION = '2.4.0'`, `requires.registry: "^2.4.0"`), trong khi TL-02 (SCR-1801, API `GET /api/v1/ui/components?registry=2026.3`) và TL-03 (`config.component_registry.registry_version`, ví dụ `v2026.3`) dùng dạng theo lịch. Trang 14 ghi điểm này để chốt.

Bảng `config.component_registry` theo TL-03 là danh mục dùng chung của nền tảng (không có `tenant_id`), do đội nền tảng phát hành cùng bản web-frontend, gồm `registry_version`, `component_key`, `props_schema`, `allowed_classification` (mức phân loại cao nhất component được hiển thị) và `status` (`HIEU_LUC`, `NGUNG`). Quy trình thêm một widget mới được trình bày ở [Quy ước mã nguồn](10-quy-uoc-ma-nguon.md).

## Lớp biểu mẫu động

`@vt/form-engine` cung cấp `VtFormComponent` (`<vt-form>`) nhận `schema` (JSON Schema của Policy Pack), `ui` (phần `forms.*.ui` của UI Pack), `rules` (quy tắc CEL), `context` (dữ liệu chỉ đọc mà quy tắc truy cập bằng `ctx.*`, ví dụ mức hệ thống tham khảo), `value` (bản nháp), `serverErrors` (mảng `errors[]` của Problem Details) và `readonly` (khoá mọi ô; đổi giá trị thì biểu mẫu dựng lại từ `value`). Quy tắc được đánh giá lại khi mô hình, `context` hoặc `rules` đổi, nên điều kiện phụ thuộc dữ liệu ngoài biểu mẫu (ví dụ số minh chứng đã gắn ở SCR-0901) cập nhật lỗi ngay. Màn hình nghiệp vụ lấy biểu mẫu đã phát hành qua `UiFormApi.current(key)` (`GET /ui/forms/{key}/current`, trả `schemaJson`, `uiJson`, `celRules`); mỗi phần tử `celRules` đổi thành `FormRule` với `when: 'true'`, `assert: expr`, `field` như SCR-0901 (`formRules()` trong `fe-appraisal`). `FormlyJsonschema` sinh cấu hình Formly, rồi engine gắn widget và bố cục. Từ WEB-010, engine không nạp bộ kiểu PrimeNG của Formly mà dùng widget riêng, có nhãn gắn `for`, `aria-describedby` tới mô tả và lỗi, `aria-invalid`, cỡ chạm theo `--vt-min-touch` nên theo được chế độ cao tuổi.

| Widget trong UI Pack | Hiển thị | Mặc định khi UI Pack không ghi |
|---|---|---|
| `vt.input.text` | Ô chữ một dòng | Chuỗi có `maxLength` không quá 255 |
| `vt.input.textarea` | Văn bản dài, đếm ký tự theo `maxLength` | Chuỗi có `maxLength` trên 255 |
| `vt.input.score` | Ô số có `minimum`, `maximum`, bước `multipleOf` | `number`, `integer` |
| `vt.input.date` | Ô ngày của trình duyệt, giá trị `YYYY-MM-DD` | Chuỗi `format: date` |
| `vt.input.catalog` | Danh sách chọn; nhãn lấy từ `oneOf` có `const` và `title`, hoặc từ `VT_FORM_CATALOGS` khi schema ghi `x-catalog` | `enum`, `oneOf` toàn hằng |
| `vt.input.checkbox` | Hộp đánh dấu có nhãn bấm được | `boolean` |

`ui.sections` gom trường thành `fieldset` có `legend`; trường có trong schema mà UI Pack không xếp vào nhóm nào vẫn được hiện ở cuối, để không giấu mất trường bắt buộc. Trường chuỗi chưa nhập mang giá trị `''` và hộp đánh dấu mang `false` (engine bỏ bộ chuyển `''` thành `undefined` của `FormlyJsonschema`), nên quy tắc như `size(giaiTrinh) >= 20` thấy được trường chưa nhập và bản gửi máy chủ đủ trường. Khi bấm gửi mà còn lỗi, đầu biểu mẫu hiện danh sách lỗi (`role="alert"`, nhận focus) gồm lỗi schema, vi phạm quy tắc và lỗi máy chủ, mỗi dòng có liên kết đưa focus tới trường.

Mỗi quy tắc (`FormRule`) có `id`, `when`, `assert`, `message`, `field` (trường nhận thông báo) và cờ `serverOnly` cho quy tắc dùng hàm chưa có trên trình duyệt (ví dụ `workdays()` trong TL-01). `evaluateRules()` chạy mỗi lần mô hình đổi, cây cú pháp của mỗi biểu thức được phân tích một lần rồi dùng lại. Thiếu biến (cel-js báo `Identifier ... not found`) không bị coi là vi phạm; lỗi khác như gọi hàm không có được ghi cảnh báo trên console. `lintRules()` cho Config Studio (SCR-1802) kiểm cú pháp, mã trùng và hàm không có trên trình duyệt mà quy tắc chưa đánh dấu `serverOnly`. Lỗi máy chủ theo trường hiện ở đúng trường cho tới khi người dùng sửa giá trị trường đó.

Thư viện cel-js 0.8 chỉ hỗ trợ một tập con CEL, và có chỗ cho kết quả khác cel-java (kiểm bằng thử trực tiếp khi làm WEB-010):
- Phép chia số nguyên cho kết quả số thực: `7 / 2` bằng 3,5, còn CEL chuẩn và cel-java cho 3. Quy tắc nên viết hằng số thực (`100.0`), như ví dụ R-1B-01 của TL-01.
- Không có cú pháp phương thức trên chuỗi hay danh sách literal: `s.size()`, `s.startsWith()`, `s.matches()`, `[a, b].filter(...)` đều lỗi. Dùng `size(s)`. Macro `filter`, `map`, `all`, `exists`, `exists_one` dùng được trên biến kiểu danh sách.
- Không có `int()`, `timestamp()`, `duration()`: quy tắc về ngày giờ phải đánh dấu `serverOnly` hoặc truyền hàm mở rộng qua `functions`.
- `size()` đếm ký tự, chuỗi tiếng Việt có dấu không bị đếm theo byte.

**Test vàng dùng chung hai engine.** Mỗi biểu mẫu có một tệp `<ma-bieu-mau>.golden.json` gồm `form`, `version` và mảng `cases`; mỗi ca có `id`, `description`, `model` (dữ liệu như bản gửi máy chủ, đủ trường), `context` (giá trị cho `ctx.*`) và `violated` (mã quy tắc vi phạm mong đợi khi chạy toàn bộ quy tắc, kể cả `serverOnly`). `runGoldenSuite(rules, suite)` chạy bộ test trên cel-js: quy tắc `serverOnly` bị bỏ qua và báo trong `skipped`, trừ khi truyền đủ hàm qua `functions` cùng `includeServerOnly`; mã quy tắc trong `violated` mà bộ quy tắc không có làm ca bị trượt. Hai bên phải cùng quy ước "thiếu trường thì quy tắc không bị coi là vi phạm"; cel-java mặc định báo lỗi khi thiếu thuộc tính, nên phía máy chủ cần bắt lỗi này tương tự (suy luận, cần thống nhất với đội mission-core). Mẫu thử Mẫu 1B (`MAU_1B_SCHEMA`, `MAU_1B_UI`, `MAU_1B_RULES` và `mau-1b.golden.json` trong `projects/form-engine/src/samples/`) theo bảng trường SCR-0901 và quy tắc R-1B-01, R-1B-02 của TL-01; đây chỉ là mẫu kiểm engine, schema thật thuộc Policy Pack. TL-01 cho trường `diemTuCham` do cán bộ tự nhập, trong khi bảng trường SCR-0901 của TL-02 ghi điểm do hệ thống tính và không sửa tay; mẫu giữ theo TL-01 để có ví dụ `vt.input.score`, điểm lệch này cần chốt khi làm WEB-035.

Ở phía dữ liệu, `config.form_schema` lưu `form_code`/`form_key`, `version`, `json_schema`/`schema_json` (JSON Schema có `x-cel` và `x-classification`), `ui_schema`/`ui_json`, `cel_rules`, `policy_pack_version_id` và `status` (`NHAP`, `DA_PHAT_HANH`, `NGUNG`). TL-01 quy định CI chạy cùng bộ test case trên cả cel-js và cel-java; MSG-18-08 báo khi hai engine cho kết quả khác nhau.

## Lớp plugin

Plugin chuyên ngành như `fe-plugin-yte` là một remote Native Federation được nạp theo manifest của tenant. Trong ví dụ xuyên suốt của TL-02, Sở Y tế tỉnh X dùng UI Pack v1.8 với widget `ChuyenMonKpiWidget` từ `fe-plugin-yte` và biểu mẫu `phieu-vc-yte` v12 gắn Policy Pack `nn-335/so-y-te` v1.3, cùng một bản build web với Đảng uỷ các cơ quan Đảng TW dùng UI Pack v2.3. Kiểu `UiPack.plugins` trong mã có các trường `name`, `remoteEntry`, `exposedModule`, `routePath`, `title`, nhưng shell chưa đọc mảng này để nạp plugin.

## Phòng thủ XSS năm lớp

TL-01 chương 7 lập luận rằng SDUI chỉ an toàn khi có nhiều lớp chặn độc lập. Năm lớp được thiết kế là: lược đồ UI Pack không có trường nào chứa HTML hay JavaScript; chỉ component có trong registry được dựng; `additionalProperties: false` và URL chỉ được là `https:` hoặc tương đối trong danh sách cho phép; biểu thức CEL chỉ đọc, giới hạn độ dài và chi phí; UI Pack được ký số. Mã hiện có lớp 2, phần lớn lớp 3 (lọc thuộc tính), lớp 4 (giới hạn 512 ký tự trong `CelContext`); lớp 5 chưa có. Chi tiết CSP và quy tắc mã an toàn xem [Bảo mật frontend](08-bao-mat-frontend.md).

## Ngân sách kích thước và kiểm thử

Baseline mục 14 và TL-01 đặt ngân sách: shell cùng design system ≤ 250 KB gzip, mỗi remote ≤ 180 KB, UI Pack ≤ 30 KB, dựng biểu mẫu 50 trường ≤ 50 ms, CEL cho toàn biểu mẫu ≤ 5 ms. Kiểm thử UI Pack theo TL-01 có bốn tầng: Storybook với bộ token mặc định, tương phản cao, cao tuổi và màu thương hiệu tối kết hợp ảnh chụp Playwright và axe-core; kiểm tra tĩnh UI Pack (tương phản ≥ 4,5 : 1); smoke khoảng 2 phút cho mỗi gói; hồi quy trên 20 UI Pack đại diện. Chi tiết xem [Hiệu năng và trải nghiệm](09-hieu-nang-va-trai-nghiem.md) và [Kiểm thử và chất lượng](11-kiem-thu-va-chat-luong.md).

## Kiểm tra tĩnh UI Pack phía máy chủ (CORE-08-10)

mission-core chạy bộ kiểm tra tĩnh mỗi lần lưu nháp, xem trước và gửi duyệt UI Pack (`UiPackStaticChecks` cùng `LayoutValidator` của CORE-08-09, gộp thành `UiPackChecker`). Kết quả từng mục mang mã `CONTRAST`, `ELDER_MODE`, `SIZE`, `SCHEMA` (cùng `REGISTRY`, `CEL_PARITY` của bố cục) và hiện trong `previewUiPackVersion`; khi gửi duyệt mà có mục không đạt, phiên bản trở về nháp và job `TEST_RUN` liệt kê lỗi theo trường.

**Quy ước cặp token chữ và nền.** Bộ kiểm suy ra cặp cần kiểm từ tên token trong cùng một nhóm của `tokens.color`, không cần khai báo riêng. Token tên `on-X` hoặc `onX` (kiểu lạc đà) là màu chữ trên nền `X` cùng nhóm, ví dụ `on-primary` trên `primary`, `onPrimary` trên `primary`. Token tên `text`, `text-…` hoặc `textX` là màu chữ trên mọi nền có tên bắt đầu bằng `surface` hoặc `background` cùng nhóm, ví dụ `text-muted` trên `surface` và `surface-alt`. Nhóm con được kiểm riêng (`brand.on-base` trên `brand.base`). Token không theo quy ước này thì không được kiểm tương phản, nên người soạn bộ token nên đặt tên theo quy ước. Bí danh `{color.white}` được giải trước khi tính. Màu phải viết dạng `#RGB`, `#RRGGBB`, `#RRGGBBFF` hoặc `rgb(r, g, b)`; màu trong suốt một phần hay tên màu không kiểm được nên bị báo lỗi.

**Ngưỡng.** Tương phản ≥ 4,5 : 1 ở chế độ thường (BR-18-01, MSG-18-01) và ≥ 7 : 1 sau khi gộp ghi đè `tokens.$extensions.modes.elderly` (BR-18-09); tỷ lệ không làm tròn lên trước khi so (4,478 : 1 không đạt), thông điệp in tỷ lệ làm tròn xuống hai chữ số. Chế độ cao tuổi còn cần `font.size.body` ≥ 17 px, và nếu khai báo `flags.minTouchTarget` (trong ghi đè cao tuổi) hoặc `size.minTouchTarget` thì phải ≥ 48 px. Chế độ thường: `font.size.body` 12–16 px, mọi token `radius.*` 0–16 px (`rem` tính 16 px). Gói nội dung phiên bản nén gzip ≤ 30 KB (BR-18-08, MSG-18-05); đây là kích thước tài liệu UI Pack 1.0 dạng JSON, chưa phải gói đã biên dịch của CORE-08-11 (suy luận).

**Tài nguyên.** Mỗi mục `assets` phải là `image/svg+xml`, `image/png` hoặc `font/woff2`, ≤ 50 KB, băm `sha256` và `sizeBytes` khớp tệp. SVG qua danh sách cho phép: chỉ phần tử vẽ tĩnh, không phần tử `script`, `foreignObject`, `style`; thuộc tính `style` được dùng nhưng không chứa `expression(` hay `@import`; không thuộc tính `on*`, `href` chỉ trỏ `#id`, `url()` chỉ trỏ `#id`, không `javascript:`, không DOCTYPE hay thực thể; tệp cần bỏ bất cứ thứ gì đều bị trả lại (từ chối thay vì sửa). Nội dung tệp đọc qua cổng `UiPackAssetStore`; hiện chưa có kho tệp và chưa có API tải tài nguyên trong hợp đồng, nên UI Pack có tài nguyên chưa qua được bước này (câu hỏi mở, chờ chốt nơi lưu tệp).

**Biểu mẫu.** Mọi `forms.<khoá>.schemaRef` phải giải được trong dải `requires.policyPack` và mọi trường ở `ui.sections[].fields` phải có trong schema (ADR-25). MOD-08 là mô-đun gốc nên chỉ khai báo cổng `config.api.PolicySchemaPort`; MOD-05 hiện thực ở CORE-05-03 hoặc task kế tiếp, trước đó UI Pack có biểu mẫu không qua được bước này.

**Bộ ca dùng chung.** `data/golden/ui-pack-checks/tuong-phan.json` là bộ ca tương phản mà `WcagContrastGoldenTest` của mission-core chạy; `token-compiler.spec.ts` của `@vt/design-system` (WEB-004) chạy cùng tệp, và `contrastRatio` nhận cùng các dạng màu với máy chủ (`#RGB`, `#RRGGBB`, `#RRGGBBFF`, `rgb()`).

**Bộ biên dịch token phía web (WEB-004).** `token-compiler.ts` của `@vt/design-system` hiện thực lại đúng các quy tắc trên (cặp màu theo tên, 4,5 : 1 và 7 : 1, chữ thân, vùng chạm, bo góc, 30 KB gzip) và sinh `compiledCss` bằng chính `tokensToCss`; công cụ `frontend/tools/token-compiler/compile.mjs` chạy mã đó trên các gói `public/ui-packs/<tenant>/` và ghi `compiledCss` vào gói, nên `UiPackLoader` không phải tự biên dịch. Bốn bộ mẫu `default`, `tuong-phan-cao`, `cao-tuoi`, `thuong-hieu-toi` đều đạt; `tools/token-compiler/fixtures/tuong-phan-thap.json` là bộ cố ý bị từ chối. Để bộ mặc định qua được quy ước tên, token chữ trên nút đổi từ `color.onPrimary` sang `color.onPrimaryStrong` (nút dùng nền `primaryStrong`; `onPrimary` sẽ bị ghép với `primary` #EE0033 chỉ đạt 4,48 : 1), và preset đọc `--vt-color-on-primary-strong` trước `--vt-color-on-primary`. Preset cũng đổi màu chữ nút phụ viền và nút chữ của Aura (`{surface.500}`, 4,4 : 1 trên nền trang) sang `--vt-color-text-muted`. Bộ kiểm trong màn hình soạn UI Pack (`fe-config-studio`) và mock vẫn dùng danh sách cặp cố định riêng; nên chuyển sang `checkTokens` khi sửa các màn hình đó (suy luận).

## UI Pack mặc định của tenant mới (DL-06)

Tenant mới nhận UI Pack mặc định ở `data/seed/ui-pack/mac-dinh-<phiên bản>.json`. Tệp có đúng thân `UiPackVersionUpdate` (token, bố cục, `componentRegistryVersion`, `elderMode`, `fewSteps`) cộng trường `version`, nên được nạp bằng chính API của QT-18 qua `nap_ui_pack.py` (tạo nháp, ghi kèm `If-Match`, gửi duyệt; phát hành cần quản trị thứ hai), không ghi thẳng CSDL. Bộ kiểm `kiem_ui_pack.py` dựng tài liệu UI Pack 1.0 giống `UiPackService.document` rồi chạy cùng các kiểm tra của máy chủ: lược đồ, component trong registry 2026.3 và `propsSchema`, tối đa 12 widget, tương phản 4,5 : 1 và 7 : 1, chữ thân, cỡ chạm, bo góc, gzip ≤ 30 KB. `UiPackSeedTest` của mission-core chạy tệp seed qua đúng lược đồ và `UiPackStaticChecks`, nên khi bộ kiểm Python và máy chủ lệch nhau, kiểm thử Java là chuẩn. Phiên bản 1.0.0 nén gzip 820 byte; lịch sử và số đo ghi ở `data/seed/ui-pack/CHANGELOG.md`.

**Quy ước tên token.** Token màu trong UI Pack đặt tên kiểu gạch nối theo nhóm `color` (`primary`, `primary-strong`, `on-primary-strong`, `surface`, `surface-elevated`, `background`, `border`, `text`, `text-muted`), các nhóm khác là `font.family`, `font.size.body`, `radius.card`, `space.lg`, `border.width` và `min-touch`. `tokensToCss()` làm phẳng thành biến `--vt-color-primary-strong`, `--vt-font-size-body`, `--vt-min-touch`…; bộ kiểm tương phản tự ghép `text*` với `surface*`/`background*` và `on-X` với `X`, nên token màu mới phải theo các tiền tố này để được kiểm. Ghi đè cao tuổi nằm ở `tokens.$extensions.modes.elderly` với cùng đường dẫn token, cộng `flags.minTouchTarget`; máy chủ chuyển nó thành `modes.elderly.tokens` và `modes.elderly.flags` (cờ `reducedSteps` lấy từ `fewSteps`).

**Bố cục trang chủ mặc định.** Lưới `vt.layout.grid` gồm "Việc của tôi hôm nay" (`id: myTasksToday`, `mandatory: true`, người dùng không ẩn được ở CNH-03), tiến độ OKR cá nhân, tiến độ OKR đơn vị (`id: okrProgressUnit`, chỉ lãnh đạo, quản lý) và việc chờ duyệt (quản lý). Hai khoá nút `id` và `mandatory` được lược đồ UI Pack 1.0 của máy chủ nhận từ DL-06; trước đó lược đồ từ chối chúng dù lớp phủ trang chủ của CNH-02 cần. Widget thông báo chưa đưa vào vì UI Pack phía máy chủ chưa có khối `i18n` để chứa khoá văn bản (suy luận về thứ tự làm).

**Di động đọc cùng token.** Ứng dụng di động (MOB-01 chuyển token thiết kế sang ThemeData của Flutter, MOB-08 chế độ cao tuổi) lấy cùng UI Pack đã phát hành qua `getCurrentUiPack` và ánh xạ cùng tên token sang ThemeData, không giữ bộ màu riêng; chế độ cao tuổi trên di động dùng cùng ghi đè `modes.elderly` và cờ `reducedSteps`, nên một thay đổi token chỉ cần phát hành một lần cho cả web và di động (suy luận từ ADR-05 và kế hoạch MOB).

## Liên quan

- [Kiến trúc frontend](02-kien-truc-frontend.md)
- [Quy trình và màn hình QT-09 đến QT-18](05b-quy-trinh-va-man-hinh-qt09-18.md) (QT-18)
- [Bảo mật frontend](08-bao-mat-frontend.md)
- [Hiệu năng và trải nghiệm](09-hieu-nang-va-trai-nghiem.md)
- [Quy ước mã nguồn](10-quy-uoc-ma-nguon.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
