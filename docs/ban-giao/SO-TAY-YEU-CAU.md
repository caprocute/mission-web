# Sổ tay yêu cầu chuẩn của anh Hiếu – nền tảng cho quy trình phát triển phần mềm bằng AI

> Người đặt yêu cầu: anh Hoàng Khắc Hiếu
> Ngày lập: 19/10/2026
> Nguồn khởi tạo: các yêu cầu anh Hiếu đã đưa ra trong dự án Mission (nền tảng đánh giá cán bộ OKR/KPI)

---

## 1. Mục đích và luật ghi chép

### 1.1. Mục đích

Tài liệu này là nguồn duy nhất tập hợp các yêu cầu thường trực mà anh Hiếu đã nêu qua các dự án. Mục tiêu là để mọi phiên làm việc của Claude (và mọi AI agent khác) áp dụng nhất quán những yêu cầu đó ở các dự án sau mà không cần anh Hiếu nhắc lại từ đầu. Sổ tay này đóng vai trò nền móng cho quy trình phát triển phần mềm bằng AI: từ cách giao tiếp, cách lập bộ tài liệu thiết kế, cách tổ chức phát triển cho tới cách xử lý hạ tầng và bảo mật.

### 1.2. Luật ghi chép bắt buộc

1. **Đọc trước khi làm.** Mọi phiên Claude bắt buộc phải đọc toàn bộ tệp này trước khi bắt đầu bất kỳ nhiệm vụ nào cho anh Hiếu, và phải tuân thủ các quy tắc trong đó trừ khi anh Hiếu chỉ đạo khác một cách rõ ràng.
2. **Ghi ngay khi phát sinh.** Ngay khi anh Hiếu nêu một yêu cầu mang tính thường trực (tức là yêu cầu có thể áp dụng lại cho lần sau, không chỉ cho một lần thao tác), phiên Claude đang làm việc phải bổ sung yêu cầu đó vào sổ tay này ngay lập tức, kèm ngày, tên dự án và luồng hội thoại nguồn, đồng thời thêm một dòng vào phụ lục "Nhật ký bổ sung".
3. **Viết thành quy tắc tái sử dụng.** Yêu cầu phải được diễn đạt thành quy tắc tổng quát dùng được cho các dự án tương lai, không ghi các chi tiết vụn vặt chỉ đúng với một dự án. Ví dụ cụ thể của dự án chỉ được dùng để minh hoạ.
4. **Không ghi bí mật.** Tuyệt đối không ghi địa chỉ máy chủ, tên đăng nhập, mật khẩu, token hay bất kỳ thông tin bí mật nào vào sổ tay này.
5. **Không xoá, chỉ sửa có ghi vết.** Khi một quy tắc thay đổi, cập nhật nội dung quy tắc và ghi lý do thay đổi vào nhật ký bổ sung thay vì xoá âm thầm.

### 1.3. Cấu trúc một quy tắc

Mỗi quy tắc được trình bày theo cùng một khuôn mẫu sau:

- **Mã:** `YC-<nhóm>-<số>`, trong đó nhóm là một trong các mã GT, TK, PT, HT (có thể bổ sung nhóm mới khi cần), số đánh liên tục trong nhóm và không tái sử dụng.
- **Nội dung:** phát biểu quy tắc bằng câu văn đầy đủ.
- **Lý do:** vấn đề mà quy tắc giúp phòng tránh.
- **Kiểm tra:** cách xác minh quy tắc đã được tuân thủ.
- **Nguồn:** dự án và ngày phát sinh yêu cầu.

---

## 2. Các nhóm quy tắc

### 2.1. Nhóm GT – Giao tiếp và cách làm việc

#### YC-GT-01 – Trả lời bằng tiếng Việt chuyên nghiệp, diễn giải đầy đủ

- **Nội dung:** Mọi câu trả lời, báo cáo và tài liệu gửi anh Hiếu phải viết bằng tiếng Việt chuyên nghiệp, thành câu văn hoàn chỉnh và diễn giải đầy đủ ý. Không viết kiểu nén ý, gạch đầu dòng cụt hay lạm dụng ký hiệu viết tắt khiến người đọc phải tự suy luận.
- **Lý do:** Tránh việc anh Hiếu phải đọc lại nhiều lần hoặc hỏi lại vì nội dung quá cô đọng, đồng thời giúp tài liệu có thể chuyển thẳng cho lãnh đạo và đồng nghiệp.
- **Kiểm tra:** Đọc lại câu trả lời và xác nhận mỗi ý chính đều là câu đầy đủ chủ ngữ, vị ngữ, có giải thích lý do hoặc hệ quả khi cần.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-GT-02 – Không hỏi xác nhận lại việc đã giao rõ

- **Nội dung:** Khi anh Hiếu đã giao việc rõ ràng thì phải bắt tay vào làm ngay, không hỏi xác nhận lại. Nếu thực sự buộc phải hỏi, chỉ gói câu hỏi trong một dòng và luôn kèm phương án đề xuất mặc định để anh Hiếu chỉ cần đồng ý hoặc sửa.
- **Lý do:** Anh Hiếu đã phản ánh việc "hỏi thừa quá"; các câu hỏi không cần thiết làm chậm tiến độ và tốn thời gian của người giao việc.
- **Kiểm tra:** Rà lại phiên làm việc và xác nhận không có câu hỏi nào về điều đã được nêu rõ trong yêu cầu; mọi câu hỏi còn lại đều nằm trên một dòng và có đề xuất mặc định.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-GT-03 – Chủ động chọn mặc định hợp lý và nêu rõ giả định

- **Nội dung:** Khi gặp điểm chưa rõ, phải chủ động chọn phương án mặc định hợp lý nhất, tiếp tục công việc và ghi rõ giả định đã dùng trong báo cáo, thay vì dừng lại chờ hỏi ý kiến.
- **Lý do:** Tránh tình trạng công việc bị treo chỉ vì một chi tiết nhỏ, trong khi giả định được nêu rõ vẫn cho phép anh Hiếu điều chỉnh về sau.
- **Kiểm tra:** Báo cáo kết quả có mục "Giả định" liệt kê các lựa chọn mặc định đã đưa ra, và công việc không bị dừng vì các điểm chưa rõ có thể tự quyết.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-GT-04 – Ý thức chi phí nhưng ưu tiên tốc độ khi được yêu cầu

- **Nội dung:** Khi chạy nhiều agent song song phải cân nhắc mức tiêu thụ quota và tránh lãng phí. Tuy nhiên, khi anh Hiếu yêu cầu đẩy nhanh tiến độ thì ưu tiên tốc độ, kể cả tăng số luồng song song như anh Hiếu đã yêu cầu trong dự án Mission. Lưu ý: dù tăng tốc, số luồng chạy đồng thời vẫn không được vượt quá 5, xem YC-PT-12.
- **Lý do:** Cân bằng giữa chi phí và tiến độ theo đúng ưu tiên của người giao việc, tránh cả hai thái cực là đốt quota vô ích hoặc làm quá chậm khi cần gấp.
- **Kiểm tra:** Kế hoạch chạy agent nêu rõ số luồng và lý do chọn số luồng đó; khi anh Hiếu yêu cầu tăng tốc thì số luồng được điều chỉnh tương ứng.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

