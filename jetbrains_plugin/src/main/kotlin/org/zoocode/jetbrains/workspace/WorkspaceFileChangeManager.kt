// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.workspace

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.*
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.util.messages.MessageBusConnection
import org.zoocode.jetbrains.core.PluginContext
import org.zoocode.jetbrains.core.ServiceProxyRegistry
import org.zoocode.jetbrains.events.*
import org.zoocode.jetbrains.ipc.proxy.interfaces.FileSystemEvents


/**
 * Workspace file change manager
 * Listens for creation, modification, deletion, and other changes of files in the workspace, and sends corresponding events
 */
@Service(Service.Level.PROJECT)
class WorkspaceFileChangeManager(val project: Project) : Disposable {
    private val logger = Logger.getInstance(WorkspaceFileChangeManager::class.java)

    private var vfsConnection: MessageBusConnection? = null
    
    init {
        // The extension host initializes this project's file watcher on startup.
        logger.info("Initialize workspace file change manager")
        registerFileListener()
    }

    /**
     * Register file listener
     */
    private fun registerFileListener() {
        logger.info("Register file listener for project ${project.name}")

        try {
            // Create connection and subscribe to VFS change events
            val connection = project.messageBus.connect(this)

            // Add virtual file system listener
            connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    processBulkFileEvents(events, project)
                }
            })

            // Save connection for later cleanup
            vfsConnection = connection

        } catch (e: Exception) {
            logger.error("Failed to register file listener for project ${project.name}", e)
        }
    }

    /**
     * Process bulk file events
     * @param events List of file events
     * @param project Related project
     */
    private fun processBulkFileEvents(events: List<VFileEvent>, project: Project) {
        if (events.isEmpty()) {
            return
        }

        // Collect all file changes
        val fileChanges = mutableListOf<WorkspaceFileChangeData>()
        val directoryChanges = mutableListOf<WorkspaceFileChangeData>()

        events.forEach { event ->
            val file = when (event) {
                is VFileCreateEvent -> event.file
                is VFileDeleteEvent -> event.file
                is VFileMoveEvent -> event.file
                is VFileCopyEvent -> event.file
                is VFilePropertyChangeEvent -> event.file
                is VFileContentChangeEvent -> event.file
                else -> null
            }

            if (file != null) {
                // Determine change type
                val changeType = when (event) {
                    is VFileCreateEvent -> FileChangeType.CREATED
                    is VFileDeleteEvent -> FileChangeType.DELETED
                    else -> FileChangeType.UPDATED
                }

                // Skip irrelevant files or directories
                if (isRelevantFileSystemItem(file, project)) {
                    val changeData = WorkspaceFileChangeData(file, changeType)

                    // Store by type
                    if (file.isDirectory) {
                        directoryChanges.add(changeData)
                        // Trigger event for each directory change
                        triggerDirectoryChangeEvent(changeData)
                    } else {
                        fileChanges.add(changeData)
                        // Trigger event for each file change
                        triggerFileChangeEvent(changeData)
                    }
                }
            }
        }

        // Trigger bulk file change event
        if (fileChanges.isNotEmpty()) {
            triggerBulkFileChangeEvent(fileChanges,project)
        }

        // Trigger bulk directory change event
        if (directoryChanges.isNotEmpty()) {
            triggerBulkDirectoryChangeEvent(directoryChanges,project)
        }
    }

    /**
     * Trigger file change event
     * @param fileChangeData File change data
     */
    private fun triggerFileChangeEvent(fileChangeData: WorkspaceFileChangeData) {
        logger.debug("File changed: ${fileChangeData.file.path}, type: ${fileChangeData.changeType}")

        // Send single file change event via EventBus
        project.getService(ProjectEventBus::class.java).emitInApplication(WorkspaceFileChangeEvent, fileChangeData)
    }

    /**
     * Trigger directory change event
     * @param directoryChangeData Directory change data
     */
    private fun triggerDirectoryChangeEvent(directoryChangeData: WorkspaceFileChangeData) {
        logger.debug("Directory changed: ${directoryChangeData.file.path}, type: ${directoryChangeData.changeType}")

        // Send single directory change event via EventBus
        project.getService(ProjectEventBus::class.java).emitInApplication(WorkspaceDirectoryChangeEvent, directoryChangeData)
    }

    /**
     * Trigger bulk file change event
     * @param fileChanges List of file changes
     */
    private fun triggerBulkFileChangeEvent(fileChanges: List<WorkspaceFileChangeData>, project: Project) {
        logger.debug("Bulk file change, total ${fileChanges.size} files")

        val proxy = PluginContext.getInstance(project).getRPCProtocol()?.getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostFileSystemEventService)
        proxy?.let {
            // Convert file change data to FileSystemEvents format
            val createdFiles = mutableListOf<Map<String, Any?>>()
            val changedFiles = mutableListOf<Map<String, Any?>>()
            val deletedFiles = mutableListOf<Map<String, Any?>>()

            // Classify files by change type
            fileChanges.forEach { fileChange ->
                val uriComponents = fileToUriComponents(fileChange.file)
                when (fileChange.changeType) {
                    FileChangeType.CREATED -> createdFiles.add(uriComponents)
                    FileChangeType.UPDATED -> changedFiles.add(uriComponents)
                    FileChangeType.DELETED -> deletedFiles.add(uriComponents)
                }
            }

            // Create FileSystemEvents object and send
            val fileSystemEvents = FileSystemEvents(
                session = fileChanges[0].timestamp.toString(),
                created = createdFiles,
                changed = changedFiles,
                deleted = deletedFiles
            )

            // Call onFileEvent method of extension host file system event service
            it.onFileEvent(fileSystemEvents)
        }

        // Send bulk file change event via EventBus
        val bulkChangeData = WorkspaceFilesChangeData(fileChanges)
        project.getService(ProjectEventBus::class.java).emitInApplication(WorkspaceFilesChangeEvent, bulkChangeData)
    }

    /**
     * Trigger bulk directory change event
     * @param directoryChanges List of directory changes
     */
    private fun triggerBulkDirectoryChangeEvent(directoryChanges: List<WorkspaceFileChangeData>, project: Project) {
        logger.debug("Bulk directory change, total ${directoryChanges.size} directories")

        val proxy = PluginContext.getInstance(project).getRPCProtocol()?.getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostFileSystemEventService)
        proxy?.let {
            // Convert directory change data to FileSystemEvents format
            val createdDirs = mutableListOf<Map<String, Any?>>()
            val changedDirs = mutableListOf<Map<String, Any?>>()
            val deletedDirs = mutableListOf<Map<String, Any?>>()

            // Classify directories by change type
            directoryChanges.forEach { dirChange ->
                val uriComponents = fileToUriComponents(dirChange.file)
                when (dirChange.changeType) {
                    FileChangeType.CREATED -> createdDirs.add(uriComponents)
                    FileChangeType.UPDATED -> changedDirs.add(uriComponents)
                    FileChangeType.DELETED -> deletedDirs.add(uriComponents)
                }
            }

            // Create FileSystemEvents object and send
            val fileSystemEvents = FileSystemEvents(
                session = directoryChanges[0].timestamp.toString(),
                created = createdDirs,
                changed = changedDirs,
                deleted = deletedDirs
            )

            // Call onFileEvent method of extension host file system event service
            it.onFileEvent(fileSystemEvents)
        }

        // Send bulk directory change event via EventBus
        val bulkChangeData = WorkspaceFilesChangeData(directoryChanges)
        project.getService(ProjectEventBus::class.java).emitInApplication(WorkspaceDirectoriesChangeEvent, bulkChangeData)
    }

    /**
     * Convert VirtualFile to URI components map
     * @param file Virtual file
     * @return URI components map
     */
    private fun fileToUriComponents(file: VirtualFile): Map<String, Any?> {
        // Build component map conforming to VSCode URI format
        return mapOf(
            "scheme" to "file",
            "path" to file.path,
            "authority" to "",
            "query" to "",
            "fragment" to ""
        )
    }

    /**
     * Check if file or directory is relevant to the project
     * @param file File or directory to check
     * @param project Project
     * @return Whether the file or directory is relevant to the project
     */
    private fun isRelevantFileSystemItem(file: VirtualFile, project: Project): Boolean {
        // Ignore hidden files and directories
        if (file.name.startsWith(".") || file.path.contains("/.")) {
            return false
        }

        // For files, ignore temporary files
        if (!file.isDirectory && (file.name.endsWith("~") || file.name.endsWith(".tmp"))) {
            return false
        }

        return true
    }

    override fun dispose() {
        logger.info("Release workspace file change manager resources")

        try {
            // Also support the explicit extension-host shutdown path.
            vfsConnection?.disconnect()
            vfsConnection = null

        } catch (e: Exception) {
            logger.error("Failed to release workspace file change manager resources", e)
        }
    }

}
