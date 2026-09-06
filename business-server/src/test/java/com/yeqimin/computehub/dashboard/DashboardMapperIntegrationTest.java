package com.yeqimin.computehub.dashboard;

import com.yeqimin.computehub.persistence.DashboardMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class DashboardMapperIntegrationTest {
  @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8").withDatabaseName("dashboard");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("spring.datasource.url", MYSQL::getJdbcUrl); registry.add("spring.datasource.username", MYSQL::getUsername); registry.add("spring.datasource.password", MYSQL::getPassword); }
  @Autowired DashboardMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @Test
  void mapperScopesGpuTasksWalletAndTopologyToTheRequestedTenant() {
    jdbc.update("INSERT INTO tenant(id,code,name,status) VALUES(3,'TENANT-3','隔离租户','ACTIVE')");
    jdbc.update("INSERT INTO tenant_wallet(tenant_id,available_cent,frozen_cent) VALUES(3,900000,300000)");
    long own = instance(2, 1, 1, "RUNNING");
    long other = instance(3, 3, 2, "UNKNOWN");
    task(own, 2, "SUCCEEDED"); task(other, 3, "DEAD");

    Map<String,Object> summary = mapper.summary(2L);
    Map<String,Object> tasks = mapper.taskSummary(2L);
    List<Map<String,Object>> topology = mapper.topology(2L);

    assertThat(number(summary, "gpuTotal")).isEqualTo(1L);
    assertThat(number(summary, "gpuAllocated")).isEqualTo(1L);
    assertThat(number(summary, "availableCent")).isEqualTo(1_000_000L);
    assertThat(number(tasks, "successfulTasks")).isEqualTo(1L);
    assertThat(number(tasks, "abnormalTasks")).isZero();
    assertThat(topology).extracting(row -> row.get("clusterCode")).containsOnly("SH-GPU-01");
  }

  private long instance(long tenantId, long productId, long clusterId, String status) {
    jdbc.update("INSERT INTO compute_order(order_no,tenant_id,product_id,product_snapshot,quantity,amount_cent,status,created_by) VALUES(?,?,?,JSON_OBJECT(),1,100,'PAID',1)", "O-DASH-" + tenantId, tenantId, productId);
    Long orderId = jdbc.queryForObject("SELECT id FROM compute_order WHERE order_no=?", Long.class, "O-DASH-" + tenantId);
    jdbc.update("INSERT INTO compute_instance(instance_no,order_id,tenant_id,product_id,cluster_id,name,scenario,status) VALUES(?,?,?,?,?,?,?,?)", "I-DASH-" + tenantId, orderId, tenantId, productId, clusterId, "dashboard", "SUCCESS", status);
    return jdbc.queryForObject("SELECT id FROM compute_instance WHERE instance_no=?", Long.class, "I-DASH-" + tenantId);
  }

  private void task(long instanceId, long tenantId, String state) {
    jdbc.update("INSERT INTO async_task(task_no,command_id,tenant_id,instance_id,state,retry_count,next_retry_at,operation_type,previous_instance_status,target_instance_status,scenario,actor_id,message_id,accepted_at) VALUES(?,?,?,?,?,0,NOW(3),'CREATE','REQUESTED','RUNNING','SUCCESS',1,?,NOW(3))", "T-DASH-" + tenantId, "C-DASH-" + tenantId, tenantId, instanceId, state, "M-DASH-" + tenantId);
  }

  private static long number(Map<String,Object> values, String key) { return ((Number) values.get(key)).longValue(); }
}
