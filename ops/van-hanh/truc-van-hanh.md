# Trực vận hành 24/7 (OPS-13, OPS-17)

> Nguồn: TL-04 mục 11.7 "Trực vận hành, mức sự cố và runbook" (OPS-13, OPS-14), mục 11.8 "Chuẩn bị trước mốc hạn" (OPS-17), mục 9.7 (SEC-43), mục 10.5 (bảng cảnh báo); INF-15 (định tuyến Alertmanager); INF-25 (bộ điều khiển suy giảm).

## 1. Tổ chức ca trực

Nền tảng có ca trực SRE liên tục 24 giờ mỗi ngày, bảy ngày mỗi tuần, chia hai cấp. Cách chia này giúp người nhận cảnh báo đầu tiên luôn có mặt và xử lý được phần lớn tình huống bằng sổ tay, còn kỹ sư chuyên sâu chỉ bị gọi khi thật sự cần, nên không bị kiệt sức vì cảnh báo vặt.

**Cấp 1 (người trực ca).** Mỗi ca có ít nhất một kỹ sư cấp 1 và một trưởng ca SRE (trưởng ca có thể là chính kỹ sư cấp 1 vào ban đêm, cuối tuần). Cấp 1 tiếp nhận mọi cảnh báo `critical` qua hệ thống gọi trực và cảnh báo `warning` qua kênh nhắn tin của đội, xác nhận cảnh báo, mở sổ tay tương ứng ở `ops/runbooks/` theo `runbook_url` của cảnh báo, thực hiện các bước kiểm tra nhanh và hành động theo nhánh. Thao tác thường xuyên dùng `vtctl` (bật mức suy giảm, khởi động lại consumer, phát lại DLQ, theo dõi saga khoá kỳ); mọi lệnh ghi của `vtctl` chạy thử trước, chỉ thực hiện khi thêm `--confirm`, và để lại bản ghi kiểm toán.

**Cấp 2 (kỹ sư chuyên sâu theo lĩnh vực).** Cấp 2 không trực tại chỗ mà ở chế độ sẵn sàng được gọi, theo bốn lĩnh vực:

| Lĩnh vực | Phạm vi | Đội nhận cảnh báo trong Alertmanager |
|---|---|---|
| Nền tảng | Kubernetes, Argo CD, cổng APISIX, Kafka, mạng, năng lực node | `nen-tang`, `chu-service` |
| CSDL | Cụm PostgreSQL CNPG, PgBouncer, sao lưu và khôi phục, nhân bản sang phía Nam | `dba` |
| Lưu trữ | Ceph RGW multisite, OpenSearch, lưu trữ nhật ký | `nhan-tin-sre` (nhãn `doi="sre-luu-tru"`) |
| An ninh | OpenBao, HSM, PKI, Kyverno, Falco, phối hợp SOC | `soc`, `quan-tri-khoa` |

Cấp 1 gọi cấp 2 khi sổ tay nói "leo thang", khi đã làm hết các nhánh của sổ tay mà triệu chứng không giảm, hoặc khi sự cố được xếp SEV-1, SEV-2 (xem [muc-su-co.md](muc-su-co.md)). Cấp 2 phản hồi trong thời gian phản hồi của mức sự cố tương ứng.

**Lịch phân ca.** Lịch phân ca có tên người, số liên lạc và người dự phòng được giữ trong hệ thống gọi trực và kho tài liệu nội bộ, không ghi vào repo. Mỗi vị trí cấp 2 có một người chính và một người dự phòng; hệ thống gọi trực tự chuyển sang người dự phòng khi người chính không xác nhận trong 10 phút (suy luận: TL-04 không nêu số, chọn nhỏ hơn thời gian phản hồi SEV-1).

## 2. Tiếp nhận cảnh báo

Alertmanager của cụm quản trị (INF-15) định tuyến cảnh báo theo mức: `critical` gọi trực ca rồi đi tiếp tới tuyến đội phụ trách; `warning` nhắn tin kênh SRE; `info` (ngân sách lỗi quá nửa trước ngày 15) báo chủ sản phẩm; `capacity` (ngưỡng nâng bậc của TL-05) chỉ báo đội nền tảng, nhắc lại mỗi ngày. Quy tắc ức chế đã che các cảnh báo hệ quả (outbox tồn đọng khi cụm CNPG lỗi, độ trễ khi cell đã vào mức suy giảm 2), nên người trực xử lý cảnh báo nguyên nhân trước.

Khi nhận một cảnh báo, người trực cấp 1 làm theo thứ tự:

1. Xác nhận cảnh báo trong hệ thống gọi trực, để hệ thống không gọi tiếp người dự phòng.
2. Mở sổ tay theo `runbook_url`, đọc phần triệu chứng và tác động, ước lượng mức sự cố theo bảng ở [muc-su-co.md](muc-su-co.md).
3. Nếu mức ước lượng là SEV-1 hoặc SEV-2, tuyên bố sự cố ngay (không đợi xử lý xong mới tuyên bố) và gọi trưởng ca làm IC.
4. Thực hiện kiểm tra nhanh, hành động theo nhánh của sổ tay; mỗi hành động ghi vào dòng thời gian của phiếu sự cố.
5. Xác nhận đã khắc phục theo mục "cách xác nhận" của sổ tay, rồi đóng hoặc chuyển phiếu.

