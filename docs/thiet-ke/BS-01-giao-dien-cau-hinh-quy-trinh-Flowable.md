---
title: "Bổ sung BS-01: Giao diện cấu hình quy trình động trên Flowable"
subtitle: "Nền tảng đánh giá cán bộ theo OKR và KPI · Bổ sung cho TL-01, TL-02, TL-03 · Phiên bản 0.1 · 10/2026"
---

# Mục đích và phạm vi

Tài liệu này trả lời câu hỏi "phần quy trình chạy trên Flowable có giao diện để cấu hình động quy trình không". Tài liệu được viết thành tệp bổ sung riêng, chưa ghép vào bốn tệp .docx đã phát hành, để không phải build lại bộ tài liệu. Khi bộ tài liệu được phát hành phiên bản tiếp theo, nội dung ở đây sẽ được đưa vào đúng vị trí đã nêu ở mục cuối.

Bộ tài liệu hiện tại đã có giao diện cấu hình quy trình ở cấp tenant, nhưng nằm trong chương Policy Pack nên khó tìm. Phần còn thiếu là giao diện để đội sản phẩm soạn và phát hành chính các mẫu luồng; hiện việc này chỉ được mô tả là thực hiện qua kho mã và CI. Tài liệu chia làm hai phần: phần thứ nhất chỉ ra những gì đã có, phần thứ hai đề xuất thiết kế cho phần còn thiếu.

# Những gì bộ tài liệu đã có

## Cấu hình luồng ở cấp tenant (SCR-1504)

Màn hình SCR-1504 "Cấu hình luồng duyệt (mẫu + tham số)" thuộc quy trình QT-15 "Soạn, kiểm thử và phát hành Policy Pack". Màn hình nằm ở TL-02, chương "Quy trình cấu hình, phân tích và trợ lý AI", mục SCR-1504, Hình 12.6. Người dùng là quản trị nghiệp vụ của cơ quan tổ chức (AC-03, vai trò `policy_author`), và đường vào là Config Studio › thẻ Luồng duyệt.

Trên màn hình này người cấu hình chọn một mẫu luồng đã được kiểm thử, ví dụ `duyet-ho-so-danh-gia` phiên bản 3, rồi điền tham số. Các tham số gồm số cấp duyệt, vai trò và đơn vị của từng cấp, thời hạn mỗi bước, nhắc hạn, hành vi khi quá hạn (chuyển cấp phó được uỷ quyền), các bước bắt buộc ký số, ký lô và điều kiện bỏ qua cấp viết bằng CEL. Màn hình hiển thị xem trước luồng sinh từ mẫu cùng kết quả linter, và chạy bộ test của mẫu với chính bộ tham số vừa điền. Tham số được lưu thành một bản ghi `flowable.wf_binding` và phát hành cùng Policy Pack, nên nó cũng phải đạt test, được phê duyệt và chỉ có hiệu lực từ một kỳ trong tương lai.

Vì vậy câu trả lời ngắn là có: tenant thay đổi được luồng duyệt của mình mà không cần phát hành lại phần mềm. Điều tenant không làm được là tự vẽ một sơ đồ BPMN mới.

## Lý do không cho tenant vẽ BPMN tự do

Quyết định này là chủ ý của ADR-04 và quy tắc BR-15-11 (TL-02), nhằm trả lời góp ý G2 về nguy cơ "mạng nhện". Nếu mỗi tenant tự vẽ luồng, số định nghĩa BPMN sẽ tăng theo số tenant, có thể tới 5.000. Mỗi định nghĩa khi đó cần kiểm thử riêng, chiếm bộ đệm định nghĩa của engine, và rất dễ sinh ra gọi chéo tiến trình hoặc service task gọi đồng bộ ra ngoài. Đây đều là những nguyên nhân làm engine nghẽn mà TL-01 chương "Chuyên đề G2" đã phân tích.

