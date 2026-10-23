package vn.viettel.okrkpi.org.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobWebMapperTest {

  @Test
  void jobIdCarriesOrgPrefixAndParsesBack() {
    UUID id = UUID.randomUUID();
    assertThat(JobWebMapper.jobId(id)).isEqualTo("org-" + id);
    assertThat(JobWebMapper.parseJobId(JobWebMapper.jobId(id))).contains(id);
    assertThat(JobWebMapper.parseJobId(id.toString())).contains(id);
    assertThat(JobWebMapper.parseJobId("score-" + id)).isEmpty();
    assertThat(JobWebMapper.parseJobId("org-khong-hop-le")).isEmpty();
    assertThat(JobWebMapper.parseJobId(null)).isEmpty();
  }
}
