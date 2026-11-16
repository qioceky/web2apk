package id.web2apk.offline

import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Salin APK sambil mengganti isi entri tertentu, membuang berkas tanda
 * tangan lama, dan menyelaraskan entri STORED (zipalign):
 * umum ke 4 byte, .so ke 4096 byte. Porting dari ZipPatch.java yang
 * sudah terverifikasi di JVM (zipalign -c + apksigner lulus).
 */
object ZipPatch {

    private class Item(val name: String, val method: Int, val data: ByteArray)

    fun patch(input: File, output: File, replacements: Map<String, ByteArray>) {
        val items = mutableListOf<Item>()
        val seen = mutableSetOf<String>()
        ZipFile(input).use { zf ->
            val en = zf.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (isSignatureFile(e.name)) continue
                var data = zf.getInputStream(e).use { it.readBytes() }
                replacements[e.name]?.let { data = it }
                items.add(Item(e.name, e.method, data))
                seen.add(e.name)
            }
        }
        for ((name, data) in replacements) {
            if (name !in seen) {
                val method = if (name == "resources.arsc" || name.endsWith(".so"))
                    ZipEntry.STORED else ZipEntry.DEFLATED
                items.add(Item(name, method, data))
            }
        }

        val cos = CountingOutputStream(FileOutputStream(output))
        ZipOutputStream(cos).use { zos ->
            for (item in items) {
                val e = ZipEntry(item.name)
                e.method = item.method
                if (item.method == ZipEntry.STORED) {
                    val crc = CRC32(); crc.update(item.data)
                    e.crc = crc.value
                    e.size = item.data.size.toLong()
                    e.compressedSize = item.data.size.toLong()
                    val align = if (item.name.endsWith(".so")) 4096 else 4
                    val nameLen = item.name.toByteArray(Charsets.UTF_8).size
                    val base = (cos.count + 30 + nameLen).toInt()
                    var pad = (align - (base % align)) % align
                    if (pad in 1..3) pad += align
                    if (pad > 0) {
                        val extra = ByteArray(pad)
                        extra[0] = 0x35.toByte()
                        extra[1] = 0xD9.toByte()
                        val sz = pad - 4
                        extra[2] = (sz and 0xFF).toByte()
                        extra[3] = ((sz shr 8) and 0xFF).toByte()
                        e.extra = extra
                    }
                }
                zos.putNextEntry(e)
                zos.write(item.data)
                zos.closeEntry()
            }
        }
    }

    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val up = name.uppercase()
        return up.endsWith(".SF") || up.endsWith(".RSA") || up.endsWith(".DSA") ||
            up.endsWith(".EC") || up == "META-INF/MANIFEST.MF" || up.contains("SIG-")
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0
        override fun write(b: Int) { super.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { super.write(b, off, len); count += len }
    }
}
