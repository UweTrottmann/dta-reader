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

import okio.BufferedSource
import okio.IOException
import okio.buffer
import okio.source
import java.io.InputStream
import java.net.URL
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
                // Byte [4:7]: size of field definitions
                val fieldDefSize = bufferedSource.readIntLe()

                var readFieldDefBytes = 0
                // Byte [8:9]: number of data sets
                val datasetsToRead = bufferedSource.readShortLe()
                readFieldDefBytes += 2
                // Byte [10:11]: length of a data set
                val datasetLength = bufferedSource.readShortLe()
                readFieldDefBytes += 2

                val fields = mutableListOf<AnalogueDataField>()
                val digitalFields = mutableListOf<DigitalDataField>()
                var category = ""

                val fieldBytes = ByteArray(fieldDefSize - 4)
                val bytesRead = bufferedSource.read(fieldBytes)
                if (bytesRead == -1 || bytesRead != fieldBytes.size) {
                    throw IOException("Does not contain expected size of field definitions")
                }

                // FIXME Length not correct, about 20 bytes too short
                while (readFieldDefBytes < fieldDefSize) {
                    val fieldId = bufferedSource.readByte()
                    val fieldType = fieldId and 0x0F
                    when (fieldType) {
                        0x00.toByte() -> {
                            // Category
                            category = readString(bufferedSource)
                            readFieldDefBytes += category.length + 1 // 0 terminator
                        }
                        0x01.toByte() -> {
                            // Analogue field
                            val name = readString(bufferedSource)
                            readFieldDefBytes += name.length + 1 // 0 terminator

                            val color = readColor(bufferedSource)
                            readFieldDefBytes += 3

                            val factor = if (fieldId and 0x80.toByte() != 0x0.toByte()) {
                                readFieldDefBytes += 2
                                bufferedSource.readShortLe()
                            } else 10
                            fields.add(AnalogueDataField(category, name, color, factor))
                        }
                        0x02.toByte(), 0x04.toByte() -> {
                            // Digital field
                            val count = bufferedSource.readByte()
                            readFieldDefBytes += 1

                            val visibility = if (fieldId and 0x40.toByte() != 0x0.toByte()) {
                                readFieldDefBytes += 2
                                bufferedSource.readShortLe()
                            } else 0xFFFF.toShort()

                            val factoryOnlyAll = if (fieldId and 0x20.toByte() != 0x0.toByte()) {
                                readFieldDefBytes += 2
                                bufferedSource.readShortLe()
                            } else 0xFFFF.toShort()

                            val inOutSeparate = if (fieldId and 0x04.toByte() != 0x0.toByte()) {
                                readFieldDefBytes += 2
                                bufferedSource.readShortLe()
                            } else if (fieldId and 0x80.toByte() != 0x0.toByte()) {
                                0xFFFF.toShort()
                            } else 0x0.toShort()

                            val items = mutableListOf<DigitalDataFieldItem>()
                            for (i in 0 until count) {
                                val name = readString(bufferedSource)
                                readFieldDefBytes += name.length + 1 // 0 terminator

                                val color = readColor(bufferedSource)
                                readFieldDefBytes += 3

                                items.add(DigitalDataFieldItem(category, name, color))
                            }
                            digitalFields.add(DigitalDataField(items))
                        }
                        0x03.toByte() -> {
                            // Enum field
                            val name = readString(bufferedSource)
                            readFieldDefBytes += name.length + 1 // 0 terminator
                            val count = bufferedSource.readByte()
                            readFieldDefBytes += 1

                            val enumValues = mutableListOf<String>()
                            for (i in 0 until count) {
                                val enumValue = readString(bufferedSource)
                                enumValues.add(enumValue)
                                readFieldDefBytes += enumValue.length + 1 // 0 terminator
                            }
                        }
                        else -> {
                            println("WARNING Unknown field type $fieldType")
//                            throw IllegalArgumentException("Unknown field type $fieldType")
                            return DtaFile(
                                version,
                                fieldDefSize,
                                datasetsToRead,
                                datasetLength,
                                fields,
                                digitalFields
                            )
                        }
                    }
                }

                // Check number of fields * 2 (length of value) == data set length

                return DtaFile(
                    version,
                    fieldDefSize,
                    datasetsToRead,
                    datasetLength,
                    fields,
                    digitalFields
                )
            }
        }
    }

    private fun readString(bufferedSource: BufferedSource): String {
        var string = ""
        // FIXME Prevent endless loop
        while (true) {
            val char = bufferedSource.readByte()
            if (char == 0x0.toByte()) {
                break
            } else {
                string += char.toInt().toChar()
            }
        }
        return string
    }

    private fun readColor(bufferedSource: BufferedSource): Int {
        val r = bufferedSource.readByte().toLong() // FIXME
        val g = bufferedSource.readByte().toLong()
        val b = bufferedSource.readByte().toLong()
        return (0xFF000000 or r shl 16 or g shl 8 or b).toInt()
    }

}