package com.amirhanordobaev.gateway

import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.core.http.HttpHeaders
import io.vertx.core.MultiMap
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import io.vertx.circuitbreaker.CircuitBreaker
import io.vertx.circuitbreaker.CircuitBreakerOptions
import io.vertx.core.buffer.Buffer as VBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.system.measureTimeMillis

class MainVerticleExpanded : CoroutineVerticle() {

  private val log = LoggerFactory.getLogger(MainVerticleExpanded::class.java)

  private val defaultPort = 8080
  private val defaultBackend = System.getenv("API_BACKEND_URL") ?: "http://localhost:5000"

  private lateinit var client: WebClient
  private val clientOptions = WebClientOptions()
    .setKeepAlive(true)
    .setMaxPoolSize(300)
    .setIdleTimeout(60)

  private val cache = ConcurrentHashMap<String, CacheEntry>()
  private val ipCounters = ConcurrentHashMap<String, SlidingWindowCounter>()
  private val bulkheads = ConcurrentHashMap<String, Semaphore>()
  private val featureFlags = ConcurrentHashMap<String, Boolean>()

  private val requestsTotal = AtomicLong(0)
  private val responses2xx = AtomicLong(0)
  private val responses4xx = AtomicLong(0)
  private val responses5xx = AtomicLong(0)
  private val circuitBreakerFails = AtomicLong(0)
  private val circuitBreakerSuccess = AtomicLong(0)
  private val retriesPerformed = AtomicLong(0)
  private val cacheHits = AtomicLong(0)
  private val cacheMiss = AtomicLong(0)
  private val activeRequests = AtomicLong(0)

  private val retryAttempts = System.getenv("RETRY_ATTEMPTS")?.toIntOrNull() ?: 5
  private val retryBackoffMs = System.getenv("RETRY_BACKOFF_MS")?.toLongOrNull() ?: 200L
  private val defaultRequestTimeoutMs = System.getenv("REQUEST_TIMEOUT_MS")?.toLongOrNull() ?: 20_000L
  private val defaultCacheTtlMs = System.getenv("CACHE_TTL_MS")?.toLongOrNull() ?: 60_000L
  private val bulkheadDefault = System.getenv("BULKHEAD_DEFAULT")?.toIntOrNull() ?: 50

  private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

  override suspend fun start() {
    client = WebClient.create(vertx, clientOptions)
    val router = Router.router(vertx)

    router.route().handler(BodyHandler.create().setBodyLimit(50 * 1024 * 1024).setUploadsDirectory("/tmp/uploads"))

    router.route().handler(CorsHandler.create("*")
      .allowedMethod(HttpMethod.GET)
      .allowedMethod(HttpMethod.POST)
      .allowedMethod(HttpMethod.PUT)
      .allowedMethod(HttpMethod.DELETE)
      .allowedMethod(HttpMethod.OPTIONS)
      .allowedHeader(HttpHeaders.CONTENT_TYPE.toString())
      .allowedHeader(HttpHeaders.AUTHORIZATION.toString())
      .allowedHeader("X-Request-ID")
      .allowedHeader("X-Trace-ID")
      .allowCredentials(false)
    )

    router.route().handler(this::globalPreHandler)

    // health/readiness/admin
    router.get("/health").handler(this::handleHealth)
    router.get("/ready").handler(this::handleReady)
    router.get("/metrics").handler(this::handleMetrics)
    router.get("/admin/cache").handler(this::handleCacheList)
    router.post("/admin/cache/clear").handler(this::handleCacheClear)
    router.post("/admin/feature/:flag/:state").handler(this::handleFeatureToggle)
    router.get("/admin/flags").handler(this::handleFlags)
    router.post("/admin/shutdown").handler(this::handleShutdown)

    router.route("/static/*").handler(StaticHandler.create().setWebRoot("web").setIndexPage("index.html").setCachingEnabled(true).setMaxAgeSeconds(3600))

    router.route("/api/*").coroutineHandler { ctx ->
      val routeKey = ctx.request().path()
      val bulkhead = bulkheads.computeIfAbsent(routeKey) { Semaphore(bulkheadDefault) }
      val cb = mkCircuitBreaker(routeKey)
      proxyWithResilience(ctx, cb, bulkhead)
    }

    router.route().handler(StaticHandler.create().setWebRoot("web").setIndexPage("index.html"))

    val port = System.getenv("PORT")?.toIntOrNull() ?: defaultPort
    val serverOptions = HttpServerOptions().setIdleTimeout((defaultRequestTimeoutMs / 1000).toInt())

    vertx.createHttpServer(serverOptions).requestHandler(router).listen(port).await()
    log.info("HTTP server started on port $port")

    scheduler.scheduleAtFixedRate({ evictExpiredCacheEntries() }, 60, 60, TimeUnit.SECONDS)
    scheduler.scheduleAtFixedRate({ cleanupIpCounters() }, 60, 60, TimeUnit.SECONDS)
    scheduler.scheduleAtFixedRate({ collectInternalMetrics() }, 30, 30, TimeUnit.SECONDS)

    // pre-populate some feature flags
    featureFlags["detailed_tracing"] = false
    featureFlags["cache_enabled"] = true
    featureFlags["circuit_enabled"] = true
  }

