package com.github.xingjianluo.powertelemetryalertingjava.config;

import com.github.xingjianluo.powertelemetryalertingjava.security.TokenAuthenticator;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册鉴权拦截器，覆盖所有 /api/** 接口。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TokenAuthenticator authenticator;

    public WebMvcConfig(TokenAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticator).addPathPatterns("/api/**");
    }
}
