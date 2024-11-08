package be.cytomine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

@Configuration
public class RequestLoggingConfig {

    @Bean
    public CommonsRequestLoggingFilter requestLoggingFilter() {
        CommonsRequestLoggingFilter loggingFilter = new CommonsRequestLoggingFilter();
        loggingFilter.setIncludeClientInfo(true);  // Logs IP and Session ID
        loggingFilter.setIncludeQueryString(true); // Logs query parameters
        loggingFilter.setIncludePayload(true);     // Logs request payload (up to a certain limit)
        loggingFilter.setIncludeHeaders(false);     // Logs headers (may contain sensitive info)
        loggingFilter.setMaxPayloadLength(10000);  // Customize max payload size
        return loggingFilter;
    }
}
