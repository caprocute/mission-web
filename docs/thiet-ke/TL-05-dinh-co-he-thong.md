---
title: "Định cỡ hệ thống theo các quy mô triển khai"
subtitle: "Nền tảng đánh giá cán bộ theo OKR và KPI · Mã tài liệu TL-05 · Phiên bản 1.0 · 10/2026"
toc-title: "Mục lục"
---

# Thông tin tài liệu {-}

## Kiểm soát phiên bản {-}

| Phiên bản | Ngày | Người thực hiện | Nội dung thay đổi |
|---|---|---|---|
| 1.0 | 19/10/2026 | Nhóm kiến trúc hạ tầng và vận hành | Bản đầu tiên: mô hình tải, bốn hồ sơ năng lực cell, năm bậc quy mô triển khai, ngưỡng nâng bậc, kế hoạch kiểm chứng |

## Tài liệu liên quan {-}

| Mã | Tên tài liệu | Quan hệ với TL-05 |
|---|---|---|
| TL-01 | Thiết kế kiến trúc chi tiết | Cung cấp danh mục thành phần SVC, MOD, các quyết định ADR, ngưỡng tách cell 1,5 triệu tài khoản hoặc 75.000 CCU |
| TL-02 | Đặc tả quy trình nghiệp vụ và giao diện | Cung cấp quy trình QT và màn hình SCR, là cơ sở của cơ cấu lưu lượng theo service |
| TL-03 | Thiết kế cơ sở dữ liệu và đồng bộ dữ liệu | Cung cấp schema, bảng, phân vùng, cơ chế outbox và CDC, là cơ sở ước tính dung lượng và lưu lượng sự kiện |
| TL-04 | Triển khai, hiệu năng, dự phòng và ATTT | Cung cấp cấu trúc cụm, node pool, cấu hình service cho cell hạng L, kịch bản PERF; TL-05 mở rộng chương 4 của TL-04 thành định cỡ cho mọi quy mô |
| TL-05 | Định cỡ hệ thống theo các quy mô triển khai | Tài liệu này |
| Đầu vào 1 | Đánh giá kiến trúc công nghệ, 10/2026 (110 tiêu chí, 64 vấn đề, lộ trình đợt 0a–3) | Tiêu chí C5 (co giãn theo SLO và giới hạn CSDL), F01 (bùng nổ kết nối), lộ trình đợt 1 đến đợt 3 |
| Đầu vào 2 | Báo cáo Ban Giám đốc "Nền tảng đánh giá cán bộ theo OKR và KPI", 10/2026 | Ba mô hình triển khai, kiến trúc cell, mục tiêu quy mô quốc gia |

# Giới thiệu

## Mục đích của tài liệu

Tài liệu này trả lời một câu hỏi mà chủ đầu tư, đội hạ tầng và bộ phận mua sắm đều cần câu trả lời bằng con số: để vận hành nền tảng đánh giá cán bộ theo OKR và KPI ở một quy mô cụ thể thì cần bao nhiêu máy chủ, bao nhiêu vCPU, bao nhiêu bộ nhớ, bao nhiêu dung lượng lưu trữ và băng thông, và khi quy mô tăng lên thì phải bổ sung gì, vào lúc nào. Chương 4 của TL-04 đã định cỡ cho đích cuối cùng là 6,5 triệu tài khoản và 325.000 người dùng đồng thời trên tám cell, lấy cell lớn nhất CELL-HCM làm chuẩn. Tuy vậy, nền tảng không ra đời ngay ở quy mô quốc gia. Theo lộ trình của bản đánh giá kiến trúc và TL-04 chương 12, hệ thống bắt đầu bằng một cell thí điểm, sau đó là cell nhóm tỉnh đầu tiên CELL-G4 ở đợt 1, mở rộng ra một nhóm cell ở đầu đợt 3 và chỉ đạt quy mô toàn quốc ở cuối lộ trình. Bên cạnh mô hình SaaS còn có các bản cài đám mây riêng cho một tỉnh hoặc một bộ và bản cài cách ly cho lực lượng vũ trang, Công an, mỗi bản là một cell đơn có quy mô rất khác nhau.

Vì vậy TL-05 định cỡ theo hai chiều. Chiều thứ nhất là **hồ sơ năng lực của một cell** (`sizing: compact`, `S`, `M`, `L`), tức là cấu hình chuẩn của một bộ triển khai đầy đủ ở bốn cỡ, dùng chung cho cả ba mô hình triển khai. Chiều thứ hai là **bậc quy mô triển khai** (QM-1 đến QM-5), tức là tổ hợp các cell, vùng điều phối toàn cục, cụm quản trị, kho đối tượng và trung tâm dữ liệu dự phòng ở từng giai đoạn từ thí điểm tới toàn quốc. Hai chiều này tách biệt có chủ ý: hồ sơ cell là đơn vị lặp lại, có kiểm thử và có tệp values trong kho GitOps, còn bậc quy mô là kế hoạch đầu tư, quyết định số cell và thời điểm mua sắm.

Tài liệu được viết ở mức đủ để lập dự toán phần cứng và lập kế hoạch năng lực. Mỗi con số đi kèm cách tính và được phân loại rõ là số baseline, giả định hay ước tính. TL-05 không nêu đơn giá hay tổng mức đầu tư bằng tiền, vì giá máy chủ, giá máy ảo trên Viettel Cloud và chính sách chiết khấu thay đổi theo từng thời điểm mua sắm; thay vào đó, chương 8 cung cấp bảng khối lượng theo loại máy chủ chuẩn để bộ phận dự toán nhân với đơn giá hiện hành.

## Phạm vi

TL-05 bao trùm năng lực tính toán (vCPU, bộ nhớ, GPU), lưu trữ khối cục bộ NVMe cho các hệ dữ liệu, kho đối tượng Ceph cho minh chứng và sao lưu, năng lực mạng Internet và kênh liên trung tâm dữ liệu, cùng năng lực của ngăn xếp quan sát (log, trace, metric). Tài liệu định cỡ cho môi trường sản xuất ở cả TTDL chính Hoà Lạc và TTDL dự phòng phía Nam, và đưa ra cỡ khuyến nghị cho các môi trường phi sản xuất cần thiết (staging, cell thử tải `CELL-PERF`, cụm sinh tải).

Các nội dung nằm ngoài phạm vi gồm: hệ thống cũ đang vận hành trên Oracle trong thời gian chuyển đổi theo mẫu Strangler Fig (được định cỡ theo hiện trạng, không theo kiến trúc mới); hạ tầng của SOC và SIEM tập trung (chỉ tính lưu lượng gửi sang); thiết bị mạng lõi của TTDL, tường lửa vật lý và thiết bị HSM (chỉ nêu số lượng tối thiểu theo cell); chi phí bản quyền, vì toàn bộ thành phần đã chọn trong TL-01 và TL-04 dùng giấy phép mã nguồn mở Apache 2.0, MPL hoặc BSD, trừ các công cụ AGPL chỉ dùng nội bộ.

## Cách đọc tài liệu

Chương 2 trình bày phương pháp định cỡ và toàn bộ giả định đầu vào, mỗi giả định có mã GD-xx để các chương sau và các tài liệu khác tham chiếu. Chương 3 định nghĩa năm bậc quy mô và bốn hồ sơ cell, đồng thời gắn mỗi bậc với một đợt của lộ trình triển khai. Chương 4 định cỡ tầng ứng dụng của từng hồ sơ cell (số pod và số node), chương 5 định cỡ tầng dữ liệu (PostgreSQL, Kafka, Valkey, OpenSearch, ClickHouse, GPU), chương 6 định cỡ lưu trữ đối tượng, sao lưu, quan sát và mạng. Chương 7 định cỡ các thành phần dùng chung giữa các cell và môi trường phi sản xuất. Chương 8 tổng hợp tài nguyên theo từng bậc quy mô, gồm bảng khối lượng máy chủ để lập dự toán. Chương 9 bàn về bản cài đám mây riêng và bản cài cách ly. Chương 10 phân tích độ nhạy của kết quả theo các giả định quan trọng. Chương 11 định nghĩa các ngưỡng nâng bậc NG-xx và quy trình nâng bậc. Chương 12 mô tả cách kiểm chứng và hiệu chuẩn bảng định cỡ bằng các kịch bản PERF của TL-04. Phụ lục A là mẫu tệp values cho bốn hồ sơ cell; phụ lục B là ma trận truy vết.

Người đọc chỉ cần con số tổng để lập kế hoạch đầu tư có thể đọc chương 3 rồi chuyển thẳng tới chương 8. Người vận hành cần cấu hình chi tiết của một cell nên đọc chương 4, 5 và phụ lục A. Người thẩm định phương pháp nên đọc chương 2 và chương 10, vì độ tin cậy của mọi con số trong tài liệu phụ thuộc vào các giả định ở đó.

## Mã do TL-05 cấp

Để tham chiếu chéo được với TL-01 đến TL-04, TL-05 không đặt lại tên bất kỳ thành phần, mã service, tên cell hay tên cụm CSDL nào đã có trong baseline và TL-04. Tài liệu chỉ cấp thêm ba dải mã mới:

| Dải mã | Ý nghĩa | Danh mục |
|---|---|---|
| GD-01 đến GD-40 | Giả định định cỡ (tham số đầu vào chưa đo được) | Mục 2.3, dùng GD-01 đến GD-26 |
| QM-1 đến QM-9 | Bậc quy mô triển khai | Mục 3.1, dùng QM-1 đến QM-5 |
| NG-01 đến NG-40 | Ngưỡng nâng bậc và tín hiệu mở rộng năng lực | Mục 11.2, dùng NG-01 đến NG-14 |

Tên hồ sơ năng lực `compact`, `S`, `M`, `L` giữ đúng như khoá `sizing` trong ba tệp values của TL-04 (mục 2.1). Hạng cell L, M, S trong bảng phân hạng của TL-04 (mục 2.6) trùng với hồ sơ cùng tên.

# Phương pháp định cỡ và mô hình tải

## Nguyên tắc chung

Định cỡ trong TL-05 dựa trên bốn nguyên tắc. Nguyên tắc thứ nhất là **một mô hình tải cho mọi quy mô**: cùng một bộ công thức và giả định được áp dụng cho cell 50.000 tài khoản và cell 1,2 triệu tài khoản, chỉ thay số tài khoản đầu vào. Nhờ vậy, khi một giả định được hiệu chỉnh bằng số đo (ví dụ thông lượng một pod mission-core sau PERF-02), mọi bảng của mọi hồ sơ được tính lại nhất quán, không có bảng nào "đứng ngoài" mô hình. Bảng tính gốc được lưu trong kho GitOps cùng tệp values, để thay đổi giả định đi qua cùng quy trình duyệt như thay đổi cấu hình.

Nguyên tắc thứ hai là **định cỡ theo đỉnh của chính cell, không trông vào cell khác**. Giữa các cell không chia sẻ tài nguyên tính toán hay dữ liệu (TL-01, ADR-07), nên mỗi cell phải chịu được ngày hạn nộp của chính nó. Hệ quả là tổng năng lực của các cell lớn hơn tải đỉnh toàn quốc, vì không phải mọi cell cùng đạt đỉnh vào một giờ. Phần dư này là chi phí của khả năng cô lập sự cố, và được bù lại một phần bằng co giãn node ở lớp ứng dụng: node pool ứng dụng chỉ đạt kích thước đỉnh trong vài ngày cuối kỳ.

Nguyên tắc thứ ba là **tách tầng co giãn được khỏi tầng không co giãn được**. Lớp ứng dụng chạy trên máy ảo của Viettel Cloud và co giãn tự động bằng HPA, KEDA và Cluster Autoscaler (TL-04 chương 6), nên được định cỡ thành hai mức "thường" và "đỉnh". Lớp dữ liệu chạy trên máy chủ vật lý có NVMe cục bộ và không co giãn tự động, nên được định cỡ một mức duy nhất theo đỉnh cộng dư địa tăng trưởng ba năm; mở rộng lớp dữ liệu là một quyết định năng lực có kế hoạch, kích hoạt bởi các ngưỡng NG ở chương 11.

Nguyên tắc thứ tư là **mọi con số phải có nguồn gốc**. Mỗi bảng ghi rõ cột nào là số baseline (đã được bốn tài liệu thống nhất tại baseline mục 3), cột nào là giả định GD-xx và cột nào là ước tính tính ra. Không có số đo nào trong TL-05, vì nền tảng mới chưa chạy; năng lực đo được của hệ hiện tại (50 phiên đồng thời theo bản đánh giá) không dùng được để ngoại suy cho kiến trúc mới. Số đo sẽ thay dần các giả định qua chương trình kiểm chứng ở chương 12.

## Chuỗi tính từ tài khoản tới máy chủ

![Hình 2.1. Chuỗi tính định cỡ: từ số tài khoản qua CCU, lưu lượng, số pod, số node tới tổng hợp theo hồ sơ cell và bậc quy mô](dc-phuong-phap.png){width=12cm}

Hình 2.1 cho thấy ba nhánh tính song song xuất phát từ số tài khoản của một cell. Nhánh bên trái là nhánh tính toán: số tài khoản quy ra số người dùng đồng thời (CCU) theo tỷ lệ GD-02, CCU quy ra lưu lượng request mỗi giây theo định luật Little với thời gian suy nghĩ GD-03, lưu lượng được nhân hệ số đột biến của ngày hạn nộp, phân bổ cho từng service theo cơ cấu GD-07 và chia cho thông lượng một pod để ra số pod. Số pod nhân với yêu cầu tài nguyên của pod (TL-04 mục 5.2) rồi chia cho phần tài nguyên khả dụng của một node, có hệ số xếp lịch, để ra số node của từng node pool.

Nhánh ở giữa là nhánh dữ liệu giao dịch. Tỷ lệ ghi GD-05 cho số giao dịch ghi mỗi giây, từ đó suy ra số sự kiện Kafka (GD-06), số bản ghi CDC cho kho phân tích (GD-14), số bản ghi sổ cái, lượng WAL và IOPS của PostgreSQL. Nhánh này cũng kiểm tra ngược ngân sách kết nối CSDL theo hai bất đẳng thức ở TL-04 mục 4.7, vì số kết nối là ràng buộc cứng giới hạn `maxReplicas` của các service.

Nhánh bên phải là nhánh dung lượng. Số tài khoản nhân với khối lượng dữ liệu bình quân mỗi người mỗi quý (GD-15 đến GD-20) cho dung lượng của từng hệ lưu trữ theo năm. Dung lượng được tính cho ba năm đầu, vì ba năm là chu kỳ khấu hao máy chủ và cũng là ngưỡng mà các phân vùng cũ được chuyển sang kho lưu trữ dài hạn (TL-04 mục 4.5).

Ba nhánh hội tụ ở hồ sơ cell. Sau đó, bậc quy mô được tính bằng cách cộng các hồ sơ cell có trong bậc với các thành phần dùng chung (vùng điều phối toàn cục, cụm quản trị, kho sao lưu) và nhân đôi phần cần có ở TTDL dự phòng.

## Danh mục giả định định cỡ

Bảng dưới đây liệt kê toàn bộ giả định. Cột "Nguồn" cho biết giả định lấy từ baseline (đã thống nhất, không thay đổi trong TL-05), từ TL-04 (đã nêu là giả định ở TL-04, TL-05 dùng lại) hay do TL-05 đặt mới. Cột "Kiểm chứng" chỉ kịch bản PERF hoặc chỉ số vận hành dùng để thay giả định bằng số đo.

