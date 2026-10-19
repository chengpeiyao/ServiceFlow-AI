package com.serviceflow.experiment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

/** 记录用户确认的标价估算，不能用估算替代服务商账单。 */
public record ExperimentPricing(BigDecimal inputPerMillion, BigDecimal outputPerMillion,
                                String currency, String source, String checkedOn) {
    public ExperimentPricing {
        if (inputPerMillion.signum() < 0 || outputPerMillion.signum() < 0 || currency.isBlank() || source.isBlank())
            throw new IllegalArgumentException("价格、币种和来源必须完整");
        LocalDate.parse(checkedOn);
    }
    public static ExperimentPricing from(Properties p) {
        if (!p.containsKey("pricing.inputPerMillion") && !p.containsKey("pricing.outputPerMillion")) return null;
        return new ExperimentPricing(new BigDecimal(p.getProperty("pricing.inputPerMillion", "")),
                new BigDecimal(p.getProperty("pricing.outputPerMillion", "")), p.getProperty("pricing.currency", ""),
                p.getProperty("pricing.source", ""), p.getProperty("pricing.checkedOn", ""));
    }
    public BigDecimal estimate(List<ModelClient.Attempt> attempts) {
        if (attempts.isEmpty() || attempts.stream().anyMatch(a -> a.inputTokens() == null || a.outputTokens() == null)) return null;
        BigDecimal value = BigDecimal.ZERO;
        for (var a : attempts) value = value.add(inputPerMillion.multiply(BigDecimal.valueOf(a.inputTokens())))
                .add(outputPerMillion.multiply(BigDecimal.valueOf(a.outputTokens())));
        return value.divide(BigDecimal.valueOf(1_000_000), 10, RoundingMode.HALF_UP);
    }
}
