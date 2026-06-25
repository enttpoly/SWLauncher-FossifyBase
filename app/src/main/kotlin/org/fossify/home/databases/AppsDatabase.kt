package com.sw.launcher.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.sw.launcher.helpers.Converters
import com.sw.launcher.interfaces.AppLaunchersDao
import com.sw.launcher.interfaces.HiddenIconsDao
import com.sw.launcher.interfaces.HomeScreenGridItemsDao
import com.sw.launcher.models.AppLauncher
import com.sw.launcher.models.HiddenIcon
import com.sw.launcher.models.HomeScreenGridItem

@Database(
    entities = [AppLauncher::class, HomeScreenGridItem::class, HiddenIcon::class],
    version = 5
)
@TypeConverters(Converters::class)
abstract class AppsDatabase : RoomDatabase() {

    abstract fun AppLaunchersDao(): AppLaunchersDao

    abstract fun HomeScreenGridItemsDao(): HomeScreenGridItemsDao

    abstract fun HiddenIconsDao(): HiddenIconsDao

    companion object {
        private var db: AppsDatabase? = null

        fun getInstance(context: Context): AppsDatabase {
            if (db == null) {
                synchronized(AppsDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(
                            context.applicationContext,
                            AppsDatabase::class.java,
                            "apps.db"
                        ).build()
                    }
                }
            }
            return db!!
        }
    }
}
