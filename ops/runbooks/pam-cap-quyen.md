# Cấp quyền đặc quyền qua PAM và kiểm chứng "không có cửa sau"

> Nguồn: TL-04 mục 9.3 "RBAC (SEC-29)" và bảng vai trò, "DBA không đọc được dữ liệu C2 (SEC-58)", mục 9.7 bảng cấp độ 4 (SEC-42 PAM có ghi phiên, lưu 1 năm, báo cáo hằng tháng), "SOC và SIEM (SEC-43)", mục 4 bảng ngân sách kết nối ($C_{\text{admin}}$ = 10 mỗi cụm); INF-19, INF-13, INF-11; INF-27.
> Thành phần: chart `deploy/charts/pam-integration` (chính sách nhập `vt-pam-binding-co-thoi-han`, CronJob `pam-quet-rbac`, `pam-quet-csdl-<cụm>`, cảnh báo `vt-pam`, ConfigMap `pam-hop-dong`, Warpgate 0.30.0 trong namespace `pam` với ConfigMap `warpgate-nhap`, ServiceAccount `warpgate-<vai trò>`, CronJob `warpgate-lam-moi`, `warpgate-ghi-phien`, chính sách nhập `vt-pam-tai-khoan-warpgate`), bucket `pam-cell-<mã>` của chart `rgw-cell`, `files/openbao/cau-hinh-pam.sh`, vai trò RBAC của `deploy/charts/security-cell` (INF-19), runbook `an-ninh-pam-rbac.md`, `pam-pha-kinh.md`.

Sản phẩm PAM: Warpgate 0.30.0, chốt 23/10/2026 (wiki 14, mục INF-27, mục 8 và 9). Chart không cấp quyền thay PAM: nó ràng buộc hợp đồng mà mọi sản phẩm PAM phải theo, kiểm chứng định kỳ rằng không có đường nào khác vào quyền đặc quyền, và thu hồi dự phòng binding PAM đã quá hạn. Đổi sản phẩm chỉ đổi khối `pam` trong values và cấu hình của sản phẩm theo ConfigMap `pam-hop-dong`.

## Hợp đồng binding có thời hạn

PAM tạo binding bằng định danh broker (`pam.broker.subjects`, mặc định `oidc:service-account-pam-broker` là tài khoản dịch vụ của client `pam-broker` trong realm `platform-ops`). ClusterRole `vt-pam-broker` chỉ cho `bind` đúng ba vai trò `debug-app`, `operate-data`, `platform-admin` và tạo, xoá binding; broker không nắm quyền của các vai trò đó. Chính sách nhập từ chối mọi binding tới bốn vai trò `debug-app`, `operate-data`, `platform-admin`, `cluster-admin` không theo hợp đồng sau, kể cả khi người tạo là `system:masters`:

| Trường | Giá trị bắt buộc |
|---|---|
| Người tạo | định danh broker PAM |
| Kiểu và vùng | `debug-app`: `RoleBinding` trong `core`, `identity`, `platform`, `workers`, `analytics`; `operate-data`: `RoleBinding` trong `data-pg`, `data-kafka`, `data-search`; `platform-admin`: `ClusterRoleBinding`; `cluster-admin`: không bao giờ |
| Tên | `pam-<mã yêu cầu>` |
| Nhãn | `vt.io/cap-phat: pam` |
| `vt.io/pam-yeu-cau` | mã yêu cầu trên PAM |
| `vt.io/nguoi-yeu-cau` | tên người yêu cầu (không được nằm trong danh sách người duyệt) |
| `vt.io/nguoi-duyet` | người duyệt, cách nhau dấu phẩy, khác nhau; `platform-admin` ít nhất 2 |
| `vt.io/cap-luc`, `vt.io/het-han` | RFC 3339; `het-han − cap-luc` > 0 và ≤ 4 giờ; `cap-luc` không được sửa (gia hạn là yêu cầu mới) |
| `vt.io/pam-phien` (tuỳ chọn) | mã phiên ghi hình, để SOC đối chiếu |
| Chủ thể | đúng một `User` `oidc:<tên>`, hoặc đúng ServiceAccount `pam/warpgate-<vai trò của roleRef>` (mục tiêu kubectl của Warpgate); không nhóm, không ServiceAccount khác |

