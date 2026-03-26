package coeusyk.game.chess;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


@Configuration
public class CorsConfig {
    @Bean
    public WebMvcConfigurer corsConfigurer() {
           return new WebMvcConfigurer() {
               @Override
               public void addCorsMappings(@NonNull CorsRegistry registry) {
                   registry.addMapping("/engine/**")
                           .allowedOrigins("http://localhost:3000")
                       .allowedMethods("GET", "POST", "PUT")
                           .allowCredentials(true);
                   registry.addMapping("/api/game/**")
                           .allowedOrigins("http://localhost:3000")
                           .allowedMethods("GET", "POST", "PUT")
                           .allowCredentials(true);
               }
           };
    }
}
