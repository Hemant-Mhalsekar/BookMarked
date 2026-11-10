package com.bookmarked.audit;

import com.bookmarked.auth.security.AuthUser;
import com.bookmarked.common.ClientInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Records every change a staff member attempts under /api/admin/** (any method but
 * GET), successful or not, so the trail can't miss an endpoint someone forgot to
 * audit. Request bodies are never stored (they can hold passwords); the action name,
 * path, path variables and response status are. Services that record a richer entry
 * themselves set {@link #RECORDED} so the action isn't logged twice.
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    public static final String RECORDED = "bookmarked.auditRecorded";
    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    private final AuditService audit;

    public AuditInterceptor(AuditService audit) {
        this.audit = audit;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // a richer entry was already written, unless the action failed before it could be
        boolean alreadyRecorded = request.getAttribute(RECORDED) != null && response.getStatus() < 400;
        if (READS.contains(request.getMethod()) || alreadyRecorded || !(handler instanceof HandlerMethod method)) {
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser staff) || !staff.isStaff()) {
            return;
        }
        String uri = request.getRequestURI();
        String[] parts = uri.replaceFirst("^/api/admin/?", "").split("/");
        String entityType = parts.length > 0 && !parts[0].isBlank() ? parts[0].toUpperCase().replace('-', '_') : "ADMIN";
        String entityId = parts.length > 1 ? parts[1] : "-";

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("method", request.getMethod());
        details.put("path", uri);
        details.put("status", response.getStatus());
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars instanceof Map<?, ?> map && !map.isEmpty()) {
            details.put("params", map);
        }
        String controller = method.getBeanType().getSimpleName().replace("Admin", "").replace("Controller", "");
        audit.record(staff.id(), controller + "." + method.getMethod().getName(), entityType, entityId, null, details,
                ClientInfo.from(request).ipAddress());
    }
}
