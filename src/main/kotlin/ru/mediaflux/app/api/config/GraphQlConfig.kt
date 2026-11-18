package ru.mediaflux.app.api.config

import graphql.analysis.MaxQueryComplexityInstrumentation
import graphql.analysis.MaxQueryDepthInstrumentation
import graphql.scalars.ExtendedScalars
import graphql.validation.rules.ValidationRules
import graphql.validation.schemawiring.ValidationSchemaWiring
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.client.HttpSyncGraphQlClient
import org.springframework.graphql.execution.RuntimeWiringConfigurer
import org.springframework.web.client.RestClient

@Configuration
class GraphQlConfig {

    @Bean
    fun runtimeWiringConfigurer(): RuntimeWiringConfigurer {
        val validation = ValidationSchemaWiring(ValidationRules.newValidationRules().build())
        return RuntimeWiringConfigurer { builder ->
            builder
                .directiveWiring(validation)
                .scalar(ExtendedScalars.GraphQLLong)
                .scalar(ExtendedScalars.DateTime)
                .scalar(ExtendedScalars.UUID)
        }
    }

    /** Глубина и сложность запроса (APPS.md §10): один тяжёлый запрос не должен уронить копию. */
    @Bean
    fun maxQueryDepthInstrumentation(props: AppApiProperties) = MaxQueryDepthInstrumentation(props.graphql.maxDepth)

    @Bean
    fun maxQueryComplexityInstrumentation(props: AppApiProperties) = MaxQueryComplexityInstrumentation(props.graphql.maxComplexity)

    /**
     * Клиент к data-service. Билдер — из контекста (на нём наблюдаемость, заголовок `traceparent`), `clone()` — чтобы
     * настройки не протекли в остальные клиенты.
     */
    @Bean
    fun dataServiceGraphQlClient(props: DataServiceProperties, restClientBuilder: RestClient.Builder): HttpSyncGraphQlClient =
        HttpSyncGraphQlClient.builder(restClientBuilder.clone().baseUrl(props.url)).build()
}
