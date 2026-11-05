package org.teamsparta.inventoryapi.integration;

import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 실제 PostgreSQL과 Flyway 스키마, application.yml의 ddl-auto: validate로 동작하는 통합 테스트 기반.
 *
 * <p>Spring 컨텍스트가 테스트 클래스 간에 캐시되므로 컨테이너도 JVM당 하나만 띄운다. 정리는 Testcontainers가 맡는다.
 * Publisher와 만료 Job은 @Scheduled라 끌 수 있는 속성이 없어, 테스트가 호출 시점을 제어하도록 스케줄링만 비활성화한다.
 */
@Tag("integration")
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Import(InventoryIntegrationTestSupport.DisableScheduling.class)
abstract class InventoryIntegrationTestSupport {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("inventorydb")
            .withUsername("root")
            .withPassword("rootpassword");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class DisableScheduling {
        @Bean
        static BeanFactoryPostProcessor removeScheduledAnnotationProcessor() {
            return beanFactory -> ((BeanDefinitionRegistry) beanFactory)
                    .removeBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
        }
    }
}
