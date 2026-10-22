# Sổ tay xoay vòng khoá dữ liệu (DEK và KEK)

> Nguồn: TL-01 "Phân cấp khoá" và ba loại xoay vòng (ADR-17, ADR-30); TL-03 "Schema keyring và bảng dek"; TL-04 mục 9.4 (SEC-11, SEC-12), OPS-20; DL-14, NEN-015, INF-13.
> Mã: `backend/libs/key-rotation` (lớp `KeyRotationMain`); chart: `deploy/charts/mission-core/templates/key-rotation.yaml`.

Sổ tay này dành cho đội quản trị khoá và SRE trực cell. Mỗi CSDL có schema `keyring` giữ DEK theo tenant, tháng và mục đích, đã bọc bằng KEK của tenant trong OpenBao Transit. Có ba loại xoay vòng, mỗi loại là một lệnh của cùng một chương trình:

| Lệnh | Khi nào | Đụng tới dữ liệu? | Cách chạy |
|---|---|---|---|
| `thang` | 00:05 ngày 1 hằng tháng | Không. Sinh trước DEK tháng mới cho mục đích đang dùng, DEK `ACTIVE` tháng trước sang `DECRYPT_ONLY` | CronJob `xoay-dek-thang` |
| `boc-lai` | Hằng ngày 02:30, và ngay sau khi KEK xoay | Không. Bọc lại DEK có bản bọc cũ hơn phiên bản KEK mới nhất bằng `transit/rewrap`, cập nhật `wrapped_dek` và `kek_ref` | CronJob `boc-lai-dek` |
| `ma-hoa-lai` | Khi nghi một DEK bị lộ | Có. Giải mã từng dòng bằng DEK nghi lộ, mã hoá lại bằng DEK `ACTIVE` hiện tại, xong thì DEK cũ sang `RETIRED` | Job `ma-hoa-lai-dek-<phiếu>`, cần hai người duyệt |

Mọi lần chạy ghi một sự kiện `sys.audit.v1` loại `ADMIN_ACTION` (đối tượng `keyring.dek`, hành động `KEY_ROTATION_DEK_MONTHLY`, `KEY_ROTATION_KEK_REWRAP` hoặc `KEY_ROTATION_DEK_REENCRYPT`) và đẩy chỉ số lên Pushgateway: `vt_key_age_days{loai="dek",muc_dich}`, `vt_key_rotation_items_total{job,ket_qua}`, `vt_key_rotation_remaining{job}`. Không chỉ số, nhật ký hay sự kiện nào chứa vật liệu khoá.

## Chuẩn bị một lần cho mỗi CSDL

1. Trong namespace của mission-core, ESO đồng bộ ba Secret: `mission-core-db-keyring` (khoá `password`, vai trò `svc_keyring`), `key-rotation-openbao-token` (khoá `token`, chính sách chỉ có `rewrap`, `encrypt`, `decrypt` trên `transit/*/kek-tenant-*` và đọc `transit/keys/kek-tenant-*`), và khi cần mã hoá lại thì `mission-core-db-key-rotation-data` (vai trò dữ liệu có `UPDATE` trên các cột `_enc`, `dek_id`, `row_version`).
2. ConfigMap `tenant-list` (khoá `tenants.txt`, một UUID mỗi dòng) liệt kê tenant đặt trên CSDL này. RLS của `keyring.dek` không cho liệt kê chéo tenant, nên job chỉ làm việc với tenant có trong tệp.
3. Bật trong values của môi trường: `keyRotation.enabled: true`, `keyRotation.jobActor: <UUID tài khoản dịch vụ của job>`, `keyRotation.dbUrl`, `keyRotation.pushgateway`. Kiểm tra bằng `helm template` rồi để Argo CD đồng bộ.

## Xoay KEK hằng năm

KEK của tenant xoay theo lịch 365 ngày do CronJob `openbao-xoay-khoa` của INF-13 (xem `openbao-van-hanh.md`). Việc của sổ tay này là đảm bảo mọi DEK được bọc lại trước khi tắt phiên bản KEK cũ.

1. Xác nhận KEK đã xoay: `bao read transit/keys/kek-tenant-<tenant>` cho `latest_version` tăng một.
2. Chạy bọc lại ngay thay vì chờ lịch: `kubectl -n <ns> create job boc-lai-tay --from=cronjob/boc-lai-dek`. Với vài trăm DEK của một tenant, job xong trong vài giây.
3. Kiểm chứng: nhật ký job ghi số DEK đã bọc lại và số DEK đã mới; chạy lại lần hai thì số đã bọc lại bằng 0. Truy vấn với vai trò `svc_keyring` trong ngữ cảnh tenant: `select count(*) from keyring.dek where status <> 'DESTROYED' and wrapped_dek not like 'vault:v<mới nhất>:%'` (cột lưu dạng `bytea`, so bằng `convert_from(wrapped_dek,'UTF8')`) phải trả 0.
4. Đọc lại mẫu: chọn vài bản ghi C2 của tenant thuộc các tháng cũ, mở bằng chức năng nghiệp vụ hoặc bằng lệnh kiểm chứng của ứng dụng; bộ đệm DEK trong pod sống 15 phút, nên đợi hết thời gian đó hoặc khởi động lại pod để chắc chắn đọc qua bản bọc mới.
5. Chỉ sau khi bước 3 và 4 đạt, tắt giải mã bằng phiên bản cũ: `bao write transit/keys/kek-tenant-<tenant>/config min_decryption_version=<mới nhất>`. Ghi số phiếu thay đổi vào biên bản.

