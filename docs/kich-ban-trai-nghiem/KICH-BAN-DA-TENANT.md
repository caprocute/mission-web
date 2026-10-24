# Kịch bản trải nghiệm hệ thống với dữ liệu thử đa tenant

Tài liệu này hướng dẫn tự trải nghiệm nền tảng Mission trên môi trường dev bằng bộ dữ liệu thử của ba khách hàng (tenant) cùng nằm trên một tỉnh giả định tên **Phú Lâm**. Mỗi kịch bản đi từng bước theo một tài khoản cụ thể, nói rõ cần bấm vào đâu và hệ thống phải phản hồi thế nào, để người đọc vừa hiểu nghiệp vụ, vừa thấy được phân quyền và cách tách dữ liệu giữa các khách hàng.

Toàn bộ dữ liệu là giả lập: tỉnh Phú Lâm không có thật, tên đơn vị và tên người là hư cấu, số hiệu văn bản căn cứ là văn bản giả định của tỉnh này. Cơ cấu tổ chức, chức danh, chỉ tiêu và nhiệm vụ được chọn cho đúng với thực tế hành chính hiện hành (chính quyền địa phương hai cấp tỉnh, xã; cơ quan tham mưu của Tỉnh ủy; doanh nghiệp nhà nước 100% vốn của tỉnh) để các số liệu, cây mục tiêu và luồng duyệt nhất quán với nhau.

## 1. Chuẩn bị

**Địa chỉ.** Mở trang web Mission của môi trường dev như các lần thử trước (qua đường hầm tới cổng 35000 của server dev).

**Mật khẩu và mã OTP.** Mọi tài khoản trong tài liệu này dùng chung mật khẩu thử và cùng khoá OTP với 13 tài khoản thử cũ của realm `mission`. Vì cùng khoá, mục OTP đã cài trên ứng dụng xác thực cho tài khoản cũ sinh đúng mã cho mọi tài khoản mới; không phải cài thêm. Mã OTP đổi sau mỗi 30 giây và không dùng lại được, nên khi đăng nhập hai tài khoản liên tiếp, hãy chờ mã mới.

**Đổi tài khoản.** Bấm tên người dùng ở góc trên, chọn "Đăng xuất", rồi đăng nhập tài khoản khác. Khi muốn so sánh hai vai trò cùng lúc, mở thêm một cửa sổ ẩn danh.

**Kỳ đánh giá.** Bộ nạp đã tạo kỳ "Quý IV/2026" ở cả ba tenant; mọi mục tiêu và nhiệm vụ thử nằm trong kỳ này.

**Nạp lại dữ liệu.** Người quản trị môi trường chạy `infra/dev/scripts/seed-da-tenant.sh` trên server dev. Bộ nạp chạy lại không sinh trùng, chỉ bổ sung phần còn thiếu.

## 2. Ba khách hàng và cách đặt tên tài khoản

| Tenant | Loại | Luồng đánh giá | Tiền tố tài khoản | Quy mô thử |
|---|---|---|---|---|
| Đảng bộ tỉnh Phú Lâm | Khối Đảng | Theo Quy định 366-QĐ/TW | `tu.` | 12 tổ chức, 16 tài khoản |
| Ủy ban nhân dân tỉnh Phú Lâm | Khối chính quyền | Cán bộ, công chức, viên chức (Thông tư 22, Nghị định 335/2025) | `cq.` | 12 đơn vị, 21 tài khoản |
| Công ty TNHH MTV Cấp nước Phú Lâm | Doanh nghiệp nhà nước | KPI sản xuất kinh doanh | `dn.` | 7 đơn vị, 14 tài khoản |

Ba tenant cùng chạy trên một cụm máy chủ dùng chung theo mô hình pool, nhưng dữ liệu tách hoàn toàn theo tenant: người của tenant này không nhìn thấy, không tìm được và không thao tác được trên dữ liệu của tenant khác. Bảng tài khoản đầy đủ (họ tên, chức danh, vai trò, mục đích) nằm ở [`data/seed/da-tenant/TAI-KHOAN.md`](../../data/seed/da-tenant/TAI-KHOAN.md); dưới đây chỉ nhắc lại cơ cấu để dễ hình dung.

