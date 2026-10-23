# Hội đồng thay đổi (OPS-19)

> Nguồn: TL-04 mục 11.3 "Đóng băng thay đổi và hội đồng thay đổi" (OPS-06, OPS-19), mục 10.5 (cảnh báo `ErrorBudgetHalfSpent`), mục 9.6 (Argo CD chỉ đồng bộ commit có chữ ký, vai trò release-manager); INF-04 (`deploy/argocd`), INF-15 (`deploy/charts/observability-mgmt/rules/dai-han.yaml`).

## 1. Ba loại thay đổi

Mọi thay đổi trên môi trường sản xuất đi qua kho GitOps và được Argo CD đồng bộ; chỉ vai trò `release-manager` (nhóm `ops-release-managers` của Keycloak vận hành, đăng nhập có MFA) được đồng bộ. Hội đồng thay đổi (CAB) quyết định thay đổi nào được đi vào kho GitOps và khi nào; mức kiểm soát tuỳ theo loại thay đổi.

| Loại | Ví dụ | Cách duyệt | Trong cửa sổ đóng băng |
|---|---|---|---|
| Thay đổi chuẩn | Phát hành ứng dụng qua pipeline (canary, kiểm tra tự động); thay đổi cấu hình trong ngưỡng đã duyệt (số replica trong giới hạn HPA, khung tăng trước KEDA sinh tự động); phát hành Policy Pack, UI Pack qua pipeline cấu hình; lịch đóng băng bổ sung do `sinh_lich.py` sinh | Không cần CAB vì pipeline đã có kiểm soát; vẫn cần merge request được duyệt và commit có chữ ký | Không được đồng bộ. Policy Pack, UI Pack không đi qua Argo CD nên sync window không chặn được; quy trình chặn bằng người: chỉ phát hành khi CAB khẩn duyệt (suy luận, xem [dong-bang.md](dong-bang.md) mục 2) |
| Thay đổi thường | Nâng cấp Kubernetes, nâng operator; thay đổi lược đồ CSDL lớn; thêm cell; đổi định tuyến cảnh báo; đổi tham số bộ điều khiển suy giảm | CAB hằng tuần | Không được thực hiện |
| Thay đổi khẩn | Sửa lỗi gây sự cố SEV-1, SEV-2; vá an ninh CVSS ≥ 9; quay lui bản phát hành gây sự cố | CAB khẩn trực tuyến | Được thực hiện, theo [dong-bang.md](dong-bang.md) mục 3 |

Thay đổi chuẩn mà vượt ngưỡng đã duyệt (ví dụ tăng giới hạn tài nguyên quá mức trong values đã duyệt) trở thành thay đổi thường. Khi không chắc một thay đổi thuộc loại nào, người đề xuất xếp vào loại cao hơn.

## 2. CAB hằng tuần

**Thành phần.** Quản lý thay đổi (chủ trì), trưởng ca SRE, đại diện bốn lĩnh vực cấp 2 có thay đổi trong tuần, chủ sản phẩm; mời SOC khi có thay đổi liên quan an ninh.

**Đầu vào.** Phiếu yêu cầu thay đổi (mục 5) nộp trước buổi họp ít nhất một ngày làm việc; trạng thái ngân sách lỗi từng cell (cảnh báo `ErrorBudgetHalfSpent` và dashboard SLO); lịch đóng băng sắp tới ([dong-bang.md](dong-bang.md)); hạng mục khắc phục còn mở của các bản phân tích sau sự cố ([mau-phan-tich-sau-su-co.md](mau-phan-tich-sau-su-co.md)).

**Chương trình.** (1) Hạng mục khắc phục quá hạn; (2) trạng thái ngân sách lỗi và cell bị tạm dừng; (3) thay đổi đã thực hiện tuần trước, kết quả, có phải quay lui không; (4) xét từng phiếu mới; (5) lịch thực hiện tuần tới, đối chiếu cửa sổ đóng băng và mốc hạn của kỳ đánh giá.

**Quyết định.** Mỗi phiếu được duyệt, duyệt có điều kiện, hoãn hoặc từ chối, kèm lý do ghi vào biên bản. Phiếu được duyệt mang mã duyệt; merge request vào kho GitOps ghi mã phiếu trong mô tả để người duyệt merge request đối chiếu.

