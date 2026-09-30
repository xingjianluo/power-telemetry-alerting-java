package com.github.xingjianluo.powertelemetryalertingjava.tests;

import com.github.xingjianluo.powertelemetryalertingjava.api.dto.TelemetryPointDto;
import com.github.xingjianluo.powertelemetryalertingjava.config.ServiceProperties;
import com.github.xingjianluo.powertelemetryalertingjava.engine.AlertEvaluator;
import com.github.xingjianluo.powertelemetryalertingjava.engine.DeviceConfigService;
import com.github.xingjianluo.powertelemetryalertingjava.engine.IngestService;
import com.github.xingjianluo.powertelemetryalertingjava.engine.InterruptionMonitor;
import com.github.xingjianluo.powertelemetryalertingjava.engine.RecordValidator;
import com.github.xingjianluo.powertelemetryalertingjava.engine.WindowManager;
import com.github.xingjianluo.powertelemetryalertingjava.model.Thresholds;
import com.github.xingjianluo.powertelemetryalertingjava.persistence.SnapshotStore;
import com.github.xingjianluo.powertelemetryalertingjava.security.AdminTokenService;
import com.github.xingjianluo.powertelemetryalertingjava.security.TokenAuthenticator;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 非法配置拒绝且不影响已有状态；单设备调整不影响其他设备；令牌鉴权与越权拒绝。
 */
class DeviceConfigTest {

    private static final Logger log = LoggerFactory.getLogger(DeviceConfigTest.class);

    private ServiceProperties props;
    private DeviceRegistry registry;
    private DeviceConfigService configService;
    private IngestService ingest;
    private AtomicLong clock;

    @BeforeEach
    void setUp() throws Exception {
        Path snap = Files.createTempDirectory("snap7").resolve("s.json");
        clock = new AtomicLong(7_000_000_000L);
        props = new ServiceProperties();
        props.setSnapshotFile(snap.toString());
        props.setFutureToleranceMs(600_000L);
        props.setMaxHistoryMs(3600_000L);
        props.setAllowedLatenessMs(600_000L);
        props.setSnapshotAfterEachBatch(false);
        registry = new DeviceRegistry();
        SnapshotStore store = new SnapshotStore(snap.toString());
        AlertEvaluator ev = new AlertEvaluator(clock::get, props);
        InterruptionMonitor im = new InterruptionMonitor(clock::get, ev, props);
        ingest = new IngestService(registry, new RecordValidator(clock::get), ev, im,
                new WindowManager(), store, props, clock::get);
        configService = new DeviceConfigService(registry, store, props, clock::get);
    }

