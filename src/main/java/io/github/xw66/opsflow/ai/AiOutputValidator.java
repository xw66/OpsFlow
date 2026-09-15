package io.github.xw66.opsflow.ai;

import io.github.xw66.opsflow.ai.AiModels.*;
import jakarta.validation.Validator;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

@Component
public class AiOutputValidator {
    private static final Pattern PRIORITY = Pattern.compile("(\\\"priority\\\"\\s*:\\s*\\\")(low|normal|medium|high|urgent|p[0-3])(\\\")", Pattern.CASE_INSENSITIVE);
    private final Validator validator;
    private final Map<Kind, BeanOutputConverter<?>> converters = Map.of(
            Kind.CLASSIFICATION, new BeanOutputConverter<>(Classification.class),
            Kind.SUMMARY, new BeanOutputConverter<>(Summary.class),
            Kind.REPLY, new BeanOutputConverter<>(Reply.class));
    public AiOutputValidator(Validator validator) { this.validator = validator; }
    public String schema(Kind kind) { return converters.get(kind).getJsonSchema(); }

    public Object validate(Kind kind, String raw, Set<String> categories) {
        if (raw == null || raw.isBlank() || raw.length() > 16000) throw new IllegalArgumentException("AI输出为空或过长");
        Object result;
        try { result = converters.get(kind).convert(normalize(kind, raw)); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("AI输出格式不合法", ex); }
        // JSON Schema不能替代服务端校验，分类还必须属于本次允许的实际业务分类。
        if (result == null || !validator.validate(result).isEmpty()) throw new IllegalArgumentException("AI输出字段校验失败");
        if (result instanceof Classification classification && !categories.contains(classification.category())) {
            throw new IllegalArgumentException("AI分类不在允许范围内");
        }
        return result;
    }

    private String normalize(Kind kind, String raw) {
        if (kind == Kind.CLASSIFICATION) raw = raw.replace("\"labels\"", "\"tags\"");
        if (kind == Kind.REPLY && !raw.contains("\"suggestion\"")) {
            return raw.replace("\"reply_draft\"", "\"suggestion\"");
        }
        if (kind == Kind.SUMMARY && !raw.contains("\"summary\"")) {
            String escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\r", "\\r").replace("\n", "\\n");
            return "{\"summary\":\"" + escaped + "\"}";
        }
        if (kind != Kind.CLASSIFICATION) return raw;
        Matcher matcher = PRIORITY.matcher(raw);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) {
            String value = switch (matcher.group(2).toUpperCase(Locale.ROOT)) {
                case "P0" -> "URGENT";
                case "P1" -> "HIGH";
                case "P2" -> "NORMAL";
                case "P3" -> "LOW";
                case "MEDIUM" -> "NORMAL";
                default -> matcher.group(2).toUpperCase(Locale.ROOT);
            };
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(matcher.group(1) + value + matcher.group(3)));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }
}
