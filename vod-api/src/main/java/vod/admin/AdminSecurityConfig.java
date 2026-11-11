package vod.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import vod.security.SecretGuard;

/**
 * Phase 5 管理端鉴权。Spring Security 只接管 /api/v1/admin/**（securityMatcher），其余路径不进任何安全过滤链：
 * 租户接口仍由 {@link vod.auth.ApiKeyFilter} 校验 API Key，/api/v1/healthz、/api/v1/qoe、/keys/v1/** 维持原来的放行与鉴权方式，
 * 响应头也不变。登录接口放行（按 IP 限流在控制器里做），其余管理接口要求 ROLE_ADMIN（由 {@link AdminJwtFilter} 放入）。
 * 无状态：不建 session、不用 CSRF token（不用 cookie 鉴权）、不开 basic / 表单登录。
 */
@Configuration
@EnableWebSecurity
public class AdminSecurityConfig {
    @Bean
    SecurityFilterChain adminChain(HttpSecurity http, SecretGuard secrets, JdbcTemplate jdbc,
                                   @Value("${vod.admin.jwt-leeway-seconds:30}") long leewaySeconds) throws Exception {
        http.securityMatcher("/api/v1/admin/**", "/api/v1/admin")
                .csrf(c -> c.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> json(res, 401, "unauthorized"))
                        .accessDeniedHandler((req, res, ex) -> json(res, 403, "forbidden")))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/login").permitAll()
                        .anyRequest().hasRole("ADMIN"))
                .addFilterBefore(new AdminJwtFilter(secrets, jdbc, leewaySeconds), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder adminPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static void json(jakarta.servlet.http.HttpServletResponse res, int status, String detail) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"detail\":\"" + detail + "\"}");
    }
}
