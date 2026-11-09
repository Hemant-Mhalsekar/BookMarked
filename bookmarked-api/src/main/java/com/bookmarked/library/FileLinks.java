package com.bookmarked.library;

import com.bookmarked.common.ApiException;
import com.bookmarked.config.AppProperties;
import com.bookmarked.storage.StorageProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Short-lived signed links to e-book files, like an S3 pre-signed URL: whoever
 * holds the link can fetch that one file until it expires, without sending the
 * login token (which keeps it out of URLs and lets the PDF viewer fetch directly).
 * <p>
 * Token = base64url(payload) + "." + base64url(HMAC-SHA256(payload)), where payload is
 * {@code 1|purpose|userId|variantId|expiresAtEpochSeconds|nonce}. The key is derived
 * from the JWT secret, so no extra secret has to be configured.
 */
@Component
public class FileLinks {

    public enum Purpose {
        READ, DOWNLOAD
    }

    public record Claims(Purpose purpose, long userId, long variantId, Instant expiresAt, String nonce) {
    }

    public record Link(String url, Instant expiresAt, String nonce) {
    }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] key;
    private final StorageProperties props;
    private final Clock clock;

    public FileLinks(AppProperties app, StorageProperties props, Clock clock) {
        this.key = hmac(app.jwt().secret().getBytes(StandardCharsets.UTF_8),
                "bookmarked-file-links-v1".getBytes(StandardCharsets.UTF_8));
        this.props = props;
        this.clock = clock;
    }

    public Link create(Purpose purpose, long userId, long variantId) {
        Instant expires = clock.instant().plus(props.linkTtl());
        String nonce = UUID.randomUUID().toString();
        String payload = "1|" + purpose.name() + "|" + userId + "|" + variantId + "|" + expires.getEpochSecond() + "|" + nonce;
        String token = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "."
                + B64.encodeToString(hmac(key, payload.getBytes(StandardCharsets.UTF_8)));
        return new Link("/api/files/" + token, expires, nonce);
    }

    /** Checks the signature first, then the expiry. Any tampering gives the same answer. */
    public Claims verify(String token) {
        try {
            int dot = token == null ? -1 : token.indexOf('.');
            if (dot <= 0 || token.length() > 600) {
                throw invalid();
            }
            byte[] payload = B64D.decode(token.substring(0, dot));
            byte[] signature = B64D.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(hmac(key, payload), signature)) {
                throw invalid();
            }
            String[] parts = new String(payload, StandardCharsets.UTF_8).split("\\|");
            if (parts.length != 6 || !"1".equals(parts[0])) {
                throw invalid();
            }
            Claims claims = new Claims(Purpose.valueOf(parts[1]), Long.parseLong(parts[2]), Long.parseLong(parts[3]),
                    Instant.ofEpochSecond(Long.parseLong(parts[4])), parts[5]);
            if (!clock.instant().isBefore(claims.expiresAt())) {
                throw new ApiException(HttpStatus.GONE, "LINK_EXPIRED", "This link has expired. Open the book again from My Library.");
            }
            return claims;
        } catch (IllegalArgumentException e) {   // bad base64, number or purpose
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return ApiException.forbidden("LINK_INVALID", "This link isn't valid.");
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
