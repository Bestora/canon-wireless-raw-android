package de.bestora.canonwirelessrawandroid.data

import de.bestora.canonwirelessrawandroid.ptp.CameraObject
import de.bestora.canonwirelessrawandroid.ptp.kindFromName
import java.io.File

/**
 * The camera's object list (name, size, date per handle) per camera, so a reconnect shows the
 * whole gallery at once instead of re-asking 10 000+ GetObjectInfo. Folders and videos are kept
 * too, or they would be asked for again on every connect.
 */
class ObjectListCache(private val dir: File) {

    fun load(camera: String): List<CameraObject> {
        val file = file(camera)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 5) return@mapNotNull null
            CameraObject(
                handle = f[0].toLongOrNull(16)?.toInt() ?: return@mapNotNull null,
                name = f[1],
                size = f[2].toLongOrNull() ?: return@mapNotNull null,
                takenAtMillis = f[3].toLongOrNull() ?: 0L,
                kind = kindFromName(f[1]),
                format = f[4].toIntOrNull() ?: 0,
            )
        }
    }

    fun save(camera: String, objects: Collection<CameraObject>) {
        dir.mkdirs()
        val tmp = File(dir, "${file(camera).name}.tmp")
        tmp.writeText(objects.joinToString("\n") {
            "%08x\t%s\t%d\t%d\t%d".format(it.handle, it.name.replace('\t', ' ').replace('\n', ' '), it.size, it.takenAtMillis, it.format)
        })
        tmp.renameTo(file(camera))
    }

    private fun file(camera: String) = File(dir, camera.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".tsv")
}
