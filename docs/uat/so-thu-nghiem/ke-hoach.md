# Kế hoạch kiểm thử chấp nhận nghiệp vụ: tenant thí điểm Sở Thử nghiệm

> Nguồn: task QA-16; TL-01 chương "Lộ trình chuyển đổi" (chỉ tiêu chí thí điểm của đợt 2: một tenant hoàn thành trọn một kỳ trên hệ mới, ca mẫu vàng đạt 100%, kiểm tra sổ cái hợp lệ trên kỳ hoàn chỉnh; phần chuyển dần từ hệ cũ không áp dụng vì Mission là hệ mới độc lập); TL-04 mục 11.4 (OPS-07 đưa tenant vào sử dụng), 11.8 (OPS-17 chuẩn bị trước mốc hạn và khoá kỳ), 12.6 (đợt 2) và bảng mức sự cố SEV-1 đến SEV-4; TL-02a, TL-02b (QT-00 đến QT-18); `wiki/15a-ma-tran-phan-quyen.md`; `frontend/e2e/hanh-trinh/danh-muc.json`.

## 1. Mục đích và trạng thái của bộ tài liệu

Kế hoạch này tổ chức kiểm thử chấp nhận nghiệp vụ (UAT) cho một tenant thí điểm chạy trọn một kỳ đánh giá trên Mission: từ chuẩn bị đơn vị và kỳ, lập kế hoạch, lập mục tiêu và kết quả then chốt, giao việc, cập nhật tiến độ, nộp và nghiệm thu minh chứng, khoá kỳ, tự đánh giá, thẩm định, quyết định xếp loại, hồ sơ số, khiếu nại, đến kiểm tra toàn vẹn sổ cái. Mục tiêu cuối là trả lời câu hỏi mà tiêu chí đợt 2 đặt ra: một tenant có dùng được hệ thống mới cho cả một kỳ đánh giá hay không, với kết quả xếp loại khớp tính tay và dữ liệu kiểm chứng được.

Bộ tài liệu hiện ở giai đoạn chuẩn bị. Tenant là tenant hư cấu "Sở Thử nghiệm" của dữ liệu thử dev, người tham gia là người dùng thử đóng vai mười vai trò, và kỳ là một kỳ rút gọn mười ngày làm việc. Chưa có buổi nào chạy với người dùng thật. Mọi giá trị phụ thuộc tenant (tenant đích, cell, kỳ, tài khoản người đại diện) nằm trong `tham-so.yaml`, nên khi có tenant thí điểm thật chỉ đổi tệp đó; kịch bản, checklist và mẫu biên bản giữ nguyên.

| Tài liệu | Nội dung |
|---|---|
| `ke-hoach.md` (tệp này) | Phạm vi, lịch, vai trò, điều kiện bắt đầu và kết thúc, tiêu chí chấp nhận, cách báo lỗi, theo dõi trong kỳ |
| `tham-so.yaml` | Tenant đích, cell, gói quy định, kỳ và mốc, người đại diện từng vai trò |
| `kich-ban.md` | Mười tám kịch bản bằng ngôn ngữ nghiệp vụ, theo thứ tự của kỳ đánh giá |
| `checklist-nghiem-thu.md` | Danh mục kiểm theo từng vai trò AC-01 đến AC-10 và danh mục kiểm cuối kỳ |
| `bien-ban-mau.md` | Mẫu biên bản dùng chung cho buổi diễn tập và buổi nghiệm thu |
| `du-lieu-mau/` | Dữ liệu hư cấu, tài khoản và kết quả xếp loại mong đợi tính tay |
| `kiem_ket_qua_mong_doi.py` | Đối chiếu bảng tính tay với bộ tính tham chiếu của gói quy định |

## 2. Phạm vi

### 2.1. Quy trình trong phạm vi

Buổi UAT phủ các quy trình mà một kỳ đánh giá quý của tenant thực sự đi qua. Mỗi quy trình được kiểm bằng một hoặc vài kịch bản, và mỗi kịch bản ánh xạ tới hành trình đầu cuối cùng nội dung trong danh mục của QA-01, để phần tự động hoá và phần người dùng kiểm cùng nói về một việc.

