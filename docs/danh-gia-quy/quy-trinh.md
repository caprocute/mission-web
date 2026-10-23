# Quy trình chấm lại 110 tiêu chí và bảng chỉ số chuyển đổi mỗi quý

> Nguồn: task VH-09; TL-01 chương "Chỉ số theo dõi chuyển đổi" và "Ma trận truy vết 110 tiêu chí"; TL-04 mục 12.8 "Bảng chỉ số theo dõi và cách đo", chương 13 "Ma trận truy vết", OPS-25 "Rà soát năng lực"; bản đánh giá kiến trúc công nghệ 10/2026 (thang chấm, trọng số trục, kết quả cơ sở). Nội dung suy ra mà nguồn không ghi trực tiếp được đánh dấu "(suy luận)".

## 1. Mục đích và các tệp

Bản đánh giá kiến trúc 10/2026 chấm hệ thống hiện hữu theo 110 tiêu chí chia 12 trục và cho điểm tổng hợp 17,4/100. TL-01 dùng điểm này làm chỉ số chính để theo dõi quá trình chuyển đổi, với mốc từ 40 sau đợt 0c, từ 60 sau đợt 2 và từ 75 sau đợt 3. Để chỉ số có ý nghĩa, mỗi quý phải chấm lại cùng 110 tiêu chí bằng cùng một thang, có bằng chứng cho từng tiêu chí, và đặt cạnh bảng chỉ số chuyển đổi của TL-04 mục 12.8. Thư mục này chứa toàn bộ công cụ cho việc đó.

| Tệp | Vai trò |
|---|---|
| `tieu-chi.yaml` | Danh mục 110 tiêu chí: mã, tên, giải pháp và mục TL-01, bằng chứng nghiệm thu, loại bằng chứng, task đáp ứng, kết quả cơ sở. Chỉ sửa khi TL đổi. |
| `chi-so-chuyen-doi.yaml` | Chín dòng chỉ số của TL-04 mục 12.8 cùng bốn dòng riêng của TL-01; mỗi dòng có cách đo, nguồn đo, truy vấn Prometheus hoặc người đo, mốc và phép so sánh. |
| `co-so-2026-10.yaml` | Bản chấm cơ sở chép từ bản đánh giá 10/2026; cho ra đúng 17,4 và là mốc so sánh của lần chấm đầu. Không sửa. |
| `<năm>-Q<n>.yaml` | Bản chấm của từng quý, ví dụ `2026-Q4.yaml`. |
| `cham.py` | Bộ chấm: kiểm bản chấm, tính điểm, so với bản trước và mốc, in báo cáo Markdown. |
| `bao-cao/<năm>-Q<n>.md` | Báo cáo sinh từ bản chấm; không sửa tay. |
| `test_cham.py` | Kiểm thử của bộ chấm: đủ 110 tiêu chí, không trùng mã, bản cơ sở ra 17,4. |

Cách chạy:

```bash
python docs/danh-gia-quy/cham.py docs/danh-gia-quy/2026-Q4.yaml                 # in báo cáo ra màn hình
python docs/danh-gia-quy/cham.py docs/danh-gia-quy/2026-Q4.yaml \
  --ra docs/danh-gia-quy/bao-cao/2026-Q4.md --nghiem                              # chốt bản chấm
PYTHONDONTWRITEBYTECODE=1 python -m pytest -q -p no:cacheprovider docs/danh-gia-quy
```

Bộ chấm cần Python 3.10 trở lên và PyYAML. Mã thoát 0 là thành công, 1 là bản chấm sai cấu trúc (thiếu tiêu chí, mã lạ, giá trị ngoài thang), 2 là còn cảnh báo khi chạy với `--nghiem`.

## 2. Thời điểm và người chấm

Việc chấm diễn ra mỗi quý, ngay sau buổi rà soát năng lực OPS-25 của quý đó, vì OPS-25 là lúc SRE và kiến trúc đã kéo đủ số liệu thật từ Thanos (độ phân giải 1 giờ) và các báo cáo PERF, DR gần nhất; nhờ vậy bằng chứng của các trục B, C, E, F, G dùng chung một bộ số liệu với rà soát năng lực thay vì đo lại. Bản chấm của quý Q được hoàn thành trong mười ngày làm việc đầu quý Q+1 (suy luận), rơi vào cửa sổ đóng băng thay đổi của VH-03, điều này không gây xung đột vì bản chấm chỉ là tài liệu.

