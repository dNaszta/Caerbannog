package dev.caerbannog.ui.streams

/** Events out of the message browser view (M3-03). */
interface MessageBrowserEvents {
    fun browseFirst()
    fun browseLastPage()
    fun browseOffset(offset: Long)
    fun browseTimestamp(epochMs: Long)
    fun nextPage()
    fun previousPage()
    fun jumpToOffset(offset: Long)
    fun jumpToNearestValidOffset()
    fun setPageSize(size: Int)
    fun selectMessage(offset: Long?)
    fun filter(query: String)
    fun cancelScan()
    fun clearFilter()
    fun retry()
}

object EmptyMessageBrowserEvents : MessageBrowserEvents {
    override fun browseFirst() {}
    override fun browseLastPage() {}
    override fun browseOffset(offset: Long) {}
    override fun browseTimestamp(epochMs: Long) {}
    override fun nextPage() {}
    override fun previousPage() {}
    override fun jumpToOffset(offset: Long) {}
    override fun jumpToNearestValidOffset() {}
    override fun setPageSize(size: Int) {}
    override fun selectMessage(offset: Long?) {}
    override fun filter(query: String) {}
    override fun cancelScan() {}
    override fun clearFilter() {}
    override fun retry() {}
}
