package io.github.xw66.opsflow.ai;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class AiWorker {
    private final AiWorkService work;
    private final AiModelService model;
    private final ObjectMapper json;
    private final boolean enabled;
    public AiWorker(AiWorkService work, AiModelService model, ObjectMapper json, @Value("${opsflow.ai.worker-enabled:true}") boolean enabled) {
        this.work = work; this.model = model; this.json = json; this.enabled = enabled;
    }
    @Scheduled(fixedDelayString = "${opsflow.ai.interval-ms:2000}")
    public void scan() { if (enabled) processOne(); }
    public boolean processOne() {
        var lease = work.claim();
        if (lease == null) return false;
        var pending = lease.pending();
        long started = System.nanoTime();
        AiModels.CallResult result;
        try {
            Set<String> categories = json.readValue(pending.categoriesJson(), new TypeReference<Set<String>>() { });
            // 网络调用不占用数据库事务；崩溃后租约恢复，旧持有者不能覆盖新的结果。
            result = model.call(pending.kind(), pending.inputText(), categories);
        } catch (RuntimeException ex) {
            result = new AiModels.CallResult("FAILED", null, "unknown", null, null,
                    (System.nanoTime() - started) / 1_000_000, 0, ex.getClass().getSimpleName());
        }
        work.finish(lease, result);
        return true;
    }
}