### 2.2. Nhóm TK – Bộ tài liệu thiết kế (bắt buộc trước khi code)

Nguyên tắc chung của nhóm: không được bắt đầu viết mã khi bộ tài liệu thiết kế dưới đây chưa đầy đủ và chưa liên kết chặt chẽ với nhau.

#### YC-TK-01 – Thiết kế kiến trúc chi tiết mức implement, có ADR

- **Nội dung:** Tài liệu kiến trúc phải chi tiết tới mức đội phát triển (hoặc AI agent) có thể implement trực tiếp, và phải phủ đủ mọi hạng mục trong checklist đánh giá kiến trúc. Với hệ thống lớn, phải chọn kiểu kiến trúc phù hợp cho từng phần thay vì áp một kiểu chung cho tất cả; ưu tiên các công nghệ anh Hiếu đã quen dùng. Mọi quyết định kiến trúc quan trọng phải được ghi thành ADR (Architecture Decision Record).
- **Lý do:** Tránh tài liệu kiến trúc chung chung khiến người phát triển phải tự đoán, tránh áp đặt một kiểu kiến trúc không phù hợp cho mọi phần, và giữ được lý do của các quyết định để truy vết về sau.
- **Kiểm tra:** Đối chiếu tài liệu với checklist đánh giá kiến trúc và xác nhận không thiếu hạng mục nào; mỗi phần hệ thống có ghi kiểu kiến trúc đã chọn kèm lý do; mỗi quyết định lớn có ADR tương ứng.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-TK-02 – Đặc tả quy trình nghiệp vụ tới cấp màn hình, có giao diện mẫu

- **Nội dung:** Đặc tả quy trình nghiệp vụ phải theo biểu mẫu của dự án MRC, chi tiết tới cấp từng màn hình, và đi kèm giao diện mẫu đầy đủ theo phong cách Viettel.
- **Lý do:** Tránh việc nghiệp vụ chỉ được mô tả ở mức tổng quát, dẫn tới giao diện làm ra không khớp với quy trình thực tế hoặc thiếu màn hình.
- **Kiểm tra:** Mỗi bước trong quy trình nghiệp vụ đều trỏ tới ít nhất một màn hình có mã SCR, và mỗi màn hình đều có giao diện mẫu tương ứng theo đúng phong cách quy định.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-TK-03 – Thiết kế cơ sở dữ liệu chi tiết

- **Nội dung:** Phải có tài liệu thiết kế cơ sở dữ liệu chi tiết tới bảng, cột, khoá, chỉ mục và ràng buộc. Nếu hệ thống theo kiến trúc microservice thì phải mô tả đầy đủ cơ sở dữ liệu của từng service và cơ chế đồng bộ dữ liệu giữa các service.
- **Lý do:** Tránh việc mỗi người phát triển tự thiết kế bảng theo cách riêng, gây trùng lặp, thiếu nhất quán và lỗi đồng bộ dữ liệu giữa các service.
- **Kiểm tra:** Mỗi service có phần mô tả CSDL riêng; mọi luồng dữ liệu liên service đều có cơ chế đồng bộ được mô tả (sự kiện EVT, API, CDC hoặc tương đương).
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-TK-04 – Tài liệu triển khai đầy đủ

- **Nội dung:** Tài liệu triển khai phải bao gồm các yêu cầu hiệu năng, phương án sao lưu và dự phòng, cơ chế tự động co giãn (autoscale) theo tải, cách triển khai trên Kubernetes và các biện pháp an toàn thông tin theo checklist ATTT.
- **Lý do:** Tránh việc hệ thống chạy được trên môi trường phát triển nhưng không đáp ứng được vận hành thật về tải, khả năng phục hồi và an toàn thông tin.
- **Kiểm tra:** Đối chiếu tài liệu với checklist ATTT và xác nhận đủ năm phần: hiệu năng, sao lưu/dự phòng, autoscale, Kubernetes, ATTT.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-TK-05 – Tài liệu định cỡ theo các quy mô triển khai

- **Nội dung:** Phải có tài liệu định cỡ (sizing) tính toán tài nguyên hạ tầng cần thiết cho từng quy mô triển khai khác nhau (ví dụ quy mô nhỏ, vừa, lớn), có giả định đầu vào và cách tính rõ ràng.
- **Lý do:** Giúp lãnh đạo và bộ phận hạ tầng dự trù chi phí, tránh thiếu hoặc thừa tài nguyên khi triển khai thật.
- **Kiểm tra:** Tài liệu có bảng sizing cho ít nhất các quy mô đã thống nhất, mỗi con số đều truy được về giả định đầu vào.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-TK-06 – Tài liệu phân quyền và ma trận phân quyền

- **Nội dung:** Phải có tài liệu phân quyền kèm ma trận phân quyền theo ba chiều vai trò × chức năng × phạm vi dữ liệu. Những ô được điền dựa trên suy luận (chưa có nguồn xác nhận) phải được đánh dấu rõ để chờ chốt.
- **Lý do:** Tránh việc phân quyền được làm theo cảm tính trong mã nguồn, và tránh nhầm lẫn giữa quyền đã được xác nhận với quyền do người viết tự suy ra.
- **Kiểm tra:** Ma trận phủ đủ mọi vai trò và mọi chức năng; mọi ô suy luận đều có ký hiệu đánh dấu và được liệt kê trong danh sách câu hỏi mở.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-TK-07 – Các tài liệu liên kết logic và dùng chung bộ mã

- **Nội dung:** Toàn bộ tài liệu thiết kế phải liên kết logic chặt chẽ với nhau, dùng chung một bộ mã định danh (ADR, SVC, MOD, EVT, QT, SCR, NFR, SEC) và có truy vết chéo giữa các tài liệu.
- **Lý do:** Tránh tình trạng mỗi tài liệu nói một kiểu, mâu thuẫn hoặc không thể lần ra một yêu cầu được hiện thực ở đâu.
- **Kiểm tra:** Chọn ngẫu nhiên một mã (ví dụ một màn hình SCR) và lần được tới quy trình QT, service SVC, sự kiện EVT, yêu cầu phi chức năng NFR và yêu cầu bảo mật SEC liên quan; không có mã nào bị treo hoặc trùng nghĩa.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-TK-08 – Không bỏ sót màn hình cấu hình và quản trị

- **Nội dung:** Mọi engine và mọi phần cấu hình của hệ thống đều phải có giao diện quản trị tương ứng, ví dụ giao diện cấu hình quy trình động BPMN. Phải rà soát có chủ đích danh sách engine và cấu hình để bảo đảm không thiếu màn hình nào.
- **Lý do:** Các màn hình cấu hình và quản trị thường bị quên vì không nằm trong luồng nghiệp vụ chính, dẫn tới việc vận hành phải sửa trực tiếp cơ sở dữ liệu hoặc tệp cấu hình.
- **Kiểm tra:** Lập bảng đối chiếu "engine/cấu hình ↔ màn hình quản trị" và xác nhận mọi dòng đều có mã SCR.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-TK-09 – Trả lời góp ý của lãnh đạo bằng phân tích có số liệu

