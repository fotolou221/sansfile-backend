package com.sansfile.app;

import com.sansfile.app.config.AsyncSyncConfiguration;
import com.sansfile.app.config.DatabaseTestcontainer;
import com.sansfile.app.config.JacksonConfiguration;
import com.sansfile.app.config.RedisTestContainer;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;

/**
 * Base composite annotation for integration tests.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
    classes = {
        SansFileBackendApp.class,
        JacksonConfiguration.class,
        AsyncSyncConfiguration.class,
        com.sansfile.app.config.JacksonHibernateConfiguration.class,
    }
)
@ImportTestcontainers({ DatabaseTestcontainer.class, RedisTestContainer.class })
public @interface IntegrationTest {}
