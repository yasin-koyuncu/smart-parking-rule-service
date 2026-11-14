package com.parkview.ruleengine.controller;

import com.parkview.platform.security.servlet.AccessAutoConfiguration;
import com.parkview.platform.security.servlet.JwtSecurityConfig;
import com.parkview.platform.web.ErrorMessagesConfig;
import com.parkview.platform.web.ParkviewWebAutoConfiguration;
import com.parkview.ruleengine.security.RuleAccessPolicy;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a {@code @WebMvcTest} of this service needs besides the controller: the auto-configurations of parkview-platform that a web
 * slice does not load by itself (JWT security chain, {@code CurrentUser}, error handler, error texts, correlation id), the service's
 * {@link RuleAccessPolicy} and the test directories of {@link WebTestSupport} (plates and sites by token subject).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ImportAutoConfiguration({ErrorMessagesConfig.class, ParkviewWebAutoConfiguration.class, JwtSecurityConfig.class, AccessAutoConfiguration.class})
@Import({RuleAccessPolicy.class, WebTestSupport.TestPlates.class, WebTestSupport.TestSites.class})
public @interface PlatformWebSlice {
}