Kubernetes không có hàm thời gian hiện tại trong CEL, nên chính sách nhập chỉ kiểm độ dài thời hạn. Hết hạn thật do PAM xoá binding; CronJob `pam-quet-rbac` (mỗi 10 phút) báo `qua-han` và xoá binding PAM đã quá `vt.io/het-han` cộng 5 phút ân hạn. CronJob chỉ xoá binding mang nhãn PAM đã quá hạn, không bao giờ tạo binding.

## Cấp quyền Kubernetes

1. Người cần quyền mở yêu cầu trên PAM: lý do, cell, vai trò, namespace, thời lượng (≤ 4 giờ).
2. Người duyệt thuộc nhóm ở `pam.nhomDuyet` duyệt; `platform-admin` cần hai người khác nhau, không ai tự duyệt.
3. PAM tạo binding theo hợp đồng, rồi mở phiên `kubectl` qua proxy ghi phiên của PAM. `kubectl exec`, `port-forward` chỉ đi qua proxy; API server ghi `RequestResponse` cho `pods/exec`, `pods/portforward` (INF-19) để SOC đối chiếu với bản ghi phiên.
4. Hết hạn hoặc người dùng kết thúc sớm: PAM xoá binding và đóng phiên.

Với Warpgate (duyệt 23/10/2026): Warpgate nối API server bằng một token cho mỗi mục tiêu, không mạo danh từng người, nên mỗi vai trò PAM có một ServiceAccount `pam/warpgate-<vai trò>` và một mục tiêu `kubectl-<cell>-<vai trò>`:

1. Khi yêu cầu được duyệt, broker tạo binding theo hợp đồng trên với chủ thể `ServiceAccount pam/warpgate-<vai trò>` (chú thích `vt.io/nguoi-yeu-cau` là người thật), đồng thời cấp vai trò Warpgate `pam-kubectl-<vai trò>` cho người đó với `expires_at` bằng `vt.io/het-han` (`POST /@warpgate/admin/api/users/{id}/roles/{role_id}`).
2. Người dùng mở phiên `kubectl` qua cổng 8443 của Warpgate; mọi lệnh ghi vào bản ghi phiên dưới tên người dùng Warpgate. Audit log của API server ghi `system:serviceaccount:pam:warpgate-<vai trò>`; đối chiếu người thật bằng chú thích binding và bản ghi phiên.
3. Hết hạn: broker xoá binding và vai trò Warpgate. Token của ServiceAccount vẫn còn tới 1 giờ nhưng không còn quyền gì khi binding đã xoá.

Chính sách `vt-pam-tai-khoan-warpgate` từ chối mọi binding khác tới ba ServiceAccount này (kể cả `view`, kể cả `system:masters`); `pam-quet-rbac` báo `cua-sau` nếu có binding không nhãn PAM tới chúng. Hai ServiceAccount có vai trò đặc quyền cùng lúc nghĩa là hai người dùng chung một token: Warpgate vẫn tách phiên theo người, nhưng quyền là hợp của các binding đang mở cho vai trò đó (suy luận: broker nên xếp hàng yêu cầu `platform-admin`).

Điều kiện để "mọi đường vào đặc quyền đều qua PAM" đúng trên cụm thật: điểm vào API server cho người chỉ nhận kết nối từ dải proxy PAM (cùng Argo CD và node); nếu không, người được cấp binding có thể gọi API server bằng token OIDC của mình mà không qua proxy ghi phiên. Việc giới hạn dải nguồn ở cân bằng tải API server thuộc cấu hình mạng của cụm (INF-08), chưa có trong INF-27.

## Cấp quyền CSDL cho DBA

