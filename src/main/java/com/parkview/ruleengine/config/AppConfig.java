package com.parkview.ruleengine.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(RuleEngineProperties.class)
public class AppConfig {

    /** Single time source so business logic and jobs are testable with a fixed clock. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Programmatic transactions for the jobs that need one transaction per row. */
    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