Thay vào đó, đội sản phẩm duy trì khoảng 20 đến 40 mẫu dùng chung, còn tenant chỉ chọn mẫu và điền tham số. Cách này giữ số định nghĩa hoạt động dưới vài trăm dù số tenant tăng từ 100 lên 5.000. TL-01 đặt ngưỡng cảnh báo "chống mạng nhện" là 300 định nghĩa hoạt động và ngưỡng nghiêm trọng là 1.000.

## Dữ liệu và quy tắc quản trị đã có

TL-03, mục "Schema flowable và saga (SVC-06)", đã định nghĩa hai bảng làm nền cho việc cấu hình động. Bảng `flowable.wf_template` lưu mẫu BPMN theo phiên bản, có trạng thái `NHAP`, `HIEU_LUC`, `NGUNG` và mã triển khai trong Flowable. Bảng `flowable.wf_binding` gắn mẫu với tenant, khối đối tượng, luồng Đảng hoặc Nhà nước và loại kỳ, kèm tham số trong `params_json`.

TL-01, mục "Quy tắc quản trị chống mạng nhện", đặt các quy tắc mà linter kiểm tra tự động. Thứ nhất, cấm triển khai định nghĩa gắn `tenantId`. Thứ hai, chỉ được có tối đa một tầng call activity. Thứ ba, không gọi tiến trình chéo tenant và không dùng signal phát rộng. Thứ tư, không có service task gọi đồng bộ ra ngoài, mà mọi tác vụ máy phải là external worker.

# Phần còn thiếu và đề xuất bổ sung

## Khoảng trống

Bộ tài liệu chỉ nói mẫu luồng được phát triển trong "repo mẫu luồng" và kiểm tra bằng CI. Bộ tài liệu chưa có màn hình nào cho ba việc sau:

1. Đội sản phẩm soạn hoặc sửa một mẫu luồng mới bằng giao diện đồ hoạ, thay vì sửa tệp XML trong kho mã.
2. Đội sản phẩm kiểm thử mẫu, phê duyệt và triển khai mẫu lên engine của từng cell.
3. Quản trị nền tảng và quản trị tenant theo dõi các tiến trình đang chạy theo mẫu, ví dụ phát hiện tiến trình kẹt và chuyển người xử lý.

Đề xuất dưới đây bổ sung hai màn hình, SCR-1505 cho việc 1 và 2, và SCR-1506 cho việc 3. Mã SCR-1505 và SCR-1506 nằm trong dải của QT-15, chưa được dùng trong baseline.

## SCR-1505. Thư viện và trình soạn mẫu luồng (nội bộ nền tảng)

**Người dùng và quyền.** Màn hình chỉ dành cho đội sản phẩm phía Viettel, gắn với một vai trò mới `wf_template_author` thuộc AC-01 (quản trị nền tảng). Người phê duyệt phát hành là một người khác giữ vai trò `second_approver` (AC-09), theo nguyên tắc bốn mắt. Tenant không thấy màn hình này. Đây là điểm mấu chốt để giữ quyết định của ADR-04: giao diện đồ hoạ làm việc soạn mẫu dễ hơn, nhưng không làm số mẫu tăng theo tenant.

**Bố cục.** Màn hình có ba vùng. Vùng trái là thư viện mẫu, liệt kê mã mẫu, phiên bản, trạng thái, số tenant đang dùng (đếm từ `flowable.wf_binding`) và số test. Vùng giữa là trình soạn BPMN nhúng thư viện bpmn-js (mã nguồn mở, cùng ký pháp BPMN 2.0 mà Flowable đọc). Bảng màu của trình soạn chỉ có các phần tử được phép: user task, external worker task, exclusive gateway, parallel gateway, timer, một tầng call activity, và các khối dựng sẵn của nền tảng như "Bước ký số", "Ký lô", "Leo thang quá hạn". Vùng phải là bảng thuộc tính, gồm khai báo các tham số mà mẫu cho phép tenant điền. Mỗi tham số có kiểu, giá trị mặc định và miền giá trị, và chính khai báo này sinh ra biểu mẫu tham số trên SCR-1504.

