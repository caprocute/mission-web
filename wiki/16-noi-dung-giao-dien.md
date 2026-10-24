> Nguồn: `AGENTS.md` quy tắc 9; yêu cầu của người dùng ngày 19/10/2026 (thread "Chuẩn hoá nội dung giao diện"); TL-02 (thuật ngữ nghiệp vụ QT-00 đến QT-18, tên trạng thái, thông điệp MSG); baseline mục 9 (trạng thái chuẩn); QĐ 366-QĐ/TW và các phụ lục; NĐ 335/2025, NĐ 233/2026 (tên mức xếp loại).

# Nội dung giao diện

Giao diện web Mission là sản phẩm dùng hằng ngày của cán bộ, công chức, viên chức và đảng viên, không phải bản trình diễn giải pháp. Trang này quy định những gì được và không được hiện lên màn hình, cách gọi tên các khái niệm, và hai công cụ kiểm tra tự động bắt các lỗi nội dung thường gặp. Mọi màn hình mới hoặc sửa đổi đều phải theo trang này; khi trang này lệch với TL-02 về tên gọi nghiệp vụ, TL-02 thắng và trang này phải được sửa.

## Nguyên tắc chung

Thứ nhất, màn hình chỉ hiện thông tin người dùng cần để làm việc: dữ liệu nghiệp vụ, hướng dẫn thao tác, căn cứ pháp lý và hệ quả của thao tác. Lý do kỹ thuật, cách hệ thống hiện thực, tên dịch vụ, tên bảng, tên sự kiện hay tiến độ phát triển phần mềm không phải là thông tin người dùng cần, nên không được in ra. Những ghi chú đó, nếu cần cho người phát triển, đặt trong chú thích mã nguồn.

Thứ hai, văn phong phải nghiêm túc, chỉn chu, đơn giản và dễ hiểu như một văn bản hành chính ngắn: câu đầy đủ, có dấu, không viết tắt tuỳ tiện, không dùng tiếng lóng hay từ tiếng Anh khi đã có từ tiếng Việt tương đương. Xưng hô thống nhất là "đồng chí" (ví dụ "Đồng chí chưa có phân công công tác"), không dùng "bạn" hay "anh/chị".

Thứ ba, mã định danh của bộ thiết kế (SCR, QT, BR, MSG, ADR, SVC, MOD, EVT, NFR, SEC, AC, TL, WEB…) là công cụ truy vết của đội phát triển và không bao giờ hiện cho người dùng cuối. Quy tắc giữ nguyên mã định danh ở `AGENTS.md` quy tắc 2 áp dụng cho mã nguồn, chú thích, commit và tài liệu; trên giao diện, mã chỉ được đặt trong thuộc tính `data-*` (ví dụ `data-scr="SCR-0901"`) để kiểm thử tra cứu. Khi một câu trên giao diện diễn đạt một quy tắc nghiệp vụ BR, giữ nội dung quy tắc bằng lời và bỏ mã.

Thứ tư, giá trị trạng thái và mã loại của hợp đồng API (`DA_GIAO`, `CHO_THAM_DINH`, `NHAP`, `MO`, `DANG`…) không hiện thô. Mỗi giá trị hiện bằng nhãn nghiệp vụ lấy từ bảng nhãn có sẵn trong `@vt/api-client` (`TASK_STATUS_LABEL`, `PERIOD_STATUS_LABEL`, `EVIDENCE_STATUS_LABEL`…); nếu chưa có bảng nhãn thì bổ sung cạnh model tương ứng, dùng đúng tên trạng thái trong TL-02.

Thứ năm, thông báo lỗi nói điều đã xảy ra và người dùng cần làm gì, bằng lời nghiệp vụ. Không hiện mã HTTP, tên trường API, mã lỗi máy chủ hay chuỗi ngoại lệ; thông điệp lấy từ `ProblemPresenter` (RFC 9457) đã được chuyển thành câu tiếng Việt. Mã tra cứu sự cố (`traceId`) chỉ hiện ở dạng "Mã tra cứu: …" khi người dùng cần báo cho bộ phận hỗ trợ.

Thứ sáu, mã định danh nghiệp vụ mà người dùng thật sự dùng thì được hiện, vì đó là dữ liệu chứ không phải mã kỹ thuật: mã nhiệm vụ (`NV-2026-Q4-0142`), số, ký hiệu văn bản (`87-KH/ĐUK`), mã yêu cầu mở lại kỳ (`MLK-2026-007`), mã khiếu nại, mã đơn vị, số CCCD đã che. Mã định danh nội bộ (UUID, khoá chống lặp, mã saga, mã phân vùng) thì không hiện; nếu người dùng cần tham chiếu thì hiện tên hoặc mã nghiệp vụ.