**Quy tắc ngân sách lỗi.** Ngân sách lỗi là đầu vào của CAB: cell đã tiêu quá 50% ngân sách lỗi của tháng trước ngày 15 tạm dừng thay đổi thường cho tới hết tháng, trừ thay đổi nhằm cải thiện độ tin cậy của chính cell đó (suy luận: ngoại lệ hợp lý, CAB ghi lý do). Cảnh báo `ErrorBudgetHalfSpent` (mức `info`, gửi chủ sản phẩm) là tín hiệu tự động của quy tắc này; khi cảnh báo cháy, quản lý thay đổi chuyển mọi phiếu thay đổi thường của cell đó sang "hoãn" và thông báo người đề xuất. Cảnh báo dùng cửa sổ trượt 14 ngày thay cho "từ đầu tháng" (ghi chú trong `dai-han.yaml`), nên đầu tháng CAB đối chiếu thêm dashboard SLO theo tháng lịch trước khi quyết định. Thay đổi chuẩn (phát hành qua pipeline) của cell bị tạm dừng chỉ gồm bản sửa lỗi; tính năng mới chờ sang tháng.

## 3. CAB khẩn

CAB khẩn họp trực tuyến khi cần, không đợi lịch tuần, với ít nhất ba người: quản lý thay đổi (hoặc người được uỷ quyền), trưởng ca SRE và chủ sản phẩm. Khi đang có sự cố, IC trình bày; CAB khẩn có thể họp ngay trong kênh chỉ huy sự cố. Quyết định cần cả ba người đồng ý và được ghi vào phiếu thay đổi khẩn (mục 5, đánh dấu "khẩn") trong lúc họp; phần mô tả chi tiết có thể bổ sung trong 1 ngày làm việc sau.

Thay đổi khẩn vẫn đi qua kho GitOps với commit có chữ ký và merge request được người thứ hai duyệt (bốn mắt); không sửa tay trên cụm (`kubectl edit`, `kubectl apply` ngoài Argo CD), trừ thao tác đã có trong sổ tay (bật mức suy giảm, khởi động lại consumer bằng `vtctl`) vốn là thao tác vận hành chứ không phải thay đổi cấu hình. Trong cửa sổ đóng băng, việc đồng bộ thay đổi khẩn làm theo [dong-bang.md](dong-bang.md) mục 3.

Mọi thay đổi khẩn được đưa ra CAB hằng tuần kế tiếp để rà lại: thay đổi có đạt mục tiêu không, có cần biến thành thay đổi thường có kế hoạch (ví dụ sửa tạm thành sửa hẳn) không.

## 4. Thực hiện và quay lui

Người thực hiện thay đổi thường, khẩn thông báo trong kênh trực trước khi bắt đầu và khi kết thúc; trưởng ca có quyền dừng thay đổi nếu đang có sự cố không liên quan. Mỗi phiếu có kế hoạch quay lui đã được kiểm chứng (với migration CSDL: theo mẫu mở rộng, thu hẹp của OPS-09, nên quay lui ứng dụng không cần quay lui lược đồ). Thay đổi làm phát sinh sự cố thì quay lui trước, phân tích sau; việc quay lui là thay đổi khẩn.

## 5. Mẫu phiếu yêu cầu thay đổi

```markdown
# Phiếu thay đổi <mã phiếu> <(khẩn) nếu là thay đổi khẩn>

- Loại: <thường | khẩn>  (thay đổi chuẩn không cần phiếu)
- Người đề xuất: <vai trò, đội>
- Phạm vi: <cell, môi trường, thành phần; số tenant bị ảnh hưởng>
- Cửa sổ thực hiện đề xuất: <từ giờ ngày tới giờ ngày>; đã đối chiếu lịch đóng băng: <có>
- Liên quan: <merge request kho GitOps; mã sự cố nếu là thay đổi khẩn; mã CVE và điểm CVSS nếu là bản vá an ninh>

## Mô tả và lý do
<Thay đổi gì, vì sao, nếu không làm thì rủi ro gì.>

## Đánh giá rủi ro và tác động
<Người dùng có bị gián đoạn không, bao lâu; rủi ro mất dữ liệu; ngân sách lỗi hiện tại của cell; có trùng
mốc hạn của kỳ đánh giá không.>

## Kế hoạch thực hiện
<Các bước, người thực hiện theo vai trò, thời lượng dự kiến.>

## Kiểm chứng sau thay đổi
<Tiêu chí thành công: SLO, kiểm tra khói, dashboard.>

## Kế hoạch quay lui
<Các bước quay lui, đã thử ở staging hay chưa, thời gian quay lui dự kiến.>

## Quyết định của CAB
- Ngày họp: <ngày>; thành phần: <vai trò>
- Quyết định: <duyệt | duyệt có điều kiện | hoãn | từ chối>; điều kiện, lý do: <...>
- Kết quả thực hiện: <thành công | quay lui | huỷ>; ghi chú: <...>
```

## Liên quan

- [dong-bang.md](dong-bang.md), [muc-su-co.md](muc-su-co.md), [mau-phan-tich-sau-su-co.md](mau-phan-tich-sau-su-co.md)
- `deploy/argocd/values.yaml` (vai trò `release-manager`), `deploy/argocd/templates/appproject.yaml`
- `deploy/charts/observability-mgmt/rules/dai-han.yaml` (`ErrorBudgetHalfSpent`)
