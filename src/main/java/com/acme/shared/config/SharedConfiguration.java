package com.acme.shared.config;

import com.acme.shared.constant.Properties;
import com.acme.shared.helper.ApiResponseUtil;
import com.acme.shared.query.PagedQueryFactory;
import com.acme.shared.service.*;
import com.acme.shared.util.*;
import com.lark.oapi.Client;
import io.r2dbc.spi.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@AutoConfiguration
@Import({
    SharedLocaleConfig.class,
    SharedRedisConfig.class,
    SharedStorageConfig.class
})
@EnableConfigurationProperties(Properties.class)
public class SharedConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public Encryption encryption() {
    return new Encryption();
  }

  @Bean
  @ConditionalOnMissingBean
  public JwtUtil jwtUtil(Encryption encryption, Properties properties) {
    return new JwtUtil(properties, encryption);
  }

  @Bean
  @ConditionalOnMissingBean
  public MsgUtil msgUtil(MessageSource messageSource) {
    return new MsgUtil(messageSource);
  }

  @Bean
  @ConditionalOnMissingBean
  public CommonUtil commonUtil(MsgUtil msgUtil) {
    return new CommonUtil(msgUtil);
  }

  @Bean
  @ConditionalOnMissingBean
  public GuardUtil guardUtil(DbUtil dbUtil) {
    return new GuardUtil(dbUtil);
  }

  @Bean
  @ConditionalOnMissingBean
  public CookieUtil cookieUtil(Encryption encryption, Properties properties) {
    return new CookieUtil(encryption, properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public ConvertUtil convertUtil() {
    return new ConvertUtil();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(R2dbcEntityTemplate.class)
  public DbUtil dbUtil(ObjectMapper objectMapper, R2dbcEntityTemplate template, EventService eventService, MsgUtil msgUtil) {
    return new DbUtil(template, eventService, msgUtil,objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public EventService eventService(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
    return new EventService(rabbitTemplate, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public PagedQueryFactory pagedQueryFactory(DatabaseClient databaseClient, MsgUtil msgUtil) {
    return new PagedQueryFactory(databaseClient, msgUtil);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(ConnectionFactory.class)
  public DatabaseClient databaseClient(ConnectionFactory connectionFactory) {
    return DatabaseClient.create(connectionFactory);
  }

  @Bean
  @ConditionalOnMissingBean
  public ApiResponseUtil apiResponseUtil(MsgUtil msgUtil) {
    return new ApiResponseUtil(msgUtil);
  }

  @Bean
  @ConditionalOnMissingBean
  public RedisService redisService(
      @Qualifier("reactiveStringRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate,
      ObjectMapper objectMapper) {
    return new RedisService(redisTemplate, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(S3AsyncClient.class)
  public SharedFileService sharedFileService(S3AsyncClient s3AsyncClient) {
    return new SharedFileService(s3AsyncClient);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(Client.class)
  public SharedLarkService sharedLarkService(Client client) {
    return new SharedLarkService(client);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(WebClient.class)
  public SharedGmailService sharedGmailService(WebClient webClient) {
    return new SharedGmailService(webClient, id -> {
      throw new IllegalStateException(
          "GmailService tokenProvider not set. Override the gmailService @Bean " +
              "and pass oauthService::getValidAccessToken as the second argument.");
    });
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(WebClient.class)
  public SharedCalendarService sharedCalendarService(WebClient webClient) {
    return new SharedCalendarService(webClient, id -> {
      throw new IllegalStateException(
          "CalendarService tokenProvider not set. Override the calendarService @Bean " +
              "and pass oauthService::getValidAccessToken as the second argument.");
    });
  }
}