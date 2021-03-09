package org.folio.des.scheduling;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.folio.des.client.AuthClient;
import org.folio.des.config.FolioExecutionContextHelper;
import org.folio.des.domain.dto.AuthCredentials;
import org.folio.des.domain.dto.ExportConfig;
import org.folio.des.domain.dto.Job;
import org.folio.des.service.ExportConfigService;
import org.folio.des.service.JobService;
import org.folio.spring.integration.XOkapiHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Log4j2
@Component
@EnableScheduling
@RequiredArgsConstructor
public class ExportScheduler implements SchedulingConfigurer {

  private ScheduledTaskRegistrar registrar;
  private Job scheduledJob;
  private final ExportTrigger trigger;
  private final AuthClient authClient;
  private final JobService jobService;
  private final ExportConfigService configService;
  private final FolioExecutionContextHelper contextHelper;

  @Value("${folio.tenant.name}")
  private String tenant;
  @Value("${folio.tenant.password}")
  private String password;
  @Value("${folio.tenant.username}")
  private String username;
  @Value("${folio.okapi.url}")
  private String okapiUrl;

  @Override
  public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
    registrar = taskRegistrar;
    initConfiguration();
    taskRegistrar.setScheduler(taskExecutor());
    log.info("Scheduled {}.", scheduledJob);
    taskRegistrar.addTriggerTask(() -> jobService.upsert(scheduledJob), trigger);
  }

  public void updateTasks(ExportConfig exportConfig) {
    trigger.setConfig(exportConfig);
    if (registrar.hasTasks()) {
      registrar.destroy();
      registrar.afterPropertiesSet();
    }
  }

  private void initConfiguration() {
    authorizeWithToken();

    fetchConfiguration();
  }

  private void fetchConfiguration() {
    Optional<ExportConfig> savedConfig = configService.getConfig();
    savedConfig.ifPresent(trigger::setConfig);
    savedConfig.ifPresent(exportConfig -> scheduledJob = defaultJob(exportConfig));
  }

  private void authorizeWithToken() {
    AuthCredentials authDto = createCredentials();

    ResponseEntity<String> authResponse = authClient.getApiKey(tenant, authDto);
    HttpHeaders headers = authResponse.getHeaders();
    List<String> token = headers.get(XOkapiHeaders.TOKEN);

    Map<String, Collection<String>> okapiHeaders = new HashMap<>();
    okapiHeaders.put(XOkapiHeaders.TOKEN, token);
    okapiHeaders.put(XOkapiHeaders.TENANT, List.of(tenant));
    okapiHeaders.put(XOkapiHeaders.URL, List.of(okapiUrl));

    contextHelper.init(okapiHeaders);
  }

  private AuthCredentials createCredentials() {
    AuthCredentials authDto = new AuthCredentials();
    authDto.setPassword(password);
    authDto.setUsername(username);
    return authDto;
  }

  private Executor taskExecutor() {
    return Executors.newScheduledThreadPool(100);
  }

  private Job defaultJob(ExportConfig exportConfig) {
    Job job = new Job();
    job.setType(exportConfig.getType());
    job.setIsSystemSource(true);

    var exportTypeSpecificParameters = exportConfig.getExportTypeSpecificParameters();
    if (exportTypeSpecificParameters == null) {
      log.error("There is no configuration for scheduled job");
      return job;
    }
    job.setExportTypeSpecificParameters(exportTypeSpecificParameters);

    return job;
  }

}
