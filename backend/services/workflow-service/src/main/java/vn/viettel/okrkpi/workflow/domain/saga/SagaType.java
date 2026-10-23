package vn.viettel.okrkpi.workflow.domain.saga;

/** Loại saga điều phối tầng 3 (TL-03 {@code flowable.saga_instance.saga_type}). */
public enum SagaType {
  CHOT_KY,
  MO_LAI_HAU_KIEM,
  TOT_NGHIEP_TENANT,
  DONG_GOI_HO_SO,
  /** Phát hành mẫu luồng cuốn chiếu lên engine từng cell (BS-01 SCR-1505, DV-06-09). */
  PHAT_HANH_MAU_LUONG
}