Thứ bảy, các viết tắt chuẩn của nghiệp vụ được dùng: OKR, KPI, KR (kết quả then chốt), O (mục tiêu), CSDL, CBCCVC, và tên viết tắt các mức xếp loại HTXSNV, HTTNV, HTNV, KHTNV trong ô bảng hẹp (kèm `title` ghi tên đầy đủ); trong câu văn thì viết tên đầy đủ. Viết tắt căn cứ pháp lý được viết đầy đủ khi hiện cho người dùng: "Phụ lục 6, mục 12" thay cho "PL 6 §12", "Bảng 3, Phụ lục 1" thay cho "Bảng 3 PL 1", "Điều 11 Quy định 366" thay cho "QĐ Điều 11".

Thứ tám, màn hình hay phân hệ chưa phát triển xong không nói về kế hoạch phát triển, mã màn hình hay tên remote. Trang giữ chỗ chỉ ghi tên chức năng và câu "Chức năng này đang được chuẩn bị đưa vào sử dụng." Thông báo lỗi nạp phân hệ ghi tên chức năng bằng tiếng Việt, không ghi tên kỹ thuật của remote.

## Bảng từ thay thế

Bảng sau là cách gọi thống nhất trên giao diện cho các khái niệm vốn có tên kỹ thuật trong TL. Cột bên phải là chữ hiện cho người dùng; trong mã nguồn, tài liệu và chú thích vẫn dùng tên kỹ thuật như TL.

| Tên kỹ thuật (mã nguồn, TL) | Hiện trên giao diện |
|---|---|
| Tenant | Đơn vị triển khai (danh sách đơn vị triển khai, khởi tạo đơn vị triển khai) |
| Cell | Cụm máy chủ; mã cụm (`CELL-HN`) là dữ liệu, được hiện ở màn hình quản trị nền tảng |
| Silo, bridge, pool | Mức cô lập: "Cụm riêng", "CSDL riêng", "Dùng chung" |
| Policy Pack | Bộ quy định đánh giá (ví dụ "Bộ quy định đánh giá khối Đảng, phiên bản 2.0") |
| UI Pack | Bộ giao diện của đơn vị |
| Config Studio | Cấu hình nghiệp vụ |
| Saga khoá kỳ, bước bù trừ | Tiến trình khoá kỳ, hoàn tác |
| Lô (batch), worker, superseded | Lô tính điểm; bỏ "worker"; "đánh dấu không còn hiệu lực" |
| BPMN, sơ đồ luồng | Sơ đồ quy trình |
| IdP, Keycloak, OIDC | Hệ thống đăng nhập |
| SCIM | Đồng bộ tài khoản tự động |
| JWKS, chứng chỉ ký token | Khoá xác thực đăng nhập |
| MFA, step-up | Xác thực nhiều lớp |
| Dữ liệu C2, C3, mã hoá trường | "Nội dung được mã hoá khi lưu"; không ghi "C2", "C3" |
| JSON (tệp xuất) | Tệp dữ liệu (ghi rõ "kèm chữ ký số" nếu có) |
| Merkle, chuỗi băm, RFC 3161 | Niêm phong, chuỗi kiểm tra toàn vẹn, dấu thời gian được chứng thực |
| Sự kiện (`task.task.assigned.v1`, `NhiemVuDaGiao`) | Tên thao tác bằng lời: "Giao nhiệm vụ", "Nghiệm thu minh chứng" |
| Tên trường dữ liệu (`status`, `valid_from`, `level`) | Tên nghiệp vụ: "Trạng thái", "Hiệu lực từ", "Mức xếp loại" |
| Hệ số A, B, C của công thức điểm | Khối lượng (A), Chất lượng (B), Tiến độ (C) |
| SL duyệt/KH | Số lượng được nghiệm thu / kế hoạch |
| Check-in | Giữ "check-in" (thuật ngữ OKR chuẩn, TL-02 dùng), có thể ghi "cập nhật tiến độ" trong câu hướng dẫn |
| Mã băm (`entry_hash`, `prev_hash`, `payload_hash`, mã băm phiếu) | Mã kiểm tra, mã của bản ghi trước, mã kiểm tra nội dung, mã kiểm tra toàn vẹn |
| Gốc Merkle (của tenant, của cell) | Mã niêm phong, mã niêm phong của cụm máy chủ |
| Bản neo (anchor), `OBJECT_LOCK` | Giữ "bản neo" kèm giải thích là bản sao niêm phong do cơ quan độc lập giữ; kho lưu trữ chống ghi đè |
| Chuỗi băm bị phá vỡ | Sổ cái mất toàn vẹn, nội dung đã bị sửa |
| Topic, loại sự kiện sổ cái | Thao tác (ví dụ "Giao nhiệm vụ") |
| Tác nhân `DICH_VU` | Hệ thống (xử lý tự động) |
| Bảng, cột được mở lại | Nhóm dữ liệu, nội dung được phép sửa |
| Ghi vào sổ cái (check-in) | Được lưu vết, không sửa được sau khi gửi |
| Tầng 2, tầng 3 của cây mục tiêu | Cấp phòng, ban; cá nhân |
| Phiên bản Policy Pack (`policyVersion`) | "Bộ quy định đánh giá <tên>, phiên bản x.y" |
| Lỗi chặn trình duyệt | Lỗi cần sửa trước khi trình (tránh nhầm với "trình duyệt" web) |
| Thiếu `If-Match` (428), xung đột phiên bản | "Dữ liệu trên màn hình đã cũ. Đồng chí vui lòng tải lại rồi thực hiện lại thao tác." |
| Mô hình thuê bao (silo, bridge, pool) | Mức cô lập |
| Golden test case, test case vàng | Bộ ca kiểm thử chuẩn |
| KEK, HSM | Khoá mã hoá dữ liệu, thiết bị HSM |
| Organization trong Keycloak | Không gian đăng nhập của đơn vị |
| Saga cấp phát tenant | Tiến trình cấp phát hạ tầng |
| Dead letter | Dữ liệu lỗi được lưu lại để chạy lại |
| Lỗi lược đồ (schema) | Bản ghi sai cấu trúc |
| Mã ngoài, mã nội bộ (đồng bộ nhân sự) | Mã tại nguồn, hồ sơ trên hệ thống |
| Ngân sách thời gian của NFR | Thời hạn cho phép |
| OIDC, SAML | Liên kết chuẩn OpenID Connect, liên kết chuẩn SAML2 (chỉ ở màn hình cấu hình đăng nhập của quản trị) |