Người chấm phải độc lập với người làm: không phải tác giả và không phải người duyệt của các task đáp ứng tiêu chí đang chấm. Cách làm khuyến nghị là nhóm kiến trúc cử một người chấm chính cho toàn bộ 110 tiêu chí và một người thứ hai kiểm chéo ngẫu nhiên ít nhất 20 tiêu chí, ưu tiên các tiêu chí đổi kết quả so với quý trước (suy luận). Bản chấm ghi `doc_lap: true` chỉ khi điều kiện này thỏa; nếu không, báo cáo tự ghi rõ kết quả chỉ có giá trị tham khảo. AI agent có thể soạn bản chấm nháp, gom bằng chứng và chạy bộ chấm, nhưng không thay người chấm độc lập.

## 3. Thang chấm và quy tắc bằng chứng

Thang lấy nguyên từ bản đánh giá. Mỗi tiêu chí của mười một trục A, B, C, D, E, F, G, I, J, K, L nhận một trong ba kết quả: Đạt (1 điểm), Một phần (0,5 điểm), Chưa đạt (0 điểm). Điểm trục là trung bình các tiêu chí của trục, điểm tổng hợp là trung bình có trọng số của 12 trục nhân 100, với trọng số A 12, L 10, B 8, C 10, D 14, E 10, F 10, G 6, H 12, I 10, J 8, K 10 (tổng 120). Cột "/4" của báo cáo là điểm trục nhân 4; 3/4 (75%) là ngưỡng đáp ứng cơ bản của bản đánh giá.

Trục H (phạm vi chức năng) chấm mức 0–4, quy về 0–1 bằng cách chia cho 4. Bản đánh giá không in mức của từng tiêu chí H mà chỉ in nhãn Đạt, Một phần, Chưa đạt cùng điểm trục 31%; để bản chấm cơ sở khớp 31% (tổng mức 11/36), mức từng tiêu chí H trong `co-so-2026-10.yaml` là suy luận từ bằng chứng của bản đánh giá. Từ quý đầu tiên, mức H được chấm theo thang sau (suy luận, cần nhóm kiến trúc xác nhận):

| Mức | Ý nghĩa |
|---|---|
| 0 | Chưa có mô hình dữ liệu, API hay màn hình cho chức năng. |
| 1 | Có mô hình dữ liệu hoặc API cơ bản, chưa có luồng nghiệp vụ dùng được. |
| 2 | Luồng chính chạy được nhưng còn thiếu yêu cầu đáng kể của tiêu chí. |
| 3 | Đủ yêu cầu của tiêu chí ở mã nguồn, có kiểm thử tự động và giao diện. |
| 4 | Đạt mức 3 và đã chạy trọn một kỳ đánh giá với tenant thật (biên bản kiểm thử chấp nhận QA-16). |

Quy tắc bằng chứng theo TL-04 chương 13: một tiêu chí chỉ được chấm Đạt khi có bằng chứng ở cột "Bằng chứng nghiệm thu", không chỉ khi có thiết kế; tiêu chí đã có thiết kế và hiện thực nhưng chưa có bằng chứng nghiệm thu được chấm Một phần. Cột này lấy nguyên từ TL-04 cho bảy trục B, C, D, E, F, G, J; với năm trục A, H, I, K, L mà TL-04 để cho TL-01, bằng chứng nghiệm thu được suy luận từ giải pháp của TL-01 và ghi `nguon_bang_chung: suy luận từ giải pháp TL-01` trong `tieu-chi.yaml`.

Mỗi tiêu chí có một `loai_bang_chung` cho biết bằng chứng nghiệm thu cần môi trường nào:

| Loại | Bằng chứng cần | Đo được trong giai đoạn phát triển |
|---|---|---|
| `ma-nguon` | Mã nguồn, cấu hình trong Git và kiểm thử tự động chạy trên máy phát triển | Có |
| `pipeline` | Báo cáo của pipeline CI thật trên mọi kho (JaCoCo, SARIF, quality gate) | Không, tới khi có máy chủ Jenkins |
| `cum-van-hanh` | Cụm Kubernetes chạy thật: báo cáo Kyverno, Loki, Prometheus, ZAP, Argo CD | Không |
| `thu-tai` | Báo cáo PERF trên CELL-PERF | Không (YC-PT-19) |
| `he-thong-ngoai` | Kết nối hệ thống ngoài thật thay cho bản giả lập | Không (YC-PT-13) |
| `nghiep-vu` | Nghiệp vụ ký bộ ca mẫu, hoặc chạy trọn một kỳ với người dùng thật | Không |

