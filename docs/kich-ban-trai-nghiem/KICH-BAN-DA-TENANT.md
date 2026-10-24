# Kịch bản trải nghiệm hệ thống với dữ liệu thử đa tenant

Tài liệu này hướng dẫn tự trải nghiệm nền tảng Mission trên môi trường dev bằng bộ dữ liệu thử của ba khách hàng (tenant) cùng nằm trên một tỉnh giả định tên **Phú Lâm**. Mỗi kịch bản đi từng bước theo một tài khoản cụ thể, nói rõ cần bấm vào đâu và hệ thống phải phản hồi thế nào, để người đọc vừa hiểu nghiệp vụ, vừa thấy được phân quyền và cách tách dữ liệu giữa các khách hàng.

Toàn bộ dữ liệu là giả lập: tỉnh Phú Lâm không có thật, tên đơn vị và tên người là hư cấu, số hiệu văn bản căn cứ là văn bản giả định của tỉnh này. Cơ cấu tổ chức, chức danh, chỉ tiêu và nhiệm vụ được chọn cho đúng với thực tế hành chính hiện hành (chính quyền địa phương hai cấp tỉnh, xã; cơ quan tham mưu của Tỉnh ủy; doanh nghiệp nhà nước 100% vốn của tỉnh) để các số liệu, cây mục tiêu và luồng duyệt nhất quán với nhau.

## 1. Chuẩn bị

**Địa chỉ.** Mở trang web Mission của môi trường dev như các lần thử trước (qua đường hầm tới cổng 35000 của server dev).

**Mật khẩu và mã OTP.** Mọi tài khoản trong tài liệu này dùng chung mật khẩu thử và cùng khoá OTP với 13 tài khoản thử cũ của realm `mission`. Vì cùng khoá, mục OTP đã cài trên ứng dụng xác thực cho tài khoản cũ sinh đúng mã cho mọi tài khoản mới; không phải cài thêm. Mã OTP đổi sau mỗi 30 giây và không dùng lại được, nên khi đăng nhập hai tài khoản liên tiếp, hãy chờ mã mới.

**Đổi tài khoản.** Bấm tên người dùng ở góc trên, chọn "Đăng xuất", rồi đăng nhập tài khoản khác. Khi muốn so sánh hai vai trò cùng lúc, mở thêm một cửa sổ ẩn danh.

**Kỳ đánh giá.** Bộ nạp đã tạo hai kỳ ở cả ba tenant: "Quý III/2026" là kỳ trước, có mục tiêu đã duyệt với lịch sử check-in và lệnh khoá kỳ đã được duyệt nhưng chưa khởi chạy; "Quý IV/2026" là kỳ hiện hành chứa toàn bộ mục tiêu, nhiệm vụ, kế hoạch đang thử.

**Bảng đối chiếu.** Muốn biết một tính năng cụ thể đã có dữ liệu để thử hay chưa, vai trò nào dùng được và tài khoản nào nên đăng nhập, tra [`BANG-DOI-CHIEU-VAI-TRO.md`](BANG-DOI-CHIEU-VAI-TRO.md); bảng đó liệt kê đủ 179 chức năng của ma trận phân quyền, ô nào trống đều ghi lý do và cách mở khoá.

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

Bộ nạp đi đúng luồng nghiệp vụ như người dùng thao tác trên màn hình (soạn rồi trình rồi duyệt, giao rồi nhận rồi cập nhật tiến độ), nên mọi bản ghi đều có lịch sử, người soạn, người duyệt, ý kiến và sự kiện như dữ liệu thật. Dữ liệu được bổ sung theo nguyên tắc: với mỗi tài khoản, mọi màn hình mà vai trò của họ được dùng đều phải có dữ liệu đúng trạng thái để thao tác, hoặc có lý do rõ vì sao còn trống. Mỗi lần nạp, bộ nạp in bảng thống kê số bản ghi mới và đã có theo tenant.

