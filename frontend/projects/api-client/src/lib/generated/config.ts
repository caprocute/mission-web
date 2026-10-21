/* eslint-disable */
/**
 * Sinh tự động bởi openapi-typescript từ contracts/openapi/modules/config.yaml (WEB-064).
 * Không sửa tay; sửa hợp đồng rồi chạy `npm run api:gen` trong frontend/.
 */
export interface paths {
  '/config/tenants': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách tenant kèm tải theo cell
     * @description Trả danh sách tenant của vùng điều phối toàn cục, lọc theo cell, mô hình thuê bao và trạng thái, phân trang con trỏ. Phần `summary` chứa bốn chỉ số đầu trang và tải theo cell của SCR-0101 (số đếm được SCR yêu cầu nên trả riêng). Cell vượt 85% kèm MSG-01-01.
     */
    get: operations['listConfigTenants'];
    put?: never;
    /**
     * Khai báo tenant mới (bước 1 của wizard)
     * @description Tạo yêu cầu khởi tạo tenant ở trạng thái `KHOI_TAO`, sinh mã yêu cầu `ONB-yyyy-nnnn`. TL-02 không ghi API riêng cho bước này (suy luận từ bước 1 QT-01 và quyền POST của AC-01 trong ma trận). Tên miền trùng trả 409 mã `CONFIG-409-DOMAIN-TAKEN` (MSG-01-04).
     */
    post: operations['createConfigTenant'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/tenants/export': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Xuất danh sách tenant
     * @description Tạo tác vụ xuất danh sách tenant theo bộ lọc hiện tại; trả 202 và địa chỉ theo dõi.
     */
    post: operations['exportConfigTenants'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/tenants/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết tenant và cấu hình wizard
     * @description Trả cấu hình đang soạn hoặc đang vận hành của tenant, bước hiện tại của wizard và kết quả kiểm tra tổng thể của bước 6. Quản trị tenant chỉ xem được tenant của mình (bước 4, 5).
     */
    get: operations['getConfigTenant'];
    put?: never;
    post?: never;
    /**
     * Huỷ yêu cầu khởi tạo tenant
     * @description Huỷ yêu cầu khi tenant còn `KHOI_TAO`, không cấp phát tài nguyên. Trạng thái khác trả 409 mã `CONFIG-409-TENANT-STATE`.
     */
    delete: operations['cancelConfigTenant'];
    options?: never;
    head?: never;
    /**
     * Lưu nháp cấu hình tenant
     * @description Lưu cấu hình đang soạn của bất kỳ bước nào (JSON Merge Patch theo RFC 7396). Bước 1, 2, 3 do `platform_admin` sửa; bước 4, 5 (IdP, nguồn nhân sự) do `tenant_admin` của tenant sửa. Mã lỗi: `CONFIG-422-SILO-REQUIRED` (BR-01-01), `CONFIG-422-CELL-OVER-CAPACITY` (BR-01-02, MSG-01-03), `CONFIG-422-POLICY-PACK-NOT-READY` (BR-01-03, MSG-01-05), `CONFIG-422-FLOW-PACK-MISSING` (BR-01-04), `CONFIG-409-DOMAIN-TAKEN` (MSG-01-04), `CONFIG-409-VERSION-CONFLICT`.
     */
    patch: operations['updateConfigTenant'];
    trace?: never;
  };
  '/config/tenants/{id}/provision': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Khởi chạy saga cấp phát tenant
     * @description Ở cuối bước 3, kiểm tra cấu hình và khởi chạy saga cấp phát (SVC-03 khởi tạo, SVC-06 điều phối): tenant chuyển `KHOI_TAO` → `DANG_CAP_PHAT`, khi xong chuyển `DANG_CAU_HINH` (MSG-01-06); lỗi không khắc phục được thì bù trừ về `KHOI_TAO` (MSG-01-07). Trả 202, job loại `PROVISION`. Không phát EVT nghiệp vụ; saga ghi tiến độ.
     */
    post: operations['provisionConfigTenant'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/tenants/{id}/idp/test': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Kiểm tra kết nối IdP của tenant
     * @description Thử luồng OIDC hoặc liên kết SAML với cấu hình IdP đang soạn (bước 4). Không đổi trạng thái tenant; kết quả được ghi để bước 6 kiểm tra "IdP hoạt động".
     */
    post: operations['testConfigTenantIdp'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/tenants/{id}/activate': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Kích hoạt tenant
     * @description Bước 6: khi mọi kiểm tra tổng thể đạt, chuyển `SAN_SANG` → `DANG_VAN_HANH` (hoặc `TAM_NGUNG` → `DANG_VAN_HANH` khi gia hạn), yêu cầu MFA bước lên; phát EVT-21 thông báo đầu mối quản trị tenant. Còn lệch mức Cao trả 422 mã `CONFIG-422-HIGH-DISCREPANCY` (BR-01-10); sai trạng thái trả 409 mã `CONFIG-409-TENANT-STATE`.
     */
    post: operations['activateConfigTenant'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/security/report-suspicious': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Báo cáo truy cập bất thường
     * @description Người dùng bấm "Báo cáo truy cập bất thường" trên cảnh báo thiết bị mới (MSG-00-08): máy chủ thu hồi mọi phiên của tài khoản, buộc đổi mật khẩu và phát EVT-21 thông báo quản trị. Phiên hiện tại cũng bị thu hồi sau khi trả phản hồi.
     */
    post: operations['reportSuspiciousAccess'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/calendar': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Lịch ngày làm việc của tenant theo năm
     * @description Trả lịch quốc gia hợp với lịch riêng của tenant cho một năm: ngày nghỉ lễ, nghỉ bù, làm bù và số ngày làm việc từng tháng. Dùng để hiển thị hạn và số ngày chậm; máy chủ (SVC-07) là nơi tính điểm.
     */
    get: operations['getWorkingCalendar'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/holiday-calendars': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách ngày nghỉ, làm bù
     * @description Danh mục ngày đặc biệt của tenant (kèm lịch quốc gia) để quản trị tenant chỉnh sửa.
     */
    get: operations['listHolidayCalendarDays'];
    put?: never;
    /**
     * Thêm ngày nghỉ hoặc làm bù
     * @description Thêm một ngày vào lịch riêng của tenant. Ngày đã có trả 409 mã `CONFIG-409-DATE-EXISTS`. Lịch quốc gia chỉ do tenant hệ thống cập nhật.
     */
    post: operations['createHolidayCalendarDay'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/holiday-calendars/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    /**
     * Sửa ngày nghỉ hoặc làm bù
     * @description Sửa loại ngày hoặc tên ngày của lịch riêng tenant.
     */
    put: operations['updateHolidayCalendarDay'];
    post?: never;
    /**
     * Xoá ngày nghỉ hoặc làm bù
     * @description Xoá một ngày của lịch riêng tenant. Ngày thuộc kỳ đã khoá vẫn xoá được nhưng không làm tính lại kỳ đó (MSG-08-06 không áp dụng cho lịch; suy luận theo BR-08-07 về tính tất định).
     */
    delete: operations['deleteHolidayCalendarDay'];
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/feature-flags': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách cờ tính năng của tenant
     * @description Trả mọi cờ tính năng của tenant cùng quy tắc bật dần (suy luận, cần để màn hình quản trị đọc trước khi PUT).
     */
    get: operations['listFeatureFlags'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/feature-flags/{key}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã cờ tính năng (`config.feature_flag.flag_key`). */
        key: components['parameters']['FlagKeyPath'];
      };
      cookie?: never;
    };
    get?: never;
    /**
     * Bật tắt tính năng theo tenant
     * @description Đặt trạng thái và quy tắc bật dần của một cờ. Cờ do nền tảng khai báo sẵn cho mọi tenant (suy luận), nên thao tác luôn là cập nhật và cần `If-Match`; mã cờ lạ trả 404.
     */
    put: operations['setFeatureFlag'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/anomaly-thresholds': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Ngưỡng phát hiện tám dấu hiệu bất thường
     * @description Trả phiên bản ngưỡng hiện hành của tenant (suy luận, cần để màn hình "Ngưỡng phát hiện" đọc trước khi PUT).
     */
    get: operations['getAnomalyThresholds'];
    /**
     * Lưu phiên bản ngưỡng mới
     * @description Lưu phiên bản ngưỡng mới có lý do (BR-14-09); phiên bản cũ được giữ trong `config.tenant_setting`. Job phát hiện đêm (SVC-15) dùng phiên bản mới từ lần chạy kế tiếp.
     */
    put: operations['updateAnomalyThresholds'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/periods': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách kỳ và trạng thái
     * @description Trả các kỳ đánh giá của tenant theo năm, loại kỳ, luồng và trạng thái, mới nhất trước.
     */
    get: operations['listPeriods'];
    put?: never;
    /**
     * Tạo kỳ đánh giá
     * @description Tạo kỳ mới với lịch mốc mặc định theo Policy Pack và ghim phiên bản Policy Pack đang hiệu lực cho kỳ (BR-08-07). Kỳ chồng lấn cùng loại, cùng luồng trả 409 mã `PERIOD-409-OVERLAP`; lịch khoá muộn hơn tham số được trả kèm cảnh báo `warnings` (BR-08-12).
     */
    post: operations['createPeriod'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/periods/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết kỳ đánh giá
     * @description Trả kỳ, lịch mốc, Policy Pack ghim và lệnh khoá kỳ hiện hành (nếu có).
     */
    get: operations['getPeriod'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/periods/{id}/lock-orders': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Lập lệnh khoá kỳ
     * @description Lập lệnh khoá kỳ khi kỳ đang `MO` (hoặc `MO_LAI_HAU_KIEM`) và chưa có lệnh còn hiệu lực; lệnh vào `CHO_DUYET` và gửi lãnh đạo (AC-04) qua EVT-21. Đã có lệnh trả 409 mã `PERIOD-409-ORDER-EXISTS`.
     */
    post: operations['createPeriodLockOrder'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/periods/{id}/lock-orders/{oid}/approve': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Mã lệnh khoá kỳ (`config.period_lock_order.id`). */
        oid: components['parameters']['LockOrderIdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Phê duyệt lệnh khoá kỳ
     * @description Lãnh đạo (AC-04) phê duyệt lệnh với MFA; người duyệt phải khác người lập (CHECK `approved_by <> requested_by`, mã `PERIOD-403-SAME-PERSON`). Lệnh chuyển `DA_DUYET` (MSG-08-01).
     */
    post: operations['approvePeriodLockOrder'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/periods/{id}/lock': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Tiến trình saga khoá kỳ
     * @description Trả lệnh khoá kỳ, mã saga, khoá chống lặp, bốn chỉ số (số hồ sơ, đã tính, lỗi, dự kiến hoàn tất), sáu bước saga chiếu từ `config.period_lock_step`, đề nghị huỷ đang chờ và cảnh báo. Giao diện nhận cập nhật thời gian thực qua `/api/v1/notifications/stream` rồi đọc lại tài nguyên này. Kỳ chưa có saga trả 404.
     */
    get: operations['getPeriodLockProgress'];
    put?: never;
    /**
     * Khởi động saga khoá kỳ
     * @description Khởi tạo saga `chot-ky` từ lệnh đã duyệt; người khởi chạy phải khác người phê duyệt (BR-08-01, mã `PERIOD-403-SAME-PERSON`), MFA trong 15 phút (BR-08-02). Mỗi kỳ chỉ một saga chạy (BR-08-08): khoá chống lặp theo kỳ (`CK-2026Q4`) làm lần gọi trùng trả lại job cũ; saga khác đang chạy trả 409 mã `PERIOD-409-SAGA-RUNNING`. Trả 202, job loại `PERIOD_LOCK`. Bước 1 phát EVT-09; bước 0 không đạt làm job `THAT_BAI` với Problem mã `PERIOD-422-PRECONDITION` (MSG-08-02).
     */
    post: operations['startPeriodLock'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/periods/{id}/lock/batches': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Danh sách lô tính điểm của saga
     * @description Các lô `scoring.score_job` của lần khoá hiện tại, phân trang con trỏ (SCR-0801 trường 10).
     */
    get: operations['listPeriodLockBatches'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/periods/{id}/lock/failures': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Danh sách hồ sơ lỗi khi tính điểm
     * @description Hồ sơ trong hàng đợi lỗi của bước 2, phân trang con trỏ (SCR-0801 trường 11).
     */
    get: operations['listPeriodLockFailures'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/periods/{id}/lock/events': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Nhật ký sự kiện của saga
     * @description Các dòng `ledger.ledger_entry` lọc theo saga, mới nhất trước (SCR-0801 trường 13, BR-08-10).
     */
    get: operations['listPeriodLockEvents'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/periods/{id}/lock/diagram': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Sơ đồ BPMN của saga khoá kỳ
     * @description Trả định nghĩa BPMN của mẫu `chot-ky` đang chạy cho kỳ và các hoạt động đã xong, đang chạy, lỗi để giao diện tô màu. XML chỉ được hiển thị bằng trình xem BPMN, không chèn vào DOM dạng HTML.
     */
    get: operations['getPeriodLockDiagram'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/periods/{id}/lock/pause': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Tạm dừng saga sau bước hiện tại
     * @description Đặt cờ tạm dừng; saga dừng khi bước hiện tại xong. Saga không chạy trả 409 mã `PERIOD-409-SAGA-NOT-RUNNING`.
     */
    post: operations['pausePeriodLock'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/periods/{id}/lock/resume': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Tiếp tục saga đã tạm dừng
     * @description Gỡ cờ tạm dừng để saga chạy tiếp bước kế tiếp (suy luận: TL-02a có "Tạm dừng" nhưng không ghi thao tác tiếp tục; QT-08 ngoại lệ "chờ tiếp" ở bước 4 cần đường này).
     */
    post: operations['resumePeriodLock'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/periods/{id}/lock/retry-failed': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Chạy lại hồ sơ lỗi
     * @description Tạo lô mới cho các hồ sơ trong hàng đợi lỗi (hoặc danh sách chỉ định); trả 202, job loại `PERIOD_LOCK_RETRY`. Khi tính xong SVC-07 phát EVT-10. Không có hồ sơ lỗi trả 409 mã `PERIOD-409-NO-FAILURE`.
     */
    post: operations['retryFailedPeriodLock'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/periods/{id}/lock/cancel': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Đề nghị huỷ và bù trừ toàn bộ
     * @description Tạo đề nghị huỷ saga, gửi người duyệt thứ hai qua EVT-21 (MSG-08-05). Chỉ cho phép trước khi bước 4 hoàn tất; quá điểm không quay lại trả 409 mã `PERIOD-409-POINT-OF-NO-RETURN` (BR-08-09).
     */
    post: operations['requestPeriodLockCancel'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/periods/{id}/lock/cancel/approve': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Duyệt thứ hai huỷ và bù trừ
     * @description Người duyệt thứ hai (AC-09), khác người đề nghị, duyệt huỷ; saga bù trừ theo thứ tự ngược, kỳ trở về `MO` và MOD-08 phát EVT-26 KyDaHuyKhoa (MSG-08-08). Trả 202, job loại `PERIOD_UNLOCK`.
     */
    post: operations['approvePeriodLockCancel'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/packs/current': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * UI Pack hiệu lực của tenant
     * @description Trả phiên bản UI Pack `DA_PHAT_HANH` của tenant (siêu dữ liệu, token, chế độ cao tuổi, bố cục, biểu mẫu, plugin, i18n) kèm băm nội dung và chữ ký để shell kiểm trước khi áp. ETag theo phiên bản; gửi `If-None-Match` để nhận 304. Bản phát qua CDN (`/ui-packs/{tenant}/current.json`) có cùng nội dung; API này là nguồn dự phòng và cho ứng dụng di động.
     */
    get: operations['getCurrentUiPack'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/packs': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách UI Pack của tenant
     * @description Trả UI Pack của tenant kèm phiên bản đang phát hành và phiên bản nháp (suy luận, cần cho thẻ Phiên bản của SCR-1801).
     */
    get: operations['listUiPacks'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/packs/{id}/versions': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Danh sách phiên bản UI Pack
     * @description Các phiên bản của một UI Pack, mới nhất trước, để quay lui về phiên bản `THAY_THE` tương thích.
     */
    get: operations['listUiPackVersions'];
    put?: never;
    /**
     * Tạo phiên bản nháp UI Pack
     * @description Tạo phiên bản `NHAP` bằng cách nhân bản phiên bản đang dùng hoặc từ bộ mặc định (QT-18 bước 1; TL-02 không ghi đường dẫn, suy luận theo quy ước).
     */
    post: operations['createUiPackVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/packs/{id}/versions/{v}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
        v: components['parameters']['UiPackVersionPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết phiên bản UI Pack
     * @description Trả token, bố cục, tài nguyên, cờ chế độ cao tuổi và trạng thái của một phiên bản.
     */
    get: operations['getUiPackVersion'];
    /**
     * Lưu nháp UI Pack
     * @description Lưu token, bố cục, tài nguyên và cờ của phiên bản `NHAP`. Máy chủ kiểm tra ngay: component lạ trả 422 mã `UI-422-UNKNOWN-COMPONENT` (MSG-18-02, BR-18-02); tương phản dưới AA trả 422 mã `UI-422-CONTRAST` (MSG-18-01, BR-18-01); quá 12 widget trên trang chủ trả 422. Phiên bản không còn `NHAP` trả 409 mã `UI-409-NOT-DRAFT`.
     */
    put: operations['updateUiPackVersion'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/packs/{id}/versions/{v}/preview': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
        v: components['parameters']['UiPackVersionPath'];
      };
      cookie?: never;
    };
    /**
     * Xem trước phiên bản UI Pack
     * @description Biên dịch thử token thành CSS custom properties và preset PrimeNG, trả kèm bố cục và kết quả kiểm tra tự động (tương phản, registry, kích thước) để shell dựng giao diện mẫu, kể cả chế độ cao tuổi. Không đẩy lên CDN, không đổi trạng thái.
     */
    get: operations['previewUiPackVersion'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/packs/{id}/versions/{v}/submit': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
        v: components['parameters']['UiPackVersionPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Gửi duyệt phiên bản UI Pack
     * @description Chuyển `NHAP` → `DANG_KIEM_TRA` và chạy bộ kiểm tra tự động (schema, registry, tương phản AA, kích thước ≤ 30 KB gzip, cel-js = cel-java). Đạt thì `CHO_DUYET` và gửi người duyệt qua EVT-21; lỗi thì về `NHAP` kèm danh sách lỗi trong job. Trả 202, job loại `TEST_RUN`.
     */
    post: operations['submitUiPackVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/packs/{id}/versions/{v}/publish': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
        v: components['parameters']['UiPackVersionPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Duyệt và phát hành UI Pack
     * @description Người duyệt (khác người soạn, mã `UI-403-SAME-PERSON`) duyệt phiên bản `CHO_DUYET`: MOD-08 biên dịch token sang CSS và preset, ký gói, đẩy lên CDN, chuyển `DA_PHAT_HANH`, chuyển phiên bản cũ sang `THAY_THE` và phát EVT-23 để vô hiệu bộ đệm (MSG-18-03). Trả 202, job loại `RENDER`.
     */
    post: operations['publishUiPackVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/packs/{id}/versions/{v}/reject': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
        v: components['parameters']['UiPackVersionPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Từ chối phiên bản UI Pack
     * @description Người duyệt từ chối phiên bản `CHO_DUYET` kèm ý kiến; phiên bản về `NHAP` (suy luận từ bảng trạng thái QT-18 "CHO_DUYET → Từ chối → NHAP"; TL-02b không ghi đường dẫn).
     */
    post: operations['rejectUiPackVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/packs/{id}/versions/{v}/activate': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
        v: components['parameters']['UiPackVersionPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Quay lui về phiên bản UI Pack trước
     * @description Kích hoạt lại một phiên bản `THAY_THE` tương thích với Policy Pack đang gắn; phiên bản hiện hành chuyển `THAY_THE`, phát EVT-23. Biểu mẫu tham chiếu Policy Pack đã ngừng trả 409 mã `UI-409-ROLLBACK-INCOMPATIBLE` (MSG-18-04).
     */
    post: operations['activateUiPackVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/packs/{id}/versions/{v}/forms': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
        v: components['parameters']['UiPackVersionPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Đưa biểu mẫu vào UI Pack nháp
     * @description Gắn một phiên bản biểu mẫu đã đạt bộ test vào phiên bản UI Pack `NHAP`. Biểu mẫu chưa đạt bộ test trả 422 mã `UI-422-FORM-TEST-FAILED`; Policy Pack không khớp trả 422 (BR-18-06).
     */
    post: operations['attachUiPackForm'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/components': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh mục component SDUI được phép
     * @description Trả component của một phiên bản registry (mặc định phiên bản mới nhất), kể cả widget của plugin có trong manifest của tenant (BR-18-12). Danh mục dùng chung nền tảng, không có `tenant_id`.
     */
    get: operations['listUiComponents'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/forms': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách biểu mẫu động
     * @description Trả các biểu mẫu của tenant với phiên bản mới nhất và trạng thái (suy luận, cần để mở trình dựng).
     */
    get: operations['listUiForms'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/forms/{key}/versions': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Khoá biểu mẫu (`config.form_schema.form_key`). */
        key: components['parameters']['FormKeyPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Tạo phiên bản nháp biểu mẫu
     * @description Tạo phiên bản `NHAP` mới của biểu mẫu (khoá mới thì tạo biểu mẫu mới), nhân bản từ phiên bản chỉ định hoặc trống (suy luận: TL-02b chỉ ghi PUT trên phiên bản đã có).
     */
    post: operations['createUiFormVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/forms/{key}/current': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Khoá biểu mẫu (`config.form_schema.form_key`). */
        key: components['parameters']['FormKeyPath'];
      };
      cookie?: never;
    };
    /**
     * Biểu mẫu hiệu lực để dựng phiếu
     * @description (suy luận) Trả phiên bản `DA_PHAT_HANH` đang áp dụng của biểu mẫu theo khoá cho người dùng nghiệp vụ, để `<vt-form>` dựng phiếu (ví dụ Mẫu 1B của SCR-0901). Máy chủ đã phân giải `schemaRef` của UI Pack sang JSON Schema dữ liệu của Policy Pack hiệu lực, nên `schemaJson` là lược đồ dữ liệu đầy đủ và `uiJson` là phần trình bày của UI Pack. Khi loader UI Pack (WEB-009) nạp được `forms` từ `GET /ui/packs/current`, thao tác này là nguồn dự phòng và cho ứng dụng di động. ETag theo phiên bản; gửi `If-None-Match` để nhận 304. Quy tắc CEL `serverOnly` vẫn chạy lại ở máy chủ khi lưu, nộp.
     */
    get: operations['getCurrentUiForm'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/forms/{key}/versions/{v}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Khoá biểu mẫu (`config.form_schema.form_key`). */
        key: components['parameters']['FormKeyPath'];
        /** @description Phiên bản biểu mẫu (`config.form_schema.version`). */
        v: components['parameters']['FormVersionPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết phiên bản biểu mẫu
     * @description Trả JSON Schema có `x-cel`, `x-classification`, cấu hình Formly và quy tắc CEL của phiên bản.
     */
    get: operations['getUiFormVersion'];
    /**
     * Lưu nháp biểu mẫu
     * @description Lưu schema và cấu hình hiển thị của phiên bản `NHAP`. Máy chủ kiểm tra biểu thức CEL bằng cel-java (mã `UI-422-CEL-INVALID`, MSG-18-06) và trường tính điểm theo Policy Pack (mã `UI-409-SCORED-FIELD`, MSG-18-07).
     */
    put: operations['updateUiFormVersion'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/forms/{key}/versions/{v}/test-runs': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Khoá biểu mẫu (`config.form_schema.form_key`). */
        key: components['parameters']['FormKeyPath'];
        /** @description Phiên bản biểu mẫu (`config.form_schema.version`). */
        v: components['parameters']['FormVersionPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Chạy bộ test biểu mẫu trên hai engine
     * @description Chạy các ca test của biểu mẫu bằng cel-java tại máy chủ và so với kết quả cel-js trình duyệt gửi lên; ca cho kết quả khác nhau được đánh dấu (MSG-18-08, BR-18-05). Trả kết quả đồng bộ.
     */
    post: operations['runUiFormTests'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/layouts/home': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Bố cục trang chủ "Việc của tôi hôm nay"
     * @description Trả cây SDUI của trang chủ từ UI Pack hiện hành, đã lọc theo vai trò trong ngữ cảnh phân công đang chọn (biểu thức `visibleWhen` được máy chủ đánh giá trước; trình duyệt chỉ đánh giá lại để phản hồi nhanh). Dữ liệu từng khối lấy từ API của module tương ứng.
     */
    get: operations['getHomeLayout'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/ui/feature-flags': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Bản đồ cờ tính năng hiệu lực của người dùng hiện hành
     * @description Tính tập cờ hiệu lực từ `config.feature_flag` theo tenant, đơn vị (`target_org_unit_ids`), vai trò (`target_roles`) và phần trăm triển khai tăng dần (`rollout_percent` băm ổn định theo `person_id`) để client web/di động kiểm tra mà không cần quyền `tenant_admin` (TL-04 OPS-04).
     */
    get: operations['getEffectiveFeatureFlags'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/settings': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách cấu hình chung của tenant hoặc đơn vị
     * @description Đọc các dòng `config.tenant_setting` của tenant hiện hành (ngoài những khoá đã có đường dẫn chuyên biệt như `working_days`, `anomaly_thresholds`), lọc tuỳ chọn theo `unitId`.
     */
    get: operations['listTenantSettings'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/settings/{key}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Khoá cấu hình (`config.tenant_setting.key`). */
        key: components['parameters']['SettingKeyPath'];
      };
      cookie?: never;
    };
    get?: never;
    /** Ghi cấu hình theo khoá của tenant hoặc đơn vị */
    put: operations['setTenantSetting'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/role-bindings': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Vai trò đã cấp theo đơn vị hoặc theo người
     * @description Trả các bản ghi `config.role_binding` của một đơn vị phạm vi (kèm đơn vị cấp dưới khi `includeSubunits=true`) hoặc của một người, lọc theo vai trò và trạng thái, phân trang con trỏ. `awaitingMe=true` chỉ trả đề nghị cấp vai trò nhạy cảm đang chờ người gọi duyệt thứ hai. Phần `counts` là số đếm cho thẻ lọc nhanh của SCR-0105 (SCR yêu cầu nên trả riêng). Mỗi lần đọc được ghi nhật ký truy cập (BR-13-07).
     */
    get: operations['listRoleBindings'];
    put?: never;
    /**
     * Cấp vai trò có thời hạn và phạm vi
     * @description Tạo bản ghi `config.role_binding` với người cấp là người gọi. Vai trò `decider`, `second_approver`, `policy_author` vào trạng thái `CHO_DUYET` (cột `approved_by` rỗng) và chỉ có hiệu lực sau khi người duyệt thứ hai phê duyệt (BR-01-14, MSG-01-17); vai trò khác có hiệu lực theo `validFrom` và phát EVT-27. Tự cấp cho chính mình trả 403 mã `CONFIG-403-SELF-GRANT` (BR-01-13, MSG-01-16); thiếu `validTo` hoặc `validTo` không sau `validFrom` trả 422 mã `CONFIG-422-VALIDITY` (BR-01-16); đã có bản ghi còn hiệu lực trùng người, vai trò, đơn vị và phạm vi trả 409 mã `CONFIG-409-DUPLICATE-BINDING`. Phiên phải đạt `aal2` trong 15 phút (BR-01-15).
     */
    post: operations['grantRoleBinding'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/role-bindings/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết một vai trò đã cấp
     * @description Trả bản ghi vai trò kèm người cấp, người duyệt và thông tin kết thúc (thu hồi, từ chối).
     */
    get: operations['getRoleBinding'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/role-bindings/{id}/approve': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Duyệt thứ hai khi cấp vai trò nhạy cảm
     * @description Người duyệt thứ hai (một `tenant_admin` khác người cấp, hoặc `leader` có thẩm quyền trên đơn vị phạm vi) phê duyệt đề nghị đang `CHO_DUYET`; ghi `approved_by` và phát EVT-27. Người duyệt trùng người cấp hoặc trùng người được cấp trả 403 mã `CONFIG-403-SELF-GRANT` (CHECK `approved_by <> granted_by`, BR-01-13, MSG-01-16). Đề nghị không còn chờ duyệt trả 409 mã `CONFIG-409-NOT-PENDING`.
     */
    post: operations['approveRoleBinding'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/role-bindings/{id}/reject': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Từ chối đề nghị cấp vai trò nhạy cảm
     * @description Người duyệt thứ hai từ chối đề nghị đang `CHO_DUYET` kèm lý do; bản ghi được giữ với `end_kind = TU_CHOI` để tra cứu (không xoá). Cùng ràng buộc người duyệt như thao tác duyệt.
     */
    post: operations['rejectRoleBinding'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/role-bindings/{id}/revoke': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Thu hồi vai trò trước hạn
     * @description Đặt `valid_to` bằng thời điểm hiện tại, ghi người thu hồi và lý do bắt buộc, không xoá bản ghi; phát EVT-27 để dữ liệu OPA và realm role Keycloak cập nhật. Phiên của người bị thu hồi không cần đăng xuất ngay vì OPA từ chối theo dữ liệu mới. Thu hồi vai trò của chính mình trả 403 mã `CONFIG-403-SELF-GRANT` (BR-01-13); vai trò đã kết thúc trả 409 mã `CONFIG-409-ALREADY-ENDED`.
     */
    post: operations['revokeRoleBinding'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/access-history': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Lịch sử cấp, duyệt, thu hồi vai trò và uỷ quyền
     * @description Dòng thời gian của một người (`personId`) hoặc một đơn vị (`orgUnitId`): cấp, duyệt, từ chối, thu hồi, hết hạn vai trò và tạo, thu hồi, hết hạn uỷ quyền, mới nhất trước. Đọc từ nhật ký kiểm toán `sys.audit.v1`; mỗi lần tra cứu được ghi lại (BR-13-07). Cần ít nhất một trong hai bộ lọc.
     */
    get: operations['listAccessHistory'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/access-history/export': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Xuất lịch sử quyền của một người ra CSV
     * @description Xuất toàn bộ lịch sử quyền của một người thành CSV (UTF-8 có BOM). Xuất là thao tác nhạy cảm: phiên phải đạt `aal2` trong 15 phút và lần xuất được ghi nhật ký kiểm toán.
     */
    get: operations['exportAccessHistory'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/delegations': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Uỷ quyền khi vắng mặt
     * @description Trả bản ghi `config.delegation` (bảng bổ sung TL-03 theo WEB-058) của một đơn vị hoặc của một người (là người uỷ quyền hoặc người nhận), lọc theo loại và trạng thái.
     */
    get: operations['listDelegations'];
    put?: never;
    /**
     * Khai báo duyệt thay, nghiệm thu thay, check-in thay
     * @description Lãnh đạo đơn vị khai báo người nhận uỷ quyền có thời hạn: duyệt thay (QT-02, QT-06), nghiệm thu thay (QT-07) hoặc check-in KR thay cho chủ sở hữu vắng dài ngày (QT-03, `subject` là KR). Phiếu duyệt, nghiệm thu và check-in do người nhận thực hiện ghi rõ "duyệt thay", "nghiệm thu thay", "check-in thay". Người nhận đang là người nhận uỷ quyền của chính người uỷ quyền khác không được uỷ quyền lại: 422 mã `CONFIG-422-REDELEGATION` (BR-01-17, MSG-01-18). Người uỷ quyền và người nhận trùng nhau trả 403 mã `CONFIG-403-SELF-GRANT` (BR-01-13). Thiếu thời hạn trả 422 mã `CONFIG-422-VALIDITY` (BR-01-16). Người nhận vẫn chịu ràng buộc tách vai trò của hồ sơ (BR-01-18).
     */
    post: operations['createDelegation'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/config/delegations/{id}/revoke': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Thu hồi uỷ quyền trước hạn
     * @description Đặt `valid_to` bằng thời điểm hiện tại kèm lý do; phiếu đang giao cho người nhận quay về người uỷ quyền theo mẫu luồng. Uỷ quyền đã kết thúc trả 409 mã `CONFIG-409-ALREADY-ENDED`.
     */
    post: operations['revokeDelegation'];
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
     * @description Mô hình thuê bao trong lần gắn cell (`registry.tenant_cell.tenancy_model`, CHECK chữ thường như TL-02a). `registry.tenant.tenancy_model` viết hoa và có thêm `AIRGAP`; hợp đồng theo TL-02a.
     * @enum {string}
     */
    TenancyModel: 'silo' | 'bridge' | 'pool';
    /**
     * @description Trạng thái vòng đời tenant (`registry.tenant.status`, TL-02a QT-01 cộng `DANG_CHUYEN_CELL` của TL-03).
     * @enum {string}
     */
    TenantStatus:
      | 'KHOI_TAO'
      | 'DANG_CAP_PHAT'
      | 'DANG_CAU_HINH'
      | 'SAN_SANG'
      | 'DANG_VAN_HANH'
      | 'TAM_NGUNG'
      | 'DANG_CHUYEN_CELL'
      | 'DA_CHAM_DUT';
    /**
     * @description Nhóm khách hàng (`registry.tenant.sector`).
     * @enum {string}
     */
    TenantSector:
      'DANG' | 'CHINH_QUYEN' | 'SU_NGHIEP' | 'TRUONG_HOC' | 'BENH_VIEN' | 'LLVT' | 'DOANH_NGHIEP';
    /**
     * @description Loại khoá mã hoá (SCR-0102 trường 9): riêng theo tenant hoặc dùng chung của cell; silo bắt buộc riêng (suy luận tên giá trị, TL-03 chỉ có cột `kek_ref`).
     * @enum {string}
     */
    TenantKekMode: 'RIENG' | 'DUNG_CHUNG_CELL';
    /**
     * @description Nguồn nhân sự (SCR-0102 trường 13; suy luận tên giá trị).
     * @enum {string}
     */
    TenantStaffSourceType: 'CSDL_CBCCVC' | 'CSDL_DANG_VIEN' | 'SCIM';
    /**
     * @description Một dòng của bảng tenant trên SCR-0101.
     * @example {
     *       "id": "0192f0a1-3b2c-7d4e-8f50-6a7b8c9d0e11",
     *       "code": "thainguyen",
     *       "name": "Tỉnh Thái Nguyên",
     *       "domain": "thainguyen.okr.viettel.vn",
     *       "cellCode": "CELL-G2",
     *       "tenancyModel": "bridge",
     *       "accountCount": 0,
     *       "policyPacks": [
     *         "Khối Đảng · Quy định mới v2.0",
     *         "Nhà nước · NĐ 335 v1.3"
     *       ],
     *       "uiPack": "Mặc định v3.0",
     *       "status": "DANG_CAP_PHAT",
     *       "onboardingStep": 4
     *     }
     */
    ConfigTenantRow: {
      id: components['schemas']['Uuid'];
      /** @description `registry.tenant.code` */
      code: string;
      /** @description `registry.tenant.name` */
      name: string;
      /** @description Tên miền chính (`registry.tenant_domain.domain`) */
      domain: string;
      /** @description Mã cell, dạng `CELL-xxx` */
      cellCode: string;
      tenancyModel: components['schemas']['TenancyModel'];
      /** @description Số tài khoản, đồng bộ từ cell */
      accountCount: number;
      /** @description Policy Pack đang gắn, dạng "tên vX.Y" theo luồng. */
      policyPacks?: string[];
      /** @description UI Pack đang dùng, dạng "tên vX.Y" */
      uiPack?: string;
      status: components['schemas']['TenantStatus'];
      /** @description Bước wizard hiện tại khi đang khởi tạo (MSG-01-02); `null` khi đã vận hành (suy luận). */
      onboardingStep?: number | null;
    };
    /** @description Tải của một cell tính bằng số tài khoản chia sức chứa thiết kế (SCR-0101 trường 9). */
    CellLoad: {
      cellCode: string;
      accountCount: number;
      /** @description `registry.cell.capacity_accounts` */
      capacityAccounts: number;
      loadPercent: number;
      /** @description Vượt ngưỡng 85% (MSG-01-01, BR-01-02) */
      overThreshold?: boolean;
    };
    /** @description Bốn chỉ số đầu trang và tải theo cell của SCR-0101. */
    ConfigTenantSummary: {
      /** @description Số tenant `DANG_VAN_HANH` */
      activeTenants: number;
      /** @description Giới hạn thiết kế, 5.000 */
      tenantLimit: number;
      totalAccounts: number;
      /** @description Giới hạn thiết kế về tài khoản (NFR-01), máy chủ trả theo cấu hình. Quy mô định cỡ đã chốt ngày 19/10/2026 là 6,5 triệu tài khoản (AGENTS.md mục 1); baseline NFR-01 và TL-02a còn ghi 6 triệu. */
      accountLimit: number;
      /** @description Số tenant đang khởi tạo */
      onboardingTenants: number;
      cellCount: number;
      /** @description Số trung tâm dữ liệu chứa các cell, dòng phụ của thẻ Cell trên Hình 6.7 (suy luận tên trường). */
      dataCenterCount?: number;
      cellLoads?: components['schemas']['CellLoad'][];
      /** @description Mọi tenant đang khởi tạo của vùng (không phụ thuộc trang đang xem), để hiện MSG-01-02 "Tenant {tên} đang khởi tạo bước {n}/6". */
      onboarding?: {
        tenantId: components['schemas']['Uuid'];
        name: string;
        step: number;
      }[];
      /** @description Danh sách việc cần xử lý của vùng điều phối toàn cục (SCR-0101 vùng 3, Hình 6.7). */
      pendingActions?: components['schemas']['TenantPendingAction'][];
    };
    /** @description Một việc cần xử lý trên SCR-0101 vùng 3 (cấu trúc suy luận từ Hình 6.7). */
    TenantPendingAction: {
      /**
       * @description `CHO` việc chờ duyệt hoặc chờ thao tác, `LICH` việc đã lên lịch, `LOI` lỗi cần xử lý.
       * @enum {string}
       */
      kind: 'CHO' | 'LICH' | 'LOI';
      tenantId?: components['schemas']['Uuid'];
      /** @description Nội dung tiếng Việt đã điền tham số */
      message: string;
      dueAt?: components['schemas']['DateTime'];
    };
    /**
     * @example {
     *       "items": [
     *         {
     *           "id": "0192f0a1-3b2c-7d4e-8f50-6a7b8c9d0e11",
     *           "code": "thainguyen",
     *           "name": "Tỉnh Thái Nguyên",
     *           "domain": "thainguyen.okr.viettel.vn",
     *           "cellCode": "CELL-G2",
     *           "tenancyModel": "bridge",
     *           "accountCount": 0,
     *           "status": "DANG_CAP_PHAT",
     *           "onboardingStep": 4
     *         }
     *       ],
     *       "nextCursor": "eyJjIjoiMDE5MmYwYTEifQ",
     *       "summary": {
     *         "activeTenants": 1284,
     *         "tenantLimit": 5000,
     *         "totalAccounts": 2418760,
     *         "accountLimit": 6500000,
     *         "onboardingTenants": 3,
     *         "cellCount": 7,
     *         "cellLoads": [
     *           {
     *             "cellCode": "CELL-G2",
     *             "accountCount": 640000,
     *             "capacityAccounts": 1000000,
     *             "loadPercent": 64,
     *             "overThreshold": false
     *           }
     *         ]
     *       }
     *     }
     */
    ConfigTenantPage: {
      items: components['schemas']['ConfigTenantRow'][];
      nextCursor: components['schemas']['NextCursor'];
      /** @description Tổng số tenant khớp bộ lọc cho dòng "Hiển thị a–b trong n" (Hình 6.7); registry dưới 100.000 dòng (TL-03) nên đếm chính xác được. */
      total?: number;
      summary?: components['schemas']['ConfigTenantSummary'];
    };
    /** @description Policy Pack gốc gắn theo luồng (SCR-0102 trường 10; `policy.policy_binding.pack_version_id`). */
    TenantPolicyBinding: {
      flow: components['schemas']['Flow'];
      packVersionId: components['schemas']['Uuid'];
      readonly packCode?: string;
      readonly version?: string;
    };
    /** @description IdP của tenant (`registry.tenant_idp`). TL-03 chỉ cho `OIDC`, `SAML`; LDAP liên kết mà TL-02a nhắc tới chưa có trong CHECK nên chưa đưa vào. */
    TenantIdp: {
      id?: components['schemas']['Uuid'];
      idpAlias: string;
      /** @enum {string} */
      protocol: 'OIDC' | 'SAML';
      issuer: string;
      /** @default false */
      isVneid: boolean;
      emailDomains?: string[];
      /** @enum {string} */
      readonly status?: 'HOAT_DONG' | 'TAM_DUNG';
      lastTestedAt?: components['schemas']['DateTime'];
      /** @description Kết quả lần kiểm tra kết nối gần nhất (suy luận) */
      readonly lastTestOk?: boolean;
    };
    /** @description Nguồn nhân sự của tenant (SCR-0102 trường 13; `integ.connector`). */
    TenantStaffSource: {
      type: components['schemas']['TenantStaffSourceType'];
      connectorId?: components['schemas']['Uuid'];
      /** @description Có kết nối hợp lệ */
      readonly connected?: boolean;
    };
    /** @description Đầu mối quản trị tenant (dữ liệu cá nhân). */
    TenantAdminContact: {
      fullName: string;
      /** Format: email */
      email: string;
      phone?: string;
    };
    /** @description Một mục kiểm tra tổng thể ở bước 6 (QT-01 bước 9). */
    ReadinessCheck: {
      /**
       * @description Mã mục kiểm tra (suy luận tên giá trị).
       * @enum {string}
       */
      code:
        | 'CAPACITY'
        | 'DOMAIN'
        | 'TLS'
        | 'POLICY_PACK_GOLDEN'
        | 'IDP'
        | 'ORG_TREE'
        | 'HIGH_DISCREPANCY';
      passed: boolean;
      detail?: string;
    };
    /**
     * @description Chi tiết tenant và cấu hình wizard khởi tạo (SCR-0102).
     * @example {
     *       "id": "0192f0a1-3b2c-7d4e-8f50-6a7b8c9d0e11",
     *       "code": "thainguyen",
     *       "name": "Tỉnh Thái Nguyên",
     *       "sector": "CHINH_QUYEN",
     *       "onboardingCode": "ONB-2026-0318",
     *       "contractRef": "HĐ-2026/VTS-TN-118",
     *       "flows": [
     *         "DANG",
     *         "NHA_NUOC"
     *       ],
     *       "expectedAccounts": 98420,
     *       "expectedUnits": 1260,
     *       "maxAccounts": 110000,
     *       "accountCount": 0,
     *       "tenancyModel": "bridge",
     *       "suggestedTenancyModel": "bridge",
     *       "cellCode": "CELL-G2",
     *       "domain": "thainguyen.okr.viettel.vn",
     *       "dataRegion": "HOALAC",
     *       "kekMode": "RIENG",
     *       "policyBindings": [
     *         {
     *           "flow": "DANG",
     *           "packVersionId": "0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a70",
     *           "packCode": "khoi-dang/quy-dinh-moi",
     *           "version": "2.0"
     *         },
     *         {
     *           "flow": "NHA_NUOC",
     *           "packVersionId": "0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a71",
     *           "packCode": "nn-335",
     *           "version": "1.3"
     *         }
     *       ],
     *       "uiPackVersionId": "0192e2d5-6e7f-7a81-8b2c-3d4e5f6a7b80",
     *       "status": "KHOI_TAO",
     *       "onboardingStep": 2,
     *       "rowVersion": 3,
     *       "createdAt": "2026-10-19T08:30:00+07:00",
     *       "updatedAt": "2026-10-19T09:05:00+07:00"
     *     }
     */
    ConfigTenant: {
      id: components['schemas']['Uuid'];
      code: string;
      name: string;
      sector?: components['schemas']['TenantSector'];
      readonly onboardingCode?: string;
      contractRef?: string;
      contractValidTo?: components['schemas']['Date'];
      flows: components['schemas']['Flow'][];
      expectedAccounts?: number;
      /** @description Số đơn vị dự kiến (suy luận cột) */
      expectedUnits?: number;
      maxAccounts?: number;
      readonly accountCount?: number;
      tenancyModel?: components['schemas']['TenancyModel'];
      /** @description Gợi ý theo quy mô và BR-01-01 */
      readonly suggestedTenancyModel?: components['schemas']['TenancyModel'];
      cellCode?: string;
      domain?: string;
      dataRegion?: string;
      kekMode?: components['schemas']['TenantKekMode'];
      policyBindings?: components['schemas']['TenantPolicyBinding'][];
      uiPackVersionId?: components['schemas']['Uuid'];
      /** @description Tên và phiên bản UI Pack đang dùng (SCR-0101 trường 7, `config.ui_pack_version`). */
      readonly uiPack?: {
        name: string;
        version: string;
      };
      /** @description Nhật ký đổi trạng thái (`registry.tenant_status_log`, TL-03), mới nhất trước, cho trang chi tiết. */
      readonly statusLog?: components['schemas']['TenantStatusLogEntry'][];
      idps?: components['schemas']['TenantIdp'][];
      staffSources?: components['schemas']['TenantStaffSource'][];
      adminContact?: components['schemas']['TenantAdminContact'];
      status: components['schemas']['TenantStatus'];
      readonly onboardingStep?: number | null;
      readonly readinessChecks?: components['schemas']['ReadinessCheck'][];
      /** @description Job saga cấp phát gần nhất */
      readonly provisionJobId?: string | null;
      rowVersion: components['schemas']['RowVersion'];
      createdAt?: components['schemas']['DateTime'];
      updatedAt?: components['schemas']['DateTime'];
    };
    /**
     * @description Thông tin bước 1 của wizard.
     * @example {
     *       "code": "thainguyen",
     *       "name": "Tỉnh Thái Nguyên",
     *       "sector": "CHINH_QUYEN",
     *       "contractRef": "HĐ-2026/VTS-TN-118",
     *       "flows": [
     *         "DANG",
     *         "NHA_NUOC"
     *       ],
     *       "expectedAccounts": 98420,
     *       "expectedUnits": 1260,
     *       "adminContact": {
     *         "fullName": "Trần Thị Hồng",
     *         "email": "quantri@thainguyen.gov.vn"
     *       }
     *     }
     */
    ConfigTenantCreate: {
      code: string;
      name: string;
      sector: components['schemas']['TenantSector'];
      contractRef: string;
      contractValidTo?: components['schemas']['Date'];
      flows: components['schemas']['Flow'][];
      expectedAccounts: number;
      expectedUnits?: number;
      adminContact: components['schemas']['TenantAdminContact'];
    };
    /**
     * @description Thay đổi một phần cấu hình đang soạn (JSON Merge Patch); mọi trường tuỳ chọn.
     * @example {
     *       "tenancyModel": "bridge",
     *       "cellCode": "CELL-G2",
     *       "domain": "thainguyen.okr.viettel.vn",
     *       "dataRegion": "HOALAC",
     *       "kekMode": "RIENG"
     *     }
     */
    ConfigTenantPatch: {
      name?: string;
      contractRef?: string;
      contractValidTo?: components['schemas']['Date'];
      flows?: components['schemas']['Flow'][];
      expectedAccounts?: number;
      expectedUnits?: number;
      tenancyModel?: components['schemas']['TenancyModel'];
      cellCode?: string;
      domain?: string;
      dataRegion?: string;
      kekMode?: components['schemas']['TenantKekMode'];
      policyBindings?: components['schemas']['TenantPolicyBinding'][];
      uiPackVersionId?: components['schemas']['Uuid'];
      idps?: components['schemas']['TenantIdp'][];
      staffSources?: components['schemas']['TenantStaffSource'][];
      adminContact?: components['schemas']['TenantAdminContact'];
    };
    /** @description Ghi chú khi kích hoạt hoặc kích hoạt lại sau tạm ngừng. */
    ConfigTenantActivateRequest: {
      /** @description Lý do, số văn bản (ghi `registry.tenant_status_log.reason`) */
      reason?: string;
    };
    /** @description Bộ lọc và định dạng tệp xuất, cùng bộ lọc với `GET /config/tenants` (định dạng là suy luận). */
    ConfigTenantExportRequest: {
      cell?: string;
      model?: components['schemas']['TenancyModel'];
      status?: components['schemas']['TenantStatus'];
      /** @description Từ khoá đang tìm trên danh sách */
      q?: string;
      /**
       * @description Định dạng tệp; vắng thì máy chủ dùng `XLSX`.
       * @enum {string}
       */
      format?: 'XLSX' | 'CSV';
    };
    /** @description Một lần đổi trạng thái tenant (`registry.tenant_status_log`). */
    TenantStatusLogEntry: {
      id: components['schemas']['Uuid'];
      fromStatus?: components['schemas']['TenantStatus'] | null;
      toStatus: components['schemas']['TenantStatus'];
      /** @description Lý do, số văn bản */
      reason: string;
      actor?: components['schemas']['Ref'];
      changedAt: components['schemas']['DateTime'];
    };
    /**
     * @description IdP cần kiểm tra; gửi `idpAlias` của IdP đã lưu hoặc cấu hình đang soạn.
     * @example {
     *       "idpAlias": "idp-tinh-thainguyen"
     *     }
     */
    TenantIdpTestRequest: {
      idpAlias?: string;
      idp?: components['schemas']['TenantIdp'];
    };
    /**
     * @example {
     *       "success": true,
     *       "protocol": "OIDC",
     *       "latencyMs": 412,
     *       "message": "Đã lấy được tài liệu khám phá OIDC và khoá ký của IdP.",
     *       "checkedAt": "2026-10-19T10:12:00+07:00"
     *     }
     */
    TenantIdpTestResult: {
      success: boolean;
      /** @enum {string} */
      protocol?: 'OIDC' | 'SAML';
      latencyMs?: number;
      /** @description Mô tả kết quả hoặc lỗi, tiếng Việt */
      message?: string;
      checkedAt: components['schemas']['DateTime'];
    };
    /**
     * @description Báo cáo truy cập bất thường từ cảnh báo thiết bị mới.
     * @example {
     *       "loginEventId": "7d2a9c1e-4b5f-4e8a-9c3d-1f2e3a4b5c6d",
     *       "occurredAt": "2026-10-19T06:42:00+07:00",
     *       "note": "Tôi không đăng nhập vào thời điểm này."
     *     }
     */
    SuspiciousAccessReport: {
      /** @description Mã sự kiện đăng nhập của Keycloak trong cảnh báo */
      loginEventId?: string;
      occurredAt?: components['schemas']['DateTime'];
      note?: string;
    };
    /**
     * @example {
     *       "reportId": "0192f3b4-1a2b-7c3d-8e4f-5a6b7c8d9e01",
     *       "sessionsRevoked": 3,
     *       "passwordChangeRequired": true,
     *       "passwordChangeUrl": "/realms/mission/login-actions/required-action?execution=UPDATE_PASSWORD"
     *     }
     */
    SuspiciousAccessResult: {
      reportId: components['schemas']['Uuid'];
      sessionsRevoked: number;
      passwordChangeRequired: boolean;
      /**
       * Format: uri-reference
       * @description Luồng đổi mật khẩu của Keycloak
       */
      passwordChangeUrl?: string;
    };
    /**
     * @description Loại ngày (`config.holiday_calendar.day_kind`).
     * @enum {string}
     */
    HolidayDayKind: 'NGHI_LE' | 'NGHI_BU' | 'LAM_BU';
    /**
     * @description Lịch quốc gia hoặc lịch riêng tenant (`config.holiday_calendar.scope`).
     * @enum {string}
     */
    HolidayScope: 'QUOC_GIA' | 'TENANT';
    /**
     * @example {
     *       "id": "0192f4c5-2b3c-7d4e-9f50-6a7b8c9d0e12",
     *       "calendarDate": "2026-09-02",
     *       "dayKind": "NGHI_LE",
     *       "name": "Quốc khánh",
     *       "scope": "QUOC_GIA",
     *       "rowVersion": 0
     *     }
     */
    HolidayCalendarDay: {
      id: components['schemas']['Uuid'];
      calendarDate: components['schemas']['Date'];
      dayKind: components['schemas']['HolidayDayKind'];
      name: string;
      scope: components['schemas']['HolidayScope'];
      rowVersion: components['schemas']['RowVersion'];
    };
    /**
     * @example {
     *       "calendarDate": "2026-12-26",
     *       "dayKind": "LAM_BU",
     *       "name": "Làm bù cho ngày nghỉ 02/01/2027"
     *     }
     */
    HolidayCalendarDayInput: {
      calendarDate: components['schemas']['Date'];
      dayKind: components['schemas']['HolidayDayKind'];
      name: string;
    };
    HolidayCalendarDayPage: {
      items: components['schemas']['HolidayCalendarDay'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /**
     * @description Lịch ngày làm việc của tenant trong một năm (lịch quốc gia hợp lịch riêng).
     * @example {
     *       "year": 2026,
     *       "weekendDays": [
     *         6,
     *         7
     *       ],
     *       "days": [
     *         {
     *           "date": "2026-09-02",
     *           "dayKind": "NGHI_LE",
     *           "name": "Quốc khánh",
     *           "scope": "QUOC_GIA"
     *         },
     *         {
     *           "date": "2026-12-26",
     *           "dayKind": "LAM_BU",
     *           "name": "Làm bù cho ngày nghỉ 02/01/2027",
     *           "scope": "TENANT"
     *         }
     *       ],
     *       "workingDaysByMonth": [
     *         21,
     *         15,
     *         22,
     *         21,
     *         19,
     *         22,
     *         23,
     *         21,
     *         20,
     *         22,
     *         21,
     *         23
     *       ]
     *     }
     */
    WorkingCalendar: {
      year: number;
      /** @description Thứ trong tuần là ngày nghỉ, ISO 8601 (6 = thứ Bảy, 7 = Chủ nhật) (suy luận). */
      weekendDays?: number[];
      /** @description Ngày đặc biệt trong năm. */
      days: {
        date: components['schemas']['Date'];
        dayKind: components['schemas']['HolidayDayKind'];
        name: string;
        scope: components['schemas']['HolidayScope'];
      }[];
      /** @description Số ngày làm việc của 12 tháng (suy luận). */
      workingDaysByMonth?: number[];
    };
    /** @description Quy tắc bật dần (`config.feature_flag.rollout`). */
    FeatureFlagRollout: {
      /** @description Chỉ bật cho các đơn vị này và cấp dưới; trống là toàn tenant. */
      unitIds?: components['schemas']['Uuid'][];
      /** @description Tỷ lệ người dùng được bật */
      percent?: number;
    };
    /**
     * @example {
     *       "flagKey": "ai.weeklySummary",
     *       "description": "Khối \"Tóm tắt tuần\" do AI tạo trên trang chủ.",
     *       "enabled": true,
     *       "rollout": {
     *         "unitIds": [],
     *         "percent": 100
     *       },
     *       "validFrom": null,
     *       "validTo": null,
     *       "rowVersion": 2
     *     }
     */
    FeatureFlag: {
      flagKey: string;
      /** @description Mô tả cờ do nền tảng khai báo (suy luận) */
      readonly description?: string;
      enabled: boolean;
      rollout?: components['schemas']['FeatureFlagRollout'];
      /** Format: date-time */
      validFrom?: string | null;
      /** Format: date-time */
      validTo?: string | null;
      rowVersion: components['schemas']['RowVersion'];
    };
    /**
     * @example {
     *       "enabled": false
     *     }
     */
    FeatureFlagInput: {
      enabled: boolean;
      rollout?: components['schemas']['FeatureFlagRollout'];
      /** Format: date-time */
      validFrom?: string | null;
      /** Format: date-time */
      validTo?: string | null;
    };
    FeatureFlagPage: {
      items: components['schemas']['FeatureFlag'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /**
     * @description Tám dấu hiệu bất thường (`oversight.anomaly_alert.signal_code`).
     * @enum {string}
     */
    ConfigAnomalySignalCode:
      | 'TU_CHAM_CAO'
      | 'CAO_BANG'
      | 'MUC_TIEU_DE'
      | 'MINH_CHUNG_TRUNG'
      | 'CAP_NHAT_DON'
      | 'SUA_SAU_KHOA'
      | 'CHE_NHO'
      | 'MIEN_TRU_LAP';
    /** @description Ngưỡng của một dấu hiệu; tham số theo từng dấu hiệu nên để dạng ánh xạ tên → số (suy luận). */
    AnomalySignalThreshold: {
      signalCode: components['schemas']['ConfigAnomalySignalCode'];
      enabled: boolean;
      /** @description Ví dụ `{ "consecutiveQuarters": 3, "scoreGap": 5 }` cho dấu hiệu 1. */
      params: {
        [key: string]: number;
      };
    };
    /**
     * @description Phiên bản ngưỡng hiện hành của tenant (lưu trong `config.tenant_setting`).
     * @example {
     *       "version": 3,
     *       "reason": "Giảm ngưỡng dồn cập nhật cuối kỳ từ 70% xuống 60% theo kết quả quý III.",
     *       "updatedAt": "2026-10-05T14:20:00+07:00",
     *       "updatedBy": {
     *         "id": "0192f5d6-3c4d-7e5f-8a61-7b8c9d0e1f23",
     *         "name": "Lê Minh Châu"
     *       },
     *       "signals": [
     *         {
     *           "signalCode": "TU_CHAM_CAO",
     *           "enabled": true,
     *           "params": {
     *             "consecutiveQuarters": 3,
     *             "scoreGap": 5
     *           }
     *         },
     *         {
     *           "signalCode": "CAO_BANG",
     *           "enabled": true,
     *           "params": {
     *             "sameLevelPercent": 90
     *           }
     *         },
     *         {
     *           "signalCode": "MUC_TIEU_DE",
     *           "enabled": true,
     *           "params": {
     *             "earlyWeeks": 2,
     *             "belowPreviousPercent": 20
     *           }
     *         },
     *         {
     *           "signalCode": "MINH_CHUNG_TRUNG",
     *           "enabled": true,
     *           "params": {
     *             "ocrSimilarityPercent": 95
     *           }
     *         },
     *         {
     *           "signalCode": "CAP_NHAT_DON",
     *           "enabled": true,
     *           "params": {
     *             "lastDaysPercent": 60,
     *             "lastDays": 3
     *           }
     *         },
     *         {
     *           "signalCode": "SUA_SAU_KHOA",
     *           "enabled": true,
     *           "params": {}
     *         },
     *         {
     *           "signalCode": "CHE_NHO",
     *           "enabled": true,
     *           "params": {
     *             "taskIncreasePercent": 50
     *           }
     *         },
     *         {
     *           "signalCode": "MIEN_TRU_LAP",
     *           "enabled": true,
     *           "params": {
     *             "exemptionsIn2Quarters": 3
     *           }
     *         }
     *       ],
     *       "rowVersion": 3
     *     }
     */
    AnomalyThresholdSet: {
      version: number;
      reason?: string;
      updatedAt?: components['schemas']['DateTime'];
      updatedBy?: components['schemas']['Ref'];
      signals: components['schemas']['AnomalySignalThreshold'][];
      rowVersion: components['schemas']['RowVersion'];
    };
    AnomalyThresholdUpdate: {
      signals: components['schemas']['AnomalySignalThreshold'][];
      /** @description Lý do thay đổi (BR-14-09); độ dài tối thiểu là suy luận */
      reason: string;
    };
    /**
     * @description Loại kỳ (`config.period.kind`).
     * @enum {string}
     */
    PeriodKind: 'THANG' | 'QUY' | 'NAM' | 'NAM_HOC';
    /**
     * @description Luồng áp dụng của kỳ (`config.period.flow`); khác `Flow` vì có `CA_HAI`.
     * @enum {string}
     */
    PeriodFlow: 'DANG' | 'NHA_NUOC' | 'CA_HAI';
    /** @description Lịch mốc của kỳ (`config.period.milestones`, TL-02a SCR-0801 trường 2). */
    PeriodMilestones: {
      planApprovalDeadline?: components['schemas']['Date'];
      evidenceDeadline?: components['schemas']['Date'];
      acceptanceBacklogDeadline?: components['schemas']['Date'];
      plannedLockDate?: components['schemas']['Date'];
    };
    /** @description Phiên bản Policy Pack ghim cho kỳ (`policy.policy_binding` theo `period_id`). */
    PeriodPinnedPolicyPack: {
      flow: components['schemas']['Flow'];
      packVersionId: components['schemas']['Uuid'];
      packCode?: string;
      version?: string;
    };
    /**
     * @description Trạng thái lệnh khoá kỳ (`config.period_lock_order.status`).
     * @enum {string}
     */
    PeriodLockOrderStatus: 'NHAP' | 'CHO_DUYET' | 'DA_DUYET' | 'DANG_CHAY' | 'HOAN_TAT' | 'DA_HUY';
    /**
     * @description Lệnh khoá kỳ (`config.period_lock_order`).
     * @example {
     *       "id": "0192f6e7-4d5e-7f60-8b72-8c9d0e1f2a34",
     *       "periodId": "0192f6e7-0000-7f60-8b72-8c9d0e1f2a01",
     *       "status": "DANG_CHAY",
     *       "basisDoc": "Thông báo 21-TB/ĐUK ngày 28/12/2026",
     *       "plannedLockAt": "2027-01-04T07:00:00+07:00",
     *       "requestedBy": {
     *         "id": "0192f6e7-1111-7f60-8b72-8c9d0e1f2a02",
     *         "name": "Hoàng Đức Dũng"
     *       },
     *       "requestedAt": "2026-12-29T09:15:00+07:00",
     *       "approvedBy": {
     *         "id": "0192f6e7-2222-7f60-8b72-8c9d0e1f2a03",
     *         "name": "Phạm Quang Huy"
     *       },
     *       "approvedAt": "2026-12-30T16:40:00+07:00",
     *       "startedBy": {
     *         "id": "0192f6e7-3333-7f60-8b72-8c9d0e1f2a04",
     *         "name": "Vũ Thị Lan"
     *       },
     *       "startedAt": "2027-01-04T07:02:00+07:00",
     *       "sagaId": "0192f6e7-5e6f-7a81-9c83-9d0e1f2a3b45",
     *       "idempotencyKey": "CK-2026Q4",
     *       "rowVersion": 4
     *     }
     */
    PeriodLockOrder: {
      id: components['schemas']['Uuid'];
      periodId: components['schemas']['Uuid'];
      status: components['schemas']['PeriodLockOrderStatus'];
      /** @description Văn bản căn cứ */
      basisDoc: string;
      /**
       * Format: date-time
       * @description Thời điểm dự kiến khởi chạy (suy luận cột)
       */
      plannedLockAt?: string | null;
      requestedBy: components['schemas']['Ref'];
      requestedAt?: components['schemas']['DateTime'];
      approvedBy?: components['schemas']['Ref'] | null;
      /** Format: date-time */
      approvedAt?: string | null;
      startedBy?: components['schemas']['Ref'] | null;
      /** Format: date-time */
      startedAt?: string | null;
      /**
       * Format: uuid
       * @description `flowable.saga_instance.id`
       */
      sagaId?: string | null;
      /** @description Khoá chống khởi chạy trùng, dạng `CK-{năm}{kỳ}` */
      readonly idempotencyKey: string;
      rowVersion: components['schemas']['RowVersion'];
    };
    /**
     * @description Kỳ đánh giá (`config.period`).
     * @example {
     *       "id": "0192f6e7-0000-7f60-8b72-8c9d0e1f2a01",
     *       "code": "2026-Q4",
     *       "name": "Quý IV/2026",
     *       "kind": "QUY",
     *       "flow": "CA_HAI",
     *       "parentPeriodId": "0192f6e7-0000-7f60-8b72-8c9d0e1f2a00",
     *       "startDate": "2026-10-01",
     *       "endDate": "2026-12-31",
     *       "periodBucket": 20264,
     *       "planDeadline": "2026-10-09",
     *       "submissionDeadline": "2027-01-08",
     *       "status": "DANG_KHOA",
     *       "notStarted": false,
     *       "lockedAt": null,
     *       "lockedBy": null,
     *       "lockSagaId": "0192f6e7-5e6f-7a81-9c83-9d0e1f2a3b45",
     *       "reopenCount": 0,
     *       "milestones": {
     *         "planApprovalDeadline": "2026-10-09",
     *         "evidenceDeadline": "2026-12-31",
     *         "acceptanceBacklogDeadline": "2027-01-03",
     *         "plannedLockDate": "2027-01-04"
     *       },
     *       "pinnedPolicyPacks": [
     *         {
     *           "flow": "DANG",
     *           "packVersionId": "0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a70",
     *           "packCode": "khoi-dang/quy-dinh-moi",
     *           "version": "2.0"
     *         }
     *       ],
     *       "warnings": [],
     *       "rowVersion": 12
     *     }
     */
    Period: {
      id: components['schemas']['Uuid'];
      /** @description Ví dụ `2026-Q4`, `2026`, `2027-03` */
      code: string;
      name: string;
      kind: components['schemas']['PeriodKind'];
      flow: components['schemas']['PeriodFlow'];
      /** Format: uuid */
      parentPeriodId?: string | null;
      startDate: components['schemas']['Date'];
      endDate: components['schemas']['Date'];
      /** @description Giá trị phân vùng, ví dụ 20264 */
      readonly periodBucket?: number;
      /** Format: date */
      planDeadline?: string | null;
      /** Format: date */
      submissionDeadline?: string | null;
      status: components['schemas']['PeriodStatus'];
      /** @description Kỳ đã tạo nhưng chưa tới ngày bắt đầu (hiển thị "Đã tạo") */
      readonly notStarted?: boolean;
      /** Format: date-time */
      lockedAt?: string | null;
      lockedBy?: components['schemas']['Ref'] | null;
      /** Format: uuid */
      lockSagaId?: string | null;
      reopenCount?: number;
      milestones?: components['schemas']['PeriodMilestones'];
      pinnedPolicyPacks?: components['schemas']['PeriodPinnedPolicyPack'][];
      currentLockOrder?: components['schemas']['PeriodLockOrder'] | null;
      /** @description Cảnh báo lịch, ví dụ khoá muộn hơn tham số (BR-08-12). */
      readonly warnings?: string[];
      rowVersion: components['schemas']['RowVersion'];
    };
    PeriodPage: {
      items: components['schemas']['Period'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /**
     * @example {
     *       "code": "2027-Q1",
     *       "name": "Quý I/2027",
     *       "kind": "QUY",
     *       "flow": "CA_HAI",
     *       "parentPeriodId": "0192f6e7-0000-7f60-8b72-8c9d0e1f2a10",
     *       "startDate": "2027-01-01",
     *       "endDate": "2027-03-31",
     *       "planDeadline": "2027-01-08"
     *     }
     */
    PeriodCreate: {
      code: string;
      name: string;
      kind: components['schemas']['PeriodKind'];
      flow: components['schemas']['PeriodFlow'];
      parentPeriodId?: components['schemas']['Uuid'];
      startDate: components['schemas']['Date'];
      endDate: components['schemas']['Date'];
      planDeadline?: components['schemas']['Date'];
      submissionDeadline?: components['schemas']['Date'];
      milestones?: components['schemas']['PeriodMilestones'];
    };
    /**
     * @example {
     *       "basisDoc": "Thông báo 21-TB/ĐUK ngày 28/12/2026",
     *       "plannedLockAt": "2027-01-04T07:00:00+07:00"
     *     }
     */
    PeriodLockOrderCreate: {
      basisDoc: string;
      plannedLockAt?: components['schemas']['DateTime'];
    };
    PeriodLockOrderApprove: {
      comment?: string;
    };
    /**
     * @example {
     *       "lockOrderId": "0192f6e7-4d5e-7f60-8b72-8c9d0e1f2a34"
     *     }
     */
    PeriodLockStart: {
      lockOrderId: components['schemas']['Uuid'];
    };
    /** @description Ghi chú kèm hành động điều khiển (ghi sổ cái, BR-08-10). */
    PeriodLockControl: {
      comment?: string;
    };
    PeriodLockRetryRequest: {
      /** @description Chỉ chạy lại các hồ sơ này; bỏ trống là toàn bộ hàng đợi lỗi. */
      profileIds?: components['schemas']['Uuid'][];
    };
    /**
     * @example {
     *       "reason": "Phát hiện sai sót cấu hình trọng số khối 3 khi đang tính điểm; cần sửa Policy Pack trước khi khoá lại."
     *     }
     */
    PeriodLockCancelRequestInput: {
      /** @description Lý do huỷ (độ dài tối thiểu là suy luận) */
      reason: string;
    };
    /**
     * @description Trạng thái saga (`flowable.saga_instance.status`).
     * @enum {string}
     */
    SagaStatus:
      'DANG_CHAY' | 'CHO_SU_KIEN' | 'HOAN_TAT' | 'DANG_BU_TRU' | 'DA_BU_TRU' | 'LOI_CAN_XU_LY';
    /**
     * @description Trạng thái một bước saga (`config.period_lock_step.status`).
     * @enum {string}
     */
    PeriodLockStepStatus: 'CHO' | 'DANG_CHAY' | 'DAT' | 'DAT_CO_CANH_BAO' | 'LOI' | 'DA_BU_TRU';
    /** @description Một bước saga chiếu từ `config.period_lock_step`. */
    PeriodLockStep: {
      stepNo: number;
      name: string;
      status: components['schemas']['PeriodLockStepStatus'];
      /** Format: date-time */
      startedAt?: string | null;
      /** Format: date-time */
      finishedAt?: string | null;
      /** @description Tóm tắt kết quả (số hồ sơ, cảnh báo). */
      resultSummary?: {
        [key: string]: unknown;
      };
      compensationAction?: string | null;
      /** Format: date-time */
      compensatedAt?: string | null;
    };
    /** @description Đề nghị huỷ và bù trừ đang có (cột `cancel_requested_by`, `cancel_approved_by`). */
    PeriodLockCancelState: {
      requestedBy: components['schemas']['Ref'];
      requestedAt: components['schemas']['DateTime'];
      reason: string;
      approvedBy?: components['schemas']['Ref'] | null;
      /** Format: date-time */
      approvedAt?: string | null;
    };
    /**
     * @description Tiến trình saga khoá kỳ trên SCR-0801.
     * @example {
     *       "periodId": "0192f6e7-0000-7f60-8b72-8c9d0e1f2a01",
     *       "lockOrder": {
     *         "id": "0192f6e7-4d5e-7f60-8b72-8c9d0e1f2a34",
     *         "periodId": "0192f6e7-0000-7f60-8b72-8c9d0e1f2a01",
     *         "status": "DANG_CHAY",
     *         "basisDoc": "Thông báo 21-TB/ĐUK ngày 28/12/2026",
     *         "requestedBy": {
     *           "id": "0192f6e7-1111-7f60-8b72-8c9d0e1f2a02",
     *           "name": "Hoàng Đức Dũng"
     *         },
     *         "idempotencyKey": "CK-2026Q4",
     *         "rowVersion": 4
     *       },
     *       "sagaStatus": "DANG_CHAY",
     *       "templateKey": "chot-ky",
     *       "templateVersion": 3,
     *       "pauseRequested": false,
     *       "totals": {
     *         "profiles": 298760,
     *         "scored": 233040,
     *         "failed": 17
     *       },
     *       "ratePerMinute": 5800,
     *       "estimatedFinishAt": "2027-01-04T10:30:00+07:00",
     *       "budgetFinishAt": "2027-01-04T13:02:00+07:00",
     *       "excludedEvidenceCount": 214,
     *       "steps": [
     *         {
     *           "stepNo": 0,
     *           "name": "Kiểm tra tiền điều kiện",
     *           "status": "DAT_CO_CANH_BAO",
     *           "startedAt": "2027-01-04T07:02:00+07:00",
     *           "finishedAt": "2027-01-04T07:05:00+07:00",
     *           "resultSummary": {
     *             "excludedEvidence": 214
     *           },
     *           "compensationAction": null,
     *           "compensatedAt": null
     *         },
     *         {
     *           "stepNo": 1,
     *           "name": "Khoá kỳ",
     *           "status": "DAT",
     *           "startedAt": "2027-01-04T07:05:00+07:00",
     *           "finishedAt": "2027-01-04T07:05:40+07:00",
     *           "resultSummary": {
     *             "event": "EVT-09"
     *           },
     *           "compensationAction": "Trả kỳ về MO",
     *           "compensatedAt": null
     *         },
     *         {
     *           "stepNo": 2,
     *           "name": "Tính điểm hàng loạt",
     *           "status": "DANG_CHAY",
     *           "startedAt": "2027-01-04T07:05:40+07:00",
     *           "finishedAt": null,
     *           "resultSummary": {
     *             "percent": 78,
     *             "retryingBatches": 3
     *           },
     *           "compensationAction": "Đánh dấu lô điểm bị thay thế",
     *           "compensatedAt": null
     *         },
     *         {
     *           "stepNo": 3,
     *           "name": "Sinh mẫu và bảng chấm điểm",
     *           "status": "CHO",
     *           "startedAt": null,
     *           "finishedAt": null,
     *           "resultSummary": {},
     *           "compensationAction": "Thu hồi tệp đã sinh",
     *           "compensatedAt": null
     *         },
     *         {
     *           "stepNo": 4,
     *           "name": "Ký số lô",
     *           "status": "CHO",
     *           "startedAt": null,
     *           "finishedAt": null,
     *           "resultSummary": {},
     *           "compensationAction": "Huỷ yêu cầu ký đang chờ",
     *           "compensatedAt": null
     *         },
     *         {
     *           "stepNo": 5,
     *           "name": "Hoàn tất",
     *           "status": "CHO",
     *           "startedAt": null,
     *           "finishedAt": null,
     *           "resultSummary": {},
     *           "compensationAction": null,
     *           "compensatedAt": null
     *         }
     *       ],
     *       "cancelRequest": null,
     *       "cancellable": true,
     *       "warnings": [
     *         "Có 214 minh chứng chưa được nghiệm thu sẽ không được tính điểm trong kỳ."
     *       ],
     *       "updatedAt": "2027-01-04T07:44:00+07:00"
     *     }
     */
    PeriodLockProgress: {
      periodId: components['schemas']['Uuid'];
      lockOrder: components['schemas']['PeriodLockOrder'];
      sagaStatus: components['schemas']['SagaStatus'];
      /** @description Mẫu saga, ví dụ `chot-ky` */
      templateKey?: string;
      templateVersion?: number;
      /** @description Đã đặt cờ tạm dừng sau bước hiện tại */
      pauseRequested: boolean;
      totals: {
        profiles: number;
        scored: number;
        failed: number;
      };
      /** @description Hồ sơ mỗi phút, tính lại sau mỗi lô */
      ratePerMinute?: number | null;
      /** Format: date-time */
      estimatedFinishAt?: string | null;
      /**
       * Format: date-time
       * @description Hạn theo ngân sách NFR-06; vượt thì MSG-08-04
       */
      budgetFinishAt?: string | null;
      /** @description Minh chứng không tính trong kỳ (BR-08-05, MSG-08-03) */
      excludedEvidenceCount?: number;
      steps: components['schemas']['PeriodLockStep'][];
      cancelRequest?: components['schemas']['PeriodLockCancelState'] | null;
      /** @description Còn trước khi bước 4 hoàn tất (BR-08-09) */
      cancellable?: boolean;
      warnings?: string[];
      updatedAt?: components['schemas']['DateTime'];
    };
    /**
     * @description Trạng thái lô tính điểm (`scoring.score_job.status`).
     * @enum {string}
     */
    ScoreBatchStatus: 'CHO' | 'DANG_CHAY' | 'XONG' | 'LOI';
    /**
     * @description Một lô tính điểm (`scoring.score_job`).
     * @example {
     *       "id": "0192f7f8-5e6f-7a81-9c83-9d0e1f2a3b56",
     *       "unit": {
     *         "id": "0192f7f8-0000-7a81-9c83-9d0e1f2a3b00",
     *         "name": "Ban Tổ chức Đảng uỷ"
     *       },
     *       "profileCount": 1000,
     *       "status": "XONG",
     *       "attempts": 1,
     *       "finishedAt": "2027-01-04T07:21:13+07:00"
     *     }
     */
    PeriodLockBatch: {
      id: components['schemas']['Uuid'];
      unit?: components['schemas']['Ref'];
      profileCount: number;
      status: components['schemas']['ScoreBatchStatus'];
      attempts: number;
      /** Format: date-time */
      finishedAt?: string | null;
    };
    PeriodLockBatchPage: {
      items: components['schemas']['PeriodLockBatch'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /**
     * @description Hồ sơ lỗi trong hàng đợi lỗi của bước tính điểm.
     * @example {
     *       "profileId": "0192f8a9-6f70-7b82-8d94-0e1f2a3b4c67",
     *       "person": {
     *         "id": "0192f8a9-1111-7b82-8d94-0e1f2a3b4c01",
     *         "name": "Nguyễn Thị Mai"
     *       },
     *       "unit": {
     *         "id": "0192f8a9-2222-7b82-8d94-0e1f2a3b4c02",
     *         "name": "Văn phòng Đảng uỷ"
     *       },
     *       "batchId": "0192f7f8-5e6f-7a81-9c83-9d0e1f2a3b56",
     *       "error": "Thiếu phân công chính tại ngày cuối kỳ.",
     *       "attempts": 3,
     *       "lastAttemptAt": "2027-01-04T07:30:02+07:00"
     *     }
     */
    PeriodLockFailure: {
      profileId: components['schemas']['Uuid'];
      person?: components['schemas']['Ref'];
      unit?: components['schemas']['Ref'];
      batchId?: components['schemas']['Uuid'];
      /** @description Mô tả lỗi, không chứa thông tin nội bộ */
      error: string;
      attempts: number;
      lastAttemptAt?: components['schemas']['DateTime'];
    };
    PeriodLockFailurePage: {
      items: components['schemas']['PeriodLockFailure'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /**
     * @description Một dòng nhật ký sự kiện của saga (`ledger.ledger_entry` lọc theo saga).
     * @example {
     *       "at": "2027-01-04T07:05:40+07:00",
     *       "event": "Kỳ Quý IV/2026 chuyển DANG_KHOA, phát KyDaKhoa.",
     *       "evt": "EVT-09",
     *       "actor": null,
     *       "ledgerEntryId": "0192f9ba-7081-7c93-8ea5-1f2a3b4c5d78"
     *     }
     */
    PeriodLockEvent: {
      at: components['schemas']['DateTime'];
      /** @description Mô tả sự kiện, tiếng Việt */
      event: string;
      /** @description Mã EVT nếu có, ví dụ EVT-09 */
      evt?: string | null;
      actor?: components['schemas']['Ref'] | null;
      /** Format: uuid */
      ledgerEntryId?: string | null;
    };
    PeriodLockEventPage: {
      items: components['schemas']['PeriodLockEvent'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /**
     * @description Định nghĩa BPMN và trạng thái các hoạt động của saga.
     * @example {
     *       "templateKey": "chot-ky",
     *       "templateVersion": 3,
     *       "bpmnXml": "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\">…</definitions>",
     *       "activeActivityIds": [
     *         "buoc2TinhDiem"
     *       ],
     *       "completedActivityIds": [
     *         "buoc0TienDieuKien",
     *         "buoc1KhoaKy"
     *       ],
     *       "failedActivityIds": []
     *     }
     */
    PeriodLockDiagram: {
      templateKey?: string;
      templateVersion?: number;
      /** @description BPMN 2.0 XML; chỉ hiển thị bằng trình xem BPMN */
      bpmnXml: string;
      activeActivityIds: string[];
      completedActivityIds: string[];
      failedActivityIds: string[];
    };
    /**
     * @description Trạng thái phiên bản UI Pack (`config.ui_pack_version.status`, bộ trạng thái TL-02b).
     * @enum {string}
     */
    UiPackVersionStatus: 'NHAP' | 'DANG_KIEM_TRA' | 'CHO_DUYET' | 'DA_PHAT_HANH' | 'THAY_THE';
    /**
     * @description Design Token theo định dạng W3C Design Tokens (`$type`, `$value`), lưu ở `config.ui_pack_version.tokens_json`. Không có trường nhận HTML hay JavaScript.
     * @example {
     *       "color": {
     *         "brand": {
     *           "primary": {
     *             "$type": "color",
     *             "$value": "#0A7E8C"
     *           },
     *           "on-primary": {
     *             "$type": "color",
     *             "$value": "#FFFFFF"
     *           }
     *         }
     *       },
     *       "radius": {
     *         "card": {
     *           "$type": "dimension",
     *           "$value": "14px"
     *         }
     *       }
     *     }
     */
    DesignTokens: {
      [key: string]: unknown;
    };
    /**
     * @description Nút cây SDUI; `component` phải có trong registry (BR-18-02), `props` kiểm theo `propsSchema` của component, `visibleWhen` là biểu thức CEL chỉ đọc ngữ cảnh người dùng.
     * @example {
     *       "component": "vt.layout.grid",
     *       "props": {
     *         "columns": {
     *           "xs": 1,
     *           "md": 2,
     *           "xl": 3
     *         }
     *       },
     *       "children": [
     *         {
     *           "component": "vt.widget.myTasksToday",
     *           "props": {
     *             "limit": 7
     *           }
     *         }
     *       ]
     *     }
     */
    SduiNode: {
      component: string;
      props?: {
        [key: string]: unknown;
      };
      visibleWhen?: string;
      children?: components['schemas']['SduiNode'][];
    };
    /** @description Tài nguyên của UI Pack (`assets_json`), ví dụ logo SVG đã làm sạch ≤ 50 KB. */
    UiPackAsset: {
      /** @description Ví dụ `logo` */
      key: string;
      path: string;
      /** @enum {string} */
      mediaType?: 'image/svg+xml' | 'image/png' | 'font/woff2';
      sha256: string;
      sizeBytes?: number;
    };
    /** @description Biểu mẫu gắn trong UI Pack. */
    UiPackFormRef: {
      formKey: string;
      version: string;
    };
    /** @description Phiên bản Policy Pack tham chiếu (`policy_pack_ref`, `policy_pack_version_id`). */
    PolicyPackVersionRef: {
      packVersionId: components['schemas']['Uuid'];
      packCode?: string;
      version?: string;
    };
    /**
     * @description Một phiên bản UI Pack (`config.ui_pack_version`).
     * @example {
     *       "id": "0192fa0b-8192-7da4-9fb6-2a3b4c5d6e89",
     *       "uiPackId": "0192fa0b-0000-7da4-9fb6-2a3b4c5d6e00",
     *       "version": "1.8.0",
     *       "status": "NHAP",
     *       "tokens": {
     *         "color": {
     *           "brand": {
     *             "primary": {
     *               "$type": "color",
     *               "$value": "#0A7E8C"
     *             }
     *           }
     *         },
     *         "radius": {
     *           "card": {
     *             "$type": "dimension",
     *             "$value": "14px"
     *           }
     *         }
     *       },
     *       "layouts": {
     *         "home": {
     *           "component": "vt.layout.grid",
     *           "children": [
     *             {
     *               "component": "vt.widget.myTasksToday",
     *               "props": {
     *                 "limit": 7
     *               }
     *             },
     *             {
     *               "component": "vt.widget.kpiTile"
     *             }
     *           ]
     *         }
     *       },
     *       "assets": [
     *         {
     *           "key": "logo",
     *           "path": "assets/logo-so-y-te-x.svg",
     *           "mediaType": "image/svg+xml",
     *           "sha256": "9f2c7a1e4b5d6c8f0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f6071",
     *           "sizeBytes": 8421
     *         }
     *       ],
     *       "forms": [
     *         {
     *           "formKey": "phieu-vc-yte",
     *           "version": "12"
     *         }
     *       ],
     *       "componentRegistryVersion": "2026.3",
     *       "policyPack": {
     *         "packVersionId": "0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a72",
     *         "packCode": "nn-335/so-y-te",
     *         "version": "1.3"
     *       },
     *       "elderMode": true,
     *       "fewSteps": false,
     *       "bundleSha256": null,
     *       "bundleSizeGzipBytes": 11469,
     *       "publishedAt": null,
     *       "reviewComment": null,
     *       "createdBy": {
     *         "id": "0192fa0b-1111-7da4-9fb6-2a3b4c5d6e01",
     *         "name": "Đỗ Thanh Tâm"
     *       },
     *       "updatedAt": "2026-10-19T09:40:00+07:00",
     *       "rowVersion": 6
     *     }
     */
    UiPackVersion: {
      id: components['schemas']['Uuid'];
      uiPackId: components['schemas']['Uuid'];
      version: string;
      status: components['schemas']['UiPackVersionStatus'];
      tokens: components['schemas']['DesignTokens'];
      /** @description Bố cục theo bề mặt cấu hình được (ví dụ `home`); tối đa 12 widget trên trang chủ. */
      layouts: {
        [key: string]: components['schemas']['SduiNode'];
      };
      assets?: components['schemas']['UiPackAsset'][];
      forms?: components['schemas']['UiPackFormRef'][];
      componentRegistryVersion: string;
      policyPack?: components['schemas']['PolicyPackVersionRef'];
      elderMode: boolean;
      fewSteps: boolean;
      readonly bundleSha256?: string | null;
      /** @description Kích thước gói đã biên dịch (suy luận; ngân sách 30 KB) */
      readonly bundleSizeGzipBytes?: number | null;
      /** Format: date-time */
      readonly publishedAt?: string | null;
      /** @description Ý kiến của người duyệt khi từ chối */
      readonly reviewComment?: string | null;
      createdBy?: components['schemas']['Ref'];
      updatedAt?: components['schemas']['DateTime'];
      rowVersion: components['schemas']['RowVersion'];
    };
    UiPackVersionPage: {
      items: components['schemas']['UiPackVersion'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /**
     * @example {
     *       "version": "1.9.0",
     *       "cloneFromVersion": "1.8.0"
     *     }
     */
    UiPackVersionCreate: {
      version: string;
      /** @description Phiên bản nhân bản; `null` là bộ mặc định của `@vt/design-system`. */
      cloneFromVersion?: string | null;
    };
    UiPackVersionUpdate: {
      tokens: components['schemas']['DesignTokens'];
      layouts: {
        [key: string]: components['schemas']['SduiNode'];
      };
      assets?: components['schemas']['UiPackAsset'][];
      componentRegistryVersion: string;
      policyPackVersionId?: components['schemas']['Uuid'];
      elderMode: boolean;
      fewSteps: boolean;
    };
    /**
     * @description Một UI Pack của tenant (`config.ui_pack`).
     * @example {
     *       "id": "0192fa0b-0000-7da4-9fb6-2a3b4c5d6e00",
     *       "code": "so-y-te-x",
     *       "name": "UI Pack Sở Y tế tỉnh X",
     *       "currentVersionId": "0192fa0b-7000-7da4-9fb6-2a3b4c5d6e70",
     *       "currentVersion": "1.7.0",
     *       "draftVersion": "1.8.0"
     *     }
     */
    UiPackSummary: {
      id: components['schemas']['Uuid'];
      code: string;
      name: string;
      /** Format: uuid */
      currentVersionId?: string | null;
      currentVersion?: string | null;
      /** @description Phiên bản đang soạn nếu có (suy luận) */
      draftVersion?: string | null;
    };
    UiPackPage: {
      items: components['schemas']['UiPackSummary'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /** @description Một mục kiểm tra tự động của UI Pack. */
    UiPackCheck: {
      /**
       * @description Mã kiểm tra (suy luận tên giá trị)
       * @enum {string}
       */
      code: 'SCHEMA' | 'REGISTRY' | 'CONTRAST' | 'SIZE' | 'CEL_PARITY' | 'ELDER_MODE';
      passed: boolean;
      message?: string;
      /** @description Mã thông điệp TL-02b nếu có, ví dụ MSG-18-01 */
      msg?: string;
    };
    /**
     * @description Kết quả biên dịch thử một phiên bản UI Pack.
     * @example {
     *       "version": "1.8.0",
     *       "mode": "default",
     *       "cssVariables": {
     *         "--vt-color-primary": "#0A7E8C",
     *         "--vt-radius-card": "14px"
     *       },
     *       "primePreset": {
     *         "semantic": {
     *           "primary": {
     *             "500": "var(--vt-color-primary)"
     *           }
     *         }
     *       },
     *       "layouts": {
     *         "home": {
     *           "component": "vt.layout.grid",
     *           "children": [
     *             {
     *               "component": "vt.widget.myTasksToday"
     *             }
     *           ]
     *         }
     *       },
     *       "checks": [
     *         {
     *           "code": "CONTRAST",
     *           "passed": true,
     *           "message": "Tương phản 4,9 : 1, đạt AA."
     *         },
     *         {
     *           "code": "SIZE",
     *           "passed": true,
     *           "message": "Gói 11,2 KB gzip, trong ngân sách 30 KB."
     *         }
     *       ],
     *       "bundleSizeGzipBytes": 11469
     *     }
     */
    UiPackPreview: {
      version: string;
      /** @enum {string} */
      mode: 'default' | 'elderly';
      /** @description CSS custom properties sinh từ token (tên → giá trị). */
      cssVariables: {
        [key: string]: string;
      };
      /** @description Preset PrimeNG dạng JSON. */
      primePreset?: {
        [key: string]: unknown;
      };
      layouts: {
        [key: string]: components['schemas']['SduiNode'];
      };
      checks: components['schemas']['UiPackCheck'][];
      bundleSizeGzipBytes?: number;
    };
    /**
     * @description UI Pack hiện hành cho shell và ứng dụng di động, theo cấu trúc năm phần của TL-01 chương 7 (siêu dữ liệu, token, bố cục, biểu mẫu, plugin) cộng `modes`, `i18n` và thông tin toàn vẹn.
     * @example {
     *       "pack": "tinh-quang-ngai/ui",
     *       "version": "1.3.0",
     *       "requires": {
     *         "registry": "^2.4.0",
     *         "policyPack": "khoi-chinh-quyen/nd335@>=1.2.0 <2.0.0"
     *       },
     *       "tokens": {
     *         "color": {
     *           "primary": {
     *             "$type": "color",
     *             "$value": "#C8102E"
     *           }
     *         }
     *       },
     *       "modes": {
     *         "elderly": {
     *           "font": {
     *             "size": {
     *               "body": {
     *                 "$value": "18px"
     *               }
     *             }
     *           },
     *           "flags": {
     *             "reducedSteps": true,
     *             "minTouchTarget": "48px"
     *           }
     *         }
     *       },
     *       "layouts": {
     *         "home": {
     *           "component": "vt.layout.grid",
     *           "children": [
     *             {
     *               "component": "vt.widget.myTasksToday",
     *               "props": {
     *                 "limit": 7
     *               }
     *             }
     *           ]
     *         }
     *       },
     *       "forms": {},
     *       "plugins": [],
     *       "i18n": {
     *         "vi": {
     *           "home.notice.q4": "Hạn nộp hồ sơ quý IV là ngày 10/01/2027."
     *         }
     *       },
     *       "contentHash": "9f2c7a1e4b5d6c8f0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f6071",
     *       "signature": "MEUCIQDx…",
     *       "publishedAt": "2026-10-12T15:00:00+07:00"
     *     }
     */
    UiPackRuntime: {
      /** @description Mã gói, ví dụ `tinh-quang-ngai/ui` */
      pack: string;
      version: string;
      requires: {
        /** @description Dải phiên bản registry tương thích */
        registry: string;
        /** @description Dải phiên bản Policy Pack tương thích */
        policyPack?: string;
      };
      tokens: components['schemas']['DesignTokens'];
      /** @description Ghi đè token theo chế độ; `elderly` kèm `flags.reducedSteps`. */
      modes?: {
        [key: string]: unknown;
      };
      layouts: {
        [key: string]: components['schemas']['SduiNode'];
      };
      /** @description Cấu hình trình bày biểu mẫu theo khoá; dữ liệu phiếu tham chiếu Policy Pack qua `schemaRef`. */
      forms?: {
        [key: string]: unknown;
      };
      plugins?: {
        name: string;
        /** Format: uri-reference */
        remoteEntry: string;
        integrity?: string;
      }[];
      i18n?: {
        [key: string]: {
          [key: string]: string;
        };
      };
      /** @description SHA-256 của gói (`bundle_sha256`) */
      contentHash: string;
      /** @description Chữ ký số của gói, base64 */
      signature?: string;
      publishedAt?: components['schemas']['DateTime'];
    };
    UiPackReviewInput: {
      comment?: string;
    };
    /**
     * @example {
     *       "comment": "Màu chữ phụ trên nền thẻ chưa đạt AA, đề nghị chỉnh lại."
     *     }
     */
    UiPackRejectInput: {
      /** @description Ý kiến bắt buộc khi từ chối */
      comment: string;
    };
    /**
     * @example {
     *       "formKey": "phieu-vc-yte",
     *       "formVersion": "12"
     *     }
     */
    UiPackFormAttach: {
      formKey: string;
      formVersion: string;
    };
    /**
     * @description Trạng thái component trong registry (`config.component_registry.status`).
     * @enum {string}
     */
    UiComponentStatus: 'HIEU_LUC' | 'NGUNG';
    /**
     * @description Một component SDUI được phép (`config.component_registry`).
     * @example {
     *       "componentKey": "vt.widget.myTasksToday",
     *       "registryVersion": "2026.3",
     *       "propsSchema": {
     *         "type": "object",
     *         "additionalProperties": false,
     *         "properties": {
     *           "limit": {
     *             "type": "integer",
     *             "maximum": 20
     *           }
     *         }
     *       },
     *       "allowedClassification": "C1",
     *       "status": "HIEU_LUC",
     *       "container": false,
     *       "mobile": true,
     *       "plugin": null
     *     }
     */
    UiComponent: {
      componentKey: string;
      registryVersion: string;
      /** @description JSON Schema của thuộc tính được phép. */
      propsSchema: {
        [key: string]: unknown;
      };
      allowedClassification: components['schemas']['Classification'];
      status: components['schemas']['UiComponentStatus'];
      /** @description Được chứa children (TL-01 chương 7) */
      container?: boolean;
      /** @description Hỗ trợ trên ứng dụng di động */
      mobile?: boolean;
      /** @description Plugin cung cấp, ví dụ `fe-plugin-yte` */
      plugin?: string | null;
    };
    UiComponentPage: {
      items: components['schemas']['UiComponent'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /**
     * @description Trạng thái biểu mẫu (`config.form_schema.status`).
     * @enum {string}
     */
    UiFormStatus: 'NHAP' | 'DA_PHAT_HANH' | 'NGUNG';
    /**
     * @description Một phiên bản biểu mẫu động (`config.form_schema`).
     * @example {
     *       "formKey": "phieu-vc-yte",
     *       "version": "12",
     *       "status": "NHAP",
     *       "schemaJson": {
     *         "$schema": "https://json-schema.org/draft/2020-12/schema",
     *         "type": "object",
     *         "properties": {
     *           "ty_le_hsba_dat": {
     *             "type": "number",
     *             "x-cel": "self >= 0.0 && self <= 100.0",
     *             "x-classification": "C1"
     *           },
     *           "mo_ta_su_co": {
     *             "type": "string",
     *             "maxLength": 2000,
     *             "x-classification": "C2"
     *           }
     *         }
     *       },
     *       "uiJson": {
     *         "mo_ta_su_co": {
     *           "hideExpression": "form.su_co_y_khoa != \"CO\"",
     *           "label": "Mô tả sự cố và biện pháp khắc phục"
     *         }
     *       },
     *       "celRules": [
     *         {
     *           "field": "ty_le_hsba_dat",
     *           "expr": "self >= 0.0 && self <= 100.0",
     *           "message": "Giá trị phải từ 0 đến 100"
     *         }
     *       ],
     *       "policyPack": {
     *         "packVersionId": "0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a72",
     *         "packCode": "nn-335/so-y-te",
     *         "version": "1.3"
     *       },
     *       "lastTestRun": null,
     *       "updatedAt": "2026-10-19T09:30:00+07:00",
     *       "rowVersion": 9
     *     }
     */
    UiFormVersion: {
      formKey: string;
      version: string;
      status: components['schemas']['UiFormStatus'];
      /** @description JSON Schema 2020-12 của dữ liệu, có `x-cel` (quy tắc) và `x-classification` (C1, C2). */
      schemaJson: {
        [key: string]: unknown;
      };
      /** @description Cấu hình trường Formly, điều kiện hiển thị CEL, thông báo lỗi. */
      uiJson: {
        [key: string]: unknown;
      };
      celRules?: {
        field: string;
        expr: string;
        message: string;
        /** @default false */
        serverOnly: boolean;
      }[];
      policyPack?: components['schemas']['PolicyPackVersionRef'];
      lastTestRun?: components['schemas']['UiFormTestRunResult'] | null;
      updatedAt?: components['schemas']['DateTime'];
      rowVersion: components['schemas']['RowVersion'];
    };
    /**
     * @example {
     *       "version": "13",
     *       "cloneFromVersion": "12"
     *     }
     */
    UiFormVersionCreate: {
      version: string;
      cloneFromVersion?: string | null;
      policyPackVersionId?: components['schemas']['Uuid'];
    };
    UiFormVersionUpdate: {
      schemaJson: {
        [key: string]: unknown;
      };
      uiJson: {
        [key: string]: unknown;
      };
      celRules?: {
        field: string;
        expr: string;
        message: string;
        /** @default false */
        serverOnly: boolean;
      }[];
      policyPackVersionId?: components['schemas']['Uuid'];
    };
    /**
     * @example {
     *       "formKey": "phieu-vc-yte",
     *       "latestVersion": "12",
     *       "status": "NHAP",
     *       "updatedAt": "2026-10-19T09:30:00+07:00"
     *     }
     */
    UiFormSummary: {
      formKey: string;
      latestVersion: string;
      status: components['schemas']['UiFormStatus'];
      policyPack?: components['schemas']['PolicyPackVersionRef'];
      updatedAt?: components['schemas']['DateTime'];
    };
    UiFormPage: {
      items: components['schemas']['UiFormSummary'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    UiFormTestCaseInput: {
      caseId: string;
      /** @description Dữ liệu biểu mẫu mẫu (dữ liệu giả, không dữ liệu thật). */
      input: {
        [key: string]: unknown;
      };
      /** @description Kết quả cel-js tại trình duyệt */
      browserValid: boolean;
      browserErrors?: components['schemas']['FieldError'][];
    };
    /**
     * @example {
     *       "cases": [
     *         {
     *           "caseId": "TC-01",
     *           "input": {
     *             "ty_le_hsba_dat": 104
     *           },
     *           "browserValid": false,
     *           "browserErrors": [
     *             {
     *               "field": "ty_le_hsba_dat",
     *               "message": "Giá trị phải từ 0 đến 100"
     *             }
     *           ]
     *         }
     *       ],
     *       "renderMs50Fields": 31
     *     }
     */
    UiFormTestRunRequest: {
      cases: components['schemas']['UiFormTestCaseInput'][];
      /** @description Thời gian dựng 50 trường đo ở trình duyệt (BR-18-08) */
      renderMs50Fields?: number;
    };
    /**
     * @example {
     *       "total": 24,
     *       "passed": 24,
     *       "parity": true,
     *       "renderMs50Fields": 31,
     *       "cases": [
     *         {
     *           "caseId": "TC-01",
     *           "serverValid": false,
     *           "browserValid": false,
     *           "match": true
     *         }
     *       ],
     *       "ranAt": "2026-10-19T09:35:00+07:00"
     *     }
     */
    UiFormTestRunResult: {
      total: number;
      passed: number;
      /** @description Mọi ca cho cùng kết quả trên cel-js và cel-java (BR-18-05) */
      parity: boolean;
      renderMs50Fields?: number | null;
      cases: {
        caseId: string;
        serverValid: boolean;
        browserValid: boolean;
        match: boolean;
        serverErrors?: components['schemas']['FieldError'][];
      }[];
      ranAt?: components['schemas']['DateTime'];
    };
    /**
     * @description Bố cục trang chủ đã phân giải cho người dùng hiện tại.
     * @example {
     *       "uiPackVersion": "2.3.0",
     *       "registryVersion": "2026.3",
     *       "mode": "default",
     *       "reducedSteps": false,
     *       "layout": {
     *         "component": "vt.layout.grid",
     *         "props": {
     *           "columns": {
     *             "xs": 1,
     *             "md": 2,
     *             "xl": 3
     *           }
     *         },
     *         "children": [
     *           {
     *             "component": "vt.widget.myTasksToday",
     *             "props": {
     *               "limit": 7
     *             }
     *           },
     *           {
     *             "component": "vt.widget.krCheckinDue"
     *           },
     *           {
     *             "component": "vt.widget.periodMilestones"
     *           },
     *           {
     *             "component": "vt.widget.weeklySummaryAi"
     *           }
     *         ]
     *       }
     *     }
     */
    HomeLayout: {
      uiPackVersion: string;
      registryVersion: string;
      /**
       * @description Chế độ đang áp cho người dùng
       * @enum {string}
       */
      mode?: 'default' | 'elderly';
      /** @description Cờ "ít bước" (NFR-11) */
      reducedSteps?: boolean;
      layout: components['schemas']['SduiNode'];
    };
    /**
     * @description Vai trò hệ thống gán theo đơn vị (`config.role_binding.role_code`, CHECK của TL-03).
     * @enum {string}
     */
    RoleCode:
      | 'tenant_admin'
      | 'policy_author'
      | 'leader'
      | 'manager'
      | 'member'
      | 'appraiser'
      | 'decider'
      | 'second_approver'
      | 'inspector';
    /**
     * @description Phạm vi ABAC (`config.role_binding.scope`).
     * @enum {string}
     */
    RoleBindingScope: 'DON_VI' | 'DON_VI_VA_CAP_DUOI';
    /**
     * @description Trạng thái suy ra, không lưu thành cột (quyết định WEB-058 điểm 2): `CHO_DUYET` khi vai trò nhạy cảm chưa có `approved_by`; `TU_CHOI`, `DA_THU_HOI` theo `end_kind`; còn lại so `valid_from`, `valid_to` với thời điểm hiện tại.
     * @enum {string}
     */
    RoleBindingStatus:
      'CHO_DUYET' | 'SAP_HIEU_LUC' | 'HIEU_LUC' | 'HET_HAN' | 'DA_THU_HOI' | 'TU_CHOI';
    /**
     * @description Cách bản ghi kết thúc trước hạn (`end_kind`, cột bổ sung theo WEB-058).
     * @enum {string}
     */
    AccessEndKind: 'THU_HOI' | 'TU_CHOI';
    /**
     * @description Vai trò đã cấp (`config.role_binding`, TL-03; cột bổ sung `reason`, `granted_at`, `approved_at`, `ended_by`, `ended_at`, `end_kind`, `end_reason` theo WEB-058). Là nguồn sự thật của vai trò; mỗi thay đổi phát EVT-27 để cập nhật dữ liệu OPA và đồng bộ realm role sang Keycloak.
     * @example {
     *       "id": "0192f7a1-1111-7c3e-9a51-3f2d9c1b0a01",
     *       "person": {
     *         "id": "0192f7a1-2222-7c3e-9a51-3f2d9c1b0a02",
     *         "name": "Lê Minh Châu"
     *       },
     *       "personTitle": "Phó Trưởng ban",
     *       "roleCode": "decider",
     *       "orgUnit": {
     *         "id": "0192f7a1-3333-7c3e-9a51-3f2d9c1b0a03",
     *         "name": "Ban Tổ chức Tỉnh uỷ"
     *       },
     *       "scope": "DON_VI_VA_CAP_DUOI",
     *       "flow": "DANG",
     *       "validFrom": "2026-11-01T00:00:00+07:00",
     *       "validTo": "2027-10-31T23:59:59+07:00",
     *       "status": "CHO_DUYET",
     *       "sensitive": true,
     *       "grantedBy": {
     *         "id": "0192f7a1-4444-7c3e-9a51-3f2d9c1b0a04",
     *         "name": "Ngô Thị Lan"
     *       },
     *       "grantedAt": "2026-10-19T09:30:00+07:00",
     *       "reason": "Quyết định 152-QĐ/TU ngày 15/10/2026 giao quyết định xếp loại khối Đảng",
     *       "approvedBy": null,
     *       "approvedAt": null,
     *       "endedBy": null,
     *       "endedAt": null,
     *       "endKind": null,
     *       "endReason": null,
     *       "rowVersion": 1
     *     }
     */
    RoleBinding: {
      id: components['schemas']['Uuid'];
      person: components['schemas']['Ref'];
      /** @description Chức danh của phân công chính */
      personTitle?: string | null;
      roleCode: components['schemas']['RoleCode'];
      orgUnit: components['schemas']['Ref'];
      scope: components['schemas']['RoleBindingScope'];
      /** @description Giới hạn theo luồng; `null` là cả hai luồng. */
      flow?: components['schemas']['Flow'] | null;
      validFrom: components['schemas']['DateTime'];
      validTo: components['schemas']['DateTime'];
      status: components['schemas']['RoleBindingStatus'];
      /** @description Vai trò cần người duyệt thứ hai (`decider`, `second_approver`, `policy_author`) */
      sensitive: boolean;
      grantedBy: components['schemas']['Ref'];
      grantedAt: components['schemas']['DateTime'];
      /** @description Căn cứ cấp (quyết định, văn bản) */
      reason: string;
      approvedBy?: components['schemas']['Ref'] | null;
      /** Format: date-time */
      approvedAt?: string | null;
      endedBy?: components['schemas']['Ref'] | null;
      /** Format: date-time */
      endedAt?: string | null;
      endKind?: components['schemas']['AccessEndKind'] | null;
      endReason?: string | null;
      rowVersion: components['schemas']['RowVersion'];
    };
    RoleBindingCreate: {
      personId: components['schemas']['Uuid'];
      roleCode: components['schemas']['RoleCode'];
      orgUnitId: components['schemas']['Uuid'];
      scope: components['schemas']['RoleBindingScope'];
      flow?: components['schemas']['Flow'] | null;
      validFrom: components['schemas']['DateTime'];
      validTo: components['schemas']['DateTime'];
      reason: string;
    };
    RoleBindingApprove: {
      /** @description Ghi chú của người duyệt (tuỳ chọn) */
      note?: string;
    };
    /** @description Lý do bắt buộc khi từ chối hoặc thu hồi vai trò, uỷ quyền. */
    AccessEndInput: {
      reason: string;
    };
    /** @description Số đếm cho thẻ lọc nhanh của SCR-0105 theo bộ lọc đơn vị hiện tại. */
    RoleBindingCounts: {
      active: number;
      pending: number;
      awaitingMe: number;
      /** @description Còn hiệu lực và hết hạn trong 30 ngày tới */
      expiringSoon: number;
    };
    RoleBindingPage: {
      items: components['schemas']['RoleBinding'][];
      nextCursor: components['schemas']['NextCursor'];
      counts: components['schemas']['RoleBindingCounts'];
    };
    /**
     * @description Loại sự kiện trong lịch sử quyền.
     * @enum {string}
     */
    AccessEventKind:
      | 'CAP_VAI_TRO'
      | 'DUYET_VAI_TRO'
      | 'TU_CHOI_VAI_TRO'
      | 'THU_HOI_VAI_TRO'
      | 'HET_HAN_VAI_TRO'
      | 'TAO_UY_QUYEN'
      | 'THU_HOI_UY_QUYEN'
      | 'HET_HAN_UY_QUYEN';
    /** @description Một sự kiện cấp, duyệt, thu hồi vai trò hoặc uỷ quyền, đọc từ nhật ký kiểm toán. */
    AccessEvent: {
      id: components['schemas']['Uuid'];
      at: components['schemas']['DateTime'];
      kind: components['schemas']['AccessEventKind'];
      /** @description Người thực hiện; `null` khi hệ thống tự kết thúc do hết hạn. */
      actor?: components['schemas']['Ref'] | null;
      person: components['schemas']['Ref'];
      /** @example Cấp vai trò decider tại Ban Tổ chức Tỉnh uỷ, chờ duyệt thứ hai */
      summary: string;
      refId: components['schemas']['Uuid'];
    };
    AccessEventPage: {
      items: components['schemas']['AccessEvent'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /**
     * @description Loại uỷ quyền (`config.delegation.kind`, bảng bổ sung theo WEB-058).
     * @enum {string}
     */
    DelegationKind: 'DUYET_THAY' | 'NGHIEM_THU_THAY' | 'CHECK_IN_THAY';
    /**
     * @description Trạng thái suy ra từ `valid_from`, `valid_to`, `end_kind`.
     * @enum {string}
     */
    DelegationStatus: 'SAP_HIEU_LUC' | 'HIEU_LUC' | 'HET_HAN' | 'DA_THU_HOI';
    /**
     * @description Uỷ quyền khi vắng mặt (`config.delegation`, bảng bổ sung TL-03 theo WEB-058): người uỷ quyền, người nhận, loại, đơn vị, đối tượng (KR khi check-in thay), thời hạn, lý do. OPA và workflow-service (SVC-06) cùng đọc bảng này; phiếu do người nhận xử lý ghi rõ "duyệt thay", "nghiệm thu thay", "check-in thay".
     * @example {
     *       "id": "0192f7a1-5555-7c3e-9a51-3f2d9c1b0a05",
     *       "kind": "DUYET_THAY",
     *       "delegator": {
     *         "id": "0192f7a1-6666-7c3e-9a51-3f2d9c1b0a06",
     *         "name": "Hoàng Văn Dũng"
     *       },
     *       "delegate": {
     *         "id": "0192f7a1-2222-7c3e-9a51-3f2d9c1b0a02",
     *         "name": "Lê Minh Châu"
     *       },
     *       "orgUnit": {
     *         "id": "0192f7a1-3333-7c3e-9a51-3f2d9c1b0a03",
     *         "name": "Ban Tổ chức Tỉnh uỷ"
     *       },
     *       "subject": null,
     *       "validFrom": "2026-10-20T00:00:00+07:00",
     *       "validTo": "2026-11-05T23:59:59+07:00",
     *       "reason": "Trưởng ban đi học lớp bồi dưỡng từ 20/10 đến 05/11/2026",
     *       "status": "SAP_HIEU_LUC",
     *       "createdBy": {
     *         "id": "0192f7a1-6666-7c3e-9a51-3f2d9c1b0a06",
     *         "name": "Hoàng Văn Dũng"
     *       },
     *       "createdAt": "2026-10-19T10:00:00+07:00",
     *       "endedBy": null,
     *       "endedAt": null,
     *       "endReason": null,
     *       "rowVersion": 1
     *     }
     */
    Delegation: {
      id: components['schemas']['Uuid'];
      kind: components['schemas']['DelegationKind'];
      delegator: components['schemas']['Ref'];
      delegate: components['schemas']['Ref'];
      orgUnit: components['schemas']['Ref'];
      /** @description Đối tượng được uỷ quyền (KR khi `CHECK_IN_THAY`); `null` là mọi phiếu của người uỷ quyền tại đơn vị. */
      subject?: components['schemas']['Ref'] | null;
      validFrom: components['schemas']['DateTime'];
      validTo: components['schemas']['DateTime'];
      reason: string;
      status: components['schemas']['DelegationStatus'];
      createdBy: components['schemas']['Ref'];
      createdAt: components['schemas']['DateTime'];
      endedBy?: components['schemas']['Ref'] | null;
      /** Format: date-time */
      endedAt?: string | null;
      endReason?: string | null;
      rowVersion: components['schemas']['RowVersion'];
    };
    DelegationCreate: {
      kind: components['schemas']['DelegationKind'];
      delegatorId: components['schemas']['Uuid'];
      delegateId: components['schemas']['Uuid'];
      orgUnitId: components['schemas']['Uuid'];
      /**
       * Format: uuid
       * @description KR khi `CHECK_IN_THAY`
       */
      subjectId?: string | null;
      validFrom: components['schemas']['DateTime'];
      validTo: components['schemas']['DateTime'];
      reason: string;
    };
    DelegationPage: {
      items: components['schemas']['Delegation'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /**
     * @example {
     *       "flags": {
     *         "elderly_mode_auto_prompt": true,
     *         "ai_summary_3_lines": true
     *       },
     *       "evaluatedAt": "2026-04-01T08:00:00Z"
     *     }
     */
    EffectiveFeatureFlagMap: {
      flags: {
        [key: string]: boolean;
      };
      evaluatedAt: components['schemas']['DateTime'];
    };
    /**
     * @example {
     *       "key": "dossier.retention_years",
     *       "orgUnitId": null,
     *       "value": {
     *         "years": 10
     *       },
     *       "version": 2,
     *       "updatedBy": "0195f3a0-0000-7000-8000-000000000101",
     *       "updatedAt": "2026-04-01T08:00:00Z"
     *     }
     */
    TenantSetting: {
      key: string;
      /** Format: uuid */
      orgUnitId?: string | null;
      value: {
        [key: string]: unknown;
      };
      version: number;
      /** Format: uuid */
      updatedBy?: string | null;
      updatedAt: components['schemas']['DateTime'];
    };
    TenantSettingList: {
      items: components['schemas']['TenantSetting'][];
    };
    TenantSettingUpdate: {
      /** Format: uuid */
      orgUnitId?: string | null;
      value: {
        [key: string]: unknown;
      };
      reason?: string;
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
     * Format: date-time
     * @description Thời điểm ISO 8601 có múi giờ.
     * @example 2026-10-19T08:30:00+07:00
     */
    DateTime: string;
    /**
     * Format: date
     * @description Ngày theo lịch (ISO 8601), không có giờ.
     * @example 2026-12-15
     */
    Date: string;
    /**
     * @description Luồng đánh giá (baseline mục 9.1).
     * @enum {string}
     */
    Flow: 'DANG' | 'NHA_NUOC';
    /** @description Tham chiếu rút gọn tới một đối tượng kèm tên hiển thị. */
    Ref: {
      id: components['schemas']['Uuid'];
      name: string;
    };
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
     * @description Trạng thái kỳ `config.period.status` (baseline mục 9.1).
     * @enum {string}
     */
    PeriodStatus: 'MO' | 'DANG_KHOA' | 'DA_KHOA' | 'MO_LAI_HAU_KIEM';
    /**
     * @description Phân loại dữ liệu theo ADR-06.
     * @enum {string}
     */
    Classification: 'C0' | 'C1' | 'C2' | 'C3';
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
    /** @description Xung đột. Hai nghĩa phân biệt bằng `code`: xung đột ghi đồng thời (`<MODULE>-409-VERSION-CONFLICT`, `type` kết thúc bằng `concurrent-modification`) hoặc vi phạm trạng thái nghiệp vụ (ví dụ `TASK-409-PERIOD-LOCKED`). Với xung đột ghi, giao diện giữ dữ liệu đang nhập và hiện khác biệt. */
    Conflict: {
      headers: {
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
    /** @description Không tìm thấy, hoặc đối tượng nằm ngoài phạm vi người gọi (không lộ sự tồn tại). */
    NotFound: {
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
    /** @description Năm dương lịch. */
    Year: number;
    /** @description Năm dương lịch; bỏ trống thì trả mọi năm. */
    YearOptional: number;
    /** @description Mã lệnh khoá kỳ (`config.period_lock_order.id`). */
    LockOrderIdPath: components['schemas']['Uuid'];
    /** @description Mã cờ tính năng (`config.feature_flag.flag_key`). */
    FlagKeyPath: string;
    /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
    UiPackVersionPath: string;
    /** @description Khoá biểu mẫu (`config.form_schema.form_key`). */
    FormKeyPath: string;
    /** @description Phiên bản biểu mẫu (`config.form_schema.version`). */
    FormVersionPath: string;
    /** @description ETag đã lưu ở client; khớp thì máy chủ trả 304. */
    IfNoneMatch: string;
    /** @description Khoá cấu hình (`config.tenant_setting.key`). */
    SettingKeyPath: string;
    /** @description Từ khoá tìm kiếm, không phân biệt dấu. */
    Q: string;
    /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
    Limit: number;
    /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
    Cursor: string;
    /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
    IdempotencyKey: string;
    IdPath: components['schemas']['Uuid'];
    /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
    IfMatch: string;
    /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
    UnitId: components['schemas']['Uuid'];
  };
  requestBodies: never;
  headers: {
    /** @description Phiên bản hiện tại của đối tượng (`row_version`), gửi lại trong `If-Match`. */
    ETag: string;
    /** @description Đường dẫn theo dõi tác vụ dài `/api/v1/jobs/{id}` hoặc đối tượng vừa tạo. */
    Location: string;
    /** @description Số giây nên chờ trước lần gọi kế tiếp. */
    RetryAfter: number;
  };
  pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
  listConfigTenants: {
    parameters: {
      query?: {
        /** @description Mã cell, ví dụ `CELL-G2`. */
        cell?: string;
        /** @description Mô hình thuê bao. */
        model?: components['schemas']['TenancyModel'];
        /** @description Trạng thái tenant. */
        status?: components['schemas']['TenantStatus'];
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
      /** @description Một trang tenant và chỉ số tổng hợp. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ConfigTenantPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createConfigTenant: {
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
        'application/json': components['schemas']['ConfigTenantCreate'];
      };
    };
    responses: {
      /** @description Đã tạo yêu cầu khởi tạo. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ConfigTenant'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  exportConfigTenants: {
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
        'application/json': components['schemas']['ConfigTenantExportRequest'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  getConfigTenant: {
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
      /** @description Chi tiết tenant. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ConfigTenant'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  cancelConfigTenant: {
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
    requestBody?: never;
    responses: {
      /** @description Đã huỷ yêu cầu khởi tạo. */
      204: {
        headers: {
          [name: string]: unknown;
        };
        content?: never;
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  updateConfigTenant: {
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
        'application/merge-patch+json': components['schemas']['ConfigTenantPatch'];
      };
    };
    responses: {
      /** @description Đã lưu nháp. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ConfigTenant'];
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
  provisionConfigTenant: {
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
    requestBody?: never;
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  testConfigTenantIdp: {
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
        'application/json': components['schemas']['TenantIdpTestRequest'];
      };
    };
    responses: {
      /** @description Kết quả kiểm tra kết nối. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TenantIdpTestResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  activateConfigTenant: {
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
        'application/json': components['schemas']['ConfigTenantActivateRequest'];
      };
    };
    responses: {
      /** @description Tenant đã vào vận hành. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ConfigTenant'];
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
  reportSuspiciousAccess: {
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
        'application/json': components['schemas']['SuspiciousAccessReport'];
      };
    };
    responses: {
      /** @description Đã ghi nhận báo cáo và thu hồi phiên. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SuspiciousAccessResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      429: components['responses']['TooManyRequests'];
      default: components['responses']['Problem'];
    };
  };
  getWorkingCalendar: {
    parameters: {
      query: {
        /** @description Năm dương lịch. */
        year: components['parameters']['Year'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Lịch ngày làm việc của năm. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkingCalendar'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  listHolidayCalendarDays: {
    parameters: {
      query: {
        /** @description Năm dương lịch. */
        year: components['parameters']['Year'];
        /** @description Lọc theo lịch quốc gia hoặc lịch riêng. */
        scope?: components['schemas']['HolidayScope'];
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
      /** @description Một trang ngày đặc biệt. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['HolidayCalendarDayPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createHolidayCalendarDay: {
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
        'application/json': components['schemas']['HolidayCalendarDayInput'];
      };
    };
    responses: {
      /** @description Đã thêm ngày. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['HolidayCalendarDay'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  updateHolidayCalendarDay: {
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
        'application/json': components['schemas']['HolidayCalendarDayInput'];
      };
    };
    responses: {
      /** @description Đã sửa. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['HolidayCalendarDay'];
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
  deleteHolidayCalendarDay: {
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
    requestBody?: never;
    responses: {
      /** @description Đã xoá. */
      204: {
        headers: {
          [name: string]: unknown;
        };
        content?: never;
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  listFeatureFlags: {
    parameters: {
      query?: {
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
      /** @description Một trang cờ tính năng. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['FeatureFlagPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  setFeatureFlag: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Mã cờ tính năng (`config.feature_flag.flag_key`). */
        key: components['parameters']['FlagKeyPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['FeatureFlagInput'];
      };
    };
    responses: {
      /** @description Đã cập nhật cờ. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['FeatureFlag'];
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
  getAnomalyThresholds: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Bộ ngưỡng hiện hành. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AnomalyThresholdSet'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  updateAnomalyThresholds: {
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
        'application/json': components['schemas']['AnomalyThresholdUpdate'];
      };
    };
    responses: {
      /** @description Đã lưu phiên bản ngưỡng mới. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AnomalyThresholdSet'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  listPeriods: {
    parameters: {
      query?: {
        /** @description Năm dương lịch; bỏ trống thì trả mọi năm. */
        year?: components['parameters']['YearOptional'];
        kind?: components['schemas']['PeriodKind'];
        flow?: components['schemas']['PeriodFlow'];
        status?: components['schemas']['PeriodStatus'];
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
      /** @description Một trang kỳ đánh giá. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PeriodPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createPeriod: {
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
        'application/json': components['schemas']['PeriodCreate'];
      };
    };
    responses: {
      /** @description Đã tạo kỳ. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Period'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getPeriod: {
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
      /** @description Chi tiết kỳ. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Period'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  createPeriodLockOrder: {
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
        'application/json': components['schemas']['PeriodLockOrderCreate'];
      };
    };
    responses: {
      /** @description Đã lập lệnh, chờ phê duyệt. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PeriodLockOrder'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  approvePeriodLockOrder: {
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
        /** @description Mã lệnh khoá kỳ (`config.period_lock_order.id`). */
        oid: components['parameters']['LockOrderIdPath'];
      };
      cookie?: never;
    };
    requestBody?: {
      content: {
        'application/json': components['schemas']['PeriodLockOrderApprove'];
      };
    };
    responses: {
      /** @description Lệnh đã được phê duyệt. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PeriodLockOrder'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  getPeriodLockProgress: {
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
      /** @description Tiến trình hiện tại. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PeriodLockProgress'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  startPeriodLock: {
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
        'application/json': components['schemas']['PeriodLockStart'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  listPeriodLockBatches: {
    parameters: {
      query?: {
        status?: components['schemas']['ScoreBatchStatus'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Một trang lô tính điểm. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PeriodLockBatchPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listPeriodLockFailures: {
    parameters: {
      query?: {
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Một trang hồ sơ lỗi. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PeriodLockFailurePage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listPeriodLockEvents: {
    parameters: {
      query?: {
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Một trang sự kiện. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PeriodLockEventPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  getPeriodLockDiagram: {
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
      /** @description Sơ đồ và trạng thái các hoạt động. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PeriodLockDiagram'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  pausePeriodLock: {
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
        'application/json': components['schemas']['PeriodLockControl'];
      };
    };
    responses: {
      /** @description Đã đặt cờ tạm dừng. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PeriodLockProgress'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  resumePeriodLock: {
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
    requestBody?: never;
    responses: {
      /** @description Saga tiếp tục. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PeriodLockProgress'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  retryFailedPeriodLock: {
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
    requestBody?: {
      content: {
        'application/json': components['schemas']['PeriodLockRetryRequest'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      default: components['responses']['Problem'];
    };
  };
  requestPeriodLockCancel: {
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
        'application/json': components['schemas']['PeriodLockCancelRequestInput'];
      };
    };
    responses: {
      /** @description Đã tạo đề nghị huỷ, chờ người duyệt thứ hai. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PeriodLockProgress'];
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
  approvePeriodLockCancel: {
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
        'application/json': components['schemas']['PeriodLockControl'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  getCurrentUiPack: {
    parameters: {
      query?: never;
      header?: {
        /** @description ETag đã lưu ở client; khớp thì máy chủ trả 304. */
        'If-None-Match'?: components['parameters']['IfNoneMatch'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description UI Pack hiện hành. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiPackRuntime'];
        };
      };
      /** @description Phiên bản không đổi so với `If-None-Match`. */
      304: {
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
  listUiPacks: {
    parameters: {
      query?: {
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
      /** @description Một trang UI Pack. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiPackPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  listUiPackVersions: {
    parameters: {
      query?: {
        status?: components['schemas']['UiPackVersionStatus'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Một trang phiên bản. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiPackVersionPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  createUiPackVersion: {
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
        'application/json': components['schemas']['UiPackVersionCreate'];
      };
    };
    responses: {
      /** @description Đã tạo phiên bản nháp. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiPackVersion'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getUiPackVersion: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
        v: components['parameters']['UiPackVersionPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Phiên bản UI Pack. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiPackVersion'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  updateUiPackVersion: {
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
        /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
        v: components['parameters']['UiPackVersionPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['UiPackVersionUpdate'];
      };
    };
    responses: {
      /** @description Đã lưu nháp. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiPackVersion'];
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
  previewUiPackVersion: {
    parameters: {
      query?: {
        /** @description Chế độ hiển thị cần xem trước. */
        mode?: 'default' | 'elderly';
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
        v: components['parameters']['UiPackVersionPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Kết quả biên dịch thử. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiPackPreview'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  submitUiPackVersion: {
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
        /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
        v: components['parameters']['UiPackVersionPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  publishUiPackVersion: {
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
        /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
        v: components['parameters']['UiPackVersionPath'];
      };
      cookie?: never;
    };
    requestBody?: {
      content: {
        'application/json': components['schemas']['UiPackReviewInput'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  rejectUiPackVersion: {
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
        /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
        v: components['parameters']['UiPackVersionPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['UiPackRejectInput'];
      };
    };
    responses: {
      /** @description Đã trả về nháp. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiPackVersion'];
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
  activateUiPackVersion: {
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
        /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
        v: components['parameters']['UiPackVersionPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Phiên bản đã được kích hoạt lại. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiPackVersion'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  attachUiPackForm: {
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
        /** @description Phiên bản ngữ nghĩa của UI Pack (`config.ui_pack_version.version`). */
        v: components['parameters']['UiPackVersionPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['UiPackFormAttach'];
      };
    };
    responses: {
      /** @description Đã gắn biểu mẫu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiPackVersion'];
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
  listUiComponents: {
    parameters: {
      query?: {
        /** @description Phiên bản registry, ví dụ `2026.3`. */
        registry?: string;
        status?: components['schemas']['UiComponentStatus'];
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
      /** @description Một trang component. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiComponentPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  listUiForms: {
    parameters: {
      query?: {
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
      /** @description Một trang biểu mẫu. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiFormPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createUiFormVersion: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Khoá biểu mẫu (`config.form_schema.form_key`). */
        key: components['parameters']['FormKeyPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['UiFormVersionCreate'];
      };
    };
    responses: {
      /** @description Đã tạo phiên bản nháp. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiFormVersion'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getCurrentUiForm: {
    parameters: {
      query?: never;
      header?: {
        /** @description ETag đã lưu ở client; khớp thì máy chủ trả 304. */
        'If-None-Match'?: components['parameters']['IfNoneMatch'];
      };
      path: {
        /** @description Khoá biểu mẫu (`config.form_schema.form_key`). */
        key: components['parameters']['FormKeyPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Biểu mẫu hiệu lực. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiFormVersion'];
        };
      };
      /** @description Phiên bản không đổi so với `If-None-Match`. */
      304: {
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
  getUiFormVersion: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Khoá biểu mẫu (`config.form_schema.form_key`). */
        key: components['parameters']['FormKeyPath'];
        /** @description Phiên bản biểu mẫu (`config.form_schema.version`). */
        v: components['parameters']['FormVersionPath'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Phiên bản biểu mẫu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiFormVersion'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  updateUiFormVersion: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Khoá biểu mẫu (`config.form_schema.form_key`). */
        key: components['parameters']['FormKeyPath'];
        /** @description Phiên bản biểu mẫu (`config.form_schema.version`). */
        v: components['parameters']['FormVersionPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['UiFormVersionUpdate'];
      };
    };
    responses: {
      /** @description Đã lưu nháp. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiFormVersion'];
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
  runUiFormTests: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Khoá biểu mẫu (`config.form_schema.form_key`). */
        key: components['parameters']['FormKeyPath'];
        /** @description Phiên bản biểu mẫu (`config.form_schema.version`). */
        v: components['parameters']['FormVersionPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['UiFormTestRunRequest'];
      };
    };
    responses: {
      /** @description Kết quả bộ test. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['UiFormTestRunResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getHomeLayout: {
    parameters: {
      query?: never;
      header?: {
        /** @description ETag đã lưu ở client; khớp thì máy chủ trả 304. */
        'If-None-Match'?: components['parameters']['IfNoneMatch'];
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Bố cục trang chủ. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['HomeLayout'];
        };
      };
      /** @description Bố cục không đổi so với `If-None-Match`. */
      304: {
        headers: {
          [name: string]: unknown;
        };
        content?: never;
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  getEffectiveFeatureFlags: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Bản đồ `flagKey -> boolean`. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['EffectiveFeatureFlagMap'];
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  listTenantSettings: {
    parameters: {
      query?: {
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Danh sách cấu hình. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TenantSettingList'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  setTenantSetting: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Khoá cấu hình (`config.tenant_setting.key`). */
        key: components['parameters']['SettingKeyPath'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['TenantSettingUpdate'];
      };
    };
    responses: {
      /** @description Cấu hình đã lưu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TenantSetting'];
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
  listRoleBindings: {
    parameters: {
      query?: {
        /** @description Đơn vị phạm vi; bỏ trống khi lọc theo `personId`. */
        orgUnitId?: components['schemas']['Uuid'];
        /** @description Gồm cả bản ghi cấp tại đơn vị cấp dưới. */
        includeSubunits?: boolean;
        /** @description Người được cấp. */
        personId?: components['schemas']['Uuid'];
        role?: components['schemas']['RoleCode'];
        /** @description Trạng thái suy ra; bỏ trống là mọi trạng thái trừ `TU_CHOI`. */
        status?: components['schemas']['RoleBindingStatus'];
        /** @description Chỉ đề nghị đang chờ người gọi duyệt thứ hai (người gọi khác người cấp và người được cấp). */
        awaitingMe?: boolean;
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
      /** @description Một trang vai trò đã cấp. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['RoleBindingPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  grantRoleBinding: {
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
        'application/json': components['schemas']['RoleBindingCreate'];
      };
    };
    responses: {
      /** @description Đã cấp (hoặc đã gửi duyệt thứ hai với vai trò nhạy cảm). */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['RoleBinding'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getRoleBinding: {
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
      /** @description Chi tiết vai trò. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['RoleBinding'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  approveRoleBinding: {
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
        'application/json': components['schemas']['RoleBindingApprove'];
      };
    };
    responses: {
      /** @description Đã duyệt; vai trò có hiệu lực theo `validFrom`. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['RoleBinding'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  rejectRoleBinding: {
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
        'application/json': components['schemas']['AccessEndInput'];
      };
    };
    responses: {
      /** @description Đã từ chối. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['RoleBinding'];
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
  revokeRoleBinding: {
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
        'application/json': components['schemas']['AccessEndInput'];
      };
    };
    responses: {
      /** @description Đã thu hồi. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['RoleBinding'];
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
  listAccessHistory: {
    parameters: {
      query?: {
        personId?: components['schemas']['Uuid'];
        orgUnitId?: components['schemas']['Uuid'];
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
      /** @description Một trang sự kiện quyền. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AccessEventPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  exportAccessHistory: {
    parameters: {
      query: {
        personId: components['schemas']['Uuid'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Tệp CSV. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'text/csv': string;
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  listDelegations: {
    parameters: {
      query?: {
        orgUnitId?: components['schemas']['Uuid'];
        personId?: components['schemas']['Uuid'];
        kind?: components['schemas']['DelegationKind'];
        status?: components['schemas']['DelegationStatus'];
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
      /** @description Một trang uỷ quyền. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DelegationPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createDelegation: {
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
        'application/json': components['schemas']['DelegationCreate'];
      };
    };
    responses: {
      /** @description Đã khai báo uỷ quyền. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Delegation'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  revokeDelegation: {
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
        'application/json': components['schemas']['AccessEndInput'];
      };
    };
    responses: {
      /** @description Đã thu hồi uỷ quyền. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Delegation'];
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
}
