/*
 * Copyright 2020 Anton Shuvaev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.elasticsearch4idea.rest

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.apache.http.Header
import org.apache.http.HttpHeaders
import org.apache.http.HttpHost
import org.apache.http.HttpResponse
import org.apache.http.client.ResponseHandler
import org.apache.http.client.methods.*
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.elasticsearch4idea.model.Method
import org.elasticsearch4idea.model.Request
import org.elasticsearch4idea.model.Response
import org.elasticsearch4idea.rest.model.*
import java.io.BufferedReader
import java.io.InputStream

@Service
class ElasticsearchClient(project: Project) : Disposable {

    private val httpClient: CloseableHttpClient by lazy {
        HttpClients.createDefault()
    }

    fun getIndexInfo(httpHost: HttpHost, headers: List<Header>, index: String): IndexInfo {
        val request = HttpGet("$httpHost/$index")
        headers.forEach { request.addHeader(it) }
        return execute(request, ConvertingResponseHandler {
            val jsonNode = objectMapper.readTree(it)
            objectMapper.treeToValue(jsonNode.get(index), IndexInfo::class.java)
        })
    }

    fun getIndex(httpHost: HttpHost, headers: List<Header>, index: String): Index {
        return getIndices(httpHost, headers, index).first()
    }

    fun getIndices(httpHost: HttpHost, headers: List<Header>, index: String? = null): List<Index> {
        val url = if (index == null) "$httpHost/_cat/indices?v" else "$httpHost/_cat/indices/$index?v"
        val request = HttpGet(url)
        headers.forEach { request.addHeader(it) }
        val table = execute(request, TableDataResponseHandler())

        val header = table[0].asSequence()
            .mapIndexed { i, value -> Pair(value, i) }
            .associate { it }
        return table.asSequence()
            .drop(1)
            .map {
                Index(
                    name = it[header.getValue("index")],
                    health = it[header.getValue("health")].toUpperCase()
                        .let { health ->
                            HealthStatus.values().firstOrNull { it.name == health }
                        },
                    status = IndexStatus.valueOf(it[header.getValue("status")].toUpperCase()),
                    primaries = it[header.getValue("pri")],
                    replicas = it[header.getValue("rep")],
                    documents = it[header.getValue("docs.count")],
                    deletedDocuments = it[header.getValue("docs.deleted")],
                    size = it[header.getValue("store.size")]
                )
            }.toList()
    }

    fun getClusterInfo(httpHost: HttpHost, headers: List<Header>) {
        val request = HttpGet("$httpHost")
        headers.forEach { request.addHeader(it) }
        execute(request, JsonResponseHandler(Map::class.java))
    }

    fun getClusterStats(httpHost: HttpHost, headers: List<Header>): ClusterStats {
        val request = HttpGet("$httpHost/_cluster/stats")
        headers.forEach { request.addHeader(it) }
        return execute(request, JsonResponseHandler(ClusterStats::class.java))
    }

    fun execute(request: Request, headers: List<Header>, checkResponseSuccess: Boolean = true): Response {
        val httpRequest = when (request.method) {
            Method.GET -> HttpGet(request.urlPath)
            Method.POST -> {
                val post = HttpPost(request.urlPath)
                post.setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                post.entity = StringEntity(request.body)
                post
            }
            Method.PUT -> {
                val put = HttpPut(request.urlPath)
                put.setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                put.entity = StringEntity(request.body)
                put
            }
            Method.HEAD -> HttpHead(request.urlPath)
            Method.DELETE -> HttpDelete(request.urlPath)
        }
        headers.forEach { httpRequest.addHeader(it) }
        return execute(
            httpRequest,
            ResponseHandler { response ->
                if (checkResponseSuccess) {
                    checkResponse(response)
                }
                val content = response.entity.content.use {
                    it.bufferedReader().use(BufferedReader::readText)
                }
                Response(content, response.statusLine)
            }
        )
    }

    private fun <T> execute(request: HttpUriRequest, responseHandler: ResponseHandler<T>): T {
        return httpClient.execute(request, responseHandler)
    }

    private class JsonResponseHandler<T>(private val clazz: Class<T>) : ConvertingResponseHandler<T>({
        objectMapper.readValue(it, clazz)
    })

    private class TableDataResponseHandler : ConvertingResponseHandler<List<List<String>>>({
        val lines = it.bufferedReader().useLines { lineSequence ->
            lineSequence.toList()
        }
        val header = lines[0]
        val columnStarts = ArrayList<Int>()
        for (i in header.indices) {
            if (i == 0 || (header[i - 1] == ' ' && header[i] != ' ')) {
                columnStarts.add(i)
            }
        }
        columnStarts.add(header.length)
        val columnRanges = columnStarts.zipWithNext()
        lines.asSequence()
            .map { line ->
                columnRanges.asSequence()
                    .map { line.substring(it.first, it.second).trim() }
                    .toList()
            }.toList()
    })

    private open class ConvertingResponseHandler<T>(private val converter: (InputStream) -> T) :
        ResponseHandler<T> {

        override fun handleResponse(response: HttpResponse): T {
            checkResponse(response)

            return response.entity.content.use {
                converter.invoke(it)
            }
        }
    }

    override fun dispose() {
        httpClient.close()
    }

    companion object {

        private fun checkResponse(response: HttpResponse) {
            if (response.statusLine.statusCode !in 200..299) {
                val content = response.entity.content.use {
                    it.bufferedReader().use(BufferedReader::readText)
                }
                throw ElasticsearchException(response.statusLine, content)
            }
        }

        private val objectMapper = jacksonObjectMapper()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

}