package dev.openrune.tools

import dev.openrune.OsrsCacheProvider
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.cache.gameval.GameValHandler.elementAs
import dev.openrune.cache.gameval.impl.Table
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.definition.type.DBRowType
import dev.openrune.filesystem.Cache
import java.util.Locale

/**
 * Resolves native music rows by name. Music released after the gameval dump has no
 * `dbrow.music_*` symbol, and gamevals cannot alias an existing cache id.
 */
public object NativeMusicRows {
    private const val MUSIC_TABLE = "music"
    private const val SORT_NAME_COLUMN = 0
    private const val DISPLAY_NAME_COLUMN = 1

    public fun load(cache: Cache, revision: Int): (String) -> Int {
        val table =
            GameValHandler.readGameVal(GameValGroupTypes.TABLETYPES, cache = cache, revision)
                .firstNotNullOf { it.elementAs<Table>()?.takeIf { table -> table.name == MUSIC_TABLE } }

        val rows = mutableMapOf<Int, DBRowType>()
        OsrsCacheProvider.DBRowDecoder().load(cache, rows)

        val byName = mutableMapOf<String, Int>()
        for (row in rows.values) {
            if (row.tableId != table.id) continue
            val columns = row.definedColumns()
            for (slot in intArrayOf(SORT_NAME_COLUMN, DISPLAY_NAME_COLUMN)) {
                val name = columns[slot]?.values?.firstOrNull() as? String ?: continue
                byName.putIfAbsent(normalize(name), row.id)
            }
        }
        return { name ->
            byName[normalize(name)] ?: error("Missing native music row: '$name'")
        }
    }

    private fun normalize(name: String): String =
        name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
}
