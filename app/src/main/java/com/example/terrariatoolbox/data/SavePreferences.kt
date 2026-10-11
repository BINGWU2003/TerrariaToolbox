package com.example.terrariatoolbox.data

import com.example.terrariatoolbox.domain.GameEdition
import com.example.terrariatoolbox.domain.SaveTarget
import kotlinx.serialization.json.*
import java.io.File

/** Only remembers a destination; permission and account existence are checked again on connection. */
class SavePreferences(private val file: File, private val writer: AtomicWriter = AndroidAtomicWriter()) {
    fun read(): SaveTarget = runCatching {
        val value = parseObject(readAtomic(file))
        val edition = GameEdition.valueOf(value.string("edition"))
        val account = value.string("account").takeIf { it.matches(Regex("[0-9]+")) }.orEmpty()
        SaveTarget(edition, account)
    }.getOrDefault(SaveTarget(GameEdition.INTERNATIONAL))

    fun write(target: SaveTarget) {
        writer.write(file, buildJsonObject {
            put("edition", target.edition.name)
            put("account", target.account)
        }.bytes())
    }
}