- **Nội dung:** Mỗi góp ý của lãnh đạo phải được trả lời rõ ràng bằng phân tích có số liệu cụ thể, không trả lời chung chung. Ví dụ: phương án giao diện riêng theo từng tenant, nguy cơ nghẽn engine BPMN khi có nhiều tenant, ảnh hưởng hiệu năng của các lớp bảo vệ và mã hoá.
- **Lý do:** Lãnh đạo cần căn cứ định lượng để ra quyết định; câu trả lời định tính dễ bị bác bỏ hoặc dẫn tới quyết định sai.
- **Kiểm tra:** Mỗi góp ý có mục trả lời riêng, nêu số liệu (thông lượng, độ trễ, tài nguyên, chi phí...) và kết luận/khuyến nghị rõ ràng.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-TK-10 – Ghi nhận và hỏi chốt các điểm mâu thuẫn

- **Nội dung:** Mọi điểm mâu thuẫn giữa các tài liệu phải được ghi nhận lại (trên wiki, ở trang quyết định hoặc trang câu hỏi mở) và đưa ra để anh Hiếu chốt, không được tự ý chọn một bên rồi bỏ qua.
- **Lý do:** Tránh việc mâu thuẫn bị che giấu và chỉ lộ ra khi đã viết mã, lúc đó chi phí sửa lớn hơn nhiều.
- **Kiểm tra:** Trang quyết định/câu hỏi mở trên wiki liệt kê đủ các mâu thuẫn đã phát hiện, mỗi mục có trạng thái (chờ chốt/đã chốt) và quyết định cuối cùng.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-TK-11 – Tư duy lại trải nghiệm người dùng (UX) và giải pháp màn hình nghiệp vụ trên cơ sở kết hợp chuẩn quốc tế (Top 10 ứng dụng) với quy định Việt Nam

- **Nội dung:** Khi thiết kế giải pháp và trải nghiệm người dùng cho các màn hình nghiệp vụ, phải khảo sát và học hỏi từ tối thiểu 10 nền tảng chuẩn mực hàng đầu thế giới trong lĩnh vực đó (ví dụ với OKR/KPI và quản trị hiệu suất: Lattice, 15Five, Microsoft Viva Goals, Workboard, Perdoo, Profit.co, Betterworks, Leapsome, Culture Amp, Linear), sau đó kết hợp nhuần nhuyễn với các quy định pháp lý và đặc thù quản trị tại Việt Nam (ví dụ QĐ 366-QĐ/TW, NĐ 335/2025, NĐ 233/2026). Giải pháp đưa ra phải tối giản, tự nhiên, gom các thao tác liên quan về một luồng trực quan tại chỗ (inline check-in, ngăn kéo chi tiết, tự động tổng hợp dữ liệu minh chứng), đảm bảo thao tác thường ngày không quá 3 bước chạm và người dùng nhìn vào hiểu ngay việc cần làm mà không bị choáng ngợp bởi hàng chục trường dữ liệu hay màn hình rời rạc.
- **Lý do:** Nếu chỉ dịch cơ học các biểu mẫu hành chính hoặc quy trình giấy tờ lên phần mềm thì giao diện sẽ cực kỳ phức tạp, nặng nề và khó sử dụng; ngược lại nếu chỉ sao chép phần mềm nước ngoài thì không đáp ứng được quy định pháp lý của Nhà nước.
- **Kiểm tra:** Có tài liệu đối chiếu giải pháp UX với Top 10 ứng dụng chuẩn quốc tế và quy định Việt Nam; mỗi màn hình nghiệp vụ đều có luồng thao tác chính ≤ 3 bước chạm, phân tách rõ lớp thông tin trọng tâm và lớp chi tiết mở rộng.
- **Nguồn:** Dự án Mission, ngày 20/10/2026, kênh điều phối Antigravity (anh Hiếu: "tôi xem qua giao diện thấy đang quá phức tạp, cần tư duy lại cách xây dựng trải nghiệm người dùng... cần tham khảo các hệ thống kpi, okr chuẩn trên thế giới, từ đó kết hợp với các quy định của việt nam để đưa ra các tính năng vừa đơn giản, dễ dùng mà hiệu quả").

#### YC-TK-12 – Thiết kế giao diện (UI), CSS, khả năng đáp ứng (responsive) và tùy biến màn hình đăng nhập (IdP/Keycloak) ấn tượng, có điểm nhấn thị giác

- **Nội dung:** Giao diện người dùng (UI) của toàn bộ hệ thống và các màn hình xác thực tập trung (như trang đăng nhập, OTP, đổi mật khẩu trên Keycloak/IdP) bắt buộc phải được thiết kế và tùy biến đồng bộ, hiện đại, có điểm nhấn thị giác rõ nét theo chuẩn thẩm mỹ của các sản phẩm phần mềm doanh nghiệp hàng đầu thế giới. Phải đầu tư kỹ lưỡng vào hệ thống token thiết kế (màu sắc chủ đạo và màu nhấn, độ tương phản, bóng đổ tinh tế, bo góc, khoảng trắng, phân cấp chữ rõ ràng), bố cục thẻ chỉ số có biểu đồ/thanh tiến độ trực quan, điều hướng phân nhóm gọn gàng, và bố cục đáp ứng (responsive) mượt mà trên mọi kích thước màn hình (máy tính để bàn, máy tính bảng, điện thoại). Tuyệt đối không để giao diện mặc định thô sơ của Keycloak/IdP hoặc các màn hình phẳng, thiếu điểm nhấn và vỡ bố cục khi thu hẹp màn hình.
- **Lý do:** Ấn tượng đầu tiên khi đăng nhập và trải nghiệm thị giác hằng ngày quyết định mức độ hào hứng và niềm tin của người dùng cũng như lãnh đạo đối với chất lượng của nền tảng.
- **Kiểm tra:** Trang đăng nhập IdP/Keycloak hiển thị giao diện tùy biến riêng có thương hiệu, hỗ trợ SSO/VNeID và responsive; khung ứng dụng và các màn hình chính có phân nhóm điều hướng rõ ràng, thẻ chỉ số có điểm nhấn thị giác và vượt qua kiểm thử hiển thị trên cả viewport desktop lẫn mobile.
- **Nguồn:** Dự án Mission, ngày 20/10/2026, kênh điều phối Antigravity (anh Hiếu: "giao diện UI cũng đơn giản, không có điểm nhấn, css, responsive chưa tốt, các màn hình login chưa được custom. Đề nghị làm lại giao diện ấn tượng, đẹp, tham khảo top 10 ứng dụng kpi okr mà làm").

### 2.3. Nhóm PT – Tổ chức phát triển

#### YC-PT-01 – Lập kế hoạch phát triển bám đúng thiết kế đã duyệt

- **Nội dung:** Kế hoạch phát triển phải được lập từ bộ tài liệu thiết kế và bám đúng thiết kế đã được duyệt; mọi thay đổi so với thiết kế phải được ghi nhận và hỏi chốt trước.
- **Lý do:** Tránh việc mã nguồn trôi dần khỏi thiết kế, làm mất giá trị của bộ tài liệu đã đầu tư.
- **Kiểm tra:** Mỗi hạng mục trong kế hoạch trỏ tới mã tài liệu thiết kế tương ứng (SVC, MOD, SCR...); không có hạng mục nào không có nguồn.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-PT-02 – Chia nhỏ task cho AI agent và nhắc quy định chung