### 2.1. Đảng bộ tỉnh Phú Lâm

Tỉnh ủy Phú Lâm có Văn phòng Tỉnh ủy (Phòng Tổng hợp), Ban Tổ chức Tỉnh ủy (Phòng Cán bộ, Phòng Tổ chức – Đảng viên), Ủy ban Kiểm tra Tỉnh ủy, Ban Tuyên giáo và Dân vận Tỉnh ủy. Theo cây tổ chức Đảng còn có Đảng ủy các cơ quan Đảng tỉnh với Chi bộ Ban Tổ chức, và Đảng ủy xã Tân Lập với Chi bộ thôn Đông Hòa.

Tài khoản tiêu biểu: Bí thư Tỉnh ủy `tu.bithu` (lãnh đạo, cấp quyết định), Phó Bí thư Thường trực `tu.phobithu` (soạn mục tiêu của Tỉnh ủy, người duyệt thứ hai), Trưởng Ban Tổ chức `tu.truongbantc` (lãnh đạo, thẩm định), Phó Trưởng Ban `tu.phobantc` (kiêm Bí thư Chi bộ Ban Tổ chức, nên có hai phân công), Chánh Văn phòng `tu.chanhvp`, Phó Chủ nhiệm Ủy ban Kiểm tra `tu.ubkt` (kiểm tra, giám sát), Bí thư Chi bộ thôn `tu.bithuchibo` và đảng viên cao tuổi `tu.dangvien`.

### 2.2. Ủy ban nhân dân tỉnh Phú Lâm

UBND tỉnh có Văn phòng UBND tỉnh (Phòng Tổng hợp, Trung tâm Phục vụ hành chính công), Sở Nội vụ (Phòng Công chức, viên chức), Sở Tài chính (Phòng Quản lý ngân sách), Sở Khoa học và Công nghệ (Phòng Chuyển đổi số), Thanh tra tỉnh và UBND xã Tân Lập.

Tài khoản tiêu biểu: Chủ tịch `cq.chutich` (lãnh đạo, cấp quyết định), Phó Chủ tịch `cq.phochutich` (soạn mục tiêu của tỉnh, người duyệt thứ hai), Giám đốc Sở Nội vụ `cq.giamdocsnv` (lãnh đạo, thẩm định), Giám đốc và Phó Giám đốc Sở Tài chính, Trưởng phòng Quản lý ngân sách `cq.truongphongns` và hai chuyên viên, Thanh tra viên `cq.thanhtra`, Chủ tịch UBND xã `cq.chutichxa`, hai quản trị `cq.quantri`, `cq.quantri2` để thử nguyên tắc bốn mắt.

### 2.3. Công ty TNHH MTV Cấp nước Phú Lâm

Công ty có Phòng Tổ chức – Hành chính, Phòng Kế hoạch – Kỹ thuật, Phòng Tài chính – Kế toán, Phòng Kinh doanh – Dịch vụ khách hàng, Xí nghiệp Cấp nước số 1 và Nhà máy nước Đông Sơn.

Tài khoản tiêu biểu: Chủ tịch Hội đồng thành viên `dn.chutich` (lãnh đạo, cấp quyết định), Tổng Giám đốc `dn.tonggiamdoc` (soạn mục tiêu Công ty, duyệt mục tiêu phòng và xí nghiệp), Kiểm soát viên `dn.kiemsoatvien` (chỉ kiểm tra, giám sát), Trưởng phòng Kinh doanh `dn.truongphongkd`, Giám đốc Xí nghiệp `dn.giamdocxn`, Tổ trưởng `dn.totruong` và công nhân vận hành `dn.congnhan`.

## 3. Dữ liệu đã nạp sẵn

Bộ nạp đi đúng luồng nghiệp vụ như người dùng thao tác trên màn hình, nên mọi bản ghi đều có lịch sử, người soạn, người duyệt và sự kiện như dữ liệu thật.

