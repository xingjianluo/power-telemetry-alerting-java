package com.github.xingjianluo.powertelemetryalertingjava.engine;

import com.github.xingjianluo.powertelemetryalertingjava.api.dto.TelemetryPointDto;
import com.github.xingjianluo.powertelemetryalertingjava.config.ServiceProperties;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertEvent;
import com.github.xingjianluo.powertelemetryalertingjava.model.IngestResult;
import com.github.xingjianluo.powertelemetryalertingjava.model.RejectedRecord;
import com.github.xingjianluo.powertelemetryalertingjava.model.TelemetrySample;
import com.github.xingjianluo.powertelemetryalertingjava.persistence.SnapshotStore;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceRegistry;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * 遥测接入主服务。
 *
 * <p>一致性模型：同一设备的所有批次由其公平重入锁串行化，因此并发批次不会重复累计、
 * 不会漏计；批内先逐记录校验（坏点隔离），通过的点一次性合入。</p>
 *
 * <p>乱序模型：新点若落在当前水位线之前（允许迟到窗口内），状态机无法做增量回退，
 * 此时按采样时间对设备全部点做一次确定性重放：清空窗口与告警状态机后逐点重算，
 * 告警事件流被刷新为与"数据本就按序到达"一致的结论，保证可复现、可追溯。</p>
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final DeviceRegistry registry;
    private final RecordValidator validator;
    private final AlertEvaluator alertEvaluator;
    private final InterruptionMonitor interruptionMonitor;
    private final WindowManager windowManager;
    private final SnapshotStore snapshotStore;
    private final ServiceProperties properties;
    private final LongSupplier clock;

    public IngestService(DeviceRegistry registry,
                         RecordValidator validator,
                         AlertEvaluator alertEvaluator,
                         InterruptionMonitor interruptionMonitor,
                         WindowManager windowManager,
                         SnapshotStore snapshotStore,
                         ServiceProperties properties,
                         LongSupplier clock) {
        this.registry = registry;
        this.validator = validator;
        this.alertEvaluator = alertEvaluator;
        this.interruptionMonitor = interruptionMonitor;
        this.windowManager = windowManager;
        this.snapshotStore = snapshotStore;
        this.properties = properties;
        this.clock = clock;
    }

    public IngestResult ingest(String deviceId, List<TelemetryPointDto> points) {
        long batchReceivedAt = clock.getAsLong();
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("设备编号缺失");
        }
        List<TelemetryPointDto> safe = points == null ? List.of() : points;

        DeviceState state = registry.getOrCreate(deviceId, batchReceivedAt);
        state.getLock().lock();
        try {
            List<RejectedRecord> rejected = new ArrayList<>();
            List<TelemetrySample> accepted = new ArrayList<>();
            int duplicate = 0;
            // 批内同采样时间也视为重复：第一遍校验时尚未合入 state，需要单独记录
            java.util.Set<Long> seenInBatch = new java.util.HashSet<>();

            // ---- 第一遍：逐条隔离校验，坏点不影响其他记录 ----
            for (int i = 0; i < safe.size(); i++) {
                TelemetryPointDto p = safe.get(i);
                Long ts = p == null ? null : p.timestamp();
                Double v = p == null ? null : p.voltage();
                Double c = p == null ? null : p.current();
                Double w = p == null ? null : p.power();

                ValidationOutcome fields = validator.validateFields(deviceId, ts, v, c, w);
                if (!fields.accepted()) {
                    rejected.add(new RejectedRecord(i, deviceId, ts == null ? -1L : ts,
                            fields.code(), fields.reason()));
                    log.info("拒绝接入 device={} index={} sampleTime={} code={} reason={}",
                            deviceId, i, ts, fields.code(), fields.reason());
                    continue;
                }
                TelemetrySample sample = new TelemetrySample(deviceId, ts, v, c, w, batchReceivedAt);
                if (!seenInBatch.add(ts)) {
                    duplicate++;
                    rejected.add(new RejectedRecord(i, deviceId, ts,
                            com.github.xingjianluo.powertelemetryalertingjava.model.RejectCode.DUPLICATE,
                            "批次内存在相同采样时间 " + ts + " 的重复记录，不重复累计"));
                    log.info("拒绝接入 device={} index={} sampleTime={} code=DUPLICATE reason=批次内重复",
                            deviceId, i, ts);
                    continue;
                }
                ValidationOutcome time = validator.validateTime(sample, state,
                        properties.getMaxHistoryMs(), properties.getFutureToleranceMs(),
                        properties.getAllowedLatenessMs());
                if (!time.accepted()) {
                    if (time.code() == com.github.xingjianluo.powertelemetryalertingjava.model.RejectCode.DUPLICATE) {
                        duplicate++;
                    }
                    rejected.add(new RejectedRecord(i, deviceId, ts, time.code(), time.reason()));
                    log.info("拒绝接入 device={} index={} sampleTime={} code={} reason={}",
                            deviceId, i, ts, time.code(), time.reason());
                    continue;
                }
                accepted.add(sample);
            }

            if (accepted.isEmpty()) {
                log.info("批次处理完成 device={} accepted=0 duplicate={} rejected={}（无有效新点，状态不变）",
                        deviceId, duplicate, rejected.size());
                return new IngestResult(deviceId, 0, duplicate, rejected, List.of(), List.of());
            }

            // ---- 第二遍：判断是否需要按采样时间确定性重放 ----
            boolean needsReplay = accepted.stream()
                    .anyMatch(s -> state.getWatermark() != Long.MIN_VALUE
                            && s.sampleTime() < state.getWatermark());
            if (needsReplay) {
                for (TelemetrySample s : accepted) {
                    state.getSamples().put(s.sampleTime(), s);
                }
                log.info("检测到乱序点 device={} newPoints={}，按采样时间重放全部 {} 点",
                        deviceId, accepted.size(), state.getSamples().size());
            }

            List<AlertEvent> raised;
            List<AlertEvent> cleared;
            if (needsReplay) {
                ReplayResult rr = replay(state);
                raised = rr.raised;
                cleared = rr.cleared;
            } else {
                IncrementalResult ir = advanceIncrementally(state, accepted);
                raised = ir.raised;
                cleared = ir.cleared;
            }

            log.info("批次处理完成 device={} accepted={} duplicate={} rejected={} raised={} cleared={} watermark={} windows={}",
                    deviceId, accepted.size(), duplicate, rejected.size(),
                    raised.stream().map(e -> e.type() + "@" + e.sampleTime()).toList(),
                    cleared.stream().map(e -> e.type() + "@" + e.sampleTime()).toList(),
                    state.getWatermark(), state.getWindows().size());

            if (properties.isSnapshotAfterEachBatch()) {
                snapshotStore.save(registry);
            }
            return new IngestResult(deviceId, accepted.size(), duplicate, rejected, raised, cleared);
        } finally {
            state.getLock().unlock();
        }
    }

    /** 增量推进：本批新点全部不早于水位线时使用（常见热路径）。 */
    private IncrementalResult advanceIncrementally(DeviceState state, List<TelemetrySample> accepted) {
        List<AlertEvent> raised = new ArrayList<>();
        List<AlertEvent> cleared = new ArrayList<>();
        TreeMap<Long, TelemetrySample> samples = state.getSamples();

        for (TelemetrySample s : accepted) {
            samples.put(s.sampleTime(), s);
            windowManager.accept(state, s, properties.getWindowMs());

            TelemetrySample previous = samples.lowerEntry(s.sampleTime()) != null
                    ? samples.lowerEntry(s.sampleTime()).getValue() : null;
            dispatch(state, s, previous, raised, cleared);

            if (s.sampleTime() > state.getWatermark()) {
                state.setWatermark(s.sampleTime());
            }
        }
        windowManager.closeAndEvict(state, state.getWatermark(), properties.getWindowMs(),
                properties.getAllowedLatenessMs(), properties.getRetainedWindows());
        return new IncrementalResult(raised, cleared);
    }

    /**
     * 确定性重放：清空窗口与告警状态机（保留配置/采样/事件历史容器），
     * 按采样时间逐点重算。事件流刷新为重放结果（先记录恢复、再记录触发，时间有序），
     * 事件编号从 1 重新连续编号。
     */
    public ReplayResult replay(DeviceState state) {
        state.getWindows().clear();
        state.getAlertStatuses().clear();
        state.getAlertEvents().clear();
        state.setAlertEventSeq(0L);
        state.setWatermark(Long.MIN_VALUE);

        List<AlertEvent> raised = new ArrayList<>();
        List<AlertEvent> cleared = new ArrayList<>();
        TelemetrySample previous = null;
        for (TelemetrySample s : state.getSamples().values()) {
            windowManager.accept(state, s, properties.getWindowMs());
            dispatch(state, s, previous, raised, cleared);
            previous = s;
            state.setWatermark(s.sampleTime());
        }
        windowManager.closeAndEvict(state, state.getWatermark(), properties.getWindowMs(),
                properties.getAllowedLatenessMs(), properties.getRetainedWindows());

        // 重放后按 (sampleTime, id) 对事件排序并重新连续编号，保证事件流时间有序
        List<AlertEvent> all = new ArrayList<>(state.getAlertEvents());
        all.sort(java.util.Comparator.comparingLong(AlertEvent::sampleTime)
                .thenComparingLong(AlertEvent::id));
        state.getAlertEvents().clear();
        for (int i = 0; i < all.size(); i++) {
            AlertEvent e = all.get(i);
            AlertEvent renumbered = new AlertEvent(i + 1L, e.deviceId(), e.type(), e.state(),
                    e.sampleTime(), e.eventTime(), e.detail());
            state.getAlertEvents().add(renumbered);
        }
        state.setAlertEventSeq(all.size());
        raised.clear();
        cleared.clear();
        for (AlertEvent e : state.getAlertEvents()) {
            (e.state() == com.github.xingjianluo.powertelemetryalertingjava.model.AlertState.ACTIVE
                    ? raised : cleared).add(e);
        }
        return new ReplayResult(raised, cleared);
    }

    /** 喂入单个采样点：四类电气告警 + 采样中断，事件按产生顺序入历史。 */
    private void dispatch(DeviceState state, TelemetrySample s, TelemetrySample previous,
                          List<AlertEvent> raised, List<AlertEvent> cleared) {
        List<AlertEvent> electrical = alertEvaluator.evaluate(state, s,
                properties.getTriggerConsecutive(), properties.getRecoverConsecutive());
        List<AlertEvent> interrupt = interruptionMonitor.onSample(state, previous, s);
        for (AlertEvent e : electrical) {
            (e.state() == com.github.xingjianluo.powertelemetryalertingjava.model.AlertState.ACTIVE
                    ? raised : cleared).add(e);
        }
        for (AlertEvent e : interrupt) {
            (e.state() == com.github.xingjianluo.powertelemetryalertingjava.model.AlertState.ACTIVE
                    ? raised : cleared).add(e);
        }
    }

    private record IncrementalResult(List<AlertEvent> raised, List<AlertEvent> cleared) {
    }

    /** 重放结果。 */
    public record ReplayResult(List<AlertEvent> raised, List<AlertEvent> cleared) {
    }
}