| Quy trình | Nội dung kiểm trong kỳ thí điểm | Kịch bản |
|---|---|---|
| QT-00 | Đăng nhập tài khoản cơ quan, chọn đơn vị và vai trò, xác thực bậc hai | 02 |
| QT-01 | Khởi tạo tenant, cây đơn vị, phân công, đồng bộ nhân sự | 01 |
| QT-15 | Ghim bộ quy định đánh giá cho kỳ, chạy ca mẫu vàng, tham số luồng duyệt | 03 |
| QT-04 | Lập kế hoạch quý, trả lại, phê duyệt | 04 |
| QT-02, QT-03 | Lập mục tiêu và kết quả then chốt, phê duyệt, cập nhật tiến độ kết quả then chốt | 05, 07 |
| QT-05, QT-06 | Giao việc, nhận việc, cập nhật tiến độ, điểm nghẽn, gia hạn | 06, 07 |
| QT-07 | Nộp minh chứng, nghiệm thu năm mức, yêu cầu bổ sung | 08, 09 |
| QT-16 | Bảng điều hành, đi xuống tới minh chứng | 10 |
| QT-08 | Lệnh khoá kỳ, phê duyệt, theo dõi tiến trình khoá kỳ; diễn tập huỷ khoá kỳ trên kỳ phụ | 11 |
| QT-09 | Tự đánh giá, thẩm định, đề xuất, quyết định xếp loại hàng loạt có ký số | 12, 13, 14 |
| QT-12 | Khiếu nại, giải trình, kết luận, trả lời | 15 |
| QT-11 | Hội nghị kiểm điểm, gói hồ sơ số có ký số | 16 |
| QT-13, QT-14 | Kiểm tra toàn vẹn sổ cái, cảnh báo bất thường, yêu cầu mở lại kỳ có hai người duyệt | 17 |
| QT-18 (một phần) | Chế độ Đảng viên cao tuổi trên các màn hình thường dùng | 18 |

### 2.2. Ngoài phạm vi của kỳ thí điểm đầu

Một số phần không chạy trong buổi UAT đầu, và biên bản ghi rõ là chưa kiểm chứ không coi là đạt. Xếp loại năm và phân nhóm triển vọng (QT-10) cần đủ bốn quý nên chỉ kiểm khi tenant đi hết năm. Đăng nhập VNeID đang tạm hoãn trong danh mục hành trình. Trợ lý AI (QT-17), ứng dụng di động và soạn UI Pack (QT-18 phần quản trị) là kịch bản tuỳ chọn, chỉ chạy khi cờ tính năng tương ứng đã bật cho tenant. Theo YC-PT-19, giai đoạn này không đo tải ở quy mô định cỡ: chỉ số SLO của cell thí điểm được ghi lại để tham khảo, không dùng làm tiêu chí đạt. Sửa lỗi phát hiện trong UAT thuộc các task theo phân hệ, không thuộc QA-16.

## 3. Vai trò và người đại diện

Mỗi vai trò có một người đại diện chịu trách nhiệm chạy kịch bản của vai trò đó và ký phần tương ứng của checklist. Ở buổi diễn tập, người đại diện là người dùng thử đóng vai, đăng nhập bằng tài khoản thử của realm dev; tên và tài khoản ghi trong `tham-so.yaml` và `du-lieu-mau/to-chuc-va-tai-khoan.csv`. Ở tenant thật, quản trị tenant cử người và cấp tài khoản; tổ UAT không giữ mật khẩu hay mã OTP của bất kỳ ai.

