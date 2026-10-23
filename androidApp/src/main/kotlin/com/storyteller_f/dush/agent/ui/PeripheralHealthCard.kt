package com.storyteller_f.dush.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.storyteller_f.dush.agent.band.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun healthTime(at: Long?) = at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")) } ?: "暂无记录"
private fun sleepDuration(minutes: Int) = "${minutes / 60} 小时 ${minutes % 60} 分钟"

@Composable
internal fun PeripheralHealthCard(summary: PeripheralHealthSummary?, status: HealthSyncStatus?, connected: Boolean, sync: () -> Unit, measure: () -> Unit) {
    val busy = status is HealthSyncStatus.Syncing
    Card(Modifier.fillMaxWidth().testTag("peripheral-health")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("健康数据", style = MaterialTheme.typography.titleMedium)
            Text("今日步数", style = MaterialTheme.typography.titleSmall)
            Text(summary?.steps?.steps?.let { "$it 步" } ?: "尚未读取今日步数", modifier = Modifier.testTag("peripheral-health-steps"))
            summary?.steps?.let { Text("手环读取于 ${healthTime(it.at)}", style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Text("最近心率", style = MaterialTheme.typography.titleSmall)
            Text(summary?.latestHeart?.bpm?.let { "$it 次/分" } ?: "暂无心率记录", modifier = Modifier.testTag("peripheral-health-heart"))
            summary?.latestHeart?.let { Text("${healthTime(it.at)} · ${it.source}", style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Text("昨晚睡眠（已同步记录）", style = MaterialTheme.typography.titleSmall)
            Text(summary?.sleepMinutes?.let { sleepDuration(it) } ?: "暂无昨晚睡眠记录", modifier = Modifier.testTag("peripheral-health-sleep"))
            if (summary?.sleepStart != null) Text("${healthTime(summary.sleepStart)} — ${healthTime(summary.sleepEnd)}", style = MaterialTheme.typography.bodySmall)
            Text("按手环标记的睡眠分钟汇总；记录可能不完整。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = sync, enabled = connected && !busy, modifier = Modifier.testTag("peripheral-health-sync")) { Text("同步近 7 天") }
                OutlinedButton(onClick = measure, enabled = connected && !busy, modifier = Modifier.testTag("peripheral-health-measure")) { Text("测量心率") }
            }
            when (status) {
                is HealthSyncStatus.Syncing -> { Text(status.description); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                is HealthSyncStatus.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            Text("历史同步：${healthTime(summary?.historySyncedAt)}", style = MaterialTheme.typography.bodySmall)
            if (!summary?.days.isNullOrEmpty()) {
                HorizontalDivider()
                Text("近 7 天已同步记录", style = MaterialTheme.typography.titleSmall)
                summary.days.forEach { day ->
                    Text("${day.date} · ${day.recordedSteps} 步 · ${if (day.sleepMinutes > 0) "睡眠 ${sleepDuration(day.sleepMinutes)}" else "暂无睡眠标记"}" +
                        (day.averageHeartRate?.let { " · 平均心率 $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
                Text("历史汇总只统计已同步分钟，不保证覆盖完整一天。", style = MaterialTheme.typography.bodySmall)
            }
            Text("绑定的对话可以读取这些本地数据及记录时间。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
