/* eslint-disable */
/**
 * Sinh tự động bởi openapi-typescript từ contracts/openapi/modules/tasks.yaml (WEB-064).
 * Không sửa tay; sửa hợp đồng rồi chạy `npm run api:gen` trong frontend/.
 */
export interface paths {
  '/tasks': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Việc của đơn vị
     * @description Danh sách nhiệm vụ theo đơn vị và kỳ cho người giao việc và lãnh đạo. Danh sách "Việc của tôi" dùng `/tasks/mine` (điểm chốt HD-04). Lọc theo KR dùng tham số `kr` (SCR-0201 "Xem tất cả nhiệm vụ liên kết").
     */
    get: operations['listUnitTasks'];
    put?: never;
    /**
     * Lưu nháp nhiệm vụ lần đầu
     * @description Tạo nhiệm vụ ở trạng thái `NHAP` từ bản đang soạn của SCR-0501. Chưa phát sự kiện.
     */
    post: operations['createTaskDraft'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/mine': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Việc của tôi
     * @description Nhiệm vụ người dùng chủ trì hoặc phối hợp trong kỳ, kèm số đếm từng bộ lọc nhanh và danh sách KR để lọc (SCR-0503, SCR-0504, khối "Việc hôm nay" của SCR-0002). Điểm chốt HD-04: TL ghi `/tasks?assignee=me`, hợp đồng tách thành đường dẫn riêng vì lược đồ trả khác danh sách đơn vị.
     */
    get: operations['listMyTasks'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/drafts/new': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Khung soạn nhiệm vụ mới
     * @description Máy chủ điền kỳ đang mở, đơn vị và người giao theo ngữ cảnh phân công, giá trị mặc định (SCR-0501). Không tạo bản ghi. (suy luận: TL-02 không ghi API riêng cho bước mở màn hình.)
     */
    get: operations['getNewTaskDraft'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/assign-check': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Kiểm tra trước khi giao
     * @description Kiểm tra tức thì của MOD-02 cho bản đang soạn (QT-05 bước 5): BR-05-01 đến BR-05-04, BR-05-06, BR-05-09, BR-05-11; trả nhiệm vụ gần giống, số ngày làm việc tới hạn và khối lượng của người chủ trì. Không ghi dữ liệu nên không cần `Idempotency-Key`. (suy luận: TL-02 mô tả kiểm tra nhưng không đặt tên API.)
     */
    post: operations['checkTaskAssignment'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/bulk-assign': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Giao đồng loạt cho nhiều đơn vị
     * @description Tạo một nhiệm vụ cho mỗi đơn vị nhận và giao ngay; chạy nền, trả 202.
     */
    post: operations['bulkAssignTasks'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/assign-batch': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Giao các nhiệm vụ đã xác nhận từ văn bản
     * @description Giao hàng loạt các nhiệm vụ `NHAP` đã xác nhận ở SCR-0502. Mỗi nhiệm vụ xử lý độc lập; nhiệm vụ lỗi không làm hỏng nhiệm vụ khác.
     */
    post: operations['assignTaskBatch'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/progress-batch': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Cập nhật tiến độ hàng loạt
     * @description Mỗi dòng mang `rowVersion` riêng thay cho `If-Match`; máy chủ xử lý độc lập từng dòng và trả mã HTTP tương đương cho từng dòng (409 khi lệch phiên bản).
     */
    post: operations['updateTaskProgressBatch'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/registrations': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Cán bộ đăng ký nhiệm vụ từ danh mục
     * @description Tạo nhiệm vụ `NHAP` có nguồn `DANG_KY`, gửi người quản lý trực tiếp xem xét (QT-05 bước 1c).
     */
    post: operations['registerTask'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/export': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Xuất Excel danh sách đang lọc
     * @description Tạo tác vụ xuất (tối đa 100.000 dòng); trả 202, tệp lấy ở `resultLocation` của job.
     */
    post: operations['exportTasks'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Chi tiết nhiệm vụ
     * @description Chi tiết nhiệm vụ cho người được giao, người giao và cấp trên (SCR-0601).
     */
    get: operations['getTask'];
    /**
     * Lưu nháp nhiệm vụ
     * @description Chỉ sửa được khi nhiệm vụ còn `NHAP`.
     */
    put: operations['saveTaskDraft'];
    post?: never;
    /**
     * Huỷ bản nháp
     * @description Bỏ nhiệm vụ còn `NHAP`. Nhiệm vụ đã giao phải huỷ qua đề nghị (SCR-0602).
     */
    delete: operations['deleteTaskDraft'];
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/draft': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Nhiệm vụ ở dạng soạn để giao
     * @description Nhiệm vụ `NHAP` (sinh từ kế hoạch, đăng ký hoặc đã lưu nháp) ở dạng soạn của SCR-0501. Thay cho `GET /tasks/{id}?view=assign` mà mã frontend đang dùng, để mỗi đường dẫn có một lược đồ trả.
     */
    get: operations['getTaskDraft'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/assign': {
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
     * Giao việc
     * @description Chuyển `NHAP` → `DA_GIAO`, ghi nhật ký trạng thái. Chỉ thực hiện khi danh sách kiểm tra không có lỗi chặn; có nhiệm vụ gần giống thì phải ghi lý do vẫn tạo mới (BR-05-06).
     */
    post: operations['assignTask'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/similar': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Nhiệm vụ gần giống
     * @description Nhiệm vụ gần giống cùng đơn vị và kỳ (BR-05-06), sắp theo độ tương đồng giảm dần.
     */
    get: operations['listSimilarTasks'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/assignees': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /** Người chủ trì và phối hợp của nhiệm vụ */
    get: operations['listTaskAssignees'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/accept': {
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
     * Nhận việc
     * @description Người chủ trì nhận nhiệm vụ, `DA_GIAO` → `DANG_THUC_HIEN` (QT-05 bước 8).
     */
    post: operations['acceptTask'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/transitions': {
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
     * Chuyển trạng thái nhiệm vụ theo máy trạng thái
     * @description Chuyển trạng thái chung cho những bước không có thao tác riêng (ví dụ tiếp tục sau `TAM_DUNG`). Giao, nhận việc, tạm dừng và huỷ có duyệt đi qua thao tác riêng (`assign`, `accept`, `change-requests`). Máy chủ kiểm bảng chuyển của baseline mục 9.1; chuyển không hợp lệ trả 409 mã `TASK-409-INVALID-TRANSITION`.
     */
    post: operations['transitionTask'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/reminders': {
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
     * Nhắc người chủ trì
     * @description Gửi nhắc tới người chủ trì việc chậm trong phạm vi (SCR-1601).
     */
    post: operations['remindTask'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/reassign': {
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
     * Giao lại nhiệm vụ
     * @description Đổi người chủ trì, bắt buộc ghi lý do; phát EVT-04 cho người nhận mới.
     */
    post: operations['reassignTask'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/progress': {
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
     * Cập nhật tiến độ
     * @description Thêm một dòng `task.task_progress` (chỉ ghi thêm). Người phối hợp chỉ ghi nhật ký, không đổi phần trăm (BR-06-03). Lần cập nhật đầu chuyển `DA_GIAO` → `DANG_THUC_HIEN`. Điểm chốt HD-03: TL-01 ghi `PATCH`, hợp đồng dùng `POST` như TL-02. Ứng dụng di động gửi lại bản nháp ngoại tuyến với cùng `Idempotency-Key`.
     */
    post: operations['updateTaskProgress'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/blockers': {
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
     * Báo điểm nghẽn
     * @description Ghi điểm nghẽn mới (`task.task_blocker`, trạng thái `MO`), báo người giao (EVT-21).
     */
    post: operations['reportTaskBlocker'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/blockers/{blockerId}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        blockerId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    /**
     * Xử lý điểm nghẽn
     * @description Người giao chỉ định người xử lý, đặt hạn, chuyển trạng thái hoặc đóng điểm nghẽn.
     */
    patch: operations['handleTaskBlocker'];
    trace?: never;
  };
  '/tasks/{id}/change-requests': {
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
     * Gửi đề nghị gia hạn, điều chỉnh, tạm dừng, huỷ
     * @description Tạo đề nghị `CHO_DUYET` và báo người duyệt; vượt ngưỡng BR-06-06 thì tạo tiến trình `duyet-de-nghi` chuyển cấp trên. Một đề nghị cùng loại đang chờ thì trả 409. Thay cho `/tasks/{id}/extensions` của TL-01 Phụ lục C.
     */
    post: operations['submitTaskChangeRequest'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/change-requests/preview': {
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
     * Xem trước hệ quả của đề nghị
     * @description Tính hệ số tiến độ C cho hai trường hợp khách quan và chủ quan (MSG-06-06), kiểm vượt ngưỡng, gửi muộn, huỷ bị chặn. Không ghi dữ liệu.
     */
    post: operations['previewTaskChangeRequest'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/change-requests/{requestId}/withdraw': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        requestId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Rút lại đề nghị
     * @description Đề nghị `CHO_DUYET` của chính người gửi chuyển `DA_RUT`.
     */
    post: operations['withdrawTaskChangeRequest'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/change-requests/{requestId}/decision': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        requestId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Duyệt đề nghị
     * @description Người duyệt chọn nguyên nhân (khách quan hoặc chủ quan) và chấp nhận hoặc từ chối. Chấp nhận đổi hạn, số lượng, sản phẩm, hoặc chuyển `TAM_DUNG`, `HUY` theo loại đề nghị.
     */
    post: operations['decideTaskChangeRequest'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/source-docs': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Văn bản đến của đơn vị
     * @description Văn bản chỉ đạo nhận qua SVC-13 (EVT-24) chờ hoặc đã xử lý ở đơn vị người dùng.
     */
    get: operations['listSourceDocs'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/source-docs/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /** Toàn văn văn bản đến */
    get: operations['getSourceDoc'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/source-docs/{id}/dismiss': {
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
     * Bỏ qua văn bản
     * @description Đánh dấu văn bản đã xem, không tạo nhiệm vụ, bắt buộc ghi lý do.
     */
    post: operations['dismissSourceDoc'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/from-document': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Xác nhận nhiệm vụ AI trích từ văn bản
     * @description Tạo nhiệm vụ `NHAP` từ gợi ý đã xác nhận, ghi danh sách ô người giao đã sửa. Trùng đoạn giao việc đã tạo nhiệm vụ (BR-05-07) trả 409 mã `TASK-409-DUPLICATE-PARAGRAPH` (MSG-05-09).
     */
    post: operations['createTaskFromDocument'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/tasks/{id}/source-docs': {
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
     * Gộp văn bản vào nhiệm vụ cũ
     * @description Văn bản trở thành căn cứ bổ sung của nhiệm vụ cũ; báo người chủ trì (EVT-21).
     */
    post: operations['mergeSourceDocIntoTask'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/plans': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Kế hoạch theo kỳ và đơn vị */
    get: operations['listPlans'];
    put?: never;
    /**
     * Tạo kế hoạch quý
     * @description Tạo kế hoạch cho đơn vị và kỳ kèm phiên bản 1 trạng thái `NHAP`. Mỗi đơn vị một kế hoạch mỗi kỳ.
     */
    post: operations['createPlan'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/plans/items': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Dòng kế hoạch đã duyệt dùng để giao việc
     * @description Dòng của phiên bản kế hoạch `DA_DUYET` hiệu lực trong kỳ, chưa sinh nhiệm vụ đã giao (BR-05-04), dùng làm nguồn ở SCR-0501.
     */
    get: operations['listApprovedPlanItems'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/plans/approvals': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Kế hoạch chờ tôi duyệt
     * @description Phiên bản kế hoạch `CHO_DUYET` mà người dùng là người duyệt theo `flowable.wf_binding`.
     */
    get: operations['listPlanApprovals'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/plans/{id}/versions': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /** Các phiên bản của kế hoạch */
    get: operations['listPlanVersions'];
    put?: never;
    /**
     * Tạo điều chỉnh kế hoạch
     * @description Tạo phiên bản n+1 `NHAP` sao chép từ phiên bản `DA_DUYET` hiệu lực; vượt số lần điều chỉnh trong quý trả 422 (BR-04-10, MSG-04-07). Lý do chung của lần điều chỉnh ghi vào `task.plan_version.change_reason` (TL-03 bắt buộc khi `version_no > 1`); lý do từng dòng thêm, sửa ghi ở `PlanItemInput.changeReason`, dòng bỏ ghi ở `PlanVersionInput.removedItems` (TL-02 SCR-0403 trường 3).
     */
    post: operations['createPlanAdjustment'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/plans/{id}/versions/{v}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    /** Một phiên bản kế hoạch kèm các dòng */
    get: operations['getPlanVersion'];
    /**
     * Lưu nháp phiên bản kế hoạch
     * @description Ghi toàn bộ dòng của phiên bản `NHAP` hoặc `TRA_LAI`. Kiểm tra tức thì trả về trong `checks` của phản hồi; lỗi chặn chỉ ngăn gửi duyệt, không ngăn lưu nháp.
     */
    put: operations['savePlanVersion'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/plans/{id}/versions/{v}/copy-from': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Sao chép từ quý trước
     * @description Sao chép dòng thường xuyên và dòng chưa hoàn thành của kế hoạch quý trước vào phiên bản 1 còn trống, hạn dịch theo kỳ.
     */
    post: operations['copyPlanFromPreviousPeriod'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/plans/{id}/suggestions': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /**
     * Gợi ý dòng kế hoạch từ OKR
     * @description Đề xuất một dòng cho mỗi KR đã duyệt cùng kỳ chưa có nhiệm vụ.
     */
    get: operations['suggestPlanItemsFromOkr'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/plans/{id}/versions/{v}/check': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Kiểm tra tức thì các dòng chưa lưu
     * @description Chạy các quy tắc BR-04-01..BR-04-10 trên nội dung đang soạn mà không lưu (SCR-0402 vùng kiểm tra), trả chỉ các vi phạm. Thao tác chỉ đọc nhưng dùng POST vì thân là cả bảng kế hoạch. (suy luận: TL-02 mô tả kiểm tra tức thì nhưng không ghi API.)
     */
    post: operations['checkPlanVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/plans/{id}/versions/{v}/submit': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Gửi duyệt kế hoạch
     * @description Kiểm tra lại, khoá nội dung, tính mã băm SHA-256, chuyển `CHO_DUYET` và tạo tiến trình duyệt trên SVC-06. Thay cho `POST /plans/{id}/submit` của TL-01 Phụ lục C.
     */
    post: operations['submitPlanVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/plans/{id}/versions/{v}/diff': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    /** So sánh hai phiên bản kế hoạch */
    get: operations['diffPlanVersions'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/plans/{id}/versions/{v}/return': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Trả lại kế hoạch
     * @description Chuyển `TRA_LAI`, bắt buộc ý kiến người duyệt.
     */
    post: operations['returnPlanVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/plans/{id}/versions/{v}/approve': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Duyệt và ký số kế hoạch
     * @description Người duyệt khác người lập, phiên AAL2 trong 15 phút. Máy chủ tạo yêu cầu ký trên SVC-10 và trả 202 kèm job. Đường chính thức để theo dõi: thăm dò `GET /jobs/{id}` theo `Location`; khi job `HOAN_TAT`, `Job.resultLocation` trỏ tới `/esign/requests/{signRequestId}` và `PlanVersion.signRequestId` được ghi, nên giao diện có thể đọc lại phiên bản rồi theo dõi yêu cầu ký. Khi ký xong (EVT-18) phiên bản chuyển `DA_DUYET` và phát EVT-03.
     */
    post: operations['approvePlanVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/catalog': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Các phiên bản danh mục người dùng được xem
     * @description Phiên bản đang áp dụng đứng trước.
     */
    get: operations['listWorkCatalogs'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/catalog/items': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Mục danh mục đang áp dụng
     * @description Mục `AP_DUNG` của phiên bản hiệu lực, dùng cho đăng ký nhiệm vụ (SCR-0503) và giao việc (SCR-0501). `applicable=me` chỉ lấy mục theo vị trí việc làm của người dùng.
     */
    get: operations['listApplicableCatalogItems'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/catalog/{catalogId}/items': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã phiên bản danh mục (`task.work_catalog.id`). */
        catalogId: components['parameters']['CatalogId'];
      };
      cookie?: never;
    };
    /** Mục của một phiên bản danh mục */
    get: operations['listCatalogVersionItems'];
    put?: never;
    /**
     * Thêm công việc vào danh mục
     * @description Thêm mục vào phiên bản nháp; khi `catalogId` là phiên bản đang áp dụng, máy chủ ghi vào phiên bản nháp mới và trả `movedToNewVersion = true`. `code` trống thì máy chủ tự sinh. Thay cho `POST /catalog/items` của TL-01 Phụ lục C.
     */
    post: operations['addCatalogItem'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/catalog/{catalogId}/items/{itemId}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã phiên bản danh mục (`task.work_catalog.id`). */
        catalogId: components['parameters']['CatalogId'];
        itemId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    get?: never;
    /**
     * Sửa mục danh mục
     * @description Mục đã dùng ở kỳ đã khoá được lưu ở phiên bản mới, kỳ cũ giữ nguyên (BR-04-13, MSG-04-01).
     */
    put: operations['updateCatalogItem'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/catalog/{catalogId}/positions': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã phiên bản danh mục (`task.work_catalog.id`). */
        catalogId: components['parameters']['CatalogId'];
      };
      cookie?: never;
    };
    /** Vị trí việc làm có trong danh mục */
    get: operations['listCatalogPositions'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/catalog/{catalogId}/versions': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã phiên bản danh mục (`task.work_catalog.id`). */
        catalogId: components['parameters']['CatalogId'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Nhân bản phiên bản danh mục
     * @description Tạo phiên bản n+1 ở trạng thái `NHAP` từ phiên bản đang xem.
     */
    post: operations['cloneCatalogVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/catalog/{catalogId}/imports': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã phiên bản danh mục (`task.work_catalog.id`). */
        catalogId: components['parameters']['CatalogId'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Nhập danh mục từ Excel
     * @description Tải tệp Excel; máy chủ kiểm tra ở tác vụ nền và trả 202 với `Location` trỏ tới `/catalog/imports/{jobId}`.
     */
    post: operations['importCatalogExcel'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/catalog/{catalogId}/imports/latest': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã phiên bản danh mục (`task.work_catalog.id`). */
        catalogId: components['parameters']['CatalogId'];
      };
      cookie?: never;
    };
    /** Lần nhập Excel gần nhất của danh mục */
    get: operations['getLatestCatalogImport'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/catalog/imports/{jobId}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        jobId: components['parameters']['JobId'];
      };
      cookie?: never;
    };
    /** Trạng thái một lần nhập Excel */
    get: operations['getCatalogImport'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/catalog/imports/{jobId}/errors': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        jobId: components['parameters']['JobId'];
      };
      cookie?: never;
    };
    /** Tải tệp các dòng lỗi */
    get: operations['downloadCatalogImportErrors'];
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
     * @description Trạng thái `task.task.status` (baseline mục 9.1).
     * @enum {string}
     */
    TaskStatus:
      'NHAP' | 'DA_GIAO' | 'DANG_THUC_HIEN' | 'CHO_NGHIEM_THU' | 'HOAN_THANH' | 'TAM_DUNG' | 'HUY';
    /**
     * @description Vai trò trên nhiệm vụ (`task.task_assignee.role`).
     * @enum {string}
     */
    AssigneeRole: 'CHU_TRI' | 'PHOI_HOP';
    /**
     * @description Nhóm tính điểm (`task.task.task_group`); một nhiệm vụ chỉ tính ở một nhóm.
     * @enum {string}
     */
    TaskGroup: 'THUONG_XUYEN' | 'THEN_CHOT';
    /**
     * @description Nguồn nhiệm vụ (`task.task.source`, ràng buộc `ck_task__source` của TL-03). TL-02 gọi nguồn ngoài kế hoạch là "phát sinh"; giá trị giữ `DOT_XUAT` theo TL-03.
     * @enum {string}
     */
    TaskSource: 'KE_HOACH' | 'VAN_BAN' | 'DANG_KY' | 'DOT_XUAT';
    /** @enum {string} */
    TaskPriority: 'CAO' | 'TRUNG_BINH' | 'THAP';
    /**
     * @description Bộ lọc nhanh của SCR-0503 trường 1.
     * @enum {string}
     */
    MyTasksQuickFilter: 'TAT_CA' | 'CHAM' | 'SAP_HAN' | 'CHO_NGHIEM_THU' | 'HOAN_THANH';
    /** @enum {string} */
    ProgressChannel: 'WEB' | 'MOBILE' | 'TICH_HOP';
    /** @enum {string} */
    BlockerStatus: 'MO' | 'DANG_XU_LY' | 'DA_GIAI_QUYET' | 'DONG';
    /** @enum {string} */
    BlockerCauseCategory: 'NGUON_LUC' | 'PHOI_HOP' | 'THU_TUC' | 'KHACH_QUAN' | 'KHAC';
    /** @enum {string} */
    ImpactLevel: 'CAO' | 'TRUNG_BINH' | 'THAP';
    /**
     * @description Nguyên nhân khách quan hoặc chủ quan (`cause_claimed`, `cause_decided`).
     * @enum {string}
     */
    CauseKind: 'KHACH_QUAN' | 'CHU_QUAN';
    /**
     * @description Loại đề nghị theo TL-02 SCR-0602 trường 1. TL-03 ghi bộ giá trị khác (`DIEU_CHINH_KHOI_LUONG`, `DIEU_CHINH_NOI_DUNG`, không có `TAM_DUNG`); hợp đồng theo TL-02, TL-03 cần sửa ràng buộc CHECK.
     * @enum {string}
     */
    ChangeRequestType: 'GIA_HAN' | 'DOI_SO_LUONG' | 'DOI_SAN_PHAM' | 'TAM_DUNG' | 'HUY';
    /** @enum {string} */
    ChangeRequestStatus: 'NHAP' | 'CHO_DUYET' | 'DA_DUYET' | 'TU_CHOI' | 'DA_RUT';
    /**
     * @description Tên chuyển trạng thái dùng cho `/tasks/{id}/transitions`.
     * @enum {string}
     */
    TaskTransition: 'TIEP_TUC' | 'TRINH_NGHIEM_THU' | 'HOAN_THANH';
    /**
     * @description Tóm tắt nhiệm vụ cho danh sách (SCR-0503) và trang chủ (SCR-0002).
     * @example {
     *       "id": "01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77",
     *       "code": "NV-2026-0157",
     *       "title": "Thẩm định hồ sơ kết nạp đảng viên đợt 2",
     *       "status": "DANG_THUC_HIEN",
     *       "overdue": false,
     *       "dueDate": "2026-11-20",
     *       "progress": 60,
     *       "role": "CHU_TRI",
     *       "assignerName": "Trần Thị Bình",
     *       "unitName": "Ban Tổ chức Đảng uỷ khối",
     *       "periodId": "01928f3e-0000-7000-8000-000000004426",
     *       "rowVersion": 7,
     *       "taskGroup": "THEN_CHOT",
     *       "source": "KE_HOACH",
     *       "plannedQuantity": 12,
     *       "doneQuantity": 7,
     *       "unit": "hồ sơ",
     *       "openBlockerCount": 0,
     *       "workingDaysLeft": 22
     *     }
     */
    TaskSummary: {
      id: components['schemas']['Uuid'];
      /** @example NV-2026-0157 */
      code: string;
      title: string;
      status: components['schemas']['TaskStatus'];
      /** @description Cờ `task.task.is_overdue`, máy chủ đặt hằng ngày (QT-06 bước 8). */
      overdue: boolean;
      dueDate: components['schemas']['Date'];
      /** @description Phần trăm 0–100; máy chủ lưu `progress_pct` dạng 0–1. */
      progress: number;
      role: components['schemas']['AssigneeRole'];
      assignerName: string;
      unitName: string;
      periodId: components['schemas']['Uuid'];
      rowVersion: components['schemas']['RowVersion'];
      taskGroup: components['schemas']['TaskGroup'];
      source: components['schemas']['TaskSource'];
      keyResultId?: components['schemas']['Uuid'];
      keyResultTitle?: string;
      plannedQuantity: number;
      doneQuantity: number;
      /** @description Đơn vị tính của sản phẩm */
      unit: string;
      openBlockerCount: number;
      /** @description Số ngày làm việc còn lại tới hạn, âm khi đã quá hạn; máy chủ tính theo lịch nghỉ. */
      workingDaysLeft: number;
    };
    TaskSummaryPage: {
      items: components['schemas']['TaskSummary'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /** @description Trang "Việc của tôi" kèm số đếm bộ lọc nhanh và danh sách KR để lọc. */
    MyTasksPage: {
      items: components['schemas']['TaskSummary'][];
      nextCursor: components['schemas']['NextCursor'];
      period: components['schemas']['PeriodRef'];
      /** @description Số đếm cho từng bộ lọc nhanh, tính trên cùng điều kiện vai trò, nhóm, KR, từ khoá. */
      counts: {
        TAT_CA: number;
        CHAM: number;
        SAP_HAN: number;
        CHO_NGHIEM_THU: number;
        HOAN_THANH: number;
      };
      keyResults: {
        id: components['schemas']['Uuid'];
        title: string;
      }[];
    };
    /** @description Một dòng nhật ký tiến độ (`task.task_progress`), chỉ ghi thêm. */
    ProgressLogEntry: {
      at: components['schemas']['DateTime'];
      /** @description Tên người ghi */
      by: string;
      byRole?: components['schemas']['AssigneeRole'];
      channel?: components['schemas']['ProgressChannel'];
      fromPercent?: number;
      percent: number;
      note: string;
    };
    /** @description Điểm nghẽn (`task.task_blocker`). */
    TaskBlocker: {
      id: components['schemas']['Uuid'];
      /** @example BN-0157-01 */
      code: string;
      content: string;
      causeCategory: components['schemas']['BlockerCauseCategory'];
      causeClaimed: components['schemas']['CauseKind'];
      impactLevel: components['schemas']['ImpactLevel'];
      handlerName?: string;
      resolveBy?: components['schemas']['Date'];
      status: components['schemas']['BlockerStatus'];
      createdAt: components['schemas']['DateTime'];
      rowVersion?: components['schemas']['RowVersion'];
    };
    BlockerReport: {
      content: string;
      causeCategory: components['schemas']['BlockerCauseCategory'];
      causeClaimed: components['schemas']['CauseKind'];
      impactLevel: components['schemas']['ImpactLevel'];
      resolveBy?: components['schemas']['Date'];
    };
    /** @description Các trường người giao được sửa khi xử lý điểm nghẽn (JSON Merge Patch). */
    BlockerPatch: {
      handlerPersonId?: components['schemas']['Uuid'];
      resolveBy?: components['schemas']['Date'];
      status?: components['schemas']['BlockerStatus'];
      resolutionNote?: string;
    };
    /** @description Đề nghị gia hạn, điều chỉnh (`task.task_change_request`). */
    ChangeRequest: {
      id: components['schemas']['Uuid'];
      type: components['schemas']['ChangeRequestType'];
      status: components['schemas']['ChangeRequestStatus'];
      newDueDate?: components['schemas']['Date'];
      newQuantity?: number;
      newProduct?: string;
      causeClaimed: components['schemas']['CauseKind'];
      blockerId?: components['schemas']['Uuid'];
      reason: string;
      approverName: string;
      submittedAt: components['schemas']['DateTime'];
      /** @description Vượt ngưỡng BR-06-06 đã chuyển cấp trên (MSG-06-07) */
      escalated?: boolean;
      decidedAt?: components['schemas']['DateTime'];
      causeDecided?: components['schemas']['CauseKind'];
      decisionNote?: string;
    };
    ChangeRequestDraft: {
      type: components['schemas']['ChangeRequestType'];
      newDueDate?: components['schemas']['Date'];
      newQuantity?: number;
      newProduct?: string;
      causeClaimed: components['schemas']['CauseKind'];
      blockerId?: components['schemas']['Uuid'];
      reason: string;
    };
    ChangeRequestDecision: {
      approve: boolean;
      causeDecided: components['schemas']['CauseKind'];
      /** @description Bắt buộc khi từ chối */
      note?: string;
    };
    ChangeRequestPreview: {
      /** @description Số ngày làm việc tăng thêm theo lịch nghỉ */
      extraWorkingDays: number;
      objective: {
        scoreDueDate: components['schemas']['Date'];
        /** @description Hệ số tiến độ C nếu nguyên nhân khách quan */
        c: number;
      };
      subjective: {
        scoreDueDate: components['schemas']['Date'];
        delayDays: number;
        c: number;
      };
      /** @description Vượt ngưỡng BR-06-06 */
      overThreshold: boolean;
      escalateToName?: string;
      /** @description Gửi sau hạn thực hiện (BR-06-07) */
      lateSubmission: boolean;
      /** @description Huỷ không được khi đã có sản phẩm được nghiệm thu (BR-06-09) */
      cancelBlocked: boolean;
    };
    /** @description Một lần chuyển trạng thái (`task.task_state_log`). */
    StateLogEntry: {
      at: components['schemas']['DateTime'];
      from: components['schemas']['TaskStatus'] | null;
      to: components['schemas']['TaskStatus'];
      transition: string;
      actorName: string;
      reason?: string;
    };
    /** @description Dự báo trễ hạn do SVC-15 tính (`task.task.delay_risk`). */
    DelayRisk: {
      /** @enum {string} */
      level: 'THAP' | 'VUA' | 'CAO';
      neededWorkingDays: number;
      explanation: string;
    };
    /** @description Chi tiết nhiệm vụ (SCR-0601). */
    TaskDetail: components['schemas']['TaskSummary'] & {
      description: string;
      sourceDoc?: {
        number: string;
        title: string;
      };
      /** @description Mô tả nguồn, ví dụ "Kế hoạch Q4 phiên bản 2 · dòng 1" */
      sourceRef?: string;
      catalogItemCode?: string;
      difficulty?: number;
      standardScore?: number;
      assignedAt?: components['schemas']['DateTime'];
      acceptorName: string;
      /** @description Hạn tính hệ số tiến độ C, khác `dueDate` là hạn thực hiện (BR-06-04, BR-06-05). */
      scoreDueDate: components['schemas']['Date'];
      products: {
        confirmed: number;
        pendingReview: number;
        required: number;
      };
      delayRisk?: components['schemas']['DelayRisk'];
      extensionsUsed: number;
      extensionsMax: number;
      coAssignees: {
        personId: components['schemas']['Uuid'];
        name: string;
        role: components['schemas']['AssigneeRole'];
      }[];
      progressLog: components['schemas']['ProgressLogEntry'][];
      blockers: components['schemas']['TaskBlocker'][];
      changeRequests: components['schemas']['ChangeRequest'][];
      stateLog: components['schemas']['StateLogEntry'][];
    };
    /** @description Một người hoặc đơn vị được giao (`task.task_assignee`). */
    TaskAssignee: {
      /** @enum {string} */
      kind: 'PERSON' | 'UNIT';
      id: components['schemas']['Uuid'];
      name: string;
      role: components['schemas']['AssigneeRole'];
    };
    ProgressUpdate: {
      percent: number;
      note: string;
      /** @description Số lượng sản phẩm đã làm (tuỳ chọn) */
      doneQuantity?: number;
      channel?: components['schemas']['ProgressChannel'];
    };
    ProgressBatchItem: {
      taskId: components['schemas']['Uuid'];
      rowVersion: components['schemas']['RowVersion'];
      percent: number;
      note: string;
    };
    ProgressBatchResult: {
      taskId: components['schemas']['Uuid'];
      /** @description Mã HTTP tương đương cho dòng này */
      status: number;
      task?: components['schemas']['TaskSummary'];
      problem?: components['schemas']['ProblemDetails'];
    };
    /** @description Đăng ký nhiệm vụ từ danh mục (QT-05 bước 1c). */
    TaskRegistration: {
      catalogItemId: components['schemas']['Uuid'];
      quantity: number;
      proposedDueDate: components['schemas']['Date'];
      note?: string;
    };
    /** @description Điều kiện lọc giống `/tasks/mine` hoặc `/tasks`. */
    TaskExportRequest: {
      /**
       * @description `me` xuất "Việc của tôi"; bỏ trống xuất theo đơn vị
       * @enum {string}
       */
      assignee?: 'me';
      unit?: components['schemas']['Uuid'];
      period?: components['schemas']['Uuid'];
      filter?: components['schemas']['MyTasksQuickFilter'];
      status?: components['schemas']['TaskStatus'][];
      overdueOnly?: boolean;
      role?: components['schemas']['AssigneeRole'];
      group?: components['schemas']['TaskGroup'];
      keyResultId?: components['schemas']['Uuid'];
      q?: string;
    };
    TaskTransitionRequest: {
      transition: components['schemas']['TaskTransition'];
      reason?: string;
    };
    TaskMilestone: {
      date: components['schemas']['Date'];
      content: string;
    };
    /** @description Người hoặc đơn vị phối hợp. */
    AssigneeRef: {
      /** @enum {string} */
      kind: 'PERSON' | 'UNIT';
      id: components['schemas']['Uuid'];
      name: string;
    };
    /** @description Nhiệm vụ đang soạn để giao (SCR-0501), tên trường theo `task.task` của TL-03. `id`, `code`, `rowVersion` chỉ có sau khi lưu nháp. */
    TaskDraft: {
      id?: components['schemas']['Uuid'];
      code?: string;
      rowVersion?: components['schemas']['RowVersion'];
      status: components['schemas']['TaskStatus'];
      periodId: components['schemas']['Uuid'];
      unitName: string;
      assignerId: components['schemas']['Uuid'];
      assignerName: string;
      source: components['schemas']['TaskSource'];
      planItemId?: components['schemas']['Uuid'];
      catalogItemId?: components['schemas']['Uuid'];
      title: string;
      taskGroup: components['schemas']['TaskGroup'];
      keyResultId?: components['schemas']['Uuid'];
      priority: components['schemas']['TaskPriority'];
      leadPersonId?: components['schemas']['Uuid'];
      coAssignees: components['schemas']['AssigneeRef'][];
      dueDate?: components['schemas']['Date'];
      acceptorId?: components['schemas']['Uuid'];
      milestones: components['schemas']['TaskMilestone'][];
      productName: string;
      quantity: number | null;
      unit: string;
      evidenceRequired: string;
      qualityCriteria: string;
      sourceDoc?: {
        number: string;
        title: string;
      };
      /** @description Căn cứ của nhiệm vụ phát sinh ngoài kế hoạch (BR-05-09) */
      basis?: string;
    };
    TaskDraftTemplate: {
      period: components['schemas']['PeriodRef'];
      draft: components['schemas']['TaskDraft'];
    };
    AssignCheckItem: {
      /** @example BR-05-01 */
      rule: string;
      /**
       * @description `CHAN` không giao được; `CANH_BAO` giao được sau khi xử lý
       * @enum {string}
       */
      level: 'CHAN' | 'CANH_BAO';
      passed: boolean;
      message: string;
    };
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
    /** @description Khối lượng việc của người chủ trì theo tuần (BR-05-11, MSG-05-04). */
    PersonWorkload: {
      personId: components['schemas']['Uuid'];
      personName: string;
      leadCount: number;
      coCount: number;
      weeklyThreshold: number;
      weeks: {
        label: string;
        count: number;
      }[];
    };
    AssignCheckResult: {
      items: components['schemas']['AssignCheckItem'][];
      similar: components['schemas']['SimilarTask'][];
      /** @description Số ngày làm việc từ hôm nay tới hạn */
      workingDays?: number;
      workload?: components['schemas']['PersonWorkload'];
    };
    AssignRequest: {
      /** @description Lý do vẫn tạo mới khi có nhiệm vụ gần giống (BR-05-06) */
      similarReason?: string;
    };
    AssignResult: {
      id: components['schemas']['Uuid'];
      code: string;
      status: components['schemas']['TaskStatus'];
      leadName: string;
    };
    BulkAssignRequest: {
      draft: components['schemas']['TaskDraft'];
      unitIds: components['schemas']['Uuid'][];
    };
    AssignBatchResult: {
      taskId: components['schemas']['Uuid'];
      code: string;
      assigned: boolean;
      status: components['schemas']['TaskStatus'];
      message?: string;
    };
    /**
     * @description Hệ nguồn (`task.task_source_doc.source_system`).
     * @enum {string}
     */
    SourceSystem: 'VOFFICE' | 'IOFFICE' | 'EDXML' | 'LGSP' | 'THU_CONG';
    /**
     * @description Tình trạng xử lý văn bản đến ở đơn vị nhận (suy luận từ SCR-0502).
     * @enum {string}
     */
    SourceDocStatus: 'CHO_XU_LY' | 'DA_XU_LY' | 'DA_BO_QUA';
    /**
     * @description Tình trạng trích nhiệm vụ của SVC-14 (cùng giá trị với `ExtractionStatus` của platform.yaml).
     * @enum {string}
     */
    SourceDocExtractionStatus: 'DANG_TRICH' | 'XONG' | 'KHONG_TRICH_DUOC';
    SourceDocSummary: {
      id: components['schemas']['Uuid'];
      sourceSystem: components['schemas']['SourceSystem'];
      /** @description Số, ký hiệu theo NĐ 30/2020 */
      docNumber: string;
      docDate: components['schemas']['Date'];
      issuer: string;
      /** @description Trích yếu */
      excerpt: string;
      receivedAt: components['schemas']['DateTime'];
      /** @example EdXML */
      format: string;
      status: components['schemas']['SourceDocStatus'];
      extraction: components['schemas']['SourceDocExtractionStatus'];
      pendingCount: number;
      suggestionCount: number;
    };
    SourceDoc: components['schemas']['SourceDocSummary'] & {
      issuerHeader: string;
      title: string;
      preamble: string;
      paragraphs: {
        no: number;
        text: string;
        /** @description Đoạn giao việc cho đơn vị nhận do AI xác định */
        assignment: boolean;
      }[];
      recipients: string;
      dismissReason?: string;
    };
    /**
     * @description Quyết định của người giao với một nhiệm vụ AI trích (`ai.suggestion_log.decision`).
     * @enum {string}
     */
    SuggestionDecision: 'CHO_XAC_NHAN' | 'DA_XAC_NHAN' | 'DA_BO' | 'DA_GOP';
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
    FromDocumentRequest: {
      sourceDocId: components['schemas']['Uuid'];
      suggestionId: components['schemas']['Uuid'];
      paragraphNo: number;
      fields: components['schemas']['TaskSuggestionFields'];
      editedFields: components['schemas']['SuggestionField'][];
      similarReason?: string;
    };
    /**
     * @description Trạng thái `task.plan_version.status` (baseline mục 9.1).
     * @enum {string}
     */
    PlanVersionStatus: 'NHAP' | 'CHO_DUYET' | 'DA_DUYET' | 'TRA_LAI';
    /** @description Kế hoạch công tác quý của một đơn vị (`task.plan`). */
    Plan: {
      id: components['schemas']['Uuid'];
      /** @example KH-Q4-2026-BTC */
      code: string;
      period: components['schemas']['PeriodRef'];
      unitId: components['schemas']['Uuid'];
      unitName: string;
      currentVersion: components['schemas']['PlanVersionSummary'];
      /** @description Phiên bản `DA_DUYET` đang hiệu lực */
      effectiveVersionNo?: number | null;
      /** @description Kỳ trước có kế hoạch đã duyệt của đơn vị, cho nút "Sao chép từ {nhãn}" (nguồn của `copy-from`). */
      previousPeriod?: components['schemas']['PeriodRef'] | null;
      approvalDeadline?: components['schemas']['Date'];
      adjustmentsUsed?: number;
      adjustmentsMax?: number;
      rowVersion: components['schemas']['RowVersion'];
    };
    PlanVersionSummary: {
      planId: components['schemas']['Uuid'];
      versionNo: number;
      status: components['schemas']['PlanVersionStatus'];
      unitName: string;
      periodLabel: string;
      itemCount: number;
      submittedAt?: components['schemas']['DateTime'];
      submittedByName?: string;
      /** @description Chức vụ người trình (SCR-0403 vùng 3) */
      submittedByTitle?: string;
      approvedAt?: components['schemas']['DateTime'];
      /** @description Người duyệt */
      approvedByName?: string;
    };
    /** @description Một dòng kế hoạch theo Bảng 1 (`task.plan_item`, SCR-0402 trường 5–14). */
    PlanItemInput: {
      id?: components['schemas']['Uuid'];
      /** @description Dòng tương ứng ở phiên bản trước, để tính thêm, sửa, bỏ (đề xuất của TL-02) */
      originItemId?: components['schemas']['Uuid'];
      title: string;
      taskGroup: components['schemas']['TaskGroup'];
      keyResultId?: components['schemas']['Uuid'];
      leadAssignee: components['schemas']['AssigneeRef'];
      coAssignees?: components['schemas']['AssigneeRef'][];
      /** @description Bỏ trống khi là việc ngoài danh mục (chờ chấm điểm) */
      catalogItemId?: components['schemas']['Uuid'];
      /** @description Tên sản phẩm. Tên chọn theo TL-02 SCR-0402 trường 11 (`product_name`); TL-03 chưa có cột này trong `task.plan_item` và cần bổ sung. */
      productName: string;
      quantity: number;
      unit?: string;
      /** @description Hạn của dòng. Với dòng lặp lại (`recurrence` khác null), là hạn của lần cuối trong kỳ, thường là ngày cuối kỳ; hạn từng lần do máy chủ sinh khi giao việc. */
      dueDate: components['schemas']['Date'];
      /**
       * @description Lặp lại thay cho một hạn cố định (`task.plan_item.recurrence`; TL-03 ghi "hằng tháng, hằng quý", TL-02 SCR-0402 trường 12 chỉ nêu "Hằng tháng"). `HANG_QUY` dùng khi kế hoạch lập cho kỳ dài hơn một quý.
       * @enum {string|null}
       */
      recurrence?: 'HANG_THANG' | 'HANG_QUY' | null;
      /** @description Điểm của dòng, bắt buộc khi duyệt (BR-04-05). Tên chọn theo cột `standard_score` của TL-03; TL-02 SCR-0402 trường 13 gọi là `score_weight`. */
      standardScore?: number | null;
      flows?: components['schemas']['Flow'][];
      /** @description Bắt buộc với mỗi thay đổi ở phiên bản điều chỉnh (BR-04-10) */
      changeReason?: string;
    };
    PlanItem: components['schemas']['PlanItemInput'] & {
      lineNo: number;
      catalogItemCode?: string;
      /** @description Việc ngoài danh mục chờ chấm điểm */
      pendingScore?: boolean;
    };
    /** @description Một kết quả kiểm tra tức thì của kế hoạch. */
    PlanCheck: {
      /** @example BR-04-03 */
      rule: string;
      /** @enum {string} */
      level: 'CHAN' | 'CANH_BAO';
      lineNo?: number;
      message: string;
      /** @example MSG-04-04 */
      msg?: string;
    };
    /** @description Một phiên bản kế hoạch kèm các dòng (`task.plan_version`, `task.plan_item`). */
    PlanVersion: {
      planId: components['schemas']['Uuid'];
      versionNo: number;
      status: components['schemas']['PlanVersionStatus'];
      period: components['schemas']['PeriodRef'];
      unitName: string;
      approvalDeadline?: components['schemas']['Date'];
      workingDaysToDeadline?: number;
      /** @description SHA-256 tính khi gửi duyệt */
      contentHash?: string | null;
      reviewComment?: string | null;
      items: components['schemas']['PlanItem'][];
      /** @description Điểm tối đa của từng nhóm theo Policy Pack (BR-04-06), kèm luồng và gói áp dụng (Hình 7.13 vùng 3). */
      groupWeights: {
        THUONG_XUYEN: number;
        THEN_CHOT: number;
        flow?: components['schemas']['Flow'];
        /** @example PP-KHOI-DANG */
        policyPackCode?: string;
        /** @example Khối Đảng */
        policyPackName?: string;
        /** @example 2.0.0 */
        policyPackVersion?: string;
      };
      checks: components['schemas']['PlanCheck'][];
      /** @description Lý do chung của lần điều chỉnh */
      changeReason?: string | null;
      /** @description Dòng của phiên bản trước bị bỏ ở phiên bản điều chỉnh này, kèm lý do. */
      removedItems?: components['schemas']['PlanRemovedItem'][];
      signRequestId?: components['schemas']['Uuid'];
      rowVersion: components['schemas']['RowVersion'];
    };
    PlanVersionInput: {
      items: components['schemas']['PlanItemInput'][];
      /** @description Ở phiên bản điều chỉnh, các dòng của phiên bản trước bị bỏ cùng lý do (BR-04-10 chặn khi thiếu lý do). Dòng phiên bản trước không có trong `items` cũng không có ở đây bị coi là thiếu lý do. */
      removedItems?: components['schemas']['PlanRemovedItem'][];
    };
    PlanRemovedItem: {
      originItemId: components['schemas']['Uuid'];
      changeReason: string;
    };
    PlanDiffLine: {
      /** @enum {string} */
      change: 'THEM' | 'SUA' | 'BO' | 'KHONG_DOI';
      before?: components['schemas']['PlanItem'];
      after?: components['schemas']['PlanItem'];
      changedFields?: string[];
      reason?: string;
    };
    PlanDiff: {
      baseVersionNo: number;
      versionNo: number;
      added: number;
      modified: number;
      removed: number;
      groupWeightChanged?: boolean;
      /** @description Lần điều chỉnh hiện tại */
      adjustmentNo?: number;
      adjustmentsMax?: number;
      lines: components['schemas']['PlanDiffLine'][];
    };
    /** @description Dòng kế hoạch đã duyệt dùng làm nguồn giao việc (BR-05-04). */
    PlanItemRef: {
      id: components['schemas']['Uuid'];
      planCode: string;
      version: number;
      lineNo: number;
      title: string;
      catalogItemId?: components['schemas']['Uuid'];
      taskGroup: components['schemas']['TaskGroup'];
      keyResultId?: components['schemas']['Uuid'];
      productName: string;
      quantity: number;
      unit: string;
      dueDate: components['schemas']['Date'];
      evidenceRequired: string;
      qualityCriteria: string;
    };
    /**
     * @description Trạng thái phiên bản danh mục (`task.work_catalog.status`, suy luận).
     * @enum {string}
     */
    WorkCatalogStatus: 'NHAP' | 'AP_DUNG' | 'HET_HIEU_LUC';
    /** @enum {string} */
    CatalogItemStatus: 'AP_DUNG' | 'NGUNG';
    /** @description Một phiên bản danh mục công việc theo vị trí việc làm (`task.work_catalog`). */
    WorkCatalog: {
      id: components['schemas']['Uuid'];
      name: string;
      versionNo: number;
      status: components['schemas']['WorkCatalogStatus'];
      /** @example Quý IV/2026 */
      effectiveFromLabel?: string;
      effectiveFromDate?: components['schemas']['Date'];
      parentCatalogName?: string;
      itemCount: number;
      positionCount: number;
      rowVersion: components['schemas']['RowVersion'];
      /** @description Hệ số mức khó từ Policy Pack, chỉ để gợi ý điểm. */
      difficultyFactors: {
        [key: string]: number;
      };
      /** @description Điểm gợi ý cho từng mức khó. */
      scoreSuggestion: {
        [key: string]: number;
      };
    };
    /** @description Vị trí việc làm (`org.position`). */
    Position: {
      id: components['schemas']['Uuid'];
      name: string;
    };
    /** @description Mục danh mục công việc theo Bảng 3 (`task.catalog_item`). */
    CatalogItem: {
      id: components['schemas']['Uuid'];
      catalogId?: components['schemas']['Uuid'];
      /** @example TC-DV-06 */
      code: string;
      name: string;
      positionId?: components['schemas']['Uuid'];
      positionName?: string;
      productName: string;
      unit: string;
      /** @description Mức khó năm mức (BR-04-14) */
      difficulty: number;
      /** @description Điểm chuẩn (`base_score`) */
      baseScore: number;
      qualityCriteria: string;
      evidenceRequired?: string;
      defaultGroup: components['schemas']['TaskGroup'];
      /** @description Thời hạn chuẩn, ngày làm việc */
      standardDays?: number;
      status?: components['schemas']['CatalogItemStatus'];
      usageCount?: number;
      usagePeriods?: number;
      /** @description Đã dùng ở kỳ đã khoá (BR-04-13) */
      usedInLockedPeriod?: boolean;
      rowVersion?: components['schemas']['RowVersion'];
    };
    CatalogItemInput: {
      /** @description Bỏ trống thì máy chủ tự sinh */
      code?: string;
      positionId?: components['schemas']['Uuid'];
      name: string;
      productName: string;
      unit: string;
      difficulty: number;
      baseScore: number;
      qualityCriteria: string;
      evidenceRequired?: string;
      defaultGroup: components['schemas']['TaskGroup'];
      standardDays?: number;
      status?: components['schemas']['CatalogItemStatus'];
    };
    CatalogItemSaveResult: {
      item: components['schemas']['CatalogItem'];
      catalog: components['schemas']['WorkCatalog'];
      /** @description Thay đổi được lưu ở phiên bản khác phiên bản đang xem */
      movedToNewVersion: boolean;
    };
    CatalogItemsPage: {
      items: components['schemas']['CatalogItem'][];
      nextCursor: components['schemas']['NextCursor'];
      /** @description Tổng số mục theo bộ lọc (SCR-0401 cần hiển thị) */
      total: number;
      positionCount: number;
    };
    /** @description Một lần nhập Excel danh mục. */
    CatalogImportJob: {
      id: string;
      fileName: string;
      /** @enum {string} */
      status: 'DANG_XU_LY' | 'XONG' | 'LOI';
      totalRows: number;
      validRows: number;
      errorGroups: {
        reason: string;
        count: number;
        /** @enum {string} */
        kind: 'THIEU' | 'MO_HO' | 'KHAC';
      }[];
      finishedAt?: components['schemas']['DateTime'];
    };
    /**
     * Format: uuid
     * @description Mã định danh UUIDv7 (cột `id` của mọi bảng, baseline mục 9).
     * @example 01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77
     */
    Uuid: string;
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
     * Format: date
     * @description Ngày theo lịch (ISO 8601), không có giờ.
     * @example 2026-12-15
     */
    Date: string;
    /**
     * @description Giá trị `row_version` (khoá lạc quan). Cùng giá trị với ETag, gửi lại trong `If-Match`.
     * @example 7
     */
    RowVersion: number;
    /** @description Con trỏ trang kế tiếp; `null` khi đã hết. */
    NextCursor: string | null;
    /** @description Kỳ đánh giá rút gọn (`config.period`). */
    PeriodRef: {
      id: components['schemas']['Uuid'];
      /** @example Quý IV/2026 */
      label: string;
      startDate: components['schemas']['Date'];
      endDate: components['schemas']['Date'];
    };
    /**
     * @description Trạng thái tác vụ dài. TL chưa đặc tả lược đồ job; hợp đồng giữ bộ giá trị WEB-007 đã dùng (lấy từ các trạng thái job trong TL-03).
     * @enum {string}
     */
    JobStatus: 'CHO' | 'DANG_CHAY' | 'HOAN_TAT' | 'THAT_BAI' | 'DA_HUY';
    /**
     * Format: date-time
     * @description Thời điểm ISO 8601 có múi giờ.
     * @example 2026-10-19T08:30:00+07:00
     */
    DateTime: string;
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
     * @description Luồng đánh giá (baseline mục 9.1).
     * @enum {string}
     */
    Flow: 'DANG' | 'NHA_NUOC';
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
    /** @description OPA từ chối theo RBAC/ABAC hoặc quy tắc tách vai trò (Phụ lục 4, mục V.1). */
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
  };
  parameters: {
    /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
    VersionNo: number;
    /** @description Mã phiên bản danh mục (`task.work_catalog.id`). */
    CatalogId: components['schemas']['Uuid'];
    JobId: string;
    /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
    UnitId: components['schemas']['Uuid'];
    /** @description Mã kỳ đánh giá (`config.period.id`). */
    PeriodId: components['schemas']['Uuid'];
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
  listUnitTasks: {
    parameters: {
      query?: {
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Danh sách trạng thái phân tách bằng dấu phẩy. */
        status?: string;
        kr?: components['schemas']['Uuid'];
        overdue?: boolean;
        /** @description Gồm cả đơn vị cấp dưới (lãnh đạo, giám sát). */
        includeSubunits?: boolean;
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
      /** @description Một trang nhiệm vụ. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskSummaryPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createTaskDraft: {
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
        'application/json': components['schemas']['TaskDraft'];
      };
    };
    responses: {
      /** @description Bản nháp đã lưu. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskDraft'];
        };
      };
      403: components['responses']['Forbidden'];
      /** @description Kỳ đã khoá (`TASK-409-PERIOD-LOCKED`). */
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  listMyTasks: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Bộ lọc nhanh (SCR-0503 trường 1); bỏ trống tương đương `TAT_CA`. */
        filter?: components['schemas']['MyTasksQuickFilter'];
        /** @description `today` cho khối việc hôm nay ở trang chủ (SCR-0002). */
        view?: 'today';
        /** @description Danh sách trạng thái phân tách bằng dấu phẩy. */
        status?: string;
        overdue?: boolean;
        role?: components['schemas']['AssigneeRole'];
        group?: components['schemas']['TaskGroup'];
        /** @description Lọc theo KR liên kết. */
        kr?: components['schemas']['Uuid'];
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
      /** @description Một trang "Việc của tôi". */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['MyTasksPage'];
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  getNewTaskDraft: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Khung soạn. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskDraftTemplate'];
        };
      };
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  checkTaskAssignment: {
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
        'application/json': components['schemas']['TaskDraft'];
      };
    };
    responses: {
      /** @description Kết quả kiểm tra. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AssignCheckResult'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  bulkAssignTasks: {
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
        'application/json': components['schemas']['BulkAssignRequest'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      403: components['responses']['Forbidden'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  assignTaskBatch: {
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
        'application/json': {
          taskIds: components['schemas']['Uuid'][];
        };
      };
    };
    responses: {
      /** @description Kết quả từng nhiệm vụ. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            results: components['schemas']['AssignBatchResult'][];
          };
        };
      };
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  updateTaskProgressBatch: {
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
        'application/json': {
          items: components['schemas']['ProgressBatchItem'][];
        };
      };
    };
    responses: {
      /** @description Kết quả từng dòng. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            results: components['schemas']['ProgressBatchResult'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  registerTask: {
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
        'application/json': components['schemas']['TaskRegistration'];
      };
    };
    responses: {
      /** @description Nhiệm vụ đăng ký đã tạo. */
      201: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskSummary'];
        };
      };
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  exportTasks: {
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
        'application/json': components['schemas']['TaskExportRequest'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      429: components['responses']['TooManyRequests'];
      default: components['responses']['Problem'];
    };
  };
  getTask: {
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
      /** @description Nhiệm vụ. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskDetail'];
        };
      };
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  saveTaskDraft: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
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
        'application/json': components['schemas']['TaskDraft'];
      };
    };
    responses: {
      /** @description Bản nháp đã lưu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskDraft'];
        };
      };
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Lệch phiên bản (`TASK-409-VERSION-CONFLICT`) hoặc nhiệm vụ không còn `NHAP` (`TASK-409-NOT-DRAFT`). */
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  deleteTaskDraft: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
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
      /** @description Đã huỷ bản nháp. */
      204: {
        headers: {
          [name: string]: unknown;
        };
        content?: never;
      };
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  getTaskDraft: {
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
      /** @description Khung soạn của nhiệm vụ. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskDraftTemplate'];
        };
      };
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  assignTask: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
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
        'application/json': components['schemas']['AssignRequest'];
      };
    };
    responses: {
      /** @description Đã giao. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['AssignResult'];
        };
      };
      403: components['responses']['Forbidden'];
      /** @description Lệch phiên bản, kỳ đã khoá, hoặc nhiệm vụ không còn `NHAP`. */
      409: components['responses']['Conflict'];
      /** @description Vi phạm quy tắc chặn của QT-05 bước 5 (mỗi lỗi trong `errors[]` có `rule`). */
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  listSimilarTasks: {
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
      /** @description Danh sách nhiệm vụ gần giống. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['SimilarTask'][];
          };
        };
      };
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listTaskAssignees: {
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
      /** @description Danh sách người, đơn vị được giao (`task.task_assignee`). */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['TaskAssignee'][];
          };
        };
      };
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  acceptTask: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
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
      /** @description Nhiệm vụ sau khi nhận. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskDetail'];
        };
      };
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  transitionTask: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
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
        'application/json': components['schemas']['TaskTransitionRequest'];
      };
    };
    responses: {
      /** @description Nhiệm vụ sau khi chuyển. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskDetail'];
        };
      };
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  remindTask: {
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
        'application/json': {
          note?: string;
        };
      };
    };
    responses: {
      /** @description Đã xếp hàng gửi nhắc (EVT-21). */
      202: {
        headers: {
          [name: string]: unknown;
        };
        content?: never;
      };
      403: components['responses']['Forbidden'];
      429: components['responses']['TooManyRequests'];
      default: components['responses']['Problem'];
    };
  };
  reassignTask: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
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
        'application/json': {
          leadPersonId: components['schemas']['Uuid'];
          reason: string;
        };
      };
    };
    responses: {
      /** @description Nhiệm vụ sau khi giao lại. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskDetail'];
        };
      };
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  updateTaskProgress: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
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
        'application/json': components['schemas']['ProgressUpdate'];
      };
    };
    responses: {
      /** @description Nhiệm vụ sau khi cập nhật. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskDetail'];
        };
      };
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Lệch phiên bản (`TASK-409-VERSION-CONFLICT`, MSG-09-07) hoặc kỳ đã khoá (`TASK-409-PERIOD-LOCKED`). */
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  reportTaskBlocker: {
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
        'application/json': components['schemas']['BlockerReport'];
      };
    };
    responses: {
      /** @description Nhiệm vụ sau khi thêm điểm nghẽn. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskDetail'];
        };
      };
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  handleTaskBlocker: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
        blockerId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/merge-patch+json': components['schemas']['BlockerPatch'];
      };
    };
    responses: {
      /** @description Điểm nghẽn sau khi xử lý. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskBlocker'];
        };
      };
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  submitTaskChangeRequest: {
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
        'application/json': components['schemas']['ChangeRequestDraft'];
      };
    };
    responses: {
      /** @description Nhiệm vụ kèm đề nghị mới. */
      201: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskDetail'];
        };
      };
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  previewTaskChangeRequest: {
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
        'application/json': components['schemas']['ChangeRequestDraft'];
      };
    };
    responses: {
      /** @description Hệ quả dự kiến. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ChangeRequestPreview'];
        };
      };
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  withdrawTaskChangeRequest: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
        requestId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Nhiệm vụ sau khi rút đề nghị. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskDetail'];
        };
      };
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      default: components['responses']['Problem'];
    };
  };
  decideTaskChangeRequest: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
        requestId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['ChangeRequestDecision'];
      };
    };
    responses: {
      /** @description Nhiệm vụ sau khi duyệt đề nghị. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskDetail'];
        };
      };
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  listSourceDocs: {
    parameters: {
      query?: {
        status?: components['schemas']['SourceDocStatus'];
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
      /** @description Một trang văn bản đến. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['SourceDocSummary'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  getSourceDoc: {
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
      /** @description Văn bản. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SourceDoc'];
        };
      };
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  dismissSourceDoc: {
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
        'application/json': {
          reason: string;
        };
      };
    };
    responses: {
      /** @description Văn bản sau khi bỏ qua. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['SourceDoc'];
        };
      };
      409: components['responses']['Conflict'];
      default: components['responses']['Problem'];
    };
  };
  createTaskFromDocument: {
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
        'application/json': components['schemas']['FromDocumentRequest'];
      };
    };
    responses: {
      /** @description Gợi ý đã chuyển `DA_XAC_NHAN` kèm nhiệm vụ đã tạo. */
      201: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskSuggestion'];
        };
      };
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  mergeSourceDocIntoTask: {
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
        'application/json': {
          sourceDocId: components['schemas']['Uuid'];
          suggestionId: components['schemas']['Uuid'];
          paragraphNo: number;
        };
      };
    };
    responses: {
      /** @description Gợi ý đã chuyển `DA_GOP`. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TaskSuggestion'];
        };
      };
      409: components['responses']['Conflict'];
      default: components['responses']['Problem'];
    };
  };
  listPlans: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
        status?: components['schemas']['PlanVersionStatus'];
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
      /** @description Một trang kế hoạch. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['Plan'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  createPlan: {
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
        'application/json': {
          periodId: components['schemas']['Uuid'];
          /** @description Bỏ trống thì lấy đơn vị của ngữ cảnh phân công. */
          unitId?: components['schemas']['Uuid'];
        };
      };
    };
    responses: {
      /** @description Kế hoạch đã tạo. */
      201: {
        headers: {
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['Plan'];
        };
      };
      /** @description Đơn vị đã có kế hoạch cho kỳ (`TASK-409-PLAN-EXISTS`) hoặc kỳ không còn `MO`. */
      409: components['responses']['Conflict'];
      default: components['responses']['Problem'];
    };
  };
  listApprovedPlanItems: {
    parameters: {
      query?: {
        /** @description Mã kỳ đánh giá (`config.period.id`). */
        period?: components['parameters']['PeriodId'];
        /** @description Trạng thái phiên bản; mặc định `DA_DUYET`. */
        status?: components['schemas']['PlanVersionStatus'];
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
      /** @description Một trang dòng kế hoạch. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['PlanItemRef'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  listPlanApprovals: {
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
      /** @description Một trang phiên bản chờ duyệt. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['PlanVersionSummary'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  listPlanVersions: {
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
      /** @description Phiên bản, mới nhất trước. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['PlanVersionSummary'][];
          };
        };
      };
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  createPlanAdjustment: {
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
        'application/json': {
          /** @description Lý do của lần điều chỉnh (BR-04-10) */
          changeReason: string;
        };
      };
    };
    responses: {
      /** @description Phiên bản mới. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PlanVersion'];
        };
      };
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getPlanVersion: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Phiên bản kế hoạch. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PlanVersion'];
        };
      };
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  savePlanVersion: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['PlanVersionInput'];
      };
    };
    responses: {
      /** @description Phiên bản sau khi lưu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PlanVersion'];
        };
      };
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  copyPlanFromPreviousPeriod: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody?: {
      content: {
        'application/json': {
          /** @description Bỏ trống thì lấy kỳ liền trước. */
          sourcePeriodId?: components['schemas']['Uuid'];
        };
      };
    };
    responses: {
      /** @description Phiên bản sau khi sao chép. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PlanVersion'];
        };
      };
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  suggestPlanItemsFromOkr: {
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
      /** @description Dòng gợi ý. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['PlanItemInput'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  checkPlanVersion: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['PlanVersionInput'];
      };
    };
    responses: {
      /** @description Các vi phạm; rỗng khi hợp lệ. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            checks: components['schemas']['PlanCheck'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  submitPlanVersion: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Phiên bản đã gửi duyệt. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PlanVersion'];
        };
      };
      409: components['responses']['Conflict'];
      /** @description Còn lỗi chặn (MSG-04-04, MSG-04-05, MSG-04-06). */
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  diffPlanVersions: {
    parameters: {
      query: {
        /** @description Số phiên bản gốc để so sánh. `0` là kế hoạch rỗng: dùng cho phiên bản đầu, mọi dòng là `THEM`. */
        base: number;
        mode?: 'changes' | 'all';
      };
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Khác biệt giữa hai phiên bản. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PlanDiff'];
        };
      };
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  returnPlanVersion: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': {
          comment: string;
        };
      };
    };
    responses: {
      /** @description Phiên bản đã trả lại. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PlanVersion'];
        };
      };
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  approvePlanVersion: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        id: components['parameters']['IdPath'];
        /** @description Số phiên bản kế hoạch (`task.plan_version.version_no`). */
        v: components['parameters']['VersionNo'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': {
          /** @enum {string} */
          signMethod: 'MYSIGN' | 'USB_TOKEN' | 'CHUNG_THU_TO_CHUC';
          comment?: string;
        };
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      /** @description Người duyệt trùng người lập (`TASK-403-SAME-AUTHOR`) hoặc không phải người duyệt được chỉ định. */
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  listWorkCatalogs: {
    parameters: {
      query?: {
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
      /** @description Một trang phiên bản danh mục. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['WorkCatalog'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  listApplicableCatalogItems: {
    parameters: {
      query?: {
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
        applicable?: 'me';
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
      /** @description Một trang mục danh mục. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['CatalogItem'][];
            nextCursor: components['schemas']['NextCursor'];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  listCatalogVersionItems: {
    parameters: {
      query?: {
        /** @description Từ khoá tìm kiếm, không phân biệt dấu. */
        q?: components['parameters']['Q'];
        position?: components['schemas']['Uuid'];
        difficulty?: number;
        status?: components['schemas']['CatalogItemStatus'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path: {
        /** @description Mã phiên bản danh mục (`task.work_catalog.id`). */
        catalogId: components['parameters']['CatalogId'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Một trang mục kèm tổng số. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CatalogItemsPage'];
        };
      };
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  addCatalogItem: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Mã phiên bản danh mục (`task.work_catalog.id`). */
        catalogId: components['parameters']['CatalogId'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['CatalogItemInput'];
      };
    };
    responses: {
      /** @description Mục đã thêm. */
      201: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CatalogItemSaveResult'];
        };
      };
      403: components['responses']['Forbidden'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  updateCatalogItem: {
    parameters: {
      query?: never;
      header: {
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Mã phiên bản danh mục (`task.work_catalog.id`). */
        catalogId: components['parameters']['CatalogId'];
        itemId: components['schemas']['Uuid'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['CatalogItemInput'];
      };
    };
    responses: {
      /** @description Kết quả lưu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CatalogItemSaveResult'];
        };
      };
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      422: components['responses']['UnprocessableEntity'];
      428: components['responses']['PreconditionRequired'];
      default: components['responses']['Problem'];
    };
  };
  listCatalogPositions: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã phiên bản danh mục (`task.work_catalog.id`). */
        catalogId: components['parameters']['CatalogId'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Vị trí việc làm (`org.position`). */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['Position'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  cloneCatalogVersion: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Mã phiên bản danh mục (`task.work_catalog.id`). */
        catalogId: components['parameters']['CatalogId'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Phiên bản mới. */
      201: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkCatalog'];
        };
      };
      403: components['responses']['Forbidden'];
      409: components['responses']['Conflict'];
      default: components['responses']['Problem'];
    };
  };
  importCatalogExcel: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Mã phiên bản danh mục (`task.work_catalog.id`). */
        catalogId: components['parameters']['CatalogId'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'multipart/form-data': {
          file: string;
        };
      };
    };
    responses: {
      /** @description Đã nhận tệp. */
      202: {
        headers: {
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CatalogImportJob'];
        };
      };
      403: components['responses']['Forbidden'];
      422: components['responses']['UnprocessableEntity'];
      default: components['responses']['Problem'];
    };
  };
  getLatestCatalogImport: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã phiên bản danh mục (`task.work_catalog.id`). */
        catalogId: components['parameters']['CatalogId'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Lần nhập gần nhất, `null` khi chưa nhập lần nào. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CatalogImportJob'] | null;
        };
      };
      401: components['responses']['Unauthorized'];
      default: components['responses']['Problem'];
    };
  };
  getCatalogImport: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        jobId: components['parameters']['JobId'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Lần nhập. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['CatalogImportJob'];
        };
      };
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  downloadCatalogImportErrors: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        jobId: components['parameters']['JobId'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Tệp Excel các dòng lỗi kèm lý do (MSG-04-02). */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': string;
        };
      };
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
}
