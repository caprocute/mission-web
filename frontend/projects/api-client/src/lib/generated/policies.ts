/* eslint-disable */
/**
 * Sinh tự động bởi openapi-typescript từ contracts/openapi/modules/policies.yaml (WEB-064).
 * Không sửa tay; sửa hợp đồng rồi chạy `npm run api:gen` trong frontend/.
 */
export interface paths {
  '/policies/packs': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách gói Policy Pack và phiên bản
     * @description Trả các gói mà người gọi được xem trong tenant (gói gốc toàn cục đã đồng bộ và gói riêng của tenant), mỗi
     *     gói kèm tóm tắt các phiên bản gần nhất. Tương ứng `GET /policies` của TL-01 Phụ lục C (điểm chốt PC-01).
     */
    get: operations['listPolicyPacks'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
      };
      cookie?: never;
    };
    /**
     * Xem một gói Policy Pack
     * @description Thông tin gói và tóm tắt các phiên bản, dùng cho thanh gói trên Config Studio.
     */
    get: operations['getPolicyPack'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
      };
      cookie?: never;
    };
    /** Danh sách phiên bản của gói */
    get: operations['listPolicyPackVersions'];
    put?: never;
    /**
     * Nhân bản thành phiên bản nháp mới
     * @description Tạo phiên bản `NHAP` kế thừa toàn bộ quy tắc, bảng quyết định, tham số và tham số luồng duyệt của phiên bản
     *     nguồn (QT-15 bước 1). Với gói kế thừa của tenant, chỉ quy tắc có `overridable = true` của gói gốc được sửa
     *     về sau (BR-15-13).
     */
    post: operations['createPolicyPackVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    /**
     * Xem chi tiết một phiên bản
     * @description Thông tin phiên bản, manifest tham số, kết quả kiểm thử gần nhất, chữ ký và các gắn kỳ hiệu lực.
     */
    get: operations['getPolicyPackVersion'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/diff': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    /**
     * So sánh hai phiên bản
     * @description Trả khác biệt quy tắc, tham số, bảng quyết định và tham số luồng duyệt giữa phiên bản `{v}` và phiên bản
     *     `base` (nút "So sánh v2.0" ở SCR-1501; báo cáo khác biệt khi phê duyệt ở SCR-1503).
     */
    get: operations['diffPolicyPackVersions'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/rules': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    /**
     * Danh sách quy tắc CEL của phiên bản
     * @description Trả toàn bộ quy tắc theo `evalOrder` (số quy tắc mỗi phiên bản nhỏ nên không phân trang).
     */
    get: operations['listPolicyRules'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/rules/{key}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
        /** @description Khoá quy tắc `rule_key`, ví dụ `kpi_nhiem_vu`, `A`, `muc_cuoi`, `nguoi_dung_dau`, `nhom`. */
        key: components['parameters']['RuleKey'];
      };
      cookie?: never;
    };
    /** Xem một quy tắc CEL */
    get: operations['getPolicyRule'];
    /**
     * Lưu nháp quy tắc và tham số
     * @description Kiểm tra kiểu CEL bằng cel-java với khai báo biến của ngữ cảnh tính điểm (BR-15-01), kiểm tra tổng điểm tối
     *     đa nhóm (BR-15-05) rồi lưu. Cho phép khi phiên bản ở `NHAP` hoặc `DAT_KIEM_THU`; nếu đang `DAT_KIEM_THU`
     *     thì phiên bản về `NHAP` và phản hồi có `statusReverted = true` để giao diện hiện MSG-15-03. Dùng cho quy tắc
     *     công thức (SCR-1501) và quy tắc `muc_cuoi`, `nguoi_dung_dau` (SCR-1502).
     */
    put: operations['savePolicyRule'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/decision-tables': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    /** Danh sách bảng quyết định của phiên bản */
    get: operations['listDecisionTables'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/decision-tables/{key}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
        /** @description Khoá bảng quyết định `table_key`, ví dụ `xep_loai`, `tieu_chi_chung`. */
        key: components['parameters']['TableKey'];
      };
      cookie?: never;
    };
    /** Xem một bảng quyết định */
    get: operations['getDecisionTable'];
    /**
     * Lưu nháp bảng quyết định
     * @description Kiểm tra cú pháp FEEL của cột đầu vào, điều khoản và giải thích bắt buộc mỗi dòng (BR-15-06), biến của mẫu
     *     câu giải thích (MSG-15-05), rồi sinh lại `dmn_xml` và `dmn_hash`. Chỉ khi phiên bản ở `NHAP` theo bảng chức
     *     năng SCR-1502; dòng bị che theo hit policy FIRST được trả trong `warnings` (MSG-15-04), không chặn lưu.
     */
    put: operations['saveDecisionTable'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/decision-tables/{key}/dmn': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
        /** @description Khoá bảng quyết định `table_key`, ví dụ `xep_loai`, `tieu_chi_chung`. */
        key: components['parameters']['TableKey'];
      };
      cookie?: never;
    };
    /** Xuất bảng quyết định dạng DMN 1.4 XML */
    get: operations['exportDecisionTableDmn'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/evaluate': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Xem trước với hồ sơ mẫu
     * @description Tính thử một ca trong bộ vàng bằng cel-java và DMN trên máy chủ (SVC-07), trả điểm, mức và vết tính. Trình
     *     duyệt tự tính lại bằng cel-js để so sánh; khác nhau thì hiện MSG-15-07. Không ghi dữ liệu nghiệp vụ.
     */
    post: operations['evaluatePolicyVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/test-runs': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    /**
     * Lịch sử chạy kiểm thử của phiên bản
     * @description Mặc định 10 lần gần nhất theo SCR-1503 bảng trường dòng 6; lần mới nhất trước.
     */
    get: operations['listPolicyTestRuns'];
    put?: never;
    /**
     * Chạy bộ test case vàng
     * @description Chạy toàn bộ ca `DA_DUYET` của bộ vàng trên cel-java và cel-js, cùng kiểm thử thuộc tính. Phiên bản chuyển
     *     `NHAP` → `DANG_KIEM_THU`, rồi `DAT_KIEM_THU` khi 100% ca đạt và hai engine khớp, ngược lại về `NHAP`
     *     (BR-15-07). Khi có `compareWithVersion`, lần chạy kèm so sánh tác động với phiên bản đó (PC-06). Theo dõi
     *     bằng `Location` (`Job.type = TEST_RUN`); `resultLocation` trỏ tới lần chạy.
     */
    post: operations['runPolicyTests'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/test-runs/{runId}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
        /** @description Lần chạy kiểm thử (`policy.test_run.id`). */
        runId: components['parameters']['RunId'];
      };
      cookie?: never;
    };
    /**
     * Xem kết quả một lần chạy
     * @description Bốn thẻ số liệu, bảng ca, chi tiết lỗi và so sánh tác động (nếu có) của một lần chạy (SCR-1503).
     */
    get: operations['getPolicyTestRun'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/test-cases': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh sách ca kiểm thử vàng của gói
     * @description Kèm tóm tắt bộ test (mã bộ, số ca, người ký, ngày ký) cho dòng phụ của SCR-1503.
     */
    get: operations['listGoldenTestCases'];
    put?: never;
    /**
     * Thêm ca kiểm thử
     * @description Tạo ca mới ở trạng thái `NHAP` (chờ ký duyệt). Ca chỉ được tính vào tỷ lệ đạt sau khi người phụ trách nghiệp
     *     vụ ký duyệt (BR-15-08). Dữ liệu đầu vào là dữ liệu giả lập, không chứa dữ liệu cá nhân thật.
     */
    post: operations['createGoldenTestCase'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/test-cases/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: components['parameters']['IdPath'];
      };
      cookie?: never;
    };
    /** Xem một ca kiểm thử */
    get: operations['getGoldenTestCase'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/test-cases/{id}/approve': {
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
     * Ký duyệt ca kiểm thử vào bộ vàng
     * @description (suy luận) Người phụ trách nghiệp vụ ký số trên `input` và `expected` của ca, chuyển ca `NHAP` → `DA_DUYET`
     *     (BR-15-08). TL-02b chỉ nêu "ca mới cần người phụ trách nghiệp vụ ký duyệt"; vai trò thực hiện chưa có trong
     *     ma trận, hợp đồng tạm gán `decider` và cần xác nhận. Người ký khác người tạo ca.
     */
    post: operations['approveGoldenTestCase'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/submit': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Gửi phê duyệt phát hành
     * @description Chọn kỳ hiệu lực trong tương lai và phạm vi áp dụng, khởi động mẫu duyệt phát hành; phiên bản
     *     `DAT_KIEM_THU` → `CHO_PHE_DUYET`, phát EVT-21 tới người phê duyệt (BR-15-07, BR-15-10).
     */
    post: operations['submitPolicyPackVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/approve': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Ký số phê duyệt và phát hành
     * @description Người phê duyệt (AC-08, khác người soạn) ký số trên `content_hash` qua SVC-10 rồi khởi động saga phát hành
     *     của SVC-06: gắn `policy_binding` với kỳ hiệu lực, đồng bộ xuống các cell, nạp vào scoring-service, kích hoạt
     *     `wf_binding`. Xong saga thì phiên bản `DA_PHAT_HANH` và phát EVT-13 (giao diện hiện MSG-15-08); saga lỗi thì
     *     bù trừ và giữ `CHO_PHE_DUYET`. Thay cho `POST /policies/{id}/versions/{v}/publish` của TL-01 PL C (PC-02).
     *     Theo dõi bằng `Location` (`Job.type = POLICY_PUBLISH`).
     */
    post: operations['approvePolicyPackVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/reject': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Từ chối phát hành có ý kiến
     * @description Phiên bản `CHO_PHE_DUYET` → `NHAP`, bắt buộc ý kiến (QT-15 bước 9, bảng trạng thái).
     */
    post: operations['rejectPolicyPackVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/revoke': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Thu hồi phiên bản đã phát hành
     * @description Người phê duyệt thu hồi phiên bản `DA_PHAT_HANH` khi phát hiện sai sót trước kỳ hiệu lực; phiên bản chuyển
     *     `NGUNG`, phiên bản trước tiếp tục áp dụng. Không thu hồi được khi kỳ hiệu lực đã khoá (`is_frozen`); khi kỳ
     *     đã bắt đầu, phải phát hành phiên bản sửa (TL-02b QT-15 "Ngoại lệ và xử lý").
     */
    post: operations['revokePolicyPackVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/republish': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Phát hành lại sau khi saga lỗi
     * @description Chạy lại saga phát hành cho phiên bản đã ký nhưng saga bù trừ (vẫn `CHO_PHE_DUYET`, có chữ ký). Không cần
     *     ký lại nếu `content_hash` không đổi. Theo dõi bằng `Location` (`Job.type = POLICY_PUBLISH`).
     */
    post: operations['republishPolicyPackVersion'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/resolve': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Phân giải gói hiệu lực cho kỳ
     * @description Client nội bộ (SVC-07, SVC-06, MOD-04) tra phiên bản Policy Pack áp cho tổ hợp tenant, kỳ, luồng, khối và
     *     đơn vị theo `policy.policy_binding` (khớp cụ thể nhất trước). Trả mã băm và tham chiếu gói đã ký để bên gọi
     *     kiểm tra toàn vẹn trước khi dùng.
     */
    get: operations['resolvePolicyPack'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/prospect-criteria': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
      };
      cookie?: never;
    };
    /**
     * Tiêu chí phân nhóm triển vọng
     * @description Tiêu chí bốn nhóm triển vọng theo PL 5 trong phiên bản hiệu lực của gói (hoặc phiên bản chỉ định), kèm tham
     *     số `phan_nhom.mo_o_ky_quy` (BR-10-07) và mức xếp loại không được vào từng nhóm (BR-10-09). Mở từ ngăn
     *     "Xem tiêu chí PL 5" của SCR-1002.
     */
    get: operations['getProspectCriteria'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/workflow-bindings': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    /** Danh sách tham số luồng duyệt của phiên bản */
    get: operations['listWorkflowBindings'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/packs/{id}/versions/{v}/workflow-bindings/{key}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
        /** @description Loại quy trình của `wf_binding` (`process_kind`), ví dụ `DANH_GIA`, `KE_HOACH_QUY` (suy luận khoá). */
        key: components['parameters']['ProcessKind'];
      };
      cookie?: never;
    };
    /** Xem tham số luồng duyệt theo loại quy trình */
    get: operations['getWorkflowBinding'];
    /**
     * Lưu tham số luồng duyệt
     * @description Lưu `wf_binding` nháp thuộc phiên bản Policy Pack đang soạn, kiểm tra `params` theo `paramSchema` của mẫu,
     *     chạy linter và bộ test của mẫu với tham số này (BR-15-11, BR-15-12). Kỳ bắt đầu phải chưa khoá. Tham số
     *     được phát hành cùng Policy Pack. Lần lưu đầu dùng `If-Match: "0"` (suy luận).
     */
    put: operations['saveWorkflowBinding'];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/workflow-bindings/simulate': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Mô phỏng luồng với tham số
     * @description Chạy mẫu với tham số trên engine Flowable thử (không phải engine của cell) cho một số hồ sơ mẫu, trả xem
     *     trước các bước sinh ra, nhãn linter và kết quả test của mẫu. Không ghi `wf_binding`.
     */
    post: operations['simulateWorkflowBinding'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/workflow-templates': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Danh mục mẫu luồng để chọn khi cấu hình luồng duyệt
     * @description Danh mục mẫu luồng đã phát hành (`HIEU_LUC`) phù hợp loại quy trình, cho vùng 1 của SCR-1504. Chỉ đọc. Thư
     *     viện đầy đủ mọi trạng thái, trình soạn, kiểm thử, phê duyệt, triển khai mẫu (SCR-1505) và giám sát tiến
     *     trình (SCR-1506) thuộc workflow-service ở `/workflow/templates`, `/workflow/instances` (platform.yaml, PC-05).
     */
    get: operations['listPolicyWorkflowTemplates'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/policies/workflow-templates/{key}/versions/{v}': {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã mẫu luồng (`wf_template.template_code`), ví dụ `duyet-ho-so-danh-gia`. */
        key: components['parameters']['TemplateKey'];
        /** @description Số phiên bản mẫu (`wf_template.version_no`). */
        v: components['parameters']['TemplateVersion'];
      };
      cookie?: never;
    };
    /**
     * Tải khai báo tham số của mẫu luồng
     * @description Trả khai báo tham số (`paramSchema`, JSON Schema, cột `param_schema` của BS-01) để sinh biểu mẫu tham số
     *     trên SCR-1504, cùng các bước xem trước của mẫu. Không trả BPMN XML (chỉ có ở `/workflow/templates/{id}`
     *     cho phạm vi nền tảng).
     */
    get: operations['getPolicyWorkflowTemplateVersion'];
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
     * @description Trạng thái phiên bản Policy Pack (baseline mục 9.1; CHECK `ck_policy_pack_version__status`).
     * @enum {string}
     */
    PolicyPackVersionStatus:
      'NHAP' | 'DANG_KIEM_THU' | 'DAT_KIEM_THU' | 'CHO_PHE_DUYET' | 'DA_PHAT_HANH' | 'NGUNG';
    /**
     * @description Nguồn gói (`policy_pack.origin`).
     * @enum {string}
     */
    PolicyPackOrigin: 'GOC_TOAN_CUC' | 'TENANT';
    /**
     * @description Kiểu kết quả của quy tắc (`policy_rule.result_type`).
     * @enum {string}
     */
    PolicyRuleResultType: 'DECIMAL' | 'BOOLEAN' | 'STRING';
    /**
     * @description Hit policy DMN 1.4 được hỗ trợ (`decision_table.hit_policy`).
     * @enum {string}
     */
    DecisionHitPolicy: 'FIRST' | 'UNIQUE' | 'PRIORITY';
    /**
     * @description Trạng thái ca kiểm thử vàng; chỉ ca `DA_DUYET` được tính vào tỷ lệ đạt.
     * @enum {string}
     */
    GoldenTestCaseStatus: 'NHAP' | 'DA_DUYET' | 'NGUNG';
    /**
     * @description Nguồn ca kiểm thử (`golden_test_case.source`).
     * @enum {string}
     */
    GoldenTestCaseSource: 'KHACH_HANG' | 'BA' | 'VAN_BAN';
    /**
     * @description Kết luận lần chạy (`test_run.result`).
     * @enum {string}
     */
    PolicyTestRunResult: 'DAT' | 'KHONG_DAT' | 'DANG_CHAY';
    /**
     * @description Engine thực thi CEL (`test_run.engine`).
     * @enum {string}
     */
    PolicyEngine: 'CEL_JAVA' | 'CEL_JS';
    /**
     * @description Mã nhóm triển vọng (`appraisal.prospect_group.group_code`, PL 5).
     * @enum {string}
     */
    PolicyProspectGroupCode: 'NHOM_1' | 'NHOM_2' | 'NHOM_3' | 'NHOM_4';
    /**
     * @description Trạng thái bản ghi `wf_binding` (cột bổ sung của BS-01).
     * @enum {string}
     */
    WorkflowBindingStatus: 'NHAP' | 'HIEU_LUC' | 'NGUNG';
    /** @description Gói Policy Pack (`policy.policy_pack`) kèm tóm tắt phiên bản. */
    PolicyPack: {
      id: components['schemas']['Uuid'];
      /** @description Mã gói, ví dụ `khoi-dang/quy-dinh-moi`. */
      code: string;
      name: string;
      /** @description Nhóm khách hàng. */
      sector?: string;
      flow: components['schemas']['Flow'];
      origin: components['schemas']['PolicyPackOrigin'];
      /** @description Gói được kế thừa. */
      parentPackId?: components['schemas']['Uuid'] | null;
      /** @description Văn bản căn cứ. */
      legalBasis?: string;
      /** @description Tóm tắt các phiên bản gần nhất, mới nhất trước (suy luận số lượng do máy chủ chọn). */
      versions?: components['schemas']['PolicyPackVersionSummary'][];
    };
    PolicyPackList: {
      items: components['schemas']['PolicyPack'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /** @description Tóm tắt một phiên bản (`policy.policy_pack_version`). */
    PolicyPackVersionSummary: {
      id: components['schemas']['Uuid'];
      policyPackId: components['schemas']['Uuid'];
      version: string;
      status: components['schemas']['PolicyPackVersionStatus'];
      /** @description Ngày bắt đầu của kỳ hiệu lực. */
      effectiveFrom?: components['schemas']['Date'] | null;
      /** @description Tỷ lệ ca vàng đạt của lần chạy gần nhất, 1 nghĩa là 100%. */
      goldenPassRate?: number | null;
      publishedAt?: components['schemas']['DateTime'] | null;
      /** @description Ghi chú thay đổi. */
      changeNote?: string;
      createdAt?: components['schemas']['DateTime'];
      createdBy?: components['schemas']['Ref'];
      rowVersion: components['schemas']['RowVersion'];
    };
    PolicyPackVersionList: {
      items: components['schemas']['PolicyPackVersionSummary'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /** @description Chi tiết phiên bản Policy Pack. */
    PolicyPackVersion: components['schemas']['PolicyPackVersionSummary'] & {
      /** @description Mã gói, để hiển thị thanh gói. */
      packCode?: string;
      /** @description Tham số chung, quy tắc làm tròn, nhãn hiển thị, tham chiếu điều khoản (`manifest`). */
      manifest?: {
        [key: string]: unknown;
      };
      /** @description SHA-256 dạng chuẩn hoá của quy tắc, DMN và manifest, mã hex; có từ `DAT_KIEM_THU`. */
      contentHash?: string | null;
      lastTestRunId?: components['schemas']['Uuid'] | null;
      approvedBy?: components['schemas']['Ref'] | null;
      approvedAt?: components['schemas']['DateTime'] | null;
      signedAt?: components['schemas']['DateTime'] | null;
      /** @description Phiên bản gốc toàn cục nếu nhân bản từ kho gốc. */
      sourceVersionId?: components['schemas']['Uuid'] | null;
      /** @description Các gắn kỳ hiệu lực (`policy.policy_binding`). */
      bindings?: components['schemas']['PolicyBinding'][];
    };
    PolicyPackVersionCreate: {
      /** @description Phiên bản nguồn để nhân bản (thường là phiên bản đang phát hành). */
      baseVersion: string;
      /** @description Số phiên bản mới; bỏ trống thì máy chủ tăng số phụ (suy luận). */
      version?: string;
      changeNote: string;
    };
    /** @description Gắn phiên bản cho tổ hợp tenant, kỳ, luồng, khối, đơn vị (`policy.policy_binding`). */
    PolicyBinding: {
      id: components['schemas']['Uuid'];
      period: components['schemas']['PeriodRef'];
      flow: components['schemas']['Flow'];
      /** @description Khối đối tượng; null là mọi khối. */
      subjectGroupId?: components['schemas']['Uuid'] | null;
      /** @description Đơn vị phạm vi; null là toàn tenant. */
      orgUnitId?: components['schemas']['Uuid'] | null;
      /** @description true khi kỳ đã `DA_KHOA`; không đổi phiên bản được nữa. */
      isFrozen: boolean;
    };
    PolicyVersionDiff: {
      base: string;
      target: string;
      changes: {
        /**
         * @description Loại thành phần thay đổi (suy luận).
         * @enum {string}
         */
        kind: 'RULE' | 'PARAM' | 'DECISION_TABLE' | 'WORKFLOW_BINDING' | 'MANIFEST';
        /** @description `rule_key`, `table_key` hoặc `process_kind`. */
        key: string;
        /** @description Đường dẫn trường trong thành phần, dạng chấm. */
        path?: string;
        /**
         * @description Kiểu thay đổi (suy luận).
         * @enum {string}
         */
        change?: 'THEM' | 'SUA' | 'XOA';
        /** @description Giá trị cũ. */
        before?: unknown;
        /** @description Giá trị mới. */
        after?: unknown;
      }[];
    };
    /** @description Quy tắc làm tròn (`policy_rule.rounding`). */
    PolicyRounding: {
      digits: number;
      /**
       * @description Kiểu làm tròn, ví dụ `NUA_LEN` (TL-02b SCR-1501).
       * @example NUA_LEN
       */
      mode: string;
    };
    /** @description Quy tắc CEL (`policy.policy_rule`). */
    PolicyRule: {
      id: components['schemas']['Uuid'];
      ruleKey: string;
      /** @description Biểu thức CEL. */
      expression: string;
      resultType: components['schemas']['PolicyRuleResultType'];
      rounding?: components['schemas']['PolicyRounding'];
      /** @description Điều khoản căn cứ, ví dụ "PL 3 mục 2". */
      legalRef?: string;
      /** @description Thứ tự đánh giá. */
      evalOrder: number;
      /**
       * @description Tham số của quy tắc (`params_json`), khoá là tên biến CEL. Khoá đã biết (TL-02b SCR-1501):
       *     `cach_tinh_chat_luong` (`NAM_MUC_PL3`, `TRU_25_MOI_LAN`), `tru_tien_do` (0,00 đến 0,20),
       *     `loai_ngay` (`NGAY_LAM_VIEC`, `NGAY_LICH`), `ly_do_tra_lai`, `lam_tron`, `diem_toi_da` (quy tắc `nhom`),
       *     `nam.cach_tinh`, `phan_nhom.mo_o_ky_quy`.
       */
      params?: {
        [key: string]: unknown;
      };
      /** @description Tenant được điều chỉnh tham số của quy tắc này (BR-15-13). */
      overridable: boolean;
      rowVersion: components['schemas']['RowVersion'];
    };
    PolicyRuleUpdate: {
      expression: string;
      resultType: components['schemas']['PolicyRuleResultType'];
      rounding?: components['schemas']['PolicyRounding'];
      legalRef?: string;
      evalOrder?: number;
      params?: {
        [key: string]: unknown;
      };
      /** @description Chỉ có nghĩa với gói gốc toàn cục. */
      overridable?: boolean;
    };
    /** @description Quy tắc sau khi lưu, kèm trạng thái phiên bản. */
    PolicyRuleSaved: components['schemas']['PolicyRule'] & {
      versionStatus: components['schemas']['PolicyPackVersionStatus'];
      /** @description true khi phiên bản vừa từ `DAT_KIEM_THU` về `NHAP` (MSG-15-03). */
      statusReverted: boolean;
    };
    /** @description Khai báo cột đầu vào của bảng (suy luận từ bảng trường SCR-1502). */
    DecisionInput: {
      /** @description Tên biến ngữ cảnh, ví dụ `diem`, `ty_le_viec_vuot`. */
      name: string;
      label?: string;
      /**
       * @description Kiểu dữ liệu FEEL.
       * @enum {string}
       */
      type: 'number' | 'string' | 'boolean';
    };
    /** @description Một dòng của bảng DMN (`rules_json`). */
    DecisionRule: {
      /** @description Biểu thức FEEL đơn giản theo tên cột; ô trống (`-` hoặc bỏ khoá) nghĩa là bất kỳ. */
      inputs: {
        [key: string]: string;
      };
      ratingLevel: components['schemas']['RatingLevel'];
      /** @description Điều khoản căn cứ. */
      clause: string;
      /** @description Giải thích ngắn. */
      explanation: string;
    };
    /** @description Bảng quyết định DMN 1.4 (`policy.decision_table`). */
    DecisionTable: {
      id: components['schemas']['Uuid'];
      tableKey: string;
      hitPolicy: components['schemas']['DecisionHitPolicy'];
      legalRef?: string;
      inputs: components['schemas']['DecisionInput'][];
      rules: components['schemas']['DecisionRule'][];
      /** @description Mẫu câu giải thích (`explanation_templates_json`), khoá là mã mẫu; mỗi mẫu tối đa 1.000 ký tự. */
      explanationTemplates?: {
        [key: string]: string;
      };
      /** @description Băm định nghĩa DMN, mã hex. */
      dmnHash?: string;
      rowVersion: components['schemas']['RowVersion'];
    };
    DecisionTableUpdate: {
      hitPolicy: components['schemas']['DecisionHitPolicy'];
      legalRef?: string;
      inputs: components['schemas']['DecisionInput'][];
      rules: components['schemas']['DecisionRule'][];
      explanationTemplates?: {
        [key: string]: string;
      };
    };
    DecisionTableSaved: components['schemas']['DecisionTable'] & {
      /** @description Cảnh báo không chặn lưu, ví dụ dòng bị che theo hit policy FIRST (MSG-15-04). */
      warnings?: {
        /** @example MSG-15-04 */
        msg: string;
        message: string;
        row?: number;
        shadowedBy?: number;
      }[];
    };
    PolicyEvaluateRequest: {
      /** @description Mã ca trong bộ vàng (chỉ ca của bộ vàng, SCR-1501 dòng 10). */
      caseCode: string;
    };
    PolicyEvaluateResult: {
      caseCode: string;
      engine: components['schemas']['PolicyEngine'];
      /** @description Giá trị các quy tắc theo `rule_key`. */
      outputs: {
        [key: string]: unknown;
      };
      ratingLevel?: components['schemas']['RatingLevel'];
      /** @description Lời giải thích sinh từ mẫu câu. */
      explanation?: string;
      /** @description Vết tính theo thứ tự đánh giá. */
      trace?: {
        ruleKey: string;
        /** @description Giá trị tính được. */
        value?: unknown;
      }[];
    };
    /** @description Ca kiểm thử vàng (`policy.golden_test_case`), gắn với gói. */
    GoldenTestCase: {
      id: components['schemas']['Uuid'];
      policyPackId: components['schemas']['Uuid'];
      caseCode: string;
      description: string;
      /** @description Dữ liệu đầu vào giả lập (C1, không chứa dữ liệu cá nhân thật). */
      input: {
        [key: string]: unknown;
      };
      /** @description Kết quả mong đợi (điểm, mức, lý do hạ mức). */
      expected: {
        [key: string]: unknown;
      };
      legalRef?: string | null;
      source: components['schemas']['GoldenTestCaseSource'];
      status: components['schemas']['GoldenTestCaseStatus'];
      /** @description Ghi chú sai lệch khi kỳ vọng còn tranh cãi (TL-02b QT-15 ngoại lệ, suy luận). */
      note?: string;
      approvedBy?: components['schemas']['Ref'] | null;
      approvedAt?: components['schemas']['DateTime'] | null;
      /** @description Đã có chữ ký số trên `input` và `expected`. */
      signed?: boolean;
      createdBy?: components['schemas']['Ref'];
      rowVersion: components['schemas']['RowVersion'];
    };
    GoldenTestCaseCreate: {
      policyPackId: components['schemas']['Uuid'];
      caseCode: string;
      description: string;
      input: {
        [key: string]: unknown;
      };
      expected: {
        [key: string]: unknown;
      };
      legalRef?: string;
      source: components['schemas']['GoldenTestCaseSource'];
      note?: string;
    };
    /** @description Tóm tắt bộ test vàng của gói (SCR-1503 dòng 1; suy luận vì TL-03 không có bảng bộ test). */
    GoldenTestSuite: {
      /** @example bo-ca-mau-ban-to-chuc-v3 */
      code?: string;
      /** @description Số ca `DA_DUYET`. */
      total: number;
      /** @description Số ca chờ ký duyệt. */
      pending?: number;
      /** @description Tên cơ quan hoặc người ký duyệt bộ. */
      approvedBy?: string;
      approvedAt?: components['schemas']['DateTime'] | null;
    };
    GoldenTestCaseList: {
      suite?: components['schemas']['GoldenTestSuite'];
      items: components['schemas']['GoldenTestCase'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    PolicyTestRunCreate: {
      /** @description Phiên bản dùng để so sánh tác động (PC-06, suy luận). */
      compareWithVersion?: string;
      /**
       * @description Số bộ dữ liệu ngẫu nhiên cho kiểm thử thuộc tính (suy luận tham số).
       * @default 10000
       */
      propertyDatasets: number;
    };
    /** @description Tóm tắt lần chạy (`policy.test_run`). */
    PolicyTestRunSummary: {
      id: components['schemas']['Uuid'];
      /** @description Số thứ tự lần chạy trong gói, ví dụ #14 (suy luận). */
      runNo?: number;
      policyPackVersionId: components['schemas']['Uuid'];
      version: string;
      result: components['schemas']['PolicyTestRunResult'];
      engines?: components['schemas']['PolicyEngine'][];
      total: number;
      passed: number;
      failed: number;
      /** @description cel-java và cel-js cho cùng kết quả ở mọi ca. */
      engineMatch: boolean;
      /** @description Số ca hai engine khớp (thẻ "Khớp hai engine", suy luận). */
      engineMatchedCount?: number;
      startedAt: components['schemas']['DateTime'];
      finishedAt?: components['schemas']['DateTime'] | null;
      durationMs?: number | null;
      createdBy?: components['schemas']['Ref'];
    };
    PolicyTestRunList: {
      items: components['schemas']['PolicyTestRunSummary'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /** @description Kết quả một ca (`policy.test_case_result`). */
    PolicyTestCaseResult: {
      goldenTestCaseId: components['schemas']['Uuid'];
      caseCode: string;
      description?: string;
      expected?: {
        [key: string]: unknown;
      };
      /** @description Kết quả cel-java. */
      actual?: {
        [key: string]: unknown;
      };
      /** @description Kết quả cel-js khi khác cel-java (MSG-15-07). */
      actualJs?: {
        [key: string]: unknown;
      } | null;
      passed: boolean;
      engineMatch?: boolean;
      /** @description Khác biệt so với mong đợi. */
      diff?: {
        [key: string]: unknown;
      } | null;
    };
    /** @description Kết quả kiểm thử thuộc tính (`property_result_json`). */
    PolicyPropertyResult: {
      datasets: number;
      /**
       * @example [
       *       "0 <= diem && diem <= 100",
       *       "C >= 0"
       *     ]
       */
      invariants?: string[];
      violations: number;
    };
    /** @description So sánh tác động với một phiên bản khác trên bộ ca (PC-06, suy luận). */
    PolicyImpact: {
      baseVersion: string;
      /** @description Số ca có điểm khác. */
      changedScoreCount?: number;
      /** @description Số ca có mức xếp loại khác. */
      changedRatingCount: number;
      /** @description Ghi chú tính lại kỳ đã khoá, ví dụ "Không áp dụng". */
      lockedPeriodNote?: string;
    };
    /** @description Kết quả đầy đủ một lần chạy. */
    PolicyTestRun: components['schemas']['PolicyTestRunSummary'] & {
      propertyResult?: components['schemas']['PolicyPropertyResult'];
      cases?: components['schemas']['PolicyTestCaseResult'][];
      /** @description Khác biệt cấu hình so với lần chạy trước (thẻ chi tiết lỗi SCR-1503). */
      configDiff?: components['schemas']['PolicyVersionDiff'] | null;
      impact?: components['schemas']['PolicyImpact'] | null;
    };
    PolicyPackSubmit: {
      /** @description Kỳ hiệu lực trong tương lai, chưa khoá (BR-15-10). */
      effectivePeriodId: components['schemas']['Uuid'];
      /** @description Phạm vi áp dụng; mỗi phần tử sinh một `policy_binding` khi phát hành. */
      scopes: {
        flow: components['schemas']['Flow'];
        subjectGroupId?: components['schemas']['Uuid'] | null;
        orgUnitId?: components['schemas']['Uuid'] | null;
      }[];
      note?: string;
    };
    PolicyPackApprove: {
      comment?: string;
      /** @description Phiên ký số đã mở ở SVC-10 (`/esign`), nếu ký từ xa cần xác nhận trên thiết bị (suy luận). */
      esignSessionId?: components['schemas']['Uuid'];
    };
    PolicyDecisionReason: {
      /** @description Ý kiến bắt buộc. */
      reason: string;
    };
    PolicyDecisionComment: {
      comment?: string;
    };
    ResolvedPolicyPack: {
      policyPackId: components['schemas']['Uuid'];
      code: string;
      versionId: components['schemas']['Uuid'];
      version: string;
      /** @description SHA-256 đã ký, mã hex; bên gọi tính lại và so trước khi dùng. */
      contentHash: string;
      /** @description Khoá gói đã ký trên Ceph của cell. */
      bundleObjectKey?: string;
      bindingId: components['schemas']['Uuid'];
      /** @description Kỳ đã khoá, phiên bản không đổi được nữa. */
      frozen: boolean;
    };
    ProspectCriteria: {
      policyPackId: components['schemas']['Uuid'];
      version: string;
      /** @description Tham số `phan_nhom.mo_o_ky_quy` (BR-10-07). */
      openInQuarter: boolean;
      legalRef?: string;
      groups: {
        groupCode: components['schemas']['PolicyProspectGroupCode'];
        name: string;
        /** @description Tiêu chí theo PL 5. */
        criteria: string;
        /** @description Mức xếp loại năm không được đưa vào nhóm (BR-10-09). */
        excludedRatingLevels?: components['schemas']['RatingLevel'][];
        /** @description Người có cờ bố trí lại không được vào nhóm (BR-10-09). */
        excludeReassignmentFlag?: boolean;
      }[];
    };
    /** @description Kết quả linter mẫu luồng (bốn quy tắc chống mạng nhện của TL-01 và giới hạn tải của BS-01). */
    WorkflowLintResult: {
      passed: boolean;
      issues: {
        /** @description Mã quy tắc linter, ví dụ `max-call-activity-depth`. */
        rule: string;
        /** @description Phần tử BPMN vi phạm. */
        elementId?: string;
        /** @enum {string} */
        severity?: 'error' | 'warning';
        message: string;
      }[];
    };
    WorkflowTestSummary: {
      passed: number;
      total: number;
    };
    /** @description Phạm vi áp dụng (`scope_json` và các cột khoá của `wf_binding`). */
    WorkflowBindingScope: {
      flow?: components['schemas']['Flow'];
      subjectGroupId?: components['schemas']['Uuid'] | null;
      /** @description Loại kỳ, ví dụ `QUY`, `NAM`. */
      periodType?: string;
      /** @description Kỳ bắt đầu, phải chưa khoá. */
      fromPeriodId: components['schemas']['Uuid'];
    };
    /** @description Tham số luồng duyệt theo mẫu (`flowable.wf_binding`). */
    WorkflowBinding: {
      id: components['schemas']['Uuid'];
      processKind: string;
      templateKey: string;
      templateVersionNo: number;
      scope: components['schemas']['WorkflowBindingScope'];
      /** @description Tham số truyền vào tiến trình (`params_json`), hợp lệ theo `paramSchema` của mẫu. */
      params: {
        [key: string]: unknown;
      };
      status: components['schemas']['WorkflowBindingStatus'];
      /** @description Phiên bản bản ghi (BS-01). */
      versionNo?: number;
      lint?: components['schemas']['WorkflowLintResult'];
      templateTests?: components['schemas']['WorkflowTestSummary'];
      rowVersion: components['schemas']['RowVersion'];
    };
    WorkflowBindingUpdate: {
      templateKey: string;
      templateVersionNo: number;
      scope: components['schemas']['WorkflowBindingScope'];
      params: {
        [key: string]: unknown;
      };
    };
    WorkflowSimulateRequest: {
      templateKey: string;
      templateVersionNo: number;
      params: {
        [key: string]: unknown;
      };
      /** @description Mã hồ sơ mẫu dùng để chạy thử; bỏ trống thì dùng bộ mẫu mặc định của mẫu luồng (suy luận). */
      samples?: string[];
    };
    WorkflowStepPreview: {
      stepKey: string;
      name: string;
      /** @description Vai trò baseline mục 10 xử lý bước. */
      role?: string;
      signRequired?: boolean;
      batchSign?: boolean;
      /** @description Bước có điều kiện bỏ qua. */
      skippable?: boolean;
    };
    WorkflowSimulateResult: {
      steps: components['schemas']['WorkflowStepPreview'][];
      lint: components['schemas']['WorkflowLintResult'];
      templateTests?: components['schemas']['WorkflowTestSummary'];
      sampleRuns?: {
        sample: string;
        skippedSteps?: string[];
        endState: string;
      }[];
    };
    /** @description Một mẫu luồng đã phát hành trong danh mục chọn mẫu của SCR-1504 (`flowable.wf_template`). */
    PolicyWorkflowTemplateSummary: {
      /** @description `template_code`, ví dụ `duyet-ho-so-danh-gia`. */
      key: string;
      /** @description Tên hiển thị (suy luận; TL-03 chưa có cột). */
      name?: string;
      versionNo: number;
      /**
       * @description Trạng thái mẫu; danh mục này chỉ trả `HIEU_LUC`.
       * @enum {string}
       */
      status: 'HIEU_LUC';
      /** @description Loại quy trình phục vụ (suy luận). */
      processKind?: string;
      /** @description Số test của mẫu. */
      testCount?: number;
    };
    PolicyWorkflowTemplateList: {
      items: components['schemas']['PolicyWorkflowTemplateSummary'][];
      nextCursor: components['schemas']['NextCursor'];
    };
    /** @description Khai báo tham số và bước xem trước của một phiên bản mẫu luồng, dùng cho SCR-1504. */
    PolicyWorkflowTemplateVersion: components['schemas']['PolicyWorkflowTemplateSummary'] & {
      /** @description Khai báo tham số dạng JSON Schema (`param_schema`, BS-01), sinh biểu mẫu SCR-1504. */
      paramSchema: {
        [key: string]: unknown;
      };
      previewSteps?: components['schemas']['WorkflowStepPreview'][];
    };
    /**
     * @description Luồng đánh giá (baseline mục 9.1).
     * @enum {string}
     */
    Flow: 'DANG' | 'NHA_NUOC';
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
     * @description Mức xếp loại (baseline mục 9.1).
     * @enum {string}
     */
    RatingLevel: 'HTXSNV' | 'HTTNV' | 'HTNV' | 'KHTNV';
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
    /** @description Không tìm thấy, hoặc đối tượng nằm ngoài phạm vi người gọi (không lộ sự tồn tại). */
    NotFound: {
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
    /** @description Dữ liệu không hợp lệ theo quy tắc nghiệp vụ (BR), thiếu lý do bắt buộc, hoặc `Idempotency-Key` dùng lại với nội dung khác. Lỗi theo trường nằm trong `errors[]`. */
    UnprocessableEntity: {
      headers: {
        [name: string]: unknown;
      };
      content: {
        'application/problem+json': components['schemas']['ProblemDetails'];
      };
    };
  };
  parameters: {
    /** @description Gói Policy Pack (`policy.policy_pack.id`). */
    PackId: components['schemas']['Uuid'];
    /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
    PackVersion: string;
    /** @description Khoá quy tắc `rule_key`, ví dụ `kpi_nhiem_vu`, `A`, `muc_cuoi`, `nguoi_dung_dau`, `nhom`. */
    RuleKey: string;
    /** @description Khoá bảng quyết định `table_key`, ví dụ `xep_loai`, `tieu_chi_chung`. */
    TableKey: string;
    /** @description Lần chạy kiểm thử (`policy.test_run.id`). */
    RunId: components['schemas']['Uuid'];
    /** @description Loại quy trình của `wf_binding` (`process_kind`), ví dụ `DANH_GIA`, `KE_HOACH_QUY` (suy luận khoá). */
    ProcessKind: string;
    /** @description Mã mẫu luồng (`wf_template.template_code`), ví dụ `duyet-ho-so-danh-gia`. */
    TemplateKey: string;
    /** @description Số phiên bản mẫu (`wf_template.version_no`). */
    TemplateVersion: number;
    /** @description Từ khoá tìm kiếm, không phân biệt dấu. */
    Q: string;
    /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
    Limit: number;
    /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
    Cursor: string;
    /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
    IdempotencyKey: string;
    /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
    IfMatch: string;
    IdPath: components['schemas']['Uuid'];
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
  listPolicyPacks: {
    parameters: {
      query?: {
        /** @description Lọc theo luồng đánh giá. */
        flow?: components['schemas']['Flow'];
        /** @description Lọc theo nguồn gói. */
        origin?: components['schemas']['PolicyPackOrigin'];
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
      /** @description Trang danh sách gói. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PolicyPackList'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  getPolicyPack: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Gói và tóm tắt phiên bản. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PolicyPack'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listPolicyPackVersions: {
    parameters: {
      query?: {
        /** @description Lọc theo trạng thái phiên bản. */
        status?: components['schemas']['PolicyPackVersionStatus'];
        /** @description Số dòng mỗi trang, tối đa 200 (baseline mục 13). Mặc định 20 theo TL-02. */
        limit?: components['parameters']['Limit'];
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Trang danh sách phiên bản, mới nhất trước. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PolicyPackVersionList'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  createPolicyPackVersion: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['PolicyPackVersionCreate'];
      };
    };
    responses: {
      /** @description Đã tạo phiên bản nháp. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PolicyPackVersion'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      409: components['responses']['Conflict'];
      /**
       * @description Dữ liệu không hợp lệ. Mã lỗi: `POLICY-422-VERSION-EXISTS` (số phiên bản đã có),
       *     `COMMON-422-VALIDATION` (thiếu ghi chú thay đổi, sai định dạng semver).
       */
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
  getPolicyPackVersion: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Chi tiết phiên bản. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PolicyPackVersion'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  diffPolicyPackVersions: {
    parameters: {
      query: {
        /** @description Phiên bản gốc để so sánh (semver, xem PC-03). */
        base: string;
      };
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Danh sách khác biệt. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PolicyVersionDiff'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listPolicyRules: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Danh sách quy tắc. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['PolicyRule'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  getPolicyRule: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
        /** @description Khoá quy tắc `rule_key`, ví dụ `kpi_nhiem_vu`, `A`, `muc_cuoi`, `nguoi_dung_dau`, `nhom`. */
        key: components['parameters']['RuleKey'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Quy tắc. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PolicyRule'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  savePolicyRule: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
        /** @description Khoá quy tắc `rule_key`, ví dụ `kpi_nhiem_vu`, `A`, `muc_cuoi`, `nguoi_dung_dau`, `nhom`. */
        key: components['parameters']['RuleKey'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['PolicyRuleUpdate'];
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
          'application/json': components['schemas']['PolicyRuleSaved'];
        };
      };
      401: components['responses']['Unauthorized'];
      /**
       * @description Không có quyền. Mã lỗi: `COMMON-403-OUT-OF-SCOPE`, `POLICY-403-NOT-OVERRIDABLE` (tenant sửa quy tắc gói gốc
       *     không đánh dấu cho phép điều chỉnh, BR-15-13).
       */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /**
       * @description Xung đột. Mã lỗi: `POLICY-409-VERSION-CONFLICT`, `POLICY-409-VERSION-FROZEN` (phiên bản từ
       *     `CHO_PHE_DUYET` trở đi hoặc `DANG_KIEM_THU`).
       */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /**
       * @description Không hợp lệ. Mã lỗi: `POLICY-422-CEL-TYPE` (MSG-15-01, kèm `errors[]` theo khối),
       *     `POLICY-422-GROUP-SUM` (MSG-15-02), `COMMON-422-VALIDATION` (tham số ngoài miền, ví dụ mức trừ tiến độ
       *     ngoài 0,00 đến 0,20).
       */
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
  listDecisionTables: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Danh sách bảng quyết định. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['DecisionTable'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  getDecisionTable: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
        /** @description Khoá bảng quyết định `table_key`, ví dụ `xep_loai`, `tieu_chi_chung`. */
        key: components['parameters']['TableKey'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Bảng quyết định dạng JSON (định nghĩa DMN XML lấy ở `/dmn`). */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['DecisionTable'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  saveDecisionTable: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
        /** @description Khoá bảng quyết định `table_key`, ví dụ `xep_loai`, `tieu_chi_chung`. */
        key: components['parameters']['TableKey'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['DecisionTableUpdate'];
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
          'application/json': components['schemas']['DecisionTableSaved'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Xung đột. Mã lỗi: `POLICY-409-VERSION-CONFLICT`, `POLICY-409-VERSION-FROZEN` (phiên bản không ở `NHAP`). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /**
       * @description Không hợp lệ. Mã lỗi: `POLICY-422-FEEL` (cú pháp FEEL hoặc kiểu không khớp khai báo),
       *     `POLICY-422-TEMPLATE-VAR` (MSG-15-05), `COMMON-422-VALIDATION` (thiếu điều khoản, giải thích, độ dài).
       */
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
  exportDecisionTableDmn: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
        /** @description Khoá bảng quyết định `table_key`, ví dụ `xep_loai`, `tieu_chi_chung`. */
        key: components['parameters']['TableKey'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Tệp DMN 1.4 (`dmn_xml`). */
      200: {
        headers: {
          /** @description Tên tệp tải về, ví dụ `attachment; filename="xep_loai-2.1.0.dmn"`. */
          'Content-Disposition'?: string;
          [name: string]: unknown;
        };
        content: {
          'application/xml': string;
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  evaluatePolicyVersion: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
      };
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['PolicyEvaluateRequest'];
      };
    };
    responses: {
      /** @description Kết quả tính thử. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PolicyEvaluateResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Không hợp lệ. Mã lỗi: `POLICY-422-CASE-NOT-GOLDEN` (ca không thuộc bộ vàng), `POLICY-422-CEL-TYPE`. */
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
  listPolicyTestRuns: {
    parameters: {
      query?: {
        /** @description Số lần chạy mỗi trang, tối đa 200. */
        limit?: number;
        /** @description Con trỏ mờ lấy từ `nextCursor` của trang trước. Không tự dựng giá trị. */
        cursor?: components['parameters']['Cursor'];
      };
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Trang lịch sử chạy. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PolicyTestRunList'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  runPolicyTests: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    requestBody?: {
      content: {
        'application/json': components['schemas']['PolicyTestRunCreate'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /**
       * @description Xung đột. Mã lỗi: `POLICY-409-VERSION-CONFLICT`, `POLICY-409-TEST-RUNNING` (đang có lần chạy),
       *     `POLICY-409-VERSION-FROZEN` (phiên bản đã `CHO_PHE_DUYET` trở đi).
       */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /** @description Không hợp lệ. Mã lỗi: `POLICY-422-CEL-TYPE` (còn biểu thức sai kiểu, điều kiện chuyển `DANG_KIEM_THU`). */
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
  getPolicyTestRun: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
        /** @description Lần chạy kiểm thử (`policy.test_run.id`). */
        runId: components['parameters']['RunId'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Kết quả lần chạy. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PolicyTestRun'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listGoldenTestCases: {
    parameters: {
      query: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`); ca vàng gắn với gói, không gắn với phiên bản. */
        pack: components['schemas']['Uuid'];
        /** @description Lọc theo trạng thái ca. */
        status?: components['schemas']['GoldenTestCaseStatus'];
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
      /** @description Trang danh sách ca. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['GoldenTestCaseList'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  createGoldenTestCase: {
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
        'application/json': components['schemas']['GoldenTestCaseCreate'];
      };
    };
    responses: {
      /** @description Đã tạo ca kiểm thử. */
      201: {
        headers: {
          ETag: components['headers']['ETag'];
          Location: components['headers']['Location'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['GoldenTestCase'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Không hợp lệ. Mã lỗi: `POLICY-422-CASE-CODE-EXISTS`, `COMMON-422-VALIDATION`. */
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
  getGoldenTestCase: {
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
      /** @description Ca kiểm thử. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['GoldenTestCase'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  approveGoldenTestCase: {
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
        'application/json': components['schemas']['PolicyDecisionComment'];
      };
    };
    responses: {
      /** @description Ca đã vào bộ vàng. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['GoldenTestCase'];
        };
      };
      401: components['responses']['Unauthorized'];
      /** @description Không có quyền. Mã lỗi: `POLICY-403-SAME-AUTHOR` (người ký trùng người tạo ca). */
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
  submitPolicyPackVersion: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['PolicyPackSubmit'];
      };
    };
    responses: {
      /** @description Đã gửi phê duyệt. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PolicyPackVersion'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /**
       * @description Xung đột. Mã lỗi: `POLICY-409-VERSION-CONFLICT`, `POLICY-409-NOT-TESTED` (chưa `DAT_KIEM_THU`, MSG-15-06),
       *     `POLICY-409-PERIOD-LOCKED` (kỳ hiệu lực đã khoá hoặc không ở tương lai, BR-15-10).
       */
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
  approvePolicyPackVersion: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    requestBody?: {
      content: {
        'application/json': components['schemas']['PolicyPackApprove'];
      };
    };
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      /** @description Không có quyền. Mã lỗi: `POLICY-403-SAME-AUTHOR` (người phê duyệt trùng người soạn, BR-15-09). */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      404: components['responses']['NotFound'];
      /** @description Xung đột. Mã lỗi: `POLICY-409-VERSION-CONFLICT`, `POLICY-409-NOT-PENDING` (phiên bản không ở `CHO_PHE_DUYET`). */
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
  rejectPolicyPackVersion: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['PolicyDecisionReason'];
      };
    };
    responses: {
      /** @description Đã từ chối, phiên bản về nháp. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PolicyPackVersion'];
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
  revokePolicyPackVersion: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['PolicyDecisionReason'];
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
          'application/json': components['schemas']['PolicyPackVersion'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Xung đột. Mã lỗi: `POLICY-409-VERSION-CONFLICT`, `POLICY-409-PERIOD-STARTED` (kỳ hiệu lực đã bắt đầu), `POLICY-409-PERIOD-LOCKED`. */
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
  republishPolicyPackVersion: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      202: components['responses']['Accepted'];
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Xung đột. Mã lỗi: `POLICY-409-VERSION-CONFLICT`, `POLICY-409-NOT-SIGNED` (chưa có chữ ký hợp lệ trên nội dung hiện tại). */
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
  resolvePolicyPack: {
    parameters: {
      query: {
        /** @description Tenant (`tenant_id`). */
        tenant: components['schemas']['Uuid'];
        /** @description Kỳ đánh giá (`config.period.id`). */
        period: components['schemas']['Uuid'];
        /** @description Luồng đánh giá. */
        flow: components['schemas']['Flow'];
        /** @description Khối đối tượng (suy luận; cột `subject_group_id` của `policy_binding`). */
        subjectGroup?: components['schemas']['Uuid'];
        /** @description Đơn vị (`org.org_unit.id`); máy chủ kiểm phạm vi theo ABAC. */
        unit?: components['parameters']['UnitId'];
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Phiên bản hiệu lực. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ResolvedPolicyPack'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Không có gói nào gắn cho tổ hợp này. Mã lỗi: `POLICY-404-NO-BINDING`. */
      404: {
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
  getProspectCriteria: {
    parameters: {
      query?: {
        /** @description Phiên bản cụ thể; bỏ trống là phiên bản đang phát hành. */
        version?: string;
      };
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Tiêu chí nhóm. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['ProspectCriteria'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  listWorkflowBindings: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Các bản ghi `wf_binding` thuộc phiên bản. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': {
            items: components['schemas']['WorkflowBinding'][];
          };
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  getWorkflowBinding: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
        /** @description Loại quy trình của `wf_binding` (`process_kind`), ví dụ `DANH_GIA`, `KE_HOACH_QUY` (suy luận khoá). */
        key: components['parameters']['ProcessKind'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Bản ghi `wf_binding`. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowBinding'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
  saveWorkflowBinding: {
    parameters: {
      query?: never;
      header: {
        /** @description Khoá chống ghi trùng cho một ý định ghi (không phải một lần gọi HTTP): lần thử lại phải dùng lại khoá cũ. UUID do client sinh, nên dùng UUIDv7. Máy chủ giữ khoá 48 giờ trong phạm vi (tenant, người gọi) (TL-03 `idempotency_key`); cùng khoá cùng nội dung trả lại phản hồi lần đầu, cùng khoá khác nội dung trả 422 mã `COMMON-422-IDEMPOTENCY-MISMATCH`. */
        'Idempotency-Key': components['parameters']['IdempotencyKey'];
        /** @description ETag nhận được ở lần đọc trước (giá trị `row_version` đặt trong dấu nháy kép, ví dụ `"7"`). Lệch phiên bản trả 409 mã `<MODULE>-409-VERSION-CONFLICT`; thiếu header trả 428. */
        'If-Match': components['parameters']['IfMatch'];
      };
      path: {
        /** @description Gói Policy Pack (`policy.policy_pack.id`). */
        id: components['parameters']['PackId'];
        /** @description Số phiên bản semver ba phần của gói (`policy_pack_version.version`, PC-03). */
        v: components['parameters']['PackVersion'];
        /** @description Loại quy trình của `wf_binding` (`process_kind`), ví dụ `DANH_GIA`, `KE_HOACH_QUY` (suy luận khoá). */
        key: components['parameters']['ProcessKind'];
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['WorkflowBindingUpdate'];
      };
    };
    responses: {
      /** @description Đã lưu, kèm kết quả linter và test mẫu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowBinding'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      /** @description Xung đột. Mã lỗi: `POLICY-409-VERSION-CONFLICT`, `POLICY-409-VERSION-FROZEN`, `POLICY-409-PERIOD-LOCKED` (kỳ bắt đầu đã khoá). */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ProblemDetails'];
        };
      };
      /**
       * @description Không hợp lệ. Mã lỗi: `POLICY-422-WF-PARAMS` (MSG-15-09: tham số sai khai báo mẫu, linter hoặc test mẫu
       *     không đạt), `POLICY-422-CEL-TYPE` (điều kiện bỏ qua cấp), `POLICY-422-TEMPLATE-NOT-ACTIVE` (mẫu chưa
       *     `HIEU_LUC`).
       */
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
  simulateWorkflowBinding: {
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
        'application/json': components['schemas']['WorkflowSimulateRequest'];
      };
    };
    responses: {
      /** @description Kết quả mô phỏng. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['WorkflowSimulateResult'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      /** @description Không hợp lệ. Mã lỗi: `POLICY-422-WF-PARAMS` (MSG-15-09). */
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
  listPolicyWorkflowTemplates: {
    parameters: {
      query?: {
        /** @description Lọc theo loại quy trình mà mẫu phục vụ (suy luận). */
        processKind?: string;
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
      /** @description Trang danh mục mẫu. */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PolicyWorkflowTemplateList'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      default: components['responses']['Problem'];
    };
  };
  getPolicyWorkflowTemplateVersion: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        /** @description Mã mẫu luồng (`wf_template.template_code`), ví dụ `duyet-ho-so-danh-gia`. */
        key: components['parameters']['TemplateKey'];
        /** @description Số phiên bản mẫu (`wf_template.version_no`). */
        v: components['parameters']['TemplateVersion'];
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Phiên bản mẫu. */
      200: {
        headers: {
          ETag: components['headers']['ETag'];
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['PolicyWorkflowTemplateVersion'];
        };
      };
      401: components['responses']['Unauthorized'];
      403: components['responses']['Forbidden'];
      404: components['responses']['NotFound'];
      default: components['responses']['Problem'];
    };
  };
}
