package com.topjohnwu.magisk.ui.module

import androidx.databinding.Bindable
import androidx.databinding.ObservableArrayList
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.download.RemoteFileService
import com.topjohnwu.magisk.core.model.module.Module
import com.topjohnwu.magisk.core.tasks.RepoUpdater
import com.topjohnwu.magisk.data.database.RepoByNameDao
import com.topjohnwu.magisk.data.database.RepoByUpdatedDao
import com.topjohnwu.magisk.databinding.RvItem
import com.topjohnwu.magisk.ktx.addOnListChangedCallback
import com.topjohnwu.magisk.ktx.reboot
import com.topjohnwu.magisk.model.entity.internal.DownloadSubject
import com.topjohnwu.magisk.model.entity.recycler.InstallModule
import com.topjohnwu.magisk.model.entity.recycler.ModuleItem
import com.topjohnwu.magisk.model.entity.recycler.RepoItem
import com.topjohnwu.magisk.model.entity.recycler.SectionTitle
import com.topjohnwu.magisk.model.events.InstallExternalModuleEvent
import com.topjohnwu.magisk.model.events.OpenChangelogEvent
import com.topjohnwu.magisk.model.events.dialog.ModuleInstallDialog
import com.topjohnwu.magisk.ui.base.*
import com.topjohnwu.magisk.utils.EndlessRecyclerScrollListener
import com.topjohnwu.magisk.utils.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.tatarka.bindingcollectionadapter2.collections.MergeObservableList
import kotlin.math.roundToInt

/*
* The repo fetching behavior should follow these rules:
*
* For the first time the repo list is queried in the app, it should ALWAYS fetch for
* updates. However, this particular fetch should go through RepoUpdater.invoke(false),
* which internally will set ETAGs when doing GET requests to GitHub's API and will
* only update repo DB only if the GitHub API shows that something is changed remotely.
*
* When a user explicitly requests a full DB refresh, it should ALWAYS do a full force
* refresh, which in code can be done with RepoUpdater.invoke(true). This will update
* every single repo's information regardless whether GitHub's API shows if there is
* anything changed or not.
* */

