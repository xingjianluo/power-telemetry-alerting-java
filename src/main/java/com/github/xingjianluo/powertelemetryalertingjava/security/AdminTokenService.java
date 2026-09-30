package com.github.xingjianluo.powertelemetryalertingjava.security;

import com.github.xingjianluo.powertelemetryalertingjava.config.ServiceProperties;
import org.springframework.stereotype.Component;

/**
 * 管理员令牌来源（本地单机：配置项 power.service.admin-token）。
 */
@Component
public class AdminTokenService {

    private final ServiceProperties properties;

    public AdminTokenService(ServiceProperties properties) {
        this.properties = properties;
    }

    public boolean isAdmin(String rawToken) {
        String configured = properties.getAdminToken();
        return configured != null && !configured.isBlank()
                && rawToken != null
                && java.security.MessageDigest.isEqual(
                        configured.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public boolean authEnabled() {
        return properties.isAuthEnabled();
    }
}
