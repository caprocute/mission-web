# Quy trình vận hành của con người (VH-03)

> Nguồn: TL-04 mục 11.3 "Đóng băng thay đổi và hội đồng thay đổi" (OPS-06, OPS-19), mục 11.7 "Trực vận hành, mức sự cố và runbook" (OPS-13, OPS-14), mục 11.8 (OPS-17), mục 9.7 (SEC-43); task `docs/tasks/VH-03-truc-su-co-cab.md`.

Thư mục này gom các quy trình mà con người thực hiện khi vận hành nền tảng Mission: ai trực, ai được gọi, một sự cố được phân mức và xử lý ra sao, một thay đổi đi qua hội đồng nào, và trong cửa sổ đóng băng được làm gì. Phần máy móc (định tuyến cảnh báo, cửa sổ đồng bộ của Argo CD, bộ điều khiển suy giảm) đã được hiện thực ở các task hạ tầng; các tài liệu ở đây nói người trực dùng những cơ chế đó thế nào và khi nào phải ra quyết định thay cho máy.

| Tệp | Nội dung |
|---|---|
| [truc-van-hanh.md](truc-van-hanh.md) | Ca trực SRE 24/7 hai cấp, tiếp nhận cảnh báo, bàn giao ca, trực tăng cường trước mốc hạn (OPS-13, OPS-17) |
| [muc-su-co.md](muc-su-co.md) | Bảng SEV-1..SEV-4, vai trò IC, người liên lạc, người ghi chép, nâng và hạ mức, phối hợp SOC |
| [mau-thong-bao-su-co.md](mau-thong-bao-su-co.md) | Mẫu thông báo sự cố gửi lãnh đạo và khách hàng |
| [mau-phan-tich-sau-su-co.md](mau-phan-tich-sau-su-co.md) | Mẫu phân tích sau sự cố không quy lỗi cá nhân cho SEV-1, SEV-2 |
| [hoi-dong-thay-doi.md](hoi-dong-thay-doi.md) | Ba loại thay đổi, CAB tuần, CAB khẩn, quy tắc ngân sách lỗi, mẫu phiếu yêu cầu thay đổi (OPS-19) |
| [dong-bang.md](dong-bang.md) | Lịch cửa sổ đóng băng, việc được phép, đồng bộ thủ công có kiểm soát (OPS-06) |

Biên bản diễn tập trên bàn nằm ở `ops/dien-tap/`. Sổ tay xử lý từng cảnh báo (OPS-14) nằm ở `ops/runbooks/`, công cụ thao tác của người trực là `vtctl` (`services-go/vtctl/README.md`).

## Nguyên tắc chung

Các tài liệu trong thư mục không ghi tên người, số điện thoại, địa chỉ thư, địa chỉ máy chủ hay đường dẫn webhook. Danh sách liên lạc, lịch phân ca có tên người và địa chỉ của hệ thống gọi trực nằm ngoài repo, trong kho tài liệu nội bộ của đơn vị vận hành; tài liệu ở đây chỉ gọi theo vai trò (trưởng ca SRE, kỹ sư cấp 2 CSDL, quản lý thay đổi…). Địa chỉ đích của Alertmanager đọc từ Secret `alertmanager-webhooks`, không nằm trong repo (INF-15).

Hệ thống gọi trực và hợp đồng SOC còn chờ chọn nhà cung cấp, nên trong giai đoạn này các đích `goi-truc`, `soc`, `chi-huy-su-co` là đích giả lập theo YC-PT-13; quy trình viết sẵn cho đích thật, chỉ thay địa chỉ trong cấu hình.

## Liên quan

- `ops/runbooks/README.md`: danh mục sổ tay theo cảnh báo.
- `deploy/charts/observability-mgmt/alertmanager/alertmanager.yaml`: định tuyến cảnh báo theo mức.
- `deploy/argocd/values.yaml`, `deploy/argocd/lich-dong-bang/`: cửa sổ đóng băng.
- `services-go/degrade-controller/README.md`: bộ điều khiển suy giảm ba mức, tín hiệu nâng SEV-2.
