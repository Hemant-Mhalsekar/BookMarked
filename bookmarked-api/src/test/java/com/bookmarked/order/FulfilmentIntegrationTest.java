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
 * After checkout: staff fulfilment, cancellations with refunds (including two
 * cancels racing), refund retry, the refund webhook, notifications and invoices.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "app.seed.demo-catalog=false",
        "app.seed.super-admin-email=admin@fulfil.test",
        "app.seed.super-admin-password=Admin@12345",
        "app.seed.super-admin-phone=9000000008",
        "app.cookie.secure=false",
        "app.razorpay.key-id=rzp_test_dummy",
        "app.razorpay.key-secret=" + FulfilmentIntegrationTest.KEY_SECRET,
        "app.razorpay.webhook-secret=" + FulfilmentIntegrationTest.WEBHOOK_SECRET
})
class FulfilmentIntegrationTest {

    static final String KEY_SECRET = "fulfil_key_secret";
    static final String WEBHOOK_SECRET = "fulfil_webhook_secret";

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
        admin = login("admin@fulfil.test", "Admin@12345");
        when(razorpay.createOrder(anyLong(), anyString())).thenAnswer(inv -> "order_F" + RZP_SERIAL.incrementAndGet());
        when(razorpay.refund(anyString(), anyLong())).thenAnswer(inv -> "rfnd_F" + RZP_SERIAL.incrementAndGet());
    }

    // ------------------------------------------------------------------
    // Fulfilment
    // ------------------------------------------------------------------

    @Test
    void staff_take_a_cod_order_from_placed_to_cash_collected() throws Exception {
        Customer c = newCustomer(true);
        long pb = createBook("Fulfil COD Journey", "600", "550", 5);
        String number = order(c, List.of(Map.of("variantId", pb, "quantity", 1)), "COD").get("orderNumber").asText();
        long itemId = adminOrder(number).get("items").get(0).get("id").asLong();

        // the new order shows up for staff, waiting to be packed
        JsonNode list = json.readTree(mvc.perform(asAdmin(get("/api/admin/orders"))
                        .param("q", c.email()).param("itemStatus", "PLACED"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.get("content")).hasSize(1);
        assertThat(list.get("content").get(0).get("toFulfil").asInt()).isEqualTo(1);

        // no skipping: PLACED can't go straight to SHIPPED
        mvc.perform(asAdmin(post("/api/admin/orders/" + number + "/ship")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(shipBody(itemId))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("INVALID_TRANSITION"));

        adminAction(number, "process", Map.of("itemIds", List.of(itemId)));
        adminAction(number, "process", Map.of("itemIds", List.of(itemId)));   // repeat is a no-op

        // shipping needs courier, tracking and a date that isn't in the past
        Map<String, Object> bad = new LinkedHashMap<>(shipBody(itemId));
        bad.put("trackingNumber", "x");
        bad.put("expectedDelivery", LocalDate.now().minusDays(1).toString());
        mvc.perform(asAdmin(post("/api/admin/orders/" + number + "/ship")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.trackingNumber").exists())
                .andExpect(jsonPath("$.fieldErrors.expectedDelivery").exists());

        JsonNode shipped = adminAction(number, "ship", shipBody(itemId));
        assertThat(shipped.get("shipments")).hasSize(1);
        assertThat(shipped.get("shipments").get(0).get("trackingNumber").asText()).isEqualTo("AWB12345");

        // cash can't be recorded before delivery, and shipped items can't be cancelled
        mvc.perform(asAdmin(post("/api/admin/orders/" + number + "/cod-collected")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("NOT_DELIVERED"));
        mvc.perform(as(c, post("/api/orders/" + number + "/cancel")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("itemIds", List.of(itemId)))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("NOT_CANCELLABLE"));

        JsonNode delivered = adminAction(number, "deliver", Map.of("itemIds", List.of(itemId)));
        assertThat(delivered.get("status").asText()).isEqualTo("COMPLETED");
        JsonNode collected = json.readTree(mvc.perform(asAdmin(post("/api/admin/orders/" + number + "/cod-collected")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(collected.get("paymentStatus").asText()).isEqualTo("COLLECTED");

        // the customer sees the whole journey
        JsonNode mine = customerOrder(c, number);
        JsonNode line = mine.get("items").get(0);
        assertThat(line.get("fulfillmentStatus").asText()).isEqualTo("DELIVERED");
        assertThat(line.get("cancellable").asBoolean()).isFalse();
        assertThat(line.get("shipment").get("courierName").asText()).isEqualTo("Delhivery");
        List<String> steps = new ArrayList<>();
        line.get("timeline").forEach(t -> steps.add(t.get("status").asText()));
        assertThat(steps).containsExactly("PLACED", "PROCESSING", "SHIPPED", "DELIVERED");

        // ...gets told at each step (shipped and delivered also by email)...
        assertThat(notificationTypes(c)).contains("ORDER_PLACED", "ORDER_PROCESSING", "ORDER_SHIPPED", "ORDER_DELIVERED");
        verify(mail, times(2)).sendNotification(org.mockito.ArgumentMatchers.any(), anyString(), anyString(),
                eq("/orders/" + number), anyString());

        // ...and the order is in the Delivered tab
        JsonNode tab = json.readTree(mvc.perform(as(c, get("/api/orders")).param("tab", "DELIVERED"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(tab.get("totalElements").asInt()).isEqualTo(1);

        // staff were told about the new order
        assertThat(jdbc.queryForObject("select count(*) from notifications n join users u on u.id = n.user_id "
                + "where u.email = 'admin@fulfil.test' and n.type = 'NEW_ORDER' and n.link like ?", Integer.class,
                "%" + number)).isEqualTo(1);
    }

    @Test
    void customers_cannot_use_staff_endpoints() throws Exception {
        Customer c = newCustomer(false);
        mvc.perform(as(c, get("/api/admin/orders"))).andExpect(status().isForbidden());
        mvc.perform(as(c, get("/api/admin/refunds"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Cancellation and refunds
    // ------------------------------------------------------------------

    @Test
    void partial_then_full_cancellation_refunds_items_then_delivery() throws Exception {
        Customer c = newCustomer(false);
        long a = createBook("Fulfil Cancel A", "300", "250", 5);
        long b = createBook("Fulfil Cancel B", "250", "200", 5);
        // ₹250 + ₹200 = ₹450, below ₹499 so ₹40 delivery: ₹490 paid
        JsonNode created = order(c, List.of(Map.of("variantId", a, "quantity", 1), Map.of("variantId", b, "quantity", 1)), "ONLINE");
        String number = created.get("orderNumber").asText();
        pay(c, created);
        JsonNode paid = customerOrder(c, number);
        long itemA = itemId(paid, "Fulfil Cancel A");
        long itemB = itemId(paid, "Fulfil Cancel B");
        assertThat(stock(a)).isEqualTo(4);

        // cancel one book: its price comes back, delivery doesn't (another book is still coming)
        JsonNode afterOne = cancel(c, number, List.of(itemA), 200);
        verify(razorpay).refund(paymentId(number), 25000L);
        assertThat(afterOne.get("paymentStatus").asText()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(afterOne.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(afterOne.get("refunds")).hasSize(1);
        assertThat(stock(a)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from inventory_movements where reference_id = ? and reason = 'CANCEL'",
                Integer.class, number)).isEqualTo(1);

        // asking again changes nothing and refunds nothing
        cancel(c, number, List.of(itemA), 200);
        verify(razorpay, times(1)).refund(anyString(), anyLong());

        // cancel the rest: the last book plus the delivery fee
        JsonNode all = cancel(c, number, null, 200);
        verify(razorpay).refund(paymentId(number), 24000L);
        assertThat(all.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(all.get("paymentStatus").asText()).isEqualTo("REFUNDED");
        assertThat(all.get("cancellable").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select sum(amount) from refunds r join orders o on o.id = r.order_id where o.order_number = ?",
                java.math.BigDecimal.class, number)).isEqualByComparingTo("490");
        assertThat(notificationTypes(c)).contains("ORDER_CANCELLED");

        // a whole-order cancel again is harmless
        cancel(c, number, null, 200);
        verify(razorpay, times(2)).refund(anyString(), anyLong());
        assertThat(itemB).isPositive();
    }

    @Test
    void two_cancels_at_once_refund_only_once() throws Exception {
        Customer c = newCustomer(false);
        long a = createBook("Fulfil Race", "700", "600", 5);
        JsonNode created = order(c, List.of(Map.of("variantId", a, "quantity", 1)), "ONLINE");
        String number = created.get("orderNumber").asText();
        pay(c, created);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(as(c, post("/api/orders/" + number + "/cancel"))).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> f : results) {
            assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdown();

        verify(razorpay, times(1)).refund(anyString(), anyLong());
        assertThat(stock(a)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from refunds r join orders o on o.id = r.order_id where o.order_number = ?",
                Integer.class, number)).isEqualTo(1);
    }

    @Test
    void a_failed_refund_can_be_retried_and_the_webhook_completes_it() throws Exception {
        Customer c = newCustomer(false);
        long a = createBook("Fulfil Refund Retry", "700", "600", 5);
        JsonNode created = order(c, List.of(Map.of("variantId", a, "quantity", 1)), "ONLINE");
        String number = created.get("orderNumber").asText();
        pay(c, created);

        when(razorpay.refund(anyString(), anyLong()))
                .thenThrow(new RazorpayGateway.GatewayException("Razorpay down", null))
                .thenReturn("rfnd_RETRY_OK");
        JsonNode cancelled = cancel(c, number, null, 200);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");   // the cancellation stands
        assertThat(cancelled.get("refunds").get(0).get("status").asText()).isEqualTo("FAILED");

        JsonNode failed = json.readTree(mvc.perform(asAdmin(get("/api/admin/refunds")).param("status", "FAILED"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode row = null;
        for (JsonNode r : failed.get("content")) {
            if (number.equals(r.get("orderNumber").asText())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("note").asText()).contains("Razorpay down");
        long refundId = row.get("id").asLong();

        JsonNode retried = json.readTree(mvc.perform(asAdmin(post("/api/admin/refunds/" + refundId + "/retry")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(retried.get("status").asText()).isEqualTo("PENDING");
        assertThat(retried.get("razorpayRefundId").asText()).isEqualTo("rfnd_RETRY_OK");
        mvc.perform(asAdmin(post("/api/admin/refunds/" + refundId + "/retry")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("REFUND_NOT_RETRYABLE"));

        // Razorpay reports the refund done
        String body = "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_RETRY_OK\","
                + "\"payment_id\":\"" + paymentId(number) + "\",\"amount\":60000}}}}";
        mvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Razorpay-Signature", Signatures.hmacSha256Hex(body, WEBHOOK_SECRET))
                        .header("X-Razorpay-Event-Id", "evt_refund_" + number))
                .andExpect(status().isOk());
        assertThat(customerOrder(c, number).get("refunds").get(0).get("status").asText()).isEqualTo("PROCESSED");
        assertThat(notificationTypes(c)).contains("REFUND_PROCESSED");
        assertThat(jdbc.queryForObject("select count(*) from notifications n join users u on u.id = n.user_id "
                + "where u.email = 'admin@fulfil.test' and n.type = 'REFUND_FAILED'", Integer.class)).isPositive();
    }

    @Test
    void e_books_and_unpaid_orders_cannot_be_cancelled() throws Exception {
        Customer c = newCustomer(false);
        long ebook = createEbook("Fulfil Ebook");
        JsonNode created = order(c, List.of(Map.of("variantId", ebook, "quantity", 1)), "ONLINE");
        String number = created.get("orderNumber").asText();

        mvc.perform(as(c, post("/api/orders/" + number + "/cancel")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("NOT_CANCELLABLE"));
        pay(c, created);
        JsonNode paid = customerOrder(c, number);
        assertThat(paid.get("status").asText()).isEqualTo("COMPLETED");   // e-book only: done at once
        mvc.perform(as(c, post("/api/orders/" + number + "/cancel")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("NOT_CANCELLABLE"));

        Customer other = newCustomer(false);
        mvc.perform(as(other, post("/api/orders/" + number + "/cancel"))).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Notifications and invoices
    // ------------------------------------------------------------------

    @Test
    void notifications_are_private_and_can_be_marked_read() throws Exception {
        Customer c = newCustomer(true);
        long pb = createBook("Fulfil Notify", "300", "250", 5);
        order(c, List.of(Map.of("variantId", pb, "quantity", 2)), "COD");

        JsonNode count = fetch(c, "/api/notifications/unread-count");
        assertThat(count.get("unread").asLong()).isEqualTo(1);
        JsonNode page = fetch(c, "/api/notifications");
        long id = page.get("content").get(0).get("id").asLong();
        assertThat(page.get("content").get(0).get("read").asBoolean()).isFalse();

        Customer other = newCustomer(false);
        mvc.perform(as(other, post("/api/notifications/" + id + "/read"))).andExpect(status().isNotFound());

        mvc.perform(as(c, post("/api/notifications/" + id + "/read"))).andExpect(status().isNoContent());
        assertThat(fetch(c, "/api/notifications/unread-count").get("unread").asLong()).isZero();
        mvc.perform(as(c, post("/api/notifications/read-all"))).andExpect(status().isOk());
    }

    @Test
    void invoices_are_pdfs_for_the_owner_and_staff_only() throws Exception {
        Customer c = newCustomer(true);
        long pb = createBook("Fulfil Invoice Edition", "300", "250", 5);
        String number = order(c, List.of(Map.of("variantId", pb, "quantity", 2)), "COD").get("orderNumber").asText();

        byte[] pdf = mvc.perform(as(c, get("/api/orders/" + number + "/invoice")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(number)))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("INVOICE", number, "Fulfil Invoice Edition", "Rs. 500.00",
                    "Cash on Delivery");
        }
        mvc.perform(asAdmin(get("/api/admin/orders/" + number + "/invoice"))).andExpect(status().isOk());
        mvc.perform(as(newCustomer(false), get("/api/orders/" + number + "/invoice"))).andExpect(status().isNotFound());

        // no invoice before the order is paid
        Customer d = newCustomer(false);
        String unpaid = order(d, List.of(Map.of("variantId", pb, "quantity", 1)), "ONLINE").get("orderNumber").asText();
        mvc.perform(as(d, get("/api/orders/" + unpaid + "/invoice")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("INVOICE_NOT_AVAILABLE"));
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
        body.put("authors", List.of("Fulfil Author"));
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
        String base = String.format("9796%08d", ISBN_SERIAL.incrementAndGet());
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
        String email = "reader" + n + "@example.com";
        String res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Book Reader", "email", email,
                                "phone", "94210" + String.format("%05d", n), "password", "Str0ng@Pass"))))
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
