import { api } from '../../app/api'

export const reviewsApi = api.injectEndpoints({
  endpoints: (build) => ({
    reviewSummary: build.query({
      query: (slug) => `/books/${encodeURIComponent(slug)}/reviews/summary`,
      providesTags: (_r, _e, slug) => [{ type: 'Review', id: `summary:${slug}` }, 'Review'],
    }),
    reviews: build.query({
      query: ({ slug, sort = 'HELPFUL', stars, page = 0, size = 10 }) => ({
        url: `/books/${encodeURIComponent(slug)}/reviews`,
        params: Object.fromEntries(Object.entries({ sort, stars, page, size }).filter(([, v]) => v !== undefined && v !== null && v !== '')),
      }),
      providesTags: ['Review'],
    }),
    createReview: build.mutation({
      query: ({ slug, ...body }) => ({ url: `/books/${encodeURIComponent(slug)}/reviews`, method: 'POST', body }),
      invalidatesTags: ['Review', 'Book', 'Overview'],
    }),
    updateReview: build.mutation({
      query: ({ id, ...body }) => ({ url: `/reviews/${id}`, method: 'PUT', body }),
      invalidatesTags: ['Review', 'Book'],
    }),
    deleteReview: build.mutation({
      query: (id) => ({ url: `/reviews/${id}`, method: 'DELETE' }),
      invalidatesTags: ['Review', 'Book', 'Overview'],
    }),
    voteHelpful: build.mutation({
      query: ({ id, add }) => ({ url: `/reviews/${id}/helpful`, method: add ? 'PUT' : 'DELETE' }),
      invalidatesTags: ['Review'],
    }),
    reportReview: build.mutation({
      query: ({ id, reason }) => ({ url: `/reviews/${id}/report`, method: 'POST', body: { reason } }),
      invalidatesTags: ['Review'],
    }),
    myReviews: build.query({
      query: () => '/account/reviews',
      providesTags: ['Review'],
    }),

    // moderation
    moderationQueue: build.query({
      query: ({ filter = 'ATTENTION', page = 0 } = {}) => ({ url: '/admin/reviews', params: { filter, page } }),
      providesTags: ['Review'],
    }),
    moderate: build.mutation({
      query: ({ id, action }) => ({ url: `/admin/reviews/${id}/${action}`, method: 'POST' }),
      invalidatesTags: ['Review', 'Book'],
    }),
  }),
})

export const {
  useReviewSummaryQuery,
  useReviewsQuery,
  useCreateReviewMutation,
  useUpdateReviewMutation,
  useDeleteReviewMutation,
  useVoteHelpfulMutation,
  useReportReviewMutation,
  useMyReviewsQuery,
  useModerationQueueQuery,
  useModerateMutation,
} = reviewsApi

export const REVIEW_SORTS = [
  { value: 'HELPFUL', label: 'Most helpful' },
  { value: 'NEWEST', label: 'Newest' },
  { value: 'HIGHEST', label: 'Highest rated' },
  { value: 'LOWEST', label: 'Lowest rated' },
]

/** Mirrors ReviewService: rating 1–5, title ≤ 100, comment 10–2000 characters once HTML is ignored. */
export function validateReview({ rating, title = '', comment = '' }) {
  const errors = {}
  if (!(rating >= 1 && rating <= 5)) errors.rating = 'Choose 1 to 5 stars'
  if (title.trim().length > 100) errors.title = 'Keep the title under 100 characters'
  const text = comment.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim()
  if (text.length < 10) errors.comment = 'Write at least 10 characters'
  else if (comment.length > 2000) errors.comment = 'Keep it under 2000 characters'
  return errors
}

/** Width of each distribution bar, as a percentage of all ratings. */
export function distributionPercents(distribution = {}) {
  const total = Object.values(distribution).reduce((a, b) => a + b, 0)
  return [5, 4, 3, 2, 1].map((stars) => ({
    stars,
    count: distribution[stars] ?? 0,
    percent: total ? Math.round(((distribution[stars] ?? 0) / total) * 100) : 0,
  }))
}
