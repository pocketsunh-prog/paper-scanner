package com.paperscanner.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One piece of text kept in the library. */
data class SavedText(
    val id: Long,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Stores text the user wants to keep, so it can be read aloud again later.
 *
 * Plain SQLite rather than Room: the project has no annotation processor set up, and
 * this needs nothing more than a table and a handful of statements. The schema lives
 * in the versioned [MIGRATIONS] history — a fresh install replays all of it, an
 * upgrade applies only the missing steps, and neither path drops user data.
 */
class SavedTextStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    init {
        // Fail at construction — app init — rather than at the first query if the
        // version constant and the schema history ever drift apart.
        check(MIGRATIONS.size == DB_VERSION) {
            "Schema out of sync: DB_VERSION=$DB_VERSION, ${MIGRATIONS.size} migration(s) defined"
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Fresh database: replay the whole history to reach DB_VERSION.
        MIGRATIONS.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // MIGRATIONS[i] upgrades schema version i to i+1, so a database at
        // oldVersion has already applied MIGRATIONS[0 until oldVersion]: run only
        // the missing steps, in order, and keep the user's saved texts.
        MIGRATIONS.subList(oldVersion, newVersion).forEach(db::execSQL)
    }

    /** Insert [body] as a new record. Returns the new row id. */
    fun insert(body: String, title: String = defaultTitle(body)): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COLUMN_TITLE, title)
            put(COLUMN_BODY, body)
            put(COLUMN_CREATED_AT, now)
            put(COLUMN_UPDATED_AT, now)
        }
        return writableDatabase.insert(TABLE, null, values)
    }

    /** Overwrite an existing record. Returns false when the id is unknown. */
    fun update(id: Long, body: String, title: String = defaultTitle(body)): Boolean {
        val values = ContentValues().apply {
            put(COLUMN_TITLE, title)
            put(COLUMN_BODY, body)
            put(COLUMN_UPDATED_AT, System.currentTimeMillis())
        }
        val rows = writableDatabase.update(
            TABLE, values, "$COLUMN_ID = ?", arrayOf(id.toString())
        )
        return rows > 0
    }

    /**
     * Save [body]: overwrite [id] when it is given, otherwise add a new record.
     * Returns the row id the text now lives at.
     */
    fun save(id: Long?, body: String, title: String = defaultTitle(body)): Long {
        if (id != null && update(id, body, title)) return id
        return insert(body, title)
    }

    fun delete(id: Long): Boolean {
        val rows = writableDatabase.delete(TABLE, "$COLUMN_ID = ?", arrayOf(id.toString()))
        return rows > 0
    }

    fun get(id: Long): SavedText? {
        readableDatabase.query(
            TABLE, COLUMNS, "$COLUMN_ID = ?", arrayOf(id.toString()),
            null, null, null, "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toSavedText() else null
        }
    }

    /** Most recently changed first. */
    fun list(): List<SavedText> {
        val results = mutableListOf<SavedText>()
        readableDatabase.query(
            TABLE, COLUMNS, null, null, null, null, "$COLUMN_UPDATED_AT DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(cursor.toSavedText())
            }
        }
        return results
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun Cursor.toSavedText() = SavedText(
        id = getLong(getColumnIndexOrThrow(COLUMN_ID)),
        title = getString(getColumnIndexOrThrow(COLUMN_TITLE)) ?: "",
        body = getString(getColumnIndexOrThrow(COLUMN_BODY)) ?: "",
        createdAt = getLong(getColumnIndexOrThrow(COLUMN_CREATED_AT)),
        updatedAt = getLong(getColumnIndexOrThrow(COLUMN_UPDATED_AT))
    )

    companion object {
        private const val DB_NAME = "saved_texts.db"

        /** Bump when the schema changes, and append the matching statement to [MIGRATIONS]. */
        private const val DB_VERSION = 1

        const val TABLE = "saved_texts"
        const val COLUMN_ID = "_id"
        const val COLUMN_TITLE = "title"
        const val COLUMN_BODY = "body"
        const val COLUMN_CREATED_AT = "created_at"
        const val COLUMN_UPDATED_AT = "updated_at"

        private val COLUMNS = arrayOf(
            COLUMN_ID, COLUMN_TITLE, COLUMN_BODY, COLUMN_CREATED_AT, COLUMN_UPDATED_AT
        )

        /**
         * Schema history, one statement per version step: entry [i] upgrades the
         * database from version i to i+1. The first entry creates the initial
         * tables; each later entry is the ALTER TABLE (or table-rebuild) statement
         * its version bump shipped with. [onCreate] replays the whole list on a
         * fresh database; [onUpgrade] applies only the steps between the stored
         * version and [DB_VERSION], so upgrades never discard saved texts.
         */
        private val MIGRATIONS = listOf(
            // v0 -> v1: initial schema
            """
            CREATE TABLE $TABLE (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TITLE TEXT NOT NULL,
                $COLUMN_BODY TEXT NOT NULL,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
            // v1 -> v2: ALTER TABLE $TABLE ADD COLUMN ... — append and bump DB_VERSION
        )

        /** Title shown in the library: the first real line, trimmed to something sane. */
        fun defaultTitle(body: String, maxLength: Int = 40): String {
            val firstLine = body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: return "Untitled"

            return if (firstLine.length <= maxLength) {
                firstLine
            } else {
                firstLine.take(maxLength).trimEnd() + "…"
            }
        }
    }
}