1. DBA mở yêu cầu `dba_ops` trên PAM (một người duyệt, nhóm `platform-ops-truong-dba`).
2. Broker PAM đọc `database/creds/<cụm>-dba-ops` từ OpenBao (auth cert `cert-pam`, token 15 phút chỉ dùng được từ dải proxy PAM) và nhận người dùng động `IN ROLE dba_ops`, sống 4 giờ, tối đa 2 kết nối.
3. PAM mở phiên `psql` qua proxy ghi phiên tới `<cụm>-rw.<namespace>.svc:5432` (NetworkPolicy `pam-proxy-to-pg` chỉ mở cho `pam.proxyCIDRs` và pod Warpgate). Với Warpgate 0.30 (duyệt 23/10/2026): mục tiêu `psql-<cụm>` dùng một người dùng động `dba_ops` dùng chung do CronJob `warpgate-lam-moi` xin mỗi khi lease còn dưới 90 phút (mật khẩu ở `kv/warpgate/psql-<cụm>`, username đổi qua API quản trị Warpgate); người thật thấy ở bản ghi phiên Warpgate. Lúc đổi người dùng có vài giây username và mật khẩu lệch nhau: phiên mở đúng lúc đó báo sai mật khẩu, mở lại là được. Người dùng cũ không thu hồi sớm, phiên đang mở chạy tới hết lease.
4. Hết phiên: broker thu hồi lease (`sys/leases/revoke`); OpenBao ngắt phiên còn mở rồi xoá người dùng. `dba_ops` không có `SELECT` trên bảng nghiệp vụ; đọc dữ liệu nghiệp vụ chỉ theo `pam-pha-kinh.md`.

Ngân sách kết nối quản trị: 10 mỗi cụm (TL-04 mục 4). Giới hạn cứng ở hai lớp: mỗi người dùng động tối đa 2 kết nối (`CONNECTION LIMIT`), và cấu hình phiên CSDL đồng thời của PAM mỗi cụm không quá 5 người (suy luận: 5 × 2 = 10). Cảnh báo `PamKetNoiQuanTriVuotNganSach` khi tổng kết nối của thành viên `dba_ops` và vai trò phá kính vượt 10 trong 5 phút. PostgreSQL 16 không có giới hạn kết nối theo nhóm vai trò, nên tổng 10 là kiểm chứng, không phải chặn cứng.

## Bí mật mục tiêu và bản ghi phiên của Warpgate

- CronJob `warpgate-lam-moi` (`pam`, mỗi 20 phút): đăng nhập OpenBao vai trò `warpgate-lam-moi` (token chiếu audience `openbao`), xin token TokenRequest 1 giờ cho `warpgate-<vai trò>`, ghi `kv/warpgate/kubectl-<cell>-<vai trò>`; xin người dùng psql mới khi cần. Chỉ số `vt_pam_warpgate_bi_mat_het_han_timestamp_seconds{kv}`, `vt_pam_warpgate_lam_moi_loi`, `..._last_success_timestamp_seconds`. Nhật ký ECS không chứa token hay mật khẩu.
- CronJob `warpgate-ghi-phien` (`pam`, mỗi 15 phút): gắn PVC `du-lieu-warpgate-0` chỉ đọc (cùng node với `warpgate-0`), chép tệp trong `/data/recordings` đã không đổi 10 phút sang `s3://pam-cell-<mã>/ghi-phien/<cell>/`. Bucket bật versioning và Object Lock COMPLIANCE 365 ngày (không ai xoá hay rút ngắn được, kể cả quản trị RGW); người dùng S3 `pam-ghi-phien` chỉ có ghi và liệt kê. Tệp ghi tiếp sau khi đã chép (phiên dài) được chép lại thành phiên bản mới.
- Cảnh báo và xử lý:
  - `PamGhiPhienCu` (không có lần chép thành công quá 2 giờ) hoặc `PamGhiPhienVangMat`: xem nhật ký Job gần nhất (`kubectl -n pam logs job/<tên>`). `SignatureDoesNotMatch`, `AccessDenied`: kiểm Secret `rgw-pam-ghi-phien` (ExternalSecret của `rgw-cell`) và chính sách bucket; lỗi kết nối: kiểm `warpgate.saoChepGhiPhien.egressTo`, CA `vt-noi-bo-ca`; Pod `Pending`: node của `warpgate-0` hết tài nguyên. PVC gần đầy (50 Gi) khi chép ngưng lâu: không xoá bản ghi trên PVC trước khi đã có trên bucket.
  - `PamWarpgateBiMatSapHet` (bí mật còn dưới 15 phút) hoặc `PamWarpgateLamMoiLoi`: xem nhật ký Job; lỗi đăng nhập OpenBao kiểm vai trò `warpgate-lam-moi` (`cau-hinh-pam.sh`); 403 TokenRequest kiểm Role `warpgate-lam-moi`; lỗi API Warpgate kiểm Secret `warpgate-api` và mục tiêu `psql-<cụm>` đã nhập. Chạy tay: `kubectl -n pam create job lm --from=cronjob/warpgate-lam-moi`.
