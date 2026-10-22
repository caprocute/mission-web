package vn.viettel.okrkpi.docgen.infrastructure.render;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.docgen.domain.render.Template;
import vn.viettel.okrkpi.docgen.domain.render.TemplateRepository;
import vn.viettel.okrkpi.docgen.domain.render.TemplateStatus;

/** {@link TemplateRepository} trên JDBC ({@code docgen.template}). */
public class JdbcTemplateRepository implements TemplateRepository {

  private final JdbcClient jdbc;

  public JdbcTemplateRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Template> findById(UUID id) {
    return jdbc.sql("SELECT * FROM docgen.template WHERE id = ?")
        .param(id)
        .query((rs, i) -> template(rs))
        .optional();
  }

  @Override
  public List<Template> findByFormCode(String formCode) {
    return jdbc.sql("SELECT * FROM docgen.template WHERE form_code = ? ORDER BY version_no DESC")
        .param(formCode)
        .query((rs, i) -> template(rs))
        .list();
  }

  @Override
  public void insert(Template t, UUID actor) {
    jdbc.sql(
            """
            INSERT INTO docgen.template
              (id, tenant_id, form_code, version_no, subject_group_codes, object_key, content_sha256,
               status, created_by, updated_by)
            VALUES (?, ?, ?, ?, ?::varchar(16)[], ?, ?, ?, ?, ?)
            """)
        .param(t.id())
        .param(t.tenantId())
        .param(t.formCode())
        .param(t.versionNo())
        .param(
            t.subjectGroupCodes().isEmpty()
                ? null
                : "{" + String.join(",", t.subjectGroupCodes()) + "}")
        .param(t.objectKey())
        .param(t.contentSha256())
        .param(t.status().name())
        .param(actor)
        .param(actor)
        .update();
  }

  private static Template template(ResultSet rs) throws SQLException {
    Array groups = rs.getArray("subject_group_codes");
    List<String> codes = groups == null ? List.of() : Arrays.asList((String[]) groups.getArray());
    return new Template(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("form_code"),
        rs.getInt("version_no"),
        codes,
        rs.getString("object_key"),
        rs.getBytes("content_sha256"),
        TemplateStatus.valueOf(rs.getString("status")));
  }
}
