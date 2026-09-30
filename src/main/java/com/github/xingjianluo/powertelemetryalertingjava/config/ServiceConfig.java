package com.github.xingjianluo.powertelemetryalertingjava.config;

import com.github.xingjianluo.powertelemetryalertingjava.persistence.SnapshotStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.LongSupplier;

/**
 * Bean 装配：时钟、快照存储等。
 */
@Configuration
@EnableConfigurationProperties(ServiceProperties.class)
public class ServiceConfig {

    @Bean
    public LongSupplier systemClock() {
        return System::currentTimeMillis;
    }

    @Bean
    public SnapshotStore snapshotStore(ServiceProperties properties) {
        return new SnapshotStore(properties.getSnapshotFile());
    }
}