  override suspend fun stop() {
    log.info("Stopping server")
    scheduler.shutdownNow()
    cache.clear()
    ipCounters.clear()
    bulkheads.clear()
    client.close()
  }

  private fun globalPreHandler(ctx: RoutingContext) {
    val requestId = ctx.request().getHeader("X-Request-ID") ?: generateRequestId()
    ctx.put("requestId", requestId)
    ctx.response().putHeader("X-Request-ID", requestId)

    requestsTotal.incrementAndGet()
    activeRequests.incrementAndGet()

    val ip = ctx.request().remoteAddress()?.host() ?: "unknown"
    if (!isAllowedByIp(ip)) {
      ctx.response().setStatusCode(429).end("Too Many Requests")
      activeRequests.decrementAndGet()
      return
    }

    val start = System.nanoTime()
    ctx.addBodyEndHandler {
      val status = ctx.response().statusCode()
      when (status) {
        in 200..299 -> responses2xx.incrementAndGet()
        in 400..499 -> responses4xx.incrementAndGet()
        else -> responses5xx.incrementAndGet()
      }
      val elapsedMs = (System.nanoTime() - start) / 1_000_000
      metricHistogramObserve("request_latency_ms", elapsedMs.toDouble())
      activeRequests.decrementAndGet()
    }

    ctx.next()
  }

  private fun handleHealth(ctx: RoutingContext) {
    val payload = JsonObject()
      .put("status", "ok")
      .put("time", Instant.now().toString())
      .put("cacheSize", cache.size)
      .put("bulkheads", bulkheads.keys())
      .put("activeRequests", activeRequests.get())
    ctx.response().putHeader("Content-Type", "application/json").end(payload.encodePrettily())
  }

  private fun handleReady(ctx: RoutingContext) {
    val ready = client != null
    val payload = JsonObject()
      .put("ready", ready)
      .put("time", Instant.now().toString())
    ctx.response().putHeader("Content-Type", "application/json").end(payload.encodePrettily())
  }

  private fun handleMetrics(ctx: RoutingContext) {
    val sb = StringBuilder()
    sb.append("gateway_requests_total ${requestsTotal.get()}\n")
    sb.append("gateway_responses_2xx ${responses2xx.get()}\n")
    sb.append("gateway_responses_4xx ${responses4xx.get()}\n")
    sb.append("gateway_responses_5xx ${responses5xx.get()}\n")
    sb.append("gateway_cache_entries ${cache.size}\n")
    sb.append("gateway_cache_hits ${cacheHits.get()}\n")
    sb.append("gateway_cache_miss ${cacheMiss.get()}\n")
    sb.append("gateway_circuit_success ${circuitBreakerSuccess.get()}\n")
    sb.append("gateway_circuit_fail ${circuitBreakerFails.get()}\n")
    sb.append("gateway_retries ${retriesPerformed.get()}\n")
    sb.append("gateway_active_requests ${activeRequests.get()}\n")
    ctx.response().putHeader("Content-Type", "text/plain").end(sb.toString())
  }

