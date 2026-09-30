package com.github.xingjianluo.powertelemetryalertingjava.tests.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.powertelemetryalertingjava.config.ConfigProperties;
import com.github.xingjianluo.powertelemetryalertingjava.engine.AlertEvaluator;
import com.github.xingjianluo.powertelemetryalertingjava.engine.DeviceRegistry;
import com.github.xingjianluo.powertelemetryalertingjava.engine.TelemetryValidator;
import com.github.xingjianluo.powertelemetryalertingjava.engine.WindowCalculator;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertType;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceConfig;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceState;
import com.github.xingjianluo.powertelemetryalertingjava.model.RawSample;
import com.github.xingjianluo.powertelemetryalertingjava.storage.DeviceStateRepository;
import com.github.xingjianluo.powertelemetryalertingjava.storage.JsonFileDeviceStateRepository;
import com.github.xingjianluo.powertelemetryalertingjava.tests.support.ManualClock;
import com.github.xingjianluo.powertelemetryalertingjava.tests.support.TestFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务重启恢复：新 registry 从存储加载后，采样点、窗口、告警状态、水位线、事件、设备配置均保持；
 * 重复上报依旧幂等，告警不会因重启重复触发或丢失。
 */
class RestartRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(RestartRecoveryTest.class);

    private static final long T0 = 1_000_000_000_000L;

    private final ObjectMapper mapper = TestFactory.mapper();

    private DeviceRegistry newRegistry(ConfigProperties props, DeviceStateRepository repo, ManualClock clock) {
        return new DeviceRegistry(props, repo,
                new TelemetryValidator(props, clock),
                new WindowCalculator(),
                new AlertEvaluator(),
                clock);
    }

    private RawSample raw(long ts, double v, double a, double w) {
        RawSample r = new RawSample();
        r.setTimestampMs(ts);
        r.setVoltageV(v);
        r.setCurrentA(a);
        r.setPowerW(w);
        return r;
    }

    @Test
    @DisplayName("JSON 文件落盘 + 重启：全部状态与设备配置恢复，重复数据仍幂等")
    void stateSurvivesRestartFromJsonFile(@TempDir Path tempDir) {
        ConfigProperties props = new ConfigProperties();
        props.setDataDir(tempDir.toString());
        props.validate();
        ManualClock clock = new ManualClock(T0 + 10_000);

        // --- 第一次生命周期：写入 + 触发过压告警 + 设备级配置
        DeviceRegistry first = newRegistry(props, new JsonFileDeviceStateRepository(mapper, props), clock);
        for (int i = 0; i < 5; i++) {
            first.ingest("dev-restart", List.of(raw(T0 + i * 1_000L, 260, 5, 1000)));
        }
        DeviceConfig patch = new DeviceConfig();
        patch.setOverPowerW(4000.0);
        first.updateConfig("dev-restart", patch, false);

        DeviceState before = first.state("dev-restart").orElseThrow();
        assertEquals(5, before.getSamples().size());
        assertTrue(before.runtime(AlertType.OVER_VOLTAGE).isActive(), "over-voltage must be active before restart");
        long watermarkBefore = before.getAlertWatermarkMs();
        int eventsBefore = before.getEvents().size();
        long revisionBefore = before.getRevision();
        Path stateFile = tempDir.resolve("dev-restart.json");
        assertTrue(Files.isRegularFile(stateFile), "state file must exist: " + stateFile);

        // --- 模拟服务重启：全新 registry（空内存），同一文件存储与时钟
        DeviceRegistry second = newRegistry(props, new JsonFileDeviceStateRepository(mapper, props), clock);
        DeviceState after = second.state("dev-restart").orElseThrow();
        assertEquals(5, after.getSamples().size());
        assertEquals(before.getSamples().keySet(), after.getSamples().keySet());
        assertEquals(before.getWindows().keySet(), after.getWindows().keySet());
        assertEquals(before.getWindows().firstEntry().getValue().getEnergyWh(),
                after.getWindows().firstEntry().getValue().getEnergyWh(), 1e-15);
        assertTrue(after.runtime(AlertType.OVER_VOLTAGE).isActive(), "alert active state must survive restart");
        assertEquals(watermarkBefore, after.getAlertWatermarkMs(), "alert watermark must survive restart");
        assertEquals(eventsBefore, after.getEvents().size(), "event history must survive restart");
        assertEquals(revisionBefore, after.getRevision());
        assertEquals(4000.0,
                com.github.xingjianluo.powertelemetryalertingjava.model.EffectiveConfig
                        .merge(props, second.getConfigOverride("dev-restart")).overPowerW(), 0.0d);

        // 重启后重放旧批次：全部 duplicate，不产生重复告警、不推进 revision
        var replay = second.ingest("dev-restart", List.of(
                raw(T0, 260, 5, 1000), raw(T0 + 1_000L, 260, 5, 1000)));
        assertEquals(2, replay.duplicate());
        assertEquals(0, replay.alerts().size());
        assertEquals(revisionBefore, second.state("dev-restart").orElseThrow().getRevision());

        // 重启后继续推进：新正常点不会立即恢复（防抖连续计数从持久化的 streak 语义继续）
        // 这里活动告警 streak=3；再来正常点，状态机从水位线继续，结论与未重启一致
        clock.advance(1_000L);
        long nextTs = T0 + 5_000L;
        var cont = second.ingest("dev-restart", List.of(raw(nextTs, 220, 5, 1000)));
        assertEquals(1, cont.accepted());
        assertTrue(second.state("dev-restart").orElseThrow().runtime(AlertType.OVER_VOLTAGE).isActive(),
                "single normal sample after restart must not instantly recover (debounce continues)");
        log.info("restart recovery ok: samples={} events={} windows={} watermark={}",
                after.getSamples().size(), after.getEvents().size(), after.getWindows().size(), watermarkBefore);
    }

    @Test
    @DisplayName("中断告警在服务重启后可预测：超时状态从最后采样时间重建判定")
    void interruptionStatePredictableAfterRestart(@TempDir Path tempDir) {
        ConfigProperties props = new ConfigProperties();
        props.setDataDir(tempDir.toString());
        props.setDefaultInterruptionTimeoutMs(60_000L);
        props.validate();
        ManualClock clock = new ManualClock(T0);

        DeviceRegistry first = newRegistry(props, new JsonFileDeviceStateRepository(mapper, props), clock);
        first.ingest("dev-intr", List.of(raw(T0, 220, 5, 1000)));
        clock.advance(120_000L);
        List<String> tripped = first.tickAll();
        assertTrue(tripped.contains("dev-intr"));
        assertTrue(first.state("dev-intr").orElseThrow()
                .runtime(AlertType.SAMPLE_INTERRUPTION).isActive());

        // 重启后立即 tick：中断仍应处于活动，且不会重复产生触发事件
        DeviceRegistry second = newRegistry(props, new JsonFileDeviceStateRepository(mapper, props), clock);
        assertTrue(second.state("dev-intr").orElseThrow()
                .runtime(AlertType.SAMPLE_INTERRUPTION).isActive(), "interruption active state survives restart");
        assertTrue(second.tickAll().isEmpty(), "no duplicate trigger after restart");

        // 设备恢复上报 -> 中断恢复
        var recovered = second.ingest("dev-intr", List.of(raw(clock.millis(), 220, 5, 1000)));
        assertFalse(second.state("dev-intr").orElseThrow()
                .runtime(AlertType.SAMPLE_INTERRUPTION).isActive());
        assertEquals(1, recovered.alerts().size());
        assertFalse(recovered.alerts().get(0).isTriggered());
    }

    @Test
    @DisplayName("损坏的状态文件被隔离，设备从空状态重新接入，不影响其他设备")
    void corruptStateFileIsQuarantined(@TempDir Path tempDir) throws Exception {
        ConfigProperties props = new ConfigProperties();
        props.setDataDir(tempDir.toString());
        props.validate();
        Files.writeString(tempDir.resolve("dev-bad.json"), "{ this is not valid json ");

        JsonFileDeviceStateRepository repo = new JsonFileDeviceStateRepository(mapper, props);
        assertTrue(repo.load("dev-bad").isEmpty());
        assertFalse(Files.isRegularFile(tempDir.resolve("dev-bad.json")), "corrupt file removed");
        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().startsWith("dev-bad.json.corrupt")),
                "corrupt file quarantined with .corrupt suffix");
    }
}
