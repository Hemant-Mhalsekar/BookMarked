package com.bookmarked.auth;

import com.bookmarked.common.RateLimitService;
import com.bookmarked.mail.MailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end auth flows against a real MySQL (Testcontainers) with Flyway migrations.
 * Email sending is mocked so tests can read the raw tokens from the captured calls.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        // Zero grace: any replay of a rotated refresh token counts as theft.
        "app.security.refresh-reuse-grace=PT0S",
        "app.cookie.secure=false"
})
class AuthFlowIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    private static final String COOKIE = "bm_refresh";
    private static final String PASSWORD = "Str0ng@Pass";
    private static final AtomicInteger SEQ = new AtomicInteger(100);

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @MockitoBean
    MailService mail;
    @MockitoBean
    RateLimitService rateLimit;   // no-op: limits are covered separately

    @BeforeEach
    void resetMocks() {
        clearInvocations(mail);
    }

    // ------------------------------------------------------------------

    @Test
    void register_verify_login_withPhone_me_refresh_logout() throws Exception {
        Account acc = newAccount();
        String verifyToken = register(acc);

        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("token", verifyToken))))
                .andExpect(status().isOk());

        // Login with the PHONE number
        Login login = login(acc.phone(), PASSWORD);
        assertThat(login.user().get("emailVerified").asBoolean()).isTrue();
        assertThat(login.user().get("role").asText()).isEqualTo("CUSTOMER");

        mvc.perform(get("/api/account/me").header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(acc.email()))
                .andExpect(jsonPath("$.phone").value(acc.phone()));

        // Refresh rotates the cookie
        MvcResult refreshed = mvc.perform(post("/api/auth/refresh")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .cookie(new Cookie(COOKIE, login.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        String newRefresh = refreshed.getResponse().getCookie(COOKIE).getValue();
        assertThat(newRefresh).isNotEqualTo(login.refreshToken());

        // Logout ends the session; the cookie no longer works
        mvc.perform(post("/api/auth/logout").header("X-Requested-With", "XMLHttpRequest")
                        .cookie(new Cookie(COOKIE, newRefresh)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest")
                        .cookie(new Cookie(COOKIE, newRefresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("SESSION_REVOKED"));
    }

    @Test
    void login_withEmail_isCaseInsensitive() throws Exception {
        Account acc = newAccount();
        register(acc);
        Login login = login(acc.email().toUpperCase(), PASSWORD);
        assertThat(login.accessToken()).isNotBlank();
    }

    @Test
    void replaying_a_rotated_refresh_token_revokes_every_session() throws Exception {
        Account acc = newAccount();
        register(acc);
        Login login = login(acc.email(), PASSWORD);

        String rotated = mvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest")
                        .cookie(new Cookie(COOKIE, login.refreshToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(COOKIE).getValue();

        // Attacker replays the old token
        mvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest")
                        .cookie(new Cookie(COOKIE, login.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("SESSION_REVOKED"));

        // ...which also kills the legitimate, newer session
        mvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest")
                        .cookie(new Cookie(COOKIE, rotated)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicate_email_and_phone_are_rejected() throws Exception {
        Account acc = newAccount();
        register(acc);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("fullName", "Other Person", "email", acc.email(),
                                "phone", newAccount().phone(), "password", PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_TAKEN"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("fullName", "Other Person", "email", newAccount().email(),
                                "phone", acc.phone(), "password", PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PHONE_TAKEN"));
    }

    @Test
    void weak_password_and_bad_phone_fail_validation() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("fullName", "Test User", "email", "weak@example.com",
                                "phone", "1234567890", "password", "password"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.password").exists())
                .andExpect(jsonPath("$.fieldErrors.phone").exists());
    }

    @Test
    void five_wrong_passwords_lock_the_account() throws Exception {
        Account acc = newAccount();
        register(acc);
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("identifier", acc.email(), "password", "Wrong@Pass1"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
        }
        // Even the right password is refused while locked
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("identifier", acc.email(), "password", PASSWORD))))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value("ACCOUNT_LOCKED"));
        verify(mail).sendAccountLocked(any(), anyLong());
    }

    @Test
    void unknown_account_gets_the_same_error_as_a_wrong_password() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("identifier", "nobody@example.com", "password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
    }

    @Test
    void forgot_and_reset_password_signs_out_everywhere() throws Exception {
        Account acc = newAccount();
        register(acc);
        Login login = login(acc.email(), PASSWORD);

        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("identifier", acc.phone()))))
                .andExpect(status().isOk());
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mail).sendPasswordReset(any(), token.capture());

        String newPassword = "N3w@Password";
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("token", token.getValue(), "newPassword", newPassword))))
                .andExpect(status().isOk());

        // Old session is gone, old password fails, new password works
        mvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest")
                        .cookie(new Cookie(COOKIE, login.refreshToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("identifier", acc.email(), "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
        assertThat(login(acc.email(), newPassword).accessToken()).isNotBlank();

        // The link is single-use
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("token", token.getValue(), "newPassword", "An0ther@Pass"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("TOKEN_INVALID"));
    }

    @Test
    void sessions_list_marks_current_device_and_can_log_out_others() throws Exception {
        Account acc = newAccount();
        register(acc);
        Login laptop = login(acc.email(), PASSWORD);
        Login phone = login(acc.phone(), PASSWORD);

        String sessions = mvc.perform(get("/api/account/sessions")
                        .header("Authorization", "Bearer " + laptop.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        long currentCount = 0;
        for (JsonNode s : json.readTree(sessions)) {
            if (s.get("current").asBoolean()) {
                currentCount++;
            }
        }
        assertThat(currentCount).isEqualTo(1);

        mvc.perform(delete("/api/account/sessions").header("Authorization", "Bearer " + laptop.accessToken()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest")
                        .cookie(new Cookie(COOKIE, phone.refreshToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest")
                        .cookie(new Cookie(COOKIE, laptop.refreshToken())))
                .andExpect(status().isOk());

        mvc.perform(get("/api/account/login-history").header("Authorization", "Bearer " + laptop.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void change_password_keeps_this_device_and_ends_others() throws Exception {
        Account acc = newAccount();
        register(acc);
        Login current = login(acc.email(), PASSWORD);
        Login other = login(acc.phone(), PASSWORD);

        mvc.perform(post("/api/account/change-password").header("Authorization", "Bearer " + current.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("currentPassword", PASSWORD, "newPassword", "Chang3d@Pass"))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest")
                        .cookie(new Cookie(COOKIE, other.refreshToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest")
                        .cookie(new Cookie(COOKIE, current.refreshToken())))
                .andExpect(status().isOk());
    }

    @Test
    void protected_endpoints_need_a_token_and_refresh_needs_the_csrf_header() throws Exception {
        mvc.perform(get("/api/account/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mvc.perform(get("/api/account/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_INVALID"));
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie(COOKIE, "anything")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CSRF_CHECK_FAILED"));
    }

    @Test
    void customers_cannot_reach_admin_endpoints() throws Exception {
        Account acc = newAccount();
        register(acc);
        Login login = login(acc.email(), PASSWORD);
        mvc.perform(get("/api/admin/anything").header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    record Account(String fullName, String email, String phone) {
    }

    record Login(String accessToken, String refreshToken, JsonNode user) {
    }

    private Account newAccount() {
        int n = SEQ.incrementAndGet();
        // Names may only contain letters, so the number is spelled with letters (101 -> "Reader Bab").
        return new Account("Reader " + lettersFor(n), "reader" + n + "@example.com", "98765" + String.format("%05d", n));
    }

    private static String lettersFor(int n) {
        StringBuilder sb = new StringBuilder();
        for (char c : Integer.toString(n).toCharArray()) {
            sb.append((char) ('a' + (c - '0')));
        }
        sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
        return sb.toString();
    }

    /** Registers and returns the raw email-verification token captured from the mail mock. */
    private String register(Account acc) throws Exception {
        clearInvocations(mail);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("fullName", acc.fullName(), "email", acc.email(),
                                "phone", acc.phone(), "password", PASSWORD))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerified").value(false));
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mail).sendEmailVerification(any(), token.capture());
        return token.getValue();
    }

    private Login login(String identifier, String password) throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/130.0")
                        .content(body(Map.of("identifier", identifier, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = json.readTree(res.getResponse().getContentAsString());
        Cookie cookie = res.getResponse().getCookie(COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        return new Login(node.get("accessToken").asText(), cookie.getValue(), node.get("user"));
    }

    private String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }
}
