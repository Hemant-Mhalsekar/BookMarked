package com.bookmarked.notification;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications", description = "In-app notifications (customers and staff)")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Operation(summary = "My notifications, newest first")
    @GetMapping
    public PageResponse<NotificationService.Item> list(@AuthenticationPrincipal AuthUser caller,
                                                       @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "10") int size) {
        return notifications.list(caller, unreadOnly, page, size);
    }

    @Operation(summary = "Unread count for the bell")
    @GetMapping("/unread-count")
    public NotificationService.UnreadCount unread(@AuthenticationPrincipal AuthUser caller) {
        return notifications.unread(caller);
    }

    @Operation(summary = "Mark one as read")
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@AuthenticationPrincipal AuthUser caller, @PathVariable long id) {
        notifications.markRead(caller, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Mark all as read")
    @PostMapping("/read-all")
    public NotificationService.UnreadCount readAll(@AuthenticationPrincipal AuthUser caller) {
        return notifications.markAllRead(caller);
    }
}