class ModuleViewModel(
    private val repoName: RepoByNameDao,
    private val repoUpdated: RepoByUpdatedDao,
    private val repoUpdater: RepoUpdater
) : BaseViewModel(), Queryable, Observer<Pair<Float, DownloadSubject>> {

    override val queryDelay = 1000L
    private var queryJob: Job? = null
    private var remoteJob: Job? = null

    @get:Bindable
    var isRemoteLoading = false
        set(value) = set(value, field, { field = it }, BR.remoteLoading)

    @get:Bindable
    var query = ""
        set(value) = set(value, field, { field = it }, BR.query) {
            submitQuery()
            // Yes we do lie about the search being loaded
            searchLoading = true
        }

    @get:Bindable
    var searchLoading = false
        set(value) = set(value, field, { field = it }, BR.searchLoading)

    val itemsSearch = diffListOf<RepoItem>()
    val itemSearchBinding = itemBindingOf<RepoItem> {
        it.bindExtra(BR.viewModel, this)
    }

    private val installSectionList = ObservableArrayList<RvItem>()
    private val updatableSectionList = ObservableArrayList<RvItem>()

    private val itemsInstalled = diffListOf<ModuleItem>()
    private val itemsUpdatable = diffListOf<RepoItem.Update>()
    private val itemsRemote = diffListOf<RepoItem.Remote>()

    private val sectionUpdate = SectionTitle(
        R.string.module_section_pending,
        R.string.module_section_pending_action,
        R.drawable.ic_update_md2
        // enable with implementation of https://github.com/topjohnwu/Magisk/issues/2036
    ).also { it.hasButton = false }

    private val sectionInstalled = SectionTitle(
        R.string.module_installed,
        R.string.reboot,
        R.drawable.ic_restart
    ).also { it.hasButton = false }

    private val sectionRemote = SectionTitle(
        R.string.module_section_remote,
        R.string.sorting_order
    ).apply { updateOrderIcon() }

    val adapter = adapterOf<RvItem>()
    val items = MergeObservableList<RvItem>()
        .insertItem(InstallModule)
        .insertList(updatableSectionList)
        .insertList(itemsUpdatable)
        .insertList(installSectionList)
        .insertList(itemsInstalled)
        .insertItem(sectionRemote)
        .insertList(itemsRemote)!!
    val itemBinding = itemBindingOf<RvItem> {
        it.bindExtra(BR.viewModel, this)
    }

    // ---

    private var refetch = false
    private val dao
        get() = when (Config.repoOrder) {
            Config.Value.ORDER_DATE -> repoUpdated
            Config.Value.ORDER_NAME -> repoName
            else -> throw IllegalArgumentException()
        }

    // ---

    init {
        RemoteFileService.reset()
        RemoteFileService.progressBroadcast.observeForever(this)

        itemsInstalled.addOnListChangedCallback(
            onItemRangeInserted = { _, _, _ ->
                if (installSectionList.isEmpty())
                    installSectionList.add(sectionInstalled)
            },
            onItemRangeRemoved = { list, _, _ ->
                if (list.isEmpty())
                    installSectionList.clear()
            }
        )
        itemsUpdatable.addOnListChangedCallback(
            onItemRangeInserted = { _, _, _ ->
                if (updatableSectionList.isEmpty())
                    updatableSectionList.add(sectionUpdate)
            },
            onItemRangeRemoved = { list, _, _ ->
                if (list.isEmpty())
                    updatableSectionList.clear()
            }
        )
    }

    // ---

    override fun onCleared() {
        super.onCleared()
        RemoteFileService.progressBroadcast.removeObserver(this)
    }

    override fun onChanged(it: Pair<Float, DownloadSubject>?) {
        val (progress, subject) = it ?: return
        if (subject !is DownloadSubject.Module)
            return

        viewModelScope.launch {
            val items = withContext(Dispatchers.Default) {
                val predicate = { it: RepoItem -> it.item.id == subject.module.id }
                itemsUpdatable.filter(predicate) +
                        itemsRemote.filter(predicate) +
                        itemsSearch.filter(predicate)
            }
            items.forEach { it.progress = progress.times(100).roundToInt() }
        }
    }

    override fun refresh(): Job {
        return viewModelScope.launch {
            state = State.LOADING
            loadInstalled()
            if (itemsRemote.isEmpty())
                loadRemote()
            state = State.LOADED
        }
    }

    private fun SectionTitle.updateOrderIcon() {
        hasButton = true
        icon = when (Config.repoOrder) {
            Config.Value.ORDER_NAME -> R.drawable.ic_order_name
            Config.Value.ORDER_DATE -> R.drawable.ic_order_date
            else -> return
        }
    }

    private suspend fun loadInstalled() {
        val installed = Module.installed().map { ModuleItem(it) }
        val diff = withContext(Dispatchers.Default) {
            itemsInstalled.calculateDiff(installed)
        }
        itemsInstalled.update(installed, diff)
    }

    private suspend fun loadUpdatable() {
        val (updates, diff) = withContext(Dispatchers.IO) {
            itemsInstalled.forEach {
                launch {
                    it.repo = dao.getRepoById(it.item.id)
                }
            }
            val updates = itemsInstalled
                .mapNotNull { dao.getUpdatableRepoById(it.item.id, it.item.versionCode) }
                .map { RepoItem.Update(it) }
            val diff = itemsUpdatable.calculateDiff(updates)
            return@withContext updates to diff
        }
        itemsUpdatable.update(updates, diff)
    }

    fun loadRemote() {
        // check for existing jobs
        if (remoteJob?.isActive == true)
            return

        if (itemsRemote.isEmpty())
            EndlessRecyclerScrollListener.ResetState().publish()

        remoteJob = viewModelScope.launch {
            suspend fun loadRemoteDB(offset: Int) = withContext(Dispatchers.IO) {
                dao.getRepos(offset).map { RepoItem.Remote(it) }
            }

            isRemoteLoading = true
            val repos = if (itemsRemote.isEmpty()) {
                repoUpdater.run(refetch)
                loadUpdatable()
                loadRemoteDB(0)
            } else {
                loadRemoteDB(itemsRemote.size)
            }
            isRemoteLoading = false
            refetch = false
            queryHandler.post { itemsRemote.addAll(repos) }
        }
    }

    fun forceRefresh() {
        itemsRemote.clear()
        itemsUpdatable.clear()
        itemsSearch.clear()
        refetch = true
        refresh()
        submitQuery()
    }

    // ---

    private suspend fun queryInternal(query: String, offset: Int): List<RepoItem> {
        return if (query.isBlank()) {
            itemsSearch.clear()
            listOf()
        } else {
            withContext(Dispatchers.IO) {
                dao.searchRepos(query, offset).map { RepoItem.Remote(it) }
            }
        }
    }

    override fun query() {
        EndlessRecyclerScrollListener.ResetState().publish()
        queryJob = viewModelScope.launch {
            val searched = queryInternal(query, 0)
            val diff = withContext(Dispatchers.Default) {
                itemsSearch.calculateDiff(searched)
            }
            searchLoading = false
            itemsSearch.update(searched, diff)
        }
    }

    fun loadMoreQuery() {
        if (queryJob?.isActive == true) return
        queryJob = viewModelScope.launch {
            val searched = queryInternal(query, itemsSearch.size)
            queryHandler.post { itemsSearch.addAll(searched) }
        }
    }

    // ---

    fun updateActiveState() = viewModelScope.launch {
        sectionInstalled.hasButton = withContext(Dispatchers.Default) {
            itemsInstalled.any { it.isModified }
        }
    }

    fun sectionPressed(item: SectionTitle) = when (item) {
        sectionInstalled -> reboot() // TODO add reboot picker, regular reboot is not always preferred
        sectionRemote -> {
            Config.repoOrder = when (Config.repoOrder) {
                Config.Value.ORDER_NAME -> Config.Value.ORDER_DATE
                Config.Value.ORDER_DATE -> Config.Value.ORDER_NAME
                else -> Config.Value.ORDER_NAME
            }
            sectionRemote.updateOrderIcon()
            queryHandler.post {
                itemsRemote.clear()
                loadRemote()
            }
            Unit
        }
        else -> Unit
    }

    fun downloadPressed(item: RepoItem) = withExternalRW {
        ModuleInstallDialog(item.item).publish()
    }

    fun installPressed() = withExternalRW {
        InstallExternalModuleEvent().publish()
    }

    fun infoPressed(item: RepoItem) = OpenChangelogEvent(item.item).publish()
    fun infoPressed(item: ModuleItem) {
        OpenChangelogEvent(item.repo ?: return).publish()
    }
}