- Dọn bản ghi trên PVC: Warpgate tự xoá bản ghi theo cấu hình của nó; bản trên bucket là bản lưu 1 năm (SEC-42).

## OpenBao và HSM

Quản trị khoá (đổi chính sách, cấu hình và xoay khoá Transit) đăng nhập OpenBao bằng OIDC `platform-ops` vai trò `quan-tri-khoa` qua phiên web của PAM; token sống 1 giờ và chỉ dùng được từ dải proxy PAM (`token_bound_cidrs`). Chính sách `quan-tri-khoa` không có `decrypt`, `datakey`, `export`, `backup` và không sửa được chính sách của chính nó hay của PAM. Root token không dùng trong vận hành thường ngày; mở niêm phong thủ công và tạo root token theo `pam-pha-kinh.md`. Bảng điều khiển quản trị HSM chỉ mở được từ máy nhảy SSH của PAM (cấu hình mạng của HSM, ngoài cụm).

## Sự kiện PAM sang SIEM

PAM gửi syslog RFC 5424 qua TLS tới `otel-siem.observability.svc:6514` (bộ nhận `syslog/pam` của INF-19), dữ liệu có cấu trúc SD-ID `vtpam@32473` (32473 là số dành cho tài liệu theo RFC 5612; thay khi có số doanh nghiệp thật). Bộ xử lý `transform/ecs-pam` của `otel-siem` gắn `event.module: pam`, `event.dataset: pam.session`, giữ tham số SD dưới khoá `pam` và đặt trường ECS theo bảng dưới đây (đã làm ngày 23/10/2026, kiểm bằng `deploy/charts/otel-collector/tests/kiem-thu-siem.py`); thẻ `pha-kinh` theo `event_action` hoặc khi thông điệp nhắc phá kính. Bảng là hợp đồng với sản phẩm PAM.

Với Warpgate (sản phẩm đã chọn), sidecar `cau-noi` của pod Warpgate dựng dòng syslog từ sự kiện kiểm toán JSON của Warpgate. Ngoài các tham số trong bảng, sidecar thêm `warpgate_type` (loại gốc), `user_id`, `client_ip`, `pod`, `reason`, `outcome`. Cách gán loại sự kiện là suy luận:
- `TargetSessionStarted1`, `TargetSessionEnded1` → bắt đầu, kết thúc phiên; `url.full` là trang phiên trong giao diện quản trị.
- `AccessRoleGranted1`, `AdminRoleGranted1`, `TicketCreated1` → cấp quyền; `AccessRoleRevoked1`, `AdminRoleRevoked1`, `TicketDeleted1` → thu hồi.
- `KubernetesExecStarted1`, `KubernetesAttachStarted1`, `KubernetesPortForwardStarted1`, `KubernetesDebugContainerCreated1`, `KubernetesPodCreated1`, `WebApprovalBypassed1` → lệnh nhạy cảm.
- `KubernetesStreamRejected1` → từ chối.
- `UserAuthenticated1` → `pam.dang-nhap`; `UserAuthenticationFailed1` → `pam.dang-nhap-that-bai`.
- `UserCreated1`, `UserDeleted1`, `CredentialCreated1`, `CredentialDeleted1` → `pam.quan-tri`.
- Loại khác → `pam.khac`.