Bộ chấm áp quy tắc này tự động: trong bản chấm có `giai_doan: phat-trien`, mọi tiêu chí có loại khác `ma-nguon` được liệt kê ở mục "Tiêu chí chưa đo được" của báo cáo, và nếu tiêu chí đó bị chấm Đạt (hoặc mức 4 với trục H) mà không ghi `da_do: true`, bộ chấm cảnh báo. Khi một bằng chứng loại đó thực sự đã có (ví dụ đã chạy ZAP trên staging), người chấm ghi `da_do: true` kèm đường dẫn báo cáo. Bộ chấm cũng cảnh báo khi tiêu chí có điểm mà không ghi bằng chứng, hoặc khi đường dẫn bằng chứng không tồn tại trong repo. Hệ quả là trong giai đoạn phát triển điểm có trần: nếu mọi tiêu chí `ma-nguon` đạt, mọi tiêu chí khác dừng ở Một phần và trục H dừng ở mức 3, điểm tổng hợp là 67,5; báo cáo in con số này để người đọc không nhầm điểm sẵn sàng của mã nguồn với điểm của hệ thống đang vận hành.

## 4. Các bước chấm một quý

1. Sao bản chấm quý trước thành `<năm>-Q<n>.yaml`, đổi `ky`, `ngay_cham`, `nguoi_cham`, `doc_lap`, `he_thong` (ghi commit hoặc bản phát hành được chấm), `giai_doan`, và đặt `so_sanh_voi` là tệp quý trước.
2. Với từng tiêu chí, đọc cột `bang_chung_nghiem_thu` trong `tieu-chi.yaml`, tìm bằng chứng mới (báo cáo PERF, DR, Kyverno, ZAP, pipeline, biên bản UAT, đường dẫn mã), cập nhật `kq` hoặc `muc`, `bang_chung`, `ghi_chu` và `da_do`. Đường dẫn trong repo ghi tương đối với gốc repo; bằng chứng ngoài repo ghi tên báo cáo và nơi lưu.
3. Điền khối `chi_so`: dòng tự động chạy truy vấn trong `chi-so-chuyen-doi.yaml` ở Grafana Explore hoặc `promtool query instant` trên Thanos rồi ghi giá trị; dòng thủ công do người được ghi ở `nguoi_do` đo và ghi kèm ngày đo. Dòng chưa đo được để `gia_tri: null` và ghi lý do. Điểm tổng hợp do bộ chấm tự điền.
4. Chạy `cham.py ... --nghiem`; xử lý hết cảnh báo hoặc ghi lý do giữ cảnh báo vào `ghi_chu` của tiêu chí.
5. Người kiểm chéo đọc báo cáo, đánh dấu đồng ý trong yêu cầu hợp nhất. Hợp nhất bản chấm và báo cáo vào `main`.
6. Đưa kết quả vào CAB theo mục 6.

## 5. Xử lý tiêu chí tụt điểm

Báo cáo liệt kê mọi tiêu chí có điểm thấp hơn lần chấm trước. Với mỗi tiêu chí tụt, người chấm ghi nguyên nhân vào `ghi_chu` theo một trong ba loại (suy luận):

- **Tụt do đổi đối tượng hoặc đổi quy tắc chấm.** Lần chấm đầu so với bản cơ sở là so hai hệ thống khác nhau (hệ thống cũ đang vận hành và nền tảng mới chưa triển khai), nên một tiêu chí đã Đạt trên hệ thống cũ có thể chỉ được Một phần trên nền tảng mới vì bằng chứng nghiệm thu cần thử tải chưa chạy. Trường hợp này chỉ cần ghi chú, không mở việc.
- **Tụt do bằng chứng hết hiệu lực.** Bằng chứng cũ quá hạn (ví dụ báo cáo PERF cũ hơn hai bản phát hành chính, hoặc ảnh đang chạy không còn chữ ký). Người chấm mở việc đo lại cho chủ tiêu chí, hạn trước lần chấm quý sau.
- **Tụt do suy giảm thật.** Một thay đổi đã làm hệ thống không còn đáp ứng tiêu chí. Người chấm mở vấn đề theo mức độ của bản đánh giá (Nghiêm trọng, Cao, Trung bình), gắn nhãn `danh-gia/nghiem-trong` nếu là mức nghiêm trọng để chỉ số "Vấn đề nghiêm trọng còn mở" đếm được, và đưa vào CAB gần nhất.