**Hai kỳ đánh giá.** Quý III/2026 là kỳ trước: mỗi tenant có hai bộ mục tiêu đã duyệt với ba lần check-in mỗi kết quả then chốt (tiến độ tăng dần đến cuối quý), quản trị đã lập lệnh khoá kỳ theo một văn bản căn cứ và người đứng đầu đã phê duyệt lệnh, nhưng chưa khởi chạy khoá vì tenant chưa phát hành Policy Pack (xem KB-X4). Quý IV/2026 là kỳ hiện hành đang mở.

**Cây mục tiêu và mọi trạng thái của mục tiêu.** Mỗi tenant có 8 đến 10 bộ mục tiêu trong quý IV: một mục tiêu cấp cao nhất đã duyệt (Tỉnh ủy, UBND tỉnh, Công ty), phân rã xuống ban, sở, xí nghiệp, phòng rồi tới mục tiêu cá nhân. Ngoài các bộ đã duyệt, mỗi tenant có đủ ba trạng thái còn lại để thử: một bộ đang **chờ duyệt** (`tu.chuyenvienth2` chờ `tu.chanhvp`; `cq.chuyenviennv` chờ `cq.giamdocsnv`; `dn.nhanvienkd2` chờ `dn.tonggiamdoc`), một bộ bị **trả lại** kèm ý kiến của người duyệt (`tu.phobantc`, `cq.truongphongccvc`, `dn.truongphongkt`) và một bộ còn ở **nháp** chưa trình (`tu.chuyenvienth`, `cq.vienchuctt`, `dn.chuyenviennhansu`).

**Check-in nhiều lần.** Các kết quả then chốt đã duyệt có từ một đến ba lần check-in nên có lịch sử để xem biểu đồ tiến độ; có lần nhảy vọt hơn 50% so với lần trước (đã xác nhận thay đổi lớn) và có lần với mức tự tin thấp kèm vướng mắc (công nhân `dn.congnhan` báo số lần phản ánh mất nước còn cao).

**Nhiệm vụ ở đủ bảy trạng thái.** Mỗi tenant có 11 hoặc 12 nhiệm vụ, phân bổ như sau:

| Trạng thái | Khối Đảng | Khối chính quyền | Doanh nghiệp |
|---|---|---|---|
| Nháp, chưa giao | "Dự thảo hướng dẫn kiểm điểm tập thể, cá nhân năm 2026" (`tu.truongphongcb` soạn) | "Chuẩn bị nội dung phiên họp thường kỳ UBND tỉnh" (`cq.truongphongth`) | "Dự thảo quy chế trả lương theo kết quả KPI năm 2027" (`dn.truongphongtchc`) |
| Đã giao, chờ nhận | "Lập danh sách cán bộ diện Ban Thường vụ quản lý…" (`tu.chuyenviencb`); "Chuẩn bị tài liệu Hội nghị Ban Thường vụ tháng 11" (`tu.chuyenvienth2`); "Cập nhật lịch công tác tuần…" đã giao lại từ `tu.chuyenvienth` sang `tu.chuyenvienth2` và đã nhắc việc | "Tổng hợp tình hình giải ngân vốn đầu tư công…" (`cq.chuyenvienns2`); "Thống kê hồ sơ thủ tục hành chính trễ hạn quý IV" (`cq.vienchuctt`); "Kiểm tra việc chấp hành dự toán năm 2026…" đã giao lại sang `cq.chuyenvienns2`, đã nhắc việc | "Thay thế đồng hồ nước đến hạn kiểm định…" (`dn.congnhan`); "Xử lý phản ánh về chất lượng nước…" (`dn.nhanvienkd2`); "Rà soát danh sách khách hàng nợ tiền nước…" đã giao lại sang `dn.nhanvienkd`, đã nhắc việc |
| Đang thực hiện | "Thẩm định hồ sơ đánh giá cán bộ khối Mặt trận…" 40% có điểm nghẽn đang xử lý; "Xây dựng dự thảo báo cáo sơ kết…" 60% đã được gia hạn; "Giới thiệu quần chúng ưu tú tại thôn Đông Hòa" 30% có đề nghị đổi số lượng chờ duyệt | "Thẩm định dự toán ngân sách năm 2027…" 45% có điểm nghẽn đã giải quyết; "Xây dựng báo cáo kinh tế - xã hội tháng 11" 20% có đề nghị gia hạn chờ duyệt; "Rà soát dữ liệu cán bộ, công chức…" 70% đã được đổi số lượng; "Rà soát, niêm yết công khai thủ tục hành chính" 50% ở xã | "Khắc phục điểm rò rỉ ống phân phối D110…" 60% có điểm nghẽn đang xử lý; "Phát triển khách hàng mới tại khu đô thị phía Đông" 40% đã được gia hạn; "Xây dựng bộ chỉ tiêu KPI năm 2027…" 30% có đề nghị đổi sản phẩm chờ duyệt |
| Chờ nghiệm thu | "Rà soát hồ sơ bổ nhiệm lại cán bộ…" (`tu.chuyenviencb` trình, `tu.truongphongcb` nghiệm thu) | "Rà soát, cập nhật biểu mẫu thủ tục hành chính lĩnh vực đất đai" (`cq.vienchuctt`, `cq.giamdoctt`) | "Ký hợp đồng dịch vụ cấp nước với 20 hộ dân đường Lê Lợi nối dài" (`dn.nhanvienkd`, `dn.truongphongkd`) |
| Hoàn thành | "Tổng hợp ý kiến góp ý dự thảo Quy chế làm việc của Tỉnh ủy" | "Thẩm định phương án phân bổ kinh phí mua sắm tài sản" | "Súc xả tuyến ống cấp nước khu dân cư số 2" |
| Tạm dừng (đề nghị tạm dừng đã duyệt) | "Biên tập Bản tin Thông tin nội bộ số tháng 11" | "Tổ chức lớp bồi dưỡng nghiệp vụ đánh giá cán bộ…" | "Lắp đặt đồng hồ tổng khu vực đo lường số 4" |
| Huỷ (đề nghị huỷ đã duyệt) | "Tổ chức buổi sinh hoạt chuyên đề về Quy định 366-QĐ/TW tại chi bộ" | "Lập báo cáo thống kê tình hình kinh tế - xã hội của xã quý IV" | "Khảo sát nhu cầu đấu nối cấp nước tại xóm Cầu Mới" |