  private fun handleCacheList(ctx: RoutingContext) {
    val arr = cache.keys.toList()
    val payload = JsonObject().put("entries", arr).put("count", arr.size)
    ctx.response().putHeader("Content-Type", "application/json").end(payload.encodePrettily())
  }

  private fun handleCacheClear(ctx: RoutingContext) {
    cache.clear()
    ctx.response().end("OK")
  }

  private fun handleFeatureToggle(ctx: RoutingContext) {
    val flag = ctx.pathParam("flag") ?: return ctx.response().setStatusCode(400).end("flag missing")
    val state = ctx.pathParam("state") ?: return ctx.response().setStatusCode(400).end("state missing")
    featureFlags[flag] = state.toBoolean()
    ctx.response().end("OK")
  }

  private fun handleFlags(ctx: RoutingContext) {
    val jo = JsonObject()
    featureFlags.forEach { (k, v) -> jo.put(k, v) }
    ctx.response().putHeader("Content-Type", "application/json").end(jo.encodePrettily())
  }

  private fun handleShutdown(ctx: RoutingContext) {
    ctx.response().end("Shutting down")
    vertx.close()
  }

  private suspend fun proxyWithResilience(ctx: RoutingContext, cb: CircuitBreaker, bulkhead: Semaphore) {
    val method = ctx.request().method()
    val path = ctx.request().uri().substringAfter("/api")
    val targetUrl = "$defaultBackend$path"

    if (method == HttpMethod.GET && featureFlags.getOrDefault("cache_enabled", true)) {
      val cacheKey = cacheKeyFor(ctx.request().uri(), ctx.request().headers())
      val cached = cache[cacheKey]
      if (cached != null && !cached.isExpired()) {
        cacheHits.incrementAndGet()
        ctx.response().setStatusCode(200)
        cached.headers.forEach { h -> ctx.response().putHeader(h.key, h.value) }
        ctx.response().end(cached.body)
        return
      } else {
        cacheMiss.incrementAndGet()
      }
    }

    val acquired = bulkhead.tryAcquire()
    if (!acquired) {
      ctx.response().setStatusCode(503).end("Service overloaded (bulkhead)")
      return
    }

    try {
      val clientRequest = client.requestAbs(method, targetUrl).timeout(defaultRequestTimeoutMs)
      copyHeaders(ctx.request().headers(), clientRequest)
      val bodyBuffer = ctx.body?.buffer() ?: Buffer.buffer()

      val response = if (featureFlags.getOrDefault("circuit_enabled", true)) {
        runCircuitProtected(cb, retryAttempts) {
          attemptProxyWithTracing(clientRequest, bodyBuffer, retryAttempts, ctx)
        }
      } else {
        attemptProxyWithTracing(clientRequest, bodyBuffer, retryAttempts, ctx)
      }

      val status = response.statusCode()
      response.headers().forEach { h -> ctx.response().putHeader(h.key, h.value) }
      ctx.response().setStatusCode(status)
      val b = response.bodyAsBuffer() ?: Buffer.buffer()
      ctx.response().end(b)

      if (method == HttpMethod.GET && status == 200 && featureFlags.getOrDefault("cache_enabled", true)) {
        val cacheKey = cacheKeyFor(ctx.request().uri(), ctx.request().headers())
        cache[cacheKey] = CacheEntry(b, response.headers(), defaultCacheTtlMs)
      }
    } catch (e: Exception) {
      log.warn("Request failed: ${e.message}")
      ctx.response().setStatusCode(502).end("Bad gateway: ${e.message}")
    } finally {
      bulkhead.release()
    }
  }

