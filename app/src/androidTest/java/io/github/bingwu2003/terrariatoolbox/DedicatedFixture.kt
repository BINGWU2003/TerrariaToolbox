package io.github.bingwu2003.terrariatoolbox

/** Excluded from ordinary connected tests; use the explicit fixture argument and class selection. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class DedicatedFixture
