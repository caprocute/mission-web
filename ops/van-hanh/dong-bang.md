# Cửa sổ đóng băng thay đổi (OPS-06)

> Nguồn: TL-04 mục 11.3 "Đóng băng thay đổi và hội đồng thay đổi" (OPS-06, OPS-19), mục 11.8 (OPS-17), mục 6.4 (khung tăng trước KEDA), mục 6.9 (suy giảm ba mức); INF-04 (`deploy/argocd`), INF-25 (`deploy/charts/degrade-controller`).

## 1. Lịch cửa sổ đóng băng

Tải đỉnh của hệ thống tập trung ở cuối kỳ đánh giá, nên trong những khoảng đó môi trường sản xuất bị đóng băng: không phát hành tính năng, không thay đổi hạ tầng có kế hoạch. Lịch gồm hai phần.

**Cửa sổ cố định (TL-04).** Áp dụng cho mọi cell, mọi năm:

| Cửa sổ | Từ | Đến hết | Cấu hình sync window |
|---|---|---|---|
| Cuối quý | 00:00 ngày 24 của tháng cuối mỗi quý (3, 6, 9, 12) | ngày 10 của tháng đầu quý sau | `schedule: "0 0 24 3,12 *"`, `duration: 432h` và `schedule: "0 0 24 6,9 *"`, `duration: 408h` |
| Cuối năm, trước Tết | 00:00 ngày 20/1 | ngày 04/2, mở lại 00:00 ngày 05/2 | `schedule: "0 0 20 1 *"`, `duration: 384h` |

Hai cửa sổ này khai trong khoá `syncWindows` của `deploy/argocd/values.yaml`, được chart dựng vào AppProject `okr-apps` và `okr-nen-tang` với `kind: deny`, `manualSync: true`, `timeZone: Asia/Ho_Chi_Minh`. Argo CD tự chặn đồng bộ tự động trong cửa sổ, nên quy tắc không phụ thuộc vào trí nhớ của người.

**Vì sao cửa sổ cuối quý tách làm hai.** Argo CD chỉ nhận một lịch cron và một thời lượng cố định cho mỗi cửa sổ. Từ 00:00 ngày 24 tới hết ngày 10 tháng sau là 18 ngày khi tháng bắt đầu có 31 ngày (tháng 3, tháng 12) và 17 ngày khi tháng đó có 30 ngày (tháng 6, tháng 9). Trước đây một lịch chung 408 giờ cho cả bốn quý làm cửa sổ quý I và quý IV hết lúc 00:00 ngày 10, bỏ sót ngày 10/4 và 10/1 (phát hiện DT-SEV1-04 của buổi diễn tập trên bàn). Nay hai cửa sổ được dựng từ ngày cuối bằng hàm `cua_so_co_dinh_argocd` của `sinh_lich.py`, và kiểm thử của tác vụ đối chiếu `values.yaml` với hàm đó, đồng thời mô phỏng lịch cron cộng thời lượng qua nhiều năm để bảo đảm mỗi cửa sổ hết đúng 00:00 ngày 11.

**Cửa sổ bổ sung theo kỳ đánh giá của tenant.** Mỗi khách hàng có thể đặt mốc hạn khác với lịch quý. Tác vụ `deploy/argocd/lich-dong-bang/sinh_lich.py` đọc kỳ đánh giá (`config.period` của MOD-08) qua `GET /api/v1/periods` của từng cell, dựng khoảng đóng băng từ 7 ngày trước mốc hạn nộp tới 3 ngày sau mốc khoá kỳ dự kiến (tham số `--truoc`, `--sau`), gộp khoảng chồng nhau, bỏ khoảng đã nằm trọn trong cửa sổ cố định, rồi ghi `deploy/argocd/values-dong-bang.yaml` (khoá `syncWindowsBoSung`). Với `--mo-yeu-cau`, tác vụ mở merge request và không bao giờ tự gộp.

Việc của ca trực với lịch bổ sung:

- Mỗi khi kỳ đánh giá mới được tạo hoặc mốc hạn đổi, và chậm nhất 10 ngày trước mốc hạn (OPS-17), trưởng ca chạy hoặc kiểm tra kết quả chạy của tác vụ, duyệt merge request lịch bổ sung. Merge request này là thay đổi chuẩn.
- Đối chiếu lịch bổ sung với khung tăng trước KEDA sinh từ cùng nguồn (TL-04 mục 6.4): hai lịch phải bao cùng các mốc hạn. Lệch nhau thì chưa duyệt và báo đội nền tảng.
- Lịch bổ sung chỉ thêm khoảng đóng băng, không bao giờ rút ngắn cửa sổ cố định.

Lịch đóng băng của năm được công bố cho khách hàng bằng mẫu ở [mau-thong-bao-su-co.md](mau-thong-bao-su-co.md) mục 4.

