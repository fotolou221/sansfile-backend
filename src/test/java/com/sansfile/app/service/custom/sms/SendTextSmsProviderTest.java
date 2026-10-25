package com.sansfile.app.service.custom.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SendTextSmsProviderTest {

    @Test
    void senegaleseNumbersAreSentWithoutPlus() {
        assertThat(SendTextSmsProvider.toSendTextPhone("+221771234567")).isEqualTo("221771234567");
        assertThat(SendTextSmsProvider.toSendTextPhone("771234567")).isEqualTo("221771234567");
        assertThat(SendTextSmsProvider.toSendTextPhone("00221771234567")).isEqualTo("221771234567");
    }

    @Test
    void numbersFromOpenCountriesAreAccepted() {
        assertThat(SendTextSmsProvider.toSendTextPhone("+33612345678")).isEqualTo("33612345678");
        assertThat(SendTextSmsProvider.toSendTextPhone("+2250701020304")).isEqualTo("2250701020304");
    }

    @Test
    void numbersFromOtherCountriesAreRefused() {
        assertThat(SendTextSmsProvider.toSendTextPhone("+8613800138000")).isNull();
        assertThat(SendTextSmsProvider.toSendTextPhone("12345")).isNull();
        assertThat(SendTextSmsProvider.toSendTextPhone(null)).isNull();
    }
}
