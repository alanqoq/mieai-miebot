package com.mieai.bot.config

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MieAiConfigStore private constructor(
    configurationFile: Path,
    initialConfig: MieAiBotConfig,
) {
    val configurationFile: Path = configurationFile.toAbsolutePath().normalize()

    private val writeLock = ReentrantLock()
    private val current = AtomicReference(initialConfig.immutableCopy())

    fun snapshot(): MieAiBotConfig = current.get()

    fun update(transform: (MieAiBotConfig) -> MieAiBotConfig): MieAiBotConfig = writeLock.withLock {
        val next = transform(current.get()).immutableCopy()
        writeAtomically(configurationFile, MieAiConfigCodec.render(next))
        current.set(next)
        next
    }

    companion object {
        @JvmStatic
        fun open(configurationFile: Path, configurationContent: String): MieAiConfigStore =
            MieAiConfigStore(configurationFile, MieAiConfigCodec.parse(configurationContent))

        private fun writeAtomically(target: Path, content: String) {
            val parent = target.parent ?: throw IOException("配置文件必须有父目录")
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, ".${target.fileName}.", ".tmp")
            try {
                val bytes = content.toByteArray(StandardCharsets.UTF_8)
                FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                try {
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