| Mã | Giả định | Giá trị | Nguồn | Kiểm chứng |
|---|---|---|---|---|
| GD-01 | Số tài khoản thiết kế của cell | Theo hồ sơ: 50.000; 300.000; 800.000; 1.200.000 | TL-05 (compact), TL-04 (S, M, L) | Số tài khoản thật trong `registry.tenant_cell` |
| GD-02 | Tỷ lệ người dùng đồng thời trên tài khoản | 5% | Baseline mục 3.1 (300.000 / 6.000.000) | Faro RUM: số phiên hoạt động trong giờ cao điểm thật |
| GD-03 | Thời gian suy nghĩ bình quân giữa hai request của một phiên | Z ≈ 20 giây | Baseline mục 3.1 | Faro và log APISIX: số request mỗi phiên mỗi phút |
| GD-04 | Hệ số đột biến ngày hạn nộp | 2 | Baseline mục 3.1 | Tỷ lệ RPS giờ đỉnh ngày hạn trên RPS giờ đỉnh ngày thường |
| GD-05 | Tỷ lệ đọc/ghi | 80/20 | Baseline NFR-02 | Nhãn phương thức HTTP trong metric APISIX |
| GD-06 | Số sự kiện nghiệp vụ trên một request ghi | 1,5 | Baseline mục 3.1 | Số dòng `outbox_event` trên số request ghi |
| GD-07 | Cơ cấu lưu lượng theo service | mission-core 65%, org 5%, iam 3%, workflow 5%, evidence 4%, analytics 5%, search 3%, notification 2%, integration 2%, AI 0,5%, còn lại từ cache 5,5% | TL-04 mục 4.3 | Metric APISIX theo tuyến |
| GD-08 | Thông lượng một pod | mission-core 250 RPS; org 400; workflow API 150; evidence API 120; analytics 100; search 300; integration 200; APISIX 1.500 | TL-04 mục 4.3 | PERF-02 |
| GD-09 | CPU bình quân một request mission-core | Đọc 8 ms, ghi 20 ms, bình quân 10,4 ms | TL-04 mục 4.3 | PERF-02, hồ sơ CPU bằng async-profiler |
| GD-10 | Số lần hoàn thành user task đỉnh | 670 lần/giây cho 6 triệu tài khoản, quy tỷ lệ theo tài khoản | Baseline mục 3.1 | Metric Flowable `flowable_task_completed_total` |
| GD-11 | Số đăng nhập đỉnh | Tài khoản / 12.000 mỗi giây (100 lần/giây ở 1,2 triệu) | TL-04 mục 4.3 | Sự kiện `iam.auth.event.v1` |
| GD-12 | Hệ số xếp lịch node | Node 16 vCPU còn 15 vCPU khả dụng; pool ứng dụng dùng được 80%, pool worker 70% | TL-05 (hiệu chuẩn cho khớp TL-04 mục 4.4) | Tỷ lệ requests trên allocatable từ kube-state-metrics |
| GD-13 | Chi phí container OPA chạy cùng pod | 5% tài nguyên pool ứng dụng | TL-04 mục 5.2 | Metric container `opa` |
| GD-14 | Số dòng thay đổi trên một giao dịch ghi (bản ghi CDC) | 6 | TL-04 mục 4.6 | Metric Debezium `TotalNumberOfEventsSeen` |
| GD-15 | Dữ liệu nghiệp vụ mỗi người mỗi quý trong `pg-core` | 200 dòng × 1 KB, nhân 2 cho chỉ mục, tức 400 KB | TL-04 mục 4.5 | `pg_total_relation_size` theo kỳ |
| GD-16 | Bản ghi sổ cái mỗi người mỗi quý | 200 bản ghi × 400 byte, cộng chỉ mục, khoảng 500 KB mỗi người mỗi năm | TL-04 mục 4.5 | Kích thước `ledger.ledger_entry` theo phân vùng |
| GD-17 | Minh chứng mỗi người mỗi quý | 8 tệp × 1 MB trước khử trùng | Baseline mục 3.1 | Metric SVC-08: dung lượng đối tượng mới mỗi ngày |
| GD-18 | Chỉ mục OpenSearch mỗi người mỗi năm | 250 KB dữ liệu chính | TL-04 mục 4.6 (300 GB cho 1,2 triệu) | `_cat/indices` |
| GD-19 | Dữ liệu ClickHouse sau nén mỗi người mỗi năm | 0,25 MB | TL-04 mục 4.6 (200–400 GB cho 1,2 triệu) | `system.parts` |
| GD-20 | Log và trace trên một request | Log 1,2 KB thô (nén khoảng 10 lần trong Loki); trace 6 KB, lưu 12% request (10% bình thường cộng lỗi và chậm), nén khoảng 3 lần | TL-05 | Metric ingest của Loki và Tempo |
| GD-21 | Tỷ lệ CCU dùng trợ lý AI | 1%, mỗi phiên 1 yêu cầu mỗi phút, 1.500 token vào, 300 token ra | TL-04 mục 4.9 | PERF-26, metric vLLM |
| GD-22 | Lưu lượng bình quân ngày so với đỉnh đột biến | 25% | TL-04 mục 4.6 | Metric APISIX theo giờ |
| GD-23 | Số phiếu cần sinh và ký trong một đợt cuối kỳ | Tài khoản / 12 (100.000 phiếu ở 1,2 triệu) | TL-04 mục 4.3 suy ngược từ NFR-06 | Số `docgen.render_job` mỗi đợt |
| GD-24 | Số chuỗi metric hoạt động mỗi cell | compact 0,2 triệu; S 0,6 triệu; M 1,0 triệu; L 1,5 triệu | TL-05 | `prometheus_tsdb_head_series` |
| GD-25 | Tỷ lệ tăng trưởng tài khoản sau khi một cell vào vận hành | Đạt 100% tài khoản thiết kế sau 12 tháng; không tăng thêm trong năm 2 và năm 3 trừ khi có quyết định mở rộng phạm vi | TL-05 | Số tài khoản hoạt động hằng tháng |
| GD-26 | Hệ số lắp đặt kho đối tượng | Dung lượng lắp năm 1 bằng 1,5 lần dung lượng minh chứng năm 1 (đã gồm erasure coding); dung lượng sau 3 năm bằng 4 lần | TL-05 (hiệu chuẩn cho khớp TL-04 mục 4.8) | Mức đầy của cụm Ceph |

Ba giả định có ảnh hưởng lớn nhất tới kết quả là GD-02, GD-03 và GD-09, vì số pod của mission-core và CPU của `pg-core` tỷ lệ thuận với cả ba. Chương 10 phân tích riêng độ nhạy theo ba giả định này. Giả định GD-12 (hệ số xếp lịch) được TL-05 chọn sao cho áp dụng lên cell hạng L cho ra đúng số node của TL-04 mục 4.4 (16 node app-general và 16 node app-worker ở đỉnh), nhờ đó các hồ sơ nhỏ hơn được tính nhất quán với cell chuẩn.

## Công thức

Các công thức dưới đây được dùng cho mọi hồ sơ. Ký hiệu A là số tài khoản thiết kế của cell (GD-01).

Lưu lượng tại cổng API:

$$\text{CCU} = 0{,}05 \times A, \qquad \text{RPS}_{\text{thường}} = \frac{\text{CCU}}{Z} = \frac{\text{CCU}}{20}, \qquad \text{RPS}_{\text{đỉnh}} = 2 \times \text{RPS}_{\text{thường}}$$

Lưu lượng ghi, sự kiện và CDC ở đỉnh:

$$W = 0{,}2 \times \text{RPS}_{\text{đỉnh}}, \qquad E = 1{,}5 \times W, \qquad \text{CDC} = 6 \times W$$

Số pod cần ở đỉnh của service s, với tỷ lệ lưu lượng $p_s$ (GD-07) và thông lượng một pod $T_s$ (GD-08):

$$n_s = \left\lceil \frac{p_s \times \text{RPS}_{\text{đỉnh}}}{T_s} \right\rceil, \qquad \text{maxReplicas}_s \ge \lceil 1{,}5 \times n_s \rceil$$

Hệ số 1,5 cho phép mất một trong ba vùng sự cố đúng lúc đỉnh mà hai vùng còn lại vẫn đủ chỗ cho $n_s$ pod (TL-04 mục 4.3). Giá trị `minReplicas` không tính từ tải mà từ yêu cầu sẵn sàng: tối thiểu 3 (mỗi vùng sự cố một pod) ở hồ sơ S, M, L và tối thiểu 2 ở hồ sơ compact chạy trong một vùng sự cố.

Số node của một pool ứng dụng ở mức đỉnh, với $c_s$ là CPU yêu cầu của một pod (TL-04 mục 5.2):

$$N_{\text{pool}} = \left\lceil \frac{1{,}05 \times \sum_s \text{maxReplicas}_s \times c_s}{15 \times u} \right\rceil$$

trong đó 1,05 là phần của OPA (GD-13), 15 là số vCPU khả dụng của node 16 vCPU và $u$ là hệ số xếp lịch (0,8 cho pool app-general, 0,7 cho pool app-worker theo GD-12). Pool worker có hệ số thấp hơn vì các pod xử lý lô (Gotenberg, scoring) có kích thước lớn và được xếp lịch theo đợt, nên phân mảnh tài nguyên nhiều hơn. Kiểm tra với cell hạng L: tổng `maxReplicas × CPU` của pool app-general là 183 vCPU, nhân 1,05 được 192 vCPU, chia 15 × 0,8 = 12 được 16 node, đúng bằng TL-04.

Các tác vụ bất đồng bộ được định cỡ theo chỉ tiêu thời gian hoàn thành của NFR-06, không theo RPS:

$$n_{\text{scoring}} = 2 \times \left\lceil \frac{A}{7.200 \times 50} \right\rceil, \qquad n_{\text{Gotenberg}} = \left\lceil \frac{(A/12) \times 1{,}5}{14.400 \times 0{,}5 \times 2} \right\rceil$$

Công thức thứ nhất chia số hồ sơ cần tính lại cho 2 giờ và năng lực 50 hồ sơ mỗi giây của một pod, rồi nhân 2 để đạt mục tiêu 1 giờ. Công thức thứ hai chia tổng CPU sinh phiếu (1,5 giây CPU mỗi phiếu) cho 4 giờ, hiệu suất 50% và 2 vCPU mỗi pod Gotenberg.

## Tải đầu vào theo hồ sơ cell

Áp dụng các công thức trên cho bốn hồ sơ và cho mức trần của hồ sơ L (1,5 triệu tài khoản, ngưỡng tách cell theo TL-01) cho bảng tải đầu vào dưới đây. Toàn bộ là ước tính tính từ giả định.

| Đại lượng | compact | S | M | L | Trần L (tách cell) |
|---|---|---|---|---|---|
| Tài khoản thiết kế (GD-01) | 50.000 | 300.000 | 800.000 | 1.200.000 | 1.500.000 |
| CCU | 2.500 | 15.000 | 40.000 | 60.000 | 75.000 |
| RPS thường | 125 | 750 | 2.000 | 3.000 | 3.750 |
| RPS đỉnh (đột biến) | 250 | 1.500 | 4.000 | 6.000 | 7.500 |
| Request ghi đỉnh mỗi giây | 50 | 300 | 800 | 1.200 | 1.500 |
| Sự kiện nghiệp vụ đỉnh mỗi giây | 75 | 450 | 1.200 | 1.800 | 2.250 |
| Bản ghi CDC đỉnh mỗi giây | 300 | 1.800 | 4.800 | 7.200 | 9.000 |
| User task hoàn thành đỉnh mỗi giây | 6 | 34 | 89 | 134 | 168 |
| Đăng nhập đỉnh mỗi giây | 4 | 25 | 67 | 100 | 125 |
| Hồ sơ tính lại cuối kỳ | 50.000 | 300.000 | 800.000 | 1.200.000 | 1.500.000 |
| Phiếu sinh và ký mỗi đợt (GD-23) | 4.200 | 25.000 | 66.700 | 100.000 | 125.000 |
| Tệp minh chứng đỉnh mỗi giây | 1,4 | 8,3 | 22 | 33 | 42 |
| Request mỗi ngày (bình quân, GD-22) | 5,4 triệu | 32 triệu | 86 triệu | 130 triệu | 162 triệu |

Cột "Trần L" cho thấy vì sao TL-01 đặt ngưỡng tách cell ở 1,5 triệu tài khoản. Ở mức này, số lần hoàn thành user task đỉnh đạt 168 lần mỗi giây, vượt năng lực thiết kế 150 lần mỗi giây của workflow-service nhưng còn dưới ngưỡng 300 lần mỗi giây phải tách engine; mission-core cần 20 pod ở đỉnh, tiến sát `maxReplicas` 24 và chỉ còn dư địa khoảng 20% thay vì 50%. Vượt mức này, cell vẫn chạy được bằng cách nâng giới hạn, nhưng phạm vi ảnh hưởng của một sự cố và thời gian khôi phục tăng theo, nên phương án đúng là tách cell (chương 11).

# Bậc quy mô triển khai và hồ sơ năng lực cell

## Năm bậc quy mô

Bậc quy mô là một mốc đầu tư: tại mỗi bậc, nền tảng có một tập cell, một cấu hình vùng điều phối toàn cục và cụm quản trị, một cấu hình kho đối tượng và một mức dự phòng xác định. TL-05 chọn năm bậc dựa trên ba căn cứ. Căn cứ thứ nhất là lộ trình triển khai của bản đánh giá và TL-04 chương 12: đợt 1 dựng cell đầu tiên CELL-G4, đợt 3 có tiêu chí 70.000 phiên đạt SLO trên một cell hạng L cộng một cell hạng M, và mục tiêu nền tảng là 300.000 CCU trên toàn bộ cell. Căn cứ thứ hai là các loại khách hàng của mô hình đám mây riêng và cách ly: một bộ, một tỉnh, một bệnh viện lớn hay một đơn vị quân đội có quy mô từ vài nghìn tới vài trăm nghìn tài khoản. Căn cứ thứ ba là quyết định của chủ đầu tư ngày 19/10/2026 lấy tổng số tài khoản thiết kế toàn quốc là 6,5 triệu (cao hơn 6 triệu của NFR-01 để có dư địa), khớp với tổng định cỡ theo cell của TL-04 mục 4.2.

| Bậc | Tên | Tài khoản thiết kế | CCU thiết kế | Cell và hồ sơ | Dự phòng | Gắn với lộ trình |
|---|---|---|---|---|---|---|
| QM-1 | Thí điểm | ≤ 50.000 | 2.500 | 1 cell `compact` (đề xuất `CELL-NB` hoặc một sở, ngành thí điểm) | Sao lưu sang kho phía Nam, không có bản sao ấm | Thí điểm nội bộ trước đợt 1 (TL-04 mục 11.2) |
| QM-2 | Một tỉnh hoặc một bộ | ≤ 300.000 | 15.000 | 1 cell `S` | Bản sao ấm ở địa điểm thứ hai nếu khách hàng có | Bản cài đám mây riêng; cũng là cỡ của CELL-DTW |
| QM-3 | Cell nhóm tỉnh đầu tiên | 800.000 | 40.000 | 1 cell `M` (CELL-G4) cùng vùng điều phối toàn cục | Bản sao ấm phía Nam | Đợt 1 và đợt 2 (Q4/2026 đến Q2/2027) |
| QM-4 | Khu vực | 3.100.000 | 155.000 | 4 cell: CELL-HCM (`L`), CELL-G4 và CELL-G1 (`M`), CELL-DTW (`S`) | Bản sao ấm phía Nam cho mọi cell | Đầu đợt 3 (từ Q3/2027) |
| QM-5 | Toàn quốc | 6.500.000 | 325.000 | 8 cell: CELL-HCM, CELL-HN (`L`), CELL-G1 đến G4, CELL-SH (`M`), CELL-DTW (`S`) | Bản sao ấm phía Nam cho mọi cell | Mục tiêu nền tảng (NFR-01) |

![Hình 3.1. Năm bậc quy mô triển khai và điều kiện chuyển bậc](dc-bac-quy-mo.png){width=7cm}

Hình 3.1 cho thấy hai kiểu chuyển bậc khác nhau về bản chất. Từ QM-1 lên QM-2 và từ QM-2 lên QM-3 là **nâng hồ sơ của một cell**: tenant vẫn ở cell cũ, nhưng cell được chuyển sang hồ sơ lớn hơn, chủ yếu bằng cách thay máy chủ tầng dữ liệu và đổi tệp values. Từ QM-3 lên QM-4 và từ QM-4 lên QM-5 là **thêm cell** theo quy trình OPS-23 của TL-04: các cell đang chạy giữ nguyên, cell mới được dựng từ cùng chart với hồ sơ phù hợp rồi nhận tenant theo đợt qua OPS-08. Kiểu thứ hai ít rủi ro hơn vì không động tới cell đang phục vụ, và đây cũng là lý do kiến trúc cell được chọn (TL-01, ADR-07).

Bậc QM-4 được chọn có chủ ý gồm một cell hạng L, hai cell hạng M và một cell hạng S, vì đây là tổ hợp nhỏ nhất chứa đủ cả ba hồ sơ đang chạy ở quy mô quốc gia. Khi đạt QM-4, mọi cấu hình mà QM-5 cần (phân vùng Kafka của cell lớn, ngân sách kết nối của `pg-core` hạng L, ba cụm Ceph theo nhóm cell, HSM riêng của khối Đảng) đều đã chạy thật ít nhất một lần, nên bước lên QM-5 chỉ còn là lặp lại. Tiêu chí hoàn thành của đợt 3 (70.000 phiên đạt SLO trên một cell L cộng một cell M) cũng được đo ngay trên QM-4.

Bậc quy mô không bắt buộc đi tuần tự cho mọi khách hàng. Một bộ triển khai đám mây riêng có thể dừng ở QM-2 vĩnh viễn; một đơn vị quân đội có thể chỉ cần QM-1 trong mạng cách ly. Chuỗi QM-1 đến QM-5 là con đường của hệ SaaS quốc gia.

## Bốn hồ sơ năng lực cell

Hồ sơ năng lực là cấu hình chuẩn của một cell đầy đủ. Mỗi hồ sơ có cùng tập thành phần (SVC-01 đến SVC-17 trừ SVC-03 toàn cục, sáu nhóm CSDL, Kafka, Valkey, OpenSearch, ClickHouse, OpenBao, ngăn xếp quan sát của cell) và chỉ khác ở số replica, kích thước tài nguyên và số node. Điều này tuân theo nguyên tắc "một mã nguồn, một bộ Helm chart" của TL-04 mục 2.1: không có thành phần nào chỉ xuất hiện ở hồ sơ lớn.

| Hồ sơ | Phạm vi tài khoản áp dụng | Định cỡ theo | Vùng sự cố | Số node (thường / đỉnh) | Ví dụ |
|---|---|---|---|---|---|
| `compact` | đến 50.000 | 50.000 tài khoản, 2.500 CCU | 1 (cho phép) | 6 / 6 | Thí điểm, bản cách ly của một đơn vị, bệnh viện, trường đại học |
| `S` | 50.000 đến 300.000 | 300.000 tài khoản, 15.000 CCU | 3 | 25 / 30 | CELL-DTW, một bộ, một tỉnh cỡ nhỏ có đám mây riêng |
| `M` | 300.000 đến 800.000 | 800.000 tài khoản, 40.000 CCU | 3 | 30 / 46 | CELL-G1 đến G4, CELL-SH |
| `L` | 800.000 đến 1.500.000 | 1.200.000 tài khoản, 60.000 CCU | 3 | 41 / 64 | CELL-HCM, CELL-HN |

