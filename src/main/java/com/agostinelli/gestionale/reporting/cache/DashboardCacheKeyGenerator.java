package com.agostinelli.gestionale.reporting.cache;

import io.quarkus.cache.CacheKeyGenerator;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

@ApplicationScoped
public class DashboardCacheKeyGenerator implements CacheKeyGenerator {

    @Override
    public Object generate(Method method, Object... methodParams) {
        return method.getName() + ":" + Arrays.stream(methodParams)
                .map(p -> p != null ? p.toString() : "null")
                .collect(Collectors.joining("_"));
    }
}