**Cây mục tiêu.** Mỗi tenant có một mục tiêu cấp cao nhất đã duyệt, phân rã xuống đơn vị cấp dưới và tới mục tiêu cá nhân. Ví dụ ở khối Đảng: mục tiêu của Tỉnh ủy "Lãnh đạo hoàn thành kiểm điểm, đánh giá, xếp loại chất lượng năm 2026 theo Quy định 366-QĐ/TW" do Phó Bí thư Thường trực soạn, Bí thư phê duyệt; mục tiêu của Ban Tổ chức, của Phòng Cán bộ và mục tiêu cá nhân của chuyên viên `tu.chuyenviencb` nối tiếp nhau bên dưới. Khối chính quyền có cây từ mục tiêu "Cải cách hành chính và chuyển đổi số của tỉnh đạt chỉ tiêu năm 2026" xuống Sở Tài chính, Trung tâm Phục vụ hành chính công, UBND xã. Doanh nghiệp có cây từ mục tiêu sản xuất kinh doanh quý IV xuống Xí nghiệp số 1, Phòng Kinh doanh và tới công nhân, nhân viên.

**Mục tiêu đang chờ duyệt.** Mỗi tenant có một bộ mục tiêu cá nhân đã trình nhưng chưa duyệt, để người lãnh đạo thử thao tác phê duyệt hoặc trả lại: `tu.chuyenvienth2` chờ `tu.chanhvp`; `cq.chuyenviennv` chờ `cq.giamdocsnv`; `dn.nhanvienkd2` chờ `dn.tonggiamdoc`.

**Check-in.** Các mục tiêu cá nhân đã duyệt có lần check-in đầu tiên với mức tự tin cao hoặc trung bình, nên cây mục tiêu hiện tiến độ khác 0.

**Nhiệm vụ.** Mỗi tenant có năm hoặc sáu nhiệm vụ do cán bộ quản lý giao: một phần đã được nhận và đang thực hiện với phần trăm tiến độ khác nhau, một phần mới giao đang chờ người chủ trì nhận. Nhiều nhiệm vụ liên kết với KR của mục tiêu cá nhân.

**Danh mục công việc.** Mỗi tenant có một danh mục công việc đang hiệu lực, 6 hoặc 7 mục có mức khó, điểm chuẩn, tiêu chuẩn chất lượng và yêu cầu minh chứng, dùng khi lập kế hoạch quý và khi giao việc.

**Căn cứ.** Mỗi tenant có ba hoặc bốn văn bản căn cứ (nghị quyết, kế hoạch, chương trình, chỉ thị, quyết định giao chỉ tiêu) để chọn khi soạn mục tiêu.

## 4. Kịch bản khối Đảng (Đảng bộ tỉnh Phú Lâm)

### KB-D1. Đảng viên xem việc của mình và cập nhật tiến độ

Tài khoản: `tu.chuyenviencb` (Chuyên viên Phòng Cán bộ, Ban Tổ chức Tỉnh ủy).

1. Đăng nhập. Trang chủ "Việc của tôi hôm nay" hiện hai nhiệm vụ: "Thẩm định hồ sơ đánh giá cán bộ khối Mặt trận Tổ quốc và các đoàn thể tỉnh" (đang thực hiện, 40%) và "Lập danh sách cán bộ diện Ban Thường vụ quản lý thuộc đối tượng đánh giá năm 2026" (mới giao).
2. Mở nhiệm vụ mới giao, bấm "Nhận việc". Trạng thái chuyển sang đang thực hiện.
3. Mở nhiệm vụ thẩm định, cập nhật tiến độ lên 60% kèm ghi chú (ví dụ "Đã thẩm định 9/14 hồ sơ"). Lịch sử tiến độ hiện thêm một dòng.
4. Vào "Mục tiêu, OKR", mở mục tiêu cá nhân "Thẩm định đúng hạn các hồ sơ đánh giá cán bộ được phân công". KR "Số hồ sơ được phân công đã thẩm định xong" đang ở 11/30.
5. Check-in KR đó với giá trị 15, mức tự tin "Cao". Tiến độ KR và mục tiêu được tính lại; mục tiêu của Phòng Cán bộ và của Ban Tổ chức phía trên trên cây cũng thay đổi theo.

