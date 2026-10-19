# Kế hoạch phát triển phân hệ web Mission (SVC-17 `web-frontend`)

Ngày lập: 19/10/2026 · Phiên bản 1.0 · Căn cứ: Baseline, TL-01, TL-02, TL-03, TL-04 trong `/mnt/project-files/kien-truc-okr-kpi/src/`

## 1. Mục đích và căn cứ

Tài liệu này biến thiết kế đã chốt trong bộ TL-01..TL-04 thành một lộ trình phát triển cụ thể cho phân hệ web, đủ chi tiết để chia việc cho cả người và AI agent. Kế hoạch không thiết kế lại kiến trúc: mọi lựa chọn công nghệ, mã định danh và quy ước đều lấy nguyên từ baseline và các TL. Khi có điểm nào trong kế hoạch lệch với TL, TL được coi là đúng và kế hoạch phải được sửa lại.

Phân hệ web là kênh làm việc chính của cán bộ, lãnh đạo, tổ chức cán bộ và quản trị viên. Nó phải phục vụ quy mô 6 triệu tài khoản và 300.000 người dùng đồng thời (NFR-01), cho phép mỗi tenant có giao diện riêng mà không phải nhân bản mã nguồn (ADR-05, phản hồi G1 của lãnh đạo), và bảo đảm khả năng sử dụng cho người cao tuổi (NFR-11).

## 2. Phạm vi

Kế hoạch bao gồm toàn bộ màn hình web trong TL-02 (từ SCR-0001 đến SCR-1802, trừ bốn màn hình di động SCR-0302, SCR-0504, SCR-0702, SCR-1803 thuộc SVC-18), khung ứng dụng (shell), các thư viện dùng chung `@vt/*`, bộ công cụ biên dịch Design Token, cơ chế UI Pack và plugin, cùng pipeline build, đóng gói và phục vụ bằng nginx. Kế hoạch cũng bao gồm hạ tầng dùng chung cho môi trường phát triển trên server dev, vì frontend cần Keycloak, API gateway, kho đối tượng và hệ thống quan sát để kiểm thử tích hợp.

Ngoài phạm vi là mã nghiệp vụ phía máy chủ (mission-core và các microservice), ứng dụng di động Flutter, và hạ tầng Kubernetes production theo TL-04. Phân hệ web làm việc với các thành phần này qua hợp đồng API `/api/v1/*` và dùng dữ liệu giả lập cho đến khi backend sẵn sàng.

## 3. Kiến trúc phân hệ web (tóm tắt từ TL-01 và baseline 5.2)

Phân hệ web gồm một ứng dụng chủ `fe-shell` và các remote micro-frontend được nạp lúc chạy bằng Native Federation. Shell lo đăng nhập (OIDC code flow + PKCE với Keycloak), khung bố cục, điều hướng theo vai trò, nạp UI Pack của tenant, thông báo và tìm kiếm toàn cục. Mỗi remote ứng với một nhóm quy trình nghiệp vụ và có thể phát hành độc lập. Các thư viện `@vt/design-system`, `@vt/sdui-renderer`, `@vt/form-engine`, `@vt/auth`, `@vt/api-client` và `@vt/telemetry` được chia sẻ dạng singleton giữa shell và remote.

Giao diện theo tenant đi theo bốn lớp của ADR-05. Lớp thứ nhất là Design Token theo định dạng W3C, được biên dịch thành CSS custom properties và nối vào preset Aura của PrimeNG. Lớp thứ hai là SDUI có giới hạn: UI Pack mô tả bố cục trang chủ và dashboard bằng các component có sẵn trong registry, điều kiện hiển thị viết bằng CEL. Lớp thứ ba là biểu mẫu động bằng Formly + JSON Schema, quy tắc kiểm tra viết bằng CEL và chạy giống nhau ở trình duyệt (cel-js) và máy chủ (cel-java). Lớp thứ tư là plugin micro-frontend cho chuyên ngành, chỉ dùng khi ba lớp trên không đáp ứng được. Cách chia này giúp phần lớn tenant chỉ cần cấu hình, không cần code riêng.