Warpgate không có bước yêu cầu và duyệt: hai bước đó do broker và chính sách nhập ở tầng Kubernetes làm. Broker cấp vai trò Warpgate cho người được duyệt bằng `POST /@warpgate/admin/api/users/{id}/roles/{role_id}` với `expires_at` bằng `vt.io/het-han`.

| Sự kiện | Tham số SD `event_action` | ECS |
|---|---|---|
| Mở yêu cầu | `pam.yeu-cau` | `event.category: [iam]`, `event.type: [creation]`, `user.name` ← `user`, `pam.request_id` |
| Duyệt, từ chối | `pam.duyet`, `pam.tu-choi` | `event.type: [allowed]` hoặc `[denied]`, `related.user` ← `approver` |
| Cấp quyền (binding hay thông tin động) | `pam.cap-quyen` | `event.type: [change]`, `orchestrator.namespace`, `user.target.name` ← `role`, `event.end` ← `expires` |
| Bắt đầu, kết thúc phiên | `pam.phien-bat-dau`, `pam.phien-ket-thuc` | `event.type: [start]` hoặc `[end]`, `session.id`, `destination.address` ← `target`, `url.full` ← `recording_uri` |
| Lệnh nhạy cảm | `pam.lenh-nhay-cam` | `event.kind: alert`, `process.command_line` ← `command` |
| Thu hồi | `pam.thu-hoi` | `event.type: [deletion]` |
| Phá kính | `pam.pha-kinh` | `vt.phat_hien: pha-kinh` (quy tắc SOC 2) |
| Đăng nhập PAM, thất bại | `pam.dang-nhap`, `pam.dang-nhap-that-bai` | `event.category: [authentication]`, `event.type: [info]`, `source.ip` ← `client_ip`, thất bại `event.outcome: failure` |
| Quản trị người dùng, thông tin đăng nhập trong PAM | `pam.quan-tri` | `event.category: [iam]`, `event.type: [admin]` |

Hai CronJob quét của chart ghi mỗi phát hiện một dòng JSON trên stdout có `event.dataset: security` (Fluent Bit đưa vào `sys.audit.v1`, `otel-siem` chuyển sang `sys.siem.v1`), hành động `pam.cua-sau-rbac`, `pam.sai-hop-dong-rbac`, `pam.qua-han-rbac`, `pam.thu-hoi`, `pam.<loại>-csdl`, `pam.vuot-ngan-sach-ket-noi`.

## Báo cáo phiên PAM hằng tháng (mẫu cho VH-07)

Bằng chứng của dòng "PAM có ghi phiên" ở bảng cấp độ 4 TL-04. Lập ngày làm việc đầu tháng, lấy từ SIEM (`event.module: pam`) và Prometheus; không chép tên tài khoản thật vào repo, bản có tên lưu ở kho bằng chứng ngoài repo.

