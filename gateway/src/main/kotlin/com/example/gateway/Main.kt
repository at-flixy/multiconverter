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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import io.vertx.circuitbreaker.CircuitBreaker
import io.vertx.circuitbreaker.CircuitBreakerOptions

class MainVerticleExpanded : CoroutineVerticle() {

  private val log = LoggerFactory.getLogger(MainVerticleExpanded::class.java)

  private val defaultPort = 8080
  private val defaultBackend = System.getenv("API_BACKEND_URL") ?: "http://localhost:5000"

  private lateinit var client: WebClient
  private val clientOptions = WebClientOptions().setKeepAlive(true).setMaxPoolSize(200).setIdleTimeout(60)

  private val cache = ConcurrentHashMap<String, CacheEntry>()
  private val ipCounters = ConcurrentHashMap<String, SlidingWindowCounter>()
  private val bulkheads = ConcurrentHashMap<String, Semaphore>()

  private val requestsTotal = AtomicLong(0)
  private val responses2xx = AtomicLong(0)
  private val responses4xx = AtomicLong(0)
  private val responses5xx = AtomicLong(0)
  private val circuitBreakerFails = AtomicLong(0)
  private val circuitBreakerSuccess = AtomicLong(0)

  private val retryAttempts = 5
  private val retryBackoffMs = 200L
  private val defaultRequestTimeoutMs = 20_000L

  override suspend fun start() {
    client = WebClient.create(vertx, clientOptions)
    val router = Router.router(vertx)

    router.route().handler(BodyHandler.create().setBodyLimit(30 * 1024 * 1024).setUploadsDirectory("/tmp/uploads"))

    router.route().handler(CorsHandler.create("*")
      .allowedMethod(HttpMethod.GET)
      .allowedMethod(HttpMethod.POST)
      .allowedMethod(HttpMethod.PUT)
      .allowedMethod(HttpMethod.DELETE)
      .allowedHeader(HttpHeaders.CONTENT_TYPE.toString())
      .allowedHeader(HttpHeaders.AUTHORIZATION.toString())
      .allowedHeader("X-Request-ID")
    )

    router.route().handler(this::globalPreHandler)

    router.get("/health").handler(this::handleHealth)
    router.get("/metrics").handler(this::handleMetrics)

    router.route("/static/*").handler(StaticHandler.create().setWebRoot("web").setIndexPage("index.html").setCachingEnabled(true).setMaxAgeSeconds(3600))

    router.route("/api/*").coroutineHandler { ctx ->
      val routeKey = ctx.request().path()
      val bulkhead = bulkheads.computeIfAbsent(routeKey) { Semaphore(50) } // default 50 concurrent
      val cb = mkCircuitBreaker(routeKey)
      proxyWithResilience(ctx, cb, bulkhead)
    }

    router.route().handler(StaticHandler.create().setWebRoot("web").setIndexPage("index.html"))

    val port = System.getenv("PORT")?.toIntOrNull() ?: defaultPort
    val serverOptions = HttpServerOptions().setIdleTimeout(defaultRequestTimeoutMs.toInt() / 1000)

    vertx.createHttpServer(serverOptions).requestHandler(router).listen(port).await()
    log.info("HTTP server started on port $port")

    vertx.setPeriodic(60_000) {
      evictExpiredCacheEntries()
      cleanupIpCounters()
    }
  }

  override suspend fun stop() {
    log.info("Stopping server")
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

    val ip = ctx.request().remoteAddress()?.host() ?: "unknown"
    if (!isAllowedByIp(ip)) {
      ctx.response().setStatusCode(429).end("Too Many Requests")
      return
    }

    ctx.addBodyEndHandler {
      val status = ctx.response().statusCode()
      when (status) {
        in 200..299 -> responses2xx.incrementAndGet()
        in 400..499 -> responses4xx.incrementAndGet()
        else -> responses5xx.incrementAndGet()
      }
    }

    ctx.next()
  }

  private fun handleHealth(ctx: RoutingContext) {
    val payload = JsonObject()
      .put("status", "ok")
      .put("time", Instant.now().toString())
      .put("cacheSize", cache.size)
      .put("bulkheads", bulkheads.keys())
    ctx.response().putHeader("Content-Type", "application/json").end(payload.encodePrettily())
  }

