package io.github.lycheeappf.tmm.core.util

import kotlinx.coroutines.CancellationException

/**
 * Coroutine-aware Variante von [kotlin.runCatching]: erkennt [CancellationException]
 * und rethrowed sie, statt sie als generische Failure zu kapseln. Das ist nötig,
 * weil `runCatching` Throwable catched — inkl. der Cancellation, die für die
 * structured-concurrency-Semantik DURCH muss (sonst werden Coroutine-Scopes
 * stumm cancel't statt korrekt zu propagieren).
 *
 * Benutze diesen Helper in jedem `suspend`-Kontext, in dem du best-effort-Code
 * laufen lassen willst (Cleanup, Logging, Defensiv-Operations).
 */
inline fun <R> coRunCatching(block: () -> R): Result<R> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
