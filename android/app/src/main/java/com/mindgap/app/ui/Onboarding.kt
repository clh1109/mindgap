package com.mindgap.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

private data class OnboardingPage(
    val title: String,
    val body: String
)

private val PAGES = listOf(
    OnboardingPage(
        title = "知隙怎么帮你",
        body = "它在你读技术文档、看网课时观察节奏：哪里停得太久、哪里回看、哪里跳过了术语。" +
            "发现你可能没意识到的盲区后，用两处改写过的原文做一道找茬题。"
    ),
    OnboardingPage(
        title = "它采集什么",
        body = "屏幕文字、画面指纹、麦克风，以及点击、滚动、切应用。" +
            "音频转写与文字识别全部在本机完成；原始音频、截图与时间轴默认不出设备。"
    ),
    OnboardingPage(
        title = "你能控制什么",
        body = "采集期间有常驻通知，可随时暂停或结束；采集结束可一键删除本次数据。" +
            "上传授权默认关闭，只有你手动打开后才允许云端生成压缩片段。"
    )
)

/**
 * 首次启动的数据边界说明；采集页也可以再次打开。
 * 不联网、不申请权限，只解释会发生什么。
 */
@Composable
fun OnboardingDialog(onDismiss: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }
    val page = PAGES[step]

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(page.title) },
        text = {
            Text(
                page.body,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (step < PAGES.lastIndex) step++ else onDismiss()
                }
            ) {
                Text(if (step == PAGES.lastIndex) "开始使用" else "下一步")
            }
        },
        dismissButton = {
            if (step > 0) {
                TextButton(onClick = { step-- }) {
                    Text("上一步")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("稍后")
                }
            }
        }
    )
}
