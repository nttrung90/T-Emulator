// Compile-only Android/Room boundary. Probes inject a DAO and never call this.
package com.temulator.app.data.db
import android.content.Context
class AppDatabase {
    fun gameDao(): GameDao = error("Use injected audit DAO")
    companion object { fun getInstance(context: Context): AppDatabase = error("No real Room in standalone audit") }
}
