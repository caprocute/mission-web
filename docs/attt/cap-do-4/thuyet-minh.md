> Nguồn: baseline NFR-01, NFR-08 và phân lớp dữ liệu C1..C3; TL-01 chương 1, 2; TL-04 mục 3.5 "Namespace theo miền và vùng bảo mật", mục 9.7; `deploy/charts/cell-base/values.yaml`. Ngày đánh giá: 23/10/2026.

# Thuyết minh đề xuất cấp độ 4

## 1. Mô tả hệ thống

Mission là nền tảng giao nhiệm vụ, quản lý mục tiêu và kết quả then chốt (OKR), chỉ số hiệu suất (KPI) và đánh giá, xếp loại cán bộ theo Quy định 366-QĐ/TW cùng các quy định đánh giá cán bộ của Nhà nước. Hệ thống được định cỡ cho 6,5 triệu tài khoản và 300.000 người dùng đồng thời (NFR-01). Người dùng gồm cán bộ, công chức, viên chức và đảng viên ở các cơ quan, đơn vị trên cả nước, cùng người quản trị của từng đơn vị khách thuê (tenant) và đội vận hành hạ tầng.

Về kiến trúc, hệ thống gồm một khối ứng dụng mô-đun hoá là mission-core (giao việc, OKR, minh chứng, đánh giá, cấu hình) và các dịch vụ chuyên biệt: định danh (Keycloak, SVC-01), tổ chức, luồng duyệt, ký số, sổ cái, tài liệu, minh chứng, tích hợp, phân tích, tìm kiếm, thông báo và phân hệ web. Hạ tầng chạy trên Kubernetes theo mô hình cell: mỗi cell phục vụ một nhóm tenant với cụm dữ liệu riêng, còn vùng điều phối toàn cục chỉ giữ thông tin định tuyến tenant. Mọi thay đổi cấu hình cụm đi qua Git và Argo CD (GitOps).

## 2. Phạm vi của hồ sơ

Hồ sơ áp dụng cho toàn bộ thành phần do dự án xây dựng và vận hành: ứng dụng web, ứng dụng di động, các dịch vụ máy chủ, cụm Kubernetes của cell và vùng điều phối toàn cục, các hệ dữ liệu (PostgreSQL, Kafka, Valkey, OpenSearch, ClickHouse, kho đối tượng Ceph), hạ tầng quản lý khoá (OpenBao và HSM) và chuỗi cung ứng phần mềm (Jenkins, Harbor, Argo CD).

Hồ sơ không bao gồm hạ tầng vật lý của trung tâm dữ liệu, dịch vụ chống DDoS lớp mạng của nhà cung cấp, dịch vụ SOC của đơn vị giám sát và các hệ thống bên ngoài mà Mission tích hợp (định danh điện tử, chữ ký số từ xa, trục liên thông). Các thành phần này có hồ sơ riêng của đơn vị cung cấp; hồ sơ này chỉ mô tả điểm giao tiếp và yêu cầu Mission đặt ra cho chúng.

## 3. Căn cứ phân loại cấp độ 4

Theo Nghị định 85/2016/NĐ-CP về bảo đảm an toàn hệ thống thông tin theo cấp độ và Thông tư 12/2022/TT-BTTTT hướng dẫn nghị định này, cấp độ của hệ thống được xác định theo loại thông tin xử lý, đối tượng phục vụ và mức độ ảnh hưởng khi bị phá hoại. Baseline của dự án xác định Mission thuộc cấp độ 4 (NFR-08) vì ba lý do sau.

Lý do thứ nhất là loại thông tin. Hệ thống thu thập và xử lý thông tin cá nhân của cán bộ, công chức, viên chức trên phạm vi cả nước, trong đó có dữ liệu cá nhân nhạy cảm theo Nghị định 13/2023/NĐ-CP, như kết quả đánh giá, xếp loại, kỷ luật và nhận xét của cấp trên. Baseline phân dữ liệu thành ba lớp. Lớp C1 là dữ liệu nội bộ thông thường. Lớp C2 là dữ liệu nhạy cảm, được mã hoá theo trường bằng khoá riêng của từng tenant. Lớp C3 là dữ liệu mật, chỉ xử lý theo luồng mã hoá đầu cuối và máy chủ không đọc được.

Lý do thứ hai là ảnh hưởng tới quyết định nhân sự. Kết quả đánh giá trên hệ thống là căn cứ cho quy hoạch, bổ nhiệm, khen thưởng và bố trí lại cán bộ. Nếu dữ liệu bị sửa trái phép, bị lộ hay hệ thống ngừng vào thời điểm chốt kỳ, thiệt hại ảnh hưởng tới quyền lợi của số đông cán bộ và tới công tác tổ chức của nhiều cơ quan. Vì vậy, hệ thống phải bảo đảm toàn vẹn và chống chối bỏ ở mức cao: mọi bước đánh giá có ký số, dấu thời gian và ghi vào sổ cái chuỗi băm (ADR-09).

