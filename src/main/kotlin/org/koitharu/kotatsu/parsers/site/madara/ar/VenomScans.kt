package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("VENOMSCANS", "Venom Scans", "ar")
internal class VenomScans(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.VENOMSCANS, "venomscans.one") {

	// افتراضياً بنعتمد على ajax (زي أغلب مواقع مادارا)
	override val withoutAjax = false

	// نمط التاريخ شائع في مواقع مادارا العربي، محتاج تأكيد فعلي من صفحة فصل حقيقية
	override val datePattern = "d MMMM، yyyy"
}
