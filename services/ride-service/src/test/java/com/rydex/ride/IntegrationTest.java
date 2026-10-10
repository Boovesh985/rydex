package com.rydex.ride;

import com.rydex.ride.geo.NominatimClient;
import com.rydex.ride.geo.OsrmClient;
import com.github.dockerjava.api.exception.NotFoundException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the whole service against real PostGIS and Redis containers. External HTTP
 * dependencies (OSRM, Nominatim) are mocked.
 *
 * <p>Containers are plain JVM-wide singletons wired in via {@link DynamicPropertySource}
 * rather than {@code @ServiceConnection} beans: Spring 7 pauses cached contexts between
 * test classes, and container beans would be stopped and restarted on new ports.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rydex.rate-limits.per-minute.auth=10000",
        "rydex.rate-limits.per-minute.book=10000",
        "rydex.rate-limits.per-minute.estimate=10000",
        "rydex.rate-limits.per-minute.places=10000",
        "rydex.rate-limits.per-minute.default=10000",
        "rydex.jwt.private-key-location=",
        "rydex.jwt.public-key-location=",
        "rydex.geo.nominatim-interval=10ms"
})
public abstract class IntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(postgisImage()).asCompatibleSubstituteFor("postgres"));

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /**
     * The production PostGIS image (infra/postgres). Reuses the local image that
     * {@code docker compose build} produced; builds it from the Dockerfile only when missing (e.g. CI).
     * Override with RYDEX_TEST_POSTGIS_IMAGE.
     */
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
    protected OsrmClient osrm;

    @MockitoBean
    protected NominatimClient nominatim;

    @Autowired
    protected StringRedisTemplate redis;

    @LocalServerPort
    protected int port;

    protected TestApi api() {
        return new TestApi(port);
    }
}
