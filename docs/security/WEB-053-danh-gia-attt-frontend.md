# Đánh giá an toàn thông tin phân hệ web (WEB-053)

> Nguồn: TL-04 mục "Kiểm thử bảo mật: ASVS mức 2 và OWASP ZAP", bảng SEC-19, SEC-24, SEC-40, bảng D7, D11; NFR-08; wiki/08-bao-mat-frontend.md; wiki/15-phan-quyen.md và wiki/15a-ma-tran-phan-quyen.md. Ngày đánh giá: 22/10/2026, nhánh `claude/b8-ha-tang-g7g48k`.

## 1. Phạm vi và cách làm

Đợt đánh giá này kiểm phần phía trình duyệt của SVC-17 `web-frontend`: bản phục vụ nginx (`deploy/web/`), shell, chín remote, gói nhúng `/embed/` và plugin mẫu. Phần API của mission-core và các microservice không thuộc phạm vi, vì TL-04 giao lớp DAST theo đặc tả OpenAPI cho từng service (quét chủ động hằng đêm trên staging).

Máy chủ dev dùng chung chưa triển khai web và mission-core (ghi chú vận hành ngày 22/10/2026), nên bản được quét là bản dựng đầy đủ (`npm run build:all`, ghép như `Dockerfile`) chạy sau nginx thật với đúng `deploy/web/nginx.conf`, `security-headers.conf` và cấu hình chạy do `40-runtime-config.sh` sinh. Ứng dụng chạy ở chế độ giả lập API, đăng nhập tắt. Cấu hình header và CSP giống hệt bản container, nên kết quả về header có giá trị cho mọi môi trường; phần phụ thuộc Keycloak và API thật phải kiểm lại trên staging (mục 7).

Công cụ gồm ba phần. Thứ nhất là OWASP ZAP 2.16.1 chạy Automation Framework ở chế độ baseline, tức spider cộng quét thụ động, không tấn công chủ động. Vì shell là ứng dụng một trang nên spider chỉ thấy khoảng mười địa chỉ; lần quét chính vì vậy gửi thêm yêu cầu tới toàn bộ 1.822 tệp đã dựng và các tuyến của ứng dụng, kể cả các đường dẫn nhạy cảm như `/.env`, `/.git/config` và biến thể `..`. Thứ hai là bộ kiểm thử Playwright đối chiếu ma trận phân quyền (WEB-057) cộng một lượt thử truy cập đối tượng ngoài phạm vi. Thứ ba là rà mã nguồn và `npm audit` theo danh mục ASVS 4.0.3 mức 2 cho các chương có phần phía trình duyệt.

## 2. Kết quả ZAP baseline

Lần quét đầu tìm ba cảnh báo mức Medium. Header CSP vắng mặt trên trang `silent-refresh.html`, trên trang lỗi do nginx sinh (400, 404) và trên `/embed/vt-okr-app.js` khi tệp thiếu. Trang `silent-refresh.html` thiếu cả chống nhúng khung (`frame-ancestors`). Mọi tệp tĩnh và `config.json` trả `Access-Control-Allow-Origin: *`.

Sau khi sửa (mục 3), lần quét toàn bộ 1.822 địa chỉ cho kết quả sau; báo cáo đầy đủ của ZAP nằm ở [zap/ket-qua-zap-baseline-2026-10-22.md](zap/ket-qua-zap-baseline-2026-10-22.md).

| Mức | Cảnh báo | Số chỗ | Kết luận |
|---|---|---|---|
| High | (không có) | 0 | Đạt tiêu chí "0 High" của TL-04 |
| Medium | Cross-Domain Misconfiguration (10098) | 12 | Giữ có giải trình, xem dưới |
| Low | Timestamp Disclosure - Unix (10096) | 13 | Dương tính giả: hằng số thời gian trong mã thư viện `angular-oauth2-oidc` |
| Thông tin | Suspicious Comments (10027) | 12 | Chú thích của mã thư viện Angular đã nén, không chứa thông tin nội bộ |
| Thông tin | Modern Web Application (10109) | 9 | Ghi nhận ứng dụng một trang, không phải lỗi |

Cảnh báo CORS được giữ vì đây là quyết định thiết kế đã ghi ở wiki trang 08: tệp tĩnh công khai và `config.json` phải đọc được từ nguồn khác để shell nạp remote khác nguồn và để cổng văn phòng số nhúng `<vt-okr-app>` (WEB-013, TL-04 D7). Không phản hồi nào kèm `Access-Control-Allow-Credentials`, và `config.json` chỉ chứa địa chỉ API, IdP và khoá công khai của UI Pack, không chứa bí mật. API thật không đi qua nginx này; CORS của API do APISIX cấu hình theo danh sách nguồn của tenant (SEC-06), không bao giờ dùng ký tự đại diện. Hướng thu hẹp hơn nêu ở mục 6.

