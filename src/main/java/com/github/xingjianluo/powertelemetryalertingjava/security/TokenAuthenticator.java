package com.github.xingjianluo.powertelemetryalertingjava.security;

import com.github.xingjianluo.powertelemetryalertingjava.engine.DeviceConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;


/**
 * Bearer 令牌鉴权：
 * <ul>
 *   <li>注册/列表（/api/devices/register、/api/devices）：仅管理员；</li>
 *   <li>单设备配置、阈值、启停、查询：管理员或该设备自己的令牌；</li>
 *   <li>遥测上报：该设备自己的令牌（管理员令牌也可）。</li>
 * </ul>
 * 越权返回 403，未携带令牌返回 401；非法请求不会触及任何设备状态。
 * power.service.auth-enabled=false（本地验证默认）时整体放行。
 */
@Component
public class TokenAuthenticator implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TokenAuthenticator.class);

    private final AdminTokenService adminTokens;
    private final DeviceConfigService configService;

    public TokenAuthenticator(AdminTokenService adminTokens, DeviceConfigService configService) {
        this.adminTokens = adminTokens;
        this.configService = configService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!adminTokens.authEnabled()) {
            return true;
        }
        String token = bearer(request);
        String deviceId = extractDeviceId(request.getRequestURI());
        boolean admin = adminTokens.isAdmin(token);
        boolean owner = deviceId != null && configService.checkToken(deviceId, token);

        boolean authorized;
        if (deviceId == null) {
            // 管理类接口
            authorized = admin;
        } else {
            authorized = admin || owner;
        }
        if (!authorized) {
            if (token == null) {
                log.info("拒绝访问 401 uri={} 原因=缺少Bearer令牌", request.getRequestURI());
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "缺少 Bearer 令牌");
            } else {
                log.info("拒绝访问 403 uri={} device={} 原因=令牌越权或无效", request.getRequestURI(), deviceId);
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "令牌无效或无权操作该设备");
            }
            return false;
        }
        return true;
    }

    private static String bearer(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            return h.substring(7).trim();
        }
        return null;
    }

    /** 从 /api/devices/{deviceId}/... 中取设备号；注册与列表接口返回 null。 */
    static String extractDeviceId(String uri) {
        String prefix = "/api/devices/";
        int start = uri.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        String rest = uri.substring(start + prefix.length());
        if (rest.isBlank() || rest.equals("register")) {
            return null;
        }
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        return id.isBlank() ? null : id;
    }
}
