package com.sansfile.app.service.custom.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class MonitoringServiceTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dakar"));

    @Test
    void daysUntilReadsSendTextDateFormats() {
        assertThat(MonitoringService.daysUntil(TODAY.plusDays(10).toString())).isEqualTo(10);
        assertThat(MonitoringService.daysUntil(TODAY.plusDays(3) + "T23:59:59Z")).isEqualTo(3);
        assertThat(MonitoringService.daysUntil(TODAY.minusDays(2) + " 00:00:00")).isEqualTo(-2);
    }

    @Test
    void daysUntilIgnoresMissingOrUnreadableDates() {
        assertThat(MonitoringService.daysUntil(null)).isNull();
        assertThat(MonitoringService.daysUntil("")).isNull();
        assertThat(MonitoringService.daysUntil("31/12/2026")).isNull();
    }
}
