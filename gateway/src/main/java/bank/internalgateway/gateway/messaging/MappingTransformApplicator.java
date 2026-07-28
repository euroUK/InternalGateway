package bank.internalgateway.gateway.messaging;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class MappingTransformApplicator {

    private static final int DEFAULT_PERCENT_SCALE = 6;

    public Object apply(Object value, EventMappingModels.TransformRule transform) {
        if (value == null) {
            return null;
        }
        if (transform == null || transform.rule() == null || transform.rule().isBlank()) {
            return value;
        }
        return switch (transform.rule()) {
            case "percent-to-decimal" -> percentToDecimal(value, transform.scale());
            case "identity" -> value;
            default -> throw new IllegalArgumentException("Unsupported mapping transform rule: " + transform.rule());
        };
    }

    private BigDecimal percentToDecimal(Object value, Integer scale) {
        int effectiveScale = scale != null ? scale : DEFAULT_PERCENT_SCALE;
        BigDecimal decimal = toBigDecimal(value);
        return decimal.divide(BigDecimal.valueOf(100), effectiveScale, RoundingMode.HALF_UP);
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }
}