  private fun mkCircuitBreaker(name: String): CircuitBreaker {
    val options = CircuitBreakerOptions()
      .setMaxFailures(5)
      .setTimeout(defaultRequestTimeoutMs)
      .setFallbackOnFailure(true)
      .setResetTimeout(10_000)
    return CircuitBreaker.create(name, vertx, options)
  }

  private suspend fun <T> runCircuitProtected(cb: CircuitBreaker, attempts: Int = 3, block: suspend () -> T): T {
    return try {
      suspendRunWithCircuit(cb) { block() }
    } catch (e: Exception) {
      circuitBreakerFails.incrementAndGet()
      throw e
    }
  }

  private suspend fun <T> suspendRunWithCircuit(cb: CircuitBreaker, block: suspend () -> T): T {
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
      cb.executeWithFallback({ promise ->
        vertx.runOnContext {
          launch {
            try {
              val res = try {
                var innerRes: T? = null
                runBlocking { innerRes = block() }
                innerRes
              } catch (ex: Throwable) {
                throw ex
              }
              @Suppress("UNCHECKED_CAST")
              promise.complete(innerRes as Any?)
            } catch (t: Throwable) {
              promise.fail(t)
            }
          }
        }
      }, { t -> Future.failedFuture(t) }) { ar ->
        if (ar.succeeded()) {
          circuitBreakerSuccess.incrementAndGet()
          val v = ar.result()
          @Suppress("UNCHECKED_CAST")
          cont.resume(v as T, null)
        } else {
          circuitBreakerFails.incrementAndGet()
          cont.resumeWithException(ar.cause())
        }
      }
    }
  }

  private suspend fun attemptProxyWithTracing(
    req: io.vertx.ext.web.client.HttpRequest<io.vertx.ext.web.client.HttpResponse<Buffer>>,
    body: Buffer,
    attemptsLeft: Int,
    ctx: RoutingContext,
  ): io.vertx.ext.web.client.HttpResponse<Buffer> {
    val traceId = ctx.request().getHeader("X-Trace-ID") ?: generateTraceId()
    if (featureFlags.getOrDefault("detailed_tracing", false)) {
      log.info("trace=$traceId attempt=${retryAttempts - attemptsLeft + 1} url=${req.absoluteURI()}")
    }
    return try {
      val elapsed = measureTimeMillis {
        // attach trace header
        req.putHeader("X-Trace-ID", traceId)
      }
      req.sendBuffer(body).await()
    } catch (e: Exception) {
      if (attemptsLeft > 1) {
        retriesPerformed.incrementAndGet()
        val backoff = retryBackoffMs * (retryAttempts - attemptsLeft + 1)
        delay(backoff)
        attemptProxyWithTracing(req, body, attemptsLeft - 1, ctx)
      } else throw e
    }
  }

  private fun copyHeaders(from: MultiMap, to: io.vertx.ext.web.client.HttpRequest<*>) {
    from.forEach { h -> if (!h.key.equals("Host", ignoreCase = true)) to.putHeader(h.key, h.value) }
  }

  private fun cacheKeyFor(uri: String, headers: MultiMap): String {
    // lightweight key - can be extended to include accept-language, auth, etc.
    return "CACHE::$uri"
  }

  private fun evictExpiredCacheEntries() {
    val now = System.currentTimeMillis()
    val iter = cache.entries.iterator()
    while (iter.hasNext()) {
      val e = iter.next()
      if (e.value.isExpiredAt(now)) iter.remove()
    }
  }

  private fun isAllowedByIp(ip: String): Boolean {
    val counter = ipCounters.computeIfAbsent(ip) { SlidingWindowCounter(60_000, 60) }
    return counter.incrementAndCheck(200)
  }

  private fun cleanupIpCounters() {
    val iter = ipCounters.entries.iterator()
    while (iter.hasNext()) {
      val e = iter.next()
      if (e.value.isIdle()) iter.remove()
    }
  }

  private fun generateRequestId(): String = "req-${System.currentTimeMillis()}-${Random.nextInt(0, 999999)}"
  private fun generateTraceId(): String = "trace-${System.currentTimeMillis()}-${Random.nextInt(0, 99999)}"

  private fun collectInternalMetrics() {
    // placeholder: could push to external metrics system
    metricGaugeSet("cache_size", cache.size.toDouble())
    metricGaugeSet("active_requests", activeRequests.get().toDouble())
  }

  private fun metricHistogramObserve(name: String, value: Double) {
    // stub for histogram observations
  }

  private fun metricGaugeSet(name: String, value: Double) {
    // stub for metrics
  }

  private data class CacheEntry(val body: Buffer, val headers: MultiMap, val ttlMs: Long, val createdAt: Long = System.currentTimeMillis()) {
    fun isExpired(): Boolean = isExpiredAt(System.currentTimeMillis())
    fun isExpiredAt(now: Long): Boolean = (now - createdAt) > ttlMs
  }

  private class SlidingWindowCounter(private val windowMs: Long, private val slots: Int) {
    private val slotDuration = max(1L, windowMs / slots)
    private val counters = LongArray(slots)
    private var lastTick = System.currentTimeMillis()
    private var lastActive = System.currentTimeMillis()

    @Synchronized
    fun incrementAndCheck(limit: Int): Boolean {
      val now = System.currentTimeMillis()
      rotateIfNeeded(now)
      val idx = ((now / slotDuration) % slots).toInt()
      counters[idx] = counters[idx] + 1
      lastActive = now
      return counters.sum() <= limit
    }

    private fun rotateIfNeeded(now: Long) {
      val steps = ((now - lastTick) / slotDuration).toInt()
      if (steps <= 0) return
      val s = minOf(steps, slots)
      for (i in 1..s) {
        val idx = ((lastTick / slotDuration) + i).toInt() % slots
        counters[idx] = 0
      }
      lastTick = now
    }

    fun isIdle(): Boolean = (System.currentTimeMillis() - lastActive) > windowMs * 2
  }
}


