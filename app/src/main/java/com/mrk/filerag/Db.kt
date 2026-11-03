package com.mrk.filerag

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

data class Hit(val path: String, val name: String, val snippet: String, val score: Int)

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "rag.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE files(id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT UNIQUE, mtime INTEGER, size INTEGER)")
        db.execSQL("CREATE VIRTUAL TABLE fts USING fts4(name, content, tokenize=porter)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM files", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun isUpToDate(path: String, mtime: Long, size: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM files WHERE path=? AND mtime=? AND size=?",
            arrayOf(path, mtime.toString(), size.toString())
        ).use { it.moveToFirst() }

    fun upsert(path: String, name: String, content: String, mtime: Long, size: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var id = -1L
            db.rawQuery("SELECT id FROM files WHERE path=?", arrayOf(path)).use { if (it.moveToFirst()) id = it.getLong(0) }
            if (id >= 0) {
                db.execSQL("DELETE FROM fts WHERE docid=?", arrayOf<Any>(id))
                db.execSQL("UPDATE files SET mtime=?, size=? WHERE id=?", arrayOf<Any>(mtime, size, id))
            } else {
                val cv = ContentValues().apply { put("path", path); put("mtime", mtime); put("size", size) }
                id = db.insert("files", null, cv)
            }
            db.execSQL("INSERT INTO fts(docid, name, content) VALUES(?,?,?)", arrayOf<Any>(id, name, content))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun removeMissing() {
        val db = writableDatabase
        val gone = ArrayList<Long>()
        db.rawQuery("SELECT id, path FROM files", null).use { c ->
            while (c.moveToNext()) if (!File(c.getString(1)).exists()) gone.add(c.getLong(0))
        }
        if (gone.isEmpty()) return
        db.beginTransaction()
        try {
            for (id in gone) {
                db.execSQL("DELETE FROM fts WHERE docid=?", arrayOf<Any>(id))
                db.execSQL("DELETE FROM files WHERE id=?", arrayOf<Any>(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun search(q: String, limit: Int = 60): List<Hit> {
        val tokens = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.distinct()
        if (tokens.isEmpty()) return emptyList()
        val match = tokens.joinToString(" OR ") { "$it*" }
        val out = ArrayList<Hit>()
        try {
            readableDatabase.rawQuery(
                "SELECT files.path, fts.name, substr(fts.content,1,20000) FROM fts JOIN files ON files.id = fts.docid WHERE fts MATCH ? LIMIT 400",
                arrayOf(match)
            ).use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0)
                    val name = (c.getString(1) ?: "").lowercase()
                    val content = c.getString(2) ?: ""
                    val lc = content.lowercase()
                    var score = 0
                    var first = -1
                    for (t in tokens) {
                        val s = if (t.length > 3) t.removeSuffix("s") else t
                        if (name.contains(s)) score += 3
                        val i = lc.indexOf(s)
                        if (i >= 0) {
                            score += 1
                            if (first < 0 || i < first) first = i
                        }
                    }
                    val snip = if (first >= 0)
                        content.substring(maxOf(0, first - 50), minOf(content.length, first + 120)).replace(Regex("\\s+"), " ")
                    else ""
                    out.add(Hit(path, File(path).name, snip, score))
                }
            }
        } catch (e: Exception) {
        }
        return out.sortedByDescending { it.score }.take(limit)
    }
}
