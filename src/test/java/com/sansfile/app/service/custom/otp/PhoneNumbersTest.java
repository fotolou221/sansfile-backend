package com.sansfile.app.service.custom.otp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhoneNumbersTest {

    @Test
    void senegaleseNumbersGetTheCountryCode() {
        assertThat(PhoneNumbers.normalize("77 123 45 67")).isEqualTo("+221771234567");
        assertThat(PhoneNumbers.normalize("221771234567")).isEqualTo("+221771234567");
        assertThat(PhoneNumbers.normalize("00221 77 123 45 67")).isEqualTo("+221771234567");
        assertThat(PhoneNumbers.normalize("+221 77 123 45 67")).isEqualTo("+221771234567");
    }

    @Test
    void internationalNumbersKeepTheirCountryCode() {
        assertThat(PhoneNumbers.normalize("+33 6 12 34 56 78")).isEqualTo("+33612345678");
        assertThat(PhoneNumbers.normalize("0033612345678")).isEqualTo("+33612345678");
        assertThat(PhoneNumbers.isSenegalese("+33612345678")).isFalse();
    }

    @Test
    void onlySenegaleseMobilesAndOpenCountriesCanReceiveACode() {
        assertThat(PhoneNumbers.isAllowedForSms("+221771234567")).isTrue();
        assertThat(PhoneNumbers.isAllowedForSms("+221338001234")).as("fixe sénégalais").isFalse();
        assertThat(PhoneNumbers.isAllowedForSms("+33612345678")).as("France").isTrue();
        assertThat(PhoneNumbers.isAllowedForSms("+2250701020304")).as("Côte d'Ivoire").isTrue();
        assertThat(PhoneNumbers.isAllowedForSms("+15145550123")).as("Canada").isTrue();
        assertThat(PhoneNumbers.isAllowedForSms("+8613800138000")).as("pays non ouvert").isFalse();
        assertThat(PhoneNumbers.isAllowedForSms("+3361")).as("trop court").isFalse();
        assertThat(PhoneNumbers.isAllowedForSms("")).isFalse();
    }

    @Test
    void countryCodeUsesTheLongestMatch() {
        assertThat(PhoneNumbers.allowedCountryCodeOf("+212612345678")).isEqualTo("212");
        assertThat(PhoneNumbers.allowedCountryCodeOf("+35261234567")).isEqualTo("352");
        assertThat(PhoneNumbers.allowedCountryCodeOf("+33612345678")).isEqualTo("33");
        assertThat(PhoneNumbers.allowedCountryCodeOf("+8613800138000")).isNull();
    }
}