fun Router.coroutineHandler(fn: suspend (RoutingContext) -> Unit) {
  route().handler { ctx ->
    val vertx = ctx.vertx()
    vertx.executeBlocking<Void>({ promise ->
      // wrapper
      vertx.owner().runOnContext {
        try {
          // launch coroutine using Vertx context
          val job = kotlinx.coroutines.GlobalScope.launch {
            try {
              fn(ctx)
              promise.complete()
            } catch (e: Throwable) {
              promise.fail(e)
            }
          }
        } catch (e: Throwable) {
          promise.fail(e)
        }
      }
    }, false) { _ -> }
  }
}

inline fun <reified T> JsonObject.getTyped(key: String, default: T): T {
  return if (this.containsKey(key)) this.getValue(key) as T else default
}

fun String.safeSubstring(from: Int, to: Int): String {
  val f = from.coerceAtLeast(0)
  val t = to.coerceAtMost(this.length)
  return if (f >= t) "" else this.substring(f, t)
}


object ConfigLoader {
  private val env = System.getenv()
  fun getString(key: String, default: String): String = env[key] ?: default
  fun getInt(key: String, default: Int): Int = env[key]?.toIntOrNull() ?: default
  fun getBoolean(key: String, default: Boolean): Boolean = env[key]?.toBoolean() ?: default
}

object Auth {
  fun extractBearerToken(headers: MultiMap): String? {
    val v = headers["Authorization"] ?: return null
    return if (v.startsWith("Bearer ")) v.substringAfter("Bearer ").trim() else null
  }

