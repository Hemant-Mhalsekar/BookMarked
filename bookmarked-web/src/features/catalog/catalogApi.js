import { api } from '../../app/api'

export const catalogApi = api.injectEndpoints({
  endpoints: (build) => ({
    searchBooks: build.query({
      // params: plain object of query-string values (see utils/searchParams.js)
      query: (params) => ({ url: '/books', params }),
      providesTags: ['Book'],
    }),
    suggestBooks: build.query({
      query: (q) => ({ url: '/books/suggest', params: { q } }),
    }),
    book: build.query({
      query: (slug) => `/books/${encodeURIComponent(slug)}`,
      providesTags: (_r, _e, slug) => [{ type: 'Book', id: slug }],
    }),
    categories: build.query({
      query: () => '/categories',
      providesTags: ['Category'],
    }),
  }),
})

export const { useSearchBooksQuery, useSuggestBooksQuery, useBookQuery, useCategoriesQuery } = catalogApi
