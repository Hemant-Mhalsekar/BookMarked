package com.bookmarked.review;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Buyers-only reviews (eligibility, HTML stripping, averages, helpful votes,
 * reports and moderation), the account dashboard and profile changes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "app.seed.demo-catalog=false",
        "app.seed.super-admin-email=admin@reviews.test",
        "app.seed.super-admin-password=Admin@12345",
        "app.seed.super-admin-phone=9000000011",
        "app.cookie.secure=false",
        "app.razorpay.key-id=rzp_test_dummy",
        "app.razorpay.key-secret=" + ReviewIntegrationTest.KEY_SECRET,
        "app.razorpay.webhook-secret=" + ReviewIntegrationTest.WEBHOOK_SECRET
})
class ReviewIntegrationTest {

    static final String KEY_SECRET = "reviews_key_secret";
    static final String WEBHOOK_SECRET = "reviews_webhook_secret";

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
        admin = login("admin@reviews.test", "Admin@12345");
        when(razorpay.createOrder(anyLong(), anyString())).thenAnswer(inv -> "order_V" + RZP_SERIAL.incrementAndGet());
        when(razorpay.refund(anyString(), anyLong())).thenAnswer(inv -> "rfnd_V" + RZP_SERIAL.incrementAndGet());
    }

    // ------------------------------------------------------------------
    // Reviews
    // ------------------------------------------------------------------

    @Test
    void buyers_review_once_and_the_average_follows() throws Exception {
        long ebook = createEbook("Reviews Main Book");
        String slug = slugOf(ebook);
        Customer a = newCustomer(false);
        Customer b = newCustomer(false);
        Customer stranger = newCustomer(false);

        // anyone can read; only buyers can write
        JsonNode anon = json.readTree(mvc.perform(get("/api/books/" + slug + "/reviews/summary"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(anon.get("canReview").asBoolean()).isFalse();
        assertThat(anon.get("ratingCount").asInt()).isZero();
        writeReview(stranger, slug, 5, "Loved it, truly great", 403, "NOT_ELIGIBLE");

        buy(a, ebook);
        buy(b, ebook);
        assertThat(summary(a, slug).get("canReview").asBoolean()).isTrue();

        JsonNode mine = writeReview(a, slug, 4, "<b>Great</b> book about <script>alert(1)</script> clean code", 201, null);
        assertThat(mine.get("comment").asText()).isEqualTo("Great book about alert(1) clean code");
        assertThat(mine.get("authorName").asText()).isEqualTo("Book R.");
        assertThat(mine.get("verifiedPurchase").asBoolean()).isTrue();
        writeReview(a, slug, 5, "Changed my mind, five stars", 409, "ALREADY_REVIEWED");
        writeReview(b, slug, 2, "<p>tiny</p>xx", 400, "COMMENT_TOO_SHORT");
        JsonNode theirs = writeReview(b, slug, 2, "Too long-winded for my taste.", 201, null);

        JsonNode s = summary(a, slug);
        assertThat(s.get("avgRating").decimalValue()).isEqualByComparingTo("3.0");
        assertThat(s.get("ratingCount").asInt()).isEqualTo(2);
        assertThat(s.get("distribution").get("4").asInt()).isEqualTo(1);
        assertThat(s.get("distribution").get("2").asInt()).isEqualTo(1);
        assertThat(s.get("myReview").get("id").asLong()).isEqualTo(mine.get("id").asLong());
        assertThat(s.get("canReview").asBoolean()).isFalse();
        assertThat(bookRating(slug)).isEqualByComparingTo("3.0");

        // helpful votes: one each, never on your own
        long reviewA = mine.get("id").asLong();
        JsonNode voted = json.readTree(mvc.perform(as(b, put("/api/reviews/" + reviewA + "/helpful")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(voted.get("helpfulCount").asInt()).isEqualTo(1);
        assertThat(voted.get("votedHelpful").asBoolean()).isTrue();
        mvc.perform(as(b, put("/api/reviews/" + reviewA + "/helpful"))).andExpect(jsonPath("$.helpfulCount").value(1));
        mvc.perform(as(a, put("/api/reviews/" + reviewA + "/helpful")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("OWN_REVIEW"));

        // sorting and filtering
        JsonNode lowest = json.readTree(mvc.perform(get("/api/books/" + slug + "/reviews").param("sort", "LOWEST"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(lowest.get("content").get(0).get("rating").asInt()).isEqualTo(2);
        JsonNode helpful = json.readTree(mvc.perform(get("/api/books/" + slug + "/reviews"))
                .andReturn().getResponse().getContentAsString());
        assertThat(helpful.get("content").get(0).get("id").asLong()).isEqualTo(reviewA);
        JsonNode fours = json.readTree(mvc.perform(get("/api/books/" + slug + "/reviews").param("stars", "4"))
                .andReturn().getResponse().getContentAsString());
        assertThat(fours.get("totalElements").asInt()).isEqualTo(1);

        // edit and delete move the average too
        mvc.perform(as(a, put("/api/reviews/" + reviewA)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("rating", 5, "comment", "On a second read, it's a classic."))))
                .andExpect(status().isOk());
        assertThat(bookRating(slug)).isEqualByComparingTo("3.5");
        mvc.perform(as(b, put("/api/reviews/" + reviewA)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("rating", 1, "comment", "Not my review to edit."))))
                .andExpect(status().isNotFound());
        mvc.perform(as(b, delete("/api/reviews/" + theirs.get("id").asLong()))).andExpect(status().isNoContent());
        assertThat(bookRating(slug)).isEqualByComparingTo("5.0");
        assertThat(jdbc.queryForObject("select rating_count from books where slug = ?", Integer.class, slug)).isEqualTo(1);
    }

    @Test
    void three_reports_hide_a_review_until_a_moderator_decides() throws Exception {
        long ebook = createEbook("Reviews Moderated Book");
        String slug = slugOf(ebook);
        Customer author = newCustomer(false);
        Customer fan = newCustomer(false);
        buy(author, ebook);
        buy(fan, ebook);
        long bad = writeReview(author, slug, 1, "Spammy text with a link somewhere", 201, null).get("id").asLong();
        writeReview(fan, slug, 5, "A genuinely useful book for me.", 201, null);
        assertThat(bookRating(slug)).isEqualByComparingTo("3.0");

        mvc.perform(as(author, post("/api/reviews/" + bad + "/report")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"testing\"}"))
                .andExpect(status().isConflict());
        for (int i = 0; i < 3; i++) {
            Customer reporter = newCustomer(false);
            report(reporter, bad, "Spam " + i);
            if (i == 0) {
                report(reporter, bad, "Same person again");   // counted once
            }
        }
        assertThat(jdbc.queryForObject("select status from reviews where id = ?", String.class, bad)).isEqualTo("HIDDEN");
        assertThat(bookRating(slug)).isEqualByComparingTo("5.0");
        JsonNode list = json.readTree(mvc.perform(get("/api/books/" + slug + "/reviews"))
                .andReturn().getResponse().getContentAsString());
        assertThat(list.get("totalElements").asInt()).isEqualTo(1);

        JsonNode queue = json.readTree(mvc.perform(asAdmin(get("/api/admin/reviews")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode row = null;
        for (JsonNode r : queue.get("content")) {
            if (r.get("id").asLong() == bad) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("reportCount").asInt()).isEqualTo(3);
        assertThat(row.get("reportReasons")).hasSize(3);
        assertThat(jdbc.queryForObject("select count(*) from notifications n join users u on u.id = n.user_id "
                + "where u.email = 'admin@reviews.test' and n.type = 'REVIEW_REPORTED'", Integer.class)).isPositive();

        mvc.perform(asAdmin(post("/api/admin/reviews/" + bad + "/restore"))).andExpect(status().isNoContent());
        assertThat(bookRating(slug)).isEqualByComparingTo("3.0");
        mvc.perform(asAdmin(post("/api/admin/reviews/" + bad + "/remove"))).andExpect(status().isNoContent());
        assertThat(bookRating(slug)).isEqualByComparingTo("5.0");
        mvc.perform(as(author, put("/api/reviews/" + bad)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("rating", 5, "comment", "Trying to sneak it back."))))
                .andExpect(status().isNotFound());
        mvc.perform(as(author, get("/api/admin/reviews"))).andExpect(status().isForbidden());
    }

    @Test
    void printed_books_become_reviewable_on_delivery() throws Exception {
        Customer c = newCustomer(true);
        long pb = createBook("Reviews Printed Book", "600", "520", 5);
        String slug = slugOf(pb);
        String number = order(c, List.of(Map.of("variantId", pb, "quantity", 1)), "COD").get("orderNumber").asText();
        assertThat(summary(c, slug).get("canReview").asBoolean()).isFalse();

        long itemId = adminOrder(number).get("items").get(0).get("id").asLong();
        adminAction(number, "process", Map.of("itemIds", List.of(itemId)));
        adminAction(number, "ship", shipBody(itemId));
        adminAction(number, "deliver", Map.of("itemIds", List.of(itemId)));
        assertThat(notificationTypes(c)).contains("REVIEW_REQUEST");
        assertThat(summary(c, slug).get("canReview").asBoolean()).isTrue();

        JsonNode waiting = fetch(c, "/api/account/reviews");
        assertThat(waiting.get("waiting").get(0).get("slug").asText()).isEqualTo(slug);
        JsonNode overview = fetch(c, "/api/account/overview");
        assertThat(overview.get("stats").get("orders").asInt()).isEqualTo(1);
        assertThat(overview.get("pending").findValuesAsText("type")).contains("REVIEW");
        assertThat(overview.get("recentOrders").get(0).get("orderNumber").asText()).isEqualTo(number);

        writeReview(c, slug, 5, "Arrived quickly and reads beautifully.", 201, null);
        JsonNode after = fetch(c, "/api/account/reviews");
        assertThat(after.get("reviews")).hasSize(1);
        assertThat(after.get("waiting")).isEmpty();
        assertThat(fetch(c, "/api/account/overview").get("pending").findValuesAsText("type")).doesNotContain("REVIEW");
        mvc.perform(asAdmin(get("/api/account/overview"))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Profile
    // ------------------------------------------------------------------

    @Test
    void name_and_phone_can_be_changed_safely() throws Exception {
        Customer c = newCustomer(false);
        Customer other = newCustomer(false);
        String otherPhone = jdbc.queryForObject("select phone from users where id = ?", String.class, other.id());

        mvc.perform(as(c, patch("/api/account/profile")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"<b>Hacker</b>\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(c, patch("/api/account/profile")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"  Asha   Rao \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullName").value("Asha Rao"));

        mvc.perform(as(c, post("/api/account/phone")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("phone", "9123456780", "password", "Wrong@Pass1"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("WRONG_PASSWORD"));
        mvc.perform(as(c, post("/api/account/phone")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("phone", otherPhone, "password", "Str0ng@Pass"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("PHONE_TAKEN"));
        mvc.perform(as(c, post("/api/account/phone")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("phone", "12345", "password", "Str0ng@Pass"))))
                .andExpect(status().isBadRequest());
        String fresh = "91" + String.format("%08d", c.id() % 100000000);
        mvc.perform(as(c, post("/api/account/phone")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("phone", fresh, "password", "Str0ng@Pass"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phone").value(fresh));
        // the new number works for login
        assertThat(login(fresh, "Str0ng@Pass")).isNotBlank();
    }

    // ---- review helpers ----

    private String slugOf(long variantId) {
        return jdbc.queryForObject("select b.slug from books b join book_variants v on v.book_id = b.id where v.id = ?",
                String.class, variantId);
    }

    private void buy(Customer c, long ebookVariant) throws Exception {
        pay(c, order(c, List.of(Map.of("variantId", ebookVariant, "quantity", 1)), "ONLINE"));
    }

    private JsonNode summary(Customer c, String slug) throws Exception {
        return fetch(c, "/api/books/" + slug + "/reviews/summary");
    }

    private java.math.BigDecimal bookRating(String slug) {
        return jdbc.queryForObject("select avg_rating from books where slug = ?", java.math.BigDecimal.class, slug);
    }

    private JsonNode writeReview(Customer c, String slug, int rating, String comment, int expected, String error)
            throws Exception {
        JsonNode res = json.readTree(mvc.perform(as(c, post("/api/books/" + slug + "/reviews"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("rating", rating, "title", "My take", "comment", comment))))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
        if (error != null) {
            assertThat(res.get("error").asText()).isEqualTo(error);
        }
        return res;
    }

    private void report(Customer c, long reviewId, String reason) throws Exception {
        mvc.perform(as(c, post("/api/reviews/" + reviewId + "/report")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", reason))))
                .andExpect(status().isNoContent());
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
        body.put("authors", List.of("Review Author"));
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
        String base = String.format("9793%08d", ISBN_SERIAL.incrementAndGet());
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
        String email = "reviewer" + n + "@example.com";
        String res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Book Reader", "email", email,
                                "phone", "97210" + String.format("%05d", n), "password", "Str0ng@Pass"))))
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
