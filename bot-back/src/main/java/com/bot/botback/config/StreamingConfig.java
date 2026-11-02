package com.bot.botback.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Threads that write streamed replies back to the browser. */
@Configuration
public class StreamingConfig {

    public static final String REPLY_STREAM_EXECUTOR = "replyStreamExecutor";

    @Bean(name = REPLY_STREAM_EXECUTOR)
    public ThreadPoolTaskExecutor replyStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        // Bounded, so a burst can't queue unlimited work; overflow is refused with a "busy" reply.
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("reply-stream-");
        // Let replies in progress finish (and be saved) when the app shuts down.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
