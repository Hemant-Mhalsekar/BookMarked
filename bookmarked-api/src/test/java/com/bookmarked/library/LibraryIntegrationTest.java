package com.bookmarked.library;

import com.bookmarked.catalog.CategoryRepository;
import com.bookmarked.common.RateLimitService;
import com.bookmarked.mail.MailService;
import com.bookmarked.payment.RazorpayGateway;
import com.bookmarked.payment.Signatures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E-books end to end: staff upload (with checks and an automatic sample), buying,
 * My Library, signed read links, single-use watermarked downloads with a limit,
 * reading progress, and who may see what.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "app.seed.demo-catalog=false",
        "app.seed.super-admin-email=admin@library.test",
        "app.seed.super-admin-password=Admin@12345",
        "app.seed.super-admin-phone=9000000010",
        "app.cookie.secure=false",
        "app.razorpay.key-id=rzp_test_dummy",
        "app.razorpay.key-secret=" + LibraryIntegrationTest.KEY_SECRET
})
class LibraryIntegrationTest {

    static final String KEY_SECRET = "library_key_secret";

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) throws Exception {
        String dir = Files.createTempDirectory("bm-library-test").toString();
        registry.add("app.storage.local-dir", () -> dir);
    }

    private static final AtomicInteger ISBN_SERIAL = new AtomicInteger(5000);
    private static final AtomicInteger USER_SERIAL = new AtomicInteger(700);
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
        admin = login("admin@library.test", "Admin@12345");
        when(razorpay.createOrder(anyLong(), anyString())).thenAnswer(inv -> "order_L" + RZP_SERIAL.incrementAndGet());
        when(razorpay.refund(anyString(), anyLong())).thenAnswer(inv -> "rfnd_L" + RZP_SERIAL.incrementAndGet());
    }

    // ------------------------------------------------------------------
    // Uploads
    // ------------------------------------------------------------------

    @Test
    void staff_upload_is_checked_and_a_sample_is_cut_automatically() throws Exception {
        long ebook = createEbook("Library Upload Checks");
        long paperback = createBook("Library Paperback Only", "300", "250", 5);

        upload(ebook, "notes.pdf", "this is plain text pretending".getBytes(), 400, "NOT_A_PDF");
        // starts like a PDF but isn't one: rejected as unreadable or empty, depending on how far the parser gets
        assertThat(upload(ebook, "broken.pdf", "%PDF-1.7 but nothing else".getBytes(), 400, null).get("error").asText())
                .isIn("PDF_UNREADABLE", "PDF_EMPTY");
        upload(paperback, "book.pdf", pdf(3, "x"), 400, "NOT_AN_EBOOK");
        mvc.perform(asAdmin(get("/api/library"))).andExpect(status().isForbidden());

        JsonNode info = upload(ebook, "book.pdf", pdf(30, "Library Upload Checks"), 200, null);
        assertThat(info.get("uploaded").asBoolean()).isTrue();
        assertThat(info.get("pages").asInt()).isEqualTo(30);
        assertThat(info.get("hasSample").asBoolean()).isTrue();
        assertThat(info.get("samplePages").asInt()).isEqualTo(3);

        // the catalog now knows the page count and that a sample exists
        String slug = jdbc.queryForObject("select b.slug from books b join book_variants v on v.book_id = b.id where v.id = ?",
                String.class, ebook);
        JsonNode book = json.readTree(mvc.perform(get("/api/books/" + slug)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode variant = book.get("variants").get(0);
        assertThat(variant.get("pages").asInt()).isEqualTo(30);
        assertThat(variant.get("sampleAvailable").asBoolean()).isTrue();

        // the sample is public and only the first pages
        byte[] sample = stream(get("/api/books/variants/" + ebook + "/sample"));
        assertThat(pages(sample)).isEqualTo(3);
        mvc.perform(get("/api/books/variants/" + paperback + "/sample")).andExpect(status().isNotFound());

        // replacing the file replaces the sample and cleans up the old files
        String oldKey = jdbc.queryForObject("select storage_key from ebook_files where variant_id = ?", String.class, ebook);
        upload(ebook, "v2.pdf", pdf(120, "Second edition"), 200, null);
        assertThat(jdbc.queryForObject("select sample_pages from ebook_files where variant_id = ?", Integer.class, ebook))
                .isEqualTo(10);
        assertThat(jdbc.queryForObject("select storage_key from ebook_files where variant_id = ?", String.class, ebook))
                .isNotEqualTo(oldKey);

        // staff can swap in their own sample
        JsonNode custom = json.readTree(mvc.perform(asAdmin(multipart(HttpMethod.PUT, "/api/admin/variants/" + ebook + "/ebook/sample")
                        .file(new MockMultipartFile("file", "sample.pdf", "application/pdf", pdf(2, "Hand-picked sample")))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(custom.get("samplePages").asInt()).isEqualTo(2);

        // customers can't upload
        Customer c = newCustomer(false);
        mvc.perform(as(c, multipart(HttpMethod.PUT, "/api/admin/variants/" + ebook + "/ebook")
                        .file(new MockMultipartFile("file", "x.pdf", "application/pdf", pdf(1, "x")))))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Reading and downloading
    // ------------------------------------------------------------------

    @Test
    void owners_read_through_signed_links_and_download_watermarked_copies() throws Exception {
        long ebook = createEbook("Library Reading");
        upload(ebook, "book.pdf", pdf(12, "Library Reading"), 200, null);
        Customer c = newCustomer(false);
        Customer stranger = newCustomer(false);

        // not bought yet
        mvc.perform(as(c, post("/api/library/" + ebook + "/read-link")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_IN_LIBRARY"));

        buyEbook(c, ebook);
        JsonNode library = fetch(c, "/api/library");
        assertThat(library).hasSize(1);
        assertThat(library.get(0).get("title").asText()).isEqualTo("Library Reading");
        assertThat(library.get(0).get("authors").get(0).asText()).isEqualTo("Library Author");
        assertThat(library.get(0).get("downloadsLeft").asInt()).isEqualTo(5);
        assertThat(library.get(0).get("available").asBoolean()).isTrue();
        assertThat(fetch(c, "/api/library/ids").get(0).asLong()).isEqualTo(ebook);

        // read: a link that works without the login token, repeatedly, until it expires
        String readUrl = fetchPost(c, "/api/library/" + ebook + "/read-link").get("url").asText();
        assertThat(readUrl).startsWith("/api/files/");
        byte[] full = stream(get(readUrl));
        assertThat(pages(full)).isEqualTo(12);
        assertThat(pages(stream(get(readUrl)))).isEqualTo(12);
        mvc.perform(get(readUrl + "x")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("LINK_INVALID"));
        mvc.perform(as(stranger, post("/api/library/" + ebook + "/read-link"))).andExpect(status().isNotFound());

        // download: watermarked with the buyer, single use
        JsonNode dl = fetchPost(c, "/api/library/" + ebook + "/download-link");
        assertThat(dl.get("downloadsLeft").asInt()).isEqualTo(4);
        MvcResult started = mvc.perform(get(dl.get("url").asText())).andExpect(request().asyncStarted()).andReturn();
        byte[] copy = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(copy)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(12);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Licensed to Book Reader <" + c.email() + ">");
            assertThat(doc.getDocumentInformation().getCustomMetadataValue("BookMarkedLicensee")).contains(c.email());
        }
        mvc.perform(get(dl.get("url").asText()))
                .andExpect(status().isGone()).andExpect(jsonPath("$.error").value("LINK_USED"));

        // five downloads per book
        for (int i = 0; i < 4; i++) {
            fetchPost(c, "/api/library/" + ebook + "/download-link");
        }
        mvc.perform(as(c, post("/api/library/" + ebook + "/download-link")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("DOWNLOAD_LIMIT"));
        assertThat(jdbc.queryForObject("select count(*) from download_logs where user_id = ? and variant_id = ?",
                Integer.class, c.id(), ebook)).isEqualTo(5);
        assertThat(fetch(c, "/api/library").get(0).get("downloadsLeft").asInt()).isZero();
        // reading still works after the downloads run out
        assertThat(pages(stream(get(fetchPost(c, "/api/library/" + ebook + "/read-link").get("url").asText())))).isEqualTo(12);
    }

    @Test
    void reading_progress_is_saved_per_customer_and_clamped() throws Exception {
        long ebook = createEbook("Library Progress");
        upload(ebook, "book.pdf", pdf(8, "Library Progress"), 200, null);
        Customer c = newCustomer(false);
        buyEbook(c, ebook);

        JsonNode saved = json.readTree(mvc.perform(as(c, put("/api/library/" + ebook + "/progress"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"page\":40,\"totalPages\":8}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(saved.get("lastPage").asInt()).isEqualTo(8);
        mvc.perform(as(c, put("/api/library/" + ebook + "/progress"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"page\":3,\"totalPages\":8}"))
                .andExpect(status().isOk());
        JsonNode book = fetch(c, "/api/library/" + ebook);
        assertThat(book.get("lastPage").asInt()).isEqualTo(3);
        assertThat(book.get("totalPages").asInt()).isEqualTo(8);
        assertThat(book.get("lastReadAt").asText()).isNotBlank();

        mvc.perform(as(c, put("/api/library/" + ebook + "/progress"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"page\":0,\"totalPages\":8}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(newCustomer(false), put("/api/library/" + ebook + "/progress"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"page\":2,\"totalPages\":8}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void a_bought_e_book_without_a_file_says_it_is_being_prepared() throws Exception {
        long ebook = createEbook("Library No File Yet");
        Customer c = newCustomer(false);
        buyEbook(c, ebook);
        assertThat(fetch(c, "/api/library").get(0).get("available").asBoolean()).isFalse();
        mvc.perform(as(c, post("/api/library/" + ebook + "/read-link")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("EBOOK_NOT_READY"));
        // and no download was used up
        mvc.perform(as(c, post("/api/library/" + ebook + "/download-link"))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select download_count from user_library where user_id = ? and variant_id = ?",
                Integer.class, c.id(), ebook)).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    static byte[] pdf(int pages, String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage(PDRectangle.A5);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 14);
                    cs.newLineAtOffset(40, 500);
                    cs.showText(text + " - page " + i);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static int pages(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    private JsonNode upload(long variantId, String name, byte[] content, int expected, String error) throws Exception {
        String body = mvc.perform(asAdmin(multipart(HttpMethod.PUT, "/api/admin/variants/" + variantId + "/ebook")
                        .file(new MockMultipartFile("file", name, "application/pdf", content))))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        JsonNode res = json.readTree(body);
        if (error != null) {
            assertThat(res.get("error").asText()).isEqualTo(error);
        }
        return res;
    }

    /** File responses stream asynchronously; MockMvc needs the second dispatch to collect them. */
    private byte[] stream(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult started = mvc.perform(req).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private void buyEbook(Customer c, long variantId) throws Exception {
        JsonNode created = order(c, List.of(Map.of("variantId", variantId, "quantity", 1)), "ONLINE");
        pay(c, created);
    }

    private JsonNode fetchPost(Customer c, String url) throws Exception {
        return json.readTree(mvc.perform(as(c, post(url))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

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
        body.put("authors", List.of("Library Author"));
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
        String base = String.format("9794%08d", ISBN_SERIAL.incrementAndGet());
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
        String email = "libreader" + n + "@example.com";
        String res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Book Reader", "email", email,
                                "phone", "96210" + String.format("%05d", n), "password", "Str0ng@Pass"))))
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
