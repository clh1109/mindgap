package com.mindgap.app.data

import android.content.Context
import com.mindgap.app.data.model.TimelineSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface TimelineLoadState {
    data object Loading : TimelineLoadState
    data class Ready(val session: TimelineSession) : TimelineLoadState
    data class Failed(val message: String) : TimelineLoadState
}

/**
 * 假数据仓库：从 assets 读取一份 TimelineSession。
 * 后续接真实采集时，这里换成本地 Room 查询，UI 与数据契约不变。
 */
object TimelineRepository {
    private const val ASSET_NAME = "mock_timeline.json"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(context: Context): TimelineLoadState = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.mapCatching { text ->
            json.decodeFromString(TimelineSession.serializer(), text)
        }.fold(
            onSuccess = { TimelineLoadState.Ready(it) },
            onFailure = { TimelineLoadState.Failed(it.message ?: "未知错误") }
        )
    }
}
