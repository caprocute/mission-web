# Phá kính: đọc dữ liệu nghiệp vụ, quyền toàn cụm và quản trị khoá khẩn cấp

> Nguồn: TL-04 mục 9.3 "DBA không đọc được dữ liệu C2 (SEC-58)" (phá kính: yêu cầu trong PAM, hai người duyệt trong đó một từ khách hàng, vai trò đọc có thời hạn 2 giờ, ghi hình phiên, ghi sổ cái), bảng vai trò RBAC (`platform-admin`: PAM 4 giờ, hai người duyệt; phá kính), mục 9.7 (SEC-42, SEC-43 quy tắc "truy cập phá kính"); INF-13, INF-19, INF-27.
> Thành phần: `deploy/charts/pam-integration/files/sql/vai-tro-dba.sql` (vai trò `dba_ops`, `dba_pha_kinh_doc`, `pam_quet`), `files/openbao/cau-hinh-pam.sh` (vai trò database `<cụm>-pha-kinh-doc` 2 giờ), CronJob `pam-quet-csdl-<cụm>`, cảnh báo `PamPhaKinhDangMo`, `PamCuaSauCsdl`; `an-ninh-pam-rbac.md` (phá kính Kubernetes bằng `admin.conf`).

Phá kính là ngoại lệ có kiểm soát, không phải lối tắt. Mỗi lần phá kính là sự kiện `pha-kinh` cho SOC (quy tắc 2 của `an-ninh-soc-quy-tac.md`), được rà soát trong báo cáo tháng (`pam-cap-quyen.md`).

## Chuẩn bị (một lần mỗi cụm)

1. DBA mở yêu cầu `operate-data` và phiên quản trị siêu người dùng qua PAM (hai người duyệt), chạy `vai-tro-dba.sql` (ConfigMap `pam-vai-tro-dba` trong `data-pg` hoặc `secure-data`): `psql -v ON_ERROR_STOP=1 -f vai-tro-dba.sql`. Tệp chạy lại được; chạy lại sau mỗi lần thêm schema để `dba_pha_kinh_doc` nhận vai trò `ro_<schema>` mới.
2. Kiểm: `SELECT rolname, rolcanlogin FROM pg_roles WHERE rolname IN ('dba_ops','dba_pha_kinh_doc','pam_quet')` có ba dòng, chỉ `pam_quet` đăng nhập được; `bao_admin` có ba vai trò đó chỉ với ADMIN (`inherit_option = false`, `set_option = false` trong `pg_auth_members`).
3. Quản trị khoá chạy `cau-hinh-pam.sh`; kiểm `bao read database/roles/<cụm>-pha-kinh-doc` có `default_ttl` và `max_ttl` 2 giờ, `bao policy read pam-broker` chỉ có đường `database/creds/<cụm>-*` và `sys/leases/*`.

## Phá kính CSDL: đọc dữ liệu nghiệp vụ 2 giờ

Khi nào: sự cố cần đọc bảng nghiệp vụ mà không xử lý được bằng thống kê, danh mục hệ thống hay nhật ký.

1. DBA mở yêu cầu phá kính trên PAM: mã sự cố, cụm, schema, câu hỏi cần trả lời, thời lượng (≤ 2 giờ).
2. Hai người duyệt khác nhau, khác người yêu cầu: một người nhóm an ninh vận hành (`platform-ops-an-ninh`) và một người phía khách hàng (`khach-hang-nguoi-duyet`, danh sách do khách hàng cấp). PAM là nơi duy nhất thực thi điều kiện hai người duyệt: OpenBao bản mã nguồn mở không có control groups (tính năng duyệt nhiều người là của Vault Enterprise), nên OpenBao chỉ bảo đảm rằng riêng định danh broker PAM đọc được `database/creds/<cụm>-pha-kinh-doc` và mọi lần đọc có trong nhật ký kiểm toán.
3. Broker PAM đọc `database/creds/<cụm>-pha-kinh-doc` và mở phiên `psql` có ghi hình. Người dùng động là thành viên `dba_pha_kinh_doc`, tức các vai trò `ro_<schema>`: chỉ đọc, vẫn chịu RLS theo tenant, và cột C2 vẫn là bản mã vì DEK chỉ mở được bằng tài khoản dịch vụ của ứng dụng (SEC-58). `VALID UNTIL` bằng thời điểm hết lease.
4. Ghi một bản ghi vào sổ cái cho lần phá kính (mã yêu cầu, hai người duyệt, cụm, thời điểm). Đường ghi sổ cái từ sự kiện PAM chưa được nối (wiki 14, INF-27); trước khi có, người duyệt phía an ninh lập biên bản và SOC lưu sự kiện `pha-kinh`.
5. Hết 2 giờ hoặc DBA đóng phiên: broker thu hồi lease; câu thu hồi của OpenBao ngắt phiên còn mở (`pg_terminate_backend`) rồi xoá người dùng. `VALID UNTIL` một mình chỉ chặn đăng nhập mới, không cắt phiên đang chạy, nên thu hồi lease là bắt buộc.