| Remote | Quy trình (QT) | Màn hình web (SCR) | Module/dịch vụ phía máy chủ |
|---|---|---|---|
| `fe-shell` | QT-00 | SCR-0001 (theme Keycloak), SCR-0002 | SVC-02, SVC-12, SVC-16 |
| `fe-admin` | QT-01, QT-08 | SCR-0101..0104, SCR-0801 | MOD-08, SVC-03, SVC-04 |
| `fe-okr` | QT-02, QT-03, QT-17 | SCR-0201..0203, SCR-0301, SCR-1701 | MOD-01, SVC-14 |
| `fe-task` | QT-04, QT-05, QT-06 | SCR-0401..0403, SCR-0501..0503, SCR-0601..0602 | MOD-02 |
| `fe-evidence` | QT-07 | SCR-0701, SCR-0703 | MOD-03, SVC-08 |
| `fe-appraisal` | QT-09, QT-10 | SCR-0901..0904, SCR-1001..1002 | MOD-04, SVC-07, SVC-10 |
| `fe-dossier` | QT-11 | SCR-1101..1102 | MOD-06, SVC-11 |
| `fe-oversight` | QT-12, QT-13, QT-14 | SCR-1201..1202, SCR-1301..1302, SCR-1401 | MOD-07, SVC-09, SVC-15 |
| `fe-dashboard` | QT-16 | SCR-1601..1603 | SVC-15 |
| `fe-config-studio` | QT-15, QT-18 | SCR-1501..1504, SCR-1801..1802 | MOD-05, MOD-08 |
| `fe-plugin-*` | theo chuyên ngành | do plugin định nghĩa | API công bố |

Bảng trên là xương sống của việc truy vết. Mỗi task màn hình trong `docs/tasks/` ghi chuỗi QT → SCR → API → MOD/SVC → EVT để người duyệt có thể đối chiếu ngược về TL-02 và TL-03.

## 4. Hiện trạng tại thời điểm lập kế hoạch

Repo `mission-web` đã có một lát cắt dọc chạy được, dùng để chứng minh kiến trúc trước khi nhân rộng. Shell đã nạp được remote `fe-task` qua Native Federation, nạp UI Pack mặc định (token, logo, bố cục trang chủ SDUI có bốn widget, chuỗi tiếng Việt), chuyển được chế độ Đảng viên cao tuổi, và điều hướng theo vai trò. Remote `fe-task` có màn hình "Việc của tôi" (SCR-0503) và "Chi tiết nhiệm vụ / cập nhật tiến độ" (SCR-0601, QT-06) chạy trên dữ liệu giả lập, kèm widget "Việc hôm nay" mà shell nhúng vào trang chủ. Sáu thư viện `@vt/*` đã có khung chức năng chính. Các màn hình đã được kiểm tra bằng Playwright, không có lỗi console.

Quá trình dựng lát cắt này đã lộ ra một số bẫy kỹ thuật của Native Federation và Angular 21, như việc shell phải tự dùng `rxjs-interop` để remote dùng chung được, hay việc phải bỏ `@primeuix/themes` khỏi danh sách chia sẻ. Các bẫy này đã được ghi vào `AGENTS.md` và wiki để agent sau không lặp lại.

Hạ tầng dùng chung cho dev được đóng gói bằng Docker Compose trong `infra/dev/`, chia theo profile (core, events, search, analytics, observability, gateway, web), dùng dải cổng 35xxx để không đụng các hệ thống đang chạy trên server 14.232.210.125, và được triển khai vào `/home/provision/mission-infra`.

## 5. Lộ trình theo giai đoạn

Các giai đoạn bám theo các đợt triển khai của TL-04. Giai đoạn 0 chạy song song với đợt 0a/0b (nền tảng), các giai đoạn sau đi cùng các đợt đưa module nghiệp vụ vào hoạt động, để mỗi màn hình web có backend tương ứng sớm nhất có thể.

### Giai đoạn 0 – Nền tảng phát triển (WEB-001..WEB-015)

Mục tiêu của giai đoạn này là làm cho việc xây màn hình về sau trở nên lặp lại được và an toàn. Nhóm công việc gồm bộ công cụ chất lượng (ESLint, Prettier, Vitest), pipeline Jenkins có cổng chất lượng theo TL-04 (Semgrep, gitleaks, Trivy, SBOM, ký image), ngân sách bundle và Lighthouse CI, công cụ biên dịch token có kiểm tra tương phản, Storybook với kiểm thử hồi quy ảnh theo bốn bộ token, đăng nhập Keycloak thật, chuẩn hoá `@vt/api-client` (sinh từ OpenAPI, xử lý lỗi RFC 9457, xung đột 409, tác vụ dài 202), interceptor giả lập API, hoàn thiện các lớp bảo vệ của SDUI và form engine, telemetry Faro, chế độ nhúng vào cổng văn phòng số, CSP chặt với Trusted Types, và công cụ sinh remote mới.

Giai đoạn 0 kết thúc khi có đủ khung 10 remote, mọi cổng chất lượng chạy tự động, và một agent mới có thể làm màn hình đầu tiên chỉ bằng cách đọc `AGENTS.md`, wiki và task card.

### Giai đoạn 1 – Định danh và tổ chức (WEB-016..WEB-020)

Giai đoạn này phục vụ QT-00 và QT-01: quản trị tenant, wizard khởi tạo tenant (đo được NFR-07 "tenant mới trong một ngày"), cây tổ chức có lịch sử theo hai trục thời gian của SVC-04, màn hình đối soát đồng bộ nhân sự, và theme đăng nhập Keycloak. Đây là nền cho mọi quy trình sau, vì phân quyền và chọn người giao việc đều dựa trên cây tổ chức.

