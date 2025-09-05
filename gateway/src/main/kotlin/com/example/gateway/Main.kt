package com.example.gateway

import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.ext.web.client.WebClient
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.core.http.HttpServerOptions

class MainVerticle: AbstractVerticle() {

  override fun start(startPromise: Promise<Void>) {
    val router = Router.router(vertx)
    val backend = System.getenv("API_BACKEND_URL") ?: "http://localhost:5000"
    val client = WebClient.create(vertx)

    router.route().handler(CorsHandler.create("*")
      .allowedMethod(HttpMethod.GET)
      .allowedMethod(HttpMethod.POST)
      .allowedMethod(HttpMethod.DELETE)
      .allowedMethod(HttpMethod.PUT)
      .allowedHeader("Authorization")
      .allowedHeader("Content-Type")
    )

    router.route("/*").handler(StaticHandler.create().setWebRoot("web").setIndexPage("index.html"))

    router.route("/api/*").handler { ctx ->
      val path = ctx.request().uri()
      val method = ctx.request().method()
      val headers = ctx.request().headers()
      val req = client.requestAbs(method, "$backend$path")
      headers.forEach { h -> req.putHeader(h.key, h.value) }
      if (method == HttpMethod.GET || method == HttpMethod.DELETE) {
        req.send { ar ->
          if (ar.succeeded()) {
            val resp = ar.result()
            ctx.response().setStatusCode(resp.statusCode())
            resp.headers().forEach { h -> ctx.response().putHeader(h.key, h.value) }
            ctx.response().end(resp.bodyAsBuffer())
          } else ctx.fail(ar.cause())
        }
      } else {
        ctx.request().bodyHandler { body: Buffer ->
          req.sendBuffer(body) { ar ->
            if (ar.succeeded()) {
              val resp = ar.result()
              ctx.response().setStatusCode(resp.statusCode())
              resp.headers().forEach { h -> ctx.response().putHeader(h.key, h.value) }
              ctx.response().end(resp.bodyAsBuffer())
            } else ctx.fail(ar.cause())
          }
        }
      }
    }

    val port = 8080
    vertx.createHttpServer(HttpServerOptions()).requestHandler(router).listen(port) {
      if (it.succeeded()) startPromise.complete() else startPromise.fail(it.cause())
    }
  }
}

fun main() { Vertx.vertx().deployVerticle(MainVerticle()) }
