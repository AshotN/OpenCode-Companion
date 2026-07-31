package com.ashotn.opencode.relay.api.transport

import com.ashotn.opencode.relay.api.withTestServer
import org.junit.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenCodeHttpTransportTest {

    @Test
    fun `get returns success for 2xx response`() {
        withTestServer { server, port ->
            server.createContext("/ok") { exchange ->
                val body = "{\"status\":\"ok\"}"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val transport = OpenCodeHttpTransport()
            val result = transport.get(port, "/ok")

            val success = assertIs<ApiResult.Success<String?>>(result)
            assertEquals("{\"status\":\"ok\"}", success.value)
        }
    }

    @Test
    fun `maps non-2xx to http error with body`() {
        withTestServer { server, port ->
            server.createContext("/boom") { exchange ->
                val body = "{\"error\":\"bad request\"}"
                exchange.sendResponseHeaders(400, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val transport = OpenCodeHttpTransport()
            val result = transport.get(port, "/boom")

            val failure = assertIs<ApiResult.Failure>(result)
            val error = assertIs<ApiError.HttpError>(failure.error)
            assertEquals(400, error.statusCode)
            assertEquals("{\"error\":\"bad request\"}", error.body)
        }
    }

    @Test
    fun `postJson sends json body and headers`() {
        withTestServer { server, port ->
            var capturedMethod = ""
            var capturedAccept = ""
            var capturedContentType = ""
            var capturedBody = ""
            server.createContext("/submit") { exchange ->
                capturedMethod = exchange.requestMethod
                capturedAccept = exchange.requestHeaders.getFirst("Accept") ?: ""
                capturedContentType = exchange.requestHeaders.getFirst("Content-Type") ?: ""
                capturedBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }

                val response = "true"
                exchange.sendResponseHeaders(200, response.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray(Charsets.UTF_8)) }
            }

            val transport = OpenCodeHttpTransport()
            val result = transport.post(port, "/submit", "{\"text\":\"hello\"}")

            assertIs<ApiResult.Success<String?>>(result)
            assertEquals("POST", capturedMethod)
            assertEquals("application/json", capturedAccept)
            assertEquals("application/json", capturedContentType)
            assertEquals("{\"text\":\"hello\"}", capturedBody)
        }
    }

    @Test
    fun `adds authorization header when configured`() {
        withTestServer { server, port ->
            var capturedAuthorization = ""
            server.createContext("/auth") { exchange ->
                capturedAuthorization = exchange.requestHeaders.getFirst("Authorization") ?: ""

                val response = "{}"
                exchange.sendResponseHeaders(200, response.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray(Charsets.UTF_8)) }
            }

            val transport = OpenCodeHttpTransport(authorizationHeaderProvider = { "Basic abc123" })
            val result = transport.get(port, "/auth")

            assertIs<ApiResult.Success<String?>>(result)
            assertEquals("Basic abc123", capturedAuthorization)
        }
    }

    @Test
    fun `maps malformed json parsing to parse error`() {
        val transport = OpenCodeHttpTransport()

        val result = transport.parseJsonObject("this is not json")

        val failure = assertIs<ApiResult.Failure>(result)
        assertIs<ApiError.ParseError>(failure.error)
    }

    @Test
    fun `parseJsonElement parses valid json array`() {
        val transport = OpenCodeHttpTransport()

        val result = transport.parseJsonElement("[1,2,3]")

        val success = assertIs<ApiResult.Success<com.google.gson.JsonElement>>(result)
        assertTrue(success.value.isJsonArray)
    }

    @Test
    fun `maps timeout override to network error`() {
        withTestServer { server, port ->
            server.createContext("/slow") { exchange ->
                Thread.sleep(300)
                val body = "{}"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val transport = OpenCodeHttpTransport(defaultConnectTimeoutMs = 3_000, defaultReadTimeoutMs = 3_000)
            val result = transport.get(
                port = port,
                path = "/slow",
                timeouts = OpenCodeHttpTransport.Timeouts(readTimeoutMs = 75),
            )

            val failure = assertIs<ApiResult.Failure>(result)
            assertIs<ApiError.NetworkError>(failure.error)
        }
    }

    @Test
    fun `maps connection refused to network error`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val transport = OpenCodeHttpTransport(defaultConnectTimeoutMs = 50, defaultReadTimeoutMs = 50)

        val result = transport.get(closedPort, "/health")

        val failure = assertIs<ApiResult.Failure>(result)
        val error = assertIs<ApiError.NetworkError>(failure.error)
        assertTrue(error.message.isNotBlank())
    }

    @Test
    fun `mapJsonObjectResponse maps object payload`() {
        val transport = OpenCodeHttpTransport()

        val result = transport.mapJsonObjectResponse(ApiResult.Success("{\"id\":\"ses_1\"}")) { obj ->
            ApiResult.Success(obj.get("id").asString)
        }

        val success = assertIs<ApiResult.Success<String>>(result)
        assertEquals("ses_1", success.value)
    }

    @Test
    fun `mapJsonArrayResponse propagates parse failure without invoking transform`() {
        val transport = OpenCodeHttpTransport()
        var transformInvoked = false

        val result = transport.mapJsonArrayResponse(ApiResult.Success("{\"id\":\"ses_1\"}")) {
            transformInvoked = true
            ApiResult.Success(it.size())
        }

        assertIs<ApiResult.Failure>(result)
        assertEquals(false, transformInvoked)
    }

    @Test
    fun `withParseContext prefixes parse error message`() {
        val endpoint = ApiEndpoint(HttpMethod.GET, "/session")
        val result = ApiResult.Failure(ApiError.ParseError("Expected JSON array"))
            .withParseContext(endpoint)

        val failure = assertIs<ApiResult.Failure>(result)
        val error = assertIs<ApiError.ParseError>(failure.error)
        assertEquals("GET /session: Expected JSON array", error.message)
    }

    @Test
    fun `withParseContext leaves non-parse failures unchanged`() {
        val original = ApiResult.Failure(ApiError.HttpError(500, "boom"))

        val result = original.withParseContext(ApiEndpoint(HttpMethod.GET, "/session"))

        assertEquals(original, result)
    }
}
