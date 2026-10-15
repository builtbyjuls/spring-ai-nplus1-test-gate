package com.builtbyjuls.gate.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.builtbyjuls.gate.ai.model.NPlusOneDiagnosis;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-tier cache for N+1 AI diagnoses:
 * 1. Checks Redis cache (if StringRedisTemplate is configured).
 * 2. Checks and persists to local JSON file (.nplus1-ai-cache.json).
 */
public class FingerprintCacheService {

    public static final String DEFAULT_CACHE_FILENAME = ".nplus1-ai-cache.json";
    private static final String REDIS_KEY_PREFIX = "nplus1:cache:";

    private final Path cacheFilePath;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final Map<String, NPlusOneDiagnosis> inMemoryCache = new ConcurrentHashMap<>();

    public FingerprintCacheService() {
        this(Path.of(DEFAULT_CACHE_FILENAME), new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT), null);
    }

    public FingerprintCacheService(Path cacheFilePath, ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        this.cacheFilePath = cacheFilePath;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.redisTemplate = redisTemplate;
        loadLocalCache();
    }

    /**
     * Retrieves a cached diagnosis by its SHA-256 fingerprint key.
     *
     * @param cacheKey SHA-256 fingerprint key
     * @return optional containing the diagnosis if cached
     */
    public Optional<NPlusOneDiagnosis> get(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return Optional.empty();
        }

        // 1. Check in-memory / local cache
        NPlusOneDiagnosis local = inMemoryCache.get(cacheKey);
        if (local != null) {
            return Optional.of(local);
        }

        // 2. Check Redis (if available)
        if (redisTemplate != null) {
            try {
                String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + cacheKey);
                if (json != null && !json.isBlank()) {
                    NPlusOneDiagnosis redisDiagnosis = objectMapper.readValue(json, NPlusOneDiagnosis.class);
                    inMemoryCache.put(cacheKey, redisDiagnosis);
                    return Optional.of(redisDiagnosis);
                }
            } catch (Exception ignored) {
            }
        }

        return Optional.empty();
    }

    /**
     * Stores a diagnosis into cache, updating in-memory cache, local JSON file, and Redis.
     *
     * @param cacheKey SHA-256 fingerprint key
     * @param diagnosis diagnosis to cache
     */
    public synchronized void put(String cacheKey, NPlusOneDiagnosis diagnosis) {
        if (cacheKey == null || diagnosis == null) {
            return;
        }

        inMemoryCache.put(cacheKey, diagnosis);
        saveLocalCache();

        if (redisTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(diagnosis);
                redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + cacheKey, json);
            } catch (Exception ignored) {
            }
        }
    }

    public int size() {
        return inMemoryCache.size();
    }

    private void loadLocalCache() {
        if (cacheFilePath != null && Files.isRegularFile(cacheFilePath)) {
            try {
                byte[] bytes = Files.readAllBytes(cacheFilePath);
                if (bytes.length > 0) {
                    Map<String, NPlusOneDiagnosis> loaded = objectMapper.readValue(
                            bytes, new TypeReference<Map<String, NPlusOneDiagnosis>>() {}
                    );
                    if (loaded != null) {
                        inMemoryCache.putAll(loaded);
                    }
                }
            } catch (IOException ignored) {
            }
        }
    }

    private synchronized void saveLocalCache() {
        if (cacheFilePath != null) {
            try {
                Path parent = cacheFilePath.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                objectMapper.writeValue(cacheFilePath.toFile(), inMemoryCache);
            } catch (IOException ignored) {
            }
        }
    }
}
