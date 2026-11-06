package dev.caerbannog.desktopapp

import dev.caerbannog.core.browser.MessageBrowserViewModel
import dev.caerbannog.ui.streams.MessageBrowserEvents

/** Adapts [MessageBrowserViewModel] calls to the [MessageBrowserEvents] interface for Compose UI. */
class MessageBrowserEventsAdapter(private val vm: MessageBrowserViewModel) : MessageBrowserEvents {
    override fun browseFirst() = vm.browseFirst()
    override fun browseLastPage() = vm.browseLastPage()
    override fun browseOffset(offset: Long) = vm.browseOffset(offset)
    override fun browseTimestamp(epochMs: Long) = vm.browseTimestamp(epochMs)
    override fun nextPage() = vm.nextPage()
    override fun previousPage() = vm.previousPage()
    override fun jumpToOffset(offset: Long) = vm.jumpToOffset(offset)
    override fun jumpToNearestValidOffset() = vm.jumpToNearestValidOffset()
    override fun setPageSize(size: Int) = vm.setPageSize(size)
    override fun selectMessage(offset: Long?) = vm.selectMessage(offset)
    override fun filter(query: String) {
        val criteria = if (query.isBlank()) {
            dev.caerbannog.core.browser.MessageFilterCriteria.None
        } else if (query.contains("=")) {
            val parts = query.split("=", limit = 2)
            dev.caerbannog.core.browser.MessageFilterCriteria.ApplicationProperty(parts[0].trim(), parts[1].trim())
        } else {
            dev.caerbannog.core.browser.MessageFilterCriteria.Text(query.trim())
        }
        vm.filter(criteria)
    }
    override fun cancelScan() = vm.cancelScan()
    override fun clearFilter() = vm.clearFilter()
    override fun retry() = vm.browseFirst()
}
