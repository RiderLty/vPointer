package com.gtm.vpointer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** 预制光标图标（内置 drawable 资源） */
data class CursorPreset(val id: String, val label: String, val resId: Int)

object CursorPresets {
    // 选中项为用户自选 PNG 时的占位 id（图片本体存 filesDir，见 CursorImages）
    const val CUSTOM_ID = "custom"
    const val DEFAULT_ID = "arrow"

    val all = listOf(
        CursorPreset("arrow", "箭头", R.drawable.pointer_arrow),
        CursorPreset("hand", "手型", R.drawable.cursor_hand),
        CursorPreset("crosshair", "十字", R.drawable.cursor_crosshair),
        CursorPreset("dot", "圆点", R.drawable.cursor_dot),
    )

    /** 未知 id（如旧数据）回退到默认箭头 */
    fun presetOr(id: String): CursorPreset =
        all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }
}

/**
 * 光标样式持久化：选中项存 SQLite settings 表（应用唯一的数据库）。
 * 自定义图片本体存 filesDir 下的 PNG 文件——SAF 返回的 Uri 是临时的，
 * 不能长期持有，读取后必须拷贝到应用私有目录。
 */
class CursorIconStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "$COLUMN_KEY TEXT PRIMARY KEY NOT NULL, $COLUMN_VALUE TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1，暂无迁移
    }

    fun getSelectedCursorId(): String {
        readableDatabase.query(
            TABLE, arrayOf(COLUMN_VALUE), "$COLUMN_KEY = ?", arrayOf(KEY_CURSOR_ICON),
            null, null, null
        ).use { c ->
            if (c.moveToFirst()) {
                val v = c.getString(0)
                if (!v.isNullOrBlank()) return v
            }
        }
        return CursorPresets.DEFAULT_ID
    }

    fun setSelectedCursorId(id: String) {
        val cv = ContentValues().apply {
            put(COLUMN_KEY, KEY_CURSOR_ICON)
            put(COLUMN_VALUE, id)
        }
        writableDatabase.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 光标调色，NO_COLOR 表示保持原色。仅对预制图标生效，自定义 PNG 不调色。 */
    fun getSelectedColor(): Int {
        readableDatabase.query(
            TABLE, arrayOf(COLUMN_VALUE), "$COLUMN_KEY = ?", arrayOf(KEY_CURSOR_COLOR),
            null, null, null
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0).toIntOrNull() ?: NO_COLOR
        }
        return NO_COLOR
    }

    fun setSelectedColor(color: Int) {
        val cv = ContentValues().apply {
            put(COLUMN_KEY, KEY_CURSOR_COLOR)
            put(COLUMN_VALUE, color.toString())
        }
        writableDatabase.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        private const val DB_NAME = "vpointer.db"
        private const val DB_VERSION = 1
        private const val TABLE = "settings"
        private const val COLUMN_KEY = "key"
        private const val COLUMN_VALUE = "value"
        private const val KEY_CURSOR_ICON = "cursor_icon"
        private const val KEY_CURSOR_COLOR = "cursor_color"
        /** 表示"原色"（不调色）的哨兵值 */
        const val NO_COLOR = -1
    }
}

/** 自定义光标图片的文件读写，以及按当前选中项加载光标 Drawable */
object CursorImages {
    // 自定义图片统一缩到该尺寸内，光标不需要大图，避免解码大图占内存
    private const val MAX_DIM = 256

    fun file(context: Context): File = File(context.filesDir, "custom_cursor.png")

    /** 按当前持久化的选中项加载光标 Drawable；自定义图不可用时回退默认箭头 */
    fun loadDrawable(context: Context): Drawable {
        val store = CursorIconStore(context)
        val id = store.getSelectedCursorId()
        if (id == CursorPresets.CUSTOM_ID) {
            decodeCustomFile(context)?.let { return BitmapDrawable(context.resources, it) }
        }
        val presetId = if (id == CursorPresets.CUSTOM_ID) CursorPresets.DEFAULT_ID else id
        val drawable = ContextCompat.getDrawable(context, CursorPresets.presetOr(presetId).resId)!!
            .mutate()
        // 预制图标按用户选择调色；mutate() 避免污染 drawable 资源缓存
        val color = store.getSelectedColor()
        if (color != CursorIconStore.NO_COLOR) {
            drawable.setTint(color)
        }
        return drawable
    }

    suspend fun loadCustomBitmap(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        decodeCustomFile(context)
    }

    private fun decodeCustomFile(context: Context): Bitmap? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    /** 从系统图片选择器返回的 Uri 读取图片，缩放后存为内部 PNG。成功返回 true。 */
    suspend fun saveCustomImage(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext false
            val bitmap = decodeScaled(bytes) ?: return@withContext false
            // 先写临时文件再替换，避免写一半时崩溃留下损坏的图片
            val tmp = File(context.filesDir, "custom_cursor.tmp")
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val target = file(context)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) return@withContext false
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("CursorImages", "saveCustomImage failed", e)
            false
        }
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / (sample * 2) >= MAX_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val decodedMax = maxOf(bmp.width, bmp.height)
        if (decodedMax > MAX_DIM * 2) {
            val scale = (MAX_DIM * 2f) / decodedMax
            bmp = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }
        return bmp
    }
}
