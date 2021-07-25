/*  Copyright (c) MediaArea.net SARL. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license that can
 *  be found in the License.html file in the root of the source tree.
 */

package net.mediaarea.mediainfo

import android.os.ParcelFileDescriptor
import java.io.File

object Core {
    data class ReportView(val name: String, val desc: String, val mime: String, val exportable: Boolean) {
        override fun toString(): String {
            return desc
        }
    }

    val ARG_REPORT_ID = "id"
    val mi: MediaInfo = MediaInfo()
    val views: MutableList<ReportView> = mutableListOf()
    val version: String = mi.Option("Info_Version").replace("MediaInfoLib - v", "")
    var language: String =  ""

    init {
        // populate views list
        val viewsCsv: String = mi.Option("Info_OutputFormats_CSV")
        viewsCsv.split("\n").forEach {
            val view: List<String> = it.split(",")
            if (view.size > 2)
                views.add(ReportView(view[0], view[1], view[2], true))
        }
    }

    fun setLocale(locale: String) {
        language = locale
    }

    fun creatReport(path: String): String {
        var fd: ParcelFileDescriptor? = null

        val file = File(path)
        try {
            fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
        }

        var name = path.replaceFirst("[/\\]+$", "").split("[/\\]+")
        var displayName = name.last()

        if (fd == null) {
            return "";
        }

        mi.Option("Language", "")

        mi.Open(fd.detachFd(), displayName)
        val report: String = mi.Inform()
        mi.Close()

        return report
    }

    fun createReport(fd: Int, name: String): ByteArray {
        mi.Option("Language", "")
        mi.Option("Inform", "MIXML")
        mi.Option("Input_Compressed", "")
        mi.Option("Inform_Compress", "zlib+base64")

        mi.Open(fd, name)
        val report: String =  mi.Inform()
        mi.Close()

        return report.toByteArray()
    }

    fun convertReport(report: ByteArray, format: String, export: Boolean = false) : String {
        mi.Option("Inform", format)
        mi.Option("Inform_Compress", "")
        mi.Option("Input_Compressed", "zlib+base64")

        if (format == "Text" && !export) {
            if (language.isNotEmpty()) {
                mi.Option("Language", language.replace("  Config_Text_ColumnSize;40", "  Config_Text_ColumnSize;25"))
            }
            else {
                mi.Option("Language", "  Config_Text_ColumnSize;25")
            }
        }
        else {
            if (language.isNotEmpty()) {
                mi.Option("Language", language)
            }
            else {
                mi.Option("Language", "")
            }
        }

        mi.Open_Buffer_Init(report.size.toLong(), 0L)
        mi.Open_Buffer_Continue(report, report.size.toLong())
        mi.Open_Buffer_Finalize()
        val output: String = mi.Inform()
        mi.Close()

        return output
    }
}
