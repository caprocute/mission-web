# cel-js-runner

Bộ chạy cel-js của CORE-05-07 (QT-15, SCR-1503, BR-15-07, ADR-19). Khi chạy kiểm thử một phiên bản Policy Pack, mission-core chạy bộ ca vàng trên cel-java, ghi lại mọi lần gọi biểu thức CEL (biểu thức, giá trị các biến tham chiếu, kiểu trả về) rồi gửi theo lô tới dịch vụ này. Dịch vụ chạy lại từng biểu thức trên cel-js và trả kết quả cùng thứ tự; mission-core so từng biểu thức, ca nào lệch thì ghi `engine_match = false` và cảnh báo MSG-15-07.

Các hàm Mission (`sum` cộng chính xác thập phân, `min_muc`, `ngay_lam_viec`) lấy thẳng từ `frontend/projects/form-engine/src/lib/mission-cel.ts` khi đóng gói bằng esbuild, nên trình duyệt và bộ chạy dùng cùng một mã. Lõi CEL là `@marcbachmann/cel-js` vì `cel-js` 0.8.2 mà form-engine đang dùng chưa có macro `map`, `filter`, `exists`, hàm `double()` và phương thức `size()` mà các gói dùng (ghi ở wiki trang 14).

## Giao diện

- `POST /v1/cel/evaluate`, thân `{"items": [{"expression", "inputs", "returnType", "variableTypes"}], "calendar"?}`; trả `{"results": [{"ok", "value", "errorCode", "message"}]}`. Số viết không có phần thập phân được đọc thành kiểu `int` của CEL.
- `GET /healthz`.

mission-core gọi dịch vụ khi đặt `mission.policy.cel-js.url`; bỏ trống thì lần chạy không đạt vì chưa đối chiếu được hai engine, trừ khi tắt `mission.policy.cel-js.required` (chỉ dùng cho môi trường phát triển).

## Chạy

```bash
npm ci
npm test          # đóng gói rồi chạy kiểm thử node:test
npm start         # cổng PORT, mặc định 8080
docker build -f backend/services/cel-js-runner/Dockerfile -t mission/cel-js-runner .   # chạy ở gốc repo
```
