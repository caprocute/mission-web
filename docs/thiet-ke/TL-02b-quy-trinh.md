# Quy trình đánh giá, xếp loại và hồ sơ số

Chương này đặc tả ba quy trình tạo ra kết quả có giá trị pháp lý của nền tảng: đánh giá và xếp loại theo quý (QT-09), xếp loại năm cùng phân nhóm triển vọng (QT-10), kiểm điểm và lập hồ sơ số (QT-11). Ba quy trình này nhận đầu vào từ các quy trình ở chương trước, cụ thể là mục tiêu O và KR đã duyệt (QT-02), kế hoạch quý đã duyệt (QT-04), nhiệm vụ và tiến độ (QT-05, QT-06), minh chứng đã nghiệm thu (QT-07) và sự kiện khoá kỳ (QT-08). Đầu ra của chúng là phiếu xếp loại, quyết định có ký số và gói hồ sơ quý, năm được lưu trữ lâu dài.

Điểm chung của cả ba quy trình là nguyên tắc "hệ thống tính, con người quyết định". Điểm số và mức đề xuất do scoring-service (SVC-07) tính theo Policy Pack đang gắn với kỳ, không ai sửa tay được. Ngược lại, mức thẩm định, mức đề xuất và mức quyết định vẫn là ý chí của người có thẩm quyền, được lưu riêng và phải có giải trình khi khác với mức trước đó. Cách tách này đáp ứng đồng thời yêu cầu của dự thảo Quy định mới (QĐ Điều 16 về lưu bốn mức) và tiêu chí I6 của bản đánh giá kiến trúc (kết quả tính lại được bất kỳ lúc nào).

Về số liệu minh hoạ, toàn bộ mockup và ví dụ trong chương dùng chung một hồ sơ mẫu để người đọc đối chiếu: đồng chí Trần Minh Hoàng, Trưởng phòng Tổng hợp, Ban Tổ chức Đảng uỷ khối, thuộc tenant "Đảng uỷ các cơ quan Đảng TW" (CELL-DTW), kỳ Quý IV/2026. Hồ sơ có bốn nhiệm vụ then chốt (KPI nhóm 0,908, quy ra 63,6 trên 70 điểm) và năm nhiệm vụ thường xuyên (KPI nhóm 0,920, quy ra 27,6 trên 30 điểm), tổng 91,2 điểm. Tỷ lệ nhiệm vụ vượt mục tiêu là 2/9, tức 22%, thấp hơn ngưỡng 30% nên hệ thống hạ mức từ "Hoàn thành xuất sắc nhiệm vụ" xuống "Hoàn thành tốt nhiệm vụ". Ví dụ này được dùng xuyên suốt từ QT-09 tới QT-13 và cả ở bảng điều hành của QT-16.

## QT-09. Tự đánh giá, thẩm định, đề xuất, quyết định xếp loại quý

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-09 |
| Tên | Tự đánh giá, thẩm định, đề xuất, quyết định xếp loại quý |
| Mục đích | Lập hồ sơ đánh giá quý cho từng cán bộ, tính điểm theo Policy Pack, thu nhận lần lượt bốn mức (tự đề xuất, thẩm định, đề xuất, quyết định) có giải trình và ký số, ra quyết định xếp loại hàng loạt |
| Phạm vi áp dụng | Luồng Đảng (100 điểm) cho cán bộ lãnh đạo, quản lý và đảng viên thuộc diện đánh giá quý; luồng Nhà nước (30/70) cho công chức, viên chức khi tenant bật; cả hai luồng dùng chung dữ liệu sản phẩm, minh chứng |
| Căn cứ | Dự thảo Quy định mới Điều 10, Điều 11 (tiêu chí chung, điều kiện các mức), khoản 11.2.4 (điều kiện loại trừ), khoản 14.3.3 (người đứng đầu), Điều 16 khoản 16.3 (lưu bốn mức, giải trình); PL 3 (A, B, C); PL 6 §12 (chuyển mức liền kề); Mẫu 2, Mẫu 3; NĐ 335/2025 cho luồng Nhà nước; Luật Giao dịch điện tử 2023 (ký số) |
| Tác nhân | AC-06 cán bộ được đánh giá; AC-05 quản lý trực tiếp (đề xuất); AC-07 cơ quan tổ chức (thẩm định); AC-08 cấp có thẩm quyền (quyết định); SVC-07 scoring-service; SVC-06 workflow-service; SVC-10 esign-service |
| Tần suất và khối lượng ước tính | Mỗi quý một lần. Ước tính theo baseline mục 3.1: khoảng 6 triệu hồ sơ quý toàn quốc khi mọi tenant dùng, cell lớn nhất khoảng 1,2 triệu hồ sơ; mỗi hồ sơ khoảng 4 user task (tự đánh giá, thẩm định, đề xuất, quyết định), 30% dồn vào 3 ngày cuối, đỉnh khoảng 80 đến 110 user task mỗi giây ở cell lớn (số ước tính) |
| Điều kiện bắt đầu | Kỳ quý ở trạng thái `DA_KHOA` sau saga chốt kỳ (QT-08, EVT-09) và scoring-service đã phát EVT-10 cho hồ sơ |
| Điều kiện kết thúc | Hồ sơ đạt `DA_KY_SO` (quyết định đã ký) và được khoá `DA_KHOA` khi hết hạn khiếu nại hoặc khi gói hồ sơ được ký (QT-11) |
| Đầu vào | Kế hoạch quý đã duyệt, nhiệm vụ và kết quả nghiệm thu (`product.acceptance`), minh chứng `DA_XAC_NHAN`, mục tiêu O và KR, lịch ngày nghỉ của tenant, Policy Pack gắn với kỳ |
| Đầu ra | `appraisal.score_snapshot`, `appraisal.rating_proposal` (bốn mức), `appraisal.rating_explanation`, quyết định xếp loại có số và ký số, sự kiện EVT-11, EVT-12 |
| Chỉ số đo hiệu quả quy trình | Tỷ lệ hồ sơ hoàn tất đúng hạn (mục tiêu ≥ 98%); thời gian trung bình từ khoá kỳ tới quyết định (mục tiêu ≤ 10 ngày làm việc); tỷ lệ hồ sơ có khác mức giữa tự đề xuất và quyết định; tỷ lệ khiếu nại trên số hồ sơ; thời gian ký lô 100.000 phiếu ≤ 4 giờ (NFR-06) |

Trong hệ thống hiện tại, theo bản đánh giá kiến trúc (tiêu chí H4), công thức tính đúng nhưng tồn tại ba bộ máy tính song song, chỉ có kỳ quý và chỉ lưu một mức xếp loại cuối cùng. Hệ quả là khi có khiếu nại, không ai trả lời được câu hỏi "ai đã đề xuất mức nào, vì sao khác". QT-09 giải quyết điều đó bằng cách tách hai phần việc. Phần tính toán do scoring-service thực hiện một lần cho mỗi hồ sơ và lưu thành ảnh chụp điểm (snapshot) có mã băm đầu vào và phiên bản Policy Pack. Phần ý chí của con người được lưu thành bốn bản ghi riêng trong `appraisal.rating_proposal`, mỗi bản ghi có người thực hiện, thời điểm, lý do và chữ ký số.

Quy trình chạy trên workflow-service (SVC-06, Flowable 7) bằng mẫu luồng `duyet-ho-so-danh-gia` phiên bản 3, được tham số hoá theo tenant và khối qua bảng `flowable.wf_binding` (xem QT-15, SCR-1504). Nhờ vậy, Đảng uỷ khối dùng bốn cấp duyệt còn một xã chỉ dùng hai cấp mà không phải nhân bản định nghĩa BPMN. Đây là biện pháp chính của ADR-04 để tránh "mạng nhện" luồng mà góp ý G2 lo ngại.

### Lưu đồ quy trình

![Hình B1.1. Lưu đồ làn bơi QT-09: tự đánh giá, thẩm định, đề xuất và quyết định xếp loại quý](nv-b-qt09-luong.png){width=16cm}

Lưu đồ gồm bốn làn. Làn "Hệ thống" khởi đầu khi nhận sự kiện KyDaKhoa (EVT-09): MOD-04 lập hồ sơ ở trạng thái `CHUA_LAP`, scoring-service tính điểm cho cả luồng Đảng và luồng Nhà nước rồi lưu ảnh chụp điểm, sau đó bảng quyết định DMN xếp loại sinh mức đề xuất của hệ thống kèm câu giải thích. Chỉ khi có ảnh chụp điểm, hồ sơ mới chuyển sang `TU_DANH_GIA` và cán bộ mới mở được phiếu. Ở làn "Cán bộ", người được đánh giá đánh dấu tiêu chí chung, chọn mức tự đề xuất, ghi giải trình rồi ký số và nộp. Làn "Cơ quan tổ chức" thẩm định, so sánh với tự đánh giá; nếu mức thẩm định khác mức tự đề xuất thì bắt buộc ghi giải trình trước khi ký. Thẩm định viên có thể trả lại để cán bộ bổ sung. Làn cuối là quản lý trực tiếp và cấp quyết định: đề xuất mức, quyết định hàng loạt và ký lô. Khi ký lô hoàn tất, esign-service phát EVT-18 và MOD-04 phát EVT-12 XepLoaiDaQuyetDinh để MOD-06 lập hồ sơ số và ledger-service ghi sổ cái.

Lưu đồ cố ý không có nhánh "sửa điểm". Điểm chỉ thay đổi khi dữ liệu đầu vào thay đổi qua một quy trình có kiểm soát: trước khi khoá kỳ là QT-07 (nghiệm thu lại), sau khi khoá kỳ là QT-13 (mở lại kỳ hai người duyệt).

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Lập hồ sơ đánh giá | Hệ thống (MOD-04) | Khi nhận EVT-09, tạo một `appraisal.appraisal_profile` cho mỗi người thuộc diện đánh giá của kỳ, theo luồng (`DANG`, `NHA_NUOC`) mà tenant bật; áp 13 trường hợp đặc thù PL 6 nếu phát hiện | Không có | Hồ sơ `CHUA_LAP` | Trong 15 phút sau khoá kỳ |
| 2 | Tính điểm | SVC-07 | Đọc kết quả nghiệm thu, minh chứng `DA_XAC_NHAN`, lịch ngày nghỉ; tính A, B, C, KPI nhiệm vụ, KPI nhóm, điểm; chạy bảng DMN xếp loại; lưu snapshot có `policy_version` và `input_hash` | Không có | `scoring.score_result`, `appraisal.score_snapshot`; EVT-10 | Cell lớn ≤ 2 giờ (NFR-06) |
| 3 | Mở phiếu tự đánh giá | AC-06 | Hồ sơ chuyển `TU_DANH_GIA`; cán bộ nhận thông báo; mở phiếu thấy điểm hệ thống tính, mức tham khảo và câu giải thích | SCR-0901 | EVT-11, EVT-21 | Hạn nộp do tham số kỳ, mặc định 3 ngày làm việc |
| 4 | Đánh dấu tiêu chí chung | AC-06 | Đánh dấu từng tiêu chí đáp ứng hay không; hệ thống quy đổi tỷ lệ ra 4 mức | SCR-0901 | `appraisal.general_criteria_result` | Trong hạn bước 3 |
| 5 | Tự đề xuất mức, giải trình | AC-06 | Chọn mức tự đề xuất; nếu khác mức tham khảo thì ghi giải trình; nếu đề xuất HTXSNV thì mô tả giải pháp đột phá kèm minh chứng | SCR-0901 | `appraisal.rating_proposal` (TU_DE_XUAT), `appraisal.self_assessment` | Trong hạn bước 3 |
| 6 | Ký số và nộp | AC-06, SVC-10 | Sinh bản xem trước Mẫu 1B hoặc Mẫu 2, ký số qua MySign hoặc Viettel-CA; hồ sơ chuyển `CHO_THAM_DINH` | SCR-0901 | EVT-18, EVT-11 | Trong hạn bước 3 |
| 7 | Thẩm định | AC-07 | Xem hàng đợi; so sánh tự đánh giá với dữ liệu nghiệm thu; đọc gợi ý bất thường; chọn mức thẩm định, ghi nhận xét | SCR-0902 | `appraisal.rating_proposal` (THAM_DINH) | 3 ngày làm việc |
| 8 | Giải trình khác mức | AC-07 | Nếu mức thẩm định khác mức tự đề xuất thì bắt buộc ghi lý do trước khi ký | SCR-0902 | `appraisal.rating_proposal.reason_enc` | Trong bước 7 |
| 9 | Ký thẩm định hoặc trả lại | AC-07 | Ký số chuyển `DA_THAM_DINH`; hoặc trả lại bổ sung (hồ sơ về `TU_DANH_GIA`, giữ nguyên các bản ghi cũ) | SCR-0902 | EVT-18, EVT-11 | Trong bước 7 |
| 10 | Đề xuất mức | AC-05 hoặc tập thể lãnh đạo | Xem phiếu xếp loại có giải thích; chọn mức đề xuất; bước này có thể được bỏ qua theo tham số mẫu luồng | SCR-0903 | `appraisal.rating_proposal` (DE_XUAT); `CHO_QUYET_DINH` | 2 ngày làm việc |
| 11 | Quyết định hàng loạt | AC-08 | Xem danh sách theo đơn vị; cột quyết định mặc định bằng mức đề xuất; đổi mức thì ghi lý do; xem phân bố mức | SCR-0904 | `appraisal.rating_proposal` (QUYET_DINH) | 3 ngày làm việc |
| 12 | Ký lô quyết định | AC-08, SVC-10 | Nhập số, ngày quyết định; một lần xác thực cho cả lô; mỗi hồ sơ một chữ ký PAdES riêng; tiến độ hiển thị trực tiếp | SCR-0904 | `appraisal.batch_decision`; EVT-18; hồ sơ `DA_KY_SO`; EVT-12 | Lô 100.000 phiếu ≤ 4 giờ |
| 13 | Công bố và thông báo | Hệ thống | Gửi kết quả cho cán bộ; mở thời hạn khiếu nại (mặc định 10 ngày làm việc) | SCR-0903 | EVT-21 | Ngay sau bước 12 |
| 14 | Khoá hồ sơ | Hệ thống | Hết hạn khiếu nại mà không có khiếu nại, hoặc gói hồ sơ được ký (QT-11), hồ sơ chuyển `DA_KHOA` | Không có | EVT-11; ghi sổ cái | Theo tham số |

Các bước 1 và 2 hoàn toàn tự động và chạy dưới dạng tác vụ nền. scoring-service tiêu thụ EVT-09 theo phân vùng `tenantId`, co giãn bằng KEDA theo độ trễ consumer, nên một cell 1,2 triệu hồ sơ được tính trong khoảng thời gian NFR-06 cho phép mà không ảnh hưởng tới các API trực tuyến. Kết quả mỗi hồ sơ được ghi một lần vào `appraisal.score_snapshot`; nếu cùng đầu vào được tính lại (ví dụ do consumer xử lý lặp), `input_hash` trùng nên bảng inbox bỏ qua, bảo đảm tính luỹ đẳng.

Các bước 3 đến 12 là user task của Flowable. Danh sách việc mà người dùng thấy trên SCR-0002 và các màn hình hàng đợi không truy vấn trực tiếp bảng `ACT_RU_TASK` mà đọc bảng chiếu `flowable.wf_task_projection`, được cập nhật bằng sự kiện. Cách này giữ cho engine chỉ phải xử lý thao tác hoàn thành task (khoảng 80 đến 110 lần mỗi giây ở cell lớn), còn tải đọc danh sách việc dồn về PostgreSQL bản sao đọc.

Thời hạn từng bước là tham số của mẫu luồng, không viết cứng. Bảng trên ghi giá trị mặc định của khối Đảng. Khi quá hạn, mẫu luồng tự chuyển cho cấp phó được uỷ quyền hoặc nhắc lại theo tham số "Quá hạn" ở SCR-1504.

### Trạng thái đối tượng

![Hình B1.2. Sơ đồ trạng thái hồ sơ đánh giá appraisal.appraisal_profile](nv-b-qt09-trangthai.png){width=14cm}

Sơ đồ bám đúng baseline mục 9.1. Hồ sơ đi tuần tự từ `CHUA_LAP` tới `DA_KHOA`, có hai vòng quay lui là "trả lại bổ sung" (từ `CHO_THAM_DINH` về `TU_DANH_GIA`) và "yêu cầu xem lại" (từ `CHO_QUYET_DINH` về `DA_THAM_DINH`). Nhánh `KHIEU_NAI` chỉ mở sau khi đã ký quyết định; trong thời gian khiếu nại, kết quả vẫn có hiệu lực và hồ sơ không thể bị khoá.

| Trạng thái hiện tại | Sự kiện hoặc hành động | Trạng thái kế tiếp | Điều kiện kiểm soát |
|---|---|---|---|
| (chưa có) | EVT-09 KyDaKhoa | `CHUA_LAP` | Người thuộc diện đánh giá của kỳ theo `org_replica.assignment_v` tại ngày khoá kỳ |
| `CHUA_LAP` | EVT-10 DiemDaTinh | `TU_DANH_GIA` | Có snapshot hợp lệ cho mọi luồng mà tenant bật |
| `TU_DANH_GIA` | Ký số và nộp | `CHO_THAM_DINH` | Đủ trường bắt buộc; có giải trình nếu tự đề xuất khác mức tham khảo; chữ ký hợp lệ |
| `CHO_THAM_DINH` | Trả lại bổ sung | `TU_DANH_GIA` | Có lý do trả lại; số lần trả lại ≤ 2 (tham số) |
| `CHO_THAM_DINH` | Ký số thẩm định | `DA_THAM_DINH` | Người thẩm định khác người được đánh giá; có giải trình nếu khác mức |
| `DA_THAM_DINH` | Đề xuất mức hoặc bỏ qua bước | `CHO_QUYET_DINH` | Theo điều kiện bỏ qua cấp 3 trong `flowable.wf_binding` |
| `CHO_QUYET_DINH` | Yêu cầu xem lại | `DA_THAM_DINH` | Có lý do |
| `CHO_QUYET_DINH` | Quyết định | `DA_QUYET_DINH` | Người quyết định có vai trò `decider` với đơn vị; không phải người thẩm định |
| `DA_QUYET_DINH` | EVT-18 KySoDaHoanTat | `DA_KY_SO` | Chữ ký hợp lệ, dấu thời gian RFC 3161 |
| `DA_KY_SO` | EVT-25 KhieuNaiDaGui | `KHIEU_NAI` | Trong thời hạn khiếu nại |
| `KHIEU_NAI` | Kết luận giữ nguyên | `DA_KY_SO` | Có văn bản trả lời đã ký |
| `KHIEU_NAI` | Kết luận có điều chỉnh | `DA_KHOA` | Điều chỉnh thi hành qua QT-13 |
| `DA_KY_SO` | Hết hạn khiếu nại hoặc khoá gói | `DA_KHOA` | Không còn khiếu nại mở |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-09-01 | KPI của một nhiệm vụ bằng (A + B + C) / 3, trong đó A = min(số lượng sản phẩm được duyệt / số lượng kế hoạch, 1), B là mức chất lượng nghiệm thu theo 5 mức 1 / 0,75 / 0,5 / 0,25 / 0, C = max(0, 1 − 0,05 × số ngày làm việc chậm do chủ quan) | PL 3 mục 1, 2, 3 | Công thức CEL trong Policy Pack, chạy trên scoring-service bằng cel-java; người dùng không sửa được; mỗi thành phần có vết tính trong `scoring.score_trace` |
| BR-09-02 | Chỉ sản phẩm có minh chứng ở trạng thái `DA_XAC_NHAN` được đếm vào A; minh chứng bị cảnh báo trùng mà chưa xác nhận hợp lệ không được đếm | PL 4 III.5 | scoring-service lọc theo `product.evidence_version.status` và cờ chặn của `oversight.anomaly_alert` |
| BR-09-03 | Điểm kết quả nhiệm vụ = Σ (KPI nhóm × điểm tối đa nhóm); KPI nhóm là trung bình KPI các nhiệm vụ trong nhóm; tổng điểm tối đa các nhóm bằng 100; làm tròn 1 chữ số thập phân, nửa lên | PL A3; Bảng chấm điểm | Tham số nhóm và quy tắc làm tròn trong Policy Pack; kiểm tra tổng 100 khi soạn (SCR-1501) |
| BR-09-04 | Phần vượt mục tiêu không cộng thêm điểm (A tối đa bằng 1) mà được ghi nhận riêng thành tỷ lệ nhiệm vụ vượt mục tiêu | PL A3 | Trường `ty_le_vuot` trong snapshot, dùng làm đầu vào bảng DMN |
| BR-09-05 | Tiêu chí chung không chấm điểm; tỷ lệ đáp ứng 100% là "Thực hiện tốt", từ 90% đến dưới 100% là "Đảm bảo", từ 60% đến dưới 90% là "Cơ bản đảm bảo", dưới 60% là "Không đảm bảo" | QĐ Điều 10; PL A3 | Quy đổi tự động trên SCR-0901 và trong DMN; nhãn hiển thị theo biểu mẫu (PL A4) nhưng cùng một dữ liệu gốc |
| BR-09-06 | Mức xếp loại cuối cùng là mức thấp hơn giữa mức theo kết quả nhiệm vụ và mức theo tiêu chí chung | QĐ Điều 11; PL 6 §12 | Hàm `min_muc` trong bảng DMN (SCR-1502) |
| BR-09-07 | HTXSNV khi đủ đồng thời: ≥ 90 điểm, hoàn thành 100% mục tiêu O, ≥ 30% nhiệm vụ vượt mục tiêu, có giải pháp đột phá được kiểm chứng, tiêu chí chung "Thực hiện tốt" | QĐ Điều 11 | Dòng 4 bảng DMN; ngưỡng 30% và cách đếm (theo số nhiệm vụ) là tham số theo PL A4 |
| BR-09-08 | HTTNV khi ≥ 80 điểm, không có nhiệm vụ then chốt nào không hoàn thành do chủ quan, tiêu chí chung từ "Đảm bảo" trở lên | QĐ Điều 11 | Dòng 5 bảng DMN |
| BR-09-09 | HTNV khi từ 60 đến dưới 80 điểm và tiêu chí chung từ "Cơ bản đảm bảo" trở lên; KHTNV khi dưới 60 điểm, hoặc tiêu chí chung "Không đảm bảo", hoặc rơi vào điều kiện loại trừ | QĐ Điều 11, khoản 11.2.4 | Dòng 1, 2, 3, 6 bảng DMN; hit policy FIRST |
| BR-09-10 | Hồ sơ đủ điểm nhưng thiếu điều kiện của mức đề xuất thì chuyển xuống mức liền kề, và hệ thống phải sinh câu giải thích nêu điều kiện thiếu, giá trị thực tế, số điều khoản và phiên bản Policy Pack | PL 6 §12; PL A3 | Mẫu câu giải thích gắn với từng dòng DMN; lưu `appraisal.rating_explanation`; hiển thị ở SCR-0903 |
| BR-09-11 | Luồng Nhà nước dùng thang 30 điểm tiêu chí chung và 70 điểm KPI, ngưỡng ≥ 90, 70 đến dưới 90, 50 đến dưới 70, dưới 50; tính trên cùng dữ liệu sản phẩm với luồng Đảng nhưng lưu kết quả riêng | NĐ 335/2025; PL A5 | Hai snapshot, mỗi snapshot một `policy_version`; không ghi đè nhau |
| BR-09-12 | Lưu riêng bốn mức tự đề xuất, thẩm định, đề xuất, quyết định; mức sau khác mức trước liền kề thì người chọn mức sau phải giải trình | QĐ khoản 16.3; Mẫu 3 | Bốn bản ghi `appraisal.rating_proposal` theo `level_kind`; API từ chối chuyển bước khi thiếu `reason` (lỗi 422) |
| BR-09-13 | Người đứng đầu bị xếp KHTNV khi tập thể không đạt 05 chỉ tiêu được giao, hoặc không đạt tất cả mục tiêu O, hoặc trên 50% đơn vị do mình phụ trách bị xếp KHTNV | QĐ khoản 14.3.3 | Quy tắc riêng trong DMN, đọc kết quả tập thể và tỷ lệ đơn vị KHTNV; hồ sơ người đứng đầu chỉ quyết định sau khi các đơn vị trực thuộc đã có mức |
| BR-09-14 | Tách vai trò: với cùng một hồ sơ, người được đánh giá, người nghiệm thu, người thẩm định, người quyết định phải là những người khác nhau | PL 4 V.1 | Chính sách OPA kiểm tra khi giao task và khi gọi API ghi |
| BR-09-15 | Ký số tại các mốc: phiếu tự đánh giá, kết quả thẩm định, quyết định xếp loại | Luật GDĐT 2023; tiêu chí I4 | Bước chuyển trạng thái chỉ hoàn tất khi nhận EVT-18 tương ứng |
| BR-09-16 | Điểm chỉ do hệ thống tính; không có chức năng sửa điểm trực tiếp | PL 4 III.2; tiêu chí I6 | Không có API ghi `score_snapshot` từ giao diện; mọi thay đổi đầu vào phải qua QT-07 hoặc QT-13 |
| BR-09-17 | Tỷ lệ HTXSNV của một đơn vị vượt ngưỡng tham số của khối (mặc định 20%) thì cảnh báo và đề nghị phiên hiệu chỉnh trước khi ký lô | Dự thảo về tỷ lệ xuất sắc; PL 4 V.6 | Kiểm tra trên SCR-0904; đồng thời job dấu hiệu số 2 ở QT-14 |

Các quy tắc BR-09-01 đến BR-09-11 thuộc Policy Pack, nghĩa là có phiên bản, có test case vàng và có thể khác nhau giữa các khối. Các quy tắc BR-09-12 đến BR-09-16 là quy tắc nền tảng, viết bằng mã trong MOD-04 và chính sách OPA, không cấu hình được, vì chúng bảo đảm tính toàn vẹn và khả năng truy vết mà mọi tenant đều phải tuân thủ.

Đối với hồ sơ mẫu, quy tắc BR-09-10 sinh câu giải thích: "Đủ điểm mức HTXSNV (91,2 ≥ 90) nhưng chưa đạt điều kiện 30% nhiệm vụ vượt mục tiêu (thực tế 22%, theo QĐ Điều 11), nên chuyển xuống mức liền kề theo PL 6 §12. Policy Pack Đảng v2.0." Câu này không do người viết mà được ghép từ mẫu câu của dòng DMN thứ 5 với giá trị thực tế, nhờ đó mọi hồ sơ bị hạ mức đều có lời giải thích cùng cấu trúc.

### Ngoại lệ và xử lý

Trường hợp thứ nhất là hồ sơ chưa có snapshot do scoring-service lỗi hoặc dữ liệu đầu vào thiếu (ví dụ nhiệm vụ chưa nghiệm thu khi khoá kỳ). Hồ sơ giữ ở `CHUA_LAP`, job tính điểm ghi lỗi vào `scoring.score_job` và đưa vào hàng đợi thử lại; sau 3 lần thất bại, hệ thống tạo việc cho cơ quan tổ chức trên SCR-0801 để xử lý thủ công. Cán bộ thấy thông báo MSG-09-01 thay vì phiếu trống.

Trường hợp thứ hai là cán bộ thuộc một trong 13 trường hợp đặc thù của PL 6, chẳng hạn nghỉ thai sản quá nửa kỳ. MOD-04 đọc cờ `dossier.special_case` khi lập hồ sơ và áp cách xử lý của Policy Pack: không lập hồ sơ quý, hoặc lập hồ sơ nhưng đánh dấu "không xếp loại", hoặc giao cho đơn vị cũ đánh giá và đơn vị mới cho ý kiến. Chi tiết trình bày ở QT-11.

Trường hợp thứ ba là cán bộ không nộp phiếu đúng hạn. Mẫu luồng tự nhắc trước 1 ngày; quá hạn, hồ sơ vẫn chuyển sang thẩm định với cờ "không tự đánh giá", mức tự đề xuất để trống và được ghi rõ trên Mẫu 3. Hệ thống không tự điền mức thay cán bộ.

Trường hợp thứ tư là ký số thất bại (chứng thư hết hạn, dịch vụ ký từ xa không phản hồi). Trạng thái hồ sơ không chuyển; esign-service thử lại theo cơ chế lùi dần, và nếu là ký lô thì các hồ sơ đã ký giữ nguyên `DA_KY_SO`, hồ sơ chưa ký vẫn ở `CHO_QUYET_DINH`. Người ký có thể tiếp tục lô sau khi khắc phục mà không phải ký lại các hồ sơ đã xong.

Trường hợp thứ năm là có xung đột cập nhật đồng thời, ví dụ hai thẩm định viên cùng mở một hồ sơ. API cập nhật yêu cầu `If-Match` theo `row_version`; người lưu sau nhận lỗi 409 và MSG-09-07, phải tải lại để xem thay đổi của người kia.

Trường hợp thứ sáu là thay đổi tổ chức trong kỳ, chẳng hạn cán bộ được điều động sau ngày khoá kỳ. Hồ sơ vẫn thuộc đơn vị tại ngày khoá kỳ, vì MOD-04 đọc `org_replica.assignment_v` theo hai trục thời gian (tiêu chí I7); người thẩm định là cơ quan tổ chức của đơn vị cũ.

### Đặc tả màn hình

#### SCR-0901. Phiếu tự đánh giá

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho cán bộ xem kết quả hệ thống tính, đánh dấu tiêu chí chung, tự đề xuất mức, ghi giải trình và ký số nộp phiếu |
| Tác nhân | AC-06 (`member`) |
| Đường vào | Trang chủ "Việc của tôi hôm nay" (SCR-0002), thẻ "Tự đánh giá quý"; menu Đánh giá › Phiếu tự đánh giá; thông báo đẩy |
| Quyền | Chỉ chủ hồ sơ được ghi khi hồ sơ ở `TU_DANH_GIA`; quản lý trực tiếp xem chỉ đọc |
| Thiết bị | Web desktop, máy tính bảng; trên di động dùng luồng rút gọn (SCR-1803 khi bật chế độ cao tuổi) |

![Hình B1.3. SCR-0901 Phiếu tự đánh giá quý IV/2026 của hồ sơ mẫu](SCR-0901.png){width=16cm}

Màn hình chia ba cột theo thứ tự công việc của người dùng: đọc tiêu chí chung, xem kết quả nhiệm vụ, rồi quyết định mức tự đề xuất. Thanh bước phía trên cho biết hồ sơ đang ở bước 1/5 và các thẻ luồng cho phép chuyển giữa luồng Đảng và luồng Nhà nước mà không rời màn hình.

Vùng 1 là các thẻ luồng. Thẻ "Luồng Đảng · 100 điểm" ghi rõ Policy Pack Đảng v2.0, thẻ "Luồng Nhà nước · 30/70" ghi Policy Pack NĐ 335 v1.3. Hai thẻ hiển thị cùng danh sách nhiệm vụ nhưng khác cách quy đổi điểm. Vùng 2 là danh sách 9 tiêu chí chung dạng hộp đánh dấu; hệ thống tính tỷ lệ đáp ứng và quy đổi mức tức thời, kèm dòng nhắc quy đổi 4 mức. Vùng 3 là bảng kết quả nhiệm vụ do hệ thống tính, có nhãn "Hệ thống tính · không sửa tay" và liên kết "Vết tính" mở chi tiết A, B, C của từng nhiệm vụ. Vùng 4 là khối tự đề xuất mức: hộp chọn mức, cảnh báo khi mức tự đề xuất cao hơn mức tham khảo, ô giải pháp đột phá, ô giải trình và ô ưu điểm, hạn chế. Vùng 5 là nhóm nút ở góc phải trên, trong đó "Ký số và nộp" chỉ bật khi đủ trường bắt buộc.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Luồng đánh giá | Thẻ chọn | Có | `DANG` hoặc `NHA_NUOC` | `DANG` | Chỉ hiện luồng tenant bật | `appraisal.appraisal_profile.flow` |
| 2 | Tiêu chí chung (9 dòng) | Hộp đánh dấu | Có | Boolean mỗi tiêu chí | Đáp ứng | Danh mục tiêu chí theo Policy Pack và khối đối tượng | `appraisal.general_criteria_result.met` |
| 3 | Tỷ lệ đáp ứng | Số, chỉ đọc | Không áp dụng | Phần trăm, 0 chữ số thập phân | Tính tự động | Bằng số tiêu chí đáp ứng / tổng | Tính trên client bằng cel-js, kiểm lại ở máy chủ |
| 4 | Mức tiêu chí chung | Nhãn, chỉ đọc | Không áp dụng | 4 giá trị | Tính tự động | Theo BR-09-05 | `appraisal.general_criteria_result.level` |
| 5 | Bảng nhiệm vụ (tên, SL duyệt/KH, B, C, KPI) | Bảng, chỉ đọc | Không áp dụng | Số 2 hoặc 3 chữ số thập phân | Từ snapshot | Không sửa được | `appraisal.score_snapshot.detail_json`, `scoring.score_trace` |
| 6 | KPI nhóm và điểm nhóm | Số, chỉ đọc | Không áp dụng | 3 chữ số (KPI), 1 chữ số (điểm) | Từ snapshot | Theo BR-09-03 | `appraisal.score_snapshot.group_scores_json` |
| 7 | Điểm kết quả nhiệm vụ | Số, chỉ đọc | Không áp dụng | 0,0 đến 100,0 | Từ snapshot | Theo BR-09-03 | `appraisal.score_snapshot.total_score` |
| 8 | Mức hệ thống tham khảo | Nhãn | Không áp dụng | HTXSNV, HTTNV, HTNV, KHTNV | Từ DMN | Theo BR-09-06 đến BR-09-10 | `appraisal.rating_explanation.system_level` |
| 9 | Mức tự đề xuất | Danh sách chọn | Có | 4 giá trị | Bằng mức tham khảo | Khác mức tham khảo thì bật trường 11 bắt buộc | `appraisal.rating_proposal.level` (level_kind = TU_DE_XUAT) |
| 10 | Giải pháp đột phá | Văn bản dài | Có khi chọn HTXSNV | ≤ 2.000 ký tự, kèm ít nhất 1 minh chứng | Trống | Phải gắn minh chứng `DA_XAC_NHAN` hoặc văn bản ghi nhận | `appraisal.self_assessment.breakthrough_enc` |
| 11 | Giải trình khác mức tham khảo | Văn bản dài | Có khi trường 9 khác trường 8 | 20 đến 2.000 ký tự | Trống | Không được để trống hoặc dưới 20 ký tự | `appraisal.rating_proposal.reason_enc` |
| 12 | Ưu điểm, hạn chế, hướng khắc phục | Văn bản dài | Có | ≤ 2.000 ký tự, C2 | Trống | Mã hoá trường, tự lưu nháp 30 giây một lần | `appraisal.self_assessment.content_enc` |
| 13 | Hạn nộp, số ngày làm việc còn lại | Nhãn | Không áp dụng | dd/mm/yyyy | Từ tham số kỳ | Tính theo `config.holiday_calendar` | `config.period.self_assess_due` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Xem trước Mẫu 1B | Luôn hiện | Sinh bản xem trước từ dữ liệu hiện tại, có dấu "Bản nháp" | `POST /api/v1/dossiers/forms/preview` | Không có |
| Lưu nháp | Hồ sơ ở `TU_DANH_GIA` | Lưu trường 2, 9 đến 12; kiểm tra `If-Match` | `PUT /api/v1/appraisals/profiles/{id}/self-assessment` | Không có |
| Vết tính | Luôn hiện | Mở ngăn bên hiển thị A, B, C và nguồn dữ liệu của từng nhiệm vụ | `GET /api/v1/appraisals/profiles/{id}/score-trace` | Không có |
| Chuyển thẻ luồng | Tenant bật hai luồng | Tải snapshot của luồng còn lại | `GET /api/v1/appraisals/profiles/{id}/scores?flow=NHA_NUOC` | Không có |
| Ký số và nộp | Đủ trường bắt buộc | Kiểm tra quy tắc, tạo yêu cầu ký, chờ EVT-18 rồi chuyển `CHO_THAM_DINH` | `POST /api/v1/appraisals/profiles/{id}/submit` (202), `POST /api/v1/esign/requests` | EVT-11, EVT-18 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-09-01 | Kết quả quý của đồng chí đang được tính, vui lòng quay lại sau. Thời điểm dự kiến hoàn tất: {thoi_diem}. | Thông tin | Hồ sơ `CHUA_LAP` |
| MSG-09-02 | Mức tự đề xuất khác mức hệ thống tham khảo. Đồng chí cần ghi giải trình trước khi nộp (QĐ Điều 16). | Cảnh báo | Chọn mức khác trường 8 |
| MSG-09-03 | Đề xuất mức Hoàn thành xuất sắc cần mô tả giải pháp đột phá và gắn ít nhất một minh chứng đã được xác nhận. | Lỗi kiểm tra | Chọn HTXSNV mà thiếu trường 10 |
| MSG-09-04 | Đã nộp phiếu tự đánh giá và ký số thành công lúc {thoi_diem}. Phiếu đã chuyển sang bước thẩm định. | Thành công | Nhận EVT-18 |
| MSG-09-05 | Ký số không thành công: {ly_do}. Phiếu chưa được nộp, dữ liệu đã nhập vẫn được giữ. | Lỗi | esign-service trả lỗi |

#### SCR-0902. Thẩm định

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho cơ quan tổ chức thẩm định lần lượt các hồ sơ trong hàng đợi, so sánh tự đánh giá với dữ liệu nghiệm thu, chọn mức thẩm định, giải trình và ký số |
| Tác nhân | AC-07 (`appraiser`) |
| Đường vào | Menu Đánh giá › Thẩm định; thông báo việc mới; SCR-1601 thẻ "Hồ sơ chờ thẩm định" |
| Quyền | `appraiser` của đơn vị chứa hồ sơ theo ABAC cây tổ chức; không thẩm định hồ sơ của chính mình hoặc hồ sơ mình đã nghiệm thu |
| Thiết bị | Web desktop (màn hình lõi viết mã, không cấu hình SDUI) |

![Hình B1.4. SCR-0902 Thẩm định hồ sơ đánh giá, so sánh tự đánh giá và kết quả thẩm định](SCR-0902.png){width=16cm}

Màn hình theo bố cục "hàng đợi bên trái, chi tiết bên phải" để thẩm định viên làm liên tục nhiều hồ sơ. Hồ sơ đang chọn hiển thị bốn thẻ số liệu: điểm tự tính theo minh chứng đã nộp (93,4), điểm sau thẩm định (91,2, giảm 2,2 do điều chỉnh B, C), mức tiêu chí chung và mức hệ thống tính.

Vùng 1 là hàng đợi thẩm định, mỗi dòng gồm tên, chức vụ, điểm hệ thống và mức tự đề xuất; dòng có nhãn cam "Tự chấm cao 3 quý" là gợi ý từ dấu hiệu bất thường số 1 của QT-14. Vùng 2 là phần đầu hồ sơ với trạng thái `CHO_THAM_DINH`, thông tin chữ ký và mã băm phiếu đã nộp, cùng nhãn gợi ý tím. Gợi ý này chỉ giúp thẩm định viên chú ý chứ không thay đổi kết quả. Vùng 3 là bảng so sánh B, C tự đánh giá với B, C thẩm định cho từng nhiệm vụ then chốt; dòng có điều chỉnh hiển thị căn cứ màu đỏ (ví dụ "B: phải sửa nội dung cơ bản, biên bản nghiệm thu 18/12"). Vùng 4 gồm thẻ "Kết luận thẩm định" và thẻ "Giải trình bắt buộc"; thẻ sau tự hiện khi mức thẩm định khác mức tự đề xuất và chặn nút ký khi ô lý do trống.

Cần lưu ý rằng cột "B tự" và "C tự" là giá trị cán bộ đề nghị khi nộp minh chứng (ví dụ cho rằng nhiệm vụ không chậm), còn cột thẩm định lấy từ `product.acceptance` do người nghiệm thu ghi. Thẩm định viên không sửa B, C ở màn hình này; nếu thấy nghiệm thu sai, họ trả lại hồ sơ kèm đề nghị nghiệm thu lại qua QT-07 khi kỳ còn mở, hoặc chuyển hậu kiểm khi kỳ đã khoá.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Bộ lọc hàng đợi | Danh sách chọn | Không | Trạng thái, đơn vị | Chờ thẩm định | Chỉ đơn vị trong phạm vi ABAC | `flowable.wf_task_projection` |
| 2 | Dòng hàng đợi | Bảng | Không áp dụng | Tên, chức vụ, điểm, mức | Sắp theo hạn | Phân trang con trỏ, tối đa 200 | `appraisal.appraisal_profile`, `org_replica.person_v` |
| 3 | Thông tin nộp và chữ ký | Nhãn | Không áp dụng | Thời điểm, nhà cung cấp, băm | Từ hồ sơ | Kiểm tra chữ ký hợp lệ khi mở | `esign.sign_item`, `appraisal.appraisal_profile.submitted_at` |
| 4 | Điểm tự tính, điểm sau thẩm định | Số | Không áp dụng | 1 chữ số thập phân | Từ snapshot | Chỉ đọc | `appraisal.score_snapshot` |
| 5 | Bảng so sánh B, C | Bảng | Không áp dụng | 2 chữ số thập phân | Từ dữ liệu | Chỉ đọc; tô đỏ khi khác | `appraisal.self_assessment.claims_json`, `product.acceptance.quality_level`, `product.acceptance.delay_days` |
| 6 | Mức thẩm định | Danh sách chọn | Có | 4 giá trị | Bằng mức hệ thống | Khác mức tự đề xuất thì bật trường 8 | `appraisal.rating_proposal.level` (THAM_DINH) |
| 7 | Nhận xét thẩm định | Văn bản dài | Có | ≤ 4.000 ký tự, C2 | Trống | Mã hoá trường | `appraisal.rating_proposal.comment_enc` |
| 8 | Lý do khác mức tự đề xuất | Văn bản dài | Có khi khác mức | 20 đến 2.000 ký tự | Trống | Theo BR-09-12 | `appraisal.rating_proposal.reason_enc` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Chọn hồ sơ trong hàng đợi | Luôn | Tải chi tiết, kiểm tra quyền OPA | `GET /api/v1/appraisals/profiles/{id}` | Không có |
| Mở minh chứng | Luôn | Mở trình xem minh chứng theo phiên bản, ghi nhật ký truy cập C2 | `GET /api/v1/evidences/{id}/versions/{v}` | Không có |
| Trả lại bổ sung | Hồ sơ `CHO_THAM_DINH` | Bắt buộc lý do; hoàn thành user task với biến `ket_qua = TRA_LAI` | `POST /api/v1/appraisals/profiles/{id}/return` | EVT-11, EVT-21 |
| Ký số và chuyển đề xuất | Đủ trường 6, 7 và trường 8 nếu cần | Lưu bản ghi THAM_DINH, ký số, hoàn thành user task | `POST /api/v1/appraisals/profiles/{id}/appraisal` (202) | EVT-18, EVT-11 |
| Xuất danh sách | Luôn | Xuất bảng hàng đợi ra XLSX (không gồm trường C2) | `POST /api/v1/appraisals/exports` (202) | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-09-06 | Mức thẩm định khác mức tự đề xuất. Theo QĐ Điều 16, đồng chí cần ghi lý do trước khi ký. | Cảnh báo | Chọn mức khác |
| MSG-09-07 | Hồ sơ vừa được người khác cập nhật. Vui lòng tải lại để xem thay đổi trước khi lưu. | Lỗi | API trả 409 |
| MSG-09-08 | Đồng chí không thể thẩm định hồ sơ này vì đã tham gia nghiệm thu một nhiệm vụ của hồ sơ (PL 4 V.1). | Lỗi phân quyền | OPA từ chối |
| MSG-09-09 | Đã trả lại hồ sơ cho đồng chí {ten} bổ sung. Lần trả lại thứ {n}/{toi_da}. | Thành công | Trả lại xong |

#### SCR-0903. Phiếu xếp loại có giải thích

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Trình bày điểm theo cấu phần, các điều kiện của từng mức, mức đề xuất của hệ thống và câu giải thích vì sao hạ mức; là nơi quản lý trực tiếp chọn mức đề xuất và cán bộ xem kết quả sau quyết định |
| Tác nhân | AC-06 xem kết quả của mình; AC-05 chọn mức đề xuất; AC-07, AC-08 xem |
| Đường vào | Từ SCR-0902 sau thẩm định; menu Đánh giá › Kết quả xếp loại; thông báo kết quả |
| Quyền | Chủ hồ sơ, quản lý trực tiếp, thẩm định viên và cấp quyết định của đơn vị; dữ liệu C2 |
| Thiết bị | Web desktop và di động (bố cục một cột trên di động) |

![Hình B1.5. SCR-0903 Phiếu xếp loại có giải thích lý do hạ mức](SCR-0903.png){width=16cm}

Đây là màn hình hiện thực hoá yêu cầu "bộ máy quy tắc phải tự giải thích vì sao một hồ sơ bị hạ mức" của PL A3. Người xem đọc từ trên xuống sẽ thấy điểm 91,2, cách cộng thành 27,6 và 63,6, rồi thấy ngay điều kiện nào đạt, điều kiện nào chưa đạt, và cuối cùng là câu kết luận có số điều khoản.

Vùng 1 là bảng điểm theo cấu phần: hai nhóm nhiệm vụ với KPI nhóm, điểm tối đa và điểm; hai dòng ghi nhận riêng là tỷ lệ việc vượt 22% và tỷ lệ hoàn thành mục tiêu O 100%, với chú thích "ghi nhận riêng, không cộng điểm". Vùng 2 là danh sách điều kiện của mức HTXSNV và HTTNV dạng dấu tích; điều kiện "≥ 30% nhiệm vụ vượt mục tiêu (thực tế 22%)" có nhãn đỏ "Chưa đạt". Vùng 3 là ghi chú luồng Nhà nước: cùng dữ liệu sản phẩm, 28,0 điểm tiêu chí chung cộng 70 × 0,912 bằng 63,8, tổng 91,8 điểm, lưu riêng. Vùng 4 là khối "Mức đề xuất của hệ thống" có viền đỏ, chứa câu giải thích sinh từ mẫu câu của DMN. Vùng 5 là nhãn phiên bản "Policy Pack Đảng v2.0" ở đầu trang, luôn hiển thị để người xem biết bộ quy tắc nào đã được áp dụng. Dưới cùng là dải "Vết các mức đề xuất" thể hiện chuỗi tự đề xuất HTXSNV (04/01), thẩm định HTTNV (07/01), giải trình bắt buộc đã ghi, đề xuất HTTNV (08/01) và chờ quyết định, cùng mã snapshot và băm đầu vào.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Điểm kết quả nhiệm vụ | Số | Không áp dụng | 1 chữ số thập phân | Từ snapshot | Chỉ đọc | `appraisal.score_snapshot.total_score` |
| 2 | Bảng cấu phần | Bảng | Không áp dụng | KPI 3 chữ số, điểm 1 chữ số | Từ snapshot | Chỉ đọc | `appraisal.score_snapshot.group_scores_json` |
| 3 | Tỷ lệ việc vượt, tỷ lệ mục tiêu O | Số | Không áp dụng | Phần trăm | Từ snapshot | Chỉ đọc | `appraisal.score_snapshot.over_target_ratio`, `appraisal.score_snapshot.objective_ratio` |
| 4 | Danh sách điều kiện các mức | Danh sách | Không áp dụng | Đạt hoặc chưa đạt kèm giá trị | Từ DMN | Chỉ đọc | `appraisal.rating_explanation.conditions_json` |
| 5 | Mức đề xuất của hệ thống | Nhãn | Không áp dụng | 4 giá trị | Từ DMN | Chỉ đọc | `appraisal.rating_explanation.system_level` |
| 6 | Câu giải thích | Văn bản | Không áp dụng | ≤ 1.000 ký tự | Sinh tự động | Chứa số điều khoản và phiên bản | `appraisal.rating_explanation.text` |
| 7 | Phiên bản Policy Pack | Nhãn | Không áp dụng | Mã gói và số phiên bản | Từ snapshot | Chỉ đọc | `appraisal.score_snapshot.policy_version` |
| 8 | Vết các mức | Dải chip | Không áp dụng | Mức, ngày, người | Từ dữ liệu | Chỉ đọc | `appraisal.rating_proposal` (4 bản ghi) |
| 9 | Mã snapshot và băm đầu vào | Nhãn | Không áp dụng | UUIDv7, SHA-256 rút gọn | Từ snapshot | Chỉ đọc | `appraisal.score_snapshot.id`, `appraisal.score_snapshot.input_hash` |
| 10 | Mức đề xuất của quản lý | Danh sách chọn | Có với AC-05 ở bước đề xuất | 4 giá trị | Bằng mức thẩm định | Khác mức thẩm định thì bắt buộc lý do | `appraisal.rating_proposal.level` (DE_XUAT) |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Chuyển thẻ Luồng Nhà nước | Tenant bật | Hiển thị snapshot luồng Nhà nước | `GET /api/v1/appraisals/profiles/{id}/scores?flow=NHA_NUOC` | Không có |
| Thẻ Vết tính | Luôn | Hiển thị vết tính từng bước của scoring-service | `GET /api/v1/appraisals/profiles/{id}/score-trace` | Không có |
| Tải Mẫu 1B (PDF/A) | Sau khi có quyết định | Tải bản đã ký từ document-service | `GET /api/v1/dossiers/forms/{formId}/file` | Không có |
| Ghi mức đề xuất | AC-05, hồ sơ `DA_THAM_DINH` | Lưu bản ghi DE_XUAT, hoàn thành user task | `POST /api/v1/appraisals/profiles/{id}/proposal` | EVT-11 |
| Gửi khiếu nại | Chủ hồ sơ, hồ sơ `DA_KY_SO`, còn hạn | Mở SCR-1201 | Không có (điều hướng) | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-09-10 | Kết quả này được tính theo Policy Pack {ma_goi} phiên bản {phien_ban}, áp dụng cho kỳ {ky}. | Thông tin | Di chuột vào nhãn phiên bản |
| MSG-09-11 | Kết quả xếp loại quý {ky} của đồng chí đã được quyết định: {muc}. Thời hạn khiếu nại đến {han}. | Thông báo đẩy | Nhận EVT-12 |
| MSG-09-12 | Mức đề xuất khác mức thẩm định. Vui lòng ghi lý do (QĐ khoản 16.3). | Cảnh báo | AC-05 chọn mức khác |

#### SCR-0904. Quyết định xếp loại hàng loạt và ký số

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho cấp có thẩm quyền xem toàn bộ hồ sơ đến bước quyết định của một đơn vị hoặc khối, kiểm tra phân bố mức, quyết định và ký lô một lần cho cả danh sách |
| Tác nhân | AC-08 (`decider`); thư ký tổng hợp hỗ trợ chuẩn bị nhưng không ký |
| Đường vào | Menu Đánh giá › Quyết định xếp loại; thông báo "Có 48 hồ sơ chờ quyết định" |
| Quyền | `decider` của đơn vị; MFA bước lên khi ký lô |
| Thiết bị | Web desktop |

![Hình B1.6. SCR-0904 Quyết định xếp loại hàng loạt và ký lô](SCR-0904.png){width=16cm}

Màn hình giải quyết bài toán quy mô của bước quyết định: một Ban Thường vụ có thể quyết định vài trăm hồ sơ trong một phiên họp, nên không thể ký từng phiếu. Bảng chính bên trái liệt kê đủ bốn cột mức (tự đề xuất, thẩm định, đề xuất, quyết định) để người quyết định thấy chỗ khác nhau mà không phải mở từng hồ sơ.

Vùng 1 là bảng hồ sơ với hộp chọn, điểm và bốn mức; cột ghi chú nêu lý do đặc biệt như "Khác mức · đã giải trình", "Dưới 60 điểm" hay "Quy tắc người đứng đầu: đạt" (hồ sơ của Trưởng ban Ngô Thanh Tâm). Vùng 2 là cột "Quyết định" dạng ô chọn, mặc định bằng mức đề xuất; đổi mức sẽ mở hộp thoại ghi lý do. Vùng 3 là biểu đồ phân bố mức quyết định (HTXSNV 9 người, 19%; HTTNV 30 người, 63%; HTNV 7 người, 15%; KHTNV 2 người, 4%) và kết luận "trong giới hạn tham số của khối (≤ 20%)". Vùng 4 là khối ký lô: chứng thư của người ký (Ngô Thanh Tâm, MySign), phương thức "một lần xác nhận cho cả lô, mỗi hồ sơ một chữ ký riêng (PAdES)", số và ngày quyết định 215-QĐ/ĐUK ngày 12/01/2027, thanh tiến độ 36/48 và ghi chú hồ sơ chưa ký vẫn ở `CHO_QUYET_DINH`.

Ký lô được thực hiện bằng saga tầng 3 (ADR-04). Sau khi người ký xác thực một lần với nhà cung cấp chữ ký số từ xa, esign-service nhận danh sách mã băm tài liệu và ký từng tài liệu; mỗi chữ ký thành công phát một EVT-18, MOD-04 chuyển hồ sơ tương ứng sang `DA_KY_SO`. Thông lượng thiết kế 100.000 phiếu trong 4 giờ, tương đương khoảng 7 phiếu mỗi giây (số ước tính, TL-04 kiểm chứng bằng kịch bản PERF).

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Bộ lọc khối, đơn vị | Danh sách chọn | Không | Theo cây tổ chức | Tất cả trong phạm vi | ABAC | `org_replica.org_unit_v` |
| 2 | Hộp chọn hồ sơ | Hộp đánh dấu | Có (ít nhất 1) | Boolean | Chọn tất cả | Chỉ hồ sơ `CHO_QUYET_DINH` | Trạng thái giao diện |
| 3 | Bốn cột mức | Nhãn | Không áp dụng | 4 giá trị | Từ dữ liệu | Chỉ đọc | `appraisal.rating_proposal` |
| 4 | Mức quyết định | Danh sách chọn | Có | 4 giá trị | Bằng mức đề xuất | Đổi mức thì bắt buộc lý do ≥ 20 ký tự | `appraisal.rating_proposal.level` (QUYET_DINH) |
| 5 | Phân bố mức | Biểu đồ | Không áp dụng | Số người và tỷ lệ | Tính tức thời | So với ngưỡng `phan_bo.toi_da_htxsnv` của khối | Tính trên client từ danh sách |
| 6 | Số, ngày quyết định | Văn bản | Có | Số ký hiệu theo quy định văn thư, ngày dd/mm/yyyy | Trống | Không trùng số trong năm của đơn vị | `appraisal.batch_decision.decision_no`, `appraisal.batch_decision.decision_date` |
| 7 | Chứng thư người ký | Nhãn | Không áp dụng | Tên, nhà cung cấp, hạn | Từ esign | Còn hạn ít nhất 1 ngày | `esign.certificate_ref` |
| 8 | Tiến độ ký lô | Thanh tiến độ | Không áp dụng | Đã ký / tổng | 0 | Cập nhật qua SSE | `esign.sign_request`, `esign.sign_item` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Xuất Mẫu 3 | Luôn | Sinh Mẫu 3 dạng nháp từ danh sách đang chọn | `POST /api/v1/dossiers/forms` (202) | Không có |
| Đổi mức quyết định | Hồ sơ đang chọn | Mở hộp thoại lý do; lưu tạm trên lô | Không có (lưu cùng lô) | Không có |
| Quyết định và ký lô | Có ít nhất 1 hồ sơ, đủ số và ngày | Tạo `appraisal.batch_decision`, ghi các bản ghi QUYET_DINH, gọi MFA bước lên, tạo yêu cầu ký lô | `POST /api/v1/appraisals/batch-decisions` (202, trả `Location: /api/v1/jobs/{id}`) | EVT-11; sau đó EVT-18, EVT-12 cho từng hồ sơ |
| Theo dõi tiến độ | Khi có lô đang chạy | Nhận cập nhật qua SSE từ notification-service | `GET /api/v1/jobs/{id}` | Không có |
| Huỷ lô | Lô chưa hoàn tất | Dừng các chữ ký chưa thực hiện; hồ sơ đã ký giữ nguyên | `POST /api/v1/appraisals/batch-decisions/{id}/cancel` | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-09-13 | Tỷ lệ HTXSNV của danh sách là {ty_le}%, vượt giới hạn {nguong}% của khối. Đề nghị tổ chức phiên hiệu chỉnh trước khi ký. | Cảnh báo | Phân bố vượt ngưỡng |
| MSG-09-14 | Hồ sơ của người đứng đầu chỉ được quyết định sau khi các đơn vị trực thuộc đã có mức (QĐ khoản 14.3.3). | Lỗi kiểm tra | Chọn hồ sơ người đứng đầu quá sớm |
| MSG-09-15 | Đã ký {da_ky}/{tong} hồ sơ. {loi} hồ sơ lỗi sẽ được tự thử lại; đồng chí có thể đóng màn hình. | Thông tin | Trong khi ký lô |
| MSG-09-16 | Đã hoàn tất quyết định {so_qd}: {tong} hồ sơ đã ký số và thông báo tới cán bộ. | Thành công | Lô hoàn tất |

### Liên kết thiết kế

QT-09 do MOD-04 `appraisal` trong mission-core (SVC-05) điều phối, phối hợp với SVC-07 scoring-service (tính điểm, DMN), SVC-06 workflow-service (mẫu luồng `duyet-ho-so-danh-gia`), SVC-10 esign-service (ký số, ký lô), SVC-11 document-service (xem trước biểu mẫu) và SVC-12 notification-service. Dữ liệu chính nằm ở schema `appraisal` (`appraisal_profile`, `general_criteria_result`, `self_assessment`, `score_snapshot`, `rating_proposal`, `rating_explanation`), `scoring` (`score_job`, `score_result`, `score_trace`), `esign` và `flowable.wf_task_projection`. Sự kiện tham gia gồm EVT-09, EVT-10, EVT-11, EVT-12, EVT-18, EVT-21. Về an toàn thông tin, các trường nhận xét, giải trình là dữ liệu C2 được mã hoá trường (ADR-06); ký số tại ba mốc đáp ứng tiêu chí I4; snapshot có `policy_version` và `input_hash` đáp ứng I6; mọi chuyển trạng thái được ghi sổ cái qua outbox (I1, I2). Cấu trúc bảng chi tiết xem TL-03, mục schema `appraisal`; kịch bản thử tải tính lại 1,2 triệu hồ sơ và ký lô 100.000 phiếu xem TL-04 nhóm PERF.

## QT-10. Xếp loại năm, liên thông quý–năm, phân nhóm triển vọng

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-10 |
| Tên | Xếp loại năm, liên thông quý–năm, phân nhóm triển vọng |
| Mục đích | Xếp loại cán bộ theo năm trên cơ sở kết quả bốn quý đã khoá, áp quy tắc liên thông quý–năm, và phân nhóm triển vọng phục vụ công tác cán bộ |
| Phạm vi áp dụng | Mọi cán bộ, đảng viên thuộc diện đánh giá năm của tenant; phân nhóm triển vọng chỉ cho đối tượng mà Policy Pack quy định (cán bộ lãnh đạo, quản lý và diện quy hoạch) |
| Căn cứ | Dự thảo Quy định mới khoản 15.5 (một quý KHTNV thì không xếp HTXSNV năm), khoản 15.6 (hai quý liên tiếp KHTNV thì đề xuất bố trí lại), khoản 12.1.5 (phân nhóm hằng năm), Điều 11; PL 5 (tiêu chí phân nhóm); PL A4 (ẩn phân nhóm ở kỳ quý) |
| Tác nhân | AC-06 cán bộ; AC-07 cơ quan tổ chức; AC-04 lãnh đạo, tập thể lãnh đạo; AC-08 cấp quyết định; SVC-07 scoring-service |
| Tần suất và khối lượng ước tính | Mỗi năm một lần, vào tháng 1 năm sau. Khoảng 6 triệu hồ sơ năm toàn quốc (ước tính); phân nhóm triển vọng ước tính khoảng 10% số hồ sơ (diện lãnh đạo, quản lý, quy hoạch), tức khoảng 600.000 lượt |
| Điều kiện bắt đầu | Kỳ năm được mở (`config.period` loại NAM, trạng thái `MO`) và kỳ quý IV đã `DA_KHOA`; bốn kết quả quý của cán bộ đã có quyết định |
| Điều kiện kết thúc | Quyết định xếp loại năm đã ký số; danh sách phân nhóm đã được tập thể thông qua và ký số |
| Đầu vào | Bốn bản `appraisal.rating_proposal` mức QUYET_DINH của các quý; bốn `appraisal.score_snapshot`; tỷ lệ việc vượt cả năm; tham số năm của Policy Pack |
| Đầu ra | `appraisal.annual_rating`, `appraisal.prospect_group`, cờ đề xuất bố trí lại, EVT-12 cho kỳ năm |
| Chỉ số đo hiệu quả quy trình | Tỷ lệ hồ sơ năm hoàn tất trước hạn nộp cấp trên; số hồ sơ bị chặn HTXSNV do liên thông; số đề xuất bố trí lại được xử lý trong 30 ngày; tỷ lệ phân nhóm có lý do khi khác gợi ý |

Hệ thống hiện tại theo bản đánh giá (H4) chỉ có kỳ quý, nên việc xếp loại năm đang làm bằng bảng tính ngoài hệ thống và quy tắc liên thông phụ thuộc trí nhớ của cán bộ tổ chức. QT-10 đưa quy tắc liên thông vào bảng quyết định của Policy Pack để mọi hồ sơ được kiểm tra như nhau, đồng thời giữ nguyên nguyên tắc kỳ đã khoá không bị tính lại: kết quả quý I đến quý III có thể đã được tính bằng Policy Pack v1.4, còn quý IV và năm dùng v2.0, và hệ thống không "nâng cấp" các quý cũ sang phiên bản mới.

Theo dự thảo, kết quả quý IV gộp vào năm, nghĩa là phiếu quý IV đồng thời là đầu vào của xếp loại năm. Cách tính điểm năm là tham số `nam.cach_tinh` của Policy Pack; giá trị mặc định `TRUNG_BINH_QUY` lấy trung bình điểm bốn quý đã quyết định. Với hồ sơ mẫu, điểm năm là (91,8 + 88,6 + 90,2 + 91,2) / 4 = 90,4. Tuy đủ 90 điểm và không có quý nào KHTNV, tỷ lệ nhiệm vụ vượt mục tiêu cả năm chỉ đạt 24%, nên hệ thống đề xuất HTTNV năm.

Phân nhóm triển vọng là bước riêng, chỉ mở sau khi đã có quyết định xếp loại năm. Bốn nhóm theo PL 5 là Nhóm 1 "Phát triển vượt trội", Nhóm 2 "Có triển vọng", Nhóm 3 "Cần bồi dưỡng thêm" và Nhóm 4 "Cần xem xét bố trí". Hệ thống chỉ đưa gợi ý ban đầu và không tự phân nhóm, vì đây là nhận định của tập thể lãnh đạo về con người, không phải phép tính.

### Lưu đồ quy trình

![Hình B1.7. Lưu đồ làn bơi QT-10: xếp loại năm và phân nhóm triển vọng](nv-b-qt10-luong.png){width=16cm}

Làn "Hệ thống" đọc bốn kết quả quý đã khoá, tính điểm năm theo tham số, rồi lần lượt kiểm tra hai quy tắc liên thông. Nếu có ít nhất một quý KHTNV, mức HTXSNV năm bị chặn (khoản 15.5). Nếu có hai quý liên tiếp KHTNV, hồ sơ được gắn cờ đề xuất bố trí lại (khoản 15.6). Sau đó bảng DMN năm sinh mức đề xuất và câu giải thích. Các làn còn lại tái sử dụng chuỗi tự đánh giá, thẩm định, quyết định của QT-09 với cùng mẫu luồng ở chế độ năm, rồi thêm bước phân nhóm triển vọng và thông qua tập thể. Khi hoàn tất, MOD-04 phát EVT-12 với `periodType = NAM` để MOD-06 lập hồ sơ năm (QT-11).

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Mở kỳ năm | AC-02 hoặc AC-03 | Mở `config.period` loại NAM, gắn Policy Pack áp dụng cho năm | SCR-0801 | `config.period` `MO` | Đầu tháng 1 |
| 2 | Lập hồ sơ năm | Hệ thống (MOD-04) | Khi quý IV khoá, tạo hồ sơ năm cho từng người, gắn bốn kết quả quý | Không có | `appraisal.appraisal_profile` (kỳ năm) | Trong 15 phút sau EVT-09 của quý IV |
| 3 | Tính điểm năm và liên thông | SVC-07 | Tính điểm năm, tỷ lệ việc vượt cả năm, áp quy tắc 15.5 và 15.6, chạy DMN năm | Không có | `appraisal.score_snapshot` (năm); EVT-10 | Cell lớn ≤ 2 giờ |
| 4 | Tự đánh giá năm | AC-06 | Như bước 3 đến 6 của QT-09, phiếu ở chế độ năm, hiển thị bốn kết quả quý chỉ đọc | SCR-0901 | `appraisal.rating_proposal` (TU_DE_XUAT) | 5 ngày làm việc |
| 5 | Thẩm định năm | AC-07 | Xem bảng tổng hợp năm, lọc hồ sơ có cảnh báo liên thông, chọn mức thẩm định | SCR-1001, SCR-0902 | `appraisal.rating_proposal` (THAM_DINH) | 5 ngày làm việc |
| 6 | Quyết định xếp loại năm | AC-08 | Quyết định hàng loạt và ký lô như QT-09 | SCR-0904 | `appraisal.annual_rating`; EVT-18; EVT-12 | 5 ngày làm việc |
| 7 | Xử lý đề xuất bố trí lại | AC-07, AC-04 | Với hồ sơ có cờ 15.6, lập đề xuất bố trí lại gửi cấp có thẩm quyền về cán bộ | SCR-1001 | Việc mới trong MOD-02, EVT-04 | 30 ngày |
| 8 | Phân nhóm triển vọng | AC-04, AC-07 | Mở bảng phân nhóm, xem gợi ý, kéo thả vào bốn nhóm, xếp thứ tự trong nhóm, ghi lý do khi khác gợi ý, gắn kế hoạch bồi dưỡng | SCR-1002 | `appraisal.prospect_group` (nháp) | 10 ngày làm việc |
| 9 | Thông qua tập thể, ký số | AC-04, AC-08 | Trình tập thể lãnh đạo; sau khi thông qua, người có thẩm quyền ký số danh sách | SCR-1002 | `appraisal.prospect_group` (đã ký); EVT-18 | Theo lịch họp |

Điểm khác biệt quan trọng so với QT-09 nằm ở bước 3. Snapshot năm không chứa lại dữ liệu nhiệm vụ mà tham chiếu tới bốn snapshot quý bằng mã và mã băm; `input_hash` của snapshot năm là băm của bốn băm quý cùng tham số năm. Cách này giúp người kiểm tra chứng minh được rằng kết quả năm được tính từ đúng các kết quả quý đã khoá, kể cả khi một quý sau đó được mở lại qua QT-13: khi đó snapshot quý mới có băm khác và hệ thống tự yêu cầu tính lại năm.

Bước 7 không thay đổi mức xếp loại mà tạo việc cho cơ quan tổ chức. Đề xuất bố trí lại là thủ tục công tác cán bộ nằm ngoài phạm vi chấm điểm, nên nền tảng chỉ bảo đảm không bỏ sót trường hợp và theo dõi đến khi có kết quả.

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-10-01 | Kết quả xếp loại năm dựa trên kết quả các quý đã được quyết định; kết quả quý IV đồng thời là đầu vào của năm | Dự thảo Quy định mới; PL A5 (quý IV gộp vào năm) | Hồ sơ năm chỉ lập khi đủ bốn bản QUYET_DINH hoặc có trường hợp đặc thù PL 6 |
| BR-10-02 | Điểm năm tính theo tham số `nam.cach_tinh`; mặc định là trung bình điểm bốn quý (`TRUNG_BINH_QUY`); có thể cấu hình trung bình có trọng số hoặc tính trên toàn bộ nhiệm vụ năm | Chưa thống nhất trong dự thảo, ghi nhận ở PL A4 | Tham số Policy Pack; mỗi giá trị có bộ test case vàng riêng |
| BR-10-03 | Có ít nhất một quý xếp loại KHTNV thì không được xếp HTXSNV năm | QĐ khoản 15.5 | Dòng chặn trong DMN năm, đứng trước dòng HTXSNV; câu giải thích nêu quý nào |
| BR-10-04 | Có hai quý liên tiếp xếp loại KHTNV thì gắn cờ đề xuất bố trí lại công việc | QĐ khoản 15.6 | Hàm CEL kiểm tra cặp quý liên tiếp; cờ `annual_rating.reassign_flag`; tạo việc trong MOD-02 |
| BR-10-05 | Các điều kiện HTXSNV, HTTNV của năm áp dụng như quý, tính trên số liệu cả năm (tỷ lệ việc vượt năm, mục tiêu O năm) | QĐ Điều 11 | DMN năm dùng chung cấu trúc DMN quý |
| BR-10-06 | Kết quả các quý đã khoá giữ nguyên phiên bản Policy Pack đã dùng; không tính lại quý cũ bằng phiên bản mới khi xếp loại năm | Nguyên tắc tái lập kết quả, tiêu chí I6 | Snapshot quý chỉ đọc; snapshot năm lưu danh sách phiên bản của từng quý |
| BR-10-07 | Phân nhóm triển vọng chỉ thực hiện ở kỳ năm; ở kỳ quý chức năng bị khoá và trường "dự kiến phân nhóm" trên Mẫu 2 quý bị ẩn | QĐ khoản 12.1.5; PL A4 | Tham số `phan_nhom.mo_o_ky_quy = false`; API trả 409 nếu gọi ở kỳ quý |
| BR-10-08 | Hệ thống chỉ đưa gợi ý thứ tự tham khảo, không tự phân nhóm; khi người dùng xếp khác gợi ý thì bắt buộc ghi lý do | PL 5; nguyên tắc AI không quyết định | Trường lý do bắt buộc khi nhóm khác gợi ý |
| BR-10-09 | Người bị xếp KHTNV năm hoặc có cờ bố trí lại không được đưa vào Nhóm 1 hoặc Nhóm 2 | PL 5 | Kiểm tra CEL khi lưu và khi trình |
| BR-10-10 | Kết quả phân nhóm là dữ liệu C2, chỉ `leader` và `appraiser` của đơn vị xem được; không xuất sang kho phân tích ở dạng định danh | ADR-06 | OPA; CDC chỉ đẩy số lượng theo nhóm, không đẩy danh tính |

Quy tắc BR-10-02 phản ánh một điểm chưa rõ trong dự thảo được nêu ở PL A4. Thay vì chọn một cách và viết cứng, nền tảng tham số hoá để khi có hướng dẫn chính thức chỉ cần phát hành Policy Pack mới (≤ 5 ngày theo NFR-07). Các ca kiểm thử của cả hai cách đều nằm trong bộ test case vàng, nên việc đổi tham số không làm hỏng kết quả của các khối khác.

### Ngoại lệ và xử lý

Khi cán bộ chỉ có ba quý do được tuyển dụng hoặc điều động giữa năm, Policy Pack áp trường hợp đặc thù tương ứng của PL 6: tính điểm năm trên số quý thực có và ghi chú trên Mẫu 2. Nếu có quý không xếp loại do nghỉ thai sản (PL 6 §4), quý đó không tính vào trung bình và không bị coi là KHTNV.

Khi một quý của năm đang có khiếu nại chưa kết luận, hồ sơ năm vẫn được lập nhưng bị chặn ở bước quyết định với thông báo MSG-10-03. Lý do là kết luận khiếu nại có thể thay đổi kết quả quý và kéo theo kết quả năm.

Khi một quý được mở lại sau khi năm đã quyết định (QT-13), hệ thống tính lại snapshot năm. Nếu mức năm thay đổi, hồ sơ năm được đưa trở lại bước quyết định với lý do "thi hành kết luận hậu kiểm" và quyết định cũ được giữ nguyên trong lịch sử, có đánh dấu bị thay thế.

Khi tập thể lãnh đạo không thông qua danh sách phân nhóm, danh sách trở về trạng thái nháp, lý do không thông qua được lưu, và việc trình lại tạo một phiên bản mới của danh sách.

### Đặc tả màn hình

#### SCR-1001. Xếp loại năm

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Tổng hợp kết quả bốn quý và mức năm của toàn đơn vị, làm nổi bật hồ sơ bị ảnh hưởng bởi quy tắc liên thông, giải thích chi tiết cho từng người |
| Tác nhân | AC-07 (`appraiser`); AC-04, AC-08 xem |
| Đường vào | Menu Đánh giá › Xếp loại năm; từ SCR-0801 khi kỳ năm mở |
| Quyền | Theo ABAC cây tổ chức; dữ liệu C2 |
| Thiết bị | Web desktop |

![Hình B1.8. SCR-1001 Xếp loại năm 2026 với các cảnh báo liên thông quý–năm](SCR-1001.png){width=16cm}

Màn hình được thiết kế để cơ quan tổ chức xử lý trước những hồ sơ "có vấn đề" thay vì đọc lần lượt 48 hồ sơ. Bốn thẻ số liệu đầu trang cho biết 48/48 hồ sơ năm đã lập, 3 hồ sơ bị chặn HTXSNV do có quý KHTNV, 1 hồ sơ đề xuất bố trí lại và 48 hồ sơ chờ phân nhóm.

Vùng 1 là tiêu đề và dòng phụ ghi rõ phiên bản: "Policy Pack Đảng v2.0 (Q1–Q3 dùng v1.4, giữ nguyên theo kỳ đã khoá)", cùng nút "Chỉ hiện hồ sơ có cảnh báo liên thông". Vùng 3 là bảng tổng hợp với bốn cột quý, điểm năm, mức tự đề xuất năm, mức hệ thống đề xuất và cột "Quy tắc liên thông". Dòng Phạm Đức Long có quý II KHTNV nên hiện ghi chú màu cam "Có quý KHTNV ⇒ chặn HTXSNV (QĐ 15.5)"; dòng Bùi Văn Khánh có quý II đến quý IV KHTNV nên hiện ghi chú màu đỏ "2 quý liên tiếp KHTNV ⇒ đề xuất bố trí lại (QĐ 15.6)". Vùng 2 là ngăn "Giải thích liên thông" của hồ sơ đang chọn (Trần Minh Hoàng): công thức điểm năm, tham số `nam.cach_tinh`, danh sách bốn điều kiện với dấu tích. Vùng 4 là câu kết luận của hệ thống kèm điều khoản và bốn nhãn mức quý chỉ đọc, kèm ghi chú quý IV gộp vào năm.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Bộ lọc đơn vị | Danh sách chọn | Không | Cây tổ chức | Đơn vị của người xem | ABAC | `org_replica.org_unit_v` |
| 2 | Lọc hồ sơ có cảnh báo | Công tắc | Không | Boolean | Tắt | Không có | Trạng thái giao diện |
| 3 | Thẻ số liệu (4 thẻ) | Số | Không áp dụng | Số nguyên | Tính tức thời | Chỉ đọc | `appraisal.annual_rating` (đếm theo cờ) |
| 4 | Mức quý I đến IV | Nhãn | Không áp dụng | 4 giá trị | Từ dữ liệu | Chỉ đọc | `appraisal.rating_proposal` (QUYET_DINH của từng quý) |
| 5 | Điểm năm | Số | Không áp dụng | 1 chữ số thập phân | Từ snapshot năm | Chỉ đọc | `appraisal.annual_rating.score` |
| 6 | Mức tự đề xuất năm | Nhãn | Không áp dụng | 4 giá trị | Từ dữ liệu | Chỉ đọc | `appraisal.rating_proposal` (TU_DE_XUAT, kỳ năm) |
| 7 | Mức hệ thống đề xuất | Nhãn | Không áp dụng | 4 giá trị | Từ DMN năm | Chỉ đọc | `appraisal.annual_rating.system_level` |
| 8 | Quy tắc liên thông | Văn bản | Không áp dụng | ≤ 200 ký tự | Sinh tự động | Chỉ đọc | `appraisal.annual_rating.block_reason`, `appraisal.annual_rating.reassign_flag` |
| 9 | Giải thích chi tiết | Văn bản và danh sách điều kiện | Không áp dụng | Theo mẫu câu | Sinh tự động | Chỉ đọc | `appraisal.rating_explanation` (kỳ năm) |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Chọn dòng | Luôn | Tải giải thích của hồ sơ | `GET /api/v1/appraisals/annual/{id}/explanation` | Không có |
| Chỉ hiện hồ sơ có cảnh báo liên thông | Luôn | Lọc theo `block_reason` hoặc `reassign_flag` | `GET /api/v1/appraisals/annual?period={id}&flag=lien_thong` | Không có |
| Chuyển thẩm định năm | Có hồ sơ ở bước tự đánh giá đã nộp | Mở SCR-0902 ở chế độ năm | Không có (điều hướng) | Không có |
| Lập đề xuất bố trí lại | Hồ sơ có `reassign_flag` | Tạo việc cho cơ quan tổ chức trong MOD-02 với căn cứ khoản 15.6 | `POST /api/v1/appraisals/annual/{id}/reassignment-proposal` | EVT-04 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-10-01 | Hồ sơ có quý {quy} xếp loại KHTNV nên không được xếp HTXSNV năm (QĐ khoản 15.5). | Thông tin | Hiển thị giải thích |
| MSG-10-02 | Cán bộ {ten} có hai quý liên tiếp KHTNV ({quy_1}, {quy_2}). Hệ thống đã tạo việc đề xuất bố trí lại (QĐ khoản 15.6). | Cảnh báo | Gắn cờ 15.6 |
| MSG-10-03 | Chưa thể quyết định xếp loại năm vì khiếu nại {ma_kn} về kết quả quý {quy} chưa có kết luận. | Lỗi kiểm tra | Có khiếu nại mở |
| MSG-10-04 | Kết quả quý {quy} vừa được điều chỉnh sau hậu kiểm. Điểm năm đã được tính lại từ {cu} thành {moi}. | Thông báo | Snapshot quý thay đổi |

#### SCR-1002. Phân nhóm triển vọng

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho lãnh đạo xếp cán bộ vào bốn nhóm triển vọng, xếp thứ tự trong nhóm, ghi lý do, gắn kế hoạch bồi dưỡng và trình tập thể |
| Tác nhân | AC-04 (`leader`); AC-07 chuẩn bị |
| Đường vào | Menu Đánh giá › Phân nhóm triển vọng (chỉ hiện khi kỳ năm đã có quyết định) |
| Quyền | `leader`, `appraiser` của đơn vị; dữ liệu C2 |
| Thiết bị | Web desktop (kéo thả cần chuột hoặc bút) |

![Hình B1.9. SCR-1002 Phân nhóm triển vọng năm 2026 dạng bảng kéo thả bốn cột](SCR-1002.png){width=16cm}

Bảng kéo thả bốn cột được chọn vì thao tác phân nhóm có bản chất là so sánh tương đối giữa người với người; nhìn cả bốn nhóm cùng lúc giúp tập thể thấy ngay sự cân đối. Mỗi thẻ cán bộ có số thứ tự trong nhóm, một dòng tóm tắt (chức vụ, số năm HTXSNV hoặc lý do) và chip "Kế hoạch bồi dưỡng".

Vùng 1 là tiêu đề cùng dòng nhắc căn cứ "chỉ áp dụng ở kỳ năm (QĐ 12.1.5), tên nhóm theo PL 5, cấu hình trong Policy Pack". Vùng 2 là ghi chú màu cam giải thích chức năng bị khoá ở kỳ quý theo tham số `phan_nhom.mo_o_ky_quy = false` và nhắc dữ liệu là C2. Vùng 3 là bốn cột nhóm: Nhóm 1 có Đỗ Quang Vinh và Vũ Ngọc Sơn; Nhóm 2 có Trần Minh Hoàng, Lê Thu Hà và Nguyễn Hải Yến; Nhóm 3 có Hoàng Thị Mai và Phạm Đức Long; Nhóm 4 có Bùi Văn Khánh. Vùng 4 gồm thẻ "Gợi ý ban đầu của hệ thống" nói rõ hệ thống không tự phân nhóm, và ô "Lý do điều chỉnh so với gợi ý" với ví dụ lý do chuyển Vũ Ngọc Sơn lên Nhóm 1.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Nhóm | Cột kéo thả | Có | 4 nhóm theo Policy Pack | Theo gợi ý | BR-10-09 | `appraisal.prospect_group.group_code` |
| 2 | Thứ tự trong nhóm | Số | Có | Số nguyên ≥ 1 | Theo gợi ý | Không trùng trong nhóm | `appraisal.prospect_group.rank_in_group` |
| 3 | Tóm tắt thẻ | Nhãn | Không áp dụng | ≤ 80 ký tự | Sinh tự động | Chỉ đọc | `appraisal.annual_rating`, `org_replica.assignment_v` |
| 4 | Kế hoạch bồi dưỡng | Liên kết | Không | Tham chiếu kế hoạch đào tạo | Trống | Không có | `appraisal.prospect_group.training_plan_ref` |
| 5 | Nhóm gợi ý | Nhãn ẩn | Không áp dụng | 4 giá trị | Sinh tự động | Chỉ đọc | `appraisal.prospect_group.suggested_group` |
| 6 | Lý do điều chỉnh | Văn bản dài | Có khi khác gợi ý | 20 đến 1.000 ký tự, C2 | Trống | BR-10-08 | `appraisal.prospect_group.reason_enc` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Kéo thả thẻ | Danh sách ở trạng thái nháp | Đổi nhóm hoặc thứ tự; nếu khác gợi ý thì yêu cầu lý do | Lưu cục bộ, gửi khi lưu nháp | Không có |
| Xem tiêu chí PL 5 | Luôn | Mở ngăn tiêu chí của từng nhóm từ Policy Pack | `GET /api/v1/policies/packs/{id}/prospect-criteria` | Không có |
| Lưu nháp | Trạng thái nháp | Lưu toàn bộ danh sách, kiểm tra `If-Match` | `PUT /api/v1/appraisals/prospect-groups/{periodId}/{orgUnitId}` | Không có |
| Trình tập thể lãnh đạo | Đủ lý do cho mọi điều chỉnh | Khởi động user task thông qua tập thể; khoá chỉnh sửa | `POST /api/v1/appraisals/prospect-groups/{periodId}/{orgUnitId}/submit` | EVT-11 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-10-05 | Phân nhóm triển vọng chỉ thực hiện ở kỳ năm (QĐ khoản 12.1.5). | Lỗi kiểm tra | Mở ở kỳ quý |
| MSG-10-06 | Cán bộ {ten} xếp loại năm {muc} nên không thể đưa vào {nhom}. | Lỗi kiểm tra | Vi phạm BR-10-09 |
| MSG-10-07 | Đồng chí đã xếp {ten} khác nhóm gợi ý. Vui lòng ghi lý do điều chỉnh. | Cảnh báo | Khác gợi ý |

### Liên kết thiết kế

QT-10 dùng MOD-04 `appraisal` (bảng `annual_rating`, `prospect_group`, `score_snapshot`, `rating_proposal`, `rating_explanation`), SVC-07 scoring-service với DMN năm, SVC-06 workflow-service với mẫu `duyet-ho-so-danh-gia` ở chế độ năm, MOD-02 `task` để tạo việc đề xuất bố trí lại và SVC-10 esign-service. Sự kiện: EVT-09 (quý IV khoá), EVT-10, EVT-11, EVT-12, EVT-04, EVT-18. Dữ liệu phân nhóm là C2 và không đưa sang ClickHouse ở dạng định danh (ADR-06); bảng điều hành QT-16 chỉ thấy số lượng theo nhóm. Tham số `nam.cach_tinh` và `phan_nhom.mo_o_ky_quy` là tham số mới của Policy Pack, được ghi trong tệp ghi chú để TL-03 bổ sung vào `policy.policy_rule`.

## QT-11. Kiểm điểm và hồ sơ số

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-11 |
| Tên | Kiểm điểm và hồ sơ số |
| Mục đích | Tổ chức kiểm điểm theo đúng trình tự (gửi dự thảo, góp ý, hội nghị, biên bản, theo dõi khắc phục), sinh tự động các biểu mẫu từ dữ liệu, xử lý 13 trường hợp đặc thù, đóng gói hồ sơ quý, năm có ký số và chuyển hồ sơ diện Trung ương quản lý |
| Phạm vi áp dụng | Tập thể lãnh đạo, quản lý và cá nhân thuộc diện kiểm điểm cuối năm (luồng Đảng); hồ sơ quý cho đối tượng đánh giá quý |
| Căn cứ | Dự thảo Quy định mới Điều 6, Điều 7 (gửi dự thảo ít nhất 03 ngày làm việc trước hội nghị); Hướng dẫn mục 2 đến 4; Mẫu 1A, 1B, 1C, 1D, Mẫu 2, Mẫu 3 và bảng chấm điểm; PL 6 (13 trường hợp đặc thù); Điều 17 và Hướng dẫn mục 10 (chuyển hồ sơ diện TW); PL 4 V.3 (ký số, dấu thời gian) |
| Tác nhân | AC-06 người được kiểm điểm; chủ trì, thư ký, thành viên chi bộ hoặc tập thể (vai trò `member` có phân công trong hội nghị); AC-07 cơ quan tổ chức; AC-08 người ký gói; SVC-11 document-service; SVC-10 esign-service; SVC-13 integration-gateway |
| Tần suất và khối lượng ước tính | Kiểm điểm năm một lần; gói hồ sơ quý và năm. Ước tính khoảng 6 triệu bản kiểm điểm cá nhân mỗi năm toàn quốc, khoảng 1,2 triệu ở cell lớn; mỗi gói đơn vị trung bình khoảng 100 tệp (ví dụ gói mẫu 106 tệp) |
| Điều kiện bắt đầu | Kỳ năm (hoặc quý) có quyết định xếp loại (EVT-12) hoặc kế hoạch kiểm điểm được tạo |
| Điều kiện kết thúc | Gói hồ sơ đã ký số, nộp cấp trên và được tiếp nhận (`DA_LUU_TRU`); gói con diện TW đã gửi thành công |
| Đầu vào | Kết quả xếp loại, snapshot điểm, danh sách đối tượng, biên bản kỳ trước, mẫu biểu trong `docgen.template` theo phiên bản Policy Pack |
| Đầu ra | `dossier.review_meeting`, `dossier.review_comment`, `dossier.form_instance`, `dossier.special_case`, `dossier.dossier_package` có manifest SHA-256 và chữ ký; EVT-17, EVT-18 |
| Chỉ số đo hiệu quả quy trình | Tỷ lệ hội nghị đạt điều kiện gửi dự thảo đúng hạn (mục tiêu 100%); tỷ lệ biểu mẫu sinh thành công lần đầu (≥ 99%); thời gian sinh và ký lô 100.000 phiếu ≤ 4 giờ (NFR-06); tỷ lệ hạn chế kỳ trước được khắc phục |

Theo bản đánh giá kiến trúc, tiêu chí H5 "Kiểm điểm, hồ sơ số" đang ở mức chưa đạt: hệ thống hiện tại không sinh mẫu, không ký số. Trong thực tế, cán bộ tổ chức đang chép số liệu từ hệ thống sang mẫu Word, đây là nguồn gốc của sai sót và của việc điểm trên biểu mẫu không khớp điểm trong hệ thống. QT-11 đảo ngược cách làm: mọi biểu mẫu được sinh từ dữ liệu đã quyết định bởi document-service (SVC-11), mang tem phiên bản Policy Pack, và mỗi tệp có mã băm trong manifest của gói.

Kiểm điểm là sinh hoạt chính trị có yếu tố con người cao, nên nền tảng không thay thế hội nghị mà hỗ trợ trình tự. Hệ thống kiểm tra thời hạn gửi dự thảo theo ngày làm việc của lịch tenant, thu góp ý trước hội nghị, cho thư ký ghi ý kiến tại hội nghị, sinh biên bản, và tự đưa các hạn chế đã nêu thành danh sách theo dõi khắc phục cho kỳ sau.

### Lưu đồ quy trình

![Hình B1.10. Lưu đồ làn bơi QT-11: kiểm điểm, sinh biểu mẫu và gói hồ sơ số](nv-b-qt11-luong.png){width=16cm}

Lưu đồ bắt đầu ở làn "Hệ thống": MOD-06 phát hiện các trường hợp đặc thù của PL 6 dựa trên dữ liệu tổ chức và trạng thái hồ sơ, sau đó document-service sinh dự thảo Mẫu 1A đến 1D đã điền sẵn số liệu. Người được kiểm điểm hoàn thiện phần tự nhận xét và gửi dự thảo. Làn "Chi bộ, tập thể" kiểm tra điều kiện ít nhất 03 ngày làm việc, thu góp ý, tổ chức hội nghị, lập biên bản có ký số và theo dõi khắc phục. Khi hội nghị kết thúc, document-service sinh bản chính thức các mẫu và bảng chấm điểm ở định dạng PDF/A, MOD-06 ghép thành gói hồ sơ có manifest. Làn "Tổ chức, cấp trên" ký số gói, nộp; với hồ sơ diện Trung ương quản lý, gói con được chuyển qua integration-gateway bằng EdXML.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Lập kế hoạch kiểm điểm | AC-07 | Tạo hội nghị: đối tượng, chủ trì, thư ký, thành phần, ngày giờ, địa điểm | SCR-1101 | `dossier.review_meeting` | Theo kế hoạch của cấp uỷ |
| 2 | Phát hiện trường hợp đặc thù | Hệ thống (MOD-06) | Đối chiếu 13 trường hợp PL 6 với dữ liệu tổ chức, phân công, trạng thái hồ sơ; ghi cách xử lý theo Policy Pack | SCR-1102 | `dossier.special_case` | Khi lập kế hoạch và khi dữ liệu thay đổi |
| 3 | Sinh dự thảo bản kiểm điểm | SVC-11 | Sinh Mẫu 1A (tập thể), 1B, 1C, 1D (cá nhân) đã điền điểm, mức, nhiệm vụ, hạn chế kỳ trước | SCR-1101 | `dossier.form_instance` (dự thảo); EVT-17 | Trong 5 phút |
| 4 | Hoàn thiện và gửi dự thảo | AC-06 | Bổ sung tự nhận xét, hướng khắc phục; bấm gửi; hệ thống ghi thời điểm gửi | SCR-1101 | `dossier.review_meeting.draft_sent_at`; EVT-21 | ≥ 03 ngày làm việc trước hội nghị |
| 5 | Kiểm tra thời hạn | Hệ thống | Đếm số ngày làm việc giữa thời điểm gửi và ngày hội nghị theo `config.holiday_calendar` | SCR-1101 | Cờ đạt hoặc không đạt | Ngay khi gửi |
| 6 | Thu góp ý | Thành viên | Đọc dự thảo, gửi góp ý phân loại ưu điểm, hạn chế, kiến nghị | SCR-1101 | `dossier.review_comment` | Đến trước hội nghị |
| 7 | Tổ chức hội nghị | Chủ trì, thư ký | Điểm danh, thư ký ghi ý kiến, biểu quyết mức xếp loại tại hội nghị | SCR-1101 | `dossier.review_comment` (tại hội nghị) | Ngày hội nghị |
| 8 | Lập biên bản, ký số | Thư ký, chủ trì | Sinh biên bản từ dữ liệu hội nghị; chủ trì và thư ký ký số | SCR-1101 | `dossier.form_instance` (biên bản); EVT-17, EVT-18 | 2 ngày làm việc |
| 9 | Ghi nhận hạn chế cần khắc phục | Hệ thống, thư ký | Tạo danh sách hạn chế cần khắc phục để theo dõi ở kỳ sau | SCR-1101 | `dossier.remediation_item` | Cùng bước 8 |
| 10 | Sinh biểu mẫu chính thức | SVC-11 | Sinh Mẫu 1A đến 1D, Mẫu 2, Mẫu 3, bảng chấm điểm ở PDF/A-2b có tem phiên bản | SCR-1102 | `docgen.render_job`, `dossier.form_instance`; EVT-17 | Lô 100.000 phiếu ≤ 4 giờ cùng ký số |
| 11 | Ký số biểu mẫu | Cá nhân, người có thẩm quyền | Mỗi người ký bản kiểm điểm của mình; Trưởng ban ký Mẫu 3 | SCR-1102 | EVT-18 | 3 ngày làm việc |
| 12 | Đóng gói và kiểm tra | MOD-06 | Ghép tệp, tính SHA-256 từng tệp, lập manifest, chạy danh sách kiểm tra trước khi ký gói | SCR-1102 | `dossier.dossier_package` `CHO_KY` | Tự động |
| 13 | Ký số gói và nộp | AC-08 | Ký manifest gói kèm dấu thời gian RFC 3161; gói chuyển `DA_NOP` và bị khoá | SCR-1102 | EVT-18; ghi sổ cái | Hạn nộp cấp trên |
| 14 | Chuyển hồ sơ diện TW | AC-07, SVC-13 | Tách gói con cho hồ sơ diện TW quản lý, mã hoá theo chứng thư bên nhận, gửi EdXML | SCR-1102 | `integ.inbox_message` phía nhận; xác nhận | Theo QĐ Điều 17 |

Bước 5 là nơi quy định "ít nhất 03 ngày làm việc" được kiểm soát bằng máy thay vì bằng sự cẩn thận của thư ký. Với hội nghị mẫu, dự thảo gửi lúc 16:20 ngày 08/01/2027 (thứ Sáu), hội nghị ngày 14/01/2027 (thứ Năm). Các ngày làm việc trọn vẹn ở giữa là 11, 12 và 13/01, tức 3 ngày, nên đạt yêu cầu. Cách đếm (có tính ngày gửi hay không, gửi sau giờ hành chính có tính là ngày hôm đó hay không) là tham số `kiem_diem.cach_dem_ngay` của Policy Pack, mặc định chỉ đếm ngày làm việc trọn vẹn nằm giữa hai mốc.

Bước 10 và 11 là điểm nóng tải cuối năm. document-service chạy dưới dạng worker co giãn bằng KEDA, sinh tệp từ mẫu docx4j, chuyển sang PDF/A qua Gotenberg rồi trả về đối tượng trong Ceph. Thời gian sinh một phiếu ước tính 0,5 đến 1,5 giây; với 100.000 phiếu và 20 worker, thời gian sinh khoảng 1 đến 2 giờ, phần còn lại của ngân sách 4 giờ dành cho ký lô (số ước tính, kiểm chứng bằng kịch bản PERF trong TL-04).

### Trạng thái đối tượng

![Hình B1.11. Sơ đồ trạng thái gói hồ sơ số dossier.dossier_package](nv-b-qt11-trangthai.png){width=13cm}

Baseline mục 9.1 chưa quy định trạng thái của gói hồ sơ, nên tài liệu này đề xuất bộ trạng thái dưới đây và ghi vào tệp ghi chú để TL-03 bổ sung. Gói bắt đầu ở `DANG_LAP`, chuyển `DANG_SINH` khi gửi yêu cầu sinh biểu mẫu, quay lại `DANG_LAP` nếu có tệp sinh lỗi, sang `CHO_KY` khi mọi tệp đã sinh, `DA_KY` khi có chữ ký gói, `DA_NOP` khi đã gửi cấp trên và `DA_LUU_TRU` khi cấp trên tiếp nhận. Gói `DA_NOP` chỉ trở lại `DANG_LAP` qua QT-13, và khi đó gói cũ được giữ nguyên, gói mới có số phiên bản tăng.

| Trạng thái hiện tại | Sự kiện hoặc hành động | Trạng thái kế tiếp | Điều kiện kiểm soát |
|---|---|---|---|
| (chưa có) | Kỳ đã quyết định (EVT-12) | `DANG_LAP` | Một gói cho mỗi đơn vị và kỳ |
| `DANG_LAP` | Yêu cầu sinh biểu mẫu | `DANG_SINH` | Mọi hồ sơ của đơn vị đã có quyết định hoặc thuộc trường hợp đặc thù |
| `DANG_SINH` | Có tệp lỗi | `DANG_LAP` | Ghi lỗi vào `docgen.render_job` |
| `DANG_SINH` | EVT-17 cho tệp cuối cùng | `CHO_KY` | Đủ tệp theo danh mục của Policy Pack |
| `CHO_KY` | EVT-18 chữ ký gói | `DA_KY` | Danh sách kiểm tra đạt |
| `DA_KY` | Nộp cấp trên | `DA_NOP` | Gửi thành công |
| `DA_NOP` | Cấp trên tiếp nhận | `DA_LUU_TRU` | Có biên nhận |
| `DA_NOP` | Mở lại kỳ (EVT-22) | `DANG_LAP` | Tạo phiên bản gói mới, giữ gói cũ |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-11-01 | Dự thảo bản kiểm điểm phải gửi tới thành viên ít nhất 03 ngày làm việc trước hội nghị | QĐ Điều 6, Điều 7; HD mục 2 | Bước 5 tính theo `config.holiday_calendar`; nếu không đạt thì cảnh báo và yêu cầu dời ngày hội nghị hoặc ghi lý do được cấp uỷ chấp thuận |
| BR-11-02 | Biểu mẫu 1A, 1B, 1C, 1D, 2, 3 và bảng chấm điểm phải sinh từ dữ liệu đã quyết định; không nhập tay điểm, mức trên biểu mẫu | PL 4 V.3; tiêu chí H5 | document-service đọc trực tiếp snapshot và bản ghi QUYET_DINH; vùng số liệu trên mẫu bị khoá |
| BR-11-03 | Mỗi biểu mẫu mang tem phiên bản Policy Pack và mẫu biểu đã dùng | Tiêu chí I6 | Chân trang tự chèn; `dossier.form_instance.template_version` |
| BR-11-04 | Nhãn tiêu chí chung trên biểu mẫu theo đúng biểu mẫu ("Đảm bảo, Không đảm bảo" ở Mẫu 1; "Không vi phạm, Vi phạm" ở Mẫu 2) nhưng dùng một dữ liệu gốc | PL A4 | Ánh xạ nhãn trong `docgen.template` |
| BR-11-05 | Hệ thống phát hiện và xử lý 13 trường hợp đặc thù của PL 6 theo cách Policy Pack quy định | PL 6 | Bảng quy tắc `special_case` trong Policy Pack; danh sách hiển thị ở SCR-1102 |
| BR-11-06 | Góp ý là dữ liệu C2; nếu tenant bật chế độ ẩn danh, người được kiểm điểm thấy nội dung nhưng không thấy người góp ý | HD mục 3; ADR-06 | Mã hoá trường; OPA lọc trường người góp ý |
| BR-11-07 | Kết quả biểu quyết tại hội nghị được ghi vào biên bản và Mẫu 3 nhưng không thay mức quyết định của cấp có thẩm quyền | HD mục 4 | Lưu trong `dossier.review_meeting.vote_json`, không ghi vào `rating_proposal` |
| BR-11-08 | Hạn chế nêu trong biên bản phải được theo dõi khắc phục và báo cáo ở kỳ kiểm điểm sau | QĐ Điều 7 | `dossier.remediation_item` với trạng thái; tự hiện ở hội nghị kỳ sau |
| BR-11-09 | Gói hồ sơ chỉ được ký khi mọi hồ sơ đã `DA_KY_SO`, không còn khiếu nại đang mở và mã băm từng tệp khớp manifest | PL 4 V.3; tiêu chí I5 | Danh sách kiểm tra tự động trên SCR-1102 |
| BR-11-10 | Gói đã ký và nộp bị khoá; mọi thay đổi phải qua mở lại kỳ (QT-13) và tạo phiên bản gói mới | PL 4 V.2; tiêu chí I3 | Trạng thái `DA_NOP`; API ghi trả 409 |
| BR-11-11 | Hồ sơ cán bộ diện Trung ương quản lý được chuyển tới Ban Tổ chức Trung ương hoặc Văn phòng Trung ương bằng gói con có ký số và mã hoá theo chứng thư bên nhận | QĐ Điều 17; HD mục 10 | SVC-13 integration-gateway, EdXML; danh sách diện TW lấy từ 40 chức danh PL 2B cấu hình trong `org` |

Bảng dưới liệt kê 13 trường hợp đặc thù mà Policy Pack khối Đảng v2.0 mô hình hoá. Danh sách được tổng hợp từ PL 6 và báo cáo BGĐ (PL A2 nhóm 5); cách xử lý là tham số của Policy Pack, nên khi văn bản chính thức thay đổi chỉ cần phát hành phiên bản mới.

| Mục | Trường hợp | Cách xử lý mặc định |
|---|---|---|
| §1 | Điều động, luân chuyển dưới 2 tháng trong kỳ | Đơn vị cũ đánh giá, đơn vị mới cho ý kiến |
| §2 | Điều động, luân chuyển từ 2 tháng trở lên trong kỳ | Đơn vị mới đánh giá, đơn vị cũ cung cấp kết quả phần thời gian trước |
| §3 | Kiêm nhiệm nhiều chức vụ | Đánh giá tại nơi giữ chức vụ chính, nơi kiêm nhiệm cho ý kiến |
| §4 | Nghỉ thai sản, ốm đau dài ngày trên 50% thời gian kỳ | Không xếp loại kỳ quý; ghi chú trên Mẫu 2 |
| §5 | Đi học, biệt phái dài hạn | Cơ sở đào tạo hoặc nơi biệt phái cung cấp nhận xét; đơn vị quản lý xếp loại |
| §6 | Mới tuyển dụng, mới bổ nhiệm dưới 6 tháng | Đánh giá theo thời gian công tác thực tế, không xét HTXSNV năm |
| §7 | Đang bị xem xét, xác minh, kiểm tra | Tạm treo quyết định đến khi có kết luận |
| §8 | Đã bị kỷ luật trong kỳ | Áp điều kiện loại trừ theo hình thức kỷ luật |
| §9 | Đơn vị mới thành lập, sáp nhập, chia tách trong kỳ | Dùng cây tổ chức theo ngày hiệu lực; cấp trên trực tiếp đánh giá |
| §10 | Nghỉ hưu, thôi việc trong kỳ | Đánh giá đến thời điểm nghỉ nếu đủ thời gian tối thiểu |
| §11 | Đảng viên ngoài hệ thống chính trị (Mẫu 1D) | Áp bộ tiêu chí riêng theo PL 2E |
| §12 | Không đủ điều kiện của mức đề xuất | Chuyển xuống mức liền kề, sinh câu giải thích |
| §13 | Người đứng đầu có đơn vị phụ trách KHTNV trên 50% | Áp quy tắc QĐ khoản 14.3.3 |

### Ngoại lệ và xử lý

Khi dự thảo không được gửi đủ 03 ngày làm việc, hệ thống không tự huỷ hội nghị mà hiển thị MSG-11-01, đề nghị dời ngày. Nếu cấp uỷ quyết định giữ nguyên ngày vì lý do đặc biệt, chủ trì phải ghi lý do và lý do này được in vào biên bản.

Khi document-service sinh lỗi một phần (ví dụ thiếu dữ liệu một trường của Mẫu 1C), gói quay về `DANG_LAP`, các tệp đã sinh được giữ, nút "Sinh lại mẫu lỗi" chỉ sinh lại các tệp lỗi. Gói mẫu đang ở trạng thái 104/106 tệp đã xong.

Khi một cá nhân chưa ký bản kiểm điểm của mình đến hạn, danh sách kiểm tra hiển thị cảnh báo; người ký gói không thể ký cho đến khi đủ hoặc khi có quyết định ghi nhận "không ký" kèm lý do trong biên bản.

Khi gửi gói con diện TW thất bại (bên nhận không phản hồi, chứng thư bên nhận hết hạn), integration-gateway đưa thông điệp vào `integ.dead_letter`, thử lại theo lịch và thông báo cho cơ quan tổ chức. Gói chính vẫn được nộp bình thường.

Khi một hồ sơ trong gói đang có khiếu nại, gói bị chặn ký (BR-11-09). Nếu đến hạn nộp mà khiếu nại chưa kết luận, cơ quan tổ chức có thể nộp gói với hồ sơ đó được đánh dấu "đang khiếu nại" theo tham số của tenant, và gói bổ sung sẽ được nộp sau.

### Đặc tả màn hình

#### SCR-1101. Hội nghị kiểm điểm

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Điều hành trình tự kiểm điểm: kiểm tra thời hạn gửi dự thảo, thu góp ý, ghi ý kiến tại hội nghị, biểu quyết, sinh biên bản và theo dõi khắc phục |
| Tác nhân | Thư ký hội nghị (vai trò `appraiser` hoặc `member` được phân công), chủ trì, thành viên, người được kiểm điểm |
| Đường vào | Menu Hồ sơ số › Kiểm điểm; thông báo mời góp ý |
| Quyền | Thành viên hội nghị xem dự thảo và góp ý; chỉ thư ký ghi ý kiến tại hội nghị; chỉ chủ trì kết thúc hội nghị |
| Thiết bị | Web desktop, máy tính bảng tại phòng họp |

![Hình B1.12. SCR-1101 Hội nghị kiểm điểm cuối năm 2026 của Chi bộ Ban Tổ chức](SCR-1101.png){width=16cm}

Thanh bước năm bước (gửi dự thảo, thu góp ý, hội nghị, biên bản và ký số, theo dõi khắc phục) cho biết hội nghị đang ở bước 3. Màn hình chia ba cột: thông tin thủ tục bên trái, nội dung góp ý ở giữa, kết quả bên phải.

Vùng 1 là thẻ "Kiểm tra thời hạn gửi dự thảo" hiển thị thời điểm gửi 08/01/2027 16:20, ngày hội nghị 14/01/2027, số ngày làm việc ở giữa là 3 và kết luận màu xanh "Đạt yêu cầu ≥ 03 ngày làm việc (QĐ Điều 6)"; bên dưới là bảng thành phần dự với trạng thái từng người (đã góp ý, đã đọc, chưa mở, vắng có lý do). Vùng 2 là danh sách góp ý phân loại ưu điểm, hạn chế, kiến nghị, gồm góp ý trước hội nghị và ý kiến thư ký ghi tại hội nghị, kèm ô nhập và ghi chú về chế độ ẩn danh. Vùng 3 là thẻ "Khắc phục hạn chế kỳ trước" lấy từ `dossier.remediation_item` với trạng thái từng hạn chế. Vùng 4 là thẻ biểu quyết: mức đề xuất của hệ thống HTTNV và kết quả biểu quyết (HTXSNV 1, HTTNV 5, HTNV 0), kèm nhắc rằng biểu quyết không thay mức quyết định.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Đối tượng kiểm điểm | Nhãn | Không áp dụng | Họ tên, chức vụ | Từ kế hoạch | Chỉ đọc | `dossier.review_meeting.subject_person_id` |
| 2 | Ngày giờ, địa điểm hội nghị | Ngày giờ, văn bản | Có | dd/mm/yyyy hh:mm | Trống | Sau thời điểm gửi dự thảo ≥ 3 ngày làm việc | `dossier.review_meeting.meeting_at` |
| 3 | Thời điểm gửi dự thảo | Ngày giờ | Không áp dụng | dd/mm/yyyy hh:mm | Ghi khi gửi | Chỉ đọc | `dossier.review_meeting.draft_sent_at` |
| 4 | Số ngày làm việc ở giữa | Số | Không áp dụng | Số nguyên | Tính tự động | Theo BR-11-01 | Tính từ `config.holiday_calendar` |
| 5 | Thành phần, trạng thái | Bảng | Không áp dụng | Họ tên, trạng thái | Từ phân công | Chỉ đọc | `dossier.review_meeting.participants_json` |
| 6 | Góp ý | Văn bản dài, loại | Có khi gửi góp ý | ≤ 4.000 ký tự, C2; loại ưu điểm, hạn chế, kiến nghị | Trống | Mã hoá trường | `dossier.review_comment.content_enc`, `dossier.review_comment.kind` |
| 7 | Ý kiến tại hội nghị | Văn bản dài | Không | ≤ 4.000 ký tự, C2 | Trống | Chỉ thư ký nhập | `dossier.review_comment` (phase = HOI_NGHI) |
| 8 | Hạn chế kỳ trước | Danh sách | Không áp dụng | Nội dung, trạng thái | Từ biên bản kỳ trước | Thư ký cập nhật trạng thái | `dossier.remediation_item` |
| 9 | Kết quả biểu quyết | Số theo mức | Có khi kết thúc | Số nguyên | 0 | Tổng ≤ số người có mặt | `dossier.review_meeting.vote_json` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Gửi dự thảo | Người được kiểm điểm, trước hội nghị | Ghi `draft_sent_at`, gửi thông báo, chạy kiểm tra thời hạn | `POST /api/v1/dossiers/meetings/{id}/send-draft` | EVT-21 |
| Tải dự thảo Mẫu 1B | Thành viên | Tải bản dự thảo, ghi nhật ký truy cập | `GET /api/v1/dossiers/forms/{formId}/file` | Không có |
| Gửi góp ý | Thành viên, trước hoặc trong hội nghị | Lưu góp ý mã hoá | `POST /api/v1/dossiers/meetings/{id}/comments` | Không có |
| Sinh biên bản | Thư ký, sau khi có biểu quyết | Gọi document-service sinh biên bản | `POST /api/v1/dossiers/meetings/{id}/minutes` (202) | EVT-17 |
| Kết thúc hội nghị | Chủ trì | Khoá góp ý, tạo `remediation_item` từ các hạn chế, yêu cầu ký biên bản | `POST /api/v1/dossiers/meetings/{id}/close` | EVT-11, EVT-18 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-11-01 | Dự thảo được gửi {so_ngay} ngày làm việc trước hội nghị, chưa đủ 03 ngày theo QĐ Điều 6. Đề nghị dời ngày hội nghị hoặc ghi lý do được cấp uỷ chấp thuận. | Cảnh báo | Kiểm tra thời hạn không đạt |
| MSG-11-02 | Đồng chí được mời góp ý cho bản kiểm điểm của đồng chí {ten} trước ngày {ngay}. | Thông báo đẩy | Gửi dự thảo |
| MSG-11-03 | Đã kết thúc hội nghị. Biên bản đang được sinh và sẽ gửi chủ trì, thư ký ký số. | Thành công | Kết thúc hội nghị |

#### SCR-1102. Gói hồ sơ số và ký số

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Theo dõi việc sinh biểu mẫu, xử lý trường hợp đặc thù, kiểm tra trước khi ký, ký số gói và nộp cấp trên, chuyển gói con diện TW |
| Tác nhân | AC-07 chuẩn bị; AC-08 ký gói |
| Đường vào | Menu Hồ sơ số › Gói hồ sơ số; thông báo "Gói hồ sơ sẵn sàng ký" |
| Quyền | `appraiser` của đơn vị chuẩn bị; người có thẩm quyền ký gói; MFA bước lên khi ký |
| Thiết bị | Web desktop |

![Hình B1.13. SCR-1102 Gói hồ sơ số năm 2026 DP-2026-N-0142](SCR-1102.png){width=16cm}

Màn hình chia hai cột: nội dung gói bên trái, kiểm tra và hành động bên phải. Dòng phụ đầu trang cho biết mã gói DP-2026-N-0142 và hạn nộp cấp trên 01/02/2027.

Vùng 1 là bảng "Biểu mẫu sinh từ dữ liệu" với nhãn tiến độ "document-service · 104/106 tệp xong"; mỗi dòng là một mẫu (1A, 1B, 1C, 1D, 2, 3, bảng chấm điểm), đối tượng, trạng thái và định dạng "PDF/A-2b · tem v2.0". Vùng 2 là bảng trường hợp đặc thù phát hiện trong kỳ: 5 trên 13 trường hợp có phát sinh (§1, §4, §7, §9, §12) với tổng 13 hồ sơ, mỗi dòng nêu cách xử lý theo Policy Pack. Vùng 3 là danh sách kiểm tra trước khi ký gói: 48/48 hồ sơ `DA_KY_SO`, mã băm khớp manifest, Mẫu 1C còn 2 người chưa ký, Mẫu 3 chờ Trưởng ban ký, không còn khiếu nại mở. Vùng 4 là thẻ chuyển hồ sơ diện TW: 2 hồ sơ, nơi nhận Ban Tổ chức Trung ương, trạng thái "Chưa gửi". Vùng 5 là thẻ ký số gói với người ký Ngô Thanh Tâm, Viettel-CA, dấu thời gian RFC 3161 và mã băm gói của 106 tệp.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Mã gói, hạn nộp | Nhãn | Không áp dụng | DP-yyyy-{Q/N}-nnnn | Sinh tự động | Duy nhất trong tenant | `dossier.dossier_package.code`, `dossier.dossier_package.due_date` |
| 2 | Danh sách biểu mẫu | Bảng | Không áp dụng | Mẫu, đối tượng, trạng thái | Từ dữ liệu | Chỉ đọc | `dossier.form_instance`, `docgen.render_job` |
| 3 | Trường hợp đặc thù | Bảng | Không áp dụng | Mục, cách xử lý, số hồ sơ | Phát hiện tự động | Chỉ đọc; xem chi tiết từng hồ sơ | `dossier.special_case` |
| 4 | Danh sách kiểm tra | Danh sách | Không áp dụng | Đạt hoặc cảnh báo | Tính tức thời | Theo BR-11-09 | Tính từ `appraisal.appraisal_profile`, `dossier.form_instance`, `oversight.complaint` |
| 5 | Nơi nhận gói con diện TW | Danh sách chọn | Có khi có hồ sơ diện TW | Ban Tổ chức TW hoặc Văn phòng TW | Theo chức danh | Chỉ đơn vị nhận đã cấu hình kết nối | `integ.connector` |
| 6 | Người ký, nhà cung cấp | Nhãn | Không áp dụng | Họ tên, CA | Từ esign | Chứng thư còn hạn | `esign.certificate_ref` |
| 7 | Mã băm gói | Nhãn | Không áp dụng | SHA-256 rút gọn, số tệp | Tính khi đóng gói | Băm của manifest | `dossier.dossier_package.manifest_hash` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Sinh lại mẫu lỗi | Có tệp lỗi | Gửi lại các render job lỗi | `POST /api/v1/dossiers/packages/{id}/regenerate` (202) | EVT-17 |
| Xem biểu mẫu | Tệp đã sinh | Mở trình xem PDF/A, ghi nhật ký truy cập | `GET /api/v1/dossiers/forms/{formId}/file` | Không có |
| Tải gói (ZIP và manifest) | Gói từ `CHO_KY` trở đi | Tạo tệp ZIP kèm manifest JSON có chữ ký | `POST /api/v1/dossiers/packages/{id}/export` (202) | Không có |
| Gửi gói con | Có hồ sơ diện TW, gói đã ký | Tách, mã hoá, gửi qua integration-gateway | `POST /api/v1/dossiers/packages/{id}/transfers` (202) | Không có (SVC-13 ghi `integ`) |
| Ký số gói và nộp | Danh sách kiểm tra đạt | MFA bước lên, ký manifest, gắn dấu thời gian, chuyển `DA_NOP` | `POST /api/v1/dossiers/packages/{id}/sign` (202), `POST /api/v1/esign/requests` | EVT-18; ghi sổ cái |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-11-04 | Chưa thể ký gói: {so_muc} mục kiểm tra chưa đạt. Xem chi tiết ở danh sách kiểm tra. | Lỗi kiểm tra | Bấm ký khi chưa đạt |
| MSG-11-05 | Gói {ma_goi} đã được ký số và nộp lúc {thoi_diem}. Gói đã bị khoá; mọi thay đổi phải qua mở lại kỳ. | Thành công | Ký gói xong |
| MSG-11-06 | Gửi gói con tới {noi_nhan} chưa thành công: {ly_do}. Hệ thống sẽ tự thử lại lúc {thoi_diem}. | Cảnh báo | SVC-13 lỗi |
| MSG-11-07 | Có {n} tệp sinh lỗi. Đồng chí có thể bấm "Sinh lại mẫu lỗi" sau khi bổ sung dữ liệu. | Cảnh báo | render job lỗi |

### Liên kết thiết kế

QT-11 do MOD-06 `dossier` điều phối (bảng `review_meeting`, `review_comment`, `form_instance`, `special_case`, `dossier_package` và bảng đề xuất mới `remediation_item`), dùng SVC-11 document-service (schema `docgen`: `template`, `render_job`, `render_output`), SVC-10 esign-service, SVC-08 evidence-service (lưu tệp PDF/A với Object Lock), SVC-13 integration-gateway (EdXML, `integ.connector`, `integ.dead_letter`) và SVC-09 ledger-service. Sự kiện: EVT-12 (đầu vào), EVT-17, EVT-18, EVT-21, EVT-22 (mở lại kỳ). Đáp ứng tiêu chí H5 (sinh mẫu, ký số, 13 trường hợp, đóng gói), I4 (ký số), I5 (tệp bất biến có mã băm) và I8 (dấu thời gian RFC 3161 trên chữ ký gói). Năng lực sinh và ký 100.000 phiếu trong 4 giờ là chỉ tiêu NFR-06, kịch bản đo ở TL-04.

# Quy trình giám sát và hậu kiểm

Chương này đặc tả ba quy trình giữ cho kết quả đánh giá đáng tin cậy sau khi đã được tính và quyết định: khiếu nại, giải trình (QT-12), hậu kiểm và mở lại kỳ có hai người duyệt (QT-13), cảnh báo bất thường (QT-14). Ba quy trình này tương ứng với nhóm tính năng 6 "Giám sát, hậu kiểm" của báo cáo BGĐ và trả lời trực tiếp các tiêu chí H6, I1, I2, I3 của bản đánh giá kiến trúc, vốn đang ở mức "Chưa đạt" hoặc "Một phần" vì hệ thống hiện tại chỉ khoá từng hồ sơ và ghi đè dữ liệu tại chỗ.

Ba quy trình dùng chung một nguyên tắc: kỳ đã khoá thì không ai sửa được dữ liệu, kể cả quản trị viên. Mọi điều chỉnh sau khoá kỳ đều phải đi qua một cửa duy nhất là yêu cầu mở lại kỳ, có lý do, có phạm vi giới hạn, có hai người có thẩm quyền khác nhau cùng duyệt và được ghi vào sổ cái chuỗi băm. Khiếu nại được chấp nhận (QT-12) và cảnh báo được kết luận có vi phạm (QT-14) đều kết thúc bằng việc tạo yêu cầu mở lại kỳ (QT-13), chứ không có đường tắt nào khác.

## QT-12. Khiếu nại, giải trình

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-12 |
| Tên | Khiếu nại, giải trình |
| Mục đích | Cho cán bộ khiếu nại kết quả đánh giá, giải trình bổ sung hoặc bảo lưu ý kiến; cho cơ quan có thẩm quyền tiếp nhận, yêu cầu giải trình các bên, kết luận và trả lời bằng văn bản có ký số |
| Phạm vi áp dụng | Kết quả xếp loại quý, năm; kết quả nghiệm thu chất lượng; kết quả phân nhóm (chỉ bảo lưu ý kiến) |
| Căn cứ | Dự thảo Quy định mới khoản 16.1 (khiếu nại, giải trình), khoản 16.2 (bảo lưu ý kiến, trả lời bằng văn bản); PL 4 V.1 (tách vai trò); PL 3 mục 2 (miễn trừ chậm do khách quan) |
| Tác nhân | AC-06 người khiếu nại; AC-07 người xử lý (được chỉ định tự động); AC-05 người nghiệm thu, AC-07 người thẩm định với vai trò bên giải trình; AC-08 người ký văn bản trả lời |
| Tần suất và khối lượng ước tính | Phát sinh sau mỗi kỳ công bố kết quả. Giả định tỷ lệ khiếu nại khoảng 0,5% số hồ sơ (chưa có số đo), tương đương khoảng 30.000 khiếu nại mỗi quý toàn quốc, khoảng 6.000 ở cell lớn |
| Điều kiện bắt đầu | Hồ sơ ở `DA_KY_SO` và còn trong thời hạn khiếu nại (tham số, mặc định 10 ngày làm việc từ ngày công bố) |
| Điều kiện kết thúc | Văn bản trả lời đã ký số và gửi người khiếu nại; nếu có điều chỉnh thì yêu cầu mở lại kỳ đã thi hành xong (QT-13); hồ sơ khiếu nại `DONG` |
| Đầu vào | Kết quả bị khiếu nại, cấu phần cụ thể, lý do, tài liệu kèm theo |
| Đầu ra | `oversight.complaint`, `oversight.complaint_response`, văn bản trả lời PDF/A ký số, yêu cầu mở lại kỳ nếu có điều chỉnh, ý kiến bảo lưu lưu vào hồ sơ |
| Chỉ số đo hiệu quả quy trình | Tỷ lệ khiếu nại được trả lời đúng hạn (mục tiêu 100% trong 15 ngày làm việc); tỷ lệ khiếu nại được chấp nhận toàn phần hoặc một phần; thời gian trung bình xử lý; tỷ lệ khiếu nại lặp lại cùng nội dung |

Khiếu nại trong hệ thống hiện tại được xử lý ngoài hệ thống bằng văn bản giấy, nên kết quả khiếu nại không gắn được với kết quả đánh giá và không ai thống kê được khiếu nại theo nội dung. QT-12 đưa khiếu nại thành một đối tượng có vòng đời riêng (`oversight.complaint`) gắn với hồ sơ đánh giá và với cấu phần cụ thể bị khiếu nại (ví dụ hệ số C của một nhiệm vụ). Nhờ gắn đến cấu phần, người xử lý tính thử được ảnh hưởng của việc chấp nhận khiếu nại ngay trên màn hình, và cơ quan kiểm tra thống kê được loại cấu phần nào bị khiếu nại nhiều nhất để điều chỉnh hướng dẫn nghiệm thu.

Trường `kind` của khiếu nại phân biệt ba hình thức theo khoản 16.1 và 16.2: khiếu nại (đề nghị xem xét lại), giải trình bổ sung (cung cấp thêm thông tin, không đề nghị thay đổi), bảo lưu ý kiến (không đồng ý nhưng không đề nghị xử lý, chỉ yêu cầu lưu vào hồ sơ). Hồ sơ mẫu dùng xuyên suốt là khiếu nại KN-2027-0031 của đồng chí Trần Minh Hoàng, đề nghị không trừ hệ số C của nhiệm vụ then chốt 3 vì chậm do Đảng uỷ cơ sở X gửi hồ sơ muộn, có công văn 45-CV/ĐU làm căn cứ.

### Lưu đồ quy trình

![Hình B2.1. Lưu đồ làn bơi QT-12: khiếu nại, giải trình và trả lời bằng văn bản](nv-b-qt12-luong.png){width=16cm}

Người khiếu nại chọn kết quả và cấu phần, nêu lý do, đính kèm tài liệu, chọn có bảo lưu ý kiến hay không, rồi ký số và gửi. Hệ thống chuyển hồ sơ đánh giá sang nhánh `KHIEU_NAI`, phát EVT-25 để workflow-service khởi động mẫu luồng `xu-ly-khieu-nai` v2 và chọn người xử lý theo quy tắc tách vai trò. Người xử lý tiếp nhận, yêu cầu giải trình các bên liên quan, tính thử phương án điều chỉnh và đưa ra kết luận. Kết luận "giữ nguyên" đi thẳng tới bước ký văn bản trả lời. Kết luận "điều chỉnh" tạo sẵn yêu cầu mở lại kỳ (QT-13) rồi mới ký văn bản trả lời. Người khiếu nại nhận văn bản; nếu vẫn không đồng ý, ý kiến bảo lưu được lưu vào hồ sơ và in trên Mẫu 3.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Soạn khiếu nại | AC-06 | Chọn kết quả, hình thức, cấu phần; nêu lý do, đề nghị cụ thể; đính kèm tài liệu (lưu ở evidence-service, có SHA-256) | SCR-1201 | `oversight.complaint` nháp | Trong thời hạn khiếu nại |
| 2 | Ký số và gửi | AC-06, SVC-10 | Ký số nội dung khiếu nại; hồ sơ đánh giá chuyển `KHIEU_NAI` | SCR-1201 | EVT-25, EVT-18 | Như trên |
| 3 | Chỉ định người xử lý | Hệ thống (SVC-06, OPA) | Chọn người có vai trò `appraiser` của cấp có thẩm quyền, loại trừ người đã nghiệm thu, thẩm định, quyết định hồ sơ | Không có | Task giao cho người xử lý; EVT-21 | Ngay khi gửi |
| 4 | Tiếp nhận, phân loại | AC-07 | Kiểm tra thẩm quyền, thời hạn; tiếp nhận hoặc không thụ lý có lý do | SCR-1202 | `complaint.status = DA_TIEP_NHAN` hoặc `KHONG_THU_LY` | 2 ngày làm việc |
| 5 | Yêu cầu giải trình | AC-07 | Gửi yêu cầu giải trình bằng văn bản tới người nghiệm thu, đơn vị liên quan | SCR-1202 | `DANG_GIAI_TRINH`; EVT-21 | Hạn giải trình 3 ngày làm việc |
| 6 | Gửi giải trình | Bên liên quan | Trả lời yêu cầu, đính kèm tài liệu | SCR-1202 (chế độ bên liên quan) | `oversight.complaint_response` (loại giải trình) | Theo hạn bước 5 |
| 7 | Tính thử điều chỉnh | AC-07, SVC-07 | Nhập phương án điều chỉnh, gọi scoring-service tính thử với Policy Pack của kỳ, không ghi | SCR-1202 | Kết quả tính thử (không lưu snapshot) | Trong bước 8 |
| 8 | Kết luận | AC-07 | Chọn kết luận: giữ nguyên, chấp nhận một phần, chấp nhận toàn bộ; sinh văn bản trả lời từ mẫu | SCR-1202 | `CHO_KET_LUAN`; `complaint_response` (kết luận) | Tổng thời gian ≤ 15 ngày làm việc |
| 9 | Tạo yêu cầu mở lại kỳ | AC-07 | Nếu có điều chỉnh và kỳ đã khoá, tạo yêu cầu mở lại kỳ gắn khiếu nại | SCR-1202, SCR-1301 | `oversight.reopen_request` | Cùng bước 8 |
| 10 | Ký văn bản trả lời | AC-08 | Ký số văn bản trả lời | SCR-1202 | `DA_TRA_LOI`; EVT-18 | Trong thời hạn 15 ngày làm việc |
| 11 | Nhận trả lời, bảo lưu | AC-06 | Đọc văn bản; nếu đã chọn bảo lưu và không đồng ý thì xác nhận bảo lưu | SCR-1201 | Ý kiến bảo lưu lưu vào hồ sơ | 5 ngày làm việc |
| 12 | Đóng khiếu nại | Hệ thống | Đóng khi giữ nguyên hoặc bảo lưu; khi có điều chỉnh thì đóng sau khi QT-13 hoàn tất | Không có | `DONG`; hồ sơ đánh giá về `DA_KY_SO` hoặc `DA_KHOA` | Tự động |

Bước 3 thể hiện cách hệ thống thực thi quy tắc tách vai trò của PL 4 V.1 mà không dựa vào sự tự giác. Mẫu luồng không gán task cho một người cố định mà gọi một quyết định OPA với đầu vào là danh sách người đã tham gia hồ sơ (lấy từ `appraisal.rating_proposal`, `product.acceptance`) và danh sách người có vai trò `appraiser` của cấp có thẩm quyền; OPA trả về những ứng viên hợp lệ, workflow-service chọn người có ít việc nhất. Trong ví dụ, người xử lý là Lê Thu Hà vì đồng chí không thẩm định hồ sơ của Trần Minh Hoàng ở quý IV.

Bước 7 cho phép người xử lý trả lời câu hỏi "nếu chấp nhận thì kết quả thay đổi thế nào" trước khi kết luận. Với KN-2027-0031, nếu bỏ trừ C của nhiệm vụ then chốt 3 thì C từ 0,85 thành 1,00, KPI nhiệm vụ từ 0,950 thành 1,000, KPI nhóm then chốt từ 0,908 thành 0,921 và điểm từ 91,2 thành 92,1. Mức vẫn là HTTNV vì tỷ lệ việc vượt 22% vẫn dưới 30%. Kết quả tính thử dùng Policy Pack Đảng v2.0, đúng phiên bản đã dùng khi khoá kỳ.

### Trạng thái đối tượng

![Hình B2.2. Sơ đồ trạng thái khiếu nại oversight.complaint](nv-b-qt12-trangthai.png){width=13cm}

Baseline chưa quy định trạng thái chi tiết của khiếu nại, nên tài liệu này đề xuất bộ trạng thái dưới đây (ghi trong tệp ghi chú). Từ `MOI`, khiếu nại được tiếp nhận hoặc không thụ lý (quá hạn, sai thẩm quyền). Sau tiếp nhận, khiếu nại có thể đi qua bước giải trình hoặc đi thẳng tới chờ kết luận. Văn bản trả lời đã ký đưa khiếu nại sang `DA_TRA_LOI`; nếu có điều chỉnh, khiếu nại chờ thi hành ở `DANG_THI_HANH` cho tới khi việc mở lại kỳ và tính lại hoàn tất.

| Trạng thái hiện tại | Sự kiện hoặc hành động | Trạng thái kế tiếp | Điều kiện kiểm soát |
|---|---|---|---|
| (chưa có) | Ký số và gửi | `MOI` | Trong thời hạn khiếu nại; hồ sơ `DA_KY_SO` |
| `MOI` | Phân công tiếp nhận | `DA_TIEP_NHAN` | Người xử lý hợp lệ theo OPA |
| `MOI` | Không thụ lý | `KHONG_THU_LY` | Có lý do (quá hạn, sai thẩm quyền, trùng nội dung đã trả lời) |
| `DA_TIEP_NHAN` | Yêu cầu giải trình | `DANG_GIAI_TRINH` | Có ít nhất một bên được yêu cầu |
| `DANG_GIAI_TRINH` | Đủ giải trình hoặc hết hạn | `CHO_KET_LUAN` | Mọi yêu cầu đã trả lời hoặc quá hạn |
| `DA_TIEP_NHAN` | Không cần giải trình | `CHO_KET_LUAN` | Có ghi chú |
| `CHO_KET_LUAN` | Ký văn bản trả lời | `DA_TRA_LOI` | Văn bản sinh từ mẫu, đã ký số |
| `DA_TRA_LOI` | Có điều chỉnh | `DANG_THI_HANH` | Có `reopen_request` gắn khiếu nại |
| `DANG_THI_HANH` | Mở lại kỳ, tính lại xong | `DONG` | `reopen_request` hoàn tất |
| `DA_TRA_LOI` | Giữ nguyên hoặc bảo lưu ý kiến | `DONG` | Hết hạn xác nhận hoặc người khiếu nại xác nhận |
| `KHONG_THU_LY` | Tự động | `DONG` | Đã gửi thông báo không thụ lý |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-12-01 | Cán bộ được khiếu nại kết quả đánh giá của mình trong thời hạn quy định kể từ ngày công bố | QĐ khoản 16.1 | Tham số `khieu_nai.thoi_han` (mặc định 10 ngày làm việc); nút gửi khoá khi quá hạn |
| BR-12-02 | Khiếu nại phải chỉ rõ kết quả, cấu phần bị khiếu nại, lý do, căn cứ và đề nghị cụ thể | QĐ khoản 16.1 | Trường bắt buộc; ít nhất một cấu phần được chọn |
| BR-12-03 | Có ba hình thức: khiếu nại, giải trình bổ sung, bảo lưu ý kiến | QĐ khoản 16.1, 16.2 | Trường `oversight.complaint.kind` |
| BR-12-04 | Người xử lý không được là người đã nghiệm thu, thẩm định, đề xuất hoặc quyết định hồ sơ bị khiếu nại | PL 4 V.1 | OPA khi giao task và khi gọi API kết luận |
| BR-12-05 | Mọi khiếu nại phải được trả lời bằng văn bản có ký số trong thời hạn quy định | QĐ khoản 16.2 | Mẫu luồng có hạn 15 ngày làm việc, nhắc và chuyển cấp khi quá hạn; văn bản sinh từ mẫu TL-KN-01 |
| BR-12-06 | Trong khi khiếu nại chưa kết luận, kết quả hiện hành vẫn có hiệu lực; gói hồ sơ chứa hồ sơ bị khiếu nại bị chặn ký | QĐ Điều 16; BR-11-09 | Trạng thái `KHIEU_NAI` của hồ sơ; danh sách kiểm tra gói |
| BR-12-07 | Điều chỉnh do chấp nhận khiếu nại với kỳ đã khoá chỉ được thi hành qua mở lại kỳ có hai người duyệt | PL 4 V.2; tiêu chí I3 | Không có API điều chỉnh trực tiếp; nút "Tạo yêu cầu mở lại kỳ" |
| BR-12-08 | Tính thử phải dùng đúng phiên bản Policy Pack của kỳ bị khiếu nại | Tiêu chí I6 | scoring-service nhận `policy_version` từ snapshot gốc |
| BR-12-09 | Người khiếu nại không đồng ý với trả lời được bảo lưu ý kiến; ý kiến bảo lưu được lưu vào hồ sơ và thể hiện trên Mẫu 3 | QĐ khoản 16.2 | `oversight.complaint_response` loại BAO_LUU; document-service in vào Mẫu 3 |
| BR-12-10 | Nội dung khiếu nại, giải trình là dữ liệu C2; mỗi lần giải mã để đọc được ghi sổ cái | ADR-06 | Mã hoá trường; nhật ký truy cập |
| BR-12-11 | Khiếu nại cùng nội dung đã được trả lời thì không thụ lý lại, trừ khi có căn cứ mới | Thực tiễn giải quyết khiếu nại | Hệ thống so khớp cấu phần và gợi ý trùng; người xử lý quyết định |

### Ngoại lệ và xử lý

Nếu khiếu nại được gửi sau thời hạn, hệ thống không cho gửi dưới hình thức khiếu nại nhưng vẫn cho gửi dưới hình thức bảo lưu ý kiến, vì ý kiến bảo lưu không làm phát sinh nghĩa vụ xem xét lại.

Nếu không tìm được người xử lý hợp lệ (ví dụ đơn vị nhỏ chỉ có một cán bộ tổ chức đã thẩm định hồ sơ), OPA trả về danh sách rỗng; mẫu luồng chuyển lên cơ quan tổ chức cấp trên trực tiếp theo cây tổ chức và ghi lý do chuyển cấp.

Nếu bên được yêu cầu giải trình không trả lời đúng hạn, khiếu nại vẫn chuyển sang `CHO_KET_LUAN` và văn bản trả lời ghi nhận việc không có giải trình.

Nếu kỳ chưa khoá (khiếu nại kết quả nghiệm thu trong kỳ), điều chỉnh được thi hành bằng nghiệm thu lại qua QT-07 mà không cần mở lại kỳ.

Nếu người khiếu nại rút khiếu nại trước khi có kết luận, khiếu nại chuyển `DONG` với lý do "rút", hồ sơ đánh giá trở lại `DA_KY_SO`, nội dung khiếu nại vẫn được lưu.

### Đặc tả màn hình

#### SCR-1201. Gửi khiếu nại

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho cán bộ soạn, ký số và gửi khiếu nại, giải trình bổ sung hoặc bảo lưu ý kiến về kết quả đánh giá; theo dõi tiến trình xử lý |
| Tác nhân | AC-06 (`member`) |
| Đường vào | Nút "Gửi khiếu nại" trên SCR-0903; thông báo kết quả xếp loại |
| Quyền | Chủ hồ sơ; hồ sơ ở `DA_KY_SO` |
| Thiết bị | Web desktop và di động |

![Hình B2.3. SCR-1201 Gửi khiếu nại kết quả xếp loại quý IV/2026](SCR-1201.png){width=16cm}

Dòng phụ đầu trang cho biết kết quả công bố ngày 12/01/2027 theo QĐ 215-QĐ/ĐUK và hạn gửi khiếu nại 22/01/2027, còn 7 ngày làm việc. Biểu mẫu chiếm cột trái, thông tin quy trình ở cột phải.

Vùng 1 là tiêu đề và dòng thời hạn. Vùng 2 gồm hai trường "Kết quả bị khiếu nại" (Xếp loại quý IV/2026, HTTNV, 91,2 điểm) và "Hình thức" (khiếu nại, giải trình bổ sung, bảo lưu ý kiến). Vùng 3 là nhóm chip cấu phần: cán bộ đã chọn "Nhiệm vụ then chốt 2 · B = 0,50" và "Nhiệm vụ then chốt 3 · C = 0,85"; tiếp theo là ô lý do, căn cứ (mã hoá C2, tối đa 8.000 ký tự), ô đề nghị cụ thể, tài liệu kèm theo có mã băm và hộp đánh dấu bảo lưu ý kiến. Vùng 4 là thẻ "Quy trình xử lý" với năm bước và thời hạn của từng bước. Vùng 5 là thẻ "Ảnh hưởng khi gửi" và ghi chú tách vai trò: hồ sơ chuyển nhánh `KHIEU_NAI`, kết quả hiện hành vẫn hiệu lực, gói hồ sơ năm bị chặn ký, người xử lý do hệ thống chọn.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Kết quả bị khiếu nại | Danh sách chọn | Có | Kỳ, mức, điểm | Kết quả mới nhất | Chỉ kết quả còn hạn khiếu nại | `oversight.complaint.profile_id` |
| 2 | Hình thức | Danh sách chọn | Có | KHIEU_NAI, GIAI_TRINH, BAO_LUU | KHIEU_NAI | Quá hạn thì chỉ còn BAO_LUU | `oversight.complaint.kind` |
| 3 | Cấu phần bị khiếu nại | Chip chọn nhiều | Có | Danh sách cấu phần của snapshot | Trống | Ít nhất 1 | `oversight.complaint.targets_json` |
| 4 | Lý do, căn cứ | Văn bản dài | Có | 50 đến 8.000 ký tự, C2 | Trống | Mã hoá trường | `oversight.complaint.content_enc` |
| 5 | Đề nghị cụ thể | Văn bản | Có | ≤ 500 ký tự | Trống | Không có | `oversight.complaint.request_text` |
| 6 | Tài liệu kèm theo | Tệp | Không | PDF, DOCX, EML, ảnh; ≤ 20 MB mỗi tệp | Trống | Quét mã độc, tính SHA-256 | `evidence.object_meta` (tham chiếu từ `oversight.complaint.attachments_json`) |
| 7 | Bảo lưu ý kiến nếu không được chấp nhận | Hộp đánh dấu | Không | Boolean | Không chọn | Không có | `oversight.complaint.reserve_opinion` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Lưu nháp | Khiếu nại chưa gửi | Lưu nháp, mã hoá nội dung | `POST /api/v1/oversight/complaints` hoặc `PUT /api/v1/oversight/complaints/{id}` | Không có |
| Thêm tệp | Chưa gửi | Tải lên dạng luồng qua evidence-service | `POST /api/v1/files` | Không có |
| Ký số và gửi | Đủ trường bắt buộc, còn hạn | Ký số, chuyển hồ sơ sang `KHIEU_NAI`, khởi động mẫu luồng | `POST /api/v1/oversight/complaints/{id}/submit` (202) | EVT-25, EVT-18 |
| Rút khiếu nại | Trước khi có kết luận | Đóng khiếu nại với lý do rút | `POST /api/v1/oversight/complaints/{id}/withdraw` | EVT-21 |
| Xác nhận bảo lưu | Sau khi có trả lời, đã chọn bảo lưu | Lưu ý kiến bảo lưu vào hồ sơ | `POST /api/v1/oversight/complaints/{id}/reserve` | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-12-01 | Đã quá thời hạn khiếu nại ({han}). Đồng chí vẫn có thể gửi ý kiến dưới hình thức bảo lưu ý kiến. | Cảnh báo | Mở sau hạn |
| MSG-12-02 | Đã gửi khiếu nại {ma_kn}. Kết quả hiện hành vẫn có hiệu lực cho tới khi có kết luận. Hạn trả lời: {han_tra_loi}. | Thành công | Gửi xong |
| MSG-12-03 | Vui lòng chọn ít nhất một cấu phần bị khiếu nại. | Lỗi kiểm tra | Thiếu trường 3 |
| MSG-12-04 | Khiếu nại {ma_kn} đã có văn bản trả lời. Đồng chí có {so_ngay} ngày làm việc để xác nhận hoặc bảo lưu ý kiến. | Thông báo đẩy | Văn bản trả lời được ký |

#### SCR-1202. Xử lý khiếu nại

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho người xử lý tiếp nhận, theo dõi dòng thời gian, yêu cầu và đọc giải trình, tính thử điều chỉnh, kết luận, sinh và ký văn bản trả lời, tạo yêu cầu mở lại kỳ |
| Tác nhân | AC-07 người xử lý; bên liên quan ở chế độ giải trình; AC-08 ký văn bản |
| Đường vào | Menu Giám sát › Khiếu nại; thông báo task mới |
| Quyền | Người xử lý được chỉ định; OPA kiểm tra tách vai trò ở mọi thao tác ghi |
| Thiết bị | Web desktop |

![Hình B2.4. SCR-1202 Xử lý khiếu nại KN-2027-0031](SCR-1202.png){width=16cm}

Màn hình có ba cột: danh sách khiếu nại, chi tiết và giải trình, kết luận và thi hành. Dòng phụ đầu trang ghi rõ người xử lý Lê Thu Hà "không tham gia thẩm định hồ sơ liên quan", là cách nhắc trực quan về quy tắc tách vai trò.

Vùng 1 là danh sách khiếu nại với mã, người khiếu nại, đối tượng, trạng thái và số ngày còn lại; KN-2027-0031 đang được chọn ở trạng thái "Đang giải trình, còn 11 ngày". Vùng 2 là phần đầu khiếu nại với nhãn `KHIEU_NAI`, ghi chú nội dung C2 đã được giải mã cho người xử lý và việc truy cập đã được ghi sổ cái, tóm tắt đề nghị và dòng thời gian bốn mốc (gửi, tiếp nhận, yêu cầu giải trình, đã nhận giải trình). Vùng 3 là thẻ "Giải trình đã nhận" từ người nghiệm thu Ngô Thanh Tâm và Đảng uỷ cơ sở X (thừa nhận gửi muộn do sáp nhập chi bộ, công văn 51-CV/ĐU). Vùng 4 là thẻ "Kết luận": kết quả xử lý "Chấp nhận một phần · điều chỉnh C", điều chỉnh đề nghị "NV then chốt 3: C 0,85 → 1,00 (miễn trừ khách quan)", ô tính thử màu cam cho kết quả 91,2 thành 92,1 và mức vẫn HTTNV, văn bản trả lời sinh từ mẫu TL-KN-01 số 08-CV/BTC. Vùng 5 là thẻ "Thi hành kết luận" nói rõ kỳ quý IV đã `DA_KHOA`, cần mở lại kỳ hai người duyệt và hệ thống đã tạo sẵn yêu cầu MLK-2027-007.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Danh sách khiếu nại | Bảng | Không áp dụng | Mã, người, trạng thái, hạn | Sắp theo hạn | Chỉ khiếu nại được giao hoặc trong phạm vi | `oversight.complaint`, `flowable.wf_task_projection` |
| 2 | Nội dung khiếu nại | Văn bản | Không áp dụng | Giải mã khi xem | Từ dữ liệu | Ghi nhật ký truy cập | `oversight.complaint.content_enc` |
| 3 | Dòng thời gian | Danh sách | Không áp dụng | Thời điểm, hành động | Từ sổ cái và luồng | Chỉ đọc | `ledger.ledger_entry`, `oversight.complaint_response` |
| 4 | Bên được yêu cầu giải trình | Chọn nhiều | Có khi yêu cầu | Người hoặc đơn vị | Gợi ý theo cấu phần | Ít nhất 1 | `oversight.complaint_response` (loại YEU_CAU) |
| 5 | Giải trình đã nhận | Danh sách | Không áp dụng | Người, nội dung, tệp | Từ dữ liệu | Chỉ đọc | `oversight.complaint_response` (loại GIAI_TRINH) |
| 6 | Kết quả xử lý | Danh sách chọn | Có | GIU_NGUYEN, CHAP_NHAN_MOT_PHAN, CHAP_NHAN | Trống | Khác GIU_NGUYEN thì bắt buộc trường 7 | `oversight.complaint_response.outcome` |
| 7 | Điều chỉnh đề nghị | Văn bản có cấu trúc | Có khi chấp nhận | Cấu phần, giá trị cũ, giá trị mới, lý do | Trống | Giá trị mới trong miền hợp lệ của cấu phần | `oversight.complaint_response.adjustment_json` |
| 8 | Kết quả tính thử | Nhãn | Không áp dụng | Điểm, mức trước và sau | Tính khi bấm | Policy Pack của kỳ | Trả về từ SVC-07, không lưu |
| 9 | Văn bản trả lời | Tệp sinh từ mẫu | Có | Số văn bản, PDF/A | Sinh từ mẫu TL-KN-01 | Phải ký trước khi gửi | `docgen.render_output`, `oversight.complaint_response.document_ref` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Tiếp nhận hoặc không thụ lý | Khiếu nại `MOI` | Chuyển trạng thái; không thụ lý bắt buộc lý do | `POST /api/v1/oversight/complaints/{id}/accept` hoặc `/reject` | EVT-21 |
| Yêu cầu giải trình | `DA_TIEP_NHAN` | Tạo yêu cầu, gửi thông báo, đặt hạn | `POST /api/v1/oversight/complaints/{id}/explanation-requests` | EVT-21 |
| Tính thử | Có điều chỉnh | Gọi scoring-service chế độ mô phỏng | `POST /api/v1/appraisals/profiles/{id}/simulate` | Không có |
| Xem văn bản | Đã có kết luận | Sinh bản xem trước văn bản trả lời | `POST /api/v1/dossiers/forms/preview` | Không có |
| Ký số và trả lời | Đủ trường 6, 7, 9 | Ký văn bản, gửi người khiếu nại | `POST /api/v1/oversight/complaints/{id}/respond` (202) | EVT-18, EVT-21 |
| Tạo yêu cầu mở lại kỳ | Kết luận có điều chỉnh, kỳ `DA_KHOA` | Tạo `reopen_request` với phạm vi lấy từ trường 7 | `POST /api/v1/oversight/reopen-requests` | Không có (sự kiện phát ở QT-13) |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-12-05 | Đồng chí không thể xử lý khiếu nại này vì đã tham gia {vai_tro} hồ sơ liên quan (PL 4 V.1). | Lỗi phân quyền | OPA từ chối |
| MSG-12-06 | Kết quả tính thử theo Policy Pack {phien_ban}: điểm {cu} thành {moi}; mức {muc_cu} thành {muc_moi}. Kết quả chưa được ghi. | Thông tin | Tính thử |
| MSG-12-07 | Kỳ {ky} đã khoá. Điều chỉnh chỉ có hiệu lực sau khi yêu cầu mở lại kỳ được hai người có thẩm quyền phê duyệt. | Cảnh báo | Kết luận có điều chỉnh |
| MSG-12-08 | Khiếu nại {ma_kn} còn {so_ngay} ngày làm việc đến hạn trả lời. | Nhắc việc | Trước hạn 2 ngày |

### Liên kết thiết kế

QT-12 do MOD-07 `oversight` quản lý (bảng `complaint`, `complaint_response`), chạy trên mẫu luồng `xu-ly-khieu-nai` v2 của SVC-06, dùng OPA để chọn người xử lý, SVC-07 để tính thử, SVC-11 sinh văn bản trả lời, SVC-10 ký số, SVC-08 lưu tài liệu kèm theo, SVC-12 thông báo. Sự kiện: EVT-25 KhieuNaiDaGui, EVT-18, EVT-21; khi thi hành dẫn sang EVT-22 của QT-13. Nội dung khiếu nại là dữ liệu C2 (ADR-06). Đáp ứng nhóm tính năng 6 "khiếu nại, giải trình, bảo lưu ý kiến; trả lời bằng văn bản" của PL A2 và tiêu chí H6.

## QT-13. Hậu kiểm, mở lại kỳ (hai người duyệt)

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-13 |
| Tên | Hậu kiểm, mở lại kỳ (hai người duyệt) |
| Mục đích | Tổ chức hậu kiểm theo kế hoạch, đột xuất hoặc theo phản ánh; mở lại kỳ đã khoá trong phạm vi giới hạn khi có hai người có thẩm quyền khác nhau cùng duyệt; tra cứu sổ cái và kiểm tra toàn vẹn chuỗi băm, chỉ ra vị trí bị phá vỡ |
| Phạm vi áp dụng | Mọi kỳ đánh giá đã `DA_KHOA`; mọi dữ liệu đánh giá trong phạm vi được mở; mọi bản ghi sổ cái của tenant |
| Căn cứ | Dự thảo Quy định mới khoản 15.3 (hậu kiểm, huỷ, đánh giá lại); PL 4 III.2 (nhật ký giá trị trước, sau, lý do, người làm, người duyệt), V.2 (khoá và mở kỳ có phê duyệt), V.1 (tách vai trò); tiêu chí I2, I3, I8 |
| Tác nhân | AC-07 cơ quan tổ chức, AC-10 cơ quan kiểm tra (tạo yêu cầu, hậu kiểm); AC-08 người duyệt thứ nhất; AC-09 người duyệt thứ hai; SVC-09 ledger-service; SVC-07; SVC-06 |
| Tần suất và khối lượng ước tính | Mở lại kỳ là sự kiện hiếm. Giả định khoảng 1 yêu cầu trên 1.000 hồ sơ mỗi quý (chưa có số đo), tức khoảng 6.000 yêu cầu mỗi quý toàn quốc; kiểm tra toàn vẹn chạy định kỳ hằng đêm và theo yêu cầu, mỗi lần quét hàng triệu bản ghi (ví dụ 1.284.311 bản ghi trong 48 giây là số đo trên môi trường thử) |
| Điều kiện bắt đầu | Có kế hoạch hậu kiểm, quyết định hậu kiểm đột xuất, phản ánh, kết luận khiếu nại có điều chỉnh (QT-12) hoặc cảnh báo kết luận có vi phạm (QT-14) |
| Điều kiện kết thúc | Kỳ trở lại `DA_KHOA` sau khi tính lại, sinh lại biểu mẫu và ký lại; báo cáo hậu kiểm được lập (`oversight.post_audit`) |
| Đầu vào | Lý do, phạm vi (danh sách hồ sơ, bảng, trường được phép sửa), thời hạn mở, căn cứ |
| Đầu ra | `oversight.reopen_request`, hai bản `oversight.reopen_approval`, bản ghi sổ cái cho mọi thay đổi, snapshot mới, biểu mẫu phiên bản mới, `oversight.post_audit`, `ledger.verify_run` |
| Chỉ số đo hiệu quả quy trình | Thời gian từ tạo yêu cầu đến đủ hai phê duyệt; tỷ lệ yêu cầu bị từ chối; số thay đổi ngoài phạm vi bị chặn; kết quả kiểm tra toàn vẹn hằng đêm (mục tiêu 100% phân vùng khớp); thời gian chạy kiểm tra một tenant |

Hai năng lực được gộp trong một quy trình vì chúng phục vụ cùng một câu hỏi của cơ quan kiểm tra: "kết quả này có bị sửa sau khi đã khoá không, và nếu cần sửa thì ai đã cho phép". Năng lực thứ nhất là mở lại kỳ theo nguyên tắc bốn mắt (four-eyes principle): không một cá nhân nào, kể cả quản trị nền tảng, đủ quyền một mình để sửa dữ liệu của kỳ đã khoá. Năng lực thứ hai là sổ cái chỉ ghi thêm theo ADR-09: mỗi thay đổi dữ liệu đánh giá được ghi một bản ghi chứa mã băm của bản ghi trước trong cùng phân vùng (64 phân vùng mỗi tenant), mỗi giờ gốc Merkle của các phân vùng được niêm phong bằng chữ ký số và dấu thời gian RFC 3161, và bản neo được gửi cho bên thứ ba giữ. Công cụ kiểm tra tính lại chuỗi băm và chỉ ra chính xác bản ghi bị sửa nếu có ai can thiệp thẳng vào cơ sở dữ liệu.

Yêu cầu MLK-2027-007 dùng làm ví dụ được tạo để thi hành kết luận khiếu nại KN-2027-0031. Phạm vi chỉ gồm một hồ sơ và chỉ cho phép sửa hệ số C của nhiệm vụ then chốt 3 trong `product.acceptance`, sau đó tính lại điểm và xếp loại.

### Lưu đồ quy trình

![Hình B2.5. Lưu đồ làn bơi QT-13: hậu kiểm và mở lại kỳ với hai người duyệt](nv-b-qt13-luong.png){width=16cm}

Cơ quan kiểm tra hoặc tổ chức khởi đầu bằng hậu kiểm theo một trong ba căn cứ, tra cứu sổ cái để xác định lịch sử thay đổi, rồi lập yêu cầu mở lại kỳ. Người duyệt thứ nhất và người duyệt thứ hai lần lượt xác thực đa yếu tố bước lên (step-up MFA) và ký số phê duyệt. Làn "Hệ thống" kiểm tra bằng OPA rằng hai người duyệt khác nhau, khác người tạo, đúng thẩm quyền; nếu vi phạm, yêu cầu bị từ chối và ghi sổ cái. Nếu hợp lệ, kỳ chuyển `MO_LAI_HAU_KIEM` chỉ trong phạm vi đã duyệt và phát EVT-22. Người được giao sửa dữ liệu trong phạm vi, mỗi thay đổi kèm lý do và được ghi sổ cái. scoring-service tính lại bằng chính phiên bản Policy Pack của kỳ, document-service sinh lại biểu mẫu, esign-service ký lại, và kỳ được khoá lại. Kết quả hậu kiểm được lập thành báo cáo trong `oversight.post_audit`.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Khởi tạo hậu kiểm | AC-10 hoặc AC-07 | Tạo đợt hậu kiểm: loại (kế hoạch, đột xuất, theo phản ánh), phạm vi, căn cứ | SCR-1301 | `oversight.post_audit` (mở) | Theo kế hoạch |
| 2 | Tra cứu sổ cái | AC-10 | Lọc theo đối tượng, thời gian, loại sự kiện; xem giá trị trước, sau, người làm | SCR-1302 | Không thay đổi dữ liệu | Không giới hạn |
| 3 | Kiểm tra toàn vẹn | AC-10, SVC-09 | Chạy kiểm tra chuỗi băm theo phân vùng, đối chiếu niêm phong Merkle và bản neo | SCR-1302 | `ledger.verify_run`; nếu phá vỡ thì EVT-20 | Vài phút (tác vụ nền) |
| 4 | Lập yêu cầu mở lại kỳ | AC-07 hoặc AC-10 | Chọn loại, phạm vi hồ sơ, dữ liệu được phép sửa, thời hạn mở, lý do, căn cứ; xem ảnh hưởng dự kiến | SCR-1301 | `oversight.reopen_request` `CHO_DUYET_1` | Không giới hạn |
| 5 | Chỉ định người duyệt | Hệ thống (OPA) | Chọn người duyệt thứ nhất (`decider`) và thứ hai (`second_approver`) theo thẩm quyền, loại trừ người tạo và người đã tham gia hồ sơ | SCR-1301 | Hai task duyệt | Ngay khi tạo |
| 6 | Duyệt thứ nhất | AC-08 | Step-up MFA, ký số phê duyệt hoặc từ chối có ý kiến | SCR-1301 | `oversight.reopen_approval` (1); `CHO_DUYET_2` | 2 ngày làm việc |
| 7 | Duyệt thứ hai | AC-09 | Như bước 6 | SCR-1301 | `oversight.reopen_approval` (2); `DA_DUYET` | 2 ngày làm việc |
| 8 | Mở lại kỳ trong phạm vi | MOD-07, MOD-08 | Kiểm tra OPA lần cuối; chuyển kỳ sang `MO_LAI_HAU_KIEM` chỉ với phạm vi đã duyệt | Không có | EVT-22; ghi sổ cái | Ngay khi đủ hai phê duyệt |
| 9 | Sửa dữ liệu | Người được giao | Sửa đúng trường trong phạm vi, mỗi thay đổi có lý do; thay đổi ngoài phạm vi bị chặn | SCR-0703 (nghiệm thu) hoặc màn hình tương ứng | Bản ghi sổ cái giá trị trước, sau | Trong thời hạn mở |
| 10 | Tính lại | SVC-07 | Tính lại các hồ sơ trong phạm vi bằng phiên bản Policy Pack của kỳ; tạo snapshot mới, giữ snapshot cũ | Không có | EVT-10 | Trong 15 phút |
| 11 | Sinh lại, ký lại | SVC-11, SVC-10, AC-08 | Sinh phiên bản mới của Mẫu 2, Mẫu 3; người có thẩm quyền ký lại | SCR-1102 | EVT-17, EVT-18 | Trong thời hạn mở |
| 12 | Khoá lại kỳ | MOD-08 | Khi hoàn tất hoặc hết hạn mở, kỳ trở lại `DA_KHOA` | Không có | EVT-09 (khoá lại); ghi sổ cái | Tự động |
| 13 | Lập báo cáo hậu kiểm | AC-10 | Ghi kết luận, các thay đổi đã thực hiện, đính kèm bằng chứng xuất từ sổ cái | SCR-1301 | `oversight.post_audit` (đóng) | Theo kế hoạch |

Điểm then chốt ở bước 8 là "mở trong phạm vi" chứ không mở cả kỳ. Trạng thái `MO_LAI_HAU_KIEM` gắn với kỳ, nhưng quyền ghi được kiểm soát ở mức hồ sơ và trường: MOD-08 lưu phạm vi trong `oversight.reopen_request.scope_json` và nạp vào bộ nhớ đệm kiểm tra trạng thái kỳ (lớp bảo vệ 5 trong G3, chi phí dưới 0,1 ms mỗi lần ghi). Một API ghi vào dữ liệu của kỳ đang mở lại chỉ được chấp nhận khi đối tượng nằm trong phạm vi; ngược lại API trả lỗi 409 với mã `period-locked`.

Bước 10 bảo đảm kết quả mới tái lập được: snapshot mới có `policy_version` giống snapshot cũ và `input_hash` mới. Người kiểm tra có thể đối chiếu hai snapshot và thấy chính xác đầu vào nào đã thay đổi.

### Trạng thái đối tượng

![Hình B2.6. Trạng thái kỳ đánh giá config.period và yêu cầu mở lại kỳ oversight.reopen_request](nv-b-qt13-trangthai.png){width=14cm}

Phần trên của sơ đồ là trạng thái kỳ đánh giá đúng baseline mục 9.1: `MO` qua saga chốt kỳ sang `DANG_KHOA`, sau đó `DA_KHOA`; khi đủ hai phê duyệt thì sang `MO_LAI_HAU_KIEM` và trở lại `DA_KHOA` khi hoàn tất hoặc hết hạn. Phần dưới là trạng thái của yêu cầu mở lại kỳ, đề xuất mới trong tài liệu này: `CHO_DUYET_1`, `CHO_DUYET_2`, `DA_DUYET` hoặc `TU_CHOI`.

| Đối tượng | Trạng thái hiện tại | Sự kiện hoặc hành động | Trạng thái kế tiếp | Điều kiện kiểm soát |
|---|---|---|---|---|
| Kỳ | `DA_KHOA` | Yêu cầu mở lại đủ hai phê duyệt | `MO_LAI_HAU_KIEM` | OPA: hai người khác nhau, khác người tạo, đúng thẩm quyền; EVT-22 |
| Kỳ | `MO_LAI_HAU_KIEM` | Hoàn tất hoặc hết hạn mở | `DA_KHOA` | Mọi hồ sơ trong phạm vi đã tính lại và ký lại, hoặc hết hạn |
| Yêu cầu | (chưa có) | Tạo yêu cầu | `CHO_DUYET_1` | Đủ lý do, phạm vi, thời hạn |
| Yêu cầu | `CHO_DUYET_1` | Người duyệt 1 đồng ý | `CHO_DUYET_2` | Step-up MFA, ký số |
| Yêu cầu | `CHO_DUYET_2` | Người duyệt 2 đồng ý | `DA_DUYET` | Step-up MFA, ký số, khác người duyệt 1 |
| Yêu cầu | `CHO_DUYET_1` hoặc `CHO_DUYET_2` | Từ chối | `TU_CHOI` | Có ý kiến |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-13-01 | Kỳ đã khoá không sửa được dữ liệu đánh giá; mọi API ghi bị chặn | PL 4 V.2; tiêu chí I3 | Kiểm tra trạng thái kỳ trong bộ nhớ đệm ở mọi lệnh ghi của MOD-01 đến MOD-04; lỗi 409 |
| BR-13-02 | Mở lại kỳ phải có hai người có thẩm quyền khác nhau cùng phê duyệt, có lý do | PL 4 V.2; tiêu chí I3 | Hai bản `oversight.reopen_approval`; OPA kiểm tra `approver_1 ≠ approver_2 ≠ requester` |
| BR-13-03 | Người duyệt không được là người đã thẩm định hoặc quyết định hồ sơ bị mở | PL 4 V.1 | OPA đọc lịch sử tham gia hồ sơ |
| BR-13-04 | Mỗi phê duyệt yêu cầu xác thực đa yếu tố bước lên và ký số | Lớp bảo vệ 6 (G3); tiêu chí I4 | Keycloak step-up (ACR cao); esign-service |
| BR-13-05 | Mở lại kỳ chỉ trong phạm vi được duyệt (danh sách hồ sơ, bảng, trường) và có thời hạn | QĐ khoản 15.3 | `scope_json`; kiểm tra phạm vi ở mọi lệnh ghi; hết hạn tự khoá lại |
| BR-13-06 | Tính lại phải dùng đúng phiên bản Policy Pack đã dùng khi khoá kỳ; không áp phiên bản mới cho kỳ cũ | Tiêu chí I6 | scoring-service đọc `policy_version` từ snapshot gốc |
| BR-13-07 | Mọi thay đổi ghi giá trị trước, giá trị sau, lý do, người thực hiện, người duyệt, thời điểm | PL 4 III.2; tiêu chí I1 | Outbox ghi bản ghi sổ cái cùng giao dịch dữ liệu |
| BR-13-08 | Biểu mẫu và snapshot cũ được giữ nguyên; bản mới có số phiên bản tăng | Tiêu chí I5, I6 | Không có thao tác xoá, ghi đè; Object Lock với tệp |
| BR-13-09 | Sổ cái chỉ ghi thêm; mỗi bản ghi chứa băm bản ghi trước trong phân vùng; niêm phong gốc Merkle mỗi giờ bằng ký số và dấu thời gian RFC 3161 | ADR-09; tiêu chí I2, I8 | ledger-service; tài khoản CSDL chỉ có quyền INSERT; cụm CSDL riêng |
| BR-13-10 | Kiểm tra toàn vẹn phải chỉ ra được phân vùng và bản ghi đầu tiên bị phá vỡ | Tiêu chí I2 | Thuật toán quét tuần tự theo phân vùng, so băm lưu và băm tính lại |
| BR-13-11 | Phát hiện phá vỡ chuỗi thì tạo cảnh báo mức Cao và khoá ghi phân vùng đó cho tới khi được xử lý | ADR-09 | EVT-20; cờ khoá phân vùng trong ledger-service |
| BR-13-12 | Công cụ kiểm tra có thể chạy độc lập trên bản xuất, không cần tin máy chủ | Tiêu chí I2 | CLI Go công bố mã nguồn; bản xuất JSON kèm chữ ký |

### Ngoại lệ và xử lý

Khi chỉ có một người có thẩm quyền ở đơn vị (đơn vị nhỏ), OPA không tìm được người duyệt thứ hai và yêu cầu tự chuyển lên cấp trên trực tiếp chọn người duyệt thứ hai. Hệ thống không bao giờ hạ yêu cầu xuống một người duyệt.

Khi hết thời hạn mở mà việc sửa chưa xong, kỳ tự khoá lại; các thay đổi đã thực hiện vẫn được giữ và ghi sổ cái, nhưng các hồ sơ chưa tính lại xong được đánh dấu "chưa hoàn tất hậu kiểm" và phải tạo yêu cầu mới.

Khi kiểm tra toàn vẹn phát hiện phá vỡ chuỗi (như ở phân vùng 17, bản ghi 1042 trong ví dụ), ledger-service khoá ghi phân vùng, phát cảnh báo mức Cao và thông báo cho quản trị an toàn thông tin. Việc khôi phục không sửa lại bản ghi bị phá mà ghi một bản ghi "đánh dấu sự cố" mới, mở một phân đoạn chuỗi mới có tham chiếu niêm phong hợp lệ gần nhất, và quy trình ứng cứu sự cố được thực hiện theo TL-04 (OPS về sự cố an toàn thông tin).

Khi dịch vụ dấu thời gian không phản hồi lúc niêm phong, ledger-service vẫn niêm phong bằng chữ ký số và gắn dấu thời gian bù khi dịch vụ trở lại; niêm phong được đánh dấu "dấu thời gian trễ" để người kiểm tra biết.

### Đặc tả màn hình

#### SCR-1301. Yêu cầu mở lại kỳ

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Lập, xem ảnh hưởng và phê duyệt yêu cầu mở lại kỳ có phạm vi giới hạn, theo nguyên tắc hai người duyệt |
| Tác nhân | AC-07, AC-10 tạo; AC-08 người duyệt thứ nhất; AC-09 người duyệt thứ hai |
| Đường vào | Menu Giám sát › Hậu kiểm, mở lại kỳ; nút "Tạo yêu cầu mở lại kỳ" ở SCR-1202; thông báo task duyệt |
| Quyền | Tạo: `appraiser`, `inspector`; duyệt: `decider`, `second_approver` được OPA chỉ định |
| Thiết bị | Web desktop |

![Hình B2.7. SCR-1301 Yêu cầu mở lại kỳ MLK-2027-007 đang chờ người duyệt thứ hai](SCR-1301.png){width=16cm}

Màn hình hiển thị yêu cầu MLK-2027-007 dưới góc nhìn của người duyệt thứ hai Phan Văn Quân. Nhãn "Chờ người duyệt thứ hai" ở đầu trang cho biết bước hiện tại.

Vùng 1 là tiêu đề và dòng căn cứ: kỳ Quý IV/2026 đã khoá từ 12/01/2027 17:30, tạo bởi Lê Thu Hà, căn cứ kết luận khiếu nại KN-2027-0031. Vùng 2 là phần phạm vi: loại hậu kiểm "Theo khiếu nại, phản ánh", phạm vi "Danh sách hồ sơ cụ thể (1)", thời hạn mở 3 ngày làm việc đến 24/01/2027, và bảng đối tượng nêu rõ bảng `appraisal.appraisal_profile`, trạng thái `DA_KHOA`, dữ liệu được phép sửa là `product.acceptance` của nhiệm vụ then chốt 3 (hệ số C). Vùng 3 là lý do mở lại và bảng "Ảnh hưởng dự kiến (tính thử, chưa ghi)": điểm 91,2 thành 92,1, mức HTTNV giữ nguyên, 2 biểu mẫu cần sinh lại. Vùng 4 là thẻ "Hai người duyệt": Ngô Thanh Tâm đã duyệt ngày 20/01 10:14 với MFA và ký số; Phan Văn Quân đang chờ duyệt; ghi chú ràng buộc tách vai trò. Vùng 5 là thẻ duyệt với ô ý kiến bắt buộc, nút "Từ chối" và "Xác thực MFA và duyệt", kèm mô tả việc hệ thống sẽ làm khi đủ hai phê duyệt.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Mã yêu cầu | Nhãn | Không áp dụng | MLK-yyyy-nnn | Sinh tự động | Duy nhất trong tenant | `oversight.reopen_request.code` |
| 2 | Kỳ | Danh sách chọn | Có | Kỳ `DA_KHOA` | Từ căn cứ | Chỉ kỳ đã khoá | `oversight.reopen_request.period_id` |
| 3 | Loại hậu kiểm | Danh sách chọn | Có | KE_HOACH, DOT_XUAT, PHAN_ANH | Theo căn cứ | Không có | `oversight.reopen_request.audit_kind` |
| 4 | Phạm vi | Danh sách chọn và bảng | Có | Danh sách hồ sơ; bảng và trường được sửa | Từ căn cứ | Ít nhất 1 đối tượng; không chọn "toàn kỳ" nếu không có quyết định hậu kiểm toàn diện | `oversight.reopen_request.scope_json` |
| 5 | Thời hạn mở | Số ngày làm việc | Có | 1 đến 10 | 3 | Tính ngày kết thúc theo lịch tenant | `oversight.reopen_request.open_until` |
| 6 | Lý do mở lại | Văn bản dài | Có | 50 đến 4.000 ký tự | Trống | Không có | `oversight.reopen_request.reason` |
| 7 | Căn cứ | Liên kết | Có | Mã khiếu nại, cảnh báo, quyết định hậu kiểm | Từ nơi tạo | Đối tượng căn cứ phải tồn tại | `oversight.reopen_request.basis_ref` |
| 8 | Ảnh hưởng dự kiến | Bảng | Không áp dụng | Chỉ tiêu, trước, sau | Tính thử | Chỉ đọc | Trả về từ SVC-07 chế độ mô phỏng |
| 9 | Người duyệt và trạng thái | Thẻ | Không áp dụng | Họ tên, vai trò, thời điểm, MFA, chữ ký | Từ dữ liệu | Chỉ đọc | `oversight.reopen_approval` |
| 10 | Ý kiến người duyệt | Văn bản dài | Có | 10 đến 2.000 ký tự | Trống | Không có | `oversight.reopen_approval.comment` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Tạo yêu cầu | Người tạo có quyền | Lưu yêu cầu, gọi OPA chỉ định người duyệt, khởi động mẫu `mo-lai-ky-hai-nguoi` | `POST /api/v1/oversight/reopen-requests` | EVT-21 |
| Tính thử ảnh hưởng | Có phạm vi và điều chỉnh | Mô phỏng tính lại | `POST /api/v1/oversight/reopen-requests/{id}/simulate` | Không có |
| Xác thực MFA và duyệt | Người duyệt được chỉ định, đúng lượt | Step-up MFA, ký số phê duyệt; nếu là phê duyệt thứ hai thì mở lại kỳ | `POST /api/v1/oversight/reopen-requests/{id}/approvals` | EVT-18; khi đủ: EVT-22 |
| Từ chối | Người duyệt được chỉ định | Ghi ý kiến, đóng yêu cầu | `POST /api/v1/oversight/reopen-requests/{id}/reject` | EVT-21 |
| Huỷ yêu cầu | Người tạo, trước khi đủ phê duyệt | Đóng yêu cầu có lý do | `POST /api/v1/oversight/reopen-requests/{id}/cancel` | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-13-01 | Hai người duyệt phải là hai người khác nhau, khác người tạo yêu cầu và chưa tham gia thẩm định, quyết định hồ sơ bị mở (PL 4 V.1). | Lỗi phân quyền | OPA từ chối |
| MSG-13-02 | Cần xác thực bổ sung để phê duyệt mở lại kỳ. | Thông tin | Trước step-up MFA |
| MSG-13-03 | Yêu cầu {ma} đã đủ hai phê duyệt. Kỳ {ky} được mở lại trong phạm vi {so_doi_tuong} đối tượng đến {han}. | Thành công | Phát EVT-22 |
| MSG-13-04 | Kỳ {ky} đã khoá. Dữ liệu này nằm ngoài phạm vi được mở lại nên không thể sửa. | Lỗi | API ghi trả 409 |

#### SCR-1302. Tra cứu sổ cái và kiểm tra toàn vẹn

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho cơ quan kiểm tra tra cứu lịch sử thay đổi của một đối tượng, chạy kiểm tra toàn vẹn chuỗi băm, xem vị trí phá vỡ, niêm phong Merkle và bản neo, xuất bằng chứng |
| Tác nhân | AC-10 (`inspector`); AC-07 xem trong phạm vi đơn vị |
| Đường vào | Menu Giám sát › Sổ cái; liên kết từ SCR-1301, SCR-1401 |
| Quyền | `inspector` của tenant; mọi lần tra cứu được ghi sổ cái |
| Thiết bị | Web desktop |

![Hình B2.8. SCR-1302 Tra cứu sổ cái và kết quả kiểm tra chuỗi băm phát hiện phá vỡ tại bản ghi 1042](SCR-1302.png){width=16cm}

Màn hình là công cụ của người kiểm tra, nên ưu tiên tính chính xác và khả năng xuất bằng chứng. Dòng phụ cho biết cấu hình sổ cái của tenant: 64 phân vùng chuỗi, niêm phong Merkle mỗi giờ, dịch vụ dấu thời gian Viettel-CA.

Vùng 1 là thanh tra cứu với đối tượng (appraisal_profile của Trần Minh Hoàng, quý IV/2026), khoảng thời gian và loại sự kiện. Vùng 2 là bảng bản ghi sổ cái chỉ ghi thêm, mỗi dòng có mã bản ghi, thời điểm, topic sự kiện, người thực hiện, giá trị trước và sau; ví dụ dòng `product.evidence.accepted.v1` ngày 18/12 ghi C từ chưa có thành 0,85. Vùng 3 là kết quả kiểm tra chuỗi băm của phân vùng 17, minh hoạ ba bản ghi 1041, 1042, 1043 nối nhau bằng `prev` và `hash`; bản ghi 1042 viền đỏ với "4b07… ≠ tính lại 9d3c…", kèm kết luận nội dung đã bị sửa sau khi ghi, gốc Merkle niêm phong lúc 18/12 15:00 chứa băm gốc 4b07… khớp bản neo, đã tạo cảnh báo mức Cao và khoá ghi phân vùng. Vùng 4 là thẻ "Lần kiểm tra" VR-2027-0112: 64 phân vùng, 1.284.311 bản ghi, 7.224 niêm phong, chạy 48 giây, 63/64 phân vùng khớp. Vùng 5 là thẻ "Niêm phong và bản neo" với mã niêm phong, gốc, chữ ký hợp lệ, dấu thời gian RFC 3161 và xác nhận của cơ quan giám sát giữ bản neo.

Thời gian 48 giây cho khoảng 1,3 triệu bản ghi là số đo trên môi trường thử với 8 luồng song song theo phân vùng; con số này dùng để minh hoạ và sẽ được đo lại theo kịch bản PERF của TL-04 trên cấu hình triển khai thực.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Đối tượng | Ô tìm kiếm | Không | Loại đối tượng và mã | Trống | Chỉ đối tượng trong phạm vi quyền | `ledger.ledger_entry.aggregate_type`, `ledger.ledger_entry.aggregate_id` |
| 2 | Khoảng thời gian | Khoảng ngày | Không | dd/mm/yyyy | 90 ngày gần nhất | Tối đa 2 năm mỗi lần | `ledger.ledger_entry.occurred_at` |
| 3 | Loại sự kiện | Danh sách chọn | Không | Topic EVT | Tất cả | Không có | `ledger.ledger_entry.event_type` |
| 4 | Bảng bản ghi | Bảng | Không áp dụng | Mã, thời điểm, sự kiện, người, trước, sau | Theo bộ lọc | Phân trang con trỏ; trường C2 hiện dạng che | `ledger.ledger_entry` |
| 5 | Kết quả chuỗi | Sơ đồ khối | Không áp dụng | prev, hash, trạng thái khớp | Từ lần kiểm tra | Chỉ đọc | `ledger.verify_run.result_json` |
| 6 | Lần kiểm tra | Thẻ | Không áp dụng | Mã, phạm vi, thời gian chạy, kết quả | Lần gần nhất | Chỉ đọc | `ledger.verify_run` |
| 7 | Niêm phong, bản neo | Thẻ | Không áp dụng | Mã, gốc, chữ ký, dấu thời gian | Theo bản ghi chọn | Chỉ đọc | `ledger.merkle_seal`, `ledger.anchor_receipt` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Tìm | Luôn | Truy vấn sổ cái theo bộ lọc | `GET /api/v1/ledger/entries?aggregate=...&from=...&to=...` | Không có |
| Chạy kiểm tra toàn vẹn | `inspector` | Tạo tác vụ kiểm tra toàn bộ hoặc một phân vùng | `POST /api/v1/ledger/verify-runs` (202) | Nếu phá vỡ: EVT-20 |
| Xem bản ghi | Chọn dòng | Hiển thị chi tiết, băm, niêm phong chứa bản ghi | `GET /api/v1/ledger/entries/{id}` | Không có |
| Xuất bằng chứng (JSON và chữ ký) | Có kết quả | Xuất các bản ghi, niêm phong, bản neo trong phạm vi, ký bằng khoá của ledger-service | `POST /api/v1/ledger/exports` (202) | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-13-05 | Kiểm tra toàn vẹn hoàn tất: {so_khop}/{tong} phân vùng khớp, {so_ban_ghi} bản ghi, {thoi_gian} giây. | Thành công | Tác vụ xong, không phá vỡ |
| MSG-13-06 | Phát hiện chuỗi băm bị phá vỡ tại bản ghi {ma} của phân vùng {pv}. Đã tạo cảnh báo mức Cao và khoá ghi phân vùng. | Lỗi nghiêm trọng | Phát hiện phá vỡ |
| MSG-13-07 | Tệp bằng chứng đã sẵn sàng. Có thể kiểm tra độc lập bằng công cụ dòng lệnh ledger-verify. | Thành công | Xuất xong |

### Liên kết thiết kế

QT-13 dùng MOD-07 `oversight` (bảng `reopen_request`, `reopen_approval`, `post_audit`), MOD-08 `config` (`config.period`), SVC-09 ledger-service (`ledger_entry`, `merkle_seal`, `anchor_receipt`, `verify_run`), SVC-02 iam cho step-up MFA, SVC-06 với mẫu `mo-lai-ky-hai-nguoi` v1, SVC-07, SVC-11, SVC-10. Sự kiện: EVT-22 KyDaMoLaiHauKiem, EVT-19 SoCaiDaNiemPhong, EVT-20 (khi phá vỡ chuỗi), EVT-09 (khoá lại), EVT-10, EVT-17, EVT-18. Đáp ứng trực tiếp các tiêu chí I1 (nhật ký trước, sau, lý do), I2 (sổ cái chuỗi băm, niêm phong, công cụ kiểm tra), I3 (khoá kỳ, mở lại hai người duyệt), I8 (dấu thời gian RFC 3161) và H6. Thiết kế chi tiết sổ cái xem TL-01 (ADR-09) và TL-03 (schema `ledger`); biện pháp bảo vệ cụm CSDL sổ cái xem TL-04.

## QT-14. Cảnh báo bất thường

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-14 |
| Tên | Cảnh báo bất thường |
| Mục đích | Tự động phát hiện tám dấu hiệu bất thường trong dữ liệu đánh giá, chuyển cảnh báo kèm chuỗi bằng chứng tới cơ quan tổ chức để xem xét, và chuyển cơ quan kiểm tra khi cần; cảnh báo không phải là kết luận vi phạm |
| Phạm vi áp dụng | Mọi dữ liệu mục tiêu, nhiệm vụ, minh chứng, nghiệm thu, tự đánh giá, thẩm định, xếp loại của tenant |
| Căn cứ | PL 4 V.6 (phát hiện dấu hiệu bất thường), III.5 (cảnh báo minh chứng trùng); báo cáo BGĐ slide 31 (tám dấu hiệu); PL A2 nhóm 6 |
| Tác nhân | SVC-15 analytics (job phát hiện); MOD-07 oversight; AC-07 cơ quan tổ chức; AC-10 cơ quan kiểm tra |
| Tần suất và khối lượng ước tính | Job chạy hằng đêm lúc 02:00 theo cell, cộng phát hiện tức thời cho minh chứng trùng mã băm. Giả định khoảng 1 cảnh báo trên 500 hồ sơ mỗi quý (ví dụ Ban Tổ chức có 23 cảnh báo trong 90 ngày), tức khoảng 12.000 cảnh báo mỗi quý toàn quốc |
| Điều kiện bắt đầu | Job phát hiện tìm thấy dữ liệu vượt ngưỡng của một dấu hiệu, hoặc evidence-service phát hiện trùng mã băm |
| Điều kiện kết thúc | Cảnh báo `DONG` với một trong các kết quả: hợp lệ, không vi phạm, có vi phạm (đã chuyển hậu kiểm), tự đóng |
| Đầu vào | Dữ liệu CDC trong ClickHouse; ngưỡng phát hiện của tenant; `evidence.dedup_index` |
| Đầu ra | `oversight.anomaly_alert`, `oversight.alert_action`, cờ chặn tính điểm tạm thời, yêu cầu mở lại kỳ nếu có vi phạm |
| Chỉ số đo hiệu quả quy trình | Tỷ lệ cảnh báo được xử lý trong 10 ngày làm việc; tỷ lệ cảnh báo đúng (dẫn tới kết luận có vi phạm) so với tổng; tỷ lệ cảnh báo xác nhận hợp lệ (dùng để hiệu chỉnh ngưỡng); số dấu hiệu lặp lại ở cùng đơn vị |

Báo cáo BGĐ (slide 31) xác định tám dấu hiệu bất thường cần theo dõi, và PL 4 V.6 yêu cầu nền tảng phát hiện và chuyển cho cơ quan tổ chức kiểm tra. Bản đánh giá kiến trúc xếp năng lực này vào H6 ở mức "Một phần" vì hệ thống hiện tại không có. QT-14 đặt việc phát hiện ở phía đọc (SVC-15 analytics) để không ảnh hưởng tới đường ghi của mission-core: job chạy trên ClickHouse với dữ liệu CDC đã giả danh hoá theo ADR-06, chỉ đẩy về MOD-07 mã đối tượng và chuỗi bằng chứng; MOD-07 mới là nơi lưu cảnh báo và quản lý vòng đời.

Nguyên tắc quan trọng nhất của quy trình là cảnh báo không phải kết luận vi phạm. Cảnh báo chỉ cho biết "chỗ này cần xem", vì nhiều dấu hiệu có lý do hợp lệ: một đơn vị có tỷ lệ xuất sắc cao có thể thực sự xuất sắc, một báo cáo tháng có thể được nộp lại vì cùng mẫu. Vì vậy đối tượng của cảnh báo không thấy cảnh báo cho tới khi có kết luận kiểm tra, mọi thao tác xử lý đều có lý do và được ghi sổ cái, và tác động tự động của cảnh báo được giới hạn ở mức tạm thời, có thể đảo ngược (ví dụ tạm chưa tính điểm minh chứng trùng).

Bảng dưới liệt kê tám dấu hiệu, cách phát hiện và hành động mặc định. Ngưỡng là tham số của tenant, có thể điều chỉnh trên màn hình "Ngưỡng phát hiện" bởi người có vai trò `policy_author`.

| Số | Dấu hiệu | Cách phát hiện (ngưỡng mặc định) | Hành động tự động | Hành động đề xuất cho người xử lý |
|---|---|---|---|---|
| 1 | Tự chấm cao hơn thẩm định có hệ thống | Mức tự đề xuất cao hơn mức thẩm định ≥ 3 quý liên tiếp, hoặc điểm tự tính cao hơn điểm sau thẩm định ≥ 5 điểm trong 2 quý | Gợi ý trên hàng đợi thẩm định (SCR-0902) | Nhắc trao đổi với cán bộ; ghi nhận vào nhận xét |
| 2 | Xếp loại đồng loạt, cào bằng | Tỷ lệ HTXSNV của đơn vị vượt ngưỡng khối, hoặc ≥ 90% cán bộ cùng một mức, hoặc độ lệch chuẩn điểm thấp bất thường | Cảnh báo trên SCR-0904 trước khi ký lô | Yêu cầu phiên hiệu chỉnh; xem lại tiêu chí |
| 3 | Mục tiêu đặt quá dễ | KR đạt ≥ 100% ngay trong 2 tuần đầu kỳ, hoặc chỉ tiêu thấp hơn kết quả kỳ trước ≥ 20% | Gắn nhãn trên cây OKR (người duyệt OKR thấy) | Xem lại chỉ tiêu ở kỳ sau; ghi nhận khi thẩm định |
| 4 | Minh chứng trùng hoặc dùng lại | Cùng SHA-256 cho nhiều sản phẩm khác nhau, hoặc tương đồng nội dung OCR ≥ 95% | Tạm chưa tính điểm các minh chứng liên quan | Xác nhận hợp lệ hoặc giao kiểm tra |
| 5 | Cập nhật dồn vào cuối kỳ | ≥ 60% lượt cập nhật tiến độ của đơn vị dồn vào 3 ngày cuối kỳ hoặc cuối tuần | Cảnh báo cho quản lý trực tiếp | Nhắc cập nhật đều; ghi nhận khi đánh giá |
| 6 | Sửa dữ liệu khi đã biết kết quả | Có thay đổi mục tiêu, kế hoạch, nghiệm thu sau thời điểm tính điểm tạm hoặc sau khoá kỳ (qua mở lại) | Cảnh báo mức Cao | Giao kiểm tra; đối chiếu sổ cái |
| 7 | Chẻ nhỏ công việc | Số nhiệm vụ của một người tăng ≥ 50% so với kỳ trước trong khi tổng khối lượng không đổi, hoặc nhiều nhiệm vụ cùng sản phẩm, cùng hạn | Cảnh báo cho người duyệt kế hoạch | Xem lại kế hoạch; gộp nhiệm vụ ở kỳ sau |
| 8 | Miễn trừ "khách quan" lặp lại | Một người nghiệm thu áp miễn trừ chậm khách quan ≥ 3 lần trong 2 quý, hoặc tỷ lệ miễn trừ vượt ngưỡng khối | Cảnh báo cho cơ quan tổ chức | Xem căn cứ miễn trừ; giao kiểm tra nếu cần |

### Lưu đồ quy trình

![Hình B2.9. Lưu đồ làn bơi QT-14: phát hiện và xử lý cảnh báo bất thường](nv-b-qt14-luong.png){width=16cm}

Dữ liệu từ các schema nghiệp vụ được Debezium đẩy sang ClickHouse. Job đêm của analytics chạy tám luật với ngưỡng của từng tenant, chấm mức Cao, Vừa, Thấp và đính kèm chuỗi bằng chứng (mã bản ghi sổ cái, mã minh chứng, mã băm). Mỗi phát hiện sinh một EVT-20; MOD-07 tạo `oversight.anomaly_alert` và áp hành động tự động tương ứng. Cơ quan tổ chức xem cảnh báo trên trung tâm cảnh báo, đi theo chuỗi truy vết tới minh chứng và đánh giá. Nếu thấy hợp lệ, họ xác nhận có lý do và cảnh báo đóng, cờ chặn được gỡ. Nếu cần kiểm tra, họ giao cho cơ quan kiểm tra; kết luận có vi phạm dẫn tới hậu kiểm (QT-13), kết luận không vi phạm thì đóng.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Phát hiện | SVC-15 | Job đêm chạy 8 luật; minh chứng trùng mã băm phát hiện tức thời khi tải lên | Không có | EVT-20 | 02:00 hằng đêm; tức thời với dấu hiệu 4 |
| 2 | Tạo cảnh báo | MOD-07 | Tạo `anomaly_alert` (mức, dấu hiệu, đối tượng, chuỗi bằng chứng); chống trùng theo khoá dấu hiệu và đối tượng | Không có | `oversight.anomaly_alert` `MOI`; EVT-21 tới người xử lý | Ngay khi nhận EVT-20 |
| 3 | Áp hành động tự động | MOD-07 | Áp hành động mặc định của dấu hiệu (ví dụ tạm chưa tính điểm minh chứng trùng) | Không có | `oversight.alert_action` (tự động) | Ngay |
| 4 | Xem cảnh báo | AC-07 | Lọc theo dấu hiệu, mức, trạng thái; mở chi tiết | SCR-1401 | `DANG_XEM` | Trong 10 ngày làm việc |
| 5 | Truy vết bằng chứng | AC-07 | Đi theo chuỗi điểm, cấu phần, KPI, sản phẩm, minh chứng; mở sổ cái nếu cần | SCR-1401, SCR-1603, SCR-1302 | Không thay đổi dữ liệu | Như trên |
| 6 | Xác nhận hợp lệ | AC-07 | Ghi lý do; gỡ hành động tự động | SCR-1401 | `HOP_LE` rồi `DONG`; `alert_action` | Như trên |
| 7 | Giao kiểm tra | AC-07 | Ghi nội dung giao; chọn cơ quan kiểm tra | SCR-1401 | `DA_GIAO_KIEM_TRA`; EVT-21 | Như trên |
| 8 | Kiểm tra, kết luận | AC-10 | Kiểm tra theo nghiệp vụ; kết luận có hoặc không vi phạm | SCR-1401 | `CO_VI_PHAM` hoặc `KHONG_VI_PHAM` | Theo kế hoạch kiểm tra |
| 9 | Chuyển hậu kiểm | AC-10 | Với kết luận có vi phạm, tạo yêu cầu mở lại kỳ (QT-13) | SCR-1301 | `oversight.reopen_request` | Sau bước 8 |
| 10 | Tự đóng | Hệ thống | Nếu dữ liệu nguồn hết dấu hiệu trước khi xử lý (ví dụ minh chứng trùng đã được thay) thì tự đóng có ghi chú | Không có | `TU_DONG_DONG` rồi `DONG` | Khi job chạy lại |

### Trạng thái đối tượng

![Hình B2.10. Sơ đồ trạng thái cảnh báo oversight.anomaly_alert](nv-b-qt14-trangthai.png){width=13cm}

Bộ trạng thái của cảnh báo là đề xuất mới (ghi trong tệp ghi chú). Trạng thái thể hiện rõ sự phân biệt giữa "xem xét" và "kết luận": chỉ cơ quan kiểm tra mới đưa cảnh báo sang `CO_VI_PHAM`, còn cơ quan tổ chức chỉ xác nhận hợp lệ hoặc giao kiểm tra.

| Trạng thái hiện tại | Sự kiện hoặc hành động | Trạng thái kế tiếp | Điều kiện kiểm soát |
|---|---|---|---|
| (chưa có) | EVT-20 | `MOI` | Không trùng cảnh báo mở cùng dấu hiệu, đối tượng |
| `MOI` | Người xử lý mở | `DANG_XEM` | Người có vai trò `appraiser` trong phạm vi |
| `MOI` | Dữ liệu nguồn hết dấu hiệu | `TU_DONG_DONG` | Job chạy lại không còn phát hiện |
| `DANG_XEM` | Xác nhận hợp lệ | `HOP_LE` | Có lý do ≥ 20 ký tự |
| `DANG_XEM` | Giao kiểm tra | `DA_GIAO_KIEM_TRA` | Có nội dung giao |
| `DA_GIAO_KIEM_TRA` | Kết luận | `CO_VI_PHAM` hoặc `KHONG_VI_PHAM` | Người có vai trò `inspector` |
| `HOP_LE`, `CO_VI_PHAM`, `KHONG_VI_PHAM`, `TU_DONG_DONG` | Hoàn tất | `DONG` | `CO_VI_PHAM` cần có yêu cầu hậu kiểm đã tạo |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-14-01 | Hệ thống phát hiện tám dấu hiệu bất thường theo ngưỡng cấu hình của tenant | PL 4 V.6; slide 31 | Job SQL trên ClickHouse, ngưỡng trong `config.tenant_setting` |
| BR-14-02 | Cảnh báo không phải kết luận vi phạm; chỉ cơ quan kiểm tra được kết luận có vi phạm | PL 4 V.6 | Trạng thái `CO_VI_PHAM` chỉ đặt được bởi vai trò `inspector` |
| BR-14-03 | Đối tượng của cảnh báo không thấy cảnh báo cho tới khi có kết luận kiểm tra | Nguyên tắc khách quan khi kiểm tra | OPA ẩn `anomaly_alert` với chủ thể là đối tượng |
| BR-14-04 | Minh chứng bị cảnh báo trùng chưa được tính điểm cho tới khi người có thẩm quyền xác nhận | PL 4 III.5 | Cờ chặn trong `alert_action`, scoring-service đọc khi tính |
| BR-14-05 | Mọi thao tác xử lý cảnh báo (xác nhận, giao, đóng) phải có lý do và được ghi sổ cái | PL 4 III.2 | Trường lý do bắt buộc; outbox ghi sổ cái |
| BR-14-06 | Job phát hiện chỉ đọc dữ liệu C0, C1 và C2 đã giả danh hoá; không đọc nội dung C3 | ADR-06 | CDC chỉ đẩy cột cho phép; chuỗi bằng chứng chỉ chứa mã |
| BR-14-07 | Phá vỡ chuỗi băm sổ cái luôn tạo cảnh báo mức Cao | ADR-09 | ledger-service phát EVT-20 |
| BR-14-08 | Người xử lý cảnh báo không được là đối tượng của cảnh báo | PL 4 V.1 | OPA |
| BR-14-09 | Tỷ lệ xác nhận hợp lệ của mỗi dấu hiệu được theo dõi để hiệu chỉnh ngưỡng; thay đổi ngưỡng có ghi lý do và phiên bản | Chất lượng phát hiện | Màn hình "Ngưỡng phát hiện", lưu phiên bản trong `config.tenant_setting` |

### Ngoại lệ và xử lý

Nếu job phát hiện lỗi hoặc chạy quá thời gian, analytics ghi lỗi, cảnh báo đã có không bị ảnh hưởng, và vận hành nhận cảnh báo kỹ thuật theo TL-04. Job được thiết kế luỹ đẳng: chạy lại không tạo cảnh báo trùng nhờ khoá (tenant, dấu hiệu, đối tượng, kỳ).

Nếu cảnh báo minh chứng trùng xảy ra ngay trước khoá kỳ, cờ chặn làm các minh chứng đó không được tính khi khoá kỳ. Sau khi khoá, nếu cảnh báo được xác nhận hợp lệ, điều chỉnh điểm phải đi qua mở lại kỳ (QT-13). Để giảm trường hợp này, saga chốt kỳ (QT-08) hiển thị danh sách cảnh báo dấu hiệu 4 còn mở trước khi khoá.

Nếu một đơn vị có quá nhiều cảnh báo cùng dấu hiệu (ví dụ dấu hiệu 5 ở mọi phòng), MOD-07 gộp thành một cảnh báo cấp đơn vị để tránh làm ngập trung tâm cảnh báo.

### Đặc tả màn hình

#### SCR-1401. Trung tâm cảnh báo

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Tập trung mọi cảnh báo bất thường của phạm vi quản lý, cho phép lọc, xem chuỗi truy vết bằng chứng và xử lý từng cảnh báo |
| Tác nhân | AC-07 (`appraiser`); AC-10 (`inspector`) |
| Đường vào | Menu Giám sát › Cảnh báo; thông báo cảnh báo mức Cao; SCR-1601 |
| Quyền | Trong phạm vi cây tổ chức; ẩn cảnh báo mà người xem là đối tượng |
| Thiết bị | Web desktop |

![Hình B2.11. SCR-1401 Trung tâm cảnh báo quý IV/2026, chi tiết cảnh báo minh chứng trùng mã băm](SCR-1401.png){width=16cm}

Màn hình theo tinh thần slide 32 của báo cáo BGĐ: danh sách cảnh báo bên trái, chi tiết và chuỗi truy vết bên phải. Dòng phụ cho biết job phát hiện chạy lúc 02:00 hằng đêm trên ClickHouse và có 5 cảnh báo mới.

Vùng 1 là bảng cảnh báo với mức (Cao, Vừa, Thấp), dấu hiệu, đối tượng, kỳ và trạng thái; tám dòng minh hoạ cả tám dấu hiệu, trong đó dòng "Minh chứng trùng mã băm, Phòng C" đang được chọn. Vùng 4 là ghi chú màu cam "Cảnh báo chỉ chỉ ra chỗ cần xem, không phải kết luận vi phạm" cùng các nguyên tắc ghi sổ cái và ẩn với đối tượng; bên dưới là thẻ phân bố 90 ngày (23 cảnh báo mới, 14 xác nhận hợp lệ, 7 giao kiểm tra, 2 có vi phạm sau kiểm tra). Vùng 2 là phần đầu chi tiết cảnh báo với chuỗi truy vết PL 4: điểm nhóm thường xuyên của Phòng C, cấu phần A của 3 nhiệm vụ, ba sản phẩm "Báo cáo tháng 10, 11, 12", cùng một tệp BC-thang.pdf có SHA-256 5e88…a1f4; bảng người nộp và thời điểm; nguồn sổ cái (ledger_entry 88412, 90177, 93305) và `evidence.dedup_index`. Vùng 3 là ghi chú tác động (3 minh chứng bị gắn cờ chưa tính điểm), ô ghi chú xử lý bắt buộc và hai nút "Xác nhận hợp lệ", "Giao kiểm tra".

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Lọc dấu hiệu, mức | Danh sách chọn | Không | 8 dấu hiệu; Cao, Vừa, Thấp | Tất cả | Không có | Trạng thái giao diện |
| 2 | Bảng cảnh báo | Bảng | Không áp dụng | Mức, dấu hiệu, đối tượng, kỳ, trạng thái | Sắp theo mức rồi thời gian | Phân trang con trỏ | `oversight.anomaly_alert` |
| 3 | Phân bố 90 ngày | Thẻ số | Không áp dụng | Số nguyên | Tính tức thời | Chỉ đọc | `oversight.anomaly_alert` (đếm theo trạng thái) |
| 4 | Chuỗi truy vết | Danh sách lồng | Không áp dụng | Từ điểm tới minh chứng | Từ cảnh báo | Chỉ đọc | `oversight.anomaly_alert.evidence_chain_json` |
| 5 | Bảng nguồn | Bảng | Không áp dụng | Người nộp, nhiệm vụ, thời điểm | Từ chuỗi | Chỉ đọc | `product.evidence_version`, `evidence.dedup_index` |
| 6 | Tác động tự động | Nhãn | Không áp dụng | Văn bản | Từ hành động | Chỉ đọc | `oversight.alert_action` (loại tự động) |
| 7 | Ghi chú xử lý | Văn bản dài | Có | 20 đến 2.000 ký tự | Trống | Bắt buộc với mọi hành động | `oversight.alert_action.note` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Chọn cảnh báo | Luôn | Tải chi tiết; nếu `MOI` thì chuyển `DANG_XEM` | `GET /api/v1/oversight/alerts/{id}` | Không có |
| Xác nhận hợp lệ | `DANG_XEM`, đủ ghi chú | Đóng cảnh báo hợp lệ, gỡ cờ chặn | `POST /api/v1/oversight/alerts/{id}/actions` (type = HOP_LE) | EVT-21; ghi sổ cái |
| Giao kiểm tra | `DANG_XEM`, đủ ghi chú | Giao cho cơ quan kiểm tra | `POST /api/v1/oversight/alerts/{id}/actions` (type = GIAO_KIEM_TRA) | EVT-21 |
| Kết luận | `inspector`, `DA_GIAO_KIEM_TRA` | Ghi kết luận; có vi phạm thì mở SCR-1301 tạo yêu cầu | `POST /api/v1/oversight/alerts/{id}/actions` (type = KET_LUAN) | EVT-21 |
| Ngưỡng phát hiện | `policy_author` | Mở màn hình ngưỡng, lưu phiên bản mới có lý do | `PUT /api/v1/config/anomaly-thresholds` | Không có |
| Mở sổ cái | Luôn | Chuyển sang SCR-1302 với bộ lọc theo bản ghi | Không có (điều hướng) | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-14-01 | Có {n} cảnh báo mới mức Cao trong phạm vi của đồng chí. | Thông báo đẩy | Sau job đêm |
| MSG-14-02 | Cảnh báo chỉ chỉ ra chỗ cần xem, không phải kết luận vi phạm. Vui lòng ghi rõ lý do xử lý. | Thông tin | Mở chi tiết lần đầu |
| MSG-14-03 | Đã xác nhận hợp lệ. {n} minh chứng được tính điểm trở lại. | Thành công | Xác nhận hợp lệ |
| MSG-14-04 | Kỳ {ky} đã khoá. Kết luận có vi phạm cần được thi hành qua yêu cầu mở lại kỳ. | Cảnh báo | Kết luận có vi phạm |

### Liên kết thiết kế

QT-14 dùng SVC-15 analytics (ClickHouse, job phát hiện), MOD-07 `oversight` (`anomaly_alert`, `alert_action`), SVC-08 evidence-service (`evidence.dedup_index`), SVC-09 ledger-service (chuỗi bằng chứng), SVC-12 thông báo, SVC-07 (đọc cờ chặn). Sự kiện: EVT-20 DauHieuBatThuong, EVT-21, và gián tiếp EVT-22 khi chuyển hậu kiểm. Dữ liệu vào job tuân thủ ADR-06; kiến trúc phía đọc theo ADR-14 (CQRS). Đáp ứng tiêu chí H6 (phát hiện 8 dấu hiệu) và H3 (cảnh báo minh chứng trùng).

# Quy trình cấu hình, phân tích và trợ lý AI

Chương này đặc tả bốn quy trình làm cho nền tảng thích ứng được với nhiều loại khách hàng mà không phải sửa mã: soạn, kiểm thử và phát hành Policy Pack trong Config Studio (QT-15), bảng điều hành và báo cáo cho lãnh đạo (QT-16), trợ lý AI tiếng Việt (QT-17) và cấu hình giao diện riêng theo tenant bằng UI Pack (QT-18). Ba trong bốn quy trình là câu trả lời trực tiếp cho các góp ý của lãnh đạo: QT-15 hiện thực cách chống "mạng nhện" luồng duyệt của góp ý G2 bằng mẫu và tham số; QT-18 hiện thực kết luận của góp ý G1 về giao diện riêng theo tenant với SDUI có giới hạn; QT-16 và QT-17 đặt toàn bộ phần đọc nặng và phần AI ra ngoài đường găng của mission-core, phù hợp tinh thần của góp ý G3 về hiệu năng.

Chương phân biệt rõ hai loại "cấu hình". Cấu hình nghiệp vụ (công thức, bảng xếp loại, luồng duyệt, danh mục) nằm trong Policy Pack, ảnh hưởng tới kết quả đánh giá, nên phải qua test case vàng, phê duyệt và ký số, thời gian phát hành tối đa 5 ngày (NFR-07). Cấu hình trình bày (màu, logo, bố cục, biểu mẫu) nằm trong UI Pack, không ảnh hưởng tới phép tính, nên phát hành trong 1 ngày và quay lui được ngay. Hai loại gói được gắn với nhau bằng tham chiếu phiên bản để biểu mẫu luôn khớp quy tắc.

## QT-15. Soạn, kiểm thử và phát hành Policy Pack

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-15 |
| Tên | Soạn, kiểm thử và phát hành Policy Pack |
| Mục đích | Cho BA cấu hình soạn công thức tính điểm dạng khối ghép, đặt tham số, sửa bảng quyết định xếp loại DMN, cấu hình luồng duyệt bằng chọn mẫu và tham số, chạy bộ test case vàng đạt 100%, gửi phê duyệt, ký số và phát hành gắn với kỳ hiệu lực |
| Phạm vi áp dụng | Policy Pack gốc của khối (Ban Tổ chức TW, Bộ Nội vụ soạn) và Policy Pack kế thừa của tenant (chỉ được sửa phần tenant được phép) |
| Căn cứ | PL 4 VI.5 (Config Studio); PL 3 (công thức); QĐ Điều 11 (điều kiện mức); PL A3, A4 (điểm cần làm rõ: cách tính chất lượng, ngày lịch hay ngày làm việc, cách hiểu 30%); ADR-19 (CEL, DMN 1.4, test case vàng); ADR-04 (mẫu và tham số); NFR-07 |
| Tác nhân | AC-03 BA cấu hình (`policy_author`); AC-08 người phê duyệt phát hành (lãnh đạo cơ quan tổ chức); SVC-07 scoring-service; SVC-06 workflow-service; SVC-10 esign-service |
| Tần suất và khối lượng ước tính | Vài lần mỗi năm cho mỗi khối; tổng số phiên bản phát hành toàn quốc ước tính dưới 200 mỗi năm. Số mẫu luồng toàn hệ thống khoảng 20 đến 40 (hiện có 27 mẫu nhân phiên bản), không tăng theo số tenant |
| Điều kiện bắt đầu | Có văn bản mới, hướng dẫn mới hoặc yêu cầu điều chỉnh; BA nhân bản phiên bản đang dùng |
| Điều kiện kết thúc | Phiên bản mới `DA_PHAT_HANH`, gắn kỳ hiệu lực qua `policy.policy_binding`, đã đồng bộ xuống các cell; phiên bản cũ vẫn áp cho các kỳ đã khoá |
| Đầu vào | Văn bản căn cứ, phiên bản đang dùng, bộ test case vàng đã được ký duyệt |
| Đầu ra | `policy.policy_pack_version` mới, `policy.policy_rule`, `policy.decision_table`, `policy.test_run`, `flowable.wf_binding`, chữ ký phát hành, EVT-13 |
| Chỉ số đo hiệu quả quy trình | Thời gian từ khi có văn bản tới khi phát hành (mục tiêu ≤ 5 ngày); tỷ lệ phiên bản phải thu hồi sau phát hành (mục tiêu 0); số ca test vàng; tỷ lệ khớp cel-java và cel-js (100%) |

Bản đánh giá kiến trúc ghi nhận hệ thống hiện tại không có Policy Pack (tiêu chí H8 "Một phần") và có ba bộ máy tính song song (H4). Hệ quả là mỗi lần văn bản thay đổi phải sửa mã và phát hành lại phần mềm, còn kết quả kỳ cũ không tái lập được. QT-15 tách toàn bộ quy tắc thành dữ liệu có phiên bản: công thức viết bằng CEL (Common Expression Language), bảng xếp loại viết bằng DMN 1.4, luồng duyệt chọn từ danh mục mẫu đã kiểm thử. scoring-service là nơi duy nhất thực thi, bằng cel-java và Flowable DMN.

Config Studio không phải công cụ lập trình. BA cấu hình thao tác trên các khối ghép (ví dụ khối "A · Số lượng", "B · Chất lượng", "C · Tiến độ") và tham số dạng danh sách chọn; biểu thức CEL được sinh ra phía sau và có thể xem ở chế độ "Xem dạng CEL" để kiểm tra. Mọi biểu thức được kiểm tra kiểu khi lưu. Thay đổi chỉ có hiệu lực khi đạt 100% bộ test case vàng, được người có thẩm quyền ký phê duyệt và gắn với một kỳ hiệu lực trong tương lai; kỳ đã khoá không bao giờ bị tính lại bằng phiên bản mới.

Về luồng duyệt, Config Studio không có trình vẽ BPMN tự do. Đây là quyết định có chủ ý của ADR-04 để trả lời góp ý G2: nếu mỗi tenant tự vẽ luồng, số định nghĩa BPMN sẽ tăng theo số tenant (tới 5.000), mỗi định nghĩa cần kiểm thử riêng, và các luồng gọi chéo nhau tạo thành "mạng nhện" khó vận hành. Thay vào đó, đội sản phẩm phát triển và kiểm thử khoảng 20 đến 40 mẫu luồng; tenant chỉ chọn mẫu và điền tham số (số cấp duyệt, vai trò từng cấp, thời hạn, hành vi khi quá hạn, bước ký số, điều kiện bỏ qua cấp), lưu trong `flowable.wf_binding`.

### Lưu đồ quy trình

![Hình B3.1. Lưu đồ làn bơi QT-15: soạn, kiểm thử và phát hành Policy Pack](nv-b-qt15-luong.png){width=16cm}

BA cấu hình nhân bản phiên bản đang dùng thành phiên bản nháp, sửa công thức và tham số, sửa bảng quyết định, chọn mẫu luồng và tham số, rồi chạy test case vàng. Làn "Hệ thống" kiểm tra hai điều kiện: 100% ca đạt và kết quả cel-java trùng cel-js. Không đạt thì quay lại bước sửa; đạt thì phiên bản chuyển `DAT_KIEM_THU`. Người phê duyệt xem khác biệt so với phiên bản đang dùng và kết quả test, rồi ký số phê duyệt hoặc từ chối. Sau khi ký, saga phát hành của workflow-service gắn kỳ hiệu lực, đồng bộ gói từ vùng điều phối toàn cục xuống các cell, nạp vào scoring-service và phát EVT-13. Phiên bản mới áp từ kỳ hiệu lực; các kỳ đã khoá giữ phiên bản cũ.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Nhân bản phiên bản | AC-03 | Chọn gói và phiên bản đang dùng; tạo phiên bản nháp mới kế thừa toàn bộ | SCR-1501 | `policy.policy_pack_version` `NHAP` | Ngày 1 |
| 2 | Soạn công thức | AC-03 | Kéo, sửa khối công thức; kiểm tra kiểu tức thời; xem trước với hồ sơ mẫu | SCR-1501 | `policy.policy_rule` | Ngày 1 đến 2 |
| 3 | Đặt tham số | AC-03 | Cách tính chất lượng, loại ngày tính chậm, làm tròn, điểm tối đa nhóm, ngưỡng việc vượt | SCR-1501 | `policy.policy_rule` (tham số) | Ngày 1 đến 2 |
| 4 | Sửa bảng quyết định | AC-03 | Sửa các dòng DMN xếp loại, quy tắc mức cuối, quy tắc người đứng đầu, mẫu câu giải thích | SCR-1502 | `policy.decision_table` | Ngày 2 |
| 5 | Cấu hình luồng duyệt | AC-03 | Chọn mẫu luồng, điền tham số, xem trước luồng, chạy linter và test của mẫu | SCR-1504 | `flowable.wf_binding` (nháp) | Ngày 2 |
| 6 | Bổ sung test case | AC-03 | Thêm ca kiểm thử cho quy tắc mới; ca mới cần người phụ trách nghiệp vụ ký duyệt mới vào bộ vàng | SCR-1503 | `policy.golden_test_case` | Ngày 2 đến 3 |
| 7 | Chạy test case vàng | AC-03, SVC-07 | Chạy toàn bộ bộ test trên cel-java và cel-js, kiểm thử thuộc tính; hiển thị ca lỗi và khác biệt | SCR-1503 | `policy.test_run`; `DANG_KIEM_THU` rồi `DAT_KIEM_THU` hoặc `NHAP` | Vài giây đến vài phút |
| 8 | Gửi phê duyệt | AC-03 | Chọn kỳ hiệu lực, phạm vi áp dụng (khối, tenant); gửi | SCR-1501, SCR-1503 | `CHO_PHE_DUYET`; EVT-21 | Ngày 3 |
| 9 | Phê duyệt, ký số | AC-08 | Xem báo cáo khác biệt, kết quả test; ký số hoặc từ chối có ý kiến | SCR-1503 (chế độ phê duyệt) | EVT-18 hoặc về `NHAP` | Ngày 3 đến 4 |
| 10 | Saga phát hành | SVC-06 | Gắn `policy_binding` với kỳ hiệu lực; đồng bộ xuống cell; nạp gói vào scoring-service; kích hoạt `wf_binding`; bù trừ nếu một bước lỗi | Không có | `DA_PHAT_HANH`; EVT-13 | Ngày 4 đến 5 |
| 11 | Ngừng phiên bản cũ | Hệ thống | Phiên bản cũ chuyển `NGUNG` với kỳ mới nhưng vẫn được dùng cho kỳ đã khoá và để tính lại khi hậu kiểm | Không có | `NGUNG` | Khi phiên bản mới có hiệu lực |

Thời hạn trong bảng là ví dụ phân bổ để đạt chỉ tiêu NFR-07 "phát hành Policy Pack mới ≤ 5 ngày" cho một thay đổi cỡ vừa (ví dụ đổi cách đếm ngày chậm). Thay đổi lớn như chuyển từ QĐ 366 sang dự thảo Quy định mới (v1.4 lên v2.0) cần nhiều thời gian soạn test hơn nhưng vẫn không cần phát hành lại phần mềm.

Bước 7 chạy hai engine cho cùng một bộ test. cel-java trên máy chủ là nguồn sự thật để tính điểm; cel-js trên trình duyệt dùng cho kiểm tra tức thời trên biểu mẫu và xem trước. Nếu hai engine cho kết quả khác nhau ở bất kỳ ca nào, phiên bản không được đạt kiểm thử, vì khác biệt đó có nghĩa người dùng sẽ thấy một kết quả trên màn hình và nhận một kết quả khác trong phiếu.

### Trạng thái đối tượng

![Hình B3.2. Sơ đồ trạng thái phiên bản Policy Pack policy.policy_pack_version](nv-b-qt15-trangthai.png){width=13cm}

Sơ đồ đúng baseline mục 9.1. Mọi thay đổi nội dung sau `DAT_KIEM_THU` đưa phiên bản trở lại `NHAP`, bảo đảm không thể phê duyệt một nội dung chưa được kiểm thử.

| Trạng thái hiện tại | Sự kiện hoặc hành động | Trạng thái kế tiếp | Điều kiện kiểm soát |
|---|---|---|---|
| (chưa có) | Nhân bản hoặc tạo mới | `NHAP` | `policy_author` của gói |
| `NHAP` | Chạy test case vàng | `DANG_KIEM_THU` | Biểu thức hợp lệ kiểu |
| `DANG_KIEM_THU` | Có ca không đạt hoặc hai engine khác nhau | `NHAP` | Lưu `test_run` |
| `DANG_KIEM_THU` | 100% ca đạt, hai engine khớp | `DAT_KIEM_THU` | Lưu `test_run` |
| `DAT_KIEM_THU` | Sửa nội dung | `NHAP` | Mọi sửa đổi |
| `DAT_KIEM_THU` | Gửi phê duyệt | `CHO_PHE_DUYET` | Có kỳ hiệu lực trong tương lai |
| `CHO_PHE_DUYET` | Từ chối | `NHAP` | Có ý kiến |
| `CHO_PHE_DUYET` | Ký số phê duyệt, saga phát hành xong | `DA_PHAT_HANH` | Người duyệt khác người soạn; EVT-13 |
| `DA_PHAT_HANH` | Phiên bản mới thay thế hoặc thu hồi | `NGUNG` | Vẫn dùng cho kỳ đã gắn |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-15-01 | Công thức viết bằng khối ghép sinh ra biểu thức CEL; biểu thức được kiểm tra kiểu khi lưu và không được truy cập dữ liệu ngoài ngữ cảnh khai báo | ADR-19 | cel-java type-check với khai báo biến của ngữ cảnh tính điểm |
| BR-15-02 | Cách tính chất lượng B là tham số: theo 5 mức của PL 3 (mặc định) hoặc trừ 25% mỗi lần chỉnh sửa theo Bảng chấm điểm | PL 3 mục 2; PL A4 | Danh sách chọn; mỗi giá trị có ca test riêng |
| BR-15-03 | Loại ngày tính chậm là tham số: ngày làm việc theo lịch tenant (mặc định) hoặc ngày lịch | PL 3; PL A4 | Danh sách chọn; hàm `ngay_lam_viec` đọc `config.holiday_calendar` |
| BR-15-04 | Ngưỡng và cách đếm "30% nhiệm vụ vượt mục tiêu" là tham số (tối thiểu hay đúng; đếm theo số nhiệm vụ hay theo điểm) | QĐ Điều 11; PL A4 | Tham số trong DMN |
| BR-15-05 | Tổng điểm tối đa các nhóm bằng 100 với luồng Đảng; 30 và 70 với luồng Nhà nước | PL A3, A5 | Kiểm tra tức thời; chặn lưu |
| BR-15-06 | Bảng xếp loại dùng DMN 1.4 hit policy FIRST; mỗi dòng có điều khoản căn cứ và mẫu câu giải thích | ADR-19; PL A3 | Trình soạn DMN; trường điều khoản và mẫu câu bắt buộc |
| BR-15-07 | Phiên bản chỉ được gửi phê duyệt khi 100% ca test vàng đạt và kết quả cel-java trùng cel-js | ADR-19; ADR-05 | Trạng thái `DAT_KIEM_THU` bắt buộc; nút gửi khoá |
| BR-15-08 | Ca test vàng mới phải được người phụ trách nghiệp vụ ký duyệt trước khi vào bộ vàng; BA không tự sửa kỳ vọng của ca đã ký | Kiểm soát chất lượng | `golden_test_case.approved_by`, chữ ký |
| BR-15-09 | Người phê duyệt phát hành khác người soạn | PL 4 V.1 | OPA |
| BR-15-10 | Phiên bản phát hành phải gắn kỳ hiệu lực; không gắn cho kỳ đã khoá; kỳ đã khoá giữ phiên bản cũ | Tiêu chí I6 | `policy.policy_binding` kiểm tra trạng thái kỳ |
| BR-15-11 | Luồng duyệt chỉ được cấu hình bằng chọn mẫu và tham số; không có trình vẽ BPMN tự do theo tenant | ADR-04; góp ý G2 | Config Studio chỉ ghi `flowable.wf_binding`; định nghĩa BPMN chỉ triển khai qua CI của đội sản phẩm |
| BR-15-12 | Mẫu luồng tuân thủ linter: tối đa 1 tầng call activity, không gọi tiến trình chéo tenant, không có service task gọi đồng bộ ra ngoài, mỗi mẫu có bộ test | ADR-04 | Linter chạy trong CI và khi lưu tham số; hiển thị ở SCR-1504 |
| BR-15-13 | Policy Pack của tenant kế thừa gói gốc và chỉ được sửa phần gói gốc đánh dấu "cho phép tenant điều chỉnh" | Đa khách thuê | Cờ `overridable` trên từng quy tắc |
| BR-15-14 | Mọi phiên bản phát hành được ký số và ghi sổ cái | Tiêu chí I4, I2 | esign-service; outbox |

### Ngoại lệ và xử lý

Nếu saga phát hành lỗi ở một cell (ví dụ cell mất kết nối với vùng điều phối toàn cục), saga thực hiện bù trừ: gỡ `policy_binding` ở các cell đã nạp, giữ phiên bản ở `CHO_PHE_DUYET` và báo lỗi. Phiên bản không bao giờ ở trạng thái "đã phát hành một nửa". Khi cell trở lại, BA bấm phát hành lại mà không cần ký lại nếu nội dung không đổi.

Nếu sau khi phát hành phát hiện sai sót trước kỳ hiệu lực, người phê duyệt có thể thu hồi (`NGUNG`) và phiên bản trước tiếp tục áp dụng. Nếu kỳ hiệu lực đã bắt đầu nhưng chưa khoá, phải phát hành phiên bản sửa và scoring-service tính lại các hồ sơ của kỳ đang mở; nếu kỳ đã khoá, việc sửa kết quả phải đi qua QT-13.

Nếu một ca test vàng có kỳ vọng mâu thuẫn với văn bản (như ví dụ CV1 = 15 hay 30 điểm trong PL A4), ca được giữ trong bộ test với ghi chú sai lệch và kỳ vọng theo cách hiểu đã chọn; khi có hướng dẫn chính thức, ca được cập nhật qua ký duyệt mới.

### Đặc tả màn hình

#### SCR-1501. Config Studio – công thức

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Soạn công thức KPI và tổng hợp điểm bằng khối ghép, đặt tham số, xem trước với hồ sơ mẫu |
| Tác nhân | AC-03 (`policy_author`) |
| Đường vào | Menu Config Studio › Công thức |
| Quyền | `policy_author` của gói; tenant chỉ sửa phần được phép |
| Thiết bị | Web desktop (màn hình lõi viết mã, không cấu hình SDUI) |

![Hình B3.3. SCR-1501 Config Studio, công thức KPI dạng khối ghép và tham số](SCR-1501.png){width=16cm}

Thanh trên cùng cho biết gói `khoi-dang/quy-dinh-moi` đang soạn phiên bản v2.1 ở trạng thái `NHAP`, trong khi v2.0 đã phát hành và đang áp dụng cho quý IV/2026; dòng kế thừa ghi "QĐ 366 v1.4 → dự thảo Quy định mới v2.x". Dải thẻ (công thức, bảng quyết định, biểu mẫu, luồng duyệt, danh mục, test case, lịch sử) là các phần của một Policy Pack.

Vùng 1 là công thức KPI dạng khối: KPI = (A · Số lượng + B · Chất lượng + C · Tiến độ) / 3, mỗi khối một màu; bên dưới là ba thẻ chi tiết với biểu thức CEL tương ứng `min(so_luong_duyet / ke_hoach, 1.0)`, `muc_chat_luong` và `max(0.0, 1.0 - 0.05 * ngay_cham_chu_quan)`, kèm ghi chú nguồn dữ liệu. Vùng 2 là thẻ tham số: cách tính chất lượng (theo 5 mức của PL 3, có nhắc phương án khác ở PL A4), trừ tiến độ 0,05 mỗi ngày làm việc theo lịch tenant, cách xử lý lý do trả lại, quy tắc làm tròn. Vùng 3 là thẻ tổng hợp điểm với biểu thức tổng và bảng điểm tối đa nhóm 30 và 70, có kiểm tra tổng bằng 100. Vùng 4 là thẻ xem trước với hồ sơ mẫu TC-017: nhiệm vụ then chốt 2 cho KPI 0,783, nhóm then chốt 63,6, nhóm thường xuyên 27,6, điểm 91,2. Vùng 5 là nút "Gửi phê duyệt phát hành", chỉ bật khi phiên bản ở `DAT_KIEM_THU`.

Công thức trong Policy Pack được lưu dưới dạng CEL. Đoạn dưới minh hoạ cách các khối ở vùng 1 và vùng 3 được biểu diễn; biến `nv` là một nhiệm vụ trong ngữ cảnh tính điểm, `tham_so` là tham số của gói; các hàm `math.least`, `math.greatest` thuộc thư viện mở rộng math của CEL.

```yaml
# policy_rule: khoi-dang/quy-dinh-moi v2.1, nhóm cong_thuc
kpi_nhiem_vu: >
  (math.least(double(nv.so_luong_duyet) / double(nv.ke_hoach), 1.0)
   + nv.muc_chat_luong
   + math.greatest(0.0, 1.0 - tham_so.tru_tien_do * double(nv.ngay_cham_chu_quan))) / 3.0
# sum() là hàm mở rộng được đăng ký trong môi trường CEL của scoring-service
diem_ket_qua: >
  sum(nhom.map(n, n.kpi_nhom * n.diem_toi_da))
tham_so:
  tru_tien_do: 0.05
  loai_ngay: NGAY_LAM_VIEC
  cach_tinh_chat_luong: NAM_MUC_PL3
  lam_tron: { chu_so: 1, kieu: NUA_LEN }
```

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Gói, phiên bản | Nhãn | Không áp dụng | Mã gói, vX.Y, trạng thái | Từ dữ liệu | Chỉ đọc | `policy.policy_pack`, `policy.policy_pack_version` |
| 2 | Khối công thức KPI | Trình soạn khối | Có | Khối và toán tử | Kế thừa | Kiểm tra kiểu CEL khi lưu | `policy.policy_rule.expression` (rule_key = kpi_nhiem_vu) |
| 3 | Biểu thức A, B, C | Mã CEL (chỉ đọc khi ở chế độ khối) | Có | CEL | Kế thừa | Kiểu trả về double trong [0, 1] | `policy.policy_rule.expression` |
| 4 | Cách tính chất lượng | Danh sách chọn | Có | NAM_MUC_PL3, TRU_25_MOI_LAN | NAM_MUC_PL3 | Không có | `policy.policy_rule.params_json` |
| 5 | Mức trừ tiến độ | Số | Có | 0,00 đến 0,20 | 0,05 | Hai chữ số thập phân | `policy.policy_rule.params_json` |
| 6 | Loại ngày | Danh sách chọn | Có | NGAY_LAM_VIEC, NGAY_LICH | NGAY_LAM_VIEC | Không có | `policy.policy_rule.params_json` |
| 7 | Lý do trả lại tính vào chất lượng | Danh sách chọn | Có | Ba lựa chọn | Chỉ lý do cốt lõi | Không có | `policy.policy_rule.params_json` |
| 8 | Làm tròn | Danh sách chọn | Có | Số chữ số, kiểu | 1, nửa lên | Không có | `policy.policy_rule.params_json` |
| 9 | Điểm tối đa nhóm | Số | Có | Số nguyên | 30, 70 | Tổng 100 (Đảng) | `policy.policy_rule.params_json` (rule_key = nhom) |
| 10 | Hồ sơ mẫu xem trước | Danh sách chọn | Không | Mã ca test | TC-017 | Chỉ ca trong bộ vàng | `policy.golden_test_case` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| So sánh v2.0 | Luôn | Hiển thị khác biệt quy tắc, tham số giữa hai phiên bản | `GET /api/v1/policies/packs/{id}/versions/{v}/diff?base=2.0` | Không có |
| Xem dạng CEL | Luôn | Chuyển trình soạn sang xem mã CEL | Không có (phía client) | Không có |
| Lưu nháp | Phiên bản `NHAP` hoặc `DAT_KIEM_THU` | Kiểm tra kiểu, lưu; nếu đang `DAT_KIEM_THU` thì về `NHAP` | `PUT /api/v1/policies/packs/{id}/versions/{v}/rules/{key}` | Không có |
| Xem trước | Luôn | Tính thử hồ sơ mẫu bằng cel-js và cel-java | `POST /api/v1/policies/packs/{id}/versions/{v}/evaluate` | Không có |
| Chạy test case | Luôn | Chuyển sang SCR-1503 và bắt đầu chạy | `POST /api/v1/policies/packs/{id}/versions/{v}/test-runs` (202) | Không có |
| Gửi phê duyệt phát hành | `DAT_KIEM_THU` | Chọn kỳ hiệu lực, phạm vi; khởi động mẫu duyệt phát hành | `POST /api/v1/policies/packs/{id}/versions/{v}/submit` | EVT-21 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-15-01 | Biểu thức không hợp lệ: {loi_cel} tại khối {khoi}. | Lỗi kiểm tra | Kiểm tra kiểu thất bại |
| MSG-15-02 | Tổng điểm tối đa các nhóm là {tong}, phải bằng 100. | Lỗi kiểm tra | Sai tổng |
| MSG-15-03 | Phiên bản đã thay đổi sau lần kiểm thử gần nhất nên trở về trạng thái nháp. Vui lòng chạy lại test case. | Cảnh báo | Sửa khi `DAT_KIEM_THU` |

#### SCR-1502. Bảng quyết định xếp loại

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Soạn bảng DMN xếp loại cá nhân, quy tắc mức cuối, quy tắc người đứng đầu và mẫu câu giải thích |
| Tác nhân | AC-03 (`policy_author`) |
| Đường vào | Config Studio › thẻ Bảng quyết định |
| Quyền | Như SCR-1501 |
| Thiết bị | Web desktop |

![Hình B3.4. SCR-1502 Bảng quyết định xếp loại DMN 1.4 với hit policy FIRST](SCR-1502.png){width=16cm}

Bảng quyết định `xep_loai_ca_nhan` có sáu dòng, đầu vào là điểm, tỷ lệ mục tiêu O, tỷ lệ việc vượt, giải pháp đột phá, mức tiêu chí chung, cờ loại trừ; đầu ra là mức, điều khoản và lời giải thích. Hit policy FIRST nghĩa là dòng đầu tiên thoả mãn được chọn, nên các dòng KHTNV đứng trước, dòng HTXSNV đứng trước dòng HTTNV, và dòng HTTNV chính là "mức liền kề" khi thiếu điều kiện xuất sắc.

Vùng 1 là thanh gói và dải thẻ. Vùng 2 là bảng DMN; dòng 5 (≥ 80 điểm, tiêu chí chung từ "Đảm bảo", không loại trừ, kết quả HTTNV, căn cứ "QĐ Điều 11 · PL 6 §12", giải thích "Thiếu điều kiện mức trên, chuyển mức liền kề") đang được chọn vì đây là dòng áp cho hồ sơ mẫu. Vùng 3 là quy tắc "mức cuối cùng" `min_muc(muc_ket_qua_nhiem_vu, muc_tu_tieu_chi_chung)` và bảng quy đổi tiêu chí chung. Vùng 4 là quy tắc người đứng đầu theo khoản 14.3.3 viết bằng CEL. Vùng 5 là mẫu câu giải thích có các biến `muc_tren`, `diem`, `nguong`, `dieu_kien_thieu`, `gia_tri`, `dieu_khoan`; phiên bản Policy Pack được chèn tự động.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Hit policy | Danh sách chọn | Có | FIRST, UNIQUE, PRIORITY | FIRST | Chỉ các policy được hỗ trợ | `policy.decision_table.hit_policy` |
| 2 | Cột đầu vào | Biểu thức FEEL đơn giản | Có | So sánh, khoảng, giá trị | Trống (ô để trống nghĩa là bất kỳ) | Kiểu khớp khai báo | `policy.decision_table.rules_json` |
| 3 | Cột kết quả mức | Danh sách chọn | Có | 4 mức | Trống | Không có | `policy.decision_table.rules_json` |
| 4 | Cột điều khoản | Văn bản | Có | ≤ 100 ký tự | Trống | Bắt buộc mỗi dòng | `policy.decision_table.rules_json` |
| 5 | Cột giải thích | Văn bản | Có | ≤ 200 ký tự | Trống | Bắt buộc mỗi dòng | `policy.decision_table.rules_json` |
| 6 | Quy tắc mức cuối | Mã CEL | Có | CEL | Kế thừa | Kiểm tra kiểu | `policy.policy_rule` (rule_key = muc_cuoi) |
| 7 | Quy tắc người đứng đầu | Mã CEL | Có | CEL | Kế thừa | Kiểm tra kiểu | `policy.policy_rule` (rule_key = nguoi_dung_dau) |
| 8 | Mẫu câu giải thích | Văn bản có biến | Có | ≤ 1.000 ký tự | Kế thừa | Mọi biến phải tồn tại trong ngữ cảnh | `policy.decision_table.explanation_templates_json` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Thêm, xoá, đổi thứ tự dòng | Phiên bản `NHAP` | Sửa bảng trên client, kiểm tra trùng và chồng lấn | Không có (lưu khi bấm lưu) | Không có |
| Xuất DMN XML | Luôn | Xuất bảng theo chuẩn DMN 1.4 | `GET /api/v1/policies/packs/{id}/versions/{v}/decision-tables/{key}/dmn` | Không có |
| Lưu nháp | Phiên bản `NHAP` | Kiểm tra cú pháp FEEL, biến của mẫu câu | `PUT /api/v1/policies/packs/{id}/versions/{v}/decision-tables/{key}` | Không có |
| Chạy test case | Luôn | Như SCR-1501 | `POST /api/v1/policies/packs/{id}/versions/{v}/test-runs` (202) | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-15-04 | Dòng {i} không bao giờ được chọn vì dòng {j} phía trên luôn thoả mãn trước (hit policy FIRST). | Cảnh báo | Phát hiện dòng bị che |
| MSG-15-05 | Mẫu câu giải thích dùng biến {bien} không có trong ngữ cảnh xếp loại. | Lỗi kiểm tra | Biến không tồn tại |

#### SCR-1503. Chạy test case vàng

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Chạy bộ test case vàng trên hai engine, xem kết quả từng ca, lịch sử chạy, chi tiết lỗi; là điều kiện để gửi phê duyệt |
| Tác nhân | AC-03; AC-08 xem khi phê duyệt |
| Đường vào | Config Studio › thẻ Test case; nút "Chạy test case" ở SCR-1501, SCR-1502 |
| Quyền | `policy_author`; người phê duyệt chỉ đọc |
| Thiết bị | Web desktop |

![Hình B3.5. SCR-1503 Kết quả chạy test case vàng lần 14 đạt 148/148](SCR-1503.png){width=16cm}

Dòng phụ cho biết bộ test `bo-ca-mau-ban-to-chuc-v3` có 148 ca, được Ban Tổ chức TW ký duyệt ngày 02/10/2026, chạy trên cel-java và cel-js. Bốn thẻ số liệu là phần quan trọng nhất của màn hình vì chúng là điều kiện phát hành.

Vùng 1 là thanh gói và dải thẻ. Vùng 2 là bốn thẻ: lần chạy #14 của v2.1 đạt 148/148 trong 6,8 giây; hai engine khớp 148/148; kiểm thử thuộc tính 10.000 bộ dữ liệu ngẫu nhiên với điều kiện điểm luôn trong [0; 100] và C ≥ 0; ghi chú "Tính lại kỳ đã khoá: Không áp dụng, v2.1 chỉ hiệu lực từ Q I/2027". Bên dưới là bảng kết quả từng ca, minh hoạ 8 ca tiêu biểu như TC-017 "Hạ mức do thiếu 30% việc vượt" (HTTNV, 91,2), TC-023 "Chậm 21 ngày làm việc nên C = 0, không âm", TC-058 "Người đứng đầu: 52% đơn vị KHTNV", TC-077 "Ngày lễ không tính ngày chậm", TC-131 "Số liệu ví dụ PL 1 Bảng 3". Vùng 3 là lịch sử chạy: #14 đạt 148/148, #13 đạt 147/148, #9 của v2.0 đạt 148/148. Vùng 4 là thẻ "Lỗi ở lần #13 (đã sửa)": TC-077 kỳ vọng C = 0,90 nhưng thực tế 0,85 do tham số loại ngày bị đổi sang ngày lịch ở nháp 6, kèm đoạn khác biệt cấu hình. Vùng 5 là nút "Gửi phê duyệt phát hành", chỉ bật khi 100% ca đạt.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Bộ test | Nhãn | Không áp dụng | Mã bộ, số ca, người ký, ngày | Bộ gắn với gói | Chỉ đọc | `policy.golden_test_case` (nhóm theo bộ) |
| 2 | Kết quả lần chạy | Thẻ số | Không áp dụng | Đạt / tổng, thời gian | Lần mới nhất | Chỉ đọc | `policy.test_run.passed`, `policy.test_run.total`, `policy.test_run.duration_ms` |
| 3 | Khớp hai engine | Thẻ số | Không áp dụng | Khớp / tổng | Lần mới nhất | Phải bằng tổng | `policy.test_run.engine_match` |
| 4 | Kiểm thử thuộc tính | Thẻ số | Không áp dụng | Số bộ dữ liệu, bất biến | 10.000 | Không vi phạm bất biến | `policy.test_run.property_result_json` |
| 5 | Bảng ca | Bảng | Không áp dụng | Mã, mô tả, kỳ vọng, thực tế, kết quả | Lọc tất cả | Chỉ đọc | `policy.test_run.cases_json` |
| 6 | Lịch sử chạy | Danh sách | Không áp dụng | Số lần, kết quả, thời điểm, bản nháp | 10 lần gần nhất | Chỉ đọc | `policy.test_run` |
| 7 | Chi tiết lỗi | Thẻ | Không áp dụng | Ca, kỳ vọng, thực tế, khác biệt cấu hình | Lần lỗi được chọn | Chỉ đọc | `policy.test_run.cases_json`, khác biệt phiên bản |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Thêm ca kiểm thử | `policy_author` | Tạo ca mới ở trạng thái chờ ký duyệt | `POST /api/v1/policies/test-cases` | Không có |
| Chạy lại | Luôn | Chạy toàn bộ bộ vàng trên hai engine và kiểm thử thuộc tính | `POST /api/v1/policies/packs/{id}/versions/{v}/test-runs` (202) | Không có |
| Lọc ca | Luôn | Lọc đạt, không đạt | Phía client | Không có |
| Gửi phê duyệt phát hành | `DAT_KIEM_THU` | Như SCR-1501 | `POST /api/v1/policies/packs/{id}/versions/{v}/submit` | EVT-21 |
| Ký số phê duyệt | AC-08, `CHO_PHE_DUYET` | Ký số, khởi động saga phát hành | `POST /api/v1/policies/packs/{id}/versions/{v}/approve` (202) | EVT-18, EVT-13 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-15-06 | Lần chạy #{n}: {dat}/{tong} ca đạt. Phiên bản chưa đạt kiểm thử nên chưa thể gửi phê duyệt. | Cảnh báo | Có ca không đạt |
| MSG-15-07 | Ca {ma} cho kết quả khác nhau giữa cel-java ({gt_java}) và cel-js ({gt_js}). | Lỗi | Hai engine khác nhau |
| MSG-15-08 | Phiên bản {v} đã được phát hành, áp dụng từ kỳ {ky}. Các kỳ đã khoá tiếp tục dùng phiên bản cũ. | Thành công | Nhận EVT-13 |

#### SCR-1504. Cấu hình luồng duyệt (mẫu + tham số)

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Chọn mẫu luồng duyệt đã kiểm thử và điền tham số cho khối hoặc tenant, xem trước luồng sinh từ mẫu, chạy linter và test của mẫu |
| Tác nhân | AC-03 (`policy_author`) |
| Đường vào | Config Studio › thẻ Luồng duyệt |
| Quyền | `policy_author`; tham số phát hành cùng Policy Pack |
| Thiết bị | Web desktop |

![Hình B3.6. SCR-1504 Cấu hình luồng duyệt bằng chọn mẫu và tham số](SCR-1504.png){width=16cm}

Dòng phụ đầu trang nêu nguyên tắc "Không vẽ BPMN tự do theo tenant, chọn mẫu đã kiểm thử, điền tham số, lưu vào flowable.wf_binding". Màn hình là bằng chứng giao diện cho cách trả lời góp ý G2.

Vùng 1 là danh sách mẫu luồng: `duyet-ho-so-danh-gia` v3 với 42 test (đang chọn), `duyet-ke-hoach-quy` v2, `duyet-kep-chi-huy-cap-uy` v1 (lực lượng vũ trang, chỉ huy song song chính uỷ), `xu-ly-khieu-nai` v2, `mo-lai-ky-hai-nguoi` v1; ghi chú cho biết toàn hệ thống có 27 mẫu nhân phiên bản và số định nghĩa BPMN không tăng theo số tenant. Vùng 2 là thẻ tham số áp cho khối Đảng, tenant Đảng uỷ các cơ quan Đảng TW, từ quý I/2027: 4 cấp duyệt, cấp 2 thẩm định bởi vai trò `appraiser` của Ban Tổ chức, cấp 4 quyết định bởi Ban Thường vụ, thời hạn mỗi bước 3 ngày làm việc và nhắc trước 1 ngày, quá hạn thì chuyển cấp phó được uỷ quyền, ký số ở bước tự đánh giá, thẩm định, quyết định. Vùng 3 là điều kiện bỏ qua cấp 3 viết bằng CEL: người được đánh giá là chuyên viên và mức thẩm định bằng mức tự đề xuất. Vùng 4 là xem trước luồng chỉ đọc sinh từ mẫu (tự đánh giá, thẩm định, đề xuất có thể bỏ qua, quyết định ký lô, khoá hồ sơ) và bốn nhãn linter: đạt; tối đa 1 tầng call activity; không có service task gọi đồng bộ ra ngoài; 42/42 test mẫu đạt với tham số này.

Tham số được lưu thành một bản ghi `flowable.wf_binding`. Khi một hồ sơ cần khởi động luồng, workflow-service tra `wf_binding` theo (tenant, khối, loại quy trình, kỳ) để biết mẫu và tham số, rồi khởi động tiến trình của mẫu với các biến tương ứng. Một ví dụ bản ghi:

```json
{
  "template_key": "duyet-ho-so-danh-gia",
  "template_version": 3,
  "scope": { "tenant": "dang-uy-cac-co-quan-dang-tw", "khoi": "DANG", "from_period": "2027-Q1" },
  "params": {
    "so_cap": 4,
    "cap": [
      { "buoc": "TU_DANH_GIA", "vai_tro": "member", "ky_so": true },
      { "buoc": "THAM_DINH", "vai_tro": "appraiser", "don_vi": "BAN_TO_CHUC", "ky_so": true },
      { "buoc": "DE_XUAT", "vai_tro": "manager", "bo_qua_khi": "nguoi_duoc_danh_gia.chuc_danh in ['CHUYEN_VIEN'] && muc_tham_dinh == muc_tu_de_xuat" },
      { "buoc": "QUYET_DINH", "vai_tro": "decider", "ky_lo": true, "ky_so": true }
    ],
    "thoi_han_moi_buoc": "P3D_LV",
    "nhac_truoc": "P1D_LV",
    "khi_qua_han": "CHUYEN_CAP_PHO_UY_QUYEN"
  }
}
```

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Mẫu luồng | Danh sách thẻ | Có | Khoá mẫu, phiên bản | Mẫu đang dùng | Chỉ mẫu đã phát hành, phù hợp loại quy trình | `flowable.wf_template` |
| 2 | Phạm vi áp dụng | Nhãn và danh sách chọn | Có | Khối, tenant, kỳ bắt đầu | Từ gói | Kỳ bắt đầu chưa khoá | `flowable.wf_binding.scope_json` |
| 3 | Số cấp duyệt | Danh sách chọn | Có | Trong khoảng mẫu cho phép (2 đến 4) | 4 | Theo khai báo mẫu | `flowable.wf_binding.params_json` |
| 4 | Vai trò, đơn vị từng cấp | Danh sách chọn | Có | Vai trò hệ thống, đơn vị | Theo mẫu | Vai trò tồn tại trong `config.role_binding` | `flowable.wf_binding.params_json` |
| 5 | Thời hạn, nhắc | Thời lượng | Có | Số ngày làm việc | 3 và 1 | 1 đến 15 | `flowable.wf_binding.params_json` |
| 6 | Hành vi khi quá hạn | Danh sách chọn | Có | Nhắc lại, chuyển cấp phó, chuyển cấp trên | Chuyển cấp phó | Theo khai báo mẫu | `flowable.wf_binding.params_json` |
| 7 | Bước ký số | Chọn nhiều | Có | Các bước của mẫu | Ba mốc pháp lý | Không bỏ được bước ký bắt buộc của mẫu | `flowable.wf_binding.params_json` |
| 8 | Điều kiện bỏ qua cấp | Mã CEL | Không | CEL trả về bool | Trống | Kiểm tra kiểu; chỉ áp cho cấp mẫu cho phép bỏ qua | `flowable.wf_binding.params_json` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Chọn mẫu | Luôn | Tải khai báo tham số của mẫu | `GET /api/v1/policies/workflow-templates/{key}/versions/{v}` | Không có |
| Mô phỏng luồng | Đủ tham số | Chạy mẫu với tham số trên engine thử, với một số hồ sơ mẫu | `POST /api/v1/policies/workflow-bindings/simulate` | Không có |
| Lưu tham số | Đủ tham số, linter đạt | Lưu `wf_binding` nháp thuộc phiên bản Policy Pack đang soạn; chạy test của mẫu | `PUT /api/v1/policies/packs/{id}/versions/{v}/workflow-bindings/{key}` | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-15-09 | Tham số chưa hợp lệ với mẫu {mau}: {loi}. | Lỗi kiểm tra | Linter hoặc test mẫu không đạt |
| MSG-15-10 | Luồng duyệt chỉ được cấu hình bằng mẫu và tham số. Nếu cần quy trình mới, vui lòng gửi yêu cầu bổ sung mẫu cho đội sản phẩm. | Thông tin | Người dùng tìm chức năng vẽ luồng |

### Liên kết thiết kế

QT-15 do MOD-05 `policy` quản lý (bảng `policy_pack`, `policy_pack_version`, `policy_rule`, `decision_table`, `golden_test_case`, `test_run`, `policy_binding`), dùng SVC-07 scoring-service (cel-java, Flowable DMN), SVC-06 workflow-service (`flowable.wf_template`, `flowable.wf_binding`, saga phát hành), SVC-10 esign-service, SVC-17 web-frontend với `@vt/form-engine` (cel-js) và vùng điều phối toàn cục (kho Policy Pack gốc). Sự kiện: EVT-13 PolicyPackDaPhatHanh, EVT-18, EVT-21. Liên quan ADR-19 (bộ máy quy tắc), ADR-04 (G2, mẫu và tham số), NFR-07 (≤ 5 ngày). Đáp ứng tiêu chí H8 (Config Studio, Policy Pack có phiên bản và test case), H4 (một bộ máy tính duy nhất cấu hình được), H9 (hai luồng), I6 (phiên bản quy tắc gắn kết quả).

## QT-16. Bảng điều hành và báo cáo

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-16 |
| Tên | Bảng điều hành và báo cáo |
| Mục đích | Cung cấp cho lãnh đạo bảng điều hành theo phạm vi quản lý (4 con số chính, so sánh đơn vị, tóm tắt tuần do AI tạo, việc cần chỉ đạo), cho phép tự dựng dashboard bằng kéo thả chỉ số chuẩn hoá và đi xuống theo chuỗi truy vết tới minh chứng và căn cứ |
| Phạm vi áp dụng | Mọi lãnh đạo, quản lý trong phạm vi cây tổ chức của mình; báo cáo định kỳ; so sánh nhiều kỳ |
| Căn cứ | PL 5 (phân tích, phân nhóm); PL 4 III.2 (truy vết hai chiều); QĐ khoản 10.2.2 (05 chỉ tiêu KT-XH, chỉ tính chỉ tiêu đã công bố số liệu); báo cáo BGĐ slide 18, 24 và PL A2 nhóm 7; ADR-14 |
| Tác nhân | AC-04 lãnh đạo (`leader`); AC-05 quản lý; SVC-15 analytics; SVC-14 ai-gateway; MOD-02 (nhắc, giao lại) |
| Tần suất và khối lượng ước tính | Hằng ngày. Giả định khoảng 5% người dùng là lãnh đạo, quản lý (khoảng 300.000 người), mỗi người mở bảng điều hành 2 lần mỗi ngày; tải truy vấn đỉnh ước tính khoảng 300 đến 600 truy vấn mỗi giây toàn quốc vào kho phân tích, phần lớn trúng bộ đệm 5 phút của Cube (số ước tính) |
| Điều kiện bắt đầu | Người dùng có vai trò `leader` hoặc `manager` mở bảng điều hành |
| Điều kiện kết thúc | Người dùng đã xem, hoặc đã thực hiện hành động chỉ đạo (nhắc, giao lại), hoặc đã lưu dashboard tự dựng |
| Đầu vào | Dữ liệu CDC trong ClickHouse; lớp ngữ nghĩa Cube; tóm tắt tuần của ai-gateway; dữ liệu chi tiết trong mission-core khi đi xuống |
| Đầu ra | Màn hình, báo cáo xuất; dashboard tự dựng (`config.dashboard_def`); việc nhắc, giao lại trong MOD-02 |
| Chỉ số đo hiệu quả quy trình | Thời gian tải bảng điều hành p95 ≤ 1 giây (NFR-03); độ trễ dữ liệu ≤ 5 phút; tỷ lệ việc chậm được chỉ đạo trong 1 ngày; số dashboard tự dựng được dùng lại |

Bản đánh giá kiến trúc ghi nhận hệ thống hiện tại chỉ có 7 khối báo cáo cố định (H7 "Một phần") và các truy vấn báo cáo chạy thẳng trên CSDL nghiệp vụ. QT-16 tách hoàn toàn phía đọc theo mẫu CQRS (ADR-14): dữ liệu từ các schema nghiệp vụ được Debezium đẩy sang ClickHouse, lớp ngữ nghĩa Cube định nghĩa một lần các chỉ số chuẩn (tiến độ mục tiêu O, tỷ lệ nhiệm vụ đúng hạn, điểm KPI trung bình, phân bố mức…), và mọi bảng điều hành, dashboard tự dựng, báo cáo đều truy vấn qua Cube với ngữ cảnh bảo mật (securityContext) chứa tenant và đường dẫn cây tổ chức của người xem. Nhờ đó, truy vấn báo cáo không bao giờ chạm vào PostgreSQL của mission-core, và chỉ số "tiến độ mục tiêu O" có cùng một định nghĩa ở mọi màn hình.

Chuỗi đi xuống điểm → cấu phần → KPI/KR → sản phẩm → minh chứng → kế hoạch → mục tiêu O → căn cứ là yêu cầu truy vết hai chiều của PL 4 III.2. Ba mắt xích đầu lấy từ kho phân tích, các mắt xích sau lấy từ mission-core theo mã đối tượng, có kiểm tra quyền ở từng bước, vì minh chứng và nhận xét là dữ liệu C2 không đưa sang kho phân tích ở dạng rõ.

### Lưu đồ quy trình

![Hình B3.7. Lưu đồ làn bơi QT-16: bảng điều hành, đi xuống và dựng dashboard](nv-b-qt16-luong.png){width=16cm}

Lãnh đạo mở bảng điều hành; dữ liệu đến từ lớp ngữ nghĩa với phạm vi cây tổ chức của người xem, tóm tắt tuần đến từ ai-gateway ở chế độ chỉ đọc. Sau khi đọc bốn con số, so sánh đơn vị và tóm tắt, lãnh đạo chọn một trong ba hành động. "Đi xuống" mở chuỗi truy vết tới minh chứng, khi đó mission-core trả chi tiết theo mã có kiểm tra quyền. "Chỉ đạo" gửi nhắc hoặc giao lại nhiệm vụ chỉ bằng một lần bấm, MOD-02 phát EVT-04 hoặc EVT-21. "Tự dựng" mở trình dựng dashboard để kéo thả các chỉ số chuẩn của lớp ngữ nghĩa.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Mở bảng điều hành | AC-04 | Tải 4 con số, bảng so sánh đơn vị, xu hướng, tóm tắt tuần, việc cần chỉ đạo theo phạm vi | SCR-1601 | Không có | p95 ≤ 1 giây |
| 2 | So sánh, lọc | AC-04 | Chọn kỳ, so với kỳ trước, lọc theo khối, luồng | SCR-1601 | Không có | Tức thời |
| 3 | Đọc tóm tắt AI | AC-04 | Đọc 3 đến 5 ý nổi bật có nhãn "Nội dung do AI tạo" và nguồn; mở "Xem dữ liệu đã dùng" | SCR-1601 | Không có | Tóm tắt sinh mỗi sáng thứ Hai |
| 4 | Chỉ đạo | AC-04 | Bấm "Nhắc" hoặc "Giao lại" trên việc chậm; giao lại mở hộp thoại chọn người | SCR-1601 | EVT-21 hoặc EVT-04 | Tức thời |
| 5 | Đi xuống | AC-04 | Bấm đơn vị, rồi cán bộ, rồi điểm; đi theo chuỗi tới minh chứng, kế hoạch, mục tiêu O, căn cứ | SCR-1603 | Không có; truy cập C2 được ghi sổ cái | Mỗi bước p95 ≤ 300 ms |
| 6 | Dựng dashboard | AC-04, AC-05 | Kéo chỉ số và chiều vào lưới, chọn kiểu biểu đồ, bộ lọc, cho phép đi xuống | SCR-1602 | `config.dashboard_def` (nháp) | Không giới hạn |
| 7 | Lưu, chia sẻ | AC-04 | Lưu dashboard; chia sẻ cho nhóm người dùng; người được chia sẻ chỉ thấy dữ liệu trong phạm vi của chính họ | SCR-1602 | `config.dashboard_def` | Tức thời |
| 8 | Xuất báo cáo | AC-04 | Xuất báo cáo PDF hoặc XLSX theo mẫu | SCR-1601 | Tác vụ nền | ≤ 1 phút |

Bước 7 cho thấy cách phân quyền dữ liệu được giữ nguyên khi chia sẻ dashboard: dashboard chỉ là định nghĩa (chỉ số, chiều, bộ lọc, bố cục), không chứa dữ liệu. Khi người được chia sẻ mở, Cube sinh truy vấn với securityContext của chính họ. Bí thư chia sẻ "Bảng của Bí thư" cho Ban Thường vụ thì mỗi uỷ viên thấy số liệu đúng phạm vi mình phụ trách.

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-16-01 | Mọi số liệu trên bảng điều hành và dashboard được lọc theo phạm vi cây tổ chức của người xem | PL 4 V.1; ADR-06 | securityContext của Cube chứa `tenant` và `org_path`; Cube áp điều kiện ở mọi truy vấn |
| BR-16-02 | Dashboard tự dựng chỉ dùng chỉ số và chiều được công bố trong lớp ngữ nghĩa; không cho viết SQL tự do | ADR-14 | Trình dựng chỉ liệt kê measures, dimensions của phiên bản lớp ngữ nghĩa |
| BR-16-03 | Đi xuống phải lần được theo chuỗi điểm, cấu phần, KPI hoặc KR, sản phẩm, minh chứng, kế hoạch, mục tiêu O, căn cứ | PL 4 III.2 | Mỗi đối tượng lưu khoá liên kết ngược; API chuỗi truy vết |
| BR-16-04 | Dữ liệu C2 (minh chứng, nhận xét) không hiển thị từ kho phân tích; khi đi xuống phải lấy từ mission-core có kiểm tra quyền và ghi nhật ký truy cập | ADR-06 | Cube không có cột C2 rõ; API chi tiết của mission-core |
| BR-16-05 | Chỉ tiêu KT-XH chỉ tính khi số liệu đã được công bố chính thức | QĐ khoản 10.2.2 | Cờ `da_cong_bo` trên dữ liệu chỉ tiêu nhận từ integration-gateway |
| BR-16-06 | Tóm tắt do AI tạo phải gắn nhãn "Nội dung do AI tạo", nêu nguồn dữ liệu và thời điểm; AI không có quyền ghi | Nguyên tắc AI không quyết định | Xem QT-17 |
| BR-16-07 | Hành động chỉ đạo (nhắc, giao lại) thực hiện bằng API của MOD-02 với quyền của người bấm, không phải quyền của bảng điều hành | PL 4 V.1 | Gọi API nghiệp vụ, OPA kiểm tra |
| BR-16-08 | Dữ liệu kho phân tích có độ trễ tối đa 5 phút và hiển thị thời điểm cập nhật | NFR-03 | CDC liên tục; nhãn thời điểm trên màn hình |

### Ngoại lệ và xử lý

Khi kho phân tích chậm hơn ngưỡng (CDC trễ quá 5 phút), bảng điều hành vẫn hiển thị dữ liệu gần nhất kèm cảnh báo "Số liệu cập nhật lúc {thời điểm}". Không có cơ chế dự phòng truy vấn thẳng vào mission-core, vì như vậy sẽ chuyển tải phân tích sang đường ghi.

Khi người dùng đi xuống tới một đối tượng ngoài phạm vi quyền (ví dụ minh chứng của đơn vị khác được liên kết ngang), mắt xích đó hiển thị dạng che với thông báo không đủ quyền; chuỗi vẫn tiếp tục ở các mắt xích được phép.

Khi ai-gateway không sẵn sàng, ô tóm tắt tuần hiển thị "Chưa có tóm tắt tuần này"; các phần khác không bị ảnh hưởng.

### Đặc tả màn hình

#### SCR-1601. Bảng điều hành lãnh đạo

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho lãnh đạo nắm tình hình thực hiện nhiệm vụ của phạm vi quản lý trong một màn hình và chỉ đạo ngay |
| Tác nhân | AC-04 (`leader`); AC-05 xem phạm vi của mình |
| Đường vào | Trang chủ của vai trò lãnh đạo; menu Bảng điều hành |
| Quyền | Theo cây tổ chức |
| Thiết bị | Web desktop, máy tính bảng; trên di động hiển thị 4 con số và danh sách cần chỉ đạo |

![Hình B3.8. SCR-1601 Bảng điều hành Đảng uỷ khối quý IV/2026](SCR-1601.png){width=16cm}

Màn hình theo tinh thần slide 18 của báo cáo BGĐ. Dòng phụ ghi "Tuần 6/13, số liệu từ kho phân tích, trễ tối đa 5 phút, phạm vi theo cây tổ chức của người xem".

Vùng 1 là tiêu đề cùng ba nút "So với quý trước", "Xuất báo cáo" và "+ Giao việc". Vùng 2 là bốn con số: tiến độ mục tiêu O 72%, nhiệm vụ đúng hạn 86% (tăng 2% so với tuần trước), việc chậm cần chỉ đạo 14 (tăng 3), hồ sơ chờ thẩm định 9 (hạn 10/01); bên dưới là bảng tiến độ theo đơn vị cùng khối (Ban Tuyên giáo 92%, Ban Kiểm tra 88%, Đảng uỷ cơ sở C 71%, Đảng uỷ cơ sở D 68%, Văn phòng E 41%, Ban Tổ chức 85%) với nhãn trạng thái và đường xu hướng 6 tuần so với quý trước. Vùng 3 là thẻ "Tóm tắt tuần" có nhãn tím "Nội dung do AI tạo", ba ý nổi bật và dòng nguồn "kho phân tích ngày 14/11, ai-gateway, mô hình Viettel LLM" với liên kết "Xem dữ liệu đã dùng". Vùng 4 là thẻ "Cần chỉ đạo ngay" với ba việc, mỗi việc có nhãn trễ hoặc còn hạn và hai nút "Nhắc", "Giao lại".

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Kỳ | Danh sách chọn | Không | Quý, năm | Kỳ hiện hành | Không có | `config.period` |
| 2 | Tiến độ mục tiêu O | Số | Không áp dụng | Phần trăm | Tính | Chỉ đọc | Cube measure `okr.tien_do_o` (từ `okr.key_result`, `okr.kr_checkin`) |
| 3 | Nhiệm vụ đúng hạn | Số | Không áp dụng | Phần trăm và chênh lệch | Tính | Chỉ đọc | Cube measure `task.ty_le_dung_han` (từ `task.task`) |
| 4 | Việc chậm cần chỉ đạo | Số | Không áp dụng | Số nguyên | Tính | Chỉ đọc | Cube measure `task.so_viec_cham` |
| 5 | Hồ sơ chờ thẩm định | Số | Không áp dụng | Số nguyên, hạn | Tính | Chỉ đọc | Cube measure `appraisal.cho_tham_dinh` |
| 6 | Tiến độ theo đơn vị | Bảng, thanh | Không áp dụng | Đơn vị, phần trăm, trạng thái | Đơn vị cùng khối | Ngưỡng màu theo tham số tenant | Cube, chiều `org_unit` |
| 7 | Xu hướng | Biểu đồ đường | Không áp dụng | 6 tuần | Kỳ này và kỳ trước | Chỉ đọc | Cube, chiều thời gian tuần |
| 8 | Tóm tắt tuần | Danh sách văn bản | Không áp dụng | 3 đến 5 ý | Bản mới nhất | Nhãn AI và nguồn bắt buộc | `ai.suggestion_log` (kind = TOM_TAT_TUAN) |
| 9 | Việc cần chỉ đạo | Danh sách | Không áp dụng | Tên, loại, đơn vị, hạn | Sắp theo mức trễ | Chỉ việc trong phạm vi | Cube kết hợp mã `task.task` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Tải bảng | Mở màn hình | Gọi các truy vấn Cube song song | `POST /api/v1/analytics/query` | Không có |
| So với quý trước | Luôn | Thêm chiều so sánh kỳ | `POST /api/v1/analytics/query` | Không có |
| Bấm đơn vị | Luôn | Mở SCR-1603 với ngữ cảnh đơn vị | Không có (điều hướng) | Không có |
| Nhắc | Việc chậm trong phạm vi | Gửi nhắc tới người chủ trì | `POST /api/v1/tasks/{id}/reminders` | EVT-21 |
| Giao lại | Việc trong phạm vi, người bấm có quyền giao | Mở hộp thoại chọn người, ghi lý do | `POST /api/v1/tasks/{id}/reassign` | EVT-04 |
| Xem dữ liệu đã dùng | Có tóm tắt | Hiển thị các truy vấn và số liệu AI đã dùng | `GET /api/v1/ai/suggestions/{id}/sources` | Không có |
| Xuất báo cáo | Luôn | Sinh báo cáo theo mẫu | `POST /api/v1/analytics/reports` (202) | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-16-01 | Số liệu cập nhật lúc {thoi_diem}, chậm hơn thường lệ. | Cảnh báo | CDC trễ quá 5 phút |
| MSG-16-02 | Đã gửi nhắc tới đồng chí {ten} về việc "{viec}". | Thành công | Nhắc xong |
| MSG-16-03 | Đã giao lại việc "{viec}" cho {ten}. Người được giao cũ đã nhận thông báo. | Thành công | Giao lại xong |

#### SCR-1602. Trình dựng dashboard

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho lãnh đạo, quản lý tự dựng dashboard bằng kéo thả chỉ số và chiều đã chuẩn hoá trong lớp ngữ nghĩa, không cần đội kỹ thuật |
| Tác nhân | AC-04, AC-05 |
| Đường vào | Menu Bảng điều hành › Dashboard của tôi › Tạo mới |
| Quyền | Mọi `leader`, `manager`; dữ liệu theo phạm vi của người xem |
| Thiết bị | Web desktop |

![Hình B3.9. SCR-1602 Trình dựng dashboard kéo thả trên lớp ngữ nghĩa](SCR-1602.png){width=16cm}

Màn hình ba cột: danh mục chỉ số và chiều bên trái, lưới dựng ở giữa, thuộc tính widget bên phải. Tiêu đề "Bảng của Bí thư, bản nháp 3".

Vùng 1 là danh mục chỉ số (tiến độ mục tiêu O, tỷ lệ đúng hạn, điểm KPI trung bình, phân bố mức xếp loại, số minh chứng chờ nghiệm thu, số ngày chậm bình quân, mức tự tin KR, số cảnh báo bất thường) và chiều (đơn vị theo cây tổ chức, kỳ, khối đối tượng PL 2A, loại nhiệm vụ, luồng Đảng hoặc Nhà nước), kèm ghi chú chỉ hiện chỉ số của lớp ngữ nghĩa phiên bản 2026.4, không viết SQL tự do. Vùng 2 là lưới với hai thẻ số và biểu đồ "Điểm KPI trung bình theo quý" đang được chọn. Vùng 3 là ô "Thả chỉ số vào đây" và thẻ phân bố mức. Vùng 4 là thuộc tính widget: chỉ số, chiều, kiểu biểu đồ, bộ lọc (luồng Đảng, khối cơ quan tham mưu), cho phép đi xuống tới minh chứng, và ghi chú truy vấn sinh ra là Cube REST `/load` với securityContext gồm tenant và org_path, cache 5 phút.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Tên dashboard | Văn bản | Có | ≤ 100 ký tự | "Dashboard mới" | Không trùng trong không gian của người dùng | `config.dashboard_def.name` |
| 2 | Danh mục chỉ số, chiều | Danh sách kéo | Không áp dụng | Theo lớp ngữ nghĩa | Phiên bản hiện hành | Chỉ đọc | Cube meta API |
| 3 | Widget | Phần tử lưới | Có (ít nhất 1) | Vị trí, kích thước | Trống | Tối đa 20 widget | `config.dashboard_def.layout_json` |
| 4 | Chỉ số của widget | Danh sách chọn | Có | Measure | Trống | Có trong lớp ngữ nghĩa | `config.dashboard_def.layout_json` |
| 5 | Chiều | Danh sách chọn | Không | Dimension | Trống | Tối đa 2 chiều | `config.dashboard_def.layout_json` |
| 6 | Kiểu biểu đồ | Danh sách chọn | Có | Thẻ số, cột, đường, thanh, bảng | Theo chỉ số | Phù hợp kiểu dữ liệu | `config.dashboard_def.layout_json` |
| 7 | Bộ lọc | Biểu thức đơn giản | Không | Chiều = giá trị | Trống | Chỉ trên chiều công bố | `config.dashboard_def.layout_json` |
| 8 | Cho phép đi xuống | Danh sách chọn | Không | Có, không | Có | Chỉ chỉ số có liên kết truy vết | `config.dashboard_def.layout_json` |
| 9 | Chia sẻ | Chọn nhiều | Không | Nhóm người dùng | Không chia sẻ | Chỉ nhóm trong tenant | `config.dashboard_def.shared_with` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Kéo chỉ số vào lưới | Luôn | Tạo widget, gọi truy vấn xem trước | `POST /api/v1/analytics/query` | Không có |
| Xem trước | Luôn | Hiển thị dashboard ở chế độ người xem | Phía client | Không có |
| Lưu dashboard | Có ít nhất 1 widget | Lưu định nghĩa | `PUT /api/v1/analytics/dashboards/{id}` | Không có |
| Chia sẻ cho Ban Thường vụ | Đã lưu | Lưu danh sách chia sẻ | `POST /api/v1/analytics/dashboards/{id}/shares` | EVT-21 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-16-04 | Chỉ số {chi_so} không hỗ trợ chiều {chieu}. | Lỗi kiểm tra | Kết hợp không hợp lệ |
| MSG-16-05 | Người được chia sẻ chỉ thấy số liệu trong phạm vi quản lý của chính họ. | Thông tin | Khi chia sẻ |

#### SCR-1603. Đi xuống tới minh chứng

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Hiển thị chuỗi truy vết từ điểm tới minh chứng và ngược lên mục tiêu O, căn cứ của một kết quả cụ thể |
| Tác nhân | AC-04, AC-05, AC-07, AC-10 |
| Đường vào | Bấm đơn vị hoặc con số ở SCR-1601, SCR-1602; liên kết từ SCR-1401 |
| Quyền | Theo cây tổ chức; mỗi mắt xích kiểm tra quyền riêng |
| Thiết bị | Web desktop |

![Hình B3.10. SCR-1603 Đi xuống từ điểm 91,2 tới minh chứng phiên bản 3](SCR-1603.png){width=16cm}

Vùng 1 là tiêu đề và dòng mô tả chuỗi truy vết PL 4. Vùng 2 là dải tám thẻ nối nhau: điểm 91,2 của Trần Minh Hoàng quý IV; cấu phần 63,6 (then chốt × 70); KPI nhiệm vụ 0,783 (nhiệm vụ then chốt 2); sản phẩm 1/1 (báo cáo sơ kết); minh chứng v3 `DA_XAC_NHAN` (đang chọn); kế hoạch KH-Q4 v2 `DA_DUYET`; mục tiêu O1, KR 1.2 của Phòng Tổng hợp; căn cứ Nghị quyết Đại hội Đảng bộ khối. Bên dưới bên trái là bảng KPI của nhiệm vụ then chốt 2 với A = 1,00, B = 0,50 (nghiệm thu 18/12 "phải sửa nội dung cơ bản"), C = 0,85 (hạn 04/12, hoàn thành 09/12, chậm 3 ngày làm việc do chủ quan), KPI 0,783 theo Policy Pack Đảng v2.0, và đoạn "Ngược lên mục tiêu". Vùng 3 là thẻ minh chứng phiên bản 3: tệp BC-so-ket-TCCS-v3.pdf 1,8 MB, SHA-256, lưu Ceph RGW với Object Lock đến 2037, quét mã độc sạch, OCR trích số 112-BC/BTC, người nộp và người xác nhận khác nhau, cùng bảng ba phiên bản (v1, v2 yêu cầu bổ sung, v3 xác nhận) và ghi chú "mọi phiên bản giữ nguyên, không ghi đè". Vùng 4 là nút "Xuất chuỗi bằng chứng".

Ví dụ này cũng kiểm chứng công thức: C = 1 − 0,05 × 3 = 0,85 vì từ 04/12/2026 (thứ Sáu) tới 09/12/2026 (thứ Tư) có 3 ngày làm việc (07, 08, 09/12); KPI = (1 + 0,5 + 0,85) / 3 = 0,783.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Mắt xích điểm, cấu phần | Thẻ | Không áp dụng | Số | Từ snapshot | Chỉ đọc | `appraisal.score_snapshot` |
| 2 | Mắt xích KPI | Thẻ và bảng A, B, C | Không áp dụng | 2 hoặc 3 chữ số | Từ vết tính | Chỉ đọc | `scoring.score_trace` |
| 3 | Mắt xích sản phẩm | Thẻ | Không áp dụng | Số lượng duyệt / kế hoạch | Từ dữ liệu | Chỉ đọc | `product.deliverable` |
| 4 | Mắt xích minh chứng | Thẻ và bảng phiên bản | Không áp dụng | Tệp, băm, trạng thái | Phiên bản được tính | Kiểm tra quyền C2; ghi nhật ký | `product.evidence_version`, `evidence.object_meta`, `evidence.scan_result` |
| 5 | Mắt xích kế hoạch | Thẻ | Không áp dụng | Mã, phiên bản, trạng thái | Từ dữ liệu | Chỉ đọc | `task.plan_version`, `task.plan_item` |
| 6 | Mắt xích mục tiêu, KR | Thẻ | Không áp dụng | Mã, tên | Từ dữ liệu | Chỉ đọc | `okr.objective`, `okr.key_result`, `okr.okr_link` |
| 7 | Mắt xích căn cứ | Thẻ | Không áp dụng | Tên văn bản | Từ dữ liệu | Chỉ đọc | `okr.basis` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Chọn mắt xích | Luôn | Tải chi tiết mắt xích | `GET /api/v1/analytics/trace/{objectType}/{id}` | Không có |
| Mở tệp minh chứng | Có quyền C2 | Mở trình xem, ghi nhật ký truy cập | `GET /api/v1/evidences/{id}/versions/{v}/content` | Không có |
| Xuất chuỗi bằng chứng | Luôn | Xuất JSON và PDF chuỗi truy vết kèm mã băm, mã bản ghi sổ cái | `POST /api/v1/analytics/trace/exports` (202) | Không có |
| Quay lại bảng điều hành | Luôn | Điều hướng | Không có | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-16-06 | Đồng chí không có quyền xem chi tiết {doi_tuong} thuộc {don_vi}. | Lỗi phân quyền | Mắt xích ngoài phạm vi |
| MSG-16-07 | Minh chứng này đã có {n} phiên bản. Phiên bản được tính điểm là v{v}. | Thông tin | Mở minh chứng |

### Liên kết thiết kế

QT-16 dùng SVC-15 analytics (ClickHouse `analytics_<cell>`, Cube, Superset nhúng, analytics-api), SVC-14 ai-gateway (tóm tắt tuần), MOD-02 `task` (nhắc, giao lại), các module MOD-01 đến MOD-04 và SVC-08 cho chuỗi đi xuống, bảng đề xuất mới `config.dashboard_def`. Sự kiện: EVT-04, EVT-21 (phát), và toàn bộ sự kiện nghiệp vụ là nguồn CDC. Liên quan ADR-14 (CQRS phía đọc), ADR-06 (không đưa C2 rõ sang kho phân tích), NFR-03. Đáp ứng tiêu chí H7 (dashboard tự dựng trên lớp ngữ nghĩa, đi xuống tới minh chứng, so sánh nhiều kỳ) và yêu cầu truy vết hai chiều PL 4 III.2.

## QT-17. Trợ lý AI

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-17 |
| Tên | Trợ lý AI |
| Mục đích | Hỗ trợ người dùng soạn nháp mục tiêu O và KR từ văn bản căn cứ, soát KR mơ hồ hoặc trùng với nhiệm vụ, tóm tắt tình hình; mọi nội dung AI tạo được gắn nhãn và nguồn, AI không có quyền ghi |
| Phạm vi áp dụng | Soạn OKR (QT-02), soát kế hoạch (QT-04), tóm tắt tuần (QT-16), hỏi đáp văn bản hướng dẫn; không áp dụng cho chấm điểm, thẩm định, quyết định |
| Căn cứ | PL 1 mục 3.2a (KR phải đo lường, kiểm chứng được, không đồng nhất với nhiệm vụ); HD mục 1c; báo cáo BGĐ slide 30 và nguyên tắc "AI không quyết định"; PL A2 nhóm 8 (kênh trợ lý AI tiếng Việt) |
| Tác nhân | AC-06, AC-05 người dùng; AC-12 trợ lý AI (`ai_readonly`); SVC-14 ai-gateway; MOD-01 okr |
| Tần suất và khối lượng ước tính | Dồn vào hai tuần đầu quý khi lập OKR và kế hoạch. Giả định 20% người dùng dùng trợ lý, mỗi người 5 lượt mỗi quý, tức khoảng 6 triệu lượt mỗi quý; đỉnh ước tính khoảng 50 đến 100 lượt suy luận mỗi giây toàn quốc (số ước tính, phụ thuộc mức chấp nhận thực tế) |
| Điều kiện bắt đầu | Người dùng mở trợ lý ở màn hình có hỗ trợ (SCR-0202, SCR-1701) và có văn bản căn cứ trong phạm vi quyền đọc |
| Điều kiện kết thúc | Người dùng chèn gợi ý vào nháp (rồi tự trình duyệt qua QT-02) hoặc bỏ qua; mọi lượt được ghi `ai.suggestion_log` |
| Đầu vào | Câu hỏi tiếng Việt, văn bản căn cứ (đã chia đoạn trong `ai.doc_chunk`), dữ liệu tổ chức trong phạm vi quyền |
| Đầu ra | Gợi ý có nhãn "Nội dung do AI tạo" và nguồn; bản nháp O/KR do người dùng chèn (cờ `ai_assisted`); `ai.suggestion_log`, `ai.guardrail_event` |
| Chỉ số đo hiệu quả quy trình | Tỷ lệ gợi ý được chấp nhận; tỷ lệ KR được trợ lý cảnh báo mơ hồ mà người dùng sửa; thời gian phản hồi p95 (mục tiêu ≤ 8 giây cho gợi ý, đo khi triển khai); số sự kiện rào chắn |

Nguyên tắc nền tảng của QT-17 là AI hỗ trợ soạn thảo chứ không ra quyết định và không ghi dữ liệu. Trợ lý chạy với vai trò `ai_readonly` (AC-12): ai-gateway (SVC-14) chỉ có quyền đọc các văn bản và dữ liệu mà chính người dùng được đọc, theo cơ chế uỷ quyền token của người dùng; ai-gateway không có tài khoản ghi vào bất kỳ schema nghiệp vụ nào. Khi người dùng bấm "Dùng gợi ý", chính giao diện của người dùng gọi API của MOD-01 để lưu vào bản nháp với quyền của người dùng, và cờ `ai_assisted` được đặt để sau này biết nội dung có nguồn gốc AI. Việc trình phê duyệt hoàn toàn do người dùng thực hiện.

Mô hình ngôn ngữ chạy tại chỗ (vLLM trong vùng bảo mật có GPU), dữ liệu không ra khỏi hạ tầng của tenant. Dữ liệu C3 không bao giờ được đưa vào trợ lý (ADR-06). Rào chắn đầu vào lọc các yêu cầu ngoài phạm vi (ví dụ "chấm điểm giúp tôi", "đề xuất xếp loại cho đồng chí X"), rào chắn đầu ra kiểm tra gợi ý có trích nguồn, không chứa dữ liệu ngoài phạm vi quyền và không chứa kết luận đánh giá.

### Lưu đồ quy trình

![Hình B3.11. Lưu đồ làn bơi QT-17: trợ lý AI chỉ đọc soạn nháp OKR và soát KR](nv-b-qt17-luong.png){width=16cm}

Người dùng chọn văn bản căn cứ và hỏi bằng tiếng Việt. ai-gateway kiểm tra quyền đọc và rào chắn đầu vào, truy xuất các đoạn liên quan từ `ai.doc_chunk` (pgvector) cùng PL 1 của dự thảo, gọi mô hình ngôn ngữ để soạn O và KR hoặc soát KR, áp rào chắn đầu ra, gắn nhãn "Nội dung do AI tạo" và nguồn, rồi ghi `ai.suggestion_log`. Người dùng xem gợi ý: dùng thì chèn vào bản nháp (lưu ở MOD-01 với cờ `ai_assisted`), bỏ qua thì việc từ chối cũng được ghi nhận. Bước trình phê duyệt nằm ở QT-02 và do người dùng tự làm.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Mở trợ lý, chọn căn cứ | AC-06 | Mở ngăn trợ lý; đính kèm hoặc chọn văn bản căn cứ đã có trong hệ thống (từ EVT-24 hoặc tải lên) | SCR-1701 | Không có | Không giới hạn |
| 2 | Kiểm tra quyền, rào chắn đầu vào | SVC-14 | Xác minh token người dùng, quyền đọc văn bản; lọc yêu cầu ngoài phạm vi | Không có | `ai.guardrail_event` nếu chặn | ≤ 200 ms |
| 3 | Truy xuất ngữ cảnh | SVC-14 | Tìm đoạn liên quan trong `ai.doc_chunk` theo véc-tơ, cộng PL 1 và dữ liệu tổ chức trong phạm vi (ví dụ danh sách 18 chi bộ) | Không có | Danh sách nguồn | ≤ 500 ms |
| 4 | Sinh gợi ý | SVC-14 | Mô hình soạn O và KR có chỉ tiêu đo được; soát KR mơ hồ; phát hiện KR trùng với KR đơn vị khác | Không có | Bản gợi ý | Vài giây |
| 5 | Rào chắn đầu ra, gắn nhãn | SVC-14 | Kiểm tra trích nguồn, phạm vi dữ liệu, không có kết luận đánh giá; gắn nhãn và nguồn | SCR-1701 | `ai.suggestion_log` | ≤ 200 ms |
| 6 | Xem và quyết định | AC-06 | Đọc gợi ý, lý do, nguồn; chọn "Dùng gợi ý" hoặc "Bỏ qua" | SCR-1701 | Cập nhật `ai.suggestion_log.accepted_by` hoặc lý do bỏ qua | Không giới hạn |
| 7 | Chèn vào nháp | AC-06, MOD-01 | Giao diện gọi API okr với quyền người dùng; người dùng có thể sửa tiếp | SCR-1701 | `okr.objective`, `okr.key_result` (NHAP, `ai_assisted = true`) | Tức thời |
| 8 | Trình phê duyệt | AC-06 | Người dùng tự bấm "Trình phê duyệt"; chuyển sang QT-02 | SCR-1701, SCR-0203 | Mục tiêu `CHO_DUYET` | Theo QT-02 |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-17-01 | Trợ lý AI không có quyền ghi dữ liệu nghiệp vụ; mọi nội dung chỉ vào hệ thống khi người dùng chủ động chèn | Nguyên tắc AI không quyết định; AC-12 | ai-gateway không có tài khoản CSDL ghi vào schema nghiệp vụ; Keycloak client `ai_readonly` chỉ có scope đọc |
| BR-17-02 | Mọi nội dung do AI tạo phải gắn nhãn "Nội dung do AI tạo" và nêu nguồn (văn bản, trang, dữ liệu đã dùng) | Minh bạch AI | Rào chắn đầu ra từ chối gợi ý không có nguồn; giao diện hiển thị nhãn bắt buộc |
| BR-17-03 | Trợ lý chỉ đọc dữ liệu mà người dùng đang có quyền đọc | PL 4 V.1; ADR-06 | Uỷ quyền token người dùng (token exchange); lọc `doc_chunk` theo ACL |
| BR-17-04 | Trợ lý không chấm điểm, không đề xuất mức xếp loại, không nhận xét cán bộ | Nguyên tắc AI không quyết định | Rào chắn đầu vào và đầu ra; ghi `guardrail_event` |
| BR-17-05 | KR phải đo lường, kiểm chứng được và không đồng nhất với nhiệm vụ; trợ lý cảnh báo KR mơ hồ và đề xuất cách viết lại | PL 1 mục 3.2a | Luật soát KR (từ mơ hồ như "tăng cường", "đẩy mạnh" không có chỉ tiêu) kết hợp mô hình |
| BR-17-06 | Mỗi kỳ khuyến nghị 1 đến 3 mục tiêu O; trợ lý không đề xuất quá 3 | PL 1 | Tham số trong prompt và kiểm tra đầu ra |
| BR-17-07 | Dữ liệu C3 không đưa vào trợ lý; dữ liệu C2 chỉ dùng khi người dùng có quyền và không lưu trong nhật ký mô hình | ADR-06 | `doc_chunk` không chứa C3; nhật ký suy luận không lưu nội dung C2 |
| BR-17-08 | Nội dung được chèn từ gợi ý mang cờ `ai_assisted`, hiển thị cho người duyệt OKR | Minh bạch AI | Cột cờ trên `okr.objective`, `okr.key_result`; nhãn trên SCR-0203 |
| BR-17-09 | Mọi lượt dùng trợ lý được ghi nhật ký: câu hỏi (đã che C2), nguồn, gợi ý, quyết định của người dùng | Truy vết | `ai.suggestion_log` |

### Ngoại lệ và xử lý

Khi hàng đợi suy luận đầy (đầu quý), ai-gateway trả 202 kèm mã theo dõi và giao diện hiển thị "Trợ lý đang bận, gợi ý sẽ hiện khi xong"; người dùng vẫn soạn OKR bình thường. ai-gateway co giãn theo độ dài hàng đợi suy luận.

Khi rào chắn chặn yêu cầu ngoài phạm vi, trợ lý trả lời lịch sự rằng việc chấm điểm, xếp loại thuộc thẩm quyền của con người theo quy định, và gợi ý màn hình phù hợp.

Khi văn bản căn cứ chưa được lập chỉ mục (vừa tải lên), ai-gateway lập chỉ mục trước rồi mới trả lời; thời gian chờ có thể tăng vài giây.

Khi gợi ý trích nguồn không tồn tại (mô hình "bịa" nguồn), rào chắn đầu ra đối chiếu với danh sách nguồn đã truy xuất và loại bỏ gợi ý đó.

### Đặc tả màn hình

#### SCR-1701. Trợ lý AI soạn OKR và soát KR

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho người dùng soạn bản nháp OKR có hỗ trợ của trợ lý AI chỉ đọc, xem cảnh báo KR mơ hồ, chèn gợi ý và tự trình phê duyệt |
| Tác nhân | AC-06, AC-05; AC-12 trợ lý |
| Đường vào | Menu Mục tiêu › Tạo OKR; nút "Trợ lý AI" trên SCR-0202 |
| Quyền | Người soạn OKR của đơn vị |
| Thiết bị | Web desktop; trên di động trợ lý mở dạng ngăn kéo toàn màn hình |

![Hình B3.12. SCR-1701 Trợ lý AI soạn OKR quý I/2027 và soát KR mơ hồ](SCR-1701.png){width=16cm}

Màn hình theo tinh thần slide 30 của báo cáo BGĐ: bản nháp OKR bên trái, trợ lý bên phải với nền tím nhạt và nhãn "Chỉ đọc · không ghi".

Vùng 4 là bản nháp OKR quý I/2027 của Ban Tổ chức (bản nháp 4, trạng thái `NHAP`), có nhắc khuyến nghị 1 đến 3 mục tiêu. Mục tiêu O1 "Nâng chất lượng sinh hoạt chi bộ khối cơ quan" mang nhãn "chèn từ gợi ý AI · đã chấp nhận" và căn cứ Kế hoạch công tác năm 2027, mục II.2; KR 1.3 "Tăng cường công tác kiểm tra, giám sát" được tô vàng với nhãn đỏ "Chưa đo được". Cuối cột là nút "Trình phê duyệt" kèm câu "Chỉ người dùng mới có nút Trình phê duyệt. Trợ lý AI không có quyền ghi vào O/KR". Vùng 1 là yêu cầu của người dùng kèm tệp KH-25-KH/ĐUK.pdf và trả lời đầu tiên của trợ lý (đề xuất 2 O và 5 KR bám mục II.2 và III.1) với nhãn "Nội dung do AI tạo" và nguồn "KH-25-KH/ĐUK tr. 4–6; PL 1 dự thảo". Vùng 2 là thẻ soát KR 1.3: giải thích cụm "tăng cường" không đo được và giống mô tả nhiệm vụ, gợi ý viết lại "Kiểm tra 100% chi bộ trực thuộc trước 31/3/2027", lý do theo PL 1 mục 3.2a, dữ liệu đã dùng là danh sách 18 chi bộ, cùng hai nút "Bỏ qua" và "Dùng gợi ý". Vùng 3 là lưu ý KR 2.1 trùng chỉ tiêu với KR của Văn phòng Đảng uỷ khối, đề nghị liên kết ngang, và ô hỏi trợ lý ở cuối.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Mục tiêu O | Văn bản | Có | ≤ 300 ký tự | Trống | 1 đến 3 mục tiêu mỗi kỳ (cảnh báo khi > 3) | `okr.objective.title` |
| 2 | Căn cứ của O | Liên kết | Có | Văn bản căn cứ, mục | Trống | Phải có căn cứ cấp trên | `okr.objective.basis_id`, `okr.basis` |
| 3 | KR | Văn bản | Có | ≤ 300 ký tự | Trống | Có chỉ tiêu đo được; cảnh báo khi mơ hồ | `okr.key_result.title` |
| 4 | Chỉ tiêu, trọng số KR | Số | Có | Chỉ tiêu theo đơn vị; trọng số % | Trống | Tổng trọng số của O bằng 100% | `okr.key_result.target_value`, `okr.key_result.weight` |
| 5 | Cờ AI hỗ trợ | Nhãn | Không áp dụng | Boolean | false | Đặt khi chèn từ gợi ý | `okr.objective.ai_assisted`, `okr.key_result.ai_assisted` |
| 6 | Câu hỏi cho trợ lý | Văn bản | Không | ≤ 2.000 ký tự | Trống | Rào chắn đầu vào | `ai.suggestion_log.prompt_redacted` |
| 7 | Tệp căn cứ đính kèm | Tệp | Không | PDF, DOCX ≤ 20 MB | Trống | Quyền đọc; lập chỉ mục | `ai.doc_chunk` (qua `evidence.object_meta`) |
| 8 | Gợi ý | Thẻ | Không áp dụng | Văn bản, nguồn, lý do | Từ trợ lý | Nhãn AI bắt buộc | `ai.suggestion_log.output`, `ai.suggestion_log.sources_json` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Gửi câu hỏi | Ô hỏi có nội dung | Gọi trợ lý; hiển thị kết quả hoặc mã theo dõi | `POST /api/v1/ai/assist/okr` | Không có |
| Dùng gợi ý | Có gợi ý chưa xử lý | Chèn vào nháp bằng API okr với quyền người dùng; ghi chấp nhận | `PUT /api/v1/okr/objectives/{id}` hoặc `POST /api/v1/okr/key-results`; `POST /api/v1/ai/suggestions/{id}/accept` | Không có |
| Bỏ qua | Có gợi ý chưa xử lý | Ghi từ chối, tuỳ chọn lý do | `POST /api/v1/ai/suggestions/{id}/dismiss` | Không có |
| Lưu nháp | Luôn | Lưu bản nháp OKR | `PUT /api/v1/okr/objectives/{id}` | Không có |
| Trình phê duyệt | Người soạn, đủ trường bắt buộc | Chuyển sang luồng QT-02 | `POST /api/v1/okr/objectives/{id}/submit` | EVT-21 (sau đó EVT-01 khi duyệt) |
| Lịch sử nháp | Luôn | Hiển thị các bản nháp và nguồn gốc nội dung | `GET /api/v1/okr/objectives/{id}/history` | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-17-01 | KR "{kr}" chưa đo được. Gợi ý: {goi_y}. | Cảnh báo | Soát KR |
| MSG-17-02 | Trợ lý không thực hiện chấm điểm hoặc đề xuất xếp loại. Việc này thuộc thẩm quyền của cán bộ và cấp có thẩm quyền theo quy định. | Thông tin | Rào chắn chặn |
| MSG-17-03 | Trợ lý đang bận. Gợi ý sẽ hiển thị khi xong, đồng chí có thể tiếp tục soạn. | Thông tin | Hàng đợi đầy (202) |
| MSG-17-04 | Đã chèn gợi ý vào bản nháp. Nội dung được đánh dấu "AI hỗ trợ" để người duyệt biết. | Thành công | Dùng gợi ý |

### Liên kết thiết kế

QT-17 dùng SVC-14 ai-gateway (schema `ai`: `doc_chunk`, `suggestion_log`, `guardrail_event`; vLLM tại chỗ; pgvector), MOD-01 `okr` (`objective`, `key_result`, `basis`, `okr_link`), SVC-02 iam (client `ai_readonly`, token exchange), SVC-13 integration-gateway (EVT-24 nhận văn bản chỉ đạo để lập chỉ mục). Sự kiện: EVT-24 (đầu vào), EVT-21. Liên quan ADR-06 (không đưa C3 vào AI). Đáp ứng PL A1 nhóm 1 "AI kiểm tra KR có trùng với nhiệm vụ không", PL A2 nhóm 8 "trợ lý AI tiếng Việt" và tiêu chí H1.

## QT-18. Cấu hình giao diện tenant (UI Pack)

### Thông tin chung

| Thuộc tính | Nội dung |
|---|---|
| Mã | QT-18 |
| Tên | Cấu hình giao diện tenant (UI Pack) |
| Mục đích | Cho quản trị tenant cấu hình giao diện riêng (token màu, logo, chữ, bo góc), bố cục trang chủ và widget từ danh mục component cho phép, biểu mẫu động, chế độ Đảng viên cao tuổi; xem trước; phát hành trong 1 ngày không cần phát hành lại mã; quay lui phiên bản |
| Phạm vi áp dụng | Mọi tenant; bề mặt cấu hình được theo kết luận G1; plugin chuyên ngành theo manifest |
| Căn cứ | Góp ý G1 và baseline mục 14 G1; ADR-05 (bốn lớp); NFR-07 (UI Pack ≤ 1 ngày), NFR-11 (WCAG 2.1 AA, chế độ cao tuổi chữ ≥ 17 px, ≤ 3 chạm) |
| Tác nhân | AC-02 quản trị tenant (soạn); người duyệt (AC-02 thứ hai hoặc AC-03 khi biểu mẫu gắn Policy Pack); SVC-17 web-frontend; SVC-18 mobile-app; MOD-08 config |
| Tần suất và khối lượng ước tính | Khi tenant mới vào vận hành và vài lần mỗi năm; tối đa 5.000 tenant nhưng mỗi lần phát hành chỉ vô hiệu bộ đệm CDN của một tenant; tải UI Pack 11,2 KB gzip với tenant mẫu (số đo trên bản dựng thử), ngân sách ≤ 30 KB |
| Điều kiện bắt đầu | Tenant đã khởi tạo (QT-01); quản trị tenant mở cấu hình giao diện |
| Điều kiện kết thúc | Phiên bản UI Pack `DA_PHAT_HANH`, EVT-23 đã phát, bộ đệm CDN đã vô hiệu; người dùng nhận giao diện mới ở lần tải trang kế tiếp |
| Đầu vào | Bộ token mặc định của `@vt/design-system`, danh mục component cho phép (registry có phiên bản), Policy Pack đang gắn (với biểu mẫu) |
| Đầu ra | `config.ui_pack_version` mới, `config.form_schema` mới, CSS custom properties và preset PrimeNG biên dịch sẵn trên CDN; EVT-23 |
| Chỉ số đo hiệu quả quy trình | Thời gian từ yêu cầu tới phát hành (≤ 1 ngày); tỷ lệ phiên bản phải quay lui; kích thước UI Pack (≤ 30 KB); thời gian dựng biểu mẫu 50 trường (≤ 50 ms); điểm kiểm tra khả năng truy cập AA |

Góp ý G1 của lãnh đạo hỏi liệu kiến trúc SDUI cộng Micro-frontends có áp dụng được để mỗi tenant có giao diện riêng. Kết luận đã chốt ở baseline mục 14 là áp dụng được nhưng có chọn lọc theo lớp, và QT-18 là quy trình người dùng của kết luận đó. Lớp 1 (token) áp dụng toàn bộ: tenant đổi màu, logo, chữ, bo góc, khoảng cách trong giới hạn tương phản AA. Lớp 2 (SDUI có giới hạn) chỉ áp dụng cho bề mặt cấu hình được: bố cục trang chủ và widget, nhóm trường và thứ tự thẻ ở trang chi tiết, bố cục dashboard; schema bố cục chỉ được tham chiếu component có trong danh mục cho phép, không chứa HTML hay JavaScript tuỳ ý. Lớp 3 (biểu mẫu động) dùng Formly cộng JSON Schema cộng CEL. Lớp 4 (plugin) nạp module chuyên ngành như `fe-plugin-yte` theo manifest của tenant.

Phần không cấu hình được cũng quan trọng như phần cấu hình được. Các màn hình tương tác nặng và có hệ quả pháp lý (cây OKR, Config Studio, thẩm định so sánh, quyết định hàng loạt, bảng điều hành có đi xuống) được viết mã, kiểm thử tự động, kiểm tra khả năng truy cập một lần cho mọi tenant; chúng chỉ nhận token. Lý do là chất lượng kiểm thử, khả năng truy cập, hiệu năng và an toàn (chống XSS) của các màn hình này không thể phụ thuộc vào cấu hình của từng tenant. Công thức tính điểm cũng không nằm trong UI Pack mà trong Policy Pack (QT-15).

Ví dụ dùng xuyên suốt là hai tenant dùng chung một bản dựng web-frontend: Đảng uỷ các cơ quan Đảng TW với UI Pack v2.3 (màu đỏ #EE0033, bo góc 8 px) và Sở Y tế tỉnh X với UI Pack v1.8 đang soạn (màu #0A7E8C, bo góc 14 px, logo riêng, widget chuyên môn từ plugin Y tế, biểu mẫu phiếu đánh giá viên chức y tế `phieu-vc-yte` v12 gắn Policy Pack `nn-335/so-y-te` v1.3).

### Lưu đồ quy trình

![Hình B3.13. Lưu đồ làn bơi QT-18: cấu hình, kiểm tra và phát hành UI Pack](nv-b-qt18-luong.png){width=16cm}

Quản trị tenant lần lượt chọn token, sắp bố cục trang chủ từ danh mục widget, dựng biểu mẫu động, xem trước song song hai tenant và chế độ cao tuổi, rồi gửi duyệt. Làn "Hệ thống" chạy bộ kiểm tra tự động: schema hợp lệ, mọi component nằm trong registry, tương phản đạt AA, kích thước gói ≤ 30 KB, biểu thức CEL cho cùng kết quả trên cel-js và cel-java. Lỗi thì quay lại sửa; đạt thì chuyển người duyệt. Sau khi duyệt, MOD-08 biên dịch token thành CSS custom properties và preset PrimeNG, lưu `ui_pack_version`, phát EVT-23 để web-frontend vô hiệu bộ đệm CDN. Người dùng nhận giao diện mới trong vòng một ngày mà không cần phát hành lại mã; khi có sự cố, quản trị tenant quay lui về phiên bản trước.

### Mô tả các bước

| STT | Bước | Tác nhân | Mô tả chi tiết | Màn hình (SCR) | Kết quả / Sự kiện (EVT) | Thời hạn |
|---|---|---|---|---|---|---|
| 1 | Tạo phiên bản nháp | AC-02 | Nhân bản phiên bản đang dùng hoặc từ bộ mặc định | SCR-1801 | `config.ui_pack_version` `NHAP` | Giờ thứ 1 |
| 2 | Chọn token | AC-02 | Màu thương hiệu, màu chữ trên nền thương hiệu, màu cảnh báo, phông, cỡ chữ cơ sở, bo góc, đơn vị khoảng cách, logo | SCR-1801 | Token JSON (W3C Design Tokens) | Giờ thứ 1 đến 2 |
| 3 | Bố cục trang chủ | AC-02 | Thêm, xoá, sắp xếp widget từ danh mục component cho phép, kể cả widget của plugin được bật | SCR-1801 | Schema bố cục SDUI | Giờ thứ 2 |
| 4 | Dựng biểu mẫu động | AC-02 | Kéo thả trường, đặt khoá dữ liệu, kiểu, quy tắc kiểm tra CEL, điều kiện hiển thị, lớp dữ liệu; chạy bộ test biểu mẫu | SCR-1802 | `config.form_schema` (phiên bản mới) | Giờ thứ 2 đến 4 |
| 5 | Chế độ cao tuổi | AC-02 | Bật bộ token phụ "elder" và cờ "ít bước" ở mức tenant | SCR-1801, SCR-1803 | Cờ trong `ui_pack_version` | Giờ thứ 4 |
| 6 | Xem trước | AC-02 | Xem song song giao diện hai tenant, xem trên khung di động, xem chế độ cao tuổi | SCR-1801, SCR-1803 | Không có | Giờ thứ 4 |
| 7 | Gửi duyệt, kiểm tra tự động | AC-02, MOD-08 | Chạy bộ kiểm tra; lỗi thì về nháp | SCR-1801 | `DANG_KIEM_TRA` rồi `CHO_DUYET` hoặc `NHAP` | Vài phút |
| 8 | Duyệt | Người duyệt | Xem khác biệt, ảnh chụp xem trước; duyệt hoặc từ chối | SCR-1801 (chế độ duyệt) | `DA_PHAT_HANH` hoặc `NHAP` | Trong ngày |
| 9 | Biên dịch, phát hành | MOD-08, SVC-17 | Biên dịch token sang CSS và preset; đẩy lên CDN với ETag theo phiên bản; phát EVT-23 vô hiệu bộ đệm | Không có | EVT-23 | ≤ 15 phút |
| 10 | Quay lui | AC-02 | Chọn phiên bản trước và kích hoạt lại | SCR-1801 thẻ Phiên bản | Phiên bản trước `DA_PHAT_HANH`; EVT-23 | ≤ 15 phút |

Tổng thời gian trong bảng thể hiện cách đạt chỉ tiêu NFR-07 "thay giao diện tenant ≤ 1 ngày": phần soạn mất vài giờ, kiểm tra tự động vài phút, duyệt trong ngày, phát hành dưới 15 phút. Không có bước nào cần đội phát triển hay quy trình phát hành phần mềm.

### Trạng thái đối tượng

![Hình B3.14. Sơ đồ trạng thái phiên bản UI Pack config.ui_pack_version](nv-b-qt18-trangthai.png){width=13cm}

Bộ trạng thái của UI Pack là đề xuất mới (baseline chỉ quy định trạng thái Policy Pack). Khác với Policy Pack, phiên bản UI Pack bị thay thế có thể được kích hoạt lại ngay (quay lui) vì thay đổi giao diện không ảnh hưởng tới kết quả tính toán.

| Trạng thái hiện tại | Sự kiện hoặc hành động | Trạng thái kế tiếp | Điều kiện kiểm soát |
|---|---|---|---|
| (chưa có) | Tạo hoặc nhân bản | `NHAP` | `tenant_admin` |
| `NHAP` | Gửi duyệt | `DANG_KIEM_TRA` | Không có |
| `DANG_KIEM_TRA` | Lỗi kiểm tra tự động | `NHAP` | Danh sách lỗi |
| `DANG_KIEM_TRA` | Đạt | `CHO_DUYET` | Mọi kiểm tra đạt |
| `CHO_DUYET` | Từ chối | `NHAP` | Có ý kiến |
| `CHO_DUYET` | Duyệt | `DA_PHAT_HANH` | Người duyệt khác người soạn; EVT-23 |
| `DA_PHAT_HANH` | Phiên bản mới phát hành | `THAY_THE` | Tự động |
| `THAY_THE` | Quay lui | `DA_PHAT_HANH` | Phiên bản tương thích Policy Pack đang gắn |

### Quy tắc nghiệp vụ

| Mã | Quy tắc | Căn cứ | Cách hệ thống kiểm soát |
|---|---|---|---|
| BR-18-01 | Token theo chuẩn W3C Design Tokens, biên dịch thành CSS custom properties và preset PrimeNG; màu chữ trên nền phải đạt tương phản WCAG AA (≥ 4,5 : 1 với chữ thường) | ADR-05; NFR-11 | Kiểm tra tương phản tự động khi chọn màu và khi gửi duyệt |
| BR-18-02 | Bố cục SDUI chỉ được tham chiếu component trong danh mục cho phép có phiên bản; không chứa HTML, JavaScript tuỳ ý | ADR-05; G1 | Kiểm tra schema với `config.component_registry`; renderer `@vt/sdui-renderer` từ chối component lạ |
| BR-18-03 | Màn hình lõi (cây OKR, Config Studio, thẩm định, quyết định hàng loạt, bảng điều hành, sổ cái) chỉ nhận token, không cấu hình bố cục | ADR-05; G1 | Các màn hình này không đọc schema SDUI |
| BR-18-04 | Biểu mẫu động dùng Formly và JSON Schema; quy tắc kiểm tra và điều kiện hiển thị viết bằng CEL; trình duyệt kiểm tra tức thời bằng cel-js, máy chủ kiểm tra lại bằng cel-java và là nguồn sự thật | ADR-05; ADR-19 | `@vt/form-engine`; API nghiệp vụ kiểm tra lại toàn bộ schema |
| BR-18-05 | Bộ test biểu mẫu phải cho cùng kết quả trên cel-js và cel-java | ADR-05 | Chạy khi gửi duyệt; chặn nếu khác |
| BR-18-06 | Phiên bản biểu mẫu tham chiếu phiên bản Policy Pack; trường được tính điểm phải khớp khai báo của Policy Pack | ADR-05 | Cột `config.ui_pack_version.policy_pack_ref`, kiểm tra khoá dữ liệu |
| BR-18-07 | Thay đổi thuần giao diện không phải chạy lại test case tính điểm | ADR-05 | UI Pack tách khỏi Policy Pack |
| BR-18-08 | Kích thước UI Pack ≤ 30 KB gzip; dựng biểu mẫu 50 trường ≤ 50 ms | Baseline G1 | Kiểm tra khi gửi duyệt; số đo từ bộ test trình duyệt không giao diện |
| BR-18-09 | Chế độ Đảng viên cao tuổi: chữ cơ sở ≥ 17 px, nút cao ≥ 48 px, tương phản ≥ 7 : 1, luồng rút gọn một câu hỏi mỗi màn hình, thao tác thường ngày ≤ 3 chạm | NFR-11 | Bộ token phụ "elder" và cờ "ít bước"; kiểm tra tự động |
| BR-18-10 | Phát hành UI Pack trong ≤ 1 ngày, không phát hành lại mã; có thể quay lui về phiên bản trước | NFR-07 | Trạng thái `THAY_THE` kích hoạt lại được; EVT-23 |
| BR-18-11 | Trường biểu mẫu khai báo lớp dữ liệu (C1, C2); trường C2 được mã hoá trường khi lưu | ADR-06 | Thuộc tính `x-classification` trong JSON Schema |
| BR-18-12 | Plugin chuyên ngành chỉ được nạp khi có trong manifest của tenant, bản dựng đã ký và quét như service | ADR-05; ADR-16 | Native Federation đọc manifest; kiểm tra chữ ký |

### Ngoại lệ và xử lý

Khi người dùng đang mở ứng dụng trong lúc phát hành, giao diện của họ không đổi đột ngột; web-frontend nhận EVT-23 qua kênh thời gian thực và hiển thị gợi ý "Có giao diện mới, tải lại khi thuận tiện". Biểu mẫu đang nhập dở vẫn dùng schema cũ cho tới khi nộp, và máy chủ chấp nhận cả hai phiên bản schema trong thời gian chuyển tiếp nếu chúng tham chiếu cùng phiên bản Policy Pack.

Khi một widget của plugin không tải được (plugin lỗi hoặc bị gỡ khỏi manifest), renderer SDUI hiển thị khung giữ chỗ và ghi lỗi, phần còn lại của trang chủ vẫn hoạt động.

Khi quay lui về một phiên bản UI Pack có biểu mẫu tham chiếu Policy Pack đã ngừng, hệ thống chặn quay lui và yêu cầu chọn phiên bản tương thích.

### Đặc tả màn hình

#### SCR-1801. Cấu hình giao diện tenant

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Soạn token, bố cục trang chủ, chế độ cao tuổi, xem trước song song hai tenant, gửi duyệt và phát hành, quay lui |
| Tác nhân | AC-02 (`tenant_admin`); người duyệt |
| Đường vào | Menu Quản trị › Giao diện tenant |
| Quyền | `tenant_admin` của tenant; người duyệt khác người soạn |
| Thiết bị | Web desktop |

![Hình B3.15. SCR-1801 Cấu hình giao diện tenant Sở Y tế tỉnh X, xem trước song song với Đảng uỷ khối](SCR-1801.png){width=16cm}

Tiêu đề nêu rõ phạm vi "Lớp 1 Design Token (W3C Design Tokens) và lớp 2 bố cục trang chủ, không cần phát hành lại mã, NFR-07 ≤ 1 ngày". Thanh trạng thái cho biết UI Pack Sở Y tế tỉnh X v1.8 đang `NHAP`, gắn Policy Pack `nn-335/so-y-te` v1.3 và biểu mẫu tham chiếu `form_schema` v12.

Vùng 1 là bảng token với tên chuẩn (ví dụ `color.brand.primary` #0A7E8C, `font.size.base` 13px, `radius.card` 14px, `asset.logo` logo-so-y-te-x.svg) và kết quả kiểm tra tương phản WCAG AA 4,9 : 1. Vùng 2 là bố cục trang chủ SDUI với ba widget kéo thả (WorkTodayList "Việc của tôi", KpiTile × 2, ChuyenMonKpiWidget của plugin `fe-plugin-yte`) và nút "Thêm widget từ danh mục" (registry v2026.3). Vùng 3 là khối chế độ Đảng viên cao tuổi với hai công tắc: bộ token phụ "elder" và cờ "ít bước". Vùng 4 là xem trước song song hai tenant dùng cùng một bản dựng web-frontend: Đảng uỷ các cơ quan Đảng TW (UI Pack v2.3, đỏ, bo góc 8 px) và Sở Y tế tỉnh X (UI Pack v1.8, xanh mòng két, bo góc 14 px). Vùng 5 là bảng "Phạm vi được cấu hình" liệt kê bề mặt nào tenant cấu hình được và phần nào không, cùng dòng "Tải UI Pack 11,2 KB gzip (ngân sách ≤ 30 KB), phục vụ qua CDN, ETag theo phiên bản, phát hành phát EVT-23 vô hiệu cache".

Đoạn dưới là ví dụ token của Sở Y tế tỉnh X ở định dạng W3C Design Tokens, đúng như được lưu trong `config.ui_pack_version.tokens_json`:

```json
{
  "color": {
    "brand": {
      "primary": { "$type": "color", "$value": "#0A7E8C" },
      "on-primary": { "$type": "color", "$value": "#FFFFFF" }
    },
    "semantic": { "danger": { "$type": "color", "$value": "#D93025" } }
  },
  "font": {
    "family": { "base": { "$type": "fontFamily", "$value": ["DejaVu Sans", "sans-serif"] } },
    "size": { "base": { "$type": "dimension", "$value": "13px" } }
  },
  "radius": { "card": { "$type": "dimension", "$value": "14px" } },
  "space": { "unit": { "$type": "dimension", "$value": "4px" } }
}
```

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Phiên bản, trạng thái | Nhãn | Không áp dụng | vX.Y, trạng thái | Từ dữ liệu | Chỉ đọc | `config.ui_pack_version.version`, `config.ui_pack_version.status` |
| 2 | Policy Pack gắn | Nhãn và liên kết | Có | Mã gói, phiên bản | Gói đang gắn với tenant | Phải `DA_PHAT_HANH` | `config.ui_pack_version.policy_pack_ref` |
| 3 | Token màu | Bộ chọn màu | Có | Hex 6 ký tự | Bộ mặc định | Tương phản AA với màu chữ tương ứng | `config.ui_pack_version.tokens_json` |
| 4 | Token chữ, bo góc, khoảng cách | Danh sách chọn, số | Có | px | Bộ mặc định | Cỡ chữ cơ sở 12 đến 16 px (chế độ thường); bo góc 0 đến 16 px | `config.ui_pack_version.tokens_json` |
| 5 | Logo | Tệp | Không | SVG hoặc PNG ≤ 50 KB | Logo mặc định | SVG được làm sạch, không có script | `config.ui_pack_version.assets_json` |
| 6 | Bố cục trang chủ | Danh sách kéo thả | Có | Widget, thứ tự, tham số | Bố cục mặc định | Mọi widget có trong registry; tối đa 12 widget | `config.ui_pack_version.layout_json`, `config.component_registry` |
| 7 | Bật chế độ cao tuổi | Công tắc | Không | Boolean | Tắt | Không có | `config.ui_pack_version.elder_mode` |
| 8 | Bật cờ ít bước | Công tắc | Không | Boolean | Tắt | Chỉ khi bật chế độ cao tuổi hoặc theo người dùng | `config.ui_pack_version.few_steps` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Lưu nháp | `NHAP` | Lưu token, bố cục, cờ | `PUT /api/v1/ui/packs/{id}/versions/{v}` | Không có |
| Thêm widget từ danh mục | `NHAP` | Mở danh mục component cho phép, kể cả widget plugin trong manifest | `GET /api/v1/ui/components?registry=2026.3` | Không có |
| Xem trước | Luôn | Dựng giao diện mẫu với token và bố cục nháp | `GET /api/v1/ui/packs/{id}/versions/{v}/preview` | Không có |
| Gửi duyệt và phát hành | `NHAP` | Chạy kiểm tra tự động, chuyển người duyệt | `POST /api/v1/ui/packs/{id}/versions/{v}/submit` | EVT-21 |
| Duyệt | Người duyệt, `CHO_DUYET` | Biên dịch, đẩy CDN, phát hành | `POST /api/v1/ui/packs/{id}/versions/{v}/publish` | EVT-23 |
| Quay lui v1.6 | Có phiên bản `THAY_THE` tương thích | Kích hoạt lại phiên bản đã chọn | `POST /api/v1/ui/packs/{id}/versions/{v}/activate` | EVT-23 |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-18-01 | Màu chữ {mau_chu} trên nền {mau_nen} có tương phản {ti_le} : 1, chưa đạt mức AA (4,5 : 1). | Lỗi kiểm tra | Chọn màu |
| MSG-18-02 | Component {ten} không có trong danh mục cho phép phiên bản {registry}. | Lỗi kiểm tra | Bố cục có component lạ |
| MSG-18-03 | UI Pack v{v} đã phát hành. Người dùng sẽ nhận giao diện mới ở lần tải trang kế tiếp. | Thành công | Phát hành xong |
| MSG-18-04 | Không thể quay lui về v{v} vì biểu mẫu của phiên bản này tham chiếu Policy Pack đã ngừng. | Lỗi | Quay lui không tương thích |
| MSG-18-05 | Kích thước UI Pack là {kb} KB, vượt ngân sách 30 KB. | Lỗi kiểm tra | Kiểm tra kích thước |

#### SCR-1802. Trình dựng biểu mẫu động

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Dựng biểu mẫu nghiệp vụ của tenant bằng kéo thả trường, khai báo quy tắc kiểm tra và điều kiện hiển thị bằng CEL, xem trước tức thời, sinh JSON Schema, chạy bộ test biểu mẫu |
| Tác nhân | AC-02 (`tenant_admin`); AC-03 khi biểu mẫu gắn Policy Pack |
| Đường vào | Menu Quản trị › Biểu mẫu động; liên kết từ SCR-1801 |
| Quyền | `tenant_admin`; trường được tính điểm chỉ thêm được khi Policy Pack khai báo |
| Thiết bị | Web desktop |

![Hình B3.16. SCR-1802 Trình dựng biểu mẫu động phiếu đánh giá viên chức y tế](SCR-1802.png){width=16cm}

Tiêu đề và dòng phụ nêu công nghệ và phiên bản: Formly cộng JSON Schema, kiểm tra bằng CEL (cel-js trên trình duyệt, cel-java trên máy chủ là nguồn sự thật), `form_schema` `phieu-vc-yte` v12, gắn Policy Pack `nn-335/so-y-te` v1.3.

Vùng 1 là bảng thành phần gồm mười loại trường được phép (văn bản ngắn, văn bản dài C2, số, ngày, chọn một, chọn nhiều, tệp minh chứng, bảng lặp, nhóm trường, chữ ký số) và ghi chú "không có trường HTML tự do". Vùng 2 là xem trước biểu mẫu như người dùng thấy: khoa, phòng; số ca trực trong quý với gợi ý tối thiểu 36 lấy từ danh mục vị trí. Vùng 3 là trường "Tỷ lệ hồ sơ bệnh án đạt chuẩn (%)" đang nhập giá trị 104 và hiển thị lỗi tức thời "Giá trị phải từ 0 đến 100 (kiểm tra tức thời, 0,04 ms)"; trường "Mô tả sự cố và biện pháp khắc phục" chỉ hiện khi "Sự cố y khoa" bằng Có và là trường C2. Vùng 4 là ngăn thuộc tính trường `ty_le_hsba_dat`: khoá dữ liệu, kiểu number, quy tắc CEL `self >= 0.0 && self <= 100.0`, điều kiện hiển thị của trường mô tả sự cố `form.su_co_y_khoa == "CO"`, thông báo lỗi. Vùng 5 là JSON Schema sinh ra kèm thuộc tính mở rộng `x-cel` và `x-classification`, và kết quả "Bộ test 24/24, cel-js = cel-java; dựng 50 trường: 31 ms".

Con số 0,04 ms mỗi lần kiểm tra và 31 ms để dựng 50 trường là số đo trên máy trạm phát triển (Chrome, CPU tầm trung) với bản dựng thử; ngân sách thiết kế là 50 ms cho 50 trường theo baseline G1.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Mã biểu mẫu, phiên bản | Nhãn | Không áp dụng | khoá, vN | Từ dữ liệu | Chỉ đọc | `config.form_schema.form_key`, `config.form_schema.version` |
| 2 | Khoá dữ liệu trường | Văn bản | Có | snake_case, ≤ 64 ký tự | Sinh từ nhãn | Duy nhất trong biểu mẫu; nếu là trường tính điểm thì phải khớp Policy Pack | `config.form_schema.schema_json` |
| 3 | Kiểu | Danh sách chọn | Có | string, number, date, enum, array, object, file, signature | Theo thành phần | Không có | `config.form_schema.schema_json` |
| 4 | Nhãn, gợi ý | Văn bản | Có | ≤ 200 ký tự | Trống | Không có | `config.form_schema.ui_json` |
| 5 | Bắt buộc | Công tắc | Có | Boolean | Không | Không có | `config.form_schema.schema_json` (required) |
| 6 | Quy tắc kiểm tra (CEL) | Mã CEL | Không | Biểu thức trả về bool, biến `self`, `form` | Trống | Kiểm tra kiểu bằng cel-js và cel-java | `config.form_schema.schema_json` (`x-cel`) |
| 7 | Điều kiện hiển thị (CEL) | Mã CEL | Không | Biểu thức trả về bool | Trống | Không tham chiếu vòng | `config.form_schema.ui_json` (hideExpression) |
| 8 | Thông báo lỗi | Văn bản | Có khi có quy tắc | ≤ 200 ký tự | Trống | Không có | `config.form_schema.ui_json` |
| 9 | Lớp dữ liệu | Danh sách chọn | Có | C1, C2 | C1 | Văn bản dài nhận xét mặc định C2 | `config.form_schema.schema_json` (`x-classification`) |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Kéo thành phần vào biểu mẫu | Phiên bản nháp | Thêm trường, sinh khoá dữ liệu | Phía client | Không có |
| Chạy bộ test biểu mẫu | Luôn | Chạy các ca test trên cel-js tại trình duyệt và cel-java tại máy chủ, so sánh | `POST /api/v1/ui/forms/{key}/versions/{v}/test-runs` | Không có |
| Lưu nháp | Phiên bản nháp | Lưu schema và ui | `PUT /api/v1/ui/forms/{key}/versions/{v}` | Không có |
| Đưa vào UI Pack v1.8 | Bộ test đạt | Gắn phiên bản biểu mẫu vào UI Pack nháp | `POST /api/v1/ui/packs/{id}/versions/{v}/forms` | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-18-06 | Biểu thức CEL không hợp lệ: {loi}. | Lỗi kiểm tra | Soạn quy tắc |
| MSG-18-07 | Trường {khoa} được Policy Pack {goi} dùng để tính điểm nên không thể đổi kiểu hoặc xoá. | Lỗi | Sửa trường tính điểm |
| MSG-18-08 | Ca test {ma} cho kết quả khác nhau giữa trình duyệt và máy chủ. | Lỗi | Hai engine khác nhau |

#### SCR-1803. Chế độ Đảng viên cao tuổi (di động)

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Cho đảng viên cao tuổi thực hiện các việc thường ngày (xác nhận sinh hoạt, tự đánh giá cuối năm) trên điện thoại với chữ lớn, tương phản cao, một câu hỏi mỗi màn hình và nhập bằng giọng nói |
| Tác nhân | AC-06 đảng viên ở chi bộ khu dân cư, đảng viên cao tuổi |
| Đường vào | Ứng dụng di động (SVC-18); bật theo tenant trong UI Pack hoặc theo từng người trong "Tôi › Hiển thị" |
| Quyền | Như chế độ thường |
| Thiết bị | Điện thoại (Flutter); web di động |

![Hình B3.17. SCR-1803 Chế độ Đảng viên cao tuổi trên di động: việc của tôi và tự đánh giá luồng rút gọn](SCR-1803.png){width=15cm}

Hai khung điện thoại minh hoạ hai màn hình của cùng một người dùng ở Chi bộ khu phố 3. Chế độ này không phải ứng dụng riêng: cùng dữ liệu, cùng API với chế độ thường, chỉ khác bộ token và cách trình bày luồng.

Vùng 1 là danh sách "Việc của tôi" với thẻ lớn, mỗi thẻ một việc và một nút chính chiếm toàn chiều ngang (ví dụ "Sinh hoạt chi bộ tháng 12, hạn thứ Năm 18/12, Đã tham gia"). Vùng 2 là thẻ "Tự đánh giá cuối năm, còn 5 ngày, 3 bước" với nút "Bắt đầu", và hai nút "Nói" (nhập bằng giọng nói tiếng Việt) và "Gọi hỗ trợ". Từ trang chủ tới hoàn thành một việc thường ngày cần không quá 3 chạm. Vùng 3 là màn hình tự đánh giá bước 2/3 ở luồng rút gọn: một câu hỏi lớn và hai lựa chọn dạng nút cao, ô ghi chú không bắt buộc có thể nhấn để nói; dòng chú thích "Chữ 19 px, tương phản 12,6 : 1, mỗi màn hình một câu hỏi". Vùng 4 là hai nút điều hướng lớn "Quay lại" và "Tiếp tục". Bên phải là chú thích thiết kế: token phụ "elder" (chữ cơ sở 17 đến 19 px, nút cao ≥ 48 px, viền đậm 2 px, tương phản ≥ 7 : 1) và cờ "ít bước" (một câu hỏi mỗi màn hình, bỏ trường không bắt buộc, nhập bằng giọng nói).

Luồng rút gọn tự đánh giá của đảng viên khu dân cư chỉ gồm 3 bước vì Policy Pack của khối này (PL 2E) có ít tiêu chí và không có nhiệm vụ then chốt; phiếu sau khi hoàn tất vẫn sinh ra cùng bản ghi `appraisal.self_assessment` như chế độ thường và đi tiếp QT-09.

**Bảng trường**

| STT | Tên trường | Kiểu | Bắt buộc | Định dạng/độ dài | Mặc định | Ràng buộc, kiểm tra | Nguồn dữ liệu |
|---|---|---|---|---|---|---|---|
| 1 | Danh sách việc | Thẻ | Không áp dụng | Tên, hạn, trạng thái | Việc trong tuần | Tối đa 5 thẻ trên trang đầu | `flowable.wf_task_projection`, `task.task` |
| 2 | Câu trả lời tiêu chí | Chọn một (nút lớn) | Có | Đầy đủ, chưa đầy đủ | Trống | Không có | `appraisal.general_criteria_result.met` |
| 3 | Ghi chú thêm | Văn bản hoặc giọng nói | Không | ≤ 1.000 ký tự | Trống | Nhận dạng giọng nói chạy tại chỗ; văn bản được hiển thị để xác nhận | `appraisal.self_assessment.content_enc` |
| 4 | Cỡ chữ, tương phản | Thiết lập người dùng | Không | Theo token elder | Theo tenant | Không thấp hơn 17 px | `notify.channel_pref` (mục hiển thị) hoặc hồ sơ người dùng trong `config.tenant_setting` |

**Bảng chức năng**

| Nút/Hành động | Điều kiện hiển thị | Xử lý | API | Sự kiện phát |
|---|---|---|---|---|
| Đã tham gia | Việc sinh hoạt chi bộ | Ghi xác nhận tham gia | `POST /api/v1/tasks/{id}/progress` | EVT-05 |
| Bắt đầu | Phiếu tự đánh giá mở | Mở luồng rút gọn | `GET /api/v1/appraisals/profiles/{id}` | Không có |
| Nói | Luôn | Ghi âm, chuyển văn bản tiếng Việt, hiển thị để xác nhận | `POST /api/v1/ai/speech-to-text` | Không có |
| Tiếp tục, Quay lại | Trong luồng | Lưu từng bước vào nháp cục bộ mã hoá và máy chủ | `PUT /api/v1/appraisals/profiles/{id}/self-assessment` | Không có |
| Gọi hỗ trợ | Luôn | Gọi số hỗ trợ của chi bộ hoặc tổng đài | Thiết bị | Không có |

**Thông báo**

| Mã | Nội dung | Loại | Khi nào |
|---|---|---|---|
| MSG-18-09 | Đã ghi nhận đồng chí tham gia sinh hoạt chi bộ tháng 12. | Thành công | Bấm "Đã tham gia" |
| MSG-18-10 | Hệ thống đã nghe: "{van_ban}". Đồng chí bấm "Đúng" để lưu hoặc "Nói lại". | Xác nhận | Sau nhận dạng giọng nói |

### Liên kết thiết kế

QT-18 dùng MOD-08 `config` (bảng `ui_pack`, `ui_pack_version`, `form_schema`, `feature_flag` và bảng đề xuất mới `component_registry`), SVC-17 web-frontend (`fe-shell`, `@vt/design-system`, `@vt/sdui-renderer`, `@vt/form-engine`, plugin `fe-plugin-yte` qua Native Federation), SVC-18 mobile-app (Flutter, token elder), CDN và vùng điều phối toàn cục (kho UI Pack gốc). Sự kiện: EVT-23 UiPackDaPhatHanh, EVT-21. Liên quan ADR-05 (G1, bốn lớp), ADR-10 (Angular, Flutter), ADR-16 (ký bản dựng plugin), NFR-07 (≤ 1 ngày), NFR-11 (WCAG 2.1 AA, chế độ cao tuổi). Đáp ứng góp ý G1 bằng quy trình cụ thể: phần tenant cấu hình được (token, bố cục bề mặt cấu hình được, biểu mẫu, plugin) và phần không cấu hình được (màn hình lõi viết mã, công thức tính điểm) đều được nêu rõ trên SCR-1801.

# Ma trận truy vết

Chương này nối các quy trình và màn hình của TL-02 với phần còn lại của bộ tài liệu theo chuỗi truy vết bắt buộc của baseline mục 1: quy trình (QT) đến màn hình (SCR), đến API và module hoặc service (MOD, SVC trong TL-01), đến bảng dữ liệu và sự kiện (TL-03), rồi đến thành phần triển khai, chỉ tiêu năng lực và biện pháp an toàn thông tin (TL-04). Chương gồm ba bảng. Bảng thứ nhất truy vết toàn bộ 19 quy trình QT-00 đến QT-18. Bảng thứ hai đối chiếu các tiêu chí nhóm H (phạm vi chức năng) và nhóm I (toàn vẹn, chống chối bỏ) của bản đánh giá kiến trúc với quy trình và màn hình đáp ứng. Bảng thứ ba đối chiếu tám nhóm tính năng trong PL A1, A2 của báo cáo BGĐ.

![Hình B4.1. Chuỗi truy vết từ quy trình tới triển khai trong bộ tài liệu TL-01 đến TL-04](nv-b-truyvet.png){width=16cm}

Hình trên tóm tắt chiều đọc của các bảng. Từ một quy trình, người đọc tìm được màn hình thực hiện nó; từ màn hình tìm được API (tiền tố `/api/v1/<module>`) và module, service xử lý; từ module tìm được bảng dữ liệu và sự kiện; từ bảng và sự kiện tìm được thành phần triển khai, kịch bản thử tải và biện pháp an toàn thông tin trong TL-04. Chiều ngược lại dùng khi đánh giá ảnh hưởng của một thay đổi: sửa một bảng dữ liệu thì biết những màn hình và quy trình nào bị ảnh hưởng.

## Truy vết quy trình, màn hình, API, dữ liệu và sự kiện

Với QT-00 đến QT-08, thông tin được lấy từ baseline mục 5.1 (module), mục 7 (sự kiện), mục 9 (bảng), mục 11 (màn hình) và suy luận theo quy ước API mục 13; chi tiết thuộc phạm vi TL-02a. Với QT-09 đến QT-18, thông tin khớp với các bảng chức năng ở hai chương trước. Cột API chỉ liệt kê nhóm đường dẫn chính, danh sách đầy đủ nằm trong đặc tả OpenAPI 3.1 của TL-01.

| QT | Màn hình | API chính | Bảng dữ liệu chính | Sự kiện | MOD / SVC |
|---|---|---|---|---|---|
| QT-00 Đăng nhập, chọn đơn vị và vai trò | SCR-0001, SCR-0002 | OIDC của Keycloak; `/api/v1/org/me`, `/api/v1/tasks/my` | bảng Keycloak trong `iam`; `org_replica.assignment_v`; `flowable.wf_task_projection` | Không có | SVC-01, SVC-02, SVC-17, SVC-18, MOD-02 |
| QT-01 Khởi tạo tenant, cây tổ chức, đồng bộ nhân sự | SCR-0101, SCR-0102, SCR-0103, SCR-0104 | `/api/v1/config/tenants`, `/api/v1/org/units`, `/api/v1/org/sync` | `registry.tenant`, `registry.tenant_cell`, `org.org_unit`, `org.person`, `org.assignment`, `org.scim_sync_log`, `integ.mapping_xref` | EVT-14, EVT-15, EVT-16 | SVC-03, SVC-04, SVC-13, MOD-08 |
| QT-02 Lập và phê duyệt mục tiêu, OKR | SCR-0201, SCR-0202, SCR-0203 | `/api/v1/okr/objectives`, `/api/v1/okr/key-results`, `/api/v1/okr/links` | `okr.basis`, `okr.objective`, `okr.key_result`, `okr.okr_link`, `flowable.wf_binding` | EVT-01 | MOD-01, SVC-06, SVC-14 |
| QT-03 Check-in KR | SCR-0301, SCR-0302 | `/api/v1/okr/key-results/{id}/checkins` | `okr.kr_checkin`, `okr.key_result` | EVT-02 | MOD-01, SVC-18, SVC-15 |
| QT-04 Lập và phê duyệt kế hoạch công tác quý | SCR-0401, SCR-0402, SCR-0403 | `/api/v1/catalog`, `/api/v1/plans`, `/api/v1/plans/{id}/versions` | `task.work_catalog`, `task.catalog_item`, `task.plan`, `task.plan_version`, `task.plan_item` | EVT-03 | MOD-02, SVC-06, SVC-10 |
| QT-05 Giao việc, đăng ký nhiệm vụ, nhận từ văn bản | SCR-0501, SCR-0502, SCR-0503, SCR-0504 | `/api/v1/tasks`, `/api/v1/tasks/{id}/assignees`, `/api/v1/tasks/from-document` | `task.task`, `task.task_assignee`, `task.task_source_doc`, `integ.inbox_message` | EVT-04, EVT-24 | MOD-02, SVC-13, SVC-14, SVC-16 |
| QT-06 Cập nhật tiến độ, điểm nghẽn, gia hạn | SCR-0601, SCR-0602 | `/api/v1/tasks/{id}/progress`, `/api/v1/tasks/{id}/blockers`, `/api/v1/tasks/{id}/change-requests` | `task.task_progress`, `task.task_blocker`, `task.task_change_request`, `task.task_state_log` | EVT-05, EVT-06 | MOD-02, SVC-06, SVC-12 |
| QT-07 Nộp và nghiệm thu sản phẩm, minh chứng | SCR-0701, SCR-0702, SCR-0703 | `/api/v1/files`, `/api/v1/evidences`, `/api/v1/products/{id}/acceptance` | `product.deliverable`, `product.evidence`, `product.evidence_version`, `product.acceptance`, `evidence.object_meta`, `evidence.dedup_index`, `evidence.scan_result` | EVT-07, EVT-08 | MOD-03, SVC-08, SVC-14 (OCR), SVC-07 |
| QT-08 Khoá kỳ (saga chốt kỳ) | SCR-0801 | `/api/v1/periods`, `/api/v1/periods/{id}/lock`, `/api/v1/jobs/{id}` | `config.period`, `config.holiday_calendar`, `scoring.score_job` | EVT-09 | MOD-08, SVC-06 (saga), SVC-07, SVC-09 |
| QT-09 Tự đánh giá, thẩm định, đề xuất, quyết định quý | SCR-0901, SCR-0902, SCR-0903, SCR-0904 | `/api/v1/appraisals/profiles`, `/api/v1/appraisals/batch-decisions`, `/api/v1/esign/requests` | `appraisal.appraisal_profile`, `appraisal.general_criteria_result`, `appraisal.self_assessment`, `appraisal.score_snapshot`, `appraisal.rating_proposal`, `appraisal.rating_explanation`, `appraisal.batch_decision`, `scoring.score_trace` | EVT-09, EVT-10, EVT-11, EVT-12, EVT-18, EVT-21 | MOD-04, SVC-07, SVC-06, SVC-10, SVC-11 |
| QT-10 Xếp loại năm, liên thông, phân nhóm | SCR-1001, SCR-1002 | `/api/v1/appraisals/annual`, `/api/v1/appraisals/prospect-groups` | `appraisal.annual_rating`, `appraisal.prospect_group`, `appraisal.score_snapshot` | EVT-10, EVT-11, EVT-12, EVT-04 | MOD-04, SVC-07, MOD-02 |
| QT-11 Kiểm điểm và hồ sơ số | SCR-1101, SCR-1102 | `/api/v1/dossiers/meetings`, `/api/v1/dossiers/forms`, `/api/v1/dossiers/packages` | `dossier.review_meeting`, `dossier.review_comment`, `dossier.form_instance`, `dossier.special_case`, `dossier.dossier_package`, `dossier.remediation_item`, `docgen.render_job` | EVT-12, EVT-17, EVT-18, EVT-21 | MOD-06, SVC-11, SVC-10, SVC-13 |
| QT-12 Khiếu nại, giải trình | SCR-1201, SCR-1202 | `/api/v1/oversight/complaints` | `oversight.complaint`, `oversight.complaint_response` | EVT-25, EVT-18, EVT-21 | MOD-07, SVC-06, SVC-07, SVC-11 |
| QT-13 Hậu kiểm, mở lại kỳ hai người duyệt | SCR-1301, SCR-1302 | `/api/v1/oversight/reopen-requests`, `/api/v1/ledger/entries`, `/api/v1/ledger/verify-runs` | `oversight.reopen_request`, `oversight.reopen_approval`, `oversight.post_audit`, `ledger.ledger_entry`, `ledger.merkle_seal`, `ledger.anchor_receipt`, `ledger.verify_run` | EVT-22, EVT-19, EVT-20, EVT-09, EVT-10 | MOD-07, MOD-08, SVC-09, SVC-02, SVC-06 |
| QT-14 Cảnh báo bất thường | SCR-1401 | `/api/v1/oversight/alerts`, `/api/v1/config/anomaly-thresholds` | `oversight.anomaly_alert`, `oversight.alert_action`, `evidence.dedup_index` | EVT-20, EVT-21 | SVC-15, MOD-07, SVC-08 |
| QT-15 Soạn, kiểm thử, phát hành Policy Pack | SCR-1501, SCR-1502, SCR-1503, SCR-1504 | `/api/v1/policies/packs`, `/api/v1/policies/test-cases`, `/api/v1/policies/workflow-templates` | `policy.policy_pack`, `policy.policy_pack_version`, `policy.policy_rule`, `policy.decision_table`, `policy.golden_test_case`, `policy.test_run`, `policy.policy_binding`, `flowable.wf_template`, `flowable.wf_binding` | EVT-13, EVT-18, EVT-21 | MOD-05, SVC-07, SVC-06, SVC-10 |
| QT-16 Bảng điều hành và báo cáo | SCR-1601, SCR-1602, SCR-1603 | `/api/v1/analytics/query`, `/api/v1/analytics/dashboards`, `/api/v1/analytics/trace` | ClickHouse `analytics_<cell>`; `config.dashboard_def`; bảng nguồn của MOD-01 đến MOD-04 | EVT-04, EVT-21 (phát); mọi EVT nghiệp vụ là nguồn CDC | SVC-15, SVC-14, MOD-02 |
| QT-17 Trợ lý AI | SCR-1701 | `/api/v1/ai/assist/okr`, `/api/v1/ai/suggestions` | `ai.doc_chunk`, `ai.suggestion_log`, `ai.guardrail_event`, `okr.objective`, `okr.key_result` | EVT-24 (đầu vào), EVT-21 | SVC-14, MOD-01, SVC-02 |
| QT-18 Cấu hình giao diện tenant | SCR-1801, SCR-1802, SCR-1803 | `/api/v1/ui/packs`, `/api/v1/ui/forms`, `/api/v1/ui/components` | `config.ui_pack`, `config.ui_pack_version`, `config.form_schema`, `config.feature_flag`, `config.component_registry` | EVT-23, EVT-21 | MOD-08, SVC-17, SVC-18 |

Bảng cho thấy hai đặc điểm của thiết kế. Thứ nhất, mỗi quy trình có một module chủ sở hữu duy nhất trong mission-core (hoặc một service với QT-16, QT-17), còn các service khác chỉ tham gia qua API công bố hoặc sự kiện; điều này khớp với ranh giới module của Spring Modulith (ADR-01) và giúp ArchUnit kiểm tra được phụ thuộc. Thứ hai, các sự kiện EVT-09, EVT-10, EVT-12, EVT-18 là "xương sống" nối các quy trình đánh giá: khoá kỳ (QT-08) kích hoạt tính điểm, tính điểm mở phiếu (QT-09), quyết định kích hoạt hồ sơ số (QT-11), ký số hoàn tất từng mốc. Các bảng có tên in trong cột dữ liệu mà không có trong baseline mục 9 (`appraisal.batch_decision`, `dossier.remediation_item`, `config.component_registry`, `config.dashboard_def`) là đề xuất mới, được liệt kê trong tệp ghi chú để TL-03 xác nhận.

## Đối chiếu tiêu chí của bản đánh giá kiến trúc

Bảng dưới đối chiếu chín tiêu chí nhóm H (phạm vi chức năng nền tảng, ISO/IEC 25010 Functional Suitability) và tám tiêu chí nhóm I (toàn vẹn và chống chối bỏ) với quy trình, màn hình đáp ứng. Cột "Hiện trạng" lấy từ bản đánh giá; cột "Cách đáp ứng" tóm tắt biện pháp trong TL-02, phần kiến trúc chi tiết nằm ở TL-01 và TL-03.

| Mã | Tiêu chí | Hiện trạng theo bản đánh giá | Quy trình | Màn hình | Cách đáp ứng |
|---|---|---|---|---|---|
| H1 | Mục tiêu và OKR | Chưa đạt | QT-02, QT-03, QT-17 | SCR-0201, SCR-0202, SCR-0203, SCR-0301, SCR-0302, SCR-1701 | Căn cứ, mục tiêu O (cảnh báo khi trên 3), KR có chỉ tiêu, trọng số, người xác nhận; phân rã ba tầng; check-in có mức tự tin; AI soát KR mơ hồ, trùng |
| H2 | Giao việc và KPI | Một phần | QT-04, QT-05, QT-06 | SCR-0401 đến SCR-0403, SCR-0501 đến SCR-0504, SCR-0601, SCR-0602 | Một chủ trì nhiều phối hợp; danh mục có sản phẩm, đơn vị tính, mức khó; kế hoạch có phiên bản; cảnh báo sớm chậm |
| H3 | Sản phẩm, minh chứng | Một phần | QT-07, QT-14 | SCR-0701 đến SCR-0703, SCR-1401 | Minh chứng có phiên bản; tách thực hiện và nghiệm thu; chất lượng 5 mức; chỉ `DA_XAC_NHAN` được tính (BR-09-02); cảnh báo trùng mã băm (dấu hiệu 4) |
| H4 | Đánh giá, xếp loại | Một phần | QT-09, QT-10, QT-15 | SCR-0901 đến SCR-0904, SCR-1001, SCR-1501, SCR-1502 | Một bộ máy tính duy nhất (SVC-07) theo Policy Pack; kỳ quý và năm; liên thông quý–năm; quy tắc người đứng đầu; lưu 4 mức; câu giải thích hạ mức |
| H5 | Kiểm điểm, hồ sơ số | Chưa đạt | QT-11 | SCR-1101, SCR-1102 | Sinh Mẫu 1A đến 1D, 2, 3 từ dữ liệu; ký số; 13 trường hợp đặc thù; đóng gói quý, năm; chuyển hồ sơ diện TW |
| H6 | Giám sát, hậu kiểm | Một phần | QT-12, QT-13, QT-14 | SCR-1201, SCR-1202, SCR-1301, SCR-1302, SCR-1401 | Nhật ký không sửa được; khoá, mở kỳ hai người duyệt; khiếu nại, giải trình, bảo lưu; hậu kiểm; tám dấu hiệu bất thường |
| H7 | Phân tích, dashboard | Một phần | QT-16, QT-10 | SCR-1601, SCR-1602, SCR-1603, SCR-1002 | Dashboard tự dựng trên lớp ngữ nghĩa Cube; đi xuống tới minh chứng; so sánh nhiều kỳ; phân nhóm triển vọng năm |
| H8 | Quản trị nền tảng | Một phần | QT-01, QT-15, QT-18 | SCR-0101 đến SCR-0104, SCR-1501 đến SCR-1504, SCR-1801 đến SCR-1803 | Đa khách thuê; cây tổ chức có lịch sử; tách vai trò (OPA); Config Studio, Policy Pack có phiên bản và test case; UI Pack |
| H9 | Hai luồng Đảng, Nhà nước | Một phần | QT-09, QT-15 | SCR-0901, SCR-0903, SCR-1501 | Hai snapshot cho cùng người, cùng kỳ, cùng dữ liệu sản phẩm; Policy Pack Đảng (100 điểm) và Nhà nước (30/70) độc lập (BR-09-11) |
| I1 | Nhật ký thay đổi đầy đủ | Chưa đạt | QT-13 và mọi quy trình ghi | SCR-1302 | Outbox ghi sổ cái cùng giao dịch; giá trị trước, sau, lý do, người làm, người duyệt, thời điểm (BR-13-07) |
| I2 | Sổ cái chỉ ghi, chuỗi băm, niêm phong | Chưa đạt | QT-13 | SCR-1302 | 64 phân vùng chuỗi mỗi tenant; niêm phong Merkle mỗi giờ; công cụ kiểm tra chỉ ra bản ghi bị phá vỡ; CLI kiểm tra độc lập |
| I3 | Khoá kỳ, mở lại cần hai người duyệt | Chưa đạt | QT-08, QT-13 | SCR-0801, SCR-1301 | Kỳ `DA_KHOA` chặn mọi ghi; mở lại cần hai phê duyệt khác người, có MFA bước lên, trong phạm vi và thời hạn |
| I4 | Ký số tại mốc pháp lý | Chưa đạt | QT-04, QT-09, QT-11, QT-12, QT-15 | SCR-0403, SCR-0901, SCR-0902, SCR-0904, SCR-1102, SCR-1202, SCR-1503 | Ký kế hoạch, phiếu tự đánh giá, thẩm định, quyết định (ký lô), gói hồ sơ, văn bản trả lời khiếu nại, phát hành Policy Pack |
| I5 | Minh chứng bất biến có mã băm | Chưa đạt | QT-07, QT-11 | SCR-0701, SCR-1102, SCR-1603 | SHA-256 khi tải lên; Ceph Object Lock; phiên bản mới không xoá bản cũ; manifest gói có băm từng tệp |
| I6 | Snapshot gắn phiên bản quy tắc | Một phần | QT-09, QT-10, QT-13, QT-15 | SCR-0903, SCR-1001, SCR-1301, SCR-1503 | `score_snapshot` có `policy_version`, `input_hash`, vết tính; tính lại bằng đúng phiên bản; kỳ đã khoá không bị tính lại bằng phiên bản mới |
| I7 | Dữ liệu hai trục thời gian | Chưa đạt | QT-01, QT-09 | SCR-0103 | Cây tổ chức, phân công lưu `valid_from/valid_to` và `recorded_from/recorded_to`; hồ sơ đánh giá đọc tổ chức tại ngày khoá kỳ |
| I8 | Dấu thời gian tin cậy | Chưa đạt | QT-11, QT-13 | SCR-1102, SCR-1302 | Dấu thời gian RFC 3161 trên chữ ký gói hồ sơ và niêm phong Merkle; bản neo gửi bên thứ ba |

Tất cả 17 tiêu chí H và I đều có ít nhất một quy trình và một màn hình đáp ứng. Các tiêu chí ở mức "Chưa đạt" của bản đánh giá (H1, H5, I1 đến I5, I7, I8) được giải quyết chủ yếu bằng các quy trình mới QT-02, QT-11, QT-13 và bằng các quy tắc nền tảng không cấu hình được (BR-09-12 đến BR-09-16, BR-13-01 đến BR-13-12).

## Đối chiếu nhóm tính năng của báo cáo BGĐ

PL A1 và A2 của báo cáo BGĐ liệt kê tám nhóm tính năng với căn cứ trong bộ dự thảo. Bảng dưới đối chiếu từng tính năng với quy trình và màn hình; tính năng được gom theo dòng như trong phụ lục.

| Nhóm | Tính năng (theo PL A1, A2) | Căn cứ | Quy trình | Màn hình |
|---|---|---|---|---|
| 1. Mục tiêu, OKR | Quản lý căn cứ; mục tiêu O khuyến nghị 1 đến 3 mỗi kỳ; KR có chỉ tiêu, trọng số, hạn, người xác nhận | HD 1b–c; PL 1 3.2a | QT-02 | SCR-0201, SCR-0202 |
| 1. Mục tiêu, OKR | Phân rã ba tầng nhiều lớp; mục tiêu con phải có căn cứ cấp trên | PL 1 mục 3 | QT-02 | SCR-0201 |
| 1. Mục tiêu, OKR | Truy vết hai chiều từ căn cứ đến O, KR, nhiệm vụ; liên kết ngang | PL 4 III.2 | QT-02, QT-16 | SCR-0201, SCR-1603 |
| 1. Mục tiêu, OKR | Check-in KR có mức tự tin; AI kiểm tra KR trùng nhiệm vụ | HD 1c; đề xuất | QT-03, QT-17 | SCR-0301, SCR-0302, SCR-1701 |
| 2. Giao việc, KPI | Kế hoạch theo Bảng 1: một chủ trì, nhiều phối hợp | PL 1 mục 2; QĐ 3.3 | QT-04, QT-05 | SCR-0402, SCR-0501 |
| 2. Giao việc, KPI | Danh mục công việc theo Bảng 3 | PL 1 Bảng 2–3 | QT-04 | SCR-0401 |
| 2. Giao việc, KPI | Phân loại thường xuyên hay then chốt; chặn tính điểm trùng | HD 1e; PL 3 | QT-04, QT-09 | SCR-0402, SCR-0901 |
| 2. Giao việc, KPI | Duyệt kế hoạch trước tuần 2; điều chỉnh có phiên bản; chặn KPI mơ hồ | QĐ 12.1.1; HD 1g | QT-04, QT-06, QT-17 | SCR-0403, SCR-0602, SCR-1701 |
| 2. Giao việc, KPI | Cập nhật tiến độ; cảnh báo sớm; sổ điểm nghẽn | QĐ 12.1.2; PL 4 | QT-06, QT-16 | SCR-0601, SCR-1601 |
| 3. Sản phẩm, minh chứng | Minh chứng có phiên bản; tách thực hiện và nghiệm thu | PL 4 II.2 | QT-07 | SCR-0701, SCR-0702 |
| 3. Sản phẩm, minh chứng | Nghiệm thu chất lượng 5 mức; ghi nguyên nhân khách quan | PL 3 mục 2 | QT-07 | SCR-0703 |
| 3. Sản phẩm, minh chứng | Chưa xác nhận thì không tính điểm; cảnh báo minh chứng trùng | PL 4 III.5 | QT-07, QT-09, QT-14 | SCR-0703, SCR-0901, SCR-1401 |
| 4. Đánh giá, xếp loại | Bộ máy KPI (A+B+C)/3, tỷ trọng nhóm cấu hình được, làm tròn | PL 3; Bảng chấm điểm | QT-09, QT-15 | SCR-0901, SCR-0903, SCR-1501 |
| 4. Đánh giá, xếp loại | Tiêu chí chung 4 mức theo tỷ lệ; mức cuối là mức thấp hơn | QĐ 10–11; PL 6 §12 | QT-09, QT-15 | SCR-0901, SCR-1502 |
| 4. Đánh giá, xếp loại | Điều kiện loại trừ; quy tắc người đứng đầu | QĐ 11.2.4, 14.3.3 | QT-09, QT-15 | SCR-0904, SCR-1502 |
| 4. Đánh giá, xếp loại | Liên thông quý và năm | QĐ 15.5–15.6 | QT-10 | SCR-1001 |
| 4. Đánh giá, xếp loại | Lưu riêng 4 mức; bắt buộc giải trình khi khác | QĐ 16.3; Mẫu 3 | QT-09 | SCR-0901 đến SCR-0904 |
| 5. Kiểm điểm, hồ sơ số | Sinh Mẫu 1A–1D, 2, 3, bảng chấm điểm; ký số, dấu thời gian | Các Mẫu; PL 4 V.3 | QT-11 | SCR-1102 |
| 5. Kiểm điểm, hồ sơ số | Gửi dự thảo ≥ 03 ngày làm việc; góp ý; biên bản; theo dõi khắc phục | QĐ Đ6–7; HD 2–4 | QT-11 | SCR-1101 |
| 5. Kiểm điểm, hồ sơ số | 13 trường hợp đặc thù | PL 6 | QT-11, QT-09 | SCR-1102 |
| 5. Kiểm điểm, hồ sơ số | Đóng gói quý, năm; chuyển hồ sơ diện TW | QĐ Đ17; HD 10 | QT-11 | SCR-1102 |
| 6. Giám sát, hậu kiểm | Nhật ký không sửa được; khoá và mở kỳ có phê duyệt | PL 4 III.2, V.2 | QT-08, QT-13 | SCR-0801, SCR-1301, SCR-1302 |
| 6. Giám sát, hậu kiểm | Khiếu nại, giải trình, bảo lưu; trả lời bằng văn bản | QĐ 16.1–16.2 | QT-12 | SCR-1201, SCR-1202 |
| 6. Giám sát, hậu kiểm | Hậu kiểm theo kế hoạch, đột xuất, theo phản ánh | QĐ 15.3 | QT-13 | SCR-1301 |
| 6. Giám sát, hậu kiểm | Phát hiện 8 dấu hiệu bất thường | PL 4 V.6 | QT-14 | SCR-1401 |
| 7. Phân tích, dashboard | Dựng dashboard trên lớp ngữ nghĩa; đi xuống tới minh chứng; so sánh nhiều kỳ | Đề xuất | QT-16 | SCR-1601, SCR-1602, SCR-1603 |
| 7. Phân tích, dashboard | Phân nhóm triển vọng năm, xếp thứ tự, kế hoạch đào tạo; khoá ở kỳ quý | PL 5; QĐ 12.1.5 | QT-10 | SCR-1002 |
| 7. Phân tích, dashboard | Theo dõi 05 chỉ tiêu KT-XH; chỉ tính chỉ tiêu đã công bố | QĐ 10.2.2 | QT-16, QT-09 | SCR-1601, SCR-1502 |
| 8. Quản trị nền tảng | Đa khách hàng, cây tổ chức có lịch sử, 9 khối, 40 chức danh diện TW | PL 2A–2E | QT-01 | SCR-0101 đến SCR-0104 |
| 8. Quản trị nền tảng | Tách vai trò khởi tạo, giao, xác nhận, nghiệm thu, thẩm định, quyết định, khoá kỳ | PL 4 V.1 | QT-09, QT-12, QT-13 (OPA) | SCR-0902, SCR-1202, SCR-1301 |
| 8. Quản trị nền tảng | Config Studio: công thức, biểu mẫu, luồng duyệt, Policy Pack có phiên bản và test case | PL 4 VI.5 | QT-15, QT-18 | SCR-1501 đến SCR-1504, SCR-1802 |
| 8. Quản trị nền tảng | Tích hợp văn bản, CSDL CBCCVC, VNeID, ký số, LGSP | PL 4 V.3; NĐ 233 | QT-00, QT-01, QT-05, QT-11 | SCR-0001, SCR-0104, SCR-0502, SCR-1102 |
| 8. Quản trị nền tảng | Kênh web, di động, thông báo đẩy, trợ lý AI tiếng Việt | Đề xuất | QT-03, QT-05, QT-17, QT-18 | SCR-0302, SCR-0504, SCR-1701, SCR-1803 |

Mọi dòng tính năng trong PL A1, A2 đều được ít nhất một quy trình đáp ứng. Bốn điểm chưa thống nhất của dự thảo nêu ở PL A4 (cách tính chất lượng, ngày lịch hay ngày làm việc, cách hiểu điều kiện 30%, phân nhóm ở kỳ quý) không được viết cứng mà trở thành tham số của Policy Pack (BR-15-02, BR-15-03, BR-15-04, BR-10-07), nên khi có hướng dẫn chính thức chỉ cần phát hành phiên bản Policy Pack mới theo QT-15 trong thời hạn NFR-07.

# Phụ lục. Cập nhật sau triển khai đợt 1 (10/2026) {-}

Phụ lục này ghi lại những điểm mà quy trình, màn hình và quy tắc nghiệp vụ đã được hiện thực khác với TL-02 phiên bản 1.0, tính đến mã trên nhánh `main` ngày 23/10/2026. Nguồn đối chiếu là mã nguồn, bộ hợp đồng `contracts/openapi/`, ma trận phân quyền `docs/phan-quyen/ma-tran.json` và trang wiki 14. Cách ghi trạng thái giống Phụ lục D của TL-01: "Đã chốt" là đã có quyết định của người phụ trách, "Theo mã, chờ xác nhận" là phương án mặc định mà mã đang chạy, "Câu hỏi mở" là việc chưa có câu trả lời. Khi TL-02 và TL-01 mâu thuẫn, phụ lục ghi theo mã và nêu rõ; trong đợt này, phần lớn mâu thuẫn được giải quyết theo TL-02, vì TL-02 mô tả quy trình ở mức chi tiết hơn.

## Quy ước chung của đặc tả {-}

Hai quy ước chung thay đổi cách đọc các bảng sự kiện của từng màn hình. Thứ nhất, theo ADR-32 (TL-01 Phụ lục F), cột "API" trong bảng sự kiện SCR nay được hiểu theo `operationId` của bộ hợp đồng; đường dẫn ghi trong TL-02 chỉ còn giá trị minh hoạ. Khi đường dẫn trong TL-02 khác hợp đồng, hợp đồng thắng; ví dụ quyết định với gợi ý AI là `POST /ai/suggestions/{id}/decision` với giá trị `CHAP_NHAN`, `SUA`, `BO_QUA`, không phải hai thao tác `/accept`, `/dismiss`. Thứ hai, văn bản hiển thị trên màn hình phải là nhãn nghiệp vụ tiếng Việt: mockup và đặc tả không được in mã SCR, QT, BR, MSG, mã trạng thái thô hay tên trường lên giao diện. Quy định chi tiết và bảng từ thay thế nằm ở trang wiki 16; ví dụ nhiệm vụ có nguồn `DOT_XUAT` hiển thị là "Phát sinh", mức dự báo trễ hạn `VUA` hiển thị là "Trung bình".

## Mã định danh bổ sung {-}

Đợt triển khai cấp thêm một số mã trong dải của TL-02a cho chức năng quản trị vai trò và uỷ quyền (WEB-058, CORE-08-16). Các mã này không thay nghĩa mã cũ.

| Loại | Mã mới | Nội dung |
|---|---|---|
| Màn hình | SCR-0105, SCR-0106, SCR-0107 | Vai trò theo đơn vị (cấp, duyệt thứ hai, thu hồi); hồ sơ quyền của một người; uỷ quyền khi vắng mặt |
| Quy tắc | BR-01-13 đến BR-01-18 | Ràng buộc cấp, duyệt thứ hai, xác thực bước cao (BR-01-15), thu hồi và uỷ quyền; nội dung đầy đủ ở `docs/phan-quyen/de-xuat-quan-tri-vai-tro.md` và trang wiki 15a |
| Thông báo | MSG-01-16 đến MSG-01-18 | Thông điệp của ba màn hình trên, theo cùng tài liệu đề xuất |
| Sự kiện | EVT-27 `VaiTroDaThayDoi` | Phát khi cấp, duyệt, từ chối, thu hồi, hết hạn vai trò và uỷ quyền |
| Màn hình (BS-01) | SCR-1505, SCR-1506 | Thư viện, trình soạn mẫu luồng và giám sát tiến trình theo mẫu (nội bộ nền tảng), đặc tả ở TL-01 Phụ lục E |

## Phần A: QT-00 đến QT-08 {-}

Bảng dưới đây liệt kê các điểm khác thiết kế của phần A. Điểm quan trọng nhất là saga khoá kỳ: TL-01 chương G2 liệt kê năm bước (khoá kỳ, tính điểm hàng loạt, sinh Mẫu 1A đến 3, ký lô, mở khoá có điều kiện), còn TL-02a QT-08 đặc tả sáu bước có bước kiểm tra tiền điều kiện ở đầu và bước hoàn tất ở cuối. Mã MOD-08 (`LockStep`) và màn hình SCR-0801 theo đúng sáu bước của TL-02a, và TL-01 đã được sửa cho khớp.

| Quy trình, màn hình | TL-02 ghi | Hiện thực trên main | Trạng thái |
|---|---|---|---|
| QT-00, SCR-0001 | Xác thực bước cao khi thao tác nhạy cảm | Hiệu lực 15 phút sau lần xác thực `aal2` (BR-00-02); thiếu thì trả mã yêu cầu xác thực bước cao để giao diện mở lại hộp xác thực | Theo mã |
| QT-01, bước 4 | Saga cấp phát do SVC-03 khởi tạo, SVC-06 điều phối | Saga cấp phát chạy ngay trong tenant-registry (SVC-03), trạng thái lưu ở `registry.job`; sự kiện `sys.tenant.provisioned.v1` phát khi tenant chuyển sang đang cấu hình | Theo mã, chờ xác nhận |
| QT-01, bước 7 | SVC-13 đồng bộ SCIM 2.0 từ IdP | SCIM 2.0 đặt ở org-service (SVC-04); SVC-13 chỉ đồng bộ CSDL CBCCVC, CSDL đảng viên và tệp lô qua cổng nội bộ của org-service | Đã chốt 22/10/2026 |
| QT-01, SCR-0101 | Ngưỡng mô hình bridge từ 5.000 tới 1.000.000 tài khoản | Giữ ngưỡng của TL-02a (tham số `TENANT_BRIDGE_MAX`, mặc định 1.000.000), khác con số 150.000 ở TL-01 | Theo mã, chờ xác nhận |
| QT-01, SCR-0104 | Bốn cách xử lý lệch dữ liệu | Hợp đồng có năm hành động gợi ý và bốn cách xử lý; giao diện hiện đủ năm | Theo mã |
| QT-02, SCR-0203 | Duyệt bộ mục tiêu một lần | Duyệt hàng loạt chạy nền (202), kết quả từng mục tiêu trả về trong danh sách lỗi của tác vụ | Theo mã |
| QT-02, SCR-0201 | KR nháp đủ chỉ tiêu và hạn | KR ở trạng thái nháp cho phép thiếu chỉ tiêu và hạn; kiểm đủ khi trình duyệt | Theo mã |
| QT-02 | Tầng OKR (cá nhân, đơn vị, cấp trên) | Chưa chốt cách phân tầng hiển thị | Câu hỏi mở |
| QT-04, BR-04-01 | Hạn duyệt kế hoạch quý lưu ở cấu hình kỳ | Tính khi đọc theo quy tắc `NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2` của Policy Pack; dấu "duyệt muộn" được tính, không lưu cột; quá hạn không chặn duyệt | Theo mã, chờ xác nhận |
| QT-04, gửi duyệt | Tiến trình `duyet-ke-hoach` | Tiến trình tên `duyet-ke-hoach-quy` (theo SCR-1504 và hợp đồng), khởi tạo ngay khi gửi duyệt, không đợi EVT-03; nội dung phiên bản chờ duyệt được khoá bằng trạng thái và mã băm SHA-256 | Theo mã, chờ xác nhận |
| QT-04, dòng kế hoạch | Có tên sản phẩm | Thêm cột `plan_item.product_name` vào TL-03 để lưu `productName` của hợp đồng | Đã chốt 23/10/2026, chờ hiện thực |
| QT-05, SCR-0503 | Năm loại đề nghị điều chỉnh | Giữ năm loại `GIA_HAN`, `DOI_SO_LUONG`, `DOI_SAN_PHAM`, `TAM_DUNG`, `HUY`; đề nghị không có bản nháp, gửi là chờ duyệt; mỗi quyết định bắt buộc ghi nguyên nhân và lý do | Theo mã |
| QT-05, giao lại | Đề nghị đổi người chủ trì | Thao tác giao lại trực tiếp của người giao, có lưu vết; consumer EVT-15 đánh dấu nhiệm vụ cần giao lại khi người chủ trì chuyển công tác | Theo mã, chờ xác nhận |
| QT-06, SCR-0601 | Dự báo nguy cơ trễ hạn | Nhịp thực hiện lấy theo 5 ngày làm việc gần nhất; ngưỡng Thấp khi n/m ≤ 0,8, Trung bình khi đến 1,0, Cao khi vượt 1,0 (suy luận, tham số dịch vụ); kết quả ghi qua cổng `delay-risk` của MOD-02 | Theo mã, chờ xác nhận |
| QT-07, SCR-0701 | Một tệp mỗi phiên bản minh chứng | Tối đa 20 tệp mỗi phiên bản; tệp trùng mã băm với minh chứng khác được đánh dấu nghi trùng; tệp bị cách ly do mã độc không cho tải | Theo mã |
| QT-07, SCR-0703 | Nghiệm thu thay | Đã hiện thực theo uỷ quyền; thêm cột `acceptance.on_behalf_of`, `delegation_id` để màn hình và báo cáo hiện nhãn "nghiệm thu thay" | Đã chốt 23/10/2026, chờ hiện thực |
| QT-08, bước 1 | Chuyển kỳ sang `DANG_KHOA` và phát EVT-09 | EVT-09 "Kỳ đã khoá" phát khi kỳ đã `DA_KHOA` (bước hoàn tất), đúng nghĩa tên sự kiện; việc chuyển `MO` sang `DANG_KHOA` được báo bằng dòng outbox kỹ thuật `period.drain.marker` mà TL-03 đã định nghĩa cho bước khoá của saga, không đặt mã EVT mới; các module chặn ghi qua bảng chiếu trạng thái kỳ; EVT-26 phát khi bù trừ `DANG_KHOA` về `MO` | Đã chốt 23/10/2026, chờ hiện thực (dấu chặn luồng) |
| QT-08, SCR-0801 | Saga sáu bước, hai người duyệt khi huỷ | Đúng sáu bước của TL-02a; huỷ được trước khi bước ký số lô xong; người duyệt phải khác người lập, người khởi chạy phải khác người duyệt; lệnh khoá có thời điểm dự kiến | Đã chốt (theo TL-02a) |

## Phần B: QT-09 đến QT-18 {-}

Phần B có nhiều điểm suy luận ở các luật cảnh báo bất thường (QT-14), vì TL-02b cho một phần số ngưỡng và TL-01 nêu ví dụ ngưỡng khác. Job phát hiện bất thường theo TL-02b, các ngưỡng còn thiếu số được đặt mặc định và tenant ghi đè được. Các con số dưới đây cần nghiệp vụ xác nhận trước khi đưa vào vận hành thật.

| Quy trình, màn hình | TL-02 ghi | Hiện thực trên main | Trạng thái |
|---|---|---|---|
| QT-09, SCR-0901 | Tự đánh giá | Nộp tự đánh giá có ký số; hồ sơ không tự đánh giá đúng hạn được đánh dấu thiếu | Theo mã |
| QT-09, QT-10 | Mức xếp loại theo bảng | "Hoàn thành xuất sắc nhiệm vụ" bao hàm điều kiện của mức thấp hơn; khi người đề xuất chọn mức khác mức gợi ý phải nêu lý do (DC-05) | Đã chốt 22/10/2026 |
| QT-09, EVT-10 | Sự kiện mang luồng, câu giải thích | Lược đồ Avro chưa có các trường này; giá trị nằm trong kết quả tính, MOD-04 đọc qua mã kết quả | Câu hỏi mở (hợp đồng) |
| QT-10, SCR-0904 | Ký lô quyết định | Ký lô có ngưỡng lỗi 0,2 và huỷ lô khi lỗi một phần; số quyết định duy nhất theo năm và tenant | Theo mã |
| QT-11, SCR-1102 | Kết thúc hội nghị phát EVT-11 | Chưa phát EVT-11 vì sự kiện thuộc MOD-04; góp ý "hạn chế" tự sinh mục cần khắc phục | Câu hỏi mở |
| QT-12, SCR-1201 | Khiếu nại gửi một lần | Cho lưu nháp (cần bỏ ràng buộc NOT NULL của TL-03); mã khiếu nại dạng KN-năm-số; hạn khiếu nại 10 ngày làm việc từ khi quyết định được ký | Theo mã, chờ xác nhận |
| QT-13 | Hai người duyệt mở lại kỳ | Chọn hai người duyệt bằng Java, không bao giờ hạ xuống một người duyệt; mở lại hết hạn thì kỳ tự khoá lại | Đã chốt bởi luồng điều phối |
| QT-14, luật 1 đến 8 | Ngưỡng ở TL-02b, ví dụ khác ở TL-01 | Theo TL-02b; ngưỡng thiếu số là giả định; dấu hiệu 8 gộp với BR-06-12 thành một luật miễn trừ lặp; cảnh báo tự đóng khi lượt sau không còn phát hiện | Theo mã, chờ xác nhận |
| QT-14, xử lý cảnh báo | Thông báo cảnh báo mức cao | Gom thông báo hằng ngày lúc 03:30, mỗi người xử lý một thông báo, không nêu tên người bị cảnh báo | Theo mã |
| QT-15, SCR-1504 | Tham số luồng phát hành cùng Policy Pack | MOD-05 công bố API, SVC-06 giữ dữ liệu; `wf_binding` thêm cột phiên bản Policy Pack vào khoá duy nhất | Đã chốt 23/10/2026, chờ hiện thực |
| QT-15 | Soạn gói cho từng tập khách hàng | Hồ sơ tập khách hàng (ADR-33); hồ sơ TWĐ chạy 1.441 ca vàng trên cùng bản build lõi với hai gói cũ | Đã chốt 22/10/2026 |
| QT-16, SCR-1601 | Đệm kết quả 5 phút | Đệm 60 giây theo TL-01, cộng làm mới pre-aggregation 5 phút, vẫn trong giới hạn BR-16-08; chỉ tiêu kinh tế xã hội có cờ công bố chưa có nguồn dữ liệu | Theo mã |
| QT-16, chia sẻ bảng điều hành | Thông báo cho người được chia sẻ | Chưa phát EVT-21 vì analytics-api không có danh bạ người dùng theo đơn vị | Câu hỏi mở |
| QT-17, SCR-1701 | Ba ca sử dụng AI | Thêm soạn OKR, soát KR, hỏi đáp quy định, giải thích điểm và nhập bằng giọng nói; đọc chữ minh chứng (OCR) lưu văn bản mã hoá C2 | Theo mã; nhà cung cấp nhận dạng tiếng nói là câu hỏi mở |
| QT-18, SCR-1801 | UI Pack | Có chế độ "Đảng viên cao tuổi" trong token; gửi duyệt chạy kiểm thử nền; quay lui chỉ về bản đã ký | Theo mã |
