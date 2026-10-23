/* eslint-disable */
/**
 * Sinh tự động bởi openapi-typescript từ contracts/openapi/modules/platform.yaml (WEB-064).
 * Không sửa tay; sửa hợp đồng rồi chạy `npm run api:gen` trong frontend/.
 */
export interface paths {
  '/registry/resolve': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Tra cell, IdP và trạng thái của một tenant
     * @description Shell gọi sau đăng nhập qua điểm vào toàn cục với tenant lấy từ token; APISIX lớp ngoài gọi bằng client nội bộ theo `host` và lưu đệm 5 phút. Máy chủ kiểm tenant khớp claim `tid` của token người dùng, trả URL cổng API của cell và đặt cookie định tuyến ký HMAC hạn 5 phút. Phải có đúng một trong hai tham số `tenant` hoặc `host`. Khi tenant đang chuyển cell hoặc đã chuyển sang cell khác với cell mà request đi tới, trả 421 kèm URL mới để shell chuyển hướng (TL-01 "Định tuyến tới cell"). Khi registry ngừng và tenant chưa có trong đệm, trả 503 với `Retry-After: 30`.
     */
    get: operations['resolveTenantCell'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/registry/cells': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách cell, sức chứa và tải hiện tại
     * @description Liệt kê các cell (`registry.cell`) cùng số tenant và số tài khoản đang phục vụ, để quản trị nền tảng chọn cell khi tiếp nhận hoặc chuyển tenant. Số tài khoản cập nhật hằng đêm từ SVC-02.
     */
    get: operations['listCells'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/registry/tenants': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Tiếp nhận tenant vào danh bạ và gán cell
     * @description Tạo bản ghi `registry.tenant` ở trạng thái `KHOI_TAO`, gán cell đích (`registry.tenant_cell`) và tên miền, rồi khởi động tiến trình tiếp nhận tự động (Keycloak Organizations, KEK trong OpenBao, CSDL hoặc phân vùng, Policy Pack và UI Pack mặc định, kiểm thử khói, phát `sys.tenant.provisioned.v1`). Mỗi bước idempotent. Màn hình SCR-0102 đi qua `POST /config/tenants/{id}/provision` của MOD-08; saga cấp phát gọi thao tác này bằng client nội bộ, quản trị nền tảng cũng gọi được trực tiếp.
     */
    post: operations['createRegistryTenant'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/registry/tenants/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Xem bản ghi danh bạ của một tenant
     * @description Trả thông tin danh bạ, cell hiện hành và lịch sử gắn cell (suy luận: cần để lấy ETag trước khi đề nghị chuyển cell và để theo dõi lần chuyển đang chạy).
     */
    get: operations['getRegistryTenant'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/registry/tenants/{id}/move': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Đề nghị chuyển tenant sang cell khác
     * @description Người đề nghị (quản trị nền tảng thứ nhất) tạo đề nghị chuyển cell ở trạng thái `CHO_DUYET`. Việc chuyển chỉ bắt đầu khi một quản trị nền tảng khác phê duyệt (`.../move/{moveId}/approve`). Khi chạy, tenant ở trạng thái `DANG_CHUYEN_CELL`, dữ liệu sao chép logic sang cell đích, khoá ghi vài phút rồi cập nhật `registry.tenant_cell` (ADR-29, TL-03 "registry").
     */
    post: operations['requestTenantMove'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/registry/tenants/{id}/move/{moveId}/approve': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Phê duyệt lần chuyển cell và khởi động saga chuyển
     * @description Người duyệt thứ hai, khác người đề nghị, phê duyệt đề nghị chuyển cell. Máy chủ chuyển tenant sang `DANG_CHUYEN_CELL`, khởi động saga sao chép logic và chuyển đổi (TL-04), trả 202. Kết thúc thành công thì tenant về `DANG_VAN_HANH`, `registry.tenant_cell` có dòng mới và phát `registry.tenant.changed.v1`.
     */
    post: operations['approveTenantMove'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/registry/tenants/{id}/move/{moveId}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Mã lệnh chuyển cell. */
        moveId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết và tiến độ lệnh chuyển cell
     * @description Theo dõi trạng thái saga sáu bước chuyển cell của tenant (ADR-29: tạo đích, Debezium sao chép ban đầu, bắt kịp WAL, chuyển tenant sang chỉ đọc `TAM_KHOA`, đổi `registry.tenant_cell.active`, mở lại ghi ở cell mới).
     */
    get: operations['getTenantMove'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/registry/tenants/{id}/move/{moveId}/cancel': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Mã lệnh chuyển cell. */
        moveId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Huỷ lệnh chuyển cell trước khi cắt chuyển hoặc quay lui an toàn
     * @description Huỷ đề nghị chuyển cell khi đang `CHO_DUYET`, hoặc dừng/quay lui an toàn trước khi đổi `registry.tenant_cell.active` (ADR-29). Yêu cầu AAL2 và hai người duyệt khi đang `DANG_CHUYEN`.
     */
    post: operations['cancelTenantMove'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/iam/users/{personId}/mfa-recovery-codes': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã nhân sự (`org.person.id`) cần cấp mã khôi phục MFA. */
        personId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Cấp mã khôi phục MFA một lần có thời hạn 15 phút
     * @description Quản trị viên đơn vị xác minh ngoại tuyến và cấp mã khôi phục một lần khi người dùng mất thiết bị SmartOTP (TL-02a QT-00 mục 3.1.5). Mã có thời hạn tối đa 15 phút, chỉ dùng một lần để đăng nhập và đăng ký thiết bị mới, ghi nhật ký kiểm toán.
     */
    post: operations['issueMfaRecoveryCode'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/iam/users/{personId}/mfa-recovery-codes/revoke': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã nhân sự (`org.person.id`) cần thu hồi các mã khôi phục MFA chưa dùng. */
        personId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Thu hồi mọi mã khôi phục MFA đang còn hiệu lực của người dùng */
    post: operations['revokeMfaRecoveryCodes'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/inbox': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Hộp việc cần duyệt của người dùng
     * @description Đọc từ `flowable.wf_task_projection` (không truy vấn `ACT_*`): user task gán trực tiếp cho người dùng và user task có nhóm ứng viên khớp vai trò, đơn vị trong ngữ cảnh đang chọn. Sắp theo hạn tăng dần. `counts` phục vụ MSG-02-12 "Còn {n} bộ chờ duyệt".
     */
    get: operations['listWorkflowInbox'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/tasks/{taskId}/complete': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Hoàn thành một user task với quyết định và lý do
     * @description Người được gán (hoặc thuộc nhóm ứng viên) hoàn thành user task. Biến `decision` được đặt vào tiến trình (mẫu dùng `${decision == 'TRA_LAI'}`, TL-01 chương 8). Thao tác nghiệp vụ có API riêng của module (ví dụ `POST /okr/objectives/{id}/approve`) gọi thao tác này qua lõi; frontend dùng trực tiếp cho user task không có API nghiệp vụ riêng (suy luận). `If-Match` mang `REV_` của `ACT_RU_TASK` (trả trong `rowVersion` của hộp việc).
     */
    post: operations['completeWorkflowTask'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/tasks/batch-complete': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Duyệt hàng loạt tối đa 200 user task
     * @description Hoàn thành nhiều user task cùng một quyết định, mỗi mục một giao dịch; mục lỗi không làm hỏng mục khác. Kết quả từng mục (`BatchItemResult`) nằm ở `resultLocation` của job. Chỉ dùng cho mục không có cảnh báo (SCR-0203 "Duyệt hàng loạt (đủ điều kiện)").
     */
    post: operations['batchCompleteWorkflowTasks'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/instances': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách tiến trình đang chạy theo mẫu, phát hiện tiến trình kẹt
     * @description Giám sát tiến trình (BS-01 SCR-1506). Đọc từ `flowable.wf_task_projection` và chỉ số engine, không truy vấn `ACT_*` lúc cao điểm. Quản trị tenant thấy tiến trình của tenant mình; quản trị nền tảng xem tổng hợp theo cell (tham số `cell`). Tiến trình "kẹt" là tiến trình có user task quá hạn hoặc không có người xử lý hợp lệ (suy luận).
     */
    get: operations['listWorkflowInstances'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/instances/{businessKey}/history': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Lịch sử duyệt của một hồ sơ theo businessKey
     * @description Lịch sử các bước của tiến trình gắn với một đối tượng nghiệp vụ (SCR-0203 trường 6, SCR-1506), lấy từ `ACT_HI_TASKINST` qua bảng chiếu, mức lịch sử `audit`. Máy chủ kiểm người gọi có quyền đọc đối tượng nghiệp vụ tương ứng.
     */
    get: operations['getWorkflowHistory'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/instances/{businessKey}/diagram': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Sơ đồ tiến trình với bước hiện tại
     * @description Trả BPMN của phiên bản mẫu mà tiến trình đang chạy cùng danh sách phần tử đã qua và đang chờ, để frontend vẽ bằng bpmn-js ở chế độ chỉ xem (BS-01 SCR-1506; dạng tương tự `/periods/{id}/lock/diagram` của QT-08).
     */
    get: operations['getWorkflowDiagram'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/tasks/{taskId}/reassign': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Chuyển người xử lý của một user task
     * @description Dùng khi cán bộ nghỉ hoặc chuyển công tác (BS-01 SCR-1506). Người nhận phải có vai trò và đơn vị phù hợp nhóm ứng viên của bước. Ghi sổ cái; không sửa biến tiến trình, không nhảy bước. Người nhận mới được thông báo qua EVT-21 (suy luận).
     */
    post: operations['reassignWorkflowTask'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/tasks/{taskId}/extend': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Gia hạn thời hạn của một bước
     * @description Đặt hạn mới cho user task; ghi sổ cái; không sửa biến khác, không nhảy bước (BS-01 SCR-1506).
     */
    post: operations['extendWorkflowTask'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/sagas/period-close': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Khởi động saga chốt kỳ
     * @description Client nội bộ (MOD-08 sau khi lệnh khoá kỳ được duyệt và quản trị tenant bấm khởi chạy ở SCR-0801) khởi động saga `CHOT_KY` trong `flowable.saga_instance`, idempotent theo (`tenant_id`, `saga_type`, `correlation_id`). Trả 202 và mã theo dõi; các bước `DAT_DANG_KHOA`, `CHO_XA_SU_KIEN`, `CHAY_TINH_DIEM`, `NIEM_PHONG`, `DAT_DA_KHOA` chạy nền, kết thúc thì MOD-08 phát EVT-09.
     */
    post: operations['startPeriodCloseSaga'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/sagas/period-close/{sagaId}/commands': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Gửi lệnh điều khiển saga chốt kỳ
     * @description Client nội bộ MOD-08 chuyển một lệnh trong hộp lệnh `config.period_lock_command` tới saga `CHOT_KY` (QT-08 bước 9, DV-06-07) sau khi giao dịch của MOD-08 đã commit; mỗi dòng hộp lệnh là một lần gọi, với `Idempotency-Key` là mã lệnh nên chuyển lại không làm lệnh có tác dụng hai lần. MOD-08 kiểm vai trò, bốn mắt và MFA; SVC-06 kiểm lại loại saga, trạng thái và người duyệt khác người đề nghị. `BU_TRU` chỉ được trước khi bước ký số lô hoàn tất, sau đó 409 `PERIOD-409-POINT-OF-NO-RETURN` (BR-08-09); bù trừ chạy ngược và kết thúc bằng kỳ trở về `MO` (EVT-26). `CHAY_LAI_LOI` tạo một lô mới chỉ gồm hồ sơ lỗi, mã lô tất định theo mã lệnh (BR-08-11). `CHAP_NHAN_LOI` chấp nhận để lại hồ sơ lỗi kèm lý do để bước tính điểm hoàn tất. Trả 202 với tác vụ của saga.
     */
    post: operations['controlPeriodCloseSaga'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/templates': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Thư viện mẫu luồng
     * @description Danh sách mẫu luồng dùng chung (BS-01 SCR-1505) với phiên bản, trạng thái, số tenant đang dùng (đếm từ `flowable.wf_binding`) và số test. Chỉ đội sản phẩm có vai trò con `wf_template_author` của AC-01 và người phê duyệt AC-09 cấp nền tảng; tenant không thấy.
     */
    get: operations['listWorkflowTemplates'];
    put?: never;
    /**
     * Tạo mẫu luồng mới hoặc phiên bản mới của mẫu
     * @description Lưu BPMN 2.0 và khai báo tham số (JSON Schema) ở trạng thái `NHAP`. Máy chủ chạy lại linter bpmnlint với bốn quy tắc chống mạng nhện của TL-01 và giới hạn tải của BS-01 (≤ 12 user task mỗi nhánh, ≤ 50 phần tử đa thể hiện, một tầng call activity, chỉ external worker task); vi phạm trả 422 với `errors[].rule`. Khi `basedOnTemplateId` có giá trị, tạo phiên bản kế tiếp của cùng `templateCode`.
     */
    post: operations['createWorkflowTemplate'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/templates/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Xem một phiên bản mẫu luồng */
    get: operations['getWorkflowTemplate'];
    /**
     * Sửa mẫu luồng đang ở trạng thái nháp
     * @description Chỉ sửa được phiên bản `NHAP`; phiên bản từ `HIEU_LUC` trở đi không sửa (BS-01 "Vòng đời phiên bản"). Linter chạy lại ở máy chủ như khi tạo. Sửa sau khi đã `DAT_KIEM_THU` đưa mẫu về `NHAP` (suy luận).
     */
    put: operations['updateWorkflowTemplate'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/templates/{id}/test': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Chạy bộ test kịch bản và thử tải rút gọn của mẫu
     * @description Gửi mẫu tới engine Flowable kiểm thử riêng (không phải engine của cell), chạy mọi kịch bản với tham số của mọi `wf_binding` đang dùng mẫu, đo ngân sách ≤ 20 câu SQL mỗi lần hoàn thành user task. Đạt hết thì mẫu chuyển `DAT_KIEM_THU`. Trả 202 và job.
     */
    post: operations['runWorkflowTemplateTests'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/templates/{id}/submit': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Đề nghị phát hành mẫu luồng
     * @description Chỉ khi mẫu `DAT_KIEM_THU`. Hệ thống tạo merge request vào repo mẫu luồng kèm BPMN, khai báo tham số và bộ test, chuyển mẫu sang `CHO_PHE_DUYET` (BS-01 "Đồng bộ với kho mã").
     */
    post: operations['submitWorkflowTemplate'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/templates/{id}/approve': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Phê duyệt phát hành mẫu luồng và triển khai lên các cell
     * @description Người phê duyệt khác người soạn (bốn mắt), xác thực bước lên AAL2. Pipeline ký số gói mẫu (cosign), ghi `wf_template` sang `HIEU_LUC`, phát `config.wf_template.published.v1` và khởi động saga triển khai cuốn chiếu (CELL-NB trước, CELL-HCM và CELL-DTW sau cùng; ngoài giờ cao điểm, không trong 3 ngày cuối kỳ). Trả 202; tiến độ từng cell xem ở `.../rollouts`.
     */
    post: operations['approveWorkflowTemplate'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/workflow/templates/{id}/rollouts': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Tiến độ triển khai một phiên bản mẫu lên từng cell
     * @description Đọc `flowable.wf_template_rollout` (bảng đề xuất của BS-01).
     */
    get: operations['listWorkflowTemplateRollouts'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/notifications/stream': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Luồng SSE thông báo thời gian thực
     * @description Kết nối Server-Sent Events dài (`text/event-stream`, UTF-8). Mỗi sự kiện gồm các dòng:
     *
     *     ```
     *     id: <mã sự kiện của Valkey Streams, tăng dần theo người nhận>
     *     event: <tên sự kiện>
     *     data: <một dòng JSON>
     *
     *     ```
     *
     *     Tên sự kiện và lược đồ của `data`:
     *
     *     - `notification`: thông báo mới, `data` theo lược đồ `Notification`.
     *     - `job`: cập nhật tác vụ dài của chính người dùng (ký lô, khoá kỳ, xuất báo cáo), `data` theo lược đồ
     *       `Job` của `common.yaml`; `JobTracker` trộn luồng này với thăm dò `GET /jobs/{id}`.
     *     - `unread`: số thông báo chưa đọc thay đổi, `data` theo lược đồ `NotificationUnreadEvent`.
     *     - Dòng chú thích `: ping` mỗi 25 giây để giữ kết nối qua proxy; không mang `id`.
     *
     *     Máy chủ gửi `retry: 5000` ở đầu luồng. Khi mất kết nối, trình duyệt tự nối lại và gửi header
     *     `Last-Event-ID`; máy chủ phát lại các sự kiện sau mã đó còn trong Valkey Streams (giữ tối thiểu 10 phút,
     *     suy luận), nếu mã quá cũ thì gửi một sự kiện `unread` để client tải lại hộp thông báo. Nối lại p95 ≤ 10 s,
     *     độ trễ phân phát p95 ≤ 2 s (TL-04 PERF-22). Nội dung không chứa dữ liệu C2. Khi pod tắt, máy chủ đóng
     *     luồng dần trong 30 giây. Token truyền qua `Authorization: Bearer`; client dùng `fetch` đọc luồng (qua
     *     `@vt/api-client`) vì `EventSource` không gửi được header.
     */
    get: operations['streamNotifications'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/notifications': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Hộp thông báo của người dùng
     * @description Thông báo trong ứng dụng của chính người gọi (`notify.notification`, giữ 90 ngày), mới nhất trước, chưa đọc lên trước khi `unread=true`. Trang chủ SCR-0002 lấy tối đa 5 dòng. Tiêu đề và nội dung được dựng từ `notify.template` kênh `INAPP` theo ngôn ngữ `vi` (suy luận).
     */
    get: operations['listNotifications'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/notifications/read': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Đánh dấu thông báo đã đọc
     * @description Đặt `read_at` cho các thông báo trong `ids`, hoặc mọi thông báo chưa đọc khi `all = true` (nút "Đánh dấu đã đọc" của SCR-0002). Thao tác lặp lại không đổi kết quả nên không cần `If-Match`. Phải có đúng một trong hai trường.
     */
    post: operations['markNotificationsRead'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/notifications/devices': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Danh sách thiết bị nhận thông báo đẩy của người dùng hiện hành */
    get: operations['listMyPushDevices'];
    put?: never;
    /** Đăng ký hoặc làm mới token thiết bị nhận thông báo đẩy (FCM/APNs/Web Push) */
    post: operations['registerPushDevice'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/notifications/devices/{deviceId}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Định danh thiết bị đẩy đã đăng ký. */
        deviceId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    post?: never;
    /** Huỷ đăng ký thiết bị nhận thông báo đẩy khi đăng xuất hoặc thu hồi phiên */
    delete: operations['unregisterPushDevice'];
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/notifications/preferences': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Tuỳ chọn kênh thông báo và khung giờ yên lặng của người dùng */
    get: operations['getNotificationPreferences'];
    /** Cập nhật tuỳ chọn kênh thông báo của người dùng */
    put: operations['updateNotificationPreferences'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ai/suggestions': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách gợi ý AI theo loại, ví dụ tóm tắt tuần
     * @description Đọc `ai.suggestion_log` của người gọi hoặc trong phạm vi quyền đọc của người gọi. Dùng cho khối "Tóm tắt tuần" của SCR-0002 (chỉ dữ liệu của chính người dùng) và SCR-1601 (`kind = TOM_TAT_TUAN`, theo đơn vị trong phạm vi). Không có tóm tắt thì trả danh sách rỗng ("Chưa có tóm tắt tuần này").
     */
    get: operations['listAiSuggestions'];
    put?: never;
    /**
     * Yêu cầu một gợi ý AI chỉ đọc
     * @description Điểm vào chung của trợ lý cho các ca dùng không có đường dẫn riêng (hỏi đáp quy định, giải thích điểm số, tóm tắt văn bản). ai-gateway xác thực, kiểm quyền qua OPA, áp rào chắn đầu vào, truy xuất RAG trong phạm vi quyền của người dùng (token exchange), gọi mô hình, áp rào chắn đầu ra, ghi `ai.suggestion_log`. Trả 200 khi xong trong thời gian chờ; trả 202 kèm job khi hàng đợi suy luận đầy (MSG-17-03), kết quả ở `GET /ai/suggestions/{id}`. Yêu cầu chấm điểm, đề xuất xếp loại hay nhận xét cán bộ bị chặn (BR-17-04): trả 200 với `blocked` (MSG-17-02) và ghi `ai.guardrail_event`.
     */
    post: operations['requestAiSuggestion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ai/suggestions/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Xem một gợi ý AI
     * @description Đọc một dòng `ai.suggestion_log` của người gọi; là `resultLocation` của job khi yêu cầu gợi ý trả 202 (suy luận).
     */
    get: operations['getAiSuggestion'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ai/suggestions/{id}/sources': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Dữ liệu và nguồn AI đã dùng cho một gợi ý
     * @description Hiển thị các văn bản, truy vấn và số liệu mà gợi ý đã dùng (`ai.suggestion_log.sources_json`, `chunk_ids`), lọc lại theo quyền đọc hiện tại của người xem; nguồn ngoài phạm vi trả dạng che.
     */
    get: operations['listAiSuggestionSources'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ai/suggestions/{id}/decision': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Ghi quyết định của người dùng với một gợi ý AI
     * @description Ghi `ai.suggestion_log.user_action` (`CHAP_NHAN`, `SUA`, `BO_QUA`), lý do, danh sách ô đã sửa và người chấp nhận. Thao tác này chỉ ghi nhật ký của SVC-14; nội dung nghiệp vụ được ghi bằng API của module dưới danh tính người dùng (ví dụ `POST /tasks/from-document`, `PUT /okr/objectives/{id}`), BR-02-11, BR-05-05. Một đường dẫn duy nhất thay cho `/accept` và `/dismiss` của SCR-1701. Với gợi ý trích nhiệm vụ (`use_case = GOI_Y_NHIEM_VU`) phản hồi là `TaskSuggestion` của tasks.yaml mà frontend SCR-0502 đang dùng (`decision = BO_QUA` cho ra `DA_BO`); với ca dùng khác là `AiSuggestion`. Không dùng `If-Match` vì `ai.suggestion_log` không có `row_version`; quyết định đã ghi rồi thì trả 409.
     */
    post: operations['decideAiSuggestion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ai/extractions': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Kết quả AI trích nhiệm vụ từ một văn bản đến
     * @description SVC-14 tự trích khi nhận EVT-24 (QT-05 bước 2, ≤ 1 phút) và ghi mỗi nhiệm vụ một dòng `ai.suggestion_log` (`use_case = GOI_Y_NHIEM_VU`). Thao tác trả kết quả cho SCR-0502; khi đang trích trả `status = DANG_TRICH` với danh sách rỗng, khi không trích được trả `KHONG_TRICH_DUOC` (MSG-05-10). Đường dẫn là suy luận và đã được frontend dùng (wiki/05a).
     */
    get: operations['getDocExtraction'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ai/okr/review': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Trợ lý AI soát KR mơ hồ hoặc giống nhiệm vụ
     * @description Gửi nội dung mục tiêu và KR đang soạn (không có dữ liệu C3) để soát theo BR-02-05, BR-02-06, BR-17-05. Mỗi phát hiện có một `suggestionId` riêng để ghi quyết định dùng hay bỏ. Gợi ý chỉ điền vào ô ở trình duyệt; ghi vào OKR do người soạn lưu (BR-02-11). Trả 202 khi hàng đợi đầy.
     */
    post: operations['reviewOkrKeyResults'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ai/assist/okr': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Hỏi trợ lý AI soạn mục tiêu và KR từ văn bản căn cứ
     * @description Trợ lý soạn O và KR có chỉ tiêu đo được bám văn bản căn cứ, soát KR mơ hồ, phát hiện KR trùng đơn vị khác (QT-17 bước 2–5). Không đề xuất quá 3 mục tiêu (BR-17-06). Trả 200 với các gợi ý có nhãn và nguồn, 202 khi hàng đợi đầy (MSG-17-03), hoặc 200 với `blocked` khi rào chắn chặn (MSG-17-02).
     */
    post: operations['assistOkrDraft'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ai/evidence/extract': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Đọc văn bản minh chứng đã tải lên và gợi ý nhiệm vụ khớp
     * @description OCR tệp PDF đã tải lên qua `/files/uploads`, trích số ký hiệu, ngày ban hành, trích yếu và gợi ý nhiệm vụ đang mở của người nộp có độ khớp cao nhất. Các ô do AI điền chỉ được ghi khi người nộp xác nhận và nộp qua `/evidences/{id}/versions/{v}/submit`. Ảnh không đủ nét trả 422 (MSG-07-06); độ khớp dưới 0,5 thì `matchedTask` rỗng (MSG-07-08).
     */
    post: operations['extractEvidenceFields'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ai/speech-to-text': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Chuyển giọng nói tiếng Việt thành văn bản
     * @description Dùng khi thiết bị không nhận dạng tại chỗ (SCR-0302 "Nói để nhập", SCR-1803 "Nói"). Âm thanh không lưu sau khi chuyển; văn bản trả về được hiển thị để người dùng xác nhận trước khi lưu (MSG-18-10).
     */
    post: operations['transcribeSpeech'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ai/ocr': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Nhận dạng chữ tiếng Việt (OCR) từ tệp ảnh hoặc PDF quét của minh chứng/văn bản
     * @description Tác vụ nội bộ cho `evidence-service` (SVC-08) và `search-indexer` (SVC-16) bóc văn bản từ tệp ảnh hoặc PDF quét thuộc mức C1/C2 (tuyệt đối từ chối C3 theo BR-17-07). Trả 200 khi tệp nhỏ hoặc 202 kèm `Location` khi xử lý bất đồng bộ.
     */
    post: operations['ocrEvidenceObject'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/analytics/query': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Truy vấn chỉ số chuẩn trên lớp ngữ nghĩa
     * @description Truy vấn đọc (dùng POST vì thân truy vấn phức tạp) chỉ trên measures, dimensions công bố của lớp ngữ nghĩa Cube (BR-16-02, không SQL tự do). analytics-api thêm securityContext gồm tenant và `org_path` của người xem (BR-16-01), lưu đệm Valkey 60 giây. Trả kèm thời điểm dữ liệu; trễ quá 5 phút thì `stale = true` (MSG-16-01, BR-16-08). Không có cột C2 rõ hay C3.
     */
    post: operations['queryAnalytics'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/analytics/semantic-model': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh mục chỉ số và chiều của lớp ngữ nghĩa
     * @description Danh mục measures, dimensions công bố của phiên bản lớp ngữ nghĩa hiện hành (Cube meta API), cho vùng 1 của SCR-1602. Chiều đi kèm danh sách chỉ số hỗ trợ để giao diện chặn sớm kết hợp sai (MSG-16-04).
     */
    get: operations['getSemanticModel'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/analytics/dashboards': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Dashboard của tôi và dashboard được chia sẻ */
    get: operations['listDashboards'];
    put?: never;
    /**
     * Tạo dashboard tự dựng
     * @description Lưu định nghĩa dashboard mới (`config.dashboard_def`, không chứa dữ liệu). Tối đa 20 widget, mỗi widget tối đa 2 chiều, chỉ chỉ số và chiều công bố. Tên không trùng trong không gian của người dùng.
     */
    post: operations['createDashboard'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/analytics/dashboards/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Định nghĩa và dữ liệu của một dashboard
     * @description Trả định nghĩa dashboard (có ETag) và số liệu từng widget tính cho kỳ, đơn vị chọn, với securityContext của chính người xem (người được chia sẻ chỉ thấy số liệu trong phạm vi của họ, MSG-16-05).
     */
    get: operations['getDashboard'];
    /**
     * Lưu dashboard
     * @description Thay toàn bộ định nghĩa; chỉ người tạo được sửa.
     */
    put: operations['updateDashboard'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/analytics/dashboards/{id}/shares': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Chia sẻ dashboard
     * @description Đặt phạm vi chia sẻ (`RIENG`, `DON_VI`, `TENANT`) và đơn vị được xem. Chia sẻ chỉ chia sẻ định nghĩa; người được chia sẻ thấy số liệu trong phạm vi của chính họ (MSG-16-05). Người nhận được thông báo qua EVT-21.
     */
    post: operations['shareDashboard'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/analytics/reports': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Xuất báo cáo bảng điều hành
     * @description Sinh báo cáo PDF hoặc XLSX theo mẫu trong phạm vi của người xem, chạy nền ≤ 1 phút (QT-16 bước 8).
     */
    post: operations['exportAnalyticsReport'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/analytics/trace/{objectType}/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Chuỗi truy vết từ điểm tới minh chứng và ngược lên căn cứ
     * @description Trả chuỗi tám mắt xích quanh đối tượng chọn: điểm, cấu phần, KPI hoặc KR, sản phẩm, minh chứng, kế hoạch, mục tiêu O, căn cứ (BR-16-03). Ba mắt xích đầu lấy từ kho phân tích, các mắt xích sau lấy từ mission-core theo mã có kiểm quyền từng mắt xích (BR-16-04). Mắt xích ngoài phạm vi trả `masked = true` với MSG-16-06, chuỗi vẫn tiếp tục. Mở mắt xích minh chứng được ghi sổ cái; nội dung tệp mở qua `GET /evidences/{id}/versions/{v}/content` của products.yaml.
     */
    get: operations['getTraceChain'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/analytics/trace/exports': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Xuất chuỗi bằng chứng JSON và PDF
     * @description Xuất chuỗi truy vết của một đối tượng kèm mã băm từng mắt xích và mã bản ghi sổ cái, chỉ gồm các mắt xích người xuất có quyền. Việc xuất được ghi `sys.audit.v1`.
     */
    post: operations['exportTraceChain'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/analytics/embed-tokens': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Cấp guest token ngắn hạn để nhúng biểu đồ Superset có khoá phạm vi `org_path`
     * @description Sinh JWT ngắn hạn (tối đa 5 phút) cho khung nhúng Apache Superset ở chế độ tự khai thác chỉ số (TL-01 mục SVC-15), gắn chặt điều kiện RLS `tenant_id` và tiền tố `org_path` của người xem (BR-16-01, BR-16-04).
     */
    post: operations['createAnalyticsEmbedToken'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/search': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Tìm kiếm toàn văn nhiệm vụ, văn bản chỉ đạo, danh mục công việc
     * @description Tìm trên OpenSearch (bí danh chỉ mục lọc theo `tenant_id` ở cell pool). Chỉ mục không chứa C2, C3 nên kết quả chỉ có trường C0, C1; đoạn trích là văn bản thuần, phần khớp đánh dấu bằng vị trí ký tự (`highlights`), không trả HTML. Kết quả được kiểm quyền lại ở lõi; mục ngoài phạm vi bị loại trước khi trả. Không có tổng số bản ghi.
     */
    get: operations['searchFullText'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
}
export type webhooks = Record<string, never>;
export interface components {
  schemas: {
    /**
     * @description Mô hình thuê bao (`registry.tenant.tenancy_model`, `registry.cell.tenancy_model`, ADR-07).
     * @enum {string}
     */
    RegistryTenancyModel: 'SILO' | 'BRIDGE' | 'POOL' | 'AIRGAP';
    /**
     * @description Trạng thái vòng đời tenant (`registry.tenant.status`, TL-02a QT-01).
     * @enum {string}
     */
    RegistryTenantStatus:
      | 'KHOI_TAO'
      | 'DANG_CAP_PHAT'
      | 'DANG_CAU_HINH'
      | 'SAN_SANG'
      | 'DANG_VAN_HANH'
      | 'TAM_NGUNG'
      | 'DANG_CHUYEN_CELL'
      | 'DA_CHAM_DUT';
    /**
     * @description Trạng thái cell (`registry.cell.status`).
     * @enum {string}
     */
    CellStatus: 'HOAT_DONG' | 'BAO_TRI' | 'NGUNG';
    /**
     * @description Trung tâm dữ liệu chính (`registry.cell.primary_dc`).
     * @enum {string}
     */
    DataCenter: 'HOALAC' | 'PHIANAM' | 'TAICHO';
    /**
     * @description Loại tên miền (`registry.tenant_domain.kind`).
     * @enum {string}
     */
    DomainKind: 'PRIMARY' | 'ALIAS' | 'EMBED';
    RegistryCellRef: {
      id: components['schemas']['Uuid'];
      /** @example CELL-HN */
      code: string;
      /** Format: uri */
      apiBaseUrl?: string;
    };
    /** @description IdP liên kết của tenant (`registry.tenant_idp`), chỉ phần cần cho khám phá IdP. */
    TenantIdpInfo: {
      idpAlias: string;
      /** @enum {string} */
      protocol: 'OIDC' | 'SAML';
      issuer: string;
      isVneid: boolean;
      emailDomains?: string[];
    };
    /**
     * @description Kết quả tra cell của tenant (TL-01 SVC-03 "Trả cell, URL IdP, trạng thái").
     * @example {
     *       "tenantId": "0192f1a2-3b4c-7d5e-8f60-718293a4b5c6",
     *       "tenantCode": "dukcqtw",
     *       "status": "DANG_VAN_HANH",
     *       "tenancyModel": "BRIDGE",
     *       "cell": {
     *         "id": "0192f1a2-0000-7c00-8a00-00000000c001",
     *         "code": "CELL-HN",
     *         "apiBaseUrl": "https://api.cell-hn.okrkpi.vn"
     *       },
     *       "cacheTtlSeconds": 300
     *     }
     */
    TenantResolution: {
      tenantId: components['schemas']['Uuid'];
      tenantCode: string;
      tenantName?: string;
      status: components['schemas']['RegistryTenantStatus'];
      tenancyModel: components['schemas']['RegistryTenancyModel'];
      cell: components['schemas']['RegistryCellRef'];
      idps?: components['schemas']['TenantIdpInfo'][];
      /** @description Thời gian được lưu đệm kết quả, mặc định 300. */
      cacheTtlSeconds: number;
    };
    /**
     * @description Cell (`registry.cell`) kèm tải hiện tại.
     * @example {
     *       "id": "0192f1a2-0000-7c00-8a00-00000000c001",
     *       "code": "CELL-HN",
     *       "name": "Cell Hà Nội",
     *       "primaryDc": "HOALAC",
     *       "tenancyModel": "BRIDGE",
     *       "apiBaseUrl": "https://api.cell-hn.okrkpi.vn",
     *       "capacityAccounts": 1500000,
     *       "status": "HOAT_DONG"
     *     }
     */
    RegistryCell: {
      id: components['schemas']['Uuid'];
      code: string;
      name: string;
      primaryDc: components['schemas']['DataCenter'];
      standbyDc?: string | null;
      tenancyModel: components['schemas']['RegistryTenancyModel'];
      apiBaseUrl: string;
      capacityAccounts: number;
      status: components['schemas']['CellStatus'];
      /** @description Số tenant đang gắn (suy luận). */
      tenantCount?: number;
      /** @description Tổng tài khoản các tenant của cell (suy luận). */
      accountCount?: number;
    };
    CellPage: {
      items: components['schemas']['RegistryCell'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    TenantDomain: {
      domain: string;
      kind: components['schemas']['DomainKind'];
      verifiedAt?: components['schemas']['DateTime'] | null;
    };
    /** @description Lần gắn cell hiện hành (`registry.tenant_cell`). */
    TenantCellBinding: {
      id: components['schemas']['Uuid'];
      code: string;
      databaseName: string;
      validFrom: components['schemas']['DateTime'];
    };
    RegistryTenantCreate: {
      code: string;
      name: string;
      sector: components['schemas']['TenantSector'];
      tenancyModel: components['schemas']['RegistryTenancyModel'];
      cellId: components['schemas']['Uuid'];
      flows: components['schemas']['Flow'][];
      maxAccounts: number;
      expectedAccounts?: number;
      contractRef?: string;
      contractValidTo?: components['schemas']['Date'];
      onboardingCode?: string;
      domains?: {
        domain: string;
        kind: components['schemas']['DomainKind'];
      }[];
    };
    /**
     * @description Bản ghi danh bạ tenant (`registry.tenant`). Không chứa `kek_ref`.
     * @example {
     *       "id": "0192f1a2-3b4c-7d5e-8f60-718293a4b5c6",
     *       "code": "dukcqtw",
     *       "name": "Đảng uỷ Khối các cơ quan Trung ương",
     *       "sector": "DANG",
     *       "tenancyModel": "BRIDGE",
     *       "status": "DANG_VAN_HANH",
     *       "flows": [
     *         "DANG"
     *       ],
     *       "maxAccounts": 60000,
     *       "accountCount": 51870,
     *       "rowVersion": 5
     *     }
     */
    RegistryTenant: {
      id: components['schemas']['Uuid'];
      code: string;
      name: string;
      sector: components['schemas']['TenantSector'];
      tenancyModel: components['schemas']['RegistryTenancyModel'];
      status: components['schemas']['RegistryTenantStatus'];
      flows: components['schemas']['Flow'][];
      maxAccounts: number;
      expectedAccounts?: number;
      accountCount: number;
      contractRef?: string;
      contractValidTo?: components['schemas']['Date'];
      onboardingCode?: string;
      currentCell?: components['schemas']['TenantCellBinding'];
      domains?: components['schemas']['TenantDomain'][];
      rowVersion: components['schemas']['RowVersion'];
      createdAt?: components['schemas']['DateTime'];
    };
    /**
     * @description Trạng thái đề nghị chuyển cell (suy luận; TL-03 chưa có bảng riêng, vết nằm ở `registry.tenant_status_log`).
     * @enum {string}
     */
    TenantMoveStatus: 'CHO_DUYET' | 'DANG_CHUYEN' | 'HOAN_TAT' | 'THAT_BAI' | 'DA_HUY';
    TenantMoveCreate: {
      targetCellId: components['schemas']['Uuid'];
      targetTenancyModel?: components['schemas']['RegistryTenancyModel'];
      /** @description `registry.tenant_cell.data_region`. */
      dataRegion?: string;
      /** @description Lý do, số văn bản (`tenant_status_log.reason`). */
      reason: string;
      plannedAt?: components['schemas']['DateTime'];
    };
    TenantMove: {
      id: components['schemas']['Uuid'];
      tenantId: components['schemas']['Uuid'];
      fromCell: components['schemas']['RegistryCellRef'];
      toCell: components['schemas']['RegistryCellRef'];
      targetTenancyModel?: components['schemas']['RegistryTenancyModel'];
      dataRegion?: string;
      status: components['schemas']['TenantMoveStatus'];
      reason: string;
      plannedAt?: components['schemas']['DateTime'];
      requestedBy: components['schemas']['Uuid'];
      requestedAt: components['schemas']['DateTime'];
      approvedBy?: components['schemas']['Uuid'];
      approvedAt?: components['schemas']['DateTime'];
      /**
       * @description Bước hiện hành của saga 6 bước chuyển cell (ADR-29).
       * @enum {string|null}
       */
      currentStep?:
        | 'PROVISION_TARGET'
        | 'INITIAL_SNAPSHOT'
        | 'WAL_CATCHUP'
        | 'READ_ONLY_FREEZE'
        | 'SWITCH_ACTIVE_CELL'
        | 'REOPEN_WRITE'
        | null;
      rowVersion: components['schemas']['RowVersion'];
    };
    /** @description Ý kiến kèm thao tác phê duyệt hoặc đề nghị (tuỳ chọn). */
    ApprovalComment: {
      comment?: string;
    };
    /**
     * @description Quyết định khi hoàn thành user task, đặt vào biến `decision` của tiến trình (TL-01 chương 8 dùng `TRA_LAI`; `DUYET` là suy luận).
     * @enum {string}
     */
    WorkflowDecision: 'DUYET' | 'TRA_LAI';
    /**
     * @description Một user task đang chờ (`flowable.wf_task_projection`).
     * @example {
     *       "taskId": "7f3c2a10-8b1e-11f1-9c5a-0242ac120002",
     *       "title": "Phê duyệt OKR Quý I/2027 của Ban Tổ chức",
     *       "businessType": "OKR_OBJECTIVE",
     *       "businessId": "0192f2c4-5d6e-7f80-91a2-b3c4d5e6f701",
     *       "businessKey": "OKR_OBJECTIVE:0192f2c4-5d6e-7f80-91a2-b3c4d5e6f701",
     *       "createdAt": "2027-01-06T09:12:00+07:00",
     *       "rowVersion": 1
     *     }
     */
    WorkflowInboxItem: {
      taskId: string;
      title: string;
      /** @description Loại đối tượng nghiệp vụ, ví dụ `OKR_OBJECTIVE`, `PLAN`, `APPRAISAL_PROFILE`, `COMPLAINT`. */
      businessType: string;
      businessId: components['schemas']['Uuid'];
      businessKey: string;
      /** @description Mã mẫu luồng (suy luận). */
      templateCode?: string;
      assigneeId?: components['schemas']['Uuid'] | null;
      candidateRole?: string | null;
      candidateUnitId?: components['schemas']['Uuid'] | null;
      periodId?: components['schemas']['Uuid'];
      dueAt?: components['schemas']['DateTime'] | null;
      createdAt: components['schemas']['DateTime'];
      /** @description Quá hạn so với `due_at` (suy luận). */
      overdue?: boolean;
      /** @description Đường dẫn mở màn hình xử lý (suy luận). */
      deepLink?: string;
      rowVersion: components['schemas']['RowVersion'];
    };
    WorkflowInboxPage: {
      items: components['schemas']['WorkflowInboxItem'][];
      nextCursor: components['schemas']['NextCursor'];
      /** @description Số đếm cho nhắc MSG-02-12 và thẻ trang chủ. */
      counts?: {
        total?: number;
        overdue?: number;
        dueToday?: number;
      };
    };
    WorkflowTaskCompletion: {
      decision: components['schemas']['WorkflowDecision'];
      /** @description Bắt buộc khi `TRA_LAI` (BR-02-09, MSG-02-10). */
      comment?: string;
    };
    WorkflowTaskResult: {
      taskId: string;
      businessKey: string;
      decision: components['schemas']['WorkflowDecision'];
      completedAt: components['schemas']['DateTime'];
      processEnded?: boolean;
    };
    WorkflowBatchCompletion: {
      decision: components['schemas']['WorkflowDecision'];
      comment?: string;
      items: {
        taskId: string;
        rowVersion: components['schemas']['RowVersion'];
      }[];
    };
    /**
     * @description Loại bước trong lịch sử tiến trình (suy luận).
     * @enum {string}
     */
    WorkflowHistoryAction:
      | 'TAO_TIEN_TRINH'
      | 'DUYET'
      | 'TRA_LAI'
      | 'CHUYEN_NGUOI_XU_LY'
      | 'GIA_HAN'
      | 'NHAC_HAN'
      | 'KET_THUC';
    /**
     * @example {
     *       "businessKey": "OKR_OBJECTIVE:0192f2c4-5d6e-7f80-91a2-b3c4d5e6f701",
     *       "businessType": "OKR_OBJECTIVE",
     *       "status": "DANG_CHAY",
     *       "entries": [
     *         {
     *           "at": "2027-01-05T16:20:00+07:00",
     *           "action": "TAO_TIEN_TRINH",
     *           "actorName": "Lê Minh Châu"
     *         }
     *       ]
     *     }
     */
    WorkflowHistory: {
      businessKey: string;
      businessType: string;
      templateCode?: string;
      templateVersion?: number;
      /**
       * @description (suy luận)
       * @enum {string}
       */
      status: 'DANG_CHAY' | 'DA_KET_THUC';
      entries: {
        at: components['schemas']['DateTime'];
        action: components['schemas']['WorkflowHistoryAction'];
        actorId?: components['schemas']['Uuid'];
        actorName?: string;
        activityName?: string;
        comment?: string;
      }[];
    };
    WorkflowInstanceSummary: {
      businessKey: string;
      businessType: string;
      businessId: components['schemas']['Uuid'];
      title?: string;
      templateCode: string;
      templateVersion: number;
      currentActivityName?: string;
      currentTaskId?: string | null;
      assigneeId?: components['schemas']['Uuid'] | null;
      candidateRole?: string | null;
      startedAt: components['schemas']['DateTime'];
      dueAt?: components['schemas']['DateTime'];
      stuck: boolean;
      stuckReason?: string;
      cellCode?: string;
      /** @description `REV_` của user task đang chờ (`flowable.wf_task_projection.row_version`), gửi lại trong `If-Match` khi chuyển người xử lý hoặc gia hạn bước (bổ sung DV-06-10). */
      rowVersion?: components['schemas']['RowVersion'];
    };
    WorkflowInstancePage: {
      items: components['schemas']['WorkflowInstanceSummary'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    WorkflowDiagram: {
      businessKey: string;
      templateCode?: string;
      templateVersion?: number;
      /** @description BPMN 2.0 XML; frontend hiển thị bằng bpmn-js ở chế độ chỉ xem. */
      bpmnXml: string;
      activeActivityIds: string[];
      completedActivityIds: string[];
    };
    WorkflowTaskReassign: {
      assigneeId: components['schemas']['Uuid'];
      reason: string;
    };
    WorkflowTaskExtend: {
      dueAt: components['schemas']['DateTime'];
      reason: string;
    };
    /** @description Tham số saga `CHOT_KY` (`flowable.saga_instance.context`). */
    PeriodCloseSagaStart: {
      periodId: components['schemas']['Uuid'];
      /** @description Lệnh khoá kỳ đã duyệt (`config.period_lock_order`), dùng làm `correlation_id`. */
      lockOrderId: components['schemas']['Uuid'];
      /** @description Danh sách đơn vị; rỗng là toàn tenant. */
      unitIds?: components['schemas']['Uuid'][];
      requestedBy: components['schemas']['Uuid'];
    };
    /** @description Một lệnh của hộp lệnh `config.period_lock_command` chuyển tới saga `CHOT_KY`. */
    PeriodCloseSagaCommand: {
      /**
       * @description `TAM_DUNG`, `TIEP_TUC`, `BU_TRU` cùng tên điều khiển của khung saga; `CHAY_LAI_LOI` chạy lại hồ sơ lỗi; `CHAP_NHAN_LOI` chấp nhận để lại hồ sơ lỗi kèm lý do.
       * @enum {string}
       */
      command: 'TAM_DUNG' | 'TIEP_TUC' | 'CHAY_LAI_LOI' | 'CHAP_NHAN_LOI' | 'BU_TRU';
      /** @description Mã dòng hộp lệnh (`config.period_lock_command.id`). */
      commandId: components['schemas']['Uuid'];
      /** @description Người đề nghị; với `BU_TRU` là người lập đề nghị huỷ. */
      requestedBy: components['schemas']['Uuid'];
      /** @description Người duyệt thứ hai, bắt buộc với `BU_TRU`. */
      approvedBy?: components['schemas']['Uuid'];
      /** @description Hồ sơ cần chạy lại với `CHAY_LAI_LOI`; rỗng là toàn bộ hàng đợi lỗi. */
      profileIds?: components['schemas']['Uuid'][];
      /** @description Lý do, bắt buộc với `CHAP_NHAN_LOI`. */
      reason?: string;
    };
    /**
     * @description Trạng thái `flowable.wf_template.status` mở rộng theo BS-01.
     * @enum {string}
     */
    WorkflowTemplateStatus: 'NHAP' | 'DAT_KIEM_THU' | 'CHO_PHE_DUYET' | 'HIEU_LUC' | 'NGUNG';
    WorkflowTemplateSummary: {
      id: components['schemas']['Uuid'];
      templateCode: string;
      versionNo: number;
      status: components['schemas']['WorkflowTemplateStatus'];
      bpmnSha256?: string;
      tenantBindingCount?: number;
      testCount?: number;
      updatedAt?: components['schemas']['DateTime'];
      rowVersion: components['schemas']['RowVersion'];
    };
    /**
     * @description Một phiên bản mẫu luồng (`flowable.wf_template` cùng cột bổ sung của BS-01).
     * @example {
     *       "id": "0192f5f7-8091-72a3-b4c5-d6e7f8091a34",
     *       "templateCode": "duyet-ho-so-danh-gia",
     *       "versionNo": 3,
     *       "status": "HIEU_LUC",
     *       "rowVersion": 6
     *     }
     */
    WorkflowTemplate: components['schemas']['WorkflowTemplateSummary'] & {
      bpmnXml?: string;
      /** @description Khai báo tham số dạng JSON Schema (`param_schema`), sinh biểu mẫu ở SCR-1504. */
      paramSchema?: Record<string, never>;
      testSuiteRef?: string;
      policyPackVersionId?: components['schemas']['Uuid'];
      flowableDeploymentId?: string;
      approvedBy?: components['schemas']['Uuid'];
      /**
       * Format: uri
       * @description Merge request vào repo mẫu luồng (suy luận).
       */
      mergeRequestUrl?: string;
    };
    WorkflowTemplatePage: {
      items: components['schemas']['WorkflowTemplateSummary'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    WorkflowTemplateCreate: {
      templateCode: string;
      basedOnTemplateId?: components['schemas']['Uuid'];
      bpmnXml: string;
      paramSchema: Record<string, never>;
    };
    WorkflowTemplateUpdate: {
      bpmnXml: string;
      paramSchema: Record<string, never>;
    };
    WorkflowTemplateRolloutList: {
      items: {
        cellCode: string;
        /**
         * @description `flowable.wf_template_rollout.status` (BS-01).
         * @enum {string}
         */
        status: 'CHO' | 'DA_TRIEN_KHAI' | 'LOI' | 'DA_BU_TRU';
        flowableDeploymentId?: string | null;
        deployedAt?: components['schemas']['DateTime'] | null;
      }[];
    };
    /**
     * @description Thông báo trong ứng dụng (`notify.notification`); không chứa dữ liệu C2.
     * @example {
     *       "id": "0192f6a8-9102-73b4-c5d6-e7f8091a2b45",
     *       "templateCode": "OKR_CHO_DUYET",
     *       "title": "Có bộ OKR cần phê duyệt",
     *       "body": "Ban Tổ chức trình OKR Quý I/2027, hạn duyệt 08/01/2027.",
     *       "deepLink": "/okr/phe-duyet",
     *       "priority": "CAO",
     *       "createdAt": "2027-01-06T09:12:05+07:00",
     *       "readAt": null
     *     }
     */
    Notification: {
      id: components['schemas']['Uuid'];
      templateCode: string;
      /** @description Tiêu đề dựng từ `notify.template.subject` (suy luận). */
      title: string;
      /** @description Nội dung dựng từ `notify.template.body` (suy luận). */
      body?: string;
      /** @description Tham số mẫu (không chứa C2). */
      params?: {
        [key: string]: unknown;
      };
      deepLink?: string | null;
      /** @description Mức ưu tiên (`notify.notification.priority`); TL-03 chưa liệt kê giá trị. */
      priority: string;
      createdAt: components['schemas']['DateTime'];
      readAt: components['schemas']['DateTime'] | null;
    };
    NotificationPage: {
      items: components['schemas']['Notification'][];
      nextCursor: components['schemas']['NextCursor'];
      unreadCount: number;
    };
    NotificationReadRequest: {
      ids?: components['schemas']['Uuid'][];
      all?: boolean;
    };
    NotificationReadResult: {
      updated: number;
      unreadCount: number;
    };
    /** @description Dữ liệu của sự kiện SSE `unread`. */
    NotificationUnreadEvent: {
      unreadCount: number;
      /** @description `true` khi client cần tải lại hộp thông báo (mã `Last-Event-ID` quá cũ). */
      reload?: boolean;
    };
    /**
     * @description Loại nguồn trích dẫn (suy luận từ `ai.suggestion_log.sources_json`).
     * @enum {string}
     */
    AiSourceKind: 'VAN_BAN' | 'DU_LIEU' | 'TRUY_VAN';
    /** @description Một nguồn mà gợi ý AI đã dùng (BR-17-02). */
    AiSource: {
      kind: components['schemas']['AiSourceKind'];
      title: string;
      /** @description Trang, mục trong văn bản, ví dụ `tr. 4–6`. */
      location?: string;
      /** @description Mã văn bản, mã đoạn `ai.doc_chunk` hoặc tên chỉ số Cube. */
      ref?: string;
      /** @description Đoạn trích hoặc số liệu đã dùng (chỉ C0, C1). */
      excerpt?: string;
      asOf?: components['schemas']['DateTime'];
      /** @description Nguồn ngoài phạm vi quyền hiện tại của người xem. */
      masked?: boolean;
    };
    AiSourceList: {
      suggestionId: components['schemas']['Uuid'];
      items: components['schemas']['AiSource'][];
    };
    /**
     * @description Phản hồi của người dùng với gợi ý (`ai.suggestion_log.user_action`).
     * @enum {string}
     */
    AiUserAction: 'CHAP_NHAN' | 'SUA' | 'BO_QUA';
    /** @description Rào chắn đã chặn yêu cầu (`ai.guardrail_event`). */
    AiGuardrailBlock: {
      /** @enum {string} */
      ruleCode: 'PII_OUT' | 'PROMPT_INJECTION' | 'OUT_OF_SCOPE' | 'C2_REQUEST';
      /** @enum {string} */
      action: 'CHAN' | 'CHE' | 'CANH_BAO';
      /** @example MSG-17-02 */
      msg?: string;
      message: string;
    };
    /**
     * @description Một lượt gợi ý AI (`ai.suggestion_log`), luôn mang nhãn "Nội dung do AI tạo".
     * @example {
     *       "id": "0192f7b9-a213-74c5-d6e7-f8091a2b3c56",
     *       "useCase": "HOI_DAP_QUY_DINH",
     *       "model": "viettel-llm-vi-2026.3",
     *       "createdAt": "2026-10-19T09:40:02+07:00",
     *       "aiGenerated": true,
     *       "label": "Nội dung do AI tạo",
     *       "sources": []
     *     }
     */
    AiSuggestion: {
      id: components['schemas']['Uuid'];
      /** @description Ví dụ `GOI_Y_NHIEM_VU`, `TOM_TAT_VAN_BAN`, `OCR`. */
      useCase: string;
      /** @description Ví dụ `TOM_TAT_TUAN`. */
      kind?: string | null;
      model: string;
      createdAt: components['schemas']['DateTime'];
      /** @constant */
      aiGenerated: true;
      /** @constant */
      label: 'Nội dung do AI tạo';
      outputText?: string | null;
      /** @description Các ý tách dòng (tóm tắt tuần 3 đến 5 ý). */
      items?: string[];
      sources: components['schemas']['AiSource'][];
      confidence?: number | null;
      decision?: components['schemas']['AiUserAction'] | null;
      blocked?: components['schemas']['AiGuardrailBlock'] | null;
    };
    AiSuggestionPage: {
      items: components['schemas']['AiSuggestion'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    AiContextRef: {
      /** @description Loại đối tượng làm ngữ cảnh, ví dụ `OKR_BASIS`, `TASK`, `SCORE`. */
      type: string;
      id: components['schemas']['Uuid'];
    };
    AiSuggestionRequest: {
      /** @description Ca dùng, ví dụ `HOI_DAP_QUY_DINH`, `GIAI_THICH_DIEM`, `TOM_TAT_VAN_BAN` (giá trị ngoài TL-03 là suy luận). */
      useCase: string;
      question: string;
      contextRefs?: components['schemas']['AiContextRef'][];
    };
    /** @description Quyết định của người dùng. Frontend SCR-0502 gửi `{ decision: 'BO_QUA', reason }`. */
    AiSuggestionDecisionRequest: {
      decision: components['schemas']['AiUserAction'];
      /** @description Bắt buộc khi bỏ nhiệm vụ trích (SCR-0502). */
      reason?: string;
      editedFields?: components['schemas']['SuggestionField'][];
      /** @description Bản ghi nhận gợi ý (`ai.suggestion_log.target_ref`), ví dụ `okr.key_result:<id>`. */
      targetRef?: string;
    };
    /**
     * @description Tình trạng trích nhiệm vụ của SVC-14 (QT-05 bước 2, ≤ 1 phút).
     * @enum {string}
     */
    ExtractionStatus: 'DANG_TRICH' | 'XONG' | 'KHONG_TRICH_DUOC';
    /**
     * @description Kết quả trích nhiệm vụ của SVC-14 cho một văn bản (frontend `DocExtraction`).
     * @example {
     *       "sourceDocId": "0192f8ca-b324-75d6-e7f8-091a2b3c4d01",
     *       "status": "DANG_TRICH",
     *       "model": "viettel-llm-vi-2026.3",
     *       "extractedAt": "2026-10-12T15:02:10+07:00",
     *       "suggestions": []
     *     }
     */
    DocExtraction: {
      sourceDocId: components['schemas']['Uuid'];
      status: components['schemas']['ExtractionStatus'];
      model: string;
      extractedAt: components['schemas']['DateTime'];
      suggestions: components['schemas']['TaskSuggestion'][];
    };
    OkrDraftKeyResult: {
      /** @example KR 1.3 */
      code?: string;
      title: string;
      unit?: string | null;
      baselineValue?: number | null;
      targetValue?: number | null;
      weight?: number | null;
    };
    OkrReviewRequest: {
      periodId: components['schemas']['Uuid'];
      objectiveId?: components['schemas']['Uuid'];
      objectiveTitle: string;
      keyResults: components['schemas']['OkrDraftKeyResult'][];
    };
    OkrReviewFinding: {
      suggestionId: components['schemas']['Uuid'];
      krCode: string;
      /**
       * @description (suy luận)
       * @enum {string}
       */
      severity: 'LOI' | 'CANH_BAO';
      /** @example BR-02-05 */
      rule?: string;
      /** @example MSG-02-05 */
      msg?: string;
      message: string;
      rewrites?: string[];
      sources: components['schemas']['AiSource'][];
    };
    /**
     * @example {
     *       "model": "viettel-llm-vi-2026.3",
     *       "createdAt": "2027-01-05T10:12:00+07:00",
     *       "aiGenerated": true,
     *       "label": "Nội dung do AI tạo",
     *       "findings": []
     *     }
     */
    OkrReviewResult: {
      model: string;
      createdAt: components['schemas']['DateTime'];
      /** @constant */
      aiGenerated: true;
      /** @constant */
      label: 'Nội dung do AI tạo';
      findings: components['schemas']['OkrReviewFinding'][];
    };
    OkrAssistRequest: {
      /** @description Câu hỏi cho trợ lý (`prompt_redacted` sau khi che). */
      question: string;
      periodId: components['schemas']['Uuid'];
      unitId: components['schemas']['Uuid'];
      basisIds?: components['schemas']['Uuid'][];
      /** @description Tệp căn cứ PDF, DOCX ≤ 20 MB đã tải lên qua `/files/uploads`; được lập chỉ mục trước khi trả lời. */
      attachmentObjectIds?: components['schemas']['Uuid'][];
      /** @description Bản nháp OKR đang soạn để trợ lý soát. */
      draftObjectiveIds?: components['schemas']['Uuid'][];
    };
    OkrAssistSuggestion: {
      id: components['schemas']['Uuid'];
      /**
       * @description (suy luận)
       * @enum {string}
       */
      kind: 'MUC_TIEU' | 'SOAT_KR' | 'LIEN_KET_NGANG';
      objective?: {
        title?: string;
        basisRef?: string;
        keyResults?: components['schemas']['OkrDraftKeyResult'][];
      };
      krCode?: string;
      text?: string;
      reason?: string;
      sources: components['schemas']['AiSource'][];
    };
    OkrAssistResult: {
      model: string;
      createdAt: components['schemas']['DateTime'];
      /** @constant */
      aiGenerated: true;
      /** @constant */
      label: 'Nội dung do AI tạo';
      answer?: string;
      suggestions: components['schemas']['OkrAssistSuggestion'][];
      blocked?: components['schemas']['AiGuardrailBlock'] | null;
    };
    EvidenceExtractRequest: {
      /** @description Đối tượng đã tải lên (`evidence.object_meta`). */
      objectId: components['schemas']['Uuid'];
    };
    AiTaskMatch: {
      id: components['schemas']['Uuid'];
      code: string;
      title: string;
      confidence: number;
    };
    /**
     * @description Kết quả OCR minh chứng (SCR-0702 trường 2–5); các ô do AI điền chờ người nộp xác nhận.
     * @example {
     *       "suggestionId": "0192f7b9-a213-74c5-d6e7-f8091a2b3ca0",
     *       "objectId": "0192f9db-c435-76e7-f809-1a2b3c4d5e01",
     *       "model": "viettel-ocr-2026.2",
     *       "aiGenerated": true,
     *       "label": "Nội dung do AI tạo",
     *       "docNumber": "47-BC/BTCĐUK"
     *     }
     */
    EvidenceExtraction: {
      suggestionId: components['schemas']['Uuid'];
      objectId: components['schemas']['Uuid'];
      model: string;
      /** @constant */
      aiGenerated: true;
      /** @constant */
      label: 'Nội dung do AI tạo';
      docNumber?: string | null;
      docDate?: components['schemas']['Date'] | null;
      docSummary?: string | null;
      matchedTask?: components['schemas']['AiTaskMatch'] | null;
      alternatives?: components['schemas']['AiTaskMatch'][];
    };
    SpeechToTextRequest: {
      /** @description Đoạn ghi âm ≤ 60 giây, ≤ 2 MB (suy luận). */
      audio: string;
      /** @default vi-VN */
      language: string;
      /**
       * @description Ô đích (suy luận).
       * @enum {string}
       */
      purpose?: 'CHECKIN_VUONG_MAC' | 'TU_DANH_GIA_GHI_CHU' | 'KHAC';
    };
    /**
     * @example {
     *       "text": "Chi bộ 3 chưa nộp biên bản sinh hoạt tháng mười.",
     *       "confidence": 0.91,
     *       "durationMs": 4200,
     *       "model": "viettel-asr-vi-2026.1"
     *     }
     */
    SpeechToTextResult: {
      text: string;
      confidence?: number;
      durationMs?: number;
      model: string;
    };
    AnalyticsFilter: {
      /** @description Tên chiều công bố trong lớp ngữ nghĩa. */
      member: string;
      /** @enum {string} */
      operator: 'equals' | 'notEquals' | 'in' | 'gte' | 'lte';
      values: (string | number)[];
    };
    /** @description Truy vấn trên lớp ngữ nghĩa Cube, chỉ measures và dimensions công bố (BR-16-02). */
    AnalyticsQuery: {
      measures: string[];
      dimensions?: string[];
      filters?: components['schemas']['AnalyticsFilter'][];
      periodId?: components['schemas']['Uuid'];
      unitId?: components['schemas']['Uuid'];
      /**
       * @description Chiều thời gian, ví dụ xu hướng 6 tuần.
       * @enum {string}
       */
      timeGranularity?: 'week' | 'month' | 'quarter' | 'year';
      /** @default false */
      compareToPreviousPeriod: boolean;
      /** @default 50 */
      limit: number;
    };
    AnalyticsColumn: {
      name: string;
      title: string;
      /** @enum {string} */
      type: 'string' | 'number' | 'count' | 'time';
      unit?: string;
    };
    /**
     * @example {
     *       "semanticModelVersion": "2026.4",
     *       "dataAsOf": "2026-11-16T08:55:00+07:00",
     *       "stale": false,
     *       "columns": [
     *         {
     *           "name": "okr.tien_do_o",
     *           "title": "Tiến độ mục tiêu O",
     *           "type": "number",
     *           "unit": "%"
     *         }
     *       ],
     *       "rows": [
     *         [
     *           72
     *         ]
     *       ]
     *     }
     */
    AnalyticsQueryResult: {
      semanticModelVersion: string;
      dataAsOf: components['schemas']['DateTime'];
      /** @description Dữ liệu trễ quá 5 phút (MSG-16-01). */
      stale: boolean;
      columns: components['schemas']['AnalyticsColumn'][];
      rows: (string | number | null)[][];
    };
    SemanticModel: {
      version: string;
      measures: {
        name: string;
        title: string;
        type: string;
        unit?: string;
        /** @description Có liên kết truy vết để đi xuống (SCR-1602 trường 8). */
        drillable?: boolean;
      }[];
      dimensions: {
        name: string;
        title: string;
        type: string;
        supportedMeasures?: string[];
      }[];
    };
    /**
     * @description Kiểu biểu đồ của widget (SCR-1602 trường 6; mã là suy luận).
     * @enum {string}
     */
    DashboardChartType: 'THE_SO' | 'COT' | 'DUONG' | 'THANH' | 'BANG';
    /**
     * @description Phạm vi chia sẻ (`config.dashboard_def.share_scope`).
     * @enum {string}
     */
    DashboardShareScope: 'RIENG' | 'DON_VI' | 'TENANT';
    DashboardWidget: {
      id: string;
      x: number;
      y: number;
      w: number;
      h: number;
      measure: string;
      dimensions?: string[];
      chartType: components['schemas']['DashboardChartType'];
      filters?: components['schemas']['AnalyticsFilter'][];
      /** @default true */
      drillDown: boolean;
    };
    AnalyticsDashboardInput: {
      title: string;
      layout: components['schemas']['DashboardWidget'][];
    };
    AnalyticsDashboardSummary: {
      id: components['schemas']['Uuid'];
      title: string;
      ownerUserId: components['schemas']['Uuid'];
      shareScope: components['schemas']['DashboardShareScope'];
      widgetCount?: number;
      updatedAt: components['schemas']['DateTime'];
    };
    AnalyticsDashboardPage: {
      items: components['schemas']['AnalyticsDashboardSummary'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /**
     * @description Định nghĩa dashboard (`config.dashboard_def`) và số liệu khi được yêu cầu.
     * @example {
     *       "id": "0192fa0c-d546-77f8-091a-2b3c4d5e6f01",
     *       "title": "Bảng của Bí thư",
     *       "ownerUserId": "0192f1a2-2222-7aaa-8bbb-000000000b01",
     *       "measures": [
     *         "okr.tien_do_o"
     *       ],
     *       "dimensions": [],
     *       "layout": [],
     *       "shareScope": "RIENG",
     *       "sharedUnitIds": [],
     *       "rowVersion": 0,
     *       "updatedAt": "2026-11-16T09:00:00+07:00"
     *     }
     */
    AnalyticsDashboard: {
      id: components['schemas']['Uuid'];
      title: string;
      ownerUserId: components['schemas']['Uuid'];
      measures: string[];
      dimensions: string[];
      layout: components['schemas']['DashboardWidget'][];
      shareScope: components['schemas']['DashboardShareScope'];
      sharedUnitIds: components['schemas']['Uuid'][];
      semanticModelVersion?: string;
      rowVersion: components['schemas']['RowVersion'];
      updatedAt: components['schemas']['DateTime'];
      data?: {
        dataAsOf?: components['schemas']['DateTime'];
        stale?: boolean;
        widgets?: {
          widgetId: string;
          columns: components['schemas']['AnalyticsColumn'][];
          rows: (string | number | null)[][];
        }[];
      };
    };
    DashboardShareRequest: {
      shareScope: components['schemas']['DashboardShareScope'];
      /** @description Bắt buộc khi `DON_VI`; chỉ đơn vị trong tenant. */
      sharedUnitIds?: components['schemas']['Uuid'][];
    };
    AnalyticsReportRequest: {
      /** @description Mã mẫu báo cáo (suy luận). */
      templateCode: string;
      periodId: components['schemas']['Uuid'];
      unitId?: components['schemas']['Uuid'];
      dashboardId?: components['schemas']['Uuid'];
      /** @default false */
      compareToPreviousPeriod: boolean;
      /** @enum {string} */
      format: 'PDF' | 'XLSX';
    };
    /**
     * @description Loại mắt xích của chuỗi truy vết PL 4 III.2 (BR-16-03): điểm, cấu phần, KPI nhiệm vụ hoặc KR, sản phẩm, minh chứng, kế hoạch, mục tiêu O, căn cứ. Mã là suy luận.
     * @enum {string}
     */
    TraceObjectType:
      | 'DIEM'
      | 'CAU_PHAN'
      | 'KPI_NHIEM_VU'
      | 'KET_QUA_THEN_CHOT'
      | 'SAN_PHAM'
      | 'MINH_CHUNG'
      | 'KE_HOACH'
      | 'MUC_TIEU'
      | 'CAN_CU';
    TraceLink: {
      objectType: components['schemas']['TraceObjectType'];
      id: components['schemas']['Uuid'];
      label: string;
      /** @description Giá trị hiển thị đã định dạng tiếng Việt. */
      value?: string;
      /** @description Trạng thái chuẩn của đối tượng (baseline mục 9.1). */
      status?: string;
      versionNo?: number;
      unitName?: string;
      /** @description Mắt xích ngoài phạm vi quyền, hiển thị dạng che (MSG-16-06). */
      masked: boolean;
      /** @description Mã thông điệp TL-02 kèm theo, ví dụ `MSG-16-06`, `MSG-16-07`. */
      msg?: string;
      /** @description Chi tiết theo loại mắt xích (bảng A, B, C của KPI, phiên bản minh chứng…). */
      details?: {
        [key: string]: unknown;
      };
    };
    /**
     * @example {
     *       "focus": {
     *         "objectType": "DIEM",
     *         "id": "0192fb1d-e657-7809-1a2b-3c4d5e6f7a01"
     *       },
     *       "links": [
     *         {
     *           "objectType": "DIEM",
     *           "id": "0192fb1d-e657-7809-1a2b-3c4d5e6f7a01",
     *           "label": "Điểm quý IV/2026",
     *           "value": "91,2",
     *           "masked": false
     *         }
     *       ]
     *     }
     */
    TraceChain: {
      focus: {
        objectType: components['schemas']['TraceObjectType'];
        id: components['schemas']['Uuid'];
      };
      links: components['schemas']['TraceLink'][];
      /** @description Mã bản ghi sổ cái của lần truy cập C2 (nếu có). */
      ledgerRef?: string;
    };
    TraceExportRequest: {
      objectType: components['schemas']['TraceObjectType'];
      id: components['schemas']['Uuid'];
      formats: ('JSON' | 'PDF')[];
    };
    /**
     * @description Loại tài liệu được lập chỉ mục (TL-01 SVC-16; mã là suy luận).
     * @enum {string}
     */
    SearchResultType: 'TASK' | 'SOURCE_DOC' | 'CATALOG_ITEM';
    /**
     * @description Một kết quả tìm kiếm; chỉ trường C0, C1.
     * @example {
     *       "type": "CATALOG_ITEM",
     *       "id": "0192fc2e-f768-791a-2b3c-4d5e6f7a8b01",
     *       "code": "DM-TC-012",
     *       "title": "Hướng dẫn kiểm điểm tổ chức đảng",
     *       "score": 8.2
     *     }
     */
    SearchResult: {
      type: components['schemas']['SearchResultType'];
      id: components['schemas']['Uuid'];
      code?: string;
      title: string;
      /** @description Đoạn trích văn bản thuần, không HTML. */
      snippet?: string;
      /** @description Vị trí phần khớp trong `snippet` (đếm theo ký tự UTF-16). */
      highlights?: {
        start: number;
        length: number;
      }[];
      unitName?: string;
      periodLabel?: string;
      status?: string;
      deepLink?: string;
      score: number;
    };
    SearchResultPage: {
      items: components['schemas']['SearchResult'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    MfaRecoveryCodeIssueRequest: {
      /**
       * @description Hình thức quản trị viên đã xác minh danh tính cán bộ mất thiết bị SmartOTP.
       * @enum {string}
       */
      verificationMethod: 'TRUC_TIEP' | 'VAN_BAN_DON_VI' | 'GOI_VIDEO_NOI_BO';
      reason: string;
      /** @default 15 */
      ttlMinutes: number;
    };
    /**
     * @example {
     *       "codeId": "0195f3f0-0000-7000-8000-000000000501",
     *       "personId": "0195f3a0-0000-7000-8000-000000000101",
     *       "recoveryCode": "9482-7310-5521",
     *       "expiresAt": "2026-04-01T08:15:00Z",
     *       "issuedAt": "2026-04-01T08:00:00Z"
     *     }
     */
    MfaRecoveryCodeIssued: {
      codeId: components['schemas']['Uuid'];
      personId: components['schemas']['Uuid'];
      /** @description Mã khôi phục một lần (hiển thị đúng một lần cho quản trị viên trao cho cán bộ). */
      recoveryCode: string;
      expiresAt: components['schemas']['DateTime'];
      issuedAt: components['schemas']['DateTime'];
    };
    MfaRecoveryCodeRevokeRequest: {
      reason: string;
    };
    MfaRecoveryCodeRevokeResult: {
      revokedCount: number;
    };
    /** @enum {string} */
    PushPlatform: 'FCM' | 'APNS' | 'WEB_PUSH';
    PushDevice: {
      id: components['schemas']['Uuid'];
      platform: components['schemas']['PushPlatform'];
      deviceName: string;
      appVersion?: string;
      registeredAt: components['schemas']['DateTime'];
      lastSeenAt: components['schemas']['DateTime'];
    };
    PushDeviceList: {
      items: components['schemas']['PushDevice'][];
    };
    PushDeviceRegisterRequest: {
      platform: components['schemas']['PushPlatform'];
      pushToken: string;
      deviceName: string;
      appVersion?: string;
      webPushKeys?: {
        p256dh?: string;
        auth?: string;
      } | null;
    };
    /** @enum {string} */
    NotificationChannel: 'IN_APP' | 'PUSH' | 'EMAIL' | 'SMS';
    /** @enum {string} */
    NotificationCategory:
      'NHIEM_VU' | 'PHE_DUYET' | 'DANH_GIA' | 'KY_SO' | 'CANH_BAO_BAO_MAT' | 'HE_THONG';
    NotificationCategoryPreference: {
      category: components['schemas']['NotificationCategory'];
      channels: components['schemas']['NotificationChannel'][];
    };
    NotificationPreferences: {
      categories: components['schemas']['NotificationCategoryPreference'][];
      /** @default false */
      quietHoursEnabled: boolean;
      quietHoursStart?: string | null;
      quietHoursEnd?: string | null;
      rowVersion: components['schemas']['RowVersion'];
      updatedAt: components['schemas']['DateTime'];
    };
    NotificationPreferencesInput: {
      categories: components['schemas']['NotificationCategoryPreference'][];
      quietHoursEnabled: boolean;
      quietHoursStart?: string | null;
      quietHoursEnd?: string | null;
    };
    AiOcrRequest: {
      objectKey: string;
      sha256: string;
      /** @enum {string} */
      mimeType: 'application/pdf' | 'image/jpeg' | 'image/png';
      /**
       * @description Chỉ cho phép C1/C2; tài liệu C3 bị từ chối theo BR-17-07.
       * @enum {string}
       */
      classification: 'C1' | 'C2';
      /** @default vi */
      languageHint: string;
    };
    AiOcrPage: {
      pageNumber: number;
      text: string;
      confidence: number;
    };
    AiOcrResult: {
      objectKey: string;
      sha256: string;
      pages: components['schemas']['AiOcrPage'][];
      fullText: string;
      modelId: string;
      processedAt: components['schemas']['DateTime'];
    };
    AnalyticsEmbedTokenRequest: {
      dashboardCode: string;
      unitId?: components['schemas']['Uuid'];
      periodId?: components['schemas']['Uuid'];
    };
    AnalyticsEmbedToken: {
      token: string;
      /** Format: uri */
      embedUrl: string;
      expiresAt: components['schemas']['DateTime'];
      /** @description Tiền tố `org_path` đã khoá cứng trong guest token (BR-16-01). */
      lockedOrgPathPrefix: string;
    };
    FieldError: {
      /** @description Tên trường theo JSON của thân yêu cầu, dạng đường dẫn chấm */
      field: string;
      /** @description Thông điệp tiếng Việt */
      message: string;
      /** @description Mã quy tắc nghiệp vụ nếu có, ví dụ `BR-05-01` */
      rule?: string;
    };
    /** @description RFC 9457, kiểu nội dung `application/problem+json`. Mở rộng theo TL-01: `code` dạng `<MODULE>-<HTTP>-<TÊN>`, `errors[]` cho lỗi theo trường, `traceId` để tra cứu. `detail` không chứa thông tin nội bộ. */
    ProblemDetails: {
      /** @description URI ổn định của loại lỗi, ví dụ `https://okrkpi.vn/problems/period-locked` */
      type: string;
      /** @description Tiêu đề tiếng Việt */
      title: string;
      status: number;
      detail?: string;
      instance?: string;
      /** @description Mã lỗi, ví dụ `TASK-409-PERIOD-LOCKED`; ánh xạ sang MSG ở giao diện */
      code?: string;
      /** @description Mã thông điệp TL-02 tương ứng nếu có, ví dụ `MSG-06-05` */
      msg?: string;
      errors?: components['schemas']['FieldError'][];
      traceId?: string;
    };
    /**
     * Format: uuid
     * @description Mã định danh UUIDv7 (cột `id` của mọi bảng, baseline mục 9).
     * @example 01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77
     */
    Uuid: string;
    /** @description Con trỏ trang kế tiếp; `null` khi đã hết. */
    NextCursor: string | null;
    /**
     * @description Nhóm khách hàng (`registry.tenant.sector`).
     * @enum {string}
     */
    TenantSector:
      'DANG' | 'CHINH_QUYEN' | 'SU_NGHIEP' | 'TRUONG_HOC' | 'BENH_VIEN' | 'LLVT' | 'DOANH_NGHIEP';
    /**
     * @description Luồng đánh giá (baseline mục 9.1).
     * @enum {string}
     */
    Flow: 'DANG' | 'NHA_NUOC';
    /**
     * Format: date
     * @description Ngày theo lịch (ISO 8601), không có giờ.
     * @example 2026-12-15
     */
    Date: string;
    /**
     * Format: date-time
     * @description Thời điểm ISO 8601 có múi giờ.
     * @example 2026-10-19T08:30:00+07:00
     */
    DateTime: string;
    /**
     * @description Giá trị `row_version` (khoá lạc quan). Cùng giá trị với ETag, gửi lại trong `If-Match`.
     * @example 7
     */
    RowVersion: number;
    /**
     * @description Trạng thái tác vụ dài. TL chưa đặc tả lược đồ job; hợp đồng giữ bộ giá trị WEB-007 đã dùng (lấy từ các trạng thái job trong TL-03).
     * @enum {string}
     */
    JobStatus: 'CHO' | 'DANG_CHAY' | 'HOAN_TAT' | 'THAT_BAI' | 'DA_HUY';
    /** @description Tác vụ dài trả qua `GET /api/v1/jobs/{id}`. */
    Job: {
      id: string;
      /** @description Loại tác vụ, ví dụ PERIOD_LOCK, ESIGN_BATCH, EXPORT, IMPORT, RENDER, TEST_RUN, BULK_ASSIGN */
      type: string;
      status: components['schemas']['JobStatus'];
      progress?: number;
      /** @description Mô tả bước đang chạy, tiếng Việt */
      message?: string;
      /** @description Đường dẫn kết quả khi HOAN_TAT */
      resultLocation?: string;
      problem?: components['schemas']['ProblemDetails'];
      updatedAt?: components['schemas']['DateTime'];
    };
    /**
     * @description Tên ô ghi vào `edited_fields` khi người giao sửa (SCR-0502 trường 13).
     * @enum {string}
     */
    SuggestionField:
      | 'title'
      | 'leadUnitId'
      | 'coUnits'
      | 'dueDate'
      | 'productName'
      | 'catalogItemId'
      | 'keyResultId';
    /**
     * @description Quyết định của người giao với một nhiệm vụ AI trích (`ai.suggestion_log.decision`).
     * @enum {string}
     */
    SuggestionDecision: 'CHO_XAC_NHAN' | 'DA_XAC_NHAN' | 'DA_BO' | 'DA_GOP';
    /** @description Người hoặc đơn vị phối hợp. */
    AssigneeRef: {
      /** @enum {string} */
      kind: 'PERSON' | 'UNIT';
      id: components['schemas']['Uuid'];
      name: string;
    };
    /** @description Các ô AI điền trên thẻ nhiệm vụ (SCR-0502 trường 5–10), tên theo `task.task`. */
    TaskSuggestionFields: {
      title: string;
      leadUnitId: components['schemas']['Uuid'];
      leadUnitName: string;
      coUnits: components['schemas']['AssigneeRef'][];
      dueDate: components['schemas']['Date'];
      productName: string;
      catalogItemId?: components['schemas']['Uuid'];
      catalogItemCode?: string;
      keyResultId?: components['schemas']['Uuid'];
      keyResultCode?: string;
    };
    /**
     * @description Trạng thái `task.task.status` (baseline mục 9.1).
     * @enum {string}
     */
    TaskStatus:
      'NHAP' | 'DA_GIAO' | 'DANG_THUC_HIEN' | 'CHO_NGHIEM_THU' | 'HOAN_THANH' | 'TAM_DUNG' | 'HUY';
    /** @description Nhiệm vụ gần giống (BR-05-06, MSG-05-03). */
    SimilarTask: {
      id: components['schemas']['Uuid'];
      code: string;
      title: string;
      status: components['schemas']['TaskStatus'];
      similarity: number;
      unitName: string;
      leadName: string;
      dueDate: components['schemas']['Date'];
      progress: number;
    };
    /** @description Một nhiệm vụ do AI trích từ văn bản (`ai.suggestion_log`, use_case `GOI_Y_NHIEM_VU`), dùng chung giữa MOD-02 và SVC-14. */
    TaskSuggestion: {
      id: components['schemas']['Uuid'];
      paragraphNo: number;
      confidence: number;
      decision: components['schemas']['SuggestionDecision'];
      fields: components['schemas']['TaskSuggestionFields'];
      aiFields: components['schemas']['TaskSuggestionFields'];
      editedFields: components['schemas']['SuggestionField'][];
      similar: components['schemas']['SimilarTask'][];
      taskId?: components['schemas']['Uuid'];
      taskCode?: string;
      taskStatus?: components['schemas']['TaskStatus'];
      reason?: string;
    };
  };
  responses: {
    /** @description Lỗi khác theo RFC 9457. */
    Problem: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description Yêu cầu sai cú pháp hoặc tham số không hợp lệ. */
    BadRequest: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description Chưa xác thực, token hết hạn, hoặc thao tác cần xác thực bước lên AAL2 (RFC 9470); khi đó `WWW-Authenticate` mang `error="insufficient_user_authentication", acr_values="aal2"`. */
    Unauthorized: {
      headers: {
        'WWW-Authenticate'?: string;
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description OPA từ chối theo RBAC/ABAC hoặc quy tắc tách vai trò (PL 4 V.1). */
    Forbidden: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description Không tìm thấy, hoặc đối tượng nằm ngoài phạm vi người gọi (không lộ sự tồn tại). */
    NotFound: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description Cắt tải khi cell gần bão hoà; thử lại có giãn cách. */
    ServiceUnavailable: {
      headers: {
        'Retry-After': components['headers']['RetryAfter'];
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description Dữ liệu không hợp lệ theo quy tắc nghiệp vụ (BR), thiếu lý do bắt buộc, hoặc `Idempotency-Key` dùng lại với nội dung khác. Lỗi theo trường nằm trong `errors[]`. */
    UnprocessableEntity: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description Thiếu header `If-Match` cho thao tác cập nhật (RFC 6585). */
    PreconditionRequired: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description Tác vụ nền đã nhận (trả trong 200 ms, NFR-03). Theo dõi bằng `Location` hoặc luồng SSE `/api/v1/notifications/stream`. */
    Accepted: {
      headers: {
        Location: components['headers']['Location'];
        'Retry-After': components['headers']['RetryAfter'];
        [name: string]: unknown;
      };
      content: {
        'application/json': components['schemas']['Job'];
      };
    };
    /** @description Xung đột. Hai nghĩa phân biệt bằng `code`: xung đột ghi đồng thời (`<MODULE>-409-VERSION-CONFLICT`, `type` kết thúc bằng `concurrent-modification`) hoặc vi phạm trạng thái nghiệp vụ (ví dụ `TASK-409-PERIOD-LOCKED`). Với xung đột ghi, giao diện giữ dữ liệu đang nhập và hiện khác biệt. */
    Conflict: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
    /** @description Vượt giới hạn tốc độ (mặc định 20 request/giây mỗi người dùng, đột biến 40). */
    TooManyRequests: {
      headers: {
        'Retry-After': components['headers']['RetryAfter'];
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
  };
  parameters: {
    /** @description Mã user task Flowable (`ACT_RU_TASK.ID_`, `flowable.wf_task_projection.task_id`), không phải UUID. */
    WorkflowTaskId: string;
    /** @description Khoá nghiệp vụ của tiến trình dạng `<businessType>:<businessId>` (suy luận từ cặp `business_type`, `business_id` của `flowable.wf_task_projection`). */
    BusinessKey: string;
    /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
    Limit: number;
    /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
    Cursor: string;
    /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
    IdempotencyKey: string;
    IdPath: components['schemas']['Uuid'];
    /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
    IfMatch: string;
    /** @description Mã kỳ đánh giá (`config.period.id`). */
    PeriodId: components['schemas']['Uuid'];
    /** @description Từ khoá tìm kiếm, không phân biệt dấu. */
    Q: string;
    /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
    UnitId: components['schemas']['Uuid'];
  };
  requestBodies: never;
  headers: {
    /** @description Số giây nên chờ trước lần gọi kế tiếp. */
    RetryAfter: number;
    /** @description Đường dẫn theo dõi tác vụ dài `/api/v1/jobs/{id}` hoặc đối tượng vừa tạo. */
    Location: string;
    /** @description Phiên bản hiện tại của đối tượng (`row_version`), gửi lại trong `If-Match`. */
    ETag: string;
  };
  pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
  resolveTenantCell: {
    parameters: {
      query?: {
        /** @description Mã tenant (`registry.tenant.code`) hoặc `tenant_id` lấy từ claim `tid`. */
        tenant?: string;
        /** @description Tên miền người dùng truy cập (`registry.tenant_domain.domain`), do APISIX lớp ngoài gửi. */
        host?: string;
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Cell hiện hành của tenant. */
      200: {
        headers: {
          /** @description Cookie định tuyến ký HMAC, hạn 5 phút, để request sau đi thẳng tới cell. */
          'Set-Cookie'?: string;
          /** @description `private, max-age=300`. */
          'Cache-Control'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TenantResolution'];
        };
      };
      400: components['responses']['BadRequest'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Tenant đã chuyển sang cell khác (mã `REGISTRY-421-TENANT-MOVED`). Header `Location` mang URL cổng API của cell mới; shell gọi lại `registry/resolve` rồi chuyển hướng. */
      421: {
        headers: {
          /** @description URL cổng API của cell mới. */
          Location?: string;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      503: components['responses']['ServiceUnavailable'];
      default: components['responses']['Problem'];
    };
  };
  listCells: {
    parameters: {
      query?: {
        status?: components['schemas']['CellStatus'];
        tenancyModel?: components['schemas']['RegistryTenancyModel'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Trang danh sách cell. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CellPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createRegistryTenant: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['RegistryTenantCreate'];
      };
    };
    responses: {
      /** @description Đã tạo tenant và khởi động tiếp nhận. */
      201: {
        headers: {
          Location: components['headers']['Location'];
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['RegistryTenant'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Mã tenant, mã hồ sơ khởi tạo hoặc tên miền đã tồn tại (`REGISTRY-409-DUPLICATE`), hoặc cell đích không ở trạng thái `HOAT_DONG` (`REGISTRY-409-CELL-UNAVAILABLE`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getRegistryTenant: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Bản ghi tenant. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['RegistryTenant'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  requestTenantMove: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['TenantMoveCreate'];
      };
    };
    responses: {
      /** @description Đã tạo đề nghị chuyển cell, chờ người duyệt thứ hai. */
      201: {
        headers: {
          Location: components['headers']['Location'];
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TenantMove'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Xung đột ghi (`REGISTRY-409-VERSION-CONFLICT`), tenant đang có lần chuyển khác (`REGISTRY-409-MOVE-IN-PROGRESS`) hoặc cell đích trùng cell hiện tại (`REGISTRY-409-SAME-CELL`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  approveTenantMove: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        id: components['parameters']['IdPath'];
        moveId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    requestBody?: {
      content: {
        'application/json': components['schemas']['ApprovalComment'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      /** @description Người duyệt trùng người đề nghị (`REGISTRY-403-SAME-APPROVER`) hoặc OPA từ chối. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  getTenantMove: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Mã lệnh chuyển cell. */
        moveId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Chi tiết lệnh chuyển cell. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TenantMove'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  cancelTenantMove: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        id: components['parameters']['IdPath'];
        /** @description Mã lệnh chuyển cell. */
        moveId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    requestBody?: {
      content: {
        'application/json': components['schemas']['ApprovalComment'];
      };
    };
    responses: {
      /** @description Lệnh chuyển cell đã chuyển sang `DA_HUY`. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TenantMove'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Xung đột phiên bản hoặc lệnh đã hoàn tất cắt chuyển (`REGISTRY-409-MOVE-ALREADY-CUTOVER`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  issueMfaRecoveryCode: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Mã nhân sự (`org.person.id`) cần cấp mã khôi phục MFA. */
        personId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['MfaRecoveryCodeIssueRequest'];
      };
    };
    responses: {
      /** @description Mã khôi phục một lần (chỉ trả dạng rõ đúng một lần khi cấp). */
      201: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['MfaRecoveryCodeIssued'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  revokeMfaRecoveryCodes: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Mã nhân sự (`org.person.id`) cần thu hồi các mã khôi phục MFA chưa dùng. */
        personId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['MfaRecoveryCodeRevokeRequest'];
      };
    };
    responses: {
      /** @description Số mã khôi phục đã thu hồi. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['MfaRecoveryCodeRevokeResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listWorkflowInbox: {
    parameters: {
      query?: {
        /** @description Chỉ hỗ trợ `me` (người gọi). */
        assignee?: 'me';
        /** @description Lọc theo loại đối tượng nghiệp vụ, ví dụ `OKR_OBJECTIVE`, `PLAN`, `APPRAISAL_PROFILE`. */
        businessType?: string;
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Trang hộp việc. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowInboxPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  completeWorkflowTask: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Mã user task Flowable (`ACT_RU_TASK.ID_`, `flowable.wf_task_projection.task_id`), không phải UUID. */
        taskId: components['parameters']['WorkflowTaskId'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['WorkflowTaskCompletion'];
      };
    };
    responses: {
      /** @description Đã hoàn thành user task. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowTaskResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      /** @description Người gọi không phải người được gán hay ứng viên, hoặc vi phạm tách vai trò (người duyệt là người soạn, `WORKFLOW-403-SELF-APPROVAL`, MSG-02-11). */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /** @description User task đã được người khác hoàn thành (`WORKFLOW-409-TASK-COMPLETED`) hoặc lệch phiên bản (`WORKFLOW-409-VERSION-CONFLICT`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  batchCompleteWorkflowTasks: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['WorkflowBatchCompletion'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Quá 200 mục (`WORKFLOW-422-BATCH-TOO-LARGE`) hoặc dữ liệu không hợp lệ. */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      default: components['responses']['Problem'];
    };
  };
  listWorkflowInstances: {
    parameters: {
      query?: {
        /** @description Mã mẫu luồng (`flowable.wf_template.template_code`). */
        template?: string;
        stuck?: boolean;
        /** @description Mã cell, chỉ quản trị nền tảng dùng. */
        cell?: string;
        /** @description Lọc theo đơn vị xử lý của bước đang chờ (đơn vị trong nhóm ứng viên của user task, `flowable.wf_task_projection.candidate_unit_id`). Bước giao thẳng cho một người, không có nhóm ứng viên, không khớp bộ lọc này (bổ sung DV-06-10, suy luận). */
        unit?: components['schemas']['Uuid'];
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Trang tiến trình. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowInstancePage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  getWorkflowHistory: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Khoá nghiệp vụ của tiến trình dạng `<businessType>:<businessId>` (suy luận từ cặp `business_type`, `business_id` của `flowable.wf_task_projection`). */
        businessKey: components['parameters']['BusinessKey'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Lịch sử tiến trình. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowHistory'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  getWorkflowDiagram: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Khoá nghiệp vụ của tiến trình dạng `<businessType>:<businessId>` (suy luận từ cặp `business_type`, `business_id` của `flowable.wf_task_projection`). */
        businessKey: components['parameters']['BusinessKey'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Sơ đồ và trạng thái các phần tử. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowDiagram'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  reassignWorkflowTask: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Mã user task Flowable (`ACT_RU_TASK.ID_`, `flowable.wf_task_projection.task_id`), không phải UUID. */
        taskId: components['parameters']['WorkflowTaskId'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['WorkflowTaskReassign'];
      };
    };
    responses: {
      /** @description Đã chuyển người xử lý. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowInboxItem'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      /** @description Thiếu lý do, hoặc người nhận không thuộc nhóm ứng viên của bước (`WORKFLOW-422-ASSIGNEE-NOT-ELIGIBLE`). */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  extendWorkflowTask: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Mã user task Flowable (`ACT_RU_TASK.ID_`, `flowable.wf_task_projection.task_id`), không phải UUID. */
        taskId: components['parameters']['WorkflowTaskId'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['WorkflowTaskExtend'];
      };
    };
    responses: {
      /** @description Đã gia hạn. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowInboxItem'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  startPeriodCloseSaga: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['PeriodCloseSagaStart'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Kỳ đã có saga chốt kỳ đang chạy hoặc đã hoàn tất (`WORKFLOW-409-SAGA-EXISTS`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  controlPeriodCloseSaga: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Mã saga do `startPeriodCloseSaga` trả (`config.period_lock_command.saga_id`). */
        sagaId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['PeriodCloseSagaCommand'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      /** @description Người duyệt huỷ trùng người đề nghị (`PERIOD-403-SAME-PERSON`) hoặc không phải client nội bộ. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /** @description Saga đã qua điểm không quay lại (`PERIOD-409-POINT-OF-NO-RETURN`) hoặc không ở trạng thái nhận lệnh (`WORKFLOW-409-VERSION-CONFLICT`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  listWorkflowTemplates: {
    parameters: {
      query?: {
        status?: components['schemas']['WorkflowTemplateStatus'];
        /** @description Từ khoá tìm kiếm, không phân biệt dấu. */
        q?: components['parameters']['Q'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Trang mẫu luồng. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowTemplatePage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createWorkflowTemplate: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['WorkflowTemplateCreate'];
      };
    };
    responses: {
      /** @description Đã tạo mẫu ở trạng thái `NHAP`. */
      201: {
        headers: {
          Location: components['headers']['Location'];
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowTemplate'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Đã có phiên bản `NHAP` khác của cùng mã mẫu (`WORKFLOW-409-DRAFT-EXISTS`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getWorkflowTemplate: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Mẫu luồng đầy đủ. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowTemplate'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  updateWorkflowTemplate: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['WorkflowTemplateUpdate'];
      };
    };
    responses: {
      /** @description Đã lưu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowTemplate'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Lệch phiên bản (`WORKFLOW-409-VERSION-CONFLICT`) hoặc mẫu không còn ở `NHAP`, `DAT_KIEM_THU` (`WORKFLOW-409-TEMPLATE-IMMUTABLE`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  runWorkflowTemplateTests: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Mẫu không ở trạng thái `NHAP` hoặc `DAT_KIEM_THU` (`WORKFLOW-409-TEMPLATE-IMMUTABLE`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      default: components['responses']['Problem'];
    };
  };
  submitWorkflowTemplate: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: {
      content: {
        'application/json': components['schemas']['ApprovalComment'];
      };
    };
    responses: {
      /** @description Đã tạo merge request, mẫu chờ phê duyệt. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowTemplate'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Mẫu chưa `DAT_KIEM_THU` (`WORKFLOW-409-NOT-TESTED`) hoặc lệch phiên bản. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  approveWorkflowTemplate: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: {
      content: {
        'application/json': components['schemas']['ApprovalComment'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      /** @description Người phê duyệt là người soạn (`WORKFLOW-403-SAME-AUTHOR`) hoặc OPA từ chối. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /** @description Mẫu không ở `CHO_PHE_DUYET`, lệch phiên bản, hoặc đang trong khung cấm triển khai (`WORKFLOW-409-ROLLOUT-WINDOW`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  listWorkflowTemplateRollouts: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Tiến độ theo cell. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowTemplateRolloutList'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  streamNotifications: {
    parameters: {
      query?: never;
      header?: {
        /** @description Mã sự kiện cuối cùng client đã nhận, để phát lại phần bị lỡ. */
        'Last-Event-ID'?: string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Luồng sự kiện mở. */
      200: {
        headers: {
          /** @description `no-cache`. */
          'Cache-Control'?: string;
          [name: string]: unknown;
        };
        content: {
          'text/event-stream': string;
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      429: components['responses']['TooManyRequests'];
      503: components['responses']['ServiceUnavailable'];
      default: components['responses']['Problem'];
    };
  };
  listNotifications: {
    parameters: {
      query?: {
        /** @description Chỉ lấy thông báo chưa đọc. */
        unread?: boolean;
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Trang thông báo. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['NotificationPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  markNotificationsRead: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['NotificationReadRequest'];
      };
    };
    responses: {
      /** @description Đã đánh dấu. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['NotificationReadResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  listMyPushDevices: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Danh sách thiết bị đã đăng ký. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PushDeviceList'];
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  registerPushDevice: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['PushDeviceRegisterRequest'];
      };
    };
    responses: {
      /** @description Thiết bị đã đăng ký. */
      201: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PushDevice'];
        };
      };
      401: components['responses']['Unauthorized'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  unregisterPushDevice: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Định danh thiết bị đẩy đã đăng ký. */
        deviceId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Đã xoá đăng ký thiết bị. */
      204: {
        headers: {
          [name: string]: unknown;
        };
        content?: never;
      };
      401: components['responses']['Unauthorized'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  getNotificationPreferences: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Tuỳ chọn thông báo hiện hành. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['NotificationPreferences'];
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  updateNotificationPreferences: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['NotificationPreferencesInput'];
      };
    };
    responses: {
      /** @description Tuỳ chọn thông báo đã lưu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['NotificationPreferences'];
        };
      };
      401: components['responses']['Unauthorized'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  listAiSuggestions: {
    parameters: {
      query?: {
        /** @description Loại gợi ý chi tiết (`ai.suggestion_log.kind`), ví dụ `TOM_TAT_TUAN`. */
        kind?: string;
        useCase?: string;
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Trang gợi ý. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AiSuggestionPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  requestAiSuggestion: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['AiSuggestionRequest'];
      };
    };
    responses: {
      /** @description Gợi ý đã sinh. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AiSuggestion'];
        };
      };
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      422: components['responses']['UnprocessableEntity'];
      429: components['responses']['TooManyRequests'];
      default: components['responses']['Problem'];
    };
  };
  getAiSuggestion: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Gợi ý. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AiSuggestion'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listAiSuggestionSources: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Danh sách nguồn. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AiSourceList'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  decideAiSuggestion: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['AiSuggestionDecisionRequest'];
      };
    };
    responses: {
      /** @description Đã ghi quyết định. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json':
            components['schemas']['TaskSuggestion'] | components['schemas']['AiSuggestion'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Gợi ý đã có quyết định (`AI-409-ALREADY-DECIDED`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Thiếu lý do khi bỏ nhiệm vụ trích (`AI-422-REASON-REQUIRED`) hoặc dữ liệu không hợp lệ. */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      default: components['responses']['Problem'];
    };
  };
  getDocExtraction: {
    parameters: {
      query: {
        /** @description Mã văn bản đến (`task.task_source_doc.id`). */
        sourceDocId: components['schemas']['Uuid'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Kết quả trích. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DocExtraction'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  reviewOkrKeyResults: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['OkrReviewRequest'];
      };
    };
    responses: {
      /** @description Kết quả soát. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrReviewResult'];
        };
      };
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      422: components['responses']['UnprocessableEntity'];
      429: components['responses']['TooManyRequests'];
      default: components['responses']['Problem'];
    };
  };
  assistOkrDraft: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['OkrAssistRequest'];
      };
    };
    responses: {
      /** @description Trả lời của trợ lý. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OkrAssistResult'];
        };
      };
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      422: components['responses']['UnprocessableEntity'];
      429: components['responses']['TooManyRequests'];
      default: components['responses']['Problem'];
    };
  };
  extractEvidenceFields: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['EvidenceExtractRequest'];
      };
    };
    responses: {
      /** @description Kết quả đọc. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['EvidenceExtraction'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Ảnh chưa đủ nét để đọc (`AI-422-UNREADABLE`, MSG-07-06) hoặc tệp chưa quét sạch. */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      default: components['responses']['Problem'];
    };
  };
  transcribeSpeech: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'multipart/form-data': components['schemas']['SpeechToTextRequest'];
      };
    };
    responses: {
      /** @description Văn bản nhận dạng. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SpeechToTextResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Đoạn ghi âm quá dài hoặc quá lớn (`AI-413-AUDIO-TOO-LARGE`). */
      413: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  ocrEvidenceObject: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['AiOcrRequest'];
      };
    };
    responses: {
      /** @description Văn bản OCR trích xuất được theo từng trang. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AiOcrResult'];
        };
      };
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      /** @description Từ chối khi đối tượng có mức độ mật `C3` (`AI-403-C3-FORBIDDEN`, BR-17-07). */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  queryAnalytics: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['AnalyticsQuery'];
      };
    };
    responses: {
      /** @description Kết quả truy vấn. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AnalyticsQueryResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Chỉ số hoặc chiều không có trong lớp ngữ nghĩa, kết hợp không hợp lệ (`ANALYTICS-422-INVALID-COMBINATION`, MSG-16-04), quá 2 chiều. */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      429: components['responses']['TooManyRequests'];
      default: components['responses']['Problem'];
    };
  };
  getSemanticModel: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Danh mục. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SemanticModel'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  listDashboards: {
    parameters: {
      query?: {
        /** @description `me` chỉ lấy dashboard của mình; bỏ trống lấy cả dashboard được chia sẻ. */
        owner?: 'me';
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Trang dashboard (chỉ định nghĩa, không có số liệu). */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AnalyticsDashboardPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createDashboard: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['AnalyticsDashboardInput'];
      };
    };
    responses: {
      /** @description Đã tạo. */
      201: {
        headers: {
          Location: components['headers']['Location'];
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AnalyticsDashboard'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Trùng tên dashboard của người dùng (`ANALYTICS-409-DUPLICATE-TITLE`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getDashboard: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
        /** @description `false` chỉ lấy định nghĩa (trình dựng). */
        includeData?: boolean;
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Dashboard. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AnalyticsDashboard'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  updateDashboard: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['AnalyticsDashboardInput'];
      };
    };
    responses: {
      /** @description Đã lưu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AnalyticsDashboard'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  shareDashboard: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['DashboardShareRequest'];
      };
    };
    responses: {
      /** @description Đã chia sẻ. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AnalyticsDashboard'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  exportAnalyticsReport: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['AnalyticsReportRequest'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      422: components['responses']['UnprocessableEntity'];
      429: components['responses']['TooManyRequests'];
      default: components['responses']['Problem'];
    };
  };
  getTraceChain: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        objectType: components['schemas']['TraceObjectType'];
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Chuỗi truy vết. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TraceChain'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  exportTraceChain: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['TraceExportRequest'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  createAnalyticsEmbedToken: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['AnalyticsEmbedTokenRequest'];
      };
    };
    responses: {
      /** @description Guest token ngắn hạn cho khung nhúng. */
      201: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AnalyticsEmbedToken'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  searchFullText: {
    parameters: {
      query: {
        /** @description Từ khoá, không phân biệt dấu. */
        q: string;
        /** @description Lọc theo loại; bỏ trống tìm mọi loại. */
        type?: components['schemas']['SearchResultType'];
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Trang kết quả. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SearchResultPage'];
        };
      };
      400: components['responses']['BadRequest'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      429: components['responses']['TooManyRequests'];
      default: components['responses']['Problem'];
    };
  };
}
