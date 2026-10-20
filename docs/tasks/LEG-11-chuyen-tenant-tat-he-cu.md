# LEG-11 · Đợt 3/ADR-20 Chuyển tenant theo nhóm từ đầu kỳ, thay toàn bộ giao diện ZK, tắt hệ cũ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh LEG-11` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 – quy mô quốc gia (tắt hệ cũ) |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày cho kế hoạch, công cụ và một nhóm tenant; mỗi nhóm sau chạy theo runbook) |
| Phụ thuộc | LEG-10, QA-16, DL-11 (bổ sung hiển nhiên: các chức năng còn lại đã chuyển theo mẫu LEG-10; màn hình web thay ZK đã xong) |
| Truy vết | ADR-20; TL-01 "Lộ trình chuyển đổi…", mục "Đợt 2" (chuyển tenant theo nhóm từ đầu kỳ) và "Đợt 3" (thay toàn bộ giao diện ZK, tắt hệ cũ); TL-03 "Di trú…" giai đoạn 3 và 4; R8; EVT-21 (thay `NOTIFICATION`, `SMS_MASTER`) |
| Trạng thái | Bị chặn bởi bên ngoài: chưa có quyền truy cập hệ cũ |

## 1. Mục tiêu

Mọi tenant chuyển sang nền mới theo nhóm, mỗi nhóm bắt đầu từ đầu một kỳ đánh giá; người dùng chỉ còn dùng giao diện Angular (không còn ZK); thông báo đi qua EVT-21 và notification-service thay việc ghi `NOTIFICATION`, `SMS_MASTER`; sau đó tuyến cũ được tắt, kết nối Oracle bị thu hồi và bảng cũ được đóng băng chỉ đọc rồi lưu trữ. Việc tắt chỉ được làm khi R8 bằng 0 và tenant thí điểm đã hoàn thành trọn một kỳ trên hệ mới.

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/BACKEND.md`, `docs/quy-dinh/HA-TANG.md`.
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`: "Lộ trình chuyển đổi theo Strangler Fig" (mục "Đợt 2", "Đợt 3", "Chỉ số theo dõi chuyển đổi"), "Chuyển dần theo Strangler Fig với APISIX" (xoá mã cũ, đóng băng bảng).
- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`, "Di trú từ hệ hiện tại theo mẫu Strangler Fig" (giai đoạn 3: thông báo và xác thực; giai đoạn 4: tắt tuyến, thu hồi kết nối, đóng băng), chương "Đối soát và kiểm tra nhất quán" (R8 chặn tắt tuyến cũ).
- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`, "Đợt 3: quy mô quốc gia" và "Bảng chỉ số theo dõi và cách đo".
- Kết quả LEG-10, DL-11, QA-16; `docs/KE-HOACH-PHAT-TRIEN-PHAN-HE-WEB.md` (màn hình web thay ZK).
- Đầu vào người dùng cần cấp:
  - Lịch chuyển theo nhóm tenant gắn với lịch kỳ đánh giá, được chủ đầu tư duyệt; kênh thông báo người dùng cuối.
  - Quyền tắt tuyến cũ trên APISIX và DNS, quyền thu hồi tài khoản Oracle của hệ cũ, đầu mối DBA đóng băng và lưu trữ bảng.
  - Quyết định thời hạn giữ bản lưu trữ dữ liệu cũ theo quy định lưu trữ hồ sơ.

## 3. Phạm vi công việc

- Lập kế hoạch nhóm tenant (thí điểm, nhỏ, lớn) gắn ngày đầu kỳ; mỗi nhóm có điều kiện vào (chức năng cần thiết đã chuyển, dữ liệu kỳ cũ đã chuyển chỉ đọc theo DL-11) và điều kiện xong.
- Viết công cụ hoặc kịch bản GitOps chuyển cả nhóm tenant sang tuyến mới cho mọi chức năng cùng lúc, có quay lui theo nhóm trước khi kỳ mới phát sinh dữ liệu.
- Chuyển thông báo: hệ cũ ngừng ghi `NOTIFICATION`, `SMS_MASTER`, phát yêu cầu thông báo qua EVT-21 (qua integration-gateway hoặc nền mới); xác thực đã chuyển ở LEG-09, kiểm tra không còn `USER_TOKENS`.
- Xác nhận mọi màn hình ZK đã có màn hình Angular thay thế (đối chiếu kế hoạch web); chuyển hướng URL ZK cũ sang trang tương ứng.
- Checklist tắt hệ cũ: R8 = 0 cho mọi bảng, `ledger-verify` hợp lệ cho dữ liệu `MIGRATED`, không còn truy cập vào tuyến cũ trong một kỳ, sau đó tắt tuyến, thu hồi kết nối Oracle, đặt bảng cũ chỉ đọc, xuất lưu trữ có băm.
- Cập nhật bảng chỉ số theo dõi chuyển đổi (tỷ lệ người dùng nền mới 100%, bảng mới trong `VOFFICE` = 0).
- Điểm lệch cần lưu ý: khung ghi truy vết "OPS-08", nhưng OPS-08 trong TL-04 là "Chuyển tenant giữa cell", không phải chuyển tenant từ hệ cũ; task này truy vết theo ADR-20 và lộ trình TL-01, ghi điểm lệch vào wiki trang 14.

## 4. Ngoài phạm vi

- Màn hình web thay ZK (các task WEB); chuyển tenant giữa cell (OPS-08, nhóm VH); xoá mã nguồn hệ cũ khỏi kho lưu trữ (người dùng quyết định sau khi tắt).

## 5. Tệp được phép sửa

- `deploy/environments/<môi-trường>/strangler/**`, `deploy/environments/<môi-trường>/legacy/**`
- Mã hệ cũ liên quan thông báo (đường dẫn do người dùng cấp)
- `ops/runbooks/chuyen-tenant-theo-nhom.md`, `ops/runbooks/tat-he-cu.md`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `helm template` và `kubeconform` cho cấu hình chuyển nhóm và tắt tuyến không lỗi; diễn tập chuyển và quay lui một nhóm trên môi trường thử thành công.
- Nhóm thí điểm chạy trọn một kỳ trên hệ mới (QA-16 đạt); R8 = 0; không còn bản ghi mới trong `NOTIFICATION`, `SMS_MASTER` từ hệ cũ.
- Checklist tắt hệ cũ được ký xác nhận từng bước; kết nối Oracle của hệ cũ bị thu hồi; bảng cũ ở chế độ chỉ đọc.
- Runbook có bước kiểm chứng và quay lui; báo cáo theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
