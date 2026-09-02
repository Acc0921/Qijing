package com.qijing.core.execution

import java.security.MessageDigest

internal data class ManagedGestureContract(
    val contractId: String,
    val enter: List<Pair<String, String>>
)

internal object ManagedGestureCommandPolicy {
    private val CONTRACT = Regex("[0-9a-f]{64}")

    fun parse(command: CapabilityCommand, restore: Boolean = command.capability.endsWith(".restore")): ManagedGestureContract? {
        val count = command.arguments["enter_count"]?.toIntOrNull()?.takeIf { it in 1..16 } ?: return null
        if (command.arguments["exit_count"] != "0" || command.arguments["event_protocol"] != "getevent:EV_KEY:BTN_TOUCH:DOWN_UP:v1" ||
            command.arguments["restore_enter_on_up"] != "true" || command.arguments["root_only"] != "true"
        ) return null
        val fixed = mutableSetOf(
            "contract_id", "event_protocol", "enter_count", "exit_count", "restore_enter_on_up", "root_only"
        )
        val enter = (0 until count).map { index ->
            val pathKey = "enter_${index}_path"
            val valueKey = "enter_${index}_value"
            fixed += pathKey
            fixed += valueKey
            val path = command.arguments[pathKey]?.takeIf(PrivilegedNodePolicy::validPath) ?: return null
            val value = command.arguments[valueKey]?.takeIf(PrivilegedNodePolicy::validValue) ?: return null
            path to value
        }
        if (restore) fixed += "expected"
        if (command.arguments.keys != fixed || enter.map { it.first }.distinct().size != enter.size) return null
        val contractId = command.arguments["contract_id"]?.takeIf(CONTRACT::matches) ?: return null
        if (restore && command.arguments["expected"] != "owned|$contractId") return null
        val canonical = buildString {
            append("qijing-gesture-v1|").append(command.arguments["event_protocol"]).append("|enter|")
            enter.forEachIndexed { index, (path, value) -> append(index).append('|').append(path).append('=').append(value).append('|') }
            append("exit|restore=true")
        }
        val expectedId = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return if (contractId == expectedId) ManagedGestureContract(contractId, enter) else null
    }
}

/** Root-only BTN_TOUCH watcher with an internal per-press snapshot and CAS restore. */
internal object ManagedGestureRuntime {
    private const val DIR = "/data/local/tmp/qijing-gesture-v1"

    fun read(contract: ManagedGestureContract): String =
        "if [ -d '$DIR' ]; then [ ! -L '$DIR' ] || exit 7; owner=\"\$(tr -d '[:space:]' < '$DIR/contract' 2>/dev/null)\"; " +
            "case \"\$owner\" in ''|*[!0-9a-f]*) exit 8;; esac; [ \"\${#owner}\" -eq 64 ] || exit 8; printf 'owned|%s' \"\$owner\"; " +
            "else printf inactive; fi"

    fun health(contract: ManagedGestureContract): String =
        "[ -d '$DIR' ] && [ ! -L '$DIR' ] && owner=\"\$(tr -d '[:space:]' < '$DIR/contract')\" && " +
            "[ \"\$owner\" = '${contract.contractId}' ] && { [ -f '$DIR/fault' ] && { printf 'fault|%s' \"\$owner\"; exit 0; }; " +
            "pid=\"\$(tr -d '[:space:]' < '$DIR/pid')\"; ticks=\"\$(tr -d '[:space:]' < '$DIR/start_ticks')\"; " +
            "[ -r /proc/\"\$pid\"/stat ] && [ \"\$(sed 's/^.*) //' /proc/\"\$pid\"/stat | awk '{print \$20}')\" = \"\$ticks\" ] && " +
            "tr '\\000' ' ' < /proc/\"\$pid\"/cmdline | grep -Fq '$DIR/worker.sh' && printf 'running|%s' \"\$owner\" || printf 'stale|%s' \"\$owner\"; }"