**Kiểm tra khi soạn.** Linter chạy ngay trên trình duyệt sau mỗi thay đổi, dùng bộ quy tắc bpmnlint tuỳ biến hiện thực đúng bốn quy tắc chống mạng nhện của TL-01. Lỗi được tô đỏ trên sơ đồ. Cùng bộ quy tắc ấy cũng chạy lại ở máy chủ khi lưu, và máy chủ là nơi quyết định.

**Kiểm thử.** Mỗi mẫu có bộ test kịch bản, mỗi kịch bản gồm bộ tham số, chuỗi hành động của người dùng và trạng thái kết thúc mong đợi. Nút "Chạy test" gửi mẫu tới một engine Flowable kiểm thử dùng riêng (không phải engine của cell), chạy toàn bộ kịch bản với tham số của mọi `wf_binding` đang dùng mẫu đó, rồi báo kết quả. Nhờ vậy, một thay đổi mẫu làm hỏng cấu hình của bất kỳ tenant nào đều bị chặn trước khi phát hành.

**Vòng đời phiên bản.** Trạng thái của `flowable.wf_template` được mở rộng thành `NHAP` → `DAT_KIEM_THU` → `CHO_PHE_DUYET` → `HIEU_LUC` → `NGUNG`, tương tự vòng đời Policy Pack. Phiên bản đã lên `HIEU_LUC` không sửa được nữa; mọi thay đổi tạo phiên bản mới. Các tiến trình đang chạy tiếp tục theo phiên bản cũ cho tới khi kết thúc, và Flowable vốn giữ định nghĩa theo phiên bản nên điều này có sẵn. Chuyển `wf_binding` của một tenant sang phiên bản mới là thao tác trên SCR-1504, có hiệu lực từ kỳ sau.

**Đồng bộ với kho mã.** Giao diện không thay thế kho mã mà ghi vào nó. Khi người soạn bấm "Đề nghị phát hành", hệ thống tạo một merge request vào repo mẫu luồng, kèm tệp BPMN, khai báo tham số và bộ test. CI chạy lại linter và test, người phê duyệt duyệt merge request, và pipeline ký số gói mẫu (cosign) rồi ghi `wf_template` sang `HIEU_LUC`. Cách này giữ một nguồn sự thật duy nhất và giữ vết kiểm toán trong Git như mọi thay đổi mã khác.

**Triển khai lên cell.** Sau khi phát hành, saga "phát hành mẫu luồng" của SVC-06 triển khai định nghĩa lên engine của từng cell theo thứ tự cuốn chiếu giống phát hành phần mềm trong TL-04: cell thí điểm CELL-NB trước, sau đó các cell nhóm tỉnh, và CELL-HCM, CELL-DTW sau cùng. Mỗi bước kiểm tra trạng thái khoẻ của engine trước khi sang cell tiếp theo, và có bước bù trừ là đặt lại phiên bản trước làm mặc định.

## SCR-1506. Giám sát tiến trình theo mẫu

Màn hình này cho quản trị tenant (AC-02) xem các tiến trình của tenant mình, và cho quản trị nền tảng (AC-01) xem tổng hợp theo cell. Dữ liệu đọc từ `flowable.wf_task_projection` và các chỉ số mà TL-01 đã định nghĩa cho engine, không truy vấn trực tiếp bảng `ACT_*` lúc cao điểm. Màn hình cung cấp ba thao tác có kiểm soát. Thao tác thứ nhất là chuyển người xử lý của một user task khi cán bộ nghỉ hoặc chuyển công tác. Thao tác thứ hai là gia hạn thời hạn của một bước. Thao tác thứ ba là xem sơ đồ tiến trình với bước hiện tại, dùng lại API `/diagram` đã có ở QT-08. Mọi thao tác đều ghi sổ cái. Màn hình không cho sửa biến tiến trình hay nhảy bước tuỳ ý, vì làm thế sẽ phá tính tái lập của hồ sơ đánh giá.

## Giới hạn để không ảnh hưởng mức tải 150 user task mỗi giây mỗi cell

