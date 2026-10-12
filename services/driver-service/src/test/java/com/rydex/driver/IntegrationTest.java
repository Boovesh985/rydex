package com.rydex.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.exception.NotFoundException;
import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.driver.matching.MatchingService;
import com.rydex.driver.matching.OsrmTableClient;
import com.rydex.events.RidePayload;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/**
 * Boots driver-service against real PostGIS and Redis containers, with tokens signed by
 * {@link TestTokens} and the OSRM table service mocked (default: 8 m/s straight-line).
 *
 * <p>Timings are shortened so whole matching sequences run in seconds: offers expire after
 * 2 s, three offers per ride, and searches give up after 7 s.
 *
 * <p>Containers are JVM-wide singletons wired in via {@link DynamicPropertySource}, not
 * {@code @ServiceConnection} beans (see ride-service's IntegrationTest for why).
 */
@SpringBootTest(classes = {DriverServiceApplication.class, TestTokens.Config.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "rydex.matching.offer-timeout=2s",
                "rydex.matching.driver-lock-ttl=3s",
                "rydex.matching.max-attempts=3",
                "rydex.matching.search-timeout=7s",
                "rydex.matching.retry-interval=300ms",
                "rydex.matching.poll-interval=100ms",
                "rydex.matching.lease=10s",
                // tests call PresenceReaper.reap() themselves
                "rydex.presence.reap-interval=1h"
        })
public abstract class IntegrationTest {

    /** Chennai Central, the default map centre. */
    protected static final GeoPoint CENTER = new GeoPoint(13.0827, 80.2707, "Chennai Central");
    protected static final GeoPoint AIRPORT = new GeoPoint(12.9941, 80.1709, "Chennai Airport");
    protected static final Duration WAIT = Duration.ofSeconds(15);

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(postgisImage()).asCompatibleSubstituteFor("postgres"));

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /** The production PostGIS image, reused when present locally, otherwise built from infra/postgres. */
    private static String postgisImage() {
        String image = System.getenv().getOrDefault("RYDEX_TEST_POSTGIS_IMAGE", "ghcr.io/boovesh985/rydex-postgres:16-3");
        try {
            DockerClientFactory.instance().client().inspectImageCmd(image).exec();
            return image;
        } catch (NotFoundException e) {
            return new ImageFromDockerfile(image, false)
                    .withFileFromPath(".", Path.of("../../infra/postgres"))
                    .get();
        }
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @MockitoBean
    protected OsrmTableClient osrm;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MatchingService matching;

    @LocalServerPort
    protected int port;

    /** Every test starts with no active matching, every driver offline and an empty Redis. */
    @BeforeEach
    void resetState() {
        jdbc.sql("UPDATE match_offers SET outcome = 'CANCELLED' WHERE outcome = 'PENDING'").update();
        jdbc.sql("UPDATE match_jobs SET status = 'CANCELLED', due_at = NULL WHERE status IN ('SEARCHING', 'OFFERED')").update();
        jdbc.sql("UPDATE drivers SET status = 'OFFLINE', current_ride_id = NULL WHERE status <> 'OFFLINE'").update();
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
        when(osrm.durationsTo(any(), any())).thenAnswer(inv -> {
            List<GeoPoint> sources = inv.getArgument(0);
            GeoPoint to = inv.getArgument(1);
            return sources.stream().map(s -> s.distanceMetersTo(to) / 8.0).toArray(Double[]::new);
        });
    }

    protected TestApi api() {
        return new TestApi(port);
    }

    /** A signed-in driver with a profile. */
    protected record TestDriver(UUID id, String token) {
    }

    protected TestDriver newDriver(RideType type) {
        UUID id = UUID.randomUUID();
        String token = TestTokens.driver(id);
        var res = api().put("/api/drivers/me", token, Map.of(
                "vehicleType", type.name(),
                "vehicleNumber", "TST-" + id.toString().substring(0, 8),
                "vehicleModel", "Test Car"));
        assertThat(res.getStatusCode().value()).as("create driver: %s", res.getBody()).isEqualTo(200);
        return new TestDriver(id, token);
    }

    protected TestDriver onlineDriver(RideType type, GeoPoint at) {
        TestDriver d = newDriver(type);
        goOnline(d, at);
        return d;
    }

    protected void goOnline(TestDriver d, GeoPoint at) {
        var res = api().patch("/api/drivers/me/status", d.token(),
                Map.of("status", "AVAILABLE", "lat", at.lat(), "lng", at.lng()));
        assertThat(res.getStatusCode().value()).as("go online: %s", res.getBody()).isEqualTo(200);
    }

    /** Simulates the RIDE_REQUESTED event (Phase 3 delivers it via Kafka). */
    protected UUID requestRide(RideType type, GeoPoint pickup) {
        UUID rideId = UUID.randomUUID();
        matching.onRideRequested(rideId, new RidePayload(UUID.randomUUID(), null, type, pickup, AIRPORT,
                new BigDecimal("339.00"), 22111, 1359, null));
        return rideId;
    }

    protected ResponseEntity<JsonNode> currentOffer(TestDriver d) {
        return api().get("/api/drivers/me/offer", d.token());
    }

    /** A point {@code meters} north of {@code p} (1 degree of latitude is about 111.2 km). */
    protected static GeoPoint north(GeoPoint p, double meters) {
        return GeoPoint.of(p.lat() + meters / 111_195.0, p.lng());
    }

    /** Event types written to the outbox for a ride, oldest first. */
    protected List<String> outboxEvents(UUID rideId) {
        return jdbc.sql("SELECT event_type FROM outbox WHERE aggregate_id = :id ORDER BY created_at")
                .param("id", rideId.toString()).query(String.class).list();
    }

    protected String outboxField(UUID rideId, String eventType, String field) {
        return jdbc.sql("SELECT payload -> 'payload' ->> :field FROM outbox WHERE aggregate_id = :id AND event_type = :type")
                .param("field", field).param("id", rideId.toString()).param("type", eventType)
                .query(String.class).single();
    }
}
