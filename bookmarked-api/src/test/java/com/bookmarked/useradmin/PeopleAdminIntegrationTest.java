package com.bookmarked.useradmin;

import com.bookmarked.common.RateLimitService;
import com.bookmarked.mail.MailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff invites and roles, blocking and signing people out (effective at once,
 * not when the access token expires), customer management, and the audit log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "app.seed.demo-catalog=false",
        "app.seed.super-admin-email=admin@people.test",
        "app.seed.super-admin-password=Admin@12345",
        "app.seed.super-admin-phone=9000000012",
        "app.cookie.secure=false"
})
class PeopleAdminIntegrationTest {

    private static final String PASSWORD = "Str0ng@Pass";
    private static final AtomicInteger SERIAL = new AtomicInteger(100);

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    MailService mail;
    @MockitoBean
    RateLimitService rateLimit;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin@people.test", "Admin@12345");
    }

    @Test
    void a_new_staff_member_is_invited_sets_a_password_and_gets_only_their_role() throws Exception {
        int n = SERIAL.incrementAndGet();
        String email = "packer" + n + "@people.test";
        JsonNode created = json.readTree(mvc.perform(bearer(admin, post("/api/admin/staff"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Asha Packer", "email", email,
                                "phone", phone(n), "role", "order_manager"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(created.get("role").asText()).isEqualTo("ORDER_MANAGER");
        assertThat(created.get("invitePending").asBoolean()).isTrue();

        // nobody knows the password until the invite is used
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("identifier", email, "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
        String token = inviteToken();
        mvc.perform(post("/api/auth/accept-invite").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token, "newPassword", "weak"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/accept-invite").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token, "newPassword", PASSWORD))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/accept-invite").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token, "newPassword", PASSWORD))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("TOKEN_INVALID"));

        String packer = login(email, PASSWORD);
        mvc.perform(bearer(packer, get("/api/admin/orders"))).andExpect(status().isOk());
        // an Order Manager can't manage staff or see the audit log, and the attempt itself is audited
        long packerId = created.get("id").asLong();
        long adminId = jdbc.queryForObject("select id from users where email = 'admin@people.test'", Long.class);
        mvc.perform(bearer(packer, get("/api/admin/staff"))).andExpect(status().isForbidden());
        mvc.perform(bearer(packer, get("/api/admin/audit"))).andExpect(status().isForbidden());
        mvc.perform(bearer(packer, post("/api/admin/staff/" + adminId + "/block"))).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where staff_id = ? and entity_type = 'STAFF' and new_value like '%403%'",
                Integer.class, packerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select enabled from users where id = ?", Boolean.class, adminId)).isTrue();

        JsonNode list = json.readTree(mvc.perform(bearer(admin, get("/api/admin/staff")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode row = find(list, packerId);
        assertThat(row.get("invitePending").asBoolean()).isFalse();

        // duplicates and unknown roles are refused
        mvc.perform(bearer(admin, post("/api/admin/staff")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Copy Cat", "email", email,
                                "phone", phone(SERIAL.incrementAndGet()), "role", "ORDER_MANAGER"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("EMAIL_TAKEN"));
        mvc.perform(bearer(admin, post("/api/admin/staff")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Wannabe Boss", "email", "boss" + n + "@people.test",
                                "phone", phone(SERIAL.incrementAndGet()), "role", "CUSTOMER"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_ROLE"));
    }

    @Test
    void role_changes_and_blocks_take_effect_immediately() throws Exception {
        long id = staffMember("ORDER_MANAGER");
        String email = jdbc.queryForObject("select email from users where id = ?", String.class, id);
        String token = login(email, PASSWORD);
        mvc.perform(bearer(token, get("/api/admin/orders"))).andExpect(status().isOk());

        // new role: the old token (with the old permissions) stops working straight away
        JsonNode changed = json.readTree(mvc.perform(bearer(admin, patch("/api/admin/staff/" + id + "/role"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"INVENTORY_MANAGER\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(changed.get("role").asText()).isEqualTo("INVENTORY_MANAGER");
        mvc.perform(bearer(token, get("/api/admin/orders")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("SESSION_REVOKED"));
        String fresh = login(email, PASSWORD);
        mvc.perform(bearer(fresh, get("/api/admin/books"))).andExpect(status().isOk());
        mvc.perform(bearer(fresh, get("/api/admin/refunds"))).andExpect(status().isForbidden());

        // blocked: the token dies and logging in is refused
        mvc.perform(bearer(admin, post("/api/admin/staff/" + id + "/block"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(bearer(fresh, get("/api/admin/books"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("identifier", email, "password", PASSWORD))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("ACCOUNT_DISABLED"));
        mvc.perform(bearer(admin, post("/api/admin/staff/" + id + "/unblock"))).andExpect(status().isOk());
        assertThat(login(email, PASSWORD)).isNotBlank();

        // you can't do these to yourself
        long adminId = jdbc.queryForObject("select id from users where email = 'admin@people.test'", Long.class);
        mvc.perform(bearer(admin, post("/api/admin/staff/" + adminId + "/block")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("NOT_YOURSELF"));
        mvc.perform(bearer(admin, patch("/api/admin/staff/" + adminId + "/role"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"DEMO_ADMIN\"}"))
                .andExpect(status().isConflict());

        // the audit log has the before and after of the role change
        JsonNode audit = json.readTree(mvc.perform(bearer(admin, get("/api/admin/audit"))
                        .param("action", "Staff.changeRole").param("entityId", String.valueOf(id)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode entry = audit.get("content").get(0);
        assertThat(entry.get("oldValue").asText()).contains("ORDER_MANAGER");
        assertThat(entry.get("newValue").asText()).contains("INVENTORY_MANAGER");
        assertThat(entry.get("staffEmail").asText()).isEqualTo("admin@people.test");
        // the failed self-block is in the trail too
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where staff_id = ? and new_value like '%409%'",
                Integer.class, adminId)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void customers_can_be_found_blocked_and_signed_out() throws Exception {
        int n = SERIAL.incrementAndGet();
        String email = "customer" + n + "@people.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Ravi Kumar", "email", email,
                                "phone", phone(n), "password", PASSWORD))))
                .andExpect(status().isCreated());
        String token = login(email, PASSWORD);
        mvc.perform(bearer(token, get("/api/account/me"))).andExpect(status().isOk());

        JsonNode found = json.readTree(mvc.perform(bearer(admin, get("/api/admin/customers")).param("q", "customer" + n))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(found.get("totalElements").asInt()).isEqualTo(1);
        long id = found.get("content").get(0).get("id").asLong();

        JsonNode detail = json.readTree(mvc.perform(bearer(admin, get("/api/admin/customers/" + id)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.get("customer").get("email").asText()).isEqualTo(email);
        assertThat(detail.get("activeSessions").asInt()).isEqualTo(1);
        assertThat(detail.get("recentLogins")).isNotEmpty();

        mvc.perform(bearer(admin, post("/api/admin/customers/" + id + "/sign-out"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSessions").value(0));
        mvc.perform(bearer(token, get("/api/account/me"))).andExpect(status().isUnauthorized());

        String again = login(email, PASSWORD);
        mvc.perform(bearer(admin, post("/api/admin/customers/" + id + "/block"))).andExpect(status().isOk());
        mvc.perform(bearer(again, get("/api/account/me"))).andExpect(status().isUnauthorized());
        JsonNode blocked = json.readTree(mvc.perform(bearer(admin, get("/api/admin/customers")).param("status", "BLOCKED"))
                .andReturn().getResponse().getContentAsString());
        assertThat(blocked.get("content").findValuesAsText("email")).contains(email);
        mvc.perform(bearer(admin, post("/api/admin/customers/" + id + "/unblock"))).andExpect(status().isOk());
        // blocking ended every session, so a fresh login is needed after unblocking
        String fresh = login(email, PASSWORD);
        assertThat(fresh).isNotBlank();

        // customers can't see any of this; staff ids aren't customers
        mvc.perform(bearer(fresh, get("/api/admin/customers"))).andExpect(status().isForbidden());
        mvc.perform(bearer(fresh, get("/api/admin/audit"))).andExpect(status().isForbidden());
        long adminId = jdbc.queryForObject("select id from users where email = 'admin@people.test'", Long.class);
        mvc.perform(bearer(admin, get("/api/admin/customers/" + adminId))).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------

    /** An active staff member with PASSWORD set. */
    private long staffMember(String role) throws Exception {
        int n = SERIAL.incrementAndGet();
        JsonNode created = json.readTree(mvc.perform(bearer(admin, post("/api/admin/staff"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Staff Member", "email", "staff" + n + "@people.test",
                                "phone", phone(n), "role", role))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/auth/accept-invite").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", inviteToken(), "newPassword", PASSWORD))))
                .andExpect(status().isOk());
        return created.get("id").asLong();
    }

    /** The raw token from the most recent invite email. */
    private String inviteToken() {
        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).sendStaffInvite(any(), tokens.capture(), anyString(), anyLong());
        List<String> all = new ArrayList<>(tokens.getAllValues());
        return all.get(all.size() - 1);
    }

    private static String phone(int n) {
        return "98765" + String.format("%05d", n);
    }

    private static JsonNode find(JsonNode list, long id) {
        for (JsonNode n : list) {
            if (n.get("id").asLong() == id) {
                return n;
            }
        }
        throw new AssertionError("no row " + id);
    }

    private static MockHttpServletRequestBuilder bearer(String token, MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + token);
    }

    private String login(String identifier, String password) throws Exception {
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("identifier", identifier, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("accessToken").asText();
    }
}