Lý do thứ ba là quy mô và tính liên thông. Hệ thống phục vụ nhiều bộ, ngành, địa phương cùng lúc và liên thông với các hệ thống quốc gia về định danh và chữ ký số. Sự cố trên hệ thống vì thế có thể lan rộng ra ngoài một cơ quan.

Từ ba lý do trên, hồ sơ đề xuất cấp độ 4 và áp đủ yêu cầu của TCVN 11930:2017 cho cấp độ này. Ngoài ra, hệ thống còn áp tám yêu cầu bổ sung mà bản đánh giá kiến trúc nêu cho cấp độ 4: MFA bắt buộc, khoá theo khách hàng trong HSM, mTLS, PAM có ghi phiên, SOC 24/7, sao lưu 3-2-1 bất biến, SBOM và build có chữ ký. Phần mềm còn được kiểm theo OWASP ASVS 4.0.3: mức 2 cho toàn hệ thống và mức 3 cho xác thực, quản lý phiên và mật mã lưu trữ.

## 4. Vùng mạng

Mỗi cell chia namespace theo vùng bảo mật. Nhãn `vt.io/security-zone` là nguồn phân loại chung cho NetworkPolicy, chính sách Kyverno và Istio (TL-04 mục 3.5, `deploy/charts/cell-base/values.yaml`). Sơ đồ dưới đây chỉ thể hiện các vùng và chiều kết nối được phép; hồ sơ cố ý không ghi địa chỉ hay dải mạng thật.

```
          Internet / mạng cơ quan
                    │  (chống DDoS lớp 3-4 của nhà cung cấp)
         ┌──────────▼──────────┐
         │ Vùng biên (edge)    │  APISIX + WAF Coraza, giới hạn tốc độ, kiểm JWT
         └──────────┬──────────┘
                    │ mTLS (Istio ambient, STRICT)
         ┌──────────▼──────────┐       ┌───────────────────────┐
         │ Vùng ứng dụng (app) │──────►│ Vùng bảo mật (secure) │  sổ cái, ký số, OpenBao, HSM
         │ core, identity,     │       │ node riêng, đĩa LUKS  │
         │ platform, workers,  │       └───────────────────────┘
         │ analytics           │
         └──────────┬──────────┘
                    │ TLS verify-full, SCRAM, ACL
         ┌──────────▼──────────┐
         │ Vùng dữ liệu (data) │  PostgreSQL, Kafka, Valkey, OpenSearch
         └─────────────────────┘
         ┌─────────────────────┐       ┌───────────────────────┐
         │ Vùng hạ tầng (infra)│       │ Vùng ra (egress)      │  chỉ tới danh sách đích FQDN
         │ quan sát, nhật ký   │       │ qua egress gateway    │
         └─────────────────────┘       └───────────────────────┘
```

Mặc định, NetworkPolicy chặn mọi chiều trong mọi namespace, và mỗi luồng được phép đều khai báo tường minh (SEC-32). Lưu lượng ra ngoài chỉ đi qua namespace `egress` tới danh sách đích khai báo theo tên miền (SEC-36). Trong cụm, các vùng ứng dụng và bảo mật dùng mTLS STRICT. Kết nối tới vùng dữ liệu dùng TLS với kiểm chứng đầy đủ chứng thư (SEC-21, SEC-57). Node của vùng bảo mật là node duy nhất có đường mạng tới HSM.

## 5. Tổ chức bảo đảm an toàn

Hồ sơ này chỉ trình bày biện pháp kỹ thuật. Về tổ chức, các vai trò liên quan gồm đơn vị chủ quản hệ thống, bộ phận vận hành (trực vận hành theo VH-03), đội phát triển, đơn vị giám sát SOC (SEC-43) và kiểm thử viên độc lập (SEC-41). Quy chế, phân công trách nhiệm và kế hoạch ứng phó sự cố được lập trong hồ sơ tổ chức của đơn vị chủ quản. Hồ sơ kỹ thuật này cung cấp đầu vào cho các tài liệu đó qua runbook trong `ops/runbooks/` và danh sách thiếu hụt.

## Liên quan

- [Bảng ánh xạ yêu cầu](anh-xa-yeu-cau.md)
- [Danh sách thiếu hụt](thieu-hut.md)
- `wiki/19-ha-tang-production-va-trien-khai.md`, `wiki/20-bao-mat-phia-may-chu.md`