- **Nội dung:** Công việc phải được chia thành các task đủ nhỏ để một AI agent thực hiện trọn vẹn. Khi bắt đầu mỗi task, agent phải được nhắc đọc quy định chung của dự án (AGENTS.md hoặc CLAUDE.md và wiki).
- **Lý do:** Task quá lớn khiến agent làm dở dang hoặc sai hướng; agent không được nhắc quy định sẽ làm lệch chuẩn chung của dự án.
- **Kiểm tra:** Mỗi task có phạm vi, đầu ra và tiêu chí hoàn thành rõ ràng; lời giao task có dòng yêu cầu đọc AGENTS.md/CLAUDE.md và wiki.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-PT-03 – Dựng wiki tri thức có dẫn nguồn

- **Nội dung:** Phải dựng wiki tri thức dự án chi tiết và đầy đủ, trong đó mỗi trang đều dẫn nguồn về tài liệu thiết kế gốc.
- **Lý do:** Giúp agent và người phát triển tra cứu nhanh mà vẫn truy được về nguồn gốc, tránh wiki trở thành một nguồn "sự thật" riêng mâu thuẫn với thiết kế.
- **Kiểm tra:** Mọi trang wiki đều có mục nguồn trỏ tới tài liệu thiết kế và mã định danh liên quan.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-PT-04 – Khởi tạo git và đẩy mã lên GitHub riêng tư của người dùng

- **Nội dung:** Phải tạo thư mục dự án và khởi tạo git ngay từ đầu. Mã nguồn phải được đẩy lên GitHub của anh Hiếu dưới dạng repo private, và mỗi lần gộp vào nhánh main thì phải đẩy lên remote.
- **Lý do:** Tránh mất mã nguồn khi máy hoặc phiên làm việc gặp sự cố, và bảo đảm anh Hiếu luôn có bản mới nhất.
- **Kiểm tra:** Repo trên GitHub ở chế độ private; commit mới nhất của main cục bộ trùng với main trên remote.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-PT-05 – Phát triển theo hướng API-first

- **Nội dung:** Hợp đồng OpenAPI cho toàn bộ dự án phải được viết trước khi phát triển; frontend và backend đều sinh mã (client, server stub, kiểu dữ liệu) từ hợp đồng này.
- **Lý do:** Tránh lệch giao tiếp giữa frontend và backend, cho phép hai phía phát triển song song mà không chờ nhau.
- **Kiểm tra:** Có tệp hợp đồng OpenAPI trong repo; mã client và server được sinh từ hợp đồng và quy trình build báo lỗi nếu mã lệch hợp đồng.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-PT-06 – Chạy song song nhiều luồng, một luồng điều phối gộp main

- **Nội dung:** Phát triển được chạy song song nhiều luồng, mỗi luồng làm việc trên một worktree và nhánh riêng. Chỉ có một luồng điều phối duy nhất được quyền gộp vào nhánh main. Lưu ý: số luồng chạy đồng thời tối đa là 5, xem YC-PT-12.
- **Lý do:** Tăng tốc độ phát triển mà vẫn tránh xung đột và tránh việc nhiều luồng cùng ghi đè main.
- **Kiểm tra:** Mỗi luồng có worktree/nhánh riêng; lịch sử gộp vào main chỉ đến từ luồng điều phối.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-PT-07 – Task chỉ hoàn thành khi build, test và màn hình thật đều đạt

- **Nội dung:** Một task chỉ được coi là xong khi build thành công, toàn bộ test đạt và màn hình liên quan được mở thật và hoạt động đúng. Không được nới lỏng ngưỡng chất lượng (bỏ test, hạ ngưỡng coverage, tắt lint...) để cho task đạt.
- **Lý do:** Tránh báo cáo "xong" giả, khi mã chỉ biên dịch được nhưng chưa thực sự chạy đúng.
- **Kiểm tra:** Báo cáo task kèm kết quả build, kết quả test và bằng chứng mở màn hình (ảnh chụp hoặc mô tả); lịch sử commit không có thay đổi nới ngưỡng chất lượng.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-PT-08 – Kiểm thử tích hợp end-to-end với hệ thống thật

- **Nội dung:** Dữ liệu giả lập (mock) không được thay thế cho kiểm thử tích hợp thật. Phải có đợt kiểm thử end-to-end chạy với backend thật, IdP thật và gateway thật.
- **Lý do:** Mock che giấu các lỗi tích hợp như xác thực, phân quyền, cấu hình gateway và định dạng dữ liệu thật, khiến lỗi chỉ lộ ra khi triển khai.
- **Kiểm tra:** Có kế hoạch và kết quả đợt kiểm thử end-to-end, ghi rõ các thành phần thật đã tham gia và các luồng đã kiểm tra.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-PT-09 – Hợp đồng OpenAPI là chuẩn, khi lệch thì sửa mã

- **Nội dung:** Khi mã nguồn (model, lời gọi API, dữ liệu giả lập) và hợp đồng OpenAPI lệch nhau, mặc định sửa mã cho khớp hợp đồng. Hợp đồng chỉ được sửa khi chính hợp đồng thiếu hoặc sai so với bộ tài liệu thiết kế; khi đó đề nghị sửa được gửi cho luồng phụ trách hợp đồng, ghi lại trong trang quyết định của wiki, rồi mã mới theo bản hợp đồng đã sửa.
- **Lý do:** Tránh để mỗi luồng tự "uốn" hợp đồng theo mã mình đã viết, làm hợp đồng mất vai trò nguồn sự thật chung giữa frontend và backend trong cách làm API-first (YC-PT-05).
- **Kiểm tra:** Bước kiểm tra hợp đồng (`api:check`, phép kiểm tương thích kiểu) xanh; mọi thay đổi trong thư mục hợp đồng đều có lý do dẫn về tài liệu thiết kế và được ghi ở trang quyết định.
- **Nguồn:** Dự án Mission, anh Hiếu chọn "Sửa code" trên thẻ quyết định ngày 19/10/2026, thread "Kế hoạch và mã nguồn phân hệ web Mission".

#### YC-PT-10 – Giao diện chính thức chỉ hiển thị thông tin người dùng cần, dùng ngôn ngữ nghiệp vụ chuẩn

