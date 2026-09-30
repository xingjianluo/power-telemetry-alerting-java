package com.github.xingjianluo.powertelemetryalertingjava.persistence;

import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 服务启动时最先加载快照：采样、窗口、阈值、告警状态机与事件历史全部恢复，
 * 因此服务重启后结论与重启前一致、可复现。
 */
@Component
@Order(0)
public class SnapshotBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SnapshotBootstrap.class);

    private final SnapshotStore snapshotStore;
    private final DeviceRegistry registry;

    public SnapshotBootstrap(SnapshotStore snapshotStore, DeviceRegistry registry) {
        this.snapshotStore = snapshotStore;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        snapshotStore.loadInto(registry);
        log.info("启动状态恢复完成，已加载 {} 台设备", registry.size());
    }
}