Kết quả mong đợi: menu chỉ có các mục của người được đánh giá và người thẩm định (không có "Cấu hình nghiệp vụ", "Quản trị"); không có nút phê duyệt mục tiêu.

### KB-D2. Lãnh đạo Văn phòng duyệt mục tiêu đang chờ

Tài khoản: `tu.chanhvp` (Chánh Văn phòng Tỉnh ủy), sau đó `tu.chuyenvienth2`.

1. Đăng nhập `tu.chanhvp`, vào "Mục tiêu, OKR", mở danh sách chờ phê duyệt. Bộ mục tiêu "Chuẩn bị tài liệu hội nghị Ban Thường vụ Tỉnh ủy đầy đủ, đúng hạn" của đồng chí Trịnh Thị Mai đang chờ.
2. Mở bộ mục tiêu, xem kết quả kiểm tra tự động (đủ căn cứ, có mục tiêu cấp trên, tổng trọng số 100%).
3. Thử "Trả lại" với ý kiến "Đề nghị bổ sung KR về thời hạn gửi tài liệu cho đại biểu".
4. Đăng xuất, đăng nhập `tu.chuyenvienth2`. Mục tiêu hiện trạng thái bị trả lại kèm ý kiến; sửa và trình lại.
5. Đăng nhập lại `tu.chanhvp`, phê duyệt.

Kết quả mong đợi: người duyệt chỉ thấy mục tiêu trong cây đơn vị mình phụ trách (Văn phòng Tỉnh ủy và Phòng Tổng hợp), không thấy mục tiêu của Ban Tổ chức. Người soạn không bao giờ thấy nút phê duyệt bộ mục tiêu của chính mình.

### KB-D3. Trưởng phòng giao việc cho chuyên viên

Tài khoản: `tu.truongphongth` (Trưởng phòng Tổng hợp, Văn phòng Tỉnh ủy).

1. Vào "Việc của tôi", chọn "Giao việc". Chọn mục danh mục "Tham mưu xây dựng báo cáo định kỳ của Ban Thường vụ Tỉnh ủy" để hệ thống điền sẵn sản phẩm, tiêu chuẩn chất lượng, minh chứng.
2. Chọn người chủ trì `Lý Văn Khánh`, người phối hợp `Trịnh Thị Mai`, đặt hạn trong quý, liên kết KR "Số báo cáo tổng hợp nộp đúng hạn" của đồng chí Khánh.
3. Thử chọn chính mình làm người nghiệm thu đồng thời là người chủ trì: hệ thống chặn vì người nghiệm thu phải khác người thực hiện.
4. Giao việc. Đăng nhập `tu.chuyenvienth` để thấy nhiệm vụ mới trên trang chủ.

### KB-D4. Một người, hai phân công

Tài khoản: `tu.phobantc` (Phó Trưởng Ban Tổ chức, kiêm Bí thư Chi bộ Ban Tổ chức).

1. Đăng nhập. Trên trang chủ bấm "Đổi phân công": có hai lựa chọn, "Phó Trưởng ban, Ban Tổ chức Tỉnh ủy" và "Bí thư chi bộ, Chi bộ Ban Tổ chức Tỉnh ủy".
2. Ở phân công Ban Tổ chức, vào "Mục tiêu, OKR" thấy mục tiêu của Ban do mình soạn. Đổi sang phân công Chi bộ, phạm vi dữ liệu đổi theo đơn vị.

### KB-D5. Chi bộ cơ sở và đảng viên cao tuổi

Tài khoản: `tu.bithuchibo` rồi `tu.dangvien`.