**Tín hiệu tự động nâng SEV-2.** Bộ điều khiển suy giảm (INF-25) gửi một thông báo nâng SEV-2 khi cell ở mức suy giảm từ 2 trở lên kéo dài quá 15 phút (`escalateAfter`). Người trực coi thông báo này như một lời tuyên bố SEV-2 đã có sẵn: mở phiếu SEV-2 nếu chưa có và giao IC, không tự hạ mức chỉ vì cell đang tự phục hồi.

**Cảnh báo an ninh.** Cảnh báo có nhãn `doi="soc"` (Falco Critical, Kyverno từ chối ảnh, chuỗi băm sổ cái đứt) đi thẳng tới SOC. Người trực cấp 1 vẫn mở phiếu và theo dõi, nhưng không tự xoá chứng cứ, không khởi động lại pod bị nghi xâm nhập trước khi SOC đồng ý (TL-04 DR-11).

## 3. Bàn giao ca

Ca kết thúc chỉ khi đã bàn giao xong. Bàn giao làm trực tiếp (gặp mặt hoặc cuộc gọi) trong 15 phút chồng ca, kèm bản bàn giao viết trong kênh trực, gồm:

- Sự cố đang mở: mức, IC hiện tại, việc đang làm, mốc thông báo tiếp theo.
- Cảnh báo đã xác nhận nhưng chưa đóng, và lý do.
- Mức suy giảm hiện tại của từng cell; các lần ghim tay (`vtctl degrade status`) còn hạn và thời điểm hết hạn.
- Thay đổi đang chạy hoặc đã lên lịch trong ca sau (phiếu thay đổi, cửa sổ thực hiện), và cửa sổ đóng băng nếu đang hoặc sắp có hiệu lực.
- Saga khoá kỳ đang chạy (`vtctl period-lock status`): tenant nào, đang ở bước nào, thời điểm dự kiến của bước 4 (ký lô).
- Việc dở dang cần người ca sau làm tiếp, với người chịu trách nhiệm.

Người nhận ca đọc lại các mục trên, xác nhận trong kênh trực, rồi mới nhận quyền nhận cuộc gọi trong hệ thống gọi trực. Nếu đang có SEV-1, IC không bàn giao giữa chừng trừ khi đã làm quá 8 giờ liên tục; khi đó bàn giao IC theo [muc-su-co.md](muc-su-co.md) mục 3.

## 4. Trực tăng cường trước mốc hạn (OPS-17)

Tải đỉnh của hệ thống tập trung ở cuối kỳ đánh giá, trùng cửa sổ đóng băng ([dong-bang.md](dong-bang.md)). Mười ngày trước mỗi mốc hạn của kỳ đánh giá, SRE và chủ sản phẩm rà danh mục OPS-17 của TL-04, và người trực có trách nhiệm với các mục sau:

| Mục OPS-17 | Việc của ca trực |
|---|---|
| (1) Lịch tăng trước của KEDA | Đối chiếu khung tăng trước đã sinh với mốc hạn của từng tenant lớn |
| (4) Ngân sách lỗi | Xem cảnh báo `ErrorBudgetHalfSpent`; đóng các hạng mục khắc phục còn mở trước mốc |
| (5) Lịch trực tăng cường | Lập và xác nhận lịch trực tăng cường dưới đây |
| (6) Thông báo khách hàng | Gửi thông báo cửa sổ đóng băng và kênh hỗ trợ theo [mau-thong-bao-su-co.md](mau-thong-bao-su-co.md) mục 4 |
| (7) Diễn tập khôi phục | Kiểm tra không có cảnh báo `RestoreCheckStale` |
| (8) Dung lượng | Ceph, Kafka, OpenSearch còn ≥ 30% sau lượng tăng dự kiến |

Lịch trực tăng cường áp dụng từ 3 ngày trước mốc hạn nộp tới khi saga khoá kỳ của mọi tenant lớn qua bước 5 (suy luận về độ dài, TL-04 chỉ yêu cầu xác nhận lịch): mỗi ca có thêm một kỹ sư cấp 1; cấp 2 CSDL và nền tảng trực tại chỗ trong giờ cao điểm (8 giờ tới 11 giờ, 14 giờ tới 17 giờ) thay vì chờ gọi; trưởng ca nắm thời điểm dự kiến của bước 4 (ký lô) với từng tenant lớn. Sau mốc bước 4, lỗi ở bước 4 hoặc 5 chỉ được xử lý bằng thử lại hoặc sửa tiến về phía trước, không quay lui (sổ tay `ops/runbooks/saga-chot-ky.md`).

## 5. Phối hợp với SOC (SEC-43)

SOC 24/7 của đơn vị an ninh mạng giám sát qua SIEM, nhận luồng `sec.*` và có thời gian phản hồi sự cố Critical không quá 15 phút. Ca trực SRE và SOC dùng chung phiếu sự cố khi sự cố có yếu tố an ninh: SOC làm chủ phần điều tra và thu thập chứng cứ, IC của SRE làm chủ việc khôi phục dịch vụ. Cách phân vai chi tiết ở [muc-su-co.md](muc-su-co.md) mục 5.

## Liên quan

- [muc-su-co.md](muc-su-co.md), [dong-bang.md](dong-bang.md), [hoi-dong-thay-doi.md](hoi-dong-thay-doi.md)
- `ops/runbooks/README.md`, `ops/runbooks/suy-giam-ba-muc.md`, `ops/runbooks/saga-chot-ky.md`
- `deploy/charts/observability-mgmt/alertmanager/alertmanager.yaml`
