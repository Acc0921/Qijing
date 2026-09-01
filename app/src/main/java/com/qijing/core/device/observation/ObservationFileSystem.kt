package com.qijing.core.device.observation

import java.io.File
import java.io.FileNotFoundException

sealed interface FileReadResult {
    data class Success(val text: String) : FileReadResult
    data object Missing : FileReadResult
    data object PermissionDenied : FileReadResult
    data class Failed(val detail: String) : FileReadResult
}

sealed interface DirectoryReadResult {
    data class Success(val names: List<String>) : DirectoryReadResult
    data object Missing : DirectoryReadResult
    data object PermissionDenied : DirectoryReadResult
    data class Failed(val detail: String) : DirectoryReadResult
}

interface ObservationFileSystem {
    fun readText(path: String): FileReadResult
    fun list(path: String): DirectoryReadResult
}

class LocalObservationFileSystem : ObservationFileSystem {
    override fun readText(path: String): FileReadResult {
        val file = File(path)
        return try {
            if (!file.exists()) return FileReadResult.Missing
            if (!file.canRead()) return FileReadResult.PermissionDenied
            FileReadResult.Success(file.readText())
        } catch (_: SecurityException) {
            FileReadResult.PermissionDenied
        } catch (_: FileNotFoundException) {
            FileReadResult.Missing
        } catch (error: Exception) {
            FileReadResult.Failed(error.message ?: error::class.java.simpleName)
        }
    }

    override fun list(path: String): DirectoryReadResult {
        val directory = File(path)
        return try {
            if (!directory.exists()) return DirectoryReadResult.Missing
            if (!directory.canRead()) return DirectoryReadResult.PermissionDenied
            val names = directory.list()?.toList()
                ?: return DirectoryReadResult.Failed("Directory could not be enumerated")
            DirectoryReadResult.Success(names)
        } catch (_: SecurityException) {
            DirectoryReadResult.PermissionDenied
        } catch (error: Exception) {
            DirectoryReadResult.Failed(error.message ?: error::class.java.simpleName)
        }
    }
}

internal fun FileReadResult.statusWhenUnavailable(): MetricStatus = when (this) {
    FileReadResult.Missing -> MetricStatus.UNSUPPORTED
    FileReadResult.PermissionDenied -> MetricStatus.PERMISSION_DENIED
    is FileReadResult.Failed -> MetricStatus.INVALID
    is FileReadResult.Success -> error("Successful reads do not have an unavailable status")
}

internal fun FileReadResult.detailWhenUnavailable(): String? = when (this) {
    FileReadResult.Missing -> "节点不存在"
    FileReadResult.PermissionDenied -> "当前身份无权读取"
    is FileReadResult.Failed -> detail
    is FileReadResult.Success -> null
}