Bảng được bổ sung khi gặp khái niệm mới; khi bổ sung, sửa luôn mục tương ứng ở [Thuật ngữ](13-thuat-ngu.md) nếu có.

## Kiểm tra tự động

Có hai lớp kiểm tra, dùng chung bộ quy tắc ở `frontend/tools/ui-text/rules.mjs` (mã thiết kế, trạng thái thô, tên service, tên bảng, tên trường, tên sự kiện, thuật ngữ kỹ thuật, mã lỗi HTTP, UUID, ghi chú giải pháp, xưng hô).

Lớp thứ nhất là kiểm tra tĩnh `npm run check:ui-text` (tệp `frontend/tools/ui-text/check.mjs`). Công cụ phân tích template Angular bằng trình biên dịch của Angular để lấy nút văn bản, thuộc tính tĩnh và chuỗi trong biểu thức, rồi phân tích mã TypeScript để lấy các chuỗi dạng câu, kể cả dữ liệu giả lập trong `api-client/src/mock`. Chuỗi dùng để so sánh trạng thái, khoá quyền, đường dẫn, biểu thức CEL và chú thích được bỏ qua. Công cụ còn bắt mẫu tra bảng nhãn rồi rơi về chính mã khi bảng thiếu giá trị (`LABEL[t] ?? t`, luật `nhan-roi-ve-ma-tho`): máy chủ thật có thể trả giá trị bảng nhãn chưa có hoặc khác hoa thường (sổ cái trả `KIEM_TRA_SO_CAI` viết hoa trong khi bảng nhãn khoá chữ thường), và khi đó mã thô lộ ra màn hình dù dữ liệu giả lập vẫn hiện đúng; chỗ rơi về phải là tên chung như "Đối tượng khác". Khi một chuỗi bị bắt nhầm, đặt chú thích `ui-text-ok: <lý do>` ngay trên dòng đó (TypeScript) hoặc trong `<!-- -->` ngay trên phần tử (HTML); người duyệt PR kiểm lý do.

