import { api } from '../../app/api'

// Toggling a bookmark updates the cached id list immediately (optimistic), and
// rolls back if the server says no.
const optimisticIds = (change) => async (bookId, { dispatch, queryFulfilled }) => {
  const patch = dispatch(
    bookmarksApi.util.updateQueryData('bookmarkIds', undefined, (ids) => change(ids, bookId)),
  )
  try {
    await queryFulfilled
  } catch {
    patch.undo()
  }
}

export const bookmarksApi = api.injectEndpoints({
  endpoints: (build) => ({
    bookmarks: build.query({
      query: () => '/bookmarks',
      providesTags: ['Bookmark'],
    }),
    bookmarkIds: build.query({
      query: () => '/bookmarks/ids',
      providesTags: ['Bookmark'],
    }),
    addBookmark: build.mutation({
      query: (bookId) => ({ url: `/bookmarks/${bookId}`, method: 'PUT' }),
      onQueryStarted: optimisticIds((ids, id) => {
        if (!ids.includes(id)) ids.push(id)
      }),
      invalidatesTags: ['Bookmark'],
    }),
    removeBookmark: build.mutation({
      query: (bookId) => ({ url: `/bookmarks/${bookId}`, method: 'DELETE' }),
      onQueryStarted: optimisticIds((ids, id) => {
        const i = ids.indexOf(id)
        if (i >= 0) ids.splice(i, 1)
      }),
      invalidatesTags: ['Bookmark'],
    }),
  }),
})

export const { useBookmarksQuery, useBookmarkIdsQuery, useAddBookmarkMutation, useRemoveBookmarkMutation } = bookmarksApi
