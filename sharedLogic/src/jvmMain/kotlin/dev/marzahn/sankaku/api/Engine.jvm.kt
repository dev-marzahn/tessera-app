package dev.marzahn.sankaku.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

actual fun createEngine(): HttpClientEngine = OkHttp.create()
