package com.gridsettlement.gate.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridsettlement.gate.ai.NPlusOneAiDiagnosisService;
import com.gridsettlement.gate.cache.FingerprintCacheService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Path;

/**
 * Auto-configuration for Spring AI ChatClient, FingerprintCacheService, and NPlusOneAiDiagnosisService.
 */
@Configuration(proxyBeanMethods = false)
public class NPlusOneAiClientConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FingerprintCacheService fingerprintCacheService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider
    ) {
        return new FingerprintCacheService(
                Path.of(FingerprintCacheService.DEFAULT_CACHE_FILENAME),
                new ObjectMapper(),
                redisTemplateProvider.getIfAvailable()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public NPlusOneAiDiagnosisService nPlusOneAiDiagnosisService(
            ObjectProvider<ChatModel> chatModelProvider,
            FingerprintCacheService cacheService
    ) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        ChatClient chatClient = chatModel != null ? ChatClient.create(chatModel) : null;
        return new NPlusOneAiDiagnosisService(chatClient, cacheService);
    }
}
