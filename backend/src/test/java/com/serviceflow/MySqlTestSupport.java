package com.serviceflow;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

abstract class MySqlTestSupport {
    private static MySQLContainer<?> container;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("SERVICEFLOW_TEST_DB_URL");
        if (url == null || url.isBlank()) {
            container = new MySQLContainer<>("mysql:8.0.46")
                    .withDatabaseName("serviceflow_s1_test").withUsername("s1test").withPassword("s1test");
            container.start();
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        } else {
            // 测试会清理业务表，只接受明确命名的独立本地测试库。
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/serviceflow_s1_test(?:\\?.*)?")) {
                throw new IllegalArgumentException("外部测试库必须是本地独立的 serviceflow_s1_test");
            }
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("SERVICEFLOW_TEST_DB_USERNAME", "root"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("SERVICEFLOW_TEST_DB_PASSWORD", ""));
        }
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
    }
}
