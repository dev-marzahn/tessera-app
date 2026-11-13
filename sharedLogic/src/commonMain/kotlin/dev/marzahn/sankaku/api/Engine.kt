package dev.marzahn.sankaku.api

import io.ktor.client.engine.HttpClientEngine

/** OkHttp on every platform, shared by both clients. */
expect fun createEngine(): HttpClientEngine