1. `tu.bithuchibo` (Bí thư Chi bộ thôn Đông Hòa) xem mục tiêu chi bộ "Chi bộ thôn Đông Hòa hoàn thành tốt nhiệm vụ năm 2026" đã được Bí thư Đảng ủy xã duyệt, và nhiệm vụ giới thiệu quần chúng ưu tú đã giao cho đảng viên Trần Văn Bảy.
2. Đăng nhập `tu.dangvien`. Bấm nút "Chữ lớn" ở thanh trên cùng: cỡ chữ, nút bấm và độ tương phản tăng; các thao tác thường ngày (xem việc, cập nhật tiến độ) vẫn trong ba lần chạm. Cập nhật tiến độ nhiệm vụ lên 50%.
3. Đăng xuất rồi đăng nhập lại trên máy khác hoặc cửa sổ ẩn danh: chế độ chữ lớn vẫn giữ vì được lưu theo người dùng.

### KB-D6. Kiểm tra, giám sát và cấu hình nghiệp vụ

1. `tu.ubkt` (Phó Chủ nhiệm Ủy ban Kiểm tra) vào "Giám sát, hậu kiểm": xem trung tâm cảnh báo, tra cứu sổ cái và kiểm tra toàn vẹn. Không có nút giao việc hay phê duyệt mục tiêu.
2. `tu.chuyenvientcdv` (Quản trị nghiệp vụ) vào "Cấu hình nghiệp vụ": xem bộ quy tắc đánh giá, chạy bộ ca kiểm thử mẫu. Có thể soạn nhưng không tự phát hành được; phát hành cần người khác duyệt và ký.
3. `tu.quantri` (Quản trị hệ thống) vào "Quản trị": xem cây tổ chức có cả cây chuyên môn và cây tổ chức Đảng, danh sách kỳ đánh giá. Tài khoản này không có mục "Việc của tôi" vì không phải đối tượng được đánh giá.

## 5. Kịch bản khối chính quyền (UBND tỉnh Phú Lâm)

### KB-C1. Cây mục tiêu từ tỉnh xuống sở, phòng, xã

Tài khoản: `cq.chutich` (Chủ tịch UBND tỉnh).

1. Vào "Mục tiêu, OKR", xem cây. Gốc là mục tiêu của UBND tỉnh với hai KR: tỷ lệ hồ sơ thủ tục hành chính đúng hạn (đầu kỳ 92%, chỉ tiêu 98%) và tỷ lệ thủ tục cung cấp trực tuyến toàn trình (70% lên 85%).
2. Mở rộng các nhánh: Sở Tài chính (giải ngân đầu tư công, thẩm định dự toán 2027), Trung tâm Phục vụ hành chính công (trả kết quả đúng hạn, mức hài lòng), UBND xã Tân Lập, các mục tiêu cá nhân.
3. Vào "Bảng điều hành" xem tiến độ theo đơn vị, nhấn vào một đơn vị để đi xuống tới nhiệm vụ.

### KB-C2. Chuỗi soạn, duyệt mục tiêu theo hai cấp

Tài khoản: `cq.truongphongcds` rồi `cq.chutich`.

1. `cq.truongphongcds` (Trưởng phòng Chuyển đổi số) soạn mục tiêu của phòng, chọn căn cứ "Kế hoạch cải cách hành chính và chuyển đổi số tỉnh Phú Lâm năm 2026", chọn mục tiêu cấp trên là mục tiêu của tỉnh, nhập hai KR có trọng số 60% và 40%, người xác nhận là Chủ tịch.
2. Thử nhập KR "Tăng cường ứng dụng công nghệ thông tin" không có chỉ tiêu: hệ thống báo KPI mơ hồ, chưa trình được.
3. Sửa lại thành KR đo được, trình duyệt.
4. `cq.chutich` phê duyệt. Mục tiêu xuất hiện trên cây.

### KB-C3. Giao việc, nhận việc và tiến độ ở Sở Tài chính

