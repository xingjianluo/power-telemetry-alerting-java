package com.github.xingjianluo.powertelemetryalertingjava;

import com.github.xingjianluo.powertelemetryalertingjava.config.ConfigProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    /** 启动时校验全局配置：非法配置直接快速失败，不带病运行。 */
    @Bean
    CommandLineRunner configSelfCheck(ConfigProperties props) {
        return args -> props.validate();
    }
}
