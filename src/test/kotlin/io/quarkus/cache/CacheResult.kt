package io.quarkus.cache

/**
 * Stand-in with the real annotation's name. [nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekInterceptor]
 * matches `@CacheResult` by name, so quarkus-cache stays off the test classpath.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class CacheResult(val cacheName: String)
