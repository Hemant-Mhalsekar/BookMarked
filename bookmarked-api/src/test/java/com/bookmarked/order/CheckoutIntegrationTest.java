package com.bookmarked.order;

import com.bookmarked.catalog.CategoryRepository;
import com.bookmarked.common.RateLimitService;
import com.bookmarked.mail.MailService;
import com.bookmarked.payment.RazorpayGateway;
import com.bookmarked.payment.Signatures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checkout against real MySQL with a mocked Razorpay: stock reservation (including
 * a race for the last copy), signature checks, webhook idempotency, expiry, late
 * payments, Cash on Delivery rules and addresses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "app.seed.demo-catalog=false",
        "app.seed.super-admin-email=admin@checkout.test",
        "app.seed.super-admin-password=Admin@12345",
        "app.seed.super-admin-phone=9000000007",
        "app.cookie.secure=false",
        "app.razorpay.key-id=rzp_test_dummy",
        "app.razorpay.key-secret=" + CheckoutIntegrationTest.KEY_SECRET,
        "app.razorpay.webhook-secret=" + CheckoutIntegrationTest.WEBHOOK_SECRET
})
class CheckoutIntegrationTest {

    static final String KEY_SECRET = "test_key_secret";
    static final String WEBHOOK_SECRET = "test_webhook_secret";

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    private static final AtomicInteger ISBN_SERIAL = new AtomicInteger(7000);
    private static final AtomicInteger USER_SERIAL = new AtomicInteger(400);
    private static final AtomicInteger RZP_SERIAL = new AtomicInteger(1);

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    CategoryRepository categories;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PaymentService payments;