- **Nội dung:** Giao diện sản phẩm chính thức chỉ được hiển thị những thông tin mà người dùng cuối thực sự cần để làm việc. Không được đưa lên màn hình các bình luận về giải pháp hay thiết kế, ghi chú khảo sát, lời giải thích dành cho người phát triển hoặc nội dung giữ chỗ. Thuật ngữ trên giao diện phải là thuật ngữ nghiệp vụ chuẩn của lĩnh vực (với hệ thống đánh giá KPI/OKR trong khu vực công là thuật ngữ theo QĐ 366 và Thông tư 22), tuyệt đối không hiển thị các định danh mang tính kỹ thuật như mã màn hình (SCR-xxxx), mã task, mã quy trình, tên trường dữ liệu, tên API, mã trạng thái HTTP, cũng như các cách diễn đạt không chuẩn. Văn phong phải phù hợp với đối tượng cán bộ, công chức, viên chức: nghiêm túc, chỉn chu, đơn giản, dễ hiểu và không mang tính kỹ thuật. Các mã định danh trong bộ tài liệu thiết kế (YC-TK-07) chỉ dùng để truy vết trong tài liệu và mã nguồn, không phải để hiển thị cho người dùng.
- **Lý do:** Người dùng là cán bộ, công chức, viên chức không có nhu cầu và không nên phải đọc nội dung kỹ thuật; việc để lộ bình luận thiết kế, mã nội bộ hay thông báo lỗi kỹ thuật làm sản phẩm thiếu chuyên nghiệp, gây khó hiểu, giảm uy tín khi trình lãnh đạo và có thể làm lộ thông tin nội bộ của hệ thống.
- **Kiểm tra:** Rà soát từng màn hình trước khi bàn giao và xác nhận mọi nhãn, tiêu đề, chú thích và thông báo đều là ngôn ngữ nghiệp vụ chuẩn, không còn ghi chú thiết kế hay khảo sát. Bổ sung bước kiểm tra tự động (lint trên tệp ngôn ngữ/chuỗi giao diện hoặc test end-to-end quét văn bản hiển thị) để báo lỗi khi gặp các mẫu như `SCR-\d+`, `QT-\d+`, `TASK-\d+`, mã HTTP dạng `\b[45]\d\d\b` đứng riêng, tên trường kiểu camelCase/snake_case hoặc đường dẫn API trong văn bản hiển thị. Thông báo lỗi phải được chuyển thành câu tiếng Việt dễ hiểu, hướng dẫn người dùng việc cần làm tiếp theo.
- **Nguồn:** Dự án Mission, ngày 19/10/2026, kênh chung của dự án (anh Hiếu phản ánh giao diện web đang hiển thị các bình luận của giải pháp).

#### YC-PT-11 – Kế hoạch phát triển phải bao trùm toàn bộ hệ thống, không lập kế hoạch từng phần

- **Nội dung:** Kế hoạch phát triển phải được lập cho toàn bộ hệ thống từ đầu đến cuối, sao cho khi thực hiện xong kế hoạch thì có được một hệ thống hoàn chỉnh, chạy được. Kế hoạch phải bao gồm đầy đủ các phần việc: tất cả các phân hệ và dịch vụ backend, frontend, cơ sở dữ liệu, các tích hợp với hệ thống ngoài, hạ tầng và triển khai, CI/CD, bảo mật, kiểm thử tích hợp và kiểm thử hiệu năng, vận hành và giám sát, chuyển đổi dữ liệu, tài liệu hướng dẫn. Không được chỉ lập kế hoạch cho một phần (ví dụ chỉ frontend) khi chưa có kế hoạch tổng thể. Nếu cần lập kế hoạch chi tiết cho một phần trước, kế hoạch đó phải nằm trong kế hoạch tổng thể và ghi rõ những phần việc còn lại chưa được chi tiết hoá.
- **Lý do:** Lập kế hoạch từng phần khiến các phần khác bị bỏ quên, tiến độ báo cáo trông có vẻ tốt nhưng hệ thống không thể chạy hoàn chỉnh; người giao việc bị bất ngờ khi phát hiện phần lớn hệ thống chưa được làm. Ví dụ minh hoạ: ở dự án Mission, kế hoạch chỉ gồm các task frontend web trong khi backend, hạ tầng và tích hợp chưa có kế hoạch, nên backend không được code.
- **Kiểm tra:** Kế hoạch có ma trận bao phủ ánh xạ mọi phân hệ, dịch vụ và mô-đun trong bộ tài liệu thiết kế (cùng các hạng mục hạ tầng, CI/CD, bảo mật, kiểm thử, vận hành, chuyển đổi dữ liệu, tài liệu) tới các task cụ thể, không còn hạng mục nào chưa được ánh xạ. Báo cáo tiến độ phải nêu mức hoàn thành so với toàn bộ hệ thống, không chỉ so với một phân hệ.
- **Nguồn:** Dự án Mission, ngày 19/10/2026, kênh chung của dự án (anh Hiếu: "lập kế hoạch thì lập sao cho làm xong trọn vẹn hệ thống chứ không phải từng phần, lập kế hoạch làm toàn bộ các việc phải làm").

#### YC-PT-12 – Giới hạn tối đa 5 luồng phát triển chạy đồng thời

- **Nội dung:** Tại mọi thời điểm, số luồng phát triển (thread hoặc agent) chạy đồng thời không được vượt quá 5. Khi cần mở thêm một luồng mới mà đã đủ 5 luồng đang chạy, phải tạm dừng một luồng khác hoặc chờ một luồng hoàn thành rồi mới mở. Luồng bị tạm dừng phải đẩy toàn bộ phần việc đang làm dở lên nhánh riêng của mình và ghi rõ trạng thái (đã làm đến đâu, còn lại việc gì, bước tiếp theo) để có thể chạy tiếp mà không mất công sức. Khi chọn luồng để giữ lại, ưu tiên các luồng nằm trên đường găng của dự án, như luồng điều phối giữ nhánh chính và các luồng làm phần nền móng mà nhiều luồng khác phụ thuộc vào.
- **Lý do:** Kiểm soát chi phí, hạn mức sử dụng và bảo đảm người dùng vẫn theo dõi được công việc. Chạy quá nhiều luồng cùng lúc khiến việc giám sát trở nên khó khăn và dễ làm cạn hạn mức sử dụng. Ví dụ minh hoạ: đêm 19/10/2026 ở dự án Mission, tất cả các luồng cùng bị dừng vì chạm giới hạn sử dụng.
- **Kiểm tra:** Trước khi mở mới hoặc cho chạy tiếp một luồng, đếm số luồng đang chạy và xác nhận sau khi mở vẫn không vượt quá 5; mọi luồng đang tạm dừng đều có phần việc dở đã được đẩy lên nhánh riêng kèm ghi chú trạng thái.
- **Nguồn:** Dự án Mission, ngày 20/10/2026, kênh chung của dự án (anh Hiếu: "Đang chạy nhiều luồng quá, tối đa 5 thôi").

#### YC-PT-13 – Hệ ngoài chưa có môi trường thử thì phát triển và kiểm thử với bản giả lập

- **Nội dung:** Khi một hệ thống bên ngoài cần tích hợp (cổng văn bản, trục liên thông, định danh quốc gia, dịch vụ ký số, HSM, cổng SMS, đẩy thông báo…) chưa có môi trường thử, không dừng việc chờ đầu vào. Bộ kết nối vẫn được làm đầy đủ phía hệ thống, chạy và kiểm thử với bản giả lập (đối tác giả lập có kiểm thử hợp đồng, HSM phần mềm, cụm Kubernetes cục bộ…). Task được coi là xong khi chạy xanh với bản giả lập. Thông tin kết nối hệ thật phải tách khỏi mã, để khi có môi trường chỉ cần đổi cấu hình và chạy lại kiểm thử tích hợp. Việc nối hệ thật ghi thành phần ngoài phạm vi của task.
- **Lý do:** Tránh để tiến độ toàn hệ thống phụ thuộc vào đầu vào mà người giao việc chưa có, đồng thời không phải viết lại mã khi hệ thật sẵn sàng.
- **Kiểm tra:** Thẻ task tích hợp có tiêu chí chạy với bản giả lập và có kiểm thử hợp đồng; cấu hình kết nối nằm trong cấu hình môi trường, không nằm trong mã; kế hoạch không còn task ở trạng thái chờ hệ ngoài.
- **Nguồn:** Dự án Mission, ngày 20/10/2026, thread "Kế hoạch tổng thể toàn hệ thống" (anh Hiếu: "chạy giả lập thôi, chưa có").

