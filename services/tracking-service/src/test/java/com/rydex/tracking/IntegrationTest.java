package com.rydex.tracking;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.exception.NotFoundException;
import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.events.EventEnvelope;
import com.rydex.events.EventType;
import com.rydex.events.RidePayload;
import com.rydex.events.Topics;
import com.rydex.tracking.location.OsrmRouteClient;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boots tracking-service against real PostGIS, Redis and Kafka containers, with tokens signed by
 * {@link TestTokens}, ride-service and driver-service replaced by {@link StubServices}, and OSRM
 * mocked (default: 8 m/s straight-line). Containers are JVM-wide singletons wired in via
 * {@link DynamicPropertySource} (see ride-service's IntegrationTest for why).
 */
@SpringBootTest(classes = {TrackingServiceApplication.class, TestTokens.Config.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "rydex.messaging.create-topics=true",
                "rydex.messaging.retry.initial-interval=100ms"
        })
public abstract class IntegrationTest {

    protected static final GeoPoint CENTER = new GeoPoint(13.0827, 80.2707, "Chennai Central");
    protected static final GeoPoint AIRPORT = new GeoPoint(12.9941, 80.1709, "Chennai Airport");
    protected static final Duration WAIT = Duration.ofSeconds(20);

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(postgisImage()).asCompatibleSubstituteFor("postgres"));

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
    }

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
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("rydex.services.ride-service-url", StubServices::url);
        registry.add("rydex.services.driver-service-url", StubServices::url);
    }

    @MockitoBean
    protected OsrmRouteClient osrm;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected JsonMapper json;

    @LocalServerPort
    protected int port;

    @BeforeEach
    void resetState() {
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
        when(osrm.route(any(), any())).thenAnswer(inv -> {
            GeoPoint from = inv.getArgument(0);
            GeoPoint to = inv.getArgument(1);
            int meters = (int) Math.round(from.distanceMetersTo(to));
            return new OsrmRouteClient.Route(meters / 8, meters);
        });
    }

    protected static KafkaProbe kafka() {
        return new KafkaProbe(KAFKA.getBootstrapServers());
    }

    protected StompTestClient connect(String token) {
        return StompTestClient.connect(port, token);
    }

    protected TestApi api() {
        return new TestApi(port);
    }

    /** A ride of {@code riderId}, as ride-service and driver-service publish it. */
    protected record TestRide(UUID id, UUID riderId, UUID driverId) {

        public RidePayload payload(String reason) {
            return new RidePayload(riderId, driverId, RideType.ECONOMY, CENTER, AIRPORT, new BigDecimal("339.00"),
                    22111, 1359, reason);
        }
    }

    protected static TestRide newRide() {
        return new TestRide(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    /** Publishes a ride event on ride.events the way the services' outbox relays do. */
    protected EventEnvelope<RidePayload> publish(EventType type, TestRide ride) {
        return publish(type, ride, null);
    }

    protected EventEnvelope<RidePayload> publish(EventType type, TestRide ride, String reason) {
        var event = EventEnvelope.of(type, ride.id().toString(), ride.payload(reason));
        publish(event);
        return event;
    }

    protected void publish(EventEnvelope<RidePayload> event) {
        kafka().publish(Topics.RIDE_EVENTS, event.rideId(), json.writeValueAsString(event), event.eventType().name());
    }

    protected String rideStatus(UUID rideId) {
        return jdbc.sql("SELECT status FROM tracked_rides WHERE ride_id = :id").param("id", rideId)
                .query(String.class).optional().orElse(null);
    }
}
