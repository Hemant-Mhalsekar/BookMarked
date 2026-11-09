import { api } from '../../app/api'

// Any admin change can affect what customers see, so it also refreshes public 'Book' data.
export const adminCatalogApi = api.injectEndpoints({
  endpoints: (build) => ({
    adminBooks: build.query({
      query: (params) => ({ url: '/admin/books', params }),
      providesTags: ['AdminBook'],
    }),
    adminBook: build.query({
      query: (id) => `/admin/books/${id}`,
      providesTags: (_r, _e, id) => [{ type: 'AdminBook', id }],
    }),
    createBook: build.mutation({
      query: (body) => ({ url: '/admin/books', method: 'POST', body }),
      invalidatesTags: ['AdminBook', 'Book'],
    }),
    updateBook: build.mutation({
      query: ({ id, ...body }) => ({ url: `/admin/books/${id}`, method: 'PUT', body }),
      invalidatesTags: ['AdminBook', 'Book'],
    }),
    setBookActive: build.mutation({
      query: ({ id, active }) => ({ url: `/admin/books/${id}/active`, method: 'PATCH', body: { active } }),
      invalidatesTags: ['AdminBook', 'Book'],
    }),
    setBookFeatured: build.mutation({
      query: ({ id, featured }) => ({ url: `/admin/books/${id}/featured`, method: 'PATCH', body: { featured } }),
      invalidatesTags: ['AdminBook', 'Book'],
    }),
    adminAuthors: build.query({
      query: (q) => ({ url: '/admin/authors', params: { q } }),
    }),
    adminCategories: build.query({
      query: () => '/admin/categories',
      providesTags: ['Category'],
    }),
    createCategory: build.mutation({
      query: (body) => ({ url: '/admin/categories', method: 'POST', body }),
      invalidatesTags: ['Category'],
    }),
    updateCategory: build.mutation({
      query: ({ id, ...body }) => ({ url: `/admin/categories/${id}`, method: 'PUT', body }),
      invalidatesTags: ['Category'],
    }),
    setCategoryActive: build.mutation({
      query: ({ id, active }) => ({ url: `/admin/categories/${id}/active`, method: 'PATCH', body: { active } }),
      invalidatesTags: ['Category', 'Book'],
    }),
  }),
})

export const {
  useAdminBooksQuery,
  useAdminBookQuery,
  useCreateBookMutation,
  useUpdateBookMutation,
  useSetBookActiveMutation,
  useSetBookFeaturedMutation,
  useAdminAuthorsQuery,
  useAdminCategoriesQuery,
  useCreateCategoryMutation,
  useUpdateCategoryMutation,
  useSetCategoryActiveMutation,
} = adminCatalogApi
