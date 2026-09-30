package com.github.xingjianluo.powertelemetryalertingjava.engine;

import com.github.xingjianluo.powertelemetryalertingjava.model.RejectCode;

/**
 * 单条记录校验结果。
 */
public record ValidationOutcome(boolean accepted, RejectCode code, String reason) {

    public static ValidationOutcome ok() {
        return new ValidationOutcome(true, null, null);
    }

    public static ValidationOutcome reject(RejectCode code, String reason) {
        return new ValidationOutcome(false, code, reason);
    }
}
