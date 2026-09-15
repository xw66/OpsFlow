package io.github.xw66.opsflow.ai;

import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {
    @Bean
    @ConditionalOnProperty(name = "opsflow.ai.enabled", havingValue = "true")
    ChatModel chatModel(@Value("${opsflow.ai.api-key:}") String apiKey,
            @Value("${opsflow.ai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${opsflow.ai.model:gpt-4o-mini}") String model,
            @Value("${opsflow.ai.timeout-ms:10000}") long timeoutMs) {
        if (apiKey.isBlank()) throw new IllegalArgumentException("启用AI时必须配置模型凭据");
        if (timeoutMs < 50 || timeoutMs > 30000) throw new IllegalArgumentException("AI单次超时必须为50至30000毫秒");
        return OpenAiChatModel.builder().options(OpenAiChatOptions.builder().apiKey(apiKey).baseUrl(baseUrl)
                .model(model).timeout(Duration.ofMillis(timeoutMs)).maxRetries(0).maxCompletionTokens(2000).build()).build();
    }
}
