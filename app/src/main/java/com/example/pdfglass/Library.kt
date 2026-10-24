package com.example.pdfglass

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class Doc(val uri: String, val name: String, val size: Long, val opened: Long, val page: Int = 0, val fav: Boolean = false)

fun docMeta(ctx: Context, uri: Uri): Pair<String, Long> =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst()) (it.getString(0) ?: "document.pdf") to (if (it.isNull(1)) 0L else it.getLong(1)) else null
    } ?: ("document.pdf" to 0L)

/** Recents, favorites and reading position, kept in DataStore (Room can replace this in a later stage). */
class LibraryVm(app: Application) : AndroidViewModel(app) {
    private val store = app.store
    private val key = stringPreferencesKey("library")

    val docs: StateFlow<List<Doc>> = store.data.map { parse(it[key]) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun parse(s: String?): List<Doc> = runCatching {
        val a = JSONArray(s ?: "[]")
        List(a.length()) {
            val o = a.getJSONObject(it)
            Doc(o.getString("u"), o.getString("n"), o.getLong("s"), o.getLong("t"), o.optInt("p"), o.optBoolean("f"))
        }
    }.getOrDefault(emptyList())

    private fun encode(l: List<Doc>) = JSONArray().also { a ->
        l.forEach { d -> a.put(JSONObject().put("u", d.uri).put("n", d.name).put("s", d.size).put("t", d.opened).put("p", d.page).put("f", d.fav)) }
    }.toString()

    private fun update(f: (List<Doc>) -> List<Doc>) { viewModelScope.launch { store.edit { it[key] = encode(f(parse(it[key]))) } } }

    fun pageOf(uri: String) = docs.value.firstOrNull { it.uri == uri }?.page ?: 0
    fun touch(uri: String, name: String, size: Long) = update { l ->
        val old = l.firstOrNull { it.uri == uri }
        listOf(Doc(uri, name, size, System.currentTimeMillis(), old?.page ?: 0, old?.fav ?: false)) + l.filter { it.uri != uri }
    }
    fun savePage(uri: String, page: Int) = update { l -> l.map { if (it.uri == uri) it.copy(page = page) else it } }
    fun toggleFav(uri: String) = update { l -> l.map { if (it.uri == uri) it.copy(fav = !it.fav) else it } }
    fun remove(uri: String) = update { l -> l.filter { it.uri != uri } }

    fun import(ctx: Context, uris: List<Uri>) {
        val app = ctx.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            uris.forEach { u ->
                runCatching { app.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val (n, s) = docMeta(app, u)
                touch(u.toString(), n, s)
            }
        }
    }
}