## 3. Đã sửa trong phạm vi `deploy/web/`

Thay đổi thứ nhất là CSP mặc định cho mọi phản hồi không phải trang shell: tệp mới `deploy/web/csp-mac-dinh.conf` đặt `default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'` ở cấp `server` và trong mọi `location` có `add_header` riêng (trừ trang shell, vốn có CSP riêng kèm nonce). Header này không ảnh hưởng tới tệp js, css, json khi được trang khác tải, nhưng bảo vệ trang lỗi và bất kỳ tệp HTML lẻ nào (ví dụ `index.html` phát triển của từng remote) khỏi chạy mã hay bị nhúng khung. `Dockerfile` chép thêm tệp này vào `/etc/nginx/mission/`.

Thay đổi thứ hai là `location = /silent-refresh.html` riêng. Trang này nhận mã uỷ quyền của cửa sổ bước lên MFA trên URL, nên nay trả `Cache-Control: no-store`, `Referrer-Policy: no-referrer` (mã không lọt sang nơi khác qua Referer, ASVS V3.1.1), và CSP `default-src 'none'; script-src 'self'; frame-ancestors 'self'` kèm `require-trusted-types-for 'script'; trusted-types 'none'`. Đã kiểm trên trình duyệt: trang vẫn gửi được kết quả về shell khi mở trong khung cùng nguồn, và 11 tuyến chính của shell không phát sinh vi phạm CSP mới hay lỗi console.

## 4. Thử truy cập vượt quyền

Ở mức tuyến, bộ kiểm thử đối chiếu ma trận (WEB-057, `E2E_MODE=gia-lap npx playwright test -c e2e/playwright.config.ts`) chạy 906 ca "vai trò × tuyến" và nội dung: 892 ca đạt. Mười bốn ca lỗi đều thuộc Studio cấu hình (`/config-studio` và các tuyến con): tuyến gốc chuyển tiếp sang `/config-studio/tien-trinh` hoặc một tuyến con chưa khớp kỳ vọng sinh từ ma trận. Đây là lệch giữa điều hướng của remote và ma trận (tuyến con mới của WEB-061, WEB-062), không phải lộ dữ liệu: vai trò không có quyền vẫn bị đưa về trang "Không có quyền truy cập". Phát hiện này cần sửa ở remote hoặc ở bộ sinh kỳ vọng (mục 6).

Ở mức đối tượng, lượt thử gọi thẳng màn hình chi tiết với mã đối tượng ngoài phạm vi, trong khi máy chủ (giả lập bằng Playwright) trả 403 có `code` hoặc 404 theo RFC 9457:

| Vai trò | Màn hình | Kết quả 403 | Kết quả 404 |
|---|---|---|---|
| member | Chi tiết nhiệm vụ `/tasks/:id` | Không lộ dữ liệu; hiện thông báo không có quyền xem nhiệm vụ | "Không tìm thấy nhiệm vụ" |
| member | Phiếu tự đánh giá `/appraisal/tu-danh-gia/:id` | Không lộ dữ liệu; "Không mở được hồ sơ đánh giá này" | Như 403 |
| member | Sửa mục tiêu `/okr/objectives/:id/edit` | Hiện đúng thông điệp MSG của máy chủ | "Không tìm thấy dữ liệu" |
| appraiser | Thẩm định `/appraisal/tham-dinh/:id` | Không lộ dữ liệu; thông điệp riêng của màn hình | Như 403 |
| decider | Gói hồ sơ `/dossier/goi-ho-so/:id` | Không lộ dữ liệu; "Không tải được gói hồ sơ" | Như 403 |
| manager | Kết quả xếp loại `/appraisal/ket-qua/:id` | Không lộ dữ liệu | Như 403 |
| vai trò không có chức năng | Ba tuyến trên với vai trò sai | Chặn ở tuyến, chuyển "Không có quyền truy cập" | Như 403 |

Không trường hợp nào hiện dữ liệu, mã đối tượng, mã MSG thô hay vết lỗi kỹ thuật, và không có lỗi JavaScript. Mọi 403 đều có dải thông báo chung "Đồng chí không có quyền thực hiện thao tác này." của `authInterceptor`. Riêng các màn hình thẩm định và gói hồ sơ thay tiêu đề MSG của máy chủ bằng câu riêng của màn hình, nên chưa đạt đầy đủ yêu cầu "hiển thị lỗi 403 đúng MSG"; đề xuất sửa ở mục 6.

