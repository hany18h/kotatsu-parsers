package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

/**
 * Parser لموقع Hijala.com
 * الموقع يستخدم WordPress (MangaReader theme)
 * 
 * ملاحظة مهمة: الموقع يستخدم WordPress وليس Blogger!
 * تأكد من أن المصدر مسجل بشكل صحيح
 */
@MangaSourceParser("HIJALACOM", "Hijala", "ar")
internal class Hijalacom(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.HIJALACOM, "hijala.com", pageSize = 20, searchPageSize = 10) {
	
	// الموقع يستخدم WordPress مع theme MangaReader
	override val listUrl = "/manga"
	
	// نمط التاريخ المستخدم في الموقع
	override val datePattern = "MMMM dd, yyyy"
	
	// تفعيل حماية الموقع إذا كانت موجودة
	override val isNetShieldProtected = false
	
	// الموقع لا يستخدم ترميز base64 للصور
	override val encodedSrc = false
}
