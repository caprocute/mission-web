# Mẫu thông báo sự cố

> Nguồn: TL-04 mục 11.7 (cột "Thông báo" của bảng mức sự cố), mục 11.8 OPS-17 mục (6) (thông báo cửa sổ đóng băng), mục 9.7 (SEC-43).

Thông báo sự cố gửi lãnh đạo đơn vị vận hành và cán bộ đầu mối của khách hàng (các cơ quan, tổ chức dùng nền tảng). Người nhận không phải kỹ sư, nên thông báo viết bằng văn phong hành chính nghiêm túc, câu đầy đủ, nói rõ người dùng bị ảnh hưởng thế nào và nên làm gì, không nêu tên máy chủ, địa chỉ IP, tên cụm, tên cơ sở dữ liệu, mã lỗi nội bộ, phiên bản phần mềm hay chi tiết lỗ hổng. Với sự cố có yếu tố an ninh, nội dung được SOC xem trước và lãnh đạo đơn vị vận hành duyệt (xem [muc-su-co.md](muc-su-co.md) mục 5).

Giờ trong thông báo là giờ Việt Nam, ghi dạng `HH:MM ngày DD/MM/YYYY`. Phạm vi ghi theo tên đơn vị (tenant) và khu vực phục vụ mà khách hàng hiểu, không ghi mã cell.

Người liên lạc giữ đúng tần suất: SEV-1 gửi lãnh đạo và khách hàng mỗi 30 phút, SEV-2 gửi khách hàng bị ảnh hưởng mỗi 2 giờ; SEV-3, SEV-4 trả lời qua phiếu hỗ trợ, không dùng các mẫu dưới đây.

## 1. Thông báo đầu tiên

```text
Tiêu đề: [Thông báo sự cố] Gián đoạn dịch vụ Mission từ <giờ bắt đầu>

Kính gửi: <chức danh người nhận, tên đơn vị>

Từ <giờ bắt đầu>, hệ thống Mission ghi nhận sự cố làm <mô tả tác động theo cách người dùng thấy, ví dụ:
cán bộ của các đơn vị thuộc khu vực phía Bắc không đăng nhập được / chức năng nộp minh chứng phản hồi chậm>.

Phạm vi ảnh hưởng: <các đơn vị, khu vực bị ảnh hưởng>. Các chức năng khác vẫn hoạt động bình thường.
Dữ liệu đã nộp trước thời điểm sự cố được bảo toàn. <Chỉ ghi câu này khi đã xác nhận; nếu chưa xác nhận thì
ghi: "Bộ phận kỹ thuật đang kiểm tra tính toàn vẹn của dữ liệu và sẽ thông báo kết quả.">

Bộ phận vận hành đang khắc phục. Trong thời gian này, đề nghị đồng chí <hướng dẫn tạm thời, ví dụ: lưu bản
nháp và thực hiện lại sau khi có thông báo; liên hệ đầu mối hỗ trợ của đơn vị nếu có việc cần xử lý gấp>.

Thông báo tiếp theo sẽ được gửi trước <giờ, theo tần suất của mức sự cố>.

Đầu mối hỗ trợ: <kênh hỗ trợ đã thống nhất với khách hàng>
```

## 2. Thông báo cập nhật

```text
Tiêu đề: [Cập nhật lần <n>] Sự cố dịch vụ Mission từ <giờ bắt đầu>

Kính gửi: <chức danh người nhận, tên đơn vị>

Tình hình đến <giờ cập nhật>: <tiến độ khắc phục bằng ngôn ngữ thông thường, ví dụ: bộ phận vận hành đã chuyển
hệ thống sang trung tâm dữ liệu dự phòng và đang kiểm tra trước khi mở lại cho người dùng>.

Phạm vi ảnh hưởng hiện nay: <giữ nguyên / đã thu hẹp còn ... / đã mở rộng tới ...>.
Thời gian dự kiến khôi phục: <giờ dự kiến, hoặc "chưa xác định, sẽ thông báo trong lần cập nhật tiếp theo">.

Thông báo tiếp theo sẽ được gửi trước <giờ>.
```

## 3. Thông báo kết thúc

```text
Tiêu đề: [Đã khắc phục] Sự cố dịch vụ Mission từ <giờ bắt đầu>

Kính gửi: <chức danh người nhận, tên đơn vị>

Sự cố bắt đầu từ <giờ bắt đầu> đã được khắc phục lúc <giờ khôi phục>. Hệ thống đã hoạt động bình thường trở lại
với <phạm vi>.

<Một trong hai câu:> Dữ liệu của đơn vị được bảo toàn đầy đủ. / Một phần thao tác thực hiện trong khoảng
<giờ> đến <giờ> chưa được ghi nhận; đề nghị cán bộ kiểm tra lại và thực hiện lại các thao tác này.

<Nếu sự cố rơi vào thời hạn nộp, ký của kỳ đánh giá:> Đơn vị vận hành đề nghị <cơ quan có thẩm quyền về thời
hạn kỳ đánh giá> xem xét điều chỉnh thời hạn cho các đơn vị bị ảnh hưởng.

Báo cáo nguyên nhân và biện pháp phòng ngừa sẽ được gửi tới đồng chí trước <ngày, trong 5 ngày làm việc>.

Đơn vị vận hành xin lỗi vì sự bất tiện và cảm ơn sự phối hợp của đồng chí.
```

## 4. Thông báo cửa sổ đóng băng và kênh hỗ trợ (OPS-17 mục 6)

```text
Tiêu đề: [Thông báo] Lịch hỗ trợ cao điểm kỳ đánh giá <tên kỳ>

Kính gửi: <chức danh người nhận, tên đơn vị>

Để bảo đảm hệ thống Mission ổn định trong giai đoạn cao điểm của kỳ đánh giá <tên kỳ>, từ <ngày bắt đầu> đến
hết <ngày kết thúc>, đơn vị vận hành tạm dừng mọi nâng cấp tính năng và thay đổi không khẩn cấp. Chỉ các bản
sửa lỗi nghiêm trọng và bản vá an ninh được triển khai trong thời gian này.

Trong thời gian trên, đội hỗ trợ trực tăng cường. Đồng chí liên hệ qua <kênh hỗ trợ đã thống nhất>; yêu cầu
khẩn được tiếp nhận 24 giờ mỗi ngày.

Đề nghị đơn vị chủ động hoàn thành việc nộp, duyệt trước thời hạn <mốc hạn> để tránh dồn việc vào ngày cuối.
```

## Liên quan

- [muc-su-co.md](muc-su-co.md), [mau-phan-tich-sau-su-co.md](mau-phan-tich-sau-su-co.md), [dong-bang.md](dong-bang.md)