## 5. Đối chiếu ASVS 4.0.3 mức 2 phần phía trình duyệt

Bảng dưới chỉ gồm các yêu cầu có phần hiện thực ở web; yêu cầu thuần máy chủ (mật mã lưu trữ, nhật ký, kiểm soát truy cập phía API) thuộc đánh giá của từng service. "Đạt" nghĩa là có bằng chứng trong mã hoặc qua kiểm thử nêu ở cột cuối.

| Mục ASVS | Yêu cầu | Đánh giá | Bằng chứng |
|---|---|---|---|
| V1.14.6 | Không dùng công nghệ phía client không an toàn | Đạt | Không Flash, không applet; CSP `object-src 'none'` |
| V3.1.1 | Token phiên không xuất hiện trên URL | Đạt có điều kiện | Luồng Code + PKCE; mã uỷ quyền chỉ ở URL trả về một lần, trang nhận có `no-referrer` và `no-store` (mục 3) |
| V3.2.3 | Token phiên chỉ lưu ở nơi an toàn | Đạt | `VtTokenStorage` giữ token trong bộ nhớ; `sessionStorage` chỉ giữ `nonce`, `PKCE_verifier`, `session_state`, tuyến đang mở |
| V3.3.1 | Đăng xuất làm mất hiệu lực phiên | Cần kiểm trên staging | Luồng đăng xuất OIDC có trong `SessionService`; chưa thử với Keycloak thật |
| V3.5.2 | Không dùng khoá API tĩnh phía client | Đạt | Mọi lời gọi qua `@vt/api-client` với Bearer token của người dùng |
| V4.1.1, V4.1.3 | Kiểm soát truy cập ở máy chủ, giao diện chỉ phản ánh | Đạt phía web | Ẩn hiện theo ma trận (WEB-055); 403, 404 không lộ dữ liệu (mục 4); quyết định cuối do OPA |
| V5.3.3 | Mã hoá đầu ra theo ngữ cảnh, chống XSS | Đạt | Không có `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `bypassSecurityTrust*`, `eval`, `new Function` trong mã nguồn (rà bằng tìm kiếm toàn bộ `projects/`); Trusted Types bắt buộc trên trang shell |
| V5.2.4 | Không đánh giá biểu thức động bằng mã | Đạt | CEL thông dịch bằng `cel-js`; Formly không dùng Ajv sinh mã; CSP không có `unsafe-eval` |
| V8.2.2 | Dữ liệu nhạy cảm không lưu ở bộ nhớ trình duyệt | Đạt | `localStorage` chỉ giữ chế độ hiển thị và mã bộ lọc "Việc của tôi" (C1); `sessionStorage` giữ gốc API của cell và khoá OIDC |
| V8.1.1 | Không cache dữ liệu nhạy cảm | Đạt phía web | Trang shell và `silent-refresh.html` `no-store`; header cache của API do APISIX đặt |
| V9.1.1 | Chỉ dùng TLS, có HSTS | Đạt có điều kiện | HSTS bật khi yêu cầu tới qua HTTPS (trực tiếp hoặc `X-Forwarded-Proto`); TLS kết thúc ở APISIX |
| V10.3.2 | Toàn vẹn mã tải động | Đạt | Plugin chỉ nạp khi `remoteEntry.json` khớp băm `integrity` trong UI Pack đã ký (WEB-051); UI Pack kiểm chữ ký (WEB-009) |
| V14.2.1 | Thành phần không có lỗ hổng đã biết | Chưa đạt | `npm audit --omit=dev`: 1 High (`lodash-es` 4.17.21 qua `cel-js` → `chevrotain`), 4 Moderate cùng chuỗi; xem mục 6 |
| V14.4.3 | Có CSP chặt | Đạt | Nonce theo yêu cầu, `strict-dynamic`, Trusted Types, `object-src 'none'`, `base-uri 'self'`; CSP mặc định cho phản hồi khác (mục 3) |
| V14.4.4 | `X-Content-Type-Options: nosniff` | Đạt | Mọi phản hồi |
| V14.4.5 | HSTS | Đạt có điều kiện | Như V9.1.1 |
| V14.4.6 | `Referrer-Policy` phù hợp | Đạt | `strict-origin-when-cross-origin`; `no-referrer` cho trang nhận mã |
| V14.4.7 | Chống nhúng khung | Đạt | `frame-ancestors 'self'` cộng danh sách cổng của tenant (`CSP_FRAME_ANCESTORS`); `'none'` cho phản hồi khác |
| V14.5.3 | CORS không cho nguồn tuỳ ý với dữ liệu có xác thực | Đạt | Ký tự đại diện chỉ cho tệp tĩnh công khai, không kèm credentials (mục 2) |

## 6. Phát hiện ngoài phạm vi tệp của task, đề xuất mở task sửa

Các phát hiện dưới đây nằm ngoài `docs/security/**` và `deploy/web/**`, nên WEB-053 không sửa mà đề xuất để luồng điều phối cấp mã task (không đặt mã mới trong báo cáo này).

1. Mức High theo `npm audit`: `lodash-es` 4.17.21 (GHSA-r5fr-rjxr-66jc và hai cảnh báo Moderate) nằm trong gói chạy thật qua `cel-js` 0.8.2 → `chevrotain` 11.0.3. Đường khai thác `_.template` bị CSP chặn vì không có `unsafe-eval`, và chevrotain không đưa dữ liệu người dùng vào `_.unset` hay `_.omit` (suy luận), nên rủi ro thực tế thấp. Cách sửa đề xuất: thêm `"overrides": { "lodash-es": "4.18.1" }` vào `frontend/package.json`, chạy lại kiểm thử CEL vàng (`form-engine`) để xác nhận.
2. Màn hình thẩm định (`fe-appraisal`) và gói hồ sơ (`fe-dossier`, `package-rules.ts`, `meeting-rules.ts`) thay tiêu đề MSG của máy chủ bằng câu riêng khi gặp 403. Đề xuất: khi `ProblemDetails` có `code` dạng `MSG-..` thì hiện `title` của máy chủ, giống màn hình mục tiêu OKR.
3. Trang "Không tìm thấy trang" của shell (`fe-shell/src/app/pages/not-found.page.ts`) dùng thuộc tính `style` nội tuyến, bị CSP `style-src` chặn (không hỏng chức năng nhưng sinh lỗi console), và lồng thẻ `<main>` trong `<main>` của khung. Đề xuất chuyển sang `styles` của component và đổi thẻ ngoài thành `<section>`.
4. Mười bốn ca đối chiếu ma trận của Studio cấu hình lỗi (mục 4). Đề xuất: sửa điều hướng của `fe-config-studio` hoặc cập nhật quy tắc kỳ vọng trong `e2e/matrix.ts` cho tuyến con mới.
5. `e2e/playwright.config.ts` truyền `E2E_MODE` rỗng khi không đặt, nên từ INF-01 (đăng nhập SSO mặc định) bộ kiểm thử giả lập hỏng toàn bộ nếu chạy không kèm `E2E_MODE=gia-lap`. Đề xuất đặt mặc định `gia-lap` trong cấu hình Playwright.
6. CORS của gói nhúng có thể thu hẹp từ `*` xuống danh sách cổng của tenant bằng `map $http_origin` sinh từ `CSP_FRAME_ANCESTORS` trong `40-runtime-config.sh`. Việc này làm cấu hình phức tạp hơn và chỉ bảo vệ tài nguyên vốn công khai, nên để quyết định ở khâu thiết kế triển khai.
7. Pipeline Jenkins chưa có bước ZAP. Đề xuất thêm bước chạy `docs/security/zap/zap-baseline.yaml` trên staging sau mỗi lần phát hành, chặn khi có High, như TL-04 mô tả.

## 7. Việc còn phải kiểm trên staging

Các mục sau cần môi trường có Keycloak và API thật: đăng xuất và hết hạn phiên (V3.3), bước lên MFA trong cửa sổ bật lên với CSP thật, header của APISIX cho API (SEC-24), CORS của API theo tenant (SEC-06), và quét chủ động ZAP theo OpenAPI của từng service (SEC-40). Kiểm thử xâm nhập độc lập (SEC-41) vẫn là điều kiện trước vận hành chính thức.

## 8. Cách chạy lại

Dựng bản đầy đủ bằng `cd frontend && npm run build:all`, ghép như `Dockerfile` (`node tools/remotes/workspace.mjs assemble <thư mục>`, chép `dist/fe-embed/browser` vào `<thư mục>/embed`), chạy nginx với `deploy/web/nginx.conf` sau khi chạy `40-runtime-config.sh`. Sau đó chạy `ZAP_TARGET=http://localhost:8080 ZAP_OUT=<thư mục báo cáo> zap.sh -cmd -silent -autorun docs/security/zap/zap-baseline.yaml`. Để quét toàn bộ tệp, thêm vào job `requestor` một dòng cho mỗi tệp trong thư mục đã ghép. Bộ đối chiếu ma trận chạy bằng `E2E_MODE=gia-lap npx playwright test -c e2e/playwright.config.ts` sau `npm run e2e:prepare`.
