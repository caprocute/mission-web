# Mức sự cố SEV-1..SEV-4 và chỉ huy sự cố (OPS-13)

> Nguồn: TL-04 mục 11.7 "Trực vận hành, mức sự cố và runbook" (bảng mức sự cố, phân tích sau sự cố), mục 6.9 (suy giảm ba mức), mục 9.7 (SEC-43), DR-10 "mất TTDL Hoà Lạc" (vai trò IC, quyết định chuyển vùng); INF-25 (`escalateAfter`).

## 1. Bảng mức sự cố

Bảng dưới đây chép nguyên từ TL-04 mục 11.7; khi TL-04 đổi, bảng này phải đổi theo. Thời gian phản hồi tính từ lúc cảnh báo đầu tiên được gửi (hoặc lúc phiếu hỗ trợ được tạo) tới lúc có người xác nhận và bắt đầu xử lý; thời gian khắc phục tính tới lúc dịch vụ trở lại bình thường, kể cả khi nguyên nhân gốc chưa được sửa hẳn.

| Mức | Định nghĩa | Thời gian phản hồi | Thời gian khắc phục mục tiêu | Thông báo |
|---|---|---|---|---|
| SEV-1 | Một cell trở lên không dùng được, mất dữ liệu, sự cố an ninh nghiêm trọng, hoặc DR | ≤ 15 phút | ≤ 4 giờ | Lãnh đạo, khách hàng mỗi 30 phút |
| SEV-2 | Chức năng chính suy giảm (mức suy giảm 2 kéo dài), vi phạm SLO nhanh | ≤ 30 phút | ≤ 8 giờ | Khách hàng bị ảnh hưởng mỗi 2 giờ |
| SEV-3 | Chức năng phụ lỗi, một tenant bị ảnh hưởng, có cách làm tạm | ≤ 4 giờ làm việc | ≤ 3 ngày làm việc | Qua phiếu hỗ trợ |
| SEV-4 | Lỗi nhỏ, câu hỏi | ≤ 1 ngày làm việc | Theo kế hoạch phát hành | Qua phiếu hỗ trợ |

Mọi sự cố SEV-1 và SEV-2 có bản phân tích sau sự cố không quy lỗi cá nhân trong 5 ngày làm việc, theo [mau-phan-tich-sau-su-co.md](mau-phan-tich-sau-su-co.md).

**Gợi ý xếp mức từ cảnh báo.** Bảng sau giúp người trực xếp mức nhanh; đây là gợi ý (suy luận từ định nghĩa ở trên và bảng cảnh báo mục 10.5), không thay cho phán đoán của IC.

| Dấu hiệu | Mức gợi ý |
|---|---|
| Cell không phục vụ được người dùng; cụm PostgreSQL của cell mất primary mà không tự chuyển được; quyết định chuyển vùng DR-10; chuỗi băm sổ cái đứt (DR-14); nghi xâm nhập có quyền quản trị (DR-11); lộ khoá (DR-12) | SEV-1 |
| `SLOAvailabilityFastBurn`; cell ở mức suy giảm ≥ 2 quá 15 phút (thông báo tự động của bộ điều khiển suy giảm); saga khoá kỳ của một tenant lớn dừng ở bước 2..4 gần mốc hạn | SEV-2 |
| Một tenant không dùng được một chức năng phụ; DLQ có bản ghi mới; `SLOAvailabilitySlowBurn` | SEV-3 |
| Câu hỏi sử dụng, lỗi hiển thị nhỏ không ảnh hưởng dữ liệu | SEV-4 |

Sự cố có yếu tố mất dữ liệu nghiệp vụ hoặc lộ dữ liệu C2, C3 luôn là SEV-1, dù số người bị ảnh hưởng ít.

## 2. Vai trò khi xử lý SEV-1, SEV-2

SEV-1 và SEV-2 được chỉ huy theo mô hình chỉ huy sự cố. Ba vai trò dưới đây là bắt buộc; một người không giữ hai vai trò trong SEV-1, còn trong SEV-2 người ghi chép có thể kiêm người liên lạc.

**Chỉ huy sự cố (IC).** Mặc định là trưởng ca SRE tại thời điểm tuyên bố. IC không tự gõ lệnh sửa lỗi mà điều phối: giao việc cho kỹ sư, quyết định nâng hoặc hạ mức, quyết định các thao tác có hệ quả lớn (bật mức suy giảm 3, chuyển vùng DR-10, cô lập cell), quyết định khi nào tuyên bố đã khắc phục. Với DR-10, quyết định chuyển vùng là quyết định của IC sau khi tham vấn trưởng ca và, nếu kịp, giám đốc vận hành; không chuyển vùng tự động.

**Người liên lạc.** Soạn và gửi thông báo theo đúng tần suất của bảng mức sự cố, bằng mẫu ở [mau-thong-bao-su-co.md](mau-thong-bao-su-co.md); trả lời câu hỏi của lãnh đạo, khách hàng và bộ phận hỗ trợ để kỹ sư không bị ngắt quãng. Người liên lạc lấy thông tin từ IC, không tự suy đoán nguyên nhân trong thông báo.

**Người ghi chép.** Ghi dòng thời gian của sự cố trong phiếu: thời điểm phát hiện, tuyên bố (T0), mỗi quyết định của IC kèm lý do, mỗi thao tác (lệnh `vtctl` đã chạy, commit GitOps), mỗi lần thông báo, thời điểm khắc phục. Dòng thời gian này là đầu vào trực tiếp của bản phân tích sau sự cố.