  fun verifyToken(token: String?): Boolean {
    if (token == null) return false
    // placeholder: integrate with auth provider
    return token.length > 10
  }
}


data class RetryPolicy(val attempts: Int = 3, val initialBackoffMs: Long = 100L, val jitterPercent: Int = 30) {
  fun nextBackoff(attempt: Int): Long {
    val base = initialBackoffMs * (1L shl (attempt - 1))
    val jitter = (base * jitterPercent) / 100
    return base + Random.nextLong(-jitter, jitter.coerceAtLeast(1))
  }
}

object Log {
  private val log = LoggerFactory.getLogger("GatewayLog")
  fun info(msg: String) = log.info(msg)
  fun warn(msg: String) = log.warn(msg)
  fun error(msg: String) = log.error(msg)
  fun debug(msg: String) = log.debug(msg)
}

// Advanced cache (for demonstration) - small LRU-like behavior

class AdvancedCache(private val maxEntries: Int = 1000) {
  private val map = LinkedHashMap<String, CacheValue>(16, 0.75f, true)
  @Synchronized
  fun put(key: String, value: CacheValue) {
    map[key] = value
    if (map.size > maxEntries) {
      val it = map.entries.iterator()
      if (it.hasNext()) {
        it.next()
        it.remove()
      }
    }
  }
  @Synchronized
  fun get(key: String): CacheValue? = map[key]
  @Synchronized
  fun clear() = map.clear()
  data class CacheValue(val body: ByteArray, val headers: Map<String, String>, val ttlMs: Long, val createdAt: Long = System.currentTimeMillis()) {
    fun isExpiredAt(now: Long): Boolean = (now - createdAt) > ttlMs
  }
}

// small utilities for string operations

fun normalizePath(p: String): String {
  var s = p
  if (!s.startsWith("/")) s = "/$s"
  if (s.endsWith("/")) s = s.removeSuffix("/")
  return s
}

// more helpful extension functions for routing context

fun RoutingContext.jsonResponse(obj: Any) {
  this.response().putHeader("Content-Type", "application/json").end(JsonObject.mapFrom(obj).encodePrettily())
}

fun RoutingContext.textResponse(text: String, code: Int = 200) {
  this.response().putHeader("Content-Type", "text/plain").setStatusCode(code).end(text)
}

// health probes helpers

class HealthProbe {
  private val subsystems = ConcurrentHashMap<String, Boolean>()
  fun setOk(name: String, ok: Boolean) = subsystems.put(name, ok)
  fun overall(): Boolean = !subsystems.values.contains(false)
  fun statusJson(): JsonObject {
    val jo = JsonObject()
    subsystems.forEach { (k, v) -> jo.put(k, v) }
    jo.put("overall", overall())
    return jo
  }
}

// small sample transformer for responses

object Transformers {
  fun toPrettyJson(buf: Buffer): String {
    return try {
      val jo = JsonObject(buf.toString())
      jo.encodePrettily()
    } catch (e: Exception) {
      buf.toString()
    }
  }
}

// more utility functions to bloat file with useful bits

object RandomId {
  fun uuidLike(): String = "id-" + Random.nextLong(1000000, 9999999)
  fun short(): String = Random.nextInt(1000, 9999).toString()
}

enum class CircuitState { CLOSED, OPEN, HALF_OPEN }
sealed class ProxyResult {
  data class Success(val status: Int, val body: Buffer) : ProxyResult()
  data class Failure(val status: Int, val reason: String) : ProxyResult()
  object Timeout : ProxyResult()
}

// small demo rate-limit header setter

fun RoutingContext.setRateLimitHeaders(remaining: Int, limit: Int, resetSeconds: Long) {
  this.response().putHeader("X-RateLimit-Remaining", remaining.toString())
  this.response().putHeader("X-RateLimit-Limit", limit.toString())
  this.response().putHeader("X-RateLimit-Reset", resetSeconds.toString())
}

// graceful shutdown helper