    @MockitoBean
    MailService mail;
    @MockitoBean
    RateLimitService rateLimit;
    @MockitoBean
    RazorpayGateway razorpay;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin@checkout.test", "Admin@12345");
        when(razorpay.createOrder(anyLong(), anyString()))
                .thenAnswer(inv -> "order_T" + RZP_SERIAL.incrementAndGet());
        when(razorpay.refund(anyString(), anyLong())).thenAnswer(inv -> "rfnd_T" + RZP_SERIAL.incrementAndGet());
    }

    // ------------------------------------------------------------------
    // Online payment
    // ------------------------------------------------------------------

    @Test
    void online_payment_from_the_stack_end_to_end() throws Exception {
        Customer c = newCustomer(false);
        Book book = createBook("Checkout Full Flow", "899", "699", 5, true);
        addToStack(c, book.paperback(), 2);
        addToStack(c, book.ebook(), 1);
        long address = addAddress(c);

        String key = "key-" + UUID.randomUUID();
        JsonNode created = createOrder(c, Map.of("source", "CART", "paymentMethod", "ONLINE", "addressId", address), key, 201);
        assertThat(created.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        // 2 × 699 + 150 (e-book) = 1548, free delivery
        assertThat(created.get("total").decimalValue()).isEqualByComparingTo("1548");
        assertThat(created.get("amountPaise").asLong()).isEqualTo(154800L);
        assertThat(created.get("keyId").asText()).isEqualTo("rzp_test_dummy");
        assertThat(created.get("prefill").get("email").asText()).isEqualTo(c.email());
        assertThat(stock(book.paperback())).isEqualTo(3);     // reserved

        // a retry with the same Idempotency-Key returns the same order and reserves nothing more
        JsonNode again = createOrder(c, Map.of("source", "CART", "paymentMethod", "ONLINE", "addressId", address), key, 201);
        assertThat(again.get("orderNumber").asText()).isEqualTo(created.get("orderNumber").asText());
        assertThat(stock(book.paperback())).isEqualTo(3);

        String rzpOrder = created.get("razorpayOrderId").asText();
        JsonNode paid = verifyPayment(c, rzpOrder, "pay_FULL1", 200);
        assertThat(paid.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(paid.get("paymentStatus").asText()).isEqualTo("PAID");
        Map<String, String> itemStatus = new LinkedHashMap<>();
        paid.get("items").forEach(i -> itemStatus.put(i.get("format").asText(), i.get("fulfillmentStatus").asText()));
        assertThat(itemStatus).containsEntry("EBOOK", "DELIVERED").containsEntry("PAPERBACK", "PLACED");
        assertThat(paid.get("shipping").get("pincode").asText()).isEqualTo("400001");

        assertThat(jdbc.queryForObject("select count(*) from user_library where user_id = ? and variant_id = ?",
                Integer.class, c.id(), book.ebook())).isEqualTo(1);
        assertThat(json.readTree(mvc.perform(as(c, get("/api/cart"))).andReturn().getResponse().getContentAsString())
                .get("lines")).isEmpty();
        assertThat(stock(book.paperback())).isEqualTo(3);

        // the webhook for the same payment arrives later: nothing changes, and a re-delivery is ignored
        String body = webhookBody("payment.captured", "pay_FULL1", rzpOrder, 154800L);
        assertThat(sendWebhook(body, "evt_full_1", 200).get("status").asText()).isEqualTo("ALREADY_CONFIRMED");
        assertThat(sendWebhook(body, "evt_full_1", 200).get("status").asText()).isEqualTo("IGNORED");

        // the e-book can't be bought again
        mvc.perform(as(c, post("/api/cart/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("variantId", book.ebook(), "quantity", 1))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("ALREADY_OWNED"));
    }

    @Test
    void a_wrong_signature_is_rejected_and_the_order_stays_unpaid() throws Exception {
        Customer c = newCustomer(false);
        Book book = createBook("Checkout Bad Signature", "300", "250", 5, false);
        JsonNode created = buyNow(c, book.paperback(), 1, "ONLINE", addAddress(c), 201);

        mvc.perform(as(c, post("/api/checkout/verify")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "razorpayOrderId", created.get("razorpayOrderId").asText(),
                                "razorpayPaymentId", "pay_FORGED",
                                "razorpaySignature", "0".repeat(64)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SIGNATURE"));
        assertThat(order(c, created.get("orderNumber").asText()).get("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void the_webhook_confirms_payment_when_the_customer_closed_the_tab() throws Exception {
        Customer c = newCustomer(false);
        Book book = createBook("Checkout Webhook Only", "500", "450", 5, false);
        JsonNode created = buyNow(c, book.paperback(), 1, "ONLINE", addAddress(c), 201);
        String rzpOrder = created.get("razorpayOrderId").asText();
        String number = created.get("orderNumber").asText();

        // forged webhook
        String forged = webhookBody("payment.captured", "pay_W1", rzpOrder, 49000L);
        mvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(forged)
                        .header("X-Razorpay-Signature", "deadbeef"))
                .andExpect(status().isBadRequest());

        // ₹450 + ₹40 delivery = ₹490
        assertThat(created.get("amountPaise").asLong()).isEqualTo(49000L);

        // wrong amount: not confirmed
        assertThat(sendWebhook(webhookBody("payment.captured", "pay_W1", rzpOrder, 100L), "evt_w_1", 200)
                .get("status").asText()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(order(c, number).get("status").asText()).isEqualTo("PENDING_PAYMENT");

        // right amount: confirmed
        assertThat(sendWebhook(webhookBody("payment.captured", "pay_W1", rzpOrder, 49000L), "evt_w_2", 200)
                .get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(order(c, number).get("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void only_one_of_many_buyers_gets_the_last_copy() throws Exception {
        Book book = createBook("Checkout Last Copy", "400", "350", 1, false);
        int buyers = 6;
        List<Customer> customers = new ArrayList<>();
        List<Long> addresses = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            Customer c = newCustomer(false);
            customers.add(c);
            addresses.add(addAddress(c));
        }

        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            Customer c = customers.get(i);
            long address = addresses.get(i);
            results.add(pool.submit(() -> {
                start.await();
                var res = mvc.perform(as(c, post("/api/checkout/create-order")).contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("source", "BUY_NOW", "variantId", book.paperback(),
                                        "quantity", 1, "paymentMethod", "ONLINE", "addressId", address))))
                        .andReturn().getResponse();
                // "201" for the winner, "409 OUT_OF_STOCK" for the rest (anything else, e.g. a deadlock, fails the test)
                return res.getStatus() == 201 ? "201"
                        : res.getStatus() + " " + json.readTree(res.getContentAsString()).path("error").asText();
            }));
        }
        start.countDown();
        List<String> statuses = new ArrayList<>();
        for (Future<String> f : results) {
            statuses.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses).filteredOn("201"::equals).hasSize(1);
        assertThat(statuses).filteredOn("409 OUT_OF_STOCK"::equals).as("all results: %s", statuses).hasSize(buyers - 1);
        assertThat(stock(book.paperback())).isZero();
    }

    @Test
    void unpaid_orders_expire_and_a_late_payment_is_still_honoured_if_stock_allows() throws Exception {
        Customer c = newCustomer(false);
        Book book = createBook("Checkout Expiry", "300", "250", 3, false);
        JsonNode created = buyNow(c, book.paperback(), 1, "ONLINE", addAddress(c), 201);
        String number = created.get("orderNumber").asText();
        assertThat(stock(book.paperback())).isEqualTo(2);

        expire(number);
        assertThat(order(c, number).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(stock(book.paperback())).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from inventory_movements where reference_id = ? and reason = 'RELEASE'",
                Integer.class, number)).isEqualTo(1);

        // retrying payment is no longer possible...
        mvc.perform(as(c, post("/api/checkout/" + number + "/retry")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("ORDER_NOT_PAYABLE"));

        // ...but money that arrives anyway is honoured while stock lasts
        JsonNode paid = verifyPayment(c, created.get("razorpayOrderId").asText(), "pay_LATE_OK", 200);
        assertThat(paid.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(stock(book.paperback())).isEqualTo(2);
    }

    @Test
    void a_late_payment_is_refunded_when_the_stock_is_gone() throws Exception {
        Customer late = newCustomer(false);
        Customer other = newCustomer(false);
        Book book = createBook("Checkout Late Refund", "300", "250", 1, false);
        JsonNode created = buyNow(late, book.paperback(), 1, "ONLINE", addAddress(late), 201);
        String number = created.get("orderNumber").asText();

        expire(number);
        buyNow(other, book.paperback(), 1, "ONLINE", addAddress(other), 201);   // takes the copy back
        assertThat(stock(book.paperback())).isZero();

        mvc.perform(as(late, post("/api/checkout/verify")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(signedVerify(created.get("razorpayOrderId").asText(), "pay_LATE_NO"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PAYMENT_REFUNDED"));

        // the whole payment comes back: ₹250 + ₹40 delivery
        verify(razorpay).refund(eq("pay_LATE_NO"), eq(29000L));
        JsonNode o = order(late, number);
        assertThat(o.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(o.get("paymentStatus").asText()).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("select count(*) from refunds r join orders o on o.id = r.order_id where o.order_number = ?",
                Integer.class, number)).isEqualTo(1);
    }

    @Test
    void razorpay_being_down_rolls_back_the_reservation() throws Exception {
        Customer c = newCustomer(false);
        Book book = createBook("Checkout Gateway Down", "300", "250", 4, false);
        when(razorpay.createOrder(anyLong(), anyString()))
                .thenThrow(new RazorpayGateway.GatewayException("down", null));

        JsonNode error = buyNow(c, book.paperback(), 2, "ONLINE", addAddress(c), 502);
        assertThat(error.get("error").asText()).isEqualTo("PAYMENT_GATEWAY_ERROR");
        assertThat(stock(book.paperback())).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from orders where user_id = ?", Integer.class, c.id())).isZero();
    }

    // ------------------------------------------------------------------
    // Cash on Delivery
    // ------------------------------------------------------------------

    @Test
    void cash_on_delivery_rules() throws Exception {
        Book cheap = createBook("Checkout COD Cheap", "300", "250", 10, true);
        Book pricey = createBook("Checkout COD Pricey", "2700", "2600", 10, false);

        Customer unverified = newCustomer(false);
        assertThat(buyNow(unverified, cheap.paperback(), 1, "COD", addAddress(unverified), 400)
                .get("error").asText()).isEqualTo("EMAIL_NOT_VERIFIED");

        Customer c = newCustomer(true);
        long address = addAddress(c);
        assertThat(buyNow(c, cheap.ebook(), 1, "COD", null, 400).get("error").asText()).isEqualTo("COD_NOT_ALLOWED");
        assertThat(buyNow(c, pricey.paperback(), 2, "COD", address, 400).get("error").asText()).isEqualTo("COD_NOT_ALLOWED");

        JsonNode first = buyNow(c, cheap.paperback(), 2, "COD", address, 201);
        assertThat(first.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(first.has("razorpayOrderId")).isFalse();
        // ₹500 is at the free-delivery threshold
        assertThat(first.get("total").decimalValue()).isEqualByComparingTo("500");
        JsonNode detail = order(c, first.get("orderNumber").asText());
        assertThat(detail.get("paymentStatus").asText()).isEqualTo("COD_PENDING");
        assertThat(stock(cheap.paperback())).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from inventory_movements where reference_id = ? and reason = 'SALE'",
                Integer.class, first.get("orderNumber").asText())).isEqualTo(1);

        buyNow(c, cheap.paperback(), 1, "COD", address, 201);
        assertThat(buyNow(c, cheap.paperback(), 1, "COD", address, 400).get("error").asText()).isEqualTo("COD_LIMIT");
        verify(razorpay, never()).refund(anyString(), anyLong());
    }

    // ------------------------------------------------------------------
    // Guards and addresses
    // ------------------------------------------------------------------

    @Test
    void checkout_guards() throws Exception {
        Customer c = newCustomer(false);
        Book book = createBook("Checkout Guards", "300", "250", 5, true);

        // physical books need an address, and only your own
        assertThat(buyNow(c, book.paperback(), 1, "ONLINE", null, 400).get("error").asText()).isEqualTo("ADDRESS_REQUIRED");
        Customer stranger = newCustomer(false);
        long theirs = addAddress(stranger);
        assertThat(buyNow(c, book.paperback(), 1, "ONLINE", theirs, 404).get("error").asText()).isEqualTo("ADDRESS_NOT_FOUND");

        // e-books need no address; delivery is free
        JsonNode ebook = buyNow(c, book.ebook(), 1, "ONLINE", null, 201);
        assertThat(ebook.get("total").decimalValue()).isEqualByComparingTo("150");

        // an empty Stack can't be checked out
        JsonNode empty = createOrder(c, Map.of("source", "CART", "paymentMethod", "ONLINE"), null, 400);
        assertThat(empty.get("error").asText()).isEqualTo("STACK_EMPTY");

        // too many copies
        assertThat(buyNow(c, book.paperback(), 9, "ONLINE", addAddress(c), 409).get("error").asText()).isEqualTo("OUT_OF_STOCK");

        // staff can't buy, other customers can't see the order
        mvc.perform(post("/api/checkout/create-order").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("source", "BUY_NOW", "variantId", book.ebook(),
                                "paymentMethod", "ONLINE"))))
                .andExpect(status().isForbidden());
        mvc.perform(as(stranger, get("/api/orders/" + ebook.get("orderNumber").asText())))
                .andExpect(status().isNotFound());

        JsonNode list = json.readTree(mvc.perform(as(c, get("/api/orders"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(list.get("totalElements").asInt()).isEqualTo(1);
        assertThat(list.get("content").get(0).get("awaitingPayment").asBoolean()).isTrue();
    }

    @Test
    void addresses_are_capped_and_keep_one_default() throws Exception {
        Customer c = newCustomer(false);
        long first = addAddress(c);
        long second = addAddress(c);
        JsonNode list = addresses(c);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(first);
        assertThat(list.get(0).get("isDefault").asBoolean()).isTrue();

        mvc.perform(as(c, patch("/api/account/addresses/" + second + "/default"))).andExpect(status().isOk());
        list = addresses(c);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(second);
        assertThat(countDefaults(list)).isEqualTo(1);

        mvc.perform(as(c, delete("/api/account/addresses/" + second))).andExpect(status().isNoContent());
        list = addresses(c);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("isDefault").asBoolean()).isTrue();

        for (int i = 0; i < 4; i++) {
            addAddress(c);
        }
        mvc.perform(as(c, post("/api/account/addresses")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(addressBody())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("ADDRESS_LIMIT"));

        Map<String, Object> bad = new LinkedHashMap<>(addressBody());
        bad.put("pincode", "12345");
        Customer d = newCustomer(false);
        mvc.perform(as(d, post("/api/account/addresses")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(bad)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.pincode").exists());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    record Customer(long id, String email, String token) {
    }

    record Book(long id, Long paperback, Long ebook) {
    }

    private Book createBook(String title, String mrp, String price, int stock, boolean withEbook) throws Exception {
        List<Map<String, Object>> variants = new ArrayList<>();
        variants.add(variant("PAPERBACK", mrp, price, stock));
        if (withEbook) {
            variants.add(variant("EBOOK", "200", "150", null));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("authors", List.of("Checkout Author"));
        body.put("categoryIds", List.of(categories.findBySlug("fiction").orElseThrow().getId()));
        body.put("variants", variants);
        JsonNode created = json.readTree(mvc.perform(post("/api/admin/books").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        Long pb = null;
        Long eb = null;
        for (JsonNode v : created.get("variants")) {
            if ("PAPERBACK".equals(v.get("format").asText())) {
                pb = v.get("id").asLong();
            } else if ("EBOOK".equals(v.get("format").asText())) {
                eb = v.get("id").asLong();
            }
        }
        return new Book(created.get("id").asLong(), pb, eb);
    }

    private static Map<String, Object> variant(String format, String mrp, String price, Integer stock) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("format", format);
        String base = String.format("9797%08d", ISBN_SERIAL.incrementAndGet());
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

    private int stock(long variantId) {
        return jdbc.queryForObject("select stock from book_variants where id = ?", Integer.class, variantId);
    }

    private void expire(String orderNumber) {
        jdbc.update("update orders set reserved_until = ? where order_number = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)), orderNumber);
        payments.expireOverdue();   // the scheduled job may get there first; the outcome is the same
    }

    private Customer newCustomer(boolean verifiedEmail) throws Exception {
        int n = USER_SERIAL.incrementAndGet();
        String email = "buyer" + n + "@example.com";
        String res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Book Buyer", "email", email,
                                "phone", "93210" + String.format("%05d", n), "password", "Str0ng@Pass"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(res).get("id").asLong();
        if (verifiedEmail) {
            jdbc.update("update users set email_verified = true where id = ?", id);
        }
        return new Customer(id, email, login(email, "Str0ng@Pass"));
    }

    private static Map<String, Object> addressBody() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("fullName", "Book Buyer");
        a.put("phone", "9876543210");
        a.put("line1", "12 Marine Drive");
        a.put("city", "Mumbai");
        a.put("state", "Maharashtra");
        a.put("pincode", "400001");
        a.put("type", "HOME");
        return a;
    }

    private long addAddress(Customer c) throws Exception {
        return json.readTree(mvc.perform(as(c, post("/api/account/addresses")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(addressBody())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode addresses(Customer c) throws Exception {
        return json.readTree(mvc.perform(as(c, get("/api/account/addresses"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static long countDefaults(JsonNode list) {
        long n = 0;
        for (JsonNode a : list) {
            if (a.get("isDefault").asBoolean()) {
                n++;
            }
        }
        return n;
    }

    private void addToStack(Customer c, Long variantId, int qty) throws Exception {
        mvc.perform(as(c, post("/api/cart/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("variantId", variantId, "quantity", qty))))
                .andExpect(status().isOk());
    }

    private JsonNode buyNow(Customer c, Long variantId, int qty, String method, Long addressId, int expectedStatus)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "BUY_NOW");
        body.put("variantId", variantId);
        body.put("quantity", qty);
        body.put("paymentMethod", method);
        if (addressId != null) {
            body.put("addressId", addressId);
        }
        return createOrder(c, body, null, expectedStatus);
    }

    private JsonNode createOrder(Customer c, Map<String, Object> body, String idempotencyKey, int expectedStatus)
            throws Exception {
        MockHttpServletRequestBuilder req = as(c, post("/api/checkout/create-order"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (idempotencyKey != null) {
            req = req.header("Idempotency-Key", idempotencyKey);
        }
        MvcResult res = mvc.perform(req).andExpect(status().is(expectedStatus)).andReturn();
        return json.readTree(res.getResponse().getContentAsString());
    }

    private Map<String, String> signedVerify(String rzpOrderId, String paymentId) {
        return Map.of("razorpayOrderId", rzpOrderId, "razorpayPaymentId", paymentId,
                "razorpaySignature", Signatures.hmacSha256Hex(rzpOrderId + "|" + paymentId, KEY_SECRET));
    }

    private JsonNode verifyPayment(Customer c, String rzpOrderId, String paymentId, int expectedStatus) throws Exception {
        return json.readTree(mvc.perform(as(c, post("/api/checkout/verify")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(signedVerify(rzpOrderId, paymentId))))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString());
    }

    private static String webhookBody(String event, String paymentId, String rzpOrderId, long amountPaise) {
        return "{\"event\":\"" + event + "\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                + "\",\"order_id\":\"" + rzpOrderId + "\",\"amount\":" + amountPaise
                + ",\"currency\":\"INR\",\"status\":\"captured\"}}}}";
    }

    private JsonNode sendWebhook(String body, String eventId, int expectedStatus) throws Exception {
        return json.readTree(mvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Razorpay-Signature", Signatures.hmacSha256Hex(body, WEBHOOK_SECRET))
                        .header("X-Razorpay-Event-Id", eventId))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString());
    }

    private JsonNode order(Customer c, String orderNumber) throws Exception {
        return json.readTree(mvc.perform(as(c, get("/api/orders/" + orderNumber))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
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