Giao diện mới không được đưa thêm tải vào đường găng của engine. Thiết kế đảm bảo điều này bằng bốn giới hạn.

Thứ nhất, linter chặn ngay từ khi soạn những cấu trúc làm tăng chi phí mỗi user task: service task đồng bộ, call activity lồng nhiều tầng, vòng lặp đa thể hiện (multi-instance) không có giới hạn số phần tử. Một mẫu được phép có tối đa 12 user task trên một nhánh và tối đa 50 phần tử đa thể hiện mỗi lần. Hai con số này là đề xuất và cần được đo lại bằng kịch bản thử tải của TL-04.

Thứ hai, mỗi mẫu trước khi phát hành phải chạy kịch bản thử tải rút gọn trên engine kiểm thử. Ngân sách là không quá 20 câu lệnh SQL cho mỗi lần hoàn thành user task. Con số này suy ra từ ước tính của TL-01: khoảng 2.250 câu lệnh mỗi giây chia cho 150 lần hoàn thành mỗi giây, tức khoảng 15 câu lệnh, cộng dư địa. Mẫu vượt ngân sách không được phát hành.

Thứ ba, tổng số định nghĩa đang hoạt động tiếp tục được giám sát bằng ngưỡng 300 và 1.000 của TL-01. Nếu giao diện mới khiến số mẫu tăng nhanh, cảnh báo sẽ kích hoạt việc rà soát gộp mẫu.

Thứ tư, triển khai định nghĩa lên engine chỉ diễn ra ngoài giờ cao điểm và không trong ba ngày cuối kỳ, vì đó là lúc tải user task cao nhất và sổ cái đang niêm phong 5 phút một lần.

## Thay đổi dữ liệu đề xuất (bổ sung cho TL-03)

Bảng `flowable.wf_template` được bổ sung bốn cột. Cột `param_schema` kiểu `jsonb` lưu khai báo tham số dạng JSON Schema, dùng để sinh biểu mẫu trên SCR-1504 và kiểm tra `wf_binding.params_json`. Cột `test_suite_ref` kiểu `varchar(128)` lưu mã commit của bộ test trong repo mẫu luồng. Cột `approved_by` kiểu `uuid` lưu người phê duyệt phát hành. Cột `signature` kiểu `bytea` lưu chữ ký gói mẫu. Ràng buộc CHECK của cột `status` được mở rộng thêm `DAT_KIEM_THU` và `CHO_PHE_DUYET`.

Bảng mới `flowable.wf_template_rollout` lưu tiến độ triển khai một phiên bản mẫu lên từng cell. Bảng gồm các cột `wf_template_id`, `cell_code`, `flowable_deployment_id`, `status` (`CHO`, `DA_TRIEN_KHAI`, `LOI`, `DA_BU_TRU`) và `deployed_at`. Bảng `flowable.wf_binding` được bổ sung `version_no` và `status` (`NHAP`, `HIEU_LUC`, `NGUNG`) để thể hiện rõ trạng thái "nháp" mà SCR-1504 đang nhắc tới. Sự kiện mới đề xuất là `config.wf_template.published.v1`, do SVC-06 phát khi một phiên bản mẫu lên `HIEU_LUC`.

# Vị trí ghép vào bộ tài liệu khi phát hành phiên bản sau

| Nội dung | Vị trí ghép |
|---|---|
| Đặc tả SCR-1505, SCR-1506 và mockup | TL-02, chương "Quy trình cấu hình, phân tích và trợ lý AI", sau SCR-1504 |
| Vai trò `wf_template_author` | Baseline mục vai trò, và TL-01 phần phân quyền |
| Vòng đời phiên bản mẫu, saga phát hành mẫu | TL-01, Chuyên đề G2, sau "Quy tắc quản trị chống mạng nhện" |
| Cột và bảng mới của schema flowable | TL-03, mục "Schema flowable và saga (SVC-06)" |
| Kịch bản thử tải rút gọn cho từng mẫu | TL-04, nhóm PERF, cạnh kịch bản thử tải workflow-service |