1. `cq.chuyenvienns` xem nhiệm vụ "Thẩm định dự toán ngân sách năm 2027 của các sở, ngành khối văn hóa – xã hội" (45%), cập nhật tiến độ và check-in KR "Số đơn vị dự toán được thẩm định xong".
2. `cq.chuyenvienns2` nhận nhiệm vụ "Tổng hợp tình hình giải ngân vốn đầu tư công đến ngày 30/11/2026". Đồng chí này có hai phân công (Phòng Quản lý ngân sách và Tổ công tác chuyển đổi số của Phòng Chuyển đổi số): thử "Đổi phân công" trên trang chủ.
3. `cq.truongphongns` (Trưởng phòng) xem "Việc của đơn vị": thấy hai nhiệm vụ của phòng, một đang làm, một vừa được nhận.
4. `cq.giamdocstc` (Giám đốc Sở) thấy tiến độ toàn Sở nhưng không thấy nhiệm vụ của Sở Nội vụ.

### KB-C4. Thẩm định và duyệt mục tiêu ở Sở Nội vụ

1. `cq.giamdocsnv` duyệt bộ mục tiêu đang chờ của `cq.chuyenviennv` ("Cập nhật đầy đủ cơ sở dữ liệu cán bộ, công chức phục vụ đánh giá năm 2026").
2. Với vai trò thẩm định, vào "Đánh giá, xếp loại" để xem màn hình thẩm định (hồ sơ cuối năm sẽ có khi kỳ đánh giá năm được mở).
3. `cq.truongphongccvc` (Trưởng phòng, kiêm quản trị nghiệp vụ) vào "Cấu hình nghiệp vụ" xem bảng quyết định xếp loại và tham số luồng duyệt.

### KB-C5. Một cửa và cấp xã

1. `cq.vienchuctt` (viên chức Trung tâm Phục vụ hành chính công) nhận nhiệm vụ thống kê hồ sơ trễ hạn quý IV.
2. `cq.congchucxa` (công chức xã) cập nhật tiến độ nhiệm vụ niêm yết thủ tục hành chính; `cq.chutichxa` xem tiến độ mục tiêu của xã.

### KB-C6. Bốn mắt khi quản trị cấu hình

1. `cq.quantri` vào "Quản trị", soạn thay đổi giao diện của đơn vị (màu, logo) và gửi duyệt.
2. Cùng tài khoản đó không có nút phát hành bản mình soạn.
3. `cq.quantri2` duyệt và phát hành. `cq.thanhtra` (Thanh tra viên) tra sổ cái thấy hai người khác nhau đã soạn và duyệt.

## 6. Kịch bản doanh nghiệp (Công ty TNHH MTV Cấp nước Phú Lâm)

### KB-N1. Công nhân xử lý sự cố trên điện thoại

Tài khoản: `dn.congnhan` (Công nhân vận hành, Xí nghiệp Cấp nước số 1). Nên mở trên điện thoại hoặc thu nhỏ cửa sổ trình duyệt cho giống điện thoại.

1. Trang chủ hiện việc "Khắc phục điểm rò rỉ ống phân phối D110 trên tuyến đường số 7, khu dân cư Bắc Sơn" (60%, hạn hai ngày, ưu tiên cao) và việc thay đồng hồ nước mới giao.
2. Cập nhật tiến độ việc khắc phục rò rỉ lên 100% kèm ghi chú; nhận việc thay đồng hồ.
3. Check-in KR "Số điểm rò rỉ được phát hiện, khắc phục" từ 14 lên 16.
4. Bật "Chữ lớn" để thấy giao diện cho người lớn tuổi hoặc thao tác ngoài hiện trường.

### KB-N2. Phòng Kinh doanh: mục tiêu chờ duyệt và giao việc gấp

1. `dn.truongphongkd` giao thêm việc gấp cho `dn.nhanvienkd2` (ưu tiên cao, hạn ba ngày) với người phối hợp `dn.nhanvienkd`.
2. `dn.tonggiamdoc` duyệt bộ mục tiêu đang chờ của `dn.nhanvienkd2`.
3. `dn.nhanvienkd` check-in KR "Số khách hàng mới ký hợp đồng dịch vụ cấp nước" và xem nhiệm vụ phát triển khách hàng ở khu đô thị phía Đông (40%).

### KB-N3. Lãnh đạo và kiểm soát viên

