package com.github.xingjianluo.powertelemetryalertingjava.api;

import com.github.xingjianluo.powertelemetryalertingjava.api.dto.TelemetryBatchDto;
import com.github.xingjianluo.powertelemetryalertingjava.engine.IngestService;
import com.github.xingjianluo.powertelemetryalertingjava.model.IngestResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 遥测上报接口。
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/telemetry")
public class TelemetryController {

    private final IngestService ingestService;

    public TelemetryController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping
    public ResponseEntity<IngestResult> upload(@PathVariable String deviceId,
                                               @RequestBody TelemetryBatchDto batch) {
        return ResponseEntity.ok(ingestService.ingest(deviceId, batch.points()));
    }
}
