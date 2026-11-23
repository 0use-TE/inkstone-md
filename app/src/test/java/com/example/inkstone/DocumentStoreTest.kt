package com.example.inkstone

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun folder() = File(temporary.root, "library")
    private fun store() = DocumentStore(folder())

    @Test fun documentsHaveIndependentPersistentBodies() {
        val db = store()
        val a = db.create("# 第一篇\n\n甲")
        val b = db.create("# 第二篇\n\n乙")
        db.saveDraft(a.id, "# 第一篇\n\n修改甲")
        assertEquals("# 第二篇\n\n乙", db.read(b.id))
        assertEquals("# 第一篇\n\n修改甲", store().read(a.id))
        assertEquals(2, store().list().size)
    }
    @Test fun migrationPreservesLegacyAndRunsOnlyForEmptyLibrary() {
        val legacy = File(temporary.root, "draft.md").apply { writeText("# 旧草稿\n\n不要丢失😀") }
        val db = store()
        val initial = db.bootstrap(legacy.readText(), "旧文件.md", "content://documents/1", "saved", "welcome")
        assertEquals(1, initial.size)
        assertEquals(legacy.readText(), db.read(initial.single().id))
        assertEquals("content://documents/1", initial.single().uri)
        assertEquals("saved", initial.single().savedHash)
        db.bootstrap("different legacy", "other", null, "", "welcome")
        assertEquals(1, db.list().size)
        assertTrue(legacy.exists())
        assertEquals("# 旧草稿\n\n不要丢失😀", legacy.readText())
    }
    @Test fun restartPreservesUnicodeNamesAndExternalSaveState() {
        val db = store()
        val doc = db.create("正文", "中文😀.md", autoTitle = false)
        db.markExported(doc.id, "content://doc/test", "中文😀.md", "正文")
        val restored = store().get(doc.id)
        assertEquals("中文😀.md", restored.name)
        assertEquals(DocumentStore.digest("正文"), restored.savedHash)
        assertEquals("content://doc/test", restored.uri)
    }
    @Test fun savingDraftDoesNotForgetExternalFileMetadata() {
        val db = store()
        val doc = db.create("before")
        db.markExported(doc.id, "content://doc/target", "文件.md", "before")
        val changed = db.saveDraft(doc.id, "after")
        assertEquals("content://doc/target", changed.uri)
        assertEquals("文件.md", changed.name)
        assertEquals(DocumentStore.digest("before"), changed.savedHash)
        assertEquals(DocumentStore.digest("after"), changed.contentHash)
    }
    @Test fun reopeningAnExternalUriFindsTheLocalEditsAfterRestart() {
        val uri = "content://documents/primary%3ADownload%2Fnotes.md"
        val db = store()
        val original = "# 文件原文"
        val doc = db.create(original, "notes.md", uri, DocumentStore.digest(original), false)
        db.saveDraft(doc.id, "# 尚未导出的本机修改")

        val reopened = store().findByUri(uri)!!
        assertEquals(doc.id, reopened.id)
        assertEquals("# 尚未导出的本机修改", store().read(reopened.id))
        assertEquals(DocumentStore.digest(original), reopened.savedHash)
        assertNull(store().findByUri("content://documents/another.md"))
    }
    @Test fun missingMetadataCannotHideDocumentBody() {
        val db = store()
        val doc = db.create("不会丢失的正文")
        assertTrue(File(folder(), "${doc.id}.properties").delete())
        val recovered = store().list().single()
        assertEquals(doc.id, recovered.id)
        assertEquals("不会丢失的正文", store().read(doc.id))
        assertEquals("恢复的文档.md", recovered.name)
    }
    @Test fun manualRenameSurvivesFurtherTyping() {
        val db = store()
        val doc = db.create("# 初始标题")
        assertEquals("初始标题.md", doc.name)
        db.rename(doc.id, "我的文件.md")
        assertEquals("我的文件.md", db.saveDraft(doc.id, "# 后续标题").name)
    }
    @Test fun invalidIdCannotEscapeLibraryDirectory() {
        try { store().read("../../secret"); fail("Must reject path traversal") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun oversizeDraftCannotOverwritePreviousContent() {
        val db = store()
        val doc = db.create("原文")
        try { db.saveDraft(doc.id, "x".repeat(DocumentStore.MAX_BYTES + 1)); fail("Must reject oversized content") }
        catch (_: IllegalArgumentException) { }
        assertEquals("原文", db.read(doc.id))
    }
    @Test fun blankLegacyIsNotReplacedByWelcome() {
        val db = store()
        val doc = db.bootstrap("", "空草稿.md", null, "", "welcome").single()
        assertEquals("", db.read(doc.id))
        assertEquals("空草稿.md", doc.name)
    }
    @Test fun deleteRemovesOnlyTheLocalCopy() {
        val db = store()
        val keep = db.create("保留的文档")
        val drop = db.create("要删掉的文档", "删除.md", "content://doc/x", "hash", false)
        assertTrue(db.delete(drop.id))
        assertEquals(listOf(keep.id), db.list().map { it.id })
        assertFalse(File(folder(), "${drop.id}.md").exists())
        assertFalse(File(folder(), "${drop.id}.properties").exists())
    }
    @Test fun deletingAnUnknownDocumentDoesNotTouchOtherFiles() {
        val db = store()
        val keep = db.create("保留")
        db.delete(keep.id)
        assertTrue(db.list().isEmpty())
        val id = java.util.UUID.randomUUID().toString()
        assertFalse(db.delete(id))
        assertTrue(db.list().isEmpty())
    }
    @Test fun deletedDocumentCannotBeReadBack() {
        val db = store()
        val doc = db.create("临时内容")
        db.delete(doc.id)
        try { db.read(doc.id); fail("Must not read a deleted document") }
        catch (_: IllegalArgumentException) { }
    }
}