1. `dn.chutich` (Chủ tịch Hội đồng thành viên) xem cây mục tiêu Công ty: sản lượng nước thương phẩm, tỷ lệ thất thoát (18,5% xuống 16%), số khách hàng mới; mở "Bảng điều hành".
2. `dn.kiemsoatvien` chỉ có quyền kiểm tra, giám sát: thấy sổ cái và cảnh báo, không có "Việc của tôi", không có nút giao việc hay duyệt.
3. `dn.chuyenviennhansu` (Quản trị nghiệp vụ) vào "Cấu hình nghiệp vụ" để xem nơi cấu hình bộ KPI và công thức tính điểm riêng của doanh nghiệp. Khác biệt cách chấm giữa doanh nghiệp và khối nhà nước là cấu hình theo hồ sơ khách hàng, không phải mã riêng.

## 7. Kịch bản xuyên tenant: tách dữ liệu và phân quyền

### KB-X1. Dữ liệu không lẫn giữa các tenant

1. Đăng nhập `tu.bithu`, xem cây mục tiêu: chỉ có mục tiêu của Đảng bộ tỉnh.
2. Đăng xuất, đăng nhập `cq.chutich`: chỉ có mục tiêu của UBND tỉnh, dù cùng tỉnh Phú Lâm và cùng tên xã Tân Lập.
3. Đăng nhập `dn.tonggiamdoc`: chỉ có dữ liệu của Công ty.
4. Với tài khoản bất kỳ, thử dán đường dẫn chi tiết một nhiệm vụ của tenant khác (sao chép từ phiên kia): hệ thống báo không tìm thấy, không lộ nội dung.

### KB-X2. Cùng chức năng, khác vai trò

So sánh menu của các tài khoản cùng tenant để thấy giao diện chỉ hiện chức năng được phép:

| Tài khoản | Thấy gì | Không thấy gì |
|---|---|---|
| `cq.chuyenvienns` (cán bộ) | Trang chủ, Việc của tôi, Mục tiêu, Minh chứng, Đánh giá của bản thân | Cấu hình nghiệp vụ, Quản trị, nút phê duyệt |
| `cq.truongphongns` (quản lý trực tiếp) | Thêm giao việc, kế hoạch quý của phòng, nghiệm thu | Phê duyệt mục tiêu, Quản trị |
| `cq.giamdocstc` (lãnh đạo) | Phê duyệt mục tiêu, kế hoạch; Bảng điều hành | Soạn cấu hình nghiệp vụ |
| `cq.thanhtra` (kiểm tra) | Giám sát, hậu kiểm; sổ cái | Giao việc, phê duyệt |
| `cq.quantri` (quản trị) | Quản trị tổ chức, kỳ, giao diện | Việc của tôi, chấm điểm |

### KB-X3. Không tự duyệt việc của mình

1. `dn.tonggiamdoc` vừa là người soạn mục tiêu Công ty vừa có vai trò lãnh đạo. Mở mục tiêu đó: không có nút phê duyệt, chỉ Chủ tịch Hội đồng thành viên duyệt được.
2. `tu.phobantc` soạn mục tiêu của Ban Tổ chức; người duyệt là `tu.truongbantc`.

## 8. Ghi chú khi thử

Một số màn hình cuối kỳ (phiếu tự đánh giá, thẩm định, quyết định xếp loại, hội nghị kiểm điểm) cần kỳ đánh giá năm và hồ sơ được lập; bộ dữ liệu này mới nạp kỳ quý IV với mục tiêu và nhiệm vụ, nên các màn hình đó mở được nhưng danh sách có thể trống. Khi gặp thông báo lỗi hoặc màn hình trống mà theo kịch bản phải có dữ liệu, ghi lại tài khoản, bước và ảnh chụp để đối chiếu.

Nguồn dữ liệu duy nhất là `data/seed/da-tenant/du_lieu.py`. Muốn thêm người, đơn vị, mục tiêu hay nhiệm vụ, sửa tệp đó, chạy `python3 -I data/seed/da-tenant/sinh_seed.py` để sinh lại tệp SQL, tài khoản và bảng tài khoản, rồi nạp lại bằng `infra/dev/scripts/seed-da-tenant.sh`.