```
BÁO CÁO PHIÊN TRUY CẬP ĐẶC QUYỀN THÁNG <MM/YYYY> – CELL <mã cell>
1. Tổng hợp
   - Số yêu cầu: <n> (duyệt <n>, từ chối <n>, hết hạn chưa duyệt <n>)
   - Theo vai trò: debug-app <n>, operate-data <n>, platform-admin <n>, dba_ops <n>, phá kính CSDL <n>, quản trị khoá <n>
   - Tổng thời lượng phiên: <giờ>; phiên dài nhất: <giờ> (≤ 4 giờ; phá kính ≤ 2 giờ)
   - Số phiên có bản ghi hình: <n>/<n> (yêu cầu 100%); bản ghi lưu Object Lock compliance đến <ngày + 365>
2. Phá kính: mỗi lần một dòng – mã yêu cầu, cụm, người yêu cầu, hai người duyệt (một từ khách hàng), thời điểm mở, đóng,
   bản ghi sổ cái, kết luận rà soát.
3. Kiểm chứng "không có cửa sau"
   - PamCuaSauRbac, PamCuaSauCsdl kích hoạt: <n> lần; nguyên nhân và xử lý từng lần
   - PamBindingQuaHan: <n>; số binding do CronJob thu hồi dự phòng (vt_pam_thu_hoi_lan_quet): <n>
   - Ngoại lệ CSDL còn hiệu lực (vt_pam_ngoai_le_csdl): vai trò, lý do, hạn
   - Lần vượt ngân sách kết nối quản trị (PamKetNoiQuanTriVuotNganSach): <n>
4. Lệnh nhạy cảm bị chặn hoặc cảnh báo: <n>, tóm tắt.
5. Đối chiếu SOC: số sự kiện pha-kinh, shell-container, doi-rbac không khớp phiên PAM nào: <n> (yêu cầu 0).
Người lập: <vai trò>      Người duyệt: <vai trò>      Ngày: <dd/mm/yyyy>
```

Truy vấn gợi ý: PromQL `max_over_time(vt_pam_cua_sau_rbac[30d])`, `max_over_time(vt_pam_pha_kinh_dang_mo[30d])`, `max_over_time(vt_pam_ket_noi_quan_tri{vai_tro="tong"}[30d])`; trên SIEM lọc `event.module: pam` theo `event.action`.

## Chuẩn bị khi triển khai

1. Chạy `deploy/openbao/cau-hinh.sh` (INF-13) rồi `files/openbao/cau-hinh-pam.sh` với `CELL`, `PG_CLUSTERS`, `PAM_CIDRS`, `PAM_BROKER_CA` (ConfigMap `pam-openbao` trong `secure-data` chứa bản của chart).
2. Chạy `files/sql/vai-tro-dba.sql` trên mỗi cụm (ConfigMap `pam-vai-tro-dba` trong namespace của cụm) theo `pam-pha-kinh.md` mục chuẩn bị.
3. Đưa CA của OpenBao (ConfigMap `openbao-ca`) và CA của từng cụm CNPG (Secret `<cụm>-ca`, khoá `ca.crt`) vào namespace `pam-kiem-soat` (trust-manager hoặc ExternalSecret); thiếu thì Pod quét CSDL không khởi động.
4. Tạo client `pam-broker` (service account) và nhóm người duyệt trong realm `platform-ops`; khai `pam.proxyCIDRs`.
5. Warpgate:
   - Tạo client `warpgate` (confidential, claim `groups`) trong realm `platform-ops`. Ghi client secret vào OpenBao để ESO dựng Secret `warpgate-oidc` (khoá `client-secret`) trong namespace `pam`.
   - Không ghi tay token kubectl hay mật khẩu psql: CronJob `warpgate-lam-moi` ghi vào `kv/warpgate/*`. Chỉ khoá SSH của mục tiêu `ssh-node-*` ghi tay vào `kv/warpgate/<mục tiêu>`.
   - Sau lần nhập đầu, tạo token API cho một người dùng quản trị Warpgate (trang hồ sơ, `/profile/api-tokens`), ghi vào OpenBao để ESO dựng Secret `warpgate-api` (khoá `token`) trong namespace `pam`; CronJob dùng token này để đổi username mục tiêu psql. Đổi token khi người quản trị đó nghỉ.
   - Chạy `cau-hinh-pam.sh` với `WARPGATE_PSQL` (cụm có mục tiêu psql, mặc định `pg-core`) để tạo chính sách và vai trò `warpgate-lam-moi`.
   - Bucket ghi phiên: cài `rgw-cell` (khoá `buckets.pam-ghi-phien`, người dùng `pam-ghi-phien`), chạy lại `khoi-tao-multisite.sh` để tạo người dùng S3; khai `warpgate.saoChepGhiPhien.egressTo` tới điểm cuối S3 của zone master; Secret CA `vt-noi-bo-ca` phải có trong namespace `pam`.
   - Khai `warpgate.nguonCIDRs` (máy nhảy, VPN của người vận hành) và `warpgate.dich.*`.
