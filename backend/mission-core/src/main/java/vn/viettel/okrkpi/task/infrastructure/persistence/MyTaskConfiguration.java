package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.task.application.assign.TaskQueryService;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.mine.LeadWorkloads;
import vn.viettel.okrkpi.task.application.mine.MyTaskService;
import vn.viettel.okrkpi.task.application.mine.TaskExportService;
import vn.viettel.okrkpi.task.infrastructure.job.InMemoryTaskExports;

/**
 * "Việc của tôi", nhận việc, xuất danh sách và khối lượng việc (CORE-02-18). Số ngày làm việc của
 * "Sắp hạn" và ngưỡng việc chủ trì mỗi tuần (BR-05-11) là tham số triển khai tới khi Policy Pack có
 * hai tham số này (suy luận).
 */
@Configuration(proxyBeanMethods = false)
public class MyTaskConfiguration {

  @Bean
  JdbcMyTaskQueries myTaskQueries(@Qualifier("taskDataSource") DataSource dataSource) {
    return new JdbcMyTaskQueries(JdbcClient.create(dataSource));
  }

  @Bean
  LeadWorkloads leadWorkloads(
      JdbcMyTaskQueries myTaskQueries,
      @Value("${mission.task.workload.weekly-lead-threshold:5}") int threshold) {
    return new LeadWorkloads(myTaskQueries, threshold);
  }

  @Bean
  MyTaskService myTaskService(
      JdbcMyTaskQueries myTaskQueries,
      PeriodApi periods,
      ObjectProvider<WorkingCalendarApi> calendar,
      ObjectProvider<OkrApi> okr,
      TaskTransitionService taskTransitionService,
      @Value("${mission.task.mine.soon-working-days:3}") int soonWorkingDays) {
    return new MyTaskService(
        myTaskQueries,
        periods,
        calendar.getIfAvailable(),
        okr.getIfAvailable(),
        taskTransitionService,
        soonWorkingDays,
        java.time.Clock.systemUTC());
  }

  @Bean
  InMemoryTaskExports taskExports() {
    return new InMemoryTaskExports();
  }

  @Bean
  TaskExportService taskExportService(
      MyTaskService myTaskService,
      TaskQueryService taskQueryService,
      InMemoryTaskExports taskExports) {
    return new TaskExportService(
        myTaskService,
        taskQueryService,
        new FastExcelTaskExportSheets(),
        taskExports,
        taskExports,
        Executors.newVirtualThreadPerTaskExecutor());
  }
}