### Giai đoạn 2 – Lõi nghiệp vụ (WEB-021..WEB-034)

Đây là giai đoạn lớn nhất, gồm QT-02 đến QT-08: cây OKR, tạo/sửa/duyệt OKR, check-in KR, thư viện danh mục công việc, lập và duyệt kế hoạch quý có so sánh phiên bản, giao việc (kể cả từ văn bản chỉ đạo với gợi ý AI), hoàn thiện "Việc của tôi" và chi tiết nhiệm vụ, đề nghị gia hạn, nộp minh chứng có tính băm phía client, nghiệm thu năm mức, và tiến trình khoá kỳ. Những màn hình này được dùng hằng ngày bởi số đông, nên ưu tiên tốc độ thao tác (không quá ba chạm), ngân sách hiệu năng và chế độ cao tuổi.

### Giai đoạn 3 – Đánh giá, hồ sơ, giám sát (WEB-035..WEB-043)

Giai đoạn này phục vụ QT-09 đến QT-13: tự đánh giá theo Mẫu 1B dựng bằng form engine, thẩm định, phiếu xếp loại có giải thích truy vết, quyết định hàng loạt có ký số, xếp loại năm và phân nhóm triển vọng, hội nghị kiểm điểm và gói hồ sơ số, khiếu nại có mã hoá đầu cuối tại trình duyệt, mở lại kỳ theo nguyên tắc bốn mắt và tra cứu sổ cái. Đây là nhóm màn hình có yêu cầu bảo mật cao nhất (dữ liệu C2, C3, step-up MFA), nên mỗi task đều bắt buộc đọc wiki bảo mật.

### Giai đoạn 4 – Điều hành, cấu hình, mở rộng (WEB-044..WEB-053)

Giai đoạn cuối gồm trung tâm cảnh báo, Config Studio (công thức CEL, bảng quyết định, test case vàng, cấu hình luồng duyệt theo mẫu tham số thay vì vẽ BPMN tự do, đúng tinh thần giải quyết điểm nghẽn G2), bảng điều hành lãnh đạo nhúng Superset, trình dựng dashboard, trợ lý AI, studio giao diện tenant và biểu mẫu động, plugin chuyên ngành mẫu, cùng hai đợt kiểm chứng tổng thể về hiệu năng và an toàn thông tin (OWASP ZAP, ASVS mức 2).

## 6. Cách tổ chức công việc cho AI agent

Toàn bộ khối lượng được chia thành 53 task trong `docs/tasks/`, mỗi task đủ nhỏ để một agent làm trọn trong một phiên (cỡ S khoảng nửa ngày, M khoảng một ngày, L khoảng hai ngày và nên tách nhỏ thêm khi bắt đầu). Mỗi task card có cùng cấu trúc: khối quy định chung bắt buộc đọc, bảng thuộc tính (giai đoạn, cỡ, phụ thuộc, truy vết), mục tiêu, đầu vào bắt buộc đọc, phạm vi, ngoài phạm vi, danh sách tệp được phép sửa, tiêu chí hoàn thành và yêu cầu báo cáo.

Việc nhắc quy định chung được bảo đảm bằng ba lớp, để không phụ thuộc vào trí nhớ của người giao việc. Lớp thứ nhất là khối "0. Quy định chung" nằm ngay đầu mọi task card, trỏ về `AGENTS.md` và tóm tắt những quy tắc hay bị quên. Lớp thứ hai là hook `SessionStart` trong `.claude/settings.json`: mỗi phiên Claude Code mở trong repo sẽ tự in `docs/tasks/_NHAC-QUY-DINH.md`, còn `CLAUDE.md` nạp sẵn `AGENTS.md` và mục lục wiki. Lớp thứ ba dành cho agent khác: lệnh `./scripts/agent-task.sh WEB-xxx` ghép `AGENTS.md` và task card thành một prompt duy nhất để dán vào bất kỳ công cụ nào.

Thứ tự làm đi theo cột phụ thuộc trong `docs/tasks/README.md`. Các task cùng giai đoạn không phụ thuộc nhau có thể giao song song, mỗi agent một nhánh `feat/WEB-xxx-…`, và người phụ trách duyệt PR của từng task trước khi gộp. Vì chi phí chạy nhiều agent cùng lúc là đáng kể, nên khởi đầu với hai đến ba agent song song cho giai đoạn 0, rồi tăng dần khi cổng chất lượng tự động đã đủ tin cậy để giảm công duyệt.

## 7. Cổng chất lượng

