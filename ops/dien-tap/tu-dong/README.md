# Kịch bản tự động chuyển vùng DR-10, DR-16

> Nguồn: task QA-15; TL-04 mục 8.11, 8.12; sổ tay `ops/runbooks/dr/dr-10.md`, `dr-13.md`, `dr-16.md`; INF-17, INF-18, INF-20, INF-28.

Thư mục này chứa kịch bản phần dữ liệu của sổ tay DR-10, DR-16 (chuyển vùng giữa hai TTDL và quay về), để thao tác lặp lại được và để lại mốc thời gian cho biên bản sự cố. Kịch bản không thay quyết định của IC: chúng không tự phát hiện sự cố, không tự chuyển vùng, và mặc định chỉ in kế hoạch.

| Tệp | Làm gì | Cần |
|---|---|---|
| `dr-chuyen-vung.sh dr10\|dr16 --env <tệp.env> [--nhat-ky <tệp.jsonl>] [--confirm]` | Phần dữ liệu của DR-10 và DR-16 trên hai cụm cell thật hoặc hai cụm kind: kiểm phía đích là bản sao, rào chắn (DR-10) hoặc chờ bắt kịp (DR-16), đổi `replica.primary` (vá trực tiếp hoặc in hướng dẫn commit ở chế độ GitOps), chờ thăng cấp, gỡ rào, tạm dừng MirrorMaker 2, in các bước thủ công còn lại (Ceph, phát lại outbox, kiểm khói, `active_dc`). Mỗi bước ghi một dòng JSON | kubectl, hai context |
| `moi-truong.mau.env` | Mẫu tệp môi trường: cell, hai context, chế độ `gitops` hoặc `truc-tiep`, namespace và tên các cụm PostgreSQL, tên MirrorMaker 2, thời gian chờ, độ trễ tối đa. Không chứa bí mật | — |
| `tests/chay-kiem-thu.sh` | shellcheck, sáu trường hợp với `tests/kubectl-gia.sh` | shellcheck |

Chạy trên hai TTDL thật chỉ đổi tệp env: chép `moi-truong.mau.env` ra ngoài repo, điền context của hai cụm, chạy không có `--confirm` để IC duyệt kế hoạch, rồi chạy lại với `--confirm` sau khi IC quyết định. Tệp JSONL mốc thời gian đính vào biên bản trong `ops/dien-tap/`.

Kết quả lần chạy đầu ở [2026-10-23-dr-10-dr-16-mo-phong.md](../2026-10-23-dr-10-dr-16-mo-phong.md) và [2026-10-23-dr-13.md](../2026-10-23-dr-13.md).

Theo quyết định 24/10/2026 (wiki 14), diễn tập DR (QA-14, QA-15) đã bỏ khỏi phạm vi vì chưa có hạ tầng; hai kịch bản chỉ dùng cho diễn tập mô phỏng (`mo-phong-pg-hai-ttdl.sh`, `dr13-geodns-cuc-bo.sh`) đã xoá. Kịch bản `dr-chuyen-vung.sh` giữ lại vì sổ tay DR-10, DR-16 dùng khi có sự cố thật.
