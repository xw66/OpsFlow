package io.github.xw66.opsflow.ticket;

import io.github.xw66.opsflow.common.ApiResponse;
import io.github.xw66.opsflow.ticket.TicketModels.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/tickets")
@SecurityRequirement(name = "bearerAuth")
public class TicketController {
    private final TicketService service;
    private final AttachmentService attachments;
    private final TicketCreationService creation;
    private final TicketDetailCache cache;

    public TicketController(TicketService service, AttachmentService attachments, TicketCreationService creation, TicketDetailCache cache) {
        this.service = service; this.attachments = attachments; this.creation = creation;
        this.cache = cache;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Detail> create(@Valid @RequestBody TicketInput input, Authentication actor,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[a-zA-Z0-9_-]{8,64}") String key) {
        return ApiResponse.success(creation.create(key, input, TicketActor.from(actor)));
    }

    @GetMapping
    public ApiResponse<List<Ticket>> list(Authentication actor, @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(service.list(TicketActor.from(actor), status, categoryId, offset, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@PathVariable @Positive long id, Authentication actor) {
        return ApiResponse.success(cache.detail(id, TicketActor.from(actor)));
    }

    @PutMapping("/{id}")
    public ApiResponse<Detail> edit(@PathVariable @Positive long id, @Valid @RequestBody EditInput input, Authentication actor) {
        return ApiResponse.success(service.edit(id, input, TicketActor.from(actor)));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Ticket> cancel(@PathVariable @Positive long id, @Valid @RequestBody ActionInput input, Authentication actor) {
        return ApiResponse.success(service.cancel(id, input, TicketActor.from(actor)));
    }

    @GetMapping("/{id}/history")
    public ApiResponse<List<History>> history(@PathVariable @Positive long id, Authentication actor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(service.history(id, TicketActor.from(actor), offset, limit));
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Comment> comment(@PathVariable @Positive long id, @Valid @RequestBody CommentInput input, Authentication actor) {
        return ApiResponse.success(service.comment(id, input, TicketActor.from(actor)));
    }

    @GetMapping("/{id}/comments")
    public ApiResponse<List<Comment>> comments(@PathVariable @Positive long id, Authentication actor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(service.comments(id, TicketActor.from(actor), offset, limit));
    }

    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Attachment> upload(@PathVariable @Positive long id, @RequestParam @PositiveOrZero long version,
            @RequestPart MultipartFile file, Authentication actor) throws IOException {
        return ApiResponse.success(attachments.upload(id, version, file, TicketActor.from(actor)));
    }

    @GetMapping("/{id}/attachments")
    public ApiResponse<List<Attachment>> attachments(@PathVariable @Positive long id, Authentication actor) {
        return ApiResponse.success(attachments.list(id, TicketActor.from(actor)));
    }

    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<Resource> download(@PathVariable @Positive long id, @PathVariable @Positive long attachmentId, Authentication actor) {
        Attachment attachment = attachments.find(id, attachmentId, TicketActor.from(actor));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(attachment.originalName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").contentLength(attachment.sizeBytes()).body(attachments.resource(attachment));
    }

    @DeleteMapping("/{id}/attachments/{attachmentId}")
    public ApiResponse<Void> deleteAttachment(@PathVariable @Positive long id, @PathVariable @Positive long attachmentId,
            @RequestParam @PositiveOrZero long version, Authentication actor) {
        attachments.delete(id, attachmentId, version, TicketActor.from(actor));
        return ApiResponse.success(null);
    }
}
