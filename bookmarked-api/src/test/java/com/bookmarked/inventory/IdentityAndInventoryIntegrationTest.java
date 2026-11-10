package com.bookmarked.inventory;

import com.bookmarked.catalog.CategoryRepository;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 7c: stock adjustments and the ledger, changing email (confirmed by the new
 * address, announced to the old one) and deleting an account (anonymised, blocked
 * while an order is in progress).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "app.seed.demo-catalog=false",
        "app.seed.super-admin-email=admin@stock.test",
        "app.seed.super-admin-password=Admin@12345",
        "app.seed.super-admin-phone=9000000013",
        "app.cookie.secure=false"
})
class IdentityAndInventoryIntegrationTest {

    private static final String PASSWORD = "Str0ng@Pass";
    private static final AtomicInteger SERIAL = new AtomicInteger(100);
    private static final AtomicInteger ISBN_SERIAL = new AtomicInteger(4000);

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CategoryRepository categories;

    @MockitoBean
    MailService mail;
    @MockitoBean
    RateLimitService rateLimit;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin@stock.test", "Admin@12345");
    }

    // ------------------------------------------------------------------
    // Inventory
    // ------------------------------------------------------------------

    @Test
    void stock_is_adjusted_atomically_with_a_ledger_and_an_audit_entry() throws Exception {
        long variant = createBook("Stock Book " + SERIAL.incrementAndGet(), "PAPERBACK", 3);

        JsonNode low = json.readTree(mvc.perform(bearer(admin, get("/api/admin/inventory")).param("filter", "LOW"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(low.get("rows").get("content").findValuesAsText("variantId")).contains(String.valueOf(variant));
        assertThat(low.get("lowCount").asInt()).isGreaterThanOrEqualTo(1);

        mvc.perform(adjust(variant, "RESTOCK", 20, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(23)).andExpect(jsonPath("$.lowStock").value(false));
        // a restock must add; a correction needs a reason; stock never goes negative
        mvc.perform(adjust(variant, "RESTOCK", -1, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_QUANTITY"));
        mvc.perform(adjust(variant, "ADJUSTMENT", -2, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("NOTE_REQUIRED"));
        mvc.perform(adjust(variant, "ADJUSTMENT", -24, "Counted shelf")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STOCK_NEGATIVE"));
        mvc.perform(adjust(variant, "ADJUSTMENT", -2, "Water damaged")).andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(21));
        mvc.perform(adjust(variant, "SALE", 1, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REASON"));

        JsonNode ledger = json.readTree(mvc.perform(bearer(admin, get("/api/admin/inventory/" + variant + "/movements")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode newest = ledger.get("content").get(0);
        assertThat(newest.get("change").asInt()).isEqualTo(-2);
        assertThat(newest.get("reason").asText()).isEqualTo("ADJUSTMENT");
        assertThat(newest.get("note").asText()).isEqualTo("Water damaged");
        assertThat(newest.get("by").asText()).isNotBlank();
        int total = ledger.get("content").findValuesAsText("change").stream().mapToInt(Integer::parseInt).sum();
        assertThat(total).isEqualTo(21);   // initial 3 + 20 - 2

        // two changes with before/after stock, and the four refused attempts logged too
        Integer changes = jdbc.queryForObject("""
                select count(*) from audit_logs where entity_type = 'INVENTORY' and entity_id = ?
                and json_extract(new_value, '$.stock') is not null
                """, Integer.class, String.valueOf(variant));
        assertThat(changes).isEqualTo(2);
        Integer refused = jdbc.queryForObject("""
                select count(*) from audit_logs where entity_type = 'INVENTORY' and entity_id = ?
                and json_extract(new_value, '$.status') >= 400
                """, Integer.class, String.valueOf(variant));
        assertThat(refused).isEqualTo(4);

        // e-books have no stock; customers can't touch inventory
        long ebook = createBook("Stockless " + SERIAL.incrementAndGet(), "EBOOK", null);
        mvc.perform(adjust(ebook, "RESTOCK", 5, null)).andExpect(status().isNotFound());
        Customer c = newCustomer();
        mvc.perform(bearer(c.token, get("/api/admin/inventory"))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Email change
    // ------------------------------------------------------------------

    @Test
    void email_changes_only_after_the_new_address_confirms_and_the_old_one_is_told() throws Exception {
        Customer c = newCustomer();
        String newEmail = "moved" + c.n + "@stock.test";
        Customer other = newCustomer();

        mvc.perform(emailChange(c, newEmail, "Wrong@Pass1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("WRONG_PASSWORD"));
        mvc.perform(emailChange(c, other.email, PASSWORD)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_TAKEN"));
        mvc.perform(emailChange(c, newEmail.toUpperCase(), PASSWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.newEmail").value(newEmail));
        verify(mail).sendEmailChangeAlert(any(), anyString(), eq(false));
        mvc.perform(bearer(c.token, get("/api/account/email-change"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.newEmail").value(newEmail));

        // nothing has changed yet
        mvc.perform(bearer(c.token, get("/api/account/me"))).andExpect(jsonPath("$.email").value(c.email));

        String token = changeToken();
        mvc.perform(post("/api/auth/confirm-email-change").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", token)))).andExpect(status().isOk());
        verify(mail).sendEmailChangeAlert(any(), anyString(), eq(true));
        mvc.perform(post("/api/auth/confirm-email-change").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("TOKEN_INVALID"));

        // every session ended; the new address logs in, the old one doesn't
        mvc.perform(bearer(c.token, get("/api/account/me"))).andExpect(status().isUnauthorized());
        login(c.email, PASSWORD, 401);
        String fresh = login(newEmail, PASSWORD);
        mvc.perform(bearer(fresh, get("/api/account/me"))).andExpect(jsonPath("$.email").value(newEmail))
                .andExpect(jsonPath("$.emailVerified").value(true));
        mvc.perform(bearer(fresh, get("/api/account/email-change"))).andExpect(status().isNoContent());

        // a new request replaces the old link; cancelling kills it
        mvc.perform(emailChange(fresh, "again" + c.n + "@stock.test", PASSWORD)).andExpect(status().isOk());
        String first = changeToken();
        mvc.perform(emailChange(fresh, "third" + c.n + "@stock.test", PASSWORD)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/confirm-email-change").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", first)))).andExpect(status().isBadRequest());
        mvc.perform(bearer(fresh, delete("/api/account/email-change"))).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/confirm-email-change").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", changeToken())))).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Account deletion
    // ------------------------------------------------------------------

    @Test
    void an_account_is_anonymised_on_deletion_but_not_while_an_order_is_in_progress() throws Exception {
        Customer c = newCustomer();
        long variant = createBook("Deletion Book " + SERIAL.incrementAndGet(), "PAPERBACK", 10);
        mvc.perform(bearer(c.token, post("/api/account/addresses")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fullName", "Ravi Kumar", "phone", c.phone,
                        "line1", "12 MG Road", "city", "Pune", "state", "Maharashtra", "pincode", "411001"))))
                .andExpect(status().isCreated());
        long orderItem = placeOrder(c.id, variant);

        mvc.perform(deleteAccount(c, PASSWORD, "delete")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CONFIRMATION_REQUIRED"));
        mvc.perform(deleteAccount(c, "Wrong@Pass1", "DELETE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("WRONG_PASSWORD"));
        mvc.perform(deleteAccount(c, PASSWORD, "DELETE")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ACCOUNT_BUSY"));

        jdbc.update("update order_items set fulfillment_status = 'DELIVERED', delivered_at = ? where id = ?",
                Timestamp.from(Instant.now()), orderItem);
        jdbc.update("update orders set status = 'COMPLETED' where id = (select order_id from order_items where id = ?)",
                orderItem);
        mvc.perform(deleteAccount(c, PASSWORD, "DELETE")).andExpect(status().isOk());
        verify(mail).sendAccountDeleted(any());

        Map<String, Object> row = jdbc.queryForMap(
                "select full_name, email, phone, enabled, deleted_at from users where id = ?", c.id);
        assertThat(row.get("full_name")).isEqualTo("Former customer");
        assertThat((String) row.get("email")).isEqualTo("deleted-" + c.id + "@deleted.invalid");
        assertThat((String) row.get("phone")).hasSize(10).startsWith("0");
        assertThat(row.get("enabled")).isEqualTo(false);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from addresses where user_id = ?", Integer.class, c.id)).isZero();
        // the order record stays, for tax law
        assertThat(jdbc.queryForObject("select count(*) from orders where user_id = ?", Integer.class, c.id)).isEqualTo(1);

        mvc.perform(bearer(c.token, get("/api/account/me"))).andExpect(status().isUnauthorized());
        login(c.email, PASSWORD, 401);
        // the old email and phone are free for a new account
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Ravi Kumar", "email", c.email,
                                "phone", c.phone, "password", PASSWORD))))
                .andExpect(status().isCreated());

        // staff can't delete themselves this way
        mvc.perform(bearer(admin, post("/api/account/delete")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("password", "Admin@12345", "confirmation", "DELETE"))))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------

    private record Customer(long id, int n, String email, String phone, String token) {
    }

    private Customer newCustomer() throws Exception {
        int n = SERIAL.incrementAndGet();
        String email = "reader" + n + "@stock.test";
        String phone = "98764" + String.format("%05d", n);
        long id = json.readTree(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Ravi Kumar", "email", email,
                                "phone", phone, "password", PASSWORD))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        return new Customer(id, n, email, phone, login(email, PASSWORD));
    }

    /** A COD order with one item still to be shipped; returns the order item id. */
    private long placeOrder(long userId, long variantId) {
        Timestamp now = Timestamp.from(Instant.now());
        String number = "BM-T" + SERIAL.incrementAndGet();
        jdbc.update("""
                insert into orders (order_number, user_id, status, payment_method, payment_status, subtotal, total,
                                    created_at, updated_at)
                values (?, ?, 'CONFIRMED', 'COD', 'COD_PENDING', 300, 340, ?, ?)
                """, number, userId, now, now);
        long orderId = jdbc.queryForObject("select id from orders where order_number = ?", Long.class, number);
        jdbc.update("""
                insert into order_items (order_id, variant_id, title_snapshot, format, mrp, unit_price, quantity, fulfillment_status)
                values (?, ?, 'Deletion Book', 'PAPERBACK', 400, 300, 1, 'PLACED')
                """, orderId, variantId);
        return jdbc.queryForObject("select id from order_items where order_id = ?", Long.class, orderId);
    }

    private long createBook(String title, String format, Integer stock) throws Exception {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("format", format);
        String base = String.format("9792%08d", ISBN_SERIAL.incrementAndGet());
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (base.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        v.put("isbn", base + (10 - sum % 10) % 10);
        v.put("mrp", "400");
        v.put("sellingPrice", "300");
        v.put("stock", stock);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("authors", List.of("Stock Author"));
        body.put("categoryIds", List.of(categories.findBySlug("fiction").orElseThrow().getId()));
        body.put("variants", List.of(v));
        JsonNode created = json.readTree(mvc.perform(bearer(admin, post("/api/admin/books"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return created.get("variants").get(0).get("id").asLong();
    }

    private MockHttpServletRequestBuilder adjust(long variant, String reason, int quantity, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("quantity", quantity);
        body.put("note", note);
        return bearer(admin, post("/api/admin/inventory/" + variant + "/adjust"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder emailChange(Customer c, String newEmail, String password) throws Exception {
        return emailChange(c.token, newEmail, password);
    }

    private MockHttpServletRequestBuilder emailChange(String token, String newEmail, String password) throws Exception {
        return bearer(token, post("/api/account/email-change")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("newEmail", newEmail, "password", password)));
    }

    private MockHttpServletRequestBuilder deleteAccount(Customer c, String password, String confirmation) throws Exception {
        return bearer(c.token, post("/api/account/delete")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("password", password, "confirmation", confirmation)));
    }

    /** The raw token from the most recent confirm-new-email message. */
    private String changeToken() {
        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).sendEmailChangeConfirm(any(), tokens.capture(), anyLong());
        List<String> all = new ArrayList<>(tokens.getAllValues());
        return all.get(all.size() - 1);
    }

    private static MockHttpServletRequestBuilder bearer(String token, MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + token);
    }

    private String login(String identifier, String password) throws Exception {
        return json.readTree(login(identifier, password, 200)).get("accessToken").asText();
    }

    private String login(String identifier, String password, int expected) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("identifier", identifier, "password", password))))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
}