**Quay lui.** Nếu bước 4 thất bại sau khi đã đặt `min_decryption_version`, hạ giá trị đó về phiên bản cũ (Transit cho phép hạ khi phiên bản cũ chưa bị `trim`); không bao giờ `trim` phiên bản KEK khi còn DEK bọc bằng nó. Job bọc lại cập nhật từng DEK theo kiểu so khớp bản bọc cũ, nên dừng giữa chừng không làm hỏng dòng nào: chạy lại job là đủ.

## Nghi lộ một DEK

Nghi lộ DEK nghĩa là khoá đã mở bọc có thể đã ra khỏi bộ nhớ của pod (heap dump, lỗi ghi nhật ký, nhân viên có quyền phá kính). Việc mã hoá lại thay đổi dữ liệu, nên cần hai người: người đề nghị và người duyệt phải khác nhau, có số phiếu sự cố.

1. Xác định `dek_id`, tenant, mục đích và tháng. Nếu DEK nghi lộ là DEK `ACTIVE` của tháng hiện tại, chương trình từ chối, vì ràng buộc duy nhất `(tenant_id, purpose, valid_month)` không cho có DEK thay thế trong cùng tháng: chờ sang tháng (CronJob tháng chuyển DEK đó sang `DECRYPT_ONLY`) hoặc chuyển tay sang `DECRYPT_ONLY` sau ngày 1 tháng sau. DEK `BLIND_INDEX` không mã hoá lại bằng job này (chỉ mục mù cần tính lại toàn bộ cột `_bidx`, ngoài phạm vi).
2. Mở PR GitOps đặt `keyRotation.reencrypt` gồm `tenant`, `dek`, `phieu`, `nguoiDeNghi`, `nguoiDuyet`; PR cần hai người duyệt như quy trình phá kính. Argo CD tạo Job `ma-hoa-lai-dek-<phiếu>`.
3. Job duyệt mọi cột C2 của các bảng có `dek_id` và `tenant_id` trong CSDL, xử lý theo lô với `FOR UPDATE SKIP LOCKED`, tăng `row_version` từng dòng để client đang sửa nhận 409 thay vì ghi đè.
4. Kiểm chứng: nhật ký job ghi số dòng theo bảng và `con_lai=0`, DEK chuyển `RETIRED`; truy vấn `select count(*) from <bảng> where dek_id = '<dek>'` trên từng bảng phải trả 0; đọc lại mẫu vài bản ghi vừa mã hoá lại. Sự kiện kiểm toán `KEY_ROTATION_DEK_REENCRYPT` mang số phiếu và người duyệt.
5. Gỡ khối `keyRotation.reencrypt` khỏi values bằng PR tiếp theo. Huỷ hẳn DEK cũ (`DESTROYED`) theo DL-13 khi hết thời gian giữ bản sao lưu có dữ liệu cũ.

**Quay lui khi job dừng giữa chừng.** Mỗi lô là một giao dịch riêng: dòng đã mã hoá lại dùng DEK mới, dòng chưa xử lý vẫn dùng DEK cũ, cả hai đều đọc được vì DEK cũ chưa bị huỷ. Cảnh báo `VtMaHoaLaiDekDungGiuaChung` báo khi còn dòng sau một ngày. Xoá Job cũ rồi để Argo CD tạo lại (hoặc `kubectl create job --from` với cùng tham số) là chạy tiếp từ chỗ dừng. Không có bước "hoàn tác" dữ liệu, và không cần: không có trạng thái nào mất khả năng đọc.

## Cảnh báo

**VtDekQuaHan.** DEK `ACTIVE` của một mục đích có tuổi quá 38 ngày, tức CronJob tháng không chạy hoặc lỗi. Xem nhật ký job gần nhất của `xoay-dek-thang`; lỗi thường gặp là Secret mật khẩu đã xoay mà pod chưa đọc lại, token OpenBao hết hạn, hoặc ConfigMap tenant rỗng. Chạy tay: `kubectl -n <ns> create job xoay-tay --from=cronjob/xoay-dek-thang`. Job tháng chạy lại an toàn: DEK đã có thì không sinh thêm.

**VtMaHoaLaiDekDungGiuaChung.** Xem mục quay lui ở trên.

## Liên quan

- `openbao-van-hanh.md` (xoay KEK, chính sách Transit)
- `wiki/20-bao-mat-phia-may-chu.md` mục "Xoay vòng khoá dữ liệu (DL-14)"
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md` mục ghi chú DL-14