Ranh giới giữa các hồ sơ được đặt ở điểm mà cấu trúc tầng dữ liệu phải thay đổi. Hồ sơ compact gộp tầng dữ liệu, tầng bảo mật và tầng ứng dụng lên sáu node trong một vùng sự cố, vì ở 50.000 tài khoản toàn bộ tải chỉ cần khoảng 80 vCPU ở đỉnh. Hồ sơ S tách đủ chín node pool như TL-04 nhưng dùng máy chủ dữ liệu cỡ nhỏ; hồ sơ M dùng máy chủ CSDL cỡ lớn nhưng gộp sáu cụm PostgreSQL lên ba máy; hồ sơ L dùng sáu máy CSDL và sáu broker Kafka như TL-04 mục 3.3. Bên trong một hồ sơ, tầng ứng dụng tự co giãn theo tải thật nhờ HPA và KEDA, nên một cell hạng M phục vụ 400.000 tài khoản vẫn dùng hồ sơ M nhưng các pool ứng dụng sẽ chạy ở mức thấp hơn bảng.

Cell hạng L thứ hai (CELL-HN, 1,0 triệu tài khoản) dùng hồ sơ L với hai điều chỉnh nhỏ ở tầng ứng dụng: pool app-general 7 / 14 node và app-worker 4 / 14 node. Các bảng của chương 4 và 5 ghi giá trị cho CELL-HCM; giá trị của CELL-HN được nêu trong bảng tổng hợp ở chương 8.

## Máy chủ chuẩn

Để bảng khối lượng ở chương 8 dùng được cho mua sắm, TL-05 quy mọi node về một danh mục máy chủ chuẩn. Danh mục này khớp với cấu hình phần cứng mà TL-04 mục 3.3 đã nêu cho cell hạng L và bổ sung các cỡ nhỏ hơn cho hồ sơ S, M và compact.

| Mã máy chủ | Loại | Cấu hình | Dùng cho |
|---|---|---|---|
| VM-8-32 | Máy ảo Viettel Cloud | 8 vCPU, 32 GiB | pool system mọi hồ sơ |
| VM-8-16 | Máy ảo | 8 vCPU, 16 GiB | pool ingress hồ sơ S; control plane |
| VM-16-32 | Máy ảo, NIC 25 Gbps | 16 vCPU, 32 GiB | pool ingress hồ sơ M, L |
| VM-16-64 | Máy ảo | 16 vCPU, 64 GiB | pool app-general, app-worker hồ sơ S, M, L |
| VM-32-128 | Máy ảo | 32 vCPU, 128 GiB | node hội tụ hồ sơ compact, cụm quản trị |
| VM-24-96 | Máy ảo | 24 vCPU, 96 GiB | cụm vùng điều phối toàn cục |
| PHY-DB-L | Máy chủ vật lý | 48 vCPU, 384 GiB, 4 × 3,84 TB NVMe | `pg-core` hạng L; toàn bộ PostgreSQL hạng M |
| PHY-DB-S | Máy chủ vật lý | 32 vCPU, 128 GiB, 2 × 3,84 TB NVMe | các cụm PostgreSQL phụ hạng L; PostgreSQL hạng S; node dữ liệu compact (thêm 2 × 8 TB HDD) |
| PHY-DATA | Máy chủ vật lý | 16 vCPU, 64 GiB, 2 × 3,84 TB NVMe | Kafka, Valkey, OpenSearch, ClickHouse hạng M, L; OpenSearch và ClickHouse hạng S |
| PHY-DATA-S | Máy chủ vật lý | 8 vCPU, 32 GiB, 2 × 1,92 TB NVMe | Kafka, Valkey hạng S |
| PHY-SEC-L | Máy chủ vật lý, NIC riêng vào mạng HSM | 32 vCPU, 128 GiB, 2 × 1,92 TB NVMe | pool security-zone hạng L |
| PHY-SEC-S | Máy chủ vật lý, NIC riêng vào mạng HSM | 16 vCPU, 64 GiB, 2 × 1,92 TB NVMe | pool security-zone hạng M, S |
| PHY-GPU8 | Máy chủ GPU | 64 vCPU, 1 TiB, 8 GPU 80 GB HBM | pool gpu hạng L |
| PHY-GPU4 | Máy chủ GPU | 32 vCPU, 512 GiB, 4 GPU 80 GB HBM | pool gpu hạng M, S |
| PHY-CEPH | Máy chủ lưu trữ | 12 khe HDD 20 TB (lắp dần), 2 × NVMe cho BlueStore DB/WAL, 2 × 25 Gbps | cụm Ceph RGW và kho sao lưu |

Đơn vị vCPU trong toàn bộ tài liệu là luồng phần cứng (hyper-thread) đối với máy chủ vật lý và vCPU danh định đối với máy ảo, đúng như cách TL-04 tính. Control plane của mỗi cụm Kubernetes (3 master) chạy trên máy ảo VM-8-16 và không được tính vào số node của các bảng, theo cùng quy ước với TL-04.

# Định cỡ tầng ứng dụng theo hồ sơ cell

## Số replica của các service đồng bộ

Bảng dưới đây cho `minReplicas` và `maxReplicas` của từng service đồng bộ ở bốn hồ sơ. Cột L lấy nguyên giá trị đã chốt ở TL-04 mục 5.2. Các cột còn lại tính theo công thức ở mục 2.4 từ RPS đỉnh của hồ sơ, rồi làm tròn theo hai quy tắc: `maxReplicas` không nhỏ hơn 1,5 lần số pod cần ở đỉnh, và `minReplicas` đủ cho mỗi vùng sự cố một pod (hồ sơ compact chỉ có một vùng nên tối thiểu 2 để chịu được mất một node). Yêu cầu tài nguyên mỗi pod giữ nguyên như TL-04 ở mọi hồ sơ, vì thông lượng một pod (GD-08) được hiệu chuẩn trên đúng cấu hình đó; thu nhỏ pod để tiết kiệm sẽ làm sai giả định và phải đo lại.

| Service | CPU / bộ nhớ mỗi pod | compact | S | M | L |
|---|---|---|---|---|---|
| SVC-01 api-gateway | 2 / 2 GiB | 2 / 3 | 3 / 4 | 3 / 6 | 3 / 8 |
| SVC-05 mission-core | 3 / 8 GiB | 2 / 3 | 3 / 6 | 6 / 17 | 6 / 24 |
| SVC-04 org-service | 1 / 3 GiB | 2 / 2 | 2 / 3 | 3 / 4 | 3 / 6 |
| SVC-02 iam | 2 / 3 GiB | 2 / 3 | 2 / 4 | 3 / 6 | 3 / 8 |
| SVC-06 workflow API | 2 / 6 GiB | 2 / 2 | 2 / 3 | 3 / 6 | 3 / 8 |
| SVC-08 evidence API | 2 / 3 GiB | 2 / 2 | 2 / 3 | 3 / 6 | 3 / 8 |
| SVC-15 analytics-api | 1 / 2 GiB | 1 / 2 | 2 / 3 | 2 / 4 | 2 / 6 |
| SVC-15 Cube | 2 / 4 GiB | 1 / 2 | 2 / 3 | 3 / 6 | 3 / 8 |
| SVC-15 Superset | 1 / 3 GiB | 1 / 1 | 1 / 2 | 2 / 3 | 2 / 4 |
| SVC-16 search API | 1 / 2 GiB | 1 / 2 | 2 / 2 | 2 / 3 | 2 / 4 |
| SVC-12 notification | 2 / 2 GiB | 2 / 2 | 2 / 3 | 3 / 6 | 4 / 10 |
| SVC-13 integration-gateway | 1 / 3 GiB | 1 / 2 | 2 / 3 | 3 / 4 | 3 / 6 |
| SVC-17 web-frontend origin | 0,25 / 256 MiB | 2 / 2 | 2 / 2 | 2 / 3 | 2 / 4 |

Ở hồ sơ M, mission-core cần 11 pod ở đỉnh (2.600 RPS chia 250), nên `maxReplicas` là 17, đúng với quy ước "M nhỏ hơn L khoảng 30%" mà TL-04 mục 5.2 đã nêu. Ở hồ sơ S, mission-core chỉ cần 4 pod ở đỉnh; `maxReplicas` 6 cho dư địa 50% và giữ đúng số kết nối tối đa 144 tới PgBouncer. Ở hồ sơ compact, gần như mọi service chỉ cần một pod ở đỉnh; số replica được đặt theo yêu cầu sẵn sàng chứ không theo tải. Một số service ít quan trọng (Superset, analytics-api, search API, integration-gateway) chạy một pod ở compact, chấp nhận gián đoạn vài chục giây khi node chứa pod đó gặp sự cố, vì các chức năng này không nằm trên đường găng của quy trình đánh giá.

Một số service có `minReplicas` cao hơn mức tải cần vì lý do khác tải. SVC-12 notification ở hồ sơ L có `minReplicas` 4 vì mỗi pod giữ tối đa 15.000 kết nối SSE và cell giữ tới 60.000 kết nối. SVC-02 iam giữ tối thiểu 3 pod ở hồ sơ M và L để bộ nhớ đệm phân tán Infinispan của Keycloak luôn có hai bản sao khi một pod khởi động lại.

## Số replica của các service bất đồng bộ và vùng bảo mật

Các service bất đồng bộ được định cỡ theo chỉ tiêu thời gian hoàn thành của NFR-06 (tính lại toàn bộ điểm của cell trong 2 giờ, sinh và ký lô phiếu trong 4 giờ) và theo thông lượng hàng đợi. Các service vùng bảo mật được định cỡ theo số phân vùng consumer và theo lưu lượng ký số.

| Service | CPU / bộ nhớ mỗi pod | compact | S | M | L | Căn cứ ở hồ sơ M |
|---|---|---|---|---|---|---|
| SVC-07 scoring-service | 2 / 4 GiB | 1 / 4 | 2 / 6 | 2 / 16 | 2 / 24 | 800.000 hồ sơ trong 1 giờ cần 222 hồ sơ/giây, 5 pod; tối đa 16 cho phát lại sau sự cố |
| SVC-11 document-service | 1 / 3 GiB | 1 / 2 | 1 / 6 | 2 / 14 | 2 / 20 | Điều phối 66.700 phiếu |
| SVC-11 Gotenberg | 2 / 3 GiB | 1 / 2 | 2 / 6 | 2 / 16 | 2 / 24 | 66.700 × 1,5 s CPU / 4 giờ / 50% hiệu suất ≈ 14 vCPU, 7 pod; tối đa 16 |
| SVC-08 ClamAV scanner | 1 / 3 GiB | 1 / 2 | 2 / 4 | 2 / 8 | 2 / 12 | 22 tệp/giây, 10 tệp mỗi pod, 3 pod; tối đa 8 |
| SVC-06 workflow executor | 2 / 4 GiB | 1 / 2 | 2 / 4 | 2 / 8 | 2 / 10 | 89 user task/giây sinh khoảng 200 job/giây, 80 job mỗi pod, 3 pod |
| SVC-16 search indexer | 1 / 2 GiB | 1 / 2 | 1 / 2 | 2 / 4 | 2 / 6 | khoảng 330 tài liệu/giây, một pod đủ |
| Kafka Connect (Debezium) | 2 / 4 GiB | 1 / 1 | 2 / 2 | 3 / 3 | 3 / 3 | Một connector mỗi database theo TL-03; số worker cố định |
| SVC-09 ledger-service | 1 / 1 GiB | 2 / 3 | 3 / 6 | 3 / 6 | 6 / 12 | Topic `sys.ledger.append.v1` 6 phân vùng ở hồ sơ S, M; 12 ở hồ sơ L |
| SVC-10 esign-service | 1 / 2 GiB | 1 / 2 | 2 / 3 | 2 / 4 | 2 / 6 | 67 phiên ký lô mỗi đợt |
| SVC-14 ai-gateway | 1 / 2 GiB | 1 / 1 | 1 / 2 | 2 / 4 | 2 / 6 | 400 phiên trợ lý, khoảng 7 yêu cầu/giây |

Số phân vùng của topic đầu vào sổ cái được hạ từ 12 xuống 6 ở hồ sơ S và M vì lưu lượng sổ cái đỉnh (450 và 1.200 sự kiện mỗi giây) nằm trong năng lực của một pod (khoảng 2.000 bản ghi mỗi giây), còn số replica của ledger-service gắn với số phân vùng (mỗi pod nhận hai phân vùng ở trạng thái thường). Việc giảm phân vùng giữ nguyên tính chất thứ tự theo khoá `tenantId:aggregateId` của TL-03, vì chuỗi băm được phân vùng theo 64 phân vùng logic mỗi tenant bên trong ledger-service, không theo phân vùng Kafka. Topic đầu vào tính điểm giữ 48 phân vùng ở hồ sơ M và L, 12 phân vùng ở hồ sơ S và 6 phân vùng ở compact, để `maxReplicas` của scoring-service không vượt số phân vùng.

## Số node của tầng ứng dụng

Từ hai bảng trên và công thức ở mục 2.4, tổng CPU yêu cầu của các pool và số node được tính như sau. Cột "CPU yêu cầu" đã gồm 5% của OPA (GD-13). Pool ingress chứa APISIX và etcd của APISIX; pool security-zone được tính ở chương 5 vì nó chia máy chủ với `pg-ledger` và `pg-secure`.

| Hồ sơ | Pool | CPU yêu cầu ở mức min | CPU yêu cầu ở mức max | Node thường | Node đỉnh | Máy chủ |
|---|---|---|---|---|---|---|
| L | ingress | 6 | 17 | 3 | 6 | VM-16-32 |
| L | app-general | 66 | 192 | 8 | 16 | VM-16-64 |
| L | app-worker | 25 | 168 | 4 | 16 | VM-16-64 |
| M | ingress | 6 | 13 | 3 | 4 | VM-16-32 |
| M | app-general | 64 | 136 | 6 | 12 | VM-16-64 |
| M | app-worker | 25 | 118 | 3 | 12 | VM-16-64 |
| S | ingress | 6 | 8 | 3 | 3 | VM-8-16 |
| S | app-general | 40 | 67 | 4 | 6 | VM-16-64 |
| S | app-worker | 21 | 50 | 2 | 5 | VM-16-64 |
| compact | hội tụ (system, ingress, app, worker) | 54 | 80 | 3 | 3 | VM-32-128 |

Số node "thường" của pool app-general không chỉ đủ cho tổng `minReplicas` mà còn đủ cho số pod mission-core cần ở RPS thường (8 pod ở hồ sơ L, 6 ở hồ sơ M, 3 ở hồ sơ S) cộng một node trống để HPA tăng pod ngay mà không phải chờ Cluster Autoscaler thêm node (khoảng 3–5 phút trên Viettel Cloud). Pool app-worker ở mức thường chỉ cần đủ `minReplicas`, vì đỉnh của nó là các đợt xử lý cuối kỳ đã biết trước và được KEDA theo lịch tăng trước (TL-04 mục 6.4), nên Cluster Autoscaler có đủ thời gian thêm node.

Hồ sơ compact không co giãn node. Ba node VM-32-128 chứa cả các thành phần hệ thống (CoreDNS, operator, KEDA, Prometheus của cell, khoảng 6 vCPU), APISIX, các service đồng bộ và service xử lý lô. Ở đỉnh, tổng CPU yêu cầu khoảng 80 vCPU trên 93 vCPU khả dụng, tức 86%. Mức này cao hơn hệ số xếp lịch của các hồ sơ khác, và được chấp nhận vì đỉnh tải lô của compact (tính lại 50.000 hồ sơ, sinh 4.200 phiếu) kéo dài dưới 30 phút. Nếu bản cài compact phải chạy AI, OCR hoặc tích hợp nặng, thêm một node VM-32-128 thứ tư.

## Pool system

Pool system ở mọi hồ sơ trừ compact gồm 3 node VM-8-32. Tải chính của pool là Prometheus của cell (lưu cục bộ 2 ngày rồi đẩy lên Thanos ở cụm quản trị), các operator (CNPG, Strimzi, KEDA, Kyverno, ESO, cert-manager) và CoreDNS. Bộ nhớ của Prometheus tăng theo số chuỗi metric hoạt động (GD-24) với khoảng 3–4 KB mỗi chuỗi: 1,5 triệu chuỗi ở hồ sơ L cần khoảng 6 GiB, đặt trong một node 32 GiB cùng các operator là đủ. Prometheus chạy hai bản sao ở hai node khác nhau. Nếu số chuỗi vượt 3 triệu (ví dụ khi bật metric chi tiết theo tenant ở cell SH có hàng nghìn tenant), pool system cần nâng lên VM-16-64; đây là ngưỡng NG-12 ở chương 11.

# Định cỡ tầng dữ liệu theo hồ sơ cell

## Nguyên tắc chung cho tầng dữ liệu

