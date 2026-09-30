package com.github.xingjianluo.powertelemetryalertingjava.engine;

import com.github.xingjianluo.powertelemetryalertingjava.config.ConfigProperties;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertEvent;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceConfig;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceState;
import com.github.xingjianluo.powertelemetryalertingjava.model.EffectiveConfig;
import com.github.xingjianluo.powertelemetryalertingjava.model.IngestBatchResult;
import com.github.xingjianluo.powertelemetryalertingjava.model.RawSample;
import com.github.xingjianluo.powertelemetryalertingjava.model.RecordResult;
import com.github.xingjianluo.powertelemetryalertingjava.model.RejectReason;
import com.github.xingjianluo.powertelemetryalertingjava.model.Sample;
import com.github.xingjianluo.powertelemetryalertingjava.storage.DeviceStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备注册表与接入主流程。
 *
 * <p>每台设备一把专用锁：同一设备的多个并发批次串行推进（不重不漏），
 * 不同设备完全并行；调整某台设备阈值/开关不影响其他设备接入。
 * 状态变更采用"接受一条 → 内存推进 → 批次结束整体落盘"，崩溃恢复以最近落盘状态为准。
 */
@Component
public class DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);

    private final ConcurrentHashMap<String, DeviceEntry> entries = new ConcurrentHashMap<>();
    private final ConfigProperties props;
    private final DeviceStateRepository repository;
    private final TelemetryValidator validator;
    private final WindowCalculator windowCalculator;
    private final AlertEvaluator alertEvaluator;
    private final Clock clock;

    public DeviceRegistry(ConfigProperties props, DeviceStateRepository repository,
                          TelemetryValidator validator, WindowCalculator windowCalculator,
                          AlertEvaluator alertEvaluator, Clock clock) {
        this.props = props;
        this.repository = repository;
        this.validator = validator;
        this.windowCalculator = windowCalculator;
        this.alertEvaluator = alertEvaluator;
        this.clock = clock;
    }

    /** 设备内存态（含专用锁与配置覆盖）。 */
    static final class DeviceEntry {
        final DeviceState state;
        DeviceConfig configOverride;
        final Object lock = new Object();

        DeviceEntry(DeviceState state, DeviceConfig configOverride) {
            this.state = state;
            this.configOverride = configOverride;
        }
    }

    private DeviceEntry entry(String deviceId) {
        return entries.computeIfAbsent(deviceId, id -> {
            DeviceState loaded = repository.load(id).orElseGet(() -> new DeviceState(id));
            return new DeviceEntry(loaded, loaded.getConfigOverride());
        });
    }

    /** 批量上报，逐条给出结论，一个坏点不影响整批。 */
    public IngestBatchResult ingest(String deviceId, List<RawSample> batch) {
        String id = validateDeviceId(deviceId);
        List<RawSample> records = batch == null ? List.of() : batch;
        DeviceEntry entry = entry(id);
        synchronized (entry.lock) {
            DeviceState state = entry.state;
            long nowMs = clock.millis();
            List<RecordResult> results = new ArrayList<>(records.size());
            List<AlertEvent> batchAlerts = new ArrayList<>();
            int accepted = 0;
            int duplicate = 0;

            // 按 (时间, 原始下标) 稳定排序：并发批次与乱序批次的处理顺序都确定、可复现。
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < records.size(); i++) {
                order.add(i);
            }
            order.sort((x, y) -> {
                RawSample rx = records.get(x);
                RawSample ry = records.get(y);
                long tx = rx != null && rx.getTimestampMs() != null ? rx.getTimestampMs() : Long.MIN_VALUE;
                long ty = ry != null && ry.getTimestampMs() != null ? ry.getTimestampMs() : Long.MIN_VALUE;
                int c = Long.compare(tx, ty);
                return c != 0 ? c : Integer.compare(x, y);
            });

            for (int idx : order) {
                RawSample raw = records.get(idx);
                long newest = state.getLastSampleMs();
                TelemetryValidator.Outcome outcome = validator.validate(raw, newest);
                if (outcome.reason() != null) {
                    results.add(RecordResult.rejected(idx,
                            raw != null ? raw.getTimestampMs() : null,
                            outcome.reason(), outcome.message()));
                    log.debug("reject device={} index={} reason={} msg='{}'",
                            id, idx, outcome.reason(), outcome.message());
                    continue;
                }
                Sample s = outcome.sample();

                // 保留窗口之外的点无法再归位到任何现存窗口，明确拒绝，绝不静默丢弃。
                // 保留下界只锚定设备最新采样窗口：墙钟只用于 FUTURE/TOO_OLD 绝对校验，
                // 避免"晚数据刚到、早窗口被误删"；设备第一批数据不做保留淘汰，
                // 以第一个合法点锚定窗口（允许设备注册时补发近期历史）。
                long newestAccepted = state.getLastSampleMs();
                if (newestAccepted != 0) {
                    long horizon = retentionHorizon(newestAccepted);
                    long sampleWindowStart = WindowCalculator.alignFloor(s.getTimestampMs(), props.getWindowMs());
                    if (sampleWindowStart < horizon) {
                        results.add(RecordResult.rejected(idx, s.getTimestampMs(),
                                RejectReason.RETENTION_EXCEEDED,
                                "window start " + sampleWindowStart + " older than retention horizon " + horizon
                                        + " (retainWindows=" + props.getRetainWindows() + ")"));
                        continue;
                    }
                }

                Sample existing = state.getSamples().get(s.getTimestampMs());
                if (existing != null) {
                    if (existing.sameReading(s)) {
                        // 同设备同采样点同读数：幂等忽略，不重复累计。
                        results.add(RecordResult.duplicate(idx, s.getTimestampMs()));
                        duplicate++;
                        log.debug("duplicate device={} ts={} ignored", id, s.getTimestampMs());
                    } else {
                        results.add(RecordResult.rejected(idx, s.getTimestampMs(), RejectReason.CONFLICT,
                                "conflicting reading for same timestamp: stored v=" + existing.getVoltageV()
                                        + " a=" + existing.getCurrentA() + " w=" + existing.getPowerW()
                                        + " vs incoming v=" + s.getVoltageV() + " a=" + s.getCurrentA()
                                        + " w=" + s.getPowerW()));
                    }
                    continue;
                }

                state.getSamples().put(s.getTimestampMs(), s);
                if (s.getTimestampMs() > state.getLastSampleMs()) {
                    state.setLastSampleMs(s.getTimestampMs());
                }
                accepted++;
                results.add(RecordResult.accepted(idx, s.getTimestampMs()));
                log.debug("accept device={} ts={} v={} a={} w={}", id, s.getTimestampMs(),
                        s.getVoltageV(), s.getCurrentA(), s.getPowerW());
            }

            boolean changed = accepted > 0;
            if (changed) {
                evictAndRecompute(state, nowMs);

                EffectiveConfig cfg = EffectiveConfig.merge(props, entry.configOverride);
                cfg.validate();
                List<AlertEvent> alerts = alertEvaluator.advance(state, cfg, state.getLastSampleMs());
                // 新数据到达会让中断告警恢复。
                alerts.addAll(alertEvaluator.evaluateInterruption(id, state, cfg, nowMs));
                batchAlerts.addAll(alerts);
                state.getEvents().addAll(alerts);

                state.setLastIngestAtMs(nowMs);
                state.setRevision(state.getRevision() + 1);
                persist(entry);
                log.info("ingest done device={} total={} accepted={} duplicate={} rejected={} alerts={} rev={}",
                        id, records.size(), accepted, duplicate, results.size() - accepted - duplicate,
                        batchAlerts.size(), state.getRevision());
            }

            int rejected = results.size() - accepted - duplicate;
            return new IngestBatchResult(id, records.size(), accepted, duplicate, rejected,
                    new ArrayList<>(results), batchAlerts);
        }
    }

    /** 后台扫描：采样中断判定与恢复，返回触发/恢复了中断告警的设备。 */
    public List<String> tickAll() {
        List<String> changedDevices = new ArrayList<>();
        for (String id : new ArrayList<>(entries.keySet())) {
            DeviceEntry entry = entries.get(id);
            if (entry == null) {
                continue;
            }
            synchronized (entry.lock) {
                DeviceState state = entry.state;
                if (state.getSamples().isEmpty()) {
                    continue;
                }
                long nowMs = clock.millis();
                EffectiveConfig cfg = EffectiveConfig.merge(props, entry.configOverride);
                List<AlertEvent> events = alertEvaluator.evaluateInterruption(id, state, cfg, nowMs);
                if (!events.isEmpty()) {
                    state.getEvents().addAll(events);
                    state.setRevision(state.getRevision() + 1);
                    persist(entry);
                    changedDevices.add(id);
                }
            }
        }
        return changedDevices;
    }

    public Optional<DeviceState> state(String deviceId) {
        String id = validateDeviceId(deviceId);
        DeviceEntry entry = entries.get(id);
        if (entry == null) {
            // 重启后尚未上报：从持久化存储懒加载，保证重启后立即可查。
            entry = repository.load(id).map(state -> {
                DeviceEntry loaded = new DeviceEntry(state, state.getConfigOverride());
                DeviceEntry existing = entries.putIfAbsent(id, loaded);
                return existing != null ? existing : loaded;
            }).orElse(null);
            if (entry == null) {
                return Optional.empty();
            }
        }
        synchronized (entry.lock) {
            return Optional.of(entry.state);
        }
    }

    public java.util.Set<String> deviceIds() {
        return java.util.Set.copyOf(entries.keySet());
    }

    public DeviceConfig getConfigOverride(String deviceId) {
        String id = validateDeviceId(deviceId);
        DeviceEntry entry = entries.get(id);
        return entry == null ? null : entry.configOverride;
    }

    /** 合法配置才允许覆盖；先校验后生效，非法配置抛异常且不动任何现有状态。 */
    public void updateConfig(String deviceId, DeviceConfig patch, boolean replace) {
        String id = validateDeviceId(deviceId);
        DeviceEntry entry = entry(id);
        synchronized (entry.lock) {
            DeviceConfig next = replace ? patch : mergeOverride(entry.configOverride, patch);
            EffectiveConfig effective = EffectiveConfig.merge(props, next);
            effective.validate(); // 非法配置在此抛出，下面的赋值不会执行
            entry.configOverride = next;
            entry.state.setConfigOverride(next);
            long nowMs = clock.millis();
            if (!effective.alertsEnabled()) {
                List<AlertEvent> recovered = alertEvaluator.deactivateAll(id, entry.state, nowMs);
                entry.state.getEvents().addAll(recovered);
            }
            entry.state.setRevision(entry.state.getRevision() + 1);
            persist(entry);
            log.info("config updated device={} replace={} alertsEnabled={} overV={} underV={} overA={} overW={}",
                    id, replace, effective.alertsEnabled(), effective.overVoltageV(),
                    effective.underVoltageV(), effective.overCurrentA(), effective.overPowerW());
        }
    }

    /** PATCH 语义：只覆盖非 null 字段。 */
    private static DeviceConfig mergeOverride(DeviceConfig base, DeviceConfig patch) {
        DeviceConfig out = new DeviceConfig();
        if (base != null) {
            out.setOverVoltageV(base.getOverVoltageV());
            out.setUnderVoltageV(base.getUnderVoltageV());
            out.setOverCurrentA(base.getOverCurrentA());
            out.setOverPowerW(base.getOverPowerW());
            out.setInterruptionTimeoutMs(base.getInterruptionTimeoutMs());
            out.setBreachTimes(base.getBreachTimes());
            out.setRecoverTimes(base.getRecoverTimes());
            out.setAlertsEnabled(base.getAlertsEnabled());
        }
        if (patch != null) {
            if (patch.getOverVoltageV() != null) out.setOverVoltageV(patch.getOverVoltageV());
            if (patch.getUnderVoltageV() != null) out.setUnderVoltageV(patch.getUnderVoltageV());
            if (patch.getOverCurrentA() != null) out.setOverCurrentA(patch.getOverCurrentA());
            if (patch.getOverPowerW() != null) out.setOverPowerW(patch.getOverPowerW());
            if (patch.getInterruptionTimeoutMs() != null) {
                out.setInterruptionTimeoutMs(patch.getInterruptionTimeoutMs());
            }
            if (patch.getBreachTimes() != null) out.setBreachTimes(patch.getBreachTimes());
            if (patch.getRecoverTimes() != null) out.setRecoverTimes(patch.getRecoverTimes());
            if (patch.getAlertsEnabled() != null) out.setAlertsEnabled(patch.getAlertsEnabled());
        }
        return out;
    }

    /** 当前保留窗口的最早起点（与最新采样窗口对齐，保证边界稳定）。 */
    private long retentionHorizon(long referenceMs) {
        long newestWindow = WindowCalculator.alignFloor(referenceMs, props.getWindowMs());
        return newestWindow - (long) (props.getRetainWindows() - 1) * props.getWindowMs();
    }

    private void evictAndRecompute(DeviceState state, long nowMs) {
        long newestSample = state.getLastSampleMs();
        long newestWindow = WindowCalculator.alignFloor(
                newestSample == 0 ? nowMs : newestSample, props.getWindowMs());
        long horizon = newestWindow - (long) (props.getRetainWindows() - 1) * props.getWindowMs();

        // 淘汰落在保留范围之外的采样点。
        TreeMap<Long, Sample> samples = state.getSamples();
        samples.headMap(horizon, false).clear();

        windowCalculator.recompute(state.getWindows(), samples, props.getWindowMs(), horizon, null);
    }

    private void persist(DeviceEntry entry) {
        repository.save(entry.state);
    }

    public static String validateDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId is required");
        }
        String id = deviceId.trim();
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException(
                    "deviceId must be 1-64 chars of [A-Za-z0-9_-], got: " + deviceId);
        }
        return id;
    }
}
