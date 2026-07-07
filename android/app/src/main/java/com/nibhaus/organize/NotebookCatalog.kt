package com.nibhaus.organize

/**
 * Maps an Ncode `book` id to a human title, so auto-created notebooks get a
 * meaningful name instead of "Book 603". Neo sells specific notebook products
 * keyed by book id; this is a small, extensible lookup with a sensible fallback.
 */
class NotebookCatalog(private val titles: Map<Int, String>) {

    fun titleFor(book: Int): String = titles[book] ?: "Notebook $book"

    companion object {
        // A starter set — real book ids would be filled in from Neo's product list.
        val DEFAULT = NotebookCatalog(
            mapOf(
                601 to "N idea pad",
                603 to "Professional notebook",
                605 to "College note",
                611 to "Pocket note",
                613 to "Plain note",
            ),
        )
    }
}
