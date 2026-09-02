package com.qijing.core.execution

import java.security.MessageDigest

/** Fixed Root-only runtime for imported limiter margins. It never accepts shell or filesystem paths. */
internal object ManagedLimiterRuntime {
    private const val BASE = "/data/local/tmp/qijing-managed-limiter-v1"

    fun isManaged(cluster: ProfileLimiterClusterCommand): Boolean =
        cluster.margins != "absent" || cluster.excludes != "absent" || cluster.prefer != "absent"

    fun contractId(cluster: ProfileLimiterClusterCommand, restore: Boolean): String {
        val min = if (restore) cluster.expectedMinKHz!! else cluster.minKHz
        val max = if (restore) cluster.expectedMaxKHz!! else cluster.maxKHz
        val core = if (restore) cluster.expectedCoreCtl!! else cluster.coreCtl
        val canonical = listOf(
            "qijing-limiter-v1", cluster.profile, cluster.policy.toString(), min.toString(), max.toString(),
            cluster.margins, cluster.excludes, cluster.prefer, core, cluster.ddrBoost.toString()
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun read(cluster: ProfileLimiterClusterCommand): String {
        val dir = "$BASE/policy${cluster.policy}"
        val policy = "/sys/devices/system/cpu/cpufreq/policy${cluster.policy}"
        val core = "/sys/devices/system/cpu/cpu${cluster.policy}/core_ctl/enable"
        val coreRead = if (cluster.coreCtl == "absent") "core=absent" else
            "[ -r '$core' ] && core=\"\$(tr -d '[:space:]' < '$core')\""
        return "if [ -d '$dir' ]; then [ ! -L '$dir' ] || exit 7; " +
            "owner=\"\$(tr -d '[:space:]' < '$dir/contract' 2>/dev/null)\"; " +
            "case \"\$owner\" in ''|*[!0-9a-f]*) exit 8;; esac; [ \"\${#owner}\" -eq 64 ] || exit 8; " +
            "printf 'owned|%s' \"\$owner\"; else " +
            "[ -r '$policy/scaling_min_freq' ] && [ -r '$policy/scaling_max_freq' ] && $coreRead && " +
            "printf 'inactive|%s|%s|%s' \"\$(tr -d '[:space:]' < '$policy/scaling_min_freq')\" " +
            "\"\$(tr -d '[:space:]' < '$policy/scaling_max_freq')\" \"\$core\"; fi"
    }

    fun health(cluster: ProfileLimiterClusterCommand): String {
        val contract = contractId(cluster, restore = false)
        val dir = "$BASE/policy${cluster.policy}"
        return "[ -d '$dir' ] && [ ! -L '$dir' ] && owner=\"\$(tr -d '[:space:]' < '$dir/contract')\" && " +
            "[ \"\$owner\" = '$contract' ] && { [ -f '$dir/fault' ] && { printf 'fault|%s' \"\$owner\"; exit 0; }; " +
            "pid=\"\$(tr -d '[:space:]' < '$dir/pid')\"; ticks=\"\$(tr -d '[:space:]' < '$dir/start_ticks')\"; " +
            "[ -r /proc/\"\$pid\"/stat ] && [ \"\$(sed 's/^.*) //' /proc/\"\$pid\"/stat | awk '{print \$20}')\" = \"\$ticks\" ] && " +
            "tr '\\000' ' ' < /proc/\"\$pid\"/cmdline | grep -Fq '$dir/worker.sh' && printf 'running|%s' \"\$owner\" || printf 'stale|%s' \"\$owner\"; }"
    }

    fun configure(cluster: ProfileLimiterClusterCommand): String {
        require(isManaged(cluster) && !cluster.ddrBoost)
        val contract = contractId(cluster, restore = false)
        val dir = "$BASE/policy${cluster.policy}"
        val policy = "/sys/devices/system/cpu/cpufreq/policy${cluster.policy}"
        val core = "/sys/devices/system/cpu/cpu${cluster.policy}/core_ctl/enable"
        val script = base64(WORKER.toByteArray(Charsets.UTF_8))
        val corePreflight = if (cluster.coreCtl == "absent") "" else " && [ -r '$core' ] && [ -w '$core' ] && [ ! -L '$core' ]"
        return "umask 077; [ ! -L '$BASE' ] && mkdir -p '$BASE' && chmod 700 '$BASE' && " +
            "[ ! -e '$dir' ] && [ -d '$policy' ] && [ ! -L '$policy/scaling_min_freq' ] && " +
            "[ ! -L '$policy/scaling_max_freq' ] && [ -r '$policy/scaling_min_freq' ] && [ -w '$policy/scaling_min_freq' ] && " +
            "[ -r '$policy/scaling_max_freq' ] && [ -w '$policy/scaling_max_freq' ] && " +
            "[ -r '$policy/scaling_cur_freq' ] && [ -r '$policy/scaling_available_frequencies' ]$corePreflight && " +
            "related=\"\$(tr -d '\\r\\n' < '$policy/related_cpus')\" && " +
            "printf '%s\\n' \"\$related\" | grep -Eq '^[0-9]+( [0-9]+)*$' && " +
            "mkdir '$dir' && " +
            "printf '%s\\n' '$contract' > '$dir/contract' && printf '%s\\n' '${cluster.policy}' > '$dir/policy' && " +
            "printf '%s\\n' '${cluster.minKHz}' > '$dir/target_min' && printf '%s\\n' '${cluster.maxKHz}' > '$dir/target_max' && " +
            "printf '%s\\n' '${cluster.margins}' > '$dir/margins' && printf '%s\\n' '${cluster.excludes}' > '$dir/excludes' && " +
            "printf '%s\\n' '${cluster.prefer}' > '$dir/prefer' && printf '%s\\n' '${cluster.coreCtl}' > '$dir/target_core' && " +
            "printf '%s\\n' \"\$related\" > '$dir/related' && " +
            "orig_min=\"\$(tr -d '[:space:]' < '$policy/scaling_min_freq')\" && " +
            "orig_max=\"\$(tr -d '[:space:]' < '$policy/scaling_max_freq')\" && " +
            (if (cluster.coreCtl == "absent") "orig_core=absent" else "orig_core=\"\$(tr -d '[:space:]' < '$core')\"") + " && " +
            "printf '%s\\n' \"\$orig_min\" > '$dir/original_min' && printf '%s\\n' \"\$orig_max\" > '$dir/original_max' && " +
            "printf '%s\\n' \"\$orig_core\" > '$dir/original_core' && printf '%s\\n' \"\$orig_min\" > '$dir/last_min' && " +
            "printf '%s\\n' \"\$orig_max\" > '$dir/last_max' && printf '%s\\n' \"\$orig_core\" > '$dir/last_core' && " +
            "printf '%s' '$script' | base64 -d > '$dir/worker.sh' && chmod 700 '$dir/worker.sh' && " +
            "{ nohup sh '$dir/worker.sh' '$dir' >/dev/null 2>&1 </dev/null & worker=\$!; " +
            "printf '%s\\n' \"\$worker\" > '$dir/pid' && " +
            "ticks=\"\$(sed 's/^.*) //' /proc/\"\$worker\"/stat 2>/dev/null | awk '{print \$20}')\"; " +
            "[ -n \"\$ticks\" ] && printf '%s\\n' \"\$ticks\" > '$dir/start_ticks' && touch '$dir/armed' || " +
            "{ kill -TERM \"\$worker\" 2>/dev/null || true; exit 9; }; " +
            "i=0; while [ \"\$i\" -lt 40 ]; do [ -f '$dir/ready' ] && break; [ -f '$dir/fault' ] && exit 10; " +
            "sleep 0.05; i=\$((i+1)); done; [ -f '$dir/ready' ] && printf 'owned|%s' '$contract'; }"
    }

    fun restore(cluster: ProfileLimiterClusterCommand): String {
        require(isManaged(cluster) && !cluster.ddrBoost)
        val contract = contractId(cluster, restore = true)
        val dir = "$BASE/policy${cluster.policy}"
        val policy = "/sys/devices/system/cpu/cpufreq/policy${cluster.policy}"
        val core = "/sys/devices/system/cpu/cpu${cluster.policy}/core_ctl/enable"
        val originalCore = cluster.coreCtl
        val coreRestore = if (originalCore == "absent") "true" else
            "current_core=\"\$(tr -d '[:space:]' < '$core')\" && last_core=\"\$(tr -d '[:space:]' < '$dir/last_core')\" && " +
                "if [ \"\$current_core\" = '$originalCore' ]; then true; elif [ \"\$current_core\" = \"\$last_core\" ]; then " +
                "printf '%s\\n' '$originalCore' > '$core' && [ \"\$(tr -d '[:space:]' < '$core')\" = '$originalCore' ]; else exit 5; fi"
        return "if [ ! -d '$dir' ]; then " +
            "[ \"\$(tr -d '[:space:]' < '$policy/scaling_min_freq')\" = '${cluster.minKHz}' ] && " +
            "[ \"\$(tr -d '[:space:]' < '$policy/scaling_max_freq')\" = '${cluster.maxKHz}' ]" +
            (if (originalCore == "absent") "" else " && [ \"\$(tr -d '[:space:]' < '$core')\" = '$originalCore' ]") +
            "; else [ ! -L '$dir' ] && [ \"\$(tr -d '[:space:]' < '$dir/contract')\" = '$contract' ] && " +
            "if [ ! -f '$dir/armed' ]; then rm -f '$dir/'* && rmdir '$dir'; exit 0; fi; " +
            "pid=\"\$(tr -d '[:space:]' < '$dir/pid')\" && ticks=\"\$(tr -d '[:space:]' < '$dir/start_ticks')\" && " +
            "case \"\$pid:\$ticks\" in *[!0-9:]*|:|*:|:*) exit 8;; esac; " +
            "if [ -r /proc/\"\$pid\"/stat ] && [ \"\$(sed 's/^.*) //' /proc/\"\$pid\"/stat | awk '{print \$20}')\" = \"\$ticks\" ]; then " +
            "tr '\\000' ' ' < /proc/\"\$pid\"/cmdline | grep -Fq '$dir/worker.sh' || exit 8; " +
            "touch '$dir/stop'; kill -TERM \"\$pid\" 2>/dev/null || true; i=0; while [ -d /proc/\"\$pid\" ] && [ \"\$i\" -lt 20 ]; do sleep 0.05; i=\$((i+1)); done; " +
            "[ -d /proc/\"\$pid\" ] && kill -KILL \"\$pid\" 2>/dev/null || true; sleep 0.05; " +
            "[ ! -d /proc/\"\$pid\" ] || [ \"\$(sed 's/^.*) //' /proc/\"\$pid\"/stat 2>/dev/null | awk '{print \$20}')\" != \"\$ticks\" ] || exit 9; fi; " +
            "last_min=\"\$(tr -d '[:space:]' < '$dir/last_min')\" && last_max=\"\$(tr -d '[:space:]' < '$dir/last_max')\" && " +
            "cur_min=\"\$(tr -d '[:space:]' < '$policy/scaling_min_freq')\" && cur_max=\"\$(tr -d '[:space:]' < '$policy/scaling_max_freq')\" && " +
            "([ \"\$cur_min\" = '${cluster.minKHz}' ] || [ \"\$cur_min\" = \"\$last_min\" ]) && " +
            "([ \"\$cur_max\" = '${cluster.maxKHz}' ] || [ \"\$cur_max\" = \"\$last_max\" ]) || exit 5; " +
            rangeRestoreShell(policy, cluster.minKHz, cluster.maxKHz) + " && $coreRestore && " +
            "rm -f '$dir/'* && rmdir '$dir'; fi"
    }

    fun clearRead(): String =
        "base='$BASE'; if [ ! -d \"\$base\" ]; then printf inactive; " +
            "elif find \"\$base\" -mindepth 1 -maxdepth 1 -type d -name 'policy*' | grep -q .; then exit 5; else printf inactive; fi"

    fun ensureAbsent(): String =
        "base='$BASE'; [ ! -L \"\$base\" ] || exit 7; if [ ! -d \"\$base\" ]; then exit 0; fi; " +
            "! find \"\$base\" -mindepth 1 -maxdepth 1 -type d -name 'policy*' | grep -q ."

    private fun rangeRestoreShell(policy: String, min: Long, max: Long): String =
        "current_min=\"\$(tr -d '[:space:]' < '$policy/scaling_min_freq')\"; " +
            "if [ '$min' -gt \"\$current_min\" ]; then printf '%s\\n' '$max' > '$policy/scaling_max_freq' && printf '%s\\n' '$min' > '$policy/scaling_min_freq'; " +
            "else printf '%s\\n' '$min' > '$policy/scaling_min_freq' && printf '%s\\n' '$max' > '$policy/scaling_max_freq'; fi; " +
            "[ \"\$(tr -d '[:space:]' < '$policy/scaling_min_freq')\" = '$min' ] && " +
            "[ \"\$(tr -d '[:space:]' < '$policy/scaling_max_freq')\" = '$max' ]"

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
        policy="§(cat "§dir/policy")"
        base="/sys/devices/system/cpu/cpufreq/policy§policy"
        min="§(cat "§dir/target_min")"
        max="§(cat "§dir/target_max")"
        margins="§(cat "§dir/margins")"
        excludes="§(cat "§dir/excludes")"
        prefer="§(cat "§dir/prefer")"
        target_core="§(cat "§dir/target_core")"
        arm_wait=0
        while [ ! -f "§dir/armed" ] && [ "§arm_wait" -lt 100 ]; do sleep 0.05; arm_wait=§((arm_wait+1)); done
        [ -f "§dir/armed" ] || exit 12
        write_range() {
          current_min="§(tr -d '[:space:]' < "§base/scaling_min_freq")" || return 1
          if [ "§min" -gt "§current_min" ]; then
            printf '%s\n' "§max" > "§base/scaling_max_freq" && printf '%s\n' "§min" > "§base/scaling_min_freq"
          else
            printf '%s\n' "§min" > "§base/scaling_min_freq" && printf '%s\n' "§max" > "§base/scaling_max_freq"
          fi
          [ "§(tr -d '[:space:]' < "§base/scaling_min_freq")" = "§min" ] || return 1
          [ "§(tr -d '[:space:]' < "§base/scaling_max_freq")" = "§max" ] || return 1
          printf '%s\n' "§min" > "§dir/last_min"
          printf '%s\n' "§max" > "§dir/last_max"
        }
        write_range || { touch "§dir/fault"; exit 10; }
        if [ "§target_core" != absent ]; then
          core="/sys/devices/system/cpu/cpu§policy/core_ctl/enable"
          printf '%s\n' "§target_core" > "§core" && [ "§(tr -d '[:space:]' < "§core")" = "§target_core" ] || { touch "§dir/fault"; exit 11; }
          printf '%s\n' "§target_core" > "§dir/last_core"
        fi
        touch "§dir/ready"
        down=0
        while [ ! -f "§dir/stop" ]; do
          awk -v related="§(cat "§dir/related")" -v excluded="§excludes" '
            BEGIN { split(related,r," "); for(i in r) keep[r[i]]=1; split(excluded,e,","); for(i in e) delete keep[e[i]] }
            /^cpu[0-9]+ / { id=substr(§1,4); if(!(id in keep)) next; total=0; for(i=2;i<=NF;i++) total+=§i; idle=§5+§6; print id,total,idle }
          ' /proc/stat > "§dir/sample.new" || { touch "§dir/fault"; break; }
          if [ -s "§dir/sample.prev" ]; then
            load="§(awk -v prefer="§prefer" 'FNR==NR {t[§1]=§2; i[§1]=§3; next} {dt=§2-t[§1]; di=§3-i[§1]; if(dt>0){st+=dt; si+=di; l=int((dt-di)*1000/dt); if(§1==prefer) pl=l}} END {if(st>0){v=int((st-si)*1000/st); if(pl>v)v=pl; print v}}' "§dir/sample.prev" "§dir/sample.new")"
            case "§load" in ''|*[!0-9]*) load=0;; esac
            cur="§(tr -d '[:space:]' < "§base/scaling_cur_freq")"
            threshold="§{margins%% *}"
            for token in §margins; do
              case "§token" in *:*) freq="§{token%:*}"; margin="§{token#*:}"; [ "§cur" -ge "§freq" ] && threshold="§margin";; esac
            done
            denominator=§((1000-threshold)); [ "§denominator" -gt 0 ] || { touch "§dir/fault"; break; }
            demand="§(awk -v c="§cur" -v l="§load" -v d="§denominator" 'BEGIN { print int(c*l/d) }')"; [ "§demand" -lt "§min" ] && demand="§min"; [ "§demand" -gt "§max" ] && demand="§max"
            target="§max"; for freq in §(tr ' ' '\n' < "§base/scaling_available_frequencies" | sort -n); do [ "§freq" -ge "§demand" ] && { target="§freq"; break; }; done
            [ "§target" -lt "§min" ] && target="§min"; [ "§target" -gt "§max" ] && target="§max"
            last="§(cat "§dir/last_max")"; current="§(tr -d '[:space:]' < "§base/scaling_max_freq")"
            [ "§current" = "§last" ] || { touch "§dir/fault"; break; }
            if [ "§target" -gt "§last" ]; then down=0; elif [ "§target" -lt "§last" ]; then down=§((down+1)); [ "§down" -lt 3 ] && target="§last" || down=0; else down=0; fi
            if [ "§target" != "§last" ]; then
              printf '%s\n' "§target" > "§base/scaling_max_freq" && [ "§(tr -d '[:space:]' < "§base/scaling_max_freq")" = "§target" ] || { touch "§dir/fault"; break; }
              printf '%s\n' "§target" > "§dir/last_max"
            fi
          fi
          mv "§dir/sample.new" "§dir/sample.prev"
          sleep 0.25
        done
    """.trimIndent().replace('§', '$')
}
