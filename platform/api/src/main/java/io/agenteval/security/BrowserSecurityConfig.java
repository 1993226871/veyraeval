package io.agenteval.security;
import io.agenteval.identity.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class BrowserSecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService userDetailsService(IdentityService identities) {
        return email -> {
            UserAccount user;
            try { user=identities.loadForLogin(email); }
            catch(ScopeViolationException missing) { throw new UsernameNotFoundException("Invalid credentials"); }
            return User.withUsername(user.userId().toString()).password(user.passwordHash()).roles(user.admin()?new String[]{"USER","ADMIN"}:new String[]{"USER"}).build();
        };
    }
    @Bean @Order(0) SecurityFilterChain sdkUploadChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/v1/sdk-runs:upload")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/sdk-runs:upload").permitAll().anyRequest().denyAll())
            .build();
    }
    @Bean @Order(2) SecurityFilterChain browserChain(HttpSecurity http,IdentityService identities) throws Exception {
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/api/v1/auth/csrf","/api/v1/auth/login","/error").permitAll()
            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN").anyRequest().authenticated())
            .formLogin(login -> login.loginProcessingUrl("/api/v1/auth/login")
                .successHandler((req,res,auth) -> { res.setStatus(200); res.setContentType("application/json"); res.getWriter().write("{\"authenticated\":true}"); })
                .failureHandler((req,res,error) -> ApiProblems.write(res,401,"INVALID_CREDENTIALS")))
            .logout(logout -> logout.logoutUrl("/api/v1/auth/logout").logoutSuccessHandler((req,res,auth) -> res.setStatus(204)))
            .exceptionHandling(errors -> errors.authenticationEntryPoint((req,res,error) -> ApiProblems.write(res,401,"UNAUTHENTICATED"))
                .accessDeniedHandler((req,res,error) -> ApiProblems.write(res,403,"FORBIDDEN")))
            .addFilterBefore(new PasswordChangeFilter(identities),AuthorizationFilter.class);
        return http.build();
    }
}
