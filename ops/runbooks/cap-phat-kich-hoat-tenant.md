# Sổ tay vận hành: Saga cấp phát và kích hoạt tenant (`tenant-registry`)

> **Mã truy vết:** DV-03-05 · QT-01 bước 4 và bước 9 · SCR-0102 · TL-01 "Tiếp nhận tenant" · TL-04 OPS-07 · BR-01-03, BR-01-04, BR-01-10 · MSG-01-05, MSG-01-06, MSG-01-07, MSG-01-12.
> **Dịch vụ:** SVC-03 `services-go/tenant-registry` (gói `internal/provision`), chart `deploy/charts/tenant-registry` (khối `provisioning`).

## 1. Saga làm gì

Sau khi quản trị nền tảng bấm "Tiếp tục" ở cuối bước 3 của wizard (`POST /api/v1/config/tenants/{id}/provision`), tenant chuyển `KHOI_TAO → DANG_CAP_PHAT` và job `PROVISION` chạy lần lượt các bước, mỗi bước thử tối đa 3 lần với thời gian chờ tăng dần (`PROVISION_BACKOFF`, nhân đôi mỗi lần):

| Thứ tự | Mã bước | Việc làm | Bù trừ |
|---|---|---|---|
| 1 | `TO_CHUC_DINH_DANH` | Tạo Organization trong Keycloak của cell (alias = mã tenant), đăng ký IdP `<mã>-<bí danh>` và gắn vào Organization, tạo tài khoản đầu mối quản trị | Xoá tài khoản (nếu saga tạo), xoá Organization, gỡ IdP saga đã đăng ký |
| 2 | `TEN_MIEN` | CNAME tên miền tenant tới điểm vào cell (PowerDNS), ghi `verified_at` | Xoá CNAME, xoá `verified_at` |
| 3 | `CHUNG_CHI_TLS` | Certificate cert-manager `tenant-<mã>-tls` trong namespace cổng vào, ghi `tls_cert_ref` | Xoá Certificate và Secret, xoá `tls_cert_ref` |
| 4 | `GOI_CHINH_SACH_GIAO_DIEN` | Gắn Policy Pack theo luồng (MOD-05 kiểm BR-01-03), gắn UI Pack | Gỡ UI Pack, gỡ Policy Pack |
| 5 | `LICH_NGHI` | Tạo lịch nghỉ mặc định của năm (MOD-08) | Xoá lịch mặc định |
| 6 | `HAN_MUC` | `registry.tenant_quota`: `API_RPS` = max(20, 2 mỗi 100 tài khoản), `ACCOUNTS` | Đóng hạn mức (`effective_to`) |
| 7 | `KIEM_THU_KHOI` | Kiểm lại Organization, CNAME, chứng chỉ, gói đã gắn | Không có |

Xong mọi bước: tenant sang `DANG_CAU_HINH`, phát `sys.tenant.provisioned.v1` và EVT-21 (thư cho đầu mối, MSG-01-06). Một bước lỗi hết số lần thử (hoặc lỗi không thử lại như BR-01-03): saga bù trừ ngược mọi bước đã làm, tenant về `KHOI_TAO`, job `THAT_BAI` mang thông điệp MSG-01-07 "Cấp phát lỗi ở bước {bước}: {lý do}. Hệ thống đã hoàn tác các bước trước.".

## 2. Kiểm tra tình trạng một lần cấp phát

```sql
-- Job PROVISION mới nhất của tenant: trạng thái, thông điệp, bước lỗi, số lần thử từng bước.
SELECT id, status, progress, message,
       params->>'phase' AS pha, params->>'failedStep' AS buoc_loi,
       jsonb_path_query_array(params, '$.steps[*] ? (@.status != "XONG").name') AS buoc_chua_xong
FROM registry.job
WHERE job_type = 'PROVISION' AND params->>'tenantId' = '<tenant_id>'
ORDER BY created_at DESC LIMIT 1;

-- Nhật ký trạng thái gần nhất.
SELECT from_status, to_status, reason, changed_at FROM registry.tenant_status_log
WHERE tenant_id = '<tenant_id>' ORDER BY changed_at DESC LIMIT 5;
```

`params.steps[].out` chỉ chứa id và tham chiếu (id Organization, id tài khoản, tên Certificate); không có khoá hay mật khẩu. Nhật ký ứng dụng ghi `step`, `job`, `tenant_id` và lỗi kỹ thuật ở mức `WARN` khi bắt đầu bù trừ.

## 3. Saga kẹt ở `DANG_CAP_PHAT`

Saga chạy trong tiến trình tenant-registry; khi pod khởi động lại, mọi job `PROVISION` còn `CHO` hoặc `DANG_CHAY` được chạy tiếp từ bước chưa xong (mỗi bước idempotent, khoá tư vấn bảo đảm chỉ một pod chạy một tenant).

