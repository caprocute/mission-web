package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.viettel.okrkpi.config.application.port.PeriodPartitions;
import vn.viettel.okrkpi.shared.datasource.ModuleName;

/**
 * Tạo phân vùng theo kỳ cho tám schema của mission-core (CORE-08-02, NEN-033, TL-03 "Phân vùng").
 *
 * <p>Mỗi schema có hàm {@code <schema>.fn_create_period_partitions} (SECURITY DEFINER của {@code
 * mig_<schema>}, {@code svc_<schema>} chỉ có EXECUTE), nên lời gọi đi qua đúng DataSource của
 * mô-đun và ứng dụng vẫn không có quyền DDL. Bảng cần tạo được dò trong catalog: bảng cha phân vùng
 * LIST theo {@code period_bucket}, nên bảng theo kỳ thêm sau này không phải khai báo lại. Hàm tạo
 * phân vùng con HASH ở mô hình pool và bật RLS trên phân vùng mới; gọi lại không lỗi. Kỳ đã chuẩn
 * bị được nhớ trong pod để lần tạo kỳ sau cùng quý không dò lại.
 */
public final class ModulePeriodPartitions implements PeriodPartitions {

  private static final Logger log = LoggerFactory.getLogger(ModulePeriodPartitions.class);

  static final String PERIOD_TABLES =
      "SELECT c.oid::regclass::text FROM pg_partitioned_table pt"
          + " JOIN pg_class c ON c.oid = pt.partrelid"
          + " JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = pt.partattrs[0]"
          + " WHERE n.nspname = ? AND NOT c.relispartition AND pt.partstrat = 'l'"
          + " AND pt.partnatts = 1 AND a.attname = 'period_bucket'"
          + " AND to_regprocedure(quote_ident(n.nspname)"
          + " || '.fn_create_period_partitions(regclass,integer,integer)') IS NOT NULL"
          + " AND NOT EXISTS (SELECT 1 FROM pg_class p WHERE p.relnamespace = c.relnamespace"
          + " AND p.relname = c.relname || '_p' || ?::int)"
          + " ORDER BY 1";

  private final Map<ModuleName, ? extends DataSource> dataSources;

  public ModulePeriodPartitions(Map<ModuleName, ? extends DataSource> dataSources) {
    this.dataSources = Map.copyOf(Objects.requireNonNull(dataSources, "dataSources"));
  }

  @Override
  public void ensure(int bucket) {
    int created = 0;
    for (ModuleName module : ModuleName.values()) {
      DataSource ds = dataSources.get(module);
      if (ds != null) {
        created += ensure(new JdbcTemplate(ds), module.schema(), bucket);
      }
    }
    if (created > 0) {
      log.info("Đã tạo {} phân vùng cho kỳ {}", created, bucket);
    }
  }

  private static int ensure(JdbcTemplate jdbc, String schema, int bucket) {
    List<String> tables = jdbc.queryForList(PERIOD_TABLES, String.class, schema, bucket);
    String call = "SELECT " + schema + ".fn_create_period_partitions(?::regclass, ?)";
    int created = 0;
    for (String table : tables) {
      created += call(jdbc, call, table, bucket);
    }
    return created;
  }

  /** Pod khác tạo cùng phân vùng cùng lúc thì lần đầu lỗi trùng tên; lần gọi lại thấy đã có. */
  private static int call(JdbcTemplate jdbc, String call, String table, int bucket) {
    try {
      return Objects.requireNonNullElse(jdbc.queryForObject(call, Integer.class, table, bucket), 0);
    } catch (DataAccessException first) {
      log.debug("Tạo phân vùng {} kỳ {} lỗi, gọi lại một lần", table, bucket, first);
      return Objects.requireNonNullElse(jdbc.queryForObject(call, Integer.class, table, bucket), 0);
    }
  }
}
