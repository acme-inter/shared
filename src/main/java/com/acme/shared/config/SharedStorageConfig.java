package com.acme.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.net.URI;
import java.time.Duration;

@Configuration
public class SharedStorageConfig {

  @Bean
  @ConditionalOnProperty(prefix = "hetzner.s3", name = {"endpoint", "access-key", "secret-key"})
  public S3AsyncClient s3Client(Environment env) {
    String endpoint  = env.getRequiredProperty("hetzner.s3.endpoint");
    String accessKey = env.getRequiredProperty("hetzner.s3.access-key");
    String secretKey = env.getRequiredProperty("hetzner.s3.secret-key");

    AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);

    return S3AsyncClient.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(credentials))
        .httpClient(NettyNioAsyncHttpClient.builder()
            .connectionTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(8))
            .maxConcurrency(50)
            .build())
        .build();
  }
}