    fun configure(contract: ManagedGestureContract): String {
        val script = base64(WORKER.toByteArray(Charsets.UTF_8))
        val config = buildString {
            contract.enter.forEachIndexed { index, (path, value) ->
                append("printf '%s\\n' '").append(path).append("' > '$DIR/path_").append(index).append("' && ")
                append("printf '%s\\n' '").append(value).append("' > '$DIR/enter_").append(index).append("' && ")
            }
        }
        return "umask 077; base='/data/local/tmp'; [ ! -L \"\$base\" ] && [ ! -e '$DIR' ] && mkdir '$DIR' && chmod 700 '$DIR' && " +
            "printf '%s\\n' '${contract.contractId}' > '$DIR/contract' && printf '%s\\n' '${contract.enter.size}' > '$DIR/count' && " +
            config +
            "devices=''; for device in /dev/input/event*; do [ -c \"\$device\" ] || continue; " +
            "getevent -lp \"\$device\" 2>/dev/null | grep -qw BTN_TOUCH && devices=\"\$devices \$device\"; done; " +
            "[ -n \"\$devices\" ] && printf '%s\\n' \"\$devices\" > '$DIR/devices' && " +
            "printf '%s' '$script' | base64 -d > '$DIR/worker.sh' && chmod 700 '$DIR/worker.sh' && " +
            "{ nohup sh '$DIR/worker.sh' '$DIR' >/dev/null 2>&1 </dev/null & worker=\$!; printf '%s\\n' \"\$worker\" > '$DIR/pid' && " +
            "ticks=\"\$(sed 's/^.*) //' /proc/\"\$worker\"/stat 2>/dev/null | awk '{print \$20}')\"; " +
            "[ -n \"\$ticks\" ] && printf '%s\\n' \"\$ticks\" > '$DIR/start_ticks' && touch '$DIR/armed' || " +
            "{ kill -TERM \"\$worker\" 2>/dev/null || true; exit 9; }; " +
            "i=0; while [ \"\$i\" -lt 40 ]; do [ -f '$DIR/ready' ] && break; [ -f '$DIR/fault' ] && exit 10; sleep 0.05; i=\$((i+1)); done; " +
            "[ -f '$DIR/ready' ] && printf 'owned|%s' '${contract.contractId}'; }"
    }

    fun restore(contract: ManagedGestureContract): String {
        val restoreRows = buildString {
            contract.enter.indices.reversed().forEach { index ->
                append("path=\"\$(cat '$DIR/path_").append(index).append("')\"; baseline=\"\$(cat '$DIR/press_").append(index)
                    .append("')\"; enter=\"\$(cat '$DIR/enter_").append(index).append("')\"; current=\"\$(tr -d '\\r\\n' < \"\$path\")\"; ")
                append("if [ \"\$current\" = \"\$baseline\" ]; then true; elif [ \"\$current\" = \"\$enter\" ]; then ")
                append("printf '%s\\n' \"\$baseline\" > \"\$path\" && [ \"\$(tr -d '\\r\\n' < \"\$path\")\" = \"\$baseline\" ]; else exit 5; fi; ")
            }
        }
        return "if [ ! -d '$DIR' ]; then exit 0; fi; [ ! -L '$DIR' ] && " +
            "[ \"\$(tr -d '[:space:]' < '$DIR/contract')\" = '${contract.contractId}' ] && " +
            "if [ ! -f '$DIR/armed' ]; then rm -f '$DIR/'* && rmdir '$DIR'; exit 0; fi; " +
            "pid=\"\$(tr -d '[:space:]' < '$DIR/pid')\" && ticks=\"\$(tr -d '[:space:]' < '$DIR/start_ticks')\" && " +
            "case \"\$pid:\$ticks\" in *[!0-9:]*|:|*:|:*) exit 8;; esac; " +
            "if [ -r /proc/\"\$pid\"/stat ] && [ \"\$(sed 's/^.*) //' /proc/\"\$pid\"/stat | awk '{print \$20}')\" = \"\$ticks\" ]; then " +
            "tr '\\000' ' ' < /proc/\"\$pid\"/cmdline | grep -Fq '$DIR/worker.sh' || exit 8; " +
            "collect() { p=\"\$1\"; [ -d /proc/\"\$p\" ] || return; for c in \$(cat /proc/\"\$p\"/task/\"\$p\"/children 2>/dev/null); do collect \"\$c\"; done; printf '%s ' \"\$p\"; }; " +
            "pids=\"\$(collect \"\$pid\")\"; touch '$DIR/stop'; kill -TERM \$pids 2>/dev/null || true; sleep 0.1; kill -KILL \$pids 2>/dev/null || true; sleep 0.05; " +
            "[ ! -d /proc/\"\$pid\" ] || [ \"\$(sed 's/^.*) //' /proc/\"\$pid\"/stat 2>/dev/null | awk '{print \$20}')\" != \"\$ticks\" ] || exit 9; fi; " +
            "if [ -f '$DIR/pressed' ]; then $restoreRows fi; rm -f '$DIR/'* && rmdir '$DIR'"
    }

