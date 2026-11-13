package cl.personal.finanzas

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Lector específico para el Excel binario (BIFF8/OLE2) de movimientos de débito
 * Banco Falabella: Fecha, Descripcion, Cargo, Abono, Saldo.
 * No interpreta estados de cuenta de CMR ni otros Excel desconocidos.
 */
object FalabellaDebitXlsReader {
    fun isBinaryXls(bytes: ByteArray): Boolean =
        bytes.size >= 512 && byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
        ).indices.all { bytes[it] == byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
        )[it] }

    fun read(bytes: ByteArray, account: String): List<Movement> {
        require(account == "Banco Falabella") {
            "Esta cartola es de débito Banco Falabella. Selecciona Banco Falabella (no CMR)."
        }
        require(isBinaryXls(bytes)) { "El archivo no es un XLS binario reconocido." }
        val workbook = extractWorkbook(bytes)
        return parseWorkbook(workbook, account)
    }

    private fun extractWorkbook(file: ByteArray): ByteArray {
        val sectorShift = file.u16(30)
        require(sectorShift == 9) { "Este Excel usa sectores no compatibles. Exporta un XLS estándar." }
        val sectorSize = 1 shl sectorShift
        val sectors = (file.size - 512) / sectorSize
        require(sectors > 0) { "Excel incompleto" }

        fun sector(id: Int): ByteArray {
            require(id in 0 until sectors) { "Sector XLS inválido" }
            val off = 512 + id * sectorSize
            return file.copyOfRange(off, off + sectorSize)
        }

        val fatSectorCount = file.i32(44)
        require(fatSectorCount in 1..109) { "Tabla XLS FAT no compatible" }
        val fat = ArrayList<Int>()
        for (index in 0 until fatSectorCount) {
            val id = file.i32(76 + index * 4)
            require(id >= 0) { "Sector FAT inválido" }
            val data = sector(id)
            for (position in 0 until sectorSize step 4) fat.add(data.i32(position))
        }

        fun readChain(start: Int, byteLimit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val visited = HashSet<Int>()
            var next = start
            while (next != -2) {
                require(next >= 0 && next < sectors && next < fat.size && visited.add(next)) {
                    "Cadena XLS dañada"
                }
                require(out.size() + sectorSize <= byteLimit + sectorSize) {
                    "Cadena XLS demasiado grande"
                }
                out.write(sector(next))
                next = fat[next]
            }
            return out.toByteArray()
        }

        val directory = readChain(file.i32(48), 16384)
        var workbookStart = -1
        var workbookSize = -1L
        for (off in 0 until directory.size - 127 step 128) {
            val length = directory.u16(off + 64)
            if (length < 2 || length > 64 || length % 2 != 0) continue
            val name = String(directory, off, length - 2, Charsets.UTF_16LE)
            if (directory.u8(off + 66) == 2 && (name == "Workbook" || name == "Book")) {
                workbookStart = directory.i32(off + 116)
                workbookSize = directory.u32(off + 120)
                break
            }
        }
        require(workbookStart >= 0 && workbookSize in 4096L..2_000_000L) {
            "No se encontró un libro XLS convencional. Este formato aún no está admitido."
        }
        return readChain(workbookStart, workbookSize.toInt()).copyOfRange(0, workbookSize.toInt())
    }

    private fun parseWorkbook(book: ByteArray, account: String): List<Movement> {
        var pos = 0
        var stringTable: List<String>? = null
        val rows = sortedMapOf<Int, MutableMap<Int, String>>()
        while (pos + 4 <= book.size) {
            val record = book.u16(pos)
            val size = book.u16(pos + 2)
            val start = pos + 4
            val end = start + size
            require(end <= book.size) { "Excel truncado" }
            when (record) {
                0x00FC -> stringTable = readStringTable(book, start, end)
                0x00FD -> {
                    require(size >= 10) { "Celda XLS inválida" }
                    val table = stringTable ?: error("Tabla de textos XLS no encontrada")
                    val row = book.u16(start)
                    val column = book.u16(start + 2)
                    val index = book.i32(start + 6)
                    require(row <= 5000 && column <= 10 && index in table.indices) {
                        "Índice de texto XLS inválido"
                    }
                    rows.getOrPut(row) { mutableMapOf() }[column] = table[index]
                }
            }
            pos = end
        }
        val header = rows[0] ?: error("La cartola no contiene encabezados")
        val expected = listOf("fecha", "descripcion", "cargo", "abono", "saldo")
        require(expected.indices.all { col ->
            header[col]?.trim()?.lowercase()?.replace("ó", "o") == expected[col]
        }) { "El XLS no tiene las columnas esperadas de Banco Falabella (Fecha, Descripcion, Cargo, Abono, Saldo)." }

        val dateFormat = DateTimeFormatter.ofPattern("d-M-uuuu")
        val result = mutableListOf<Movement>()
        rows.filterKeys { it > 0 }.forEach { (rowNumber, columns) ->
            val date = try { LocalDate.parse(columns[0]?.trim() ?: "", dateFormat) }
                catch (_: Exception) { error("Fila ${rowNumber + 1}: fecha inválida") }
            val description = (columns[1] ?: "").trim()
            require(description.isNotEmpty() && description.length <= 300) {
                "Fila ${rowNumber + 1}: descripción inválida"
            }
            val charge = parseClp(columns[2] ?: "")
            val credit = parseClp(columns[3] ?: "")
            require((charge > 0L) != (credit > 0L)) {
                "Fila ${rowNumber + 1}: debe tener un cargo o abono, no ambos"
            }
            val category = when {
                description.contains("PAGO TARJETA CMR", ignoreCase = true) ||
                    description.contains("PAGO TC", ignoreCase = true) ||
                    description.contains("DEUDA TARJETA CMR", ignoreCase = true) -> "Pago de tarjeta"
                description.contains("TRANSF", ignoreCase = true) -> "Transferencia"
                else -> "Importado"
            }
            result.add(Movement(
                account = account,
                description = description,
                amountClp = if (charge > 0) -charge else credit,
                date = date.toString(),
                category = category
            ))
        }
        require(result.isNotEmpty()) { "No se encontraron movimientos en el XLS" }
        return result
    }

    private fun parseClp(raw: String): Long {
        val value = raw.trim()
        if (value.isEmpty()) return 0L
        require(Regex("""^\$?\s*[0-9][0-9.,\s]*$""").matches(value)) {
            "Monto CLP inválido en la cartola"
        }
        return value.filter(Char::isDigit).toLongOrNull()
            ?: error("Monto CLP demasiado grande")
    }

    private fun readStringTable(book: ByteArray, start: Int, end: Int): List<String> {
        require(end - start >= 8) { "Tabla de textos XLS inválida" }
        val unique = book.i32(start + 4)
        require(unique in 1..20000) { "Tabla de textos XLS demasiado grande" }
        val values = ArrayList<String>(unique)
        var pos = start + 8
        repeat(unique) {
            require(pos + 3 <= end) { "Tabla de textos XLS fragmentada (no admitida)" }
            val characterCount = book.u16(pos)
            val flags = book.u8(pos + 2)
            pos += 3
            val richRuns = if (flags and 0x08 != 0) {
                require(pos + 2 <= end) { "Texto XLS inválido" }
                val n = book.u16(pos); pos += 2; n
            } else 0
            val extBytes = if (flags and 0x04 != 0) {
                require(pos + 4 <= end) { "Texto XLS inválido" }
                val n = book.i32(pos); pos += 4; n
            } else 0
            val byteCount = characterCount * (if (flags and 0x01 != 0) 2 else 1)
            require(byteCount >= 0 && extBytes >= 0 && pos + byteCount.toLong() + richRuns.toLong() * 4 + extBytes <= end) {
                "Tabla de textos XLS incompleta"
            }
            val charset = if (flags and 0x01 != 0) Charsets.UTF_16LE else Charset.forName("windows-1252")
            values.add(String(book, pos, byteCount, charset))
            pos += byteCount + richRuns * 4 + extBytes
        }
        return values
    }

    private fun ByteArray.u8(pos: Int) = this[pos].toInt() and 255
    private fun ByteArray.u16(pos: Int) = u8(pos) or (u8(pos + 1) shl 8)
    private fun ByteArray.i32(pos: Int) =
        u8(pos) or (u8(pos + 1) shl 8) or (u8(pos + 2) shl 16) or (u8(pos + 3) shl 24)
    private fun ByteArray.u32(pos: Int): Long = i32(pos).toLong() and 0xffffffffL
}
