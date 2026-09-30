package com.github.xingjianluo.powertelemetryalertingjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本地 HTTP 全链路：批量上报（好点接受/坏点原因可区分）、窗口统计、告警查询、
 * 非法配置 400、未注册设备 404。
 */
@SpringBootTest(properties = {
        "power.service.snapshot-file=target/test-data/web-snapshot.json",
        "power.service.future-tolerance-ms=600000",
        "power.service.allowed-lateness-ms=600000",
        "power.service.max-history-ms=7200000",
        "power.service.default-over-voltage=250",
        "power.service.trigger-consecutive=3",
        "power.service.recover-consecutive=2",
        "power.service.interruption-sweep-ms=3600000",
        "power.service.snapshot-after-each-batch=false"
})
@AutoConfigureMockMvc
class WebApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(WebApiIntegrationTest.class);
    private static final ObjectMapper M = new ObjectMapper();

    static {
        // 类初始化先于 Spring 上下文加载：删除上次运行残留快照，保证测试可重复、相互独立
        try {
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("target/test-data/web-snapshot.json"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Autowired
    private MockMvc mvc;

    private long t = System.currentTimeMillis();

    @Test
    void fullLifecycleOverHttp() throws Exception {
        // 1) 一批数据：2 好点 + 缺字段 + 非法值，坏点不拖垮整批
        String body = """
                {"points":[
                  {"timestamp":%d,"voltage":220.0,"current":10.0,"power":2200.0},
                  {"timestamp":%d,"voltage":260.0,"current":10.0,"power":2300.0},
                  {"timestamp":%d,"current":10.0,"power":2300.0},
                  {"timestamp":%d,"voltage":-5.0,"current":10.0,"power":2300.0}
                ]}
                """.formatted(t, t + 1000, t + 2000, t + 3000);
        String resp = mvc.perform(post("/api/devices/web-dev/telemetry")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected[0].code").value("MISSING_FIELD"))
                .andExpect(jsonPath("$.rejected[1].code").value("INVALID_VALUE"))
                .andReturn().getResponse().getContentAsString();
        log.info("HTTP 上报响应: {}", resp);

        // 2) 再来 2 个连续过压点（共3连）触发告警
        String body2 = """
                {"points":[
                  {"timestamp":%d,"voltage":261.0,"current":10.0,"power":2400.0},
                  {"timestamp":%d,"voltage":262.0,"current":10.0,"power":2500.0}
                ]}
                """.formatted(t + 4000, t + 5000);
        mvc.perform(post("/api/devices/web-dev/telemetry")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsRaised[0].type").value("OVER_VOLTAGE"))
                .andExpect(jsonPath("$.alertsRaised[0].detail").isNotEmpty());

        // 3) 窗口统计可查询，峰值=2500W
        mvc.perform(get("/api/devices/web-dev/windows").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].peakPower").value(2500.0))
                .andExpect(jsonPath("$[0].sampleCount").value(4));

        // 4) 告警历史可查询、可按状态过滤
        mvc.perform(get("/api/devices/web-dev/alerts").param("state", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("OVER_VOLTAGE"))
                .andExpect(jsonPath("$[0].state").value("ACTIVE"));

        // 5) 非法配置 → 400，错误结构可区分
        String bad = """
                {"overVoltage":100.0,"underVoltage":200.0}
                """;
        mvc.perform(post("/api/devices/web-dev/thresholds")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ARGUMENT"));

        // 6) 未注册设备查询配置 → 404
        mvc.perform(get("/api/devices/no-such-device/config"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        // 7) 合法阈值更新 → 200
        String good = """
                {"overVoltage":260.0,"underVoltage":190.0,"overCurrent":32.0}
                """;
        mvc.perform(post("/api/devices/web-dev/thresholds")
                        .contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thresholds.overVoltage").value(260.0));

        log.info("HTTP 全链路验证通过：上报/拒绝原因/窗口/告警/400/404/配置更新");
    }
}
