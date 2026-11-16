package id.web2apk.data

import android.content.Context
import org.json.JSONArray

/** Riwayat project yang pernah dibuat, disimpan lokal (SharedPreferences). */
class ProjectStore(context: Context) {
    private val prefs = context.getSharedPreferences("projects", Context.MODE_PRIVATE)

    fun loadAll(): List<ProjectConfig> {
        val raw = prefs.getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { ProjectConfig.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(cfg: ProjectConfig) {
        val list = loadAll().filterNot { it.packageId == cfg.packageId }.toMutableList()
        list.add(0, cfg)
        persist(list.take(30))
    }

    fun delete(packageId: String) = persist(loadAll().filterNot { it.packageId == packageId })

    private fun persist(list: List<ProjectConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
