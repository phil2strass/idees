package fr.idee;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ImageScheduling {
    // Keep the usual jobs on their own scheduler; slow image hosts cannot delay imports or batches.
    @Bean(name="taskScheduler")
    ThreadPoolTaskScheduler taskScheduler() { return scheduler("idee-jobs-"); }
    @Bean(name="imageScheduler")
    ThreadPoolTaskScheduler imageScheduler() { return scheduler("idee-images-"); }
    private ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        return scheduler;
    }
}