#### YC-PT-14 – Quy tắc nghiệp vụ hay thay đổi phải là cấu hình động; chưa có bản chính thức thì dùng bản giả lập

- **Nội dung:** Công thức tính điểm, bảng quyết định xếp loại, tham số nghiệp vụ và mẫu biểu là cấu hình động: lưu theo phiên bản, kiểm thử bằng bộ ca mẫu, duyệt và phát hành bởi quản trị viên mà không cần sửa mã hay phát hành phần mềm. Khi nghiệp vụ chưa đưa ra bản chính thức, đội phát triển soạn một bản giả lập theo văn bản pháp lý hiện hành, đánh dấu rõ là giả lập trong siêu dữ liệu, và dùng nó để phát triển và kiểm thử; khi có bản chính thức hoặc quy định thay đổi thì chỉ phát hành phiên bản cấu hình mới.
- **Lý do:** Quy định đánh giá cán bộ thay đổi theo thời gian; nếu công thức nằm trong mã thì mỗi lần thay đổi phải sửa và phát hành lại phần mềm. Chờ bản chính thức mới làm sẽ chặn toàn bộ phần tính điểm.
- **Kiểm tra:** Có ca kiểm thử chứng minh thay phiên bản cấu hình thì kết quả tính theo quy tắc mới mà không đổi mã; bản giả lập được đánh dấu rõ và có đường thay thế bằng bản chính thức.
- **Nguồn:** Dự án Mission, ngày 20/10/2026, thread "Kế hoạch tổng thể toàn hệ thống" (anh Hiếu: "tạm thời đưa ra công thức giả lập, nhưng chú ý tính năng này là tính năng cấu hình động, sau có thay đổi thì vẫn update được").

#### YC-PT-15 – Phần việc đã giao cho công cụ AI khác thì các luồng Claude dừng và bàn giao gọn

- **Nội dung:** Khi người dùng giao một phân hệ hoặc một nhóm task cho công cụ AI khác (ví dụ giao toàn bộ backend cho Antigravity), các luồng Claude phải dừng ngay nhóm task đó. Phần việc đã làm phải được đẩy lên nhánh riêng, kèm tệp ghi chú trạng thái của từng task (đã làm đến đâu, còn lại việc gì, bước tiếp theo) để bên kia có thể tiếp nhận mà không mất công sức. Không được mở luồng mới cho nhóm task đã giao đi, và không được gộp nhánh của nhóm đó vào main khi người dùng chưa quyết định. Hợp đồng API (OpenAPI, xem YC-PT-05 và YC-PT-09) là ranh giới bàn giao giữa các công cụ: phía Claude chỉ làm việc theo hợp đồng, và mọi đề nghị sửa hợp đồng phải được ghi lại trong repo (ví dụ trong trang quyết định hoặc tệp đề nghị đặt cạnh hợp đồng) để bên kia nhìn thấy.
- **Lý do:** Tránh việc hai công cụ cùng sửa một phần mã nguồn, gây xung đột khi gộp nhánh và làm trùng lặp công việc, lãng phí công sức và hạn mức sử dụng.
- **Kiểm tra:** Không có luồng Claude nào đang chạy task thuộc nhóm đã giao cho công cụ khác; mỗi nhánh bị dừng đều có tệp ghi trạng thái từng task; nhánh của nhóm đã giao chưa được gộp vào main nếu người dùng chưa quyết; mọi đề nghị sửa hợp đồng API đều có trong repo.
- **Nguồn:** Dự án Mission, ngày 20/10/2026, kênh chung của dự án (tin nhắn của anh Hiếu lúc 06:30 UTC: "các task backend, tôi đang giao cho antigravity làm rồi nhé").

#### YC-PT-16 – Hết hạn mức sử dụng thì tạm dừng có trật tự, đóng gói tiến độ và viết hướng dẫn bàn giao cho công cụ khác

- **Nội dung:** Khi người dùng báo hết hạn mức sử dụng (quota) và cho tạm dừng, mọi luồng đang chạy phải dừng có trật tự: đẩy phần đã làm và phần dở lên nhánh riêng kèm tệp ghi trạng thái từng task (xong, dở ở bước nào, bước tiếp theo); luồng giữ main gộp các nhánh đã xong và đã qua kiểm tra; sau đó viết một hướng dẫn bàn giao duy nhất đặt ở gốc repo và đẩy lên main, có bản sao trong thư mục dùng chung của project. Hướng dẫn tối thiểu gồm: tiến độ trên toàn hệ thống, danh sách nhánh và trạng thái, task dở đang ở bước nào, thứ tự làm tiếp đề xuất, quy định bắt buộc, cách chạy và kiểm thử, việc còn chờ người dùng quyết định. Hướng dẫn viết sao cho một công cụ AI khác tiếp nhận được mà không cần hỏi lại, và không chứa mật khẩu, địa chỉ máy chủ hay tài khoản.
- **Lý do:** Công việc phải tiếp tục được bằng công cụ khác hoặc ở tuần sau mà không mất phần đã làm và không phải dò lại trạng thái từ nhiều thread.
- **Kiểm tra:** Không còn luồng nào chạy; mọi nhánh dở có tệp trạng thái; main chứa các nhánh đã xong; tệp hướng dẫn bàn giao có trên main và trong thư mục dùng chung, đủ các mục nêu trên.
- **Nguồn:** Dự án Mission, ngày 20/10/2026, kênh chung của dự án (tin nhắn của anh Hiếu lúc 06:32 UTC: "các task còn lại, do hết quota tuần rồi, tạm dừng. đóng gói tiến độ đã làm, viết hướng dẫn bàn giao cho antigravity làm tiếp"); thực hiện trong thread "Đóng gói tiến độ và bàn giao".

#### YC-PT-17 – Theo dõi tiến độ toàn hệ thống trên một tệp tổng hợp duy nhất và bắt buộc mọi agent cập nhật

