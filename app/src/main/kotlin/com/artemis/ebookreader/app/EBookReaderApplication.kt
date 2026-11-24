package com.artemis.ebookreader.app

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.artemis.ebookreader.data.dictionary.OfflineDictionary
import com.artemis.ebookreader.data.library.LibraryDatabase
import com.artemis.ebookreader.data.library.LibraryDatabaseName
import com.artemis.ebookreader.data.library.LibraryRepository
import com.artemis.ebookreader.data.preferences.ApplicationThemeSettings
import com.artemis.ebookreader.data.preferences.LibraryLayoutSettings
import com.artemis.ebookreader.data.preferences.ReaderAppearanceSettings
import com.artemis.ebookreader.data.preferences.readerAppearanceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class EBookReaderApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val readerAppearance by lazy { ReaderAppearanceSettings(readerAppearanceStore, scope) }
    val appAppearance by lazy { ApplicationThemeSettings(readerAppearanceStore, scope) }
    val libraryLayout by lazy { LibraryLayoutSettings(readerAppearanceStore, scope) }
    val offlineDictionary by lazy { OfflineDictionary(this) }
    val library by lazy {
        val database = Room.databaseBuilder(this, LibraryDatabase::class.java, LibraryDatabaseName).build()
        LibraryRepository(this, database.libraryDao())
    }

    override fun onCreate() {
        super.onCreate()
        readerAppearance
        scope.launch {
            try {
                library.recover()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("EBookReader", "Library recovery failed", error)
            }
        }
    }
}
