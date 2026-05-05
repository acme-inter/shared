package com.acme.shared.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

@Configuration
public class SharedLocaleConfig {

  @Bean
  public MessageSource messageSource() {
    ResourceBundleMessageSource src = new ResourceBundleMessageSource();
    src.setBasenames("messages");
    src.setDefaultEncoding("UTF-8");
    src.setFallbackToSystemLocale(false);
    src.setCacheSeconds(3600);
    src.setUseCodeAsDefaultMessage(true);
    return src;
  }
}