Tầng dữ liệu được định cỡ theo hai đại lượng độc lập: năng lực xử lý ở giờ đỉnh (CPU, bộ nhớ, IOPS, số kết nối) và dung lượng sau ba năm vận hành. Đại lượng nào lớn hơn quyết định cấu hình. Ở cell hạng L, CPU của `pg-core` là ràng buộc chính như TL-04 mục 4.7 đã chỉ ra; ở hồ sơ compact và S, ràng buộc chính lại là yêu cầu sẵn sàng (ba bản sao trên ba node) và bộ nhớ tối thiểu để chạy ổn định các hệ như OpenSearch và ClickHouse, chứ không phải tải. Vì vậy, khi đi từ hồ sơ lớn xuống hồ sơ nhỏ, tài nguyên tầng dữ liệu giảm chậm hơn nhiều so với tầng ứng dụng: hồ sơ S phục vụ 25% số tài khoản của hồ sơ L nhưng tầng dữ liệu của nó vẫn cần khoảng 40% số vCPU.

Ba quy tắc giữ nguyên ở mọi hồ sơ. Một là mỗi hệ dữ liệu có ba bản sao trên ba vùng sự cố (hoặc ba node ở hồ sơ compact), để mất một node không mất dữ liệu đã xác nhận. Hai là ranh giới quyền giữa cụm ứng dụng và cụm vùng bảo mật được giữ: dù gộp cụm PostgreSQL để tiết kiệm ở hồ sơ nhỏ, dữ liệu sổ cái và ký số không bao giờ nằm chung cụm với dữ liệu nghiệp vụ, vì DBA của cụm ứng dụng không được có quyền trên sổ cái (TL-04 mục 4.5, SEC-29). Ba là cấu hình chịu lỗi của Kafka (`min.insync.replicas=2`, `acks=all`, hệ số nhân bản 3) không thay đổi theo hồ sơ.

## PostgreSQL

TL-04 mục 4.5 chia mỗi cell thành sáu cụm PostgreSQL: `pg-core`, `pg-flowable`, `pg-platform`, `pg-iam`, `pg-ledger`, `pg-secure`. Ở hồ sơ L, sáu cụm chạy trên sáu máy chủ CSDL và ba máy vùng bảo mật. Ở hồ sơ nhỏ hơn, giữ sáu cụm riêng là hợp lý về logic nhưng lãng phí về phần cứng nếu mỗi cụm chiếm máy riêng, nên TL-05 áp dụng hai mức gộp. Mức gộp thứ nhất (hồ sơ M và S) giữ nguyên sáu cụm CNPG nhưng đặt bốn cụm ứng dụng lên chung ba máy chủ, mỗi máy chứa một instance của mỗi cụm; các cụm vẫn tách tiến trình, tách volume và tách tài khoản, chỉ chung phần cứng. Mức gộp thứ hai (hồ sơ compact) gộp bốn cụm ứng dụng thành một cụm CNPG tên `pg-core` với các database `core`, `flowable`, `platform`, `iam` riêng, và gộp hai cụm vùng bảo mật thành cụm `pg-ledger` với các database `ledger`, `esign`, `ai`. Mức gộp thứ hai làm mất khả năng cô lập tải giữa Flowable và mission-core, điều chấp nhận được ở 50.000 tài khoản vì tổng tải ghi chỉ khoảng 50 giao dịch mỗi giây.

Bảng dưới đây cho cấu hình mỗi instance (mỗi cụm có 3 instance: một primary, một standby đồng bộ, một standby bất đồng bộ phục vụ đọc).

| Cụm | compact | S | M | L (TL-04) |
|---|---|---|---|---|
| `pg-core` | 8 vCPU / 48 GiB (gộp core, flowable, platform, iam) | 8 / 64 | 16 / 128 | 32 / 256 |
| `pg-flowable` | gộp vào `pg-core` | 4 / 16 | 8 / 32 | 16 / 64 |
| `pg-platform` | gộp vào `pg-core` | 4 / 16 | 8 / 32 | 16 / 64 |
| `pg-iam` | gộp vào `pg-core` | 2 / 8 | 2 / 8 | 4 / 16 |
| `pg-ledger` | 2 / 12 (gộp ledger, esign, ai) | 2 / 8 | 4 / 16 | 8 / 32 |
| `pg-secure` | gộp vào `pg-ledger` | 2 / 16 | 4 / 24 | 8 / 64 |
| Máy chủ cho các cụm ứng dụng | 3 node dữ liệu PHY-DB-S dùng chung | 3 × PHY-DB-S | 3 × PHY-DB-L | 3 × PHY-DB-L (`pg-core`) và 3 × PHY-DB-S (ba cụm còn lại) |
| Máy chủ cho các cụm vùng bảo mật | 3 node dữ liệu PHY-DB-S dùng chung | 3 × PHY-SEC-S | 3 × PHY-SEC-S | 3 × PHY-SEC-L |

Ở hồ sơ M, mỗi máy PHY-DB-L chứa một instance của bốn cụm ứng dụng với tổng 34 vCPU và 200 GiB, còn khoảng 14 vCPU và 184 GiB dư. Phần dư này có chủ đích: khi cell M tiến tới 800.000 tài khoản thật và CPU của `pg-core` chạm ngưỡng NG-03, instance `pg-core` được nâng lên 24 vCPU và 192 GiB ngay trên máy hiện có, chỉ cần một lần chuyển primary có kiểm soát, không phải mua máy mới. Ở hồ sơ S, mỗi máy PHY-DB-S chứa 18 vCPU và 104 GiB của bốn instance.

Kiểm tra năng lực CPU của `pg-core` theo phương pháp TL-04 mục 4.7 (mỗi pod mission-core đặt khoảng 600 câu lệnh mỗi giây lên primary, mỗi câu lệnh 0,6 ms CPU, primary dùng tối đa 70% CPU):

| Hồ sơ | vCPU primary `pg-core` | Năng lực câu lệnh mỗi giây | `maxReplicas` mission-core | Tải tối đa lên primary | Biên |
|---|---|---|---|---|---|
| compact | 8 (gộp) | ≈ 9.300 | 3 | ≈ 1.800 cộng khoảng 1.000 của Flowable và platform | ≈ 3,3 lần |
| S | 8 | ≈ 9.300 | 6 | ≈ 3.600 | ≈ 2,6 lần |
| M | 16 | ≈ 18.700 | 17 | ≈ 10.200 | ≈ 1,8 lần |
| L | 32 | ≈ 37.000 | 24 | ≈ 14.400 | ≈ 2,6 lần |

Hồ sơ M có biên thấp nhất (1,8 lần) vì `maxReplicas` của mission-core bị chặn bởi tải đỉnh, còn primary được chọn ở 16 vCPU để vừa với máy PHY-DB-L cùng ba cụm khác. Biên 1,8 lần vẫn đủ cho đột biến dự kiến, và nếu PERF-02 cho thấy số câu lệnh mỗi request cao hơn giả định, phương án nâng `pg-core` lên 24 vCPU nói ở trên nâng biên lên khoảng 2,7 lần.

Dung lượng PostgreSQL sau ba năm được tính từ GD-15 và GD-16, kèm volume WAL đủ cho 8 giờ WAL đỉnh (phòng khi kho sao lưu tạm không nhận):

| Cụm | compact | S | M | L |
|---|---|---|---|---|
| `pg-core` dữ liệu 3 năm | ≈ 0,5 TB (gộp) | ≈ 1,5 TB | ≈ 3,9 TB | ≈ 5,8 TB |
| Volume `pg-core` (data / WAL) | 1 TB / 200 GB | 2 TB / 256 GB | 4 TB / 512 GB | 6 TB / 1 TB |
| `pg-flowable` (runtime cộng lịch sử 90 ngày) | gộp | 0,5 TB | 1 TB | 2 TB |
| `pg-platform` | gộp | 1 TB | 2,5 TB | 3 TB |
| `pg-iam` | gộp | 50 GB | 100 GB | 200 GB |
| `pg-ledger` 3 năm (trước khi xuất kho dài hạn) | 0,1 TB (gộp) | 0,5 TB | 1,5 TB | 2 TB |
| `pg-secure` | gộp | 0,3 TB | 1 TB | 1 TB |
| WAL bình quân mỗi ngày trước nén | ≈ 4 GB | ≈ 22 GB | ≈ 57 GB | ≈ 86 GB |
| IOPS ghi ngẫu nhiên đỉnh của `pg-core` | ≈ 330 | ≈ 2.000 | ≈ 5.300 | ≈ 8.000 |

Ở mọi hồ sơ, IOPS đỉnh thấp hơn hai bậc độ lớn so với năng lực một ổ NVMe doanh nghiệp, nên đĩa không phải ràng buộc; số ổ được chọn theo dung lượng và theo yêu cầu dự phòng (RAID không được dùng, dự phòng nằm ở ba bản sao của CNPG).

## Ngân sách kết nối theo hồ sơ

Hai bất đẳng thức ngân sách kết nối của TL-04 mục 4.7 được áp dụng cho từng hồ sơ. Kích thước pool của mission-core giữ 24 kết nối mỗi pod (tổng 8 DataSource theo module) ở hồ sơ S, M, L, vì đây là cấu hình được hiệu chuẩn bằng PERF-02; ở hồ sơ compact, pool mỗi pod được hạ xuống 12 (`task` 5, các module còn lại 1) vì `pg-core` gộp phải chia kết nối cho Flowable, các service nền tảng và Keycloak.

| Đại lượng | compact | S | M | L |
|---|---|---|---|---|
| `maxReplicas` mission-core × pool mỗi pod | 3 × 12 = 36 | 6 × 24 = 144 | 17 × 24 = 408 | 24 × 24 = 576 |
| Số instance PgBouncer | 2 | 2 | 3 | 3 |
| `default_pool_size` tổng mỗi instance cho mission-core | 8 | 13 | 20 | 40 |
| Kết nối PgBouncer tới primary cho mission-core | 16 | 26 | 60 | 120 |
| Kết nối của các service khác dùng chung cụm | 16 (Flowable 6, platform 6, iam 4) | 0 (cụm riêng) | 0 (cụm riêng) | 0 (cụm riêng) |
| Kết nối trực tiếp (Debezium, bản sao, sao lưu, giám sát) và dự trữ | 35 | 35 | 50 | 50 |
| Tổng phía máy chủ / `max_connections` | 67 / 200 | 61 / 200 | 110 / 300 | 170 / 400 |
| Kết nối hoạt động trên vCPU primary | 32 / 8 = 4,0 | 26 / 8 = 3,3 | 60 / 16 = 3,8 | 120 / 32 = 3,8 |

Cột cuối cùng giữ số kết nối hoạt động trong khoảng 2 đến 4 lần số vCPU như TL-04 khuyến nghị. Tham số `max_db_connections` của PgBouncer ở cell bridge (CELL-G1 đến G4, hồ sơ M) được đặt theo từng database tỉnh để một tỉnh đột biến không chiếm hết kết nối của các tỉnh khác: với 7 đến 9 tỉnh mỗi cell, mỗi database được tối đa 12 kết nối máy chủ trên mỗi instance PgBouncer, và tổng vẫn bị chặn bởi `default_pool_size` 20 của mission-core.

## Kafka và Valkey

Thông lượng Kafka ở mọi hồ sơ thấp hơn nhiều so với năng lực broker, nên số broker được quyết định bởi yêu cầu sẵn sàng và số phân vùng như TL-04 mục 4.6 đã phân tích. Hồ sơ L giữ 6 broker và 3 controller KRaft tách riêng. Hồ sơ M và S dùng 3 broker (một mỗi vùng sự cố) với 3 controller KRaft chạy thành pod riêng trên cùng ba node. Hồ sơ compact dùng 3 node Kafka vai trò kép (broker và controller trong cùng tiến trình, chế độ KRaft combined của Strimzi) vì chỉ có một vùng sự cố và tải rất nhỏ.

| Đại lượng | compact | S | M | L |
|---|---|---|---|---|
| Thông điệp đỉnh mỗi giây (sự kiện cộng CDC) | 375 | 2.250 | 6.000 | 9.000 |
| Lưu lượng ghi đỉnh trước nhân bản | 0,6 MB/s | 3,4 MB/s | 9 MB/s | 13,5 MB/s |
| Dung lượng lưu giữ (5 ngày bình quân, nhân bản 3, nén zstd) | ≈ 0,1 TB | ≈ 0,6 TB | ≈ 1,5 TB | ≈ 2,2 TB |
| Broker / controller | 3 vai trò kép | 3 / 3 | 3 / 3 | 6 / 3 |
| Tài nguyên mỗi broker | 2 vCPU, 8 GiB | 4 vCPU, 16 GiB | 8 vCPU, 32 GiB | 8 vCPU, 32 GiB |
| Phân vùng topic đầu vào tính điểm | 6 | 12 | 48 | 48 |
| Tổng phân vùng của cell (tính cả bản sao) | ≈ 300 | ≈ 600 | ≈ 1.200 | 1.500–2.500 |
| Máy chủ | node dữ liệu compact | 3 × PHY-DATA-S | 3 × PHY-DATA | 6 × PHY-DATA |

Ổ NVMe của broker được chọn dư khoảng 7 đến 10 lần so với dung lượng lưu giữ bình thường để chịu được tình huống một nhóm consumer (ví dụ ClickHouse) dừng nhiều ngày, hoặc phải giữ lâu hơn để phát lại sau sự cố. Valkey Cluster chạy trên cùng node pool với Kafka: 3 shard, mỗi shard một primary và một replica ở vùng khác. Tập dữ liệu nóng tỷ lệ với số người dùng hoạt động trong ngày, ước tính 8–12 GB ở hồ sơ L (TL-04), 6–8 GB ở M, 2–3 GB ở S và dưới 1 GB ở compact. Cấu hình mỗi pod Valkey là 4 vCPU, 16 GiB (`maxmemory 10gb`) ở hồ sơ L, 2 vCPU, 8 GiB (`maxmemory 5gb`) ở M, 1 vCPU, 4 GiB (`maxmemory 2500mb`) ở S và 0,5 vCPU, 2 GiB ở compact; ở mọi hồ sơ, tập nóng chiếm dưới 60% tổng `maxmemory` của cụm.

## OpenSearch và ClickHouse

OpenSearch (SVC-16) và ClickHouse (SVC-15) chạy trên pool data-search. Hai hệ này có yêu cầu bộ nhớ tối thiểu tương đối cao (heap OpenSearch, bộ đệm mark và bộ nhớ truy vấn của ClickHouse), nên ở hồ sơ nhỏ, cấu hình được chọn theo mức tối thiểu để vận hành ổn định chứ không theo tải.

| Đại lượng | compact | S | M | L |
|---|---|---|---|---|
| Dữ liệu OpenSearch chính mỗi năm (GD-18) | ≈ 12 GB | ≈ 75 GB | ≈ 200 GB | ≈ 300 GB |
| Node OpenSearch | 3 vai trò kép, 2 vCPU, 8 GiB, heap 4 GiB | 3 vai trò kép, 4 vCPU, 16 GiB, heap 8 GiB | 3 quản lý (1 vCPU, 2 GiB) và 3 dữ liệu (6 vCPU, 24 GiB, heap 12 GiB) | 3 quản lý (2 vCPU, 4 GiB) và 3 dữ liệu (8 vCPU, 32 GiB, heap 16 GiB) |
| Dữ liệu ClickHouse sau nén mỗi năm (GD-19) | ≈ 12 GB | ≈ 75 GB | ≈ 200 GB | 200–400 GB |
| Node ClickHouse | 1 shard × 2 bản sao, 4 vCPU, 16 GiB | 1 shard × 2 bản sao, 6 vCPU, 24 GiB | 1 shard × 2 bản sao, 12 vCPU, 48 GiB | 2 shard × 2 bản sao, 16 vCPU, 64 GiB |
| ClickHouse Keeper | 3 × (0,5 vCPU, 2 GiB) | 3 × (1 vCPU, 2 GiB) | 3 × (1 vCPU, 2 GiB) | 3 × (2 vCPU, 4 GiB) |
| Máy chủ pool data-search | node dữ liệu compact | 3 × PHY-DATA | 5 × PHY-DATA | 7 × PHY-DATA |

Hồ sơ M dùng một shard ClickHouse thay vì hai vì khối lượng dữ liệu sau ba năm (khoảng 600 GB) nằm gọn trong một node và truy vấn dashboard đi qua lớp tổng hợp trước của Cube. Khi một cell M có nhu cầu phân tích nặng hơn dự kiến (ví dụ bảng điều hành của tỉnh lớn với nhiều drill-down), shard thứ hai được thêm trên hai node PHY-DATA mới; ClickHouse cho phép thêm shard mà không dừng, dữ liệu cũ giữ ở shard đầu và dữ liệu mới phân bố theo khoá phân shard.

## Vùng bảo mật

Pool security-zone chứa ledger-service, esign-service, ai-gateway, `pg-ledger`, `pg-secure` và OpenBao, trên máy chủ có card mạng riêng vào phân đoạn HSM (TL-04 mục 3.3). Bảng dưới đây cộng tài nguyên yêu cầu ở mức đỉnh trên mỗi node, giả định các workload được rải đều trên ba node.

| Thành phần (mỗi node) | S | M | L |
|---|---|---|---|
| ledger-service, esign-service, ai-gateway ở `maxReplicas` | ≈ 3,9 vCPU | ≈ 4,9 vCPU | ≈ 8,4 vCPU |
| Instance `pg-ledger` | 2 vCPU | 4 vCPU | 8 vCPU |
| Instance `pg-secure` | 2 vCPU | 4 vCPU | 8 vCPU |
| OpenBao | 1 vCPU | 1 vCPU | 2 vCPU |
| Tổng trên mỗi node | ≈ 8,9 / 16 vCPU | ≈ 13,9 / 16 vCPU | ≈ 26,4 / 32 vCPU |
| Máy chủ | 3 × PHY-SEC-S | 3 × PHY-SEC-S | 3 × PHY-SEC-L |

