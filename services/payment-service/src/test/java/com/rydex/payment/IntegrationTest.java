package com.rydex.payment;

import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.exception.NotFoundException;
import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.events.EventEnvelope;
import com.rydex.events.EventType;
import com.rydex.events.PaymentPayload;
import com.rydex.events.RidePayload;
import com.rydex.events.Topics;
import com.rydex.payment.payment.Payment;
import com.rydex.payment.payment.PaymentRepository;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boots payment-service against real Postgres and Kafka containers, with tokens signed by
 * {@link TestTokens} and Razorpay replaced by {@link RazorpayStub}. New payments go to the mock
 * provider; Razorpay is configured too (against the stub), and Razorpay tests create their payments
 * with it directly, so one Spring context (one consumer group) serves every test. Containers are
 * JVM-wide singletons wired in via {@link DynamicPropertySource} (see ride-service's IntegrationTest).
 */
@SpringBootTest(classes = {PaymentServiceApplication.class, TestTokens.Config.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "rydex.messaging.create-topics=true",
                "rydex.messaging.retry.initial-interval=100ms",
                // explicit, so a developer's services/payment-service/.env cannot change the tests
                "rydex.payments.provider=mock",
                "rydex.payments.mock.decline-above=1000",
                "rydex.payments.process-interval=200ms",
                "rydex.payments.razorpay.key-id=" + RazorpayStub.KEY_ID,
                "rydex.payments.razorpay.key-secret=" + RazorpayStub.KEY_SECRET,
                "rydex.payments.razorpay.webhook-secret=" + RazorpayStub.WEBHOOK_SECRET,
                "rydex.payments.razorpay.check-interval=500ms",
                "rydex.payments.razorpay.max-check-interval=1s"
        })
public abstract class IntegrationTest {

    protected static final GeoPoint CENTER = new GeoPoint(13.0827, 80.2707, "Chennai Central");
    protected static final GeoPoint AIRPORT = new GeoPoint(12.9941, 80.1709, "Chennai Airport");
    protected static final Duration WAIT = Duration.ofSeconds(20);

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(postgisImage()).asCompatibleSubstituteFor("postgres"));

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    /** Same Postgres image as production, so the schema runs on what it will run on. */
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
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("rydex.payments.razorpay.base-url", RazorpayStub::baseUrl);
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected PaymentRepository payments;

    @LocalServerPort
    protected int port;

    @BeforeEach
    void resetStub() {
        RazorpayStub.reset();
    }

    protected static KafkaProbe kafka() {
        return new KafkaProbe(KAFKA.getBootstrapServers());
    }

    protected TestApi api() {
        return new TestApi(port);
    }

    /** A ride of a rider and a driver, as ride-service publishes it. */
    public record TestRide(UUID id, UUID riderId, UUID driverId) {

        public RidePayload completed(BigDecimal fare) {
            return new RidePayload(riderId, driverId, RideType.ECONOMY, CENTER, AIRPORT, fare, 22111, 1359, null);
        }

        public RidePayload cancelled(BigDecimal fee) {
            return new RidePayload(riderId, driverId, RideType.ECONOMY, CENTER, AIRPORT, new BigDecimal("339.00"),
                    22111, 1359, "Changed plans", fee);
        }
    }

    protected static TestRide newRide() {
        return new TestRide(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    protected EventEnvelope<RidePayload> publish(EventType type, TestRide ride, RidePayload payload) {
        var event = EventEnvelope.of(type, ride.id().toString(), payload);
        kafka().publish(Topics.RIDE_EVENTS, event.rideId(), json.writeValueAsString(event), type.name());
        return event;
    }

    /** Completes {@code ride} (RIDE_COMPLETED) and waits until its payment exists. */
    protected Payment complete(TestRide ride, String fare) {
        publish(EventType.RIDE_COMPLETED, ride, ride.completed(new BigDecimal(fare)));
        return await().atMost(WAIT).until(() -> payments.findByRideId(ride.id()).orElse(null), p -> p != null);
    }

    protected Payment awaitStatus(UUID rideId, PaymentPayload.Status status) {
        return await().atMost(WAIT).until(() -> payments.findByRideId(rideId).orElseThrow(),
                p -> p.getStatus() == status);
    }

    /** payment.events records of a ride, in order. */
    protected List<ConsumerRecord<String, String>> paymentEvents(UUID rideId, int count) {
        return kafka().await(Topics.PAYMENT_EVENTS, r -> rideId.toString().equals(r.key()), count, WAIT);
    }

    protected EventEnvelope<PaymentPayload> parse(ConsumerRecord<String, String> record) {
        return json.readValue(record.value(), new TypeReference<EventEnvelope<PaymentPayload>>() {
        });
    }

    protected boolean processed(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = :id").param("id", eventId)
                .query(Long.class).single() > 0;
    }
}