  private fun handleMetrics(ctx: RoutingContext) {
    val sb = StringBuilder()
    sb.append("gateway_requests_total ${requestsTotal.get()}
")
    sb.append("gateway_responses_2xx ${responses2xx.get()}
")
    sb.append("gateway_responses_4xx ${responses4xx.get()}
")
    sb.append("gateway_responses_5xx ${responses5xx.get()}
")
    sb.append("gateway_cache_entries ${cache.size}
")
    sb.append("gateway_circuit_success ${circuitBreakerSuccess.get()}
")
    sb.append("gateway_circuit_fail ${circuitBreakerFails.get()}
")
    ctx.response().putHeader("Content-Type", "text/plain").end(sb.toString())
  }

  private suspend fun proxyWithResilience(ctx: RoutingContext, cb: CircuitBreaker, bulkhead: Semaphore) {
    val method = ctx.request().method()
    val path = ctx.request().uri().substringAfter("/api")
    val targetUrl = "$defaultBackend$path"

    if (method == HttpMethod.GET) {
      val cacheKey = cacheKeyFor(ctx.request().uri(), ctx.request().headers())
      val cached = cache[cacheKey]
      if (cached != null && !cached.isExpired()) {
        ctx.response().setStatusCode(200)
        cached.headers.forEach { h -> ctx.response().putHeader(h.key, h.value) }
        ctx.response().end(cached.body)
        return
      }
    }

    // Bulkhead tryAcquire
    val acquired = bulkhead.tryAcquire()
    if (!acquired) {
      ctx.response().setStatusCode(503).end("Service overloaded (bulkhead)")
      return
    }

    try {
      val clientRequest = client.requestAbs(method, targetUrl).timeout(defaultRequestTimeoutMs)
      copyHeaders(ctx.request().headers(), clientRequest)
      val bodyBuffer = ctx.body?.buffer() ?: Buffer.buffer()

      // Use circuit breaker around the remote call
      val response = runCircuitProtected(cb, retryAttempts) {
        // This block returns HttpResponse<Buffer> via suspend
        attemptProxy(clientRequest, bodyBuffer, retryAttempts)
      }

      val status = response.statusCode()
      response.headers().forEach { h -> ctx.response().putHeader(h.key, h.value) }
      ctx.response().setStatusCode(status)
      val b = response.bodyAsBuffer() ?: Buffer.buffer()
      ctx.response().end(b)

      if (method == HttpMethod.GET && status == 200) {
        val cacheKey = cacheKeyFor(ctx.request().uri(), ctx.request().headers())
        cache[cacheKey] = CacheEntry(b, response.headers(), 60_000)
      }
    } catch (e: Exception) {
      log.warn("Request failed with resilience mechanisms: ${e.message}")
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
        // launch coroutine to run block and complete promise
        vertx.runOnContext {
          launch {
            try {
              val res = try {
                // run blocking wrapper for suspending block
                var innerRes: T? = null
                kotlinx.coroutines.runBlocking { innerRes = block() }
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

  private suspend fun attemptProxy(
    req: io.vertx.ext.web.client.HttpRequest<io.vertx.ext.web.client.HttpResponse<Buffer>>,
    body: Buffer,
    attemptsLeft: Int
  ): io.vertx.ext.web.client.HttpResponse<Buffer> {
    return try {
      req.sendBuffer(body).await()
    } catch (e: Exception) {
      if (attemptsLeft > 1) {
        val backoff = retryBackoffMs * (retryAttempts - attemptsLeft + 1)
        delay(backoff)
        attemptProxy(req, body, attemptsLeft - 1)
      } else throw e
    }
  }

  private fun copyHeaders(from: MultiMap, to: io.vertx.ext.web.client.HttpRequest<*>) {
    from.forEach { h -> if (!h.key.equals("Host", ignoreCase = true)) to.putHeader(h.key, h.value) }
  }

  private fun cacheKeyFor(uri: String, headers: MultiMap): String = "CACHE::$uri"

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

  private data class CacheEntry(val body: Buffer, val headers: MultiMap, val ttlMs: Long, val createdAt: Long = System.currentTimeMillis()) {
    fun isExpired(): Boolean = isExpiredAt(System.currentTimeMillis())
    fun isExpiredAt(now: Long): Boolean = (now - createdAt) > ttlMs
  }

  private class SlidingWindowCounter(private val windowMs: Long, private val slots: Int) {
    private val slotDuration = windowMs / slots
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
      // simple wrapper: launch coroutine and complete when done
      launch {
        try {
          fn(ctx)
          promise.complete()
        } catch (e: Throwable) {
          promise.fail(e)
        }
      }
    }, false) { _ -> }
  }
}

fun main() {
  val vertx = Vertx.vertx()
  vertx.deployVerticle(MainVerticleExpanded()) { ar ->
    if (ar.succeeded()) println("Deployed MainVerticleExpanded: ${ar.result()}")
    else println("Failed to deploy: ${ar.cause()}")
  }
}
