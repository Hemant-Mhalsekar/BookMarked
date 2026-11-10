package com.bookmarked.mail;

import com.bookmarked.config.AppProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Transactional email. Every send is @Async so a slow SMTP server never slows
 * a request, and failures are logged instead of breaking the user's action.
 * Methods take plain values (never JPA entities) because they run on another thread.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a 'IST'").withZone(ZoneId.of("Asia/Kolkata"));

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final AppProperties props;

    public MailService(JavaMailSender mailSender, TemplateEngine templateEngine, AppProperties props) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.props = props;
    }

    public record Recipient(String email, String name) {
    }

    @Async
    public void sendEmailVerification(Recipient to, String rawToken) {
        String link = props.frontendUrl() + "/verify-email?token=" + encode(rawToken);
        send(to, "Verify your BookMarked email", "email/verify-email",
                Map.of("name", to.name(), "link", link, "hours", props.security().emailVerificationTtl().toHours()));
    }

    @Async
    public void sendPasswordReset(Recipient to, String rawToken) {
        String link = props.frontendUrl() + "/reset-password?token=" + encode(rawToken);
        send(to, "Reset your BookMarked password", "email/reset-password",
                Map.of("name", to.name(), "link", link, "minutes", props.security().passwordResetTtl().toMinutes()));
    }

    @Async
    public void sendPasswordChanged(Recipient to) {
        send(to, "Your BookMarked password was changed", "email/password-changed",
                Map.of("name", to.name(), "when", WHEN.format(Instant.now()),
                        "resetLink", props.frontendUrl() + "/forgot-password"));
    }

    @Async
    public void sendNewLoginAlert(Recipient to, String device, String ip, Instant when) {
        send(to, "New sign-in to your BookMarked account", "email/new-login",
                Map.of("name", to.name(), "device", device == null ? "Unknown device" : device,
                        "ip", ip == null ? "unknown" : ip, "when", WHEN.format(when),
                        "securityLink", props.frontendUrl() + "/account/security"));
    }

    @Async
    public void sendAccountLocked(Recipient to, long minutes) {
        send(to, "BookMarked account temporarily locked", "email/account-locked",
                Map.of("name", to.name(), "minutes", minutes,
                        "resetLink", props.frontendUrl() + "/forgot-password"));
    }

    @Async
    /** Sent to the NEW address: proves the person owns it. */
    public void sendEmailChangeConfirm(Recipient to, String rawToken, long hours) {
        String link = props.frontendUrl() + "/confirm-email-change?token=" + encode(rawToken);
        send(to, "Confirm your new BookMarked email", "email/email-change-confirm",
                Map.of("name", to.name(), "link", link, "hours", hours));
    }

    /** Sent to the OLD address when a change is requested ({@code done=false}) and when it happens. */
    public void sendEmailChangeAlert(Recipient to, String maskedNewEmail, boolean done) {
        send(to, done ? "Your BookMarked email was changed" : "Someone asked to change your BookMarked email",
                "email/email-change-alert",
                Map.of("name", to.name(), "newEmail", maskedNewEmail, "done", done, "when", WHEN.format(Instant.now()),
                        "resetLink", props.frontendUrl() + "/forgot-password"));
    }

    public void sendAccountDeleted(Recipient to) {
        send(to, "Your BookMarked account was deleted", "email/account-deleted",
                Map.of("name", to.name(), "when", WHEN.format(Instant.now())));
    }

    public void sendStaffInvite(Recipient to, String rawToken, String roleLabel, long hours) {
        String link = props.frontendUrl() + "/accept-invite?token=" + encode(rawToken);
        send(to, "You're invited to the BookMarked team", "email/staff-invite",
                Map.of("name", to.name(), "link", link, "role", roleLabel, "hours", hours));
    }

    /** One line of the order email, e.g. ("Clean Code", "Paperback × 2", "₹1,398"). */
    public record OrderLine(String title, String detail, String amount) {
    }

    @Async
    public void sendOrderConfirmation(Recipient to, String orderNumber, List<OrderLine> lines, String total,
                                      boolean cashOnDelivery, boolean hasEbooks, boolean hasPhysical) {
        send(to, "Order " + orderNumber + " confirmed", "email/order-confirmed",
                Map.of("name", to.name(), "orderNumber", orderNumber, "lines", lines, "total", total,
                        "cod", cashOnDelivery, "hasEbooks", hasEbooks, "hasPhysical", hasPhysical,
                        "orderLink", props.frontendUrl() + "/orders/" + orderNumber,
                        "libraryLink", props.frontendUrl() + "/library"));
    }

    /** Generic event email (shipped, delivered, cancelled, refund…); {@code path} is relative to the site. */
    @Async
    public void sendNotification(Recipient to, String title, String message, String path, String linkLabel) {
        Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("name", to.name());
        vars.put("title", title);
        vars.put("message", message);
        vars.put("link", path == null ? null : props.frontendUrl() + path);
        vars.put("linkLabel", linkLabel == null ? "View details" : linkLabel);
        send(to, title, "email/notification", vars);
    }

    private void send(Recipient to, String subject, String template, Map<String, Object> vars) {
        try {
            Context ctx = new Context();
            ctx.setVariables(vars);
            ctx.setVariable("frontendUrl", props.frontendUrl());
            String html = templateEngine.process(template, ctx);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(props.mailFrom());
            helper.setTo(to.email());
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.debug("Sent '{}' to {}", subject, to.email());
        } catch (MessagingException | RuntimeException e) {
            log.warn("Could not send '{}' to {}: {}", subject, to.email(), e.getMessage());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