Hồ sơ M có mức sử dụng cao nhất (khoảng 87% ở đỉnh) nhưng chấp nhận được vì đỉnh của ba service không trùng nhau: esign-service đạt đỉnh trong đợt ký lô cuối kỳ (thường ngoài giờ hành chính theo lịch của saga khoá kỳ), còn ai-gateway đạt đỉnh trong giờ làm việc. Nếu theo dõi vận hành cho thấy hai đỉnh trùng nhau, ai-gateway có thể chuyển khỏi vùng bảo mật sang một node riêng có nhãn bảo mật tương đương, mà không phải thay máy. Ở hồ sơ compact, các thành phần vùng bảo mật chạy trên ba node dữ liệu; đây là ngoại lệ được chấp nhận và có biện pháp bù trình bày ở mục 9.2.

HSM không thuộc cụm Kubernetes và được chia sẻ theo nhóm cell như TL-04 mô tả. Số lượng tối thiểu là một cặp HSM (hoạt động và dự phòng) ở mỗi TTDL cho các cell SaaS, một cặp riêng cho CELL-DTW ở mỗi địa điểm của khối Đảng, và một cặp cho mỗi bản cài đám mây riêng hoặc cách ly. Tải lên HSM rất thấp, vì HSM chỉ dùng để mở DEK (dưới một lần mỗi 15 phút mỗi tenant mỗi pod theo G3) và cho các phép ký của ledger-service (mỗi cell mỗi giờ một chữ ký niêm phong) và esign-service (ký bằng khoá của hệ thống, không phải chữ ký cá nhân từ xa).

## GPU cho trợ lý AI

Định cỡ GPU giữ nguyên phương pháp của TL-04 mục 4.9: trợ lý AI chỉ đọc, không nằm trên đường găng, được định cỡ theo mức sử dụng dự kiến (GD-21) chứ không theo đỉnh của hệ thống, và tạm ngừng khi chạy ở TTDL dự phòng.

| Đại lượng | compact | S | M | L |
|---|---|---|---|---|
| Phiên trợ lý đồng thời (1% CCU) | 25 | 150 | 400 | 600 |
| Yêu cầu mỗi giây | 0,4 | 2,5 | 6,7 | 10 |
| Token đầu ra mỗi giây | 125 | 750 | 2.000 | 3.000 |
| GPU cho mô hình 30 tỷ tham số FP8 (tải cộng dự phòng) | 1 + 1 | 1 + 1 | 2 + 1 | 2 + 2 |
| GPU cho mô hình 120 tỷ tham số (song song tensor) | không | không | không | 4 |
| OCR | chia GPU (MIG) | chia GPU (MIG) | chia GPU (MIG) | chia GPU (MIG) |
| Máy chủ | tuỳ chọn: một máy 2 GPU nếu bật AI | 1 × PHY-GPU4 | 1 × PHY-GPU4 | 1 × PHY-GPU8 |

Hồ sơ compact mặc định tắt trợ lý AI bằng cờ tính năng. Với bản cài cách ly có yêu cầu AI chạy tại chỗ, một máy chủ hai GPU 80 GB là đủ cho mô hình 30 tỷ tham số và OCR ở quy mô 50.000 tài khoản. Hồ sơ S được giữ 4 GPU như TL-04 đã định cỡ cho CELL-DTW, vì khối Đảng có nhu cầu dùng OCR cho văn bản giấy cao hơn bình quân; một bản cài S cho bộ, ngành không có nhu cầu này có thể giảm xuống máy 2 GPU.

# Định cỡ lưu trữ đối tượng, sao lưu, quan sát và mạng

## Kho đối tượng cho minh chứng

Minh chứng là khối dữ liệu lớn nhất và tăng đều theo thời gian, vì theo ADR-13 nó được lưu ở chế độ Object Lock compliance và không xoá trước hạn giữ (10 năm đối với minh chứng gắn với hồ sơ đánh giá). Dung lượng minh chứng gốc mỗi năm bằng số tài khoản nhân 4 quý, 8 tệp mỗi quý và 1 MB mỗi tệp (GD-17), tức 32 MB mỗi người mỗi năm trước khử trùng. Với erasure coding 4+2, dung lượng thô gấp 1,5 lần. Dung lượng cần lắp được tính theo GD-26: năm đầu lắp 1,5 lần dung lượng thô của minh chứng năm đầu (phần dư dành cho ngưỡng đầy 75%, pool RBD, sản phẩm sinh ra như phiếu PDF/A và kho log, trace của ngăn xếp quan sát), và sau ba năm cần khoảng 4 lần.

| Hồ sơ hoặc cụm | Tài khoản | Minh chứng gốc mỗi năm | Thô năm 1 (EC 4+2) | Lắp năm 1 | Ước tính sau 3 năm | Cách triển khai |
|---|---|---|---|---|---|---|
| compact | 50.000 | 1,6 TB | 2,4 TB (bản sao 3: 4,8 TB) | 12 TB thô | ≈ 20 TB | Rook-Ceph trên 3 node dữ liệu, bản sao 3, mỗi node 2 × 8 TB HDD |
| S, đám mây riêng | 300.000 | 9,6 TB | 14,4 TB (bản sao 3: 29 TB) | ≈ 45 TB thô | ≈ 140 TB thô | Rook-Ceph trên 4 máy PHY-CEPH, bản sao 3, lắp 4 × 20 TB mỗi máy rồi bổ sung theo năm |
| M, đám mây riêng | 800.000 | 25,6 TB | 38,4 TB | ≈ 58 TB | ≈ 155 TB | Ceph do cephadm vận hành, tối thiểu 8 máy PHY-CEPH |
| `ceph-dtw` (CELL-DTW) | 300.000 | 9,6 TB | 14,4 TB | ≈ 30 TB | ≈ 60 TB | Như TL-04 |
| `ceph-hl-1` ở QM-4 (CELL-HCM) | 1.200.000 | 38,4 TB | 57,6 TB | ≈ 86 TB | ≈ 230 TB | Cụm do cephadm vận hành |
| `ceph-hl-1` ở QM-5 (HCM, HN) | 2.200.000 | 70 TB | 106 TB | ≈ 160 TB | ≈ 420 TB | Như TL-04 |
| `ceph-hl-2` ở QM-3 (CELL-G4) | 800.000 | 25,6 TB | 38,4 TB | ≈ 58 TB | ≈ 155 TB | Cụm khởi đầu của nhóm G |
| `ceph-hl-2` ở QM-4 (G1, G4) | 1.600.000 | 51 TB | 77 TB | ≈ 115 TB | ≈ 307 TB | |
| `ceph-hl-2` ở QM-5 (G1 đến G4, SH) | 4.000.000 | 128 TB | 194 TB | ≈ 290 TB | ≈ 780 TB | Như TL-04 |

Số máy chủ lưu trữ không tỷ lệ tuyến tính với dung lượng. TL-04 mục 4.8 quy định mỗi cụm Ceph lưu minh chứng có tối thiểu 8 máy chủ trên ba vùng sự cố, để erasure coding 4+2 với miền lỗi là host vẫn ghi được khi một host đang bảo trì và một host khác hỏng. Vì vậy ở QM-3, cụm `ceph-hl-2` chỉ cần khoảng 58 TB nhưng vẫn phải có 8 máy; giải pháp kinh tế là lắp 2 ổ 20 TB mỗi máy (320 TB thô, khoảng 160 TB dùng được ở ngưỡng 75%) và bổ sung ổ theo quý khi các cell nhóm G khác gia nhập. Một máy PHY-CEPH có 12 khe, nên một cụm 8 máy lắp đầy đạt 1,9 PB thô, đủ cho cả nhóm G đến hết năm thứ bảy mà không thêm máy.

Ở hồ sơ compact và S chạy đám mây riêng, erasure coding không còn kinh tế vì cần ít nhất 6 host; TL-05 chọn bản sao 3 trên Rook-Ceph. Bản sao 3 tốn gấp đôi dung lượng thô so với erasure coding 4+2 cho cùng dữ liệu, nhưng ở quy mô vài chục terabyte, chi phí thêm vài ổ đĩa thấp hơn chi phí thêm hai máy chủ. Khách hàng đám mây riêng có sẵn kho S3 tương thích hỗ trợ Object Lock có thể dùng kho đó thay Rook-Ceph (TL-04 mục 2.3), khi đó dung lượng ở bảng trên là dung lượng khách hàng phải cấp.

Hệ số khử trùng theo SHA-256 chưa được tính vào bảng, đúng như TL-04. Nếu đo được tỷ lệ trùng 10–20% sau năm đầu, kế hoạch bổ sung ổ của các năm sau giảm tương ứng.

## Kho sao lưu

Kho sao lưu phía Nam `ceph-sn-bk` là cụm Ceph riêng, miền quản trị riêng (TL-04 mục 3.8, chương 8). Nó nhận bản sao lưu cơ sở và WAL của CNPG (Barman Cloud), ảnh chụp OpenSearch và ClickHouse, sao lưu Velero, bản xuất sổ cái và bản sao lưu bất biến của minh chứng theo nguyên tắc 3-2-1-1-0. TL-04 định cỡ kho này khoảng 200 TB lắp năm đầu và 400 TB sau ba năm cho 6,5 triệu tài khoản, tức khoảng 31 TB năm đầu cho mỗi triệu tài khoản. TL-05 dùng tỷ lệ này cho các bậc nhỏ hơn.

| Bậc | Tài khoản | Lắp năm 1 | Sau 3 năm | Máy chủ |
|---|---|---|---|---|
| QM-1 | 50.000 | ≈ 2 TB | ≈ 4 TB | Một bucket có Object Lock trên `ceph-sn-bk` hiện có, hoặc kho S3 tương thích ở địa điểm thứ hai |
| QM-2 | 300.000 | ≈ 10 TB | ≈ 20 TB | Một bucket trên `ceph-sn-bk` (SaaS) hoặc 4 máy PHY-CEPH ở địa điểm thứ hai (đám mây riêng) |
| QM-3 | 800.000 | ≈ 25 TB | ≈ 50 TB | 6 máy PHY-CEPH, mỗi máy 2 × 20 TB |
| QM-4 | 3.100.000 | ≈ 96 TB | ≈ 190 TB | 8 máy PHY-CEPH, mỗi máy 4 × 20 TB |
| QM-5 | 6.500.000 | ≈ 200 TB | ≈ 400 TB | 8 máy PHY-CEPH, mỗi máy 6 × 20 TB, bổ sung ổ theo năm |

## Ngăn xếp quan sát

Ngăn xếp quan sát (TL-04 chương 10) có thành phần thu thập chạy trong cell (OTel Collector, Fluent Bit, Prometheus) và thành phần lưu trữ, truy vấn chạy ở cụm quản trị (Loki, Tempo, Thanos, Grafana, Alertmanager). Dữ liệu được lưu trên Ceph RGW dưới dạng đối tượng. Khối lượng phụ thuộc số request mỗi ngày (GD-22), khối lượng log và trace mỗi request (GD-20) và số chuỗi metric (GD-24). Thời gian giữ theo TL-04: Loki 90 ngày nóng và 1 năm lạnh, Tempo 14 ngày, Thanos dữ liệu thô 30 ngày, lấy mẫu 5 phút 180 ngày, lấy mẫu 1 giờ 2 năm. Nhật ký kiểm toán 10 năm đi theo luồng riêng `sys.audit.v1` sang kho Object Lock và SIEM, không tính ở đây.

| Bậc | Log thô mỗi ngày | Loki lưu 90 ngày (sau nén) | Loki lưu lạnh 1 năm | Tempo 14 ngày | Chuỗi metric | Thanos (thô, 5 phút, 1 giờ) | Tổng trên Ceph |
|---|---|---|---|---|---|---|---|
| QM-1 | ≈ 6 GB | ≈ 0,05 TB | ≈ 0,2 TB | ≈ 0,02 TB | ≈ 0,3 triệu | ≈ 0,2 TB | ≈ 0,5 TB |
| QM-2 | ≈ 39 GB | ≈ 0,35 TB | ≈ 1,4 TB | ≈ 0,1 TB | ≈ 0,8 triệu | ≈ 0,5 TB | ≈ 2,4 TB |
| QM-3 | ≈ 104 GB | ≈ 0,9 TB | ≈ 3,8 TB | ≈ 0,3 TB | ≈ 1,4 triệu | ≈ 0,9 TB | ≈ 6 TB |
| QM-4 | ≈ 400 GB | ≈ 3,6 TB | ≈ 14,7 TB | ≈ 1,1 TB | ≈ 4,7 triệu | ≈ 3,1 TB | ≈ 23 TB |
| QM-5 | ≈ 845 GB | ≈ 7,6 TB | ≈ 30,8 TB | ≈ 2,4 TB | ≈ 9,6 triệu | ≈ 6,2 TB | ≈ 47 TB |

Cách tính dòng QM-5 làm ví dụ. Lưu lượng bình quân ngày bằng 25% đỉnh đột biến (GD-22); cộng theo cell, tám cell sinh khoảng 700 triệu request mỗi ngày. Mỗi request để lại khoảng 1,2 KB log thô qua bốn tầng (APISIX, service, truy vấn chậm, tác vụ nền), tức 845 GB mỗi ngày; Loki nén khoảng 10 lần nên lưu 84,5 GB mỗi ngày, 90 ngày là 7,6 TB. Trace được giữ cho 12% request (100% lỗi, 100% chậm, 10% bình thường theo tail sampling của TL-04), mỗi trace khoảng 6 KB, tức khoảng 500 GB mỗi ngày trước nén, lưu khoảng 170 GB mỗi ngày sau nén, 14 ngày là 2,4 TB. Metric được tính với 9,6 triệu chuỗi hoạt động (tám cell cộng cụm dùng chung), mỗi chuỗi lấy mẫu 30 giây, khoảng 650 GB cho mỗi triệu chuỗi qua ba mức lưu trữ của Thanos. Tổng khoảng 47 TB nằm trong phần "khối quan sát" đã được tính vào dung lượng lắp năm đầu của `ceph-hl-1` và `ceph-hl-2`.

## Mạng

Năng lực mạng được định cỡ cho ba luồng: lưu lượng Internet vào cell, lưu lượng tài nguyên tĩnh qua CDN và lưu lượng nhân bản giữa hai TTDL. Các giá trị dưới đây là ước tính ở giờ đỉnh đột biến.

| Luồng | compact | S | M | L | Cách tính |
|---|---|---|---|---|---|
| API qua APISIX (phản hồi bình quân 8 KB sau nén Brotli) | ≈ 16 Mbps | ≈ 100 Mbps | ≈ 260 Mbps | ≈ 400 Mbps | RPS đỉnh × 8 KB |
| Tải lên minh chứng | ≈ 11 Mbps | ≈ 66 Mbps | ≈ 180 Mbps | ≈ 270 Mbps | Tệp đỉnh mỗi giây × 1 MB |
| Tổng vào cell (làm tròn, chưa tính SSE) | ≈ 30 Mbps | ≈ 0,2 Gbps | ≈ 0,5 Gbps | ≈ 0,7 Gbps | |
| Nhân bản sang TTDL phía Nam (WAL, MirrorMaker 2, RGW multisite) | ≈ 15 Mbps | ≈ 0,11 Gbps | ≈ 0,3 Gbps | ≈ 0,46 Gbps | WAL đỉnh, Kafka đỉnh, minh chứng đỉnh |

Tài nguyên tĩnh (shell Angular, remote Native Federation, UI Pack) đi qua CDN và không tính vào lưu lượng của cell. Ở QM-5, đỉnh đăng nhập toàn quốc khoảng 540 lượt mỗi giây; nếu 30% lượt đăng nhập không có bộ nhớ đệm trình duyệt và tải khoảng 1 MB tài nguyên, CDN phát khoảng 1,3 Gbps ở đỉnh, phần lớn từ nút biên của CDN, origin của vùng điều phối toàn cục chỉ nhận phần trượt bộ nhớ đệm.

Kênh liên TTDL giữa Hoà Lạc và phía Nam cần chịu tổng luồng nhân bản của mọi cell cộng luồng sao lưu sang `ceph-sn-bk`. Luồng nhân bản ở QM-5 khoảng 2,5 Gbps đỉnh (2 cell L, 5 cell M, 1 cell S). Luồng sao lưu cơ sở hằng tuần của CNPG lớn hơn theo đợt: sao lưu cơ sở 6 TB của `pg-core` hạng L trong cửa sổ 6 giờ cần khoảng 2,2 Gbps, nên các cửa sổ sao lưu cơ sở của các cell được xếp lệch nhau (OPS-18). TL-05 khuyến nghị kênh liên TTDL tối thiểu 2 × 10 Gbps từ QM-3 (một kênh hoạt động đủ cho mọi luồng, kênh thứ hai dự phòng đường truyền), và nâng lên 2 × 25 Gbps ở QM-5 để thời gian đồng bộ lại toàn bộ một cell sau sự cố kéo dài (khoảng 20 TB dữ liệu CSDL và Kafka của một cell L) không vượt 3 giờ. Ở QM-1 và QM-2, kênh 1 Gbps đủ cho nhân bản và sao lưu.

