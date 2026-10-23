# Quyền vận hành Kubernetes, PAM có thời hạn và phá kính

> Nguồn: TL-04 mục 9.3 "RBAC (SEC-29)", "Nhật ký kiểm toán API (SEC-30)", mục 9.7 (SEC-42 PAM có ghi phiên); INF-19.
> Thành phần: `deploy/charts/security-cell/templates/rbac.yaml`, cờ OIDC của API server trong `deploy/clusters/cell-cluster/templates/cluster.yaml`.

Không ai có `cluster-admin` thường trực. Người vận hành đăng nhập `kubectl` bằng OIDC của Keycloak vận hành (realm `platform-ops`, MFA bắt buộc, WebAuthn với người vận hành); API server gắn tiền tố `oidc:` cho tên và nhóm, nên nhóm Keycloak `platform-ops-sre` thành `oidc:platform-ops-sre` trong RBAC.

## Bảng vai trò

| Vai trò | Phạm vi | Quyền | Cấp phát |
|---|---|---|---|
| `view-app` | `core`, `identity`, `platform`, `workers`, `analytics` | `get`, `list`, `watch`, không có Secret | Thường trực: `oidc:platform-ops-dev`, `oidc:platform-ops-sre` |
| `operate-app` | như trên | Thêm xoá pod, đổi số bản, khởi động lại cuốn chiếu | Thường trực: `oidc:platform-ops-sre-truc` |
| `debug-app` | như trên | Thêm `pods/exec`, `pods/portforward` | PAM, 4 giờ |
| `view-data` | `data-pg`, `data-kafka`, `data-search` | Đọc CNPG, Strimzi; không có Secret | Thường trực: `oidc:platform-ops-dba` |
| `operate-data` | như trên | Thao tác CR CNPG, Strimzi | PAM, 4 giờ, DBA |
| `platform-admin` | Toàn cụm | Toàn quyền | PAM, 4 giờ, hai người duyệt; phá kính |

Chart từ chối render nếu `rbac.bindings` khai binding thường trực cho `debug-app`, `operate-data` hoặc `platform-admin`. Vùng bảo mật (`secure`, `secure-data`) không có vai trò thường trực nào.

## Cấp quyền qua PAM

1. Người cần quyền mở yêu cầu trên PAM, ghi lý do, cell, namespace, vai trò.
2. Người duyệt chấp thuận (`platform-admin`: hai người duyệt khác nhau, không ai tự duyệt).
3. PAM tạo `RoleBinding` (hoặc `ClusterRoleBinding` với `platform-admin`) tên `pam-<mã yêu cầu>`, gắn nhãn `vt.io/cap-phat: pam` và chú thích `vt.io/het-han: <RFC 3339>` bằng 4 giờ sau, chủ thể là `oidc:<tên người dùng>`.
4. PAM ghi toàn bộ phiên (SEC-42, lưu 1 năm). Mọi `pods/exec`, `pods/portforward` và thay đổi RBAC được API server ghi mức `RequestResponse`; Falco báo shell trong container; SOC đối chiếu với phiên PAM (`an-ninh-soc-quy-tac.md`, quy tắc 3 và 5).
5. Hết 4 giờ PAM xoá binding. Kiểm tra hằng ngày: `kubectl get rolebindings,clusterrolebindings -A -l vt.io/cap-phat=pam` không được có binding nào quá chú thích `vt.io/het-han`; còn thì xoá ngay và báo SOC.

## Phá kính

Dùng khi PAM hoặc Keycloak vận hành không hoạt động và cụm cần can thiệp khẩn.

1. Hai người của đội SRE nền tảng có mặt; mở phong bì chứa chứng thư quản trị khẩn (kubeconfig `admin.conf` niêm phong, lưu ngoài cụm theo quy trình của đội quản trị khoá).
2. Báo SOC trước khi dùng; SOC ghi nhận sự kiện `pha-kinh` từ nhật ký kiểm toán API.
3. Chỉ làm đúng thao tác khẩn, ghi biên bản từng lệnh.
4. Sau sự cố: xoay chứng thư khẩn (`kubeadm certs renew admin.conf` trên master), niêm phong lại, rà soát toàn bộ bản ghi kiểm toán trong khoảng thời gian dùng.

## Kiểm chứng

- Tĩnh: `deploy/charts/security-cell/tests/chay-kiem-thu.sh` (unittest khẳng định `view-app` không có Secret, không có binding thường trực cho vai trò PAM, chủ thể có tiền tố `oidc:`).
- Trên cụm: `kubectl auth can-i get secrets -n core --as=u --as-group=oidc:platform-ops-dev` trả `no`; `kubectl auth can-i create pods/exec -n core --as=u --as-group=oidc:platform-ops-dev` trả `no`. Kịch bản đầy đủ ở `deploy/environments/kind/kiem-thu-an-ninh.sh` (chưa chạy trên kind trong INF-19).

## Quay lui

Vai trò và binding thường trực do Argo CD đồng bộ từ chart `security-cell`; quay về phiên bản trước bằng rollback Application. Không sửa tay RBAC trên cụm: Argo CD ghi đè và thay đổi tay là sự kiện `doi-rbac` cho SOC.
