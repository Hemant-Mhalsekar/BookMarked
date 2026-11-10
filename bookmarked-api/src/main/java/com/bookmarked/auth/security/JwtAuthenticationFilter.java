package com.bookmarked.auth.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads "Authorization: Bearer ..." and, if the token is valid, puts an
 * {@link AuthUser} into the security context. An invalid or expired token
 * leaves the request anonymous; protected endpoints then answer 401.
 * <p>
 * Not a @Component on purpose: Spring Boot would otherwise also register it
 * as a plain servlet filter and it would run twice.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = "bookmarked.authError";

    private final JwtService jwtService;
    private final SessionGuard sessionGuard;

    public JwtAuthenticationFilter(JwtService jwtService, SessionGuard sessionGuard) {
        this.jwtService = jwtService;
        this.sessionGuard = sessionGuard;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            try {
                AuthUser user = jwtService.parse(token);
                if (sessionGuard.allows(user)) {
                    var auth = new UsernamePasswordAuthenticationToken(user, null, user.authorities());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } else {
                    // logged out, signed out by staff, or the account was blocked
                    request.setAttribute(AUTH_ERROR_ATTRIBUTE, "SESSION_REVOKED");
                }
            } catch (ExpiredJwtException e) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, "TOKEN_EXPIRED");
            } catch (JwtException | IllegalArgumentException e) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, "TOKEN_INVALID");
            }
        }
        chain.doFilter(request, response);
    }
}