    private fun base64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        return buildString((bytes.size + 2) / 3 * 4) {
            var index = 0
            while (index < bytes.size) {
                val a = bytes[index++].toInt() and 0xff
                val b = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
                val c = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
                append(alphabet[a ushr 2])
                append(alphabet[((a and 3) shl 4) or if (b >= 0) (b ushr 4) else 0])
                append(if (b >= 0) alphabet[((b and 15) shl 2) or if (c >= 0) (c ushr 6) else 0] else '=')
                append(if (c >= 0) alphabet[c and 63] else '=')
            }
        }
    }

    private val WORKER = """
        #!/system/bin/sh
        dir="§1"
        count="§(cat "§dir/count")"
        devices="§(cat "§dir/devices")"
        arm_wait=0
        while [ ! -f "§dir/armed" ] && [ "§arm_wait" -lt 100 ]; do sleep 0.05; arm_wait=§((arm_wait+1)); done
        [ -f "§dir/armed" ] || exit 12
        restore_press() {
          i=§((count-1))
          while [ "§i" -ge 0 ]; do
            path="§(cat "§dir/path_§i")"; baseline="§(cat "§dir/press_§i")"; enter="§(cat "§dir/enter_§i")"
            current="§(tr -d '\r\n' < "§path")" || return 1
            if [ "§current" = "§baseline" ]; then true
            elif [ "§current" = "§enter" ]; then printf '%s\n' "§baseline" > "§path" && [ "§(tr -d '\r\n' < "§path")" = "§baseline" ] || return 1
            else return 1; fi
            i=§((i-1))
          done
          rm -f "§dir/pressed" "§dir/press_"*
        }
        touch "§dir/ready"
        getevent -lt §devices 2>/dev/null | while IFS= read -r line; do
          [ -f "§dir/stop" ] && break
          [ -f "§dir/fault" ] && break
          case "§line" in
            *EV_KEY*BTN_TOUCH*DOWN)
              [ -f "§dir/pressed" ] && continue
              i=0; ok=1
              while [ "§i" -lt "§count" ]; do path="§(cat "§dir/path_§i")"; value="§(tr -d '\r\n' < "§path")" || ok=0; printf '%s\n' "§value" > "§dir/press_§i" || ok=0; i=§((i+1)); done
              [ "§ok" -eq 1 ] || { touch "§dir/fault"; continue; }
              touch "§dir/pressed"
              i=0
              while [ "§i" -lt "§count" ]; do path="§(cat "§dir/path_§i")"; value="§(cat "§dir/enter_§i")"; printf '%s\n' "§value" > "§path" && [ "§(tr -d '\r\n' < "§path")" = "§value" ] || { touch "§dir/fault"; restore_press || true; break; }; i=§((i+1)); done
              ;;
            *EV_KEY*BTN_TOUCH*UP)
              [ -f "§dir/pressed" ] && restore_press || true
              [ -f "§dir/pressed" ] && touch "§dir/fault"
              ;;
          esac
        done
        [ -f "§dir/pressed" ] && restore_press || true
        [ -f "§dir/pressed" ] && touch "§dir/fault"
    """.trimIndent().replace('§', '$')
}