1. Kiểm job theo mục 2. Nếu `status = DANG_CHAY` và `updated_at` cũ hơn `PROVISION_STEP_TIMEOUT` × 3: khởi động lại một pod tenant-registry (`kubectl rollout restart deploy/<release>-tenant-registry`) để saga chạy tiếp.
2. Nếu `phase = BU_TRU_LOI` (hoàn tác một bước không thành công, job `THAT_BAI` mã `CONFIG-500-PROVISION-COMPENSATION`): tenant vẫn ở `DANG_CAP_PHAT`. Dọn tay đúng bước ghi trong thông điệp theo bảng mục 1 (ví dụ xoá Organization theo `steps[0].out.orgId` bằng Admin API của Keycloak cell; xoá CNAME bằng `pdnsutil delete-rrset`), rồi chuyển job về bù trừ để saga làm nốt:

```sql
UPDATE registry.job SET status = 'DANG_CHAY', params = jsonb_set(params, '{phase}', '"BU_TRU"')
WHERE id = '<job_id>';
```

   sau đó khởi động lại một pod. Kiểm chứng: tenant về `KHOI_TAO`, job `THAT_BAI` với `phase = DA_BU_TRU`.

## 4. Chứng chỉ TLS chưa cấp

Thông điệp job "Cấp phát lỗi ở bước cấp chứng chỉ bảo mật cho tên miền: Chứng chỉ TLS chưa được cấp".

1. Xem Certificate: `kubectl -n <namespace cổng vào> describe certificate tenant-<mã>-tls` và `kubectl get certificaterequest,order,challenge -n <namespace>`.
2. Nguyên nhân thường gặp: CNAME chưa lan truyền (kiểm `dig +short <tên miền> CNAME` phải ra điểm vào cell), ClusterIssuer sai tên (`provisioning.certManager.issuer`), hạn mức của CA.
3. Sau khi khắc phục, chạy lại cấp phát từ wizard (tenant đã về `KHOI_TAO`, bản nháp còn nguyên). Có thể tăng `provisioning.certManager.wait` nếu CA chậm.
4. Tên miền riêng của khách hàng (ngoài vùng `PDNS_ZONE`): saga không tạo được CNAME, chỉ kiểm khách hàng đã trỏ đúng; đề nghị khách hàng tạo `CNAME <tên miền> → <điểm vào cell>` rồi chạy lại.

## 5. Kích hoạt bị chặn

`POST /api/v1/config/tenants/{id}/activate` (AAL2) chạy kiểm tra tổng thể; mục chưa đạt nằm ở `errors[].field = readinessChecks.<MÃ>`:

| Mã lỗi | Ý nghĩa | Xử lý |
|---|---|---|
| `CONFIG-422-HIGH-DISCREPANCY` (MSG-01-12) | Còn lệch mức Cao (BR-01-10) | Quản trị tenant đối soát ở SCR-0104 tới khi lệch mức Cao bằng 0 |
| `CONFIG-422-READINESS-FAILED`, `readinessChecks.IDP` | Chưa có IdP nào kiểm kết nối thành công | Quản trị tenant chạy "Kiểm tra kết nối IdP" ở bước 4 |
| `readinessChecks.ORG_TREE` | Chưa nhập cây tổ chức | Nhập cây ở SCR-0103 |
| `readinessChecks.DOMAIN`, `readinessChecks.TLS` | CNAME mất hoặc chứng chỉ hết hạn, chưa sẵn sàng | Kiểm như mục 4; tạo lại CNAME bằng `pdnsutil add-record` |
| `readinessChecks.POLICY_PACK_GOLDEN` | Gói chưa `DA_PHAT_HANH` hoặc chưa đạt 100% test case vàng | Chạy lại bộ ca vàng (QT-15), phát hành gói, gắn lại |
| `readinessChecks.CAPACITY` | Cell vượt 85% hoặc không hoạt động | Theo quy trình mở cell mới (TL-04) hoặc chuyển tenant (DV-03-07) |
| `CONFIG-422-RPO-EXCEEDED` | Bản sao dự phòng của cell trễ quá 15 phút | Kiểm `cnpg_pg_replication_lag` của cell, chờ bắt kịp rồi kích hoạt lại; trễ dưới ngưỡng chỉ ghi cảnh báo vào nhật ký trạng thái |
| `CONFIG-409-TENANT-STATE` | Tenant không ở `DANG_CAU_HINH`, `SAN_SANG` hoặc `TAM_NGUNG` | Kiểm trạng thái; đang cấp phát thì chờ saga xong |

Kiểm chứng sau kích hoạt: tenant `DANG_VAN_HANH`, nhật ký trạng thái có lý do kích hoạt, outbox có EVT-21 `TENANT_ACTIVATED`.

## 6. Quay lui

- Cấp phát thành công nhưng cần huỷ trước khi kích hoạt: chưa có thao tác tự động (`DANG_CAU_HINH` không quay về `KHOI_TAO` theo máy trạng thái TL-02a); dọn tay theo cột "Bù trừ" ở mục 1, ghi biên bản và báo người phụ trách để xử lý trạng thái.
- Kích hoạt nhầm: chuyển `DANG_VAN_HANH → TAM_NGUNG` theo quyết định của quản trị nền tảng (chặn đăng nhập người dùng, giữ dữ liệu).