**Kỹ sư xử lý.** Cấp 1 và cấp 2 được IC giao việc; mỗi người báo kết quả cho IC trong kênh chỉ huy, không tự mở nhánh xử lý song song khi IC chưa biết.

## 3. Trình tự xử lý

1. Người phát hiện tuyên bố sự cố trong kênh trực, ghi mức ước lượng; IC mở kênh chỉ huy riêng cho sự cố, giao vai trò, ghi T0.
2. IC xác định phạm vi: cell nào, tenant nào, chức năng nào, có yếu tố an ninh không. Có yếu tố an ninh thì gọi SOC ngay (mục 5).
3. Người liên lạc gửi thông báo đầu tiên trong thời gian phản hồi của mức sự cố, kể cả khi chưa rõ nguyên nhân.
4. Kỹ sư ưu tiên giảm tác động trước, tìm nguyên nhân sau: bật mức suy giảm, chuyển tải, tạm dừng tác vụ nền không thiết yếu, quay lui bản phát hành gần nhất nếu sự cố bắt đầu ngay sau phát hành (quay lui là thay đổi khẩn, xem [hoi-dong-thay-doi.md](hoi-dong-thay-doi.md)).
5. Người liên lạc gửi thông báo cập nhật đúng tần suất: SEV-1 mỗi 30 phút, SEV-2 mỗi 2 giờ, kể cả khi không có gì mới.
6. Khi dịch vụ bình thường trở lại, IC theo dõi SLO thêm một khoảng (40 phút với DR-10; tối thiểu 30 phút với sự cố khác, suy luận) rồi tuyên bố đã khắc phục; người liên lạc gửi thông báo kết thúc.
7. IC giao người viết bản phân tích sau sự cố và hạn nộp (5 ngày làm việc với SEV-1, SEV-2).

**Bàn giao IC.** Khi IC phải rời (hết ca, sự cố kéo dài quá 8 giờ), IC mới đọc lại dòng thời gian, nhận tóm tắt trạng thái và việc đang giao, rồi tuyên bố trong kênh chỉ huy "IC chuyển sang <vai trò>" với thời điểm. Người ghi chép ghi lại lần bàn giao.

## 4. Nâng và hạ mức

IC có thể đổi mức bất cứ lúc nào, ghi lý do vào dòng thời gian, và người liên lạc thông báo việc đổi mức trong lần cập nhật kế tiếp.

**Nâng mức** khi tác động rộng hơn ước lượng ban đầu (thêm tenant, thêm cell), khi phát hiện yếu tố mất dữ liệu hoặc an ninh, khi sắp vượt thời gian khắc phục mục tiêu mà chưa có hướng xử lý, hoặc khi nhận thông báo nâng SEV-2 tự động của bộ điều khiển suy giảm. Sự cố đang ở SEV-3 mà rơi vào cửa sổ trước mốc hạn và ảnh hưởng tới việc nộp, ký của một tenant lớn thì nâng lên SEV-2 (suy luận: theo tinh thần OPS-17).

**Hạ mức** chỉ khi tác động đã giảm thật sự và đã ổn định qua ít nhất một chu kỳ thông báo: ví dụ cell đã hết mức suy giảm 2 nhưng còn lỗi ở một chức năng phụ thì hạ từ SEV-2 xuống SEV-3. Hạ mức không xoá nghĩa vụ phân tích sau sự cố: sự cố đã từng là SEV-1 hoặc SEV-2 vẫn phải có bản phân tích.

## 5. Phối hợp SOC khi có yếu tố an ninh

Sự cố có yếu tố an ninh gồm: cảnh báo Falco Critical, ảnh không ký bị từ chối hàng loạt, truy cập phá kính, thay đổi RBAC bất thường, lời gọi `decrypt` tới Transit tăng đột biến, xuất dữ liệu bất thường, chuỗi băm sổ cái đứt, nghi lộ khoá. Khi gặp các dấu hiệu này:

- IC gọi SOC ngay, không đợi xác minh xong; SOC phản hồi trong 15 phút (SEC-43).
- SOC làm chủ việc điều tra, cô lập và thu thập chứng cứ số; IC của SRE làm chủ việc khôi phục dịch vụ và chỉ khôi phục khi SOC đồng ý rằng việc khôi phục không xoá chứng cứ.
- Không khởi động lại, xoá hay dựng lại thành phần bị nghi xâm nhập trước khi SOC chụp chứng cứ; với DR-11, không khôi phục cấu hình hay tệp thực thi từ thời điểm sau mốc nghi ngờ.
- Thông báo ra ngoài về sự cố an ninh do người liên lạc soạn cùng SOC và được lãnh đạo đơn vị vận hành duyệt; không nêu chi tiết kỹ thuật có thể giúp kẻ tấn công.
- Báo cáo cơ quan chức năng theo quy định khi SOC xác định là sự cố an toàn thông tin phải báo cáo.

## Liên quan

- [truc-van-hanh.md](truc-van-hanh.md), [mau-thong-bao-su-co.md](mau-thong-bao-su-co.md), [mau-phan-tich-sau-su-co.md](mau-phan-tich-sau-su-co.md)
- `ops/runbooks/suy-giam-ba-muc.md`, `ops/runbooks/degradation.md`
- `services-go/degrade-controller/README.md`
