package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.common.BusinessException;
import io.github.xw66.opsflow.ticket.TicketModels.Attachment;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AttachmentService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);
    private final TicketService tickets;
    private final TicketMapper data;
    private final Clock clock;
    // ponytail: 本地文件存储仅适合单实例，多实例前改共享存储并加入孤儿文件清理。
    private final Path root;

    public AttachmentService(TicketService tickets, TicketMapper data, Clock clock,
            @Value("${opsflow.attachments.directory:data/attachments}") String directory) {
        this.tickets = tickets; this.data = data; this.clock = clock;
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Transactional(rollbackFor = IOException.class)
    public Attachment upload(long ticketId, long version, MultipartFile file, TicketActor actor) throws IOException {
        var ticket = tickets.read(ticketId, actor);
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 128 || name.contains("/") || name.contains("\\")
                || name.contains(":") || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FILENAME", "附件文件名不合法");
        }
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FILE_SIZE", "附件必须为1字节至5MB");
        }
        byte[] bytes = file.getBytes();
        String type = detectType(bytes);
        var now = clock.instant();
        // 先取得工单版本的写锁，再计数和写入，避免并发上传突破附件数量限制。
        tickets.touch(ticket, version, now, null);
        if (data.attachments(ticketId).size() >= 10) {
            throw new BusinessException(HttpStatus.CONFLICT, "ATTACHMENT_LIMIT", "每个工单最多10个附件");
        }
        String key = UUID.randomUUID().toString();
        Files.createDirectories(root);
        Path path = path(key);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) deleteFile(path);
            }
        });
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
        data.attachment(ticketId, actor.id(), name, key, type, bytes.length, now);
        Attachment result = data.attachmentById(ticketId, data.insertedId());
        tickets.audit(actor.id(), "ATTACHMENT_UPLOADED", ticketId, null, Map.of("attachmentId", result.id()), "上传附件");
        return result;
    }

    public List<Attachment> list(long ticketId, TicketActor actor) {
        tickets.read(ticketId, actor);
        return data.attachments(ticketId);
    }

    public Attachment find(long ticketId, long id, TicketActor actor) {
        tickets.read(ticketId, actor);
        Attachment attachment = data.attachmentById(ticketId, id);
        if (attachment == null) throw new BusinessException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件不存在");
        return attachment;
    }

    public Resource resource(Attachment attachment) {
        Path path = path(attachment.storageKey());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ATTACHMENT_FILE_MISSING", "附件文件不可用");
        }
        return new FileSystemResource(path);
    }

    @Transactional
    public void delete(long ticketId, long id, long version, TicketActor actor) {
        Attachment attachment = find(ticketId, id, actor);
        if (attachment.uploaderId() != actor.id() && !actor.admin()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "只能删除自己上传的附件");
        }
        tickets.touch(tickets.read(ticketId, actor), version, clock.instant(), null);
        data.deleteAttachment(ticketId, id);
        tickets.audit(actor.id(), "ATTACHMENT_DELETED", ticketId, Map.of("attachmentId", id), null, "删除附件");
        // 文件系统不能参与数据库事务，只有提交成功后才移除文件，避免回滚导致数据丢失。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { deleteFile(path(attachment.storageKey())); }
        });
    }

    private Path path(String key) {
        if (!key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalStateException("附件存储标识不合法");
        }
        return root.resolve(key);
    }

    private String detectType(byte[] bytes) {
        if (bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-') return "application/pdf";
        byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (bytes.length >= 8 && java.util.Arrays.equals(png, java.util.Arrays.copyOf(bytes, 8))) return "image/png";
        if (bytes.length >= 3 && bytes[0] == (byte) 255 && bytes[1] == (byte) 216 && bytes[2] == (byte) 255) return "image/jpeg";
        throw new BusinessException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_ATTACHMENT", "仅允许 PDF、PNG、JPEG 附件");
    }

    private void deleteFile(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException ex) { log.error("附件文件清理失败，需核对数据库引用后清理：{}", path, ex); }
    }
}
