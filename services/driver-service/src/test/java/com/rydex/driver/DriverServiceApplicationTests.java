package com.rydex.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DriverServiceApplicationTests extends IntegrationTest {

    @Test
    void healthEndpointReportsUp() {
        var res = api().get("/actuator/health", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().path("status").asString()).isEqualTo("UP");
    }

    @Test
    void seedsFiftyDemoDriversMatchingRideServiceUsers() {
        Map<String, Integer> byType = jdbc.sql("""
                        SELECT vehicle_type, count(*) AS n FROM drivers
                        WHERE user_id::text LIKE '00000000-0000-4000-8000-0000000002%'
                        GROUP BY vehicle_type""")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getInt(2)))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(byType).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ECONOMY", 12, "COMFORT", 7, "XL", 4, "PREMIUM", 3, "BIKE", 13, "AUTO", 11));
        assertThat(jdbc.sql("SELECT count(DISTINCT upper(vehicle_number)) FROM drivers WHERE user_id::text LIKE '00000000-0000-4000-8000-0000000002%'")
                .query(Integer.class).single()).isEqualTo(50);
    }

    @Test
    void swaggerUiIsPublic() {
        var res = api().get("/v3/api-docs", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().path("paths").has("/api/drivers/me/offers/{rideId}/accept")).isTrue();
    }
}
