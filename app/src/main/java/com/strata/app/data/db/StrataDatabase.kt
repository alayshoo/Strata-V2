package com.strata.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.math.BigDecimal
import java.time.LocalDate

class Converters {
    @TypeConverter fun decimalToString(value: BigDecimal?): String? = value?.toPlainString()
    @TypeConverter fun stringToDecimal(value: String?): BigDecimal? = value?.let(::BigDecimal)
    @TypeConverter fun dateToEpochDay(value: LocalDate?): Long? = value?.toEpochDay()
    @TypeConverter fun epochDayToDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)
}

@Database(
    entities = [
        SourceEntity::class,
        AssetClassEntity::class,
        SpendingCategoryEntity::class,
        ProductEntity::class,
        SnapshotEntity::class,
        TransactionEntity::class,
        ImportEntity::class,
        FxRateEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        SettingEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StrataDatabase : RoomDatabase() {
    abstract fun setupDao(): SetupDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun fxDao(): FxDao
    abstract fun chatDao(): ChatDao
    abstract fun settingsDao(): SettingsDao
    abstract fun backupDao(): BackupDao

    companion object {
        const val FILE_NAME = "strata.db"

        /** Opens the SQLCipher-encrypted database. [passphrase] is zeroed by SQLCipher after use. */
        fun open(context: Context, passphrase: ByteArray): StrataDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, StrataDatabase::class.java, FILE_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }

        fun exists(context: Context): Boolean = context.getDatabasePath(FILE_NAME).exists()
    }
}
