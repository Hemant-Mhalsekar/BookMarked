package com.bookmarked.catalog;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Catalog against real MySQL: admin book management, public search (including
 * MySQL full-text), filters, sorting, visibility rules and the stock ledger.
 * Each test uses made-up words in its titles so tests don't see each other's books.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "app.seed.demo-catalog=false",
        "app.seed.super-admin-email=admin@test.local",
        "app.seed.super-admin-password=Admin@12345",
        "app.seed.super-admin-phone=9000000009",
        "app.cookie.secure=false"
})
class CatalogIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    private static final AtomicInteger ISBN_SERIAL = new AtomicInteger(1000);
    private static final AtomicInteger PHONE_SERIAL = new AtomicInteger(200);

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    CategoryRepository categories;
    @Autowired
    InventoryMovementRepository movements;

    @MockitoBean
    MailService mail;
    @MockitoBean
    RateLimitService rateLimit;

    private String adminToken;

    @BeforeEach
    void loginAdmin() throws Exception {
        adminToken = login("admin@test.local", "Admin@12345");
    }

    // ------------------------------------------------------------------

    @Test
    void admin_creates_a_book_and_customers_can_find_it_many_ways() throws Exception {
        long java = categoryId("java");
        JsonNode created = createBook(book("Zorblax Patterns in Java", "Plinkerton Vane", List.of(java),
                "A field guide to quillfeather techniques for modern services.",
                variant("PAPERBACK", "899", "699", 3),
                variant("HARDCOVER", "1299", "1099", 20),
                variant("EBOOK", "599", "399", null)));

        assertThat(created.get("slug").asText()).isEqualTo("zorblax-patterns-in-java");
        assertThat(created.get("variants")).hasSize(3);

        // title, author, ISBN, full-text (word only in the description), parent category
        assertSearchFinds("q=zorblax", "Zorblax Patterns in Java");
        assertSearchFinds("q=plinkerton", "Zorblax Patterns in Java");
        assertSearchFinds("q=" + created.get("variants").get(0).get("isbn").asText(), "Zorblax Patterns in Java");
        assertSearchFinds("q=quillfeather", "Zorblax Patterns in Java");
        assertSearchFinds("category=programming&q=zorblax", "Zorblax Patterns in Java");
        assertSearchFinds("format=EBOOK&q=zorblax", "Zorblax Patterns in Java");
        assertSearchFinds("minPrice=1000&maxPrice=1200&q=zorblax", "Zorblax Patterns in Java");

        // filters that must exclude it
        assertThat(searchTitles("category=history&q=zorblax")).isEmpty();
        assertThat(searchTitles("maxPrice=300&q=zorblax")).isEmpty();
        assertThat(searchTitles("minRating=4&q=zorblax")).isEmpty();

        // listing card: cheapest format and discount
        JsonNode card = search("q=zorblax").get("content").get(0);
        assertThat(card.get("fromPrice").decimalValue()).isEqualByComparingTo("399");
        assertThat(card.get("mrp").decimalValue()).isEqualByComparingTo("599");
        assertThat(card.get("discountPercent").asInt()).isEqualTo(33);
        assertThat(card.get("badges").toString()).contains("EBOOK_AVAILABLE");

        // detail page: low stock shows "only N left", healthy stock is never exposed
        JsonNode detail = getJson("/api/books/zorblax-patterns-in-java");
        Map<String, JsonNode> byFormat = new LinkedHashMap<>();
        detail.get("variants").forEach(v -> byFormat.put(v.get("format").asText(), v));
        assertThat(byFormat.get("PAPERBACK").get("stockStatus").asText()).isEqualTo("LOW_STOCK");
        assertThat(byFormat.get("PAPERBACK").get("stockLeft").asInt()).isEqualTo(3);
        assertThat(byFormat.get("HARDCOVER").get("stockStatus").asText()).isEqualTo("IN_STOCK");
        assertThat(byFormat.get("HARDCOVER").has("stockLeft")).isFalse();
        assertThat(byFormat.get("EBOOK").get("stockStatus").asText()).isEqualTo("DIGITAL");
        assertThat(detail.get("authors").get(0).get("name").asText()).isEqualTo("Plinkerton Vane");

        // search-as-you-type
        String suggestions = mvc.perform(get("/api/books/suggest").param("q", "zorbl"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(suggestions).contains("Zorblax Patterns in Java");
    }

    @Test
    void price_sort_and_out_of_stock_filter() throws Exception {
        long fiction = categoryId("fiction");
        createBook(book("Glimwick Alpha", "Ada Glimwick", List.of(fiction), null, variant("PAPERBACK", "500", "450", 5)));
        createBook(book("Glimwick Beta", "Ada Glimwick", List.of(fiction), null, variant("PAPERBACK", "300", "150", 0)));
        createBook(book("Glimwick Gamma", "Ada Glimwick", List.of(fiction), null, variant("PAPERBACK", "900", "800", 9)));

        assertThat(searchTitles("q=glimwick&sort=price_asc"))
                .containsExactly("Glimwick Beta", "Glimwick Alpha", "Glimwick Gamma");
        assertThat(searchTitles("q=glimwick&sort=price_desc"))
                .containsExactly("Glimwick Gamma", "Glimwick Alpha", "Glimwick Beta");
        assertThat(searchTitles("q=glimwick&inStock=true")).doesNotContain("Glimwick Beta");

        JsonNode beta = search("q=glimwick&sort=price_asc").get("content").get(0);
        assertThat(beta.get("badges").toString()).contains("OUT_OF_STOCK");
    }

    @Test
    void inactive_books_disappear_from_the_store_but_not_from_admin() throws Exception {
        JsonNode created = createBook(book("Hidden Tarnwhistle", "Tarn Writer", List.of(categoryId("science")), null,
                variant("PAPERBACK", "400", "350", 10)));
        long id = created.get("id").asLong();

        mvc.perform(withAdmin(patch("/api/admin/books/" + id + "/active"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertThat(searchTitles("q=tarnwhistle")).isEmpty();
        mvc.perform(get("/api/books/hidden-tarnwhistle"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BOOK_NOT_FOUND"));

        String adminList = mvc.perform(withAdmin(get("/api/admin/books")).param("q", "tarnwhistle"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(adminList).contains("Hidden Tarnwhistle");
    }

    @Test
    void update_deactivates_removed_formats_and_records_stock_changes() throws Exception {
        long history = categoryId("history");
        JsonNode created = createBook(book("Quorvath Chronicles", "Mira Quorvath", List.of(history), null,
                variant("PAPERBACK", "600", "500", 5),
                variant("EBOOK", "300", "250", null)));
        long id = created.get("id").asLong();
        String paperbackIsbn = created.get("variants").get(0).get("isbn").asText();
        long paperbackId = created.get("variants").get(0).get("id").asLong();

        Map<String, Object> update = book("Quorvath Chronicles", "Mira Quorvath", List.of(history), null,
                variantWithIsbn("PAPERBACK", paperbackIsbn, "600", "480", 8));
        JsonNode updated = json.readTree(mvc.perform(withAdmin(put("/api/admin/books/" + id))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(update)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        Map<String, JsonNode> byFormat = new LinkedHashMap<>();
        updated.get("variants").forEach(v -> byFormat.put(v.get("format").asText(), v));
        assertThat(byFormat.get("EBOOK").get("active").asBoolean()).isFalse();
        assertThat(byFormat.get("PAPERBACK").get("stock").asInt()).isEqualTo(8);

        JsonNode detail = getJson("/api/books/quorvath-chronicles");
        assertThat(detail.get("variants")).hasSize(1);
        assertThat(search("q=quorvath").get("content").get(0).get("fromPrice").decimalValue()).isEqualByComparingTo("480");

        List<InventoryMovement> ledger = movements.findByVariantIdOrderByCreatedAtDesc(paperbackId);
        assertThat(ledger).extracting(InventoryMovement::getChangeQty).containsExactlyInAnyOrder(5, 3);
        assertThat(ledger).extracting(InventoryMovement::getReason)
                .containsExactlyInAnyOrder(InventoryMovement.Reason.RESTOCK, InventoryMovement.Reason.ADJUSTMENT);
    }

    @Test
    void invalid_books_are_rejected() throws Exception {
        long sci = categoryId("science");

        expectError(book("Bad Price", "A Writer", List.of(sci), null, variant("PAPERBACK", "100", "150", 1)),
                400, "PRICE_ABOVE_MRP");
        expectError(book("Bad Isbn", "A Writer", List.of(sci), null,
                variantWithIsbn("PAPERBACK", "9780000000001", "100", "90", 1)), 400, "INVALID_ISBN");
        expectError(book("Twice Paperback", "A Writer", List.of(sci), null,
                variant("PAPERBACK", "100", "90", 1), variant("PAPERBACK", "100", "90", 1)), 400, "DUPLICATE_FORMAT");
        expectError(book("No Stock", "A Writer", List.of(sci), null, variant("PAPERBACK", "100", "90", null)),
                400, "STOCK_REQUIRED");
        expectError(book("Ghost Category", "A Writer", List.of(999_999L), null, variant("EBOOK", "100", "90", null)),
                400, "UNKNOWN_CATEGORY");

        JsonNode first = createBook(book("Isbn Owner", "A Writer", List.of(sci), null, variant("EBOOK", "100", "90", null)));
        String takenIsbn = first.get("variants").get(0).get("isbn").asText();
        expectError(book("Isbn Thief", "A Writer", List.of(sci), null,
                variantWithIsbn("EBOOK", takenIsbn, "100", "90", null)), 409, "ISBN_TAKEN");

        Map<String, Object> missingTitle = book("", "A Writer", List.of(sci), null, variant("EBOOK", "100", "90", null));
        mvc.perform(withAdmin(post("/api/admin/books")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(missingTitle)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.title").exists());
    }

    @Test
    void same_title_gets_a_unique_slug() throws Exception {
        long fiction = categoryId("fiction");
        JsonNode a = createBook(book("Echo Valley", "First Author", List.of(fiction), null, variant("EBOOK", "100", "90", null)));
        JsonNode b = createBook(book("Echo Valley", "Second Author", List.of(fiction), null, variant("EBOOK", "100", "90", null)));
        assertThat(a.get("slug").asText()).isEqualTo("echo-valley");
        assertThat(b.get("slug").asText()).isEqualTo("echo-valley-2");
    }

    @Test
    void customers_cannot_manage_the_catalog() throws Exception {
        String customer = registerAndLoginCustomer();
        mvc.perform(post("/api/admin/books").header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(book("Nope", "X Y", List.of(1L), null,
                                variant("EBOOK", "100", "90", null)))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/books").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void category_tree_includes_new_subcategories() throws Exception {
        long programming = categoryId("programming");
        mvc.perform(withAdmin(post("/api/admin/categories")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kotlin\",\"parentId\":" + programming + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("kotlin"));

        JsonNode tree = json.readTree(mvc.perform(get("/api/categories"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> programmingChildren = new ArrayList<>();
        tree.forEach(node -> {
            if (node.get("slug").asText().equals("programming")) {
                node.get("children").forEach(ch -> programmingChildren.add(ch.get("slug").asText()));
            }
        });
        assertThat(programmingChildren).contains("java", "kotlin", "software-engineering");

        // a subcategory cannot get its own subcategory
        long kotlin = categoryId("kotlin");
        mvc.perform(withAdmin(post("/api/admin/categories")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ktor\",\"parentId\":" + kotlin + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARENT"));
    }

    @Test
    void bad_search_parameters_are_rejected() throws Exception {
        mvc.perform(get("/api/books").param("sort", "cheapest")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SORT"));
        mvc.perform(get("/api/books").param("size", "500")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGE_SIZE"));
        mvc.perform(get("/api/books").param("format", "AUDIO")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_FORMAT"));
        mvc.perform(get("/api/books").param("minPrice", "abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
        // LIKE wildcards in the query are treated literally
        mvc.perform(get("/api/books").param("q", "%_!")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private long categoryId(String slug) {
        return categories.findBySlug(slug).orElseThrow().getId();
    }

    @SafeVarargs
    private Map<String, Object> book(String title, String author, List<Long> categoryIds, String description,
                                     Map<String, Object>... variants) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", description);
        body.put("language", "English");
        body.put("publisher", "Test Press");
        body.put("publishedYear", 2024);
        body.put("authors", List.of(author));
        body.put("categoryIds", categoryIds);
        body.put("variants", List.of(variants));
        return body;
    }

    private Map<String, Object> variant(String format, String mrp, String price, Integer stock) {
        return variantWithIsbn(format, isbn13(ISBN_SERIAL.incrementAndGet()), mrp, price, stock);
    }

    private Map<String, Object> variantWithIsbn(String format, String isbn, String mrp, String price, Integer stock) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("format", format);
        v.put("isbn", isbn);
        v.put("pages", 300);
        v.put("mrp", mrp);
        v.put("sellingPrice", price);
        v.put("stock", stock);
        return v;
    }

    /** Checksum-valid ISBN-13 in the 979-9 range. */
    static String isbn13(int serial) {
        String base = String.format("9799%08d", serial);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (base.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return base + (10 - sum % 10) % 10;
    }

    private JsonNode createBook(Map<String, Object> body) throws Exception {
        String res = mvc.perform(withAdmin(post("/api/admin/books")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    private void expectError(Map<String, Object> body, int status, String code) throws Exception {
        ResultActions result = mvc.perform(withAdmin(post("/api/admin/books")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error").value(code));
    }

    private JsonNode search(String query) throws Exception {
        return getJson("/api/books?" + query);
    }

    private List<String> searchTitles(String query) throws Exception {
        List<String> titles = new ArrayList<>();
        search(query).get("content").forEach(n -> titles.add(n.get("title").asText()));
        return titles;
    }

    private void assertSearchFinds(String query, String title) throws Exception {
        assertThat(searchTitles(query)).as("search ?%s", query).contains(title);
    }

    private JsonNode getJson(String url) throws Exception {
        return json.readTree(mvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder withAdmin(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + adminToken);
    }

    private String login(String identifier, String password) throws Exception {
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("identifier", identifier, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("accessToken").asText();
    }

    private String registerAndLoginCustomer() throws Exception {
        int n = PHONE_SERIAL.incrementAndGet();
        String email = "shopper" + n + "@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Shopper Person", "email", email,
                                "phone", "97654" + String.format("%05d", n), "password", "Str0ng@Pass"))))
                .andExpect(status().isCreated());
        return login(email, "Str0ng@Pass");
    }
}