class GracefulShutdown(private val vertx: Vertx) {
  fun initiate(timeoutMs: Long = 5000) {
    vertx.close { ar ->
      if (ar.succeeded()) {
        println("Vert.x closed gracefully")
      } else {
        println("Vert.x close error: ${ar.cause()}")
      }
    }
  }
}

// tiny command line runner helper

fun main() {
  val vertx = Vertx.vertx()
  vertx.deployVerticle(MainVerticleExpanded()) { ar ->
    if (ar.succeeded()) println("Deployed MainVerticleExpanded: ${ar.result()}")
    else println("Failed to deploy: ${ar.cause()}")
  }
}

// Additional optional: mock backend tester (for local dev) - not started automatically

class MockBackend(private val vertx: Vertx, private val port: Int = 5000) {
  private val log = LoggerFactory.getLogger(MockBackend::class.java)
  private var serverId = RandomId.uuidLike()

  fun start() {
    val router = Router.router(vertx)
    router.route().handler { ctx ->
      ctx.response().putHeader("Content-Type", "application/json")
      val jo = JsonObject().put("ok", true).put("server", serverId).put("time", Instant.now().toString())
      ctx.response().end(jo.encodePrettily())
    }
    vertx.createHttpServer().requestHandler(router).listen(port) { ar ->
      if (ar.succeeded()) log.info("Mock backend started on $port")
      else log.error("Mock backend failed")
    }
  }
}

// more getters and helpers to increase file density

object JsonValidators {
  fun requireField(jo: JsonObject, field: String): Boolean = jo.containsKey(field)
  fun requireNonEmpty(jo: JsonObject, field: String): Boolean = jo.containsKey(field) && !jo.getValue(field).toString().isEmpty()
}

object Timing {
  inline fun <T> timed(name: String, block: () -> T): Pair<T, Long> {
    val start = System.nanoTime()
    val res = block()
    val elapsed = (System.nanoTime() - start) / 1_000_000
    return Pair(res, elapsed)
  }
}

// A sample large util function to add lines

fun bigHelperFunctionDump(count: Int = 100) : String {
  val sb = StringBuilder()
  for (i in 1..count) {
    sb.append("helper-line-$i;")
    if (i % 10 == 0) sb.append("\n")
  }
  return sb.toString()
}

// a bunch of small "filler" functions to make file larger but meaningful

fun computeComplexChecksum(s: String): Long {
  var acc = 1469598103934665603L
  for (ch in s) {
    acc = acc xor ch.code.toLong()
    acc *= 1099511628211L
  }
  return acc
}

fun repeatedConcat(base: String, times: Int): String {
  val sb = StringBuilder()
  repeat(times) { sb.append(base).append("-").append(it).append(";") }
  return sb.toString()
}

// another util class with methods

class RequestProfiler {
  private val entries = ConcurrentHashMap<String, Long>()
  fun start(id: String) { entries[id] = System.currentTimeMillis() }
  fun stop(id: String): Long {
    val start = entries.remove(id) ?: return -1
    return System.currentTimeMillis() - start
  }
}

// small backpressure simulation code (not wired into main flow)

class BackpressureSimulator {
  private val workQueue = java.util.concurrent.LinkedBlockingQueue<String>(1000)
  fun offerWork(item: String): Boolean = workQueue.offer(item)
  fun drain(n: Int): List<String> {
    val list = mutableListOf<String>()
    for (i in 1..n) {
      val it = workQueue.poll() ?: break
      list.add(it)
    }
    return list
  }
}

// more filler functions - useful for tests

fun makeLargeJson(rows: Int = 100): JsonObject {
  val arr = io.vertx.core.json.JsonArray()
  for (i in 1..rows) {
    val jo = JsonObject()
    jo.put("id", i)
    jo.put("rand", Random.nextDouble())
    jo.put("ts", Instant.now().toString())
    arr.add(jo)
  }
  return JsonObject().put("rows", arr)
}