Nhờ vậy, chi tiết nhiệm vụ có đủ nhật ký tiến độ, lịch sử trạng thái, điểm nghẽn (mở, đang xử lý, đã giải quyết), đề nghị điều chỉnh đủ bốn loại (gia hạn, đổi số lượng, đổi sản phẩm, tạm dừng, huỷ) ở cả ba kết cục (chờ duyệt, đã duyệt, từ chối), lần nhắc việc và lần giao lại kèm lý do. Nhiều nhiệm vụ liên kết với kết quả then chốt của mục tiêu cá nhân.

**Kế hoạch công tác quý.** Mỗi tenant có hai kế hoạch quý IV ở trạng thái khác nhau: khối Đảng có kế hoạch Ban Tổ chức (bốn dòng, `tu.phobantc` lập) đang chờ `tu.truongbantc` duyệt và kế hoạch Tỉnh ủy còn nháp; khối chính quyền có kế hoạch Sở Tài chính đã duyệt (bước ký số phụ thuộc xác thực bước lên trên dev) và kế hoạch UBND tỉnh bị Chủ tịch trả lại với ý kiến bổ sung; doanh nghiệp có kế hoạch Xí nghiệp số 1 chờ Giám đốc Xí nghiệp duyệt và kế hoạch Công ty còn nháp. Các dòng kế hoạch lấy từ danh mục công việc hoặc ghi điểm chuẩn riêng, có dòng gắn kết quả then chốt.

**Uỷ quyền khi vắng mặt.** Bí thư Tỉnh ủy uỷ quyền duyệt thay cho Phó Bí thư Thường trực; Chánh Văn phòng giao `tu.chuyenvienth2` check-in thay kết quả then chốt của `tu.chuyenvienth`; Giám đốc Sở Tài chính uỷ quyền duyệt thay cho Phó Giám đốc và giao `cq.chuyenvienns2` check-in thay; Giám đốc Xí nghiệp uỷ quyền duyệt thay cho Tổ trưởng.