| Vai trò | Người đại diện (diễn tập) | Việc chính trong kỳ |
|---|---|---|
| AC-01 Quản trị nền tảng | Tạ Văn Nền | Cấp phát tenant, kiểm tra cấu hình cell và cờ tính năng |
| AC-02 Quản trị tenant | Hoàng Văn Quản Trị (dự phòng Lê Văn Hai) | Cây đơn vị, phân công, tạo kỳ, khởi chạy khoá kỳ |
| AC-03 Quản trị nghiệp vụ | Đỗ Thị Chính Sách | Ghim bộ quy định cho kỳ, ca mẫu vàng, tham số luồng duyệt |
| AC-04 Lãnh đạo | Lê Văn Lãnh Đạo | Duyệt kế hoạch và mục tiêu, phê duyệt lệnh khoá kỳ, chủ trì hội nghị kiểm điểm |
| AC-05 Cán bộ quản lý trực tiếp | Trần Thị Quản Lý | Lập kế hoạch, giao việc, nghiệm thu, xử lý điểm nghẽn và gia hạn |
| AC-06 Cán bộ | Nguyễn Văn Cán Bộ | Nhận việc, cập nhật tiến độ, nộp minh chứng, tự đánh giá, khiếu nại |
| AC-07 Thẩm định | Phạm Thị Thẩm Định | Thẩm định, đề xuất mức xếp loại, lập gói hồ sơ số, giải trình khiếu nại |
| AC-08 Quyết định | Vũ Văn Quyết Định | Quyết định xếp loại hàng loạt và ký số, ký gói hồ sơ, ký trả lời khiếu nại |
| AC-09 Duyệt thứ hai | Bùi Thị Duyệt Hai | Duyệt thứ hai khi huỷ khoá kỳ và khi mở lại kỳ |
| AC-10 Kiểm tra, giám sát | Ngô Văn Kiểm Tra | Kiểm tra sổ cái, kết luận cảnh báo, tiếp nhận khiếu nại, đề nghị mở lại kỳ |

Năm người trong số này đồng thời là cán bộ được đánh giá trong kỳ (cột "duoc_danh_gia_trong_ky" của tệp tài khoản), vì người có vai trò quản lý hay thẩm định vẫn là đảng viên, công chức có hồ sơ của mình. Điều này cũng giúp kiểm quy tắc tách vai trò: người thẩm định không thẩm định hồ sơ của chính mình, và người khởi chạy khoá kỳ không tự phê duyệt lệnh của mình. Hồ sơ của người thẩm định do một người thẩm định khác hoặc do lãnh đạo xử lý theo phân cấp của tenant (suy luận; ở buổi diễn tập, tổ UAT dùng tài khoản dự phòng nếu luồng duyệt yêu cầu).

Ngoài người đại diện vai trò, tổ UAT gồm một trưởng tổ điều phối lịch và ghi biên bản, một thư ký ghi phiếu lỗi, và một kỹ sư trực hệ thống theo dõi chỉ số và nhật ký của cell thí điểm. Kỹ sư trực không thao tác thay người dùng.

## 4. Lịch theo mốc của kỳ

Kỳ thí điểm là một kỳ quý. Để diễn tập trong thời gian ngắn, buổi diễn tập dùng một kỳ rút gọn mười ngày làm việc; các mốc ghi theo ngày làm việc thứ n tính từ ngày bắt đầu kỳ, và cùng một bảng mốc được nhập vào lịch kỳ (`config.period`) khi quản trị tenant tạo kỳ. Khi chạy với tenant thật trong một quý đầy đủ, mỗi mốc giãn ra theo lịch thật của tenant (thường: lập kế hoạch tuần đầu, cập nhật tiến độ suốt quý, khoá kỳ sau ngày cuối quý, đánh giá trong hai tuần sau khoá).

