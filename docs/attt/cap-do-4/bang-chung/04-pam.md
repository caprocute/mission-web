> Nguồn: TL-04 mục 9.3 (SEC-42), mục 9.7; task INF-27. Ngày đánh giá: 23/10/2026.

# Bằng chứng 04: PAM có ghi phiên (SEC-42)

## Yêu cầu

Mọi truy cập đặc quyền tới Kubernetes, CSDL, máy chủ và HSM phải đi qua hệ thống quản lý truy cập đặc quyền (PAM). Mỗi phiên được ghi hình và lưu một năm. TL-04 yêu cầu bằng chứng là báo cáo phiên PAM hằng tháng.

## Biện pháp đã có

Repo hiện chưa có thành phần PAM. Một số biện pháp liên quan đã có và sẽ là điểm nối khi triển khai PAM. Thứ nhất, các thao tác vận hành thường gặp được đóng gói trong công cụ `vtctl`, chạy dưới dạng Job có kiểm soát (`services-go/vtctl/internal/runner/runner.go`), nên người vận hành không cần quyền quản trị cụm cho các việc này. Thứ hai, quy trình phá kính khi DBA cần truy cập dữ liệu C2 được thiết kế trong `deploy/openbao/cau-hinh.sh` và `ops/runbooks/luu-tru-xoa-khoi-phuc.md`. Thứ ba, chính sách Argo CD giới hạn quyền đồng bộ theo nhóm (`deploy/argocd/values.yaml`).

## Trạng thái và điểm còn thiếu

Trạng thái: **Thiếu**. Task INF-27 (PAM cho quản trị hạ tầng và CSDL) đang được làm ở một luồng khác, và tại ngày đánh giá chưa có trên nhánh chính. Theo phạm vi của VH-07, khi chưa có PAM thật thì dùng bản giả lập và ghi rõ là bằng chứng giả lập; khi INF-27 xong, trang này sẽ được cập nhật bằng mẫu báo cáo phiên của bản giả lập. Việc mua sắm sản phẩm PAM và lưu bản ghi phiên một năm thuộc đơn vị chủ quản. Xem [danh sách thiếu hụt](../thieu-hut.md).
