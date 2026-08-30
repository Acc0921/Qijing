package com.scenepilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.scenepilot.core.execution.DryRunExecutionBroker
import com.scenepilot.core.model.ExecutionBackend

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScenePilotApp() }
    }
}

@Composable
private fun ScenePilotApp() {
    val modules = remember { listOf("设备总览" to "M1", "应用列表" to "M2", "应用场景" to "M3", "CPU 调节" to "M4", "内存与 ZRAM" to "M5", "FPS 监控" to "M8") }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("帧域", style = MaterialTheme.typography.headlineLarge)
                    Text("设备性能场景控制台", style = MaterialTheme.typography.bodyMedium)
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("执行后端"); Text("DRY-RUN", color = MaterialTheme.colorScheme.primary) }
                            Text(ExecutionBackend.DRY_RUN.name)
                        }
                    }
                }
                items(modules) { (name, code) ->
                    Card(modifier = Modifier.fillMaxWidth()) { Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(name); Text(code, color = MaterialTheme.colorScheme.primary) } }
                }
            }
        }
    }
}
