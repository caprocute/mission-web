-- Migration mẫu có bảng không chứa cột tenant_id để kiểm thử âm danh sách ngoại lệ lớp 1 (NEN-034 mục 3).
CREATE TABLE bad_no_tenant.global_dict (
    code varchar(32) NOT NULL,
    label varchar(100) NOT NULL,
    CONSTRAINT pk_global_dict PRIMARY KEY (code)
);
