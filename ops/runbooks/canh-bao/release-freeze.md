# Dừng phát hành khi tiêu quá nửa ngân sách lỗi trước ngày 15

> **Truy vết:** TL-04 mục 11.3 (OPS-19: cell tiêu quá 50% ngân sách trước ngày 15 tạm dừng thay đổi thường) · luật `deploy/charts/observability-mgmt/rules/dai-han.yaml`.
> **Cảnh báo:** `ErrorBudgetHalfSpent` (info: tổng lỗi khả dụng tháng của cell vượt 50% ngân sách khi chưa tới ngày 15).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Cell đã dùng hơn nửa ngân sách lỗi của tháng trong nửa đầu tháng.

## Tác động

Không ảnh hưởng người dùng ngay; là tín hiệu quản trị: tạm dừng phát hành tính năng và thay đổi thường cho cell.

## Kiểm tra nhanh

Dashboard ngân sách lỗi theo cell: phần ngân sách đã tiêu, các sự cố đóng góp.

## Các bước

Quy tắc và cách CAB xử lý nằm ở `ops/van-hanh/hoi-dong-thay-doi.md` mục 2 (quy tắc ngân sách lỗi); ở đây chỉ là việc phải làm khi cảnh báo cháy.

1. Quản lý thay đổi chuyển mọi phiếu thay đổi thường của cell sang "hoãn" tới hết tháng và báo người đề xuất; ghi vào chương trình CAB tuần kế tiếp ([OPS-19](../ops/ops-19-hoi-dong-thay-doi.md)).
2. Chỉ cho phát hành bản sửa lỗi và vá an ninh tới cell đó (theo OPS-02, giữ đợt của cell); tính năng mới chờ sang tháng.
3. Đầu tháng, cảnh báo dùng cửa sổ trượt 14 ngày nên có thể còn cháy do lỗi của tháng trước: đối chiếu dashboard SLO theo tháng lịch trước khi giữ quyết định tạm dừng.
4. Rà các sự cố đã tiêu ngân sách; hạng mục khắc phục phải có người và hạn (`ops/van-hanh/mau-phan-tich-sau-su-co.md`).

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| (mọi thao tác) | (chưa có) | như mục "Kiểm tra nhanh" và "Các bước" |

## Kiểm sau khi làm

Biên bản CAB ghi quyết định tạm dừng; tháng sau ngân sách được đặt lại.

## Quay lui

Không áp dụng.

## Leo thang

Ngân sách tháng đã hết: báo chủ sản phẩm và trưởng ca SRE.

## Liên quan

[OPS-19](../ops/ops-19-hoi-dong-thay-doi.md), [OPS-02](../ops/ops-02-phat-hanh-theo-dot-cell.md), [slo-availability](slo-availability.md), `ops/van-hanh/hoi-dong-thay-doi.md`.