**Cấp vai trò có thời hạn.** Quản trị tenant đã lập các đề nghị cấp vai trò: `tu.chuyenvienth2` làm quản lý trực tiếp Phòng Tổng hợp (Chánh Văn phòng đã duyệt, đang hiệu lực) và `tu.ubkt` làm người thẩm định toàn Đảng bộ (chờ duyệt); `cq.chuyenvienns2` làm quản lý Phòng Chuyển đổi số (`cq.quantri` lập, `cq.quantri2` duyệt, đúng nguyên tắc bốn mắt) và `cq.chuyenvienth` làm quản lý Phòng Tổng hợp (chờ duyệt); `dn.nhanvienkd` làm quản lý Phòng Kinh doanh (Tổng Giám đốc đã duyệt).

**Policy Pack mẫu.** Mỗi tenant có một gói cấu hình nghiệp vụ ở bản nháp để quản trị nghiệp vụ xem công thức, ba bảng quyết định xếp loại và bộ ca mẫu đã duyệt: khối Đảng dùng gói theo Quy định mới của Trung ương, khối chính quyền và doanh nghiệp dùng gói theo Nghị định 335/2025. Gói chưa phát hành, nên muốn chạy khoá kỳ và xem hồ sơ đánh giá phải đi qua KB-X4.

**Danh mục công việc và căn cứ.** Mỗi tenant có một danh mục công việc đang hiệu lực (6 hoặc 7 mục có mức khó, điểm chuẩn, tiêu chuẩn chất lượng, yêu cầu minh chứng) và ba hoặc bốn văn bản căn cứ (nghị quyết, kế hoạch, chương trình, chỉ thị, quyết định giao chỉ tiêu).

**Chưa có sẵn, có lý do.** Minh chứng (tệp thật phải qua quét vi-rút, nộp ở KB-X5), hồ sơ đánh giá cuối kỳ và xếp loại (chỉ sinh khi khoá kỳ với gói đã phát hành, KB-X4), khiếu nại và mở lại kỳ (cần kết quả xếp loại), đăng ký nhiệm vụ từ danh mục (dữ liệu tổ chức thử chưa có vị trí việc làm), trợ lý AI (dịch vụ chưa bật trên dev). Chi tiết từng chức năng ở bảng đối chiếu.

## 4. Kịch bản khối Đảng (Đảng bộ tỉnh Phú Lâm)

### KB-D1. Đảng viên xem việc của mình và cập nhật tiến độ

Tài khoản: `tu.chuyenviencb` (Chuyên viên Phòng Cán bộ, Ban Tổ chức Tỉnh ủy).

1. Đăng nhập. Trang chủ "Việc của tôi hôm nay" hiện các nhiệm vụ: "Thẩm định hồ sơ đánh giá cán bộ khối Mặt trận Tổ quốc và các đoàn thể tỉnh" (đang thực hiện, 40%, có điểm nghẽn đang được trưởng phòng xử lý), "Lập danh sách cán bộ diện Ban Thường vụ quản lý thuộc đối tượng đánh giá năm 2026" (mới giao) và "Rà soát hồ sơ bổ nhiệm lại cán bộ diện Ban Thường vụ quản lý đến hạn trong quý IV" (đã trình, chờ nghiệm thu).
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

### KB-D7. Điểm nghẽn và đề nghị điều chỉnh nhiệm vụ

Tài khoản: `tu.truongphongcb`, `tu.chuyenvienth`, `tu.bithuchibo`, `tu.dangvien`.

1. Đăng nhập `tu.truongphongcb`, mở nhiệm vụ "Thẩm định hồ sơ đánh giá cán bộ khối Mặt trận Tổ quốc…". Phần điểm nghẽn có một điểm nghẽn do chuyên viên báo (nhóm nguyên nhân phối hợp, mức ảnh hưởng trung bình) đang ở trạng thái "đang xử lý" với người xử lý là chính trưởng phòng. Ghi kết quả xử lý và đóng điểm nghẽn.
2. Đăng nhập `tu.chuyenvienth`, mở nhiệm vụ "Xây dựng dự thảo báo cáo sơ kết công tác xây dựng Đảng quý IV". Lịch sử đề nghị có một đề nghị gia hạn đã được trưởng phòng chấp nhận (nguyên nhân khách quan), hạn mới đã thay vào nhiệm vụ. Thử gửi thêm một đề nghị gia hạn nữa: hệ thống chỉ cho gửi khi không còn đề nghị cùng loại đang chờ, và lý do phải từ 20 ký tự.
3. Đăng nhập `tu.bithuchibo`, mở nhiệm vụ "Giới thiệu quần chúng ưu tú tại thôn Đông Hòa…". Có đề nghị đổi số lượng của đảng viên đang chờ quyết định; chọn nguyên nhân (khách quan hay chủ quan), chấp nhận hoặc từ chối với ý kiến. Đăng nhập `tu.dangvien` để thấy kết quả và lý do.

