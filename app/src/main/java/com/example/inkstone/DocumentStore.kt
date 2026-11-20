package com.example.inkstone

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** App-private, per-document storage. No Android APIs: exercised by JVM tests. */
data class LocalDocument(
    val id: String,
    val name: String,
    val uri: String?,
    val savedHash: String,
    val contentHash: String,
    val preview: String,
    val updatedAt: Long,
    val autoTitle: Boolean
)

class DocumentStore(private val directory: File) {
    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    init {
        require(directory.isDirectory || directory.mkdirs()) { "Cannot open document library" }
    }
    private fun checkedId(id: String): String {
        require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id)) { "Invalid document ID" }
        return id
    }
    private fun bodyFile(id: String) = File(directory, "${checkedId(id)}.md")
    private fun metaFile(id: String) = File(directory, "${checkedId(id)}.properties")
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temporary = File(directory, file.name + ".new")
        FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    private fun snippet(text: String): String = text.lineSequence().take(15)
        .map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?.take(100)?.replace(Regex("[#*_`>]+"), "")?.take(60) ?: "空白文档"
    private fun suggestedTitle(text: String): String? = text.lineSequence().take(30)
        .firstOrNull { it.matches(Regex("^#{1,6}\\s+.+")) }
        ?.replace(Regex("^#{1,6}\\s+"), "")?.trim()?.take(70)?.let { "$it.md" }
    private fun writeMetadata(doc: LocalDocument) {
        val values = Properties().apply {
            setProperty("id", doc.id)
            setProperty("name", doc.name)
            setProperty("uri", doc.uri ?: "")
            setProperty("savedHash", doc.savedHash)
            setProperty("contentHash", doc.contentHash)
            setProperty("preview", doc.preview)
            setProperty("updatedAt", doc.updatedAt.toString())
            setProperty("autoTitle", doc.autoTitle.toString())
            setProperty("bodySize", bodyFile(doc.id).length().toString())
        }
        val writer = java.io.StringWriter()
        values.store(writer, "Inkstone local document")
        atomicWrite(metaFile(doc.id), writer.toString().toByteArray(Charsets.UTF_8))
    }
    private fun readMetadata(id: String): LocalDocument {
        val properties = Properties()
        try { metaFile(id).reader(Charsets.UTF_8).use(properties::load) } catch (_: Exception) { }
        // Normal listing reads only tiny metadata, not every full document/image.
        // Body wins after a partial write or missing/corrupt metadata.
        val body = bodyFile(id)
        require(body.isFile) { "Document does not exist" }
        val cachedHash = properties.getProperty("contentHash", "")
        val cacheValid = Regex("[0-9a-f]{64}").matches(cachedHash) &&
            properties.getProperty("bodySize")?.toLongOrNull() == body.length() &&
            metaFile(id).lastModified() >= body.lastModified()
        val recovered = if (cacheValid) null else read(id)
        return LocalDocument(
            id, properties.getProperty("name")?.takeIf { it.isNotBlank() } ?: "恢复的文档.md",
            properties.getProperty("uri")?.takeIf { it.isNotBlank() },
            properties.getProperty("savedHash", ""), recovered?.let(::digest) ?: cachedHash,
            recovered?.let(::snippet) ?: properties.getProperty("preview", "空白文档"), properties.getProperty("updatedAt")?.toLongOrNull() ?: body.lastModified(),
            properties.getProperty("autoTitle", "false").toBoolean()
        )
    }
    @Synchronized fun list(): List<LocalDocument> = directory.listFiles()
        ?.filter { it.isFile && it.extension == "md" && runCatching { checkedId(it.nameWithoutExtension) }.isSuccess }
        ?.map { readMetadata(it.nameWithoutExtension) }
        ?.sortedByDescending { it.updatedAt } ?: emptyList()
    @Synchronized fun read(id: String): String {
        val file = bodyFile(id)
        require(file.isFile) { "Document does not exist" }
        require(file.length() <= MAX_BYTES) { "Document too large" }
        return file.readBytes().decodeToString(throwOnInvalidSequence = true)
    }
    @Synchronized fun get(id: String): LocalDocument = readMetadata(checkedId(id))
    @Synchronized fun create(
        text: String, name: String = "未命名.md", uri: String? = null,
        savedHash: String = "", autoTitle: Boolean = true
    ): LocalDocument {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Document too large" }
        val id = UUID.randomUUID().toString()
        atomicWrite(bodyFile(id), bytes)
        val doc = LocalDocument(id, if (autoTitle) suggestedTitle(text) ?: name else name,
            uri, savedHash, digest(text), snippet(text), System.currentTimeMillis(), autoTitle)
        writeMetadata(doc)
        return doc
    }
    @Synchronized fun saveDraft(id: String, text: String): LocalDocument {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Document too large" }
        val previous = readMetadata(id)
        if (previous.contentHash == digest(text)) return previous
        atomicWrite(bodyFile(id), bytes)
        val next = previous.copy(
            name = if (previous.autoTitle) suggestedTitle(text) ?: previous.name else previous.name,
            contentHash = digest(text), preview = snippet(text), updatedAt = System.currentTimeMillis())
        writeMetadata(next)
        return next
    }
    @Synchronized fun rename(id: String, name: String): LocalDocument {
        require(name.isNotBlank() && name.length <= 120 && !name.contains('\n')) { "Invalid title" }
        val next = readMetadata(id).copy(name = name.trim(), autoTitle = false)
        writeMetadata(next)
        return next
    }
    @Synchronized fun markExported(id: String, uri: String, name: String, savedText: String): LocalDocument {
        val next = readMetadata(id).copy(uri = uri, name = name, savedHash = digest(savedText), autoTitle = false)
        writeMetadata(next)
        return next
    }
    @Synchronized fun findByUri(uri: String): LocalDocument? = list().firstOrNull { it.uri == uri }
    /** Removes the local copy only. Exported files are outside this directory and stay untouched. */
    @Synchronized fun delete(id: String): Boolean {
        val body = bodyFile(id)
        val meta = metaFile(id)
        val removed = body.isFile && body.delete()
        meta.delete()
        File(directory, "${body.name}.new").delete()
        return removed
    }
    /** Called only if the library is empty; old draft remains untouched. */
    @Synchronized fun bootstrap(legacyText: String?, legacyName: String, legacyUri: String?, legacySavedHash: String, welcome: String): List<LocalDocument> {
        val existing = list()
        if (existing.isNotEmpty()) return existing
        if (legacyText != null) create(legacyText, legacyName, legacyUri, legacySavedHash, false)
        else create(welcome, "欢迎使用墨页.md", autoTitle = false)
        return list()
    }
}
