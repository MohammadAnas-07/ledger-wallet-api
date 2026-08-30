import type { ReactNode } from 'react'

import './notice.css'

/**
 * Three tones, and the difference between them is not decoration.
 *
 * `error`   — something is wrong and the user must change something.
 * `success` — something worked. Uses --credit, the only other job that colour
 *             has (design.md §2).
 * `retry`   — nothing was written and the same action may simply be repeated.
 *             Neutral ink on parchment, deliberately not red: a 409 under
 *             contention is the backend working as designed, and dressing it
 *             as a failure teaches the user to distrust a screen that is fine.
 */
export type NoticeTone = 'error' | 'success' | 'retry'

interface NoticeProps {
  tone: NoticeTone
  children: ReactNode
}

export function Notice({ tone, children }: NoticeProps) {
  return (
    <p
      className={`notice notice--${tone} body`}
      // Errors interrupt; a success or a retry prompt is announced when the
      // reader gets to it.
      role={tone === 'error' ? 'alert' : 'status'}
    >
      {children}
    </p>
  )
}