Kết quả mong đợi: chỉ người chủ trì gửi được đề nghị và báo điểm nghẽn; chỉ người giao việc quyết định đề nghị và đóng điểm nghẽn; mọi quyết định đều lưu nguyên nhân do người giao xác định, tách khỏi nguyên nhân người thực hiện tự khai.

### KB-D8. Nghiệm thu, tạm dừng, huỷ, giao lại và nhắc việc

Tài khoản: `tu.truongphongcb`, `tu.truongphongth`, `tu.chuyenvienth2`.

1. `tu.truongphongcb` mở "Rà soát hồ sơ bổ nhiệm lại cán bộ…" đang chờ nghiệm thu (chuyên viên đã cập nhật 100% và trình). Nghiệm thu hoàn thành; nhiệm vụ chuyển sang hoàn thành và tính vào kết quả của chuyên viên. Muốn chấm chất lượng theo 5 mức trên minh chứng, làm KB-X5 trước.
2. `tu.truongphongth` mở "Biên tập Bản tin Thông tin nội bộ số tháng 11": nhiệm vụ đang tạm dừng vì đề nghị tạm dừng của `tu.chuyenvienth2` đã được chấp nhận; lịch sử trạng thái ghi rõ. Dùng "Tiếp tục" để đưa về đang thực hiện.
3. Cũng `tu.truongphongth`, mở "Cập nhật lịch công tác tuần của Thường trực Tỉnh ủy…": việc đã được giao lại từ `tu.chuyenvienth` sang `tu.chuyenvienth2` với lý do ghi trong lịch sử và đã được nhắc việc một lần. Nhắc lại ngay: hệ thống từ chối vì cùng người, cùng việc chỉ nhắc một lần trong 24 giờ.
4. `tu.truongphongth` mở nhiệm vụ nháp "Dự thảo hướng dẫn kiểm điểm…" của Phòng Cán bộ: không thấy, vì nháp chỉ người soạn (`tu.truongphongcb`) thấy. Đăng nhập `tu.truongphongcb` để hoàn thiện và giao.
5. `tu.bithuchibo` mở "Tổ chức buổi sinh hoạt chuyên đề về Quy định 366-QĐ/TW tại chi bộ": đã huỷ theo đề nghị của đảng viên với lý do đã duyệt; không còn nút thao tác.

### KB-D9. Kế hoạch công tác quý của Ban Tổ chức

Tài khoản: `tu.phobantc` rồi `tu.truongbantc`.

1. `tu.phobantc` vào "Kế hoạch quý", mở kế hoạch quý IV của Ban Tổ chức: bốn dòng lấy từ danh mục công việc và một dòng gắn kết quả then chốt, trạng thái chờ duyệt, không sửa được.
2. `tu.truongbantc` mở cùng kế hoạch, xem kết quả kiểm tra (đủ trường, có điểm chuẩn, một người chủ trì mỗi dòng). Trả lại với ý kiến; `tu.phobantc` sửa và gửi lại; `tu.truongbantc` duyệt và ký số (bước ký số cần xác thực bước lên; nếu dev chưa cấu hình, hệ thống báo rõ).
3. `tu.phobithu` mở kế hoạch nháp của Tỉnh ủy, thêm dòng rồi gửi duyệt cho `tu.bithu`.

### KB-D10. Uỷ quyền khi vắng mặt và cấp vai trò có thời hạn

Tài khoản: `tu.phobithu`, `tu.chuyenvienth2`, `tu.quantri`, `tu.chanhvp`.