Một tiêu chí tụt hai quý liền mà không có hướng xử lý được đưa lên CAB như một yêu cầu thay đổi khẩn (suy luận).

## 6. Đưa kết quả vào CAB

Sau khi bản chấm được hợp nhất, người chấm trình bày tại CAB tuần gần nhất của quy trình thay đổi VH-03 (`ops/van-hanh/hoi-dong-thay-doi.md` khi VH-03 hoàn thành): điểm tổng hợp và so với mốc của đợt đang chạy, các trục dưới 3/4, danh sách tiêu chí tụt cùng loại nguyên nhân, chỉ số chuyển đổi chưa đạt mốc, và các tiêu chí chưa đo được kèm thời điểm dự kiến đo. CAB quyết định việc nào được ưu tiên trong quý sau và ghi quyết định vào biên bản CAB; bản chấm quý sau kiểm lại các việc đó. Điểm tổng hợp chỉ được dùng để báo cáo đạt mốc đợt khi bản chấm có `doc_lap: true` và `giai_doan: van-hanh`.

## 7. Bảng chỉ số chuyển đổi

`chi-so-chuyen-doi.yaml` giữ nguyên chín dòng của TL-04 mục 12.8: điểm đáp ứng tổng hợp, vấn đề nghiêm trọng còn mở, phát hiện secret scanning (gitleaks), lời gọi liên service có circuit breaker (Resilience4j), request có trace end-to-end (`trace_completeness_ratio`), pipeline có quality gate (nhãn `vt-pipeline`), phiên đồng thời đạt SLO (PERF-03 và Faro), node tối thiểu cho mỗi service (Kyverno `vt-require-ha`), bảng mới trong schema VOFFICE. Bốn dòng riêng của TL-01 (lỗ hổng nghiêm trọng và cao, p95 thao tác ghi, thời gian áp dụng thay đổi quy định, tỉ lệ người dùng trên nền tảng mới) được thêm ở cuối, đánh dấu `nguon_tl`. Dòng có `nguon: tu-dong` mang truy vấn PromQL; truy vấn dùng tên metric chưa có trong repo được đánh dấu suy luận trong `ghi_chu` và phải sửa khi metric được hiện thực. Dashboard Grafana "Tiến độ đợt" tự vẽ bảng này là phần mở rộng sau, ngoài phạm vi VH-09.

## 8. Điểm lệch giữa các nguồn

TL-04 chương 13 ghi năm trục để cho TL-01 là "A kiến trúc ứng dụng, H nghiệp vụ, I toàn vẹn và pháp lý, K tích hợp, L trải nghiệm", trong khi checklist của bản đánh giá và ma trận TL-01 đặt tên trục K là "Đa khách thuê và khả chuyển" và trục L là "Khả năng tương hợp và tích hợp". Bộ chấm theo tên và mã tiêu chí của bản đánh giá và TL-01, vì đó là bảng gốc của 110 tiêu chí. TL-04 mục 12.8 ghi mốc điểm "sau đợt 0 ≥ 40", TL-01 ghi "sau đợt 0c từ 40"; bộ chấm dùng mốc của TL-01. Bằng chứng nghiệm thu của F9 trong TL-04 đếm "409/412" trong khi AGENTS.md quy ước 409 cho xung đột `If-Match`; điểm lệch này không ảnh hưởng tới cách chấm.

## Liên quan

- [Danh mục tiêu chí](tieu-chi.yaml), [bảng chỉ số chuyển đổi](chi-so-chuyen-doi.yaml), [bản chấm cơ sở](co-so-2026-10.yaml)
- [Sổ tay OPS-25 rà soát năng lực](../../ops/runbooks/ops/ops-25-ra-soat-nang-luc.md)
- [Task VH-09](../tasks/VH-09-cham-checklist-quy.md), [task VH-03](../tasks/VH-03-truc-su-co-cab.md)