- **Nội dung:** Toàn bộ dự án phải có một tệp theo dõi tiến độ tổng hợp duy nhất đặt ở gốc repo (`TIEN-DO-HE-THONG.md`), bao quát đầy đủ tất cả các task của mọi phân hệ (Web, nền móng, backend lõi, dịch vụ vệ tinh, dữ liệu, hạ tầng, kiểm thử, di động, vận hành, cá nhân hoá), bảng tổng hợp theo phân hệ và theo mốc triển khai, trạng thái đường găng, danh sách các luồng đang chạy và nhật ký cập nhật. Mọi AI agent (cả luồng điều phối giữ nhánh `main` lẫn các subagent chạy trên nhánh/worktree riêng) bắt buộc phải cập nhật trạng thái vào tệp này (thông qua kịch bản `python3 scripts/cap-nhat-tien-do.py set <MÃ_TASK> ...` hoặc sửa bảng rồi chạy `python3 scripts/cap-nhat-tien-do.py sync`) ở cả ba thời điểm: khi bắt đầu nhận task (`Đang làm`), khi hoàn thành kiểm thử trên nhánh (`Xong, chờ gộp`) và khi gộp vào `main` (`Đã gộp main`). Quy định bắt buộc này phải được ghi trực tiếp trong `AGENTS.md`, quy định riêng từng phân hệ (`docs/quy-dinh/*.md`), mẫu task và kịch bản giao việc `scripts/agent-task.sh`.
- **Lý do:** Khi thông tin tiến độ nằm rải rác ở nhiều tệp danh mục hoặc chỉ nằm trong lịch sử git và các nhánh con, người phụ trách dự án không có một nơi duy nhất để nắm bắt tức thì mức độ hoàn thành thực tế của toàn hệ thống, và các agent dễ quên cập nhật sau khi làm xong task.
- **Kiểm tra:** Tệp `TIEN-DO-HE-THONG.md` ở gốc repo chứa đủ 100% số task của dự án với số liệu tổng hợp khớp chính xác với bảng chi tiết (`python3 scripts/cap-nhat-tien-do.py sync` chạy không lỗi); `AGENTS.md`, `docs/tasks/_NHAC-QUY-DINH.md`, `scripts/agent-task.sh` và các tệp `docs/quy-dinh/*.md` đều có điều khoản bắt buộc cập nhật `TIEN-DO-HE-THONG.md`; mọi commit hoàn thành hoặc gộp task đều kèm cập nhật tiến độ trong tệp này.
- **Nguồn:** Dự án Mission, ngày 20/10/2026, kênh điều phối Antigravity (anh Hiếu: "tôi cần 1 file tổng hợp duy nhất, và yêu cầu mọi agent phải cập nhật tiến độ vào").

#### YC-PT-18 – Ưu tiên xây dựng hoàn chỉnh khung nền tảng (Platform Foundation) trước khi triển khai sâu các tính năng nghiệp vụ

- **Nội dung:** Khi tổ chức thứ tự phát triển và phân bổ các luồng agent, bắt buộc phải ưu tiên tập trung xây dựng xong khung nền tảng (Platform Foundation) trước, bao gồm: xác thực tập trung & phân quyền động (IdP/Keycloak, OPA, phân công vai trò), quản trị tổ chức/đơn vị/người dùng/tenant, động cơ quy trình động (BPMN workflow engine & trình thiết kế quy trình), động cơ biểu mẫu động/giao diện động theo tenant (UI Pack, Form Engine, CEL rule/công thức động), và khung ứng dụng Shell/Design System. Chỉ sau khi khung nền tảng đã chạy thông suốt và giải pháp trải nghiệm người dùng cho nghiệp vụ đã được thiết kế lại tinh gọn thì mới đẩy mạnh triển khai các màn hình nghiệp vụ chuyên sâu trên nền tảng đó.
- **Lý do:** Nếu dàn trải nguồn lực làm các màn hình nghiệp vụ khi khung nền tảng (luồng động, người dùng, tổ chức, cấu hình động, hệ thống giao diện) chưa hoàn chỉnh thì các màn hình nghiệp vụ chỉ là vỏ cứng rời rạc, khó vận hành thực tế và phải đập đi làm lại khi nền tảng thay đổi.
- **Kiểm tra:** Thứ tự ưu tiên trên bảng điều phối (`TIEN-DO-HE-THONG.md`) và danh sách các luồng đang chạy luôn ưu tiên hoàn tất các nhóm task nền tảng (`NEN-*`, `DV-02/03/04`, `DV-06` luồng động BPMN, `DV-10` UI Pack/Form động, `CORE-00`, `fe-shell`, `fe-admin`, `fe-config-studio`, `WEB-020`, `WEB-036`) trước các màn hình nghiệp vụ thứ cấp.
- **Nguồn:** Dự án Mission, ngày 20/10/2026, kênh điều phối Antigravity (anh Hiếu: "trước mắt tập trung vào xây dựng khung nền tảng, tức là các tính năng nền tảng (ví dụ luồng động, quản lý người dùng,...) để chạy được nghiệp vụ về sau, các tính năng nghiệp vụ thì bố trí làm sau").

### 2.4. Nhóm HT – Hạ tầng và bảo mật

#### YC-HT-01 – Hạ tầng dùng chung triển khai bằng Docker Compose trên server dev

- **Nội dung:** Các thành phần hạ tầng dùng chung (cơ sở dữ liệu, cache, message broker, IdP, object storage...) được triển khai lên server dev của anh Hiếu bằng Docker Compose. Trước khi triển khai phải khảo sát server và tuyệt đối không ghi đè các dịch vụ đang có sẵn.
- **Lý do:** Bảo đảm môi trường dev gần với thật và dùng chung được, đồng thời tránh làm hỏng các dịch vụ khác đang chạy trên cùng server.
- **Kiểm tra:** Có báo cáo khảo sát server (cổng, container, volume đang dùng) trước khi triển khai; tên container, cổng và volume mới không trùng với dịch vụ sẵn có.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-HT-02 – Quản lý bí mật trong .env, không bao giờ commit bí mật

- **Nội dung:** Thông tin đăng nhập và bí mật phải được lưu trong tệp .env (đã được đưa vào .gitignore), kèm theo tệp .env.example chỉ chứa tên biến. Không bao giờ commit bí mật, không ghi bí mật vào tài liệu hay memory, và phải rà lịch sử commit trước khi đẩy lên remote.
- **Lý do:** Ngăn rò rỉ thông tin đăng nhập ra repo, tài liệu hoặc bộ nhớ của AI, vốn rất khó thu hồi một khi đã lộ.
- **Kiểm tra:** .env có trong .gitignore; .env.example không chứa giá trị thật; quét lịch sử commit không phát hiện bí mật trước mỗi lần đẩy lên remote.
- **Nguồn:** Dự án Mission, ngày 19/10/2026.

#### YC-HT-03 – Sử dụng SSH Tunnel khi máy chủ hạn chế mở cổng và tham số hóa cấu hình động qua biến môi trường (.env) cho cả Backend và Frontend