1. `tu.phobithu` vào hàng chờ phê duyệt: nhờ uỷ quyền duyệt thay của Bí thư (đang hiệu lực, có thời hạn), Phó Bí thư thấy và duyệt được bộ mục tiêu trình lên Tỉnh ủy, nhưng không duyệt được bộ do chính mình soạn.
2. `tu.chuyenvienth2` mở kết quả then chốt "Số báo cáo tổng hợp nộp đúng hạn" của `tu.chuyenvienth`: có nút check-in nhờ uỷ quyền check-in thay do Chánh Văn phòng giao; lần check-in ghi rõ người thực hiện thay.
3. `tu.quantri` vào "Vai trò theo đơn vị": đề nghị cấp vai trò người thẩm định cho `tu.ubkt` đang chờ duyệt thứ hai; `tu.chanhvp` (hoặc `tu.bithu`) duyệt. Vai trò quản lý trực tiếp Phòng Tổng hợp cấp cho `tu.chuyenvienth2` đã hiệu lực, nên tài khoản này giao được việc trong phòng (thử ở "Giao việc").
4. `tu.quantri` thử tự duyệt đề nghị do mình lập: bị từ chối (nguyên tắc bốn mắt). Xem "Hồ sơ quyền" của `tu.chuyenvienth2`: vai trò gốc, vai trò cấp thêm có hạn, uỷ quyền nhận được.

### KB-D11. Kỳ trước và lệnh khoá kỳ

Tài khoản: `tu.quantri`, `tu.bithu`, `tu.truongphongcb`.

1. `tu.quantri` vào "Kỳ đánh giá": quý III/2026 có lệnh khoá kỳ đã được Bí thư phê duyệt, chưa khởi chạy; quý IV đang mở. Khởi chạy lệnh ngay sẽ không sinh hồ sơ đánh giá vì tenant chưa phát hành Policy Pack; làm KB-X4 trước.
2. `tu.bithu` mở lệnh khoá: thấy văn bản căn cứ, người lập, thời điểm duyệt; không khởi chạy được vì người khởi chạy phải khác người duyệt.
3. `tu.truongphongcb` vào "Mục tiêu, OKR", chọn kỳ quý III: hai bộ mục tiêu đã duyệt với ba lần check-in mỗi kết quả then chốt; bảng điều hành so sánh quý IV với quý III.

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

### KB-C7. Điểm nghẽn đã giải quyết, đề nghị chờ duyệt và đã duyệt

Tài khoản: `cq.chuyenvienns`, `cq.truongphongth`, `cq.chuyenviennv`.

1. `cq.chuyenvienns` mở "Thẩm định dự toán ngân sách năm 2027…": điểm nghẽn về đơn vị nộp hồ sơ chậm đã được trưởng phòng nhận xử lý và đóng với kết quả ghi rõ; nhật ký tiến độ có hai mốc.
2. `cq.truongphongth` mở "Xây dựng báo cáo tình hình kinh tế - xã hội tháng 11": đề nghị gia hạn của chuyên viên đang chờ; xem trước hệ quả (hạn mới vẫn trong kỳ) rồi quyết định.
3. `cq.chuyenviennv` mở "Rà soát dữ liệu cán bộ, công chức…": số lượng sản phẩm đã đổi theo đề nghị được duyệt; thử rút một đề nghị đang chờ (chỉ rút được đề nghị của chính mình và còn chờ duyệt).

### KB-C8. Kế hoạch quý đã duyệt và kế hoạch bị trả lại

Tài khoản: `cq.phogiamdocstc`, `cq.giamdocstc`, `cq.phochutich`, `cq.chutich`.

1. `cq.phogiamdocstc` mở kế hoạch quý IV của Sở Tài chính: đã duyệt, không sửa trực tiếp; tạo "Điều chỉnh" để sinh phiên bản 2, thêm dòng, gửi duyệt.
2. `cq.giamdocstc` so sánh hai phiên bản, duyệt và ký số phiên bản 2.
3. `cq.phochutich` mở kế hoạch UBND tỉnh: bị Chủ tịch trả lại với ý kiến "Đề nghị bổ sung dòng về sắp xếp tổ chức…"; bổ sung dòng, gửi lại; `cq.chutich` duyệt.

