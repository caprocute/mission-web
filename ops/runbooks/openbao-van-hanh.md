# Sổ tay vận hành OpenBao của cell

> Nguồn: TL-04 mục 9.4 (SEC-10, SEC-11, SEC-12, SEC-59), mục 8.7 (sao lưu OpenBao, HSM), DR-12, DR-15; INF-13.
> Chart `deploy/charts/vt-platform/ha-tang/openbao`, cấu hình dạng mã `deploy/openbao/`.

Sổ tay này dành cho đội quản trị khoá và SRE trực cell. OpenBao chạy 5 node Raft trong namespace `secure-data` trên pool `security-zone`, tự mở khoá bằng HSM qua plugin PKCS#11. Mọi thay đổi cấu hình bên trong OpenBao (auth, chính sách, engine) đi qua `deploy/openbao/cau-hinh.sh` từ Git, không sửa tay bằng giao diện hay lệnh rời.

## Khởi tạo một cụm mới

Bước khởi tạo chỉ làm một lần cho mỗi cell và cần hai người của đội quản trị khoá có mặt.

1. Chuẩn bị Secret ngoài Git trong `secure-data`: `openbao-hsm-pin` (khoá `pin`) theo `openbao-hsm.md`; trong `cert-manager`: `vt-noi-bo-goc-ca` (CA trung gian khởi đầu, theo `openbao-pki.md`).
2. Cài chart (Argo CD, sóng đồng bộ sau cert-manager và ESO). Năm pod chạy nhưng chưa sẵn sàng vì chưa khởi tạo.
3. Khởi tạo trên `openbao-0`: `bao operator init -recovery-shares=5 -recovery-threshold=3`. Khoá phục hồi (recovery key) chia cho năm người giữ theo biên bản, không lưu ở đâu khác; root token chỉ dùng cho bước 4 rồi thu hồi bằng `bao token revoke -self`. Bốn node còn lại tự nối Raft qua `retry_join` và tự mở khoá.
4. Ghi mật khẩu khởi tạo của `bao_admin` cho từng cụm PostgreSQL vào `kv/data-pg/<cụm>/bao_admin` (trước khi cài `data-pg`), chép CA của cụm vào ConfigMap `openbao-pg-ca` (khoá `<cụm>.crt`, lấy từ Secret `<cụm>-ca` của CNPG), rồi chạy `CELL=<mã> deploy/openbao/cau-hinh.sh`. Lần đầu script sinh CSR của CA trung gian; ký theo `openbao-pki.md` và chạy lại với `PKI_SIGNED_CERT=<tệp>`.
5. Sau bước này mật khẩu `bao_admin` đã bị `rotate-root` thay, không ai biết; giá trị cũ trong KV có thể xoá.

Kiểm thử đầy đủ quy trình trên kind: `deploy/environments/kind/kiem-thu-bi-mat.sh`.

## Cảnh báo

**OpenBaoSealed** (observability-cell). Một node bị niêm phong nghĩa là nó không mở được khoá qua HSM. Xem nhật ký pod (`kubectl -n secure-data logs openbao-N`), tìm dòng của plugin `kms.openbao-plugin-kms-pkcs11`. Nguyên nhân thường gặp: mất đường mạng tới HSM (kiểm tra NIC riêng của node và `hsmCidrs`), PIN sai sau khi đổi, khe HSM đầy phiên. Plugin tự khởi động lại khi thư viện PKCS#11 treo, nên sau khi sửa nguyên nhân pod tự mở khoá; nếu không, xoá pod. Không bao giờ chuyển sang mở khoá bằng tay.

**OpenBaoMatLeader**. Không node nào hoạt động: ứng dụng chạy tiếp bằng DEK đã đệm 15 phút và Secret đã đồng bộ (DR-15). Kiểm tra `bao operator raft list-peers` trên từng pod; mất đa số (3/5) thì khôi phục từ snapshot Raft mới nhất theo DR-15.