- **Nội dung:** Khi máy chủ phát triển/triển khai bị giới hạn tường lửa và không thể mở nhiều cổng ra ngoài, phải sử dụng cơ chế đường hầm SSH (SSH port forwarding `-L` / `-R` qua cổng SSH duy nhất được mở) thông qua kịch bản tự động để ánh xạ các cổng hạ tầng và dịch vụ về `127.0.0.1`. Đồng thời, toàn bộ cấu hình kết nối của cả Backend (cơ sở dữ liệu, cache, broker, IdP, OPA, kho khóa, cổng dịch vụ) và Frontend (cổng lắng nghe, `API_BASE_URL`, `AUTH_ENABLED`, `AUTH_ISSUER`, `USE_MOCK_API`, địa chỉ các micro-frontend và đích proxy API) phải được tham số hóa qua biến môi trường (`.env`) và nạp động lúc chạy (runtime) để có thể thay đổi tức thì mà không cần sửa mã hay biên dịch lại.
- **Lý do:** Máy chủ thực tế thường chỉ mở cổng SSH để đảm bảo an toàn thông tin; nếu gán cứng địa chỉ máy chủ hoặc cổng trong mã nguồn hay bản build tĩnh thì ứng dụng sẽ không kết nối được qua tunnel và phải build lại mỗi khi đổi môi trường (giả lập, tunnel cục bộ, server dev, staging).
- **Kiểm tra:** Có kịch bản quản lý SSH tunnel đọc thông số từ `.env`; `.env.example` liệt kê đầy đủ biến môi trường cho cả hạ tầng, Backend và Frontend; cấu hình Backend (`application.yml` / biến môi trường) và Frontend (`/config.json`, `/federation.manifest.json`, máy chủ phục vụ) đều đọc từ biến môi trường và đổi được hành vi chỉ bằng cách sửa `.env` rồi khởi động lại tiến trình.
- **Nguồn:** Dự án Mission, ngày 20/10/2026 (anh Hiếu: "vì server không mở được cổng nhiều, nên tôi gợi ý phương pháp tunnel, chú ý là nên cấu hình cho backend và fe các tham số env để dễ dàng thay đổi động").

---

## 3. Tổng hợp số lượng quy tắc

| Nhóm | Tên nhóm | Số quy tắc |
|---|---|---|
| GT | Giao tiếp và cách làm việc | 4 |
| TK | Bộ tài liệu thiết kế | 12 |
| PT | Tổ chức phát triển | 18 |
| HT | Hạ tầng và bảo mật | 3 |
| | **Tổng cộng** | **37** |

---

## Phụ lục – Nhật ký bổ sung

| Ngày | Mã | Nội dung | Nguồn |
|---|---|---|---|
| 19/10/2026 | YC-GT-01 … YC-HT-02 | Khởi tạo sổ tay với 24 quy tắc, tổng hợp từ các yêu cầu anh Hiếu đã nêu trong dự án Mission. | Dự án Mission, phiên điều phối ngày 19/10/2026 |
| 19/10/2026 | YC-PT-09 | Khi mã và hợp đồng OpenAPI lệch nhau, mặc định sửa mã; hợp đồng chỉ sửa khi sai so với tài liệu thiết kế. | Thread "Kế hoạch và mã nguồn phân hệ web Mission", thẻ quyết định |
| 19/10/2026 | YC-PT-10 | Giao diện chính thức chỉ hiển thị thông tin người dùng cần; không hiển thị bình luận giải pháp, ghi chú khảo sát, mã kỹ thuật (SCR, mã task, tên trường, mã HTTP); dùng thuật ngữ KPI/OKR chuẩn và văn phong phù hợp cán bộ, công chức, viên chức. | Dự án Mission, kênh chung của dự án |
| 19/10/2026 | YC-PT-11 | Kế hoạch phát triển phải bao trùm toàn bộ hệ thống (mọi phân hệ, backend, CSDL, tích hợp, hạ tầng, CI/CD, bảo mật, kiểm thử, vận hành, chuyển đổi dữ liệu, tài liệu); không lập kế hoạch từng phần; có ma trận bao phủ và báo cáo tiến độ theo toàn hệ thống. | Dự án Mission, kênh chung của dự án |
| 20/10/2026 | YC-PT-12 | Số luồng phát triển chạy đồng thời tối đa là 5; muốn mở luồng mới phải tạm dừng hoặc chờ luồng khác xong; luồng tạm dừng đẩy việc dở lên nhánh riêng kèm ghi chú trạng thái; ưu tiên giữ các luồng trên đường găng. Bổ sung ghi chú trỏ tới quy tắc này trong YC-GT-04 và YC-PT-06. | Dự án Mission, kênh chung của dự án |
| 20/10/2026 | YC-PT-13 | Hệ ngoài chưa có môi trường thử thì vẫn làm đủ bộ kết nối, kiểm thử với bản giả lập, tách cấu hình kết nối khỏi mã để sau chỉ đổi cấu hình. | Dự án Mission, thread "Kế hoạch tổng thể toàn hệ thống" |
| 20/10/2026 | YC-PT-14 | Công thức, bảng quyết định, tham số nghiệp vụ và mẫu biểu là cấu hình động có phiên bản; chưa có bản chính thức thì dùng bản giả lập đánh dấu rõ, sau chỉ phát hành phiên bản cấu hình mới. | Dự án Mission, thread "Kế hoạch tổng thể toàn hệ thống" |
| 20/10/2026 | YC-PT-15 | Phần việc người dùng đã giao cho công cụ AI khác thì các luồng Claude dừng: đẩy phần đã làm lên nhánh riêng kèm ghi chú trạng thái từng task, không mở luồng mới, không gộp vào main khi chưa có quyết định; hợp đồng OpenAPI là ranh giới bàn giao, đề nghị sửa hợp đồng ghi trong repo. Cập nhật bảng tổng hợp số lượng quy tắc. | Dự án Mission, kênh chung của dự án (tin nhắn 06:30 UTC) |
| 20/10/2026 | YC-PT-16 | Hết hạn mức sử dụng thì tạm dừng có trật tự: đẩy phần dở lên nhánh kèm trạng thái, gộp nhánh đã xong, viết một hướng dẫn bàn giao ở gốc repo (có bản sao trong thư mục dùng chung) để công cụ AI khác làm tiếp. Cập nhật bảng tổng hợp số lượng quy tắc. | Dự án Mission, kênh chung của dự án (tin nhắn 06:32 UTC) |
| 20/10/2026 | YC-HT-03 | Sử dụng SSH Tunnel khi máy chủ hạn chế mở cổng và tham số hóa cấu hình động qua biến môi trường (.env) cho cả Backend và Frontend để thay đổi động không cần build lại. Cập nhật bảng tổng hợp số lượng quy tắc. | Dự án Mission, kênh điều phối Antigravity |
| 20/10/2026 | YC-PT-17 | Duy trì một tệp theo dõi tiến độ tổng hợp duy nhất cho toàn hệ thống (`TIEN-DO-HE-THONG.md`) kèm công cụ `scripts/cap-nhat-tien-do.py`, bắt buộc mọi AI agent phải cập nhật trạng thái vào tệp này khi nhận task, khi hoàn thành trên nhánh và khi gộp vào `main`. Cập nhật bảng tổng hợp số lượng quy tắc. | Dự án Mission, kênh điều phối Antigravity |
| 20/10/2026 | YC-TK-11, YC-TK-12, YC-PT-18 | Tư duy lại UX và giải pháp màn hình nghiệp vụ trên cơ sở tham khảo Top 10 ứng dụng OKR/KPI quốc tế kết hợp quy định Việt Nam (QĐ 366, NĐ 335, NĐ 233); thiết kế lại UI/CSS/Responsive ấn tượng và tùy biến trang đăng nhập Keycloak; ưu tiên tập trung xây dựng hoàn chỉnh khung nền tảng (luồng động BPMN, quản lý người dùng/tổ chức, biểu mẫu/cấu hình động) trước khi mở rộng các tính năng nghiệp vụ. Cập nhật bảng tổng hợp số lượng quy tắc. | Dự án Mission, kênh điều phối Antigravity |
