package com.bookmarked.cart;

import com.bookmarked.catalog.CategoryRepository;
import com.bookmarked.common.RateLimitService;
import com.bookmarked.mail.MailService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Your Stack, Bookmarks, related books and recently viewed against real MySQL.
 * Every test creates its own books and its own customer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "app.seed.demo-catalog=false",
        "app.seed.super-admin-email=admin@stack.test",
        "app.seed.super-admin-password=Admin@12345",
        "app.seed.super-admin-phone=9000000008",
        "app.cookie.secure=false"
})
class StackIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    private static final AtomicInteger ISBN_SERIAL = new AtomicInteger(5000);
    private static final AtomicInteger USER_SERIAL = new AtomicInteger(300);

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

    private String admin;
    private String customer;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin@stack.test", "Admin@12345");
        customer = newCustomer();
    }

    // ------------------------------------------------------------------
    // Your Stack
    // ------------------------------------------------------------------

    @Test
    void add_update_and_price_the_stack() throws Exception {
        Book clean = createBook("Stack Clean Code", "899", "699", 12, "599", "399");
        Book habits = createBook("Stack Atomic Habits", "699", "499", 30, null, null);

        add(clean.paperback(), 1);
        JsonNode view = add(habits.paperback(), 1);

        JsonNode summary = view.get("summary");
        assertThat(summary.get("mrpTotal").decimalValue()).isEqualByComparingTo("1598");
        assertThat(summary.get("discount").decimalValue()).isEqualByComparingTo("400");
        assertThat(summary.get("deliveryFee").decimalValue()).isEqualByComparingTo("0");
        assertThat(summary.get("total").decimalValue()).isEqualByComparingTo("1198");
        assertThat(view.get("checkoutBlocked").asBoolean()).isFalse();

        // adding the same format again increases the quantity
        view = add(clean.paperback(), 2);
        assertThat(line(view, clean.paperback()).get("quantity").asInt()).isEqualTo(3);
        assertThat(view.get("totalQuantity").asInt()).isEqualTo(4);

        long itemId = line(view, clean.paperback()).get("itemId").asLong();
        view = send(patch("/api/cart/items/" + itemId), Map.of("quantity", 1));
        assertThat(line(view, clean.paperback()).get("quantity").asInt()).isEqualTo(1);

        view = send(delete("/api/cart/items/" + itemId), null);
        assertThat(view.get("lines")).hasSize(1);
        JsonNode remaining = view.get("summary");
        assertThat(remaining.get("subtotal").decimalValue()).isEqualByComparingTo("499");
        assertThat(remaining.get("deliveryFee").decimalValue()).isEqualByComparingTo("0");

        // the stack persists across requests
        JsonNode fetched = json.readTree(mvc.perform(asCustomer(get("/api/cart")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(fetched.get("lines")).hasSize(1);
    }

    @Test
    void ebooks_are_single_copy_and_physical_books_are_capped_by_stock() throws Exception {
        Book book = createBook("Stack Limited Copies", "500", "400", 3, "300", "200");

        JsonNode view = add(book.ebook(), 1);
        view = add(book.ebook(), 1);
        assertThat(line(view, book.ebook()).get("quantity").asInt()).isEqualTo(1);
        assertThat(view.get("notice").asText()).contains("one copy");
        assertThat(view.get("summary").get("deliveryFee").decimalValue()).isEqualByComparingTo("0");

        view = add(book.paperback(), 5);
        assertThat(line(view, book.paperback()).get("quantity").asInt()).isEqualTo(3);
        assertThat(line(view, book.paperback()).get("stockStatus").asText()).isEqualTo("LOW_STOCK");
        assertThat(view.get("notice").asText()).contains("Only 3 available");

        // quantity above 10 is rejected outright
        mvc.perform(asCustomer(post("/api/cart/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("variantId", book.paperback(), "quantity", 11))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sold_out_or_hidden_books_block_checkout() throws Exception {
        Book book = createBook("Stack Vanishing Book", "500", "400", 2, null, null);
        JsonNode view = add(book.paperback(), 2);
        assertThat(view.get("checkoutBlocked").asBoolean()).isFalse();

        // stock drops to 1: the line now asks for more than is available
        updateBook(book, "500", "400", 1);
        view = getStack();
        assertThat(line(view, book.paperback()).get("issue").asText()).isEqualTo("QUANTITY_EXCEEDS_STOCK");
        assertThat(view.get("checkoutBlocked").asBoolean()).isTrue();
        assertThat(view.get("summary").get("total").decimalValue()).isEqualByComparingTo("0");

        // the book is hidden from the store
        mvc.perform(withAdmin(patch("/api/admin/books/" + book.id() + "/active"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andExpect(status().isOk());
        view = getStack();
        assertThat(line(view, book.paperback()).get("issue").asText()).isEqualTo("UNAVAILABLE");

        // and you can't add a sold-out or hidden format
        mvc.perform(asCustomer(post("/api/cart/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("variantId", book.paperback(), "quantity", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("UNAVAILABLE"));
        Book soldOut = createBook("Stack Sold Out", "300", "250", 0, null, null);
        mvc.perform(asCustomer(post("/api/cart/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("variantId", soldOut.paperback(), "quantity", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("OUT_OF_STOCK"));
    }

    @Test
    void price_changes_are_flagged_until_accepted() throws Exception {
        Book book = createBook("Stack Repriced", "800", "700", 20, null, null);
        add(book.paperback(), 1);

        updateBook(book, "800", "650", 20);
        JsonNode view = getStack();
        assertThat(view.get("pricesChanged").asBoolean()).isTrue();
        assertThat(line(view, book.paperback()).get("previousPrice").decimalValue()).isEqualByComparingTo("700");
        assertThat(line(view, book.paperback()).get("unitPrice").decimalValue()).isEqualByComparingTo("650");

        view = send(post("/api/cart/accept-prices"), null);
        assertThat(view.get("pricesChanged").asBoolean()).isFalse();
        assertThat(line(view, book.paperback()).has("previousPrice")).isFalse();
    }

    @Test
    void owned_ebooks_cannot_be_added() throws Exception {
        Book book = createBook("Stack Already Owned", "300", "250", 5, "200", "150");
        long userId = json.readTree(mvc.perform(asCustomer(get("/api/account/me")))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        long orderId = insertDummyOrder(userId);
        jdbc.update("insert into user_library (user_id, variant_id, order_id, purchased_at) values (?, ?, ?, now(6))",
                userId, book.ebook(), orderId);

        mvc.perform(asCustomer(post("/api/cart/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("variantId", book.ebook(), "quantity", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ALREADY_OWNED"));
    }

    @Test
    void guest_preview_and_merge_on_login() throws Exception {
        Book a = createBook("Stack Guest One", "400", "300", 4, "250", "150");
        Book b = createBook("Stack Guest Two", "350", "299", 10, null, null);

        List<Map<String, Object>> guest = List.of(
                Map.of("variantId", a.paperback(), "quantity", 3),
                Map.of("variantId", a.ebook(), "quantity", 4),
                Map.of("variantId", 999_999, "quantity", 1));

        // preview needs no login; unknown ids are dropped and e-books count once
        JsonNode preview = json.readTree(mvc.perform(post("/api/cart/preview").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("items", guest))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(preview.get("lines")).hasSize(2);
        assertThat(line(preview, a.ebook()).get("quantity").asInt()).isEqualTo(1);
        assertThat(preview.get("summary").get("subtotal").decimalValue()).isEqualByComparingTo("1050");

        // the account already has 2 paperbacks of A and one of B; merging adds and caps at stock (4)
        add(a.paperback(), 2);
        add(b.paperback(), 1);
        JsonNode merged = send(post("/api/cart/merge"), Map.of("items", guest));
        assertThat(line(merged, a.paperback()).get("quantity").asInt()).isEqualTo(4);
        assertThat(line(merged, a.ebook()).get("quantity").asInt()).isEqualTo(1);
        assertThat(line(merged, b.paperback()).get("quantity").asInt()).isEqualTo(1);
        assertThat(merged.get("notice").asText()).contains("adjusted");
    }

    @Test
    void save_for_later_moves_the_book_to_bookmarks() throws Exception {
        Book book = createBook("Stack Later Please", "450", "399", 6, null, null);
        JsonNode view = add(book.paperback(), 1);
        long itemId = line(view, book.paperback()).get("itemId").asLong();

        view = send(post("/api/cart/items/" + itemId + "/save-for-later"), null);
        assertThat(view.get("lines")).isEmpty();
        assertThat(bookmarkIds()).contains(book.id());
    }

    @Test
    void staff_cannot_shop_and_guests_must_log_in() throws Exception {
        Book book = createBook("Stack Staff Only", "300", "250", 5, null, null);
        mvc.perform(withAdmin(post("/api/cart/items")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("variantId", book.paperback(), "quantity", 1))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("STAFF_CANNOT_SHOP"));
        mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookmarks")).andExpect(status().isUnauthorized());
    }

    @Test
    void a_customer_cannot_touch_another_customers_stack() throws Exception {
        Book book = createBook("Stack Private Line", "300", "250", 5, null, null);
        JsonNode view = add(book.paperback(), 1);
        long itemId = line(view, book.paperback()).get("itemId").asLong();

        String other = newCustomer();
        add(other, book.paperback(), 1);   // the other customer has a stack too
        mvc.perform(patch("/api/cart/items/" + itemId).header("Authorization", "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ITEM_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // Bookmarks, related, recently viewed
    // ------------------------------------------------------------------

    @Test
    void bookmarks_are_idempotent_and_list_formats() throws Exception {
        Book book = createBook("Bookmark Me Twice", "500", "450", 8, "300", "250");
        mvc.perform(asCustomer(put("/api/bookmarks/" + book.id()))).andExpect(status().isNoContent());
        mvc.perform(asCustomer(put("/api/bookmarks/" + book.id()))).andExpect(status().isNoContent());
        assertThat(bookmarkIds()).containsOnlyOnce(book.id());

        JsonNode list = json.readTree(mvc.perform(asCustomer(get("/api/bookmarks")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("book").get("title").asText()).isEqualTo("Bookmark Me Twice");
        assertThat(list.get(0).get("variants")).hasSize(2);
        assertThat(list.get(0).get("available").asBoolean()).isTrue();

        mvc.perform(asCustomer(delete("/api/bookmarks/" + book.id()))).andExpect(status().isNoContent());
        mvc.perform(asCustomer(delete("/api/bookmarks/" + book.id()))).andExpect(status().isNoContent());
        assertThat(bookmarkIds()).doesNotContain(book.id());

        mvc.perform(asCustomer(put("/api/bookmarks/987654321"))).andExpect(status().isNotFound());
    }

    @Test
    void related_books_by_author_and_category() throws Exception {
        long fantasy = categoryId("fantasy");
        Book first = createBook("Related Dragon One", "Quill Morrow", fantasy, "400", "300", 5);
        Book second = createBook("Related Dragon Two", "Quill Morrow", fantasy, "400", "300", 5);
        Book neighbour = createBook("Related Elf Tale", "Other Pen", fantasy, "400", "300", 5);

        JsonNode related = json.readTree(mvc.perform(get("/api/books/" + first.slug() + "/related"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(titles(related.get("moreByAuthor"))).containsExactly("Related Dragon Two");
        assertThat(titles(related.get("similar"))).contains("Related Elf Tale")
                .doesNotContain("Related Dragon One", "Related Dragon Two");
        assertThat(second.id()).isNotEqualTo(neighbour.id());
    }

    @Test
    void recently_viewed_keeps_newest_first_and_merges_guest_history() throws Exception {
        Book a = createBook("Viewed Alpha", "300", "250", 5, null, null);
        Book b = createBook("Viewed Beta", "300", "250", 5, null, null);
        Book c = createBook("Viewed Gamma", "300", "250", 5, null, null);

        mvc.perform(asCustomer(post("/api/recently-viewed/" + a.id()))).andExpect(status().isNoContent());
        Thread.sleep(20);
        mvc.perform(asCustomer(post("/api/recently-viewed/" + b.id()))).andExpect(status().isNoContent());
        Thread.sleep(20);
        mvc.perform(asCustomer(post("/api/recently-viewed/" + a.id()))).andExpect(status().isNoContent());

        assertThat(recentlyViewed()).containsExactly("Viewed Alpha", "Viewed Beta");

        // guest history from before login: older than what's already recorded
        mvc.perform(asCustomer(post("/api/recently-viewed/merge")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("bookIds", List.of(c.id(), b.id())))))
                .andExpect(status().isNoContent());
        assertThat(recentlyViewed()).containsExactly("Viewed Alpha", "Viewed Beta", "Viewed Gamma");

        // cards by id keep the requested order (used for guests)
        JsonNode cards = json.readTree(mvc.perform(get("/api/books/by-ids").param("ids", c.id() + "," + a.id()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(titles(cards)).containsExactly("Viewed Gamma", "Viewed Alpha");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    record Book(long id, String slug, Long paperback, Long ebook, String paperbackIsbn, String ebookIsbn) {
    }

    private Book createBook(String title, String mrp, String price, int stock, String ebookMrp, String ebookPrice)
            throws Exception {
        return createBook(title, "Test Author", categoryId("fiction"), mrp, price, stock, ebookMrp, ebookPrice);
    }

    private Book createBook(String title, String author, long categoryId, String mrp, String price, int stock)
            throws Exception {
        return createBook(title, author, categoryId, mrp, price, stock, null, null);
    }

    private Book createBook(String title, String author, long categoryId, String mrp, String price, int stock,
                            String ebookMrp, String ebookPrice) throws Exception {
        List<Map<String, Object>> variants = new ArrayList<>();
        String pbIsbn = isbn13(ISBN_SERIAL.incrementAndGet());
        variants.add(variant("PAPERBACK", pbIsbn, mrp, price, stock));
        String ebIsbn = null;
        if (ebookMrp != null) {
            ebIsbn = isbn13(ISBN_SERIAL.incrementAndGet());
            variants.add(variant("EBOOK", ebIsbn, ebookMrp, ebookPrice, null));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("authors", List.of(author));
        body.put("categoryIds", List.of(categoryId));
        body.put("variants", variants);
        JsonNode created = json.readTree(mvc.perform(withAdmin(post("/api/admin/books"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        Long pb = null;
        Long eb = null;
        for (JsonNode v : created.get("variants")) {
            if (v.get("format").asText().equals("PAPERBACK")) {
                pb = v.get("id").asLong();
            } else if (v.get("format").asText().equals("EBOOK")) {
                eb = v.get("id").asLong();
            }
        }
        return new Book(created.get("id").asLong(), created.get("slug").asText(), pb, eb, pbIsbn, ebIsbn);
    }

    /** Re-saves the book's paperback with a new price/stock (and keeps its e-book). */
    private void updateBook(Book book, String mrp, String price, int stock) throws Exception {
        JsonNode current = json.readTree(mvc.perform(withAdmin(get("/api/admin/books/" + book.id())))
                .andReturn().getResponse().getContentAsString());
        List<Map<String, Object>> variants = new ArrayList<>();
        variants.add(variant("PAPERBACK", book.paperbackIsbn(), mrp, price, stock));
        for (JsonNode v : current.get("variants")) {
            if (v.get("format").asText().equals("EBOOK") && v.get("active").asBoolean()) {
                variants.add(variant("EBOOK", v.get("isbn").asText(), v.get("mrp").asText(),
                        v.get("sellingPrice").asText(), null));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", current.get("title").asText());
        body.put("authors", List.of(current.get("authors").get(0).get("name").asText()));
        body.put("categoryIds", List.of(current.get("categoryIds").get(0).asLong()));
        body.put("active", current.get("active").asBoolean());
        body.put("variants", variants);
        mvc.perform(withAdmin(put("/api/admin/books/" + book.id())).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isOk());
    }

    private static Map<String, Object> variant(String format, String isbn, String mrp, String price, Integer stock) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("format", format);
        v.put("isbn", isbn);
        v.put("mrp", mrp);
        v.put("sellingPrice", price);
        v.put("stock", stock);
        return v;
    }

    /** Checksum-valid ISBN-13 in a range no other test uses. */
    private static String isbn13(int serial) {
        String base = String.format("9798%08d", serial);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (base.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return base + (10 - sum % 10) % 10;
    }

    private long categoryId(String slug) {
        return categories.findBySlug(slug).orElseThrow().getId();
    }

    /** user_library needs an order to point at; checkout doesn't exist yet, so insert a minimal one. */
    private long insertDummyOrder(long userId) {
        String number = "BM-TEST-" + System.nanoTime();
        jdbc.update("""
                insert into orders (order_number, user_id, status, payment_method, payment_status, subtotal, total,
                                    created_at, updated_at)
                values (?, ?, 'CONFIRMED', 'ONLINE', 'PAID', 150, 150, now(6), now(6))
                """, number.substring(0, Math.min(24, number.length())), userId);
        return jdbc.queryForObject("select id from orders where user_id = ? order by id desc limit 1", Long.class, userId);
    }

    private JsonNode add(Long variantId, int qty) throws Exception {
        return add(customer, variantId, qty);
    }

    private JsonNode add(String token, Long variantId, int qty) throws Exception {
        return json.readTree(mvc.perform(post("/api/cart/items").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("variantId", variantId, "quantity", qty))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode getStack() throws Exception {
        return json.readTree(mvc.perform(asCustomer(get("/api/cart")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode send(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        MockHttpServletRequestBuilder req = asCustomer(builder);
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode line(JsonNode view, Long variantId) {
        for (JsonNode l : view.get("lines")) {
            if (l.get("variantId").asLong() == variantId) {
                return l;
            }
        }
        throw new AssertionError("No line for variant " + variantId + " in " + view);
    }

    private List<Long> bookmarkIds() throws Exception {
        List<Long> ids = new ArrayList<>();
        json.readTree(mvc.perform(asCustomer(get("/api/bookmarks/ids")))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .forEach(n -> ids.add(n.asLong()));
        return ids;
    }

    private List<String> recentlyViewed() throws Exception {
        return titles(json.readTree(mvc.perform(asCustomer(get("/api/recently-viewed")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
    }

    private static List<String> titles(JsonNode cards) {
        List<String> t = new ArrayList<>();
        cards.forEach(c -> t.add(c.get("title").asText()));
        return t;
    }

    private MockHttpServletRequestBuilder asCustomer(MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + customer);
    }

    private MockHttpServletRequestBuilder withAdmin(MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + admin);
    }

    private String login(String identifier, String password) throws Exception {
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("identifier", identifier, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("accessToken").asText();
    }

    private String newCustomer() throws Exception {
        int n = USER_SERIAL.incrementAndGet();
        String email = "stacker" + n + "@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Stack Shopper", "email", email,
                                "phone", "96543" + String.format("%05d", n), "password", "Str0ng@Pass"))))
                .andExpect(status().isCreated());
        return login(email, "Str0ng@Pass");
    }
}