Giám sát trong lúc mở: `PamPhaKinhDangMo` (cảnh báo SOC) bật khi có người dùng phá kính hiệu lực; `PamCuaSauCsdl` loại `pha-kinh-qua-han` bật khi có thành viên `dba_pha_kinh_doc` không có `VALID UNTIL` hoặc hạn vượt 2 giờ 10 phút (cấp tay ngoài OpenBao).

## Phá kính Kubernetes

- Có PAM: `platform-admin` qua PAM, hai người duyệt, ≤ 4 giờ (`pam-cap-quyen.md`). Chính sách nhập từ chối `platform-admin` thiếu người duyệt thứ hai.
- PAM hoặc Keycloak vận hành không hoạt động: dùng `admin.conf` niêm phong theo `an-ninh-pam-rbac.md` mục "Phá kính". Không tạo binding tới `platform-admin` hay `cluster-admin` bằng `admin.conf`: chính sách nhập từ chối mọi binding không do broker PAM tạo, kể cả của `system:masters`; `admin.conf` đã đủ quyền cho thao tác khẩn.

## Phá kính OpenBao và HSM

- Mở niêm phong thủ công (khi auto-unseal bằng HSM hỏng): người giữ mảnh khoá vào máy nhảy qua phiên SSH có ghi hình của PAM, mỗi người nhập mảnh của mình (`bao operator unseal`); không ai nhập hộ.
- Tạo root token chỉ khi không còn cách nào khác (mất toàn bộ chính sách quản trị): `bao operator generate-root` cần đủ ngưỡng mảnh khoá, làm trong phiên PAM có ít nhất hai người giữ mảnh, báo SOC trước. Dùng xong: `bao token revoke -self`, ghi biên bản từng lệnh. Root token không bao giờ lưu lại.
- Quản trị khoá HSM (đổi PIN, sao lưu khoá gốc) qua bảng điều khiển HSM mở từ máy nhảy SSH của PAM; thẻ M trong N do khách hàng giữ (SEC-11).

## Kiểm chứng

- Tĩnh và SQL: `deploy/charts/pam-integration/tests/chay-kiem-thu.sh`. Ca `SqlThat` chạy thật trên PostgreSQL tạm: người dùng động `dba_ops` bị từ chối `SELECT` bảng nghiệp vụ nhưng có `pg_monitor`; người dùng phá kính đọc được; thành viên phá kính cấp tay không có hạn bị quét báo `pha-kinh-qua-han`; câu thu hồi của OpenBao chạy bằng `bao_admin` (không phải siêu người dùng) ngắt phiên đang mở và xoá vai trò.
- Sau mỗi lần phá kính: chạy tay `kubectl -n pam-kiem-soat create job pk-<mã> --from=cronjob/pam-quet-csdl-<cụm>`; nhật ký không còn dòng `pam.pha-kinh-dang-mo-csdl` của người dùng đã đóng, `vt_pam_pha_kinh_dang_mo` về 0.
- Đối chiếu SOC: số sự kiện `pha-kinh` trong SIEM bằng số yêu cầu phá kính đã duyệt trên PAM trong tháng.

## Quay lui

- Lỡ cấp phá kính ngoài OpenBao (`GRANT dba_pha_kinh_doc TO ...` tay): `REVOKE dba_pha_kinh_doc FROM <vai trò>`, ngắt phiên `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = '<vai trò>'`, báo SOC; cảnh báo `PamCuaSauCsdl` tự tắt ở lần quét sau.
- Lease không thu hồi được (lỗi kết nối): `bao lease revoke -force -prefix database/creds/<cụm>-pha-kinh-doc` rồi xoá tay người dùng còn sót bằng phiên `operate-data` qua PAM.
- Gỡ vai trò của INF-27: `DROP ROLE dba_pha_kinh_doc; DROP ROLE dba_ops; DROP ROLE pam_quet;` sau khi xoá vai trò database tương ứng trong OpenBao; không ảnh hưởng vai trò `svc_`, `ro_`, `mig_`, `cdc_` của INF-11.
