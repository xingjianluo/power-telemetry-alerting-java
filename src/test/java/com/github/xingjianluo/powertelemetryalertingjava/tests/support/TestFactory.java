package com.github.xingjianluo.powertelemetryalertingjava.tests.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.powertelemetryalertingjava.config.ConfigProperties;
import com.github.xingjianluo.powertelemetryalertingjava.engine.AlertEvaluator;
import com.github.xingjianluo.powertelemetryalertingjava.engine.DeviceRegistry;
import com.github.xingjianluo.powertelemetryalertingjava.engine.TelemetryValidator;
import com.github.xingjianluo.powertelemetryalertingjava.engine.WindowCalculator;
import com.github.xingjianluo.powertelemetryalertingjava.storage.DeviceStateRepository;

/** 组装不依赖 Spring 的引擎组件，方便纯单测。 */
public final class TestFactory {

    private TestFactory() {}

    public static ConfigProperties config() {
        ConfigProperties p = new ConfigProperties();
        p.validate();
        return p;
    }

    public static ObjectMapper mapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public static DeviceRegistry registry(ConfigProperties props, DeviceStateRepository repo, ManualClock clock) {
        return new DeviceRegistry(props, repo,
                new TelemetryValidator(props, clock),
                new WindowCalculator(),
                new AlertEvaluator(),
                clock);
    }
}
