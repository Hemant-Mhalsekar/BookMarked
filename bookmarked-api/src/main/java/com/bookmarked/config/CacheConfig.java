package com.bookmarked.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/** Caffeine caches, configured under spring.cache in application.yml. */
@Configuration
@EnableCaching
public class CacheConfig {
}