**OpenBaoKiemToanLoi**. OpenBao từ chối mọi yêu cầu khi không ghi được nhật ký kiểm toán. Thường do PVC `audit-openbao-N` đầy: mở rộng PVC hoặc xoay tệp (Fluent Bit đã đẩy nội dung sang `sys.audit.v1`). Thiết bị kiểm toán khai trong cấu hình máy chủ nên không thể (và không được) tắt bằng API.

**VtKhoaQuaHan**, **VtXoayKhoaKhongChay**. CronJob `openbao-xoay-khoa` xoay KEK theo tenant và khoá `cosign-release` quá 365 ngày, rồi đẩy `vt_key_age_days`. Xem nhật ký Job gần nhất; lỗi đăng nhập thường do vai trò `xoay-khoa` hoặc chính sách chưa được `cau-hinh.sh` cập nhật. Chạy tay: `kubectl -n secure-data create job xoay-tay --from=cronjob/openbao-xoay-khoa`.

## Thao tác định kỳ

Nâng phiên bản OpenBao: StatefulSet dùng `OnDelete`. Xoá lần lượt từng pod standby, chờ pod mới mở khoá và vào Raft, cuối cùng `bao operator step-down` trên leader rồi xoá pod đó. Đổi ảnh luôn kèm đổi `seal.plugin.sha256` nếu plugin đổi.

Thêm schema vào cụm PostgreSQL đã khởi tạo: khối SQL initdb của `data-pg` chỉ chạy lúc dựng cụm, nên với schema mới cần cấp ADMIN OPTION cho `bao_admin` bằng siêu người dùng: `kubectl cnpg psql <cụm> -n data-pg -- -c "GRANT svc_<schema> TO bao_admin WITH ADMIN TRUE, INHERIT FALSE, SET FALSE;"`, rồi thêm `svc_<schema>` vào `PG_CORE_SVC_ROLES` và chạy lại `cau-hinh.sh`. Chỉ cấp ADMIN: `bao_admin` không được đọc bảng nghiệp vụ (SEC-58); quét `pam-quet-csdl` của pam-integration báo `thua-ke-dich-vu` nếu cấp kèm INHERIT hay SET.

Chuyển cụm dựng trước 23/10/2026 sang mẫu quyền mới (INF-27 sửa INF-11, INF-13): khối initdb cũ cấp `svc_<schema>` cho `bao_admin` với `INHERIT TRUE, SET TRUE` và đặt `createrole_self_grant`, nên `bao_admin` đọc được bảng nghiệp vụ. Chạy bằng siêu người dùng (`kubectl cnpg psql <cụm> -n data-pg`), trong một giao dịch:

```sql
BEGIN;
ALTER ROLE bao_admin RESET createrole_self_grant;
ALTER ROLE bao_admin NOINHERIT;
GRANT pg_signal_backend TO bao_admin WITH INHERIT TRUE;
-- lặp cho từng svc_<schema> của cụm:
GRANT svc_task TO bao_admin WITH ADMIN TRUE, INHERIT FALSE, SET FALSE;
COMMIT;
```

Sau đó chạy lại `cau-hinh.sh` để ghi câu tạo và câu thu hồi mới của `pg-core-mission` (ngắt phiên rồi `DROP ROLE`, không còn `REASSIGN OWNED`, `DROP OWNED`). Người dùng động đã cấp trước vẫn có thành viên `bao_admin` kiểu cũ (tự cấp lúc tạo, `INHERIT TRUE, SET TRUE`); chúng hết hạn trong tối đa 72 giờ, hoặc thu hồi ngay bằng `bao lease revoke -prefix database/creds/pg-core-mission` (pod tự xin người dùng mới). Kiểm: `SELECT r.rolname, m.admin_option, m.inherit_option, m.set_option FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid WHERE m.member = 'bao_admin'::regrole;` chỉ còn `pg_signal_backend` có `inherit_option = true`, mọi dòng khác `f`, `f`.

Lộ mật khẩu CSDL động (DR-12): `bao lease revoke -prefix database/creds/pg-core-mission` thu hồi toàn bộ người dùng động; pod tự xin người dùng mới.

Snapshot Raft hằng giờ: `bao operator raft snapshot save` (TL-04 mục 8.7); snapshot chỉ khôi phục được khi còn HSM chứa khoá mở tương ứng.
