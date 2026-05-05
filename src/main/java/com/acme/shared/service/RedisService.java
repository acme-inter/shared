package com.acme.shared.service;

import com.acme.shared.exception.ThrowException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

@Slf4j
@Service
public class RedisService {

  private final ReactiveRedisTemplate<String, String> redisTemplate;
  private final ObjectMapper objectMapper;

  public RedisService(@Qualifier("reactiveStringRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate,
      ObjectMapper objectMapper
  ) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  public <T> Mono<T> getValidSession(Long sessionId, Class<T> clazz) {
    String sid = "SID:"+sessionId ;
    return redisTemplate.opsForValue()
        .get(sid)
        .flatMap(json -> {
          try {
            return Mono.just(objectMapper.readValue(json, clazz));
          } catch (Exception e) {
            log.error("Failed to deserialize key {}: {}", sid, e.getMessage());
            return Mono.error(new ThrowException("redis.deserialize.failed"));
          }
        });
  }

  public <T> Mono<Void> store(Long sessionId,Integer durationMin, T data) {
    String sid = "SID:"+sessionId ;
    return Mono.fromCallable(() -> {
          try {
            return objectMapper.writeValueAsString(data);
          } catch (Exception e) {
            log.error("Failed to serialize key {}: {}", sid, e.getMessage());
            throw new ThrowException("redis.serialize.failed");
          }
        })
        .flatMap(json ->
            redisTemplate.opsForValue()
                .set(sid, json, Duration.ofMinutes(durationMin))
        )
        .then()
        .subscribeOn(Schedulers.boundedElastic());
  }

  public <T> Mono<Void> update(Long sessionId, Integer durationMin, T data) {
    String sid = "SID:" + sessionId;
    return Mono.fromCallable(() -> {
          try {
            return objectMapper.writeValueAsString(data);
          } catch (Exception e) {
            throw new ThrowException("redis.serialize.failed");
          }
        })
        .flatMap(json ->
            redisTemplate.delete(sid)
                .then(redisTemplate.opsForValue().set(sid, json, Duration.ofMinutes(durationMin)))
        )
        .then()
        .subscribeOn(Schedulers.boundedElastic());
  }

  public Mono<Void> delete(Long sessionId) {
    String sid = "SID:"+sessionId;
    return redisTemplate.opsForValue().delete(sid).then();
  }
}