| Ngày | Việc | Kịch bản | Điều kiện để sang ngày sau |
|---|---|---|---|
| Trước D1 | Cấp phát tenant, cây đơn vị, phân công, tạo kỳ, ghim bộ quy định, chạy ca mẫu vàng; mọi người đăng nhập thử | 01, 02, 03 | Mười tài khoản đăng nhập được với đúng vai trò; ca mẫu vàng đạt 100% |
| D1 | Lập kế hoạch quý (bị trả lại một lần), lập mục tiêu và kết quả then chốt | 04, 05 | Kế hoạch và mục tiêu đã được phê duyệt |
| D2 | Giao mười bốn nhiệm vụ, cán bộ nhận việc | 06 | Mọi nhiệm vụ ở trạng thái đang thực hiện |
| D3 đến D5 | Cập nhật tiến độ, check-in kết quả then chốt, báo điểm nghẽn, đề nghị gia hạn; nộp minh chứng ở D5 | 07, 08 | Mọi nhiệm vụ đã nộp minh chứng |
| D6 | Nghiệm thu, một lần yêu cầu bổ sung; lãnh đạo xem bảng điều hành | 09, 10 | Mọi minh chứng đã được xác nhận với số lượng, chất lượng như dữ liệu mẫu |
| D7 | Diễn tập huỷ khoá kỳ trên kỳ phụ; khoá kỳ chính; mở tự đánh giá | 11, 12 | Kỳ chính đã khoá xong; hồ sơ của năm cán bộ đã có điểm tạm tính |
| D8 | Thẩm định, đề xuất, quyết định xếp loại hàng loạt có ký số | 13, 14 | Năm hồ sơ đã có quyết định; điểm và mức khớp `ket-qua-mong-doi.yaml` |
| D9 | Hội nghị kiểm điểm, gói hồ sơ số; khiếu nại và trả lời | 15, 16 | Gói hồ sơ đã ký; khiếu nại đã có văn bản trả lời |
| D10 | Kiểm tra sổ cái, cảnh báo, đề nghị mở lại kỳ (sau khi đã ghi số liệu nghiệm thu); kiểm chế độ cao tuổi; họp kết luận | 17, 18 | Biên bản có đủ chữ ký của tổ UAT |

Kịch bản 18 (chế độ Đảng viên cao tuổi) không phải một ngày riêng: người đại diện AC-06 bật chế độ này từ D3 và làm các thao tác thường ngày của mình trong chế độ đó, rồi ghi nhận xét vào D10.

## 5. Điều kiện bắt đầu

Buổi UAT chỉ bắt đầu khi các điều kiện sau đều đạt, và trưởng tổ ghi kết quả kiểm vào phần "Chuẩn bị" của biên bản.

1. Tenant thí điểm đã được cấp phát theo OPS-07 trên cell ghi trong `tham-so.yaml`, cờ tính năng bật đúng danh sách (OPS-04), và VH-04 đã diễn tập quy trình cấp phát.
2. Bản phát hành trên staging là bản đã qua cổng chất lượng; `E2E_MODE=he-thong npm run e2e:he-thong` chạy xanh trên staging trong vòng 24 giờ trước buổi UAT, và kết quả đính kèm biên bản.
3. Bộ quy định đánh giá của kỳ đã ghim; ca mẫu vàng của gói đạt 100% (`python3 -I data/golden/chay_ca_mau.py data/policy-pack` và màn hình bộ ca của hệ thống); `python3 -I docs/uat/so-thu-nghiem/kiem_ket_qua_mong_doi.py` in "Khớp 5/5 ca".
4. Dữ liệu dùng cho buổi UAT là dữ liệu hư cấu của `du-lieu-mau/`, hoặc bản sao đã che bằng tác vụ ẩn danh hoá (SEC-52, DL-15) có biên bản quét "đạt". Không dùng dữ liệu cá nhân thật ở staging.
5. Mỗi người đại diện đã đăng nhập thử thành công, đã cài ứng dụng tạo mã OTP nếu vai trò cần xác thực bậc hai, và có chứng thư ký số thử nếu vai trò cần ký (AC-04, AC-07, AC-08).
6. Kênh báo lỗi và mẫu phiếu lỗi (mục 7) đã sẵn sàng; kỹ sư trực có quyền xem bảng chỉ số của cell thí điểm.

## 6. Tiêu chí chấp nhận

