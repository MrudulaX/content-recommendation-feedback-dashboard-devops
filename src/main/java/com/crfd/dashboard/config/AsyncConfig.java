package com.crfd.dashboard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated, bounded executor for the feedback enrichment pipeline.
 *
 * <p>A separate pool keeps ingestion latency independent from enrichment work and prevents
 * a burst of events from exhausting the web container's threads. {@code CallerRunsPolicy}
 * applies natural back-pressure instead of silently dropping events.</p>
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "eventExecutor")
    public TaskExecutor eventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("crfd-pipeline-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