Mạng nội bộ cell: node dữ liệu dùng tối thiểu 2 × 25 Gbps (LACP), node Ceph dùng 2 × 25 Gbps tách mạng công khai và mạng nhân bản của Ceph, node ứng dụng trên Viettel Cloud dùng card ảo 10 Gbps trở lên, riêng pool ingress hạng M và L dùng 25 Gbps như TL-04.

# Thành phần dùng chung và môi trường phi sản xuất

## Vùng điều phối toàn cục và cụm quản trị

Vùng điều phối toàn cục (`global-hl`, `global-sn`) và cụm quản trị (`mgmt-hl`, `mgmt-sn`) phục vụ mọi cell, nên kích thước của chúng tăng theo số cell và theo khối lượng quan sát, không theo số tài khoản của từng cell. Ở các bậc nhỏ, hai cụm này được gộp để tránh vận hành nhiều control plane cho một lượng tải rất nhỏ.

| Bậc | Cụm quản trị (mỗi TTDL) | Vùng điều phối toàn cục (mỗi TTDL) | Tổng mỗi TTDL |
|---|---|---|---|
| QM-1 | 3 × VM-16-64, gộp cả vùng điều phối rút gọn (SVC-03, kho Policy Pack, UI Pack); dùng lại cụm quản trị dựng ở đợt 0b | gộp vào cụm quản trị | 3 node, 48 vCPU, 192 GiB; chỉ ở Hoà Lạc |
| QM-2 | 3 × VM-32-128, gộp vùng điều phối rút gọn | gộp vào cụm quản trị | 3 node, 96 vCPU, 384 GiB |
| QM-3 | 4 × VM-32-128 | 3 × VM-16-64 | 7 node, 176 vCPU, 704 GiB |
| QM-4 | 7 × VM-32-128 | 4 × VM-24-96 | 11 node, 320 vCPU, 1.280 GiB |
| QM-5 | 11 × VM-32-128 | 7 × VM-24-96 | 18 node, 520 vCPU, 2.080 GiB |

Dòng QM-5 khớp với TL-04 mục 4.4 (18 node, 520 vCPU, khoảng 2 TiB mỗi TTDL cho hai cụm). Thành phần tốn tài nguyên nhất của cụm quản trị là Loki và Thanos: ở QM-5, Loki cần khoảng 40 vCPU cho distributor, ingester và querier ở đỉnh truy vấn khi sự cố (lưu lượng ghi bình quân khoảng 10 MB/s, đỉnh khoảng 40 MB/s), Thanos Store và Query cần khoảng 64 vCPU và 256 GiB để truy vấn 9,6 triệu chuỗi trong dashboard toàn quốc, Tempo khoảng 24 vCPU, Harbor và Argo CD khoảng 32 vCPU. Vùng điều phối toàn cục chứa APISIX lớp ngoài, PowerDNS, SVC-03 với cụm PostgreSQL `registry` sao chép đồng bộ chéo TTDL, origin của CDN, kho gốc Policy Pack và UI Pack, và kho phân tích quốc gia ClickHouse nhận dữ liệu tổng hợp qua MirrorMaker 2; kho phân tích quốc gia là phần tăng theo số cell rõ nhất.

Từ QM-3 trở đi, vùng điều phối toàn cục chạy chủ động ở cả hai TTDL (NFR-04 yêu cầu 99,95%), nên kích thước ở TTDL phía Nam bằng Hoà Lạc. Cụm quản trị phía Nam cũng có kích thước bằng Hoà Lạc vì nó phải tiếp quản Argo CD, Harbor và quan sát khi chuyển vùng toàn phần.

CELL-DTW có cụm quản trị riêng của khối Đảng (TL-04 mục 2.5). Cụm này dùng cấu hình 3 × VM-16-64 mỗi địa điểm và được ghi thành một khoản riêng ở mục 8.2.

## Môi trường phi sản xuất

Ba môi trường phi sản xuất có ảnh hưởng đáng kể tới năng lực cần mua sắm.

**Staging.** Staging chạy cùng chart, cùng phiên bản với sản xuất và là điểm dừng bắt buộc của mọi bản phát hành (TL-04 mục 11.2). TL-05 khuyến nghị staging dùng hồ sơ S ở mức `minReplicas`, không có GPU (trợ lý AI dùng mô hình nhỏ trên CPU hoặc gọi GPU của `CELL-PERF` khi có), tầng dữ liệu chạy trên máy ảo thay vì máy vật lý vì staging không đo hiệu năng. Cỡ khoảng 22 node, 330 vCPU, 1,4 TiB. Staging cần có từ QM-1 và giữ nguyên kích thước ở mọi bậc.

**Cell thử tải `CELL-PERF`.** TL-04 mục 7.3 quy định `CELL-PERF` dùng hồ sơ L trên phần cứng cùng loại với CELL-HCM, vì kết quả đo chỉ có giá trị khi phần cứng giống sản xuất, đặc biệt ở tầng dữ liệu. Vì vậy tầng dữ liệu của `CELL-PERF` (6 máy CSDL, 13 máy PHY-DATA, 3 máy vùng bảo mật) là khoản mua sắm cố định. Tầng ứng dụng chỉ cần dựng khi chạy chiến dịch thử tải (trước mỗi đợt mở rộng và mỗi quý một lần), nên dùng năng lực dự trữ trên Viettel Cloud thay vì giữ thường trực. Một cách tiết kiệm được TL-05 đề xuất là ở QM-1 đến QM-3, `CELL-PERF` dùng hồ sơ M (khớp với cell lớn nhất đang vận hành), và chỉ nâng lên hồ sơ L từ trước khi CELL-HCM vào vận hành ở QM-4.

**Cụm sinh tải `perf-gen`.** Kịch bản PERF-03 cho một cell hạng L cần mô phỏng 60.000 người dùng ảo ở 6.000 RPS. Với k6 chạy kịch bản HTTP có kiểm tra phản hồi, một vCPU sinh được khoảng 400–500 người dùng ảo ở thời gian suy nghĩ 20 giây; cần khoảng 150 vCPU, tức 10 máy ảo VM-16-32. Cụm này chỉ tồn tại trong thời gian chạy thử và dùng năng lực dự trữ.

| Môi trường | Bậc cần có | Thường trực | Theo chiến dịch |
|---|---|---|---|
| Staging (hồ sơ S rút gọn) | Từ QM-1 | ≈ 22 node, 330 vCPU, 1,4 TiB | |
| `CELL-PERF` tầng dữ liệu | Từ QM-3 (hồ sơ M), nâng L trước QM-4 | 14 máy vật lý (M) hoặc 22 máy (L) | |
| `CELL-PERF` tầng ứng dụng | Từ QM-3 | | 31 đến 41 node VM theo hồ sơ |
| `perf-gen` | Từ QM-3 | | ≈ 10 × VM-16-32 |

# Tổng hợp tài nguyên theo bậc quy mô

## Tổng hợp theo hồ sơ cell

Bảng dưới đây cộng toàn bộ node pool của một cell ở TTDL chính, và phần bản sao ấm của cell đó ở TTDL dự phòng. Ở TTDL dự phòng, theo TL-04 mục 2.5, tầng dữ liệu và vùng bảo mật chạy đủ kích thước để nhận dữ liệu liên tục, pool system và ingress giữ ba node, pool app-general giữ hai node và pool app-worker giữ một node để mỗi Deployment có một replica, còn GPU không được giữ dự phòng.

| Hồ sơ | Node thường / đỉnh | vCPU thường / đỉnh | RAM đỉnh | NVMe thô | GPU | Bản sao ấm phía Nam: node / vCPU / RAM |
|---|---|---|---|---|---|---|
| L (CELL-HCM) | 41 / 64 | 872 / 1.240 | 6,0 TiB | 180 TB | 8 | 31 / 664 / 3,1 TiB |
| L (CELL-HN) | 40 / 60 | 856 / 1.176 | 5,7 TiB | 180 TB | 8 | 31 / 664 / 3,1 TiB |
| M | 30 / 46 | 568 / 824 | 4,0 TiB | 119 TB | 4 | 23 / 440 / 2,2 TiB |
| S | 25 / 30 | 392 / 472 | 2,2 TiB | 69 TB | 4 | 21 / 312 / 1,2 TiB |
| compact | 6 / 6 | 192 / 192 | 0,75 TiB | 23 TB NVMe và 48 TB HDD | 0 (tuỳ chọn 2) | không có bản sao ấm |

Cột vCPU đỉnh của hồ sơ L khớp TL-04 mục 4.4. Hồ sơ M và S có số node và vCPU thấp hơn bảng sơ bộ của TL-04 (M 33 / 49 node, 640 / 896 vCPU; S 24 / 31 node, 352 / 464 vCPU) vì TL-05 tính lại từ số replica cụ thể và gộp các cụm PostgreSQL lên ba máy chủ ở hồ sơ M. Bảng của TL-04 mục 4.4 được cập nhật theo TL-05 trong cùng đợt phát hành tài liệu này.

Hai nhận xét rút ra từ bảng. Thứ nhất, tài nguyên không tăng tuyến tính theo số tài khoản: hồ sơ L phục vụ gấp 4 lần tài khoản của hồ sơ S nhưng chỉ cần khoảng 2,6 lần vCPU đỉnh. Nguyên nhân là các hồ sơ nhỏ phải trả "chi phí cố định" của một cell đầy đủ (ba bản sao cho mọi hệ dữ liệu, pool system, vùng bảo mật, GPU). Đây là lý do không nên chia nhỏ cell quá mức cần thiết cho cô lập, và là lý do mô hình pool (CELL-SH) tồn tại: hàng nghìn tenant nhỏ dùng chung một cell M thay vì mỗi tenant một cell. Thứ hai, ở mọi hồ sơ, phần co giãn được (chênh lệch giữa thường và đỉnh) chỉ chiếm 15–30% tổng vCPU, vì tầng dữ liệu chiếm phần lớn và không co giãn. Khoản tiết kiệm từ co giãn tự động vì vậy chủ yếu nằm ở tầng ứng dụng trên Viettel Cloud.

## Tổng hợp theo bậc quy mô

Bảng tổng hợp cộng các cell của từng bậc với cụm quản trị và vùng điều phối toàn cục (mục 7.1). Toàn bộ là ước tính; số node và vCPU không gồm control plane Kubernetes (3 máy ảo VM-8-16 mỗi cụm) và không gồm môi trường phi sản xuất (mục 7.2).

| Bậc | Cụm Kubernetes sản xuất | TTDL chính: node thường / đỉnh | TTDL chính: vCPU thường / đỉnh | TTDL chính: RAM đỉnh | NVMe thô TTDL chính | GPU | TTDL dự phòng: node / vCPU / RAM |
|---|---|---|---|---|---|---|---|
| QM-1 | 2 (1 cell, 1 quản trị) | 9 / 9 | 240 / 240 | 0,9 TiB | 23 TB | 0 | không có, chỉ sao lưu |
| QM-2 | 2 mỗi địa điểm | 28 / 33 | 488 / 568 | 2,5 TiB | 69 TB | 4 | 24 / 408 / 1,5 TiB |
| QM-3 | 3 mỗi TTDL (cell, quản trị, điều phối) | 37 / 53 | 744 / 1.000 | 4,7 TiB | 119 TB | 4 | 30 / 616 / 2,9 TiB |
| QM-4 | 6 mỗi TTDL | 137 / 197 | 2.720 / 3.680 | 17,5 TiB | 488 TB | 20 | 109 / 2.176 / 9,9 TiB |
| QM-5 | 10 mỗi TTDL (20 tổng, như TL-04) | 274 / 402 | 5.480 / 7.528 | 36 TiB | 1.025 TB | 40 | 216 / 4.360 / 20,3 TiB |

Số liệu QM-4 và QM-5 đã gồm CELL-DTW, cell này chạy trên hạ tầng riêng của khối Đảng ở hai địa điểm do khối Đảng quản lý; ngoài ra CELL-DTW có cụm quản trị riêng 3 × VM-16-64 (48 vCPU, 192 GiB) ở mỗi địa điểm, không cộng vào bảng. Khi lập dự toán cho hạ tầng Viettel, cần trừ phần của CELL-DTW (25 / 30 node, 392 / 472 vCPU ở TTDL chính).

Bảng cho thấy bước nhảy lớn nhất về tài nguyên là từ QM-3 lên QM-4 (gấp 3,7 lần vCPU đỉnh), lớn hơn tỷ lệ tăng tài khoản (3,9 lần) không nhiều. Bước từ QM-4 lên QM-5 gấp đôi cả tài khoản lẫn tài nguyên. Điều này phù hợp với tính chất của kiến trúc cell: sau khi trả chi phí cố định của vùng điều phối và cụm quản trị ở QM-3, tài nguyên tăng gần tuyến tính theo số cell.

## Năng lực dự trữ cho chuyển vùng

Ở TTDL phía Nam, tầng ứng dụng của mỗi cell chỉ chạy tối thiểu. Khi chuyển vùng một cell (DR-10), tầng ứng dụng phải tăng lên mức cần thiết trong vòng RTO 4 giờ, nên cần hợp đồng giữ chỗ năng lực máy ảo trên Viettel Cloud phía Nam. TL-04 mục 4.4 đã đề xuất 2.000 vCPU cho QM-5. TL-05 tính cho các bậc khác theo cùng nguyên tắc: đủ để chuyển cell lớn nhất ở mức đỉnh, hoặc mọi cell ở mức thường.

| Bậc | Năng lực giữ chỗ phía Nam | Căn cứ |
|---|---|---|
| QM-1 | không có | Thí điểm chấp nhận RTO khi mất TTDL là 48 giờ, khôi phục từ sao lưu |
| QM-2 | ≈ 200 vCPU | Pool ứng dụng hồ sơ S ở đỉnh trừ phần đã chạy ấm |
| QM-3 | ≈ 400 vCPU | Pool ứng dụng hồ sơ M ở đỉnh (ingress 4 node, app 24 node) trừ phần đã chạy ấm |
| QM-4 | ≈ 1.000 vCPU | Một cell L ở đỉnh (khoảng 510 vCPU) cộng các cell còn lại ở mức thường |
| QM-5 | ≈ 2.000 vCPU | Như TL-04: hai cell L ở đỉnh hoặc mọi cell ở mức thường |

## Bảng khối lượng máy chủ

Bảng khối lượng quy toàn bộ tài nguyên của từng bậc về danh mục máy chủ chuẩn ở mục 3.3, để bộ phận dự toán nhân với đơn giá. Số máy ảo ghi ở mức đỉnh (mức mà hợp đồng Viettel Cloud cần đáp ứng) và mức thường trong ngoặc. Máy chủ vật lý ghi theo TTDL chính và TTDL dự phòng.

| Hạng mục | QM-1 | QM-2 | QM-3 | QM-4 | QM-5 |
|---|---|---|---|---|---|
| VM-8-32 (system) | 0 | 3 | 3 | 12 | 24 |
| VM-8-16 (ingress hồ sơ S) | 0 | 3 | 0 | 3 | 3 |
| VM-16-32 (ingress M, L) | 0 | 0 | 4 (3) | 14 (9) | 32 (21) |
| VM-16-64 (app-general, app-worker, điều phối QM-3) | 3 (quản trị) | 11 (6) | 27 (12) | 91 (36) | 191 (74) |
| VM-24-96 (vùng điều phối toàn cục) | 0 | 0 | 0 | 4 | 7 |
| VM-32-128 (hội tụ compact, cụm quản trị) | 3 | 3 | 4 | 7 | 11 |
| PHY-DB-L | 0 | 0 | 3 + 3 | 9 + 9 | 21 + 21 |
| PHY-DB-S | 3 + 0 | 3 + 3 | 0 | 6 + 6 | 9 + 9 |
| PHY-DATA | 0 | 3 + 3 | 8 + 8 | 32 + 32 | 69 + 69 |
| PHY-DATA-S | 0 | 3 + 3 | 0 | 3 + 3 | 3 + 3 |
| PHY-SEC-L | 0 | 0 | 0 | 3 + 3 | 6 + 6 |
| PHY-SEC-S | 0 | 3 + 3 | 3 + 3 | 9 + 9 | 18 + 18 |
| PHY-GPU8 | 0 | 0 | 0 | 1 | 2 |
| PHY-GPU4 | 0 | 1 | 1 | 3 | 6 |
| Tổng máy chủ vật lý tính toán (chính + dự phòng) | 3 | 13 + 12 | 15 + 14 | 66 + 62 | 134 + 126 |
| PHY-CEPH (minh chứng chính + bản sao RGW + sao lưu) | Rook trên node dữ liệu | 4 + 4 + (4 hoặc kho sẵn có) | 8 + 8 + 6 | 24 + 24 + 8 | 24 + 24 + 8 |
| Ổ HDD 20 TB lắp năm đầu (ước tính) | 6 ổ 8 TB | ≈ 40 | ≈ 44 | ≈ 128 | ≈ 176 |
| HSM (cặp) | 1 | 1 mỗi địa điểm | 1 mỗi TTDL | 1 mỗi TTDL + 1 mỗi địa điểm DTW | 1 mỗi TTDL + 1 mỗi địa điểm DTW |
| Kênh liên TTDL | 1 Gbps | 1 Gbps | 2 × 10 Gbps | 2 × 10 Gbps | 2 × 25 Gbps |
| Giữ chỗ năng lực phía Nam | không | ≈ 200 vCPU | ≈ 400 vCPU | ≈ 1.000 vCPU | ≈ 2.000 vCPU |

