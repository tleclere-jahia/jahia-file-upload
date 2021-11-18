package org.foo.modules.jahia.jaxrs.impl;

import org.apache.commons.io.FileUtils;
import org.foo.modules.jahia.jaxrs.api.UploadService;
import org.jahia.services.scheduler.BackgroundJob;
import org.jahia.services.scheduler.SchedulerService;
import org.jahia.settings.SettingsBean;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.Map;

@Component(service = BackgroundJob.class, immediate = true)
public class PurgeFileUploadJob extends BackgroundJob {
    private static Logger logger = LoggerFactory.getLogger(PurgeFileUploadJob.class);

    private static final String CONFIGURATION_KEY_CRON_ENABLED = "cron.enabled";
    private static final String CONFIGURATION_KEY_CRON_EXPRESSION = "cron.expression";

    private SchedulerService schedulerService;
    private JobDetail jobDetail;

    @Reference
    public void setSchedulerService(SchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    @Activate
    public void start(Map<String, ?> configuration) throws Exception {
        if (configuration.containsKey(CONFIGURATION_KEY_CRON_ENABLED) && (Boolean) configuration.get(CONFIGURATION_KEY_CRON_ENABLED)) {
            jobDetail = BackgroundJob.createJahiaJob("Purge uploaded files", PurgeFileUploadJob.class);
            if (schedulerService.getAllJobs(jobDetail.getGroup()).isEmpty() && SettingsBean.getInstance().isProcessingServer() && configuration.containsKey(CONFIGURATION_KEY_CRON_EXPRESSION)) {
                Trigger trigger = new CronTrigger("PurgeFileUploadJob_trigger", jobDetail.getGroup(), (String) configuration.get(CONFIGURATION_KEY_CRON_EXPRESSION));
                schedulerService.getScheduler().scheduleJob(jobDetail, trigger);
            }
        }
    }

    @Deactivate
    public void stop() throws Exception {
        if (jobDetail != null && !schedulerService.getAllJobs(jobDetail.getGroup()).isEmpty() && SettingsBean.getInstance().isProcessingServer()) {
            schedulerService.getScheduler().deleteJob(jobDetail.getName(), jobDetail.getGroup());
        }
    }

    @Override
    public void executeJahiaJob(JobExecutionContext jobExecutionContext) {
        logger.info("Purge uploaded files");
        FileUtils.deleteQuietly(Paths.get(System.getProperty("java.io.tmpdir"), UploadService.ROOT_FOLDER).toFile());
    }
}
