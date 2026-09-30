package com.github.xingjianluo.powertelemetryalertingjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端到端 HTTP 验证：上报逐条拒绝原因、状态查询、配置鉴权与非法配置 400。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TelemetryApiIntegrationTest {

    /** 每个测试 JVM 使用独立临时数据目录，避免跨次运行的状态文件污染。 */
    static Path dataDir;

    @BeforeAll
    static void initDataDir() throws Exception {
        dataDir = Files.createTempDirectory("pta-test-data-");
    }

    @DynamicPropertySource
    static void dataDirProperty(DynamicPropertyRegistry registry) throws Exception {
        if (dataDir == null) {
            dataDir = Files.createTempDirectory("pta-test-data-");
        }
        registry.add("power.telemetry.data-dir", () -> dataDir.toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders jsonHeaders(String adminToken) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (adminToken != null) {
            h.set("X-Admin-Token", adminToken);
        }
        return h;
    }

    private String ingestBody(String device, String sampleJsonArray) {
        return "{\"deviceId\":\"" + device + "\",\"samples\":" + sampleJsonArray + "}";
    }

    @Test
    void ingestMixedBatchReturnsPerRecordReasons() throws Exception {
        long now = System.currentTimeMillis();
        String body = ingestBody("dev-api-1", "["
                + "{\"timestampMs\":" + (now - 1000) + ",\"voltageV\":220,\"currentA\":5,\"powerW\":1000},"
                + "{\"timestampMs\":" + (now - 2000) + ",\"currentA\":5,\"powerW\":1000},"
                + "{\"timestampMs\":" + (now - 3000) + ",\"voltageV\":-1,\"currentA\":5,\"powerW\":1000}"
                + "]");
        ResponseEntity<String> resp = rest.postForEntity(url("/api/telemetry"),
                new HttpEntity<>(body, jsonHeaders(null)), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode json = mapper.readTree(resp.getBody());
        assertEquals(3, json.get("total").asInt());
        assertEquals(1, json.get("accepted").asInt());
        assertEquals(2, json.get("rejected").asInt());
        String reasons = resp.getBody();
        assertTrue(reasons.contains("MISSING_VOLTAGE"), reasons);
        assertTrue(reasons.contains("INVALID_VALUE"), reasons);
    }

    @Test
    void deviceStateAndConfigEndpoints() throws Exception {
        long now = System.currentTimeMillis();
        String body = ingestBody("dev-api-2", "["
                + "{\"timestampMs\":" + (now - 1000) + ",\"voltageV\":220,\"currentA\":5,\"powerW\":1000}]");
        rest.postForEntity(url("/api/telemetry"), new HttpEntity<>(body, jsonHeaders(null)), String.class);

        ResponseEntity<String> state = rest.getForEntity(url("/api/devices/dev-api-2"), String.class);
        assertEquals(HttpStatus.OK, state.getStatusCode());
        JsonNode stateJson = mapper.readTree(state.getBody());
        assertEquals(1, stateJson.get("samples").size());
        assertEquals(1, stateJson.get("windows").size());
        assertNotNull(stateJson.get("alertRuntimes"));

        ResponseEntity<String> cfg = rest.getForEntity(url("/api/devices/dev-api-2/config"), String.class);
        assertEquals(HttpStatus.OK, cfg.getStatusCode());
        assertTrue(cfg.getBody().contains("\"overVoltageV\":253.0"), cfg.getBody());
    }

    @Test
    void configWriteRequiresAdminToken() {
        String patch = "{\"overVoltageV\":260.0}";
        ResponseEntity<String> noToken = rest.exchange(url("/api/devices/dev-api-3/config"),
                HttpMethod.PATCH, new HttpEntity<>(patch, jsonHeaders(null)), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, noToken.getStatusCode());
        ResponseEntity<String> badToken = rest.exchange(url("/api/devices/dev-api-3/config"),
                HttpMethod.PATCH, new HttpEntity<>(patch, jsonHeaders("wrong")), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, badToken.getStatusCode());
        ResponseEntity<String> ok = rest.exchange(url("/api/devices/dev-api-3/config"),
                HttpMethod.PATCH, new HttpEntity<>(patch, jsonHeaders("test-admin-token")), String.class);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertTrue(ok.getBody().contains("\"overVoltageV\":260.0"));
    }

    @Test
    void invalidConfigReturns400AndUnknownFieldsRejected() {
        String bad = "{\"overVoltageV\":100.0,\"underVoltageV\":200.0}";
        ResponseEntity<String> resp = rest.exchange(url("/api/devices/dev-api-4/config"),
                HttpMethod.PATCH, new HttpEntity<>(bad, jsonHeaders("test-admin-token")), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().contains("underVoltageV"), resp.getBody());

        String unknown = "{\"unexpectedField\":1}";
        ResponseEntity<String> unk = rest.exchange(url("/api/devices/dev-api-4/config"),
                HttpMethod.PATCH, new HttpEntity<>(unknown, jsonHeaders("test-admin-token")), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, unk.getStatusCode());
    }

    @Test
    void missingDeviceAndMalformedBodyReturnClearErrors() {
        assertEquals(HttpStatus.NOT_FOUND,
                rest.getForEntity(url("/api/devices/no-such-device"), String.class).getStatusCode());
        ResponseEntity<String> bad = rest.postForEntity(url("/api/telemetry"),
                new HttpEntity<>("not-json", jsonHeaders(null)), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        ResponseEntity<String> empty = rest.postForEntity(url("/api/telemetry"),
                new HttpEntity<>(ingestBody("dev-api-5", "[]"), jsonHeaders(null)), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());
    }
}