Máy ảo ở TTDL dự phòng không ghi riêng trong bảng vì chúng nằm trong số node "TTDL dự phòng" ở mục 8.2 (mỗi cell 3 VM-8-32, 3 VM ingress, 3 VM-16-64, cộng cụm quản trị và điều phối bằng TTDL chính). Ước tính ổ HDD năm đầu được tính từ dung lượng lắp năm đầu của các cụm Ceph (minh chứng, bản sao RGW phía Nam, kho sao lưu) chia 20 TB và làm tròn lên theo số máy, tối thiểu 2 ổ mỗi máy.

## Mốc mua sắm theo lộ trình

Máy chủ vật lý, đặc biệt máy GPU và máy lưu trữ, có thời gian giao hàng dài, thường 8 đến 16 tuần. Vì vậy bậc quy mô phải được đặt hàng trước khi đợt triển khai bắt đầu, và mỗi đợt nên mua đúng bậc của đợt sau chứ không mua đón đầu nhiều bậc, vì giả định định cỡ sẽ được hiệu chỉnh bằng số đo ở mỗi bậc.

| Mốc | Bậc cần sẵn sàng | Hạng mục mua sắm chính | Thời điểm đặt hàng khuyến nghị |
|---|---|---|---|
| Thí điểm nội bộ | QM-1 | 3 PHY-DB-S, máy ảo | Ngay sau đợt 0b |
| Đợt 1 (Q4/2026 đến Q1/2027) | QM-3 | 15 + 14 máy tính toán, 22 PHY-CEPH, HSM hai TTDL, kênh liên TTDL, tầng dữ liệu `CELL-PERF` hồ sơ M | Trước khi đợt 1 bắt đầu ít nhất 12 tuần |
| Đợt 2 (Q1 đến Q2/2027) | QM-3 (giữ nguyên) | Bổ sung ổ Ceph theo mức đầy; chạy chương trình PERF đầy đủ để hiệu chỉnh GD-xx | Theo NG-07 |
| Đầu đợt 3 (từ Q3/2027) | QM-4 | 51 + 48 máy tính toán, 34 PHY-CEPH, nâng `CELL-PERF` lên hồ sơ L | Cuối đợt 2, sau khi PERF-03 và PERF-11 đạt trên hồ sơ M |
| Cuối đợt 3 | QM-5 | 68 + 64 máy tính toán, bổ sung ổ Ceph, nâng kênh liên TTDL | Khi QM-4 đã chạy qua một kỳ đánh giá quý và số đo khớp bảng định cỡ trong sai số 20% |

Điều kiện "số đo khớp trong sai số 20%" ở dòng cuối là cổng quyết định quan trọng: nếu số đo thực tế của QM-4 lệch nhiều so với giả định (ví dụ tỷ lệ CCU thật là 8% thay vì 5%), bảng định cỡ được tính lại theo chương 10 trước khi đặt hàng QM-5, tránh mua thừa hoặc thiếu ở bậc tốn kém nhất.

# Bản cài đám mây riêng và bản cài cách ly

## Lựa chọn hồ sơ cho bản cài đơn cell

Bản cài đám mây riêng và bản cài cách ly đều là một cell đơn, nhưng khác bản SaaS ở ba điểm ảnh hưởng tới định cỡ. Thứ nhất, cell phải tự chứa cụm quản trị và vùng điều phối rút gọn, vì không dùng chung với hệ SaaS. Thứ hai, khách hàng có thể chỉ có một địa điểm, khi đó không có bản sao ấm và RTO khi mất địa điểm phụ thuộc vào sao lưu ngoài địa điểm. Thứ ba, hạ tầng thường do khách hàng cung cấp, nên nhiều khi không có sự phân biệt máy ảo co giãn và máy vật lý dữ liệu.

| Quy mô khách hàng | Hồ sơ | Hạ tầng tối thiểu tại một địa điểm | Ghi chú |
|---|---|---|---|
| Đến 10.000 tài khoản (đơn vị, trường, bệnh viện nhỏ) | `compact` mức tối thiểu | 3 máy 32 vCPU, 192 GiB, 4 × 1,92 TB NVMe, 2 × 8 TB HDD; mọi thành phần chạy hội tụ trên 3 máy | Control plane chạy chung trên 3 máy; không có trợ lý AI |
| 10.000 đến 50.000 | `compact` | 3 VM-32-128 cộng 3 PHY-DB-S có HDD, cộng 3 VM-16-64 cho quản trị | Như mục 8.1 |
| 50.000 đến 300.000 (bộ, tỉnh nhỏ) | `S` | Như hồ sơ S, cộng cụm quản trị 3 × VM-32-128 | Có thể dùng máy ảo cho tầng dữ liệu nếu khách hàng bảo đảm đĩa NVMe chuyên dụng |
| 300.000 đến 800.000 (tỉnh lớn có đám mây riêng) | `M` | Như hồ sơ M, cộng cụm quản trị 4 × VM-32-128 | Cần 8 máy Ceph nếu dùng erasure coding |

Mức tối thiểu tuyệt đối 3 máy ở dòng đầu là cấu hình mà TL-04 gọi là "cho phép 3 node, một vùng sự cố". Ở cấu hình này, mỗi máy chạy một thành viên etcd, một instance của mỗi cụm dữ liệu và các pod ứng dụng; tổng tải ở 10.000 tài khoản khoảng 30 vCPU ở đỉnh nên vừa với ba máy 32 vCPU sau khi trừ tài nguyên của dữ liệu. Cấu hình này phù hợp cho thí điểm ngắn hạn và đơn vị nhỏ, không phù hợp cho đơn vị có yêu cầu RTO dưới 4 giờ.

## Ngoại lệ và biện pháp bù ở hồ sơ compact

Hồ sơ compact chấp nhận ba ngoại lệ so với thiết kế chuẩn của TL-04, mỗi ngoại lệ có biện pháp bù.

Ngoại lệ thứ nhất là chỉ có một vùng sự cố. Mất nguồn điện của cả phòng máy làm gián đoạn toàn bộ dịch vụ. Biện pháp bù là sao lưu ngoài địa điểm hằng ngày với WAL liên tục (RPO 15 phút vẫn giữ được nếu kênh tới địa điểm sao lưu hoạt động), cộng diễn tập khôi phục mỗi quý theo OPS-18. RTO khi mất địa điểm được cam kết ở mức 48 giờ thay vì 4 giờ, và điều này phải được ghi rõ trong hợp đồng dịch vụ của bản cài.

Ngoại lệ thứ hai là các thành phần vùng bảo mật (ledger-service, esign-service, `pg-ledger`, OpenBao) chạy trên cùng node với tầng dữ liệu chứ không trên node riêng có card mạng riêng vào HSM. Biện pháp bù gồm: node dữ liệu là node duy nhất có đường mạng tới HSM (pod ứng dụng chạy trên node hội tụ không có đường vật lý tới HSM); NetworkPolicy mặc định từ chối của TL-04 mục 3.6 vẫn áp dụng đầy đủ; namespace vùng bảo mật có RBAC tách khỏi namespace dữ liệu; và ổ đĩa node dữ liệu được mã hoá ràng buộc mạng như node vùng bảo mật của hồ sơ chuẩn.

Ngoại lệ thứ ba là gộp các cụm PostgreSQL (mục 5.2). Biện pháp bù là mỗi service vẫn có database và tài khoản riêng, `max_db_connections` của PgBouncer được đặt cho từng database, và tham số `statement_timeout` 30 giây ngăn truy vấn nặng của một service chiếm CPU kéo dài.

## Bản cài cách ly

Bản cài cách ly cho lực lượng vũ trang và Công an dùng hồ sơ compact hoặc S tuỳ quy mô đơn vị, với `crypto.provider: bcy` và không có kết nối ra ngoài (TL-04 mục 2.4). Định cỡ có ba khác biệt. Một là trợ lý AI và OCR, nếu được bật, phải chạy hoàn toàn tại chỗ, nên máy GPU là bắt buộc khi đơn vị yêu cầu AI; ở quy mô compact, một máy hai GPU 80 GB là đủ. Hai là bộ mật mã của Ban Cơ yếu có thể có thông lượng thấp hơn AES-256-GCM có tăng tốc phần cứng; nếu đo được chi phí mã hoá trường lớn hơn 10 lần so với giả định của G3 (1–3 µs mỗi trường), giả định GD-09 của bản cách ly phải được hiệu chỉnh bằng PERF-14 chạy trên phần cứng của đơn vị. Ba là kho Harbor nội bộ, kho Git cấu hình và trạm nhận gói cập nhật cần thêm khoảng 2 TB lưu trữ cho các bản LTS giữ lại (mỗi bản LTS khoảng 40–60 GB ảnh và chart, giữ tối thiểu 3 bản cộng các bản vá).

# Phân tích độ nhạy

## Vì sao cần phân tích độ nhạy

Toàn bộ bảng định cỡ dựa trên các giả định chưa đo được. Ba giả định có tác động lớn nhất là tỷ lệ người dùng đồng thời (GD-02), thời gian suy nghĩ giữa hai request (GD-03) và chi phí CPU mỗi request (GD-09), vì số pod của mission-core, số node của pool ứng dụng và tải lên `pg-core` tỷ lệ thuận với chúng. Phân tích độ nhạy trả lời hai câu hỏi: nếu giả định sai thì thiết kế còn đứng vững tới đâu, và phần nào của hạ tầng phải thay đổi. Câu hỏi thứ hai đặc biệt quan trọng cho mua sắm, vì tầng ứng dụng trên Viettel Cloud có thể tăng trong vài phút, còn tầng dữ liệu trên máy vật lý cần nhiều tuần.

## Độ nhạy của hồ sơ L

Bảng dưới đây tính lại cell hạng L (CELL-HCM) khi từng giả định thay đổi theo hướng bất lợi, và khi nhiều giả định cùng bất lợi. Cột "Tải lên `pg-core`" là số câu lệnh mỗi giây đặt lên primary ở số pod cần tại đỉnh, so với năng lực khoảng 37.000 câu lệnh mỗi giây ở mức sử dụng 70%.

| Kịch bản | Hệ số tải | Pod mission-core cần ở đỉnh | `maxReplicas` cần | Node app-general ở đỉnh | Tải lên `pg-core` |
|---|---|---|---|---|---|
| Thiết kế (GD-02 5%, GD-03 20 s, GD-04 2, GD-09 10,4 ms) | 1,00 | 16 | 24 | 16 | ≈ 9.600 (26%) |
| Thời gian suy nghĩ 15 giây | 1,33 | 21 | 32 | 18 | ≈ 12.600 (34%) |
| Thời gian suy nghĩ 10 giây | 2,00 | 32 | 48 | 23 | ≈ 19.200 (52%) |
| Tỷ lệ CCU 8% | 1,60 | 25 | 38 | 20 | ≈ 15.000 (41%) |
| Hệ số đột biến 3 | 1,50 | 24 | 36 | 20 | ≈ 14.400 (39%) |
| CPU mỗi request 15 ms | 1,44 (chỉ CPU ứng dụng) | 23 | 35 | 19 | ≈ 9.600 (26%) |
| Tỷ lệ ghi 30% | 1,12 (CPU), 1,5 (ghi) | 18 | 27 | 17 | ≈ 11.500 (31%) |
| Tổ hợp bi quan: Z 15 s, CCU 6%, đột biến 2,5, CPU 12 ms | 2,30 (CPU), 2,00 (CSDL) | 36 | 54 | 24 | ≈ 19.200 (52%) |

Kết luận quan trọng nhất của bảng là **tầng dữ liệu của hồ sơ L chịu được mọi kịch bản trên mà không cần thêm máy**: ngay cả khi tải gấp đôi thiết kế, primary `pg-core` chỉ dùng khoảng một nửa năng lực ở ngưỡng 70%. Phần phải điều chỉnh là tầng ứng dụng: `maxReplicas` của mission-core tăng lên 32 đến 54 và pool app-general cần thêm 2 đến 8 node ở đỉnh. Cả hai đều là thay đổi tệp values trong kho GitOps cộng mở rộng hợp đồng giữ chỗ trên Viettel Cloud, không phải mua máy vật lý. Ngân sách kết nối phía client cũng còn đủ: ở `maxReplicas` 54, mission-core mở tối đa 1.296 kết nối tới PgBouncer, dưới giới hạn 3.000 của ba instance; phía máy chủ không đổi vì `default_pool_size` không phụ thuộc số pod.

## Độ nhạy của hồ sơ M và S

Hồ sơ M nhạy hơn hồ sơ L vì primary `pg-core` được chọn ở 16 vCPU. Ở tải thiết kế, 11 pod mission-core đặt khoảng 6.600 câu lệnh mỗi giây lên primary (35% năng lực ở ngưỡng 70%); ở tổ hợp bi quan với hệ số CSDL 2,0, tải lên khoảng 13.200 câu lệnh mỗi giây (71%), chạm ngưỡng NG-03. Đây là lý do mục 5.2 chọn máy PHY-DB-L cho hồ sơ M với dư 14 vCPU: nâng `pg-core` lên 24 vCPU ngay trên máy hiện có đưa mức sử dụng về khoảng 47%. Hồ sơ S có primary 8 vCPU và tải thiết kế khoảng 2.400 câu lệnh mỗi giây (26%); ở tổ hợp bi quan khoảng 4.800 (51%), vẫn trong giới hạn.

## Độ nhạy của dung lượng và GPU

Dung lượng minh chứng nhạy với số tệp và kích thước tệp. Nếu mỗi người nộp 12 tệp mỗi quý thay vì 8 và kích thước bình quân 2 MB thay vì 1 MB, dung lượng tăng 3 lần. Ở QM-5, cụm `ceph-hl-2` khi đó cần khoảng 870 TB lắp năm đầu thay vì 290 TB; con số này vẫn nằm trong sức chứa 1,9 PB của 8 máy lắp đầy, nên rủi ro chỉ là mua ổ sớm hơn, không phải thêm máy. Biện pháp quản lý rủi ro là lắp ổ theo quý theo ngưỡng NG-07 và theo dõi tỷ lệ khử trùng thực tế.

Năng lực GPU nhạy với tỷ lệ người dùng trợ lý AI. Nếu 3% CCU dùng trợ lý thay vì 1%, cell hạng L cần khoảng 9.000 token đầu ra mỗi giây, tức 5 đến 6 GPU cho mô hình 30 tỷ tham số, vượt số GPU dành cho mô hình này trên máy 8 GPU. Vì trợ lý AI không nằm trên đường găng, phản ứng thiết kế là để ai-gateway trả 503 kèm `Retry-After` khi hàng đợi vượt 200 yêu cầu (TL-04 mục 6.9), và quyết định mua thêm GPU dựa trên số đo thật qua ngưỡng NG-13, không mua đón đầu.

# Ngưỡng nâng bậc và quy trình mở rộng

## Nguyên tắc

Bảng định cỡ cho biết cần gì ở mỗi bậc, còn ngưỡng nâng bậc cho biết khi nào phải chuyển bậc hoặc mở rộng trong bậc. Mỗi ngưỡng gắn với một chỉ số đo được trên ngăn xếp quan sát của TL-04, một giá trị ngưỡng và một hành động. Ngưỡng được đặt ở mức cảnh báo sớm, đủ xa giới hạn cứng để còn thời gian mua sắm nếu hành động cần phần cứng mới. Một ngưỡng chỉ được coi là chạm khi vượt ở hai kỳ đỉnh liên tiếp (hai đợt hạn nộp quý), trừ ngưỡng dung lượng là chạm ngay khi vượt, vì dung lượng chỉ tăng không giảm.

## Danh mục ngưỡng NG