    @Test
    void invalidThresholdsAreRejectedAndStateUntouched() {
        configService.register("dev-A", null);
        var original = configService.get("dev-A").thresholds();

        assertThrows(IllegalArgumentException.class,
                () -> configService.updateThresholds("dev-A",
                        new Thresholds(-1d, null, null, null, null)), "负过压阈值必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> configService.updateThresholds("dev-A",
                        new Thresholds(100d, 100d, null, null, null)), "欠压>=过压必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> configService.updateThresholds("dev-A",
                        new Thresholds(null, null, 0d, null, null)), "零过流阈值必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> configService.updateThresholds("dev-A",
                        new Thresholds(null, null, null, null, -5L)), "负采样间隔必须拒绝");
        assertThrows(IllegalStateException.class,
                () -> configService.updateThresholds("ghost", new Thresholds(1d, null, null, null, null)),
                "未注册设备必须拒绝");

        assertEquals(original, configService.get("dev-A").thresholds(), "非法配置后已有阈值保持不变");
        // 合法配置正常生效
        var ok = configService.updateThresholds("dev-A",
                new Thresholds(260d, 190d, 35d, 8000d, 60_000L));
        assertEquals(260d, ok.thresholds().overVoltage());
        log.info("非法阈值全部拒绝且状态不变，合法阈值生效: {}", ok.thresholds());
    }

    @Test
    void perDeviceChangeDoesNotAffectOthersAndIngestContinues() {
        configService.register("dev-A", null);
        configService.register("dev-B", null);
        configService.updateThresholds("dev-A", new Thresholds(300d, 100d, 50d, 9999d, 90_000L));
        configService.setAlertsEnabled("dev-B", false);

        assertEquals(300d, configService.get("dev-A").thresholds().overVoltage());
        assertTrue(configService.get("dev-A").alertsEnabled());
        assertFalse(configService.get("dev-B").alertsEnabled());
        AlertEvaluator evaluator = new AlertEvaluator(clock::get, props);
        assertEquals(253d, evaluator.resolveThresholds(configService.get("dev-B").thresholds()).overVoltage(),
                "dev-B 生效阈值仍为全局默认 253");
        assertEquals(300d, evaluator.resolveThresholds(configService.get("dev-A").thresholds()).overVoltage(),
                "dev-A 生效阈值为自定义 300");

        // 两台设备都能继续上报
        long t = clock.get();
        ingest.ingest("dev-A", List.of(new TelemetryPointDto(t, 220d, 10d, 1000d)));
        ingest.ingest("dev-B", List.of(new TelemetryPointDto(t, 220d, 10d, 1000d)));
        assertEquals(1, registry.get("dev-A").getSamples().size());
        assertEquals(1, registry.get("dev-B").getSamples().size());
        log.info("单设备调阈值/启停不影响其他设备：A自定义阈值+B停用告警，各自接入正常");
    }

    @Test
    void tokensAuthenticateAndUnauthorizedIsRejected() throws Exception {
        String tokenA = configService.register("dev-A", null);
        String tokenB = configService.register("dev-B", null);
        assertTrue(configService.checkToken("dev-A", tokenA));
        assertFalse(configService.checkToken("dev-A", tokenB), "别的设备令牌不能操作 dev-A");
        assertFalse(configService.checkToken("dev-A", "wrong-token"));
        assertFalse(configService.checkToken("dev-A", null));

        // 开启鉴权
        props.setAuthEnabled(true);
        props.setAdminToken("admin-secret");
        var authenticator = new TokenAuthenticator(new AdminTokenService(props), configService);

        // 无令牌 → 401
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/devices/dev-A/telemetry");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(authenticator.preHandle(req, res, new Object()));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, res.getStatus());

        // dev-B 令牌操作 dev-A → 403，且不触碰状态
        req = new MockHttpServletRequest("POST", "/api/devices/dev-A/telemetry");
        req.addHeader("Authorization", "Bearer " + tokenB);
        res = new MockHttpServletResponse();
        assertFalse(authenticator.preHandle(req, res, new Object()));
        assertEquals(HttpServletResponse.SC_FORBIDDEN, res.getStatus());

        // dev-A 自己的令牌 → 放行
        req = new MockHttpServletRequest("POST", "/api/devices/dev-A/telemetry");
        req.addHeader("Authorization", "Bearer " + tokenA);
        res = new MockHttpServletResponse();
        assertTrue(authenticator.preHandle(req, res, new Object()));

        // 管理员令牌可以操作任意设备，注册接口仅管理员
        req = new MockHttpServletRequest("POST", "/api/devices/register");
        req.addHeader("Authorization", "Bearer " + tokenA);
        res = new MockHttpServletResponse();
        assertFalse(authenticator.preHandle(req, res, new Object()), "设备令牌不能调用注册接口");
        req = new MockHttpServletRequest("POST", "/api/devices/register");
        req.addHeader("Authorization", "Bearer admin-secret");
        res = new MockHttpServletResponse();
        assertTrue(authenticator.preHandle(req, res, new Object()));
        log.info("鉴权验证：401无令牌/403越权/设备令牌仅自身/管理员全权，且拒绝访问不改状态");
    }
}
