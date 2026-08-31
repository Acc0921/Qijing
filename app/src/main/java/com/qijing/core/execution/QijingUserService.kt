package com.qijing.core.execution

/** Runs in the Shizuku user-service process. Only fixed broker mappings may call it. */
class QijingUserService : IQijingUserService.Stub() {
    override fun execute(command: String): String {
        require(command.isNotBlank() && command.length <= MAX_COMMAND_LENGTH) { "Invalid privileged command" }
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val deadline = System.nanoTime() + COMMAND_TIMEOUT_MS * 1_000_000L
        while (true) {
            try {
                process.exitValue()
                break
            } catch (_: IllegalThreadStateException) {
                if (System.nanoTime() >= deadline) {
                    process.destroy()
                    throw IllegalStateException("SHIZUKU_TIMEOUT")
                }
                Thread.sleep(10L)
            }
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.exitValue() == 0) {
            "SHIZUKU_COMMAND_FAILED:${process.exitValue()}:${output.take(256)}"
        }
        return output
    }

    override fun destroy() {
        System.exit(0)
    }

    private companion object {
        const val COMMAND_TIMEOUT_MS = 5_000L
        const val MAX_COMMAND_LENGTH = 2_048
    }
}