6. Cài chart qua Argo CD. Sau lần chạy đầu, nhập ConfigMap `warpgate-nhap` vào Warpgate qua API quản trị (`/@warpgate/admin/api`) bằng tài khoản quản trị nhóm `platform-ops-an-ninh`, theo thứ tự:
   - `vai-tro.json` → `POST /roles`;
   - `kho-bi-mat.json` → `POST /secret-backends`;
   - `muc-tieu.json` → `POST /targets` rồi `POST /targets/{id}/roles/{role_id}`.

   Kiểm chứng như mục dưới.

## Kiểm chứng

- Tĩnh: `deploy/charts/pam-integration/tests/chay-kiem-thu.sh` (lint, render, `kubeconform -strict`, Kyverno, promtool, shellcheck, unittest; phần SQL chạy thật trên PostgreSQL tạm khi máy có bản cài PostgreSQL ≥ 16).
- Chính sách nhập ngoài cụm: dựng `tests/vap-cel` (`go build`) rồi chạy bộ kiểm với `VAP_CEL=<tệp>`.
- Sau khi cài: `kubectl -n pam create job gp --from=cronjob/warpgate-ghi-phien`, nhật ký phải có `chép N, chờ ổn định M, lỗi 0`; thử xoá một đối tượng trên bucket bằng tài khoản quản trị phải bị từ chối (`AccessDenied` do Object Lock).
- Trên cụm: `deploy/environments/kind/kiem-thu-pam.sh` (binding tạo tay tới `platform-admin` bị từ chối; broker thiếu người duyệt, quá 4 giờ, tự duyệt bị từ chối; cấp `debug-app` 90 giây rồi tự thu hồi; binding tạo tay tới `edit` bị quét báo; vai trò CSDL thừa quyền bị quét báo).
- Thủ công: `kubectl create clusterrolebinding thu --clusterrole=platform-admin --user=oidc:x` phải bị từ chối với thông điệp "Chỉ hệ thống PAM được cấp quyền đặc quyền"; `kubectl -n pam-kiem-soat create job q --from=cronjob/pam-quet-rbac` rồi xem nhật ký, phải có dòng `pam.quet-rbac` với 0 phát hiện.

## Quay lui

- Chính sách nhập chặn nhầm (ví dụ nâng cấp cụm cần tạo lại binding hệ thống): đổi `admission.validationActions` sang `[Audit]` qua merge request, Argo CD đồng bộ; xử lý xong trả về `[Deny]`. Khẩn khi Argo CD không chạy: dùng `admin.conf` theo phá kính của `an-ninh-pam-rbac.md`, `kubectl delete validatingadmissionpolicybinding vt-pam-binding-co-thoi-han`, ghi biên bản; Argo CD tạo lại ở lần đồng bộ sau.
- CronJob thu hồi nhầm: tắt `quetRbac.thuHoiQuaHan` (chỉ còn báo động), rollback Application về phiên bản trước; binding bị xoá được PAM cấp lại theo yêu cầu mới.
- Quét CSDL báo nhầm một vai trò hợp lệ: thêm vào `quetCsdl.ngoaiLe` có `denNgay` và `lyDo`, merge request cần người phụ trách an ninh duyệt; cảnh báo `PamNgoaiLeCsdl` nhắc ngoại lệ còn hiệu lực.
- Gỡ toàn bộ: rollback hoặc xoá Application `pam-integration`; vai trò RBAC của `security-cell` và cấu hình OpenBao không bị ảnh hưởng (vai trò database của PAM xoá bằng `bao delete database/roles/<cụm>-dba-ops`, `<cụm>-pha-kinh-doc`).
