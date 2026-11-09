package com.bookmarked.library;

import com.bookmarked.common.ApiException;
import com.bookmarked.config.AppProperties;
import com.bookmarked.storage.StorageProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileLinksTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    private static FileLinks links(String secret, Instant now) {
        AppProperties app = new AppProperties(null, null,
                new AppProperties.Jwt(secret, null, null, null, null), null, null, null, null);
        return new FileLinks(app, new StorageProperties("./data/files", Duration.ofMinutes(5)), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String token(FileLinks.Link link) {
        return link.url().substring("/api/files/".length());
    }

    @Test
    void a_fresh_link_verifies_with_its_claims() {
        FileLinks links = links("a-very-long-test-secret-0123456789-abcdefghij", NOW);
        FileLinks.Link link = links.create(FileLinks.Purpose.DOWNLOAD, 42, 7);
        FileLinks.Claims c = links.verify(token(link));
        assertThat(c.purpose()).isEqualTo(FileLinks.Purpose.DOWNLOAD);
        assertThat(c.userId()).isEqualTo(42);
        assertThat(c.variantId()).isEqualTo(7);
        assertThat(c.nonce()).isEqualTo(link.nonce());
        assertThat(link.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(link.url()).doesNotContain("42|");   // the payload is encoded, not readable in logs at a glance
    }

    @Test
    void tampering_or_another_key_is_rejected() {
        FileLinks links = links("a-very-long-test-secret-0123456789-abcdefghij", NOW);
        String token = token(links.create(FileLinks.Purpose.READ, 42, 7));
        String payload = token.substring(0, token.indexOf('.'));
        String sig = token.substring(token.indexOf('.') + 1);

        // someone swaps the user id in the payload but keeps the signature
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(java.util.Base64.getUrlDecoder().decode(payload)).replace("|42|", "|43|").getBytes()) + "." + sig;
        for (String bad : new String[]{forged, payload + ".AAAA", "garbage", "", "a.b.c", payload + "." + sig + "x"}) {
            assertThatThrownBy(() -> links.verify(bad)).isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("LINK_INVALID");
        }
        assertThatThrownBy(() -> links("another-secret-entirely-0123456789-abcdefghij", NOW).verify(token))
                .extracting("code").isEqualTo("LINK_INVALID");
    }

    @Test
    void links_expire_after_five_minutes() {
        String token = token(links("a-very-long-test-secret-0123456789-abcdefghij", NOW).create(FileLinks.Purpose.READ, 1, 2));
        assertThat(links("a-very-long-test-secret-0123456789-abcdefghij", NOW.plusSeconds(299)).verify(token)).isNotNull();
        assertThatThrownBy(() -> links("a-very-long-test-secret-0123456789-abcdefghij", NOW.plusSeconds(300)).verify(token))
                .extracting("code").isEqualTo("LINK_EXPIRED");
    }
}
