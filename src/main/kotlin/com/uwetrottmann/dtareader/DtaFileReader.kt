/*
 * Copyright 2022 Uwe Trottmann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uwetrottmann.dtareader

import okio.IOException
import okio.buffer
import okio.source
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.experimental.and

/**
 * Based upon https://sourceforge.net/p/opendta/git/ci/master/tree/dtafile/dtafile9003.cpp
 */
class DtaFileReader {

    data class DtaFile(
        val version: Int,
        val fieldDefSize: Int,
        val datasetsToRead: Short,
        val datasetLength: Short,
        val analogueFields: List<AnalogueDataField>,
        val digitalFields: List<DigitalDataField>
    )

    data class AnalogueDataField(
        val category: String,
        val name: String,
        val color: Int,
        val factor: Short
    )

    data class DigitalDataField(
        val items: List<DigitalDataFieldItem>
    )

    data class DigitalDataFieldItem(
        val category: String,
        val name: String,
        val color: Int
    )

    fun getLoggerFileStream(host: String): InputStream? {
        val url = URL("http://$host/NewProc")
        return url.openConnection().getInputStream()
    }

    fun readLoggerFile(inputStream: InputStream): DtaFile {
        inputStream.source().use { source ->
            source.buffer().use { bufferedSource ->
                // Byte [0:3]: version
                val version = bufferedSource.readIntLe()
                if (version != VERSION_9003) {
                    throw IOException("Version is not $VERSION_9003")
                }

                // Byte [4:7]: size of header
                val headerSize = bufferedSource.readIntLe()
                val headerBytes = ByteArray(headerSize)
                val bytesRead = bufferedSource.read(headerBytes)
                if (bytesRead == -1 || bytesRead != headerBytes.size) {
                    throw IOException("Header is not $headerSize bytes long")
                }
                val headerBuffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

                // Byte [8:9]: number of data sets
                val datasetsToRead = headerBuffer.short
                // Byte [10:11]: length of a data set
                val datasetLength = headerBuffer.short

                val fields = mutableListOf<AnalogueDataField>()
                val digitalFields = mutableListOf<DigitalDataField>()
                var category = ""
                while (headerBuffer.hasRemaining()) {
                    val fieldId = headerBuffer.get()
                    when (val fieldType = fieldId and 0x0F) {
                        0x00.toByte() -> {
                            // Category
                            category = readString(headerBuffer)
                        }
                        0x01.toByte() -> {
                            // Analogue field
                            val name = readString(headerBuffer)
                            val color = readColor(headerBuffer)
                            val factor = if (fieldId and 0x80.toByte() != 0x0.toByte()) {
                                headerBuffer.short
                            } else 10
                            fields.add(AnalogueDataField(category, name, color, factor))
                        }
                        0x02.toByte(), 0x04.toByte() -> {
                            // Digital field
                            val count = headerBuffer.get()
                            val visibility = if (fieldId and 0x40.toByte() != 0x0.toByte()) {
                                headerBuffer.short
                            } else 0xFFFF.toShort()

                            val factoryOnlyAll = if (fieldId and 0x20.toByte() != 0x0.toByte()) {
                                headerBuffer.short
                            } else 0xFFFF.toShort()

                            val inOutSeparate = if (fieldId and 0x04.toByte() != 0x0.toByte()) {
                                headerBuffer.short
                            } else if (fieldId and 0x80.toByte() != 0x0.toByte()) {
                                0xFFFF.toShort()
                            } else 0x0.toShort()

                            val items = mutableListOf<DigitalDataFieldItem>()
                            for (i in 0 until count) {
                                val name = readString(headerBuffer)

                                val color = readColor(headerBuffer)

                                items.add(DigitalDataFieldItem(category, name, color))
                            }
                            digitalFields.add(DigitalDataField(items))
                        }
                        0x03.toByte() -> {
                            // Enum field
                            val name = readString(headerBuffer)
                            val count = headerBuffer.get()

                            val enumValues = mutableListOf<String>()
                            for (i in 0 until count) {
                                val enumValue = readString(headerBuffer)
                                enumValues.add(enumValue)
                            }
                        }
                        else -> throw IOException("Unknown field type $fieldType")
                    }
                }

                // Check number of fields * 2 (length of value) == data set length

                return DtaFile(
                    version,
                    headerSize,
                    datasetsToRead,
                    datasetLength,
                    fields,
                    digitalFields
                )
            }
        }
    }

    private fun readString(buffer: ByteBuffer): String {
        var string = ""
        while (true) {
            val char = buffer.get()
            if (char == 0x0.toByte()) {
                break
            } else {
                string += char.toInt().toChar()
            }
        }
        return string
    }

    private fun readColor(buffer: ByteBuffer): Int {
        val r = buffer.get().toLong()
        val g = buffer.get().toLong()
        val b = buffer.get().toLong()
        return (0xFF000000 or r shl 16 or g shl 8 or b).toInt()
    }

    companion object {
        const val VERSION_9003 = 9003
    }
}