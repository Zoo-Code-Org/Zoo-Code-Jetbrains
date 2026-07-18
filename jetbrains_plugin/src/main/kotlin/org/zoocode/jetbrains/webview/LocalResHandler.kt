// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.webview

import com.intellij.openapi.diagnostic.Logger
import io.ktor.http.*
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy


class LocalResHandler(val resourcePath:String , val request: CefRequest?) : CefResourceRequestHandlerAdapter() {

    override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): CefResourceHandler {
        val handler = LocalCefResHandle(resourcePath, request)
        return Proxy.newProxyInstance(
            CefResourceHandler::class.java.classLoader,
            arrayOf(CefResourceHandler::class.java),
            handler
        ) as CefResourceHandler
    }

}

class LocalCefResHandle(val resourceBasePath: String, val request: CefRequest?) : InvocationHandler {
    private val logger = Logger.getInstance(LocalCefResHandle::class.java)

    private var file: File? = null
    private var fileContent: ByteArray? = null
    private var offset = 0

    init {
        logger.info("=== LocalCefResHandle INIT START ===")
        logger.info("Resource base path: $resourceBasePath")
        logger.info("Request URL: ${request?.url}")
        
        val requestPath = request?.url?.decodeURLPart()?.replace("http://localhost:","")?.substringAfter("/")?.substringBefore("?")
        logger.info("Extracted request path: $requestPath")
        
        requestPath?.let {
            val filePath = if (requestPath.isEmpty()) {
                "$resourceBasePath/index.html"
            } else {
                "$resourceBasePath/$requestPath"
            }
            logger.info("Constructed file path: $filePath")
            
            file = File(filePath)
            logger.info("File object created: $file")

            if (file!!.exists() && file!!.isFile) {
                try {
                    fileContent = file!!.readBytes()
                    logger.info("File content loaded successfully, size: ${fileContent?.size} bytes")
                } catch (e: Exception) {
                    logger.warn("Cannot get file content, error: ${e}")
                    file = null
                    fileContent = null
                }
            } else {
                logger.warn("File does not exist or is not a file: exists=${file?.exists()}, isFile=${file?.isFile}")
                file = null
                fileContent = null
            }
            logger.info("Final state: file=$file, exists=${file?.exists()}, content size=${fileContent?.size}")
        }
        logger.info("=== LocalCefResHandle INIT END ===")
    }


    fun processRequest(p0: CefRequest?, callback: CefCallback?): Boolean {
        callback?.Continue()
        return true
    }

    /**
     * Get MIME type according to file path
     */
    fun getMimeTypeForFile(filePath: String): String {
        return when {
            filePath.endsWith(".html", true) -> "text/html"
            filePath.endsWith(".css", true) -> "text/css"
            filePath.endsWith(".js", true) -> "application/javascript"
            filePath.endsWith(".json", true) -> "application/json"
            filePath.endsWith(".png", true) -> "image/png"
            filePath.endsWith(".jpg", true) || filePath.endsWith(".jpeg", true) -> "image/jpeg"
            filePath.endsWith(".gif", true) -> "image/gif"
            filePath.endsWith(".svg", true) -> "image/svg+xml"
            filePath.endsWith(".woff", true) -> "font/woff"
            filePath.endsWith(".woff2", true) -> "font/woff2"
            filePath.endsWith(".ttf", true) -> "font/ttf"
            filePath.endsWith(".eot", true) -> "application/vnd.ms-fontobject"
            filePath.endsWith(".otf", true) -> "font/otf"
            else -> "application/octet-stream"
        }
    }

    fun getResponseHeaders(resp: CefResponse?, p1: IntRef?, p2: StringRef?) {
        if (fileContent == null) {
            resp?.status = 404
            resp?.statusText = "Not Found"
            p1?.set(0)
            return
        }

        resp?.status = 200
        resp?.statusText = "OK"
        resp?.mimeType = getMimeTypeForFile(file?.name ?: "index.html")
        resp?.setHeaderByName("Content-Length", fileContent!!.size.toString(), true)
        p1?.set(fileContent!!.size)
    }

    fun readResponse(dataOut: ByteArray?, bytesToRead: Int, bytesRead: IntRef?, callback: CefCallback?): Boolean {
        return readContent(dataOut, bytesToRead, bytesRead)
    }

    private fun skipContent(bytesToSkip: Long, bytesSkipped: Any?): Boolean {
        val content = fileContent
        if (content == null || bytesSkipped == null || bytesToSkip < 0) {
            setRefValue(bytesSkipped, -2L)
            return false
        }

        val skipped = minOf(bytesToSkip, (content.size - offset).coerceAtLeast(0).toLong())
        offset += skipped.toInt()
        setRefValue(bytesSkipped, skipped)
        return skipped > 0
    }

    private fun readContent(dataOut: ByteArray?, bytesToRead: Int, bytesRead: IntRef?): Boolean {
        if (fileContent == null || dataOut == null || bytesRead == null) {
            bytesRead?.set(0)
            return false
        }

        val remaining = fileContent!!.size - offset
        if (remaining <= 0) {
            bytesRead.set(0)
            return false
        }

        val readSize = minOf(bytesToRead, remaining)
        System.arraycopy(fileContent, offset, dataOut, 0, readSize)
        offset += readSize
        bytesRead.set(readSize)

        return true
    }

    fun cancel() {
        file = null
        fileContent = null
        offset = 0
    }

    override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
        val values = args ?: emptyArray()
        return when (method.name) {
            "processRequest" -> processRequest(values.getOrNull(0) as? CefRequest, values.getOrNull(1) as? CefCallback)
            "open" -> {
                setRefValue(values.getOrNull(1), true)
                true
            }
            "getResponseHeaders" -> {
                getResponseHeaders(
                    values.getOrNull(0) as? CefResponse,
                    values.getOrNull(1) as? IntRef,
                    values.getOrNull(2) as? StringRef
                )
                null
            }
            "readResponse", "read" -> readContent(
                values.getOrNull(0) as? ByteArray,
                values.getOrNull(1) as? Int ?: 0,
                values.getOrNull(2) as? IntRef
            )
            "skip" -> skipContent(values.getOrNull(0) as? Long ?: 0L, values.getOrNull(1))
            "cancel" -> {
                cancel()
                null
            }
            "toString" -> "LocalCefResHandle($resourceBasePath)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === values.getOrNull(0)
            else -> throw UnsupportedOperationException("Unsupported CefResourceHandler method: ${method.name}")
        }
    }

    private fun setRefValue(ref: Any?, value: Any) {
        if (ref == null) return
        val setter = ref.javaClass.methods.firstOrNull { it.name == "set" && it.parameterCount == 1 } ?: return
        val parameterType = setter.parameterTypes[0]
        val convertedValue = when (parameterType) {
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> value as Boolean
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> (value as Number).toInt()
            java.lang.Long.TYPE, java.lang.Long::class.java -> (value as Number).toLong()
            else -> value
        }
        setter.invoke(ref, convertedValue)
    }

}