Kỳ thí điểm được coi là đạt khi đồng thời thoả mãn các tiêu chí dưới đây. Ba tiêu chí đầu là tiêu chí của đợt 2 trong TL-01; các tiêu chí sau cụ thể hoá yêu cầu của QA-16 và các yêu cầu phi chức năng mà người dùng cảm nhận trực tiếp.

| Mã | Tiêu chí | Cách kiểm |
|---|---|---|
| TC-01 | Tenant hoàn thành trọn một kỳ: mọi kịch bản bắt buộc chạy hết, kỳ ở trạng thái đã khoá, năm hồ sơ có quyết định xếp loại | Biên bản mục 3 và 4 |
| TC-02 | Ca mẫu vàng của gói quy định ghim cho kỳ đạt 100% | Màn hình bộ ca và lệnh chạy ca mẫu |
| TC-03 | Kiểm tra toàn vẹn sổ cái báo hợp lệ cho cả kỳ sau khi khoá và sau khi trả lời khiếu nại | Kịch bản 17, kết quả lượt kiểm tra sổ cái đính kèm |
| TC-04 | Điểm, mức xếp loại và câu giải thích của từng hồ sơ khớp bảng tính tay `ket-qua-mong-doi.yaml` | Biên bản mục 4 |
| TC-05 | Không còn lỗi SEV-1 hoặc SEV-2 đang mở khi kết thúc; mọi lỗi SEV-3, SEV-4 có phiếu và hướng xử lý | Sổ phiếu lỗi |
| TC-06 | Quyền đúng ma trận: mỗi vai trò chỉ thấy và làm được đúng chức năng của mình; các thao tác bị chặn có thông báo dễ hiểu | Checklist theo AC |
| TC-07 | Thao tác thường ngày (xem việc của tôi, cập nhật tiến độ, check-in kết quả then chốt) không quá ba chạm; chế độ Đảng viên cao tuổi dùng được trên các màn hình đó | Kịch bản 07, 18 |
| TC-08 | Màn hình dùng văn phong nghiêm túc, đúng thuật ngữ nghiệp vụ, không lộ mã kỹ thuật hay trạng thái thô | Nhận xét của người đại diện trong checklist |

## 7. Báo lỗi và phân mức

Mỗi lỗi hoặc góp ý được ghi thành một phiếu ngay khi phát hiện, kể cả khi người dùng tự tìm được cách làm tạm. Phiếu ghi: số phiếu, ngày giờ, người phát hiện và vai trò, kịch bản và bước, mô tả việc đang làm, kết quả thấy được, kết quả mong đợi, ảnh chụp màn hình (đã che thông tin cá nhân nếu có), mã tham chiếu hiển thị trên thông báo lỗi nếu có, mức đề xuất. Thư ký gán mức theo bảng sau (lấy từ bảng mức sự cố của TL-04) và chuyển phiếu cho task phân hệ phụ trách; QA-16 không sửa lỗi.

| Mức | Khi nào | Ảnh hưởng tới buổi UAT |
|---|---|---|
| SEV-1 | Cell thí điểm không dùng được, mất hoặc sai dữ liệu đã ghi, sự cố an ninh (lộ dữ liệu, sai quyền nghiêm trọng) | Dừng buổi UAT, lập biên bản sự cố; chỉ chạy tiếp khi đã khắc phục và kiểm lại |
| SEV-2 | Chức năng chính của kỳ không làm được hoặc cho kết quả sai (ví dụ điểm, xếp loại lệch tính tay, khoá kỳ không hoàn tất) | Dừng kịch bản liên quan; các kịch bản không phụ thuộc vẫn chạy |
| SEV-3 | Chức năng phụ lỗi, có cách làm tạm (ví dụ bộ lọc sai, thông báo khó hiểu) | Ghi phiếu, chạy tiếp |
| SEV-4 | Lỗi nhỏ, góp ý giao diện, câu hỏi | Ghi phiếu, chạy tiếp |