## 2. Việc được phép trong cửa sổ đóng băng

Trong cửa sổ, chỉ cho phép hai loại thay đổi, và cả hai phải qua CAB khẩn ([hoi-dong-thay-doi.md](hoi-dong-thay-doi.md) mục 3):

1. Bản sửa lỗi nghiêm trọng: lỗi đang gây hoặc sắp gây sự cố SEV-1, SEV-2, hoặc lỗi làm sai kết quả đánh giá, chặn việc nộp, ký trong kỳ.
2. Bản vá an ninh cho lỗ hổng có điểm CVSS từ 9 trở lên.

Không thuộc diện thay đổi và vẫn làm bình thường trong cửa sổ: thao tác vận hành trong sổ tay (bật, ghim mức suy giảm bằng `vtctl degrade`, khởi động lại consumer, phát lại DLQ, theo dõi và thử lại saga khoá kỳ); tự co giãn của HPA, KEDA và Cluster Autoscaler; thay đổi trạng thái do bộ điều khiển suy giảm tự thực hiện. Bộ điều khiển suy giảm (INF-25) chỉ vá ConfigMap `apisix-degrade-levels` và tạm dừng tác vụ nền theo cảnh báo, không đi qua Argo CD, nên sync window không chặn nó; đó là chủ đích, vì suy giảm có kiểm soát là cơ chế bảo vệ chính trong giai đoạn tải đỉnh. Ngược lại, đổi tham số của chính bộ điều khiển (mức nền `baseLevel`, `holdDown`, `escalateAfter`) là thay đổi cấu hình qua Git và bị đóng băng như mọi thay đổi thường.

Policy Pack và UI Pack được phát hành qua pipeline cấu hình, không qua Argo CD, nên sync window không chặn được. Trong cửa sổ đóng băng, ca trực và chủ sản phẩm chỉ phát hành Policy Pack, UI Pack khi CAB khẩn duyệt, ví dụ để sửa một quy tắc tính điểm sai đang ảnh hưởng tới kỳ (suy luận: TL-04 không nói riêng về gói cấu hình, áp tinh thần của OPS-06).

## 3. Đồng bộ thủ công có kiểm soát

Sync window đặt `manualSync: true`, nên trong cửa sổ Argo CD vẫn cho người có vai trò `release-manager` đồng bộ thủ công. Quyền này chỉ dùng cho thay đổi đã được CAB khẩn duyệt, theo các bước:

1. Thay đổi đã nằm trong kho GitOps: merge request có mã phiếu thay đổi khẩn, được người thứ hai duyệt, commit có chữ ký (Argo CD chỉ đồng bộ commit có chữ ký thuộc `signatureKeys`).
2. Người thực hiện thông báo trong kênh trực và kênh chỉ huy sự cố (nếu có): mã phiếu, ứng dụng, cell sẽ đồng bộ, thời điểm.
3. Người có vai trò `release-manager` đăng nhập Argo CD qua Keycloak vận hành (có MFA), đồng bộ thủ công đúng các Application trong phạm vi phiếu, từng cell một theo thứ tự đợt cell của OPS-02; không đồng bộ "tất cả".
4. Sau mỗi cell, kiểm chứng theo mục "kiểm chứng sau thay đổi" của phiếu (SLO, kiểm tra khói) trước khi sang cell tiếp theo. Có dấu hiệu xấu thì dừng và quay lui theo kế hoạch quay lui của phiếu.
5. Người ghi chép (hoặc người thực hiện) ghi vào phiếu: các Application đã đồng bộ, revision, thời điểm, kết quả kiểm chứng.

Không tắt sync window, không sửa AppProject để "mở tạm" cửa sổ, không đồng bộ bằng tài khoản dùng chung. Nhật ký kiểm toán của Argo CD và API Kubernetes đi vào SIEM, nên mọi lần đồng bộ thủ công đều để lại dấu vết.

## 4. Mở lại sau cửa sổ

Ngày đầu sau cửa sổ không dồn thay đổi: CAB hằng tuần kế tiếp xếp lịch các thay đổi đã hoãn, ưu tiên thay đổi nhỏ, rủi ro thấp trước, và không dồn quá một thay đổi thường cho mỗi cell trong ngày đầu (suy luận). Các thay đổi khẩn đã làm trong cửa sổ được rà lại ở buổi CAB này.

## Liên quan

- [hoi-dong-thay-doi.md](hoi-dong-thay-doi.md), [truc-van-hanh.md](truc-van-hanh.md) mục 4
- `deploy/argocd/values.yaml`, `deploy/argocd/values-dong-bang.yaml`, `deploy/argocd/lich-dong-bang/sinh_lich.py`
- `deploy/charts/degrade-controller/values.yaml`, `ops/runbooks/suy-giam-ba-muc.md`
