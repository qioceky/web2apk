package id.web2apk.offline

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Salin APK sambil mengganti isi entri tertentu + membuang berkas tanda
 * tangan lama, dengan PENULIS ZIP MANUAL (tanpa ZipOutputStream).
 *
 * Kenapa manual: ZipOutputStream di ART (Android) memperlakukan extra
 * field & buffering berbeda dari JVM — terbukti di lapangan (v0.4.1):
 * ~110 entri STORED (termasuk resources.arsc) meleset alignment 1–3 byte
 * sehingga zipalign gagal dan installer menolak APK ("App not installed").
 * Di sini semua offset dihitung aritmetika dan ditulis byte-per-byte:
 * flags hanya UTF-8 (tanpa data descriptor), entri STORED disejajarkan
 * 4 byte (.so 4096) via extra field 0xD935 yang ditulis verbatim.
 * Algoritma identik terverifikasi di JVM: zipalign -c lulus sebelum &
 * sesudah penandatanganan apksig.
 */
object ZipPatch {

    private class Item(val name: String, val method: Int, val data: ByteArray)

    private class CdEntry(
        val name: String, val method: Int, val crc: Long,
        val compSize: Int, val size: Int, val lho: Long, val dir: Boolean
    )

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
        require(items.size <= 0xFFFF) { "Terlalu banyak entri ZIP" }

        val cd = mutableListOf<CdEntry>()
        var offset = 0L
        BufferedOutputStream(FileOutputStream(output)).use { out ->
            for (item in items) {
                val nameBytes = item.name.toByteArray(Charsets.UTF_8)
                val dir = item.name.endsWith("/")
                val crc32 = CRC32(); crc32.update(item.data)
                val method = if (dir) ZipEntry.STORED else item.method
                val payload = if (method == ZipEntry.DEFLATED) deflate(item.data) else item.data

                val align = if (method == ZipEntry.STORED && !dir)
                    (if (item.name.endsWith(".so")) 4096 else 4) else 1
                var extra = ByteArray(0)
                if (align > 1) {
                    val base = offset + 30 + nameBytes.size
                    var pad = ((align - (base % align)) % align).toInt()
                    if (pad in 1..3) pad += align
                    if (pad > 0) {
                        extra = ByteArray(pad)
                        extra[0] = 0x35.toByte()
                        extra[1] = 0xD9.toByte()
                        val sz = pad - 4
                        extra[2] = (sz and 0xFF).toByte()
                        extra[3] = ((sz shr 8) and 0xFF).toByte()
                    }
                }

                cd.add(CdEntry(item.name, method, crc32.value, payload.size, item.data.size, offset, dir))
                out.writeIntLE(0x04034b50)
                out.writeShortLE(if (method == ZipEntry.DEFLATED) 20 else 10)
                out.writeShortLE(0x0800)
                out.writeShortLE(method)
                out.writeShortLE(0)      // mod time
                out.writeShortLE(0x21)   // mod date 1980-01-01
                out.writeIntLE(crc32.value)
                out.writeIntLE(payload.size.toLong())
                out.writeIntLE(item.data.size.toLong())
                out.writeShortLE(nameBytes.size)
                out.writeShortLE(extra.size)
                out.write(nameBytes)
                out.write(extra)
                out.write(payload)
                offset += 30 + nameBytes.size + extra.size + payload.size
            }
            val cdStart = offset
            for (c in cd) {
                val nameBytes = c.name.toByteArray(Charsets.UTF_8)
                out.writeIntLE(0x02014b50)
                out.writeShortLE(0x0314) // version made by: Unix, 2.0
                out.writeShortLE(if (c.method == ZipEntry.DEFLATED) 20 else 10)
                out.writeShortLE(0x0800)
                out.writeShortLE(c.method)
                out.writeShortLE(0)
                out.writeShortLE(0x21)
                out.writeIntLE(c.crc)
                out.writeIntLE(c.compSize.toLong())
                out.writeIntLE(c.size.toLong())
                out.writeShortLE(nameBytes.size)
                out.writeShortLE(0) // extra CD
                out.writeShortLE(0) // comment
                out.writeShortLE(0) // disk
                out.writeShortLE(0) // internal attrs
                out.writeIntLE((if (c.dir) 0x41EDL else 0x81A4L) shl 16)
                out.writeIntLE(c.lho)
                out.write(nameBytes)
                offset += 46 + nameBytes.size
            }
            out.writeIntLE(0x06054b50)
            out.writeShortLE(0); out.writeShortLE(0)
            out.writeShortLE(cd.size); out.writeShortLE(cd.size)
            out.writeIntLE(offset - cdStart)
            out.writeIntLE(cdStart)
            out.writeShortLE(0)
        }
    }

    /**
     * Periksa alignment semua entri STORED pada APK jadi. Mengembalikan
     * nama entri pertama yang meleset, atau null bila semua sejajar.
     * Dipakai OfflineBuilder sebagai self-check sebelum APK ditawarkan
     * ke pemasang — kegagalan platform apa pun berubah jadi pesan error
     * yang jelas, bukan APK rusak yang ditolak installer.
     */
    fun checkAlignment(apk: File): String? {
        val d = apk.readBytes()
        fun u16(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = (d[o].toLong() and 0xFF) or ((d[o + 1].toLong() and 0xFF) shl 8) or
            ((d[o + 2].toLong() and 0xFF) shl 16) or ((d[o + 3].toLong() and 0xFF) shl 24)
        var eocd = -1
        var i = d.size - 22
        while (i >= 0 && i >= d.size - 22 - 65536) {
            if (u32(i) == 0x06054b50L) { eocd = i; break }
            i--
        }
        if (eocd < 0) return "(EOCD tidak ditemukan)"
        var off = u32(eocd + 16).toInt()
        val count = u16(eocd + 10)
        repeat(count) {
            if (u32(off) != 0x02014b50L) return "(CD rusak)"
            val method = u16(off + 10)
            val nl = u16(off + 28); val el = u16(off + 30); val cl = u16(off + 32)
            val lho = u32(off + 42).toInt()
            val name = String(d, off + 46, nl, Charsets.UTF_8)
            if (method == 0) {
                val lnl = u16(lho + 26); val lel = u16(lho + 28)
                val dataOff = lho + 30 + lnl + lel
                val need = if (name.endsWith(".so")) 4096 else 4
                if (dataOff % need != 0) return name
            }
            off += 46 + nl + el + cl
        }
        return null
    }

    private fun deflate(data: ByteArray): ByteArray {
        val def = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        def.setInput(data); def.finish()
        val bos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!def.finished()) {
            val n = def.deflate(buf)
            bos.write(buf, 0, n)
        }
        def.end()
        return bos.toByteArray()
    }

    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val up = name.uppercase()
        return up.endsWith(".SF") || up.endsWith(".RSA") || up.endsWith(".DSA") ||
            up.endsWith(".EC") || up == "META-INF/MANIFEST.MF" || up.contains("SIG-")
    }

    private fun OutputStream.writeShortLE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }

    private fun OutputStream.writeIntLE(v: Long) {
        write((v and 0xFF).toInt()); write(((v shr 8) and 0xFF).toInt())
        write(((v shr 16) and 0xFF).toInt()); write(((v shr 24) and 0xFF).toInt())
    }
}