Kết quả xếp loại lệch bảng tính tay luôn được xếp ít nhất SEV-2, vì đó là kết quả nghiệp vụ cuối cùng mà cán bộ nhận. Trước khi kết luận là lỗi của hệ thống, tổ UAT kiểm lại dữ liệu đã nhập (số lượng nghiệm thu, mức chất lượng, điều kiện hồ sơ) so với `ke-hoach-okr-nhiem-vu.yaml`, vì sai sót nhập liệu trong buổi UAT là nguyên nhân thường gặp nhất.

## 8. Theo dõi trong kỳ

Trong suốt kỳ, kỹ sư trực theo dõi bảng chỉ số của cell thí điểm và ghi vào biên bản ba nhóm số liệu: tỷ lệ lỗi và độ trễ của các thao tác chính theo SLO đã đặt cho cell, thời gian chạy của tiến trình khoá kỳ, và các cảnh báo vận hành phát sinh. Theo YC-PT-19, các số liệu này chỉ dùng để tham khảo và phát hiện bất thường, không dùng làm tiêu chí đạt của buổi UAT.

Thư ký tổng hợp sổ phiếu lỗi mỗi cuối ngày theo mức SEV-1 đến SEV-4 và trạng thái (mới, đã chuyển, đã khắc phục, đã kiểm lại), và trưởng tổ báo cáo ngắn cho người phụ trách. Phản hồi về chế độ Đảng viên cao tuổi và số chạm được ghi riêng trong checklist của AC-06, vì đây là yêu cầu NFR-11 mà người dùng thật là người đánh giá chính xác nhất.

## 9. Kết thúc và nghiệm thu

Cuối kỳ, tổ UAT họp kết luận: đối chiếu điểm và xếp loại với bảng tính tay, kiểm ca mẫu vàng, kiểm kết quả lượt kiểm tra sổ cái cho cả kỳ, rà sổ phiếu lỗi, và ghi kết luận "đạt", "đạt có điều kiện" (còn lỗi SEV-3 trở xuống có hướng xử lý) hoặc "không đạt" vào biên bản theo `bien-ban-mau.md`. Với buổi diễn tập, biên bản là "biên bản diễn tập", không có chữ ký của đại diện tenant. Với tenant thật, biên bản nghiệm thu có thêm chữ ký của đại diện tenant và lưu tại `docs/uat/<mã tenant>/bien-ban.md` cùng bản ký số lưu ngoài repo.

## 10. Rủi ro và cách xử lý

| Rủi ro | Cách xử lý |
|---|---|
| Kỳ rút gọn không tái hiện được trừ điểm theo ngày chậm | Dữ liệu mẫu đặt mọi nhiệm vụ đúng hạn; trừ điểm tiến độ đã có trong bộ ca mẫu vàng của gói |
| Người dùng thử nhập sai dữ liệu nên kết quả lệch | Dữ liệu mẫu ghi từng con số cần nhập; tổ UAT kiểm dữ liệu nhập trước khi kết luận lỗi |
| Hệ thống ngoài (ký số, văn bản chỉ đạo, nhân sự) chưa có môi trường thật | Dùng bản giả lập của hệ ngoài theo YC-PT-13; biên bản ghi rõ phần nào chạy với giả lập |
| Gói quy định là bản giả lập chưa ký | Biên bản ghi phiên bản gói; khi có gói chính thức thì tính lại bảng tính tay và chạy lại |
| Mở lại kỳ ở kịch bản 17 làm đổi số liệu đã nghiệm thu | Ghi số liệu nghiệm thu và kết quả kiểm tra sổ cái trước, rồi mới chạy phần mở lại kỳ |

## Liên quan

- [Kịch bản UAT](kich-ban.md), [Checklist nghiệm thu](checklist-nghiem-thu.md), [Mẫu biên bản](bien-ban-mau.md)
- `frontend/e2e/hanh-trinh/danh-muc.json` (trường `kichBanUat` ánh xạ hành trình với kịch bản)
- `wiki/11-kiem-thu-va-chat-luong.md` (chiến lược kiểm thử QA-01), `wiki/15a-ma-tran-phan-quyen.md`
