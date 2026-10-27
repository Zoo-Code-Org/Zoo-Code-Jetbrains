// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.actors

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.zoocode.jetbrains.events.WebviewViewProviderData
import org.zoocode.jetbrains.util.FocusRequestEpoch
import org.zoocode.jetbrains.util.ManualFocusGuard
import org.zoocode.jetbrains.webview.WebViewManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Webview view related interface
 */
interface MainThreadWebviewViewsShape : Disposable {
    /**
     * Register Webview view provider
     * @param extension Webview extension description
     * @param viewType View type
     * @param options Option configuration
     */
    fun registerWebviewViewProvider(
        extension: Map<String, Any?>,
        viewType: String,
        options: Map<String, Any?>
    )

    /**
     * Unregister Webview view provider
     * @param viewType View type
     */
    fun unregisterWebviewViewProvider(viewType: String)

    /**
     * Set Webview view title
     * @param handle Webview handle
     * @param value Title value
     */
    fun setWebviewViewTitle(handle: String, value: String?)

    /**
     * Set Webview view description
     * @param handle Webview handle
     * @param value Description content
     */
    fun setWebviewViewDescription(handle: String, value: String?)

    /**
     * Set Webview view badge
     * @param handle Webview handle
     * @param badge Badge information
     */
    fun setWebviewViewBadge(handle: String, badge: Map<String, Any?>?)

    /**
     * Show Webview view
     * @param handle Webview handle
     * @param preserveFocus Whether to preserve focus
     */
    fun show(handle: String, preserveFocus: Boolean)
}

class MainThreadWebviewViews(val project: Project) : MainThreadWebviewViewsShape {
    private val logger = Logger.getInstance(MainThreadWebviewViews::class.java)
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    // Bounded request invalidation: a newer show supersedes queued show work
    private val showEpoch = FocusRequestEpoch()

    override fun registerWebviewViewProvider(
        extension: Map<String, Any?>,
        viewType: String,
        options: Map<String, Any?>
    ) {
        logger.info("Registering Webview view provider: viewType=$viewType, options=$options")

//         Use EventBus to send WebView view provider registration event, using IntelliJ platform compatible method
//        project.getService(ProjectEventBus::class.java).emitInApplication(
//            WebviewViewProviderRegisterEvent,
//            WebviewViewProviderData(extension, viewType, options)
//        )
        project.getService(WebViewManager::class.java).registerProvider(WebviewViewProviderData(extension, viewType, options))
    }

    override fun unregisterWebviewViewProvider(viewType: String) {
        logger.info("Unregistering Webview view provider: viewType=$viewType")
    }

    override fun setWebviewViewTitle(handle: String, value: String?) {
        logger.info("Setting Webview view title: handle=$handle, title=$value")
    }

    override fun setWebviewViewDescription(handle: String, value: String?) {
        logger.info("Setting Webview view description: handle=$handle, description=$value")
    }

    override fun setWebviewViewBadge(handle: String, badge: Map<String, Any?>?) {
        logger.info("Setting Webview view badge: handle=$handle, badge=$badge")
    }

    /**
     * Shows the requested webview view.
     *
     * A preserving request only reveals the tool window without activation.
     * A deliberate request activates the view and targets the browser keyboard
     * component once, so OSR keystrokes reach the page without a second click.
     * A deliberate request observes focus-owner moves while it waits in the
     * queue through a bounded [ManualFocusGuard]: a manual user choice between
     * the request and its execution cancels the activation. A preserving
     * request is background work and is not cancelled by a manual move.
     * Unknown and stale handles are rejected.
     */
    override fun show(handle: String, preserveFocus: Boolean) {
        logger.info("Showing Webview view, preserveFocus=$preserveFocus")
        val webViewManager = project.getService(WebViewManager::class.java)
        val epoch = showEpoch.next()
        val guard = if (preserveFocus) null else ManualFocusGuard().apply { start() }
        ApplicationManager.getApplication().invokeLater {
            try {
                if (project.isDisposed) {
                    return@invokeLater
                }
                if (!showEpoch.isCurrent(epoch)) {
                    return@invokeLater
                }
                val webView = webViewManager.getLatestWebView()
                if (webView == null || webView.viewId != handle) {
                    logger.warn("Rejected Webview view show for a handle that is not the current view")
                    return@invokeLater
                }
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ZOO_CODE_TOOL_WINDOW_ID)
                if (toolWindow == null || toolWindow.isDisposed) {
                    logger.warn("Rejected Webview view show: tool window is not available")
                    return@invokeLater
                }
                if (preserveFocus) {
                    toolWindow.show(null)
                } else {
                    if (guard?.focusMoved() == true) {
                        logger.info("Skipped deliberate Webview view show: the user moved focus first")
                        return@invokeLater
                    }
                    toolWindow.activate {
                        if (project.isDisposed || !showEpoch.isCurrent(epoch)) {
                            return@activate
                        }
                        if (webView.isDisposed || webViewManager.getLatestWebView() !== webView) {
                            return@activate
                        }
                        webView.browser.component.requestFocusInWindow()
                    }
                }
            } finally {
                guard?.close()
            }
        }
    }

    override fun dispose() {
        logger.info("Disposing MainThreadWebviewViews resources")
    }

    private companion object {
        const val ZOO_CODE_TOOL_WINDOW_ID = "Zoo Code JetBrains"
    }
}