### KB-C9. Bốn mắt khi cấp quyền và uỷ quyền ở Sở Tài chính

Tài khoản: `cq.quantri`, `cq.quantri2`, `cq.chuyenvienns2`, `cq.phogiamdocstc`.

1. `cq.quantri` vào "Vai trò theo đơn vị": vai trò quản lý Phòng Chuyển đổi số của `cq.chuyenvienns2` đã hiệu lực do `cq.quantri2` duyệt; đề nghị cấp quản lý Phòng Tổng hợp cho `cq.chuyenvienth` đang chờ. `cq.quantri` không duyệt được đề nghị của chính mình; `cq.quantri2` duyệt hoặc từ chối.
2. `cq.chuyenvienns2` check-in thay kết quả then chốt của `cq.chuyenvienns` nhờ uỷ quyền do Giám đốc Sở giao; `cq.phogiamdocstc` thấy hàng chờ duyệt của Sở nhờ uỷ quyền duyệt thay.
3. `cq.giamdocstc` thu hồi uỷ quyền duyệt thay trước hạn; `cq.phogiamdocstc` không còn thấy hàng chờ.

### KB-C10. Kỳ trước, lệnh khoá và bảng điều hành so sánh quý

Tài khoản: `cq.quantri`, `cq.chutich`.

1. `cq.quantri` xem quý III/2026: lệnh khoá kỳ theo Công văn của UBND tỉnh đã được Chủ tịch duyệt; quý IV đang mở với 12 nhiệm vụ, 9 bộ mục tiêu.
2. `cq.chutich` mở "Bảng điều hành": tỷ lệ việc đúng hạn, việc chậm, điểm nghẽn mở, mục tiêu chờ duyệt theo sở, ngành; so với quý trước; nhắc người chủ trì việc chậm và giao lại việc chưa nhận ngay trên bảng.

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

### KB-N4. Công nhân báo điểm nghẽn, check-in tự tin thấp

Tài khoản: `dn.congnhan`, `dn.totruong`, `dn.truongphongtchc`.

1. `dn.congnhan` mở "Khắc phục điểm rò rỉ ống phân phối D110…": điểm nghẽn thiếu vật tư đang được Tổ trưởng xử lý; kết quả then chốt "Số lần khách hàng phản ánh mất nước" có lần check-in mức tự tin thấp kèm vướng mắc.
2. `dn.totruong` đóng điểm nghẽn với kết quả, xem lịch sử check-in của công nhân trên cây mục tiêu của Xí nghiệp.
3. `dn.truongphongtchc` mở "Xây dựng bộ chỉ tiêu KPI năm 2027…": đề nghị đổi sản phẩm của chuyên viên nhân sự chờ quyết định.

### KB-N5. Kế hoạch Xí nghiệp và uỷ quyền không được tự duyệt

Tài khoản: `dn.totruong`, `dn.giamdocxn`.

1. `dn.totruong` lập kế hoạch quý IV của Xí nghiệp số 1 (đang chờ duyệt). Dù Giám đốc Xí nghiệp đã uỷ quyền duyệt thay cho Tổ trưởng, Tổ trưởng không duyệt được kế hoạch do chính mình lập.
2. `dn.giamdocxn` duyệt kế hoạch; `dn.tonggiamdoc` hoàn thiện kế hoạch nháp của Công ty và gửi `dn.chutich` duyệt.

### KB-N6. Cấp vai trò quản lý có thời hạn cho nhân viên

Tài khoản: `dn.quantri`, `dn.nhanvienkd`, `dn.nhanvienkd2`.

1. `dn.quantri` xem vai trò quản lý Phòng Kinh doanh cấp cho `dn.nhanvienkd` (Tổng Giám đốc đã duyệt, có hạn 60 ngày).
2. `dn.nhanvienkd` giao một việc cho `dn.nhanvienkd2` trong phòng; `dn.nhanvienkd2` nhận việc. Khi hết hạn vai trò, nút giao việc biến mất.

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

### KB-X4. Phát hành Policy Pack và khoá kỳ quý trước

