@MangaSourceParser("MANGALINKNET", "Link-Manga.com", "ar")
internal class MangaLinkNet(context: MangaLoaderContext) :
    MadaraParser(
        context,
        MangaParserSource.MANGALINKNET,
        "link-manga.com",
        pageSize = 10
    ) {

    override val iconUrl =
        "https://raw.githubusercontent.com/hany18h/kotatsu-parsers/master/src/main/kotlin/org/koitharu/kotatsu/parsers/icons/MangaLink.webp"
}
