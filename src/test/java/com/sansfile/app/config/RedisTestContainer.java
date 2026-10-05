package com.sansfile.app.config;

import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Container;

public interface RedisTestContainer {
    /** Démarré et arrêté par Testcontainers (@Container), réutilisé d'un test à l'autre : pas de fermeture ici. */
    @Container
    @SuppressWarnings("resource")
    GenericContainer<?> redisContainer = new GenericContainer<>("redis:8.8.0")
        .withExposedPorts(6379)
        .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger(RedisTestContainer.class)))
        .withReuse(true);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("jhipster.cache.redis.server", () -> "redis://" + redisContainer.getHost() + ":" + redisContainer.getMappedPort(6379));
    }
}
