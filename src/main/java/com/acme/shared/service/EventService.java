package com.acme.shared.service;

import com.acme.shared.constant.RabbitConstants;
import com.acme.shared.payload.event.DepartmentMsgDTO;
import com.acme.shared.payload.event.LogMsgDTO;
import com.acme.shared.payload.event.MemberMsgDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Slf4j
@Service
public class EventService {

  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper objectMapper;

  public EventService(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
    this.rabbitTemplate = rabbitTemplate;
    this.objectMapper = objectMapper;
  }

  public Mono<Void> publishLog(LogMsgDTO event) {
    return publish(RabbitConstants.LOG_EXCHANGE, RabbitConstants.LOG_ROUTING_KEY, event, "log");
  }

  public Mono<Void> publishMembers(List<MemberMsgDTO> events) {
    return publish(RabbitConstants.MEMBER_EXCHANGE, RabbitConstants.MEMBER_ROUTING_KEY, events, "member-sync");
  }

  public Mono<Void> publishDepartments(List<DepartmentMsgDTO> events) {
    return publish(RabbitConstants.DEPARTMENT_EXCHANGE, RabbitConstants.DEPARTMENT_ROUTING_KEY, events, "department-sync");
  }

  private <T> Mono<Void> publish(String exchange, String routingKey, T payload, String type) {
    return Mono.fromCallable(() -> {
          byte[] body = objectMapper.writeValueAsBytes(payload);
          Message message = MessageBuilder
              .withBody(body)
              .setContentType(MessageProperties.CONTENT_TYPE_JSON)
              .setContentEncoding("UTF-8")
              .build();
          rabbitTemplate.send(exchange, routingKey, message);
          return true;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnSuccess(v -> log.debug("Published {} event to exchange={}", type, exchange))
        .doOnError(e -> log.error("Failed to publish {} event to exchange={}: {}", type, exchange, e.getMessage()))
        .onErrorResume(e -> Mono.empty())
        .then();
  }
}