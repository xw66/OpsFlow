package io.github.xw66.opsflow.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "opsflow.assignment.enabled", havingValue = "true", matchIfMissing = true)
public class AssignmentJob {
    private static final Logger log = LoggerFactory.getLogger(AssignmentJob.class);
    private final WorkflowMapper data;
    private final WorkflowService service;
    private final int batchSize;
    private long cursor;

    public AssignmentJob(WorkflowMapper data, WorkflowService service,
            @Value("${opsflow.assignment.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("自动分配批量必须为1至1000");
        this.data = data; this.service = service; this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${opsflow.assignment.interval-ms:5000}", initialDelayString = "${opsflow.assignment.interval-ms:5000}")
    public void scan() {
        var ids = data.unassignedIds(cursor, batchSize);
        for (long id : ids) {
            try { service.autoAssign(id); }
            catch (RuntimeException ex) { log.warn("自动分配失败，工单保留人工队列并在下次扫描重试：{}", id, ex); }
        }
        // 游标走完整个待分配集合后再回到开头，避免无候选人的旧工单挡住后面的工单。
        cursor = ids.size() < batchSize ? 0 : ids.getLast();
    }
}
