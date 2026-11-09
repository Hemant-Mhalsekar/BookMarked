package com.bookmarked.order;

import com.bookmarked.catalog.CategoryRepository;
import com.bookmarked.common.RateLimitService;
import com.bookmarked.mail.MailService;
import com.bookmarked.payment.RazorpayGateway;
import com.bookmarked.payment.Signatures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 7-day returns: online refunds through Razorpay, COD refunds by UPI, restock vs
 * write-off, the return window, rejection, and the order of steps.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "app.seed.demo-catalog=false",
        "app.seed.super-admin-email=admin@returns.test",
        "app.seed.super-admin-password=Admin@12345",
        "app.seed.super-admin-phone=9000000009",
        "app.cookie.secure=false",
        "app.razorpay.key-id=rzp_test_dummy",
        "app.razorpay.key-secret=" + ReturnIntegrationTest.KEY_SECRET,
        "app.razorpay.webhook-secret=" + ReturnIntegrationTest.WEBHOOK_SECRET
})
class ReturnIntegrationTest {

    static final String KEY_SECRET = "returns_key_secret";
    static final String WEBHOOK_SECRET = "returns_webhook_secret";

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    private static final AtomicInteger ISBN_SERIAL = new AtomicInteger(3000);
    private static final AtomicInteger USER_SERIAL = new AtomicInteger(600);
    private static final AtomicInteger RZP_SERIAL = new AtomicInteger(1);

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    CategoryRepository categories;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    MailService mail;
    @MockitoBean
    RateLimitService rateLimit;
    @MockitoBean
    RazorpayGateway razorpay;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        reset(razorpay);
        admin = login("admin@returns.test", "Admin@12345");
        when(razorpay.createOrder(anyLong(), anyString())).thenAnswer(inv -> "order_R" + RZP_SERIAL.incrementAndGet());
        when(razorpay.refund(anyString(), anyLong())).thenAnswer(inv -> "rfnd_R" + RZP_SERIAL.incrementAndGet());
    }

    // ------------------------------------------------------------------
    // Online order: refund through Razorpay, book restocked
    // ------------------------------------------------------------------

    @Test
    void online_return_goes_from_request_to_refund_and_restock() throws Exception {
        Customer c = newCustomer(false);
        long pb = createBook("Returns Online", "700", "600", 5);
        JsonNode created = order(c, List.of(Map.of("variantId", pb, "quantity", 1)), "ONLINE");
        String number = created.get("orderNumber").asText();
        pay(c, created);
        long itemId = customerOrder(c, number).get("items").get(0).get("id").asLong();

        // not delivered yet: nothing to return
        requestReturn(c, number, itemId, Map.of("reason", "DAMAGED"), 409, "NOT_RETURNABLE");
        deliver(number, itemId);
        assertThat(stock(pb)).isEqualTo(4);

        JsonNode line = customerOrder(c, number).get("items").get(0);
        assertThat(line.get("returnable").asBoolean()).isTrue();
        assertThat(line.get("returnBy").asText()).isNotBlank();

        JsonNode afterRequest = requestReturn(c, number, itemId,
                Map.of("reason", "DAMAGED", "comment", "Cover torn"), 200, null);
        JsonNode item = afterRequest.get("items").get(0);
        assertThat(item.get("fulfillmentStatus").asText()).isEqualTo("RETURN_REQUESTED");
        assertThat(item.get("returnable").asBoolean()).isFalse();
        assertThat(item.get("returnRequest").get("status").asText()).isEqualTo("REQUESTED");
        assertThat(item.get("returnRequest").get("refundAmount").decimalValue()).isEqualByComparingTo("600");
        assertThat(afterRequest.get("status").asText()).isEqualTo("CONFIRMED");   // open again while the return runs

        // asking twice doesn't create a second return
        requestReturn(c, number, itemId, Map.of("reason", "DAMAGED"), 200, null);
        long returnId = returnIdFor(number);
        assertThat(jdbc.queryForObject("select count(*) from notifications n join users u on u.id = n.user_id "
                + "where u.email = 'admin@returns.test' and n.type = 'NEW_RETURN'", Integer.class)).isPositive();

        // steps go in order
        staff(returnId, "picked-up", null, 409);
        staff(returnId, "approve", null, 200);
        staff(returnId, "approve", null, 200);   // repeat is harmless
        staff(returnId, "picked-up", null, 200);
        staff(returnId, "refund", Map.of(), 409);   // not received yet
        JsonNode received = staff(returnId, "received", Map.of("restock", true), 200);
        assertThat(received.get("restocked").asBoolean()).isTrue();
        assertThat(stock(pb)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from inventory_movements where reference_id = ? and reason = 'RETURN'",
                Integer.class, number)).isEqualTo(1);

        JsonNode refunded = staff(returnId, "refund", Map.of(), 200);
        assertThat(refunded.get("status").asText()).isEqualTo("REFUNDED");
        assertThat(refunded.get("refundMethod").asText()).isEqualTo("RAZORPAY");
        verify(razorpay).refund(paymentId(number), 60000L);

        JsonNode done = customerOrder(c, number);
        assertThat(done.get("items").get(0).get("fulfillmentStatus").asText()).isEqualTo("RETURNED");
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("paymentStatus").asText()).isEqualTo("REFUNDED");
        assertThat(notificationTypes(c).stream().filter("RETURN_UPDATE"::equals).count()).isEqualTo(4);

        staff(returnId, "refund", Map.of(), 200);   // repeat: no second refund
        verify(razorpay, times(1)).refund(anyString(), anyLong());
    }

    // ------------------------------------------------------------------
    // COD order: UPI payout recorded by staff, book written off
    // ------------------------------------------------------------------

    @Test
    void cod_return_is_refunded_by_upi_with_a_reference() throws Exception {
        Customer c = newCustomer(true);
        long pb = createBook("Returns COD", "600", "520", 5);
        String number = order(c, List.of(Map.of("variantId", pb, "quantity", 1)), "COD").get("orderNumber").asText();
        long itemId = adminOrder(number).get("items").get(0).get("id").asLong();
        deliver(number, itemId);

        requestReturn(c, number, itemId, Map.of("reason", "CHANGED_MIND"), 400, "UPI_REQUIRED");
        mvc.perform(as(c, post("/api/orders/" + number + "/items/" + itemId + "/return"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", "CHANGED_MIND", "refundUpiId", "not a upi"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.refundUpiId").exists());
        requestReturn(c, number, itemId, Map.of("reason", "CHANGED_MIND", "refundUpiId", "reader.one@okhdfc"), 200, null);
        long returnId = returnIdFor(number);

        staff(returnId, "approve", null, 200);
        staff(returnId, "picked-up", null, 200);
        staff(returnId, "received", Map.of("restock", false, "note", "Water damaged"), 200);
        assertThat(stock(pb)).isEqualTo(4);   // written off

        JsonNode noRef = json.readTree(mvc.perform(asAdmin(post("/api/admin/returns/" + returnId + "/refund"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());
        assertThat(noRef.get("error").asText()).isEqualTo("UPI_REFERENCE_REQUIRED");

        JsonNode refunded = staff(returnId, "refund", Map.of("upiReference", "431298765012"), 200);
        assertThat(refunded.get("refundMethod").asText()).isEqualTo("UPI");
        assertThat(refunded.get("upiReference").asText()).isEqualTo("431298765012");
        assertThat(refunded.get("refundUpiId").asText()).isEqualTo("reader.one@okhdfc");
        verify(razorpay, times(0)).refund(anyString(), anyLong());
        assertThat(jdbc.queryForObject("select note from refunds r join orders o on o.id = r.order_id where o.order_number = ?",
                String.class, number)).contains("431298765012");
        assertThat(customerOrder(c, number).get("paymentStatus").asText()).isEqualTo("REFUNDED");
    }

    // ------------------------------------------------------------------
    // Limits
    // ------------------------------------------------------------------

    @Test
    void returns_close_after_seven_days_and_a_declined_return_is_final() throws Exception {
        Customer c = newCustomer(true);
        long a = createBook("Returns Late", "600", "520", 5);
        long b = createBook("Returns Declined", "600", "530", 5);
        String number = order(c, List.of(Map.of("variantId", a, "quantity", 1), Map.of("variantId", b, "quantity", 1)), "COD")
                .get("orderNumber").asText();
        JsonNode adminView = adminOrder(number);
        long late = itemIdIn(adminView, "Returns Late");
        long declined = itemIdIn(adminView, "Returns Declined");
        deliver(number, late, declined);

        jdbc.update("update order_items set delivered_at = ? where id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(8))), late);
        assertThat(itemIn(customerOrder(c, number), late).get("returnable").asBoolean()).isFalse();
        requestReturn(c, number, late, Map.of("reason", "DAMAGED", "refundUpiId", "me@okaxis"), 409, "RETURN_WINDOW_CLOSED");

        requestReturn(c, number, declined, Map.of("reason", "NOT_AS_DESCRIBED", "refundUpiId", "me@okaxis"), 200, null);
        long returnId = returnIdFor(number);
        mvc.perform(asAdmin(post("/api/admin/returns/" + returnId + "/reject")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"\"}"))
                .andExpect(status().isBadRequest());
        JsonNode rejected = staff(returnId, "reject", Map.of("note", "The book matches the listing photos."), 200);
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        JsonNode line = itemIn(customerOrder(c, number), declined);
        assertThat(line.get("fulfillmentStatus").asText()).isEqualTo("DELIVERED");
        assertThat(line.get("returnRequest").get("adminNote").asText()).contains("listing photos");
        requestReturn(c, number, declined, Map.of("reason", "DAMAGED", "refundUpiId", "me@okaxis"), 409, "RETURN_EXISTS");

        // someone else can't return this customer's books, and customers can't use the queue
        mvc.perform(as(newCustomer(false), post("/api/orders/" + number + "/items/" + declined + "/return"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"DAMAGED\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(as(c, get("/api/admin/returns"))).andExpect(status().isForbidden());

        JsonNode queue = json.readTree(mvc.perform(asAdmin(get("/api/admin/returns")).param("status", "REJECTED"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(queue.get("content").findValuesAsText("orderNumber")).contains(number);
    }

    @Test
    void e_books_cannot_be_returned() throws Exception {
        Customer c = newCustomer(false);
        long ebook = createEbook("Returns Ebook");
        JsonNode created = order(c, List.of(Map.of("variantId", ebook, "quantity", 1)), "ONLINE");
        pay(c, created);
        String number = created.get("orderNumber").asText();
        JsonNode line = customerOrder(c, number).get("items").get(0);
        assertThat(line.get("returnable").asBoolean()).isFalse();
        requestReturn(c, number, line.get("id").asLong(), Map.of("reason", "DAMAGED"), 409, "NOT_RETURNABLE");
    }

    // ---- return helpers ----

    private void deliver(String number, Long... itemIds) throws Exception {
        List<Long> ids = List.of(itemIds);
        adminAction(number, "process", Map.of("itemIds", ids));
        Map<String, Object> ship = new LinkedHashMap<>(shipBody(ids.get(0)));
        ship.put("itemIds", ids);
        adminAction(number, "ship", ship);
        adminAction(number, "deliver", Map.of("itemIds", ids));
    }

    private JsonNode requestReturn(Customer c, String number, long itemId, Map<String, Object> body, int expected,
                                   String error) throws Exception {
        JsonNode res = json.readTree(mvc.perform(as(c, post("/api/orders/" + number + "/items/" + itemId + "/return"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
        if (error != null) {
            assertThat(res.get("error").asText()).isEqualTo(error);
        }
        return res;
    }

    private JsonNode staff(long returnId, String action, Map<String, Object> body, int expected) throws Exception {
        MockHttpServletRequestBuilder req = asAdmin(post("/api/admin/returns/" + returnId + "/" + action));
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return json.readTree(mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }

    private long returnIdFor(String number) {
        return jdbc.queryForObject("""
                select r.id from returns r join order_items oi on oi.id = r.order_item_id join orders o on o.id = oi.order_id
                where o.order_number = ? order by r.id desc limit 1
                """, Long.class, number);
    }

    private static long itemIdIn(JsonNode order, String title) {
        return itemId(order, title);
    }

    private static JsonNode itemIn(JsonNode order, long itemId) {
        for (JsonNode i : order.get("items")) {
            if (i.get("id").asLong() == itemId) {
                return i;
            }
        }
        throw new AssertionError("no item " + itemId);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    record Customer(long id, String email, String token) {
    }

    private static Map<String, Object> shipBody(long itemId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemIds", List.of(itemId));
        m.put("courierName", "Delhivery");
        m.put("trackingNumber", "awb12345");
        m.put("expectedDelivery", LocalDate.now().plusDays(3).toString());
        return m;
    }

    private JsonNode adminAction(String number, String action, Map<String, Object> body) throws Exception {
        return json.readTree(mvc.perform(asAdmin(post("/api/admin/orders/" + number + "/" + action))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode adminOrder(String number) throws Exception {
        return json.readTree(mvc.perform(asAdmin(get("/api/admin/orders/" + number)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode customerOrder(Customer c, String number) throws Exception {
        return fetch(c, "/api/orders/" + number);
    }

    private JsonNode fetch(Customer c, String url) throws Exception {
        return json.readTree(mvc.perform(as(c, get(url)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode cancel(Customer c, String number, List<Long> itemIds, int expected) throws Exception {
        MockHttpServletRequestBuilder req = as(c, post("/api/orders/" + number + "/cancel"));
        if (itemIds != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("itemIds", itemIds)));
        }
        return json.readTree(mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }

    private List<String> notificationTypes(Customer c) {
        return jdbc.queryForList("select type from notifications where user_id = ?", String.class, c.id());
    }

    private static long itemId(JsonNode order, String title) {
        for (JsonNode i : order.get("items")) {
            if (title.equals(i.get("title").asText())) {
                return i.get("id").asLong();
            }
        }
        throw new AssertionError("no item " + title);
    }

    private String paymentId(String number) {
        return jdbc.queryForObject("select razorpay_payment_id from orders where order_number = ?", String.class, number);
    }

    private int stock(long variantId) {
        return jdbc.queryForObject("select stock from book_variants where id = ?", Integer.class, variantId);
    }

    private JsonNode order(Customer c, List<Map<String, Object>> lines, String method) throws Exception {
        for (Map<String, Object> l : lines) {
            mvc.perform(as(c, post("/api/cart/items")).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(l))).andExpect(status().isOk());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "CART");
        body.put("paymentMethod", method);
        boolean physical = jdbc.queryForObject("select count(*) from book_variants where id = ? and format <> 'EBOOK'",
                Integer.class, lines.get(0).get("variantId")) > 0;
        if (physical) {
            body.put("addressId", addAddress(c));
        }
        return json.readTree(mvc.perform(as(c, post("/api/checkout/create-order")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void pay(Customer c, JsonNode created) throws Exception {
        String rzpOrder = created.get("razorpayOrderId").asText();
        String paymentId = "pay_" + created.get("orderNumber").asText().replace("-", "");
        mvc.perform(as(c, post("/api/checkout/verify")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("razorpayOrderId", rzpOrder, "razorpayPaymentId", paymentId,
                                "razorpaySignature", Signatures.hmacSha256Hex(rzpOrder + "|" + paymentId, KEY_SECRET)))))
                .andExpect(status().isOk());
    }

    private long addAddress(Customer c) throws Exception {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("fullName", "Book Buyer");
        a.put("phone", "9876543210");
        a.put("line1", "12 Marine Drive");
        a.put("city", "Mumbai");
        a.put("state", "Maharashtra");
        a.put("pincode", "400001");
        return json.readTree(mvc.perform(as(c, post("/api/account/addresses")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(a)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private long createBook(String title, String mrp, String price, int stock) throws Exception {
        return create(title, variant("PAPERBACK", mrp, price, stock));
    }

    private long createEbook(String title) throws Exception {
        return create(title, variant("EBOOK", "300", "200", null));
    }

    private long create(String title, Map<String, Object> variant) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("authors", List.of("Returns Author"));
        body.put("categoryIds", List.of(categories.findBySlug("fiction").orElseThrow().getId()));
        body.put("variants", List.of(variant));
        JsonNode created = json.readTree(mvc.perform(asAdmin(post("/api/admin/books"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return created.get("variants").get(0).get("id").asLong();
    }

    private static Map<String, Object> variant(String format, String mrp, String price, Integer stock) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("format", format);
        String base = String.format("9795%08d", ISBN_SERIAL.incrementAndGet());
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (base.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        v.put("isbn", base + (10 - sum % 10) % 10);
        v.put("mrp", mrp);
        v.put("sellingPrice", price);
        v.put("stock", stock);
        return v;
    }

    private Customer newCustomer(boolean verifiedEmail) throws Exception {
        int n = USER_SERIAL.incrementAndGet();
        String email = "returner" + n + "@example.com";
        String res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Book Reader", "email", email,
                                "phone", "95210" + String.format("%05d", n), "password", "Str0ng@Pass"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(res).get("id").asLong();
        if (verifiedEmail) {
            jdbc.update("update users set email_verified = true where id = ?", id);
        }
        return new Customer(id, email, login(email, "Str0ng@Pass"));
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + admin);
    }

    private static MockHttpServletRequestBuilder as(Customer c, MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + c.token());
    }

    private String login(String identifier, String password) throws Exception {
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("identifier", identifier, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("accessToken").asText();
    }
}