Làm lần lượt ở mỗi tenant với bộ tài khoản tương ứng (khối Đảng: `tu.chuyenvientcdv`, `tu.bithu`, `tu.quantri`; khối chính quyền: `cq.truongphongccvc`, `cq.chutich`, `cq.quantri`; doanh nghiệp: `dn.chuyenviennhansu`, `dn.chutich`, `dn.quantri`).

1. Quản trị nghiệp vụ vào "Cấu hình nghiệp vụ": gói mẫu ở bản nháp với công thức, ba bảng quyết định và bộ ca mẫu đã duyệt. Chạy lại bộ ca mẫu, xem kết quả đạt, gửi phê duyệt phát hành.
2. Cấp có thẩm quyền xem kết quả ca mẫu rồi ký số phê duyệt phát hành (cần xác thực bước lên).
3. Quản trị tenant khởi chạy lệnh khoá kỳ quý III đã được duyệt. Theo dõi tiến trình: hệ thống tạo hồ sơ đánh giá cho từng người trong kỳ, tính kết quả theo gói vừa phát hành.
4. Sau khi khoá: cán bộ xem phiếu tự đánh giá và nộp (ký số); người thẩm định xem hàng đợi; cấp quyết định xếp loại hàng loạt; từ đây mở được khiếu nại, hội nghị kiểm điểm, mở lại kỳ.

Nếu bước ký số hay xác thực bước lên chưa chạy trên dev, ghi lại thông báo; phần còn lại của bộ dữ liệu không phụ thuộc bước này.

### KB-X5. Nộp minh chứng và nghiệm thu chất lượng 5 mức

1. Người chủ trì một nhiệm vụ đang thực hiện (ví dụ `cq.vienchuctt` với việc chờ nghiệm thu, hoặc `dn.nhanvienkd` với hợp đồng cấp nước) tải lên một tệp bất kỳ làm minh chứng, chọn sản phẩm tương ứng, nộp.
2. Hệ thống quét tệp rồi mới cho nộp; tệp hiện trong "Minh chứng" của nhiệm vụ.
3. Người giao việc mở minh chứng, kiểm tra toàn vẹn, xác nhận nghiệm thu theo 5 mức chất lượng hoặc yêu cầu bổ sung.
4. Người kiểm tra (`tu.ubkt`, `cq.thanhtra`, `dn.kiemsoatvien`) đi từ bảng điều hành xuống tới minh chứng vừa nộp; lần mở tệp được ghi vào nhật ký truy cập.

## 8. Ghi chú khi thử

Các màn hình cuối kỳ (phiếu tự đánh giá, thẩm định, quyết định xếp loại, hội nghị kiểm điểm, khiếu nại, mở lại kỳ) chỉ có dữ liệu sau khi làm KB-X4; minh chứng chỉ có sau KB-X5. Bảng đối chiếu `BANG-DOI-CHIEU-VAI-TRO.md` ghi tình trạng từng chức năng. Các bước ký số, duyệt kế hoạch, uỷ quyền và cấp vai trò cần xác thực bước lên (MFA mức cao) ở máy chủ; bộ nạp vẫn thử các bước đó và ghi vào danh sách lỗi của bảng thống kê nếu máy chủ dev từ chối, nên khi một mục trong tài liệu này không thấy dữ liệu, xem lại bảng thống kê của lần nạp gần nhất trước khi ghi nhận lỗi. Khi gặp thông báo lỗi hoặc màn hình trống mà theo kịch bản phải có dữ liệu, ghi lại tài khoản, bước và ảnh chụp để đối chiếu.

Nguồn dữ liệu duy nhất là `data/seed/da-tenant/du_lieu.py`. Muốn thêm người, đơn vị, mục tiêu, nhiệm vụ, kế hoạch hay uỷ quyền, sửa tệp đó, chạy `python3 -I data/seed/da-tenant/sinh_seed.py` để sinh lại tệp SQL, tài khoản, bảng tài khoản và Policy Pack mẫu, chạy `python3 -I data/seed/da-tenant/sinh_bang_doi_chieu.py` để sinh lại bảng đối chiếu, rồi nạp lại bằng `infra/dev/scripts/seed-da-tenant.sh`.
