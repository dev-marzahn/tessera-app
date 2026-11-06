package dev.marzahn.sankaku.images

import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options

/**
 * Images are immutable (keyed by md5), so a cached copy is always used as is: no revalidation, which would send the
 * cached, possibly expired URL. Only 2xx responses are written. Coil's default also caches a 404 or 410 by HTTP
 * heuristics, and a cached refusal would come back on the retry after a refetch instead of the new download.
 */
object ImmutableCacheStrategy : CacheStrategy {
  override suspend fun read(
    cacheResponse: NetworkResponse,
    networkRequest: NetworkRequest,
    options: Options,
  ): CacheStrategy.ReadResult = CacheStrategy.ReadResult(cacheResponse)

  override suspend fun write(
    cacheResponse: NetworkResponse?,
    networkRequest: NetworkRequest,
    networkResponse: NetworkResponse,
    options: Options,
  ): CacheStrategy.WriteResult =
    if (networkResponse.code in 200..299) CacheStrategy.WriteResult(networkResponse) else CacheStrategy.WriteResult.DISABLED
}
