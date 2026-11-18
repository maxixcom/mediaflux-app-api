package ru.mediaflux.app.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import ru.mediaflux.app.api.security.AppAuthenticationConverter
import ru.mediaflux.app.api.security.AppAuthenticationEntryPoint

@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity, converter: AppAuthenticationConverter): SecurityFilterChain {
        http.oauth2ResourceServer { rs ->
            rs.authenticationEntryPoint(AppAuthenticationEntryPoint())
                .jwt { it.jwtAuthenticationConverter(converter) }
        }
        return http
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/actuator", "/actuator/**", "/error", "/error/**").permitAll()
                    // Единственная анонимная операция (APPS.md §10): по ней приложение узнаёт сервер ДО входа.
                    .requestMatchers(HttpMethod.GET, "/server-info").permitAll()
                    .anyRequest().authenticated()
            }
            .build()
    }
}
