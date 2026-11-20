package com.mindgap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mindgap.app.data.TimelineLoadState
import com.mindgap.app.data.TimelineRepository
import com.mindgap.app.ui.MindGapApp
import com.mindgap.app.ui.theme.MindGapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MindGapTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val context = LocalContext.current
                    val state by produceState<TimelineLoadState>(
                        initialValue = TimelineLoadState.Loading,
                        key1 = context
                    ) {
                        value = TimelineRepository.load(context)
                    }

                    when (val current = state) {
                        is TimelineLoadState.Loading -> CenterMessage("正在读取假数据…")
                        is TimelineLoadState.Failed -> CenterMessage("加载失败：${current.message}")
                        is TimelineLoadState.Ready -> MindGapApp(current.session)
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CenterMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
