package app.omnivore.omnivore.ui.library

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnivore.omnivore.*
import app.omnivore.omnivore.dataService.*
import app.omnivore.omnivore.networking.*
import app.omnivore.omnivore.persistence.entities.SavedItem
import app.omnivore.omnivore.persistence.entities.SavedItemCardDataWithLabels
import app.omnivore.omnivore.ui.reader.WebFont
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
  private val networker: Networker,
  private val dataService: DataService,
  private val datastoreRepo: DatastoreRepository
): ViewModel() {

  init {
    // Load the last saved library filter
    runBlocking {
      datastoreRepo.getString(DatastoreKeys.lastUsedSavedItemFilter)?.let { str ->
        try {
          val filter = SavedItemFilter.values().first { it.rawValue == str }
          appliedFilterLiveData.postValue(filter)
        } catch (e: Exception) {
          Log.d("error", "invalid filter value store in datastore repo: $e")
        }
      }
    }
  }

  private var cursor: String? = null

  // These are used to make sure we handle search result
  // responses in the right order
  private var searchIdx = 0
  private var receivedIdx = 0

  // Live Data
  val searchTextLiveData = MutableLiveData("")
  val searchItemsLiveData = MutableLiveData<List<SavedItemCardDataWithLabels>>(listOf())
  val itemsLiveData = dataService.db.savedItemDao().getLibraryLiveDataWithLabels()
  val appliedFilterLiveData = MutableLiveData<SavedItemFilter>(SavedItemFilter.INBOX)

  var isRefreshing by mutableStateOf(false)

  fun updateSearchText(text: String) {
    searchTextLiveData.value = text

    if (text == "") {
      searchItemsLiveData.value = listOf()
    } else {
      load(clearPreviousSearch = true)
    }
  }

  fun refresh() {
    isRefreshing = true
    load(true)
  }

  fun getLastSyncTime(): Instant? = runBlocking {
    datastoreRepo.getString(DatastoreKeys.libraryLastSyncTimestamp)?.let {
      try {
        return@let Instant.parse(it)
      } catch (e: Exception) {
        return@let null
      }
    }
  }

  fun load(clearPreviousSearch: Boolean = false) {
    viewModelScope.launch {
      if (searchTextLiveData.value != "") {
        performSearch(clearPreviousSearch)
      } else {
        syncItems()
      }
    }
  }

  private suspend fun syncItems() {
    val syncStart = Instant.now()
    val lastSyncDate = getLastSyncTime() ?: Instant.MIN

    withContext(Dispatchers.IO) {
      performItemSync(cursor = null, since = lastSyncDate.toString(), count = 0, startTime = syncStart.toString())
      CoroutineScope(Dispatchers.Main).launch {
        isRefreshing = false
      }
    }
  }

  private suspend fun performItemSync(cursor: String?, since: String, count: Int, startTime: String, isInitialBatch: Boolean = true) {
    dataService.syncOfflineItemsWithServerIfNeeded()
    val result = dataService.sync(since = since, cursor = cursor, limit = 20)

    // Fetch content for the initial batch only
    if (isInitialBatch) {
      for (slug in result.savedItemSlugs) {
        dataService.syncSavedItemContent(slug)
      }
    }

    val totalCount = count + result.count

    Log.d("sync", "fetched ${result.count} items")

    if (!result.hasError && result.hasMoreItems && result.cursor != null) {
      performItemSync(
        cursor = result.cursor,
        since = since,
        count = totalCount,
        startTime = startTime,
        isInitialBatch = false
      )
    } else {
      datastoreRepo.putString(DatastoreKeys.libraryLastSyncTimestamp, startTime)
    }
  }

  private suspend fun performSearch(clearPreviousSearch: Boolean) {
    if (clearPreviousSearch) {
      cursor = null
    }

    val thisSearchIdx = searchIdx
    searchIdx += 1

    // Execute the search
    val searchResult = networker.typeaheadSearch(searchTextLiveData.value ?: "")

    // Search results aren't guaranteed to return in order so this
    // will discard old results that are returned while a user is typing.
    // For example if a user types 'Canucks', often the search results
    // for 'C' are returned after 'Canucks' because it takes the backend
    // much longer to compute.
    if (thisSearchIdx in 1..receivedIdx) {
      return
    }

    val cardsDataWithLabels = searchResult.cardsData.map {
      SavedItemCardDataWithLabels(cardData = it, labels = listOf())
    }

    searchItemsLiveData.postValue(cardsDataWithLabels)

    CoroutineScope(Dispatchers.Main).launch {
      isRefreshing = false
    }
  }

  fun handleSavedItemAction(itemID: String, action: SavedItemAction) {
    when (action) {
      SavedItemAction.Delete -> {
        viewModelScope.launch {
          dataService.deleteSavedItem(itemID)
        }
      }
      SavedItemAction.Archive -> {
        viewModelScope.launch {
          dataService.archiveSavedItem(itemID)
        }
      }
      SavedItemAction.Unarchive -> {
        viewModelScope.launch {
          dataService.unarchiveSavedItem(itemID)
        }
      }
    }
  }
}

enum class SavedItemAction {
  Delete,
  Archive,
  Unarchive
}