Lớp thứ hai là kiểm thử đầu cuối `e2e/gia-lap.noi-dung.spec.ts`, chạy cùng `npm run e2e` ở chế độ giả lập. Kiểm thử mở mọi tuyến của shell và remote với người dùng mang mọi vai trò, đọc văn bản đang hiện cùng `aria-label`, `title`, `placeholder`, `alt`, rồi đối chiếu cùng bộ quy tắc. Lớp này bắt được lỗi mà kiểm tra tĩnh không thấy, ví dụ `{{ task.status }}` in ra `DA_GIAO` hay dữ liệu máy chủ chứa tên trường. Tuyến chi tiết cần mã có thật của dữ liệu giả lập được khai trong `e2e/noi-dung/<nhóm>.json` (một mảng đường dẫn), mỗi nhóm màn hình một tệp để các luồng không sửa chung một tệp.

Một task giao diện chỉ hoàn thành khi cả hai lớp đều xanh với các màn hình của task (bổ sung vào tiêu chí ở [Kiểm thử và chất lượng](11-kiem-thu-va-chat-luong.md)).

## Bố cục và mức độ thông tin (YC-PT-24)

Ngày 24/10/2026, người phụ trách yêu cầu giao diện được thiết kế như một sản phẩm hoàn chỉnh cho người dùng cuối (YC-PT-24 trong sổ tay yêu cầu). Ngoài việc không lộ mã thiết kế, mỗi màn hình phải tuân thủ các điểm sau, và đợt rà soát cùng ngày đã sửa toàn bộ màn hình theo các điểm này:

- Đầu trang dùng lớp chung `.vt-page-head`, gồm tiêu đề, một dòng mô tả ngắn và các nút thao tác ở bên phải. Không dùng băng tiêu đề nền tối, dòng khẩu hiệu hay mô tả quảng bá ("Khung nền tảng", "nền tảng lõi").
- Trang tổng hợp dẫn tới các chức năng con dùng lưới thẻ `.vt-hub`, mỗi thẻ có biểu tượng, tên và một dòng mô tả.
- Không in lời giải thích cách hệ thống vận hành (máy chủ kiểm tra lại, ghi sổ cái, tính mã băm, chạy lúc 02:00, số lô, tốc độ xử lý, ngân sách dung lượng, quy trình phiên bản N). Quy tắc nghiệp vụ được thể hiện bằng ràng buộc trên ô nhập và bằng thông báo lỗi đúng lúc; căn cứ thiết kế đặt trong chú thích mã nguồn.
- Không hiện số đánh dấu vùng của bản phác thảo, mã băm, khoá kỹ thuật như mã gói chính sách (`nn-335/so-y-te`). Trùng minh chứng gọi là "trùng nội dung".
- Bảng phải có cột tên đủ rộng (không ép tên cán bộ xuống nhiều dòng); cột ít dùng có thể ẩn mặc định bằng `hiddenByDefault` trong `ListColumnDef`, người dùng bật lại qua cá nhân hoá danh sách.
- Bảng phụ, khung đăng ký nhanh hoặc khung xem nhanh chỉ mở khi người dùng yêu cầu, không mở sẵn.
- Ghi chú nguồn của nội dung do AI tạo ("Nguồn: …") là yêu cầu minh bạch nghiệp vụ nên được giữ lại.

## Ví dụ sửa

Câu "Nhiệm vụ chuyển trạng thái Đã giao, phát sự kiện NhiemVuDaGiao (EVT-04); người chủ trì và phối hợp nhận thông báo" được sửa thành "Nhiệm vụ chuyển sang trạng thái Đã giao; người chủ trì và người phối hợp nhận được thông báo." Phần "phát sự kiện" là cách hệ thống hiện thực nên bị bỏ.

Câu "Tiến độ nhiệm vụ không tự cộng vào giá trị KR (BR-03-07)" được sửa thành "Tiến độ nhiệm vụ không tự cộng vào giá trị KR." Nội dung quy tắc được giữ, mã quy tắc chuyển vào chú thích mã nguồn.

Dòng phụ đề "Nguồn: org-service (SVC-04) · dữ liệu hai trục thời gian" được sửa thành "Cơ cấu tổ chức theo thời gian hiệu lực; có thể xem lại cơ cấu tại một ngày bất kỳ."

Cột trạng thái in `DANG_THUC_HIEN` được sửa để in `TASK_STATUS_LABEL[status]`, tức "Đang thực hiện".

## Liên quan

- [Quy ước mã nguồn](10-quy-uoc-ma-nguon.md)
- [Kiểm thử và chất lượng](11-kiem-thu-va-chat-luong.md)
- [Thuật ngữ](13-thuat-ngu.md)
- [Hiệu năng và trải nghiệm](09-hieu-nang-va-trai-nghiem.md)