Một task chỉ được coi là xong khi đạt đồng thời các điều kiện sau. Build shell và các remote liên quan thành công mà không vượt ngân sách bundle. Kiểm thử đơn vị cho logic mới chạy xanh. Màn hình đã được mở thật trên trình duyệt và không có lỗi console. Chế độ cao tuổi và thao tác bàn phím hoạt động, axe-core không có lỗi mức nghiêm trọng. Không có cảnh báo mới từ lint, Semgrep hay gitleaks. Báo cáo cuối task theo mẫu trong `AGENTS.md` mục 6 nêu rõ những gì chưa làm và giả định đã chọn.

Ở mức toàn phân hệ, các chỉ tiêu phải theo dõi liên tục là LCP ≤ 2,5 giây và INP ≤ 200 ms trên mạng 4G, shell ≤ 250 KB gzip, mỗi remote ≤ 180 KB gzip, UI Pack ≤ 30 KB, và WCAG 2.1 AA. Các chỉ tiêu này được đo tự động trong pipeline (WEB-002, WEB-003) và bằng dữ liệu thật qua telemetry (WEB-011).

## 8. Môi trường phát triển dùng chung

Server dev 14.232.210.125 chạy hạ tầng dùng chung bằng Docker Compose với tên project `mission-dev`, tách biệt hoàn toàn với các stack đang có trên máy. Thông tin truy cập SSH nằm trong `.env` ở gốc repo, mật khẩu các dịch vụ nằm trong `infra/dev/.env`; cả hai đều bị `.gitignore` chặn và không bao giờ được commit. Chi tiết dịch vụ, cổng, tài khoản thử và cách vận hành nằm trong `infra/dev/README.md` và wiki trang 12.

Ổ `/u01` chứa dữ liệu Docker của server đã đầy khoảng 95%, nên toàn bộ dữ liệu của `mission-dev` được đặt dưới `/home/provision/mission-infra/data`. Trước khi bật thêm profile nặng (events, search, analytics) cần kiểm tra lại dung lượng ổ và bộ nhớ.

## 9. Rủi ro và cách giảm thiểu

| Rủi ro | Ảnh hưởng | Cách giảm thiểu |
|---|---|---|
| Backend chưa sẵn sàng khi làm màn hình | Màn hình làm xong nhưng không tích hợp được | Hợp đồng OpenAPI làm trước, interceptor giả lập (WEB-008), đổi sang API thật chỉ bằng cấu hình |
| Lệch phiên bản thư viện dùng chung giữa shell và remote | Lỗi lúc chạy (NG0203), mất trạng thái singleton | Một workspace, một `package.json`; quy tắc shell phải dùng mọi thư viện mà remote cần |
| AI agent tự sáng tác kiến trúc hoặc mã định danh | Mất truy vết với TL, khó nghiệm thu | 12 quy tắc trong `AGENTS.md`, nhắc ở ba lớp, danh sách tệp được phép sửa, duyệt PR |
| UI Pack của tenant làm vỡ giao diện hoặc gây XSS | Sự cố trên nhiều tenant | Registry đóng, kiểm chữ ký gói, CSP + Trusted Types, kiểm thử hồi quy theo bộ token |
| Hiệu năng giảm dần khi thêm màn hình | Không đạt NFR-03 khi tải cao | Ngân sách bundle làm build thất bại, Lighthouse CI, telemetry Web Vitals thật |
| Server dev dùng chung hết dung lượng | Hạ tầng dev ngừng | Dữ liệu ở `/home`, bật profile theo nhu cầu, script kiểm tra sức khoẻ |

## 10. Các mốc

| Mốc | Điều kiện đạt |
|---|---|
| M0 – Lát cắt dọc | Shell + `fe-task` + UI Pack + chế độ cao tuổi chạy được (đã đạt ngày 19/10/2026) |
| M1 – Nền tảng xong | WEB-001..WEB-015 hoàn thành, đủ khung 10 remote, pipeline có cổng chất lượng |
| M2 – Tổ chức sẵn sàng | GĐ1 xong, đăng nhập thật và cây tổ chức chạy với backend đợt 1 |
| M3 – Một quý làm việc trọn vẹn | GĐ2 xong: lập OKR, kế hoạch, giao việc, cập nhật, minh chứng, nghiệm thu, khoá kỳ |
| M4 – Đánh giá trọn vẹn | GĐ3 xong: tự đánh giá đến quyết định xếp loại có ký số |
| M5 – Sẵn sàng mở rộng | GĐ4 xong, đạt kiểm thử hiệu năng và ATTT |

## 11. Tài liệu liên quan

`AGENTS.md` (quy định chung), `docs/tasks/README.md` (danh mục task), `wiki/README.md` (mục lục tri thức), `infra/dev/README.md` (hạ tầng dev), và bộ TL gốc trong `/mnt/project-files/kien-truc-okr-kpi/`.
