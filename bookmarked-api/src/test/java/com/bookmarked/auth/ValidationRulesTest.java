package com.bookmarked.auth;

import com.bookmarked.auth.dto.AuthDtos;
import com.bookmarked.common.UserAgents;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"Str0ng@Pass", "Abcdef1!", "My-Long_Passw0rd"})
    void accepts_strong_passwords(String pw) {
        assertThat(pw).matches(AuthDtos.PASSWORD_REGEX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "Password1", "PASSWORD1!", "Sh0rt!", "alllower1!"})
    void rejects_weak_passwords(String pw) {
        assertThat(pw).doesNotMatch(AuthDtos.PASSWORD_REGEX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9876543210", "6000000000", "7123456789", "8999999999"})
    void accepts_indian_mobile_numbers(String phone) {
        assertThat(phone).matches(AuthDtos.PHONE_REGEX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5876543210", "987654321", "98765432101", "+919876543210", "98765 43210"})
    void rejects_invalid_mobile_numbers(String phone) {
        assertThat(phone).doesNotMatch(AuthDtos.PHONE_REGEX);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/130.0 Safari/537.36 | Chrome on macOS",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1 | Safari on iOS",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36 Edg/130.0 | Edge on Windows",
            "Mozilla/5.0 (Linux; Android 14) Gecko/20100101 Firefox/131.0 | Firefox on Android"
    })
    void describes_user_agents(String ua, String expected) {
        assertThat(UserAgents.describe(ua)).isEqualTo(expected);
    }
}