| Mã | Chỉ số | Ngưỡng | Hành động | Cần phần cứng mới |
|---|---|---|---|---|
| NG-01 | Số tài khoản của cell trong `registry.tenant_cell`; CCU đỉnh đo bằng Faro | > 1,5 triệu tài khoản hoặc > 75.000 CCU | Tách cell theo OPS-23, chuyển tenant theo OPS-08 (TL-01) | Có, một cell mới |
| NG-02 | Lần hoàn thành user task mỗi giây ở giờ đỉnh (`flowable_task_completed_total`) | > 150/giây ở hai kỳ; > 300/giây bất kỳ lúc nào | Tăng `maxReplicas` workflow API và executor, kiểm tra `pg-flowable`; trên 300/giây tách engine theo cell con | Có thể (nâng `pg-flowable`) |
| NG-03 | CPU primary `pg-core` p95 trong giờ đỉnh | > 60% ở hai kỳ | Hồ sơ M: nâng instance lên 24 vCPU trên máy sẵn có; hồ sơ L: chuyển thêm truy vấn đọc sang standby, rà truy vấn chậm, xem xét tách cell | Không ở M; có thể ở L |
| NG-04 | Số tài khoản của cell so với trần hồ sơ | > 90% trần (45.000 compact; 270.000 S; 720.000 M) hoặc dự báo vượt trần trong 2 quý | Nâng hồ sơ: compact lên S, S lên M, M lên L | Có |
| NG-05 | Thời gian mission-core chạy ở `maxReplicas` | > 10 phút trong hai ngày hạn liên tiếp | Tăng `maxReplicas` trong ngân sách kết nối (kiểm tra CI hai bất đẳng thức), kiểm tra NG-03 | Không |
| NG-06 | Số node của pool app-general, app-worker so với giới hạn của Cluster Autoscaler | Chạm giới hạn trên trong ngày hạn | Nâng giới hạn pool và hợp đồng giữ chỗ Viettel Cloud | Không (máy ảo) |
| NG-07 | Mức đầy cụm Ceph | > 60%: đặt hàng ổ; > 75%: ngưỡng cứng, ngừng nhận dữ liệu không thiết yếu | Lắp ổ vào khe trống; khi hết khe, thêm máy PHY-CEPH | Có |
| NG-08 | Đĩa broker Kafka; số phân vùng mỗi broker | > 50% đĩa kéo dài 7 ngày; > 4.000 phân vùng mỗi broker | Rà thời gian lưu giữ; thêm broker | Có thể |
| NG-09 | Kích thước shard OpenSearch; heap | Shard > 50 GB; heap > 75% kéo dài | Chia chỉ mục theo năm sớm hơn; thêm node dữ liệu | Có thể |
| NG-10 | Đĩa ClickHouse; p95 truy vấn dashboard trong PERF-24 | > 70% đĩa; p95 > 1 s | Thêm shard ClickHouse, rà lớp tổng hợp trước của Cube | Có thể |
| NG-11 | Bộ nhớ Valkey so với `maxmemory`; tỷ lệ trục xuất khoá nóng | > 70% hoặc có trục xuất khoá quyền | Thêm shard Valkey | Không (dùng dư địa node Kafka) |
| NG-12 | Số chuỗi metric hoạt động mỗi cell | > 3 triệu | Nâng pool system lên VM-16-64; rà nhãn có số giá trị lớn | Không (máy ảo) |
| NG-13 | Hàng đợi vLLM (`vllm:num_requests_waiting`); tỷ lệ 503 của ai-gateway | > 200 kéo dài 15 phút; 503 > 1% trong giờ làm việc | Thêm GPU cho cell | Có |
| NG-14 | Tỷ lệ CCU trên tài khoản và thời gian suy nghĩ đo bằng Faro | CCU > 6% hoặc Z < 15 giây | Chạy lại mô hình định cỡ với số đo, cập nhật TL-05 trước khi đặt hàng bậc tiếp theo | Tuỳ kết quả |

Các ngưỡng NG được cài thành luật cảnh báo trong Prometheus với mức nghiêm trọng "kế hoạch năng lực" (không gọi trực ca), gửi về kênh của đội nền tảng. Báo cáo năng lực hằng quý (OPS-23 mở rộng) tổng hợp trạng thái của mọi ngưỡng ở mọi cell và là đầu vào của quyết định mua sắm.

## Quy trình nâng bậc

![Hình 11.1. Quy trình từ tín hiệu ngưỡng tới quyết định mở rộng](dc-nang-bac.png){width=16cm}

Hình 11.1 mô tả quy trình. Khi một ngưỡng chạm ở hai kỳ đỉnh liên tiếp, đội nền tảng chạy lại các kịch bản PERF liên quan trên `CELL-PERF` với thông số tải cập nhật theo số đo, để phân biệt "tải thật cao hơn giả định" với "có hồi quy hiệu năng trong mã". Sau đó hành động thuộc một trong ba loại. Loại thứ nhất là điều chỉnh trong hồ sơ (tăng `maxReplicas`, nâng giới hạn node pool), thực hiện bằng merge request vào kho GitOps và có hiệu lực trong ngày. Loại thứ hai là nâng hồ sơ cell (S lên M, M lên L), cần mua máy chủ dữ liệu và một cửa sổ bảo trì để chuyển cụm CSDL sang máy mới bằng cách thêm instance mới vào cụm CNPG rồi chuyển primary, không dừng dịch vụ quá thời gian chuyển primary (dưới 30 giây theo PERF-09). Loại thứ ba là vượt hồ sơ L, khi đó tách cell theo OPS-23 và chuyển một phần tenant sang cell mới theo OPS-08 (chỉ đọc 5 đến 15 phút cho mỗi tenant được chuyển). Mọi hành động kết thúc bằng việc cập nhật bảng định cỡ, để lần tính sau dùng đúng cấu hình đang chạy.

Nâng hồ sơ cell không đổi tên miền, không đổi cell của tenant và không cần người dùng làm gì. Quy trình mẫu để nâng một cell từ S lên M: lắp ba máy PHY-DB-L và năm máy PHY-DATA mới vào cụm Kubernetes của cell với nhãn node pool tương ứng; tăng `instances` của các cụm CNPG từ 3 lên 6 với `nodeSelector` trỏ sang máy mới, chờ đồng bộ; chuyển primary sang máy mới; giảm `instances` về 3 để loại các instance trên máy cũ; làm tương tự với Kafka (thêm broker, tái phân bố phân vùng bằng Cruise Control của Strimzi, gỡ broker cũ), OpenSearch và ClickHouse; cuối cùng đổi `global.sizing` từ `S` sang `M` trong tệp values để cập nhật số replica và kích thước tài nguyên. Máy cũ của hồ sơ S được thu hồi làm staging hoặc cell cho bản cài đám mây riêng khác.

# Kiểm chứng và hiệu chuẩn bảng định cỡ

## Nguyên tắc

Bảng định cỡ chỉ có giá trị khi được kiểm chứng bằng số đo. TL-05 dùng lại toàn bộ chương trình thử tải PERF của TL-04 chương 7, và bổ sung hai yêu cầu. Yêu cầu thứ nhất là mỗi hồ sơ cell phải qua bộ kịch bản tối thiểu ở đúng tải thiết kế của hồ sơ trước khi hồ sơ đó được dùng cho sản xuất lần đầu. Yêu cầu thứ hai là sau mỗi chiến dịch thử tải, các giả định GD-xx có kịch bản kiểm chứng được cập nhật theo số đo, mô hình được tính lại, và cấu hình đang chạy được so sánh với kết quả tính lại.

## Bộ kịch bản theo hồ sơ

| Kịch bản | compact | S | M | L | Kiểm chứng giả định |
|---|---|---|---|---|---|
| PERF-01 tải thường | 125 RPS | 750 RPS | 2.000 RPS | 3.000 RPS | GD-07, GD-08 |
| PERF-02 hiệu chuẩn một pod | chung cho mọi hồ sơ (chạy một lần trên `CELL-PERF`) | | | | GD-08, GD-09 |
| PERF-03 đỉnh | 250 RPS | 1.500 RPS | 4.000 RPS | 6.000 RPS | GD-04, kết quả bảng mục 4.3 |
| PERF-04 đột biến | 125 lên 250 RPS | 750 lên 1.500 | 2.000 lên 4.000 | 3.000 lên 6.000 | Thời gian phản ứng của HPA và Cluster Autoscaler |
| PERF-06 điểm gãy | ≥ 375 RPS | ≥ 2.250 RPS | ≥ 6.000 RPS | ≥ 9.000 RPS | Biên 1,5 lần |
| PERF-11 engine luồng | 10/giây | 40/giây | 100/giây | 150 tới 300/giây | GD-10 |
| PERF-19 tính lại cuối kỳ | 50.000 hồ sơ | 300.000 | 800.000 | 1.200.000 | Công thức scoring mục 2.4 |
| PERF-20 sinh và ký lô | 4.200 phiếu | 25.000 | 66.700 | 100.000 | GD-23, công thức Gotenberg |
| PERF-22 kết nối thời gian thực | 2.500 | 15.000 | 40.000 | 60.000 | `minReplicas` notification |

Ở QM-1 đến QM-3, `CELL-PERF` chạy hồ sơ M (mục 7.2), nên kịch bản của hồ sơ L chưa chạy được trên phần cứng giống sản xuất; số pod cần của hồ sơ L được kiểm chứng gián tiếp bằng PERF-02 (thông lượng một pod không phụ thuộc hồ sơ) và PERF-06 trên hồ sơ M. Bộ kịch bản đầy đủ của hồ sơ L chạy trước khi CELL-HCM vào vận hành ở QM-4.

## Vòng hiệu chuẩn

Mô hình định cỡ được lưu trong kho GitOps dưới dạng tệp tham số `capacity/assumptions.yaml` (giá trị GD-xx kèm nguồn: giả định hay số đo, ngày đo, kịch bản đo) và một script tính sinh ra các bảng của chương 2, 4, 5, 6, 8. Sau mỗi chiến dịch thử tải và sau mỗi kỳ đánh giá quý, đội nền tảng thực hiện năm bước. Bước một là trích số đo từ Prometheus, Faro và báo cáo k6 cho các GD có thể đo. Bước hai là cập nhật `assumptions.yaml` bằng merge request, ghi rõ giá trị cũ, giá trị mới và nguồn. Bước ba là chạy script để tính lại các bảng. Bước bốn là so sánh `maxReplicas`, số node và kích thước cụm dữ liệu đang chạy với kết quả tính lại; chênh lệch quá 20% theo hướng thiếu tạo một yêu cầu thay đổi values, theo hướng thừa được ghi nhận để giảm ở lần mua sắm sau. Bước năm là phát hành lại TL-05 với bảng mới nếu có thay đổi ở cấp hồ sơ hoặc bậc.

Kiểm tra tự động ngân sách kết nối CSDL của TL-04 mục 4.7 dùng chính các giá trị của hồ sơ ở chương 5; khi một merge request tăng `maxReplicas` vượt ngân sách của hồ sơ, pipeline CI từ chối và chỉ ra bất đẳng thức bị vi phạm.

# Phụ lục A. Mẫu tệp values cho bốn hồ sơ cell {-}

Mỗi hồ sơ là một tệp values riêng được chồng lên tệp values theo mô hình triển khai (`values-saas.yaml`, `values-private.yaml`, `values-airgap.yaml`) và tệp của cell. Thứ tự chồng là: mô hình triển khai, hồ sơ, cell; tệp sau ghi đè tệp trước. Ví dụ CELL-G4 dùng `values-saas.yaml`, `sizing/values-sizing-m.yaml`, `cells/g4/values.yaml`. Dưới đây là phần chính của tệp hồ sơ M; ba hồ sơ còn lại có cùng cấu trúc với các giá trị ở chương 4 và 5.

```yaml
# sizing/values-sizing-m.yaml
global:
  sizing: M
  topology:
    zones: [hl-a, hl-b, hl-c]

api-gateway:
  replicas: { min: 3, max: 6 }
mission-core:
  replicas: { min: 6, max: 17 }
  datasources:
    modules:
      task:      { pool: 8 }
      okr:       { pool: 4 }
      appraisal: { pool: 4 }
      product:   { pool: 3 }
      config:    { pool: 2 }
      policy:    { pool: 1 }
      dossier:   { pool: 1 }
      oversight: { pool: 1 }
org-service:            { replicas: { min: 3, max: 4 } }
iam:                    { replicas: { min: 3, max: 6 } }
workflow-api:           { replicas: { min: 3, max: 6 } }
workflow-executor:      { replicas: { min: 2, max: 8 } }
evidence-service:       { replicas: { min: 3, max: 6 } }
clamav-scanner:         { replicas: { min: 2, max: 8 } }
scoring-service:        { replicas: { min: 2, max: 16 } }
document-service:       { replicas: { min: 2, max: 14 } }
gotenberg:              { replicas: { min: 2, max: 16 } }
notification-service:   { replicas: { min: 3, max: 6 } }
integration-gateway:    { replicas: { min: 3, max: 4 } }
ledger-service:         { replicas: { min: 3, max: 6 } }
esign-service:          { replicas: { min: 2, max: 4 } }
ai-gateway:             { replicas: { min: 2, max: 4 } }
analytics-api:          { replicas: { min: 2, max: 4 } }
cube:                   { replicas: { min: 3, max: 6 } }
superset:               { replicas: { min: 2, max: 3 } }
search-indexer:         { replicas: { min: 2, max: 4 } }
web-frontend-origin:    { replicas: { min: 2, max: 3 } }

postgres:
  clusters:
    pg-core:
      instances: 3
      resources: { cpu: "16", memory: 128Gi }
      parameters: { max_connections: "300", shared_buffers: "32GB", effective_cache_size: "96GB" }
      storage: { size: 4Ti, walSize: 512Gi }
      nodeSelector: { vt.io/pool: postgres, vt.io/pg-size: large }
      pooler: { instances: 3, defaultPoolSize: 20 }
    pg-flowable:
      instances: 3
      resources: { cpu: "8", memory: 32Gi }
      storage: { size: 1Ti }
      nodeSelector: { vt.io/pool: postgres, vt.io/pg-size: large }
    pg-platform:
      instances: 3
      resources: { cpu: "8", memory: 32Gi }
      storage: { size: 2560Gi }
      nodeSelector: { vt.io/pool: postgres, vt.io/pg-size: large }
    pg-iam:
      instances: 3
      resources: { cpu: "2", memory: 8Gi }
      storage: { size: 100Gi }
      nodeSelector: { vt.io/pool: postgres, vt.io/pg-size: large }
    pg-ledger:
      instances: 3
      resources: { cpu: "4", memory: 16Gi }
      storage: { size: 1536Gi }
      nodeSelector: { vt.io/zone: secure }
    pg-secure:
      instances: 3
      resources: { cpu: "4", memory: 24Gi }
      storage: { size: 1Ti }
      nodeSelector: { vt.io/zone: secure }

kafka:
  brokers: { replicas: 3, resources: { cpu: "8", memory: 32Gi }, storage: 3500Gi }
  controllers: { replicas: 3, resources: { cpu: "1", memory: 4Gi } }
  topics:
    scoringInputPartitions: 48
    ledgerAppendPartitions: 6

valkey:
  shards: 3
  replicasPerShard: 1
  resources: { cpu: "2", memory: 8Gi }
  maxmemory: 5gb

opensearch:
  managers: { replicas: 3, resources: { cpu: "1", memory: 2Gi } }
  data: { replicas: 3, resources: { cpu: "6", memory: 24Gi }, heap: 12g, storage: 1536Gi }

clickhouse:
  shards: 1
  replicasPerShard: 2
  resources: { cpu: "12", memory: 48Gi }
  storage: 3Ti
  keeper: { replicas: 3, resources: { cpu: "1", memory: 2Gi } }

vllm:
  gpus: 4
  models:
    - name: vt-llm-30b-fp8
      replicas: { min: 2, max: 3 }
      gpuPerReplica: 1

nodePools:
  system:        { size: VM-8-32,  min: 3, max: 3 }
  ingress:       { size: VM-16-32, min: 3, max: 4 }
  app-general:   { size: VM-16-64, min: 6, max: 12 }
  app-worker:    { size: VM-16-64, min: 3, max: 12 }
```

Khối `nodePools` được Cluster API và Cluster Autoscaler đọc để đặt giới hạn co giãn của các pool máy ảo; các pool máy vật lý không nằm trong khối này vì chúng không co giãn tự động. Tệp hồ sơ compact khác các tệp còn lại ở chỗ có thêm khối `postgres.consolidated: true` để chart sinh hai cụm CNPG gộp thay vì sáu cụm, và `global.topology.zones` chỉ có một vùng, khi đó chart thư viện `vt-service` đổi `topologySpreadConstraints` theo vùng từ `DoNotSchedule` sang ràng buộc theo node.

# Phụ lục B. Ma trận truy vết {-}

| Yêu cầu hoặc tiêu chí | Nội dung | Mục đáp ứng trong TL-05 |
|---|---|---|
| NFR-01 | 6 triệu tài khoản (định cỡ 6,5 triệu), 300.000 CCU, tối đa 5.000 tenant | Mục 2.5, 3.1, 8.2 (QM-5) |
| NFR-02 | 15.000 RPS thường, 30.000 RPS đột biến, đọc/ghi 80/20 | Mục 2.3 (GD-03 đến GD-05), 2.5 |
| NFR-03 | Độ trễ p95 | Mục 4.1 (giữ nguyên tài nguyên pod đã hiệu chuẩn), 12.2 |
| NFR-04, NFR-05 | Khả dụng, RPO, RTO | Mục 8.1 (bản sao ấm), 8.3 (giữ chỗ năng lực), 9.2 (ngoại lệ compact) |
| NFR-06 | Tính lại 1,2 triệu hồ sơ trong 2 giờ, 100.000 phiếu trong 4 giờ | Mục 2.4, 4.2, 12.2 |
| NFR-09, NFR-10 | Lưu giữ log, quan sát | Mục 6.3 |
| Tiêu chí C5 | maxReplicas tính theo SLO và giới hạn CSDL | Mục 4.1, 5.3, 11.2 (NG-05) |
| Tiêu chí C7 | Phân bố theo vùng sự cố, dự phòng mất một vùng | Mục 2.4 (hệ số 1,5), 4.1 |
| Vấn đề F01 | Bùng nổ kết nối CSDL khi co giãn | Mục 5.3 |
| Góp ý G2 | Engine BPMN khi hàng trăm, hàng nghìn tenant | Mục 2.5 (user task theo hồ sơ), 11.2 (NG-02) |
| Góp ý G3 | Chi phí bảy lớp bảo vệ và mã hoá | Mục 5.6 (vùng bảo mật, HSM), 9.3 (mật mã Ban Cơ yếu) |
| Lộ trình đợt 1 đến đợt 3 | Dựng cell đầu tiên, mở rộng, quy mô quốc gia | Mục 3.1, 8.5 |
| TL-04 mục 2.1 | Hồ sơ năng lực `compact`, `S`, `M`, `L` | Chương 3, 4, 5, phụ lục A |
| TL-04 mục 4.4 | Tổng hợp tài nguyên theo hạng cell | Mục 8.1 (thay thế bảng sơ bộ cho hạng M và S) |
