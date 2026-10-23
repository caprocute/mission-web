# Mẫu phân tích sau sự cố (không quy lỗi cá nhân)

> Nguồn: TL-04 mục 11.7: "Mọi sự cố SEV-1 và SEV-2 có bản phân tích sau sự cố không quy lỗi cá nhân (blameless postmortem) trong 5 ngày làm việc, với hạng mục khắc phục có người và hạn; hạng mục được theo dõi tới khi đóng."

## Cách dùng mẫu

Bản phân tích bắt buộc với mọi sự cố đã từng ở mức SEV-1 hoặc SEV-2, kể cả khi sau đó được hạ mức. Hạn nộp là 5 ngày làm việc kể từ lúc tuyên bố đã khắc phục; IC giao người viết (thường là IC hoặc kỹ sư cấp 2 chính) ngay khi đóng sự cố. Bản phân tích được đọc chung trong một buổi họp rà soát có IC, các kỹ sư tham gia và chủ sản phẩm; sau buổi họp, hạng mục khắc phục được đưa vào hệ thống theo dõi công việc của đội và được rà lại ở CAB hằng tuần ([hoi-dong-thay-doi.md](hoi-dong-thay-doi.md)) tới khi đóng.

**Không quy lỗi cá nhân** nghĩa là bản phân tích tìm điều kiện của hệ thống và quy trình đã cho phép lỗi xảy ra hoặc lan rộng, không tìm người để trách. Bản phân tích gọi người theo vai trò ("kỹ sư trực cấp 1", "trưởng ca"), không ghi họ tên. Câu hỏi đúng là "vì sao thao tác đó có thể gây hậu quả này và vì sao ta không phát hiện sớm hơn", không phải "ai đã làm sai". Người tham gia được khuyến khích kể trung thực điều mình đã thấy và đã nghĩ lúc đó, kể cả nhầm lẫn của mình.

Bản phân tích lưu trong kho tài liệu sự cố của đơn vị vận hành. Bản gửi khách hàng là bản rút gọn (mục 1, 3, 6 và phần hạng mục liên quan tới khách hàng), viết theo văn phong của [mau-thong-bao-su-co.md](mau-thong-bao-su-co.md), không có chi tiết kỹ thuật nhạy cảm.

## Mẫu

```markdown
# Phân tích sau sự cố <mã phiếu sự cố>: <tiêu đề ngắn theo tác động>

- Mức sự cố: <SEV-1 | SEV-2> (mức cao nhất từng có; ghi các lần đổi mức)
- Thời gian: phát hiện <giờ, ngày>; tuyên bố T0 <giờ>; khắc phục <giờ>; tổng thời gian ảnh hưởng <giờ, phút>
- Phạm vi: <cell, số tenant, số người dùng ước tính, chức năng bị ảnh hưởng>
- Vai trò: IC <vai trò>; người liên lạc <vai trò>; người ghi chép <vai trò>; SOC tham gia <có/không>
- Người viết: <vai trò>; ngày nộp: <ngày> (hạn: 5 ngày làm việc sau khắc phục)
- Trạng thái hạng mục: <số mở>/<tổng số>

## 1. Tóm tắt

<Ba tới năm câu: chuyện gì xảy ra, ai bị ảnh hưởng, đã khắc phục bằng cách nào, nguyên nhân chính.>

## 2. Dòng thời gian

| Thời điểm | Sự kiện, quyết định | Nguồn (cảnh báo, lệnh, commit, thông báo) |
|---|---|---|
| <HH:MM> | <...> | <...> |

## 3. Tác động

<Số người dùng, tenant, giao dịch bị ảnh hưởng; dữ liệu có mất không (RPO thực tế nếu có); vi phạm SLO nào,
lượng ngân sách lỗi đã tiêu; có rơi vào thời hạn của kỳ đánh giá không.>

## 4. Nguyên nhân

<Nguyên nhân trực tiếp, rồi các điều kiện góp phần, đi ngược lên bằng câu hỏi "vì sao" tới khi chạm điều kiện
của hệ thống hoặc quy trình. Không dừng ở "lỗi thao tác".>

## 5. Phát hiện và xử lý

- Phát hiện: <cảnh báo nào báo trước, sau bao lâu; nếu người dùng báo trước cảnh báo thì vì sao>
- Sổ tay: <sổ tay nào được dùng, có đúng và đủ không, cần sửa gì>
- Điều đã làm tốt: <...>
- Điều chưa tốt: <...>
- Điều may mắn: <yếu tố may mắn đã làm sự cố nhẹ hơn, để không dựa vào lần sau>

## 6. Hạng mục khắc phục

Mỗi hạng mục có đúng một người chịu trách nhiệm (theo vai trò, tên người ghi trong hệ thống theo dõi công việc)
và một hạn. Hạng mục loại "phòng ngừa" ngăn tái diễn; "phát hiện" giúp phát hiện sớm hơn; "giảm thiểu" làm
tác động nhỏ hơn khi tái diễn; "quy trình" sửa sổ tay, mẫu biểu hoặc lịch trực.

| Mã | Hạng mục | Loại | Người chịu trách nhiệm | Hạn | Trạng thái |
|---|---|---|---|---|---|
| <mã phiếu>-01 | <...> | <phòng ngừa/phát hiện/giảm thiểu/quy trình> | <vai trò> | <ngày> | <mở/đang làm/đóng> |

## 7. Bài học

<Điều đội rút ra áp dụng được cho các phần khác của nền tảng.>
```

## Theo dõi hạng mục tới khi đóng

Hạng mục khắc phục là một đầu vào cố định của CAB hằng tuần. Hạng mục quá hạn được đưa lên đầu chương trình họp; người chịu trách nhiệm hoặc nêu hạn mới có lý do, hoặc đề nghị chuyển người. Trước mỗi mốc hạn của kỳ đánh giá (OPS-17 mục 4), mọi hạng mục còn mở phải được đóng hoặc có quyết định chấp nhận rủi ro của chủ sản phẩm ghi trong biên bản CAB. Bản phân tích chỉ chuyển sang trạng thái "đã đóng" khi mọi hạng mục đã đóng.

## Liên quan

- [muc-su-co.md](muc-su-co.md), [hoi-dong-thay-doi.md](hoi-dong-thay-doi.md), [truc-van-hanh.md](truc-van-hanh.md)
